package top.wkbin.taixu.runtime.apps

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.wkbin.taixu.core.database.AndroidAppEntity
import top.wkbin.taixu.core.database.AndroidAppRepository
import top.wkbin.taixu.core.model.ExecutionMode
import top.wkbin.taixu.runtime.privilege.PrivilegeManager

data class AppInventorySyncResult(val total: Int, val systemApps: Int, val userApps: Int)

/**
 * Always builds the basic inventory through PackageManager. When Shizuku/Root is active, it
 * supplements it with shell-only state (suspended and netpolicy) before reconciling Room.
 */
class AndroidAppManager(
    private val context: Context,
    private val privilegeManager: PrivilegeManager,
    private val repository: AndroidAppRepository,
) {
    suspend fun synchronize(): Result<AppInventorySyncResult> = withContext(Dispatchers.IO) {
        runCatching {
            val localApps = installedApplications()
            check(localApps.isNotEmpty()) { "No app inventory read, retained existing database data." }
            val privilege = privilegeManager.getPrivilegeInfo()
            val privilegedSections = if (privilege.mode != ExecutionMode.PROOT && privilege.modeActive) {
                privilegeManager.executeShellCommand(INVENTORY_COMMAND, "android-app-inventory")
                    .takeIf { it.success }
                    ?.stdout
                    ?.let(::parseSections)
                    .orEmpty()
            } else {
                emptyMap()
            }
            val disabled = packageNames(privilegedSections[DISABLED].orEmpty())
            val suspended = packageNames(privilegedSections[SUSPENDED].orEmpty())
            val restrictedUids = Regex("\\b\\d{4,10}\\b").findAll(privilegedSections[NETWORK].orEmpty())
                .map { it.value.toInt() }.toSet()
            val now = System.currentTimeMillis()
            val apps = localApps.map { info ->
                val packageName = info.packageName
                val flags = info.flags
                // In non-privileged mode ApplicationInfo.enabled only reflects manifest default (almost always true),
                // cannot detect apps frozen by user via pm disable-user; getApplicationEnabledSetting
                // reads actual component enabled state without special permissions.
                val enabled = if (privilegedSections.isEmpty()) {
                    isEffectivelyEnabled(packageName, info.enabled)
                } else {
                    packageName !in disabled
                }
                AndroidAppEntity(
                    packageName = packageName,
                    label = info.loadLabel(context.packageManager).toString().ifBlank { packageName },
                    uid = info.uid,
                    apkPath = info.sourceDir.orEmpty(),
                    isSystemApp = flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0,
                    isEnabled = enabled,
                    isSuspended = packageName in suspended,
                    isNetworkRestricted = info.uid in restrictedUids,
                    lastSyncedAt = now,
                )
            }
            repository.reconcile(apps)
            AppInventorySyncResult(apps.size, apps.count { it.isSystemApp }, apps.count { !it.isSystemApp })
        }
    }

    suspend fun requireInitialized(packageName: String): AndroidAppEntity {
        check(repository.count() > 0) { INITIALIZATION_REQUIRED_MESSAGE }
        return requireNotNull(repository.findByPackageName(packageName)) {
            "App $packageName not in synced app database; go to Settings -> App Management to sync first."
        }
    }

    suspend fun isInitialized(): Boolean = repository.count() > 0

    @Suppress("DEPRECATION")
    private fun installedApplications(): List<ApplicationInfo> = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
        } else {
            context.packageManager.getInstalledApplications(0)
        }
    }.getOrDefault(emptyList())

    /**
     * Determine if app is effectively enabled in non-privileged mode: getApplicationEnabledSetting
     * reads user-disabled state via pm disable-user / Settings "Disable" without special perms,
     * compensating for ApplicationInfo.enabled only reflecting manifest default.
     */
    private fun isEffectivelyEnabled(packageName: String, manifestDefault: Boolean): Boolean = runCatching {
        when (context.packageManager.getApplicationEnabledSetting(packageName)) {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED,
            -> false
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            else -> manifestDefault
        }
    }.getOrDefault(manifestDefault)

    private fun parseSections(output: String): Map<String, String> {
        val marker = Regex("^__(TAIXU_[A-Z_]+)__$", RegexOption.MULTILINE)
        val matches = marker.findAll(output).toList()
        return matches.mapIndexed { index, match ->
            val end = matches.getOrNull(index + 1)?.range?.first ?: output.length
            match.groupValues[1].removePrefix("TAIXU_") to output.substring(match.range.last + 1, end)
        }.toMap()
    }

    private fun packageNames(output: String): Set<String> = output.lineSequence()
        .map { it.trim().removePrefix("package:") }
        .filter { PACKAGE_NAME.matches(it) }
        .toSet()

    private companion object {
        const val INITIALIZATION_REQUIRED_MESSAGE = "App database not initialized; go to Settings -> App Management to initialize and sync first."
        const val DISABLED = "DISABLED"
        const val SUSPENDED = "SUSPENDED"
        const val NETWORK = "NETWORK"
        val PACKAGE_NAME = Regex("^[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+$")
        const val INVENTORY_COMMAND = """
            printf '__TAIXU_DISABLED__\\n'; /system/bin/pm list packages -d;
            printf '__TAIXU_SUSPENDED__\\n'; /system/bin/pm list packages --suspended 2>/dev/null || true;
            printf '__TAIXU_NETWORK__\\n'; /system/bin/cmd netpolicy list restrict-background-blacklist 2>/dev/null || true
        """
    }
}
