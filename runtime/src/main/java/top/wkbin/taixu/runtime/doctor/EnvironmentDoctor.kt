package top.wkbin.taixu.runtime.doctor

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import top.wkbin.taixu.core.model.DoctorCategory
import top.wkbin.taixu.core.model.DoctorItem
import top.wkbin.taixu.core.model.DoctorReport
import top.wkbin.taixu.core.model.DoctorStatus
import top.wkbin.taixu.core.model.RuntimeState
import top.wkbin.taixu.runtime.LinuxRuntime
import top.wkbin.taixu.runtime.shell.ShellCommand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class EnvironmentDoctor(
    private val context: Context? = null,
    private val linuxRuntime: LinuxRuntime,
) {
    suspend fun check(): DoctorReport = withContext(Dispatchers.IO) {
        // Host-side permission check: not dependent on sandbox state, both paths must be shown
        val allFilesAccessItem = checkAllFilesAccess()

        val state = linuxRuntime.state.value
        if (state !is RuntimeState.Ready) {
            val unreadyItem = DoctorItem(
                id = "sandbox_unready",
                category = DoctorCategory.SANDBOX,
                title = "PRoot Sandbox Status",
                status = DoctorStatus.ERROR,
                summary = if (state is RuntimeState.Error) "Sandbox error: ${state.throwable.message}" else "Sandbox not initialized",
                detail = "Please initialize and start Linux sandbox from dashboard first",
                fixable = false,
            )
            return@withContext DoctorReport(
                items = listOf(unreadyItem, allFilesAccessItem),
                timestamp = System.currentTimeMillis(),
                overallStatus = DoctorStatus.ERROR,
                healthyCount = itemsHealthy(listOf(unreadyItem, allFilesAccessItem)),
                warningCount = itemsWarning(listOf(unreadyItem, allFilesAccessItem)),
                errorCount = 1,
            )
        }

        val items = mutableListOf<DoctorItem>()

        // 1. Sandbox & storage check
        items.add(checkSandboxStorage())
        items.add(allFilesAccessItem)

        // 2. DNS & network connectivity check
        items.add(checkDnsAndNetwork())

        // 3. CA root certificate check
        items.add(checkCaCertificates())

        // 4. APT package source & mirror acceleration check
        items.add(checkAptMirrors())

        // 5. Basic dev toolchain check (git/curl/tar/xz)
        items.add(checkBaseDevTools())

        // 6. Node.js runtime check
        items.add(checkNodeRuntime())

        // 7. Android dev env check (optional plugin, guides to offline/online plugin if not installed)
        items.add(checkAndroidEnvironment())

        val healthyCount = items.count { it.status == DoctorStatus.HEALTHY }
        val warningCount = items.count { it.status == DoctorStatus.WARNING }
        val errorCount = items.count { it.status == DoctorStatus.ERROR }

        val overallStatus = when {
            errorCount > 0 -> DoctorStatus.ERROR
            warningCount > 0 -> DoctorStatus.WARNING
            else -> DoctorStatus.HEALTHY
        }

        DoctorReport(
            items = items,
            timestamp = System.currentTimeMillis(),
            overallStatus = overallStatus,
            healthyCount = healthyCount,
            warningCount = warningCount,
            errorCount = errorCount,
        )
    }

    private fun checkAllFilesAccess(): DoctorItem {
        val granted = if (context == null) {
            true
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }
        return if (granted) {
            DoctorItem(
                id = "host_all_files_access",
                category = DoctorCategory.SANDBOX,
                title = "All Files Access Permission",
                status = DoctorStatus.HEALTHY,
                summary = "All Files Access granted, file browser can fully access shared storage",
            )
        } else {
            DoctorItem(
                id = "host_all_files_access",
                category = DoctorCategory.SANDBOX,
                title = "All Files Access Permission",
                status = DoctorStatus.WARNING,
                summary = "All Files Access permission not granted",
                detail = "Android filters other apps' files in shared storage, file browser /sdcard may only show folders without files. Click "Grant" on the right to open system settings.",
                fixable = true,
            )
        }
    }

    private fun itemsHealthy(items: List<DoctorItem>): Int = items.count { it.status == DoctorStatus.HEALTHY }

    private fun itemsWarning(items: List<DoctorItem>): Int = items.count { it.status == DoctorStatus.WARNING }

    private suspend fun checkSandboxStorage(): DoctorItem {
        val res = runCatching {
            linuxRuntime.execute(
                ShellCommand(
                    commandLine = "mkdir -p /workspace /tmp && touch /workspace/.doctor_probe && rm -f /workspace/.doctor_probe",
                    timeoutMs = 5000L,
                ),
            )
        }.getOrNull()

        return if (res != null && res.isSuccess) {
            DoctorItem(
                id = "sandbox_storage",
                category = DoctorCategory.SANDBOX,
                title = "PRoot Sandbox & Workspace",
                status = DoctorStatus.HEALTHY,
                summary = "Sandbox virtual env normal, /workspace & /tmp read/write ready",
            )
        } else {
            DoctorItem(
                id = "sandbox_storage",
                category = DoctorCategory.SANDBOX,
                title = "PRoot Sandbox & Workspace",
                status = DoctorStatus.ERROR,
                summary = "Workspace read/write or permission test failed",
                detail = res?.stderr?.ifBlank { res.stdout } ?: "Command execution timeout",
            )
        }
    }

    private suspend fun checkDnsAndNetwork(): DoctorItem {
        val resolvCheck = runCatching {
            linuxRuntime.execute(ShellCommand("cat /etc/resolv.conf 2>/dev/null", timeoutMs = 3000L))
        }.getOrNull()

        val resolvContent = resolvCheck?.stdout.orEmpty()
        val hasNameserver = resolvContent.contains("nameserver", ignoreCase = true)

        if (!hasNameserver) {
            return DoctorItem(
                id = "network_dns",
                category = DoctorCategory.NETWORK_SSL,
                title = "DNS Resolution",
                status = DoctorStatus.WARNING,
                summary = "No valid DNS resolver configured",
                detail = "/etc/resolv.conf empty or missing, may fail to resolve software download domains",
            )
        }

        return DoctorItem(
            id = "network_dns",
            category = DoctorCategory.NETWORK_SSL,
            title = "DNS Resolution",
            status = DoctorStatus.HEALTHY,
            summary = "DNS resolution config normal",
            detail = resolvContent.lineSequence().filter { it.startsWith("nameserver") }.take(2).joinToString(", "),
        )
    }

    private suspend fun checkCaCertificates(): DoctorItem {
        val certCheck = runCatching {
            linuxRuntime.execute(
                ShellCommand(
                    commandLine = "test -f /etc/ssl/certs/ca-certificates.crt || test -d /etc/ssl/certs",
                    timeoutMs = 3000L,
                ),
            )
        }.getOrNull()

        val hasCerts = certCheck != null && certCheck.isSuccess
        return if (hasCerts) {
            DoctorItem(
                id = "ca_certificates",
                category = DoctorCategory.NETWORK_SSL,
                title = "SSL Root Certificates (CA)",
                status = DoctorStatus.HEALTHY,
                summary = "CA root certificates ready, supports HTTPS dependency downloads",
            )
        } else {
            DoctorItem(
                id = "ca_certificates",
                category = DoctorCategory.NETWORK_SSL,
                title = "SSL Root Certificates (CA)",
                status = DoctorStatus.WARNING,
                summary = "System missing CA root certificates",
                detail = "HTTPS downloads or Git Clone may hit SSL verification errors",
            )
        }
    }

    private suspend fun checkAptMirrors(): DoctorItem {
        val sourcesCheck = runCatching {
            linuxRuntime.execute(
                ShellCommand(
                    commandLine = "cat /etc/apt/sources.list /etc/apt/sources.list.d/*.sources /etc/apt/sources.list.d/*.list 2>/dev/null || true",
                    timeoutMs = 4000L,
                ),
            )
        }.getOrNull()

        val content = sourcesCheck?.stdout.orEmpty()
        val hasInvalidUbuntuMirror = (content.contains("/ubuntu ") || content.contains("/ubuntu/")) &&
            !content.contains("ubuntu-ports")

        if (hasInvalidUbuntuMirror) {
            return DoctorItem(
                id = "apt_mirrors",
                category = DoctorCategory.PACKAGE_MANAGER,
                title = "APT Package Sources",
                status = DoctorStatus.WARNING,
                summary = "APT source config abnormal (Ubuntu ARM64 requires ubuntu-ports source)",
                detail = "Detected x86 mirror path on ARM64, causes package 404 errors. Click one-click fix to auto-correct.",
            )
        }

        val hasDomesticMirror = content.contains("tsinghua.edu.cn", ignoreCase = true) ||
            content.contains("aliyun.com", ignoreCase = true) ||
            content.contains("ustc.edu.cn", ignoreCase = true) ||
            content.contains("tencent.com", ignoreCase = true) ||
            content.contains("163.com", ignoreCase = true)

        return if (hasDomesticMirror) {
            DoctorItem(
                id = "apt_mirrors",
                category = DoctorCategory.PACKAGE_MANAGER,
                title = "APT Package Sources",
                status = DoctorStatus.HEALTHY,
                summary = "Domestic mirror sources configured for acceleration (Tsinghua/Ali/USTC)",
            )
        } else {
            DoctorItem(
                id = "apt_mirrors",
                category = DoctorCategory.PACKAGE_MANAGER,
                title = "APT Package Sources",
                status = DoctorStatus.WARNING,
                summary = "Currently using official default source, domestic installs may be slow or timeout",
                detail = "Recommend one-click switch to domestic mirror for fast stable dependency downloads",
            )
        }
    }

    private suspend fun checkBaseDevTools(): DoctorItem {
        val toolsCheck = runCatching {
            linuxRuntime.execute(
                ShellCommand(
                    commandLine = "for t in curl git tar xz; do which \$t >/dev/null 2>&1 || echo \$t; done",
                    timeoutMs = 4000L,
                ),
            )
        }.getOrNull()

        val missingTools = toolsCheck?.stdout?.lines()?.map { it.trim() }?.filter { it.isNotBlank() }.orEmpty()

        return if (missingTools.isEmpty()) {
            DoctorItem(
                id = "base_devtools",
                category = DoctorCategory.DEV_RUNTIMES,
                title = "Core Basic Toolchain",
                status = DoctorStatus.HEALTHY,
                summary = "Git, Curl, Tar, XZ and other common tools ready",
            )
        } else {
            DoctorItem(
                id = "base_devtools",
                category = DoctorCategory.DEV_RUNTIMES,
                title = "Core Basic Toolchain",
                status = DoctorStatus.WARNING,
                summary = "Missing common basic tools: ${missingTools.joinToString(", ")}",
                detail = "Some install scripts or plugins depend on above tools for extraction and code fetching",
            )
        }
    }

    /**
     * Reuse same probe command as android-core component in plugin center,
     * ensuring diagnosis and plugin install status checks are consistent.
     */
    private suspend fun checkAndroidEnvironment(): DoctorItem {
        val checkCommand = top.wkbin.taixu.core.model.BuiltinPluginBundles.bundles
            .firstOrNull { it.id == "android-suite" }
            ?.components?.firstOrNull { it.id == "android-core" }
            ?.checkCommand

        val installed = if (checkCommand.isNullOrBlank()) {
            false
        } else {
            val res = runCatching {
                linuxRuntime.execute(
                    ShellCommand(
                        commandLine = checkCommand,
                        timeoutMs = 8000L,
                    ),
                )
            }.getOrNull()
            res != null && res.isSuccess
        }

        return if (installed) {
            DoctorItem(
                id = "android_environment",
                category = DoctorCategory.DEV_RUNTIMES,
                title = "Android Development Environment",
                status = DoctorStatus.HEALTHY,
                summary = "JDK 17 / Android SDK / Gradle / NDK ready, can build and decompile APK",
            )
        } else {
            DoctorItem(
                id = "android_environment",
                category = DoctorCategory.DEV_RUNTIMES,
                title = "Android Development Environment",
                status = DoctorStatus.WARNING,
                summary = "Android / Flutter / Decompile environment not installed",
                detail = "Join official QQ group to download full offline plugin package (includes Android, Flutter, Decompile three envs, no online install needed), or install online from plugin center.",
                fixable = false,
            )
        }
    }

    private suspend fun checkNodeRuntime(): DoctorItem {
        val nodeCheck = runCatching {
            linuxRuntime.execute(
                ShellCommand(
                    commandLine = "node --version 2>/dev/null || /opt/taixu/bin/node --version 2>/dev/null || /usr/bin/node --version 2>/dev/null",
                    timeoutMs = 4000L,
                ),
            )
        }.getOrNull()

        val rawVersion = nodeCheck?.stdout?.trim().orEmpty()
        if (rawVersion.isBlank()) {
            return DoctorItem(
                id = "node_runtime",
                category = DoctorCategory.DEV_RUNTIMES,
                title = "Node.js Runtime",
                status = DoctorStatus.WARNING,
                summary = "Node.js runtime not detected",
                detail = "OpenClaw, Claude Code and other AI agent tools strongly depend on Node.js (recommend >= v20)",
            )
        }

        val majorVersion = Regex("v?(\\d+)").find(rawVersion)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        return if (majorVersion >= 20) {
            DoctorItem(
                id = "node_runtime",
                category = DoctorCategory.DEV_RUNTIMES,
                title = "Node.js Runtime",
                status = DoctorStatus.HEALTHY,
                summary = "Node.js $rawVersion (meets mainstream AI tool requirements)",
            )
        } else {
            DoctorItem(
                id = "node_runtime",
                category = DoctorCategory.DEV_RUNTIMES,
                title = "Node.js Runtime",
                status = DoctorStatus.WARNING,
                summary = "Node.js $rawVersion version too low (recommend >= v20)",
                detail = "New AI tools may need newer JavaScript/V8 engine features",
            )
        }
    }
}
