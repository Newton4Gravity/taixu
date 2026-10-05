package top.wkbin.taixu.core.tools

import top.wkbin.taixu.core.database.InstallLogEntity
import top.wkbin.taixu.core.database.InstallTaskEntity
import top.wkbin.taixu.core.database.ToolEntity
import top.wkbin.taixu.core.model.ToolManifest
import top.wkbin.taixu.core.model.ToolState
import top.wkbin.taixu.core.security.SecretRedactor
import top.wkbin.taixu.runtime.LinuxRuntime
import top.wkbin.taixu.runtime.BackgroundTaskRegistry
import top.wkbin.taixu.runtime.service.LocalServiceSpec
import top.wkbin.taixu.runtime.tools.InstallEvent
import top.wkbin.taixu.runtime.shell.LinuxSession
import top.wkbin.taixu.runtime.shell.ManagedProcess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import android.net.Uri
import top.wkbin.taixu.core.common.result.AppResult

data class ToolInstallProgress(
    val toolId: String,
    val message: String,
    val progress: Float? = null,
    val terminal: Boolean = false,
)

sealed interface LocalPluginImportState {
    data object Idle : LocalPluginImportState
    /** The URI has been selected; only manifest metadata is being read. */
    data class Reading(val fileName: String) : LocalPluginImportState
    data class PendingConfirmation(
        val uri: Uri,
        val fileName: String,
        val manifest: ToolManifest,
        val archiveSizeBytes: Long?,
    ) : LocalPluginImportState
    data class Importing(
        val fileName: String,
        val progress: Float?,
        val bytesRead: Long,
        val totalBytes: Long?,
        val currentEntry: String?,
    ) : LocalPluginImportState
    data class Succeeded(val pluginName: String, val version: String) : LocalPluginImportState
    data class AlreadyImported(val pluginName: String, val version: String) : LocalPluginImportState
    data class Failed(val message: String) : LocalPluginImportState
}

private data class UninstallOutcome(
    val success: Boolean,
    val message: String,
)

class ToolManager(
    private val toolRepository: ToolRepository,
    private val installLogRepository: InstallLogRepository,
    private val installTaskRepository: InstallTaskRepository,
    private val installTransactionManager: InstallTransactionManager,
    private val dependencyManager: DependencyManager,
    private val linuxRuntime: LinuxRuntime,
    private val backgroundTaskRegistry: BackgroundTaskRegistry,
    private val providerManager: ProviderManager,
    private val toolCommandLinker: top.wkbin.taixu.runtime.tools.ToolCommandLinker,
    private val notificationNotifier: ToolNotificationNotifier,
    private val secretRedactor: SecretRedactor,
    private val toolSettingsRepository: top.wkbin.taixu.core.database.ToolSettingsRepository,
    private val settingsDataStore: top.wkbin.taixu.core.datastore.ToolPreferences,
    private val assetSynchronizer: top.wkbin.taixu.runtime.scripts.RuntimeAssetSynchronizer,
    private val flutterSdkDownloader: FlutterSdkDownloader,
    private val localPluginPayloadManager: LocalPluginPayloadManager,
    private val serviceController: ToolServiceController,
    installerAdapters: Set<@JvmSuppressWildcards ToolRuntimeAdapter>,
) {
    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val installMutex = Mutex()
    private val installJobs = mutableMapOf<String, Job>()
    private val _installProgress = MutableStateFlow<Map<String, ToolInstallProgress>>(emptyMap())
    val installProgress: StateFlow<Map<String, ToolInstallProgress>> = _installProgress.asStateFlow()
    private val _verifications = MutableStateFlow<Map<String, ToolVerification>>(emptyMap())
    val verifications: StateFlow<Map<String, ToolVerification>> = _verifications.asStateFlow()

    private val _bundleInstallState = MutableStateFlow<String?>(null)
    val bundleInstallState: StateFlow<String?> = _bundleInstallState.asStateFlow()
    private val _bundleInstallLog = MutableStateFlow<List<String>>(emptyList())
    val bundleInstallLog: StateFlow<List<String>> = _bundleInstallLog.asStateFlow()

    private val _isBatchInstalling = MutableStateFlow(false)
    val isBatchInstalling: StateFlow<Boolean> = _isBatchInstalling.asStateFlow()

    private val _localPluginImportState = MutableStateFlow<LocalPluginImportState>(LocalPluginImportState.Idle)
    val localPluginImportState: StateFlow<LocalPluginImportState> = _localPluginImportState.asStateFlow()
    private var localPluginImportJob: Job? = null

    private val staticInstallerById = installerAdapters.associateBy { it.toolId }

    init {
        // Keep the latest bundle log available after a tab/activity recreation.
        managerScope.launch {
            runCatching {
                val persisted = installLogRepository
                    .observeForTool(currentDistroId(), BUNDLE_LOG_TOOL_ID)
                    .first()
                    .map { it.message }
                if (persisted.isNotEmpty()) _bundleInstallLog.value = persisted.takeLast(MAX_BUNDLE_LOG_LINES)
            }
        }
    }

    /** Current distro (target system for install/uninstall/verify operations). */
    private fun currentDistroId(): String = linuxRuntime.activeDistroId.value

    /** Plugin state isolated by current distro: switches to that system's install state on distro change. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeTools(): Flow<List<ToolEntity>> =
        linuxRuntime.activeDistroId.flatMapLatest { distroId ->
            toolRepository.observeTools(distroId).map { list ->
                list.filterNot { it.id in ToolRegistry.REMOVED_TOOL_IDS }
            }
        }

    /** Expose manifest metadata for detail screens. */
    fun manifest(toolId: String): ToolManifest? = toolRepository.manifest(toolId)

    suspend fun importLocalPlugin(
        uri: Uri,
        onProgress: (LocalPluginImportProgress) -> Unit = {},
    ): AppResult<ToolManifest> {
        val result = toolRepository.importLocal(uri, onProgress)
        if (result is AppResult.Success) syncRegistry()
        return result
    }

    /** Read and validate package metadata; no extraction or registry mutation occurs yet. */
    @Synchronized
    fun startLocalPluginImport(uri: Uri, fileName: String): Job {
        localPluginImportJob?.takeIf { it.isActive }?.let { return it }
        _localPluginImportState.value = LocalPluginImportState.Reading(fileName)
        return managerScope.launch {
            val result = toolRepository.inspectLocal(uri)
            # Same version already imported still enters confirmation (overwrite import): offline packages may be repackaged by authors
            # repackaged (e.g., fixed install scripts), importLocal supports atomic same-version replacement.
            # Blind AlreadyImported check would block fixed packages forever.
            _localPluginImportState.value = when (result) {
                is AppResult.Success -> LocalPluginImportState.PendingConfirmation(
                    uri = uri,
                    fileName = fileName,
                    manifest = result.data.manifest,
                    archiveSizeBytes = result.data.archiveSizeBytes,
                )
                is AppResult.Failure -> LocalPluginImportState.Failed(result.error.message)
            }
        }.also { localPluginImportJob = it }
    }

    /** Confirm a previously previewed package and perform extraction/registration. */
    @Synchronized
    fun confirmLocalPluginImport(): Job? {
        val pending = _localPluginImportState.value as? LocalPluginImportState.PendingConfirmation ?: return null
        _localPluginImportState.value = LocalPluginImportState.Importing(
            fileName = pending.fileName,
            progress = null,
            bytesRead = 0L,
            totalBytes = pending.archiveSizeBytes,
            currentEntry = null,
        )
        return managerScope.launch {
            try {
                val result = importLocalPlugin(pending.uri) { progress ->
                    _localPluginImportState.value = LocalPluginImportState.Importing(
                        fileName = pending.fileName,
                        progress = progress.fraction,
                        bytesRead = progress.bytesRead,
                        totalBytes = progress.totalBytes,
                        currentEntry = progress.currentEntry,
                    )
                }
                _localPluginImportState.value = when (result) {
                is AppResult.Success -> {
                    val manifest = result.data
                    var installCompleted = false
                    var installFailure: String? = null
                    try {
                        install(manifest.id).collect { event ->
                            when (event) {
                                is InstallEvent.Progress -> _localPluginImportState.value = LocalPluginImportState.Importing(
                                    fileName = pending.fileName,
                                    progress = event.progress,
                                    bytesRead = pending.archiveSizeBytes ?: 0L,
                                    totalBytes = pending.archiveSizeBytes,
                                    currentEntry = event.message,
                                )
                                is InstallEvent.Completed -> installCompleted = true
                                is InstallEvent.Failed -> installFailure = event.message
                                is InstallEvent.Cancelled -> installFailure = "Plugin installation cancelled"
                                else -> Unit
                            }
                        }
                    } catch (throwable: Throwable) {
                        installFailure = throwable.message ?: "Plugin installation failed"
                    }
                    if (installCompleted) {
                        LocalPluginImportState.Succeeded(manifest.name, manifest.version)
                    } else {
                        LocalPluginImportState.Failed(installFailure ?: "Plugin extracted but installation incomplete")
                    }
                }
                    is AppResult.Failure -> LocalPluginImportState.Failed(result.error.message)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                _localPluginImportState.value = LocalPluginImportState.Failed(
                    "Local plugin install failed: ${throwable.message ?: "unknown error"}",
                )
            }
        }.also { localPluginImportJob = it }
    }

    fun cancelLocalPluginImport() {
        if (_localPluginImportState.value is LocalPluginImportState.PendingConfirmation) {
            _localPluginImportState.value = LocalPluginImportState.Idle
        }
    }

    fun clearLocalPluginImportState() {
        if (localPluginImportJob?.isActive != true) {
            _localPluginImportState.value = LocalPluginImportState.Idle
        }
    }

    /** Check whether a background gateway process is alive AND its port is listening (web services). */
    fun isGatewayRunning(toolId: String): Boolean {
        val spec = serviceSpec(toolId)
        return serviceController.isRunning(toolId, spec)
    }

    /** Stop a running gateway service for the given tool. */
    suspend fun stopGateway(toolId: String) {
        val spec = serviceSpec(toolId)
        serviceController.stop(toolId, spec)
    }

    /**
     * Restart a running gateway service. Used to apply config changes (new token,
     * model environment) that are injected via environment variables at process start.
     */
    suspend fun restartGateway(toolId: String): ManagedProcess {
        requireInstalledTool(toolId)
        return serviceController.restart(toolId, requireAdapter(toolId), serviceSpec(toolId))
    }

    /** Observe real-time output logs for a tool's background service. */
    fun observeServiceLogs(toolId: String): Flow<List<String>> =
        serviceController.observeLogs(toolId)

    /** Get snapshot of service logs for a tool. */
    fun getServiceLogs(toolId: String): List<String> =
        serviceController.getLogs(toolId)

    /** Clear service logs for a tool. */
    fun clearServiceLogs(toolId: String) {
        serviceController.clearLogs(toolId)
    }

    fun isToolSupported(toolId: String): Boolean = getAdapter(toolId) != null

    fun startInstall(toolId: String): Job {
        val existing = installJobs[toolId]
        if (existing?.isActive == true) return existing
        return managerScope.launch {
            try {
                install(toolId).collect { }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                // Handled in install flow
            }
        }
    }

    fun startUpdate(toolId: String): Job {
        val existing = installJobs[toolId]
        if (existing?.isActive == true) return existing
        return managerScope.launch {
            try {
                update(toolId).collect { }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                // Handled in update flow
            }
        }
    }

    private fun getAdapter(toolId: String): ToolRuntimeAdapter? {
        staticInstallerById[toolId]?.let { return it }
        val manifest = toolRepository.manifest(toolId) ?: return null
        if (!manifest.installScript.isNullOrBlank() || manifest.installMethod.isNotBlank()) {
            return top.wkbin.taixu.runtime.tools.GenericRecipeInstaller(
                manifest = manifest,
                linuxRuntime = linuxRuntime,
                dependencyManager = dependencyManager,
                providerManager = providerManager,
                toolCommandLinker = toolCommandLinker,
                localPluginPayloadManager = localPluginPayloadManager,
            )
        }
        return null
    }

    /** Service metadata comes from the signed/validated manifest, not the UI. */
    fun serviceSpec(toolId: String): LocalServiceSpec? {
        val manifest = toolRepository.manifest(toolId) ?: return null
        val port = manifest.servicePort ?: return null
        return LocalServiceSpec(
            serviceId = toolId,
            port = port,
            path = manifest.servicePath,
        )
    }

    suspend fun syncRegistry() {
        val liveInstallTools = installJobs.keys.toSet()
        # Interrupted task recovery per task-recorded system; metadata sync covers all installed systems
        val distroIds = linuxRuntime.installedDistros.value.map { it.id }
            .ifEmpty { listOf(currentDistroId()) }
            .distinct()
        installTaskRepository.listByState(TASK_RUNNING)
            .filter { it.toolId !in liveInstallTools }
            .forEach { task ->
                val taskDistro = task.distroId
                installTransactionManager.recover(
                    distroId = taskDistro,
                    toolId = task.toolId,
                    preserveExisting = task.operation == OPERATION_UPDATE,
                )
                installTaskRepository.upsert(
                    task.copy(
                        state = TASK_INTERRUPTED,
                        message = "Detected app process interrupted, please retry",
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
                val interruptedTool = toolRepository.findById(taskDistro, task.toolId)
                val recoveredState = if (
                    task.operation == OPERATION_UPDATE && interruptedTool?.installedVersion != null
                ) {
                    ToolState.INSTALLED.name
                } else {
                    ToolState.FAILED.name
                }
                toolRepository.updateState(taskDistro, task.toolId, recoveredState)
                installLogRepository.insert(
                    InstallLogEntity(
                        distroId = taskDistro,
                        toolId = task.toolId,
                        event = TASK_INTERRUPTED,
                        message = "${task.operation} task marked interrupted after app process terminated",
                    ),
                )
            }
        // Registry parsing is a recoverable boundary. A malformed optional/remote
        // manifest must not terminate the application or the local-plugin flow.
        val manifests = try {
            toolRepository.manifests()
        } catch (_: Throwable) {
            return
        }
        distroIds.forEach { distroId ->
            manifests.forEach { manifest ->
                val existing = toolRepository.findById(distroId, manifest.id)
                toolRepository.upsert(manifest.toEntity(distroId, existing))
                if (existing?.state == ToolState.INSTALLING.name && manifest.id !in liveInstallTools) {
                    // The process may have been killed while an installer was running.
                    // Never leave a durable INSTALLING state that has no live Job behind it.
                    toolRepository.updateState(distroId, manifest.id, ToolState.FAILED.name)
                    installLogRepository.insert(
                        InstallLogEntity(
                            distroId = distroId,
                            toolId = manifest.id,
                            event = "RECOVERED",
                            message = "Detected previous install interrupted, please retry",
                        ),
                    )
                }
            }
        }
        # Clean up deprecated/removed standalone online plugins (e.g., claude-code, openclaw, hermes-agent)
        toolRepository.deleteByIds(ToolRegistry.REMOVED_TOOL_IDS)
        installTransactionManager.cleanupOrphans(liveInstallTools)
    }

    /** Install and update share the same transactional adapter path. */
    fun install(toolId: String): Flow<InstallEvent> = installInternal(toolId, OPERATION_INSTALL)

    /**
     * 🔍 Live probe: detect actual install readiness of each component in Linux sandbox
     */
    suspend fun probeInstalledComponents(): Set<String> {
        val distroId = currentDistroId()
        val allComponents = top.wkbin.taixu.core.model.BuiltinPluginBundles.bundles.flatMap { it.components }
        val installed = mutableSetOf<String>()
        allComponents.forEach { comp ->
            val result = linuxRuntime.execute(
                top.wkbin.taixu.runtime.shell.ShellCommand(
                    commandLine = comp.checkCommand,
                    workingDirectory = "/root",
                    timeoutMs = 5000L,
                ),
                distroId = distroId,
            )
            if (result.isSuccess) {
                installed.add(comp.id)
            }
        }
        return installed
    }

    /**
     * 🛠️ Batch aggregate atomic install of selected components (Batch Component Installation)
     * Auto-deduplicate APT deps, run 1 update + 1 install, eliminating dpkg lock conflicts, 3-5x speedup.
     */
    fun batchInstallComponents(componentIds: Set<String>): Flow<InstallEvent> = flow {
        if (componentIds.isEmpty()) {
            emit(InstallEvent.Completed("components", "1.0.0"))
            return@flow
        }
        val distroId = currentDistroId()
        val bundleTitle = "Dev Bundle Assembly"
        installMutex.withLock {
            _isBatchInstalling.value = true
            backgroundTaskRegistry.start(BUNDLE_TASK_ID)
            _bundleInstallLog.value = emptyList()
            runCatching { installLogRepository.deleteForTool(distroId, BUNDLE_LOG_TOOL_ID) }
            emit(InstallEvent.Started("components"))
            runCatching {
                assetSynchronizer.syncAssetsToDistro(distroId)
            }
            val steps = top.wkbin.taixu.core.model.BuiltinPluginBundles.buildBatchInstallScript(componentIds)
            val selectedComps = top.wkbin.taixu.core.model.BuiltinPluginBundles.bundles.flatMap { it.components }.filter { it.id in componentIds }
            val compNames = selectedComps.joinToString("、") { it.name }

            val initialMsg = "Preparing [$compNames] batch assembly pipeline..."
            _bundleInstallState.value = initialMsg
            appendBundleInstallLog(initialMsg)
            notificationNotifier.showProgress("dev_bundle_install", bundleTitle, initialMsg, 0.05f)
            emit(InstallEvent.Progress("components", initialMsg, 0.05f))

            try {
                steps.forEachIndexed { index, step ->
                    val progress = 0.1f + 0.8f * (index.toFloat() / steps.size.toFloat())
                    val shortDesc = when {
                        index == 0 -> "Creating dpkg config directory..."
                        index == 1 -> "Writing PRoot dpkg security policy..."
                        index == 2 -> "Cleaning dpkg/apt leftover locks and temp files..."
                        "dpkg --remove" in step -> "Cleaning incomplete optional package transactions..."
                        "dpkg --configure" in step -> "Recovering incomplete dpkg transactions..."
                        # apt-get has -o params; cannot use fixed "apt-get update" substring or misclassifies install steps.
                        "apt-get" in step && " update " in step -> "Syncing repos and aggregating all dependency downloads..."
                        "apt-get" in step -> "Installing system deps for [$compNames]..."
                        "gradle" in step -> "Deploying and linking Gradle 8.14.2 build environment..."
                        "setup_android_core" in step -> "Deploying Android SDK platforms and Gradle build env (CN mirror)..."
                        "termux_ndk" in step -> "Downloading, verifying and atomically assembling Linux AArch64 NDK..."
                        "jadx" in step -> "Deploying JADX-CLI source decompiler toolkit..."
                        "android" in step -> "Configuring Android SDK official toolchain..."
                        "flutter" in step -> "Fetching and configuring Flutter SDK cross-platform dev env..."
                        else -> "Executing environment prep step..."
                    }
                    # Heavy download scripts (SDK platforms ~60MB / Gradle ~120MB / Flutter SDK git clone) get extended timeout
                    val stepTimeoutMs = when {
                        "/opt/taixu/scripts/" in step ||
                            "setup_android_core.sh" in step || "setup_flutter.sh" in step -> HEAVY_SETUP_STEP_TIMEOUT_MS
                        else -> DEFAULT_STEP_TIMEOUT_MS
                    }
                    val stepLabel = "[Step ${index + 1}/${steps.size}] $shortDesc"
                    _bundleInstallState.value = stepLabel
                    appendBundleInstallLog(stepLabel)
                    notificationNotifier.showProgress("dev_bundle_install", bundleTitle, stepLabel, progress)
                    emit(InstallEvent.Progress("components", stepLabel, progress))

                    val flutterArchive = if ("setup_flutter.sh" in step) {
                        appendBundleInstallLog("==> [TaiXu] Using in-app resumable downloader for Flutter SDK (no curl in PRoot)...")
                        var lastFlutterLogAt = 0L
                        var lastFlutterLoggedBytes = -1L
                        flutterSdkDownloader.prepare(distroId) { downloaded, total ->
                            val totalText = total?.takeIf { it > 0 }?.let { " / ${it / (1024 * 1024)} MB" }.orEmpty()
                            val downloadedMb = downloaded / (1024 * 1024)
                            val now = System.currentTimeMillis()
                            val completed = total != null && total > 0L && downloaded >= total
                            val shouldLog = downloaded == 0L || completed ||
                                (downloaded > lastFlutterLoggedBytes && now - lastFlutterLogAt >= FLUTTER_DOWNLOAD_LOG_INTERVAL_MS)
                            if (shouldLog) {
                                appendBundleInstallLog("[TaiXu] Flutter SDK in-app download: $downloadedMb MB$totalText")
                                lastFlutterLoggedBytes = downloaded
                                lastFlutterLogAt = now
                            }
                        }
                    } else {
                        null
                    }
                    val result = linuxRuntime.execute(
                        top.wkbin.taixu.runtime.shell.ShellCommand(
                            commandLine = step,
                            workingDirectory = "/root",
                            environment = flutterArchive?.let {
                                mapOf("TAIXU_FLUTTER_ARCHIVE" to it.guestPath)
                            }.orEmpty(),
                            timeoutMs = stepTimeoutMs,
                        ),
                        distroId = distroId,
                    )
                    result.stdout.trim().takeIf { it.isNotBlank() }?.let { appendBundleInstallLog(it.takeLast(4000)) }
                    result.stderr.trim().takeIf { it.isNotBlank() }?.let { appendBundleInstallLog(it.takeLast(4000)) }
                    if (!result.isSuccess) {
                        error("Install step failed: ${result.stderr.ifBlank { result.stdout }.takeLast(800)}")
                    }
                }

                _bundleInstallState.value = "Verifying installed component status..."
                appendBundleInstallLog("Verifying installed component status...")
                notificationNotifier.showProgress("dev_bundle_install", bundleTitle, "Verifying status...", 0.95f)
                emit(InstallEvent.Progress("components", "Verifying installed component status...", 0.95f))

                val installed = probeInstalledComponents()
                val missing = componentIds - installed
                if (missing.isNotEmpty()) {
                    error("Dev bundle install incomplete, missing components: ${missing.joinToString()}")
                }

                notificationNotifier.showSuccess("dev_bundle_install", bundleTitle, "Successfully ready")
                emit(InstallEvent.Completed("components", "1.0.0"))
            } catch (e: Exception) {
                appendBundleInstallLog("Install failed: ${e.message ?: "Assembly error"}")
                notificationNotifier.showFailed("dev_bundle_install", bundleTitle, e.message ?: "Assembly error")
                emit(InstallEvent.Failed("components", e.message ?: "Assembly error"))
                throw e
            } finally {
                _isBatchInstalling.value = false
                backgroundTaskRegistry.finish(BUNDLE_TASK_ID)
                _bundleInstallState.value = null
            }
        }
    }

    private fun appendBundleInstallLog(message: String) {
        val normalized = message.trim()
        if (normalized.isBlank()) return
        val incoming = normalized.lineSequence().filter { it.isNotBlank() }.toList()
        if (incoming.isEmpty()) return
        val retained = (_bundleInstallLog.value + incoming).toMutableList()
        var totalChars = retained.sumOf { it.length + 1 }
        while (totalChars > MAX_BUNDLE_LOG_CHARS && retained.size > 1) {
            totalChars -= retained.removeAt(0).length + 1
        }
        // A single tool line can be unusually large; cap it independently.
        _bundleInstallLog.value = retained.map { line ->
            if (line.length <= MAX_BUNDLE_LINE_CHARS) line else line.takeLast(MAX_BUNDLE_LINE_CHARS)
        }
        managerScope.launch {
            runCatching {
                incoming.forEach { line ->
                    installLogRepository.insert(
                        InstallLogEntity(
                            distroId = currentDistroId(),
                            toolId = BUNDLE_LOG_TOOL_ID,
                            event = "bundle",
                            message = if (line.length <= MAX_BUNDLE_LINE_CHARS) line else line.takeLast(MAX_BUNDLE_LINE_CHARS),
                        ),
                    )
                }
            }
        }
    }

    /**
     * 🚀 Run batch assembly silently offline in app-lifecycle coroutine (managerScope)
     * User can freely switch pages (workshop, chat, terminal, etc.), background assembly unaffected, notification bar updates in real-time.
     */
    fun startBackgroundBatchInstall(componentIds: Set<String>, onCompleted: (() -> Unit)? = null): Job {
        return managerScope.launch {
            try {
                batchInstallComponents(componentIds).collect {}
                onCompleted?.invoke()
            } catch (e: Exception) {
                android.util.Log.w("ToolManager", "Background batch install components failed: ${e.message}", e)
            }
        }
    }

    /**
     * 🛠️ Supports batch install by bundle ID
     */
    fun batchInstallSuites(suiteIds: Set<String>): Flow<InstallEvent> {
        val componentIds = top.wkbin.taixu.core.model.BuiltinPluginBundles.bundles
            .filter { it.id in suiteIds }
            .flatMap { it.components }
            .map { it.id }
            .toSet()
        return batchInstallComponents(if (componentIds.isEmpty()) suiteIds else componentIds)
    }

    private fun installInternal(toolId: String, operation: String): Flow<InstallEvent> = flow {
        require(isToolSupported(toolId)) { "Install not supported for tool: $toolId" }
        requireManifestEnabled(toolId)
        val distroId = currentDistroId()
        val currentJob = coroutineContext[Job]
            ?: error("Install task must run in coroutine")
        val previousTool = toolRepository.findById(distroId, toolId)
        val preservePreviousInstall = operation == OPERATION_UPDATE &&
            previousTool?.installedVersion != null
        installMutex.withLock {
            check(toolId !in installJobs) { "Tool already installing: $toolId" }
            installJobs[toolId] = currentJob
            installLogRepository.deleteForTool(distroId, toolId)
            updateProgress(ToolInstallProgress(toolId, "Preparing install", 0f))
            toolRepository.updateState(distroId, toolId, ToolState.INSTALLING.name)
            installTaskRepository.upsert(
                InstallTaskEntity(
                    distroId = distroId,
                    toolId = toolId,
                    operation = operation,
                    state = TASK_RUNNING,
                    message = "Task started",
                ),
            )
        }

        val toolName = previousTool?.name ?: toolRepository.findById(distroId, toolId)?.name ?: toolId
        var cancelled = false
        var transaction: InstallTransaction? = null
        try {
            if (operation == OPERATION_UPDATE) {
                linuxRuntime.listBackground()
                    .filter { it.toolId == toolId }
                    .forEach { linuxRuntime.stopBackground(it.id) }
            }
            if (preservePreviousInstall) {
                val current = requireAdapter(toolId).verify()
                check(current.isSuccess) {
                    "Pre-update verify failed: ${current.stderr.ifBlank { current.stdout }.trim()}"
                }
            }
            transaction = installTransactionManager.begin(distroId, toolId, preservePreviousInstall)
            selectInstaller(toolId).collect { event ->
                val safeEvent = event.redacted()
                recordEvent(distroId, safeEvent)
                updateFromEvent(safeEvent)
                when (safeEvent) {
                    is InstallEvent.Progress -> {
                        notificationNotifier.showProgress(toolId, toolName, safeEvent.message, safeEvent.progress)
                    }
                    is InstallEvent.Completed -> {
                        val installedVer = safeEvent.version?.trim()?.takeIf { it.isNotBlank() }
                            ?: toolRepository.findById(distroId, toolId)?.manifestVersion
                        toolRepository.updateStateAndInstalledVersion(
                            distroId = distroId,
                            id = toolId,
                            state = ToolState.INSTALLED.name,
                            installedVersion = installedVer,
                        )
                        notificationNotifier.showSuccess(toolId, toolName, installedVer)
                    }
                    is InstallEvent.Failed -> {
                        transaction?.let { installTransactionManager.rollback(it) }
                        transaction = null
                        toolRepository.updateStateAndInstalledVersion(
                            distroId = distroId,
                            id = toolId,
                            state = failureState(preservePreviousInstall, previousTool),
                            installedVersion = if (preservePreviousInstall) previousTool.installedVersion else null,
                        )
                        if (!preservePreviousInstall) releaseRuntimeReferences(toolId, distroId)
                        notificationNotifier.showFailed(toolId, toolName, safeEvent.message)
                    }
                    is InstallEvent.Cancelled -> {
                        notificationNotifier.cancel(toolId)
                    }
                    else -> Unit
                }
                when (safeEvent) {
                    is InstallEvent.Completed -> updateTask(distroId, toolId, TASK_COMPLETED, "Installation complete")
                    is InstallEvent.Failed -> updateTask(distroId, toolId, TASK_FAILED, safeEvent.message)
                    else -> Unit
                }
                if (safeEvent is InstallEvent.Completed) {
                    transaction?.let { installTransactionManager.commit(it) }
                    transaction = null
                }
                emit(safeEvent)
            }
        } catch (throwable: CancellationException) {
            cancelled = true
            notificationNotifier.cancel(toolId)
            withContext(NonCancellable) {
                transaction?.let { tx ->
                    try {
                        installTransactionManager.rollback(tx)
                    } finally {
                        transaction = null
                    }
                }
                toolRepository.updateStateAndInstalledVersion(
                    distroId = distroId,
                    id = toolId,
                    state = failureState(preservePreviousInstall, previousTool, cancelled = true),
                    installedVersion = if (preservePreviousInstall) previousTool.installedVersion else null,
                )
                updateTask(distroId, toolId, TASK_CANCELLED, "User cancelled installation")
                if (!preservePreviousInstall) releaseRuntimeReferences(toolId, distroId)
                val event = InstallEvent.Cancelled(toolId)
                recordEvent(distroId, event)
                updateFromEvent(event)
            }
            throw throwable
        } catch (throwable: Throwable) {
            val event = InstallEvent.Failed(
                toolId,
                secretRedactor.redact(throwable.message ?: "Install process terminated unexpectedly"),
            )
            notificationNotifier.showFailed(toolId, toolName, event.message)
            withContext(NonCancellable) {
                transaction?.let { tx ->
                    try {
                        installTransactionManager.rollback(tx)
                    } finally {
                        transaction = null
                    }
                }
                toolRepository.updateStateAndInstalledVersion(
                    distroId = distroId,
                    id = toolId,
                    state = failureState(preservePreviousInstall, previousTool),
                    installedVersion = if (preservePreviousInstall) previousTool.installedVersion else null,
                )
                if (!preservePreviousInstall) releaseRuntimeReferences(toolId, distroId)
                updateTask(distroId, toolId, TASK_FAILED, event.message)
                recordEvent(distroId, event)
                updateFromEvent(event)
            }
            emit(event)
        } finally {
            withContext(NonCancellable) {
                installMutex.withLock {
                    if (installJobs[toolId] === currentJob) installJobs.remove(toolId)
                }
                if (!cancelled && _installProgress.value[toolId]?.terminal != true) {
                    // An adapter that ended without Completed/Failed is not a successful install.
                    transaction?.let { tx ->
                        try {
                            installTransactionManager.rollback(tx)
                        } finally {
                            transaction = null
                        }
                    }
                    toolRepository.updateStateAndInstalledVersion(
                        distroId = distroId,
                        id = toolId,
                        state = failureState(preservePreviousInstall, previousTool),
                        installedVersion = if (preservePreviousInstall) previousTool.installedVersion else null,
                    )
                    if (!preservePreviousInstall) releaseRuntimeReferences(toolId, distroId)
                    val event = InstallEvent.Failed(toolId, "Install process incomplete")
                    updateTask(distroId, toolId, TASK_FAILED, event.message)
                    recordEvent(distroId, event)
                    updateFromEvent(event)
                }
            }
        }
    }

    fun update(toolId: String): Flow<InstallEvent> = installInternal(toolId, OPERATION_UPDATE)

    fun cancelInstall(toolId: String) {
        installJobs[toolId]?.cancel(CancellationException("User cancelled installation"))
    }

    suspend fun uninstall(toolId: String, deleteData: Boolean = false) {
        require(isToolSupported(toolId)) { "Uninstall not supported for tool: $toolId" }
        installMutex.withLock {
            check(toolId !in installJobs) { "Tool already installing: $toolId" }
        }
        linuxRuntime.listBackground()
            .filter { it.toolId == toolId }
            .forEach { linuxRuntime.stopBackground(it.id) }
        uninstallLocked(toolId, deleteData)
    }

    private suspend fun uninstallLocked(toolId: String, deleteData: Boolean) {
        val distroId = currentDistroId()
        installTaskRepository.upsert(
            InstallTaskEntity(
                distroId = distroId,
                toolId = toolId,
                operation = OPERATION_UNINSTALL,
                state = TASK_RUNNING,
                message = "Uninstall task started",
            ),
        )
        try {
            val outcome = requireAdapter(toolId, requireEnabled = false).uninstall(deleteData).toUninstallOutcome()
            val event = if (outcome.success) "UNINSTALLED" else "UNINSTALL_FAILED"
            val safeMessage = secretRedactor.redact(outcome.message.ifBlank { "Uninstall complete" })
            toolRepository.updateStateAndInstalledVersion(
                distroId = distroId,
                id = toolId,
                state = if (outcome.success) ToolState.AVAILABLE.name else ToolState.FAILED.name,
                installedVersion = if (outcome.success) null else toolRepository.findById(distroId, toolId)?.installedVersion,
            )
            if (outcome.success) {
                releaseRuntimeReferences(toolId, distroId)
                toolSettingsRepository.delete(distroId, toolId)
                settingsDataStore.setToolAccessToken(distroId, toolId, null)
            }
            updateTask(distroId, toolId, if (outcome.success) TASK_COMPLETED else TASK_FAILED, safeMessage)
            installLogRepository.insert(InstallLogEntity(distroId = distroId, toolId = toolId, event = event, message = safeMessage))
        } catch (cancellation: CancellationException) {
            withContext(NonCancellable) { updateTask(distroId, toolId, TASK_CANCELLED, "User cancelled uninstall") }
            throw cancellation
        } catch (throwable: Throwable) {
            val message = secretRedactor.redact(throwable.message ?: "Uninstall process terminated unexpectedly")
            updateTask(distroId, toolId, TASK_FAILED, message)
            throw throwable
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeInstallLogs(toolId: String): Flow<List<InstallLogEntity>> =
        linuxRuntime.activeDistroId.flatMapLatest { distroId ->
            installLogRepository.observeForTool(distroId, toolId)
        }

    suspend fun clearLogs(toolId: String) =
        installLogRepository.deleteForTool(currentDistroId(), toolId)

    /** Drop persisted tool/install state after the corresponding distro is reset. */
    suspend fun resetDistroState(distroId: String) {
        toolRepository.getForDistro(distroId).forEach { tool ->
            settingsDataStore.setToolAccessToken(distroId, tool.id, null)
        }
        toolRepository.deleteByDistro(distroId)
        installTaskRepository.deleteByDistro(distroId)
        installLogRepository.deleteByDistro(distroId)
        toolSettingsRepository.deleteByDistro(distroId)
        _installProgress.value = emptyMap()
        _verifications.value = emptyMap()
    }

    suspend fun launch(toolId: String): top.wkbin.taixu.runtime.shell.CommandResult {
        requireInstalledTool(toolId)
        return requireAdapter(toolId).launch()
    }

    suspend fun startSession(toolId: String?, workingDirectory: String = "/root"): LinuxSession {
        val config = if (toolId.isNullOrBlank()) {
            null
        } else {
            requireInstalledTool(toolId)
            requireAdapter(toolId).interactiveSessionConfig()
        }
        return if (config == null) {
            linuxRuntime.startSession(top.wkbin.taixu.runtime.shell.SessionConfig(workingDirectory = workingDirectory))
        } else {
            linuxRuntime.startSession(config.copy(workingDirectory = workingDirectory))
        }
    }

    suspend fun verify(toolId: String): ToolVerification {
        require(isToolSupported(toolId)) { "Verify not supported for tool: $toolId" }
        requireManifestEnabled(toolId)
        val result = requireAdapter(toolId).verify()
        val safeStdout = secretRedactor.redact(result.stdout)
        val safeStderr = secretRedactor.redact(result.stderr)
        val verification = ToolVerification(
            toolId = toolId,
            healthy = result.isSuccess,
            version = safeStdout.trim().lineSequence().firstOrNull()?.takeIf { it.isNotBlank() },
            detail = if (result.isSuccess) {
                safeStdout.trim().ifBlank { "Command executed successfully" }
            } else {
                safeStderr.ifBlank { safeStdout }.trim().ifBlank { "Command exit code ${result.exitCode}" }
            },
        )
        _verifications.value = _verifications.value + (toolId to verification)
        installLogRepository.insert(
            InstallLogEntity(
                distroId = currentDistroId(),
                toolId = toolId,
                event = if (verification.healthy) "VERIFIED" else "VERIFY_FAILED",
                message = verification.detail,
            ),
        )
        if (verification.healthy) {
            val distroId = currentDistroId()
            val current = toolRepository.findById(distroId, toolId)
            toolRepository.updateStateAndInstalledVersion(
                distroId = distroId,
                id = toolId,
                state = ToolState.INSTALLED.name,
                installedVersion = current?.installedVersion
                    ?: current?.manifestVersion
                    ?: verification.version,
            )
        } else {
            toolRepository.updateState(currentDistroId(), toolId, ToolState.FAILED.name)
        }
        return verification
    }

    suspend fun startGateway(toolId: String): ManagedProcess {
        requireInstalledTool(toolId)
        return serviceController.start(toolId, requireAdapter(toolId), serviceSpec(toolId))
    }

    private suspend fun requireInstalledTool(toolId: String): ToolEntity {
        val tool = toolRepository.findById(currentDistroId(), toolId)
            ?: error("Tool not in current manifest: $toolId")
        check(tool.state == ToolState.INSTALLED.name || tool.state == ToolState.UPDATE_AVAILABLE.name) {
            "Tool not installed: ${tool.name}"
        }
        return tool
    }

    private fun selectInstaller(toolId: String): Flow<InstallEvent> =
        requireAdapter(toolId).install()

    private fun requireAdapter(
        toolId: String,
        requireEnabled: Boolean = true,
    ): ToolRuntimeAdapter = checkNotNull(getAdapter(toolId)) { "Tool not supported: $toolId" }.also {
        if (requireEnabled) {
            requireManifestEnabled(toolId)
        }
    }

    private fun requireManifestEnabled(toolId: String) {
        val manifest = toolRepository.manifest(toolId)
            ?: error("Tool not in current manifest: $toolId")
        check(manifest.enabled) { "Tool suspended by Registry: ${manifest.name}" }
    }

    private suspend fun recordEvent(distroId: String, event: InstallEvent) {
        installLogRepository.insert(event.toLog(distroId))
    }

    private suspend fun updateTask(distroId: String, toolId: String, state: String, message: String) {
        val current = installTaskRepository.findByTool(distroId, toolId) ?: return
        installTaskRepository.upsert(
            current.copy(
                state = state,
                message = secretRedactor.redact(message),
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    private fun InstallEvent.redacted(): InstallEvent = when (this) {
        is InstallEvent.Progress -> copy(message = secretRedactor.redact(message))
        is InstallEvent.Output -> copy(line = secretRedactor.redact(line))
        is InstallEvent.Failed -> copy(message = secretRedactor.redact(message))
        else -> this
    }

    private fun updateFromEvent(event: InstallEvent) {
        val state = when (event) {
            is InstallEvent.Started -> ToolInstallProgress(event.toolId, "Installation started", 0f)
            is InstallEvent.Progress -> ToolInstallProgress(event.toolId, event.message, event.progress)
            is InstallEvent.Output -> ToolInstallProgress(event.toolId, event.line)
            is InstallEvent.Completed -> ToolInstallProgress(event.toolId, "Installation complete", 1f, terminal = true)
            is InstallEvent.Failed -> ToolInstallProgress(event.toolId, event.message, terminal = true)
            is InstallEvent.RolledBack -> ToolInstallProgress(event.toolId, "Install failed, rolling back")
            is InstallEvent.Cancelled -> ToolInstallProgress(event.toolId, "Installation cancelled", terminal = true)
        }
        updateProgress(state)
    }

    private fun updateProgress(progress: ToolInstallProgress) {
        _installProgress.value = _installProgress.value + (progress.toolId to progress)
    }

    private fun failureState(
        preservePreviousInstall: Boolean,
        previousTool: ToolEntity?,
        cancelled: Boolean = false,
    ): String = when {
        preservePreviousInstall -> previousTool?.state ?: ToolState.INSTALLED.name
        cancelled -> ToolState.AVAILABLE.name
        else -> ToolState.FAILED.name
    }

    private suspend fun releaseRuntimeReferences(toolId: String, distroId: String) {
        val dependencies = toolRepository.findById(distroId, toolId)?.dependencies.orEmpty()
            .split(',')
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
        dependencies.forEach { dependency ->
            val runtimeName = when (ManifestDependencyParser.parse(dependency)?.name) {
                "node" -> "node"
                "python" -> "python"
                "git" -> "git"
                "ca-certificates" -> "ca_certificates"
                "curl" -> "curl"
                else -> null
            }
            runtimeName?.let { dependencyManager.release(it, toolId) }
        }
    }

    private fun InstallEvent.toLog(distroId: String): InstallLogEntity = when (this) {
        is InstallEvent.Started -> InstallLogEntity(distroId = distroId, toolId = toolId, event = "STARTED", message = "Installation started")
        is InstallEvent.Progress -> InstallLogEntity(
            distroId = distroId,
            toolId = toolId,
            event = "PROGRESS_${phase.name}",
            message = message,
        )
        is InstallEvent.Output -> InstallLogEntity(distroId = distroId, toolId = toolId, event = "OUTPUT", message = line)
        is InstallEvent.Completed -> InstallLogEntity(distroId = distroId, toolId = toolId, event = "COMPLETED", message = "Installation complete${version?.let { ": $it" } ?: ""}")
        is InstallEvent.Failed -> InstallLogEntity(distroId = distroId, toolId = toolId, event = "FAILED", message = message)
        is InstallEvent.RolledBack -> InstallLogEntity(distroId = distroId, toolId = toolId, event = "ROLLED_BACK", message = "Installation transaction rolled back")
        is InstallEvent.Cancelled -> InstallLogEntity(distroId = distroId, toolId = toolId, event = "CANCELLED", message = "User cancelled installation")
    }

    private suspend fun ToolManifest.toEntity(distroId: String, existing: ToolEntity?) = ToolEntity(
        distroId = distroId,
        id = id,
        name = name,
        description = description,
        dependencies = dependencies.joinToString(","),
        launchType = launchType,
        state = when {
            !enabled -> ToolState.DISABLED.name
            existing == null -> ToolState.AVAILABLE.name
            else -> {
                val installedVersion = existing.installedVersion
                val manifestLatest = latestVersion ?: version
                when {
                    existing.state == ToolState.DISABLED.name ->
                        if (installedVersion != null) ToolState.INSTALLED.name else ToolState.AVAILABLE.name
                    installedVersion != null && isUpdateNewer(manifestLatest, installedVersion) ->
                        ToolState.UPDATE_AVAILABLE.name
                    existing.state == ToolState.UPDATE_AVAILABLE.name -> ToolState.INSTALLED.name
                    else -> existing.state
                }
            }
        },
        manifestVersion = version,
        installedVersion = existing?.installedVersion,
        publisher = publisher,
        category = category,
        permissions = permissions.joinToString(","),
        homepage = homepage,
        updateStrategy = updateStrategy,
        latestVersion = latestVersion ?: version,
    )

    private fun isUpdateNewer(manifestLatest: String?, installedVersion: String?): Boolean {
        if (manifestLatest.isNullOrBlank() || installedVersion.isNullOrBlank()) return false
        val latestNums = manifestLatest.versionNumbers() ?: return false
        val currentNums = installedVersion.versionNumbers() ?: return false
        for (i in 0 until maxOf(latestNums.size, currentNums.size)) {
            val l = latestNums.getOrElse(i) { 0 }
            val c = currentNums.getOrElse(i) { 0 }
            if (l > c) return true
            if (l < c) return false
        }
        return false
    }

    private fun String.versionNumbers(): List<Int>? = Regex("\\d+(?:\\.\\d+){0,3}")
        .find(this)
        ?.value
        ?.split('.')
        ?.mapNotNull { it.toIntOrNull() }

    private fun ToolActionResult.toUninstallOutcome(): UninstallOutcome =
        UninstallOutcome(success, message)

    private fun top.wkbin.taixu.runtime.shell.CommandResult.toUninstallOutcome(): UninstallOutcome =
        UninstallOutcome(isSuccess, stderr.ifBlank { stdout })

    private companion object {
        const val OPERATION_INSTALL = "INSTALL"
        const val OPERATION_UPDATE = "UPDATE"
        const val OPERATION_UNINSTALL = "UNINSTALL"
        const val TASK_RUNNING = "RUNNING"
        const val TASK_COMPLETED = "COMPLETED"
        const val TASK_FAILED = "FAILED"
        const val TASK_CANCELLED = "CANCELLED"
        const val TASK_INTERRUPTED = "INTERRUPTED"
        /** Default timeout for normal install steps (dpkg self-heal / apt batch install / symlink config) */
        // PRoot cold-starts (JDK/Gradle/Flutter) can spend several minutes
        // unpacking or compiling on slower ARM devices. A three-minute default
        // incorrectly aborts valid installs before the script can finish.
        const val DEFAULT_STEP_TIMEOUT_MS = 10 * 60_000L

        /** Timeout for heavy download scripts (Android SDK platforms / Gradle / Flutter SDK / JADX), aligned with GenericRecipeInstaller */
        const val HEAVY_SETUP_STEP_TIMEOUT_MS = 45 * 60_000L
        const val FLUTTER_DOWNLOAD_LOG_INTERVAL_MS = 2_000L
        const val MAX_BUNDLE_LOG_CHARS = 120 * 1024
        const val MAX_BUNDLE_LINE_CHARS = 8 * 1024
        const val MAX_BUNDLE_LOG_LINES = 2048
        const val BUNDLE_LOG_TOOL_ID = "components"
        const val BUNDLE_TASK_ID = "dev-bundle-install"
    }

}
