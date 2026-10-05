package top.wkbin.taixu.runtime.privilege

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import top.wkbin.taixu.core.common.logging.AppLogger
import top.wkbin.taixu.core.common.result.AppError
import top.wkbin.taixu.core.common.result.AppResult
import top.wkbin.taixu.core.common.result.ErrorCode
import top.wkbin.taixu.core.datastore.RuntimePreferences
import top.wkbin.taixu.core.model.ExecutionMode
import top.wkbin.taixu.core.model.PrivilegeCheckResult
import java.util.concurrent.TimeUnit
import java.util.UUID

enum class PrivilegeAvailability { CHECKING, ACTIVE, DEGRADED, UNAVAILABLE }

/** App-wide authoritative privilege state; preferred mode and actual effective mode explicitly separated. */
data class PrivilegeState(
    val preferredMode: ExecutionMode = ExecutionMode.PROOT,
    val effectiveMode: ExecutionMode = ExecutionMode.PROOT,
    val availability: PrivilegeAvailability = PrivilegeAvailability.CHECKING,
    val reason: String = "Validating runtime permissions",
    val shizukuAvailable: Boolean = false,
    val rootAvailable: Boolean = false,
) {
    val active: Boolean get() = availability == PrivilegeAvailability.ACTIVE
    val degraded: Boolean get() = availability == PrivilegeAvailability.DEGRADED
}

class PrivilegeManager(
    private val context: Context,
    private val settingsDataStore: RuntimePreferences,
    private val logger: AppLogger,
    private val shizukuHostServiceClient: ShizukuHostServiceClient,
) {
    private val _state = MutableStateFlow(PrivilegeState())
    val state: StateFlow<PrivilegeState> = _state.asStateFlow()
    private val rootRunner = HostProcessRunner { command -> ProcessBuilder("su", "-c", command).start() }

    /**
     * Restore and validate last chosen mode on app process startup.
     *
     * Only performs actual permission detection here; does not proactively show
     * Shizuku auth dialog. If auth expired, service not running, or Root unavailable,
     * immediately persist effective mode as PRoot to prevent UI and Harness from
     * treating an already-invalid high-privilege mode as available capability.
     */
    suspend fun reconcilePersistedMode(): ExecutionMode = withContext(Dispatchers.IO) {
        val preferred = preferredMode()
        // Before cold-start validation completes, lock execution plane to lowest privilege to prevent Harness from racing ahead with stale high-privilege value.
        settingsDataStore.setEffectiveExecutionMode(ExecutionMode.PROOT)
        _state.value = PrivilegeState(
            preferredMode = preferred,
            effectiveMode = ExecutionMode.PROOT,
            availability = PrivilegeAvailability.CHECKING,
            reason = "Restoring ${preferred.shortLabel} privilege",
        )
        if (preferred == ExecutionMode.PROOT) {
            settingsDataStore.setExecutionModes(ExecutionMode.PROOT, ExecutionMode.PROOT)
            _state.value = PrivilegeState(
                preferredMode = ExecutionMode.PROOT,
                effectiveMode = ExecutionMode.PROOT,
                availability = PrivilegeAvailability.ACTIVE,
                reason = "PRoot user-mode requires no extra auth",
            )
            return@withContext ExecutionMode.PROOT
        }

        // ShizukuProvider and Application.onCreate have a brief Binder delivery window; wait for sticky
        // callback before judging to avoid "service actually available but Binder not yet delivered at startup" causing false downgrade.
        if (preferred == ExecutionMode.SHIZUKU) awaitShizukuBinder()

        val check = passiveCheck(preferred)
        if (check is PrivilegeCheckResult.Authorized) {
            settingsDataStore.setExecutionModes(preferred, preferred)
            applyPrivilegeOptimizations(preferred)
            refreshState(preferred, preferred, PrivilegeAvailability.ACTIVE, check.details)
            logger.i("Startup privilege check passed, restored run mode: ${preferred.name}")
            preferred
        } else {
            val reason = when (check) {
                is PrivilegeCheckResult.Unauthorized -> check.reason
                is PrivilegeCheckResult.ServiceNotRunning -> check.guidance
                is PrivilegeCheckResult.Authorized -> check.details
            }
            // Only downgrade effective mode, keep user preference; next cold start retries automatically.
            settingsDataStore.setExecutionModes(preferred, ExecutionMode.PROOT)
            refreshState(preferred, ExecutionMode.PROOT, PrivilegeAvailability.DEGRADED, reason)
            logger.w("Startup privilege check failed, ${preferred.name} temporarily degraded to PROOT: $reason")
            ExecutionMode.PROOT
        }
    }

    private fun passiveCheck(mode: ExecutionMode): PrivilegeCheckResult = when (mode) {
        ExecutionMode.PROOT -> PrivilegeCheckResult.Authorized(mode, "PRoot user-mode requires no extra auth")
        ExecutionMode.ROOT -> checkRootPrivilege()
        ExecutionMode.SHIZUKU -> when {
            !runCatching { Shizuku.pingBinder() }.getOrDefault(false) ->
                PrivilegeCheckResult.ServiceNotRunning(mode, "Shizuku service not running")
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED ->
                PrivilegeCheckResult.Unauthorized(mode, "Shizuku permission not granted or revoked")
            else -> PrivilegeCheckResult.Authorized(mode, "Shizuku permission restored")
        }
    }

    private suspend fun refreshState(
        preferred: ExecutionMode,
        effective: ExecutionMode,
        availability: PrivilegeAvailability,
        reason: String,
    ) {
        val shizuku = runCatching {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        _state.value = PrivilegeState(
            preferredMode = preferred,
            effectiveMode = effective,
            availability = availability,
            reason = reason,
            shizukuAvailable = shizuku,
            rootAvailable = effective == ExecutionMode.ROOT && availability == PrivilegeAvailability.ACTIVE,
        )
    }

    private suspend fun awaitShizukuBinder(): Boolean {
        if (runCatching { Shizuku.pingBinder() }.getOrDefault(false)) return true
        val received = CompletableDeferred<Unit>()
        val listener = Shizuku.OnBinderReceivedListener { received.complete(Unit) }
        Shizuku.addBinderReceivedListenerSticky(listener)
        return try {
            withTimeoutOrNull(SHIZUKU_STARTUP_WAIT_MS) {
                received.await()
                true
            } ?: false
        } finally {
            Shizuku.removeBinderReceivedListener(listener)
        }
    }

    /**
     * Probe and attempt to acquire privilege authorization for target run mode.
     */
    suspend fun checkAndAuthorize(mode: ExecutionMode): PrivilegeCheckResult = withContext(Dispatchers.IO) {
        when (mode) {
            ExecutionMode.PROOT -> {
                PrivilegeCheckResult.Authorized(
                    mode = ExecutionMode.PROOT,
                    details = "PRoot user-mode sandbox ready; no extra system privileges needed.",
                )
            }

            ExecutionMode.ROOT -> {
                checkRootPrivilege()
            }

            ExecutionMode.SHIZUKU -> {
                checkShizukuPrivilege()
            }
        }
    }

    /**
     * Request and switch to specified run mode. On success, persist and unlock privileged capabilities.
     */
    suspend fun switchMode(mode: ExecutionMode): AppResult<PrivilegeCheckResult.Authorized> = withContext(Dispatchers.IO) {
        _state.value = _state.value.copy(
            preferredMode = mode,
            availability = PrivilegeAvailability.CHECKING,
            reason = "Requesting ${mode.shortLabel} privilege",
        )
        val check = checkAndAuthorize(mode)
        when (check) {
            is PrivilegeCheckResult.Authorized -> {
                settingsDataStore.setExecutionModes(mode, mode)
                applyPrivilegeOptimizations(mode)
                refreshState(mode, mode, PrivilegeAvailability.ACTIVE, check.details)
                logger.i("Successfully switched to run mode: ${mode.name} (${check.details})")
                AppResult.Success(check)
            }

            is PrivilegeCheckResult.Unauthorized -> {
                val previousPreferred = settingsDataStore.preferredExecutionMode.first()
                val previousEffective = settingsDataStore.effectiveExecutionMode.first()
                refreshState(previousPreferred, previousEffective, PrivilegeAvailability.ACTIVE, "Switch failed: ${check.reason}")
                logger.w("Switch to ${mode.name} failed: ${check.reason}")
                AppResult.Failure(
                    AppError(
                        code = ErrorCode.SECURITY,
                        message = check.reason,
                    ),
                )
            }

            is PrivilegeCheckResult.ServiceNotRunning -> {
                val previousPreferred = settingsDataStore.preferredExecutionMode.first()
                val previousEffective = settingsDataStore.effectiveExecutionMode.first()
                refreshState(previousPreferred, previousEffective, PrivilegeAvailability.ACTIVE, "Switch failed: ${check.guidance}")
                logger.w("Switch to ${mode.name} failed: ${check.guidance}")
                AppResult.Failure(
                    AppError(
                        code = ErrorCode.UNKNOWN,
                        message = check.guidance,
                    ),
                )
            }
        }
    }

    /**
     * Probe Root privilege (test UID 0 via su execution stream).
     */
    private fun checkRootPrivilege(): PrivilegeCheckResult {
        return try {
            val process = ProcessBuilder("su", "-c", "id").start()
            val completed = process.waitFor(5, TimeUnit.SECONDS)
            if (!completed) {
                process.destroyForcibly()
                return PrivilegeCheckResult.Unauthorized(
                    ExecutionMode.ROOT,
                    "Root auth request timed out; allow grant in Magisk / KernelSU / APatch dialog.",
                )
            }

            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.exitValue()

            if (exitCode == 0 && output.contains("uid=0")) {
                PrivilegeCheckResult.Authorized(
                    ExecutionMode.ROOT,
                    "Root privilege acquired (UID 0: root); native Linux and kernel hardware acceleration unlocked!",
                )
            } else {
                PrivilegeCheckResult.Unauthorized(
                    ExecutionMode.ROOT,
                    "Root auth failed (exit $exitCode): $output",
                )
            }
        } catch (e: Exception) {
            logger.e("Root privilege check exception", e)
            PrivilegeCheckResult.ServiceNotRunning(
                ExecutionMode.ROOT,
                "No usable su executable detected on device. If rooted, check if TaiXu has grant in auth manager.",
            )
        }
    }

    /**
     * Use official Shizuku-API for Binder service probe and permission check.
     */
    private suspend fun checkShizukuPrivilege(): PrivilegeCheckResult {
        return try {
            // 1. Probe if Shizuku Binder service is in running active state
            val isBinderAlive = Shizuku.pingBinder()
            if (!isBinderAlive) {
                return PrivilegeCheckResult.ServiceNotRunning(
                    ExecutionMode.SHIZUKU,
                    "Shizuku service not running. Open Shizuku app and ensure status is Running (wireless debug or Root start).",
                )
            }

            // 2. Check if app has obtained Shizuku permission
            val isGranted = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED

            if (isGranted) {
                PrivilegeCheckResult.Authorized(
                    ExecutionMode.SHIZUKU,
                    "Shizuku (v${Shizuku.getVersion()}) authorized; ADB-level privilege and Android 12+ phantom process limit exemption unlocked!",
                )
            } else {
                if (Shizuku.shouldShowRequestPermissionRationale()) {
                    PrivilegeCheckResult.Unauthorized(
                        ExecutionMode.SHIZUKU,
                        "Allow TaiXu access to ADB privileged service in Shizuku dialog.",
                    )
                } else {
                    val granted = requestShizukuPermission()
                    if (granted) {
                        PrivilegeCheckResult.Authorized(
                            ExecutionMode.SHIZUKU,
                            "Shizuku (v${Shizuku.getVersion()}) authorized; auto-switched to ADB privilege mode.",
                        )
                    } else {
                        PrivilegeCheckResult.Unauthorized(
                            ExecutionMode.SHIZUKU,
                            "Shizuku auth denied or timed out; check TaiXu auth status in Shizuku app.",
                        )
                    }
                }
            }
        } catch (e: Exception) {
            logger.e("Shizuku check exception", e)
            PrivilegeCheckResult.ServiceNotRunning(
                ExecutionMode.SHIZUKU,
                "Cannot connect to Shizuku service (${e.message}). Check if Shizuku is running.",
            )
        }
    }

    private suspend fun requestShizukuPermission(): Boolean {
        val result = CompletableDeferred<Boolean>()
        val listener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == SHIZUKU_PERMISSION_REQUEST_CODE) {
                result.complete(grantResult == PackageManager.PERMISSION_GRANTED)
            }
        }
        Shizuku.addRequestPermissionResultListener(listener)
        return try {
            Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
            withTimeoutOrNull(SHIZUKU_PERMISSION_WAIT_MS) { result.await() } ?: false
        } finally {
            Shizuku.removeRequestPermissionResultListener(listener)
        }
    }

    /**
     * Apply system-level privilege optimizations after auth success (e.g., lift Android 12+ phantom process 32 limit).
     */
    private suspend fun applyPrivilegeOptimizations(mode: ExecutionMode) {
        when (mode) {
            ExecutionMode.ROOT -> {
                runCatching { executeViaRoot(PHANTOM_PROCESS_REMOVE_COMMAND, "privilege-opt-root") }
                    .onSuccess { result ->
                        if (!result.success) logger.w("Remove phantom process limit via Root failed: ${result.stderr}")
                    }
                    .onFailure {
                        logger.w("Remove phantom process limit via Root failed", it)
                    }
                selfGrantWriteSettings(mode)
            }
            ExecutionMode.SHIZUKU -> {
                runCatching { executeViaShizuku(PHANTOM_PROCESS_REMOVE_COMMAND, "privilege-opt-shizuku") }
                    .onSuccess { result ->
                        if (!result.success) logger.w("Remove phantom process limit via Shizuku failed: ${result.stderr}")
                    }
                    .onFailure {
                        logger.w("Remove phantom process limit via Shizuku failed", it)
                    }
                selfGrantWriteSettings(mode)
            }
            ExecutionMode.PROOT -> Unit
        }
    }

    /**
     * Self-grant WRITE_SETTINGS via appops at shell/root level, avoiding manual system settings grant.
     * After grant, writeSystemSetting() can use app's own ContentResolver to write system settings,
     * bypassing silent rejection of `settings put` shell commands on some vendor ROMs (e.g., vivo).
     */
    private suspend fun selfGrantWriteSettings(mode: ExecutionMode) {
        if (Settings.System.canWrite(context)) {
            logger.i("WRITE_SETTINGS already granted, skipping self-grant")
            return
        }
        val pkg = context.packageName
        // appops op code 23 = android:write_settings; using name for better compat
        val command = "appops set $pkg android:write_settings allow"
        val result = when (mode) {
            ExecutionMode.SHIZUKU -> runCatching { executeViaShizuku(command, "self-grant-write-settings") }
            ExecutionMode.ROOT -> runCatching { executeViaRoot(command, "self-grant-write-settings") }
            else -> return
        }
        result.onSuccess { r ->
            if (r.success && Settings.System.canWrite(context)) {
                logger.i("Self-grant WRITE_SETTINGS success (via ${mode.shortLabel})")
            } else {
                logger.w("Self-grant WRITE_SETTINGS failed: exit=${r.exitCode} stderr=${r.stderr} canWrite=${Settings.System.canWrite(context)}")
            }
        }.onFailure {
            logger.w("Self-grant WRITE_SETTINGS exception", it)
        }
    }

    /**
     * Read actual system values of Android phantom process monitoring, not relying on in-app "executed" flags.
     * Android 12 introduced this limit; different versions/vendors may use either count cap or monitoring switch.
     */
    suspend fun checkPhantomProcessLimit(): PhantomProcessLimitStatus = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return@withContext PhantomProcessLimitStatus(
                state = PhantomProcessLimitState.UNSUPPORTED,
                details = "Android 12 and below have no 32 phantom process limit.",
            )
        }

        // First try system APIs readable by app process. So even if user runs ADB from PC or uses PRoot,
        // page can still self-detect removed state as long as ROM allows reading the config.
        val directMonitoring = runCatching {
            Settings.Global.getString(context.contentResolver, "settings_enable_monitor_phantom_procs")
        }.getOrNull()
        val directStatus = if (directMonitoring != null) {
            parsePhantomProcessLimit("max=\nmonitor=$directMonitoring\n")
        } else {
            null
        }
        if (directStatus?.state == PhantomProcessLimitState.REMOVED) {
            return@withContext directStatus
        }

        val result = executeShellCommand(PHANTOM_PROCESS_QUERY_COMMAND)
        if (!result.success) {
            if (directStatus != null) return@withContext directStatus
            return@withContext PhantomProcessLimitStatus(
                state = PhantomProcessLimitState.UNAVAILABLE,
                details = result.stderr.ifBlank { "Need to enable and authorize Shizuku or Root first to read system state." },
            )
        }

        parsePhantomProcessLimit(result.stdout)
    }

    /** Remove Android 12+ phantom process limit using current Shizuku/Root host privilege. */
    suspend fun removePhantomProcessLimit(): ShellExecResult = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return@withContext ShellExecResult(true, 0, "", "")
        }
        executeShellCommand(PHANTOM_PROCESS_REMOVE_COMMAND)
    }

    // ============================ HostBridge / External Interface ============================

    /** Reactive effective mode; becomes PRoot when privilege expires. */
    val activeMode: Flow<ExecutionMode> = settingsDataStore.effectiveExecutionMode
    val preferredModeFlow: Flow<ExecutionMode> = settingsDataStore.preferredExecutionMode

    /** Read current effective mode; fallback to PRoot on read failure. */
    private suspend fun currentMode(): ExecutionMode = runCatching { settingsDataStore.effectiveExecutionMode.first() }
        .getOrDefault(ExecutionMode.PROOT)

    private suspend fun preferredMode(): ExecutionMode = runCatching { settingsDataStore.preferredExecutionMode.first() }
        .getOrDefault(ExecutionMode.PROOT)

    /**
     * Execute shell command on host side with current privilege mode.
     * - SHIZUKU mode: via Shizuku Binder at ADB level (shell uid)
     * - ROOT mode: via su at root uid
     * - PROOT mode: unsupported, returns error
     *
     * This is the key capability breaking the "circular permission dependency":
     * Sandbox cannot directly execute Android commands needing shell/root (settings put, pm grant, appops set),
     * but via HostBridge → PrivilegeManager.executeShellCommand it bypasses sandbox limits,
     * executing on host with privileged identity.
     */
    suspend fun executeShellCommand(
        command: String,
        operationId: String = UUID.randomUUID().toString(),
    ): ShellExecResult = withContext(Dispatchers.IO) {
        val snapshot = state.value
        if (snapshot.availability != PrivilegeAvailability.ACTIVE || snapshot.effectiveMode == ExecutionMode.PROOT) {
            return@withContext ShellExecResult(
                success = false,
                exitCode = -1,
                stdout = "",
                stderr = if (snapshot.availability == PrivilegeAvailability.CHECKING) {
                    "Host privilege still in startup validation, retry later."
                } else {
                    "Current effective mode is PRoot; preferred ${snapshot.preferredMode.shortLabel} temporarily unavailable: ${snapshot.reason}"
                },
            )
        }
        val mode = snapshot.effectiveMode

        when (mode) {
            ExecutionMode.SHIZUKU -> executeViaShizuku(command, operationId)
            ExecutionMode.ROOT -> executeViaRoot(command, operationId)
            ExecutionMode.PROOT -> ShellExecResult(
                success = false,
                exitCode = -1,
                stdout = "",
                stderr = "Current run mode (PRoot) does not support host shell execution. Switch to Shizuku or Root in settings.",
            )
        }
    }

    /** Harness cancels task; synchronously terminates corresponding Shizuku/Root host subprocess. */
    fun cancelShellCommand(operationId: String): Boolean =
        rootRunner.cancel(operationId) or shizukuHostServiceClient.cancel(operationId)

    /**
     * Directly write system namespace setting via Android ContentResolver, bypassing shell commands.
     * Only applies to system namespace (requires WRITE_SETTINGS); secure/global still need shell.
     * Returns true on success, false on no permission or write error (caller should fall back to shell).
     */
    fun writeSystemSetting(key: String, value: String): Boolean = try {
        if (!Settings.System.canWrite(context)) {
            logger.w("writeSystemSetting: app lacks WRITE_SETTINGS permission, key=$key")
            false
        } else {
            val resolver = context.contentResolver
            // Prefer int write (brightness, timeout, etc.), fallback to string
            val asInt = value.toIntOrNull()
            if (asInt != null) {
                Settings.System.putInt(resolver, key, asInt)
            } else {
                value.toFloatOrNull()?.let { Settings.System.putFloat(resolver, key, it) }
                    ?: Settings.System.putString(resolver, key, value)
            }
            logger.i("writeSystemSetting: wrote $key=$value via ContentResolver")
            true
        }
    } catch (e: Exception) {
        logger.e("writeSystemSetting failed: key=$key value=$value", e)
        false
    }

    /**
     * Get current privilege state snapshot: active mode + whether its privilege is actually effective + auth channel availability.
     * This is the authoritative state source shared by Home UI and sandbox Agent (via HostBridge /api/health).
     * PRoot mode is always considered effective without probe; Shizuku/Root rely on live probe results.
     */
    suspend fun getPrivilegeInfo(): PrivilegeInfo = withContext(Dispatchers.IO) {
        if (state.value.availability == PrivilegeAvailability.CHECKING) {
            return@withContext PrivilegeInfo(
                mode = ExecutionMode.PROOT,
                modeActive = true,
                shizukuAvailable = false,
                rootAvailable = false,
            )
        }
        val mode = currentMode()

        val shizukuAvailable = runCatching {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)

        // PRoot needs no auth, skip costly su probe
        val rootAvailable = if (mode == ExecutionMode.ROOT) {
            runCatching {
                val process = ProcessBuilder("su", "-c", "echo ok").start()
                val completed = process.waitFor(3, TimeUnit.SECONDS)
                if (!completed) {
                    process.destroyForcibly()
                    false
                } else {
                    process.exitValue() == 0
                }
            }.getOrDefault(false)
        } else {
            false
        }

        val modeActive = when (mode) {
            ExecutionMode.PROOT -> true
            ExecutionMode.SHIZUKU -> shizukuAvailable
            ExecutionMode.ROOT -> rootAvailable
        }

        val effectiveMode = if (mode != ExecutionMode.PROOT && !modeActive) {
            val preferred = preferredMode()
            settingsDataStore.setEffectiveExecutionMode(ExecutionMode.PROOT)
            refreshState(
                preferred = preferred,
                effective = ExecutionMode.PROOT,
                availability = PrivilegeAvailability.DEGRADED,
                reason = "${mode.shortLabel} privilege expired, safely degraded to PRoot",
            )
            ExecutionMode.PROOT
        } else {
            mode
        }

        PrivilegeInfo(
            mode = effectiveMode,
            modeActive = effectiveMode == ExecutionMode.PROOT || modeActive,
            shizukuAvailable = shizukuAvailable,
            rootAvailable = rootAvailable,
        )
    }

    /**
     * Execute command via Shizuku at ADB level (shell uid, UID 2000).
     */
    private suspend fun executeViaShizuku(command: String, operationId: String): ShellExecResult {
        if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
            return ShellExecResult(false, -1, "", "Shizuku service not running. Open Shizuku app and ensure service started.")
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            return ShellExecResult(false, -1, "", "Shizuku not authorized. Grant TaiXu access in Shizuku app.")
        }

        return try {
            shizukuHostServiceClient.execute(operationId, command)
        } catch (e: Exception) {
            logger.e("Shizuku UserService execution failed", e)
            ShellExecResult(false, -1, "", "Shizuku UserService execution failed: ${e.message}")
        }
    }

    /**
     * Execute command via su at root uid (UID 0).
     */
    private fun executeViaRoot(command: String, operationId: String): ShellExecResult =
        rootRunner.execute(operationId, command)

    companion object {
        private const val SHIZUKU_STARTUP_WAIT_MS = 3_000L
        private const val SHIZUKU_PERMISSION_WAIT_MS = 60_000L
        private const val SHIZUKU_PERMISSION_REQUEST_CODE = 1001
        /** Runnable directly in PC terminal, for devices not using Shizuku/Root. */
        const val PHANTOM_PROCESS_ADB_COMMAND =
            "adb shell device_config put activity_manager max_phantom_processes 2147483647\n" +
                "adb shell settings put global settings_enable_monitor_phantom_procs false"

        private const val PHANTOM_PROCESS_REMOVE_COMMAND =
            "/system/bin/device_config put activity_manager max_phantom_processes 2147483647; MAX_EXIT=\$?; " +
                "/system/bin/settings put global settings_enable_monitor_phantom_procs false; MONITOR_EXIT=\$?; " +
                "if [ \"\$MAX_EXIT\" -eq 0 ] || [ \"\$MONITOR_EXIT\" -eq 0 ]; then exit 0; else exit 1; fi"

        private const val PHANTOM_PROCESS_QUERY_COMMAND =
            "MAX=\$(/system/bin/device_config get activity_manager max_phantom_processes 2>/dev/null); " +
                "MONITOR=\$(/system/bin/settings get global settings_enable_monitor_phantom_procs 2>/dev/null); " +
                "printf 'max=%s\\nmonitor=%s\\n' \"\$MAX\" \"\$MONITOR\""
    }
}

enum class PhantomProcessLimitState {
    REMOVED,
    ACTIVE,
    UNAVAILABLE,
    UNSUPPORTED,
}

data class PhantomProcessLimitStatus(
    val state: PhantomProcessLimitState,
    val maxPhantomProcesses: Long? = null,
    val monitoringEnabled: Boolean? = null,
    val details: String,
)

internal fun parsePhantomProcessLimit(output: String): PhantomProcessLimitStatus {
    val values = output.lineSequence()
        .mapNotNull { line ->
            val separator = line.indexOf('=')
            if (separator <= 0) null else line.substring(0, separator).trim() to line.substring(separator + 1).trim()
        }
        .toMap()
    val max = values["max"]?.takeUnless { it.isBlank() || it.equals("null", true) }?.toLongOrNull()
    val monitoring = values["monitor"]
        ?.takeUnless { it.isBlank() || it.equals("null", true) }
        ?.let { raw ->
            when (raw.lowercase()) {
                "1", "true" -> true
                "0", "false" -> false
                else -> null
            }
        }
    val removed = max == Long.MAX_VALUE || (max != null && max >= Int.MAX_VALUE) || monitoring == false

    return if (removed) {
        PhantomProcessLimitStatus(
            state = PhantomProcessLimitState.REMOVED,
            maxPhantomProcesses = max,
            monitoringEnabled = monitoring,
            details = "Android phantom process limit removed.",
        )
    } else {
        PhantomProcessLimitStatus(
            state = PhantomProcessLimitState.ACTIVE,
            maxPhantomProcesses = max,
            monitoringEnabled = monitoring,
            details = if (max == null && monitoring == null) {
                "Still using system default limit (typically max 32 phantom processes)."
            } else {
                "System still limiting phantom processes."
            },
        )
    }
}

/** Shell command execution result. */
data class ShellExecResult(
    val success: Boolean,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
)

/** Privilege state snapshot: authoritative description shared by Home UI and HostBridge /api/health. */
data class PrivilegeInfo(
    /** Currently active (persisted) run mode. */
    val mode: ExecutionMode,
    /** Whether current active mode privilege is actually effective (PRoot always true; Shizuku/Root per live probe). */
    val modeActive: Boolean,
    val shizukuAvailable: Boolean,
    val rootAvailable: Boolean,
)
