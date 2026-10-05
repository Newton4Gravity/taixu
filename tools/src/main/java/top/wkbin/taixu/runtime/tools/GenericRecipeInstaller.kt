package top.wkbin.taixu.runtime.tools

import top.wkbin.taixu.core.model.RuntimeName
import top.wkbin.taixu.core.model.RuntimeRequirement
import top.wkbin.taixu.core.model.ToolManifest
import top.wkbin.taixu.core.tools.DependencyManager
import top.wkbin.taixu.core.tools.ManifestDependencyParser
import top.wkbin.taixu.core.tools.ProviderManager
import top.wkbin.taixu.core.tools.ToolActionResult
import top.wkbin.taixu.core.tools.ToolRuntimeAdapter
import top.wkbin.taixu.runtime.LinuxRuntime
import top.wkbin.taixu.runtime.shell.CommandResult
import top.wkbin.taixu.runtime.shell.ManagedProcess
import top.wkbin.taixu.runtime.shell.ProcessType
import top.wkbin.taixu.runtime.shell.SessionConfig
import top.wkbin.taixu.runtime.shell.ShellCommand
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow

/**
 * Generic declarative recipe execution engine (Generic Recipe Installer)
 * Dynamically drives install, symlinks, verification, and lifecycle for any tool
 * based on scripts, dependencies, and env vars declared in ToolManifest.
 */
class GenericRecipeInstaller(
    private val manifest: ToolManifest,
    private val linuxRuntime: LinuxRuntime,
    private val dependencyManager: DependencyManager,
    private val providerManager: ProviderManager,
    private val toolCommandLinker: ToolCommandLinker,
    private val localPluginPayloadManager: top.wkbin.taixu.core.tools.LocalPluginPayloadManager? = null,
) : ToolRuntimeAdapter {
    override val toolId: String = manifest.id

    override fun install(): Flow<InstallEvent> = flow {
        emit(InstallEvent.Started(toolId))
        try {
            checkReady()

            val localPayload = if (manifest.source == "LOCAL") {
                emit(InstallEvent.Progress(toolId, "Loading local plugin assets", 0.05f, InstallEvent.Phase.PREPARING))
                var preparedPath: String? = null
                localPluginPayloadManager?.prepare(toolId, linuxRuntime.activeDistroId.value)?.collect { event ->
                    when (event) {
                        is top.wkbin.taixu.core.tools.LocalPluginPreparationEvent.Copying -> emit(
                            InstallEvent.Progress(
                                toolId = toolId,
                                message = event.message,
                                progress = LOCAL_COPY_PROGRESS_START + event.fraction * LOCAL_COPY_PROGRESS_SPAN,
                                phase = InstallEvent.Phase.PREPARING,
                            ),
                        )
                        is top.wkbin.taixu.core.tools.LocalPluginPreparationEvent.Ready -> preparedPath = event.payloadPath
                    }
                } ?: error("Local plugin payload manager unavailable")
                preparedPath ?: error("Local plugin payload not found")
            } else null

            # 1. Prepare prerequisites
            emit(InstallEvent.Progress(toolId, "Resolving and preparing tool dependencies...", 0.15f, InstallEvent.Phase.INSTALLING_DEPENDENCY))
            for (depString in manifest.dependencies.takeUnless { manifest.offlineOnly }.orEmpty()) {
                val parsed = ManifestDependencyParser.parse(depString)
                if (parsed != null) {
                    val runtimeName = when (parsed.name.lowercase()) {
                        "node" -> RuntimeName.NODE
                        "python" -> RuntimeName.PYTHON
                        "git" -> RuntimeName.GIT
                        "curl" -> RuntimeName.CURL
                        "ca-certificates" -> RuntimeName.CA_CERTIFICATES
                        else -> null
                    }
                    if (runtimeName != null) {
                        acquire(runtimeName, toolId, parsed.constraint)
                    }
                }
            }

            # 2. Prepare isolated directories and environment variables
            emit(InstallEvent.Progress(toolId, "Configuring sandbox isolation env...", 0.35f, InstallEvent.Phase.RUNNING_INSTALLER))
            val toolDir = ToolLayout.toolDirectory(toolId)
            val toolDataDir = ToolLayout.toolDataDirectory(toolId)
            // The framework may install compatibility helpers before the plugin recipe runs,
            // so the conventional bin directory must already exist here.
            executeAndReport("mkdir -p $toolDir/bin $toolDataDir")

            val baseEnvironment = runtimeEnvironment(localPayload)

            // Older offline Android-suite packages called `unzip` directly before
            // the package itself had a chance to install a ZIP extractor. Provide
            // a compatibility shim from the already-installed JDK so those
            // packages remain installable on minimal rootfs images. New packages
            // use the same fallback internally, so this is harmless when unused.
            if (manifest.source == "LOCAL") {
                val unzipShim = localUnzipCompatibilityCommand()
                val shimResult = linuxRuntime.execute(
                    ShellCommand(commandLine = unzipShim, environment = baseEnvironment),
                )
                if (!shimResult.isSuccess) {
                    error(shimResult.stderr.ifBlank { shimResult.stdout }.ifBlank { "Failed to prepare ZIP extraction compat layer" })
                }
            }

            # 2.5 Preflight and self-heal base package manager state (cleanup leftover locks, broken updates transactions, unconfigured dpkg state)
            val preflightCmd = "rm -rf /var/lib/dpkg/updates/* /var/lib/dpkg/lock* /var/lib/apt/lists/lock /var/cache/apt/archives/lock 2>/dev/null || true; DEBIAN_FRONTEND=noninteractive dpkg --configure -a 2>/dev/null || true"
            linuxRuntime.execute(ShellCommand(commandLine = preflightCmd, environment = baseEnvironment))

            # 3. Execute install recipe script
            emit(InstallEvent.Progress(toolId, "Executing ${manifest.name} install recipe...", 0.55f, InstallEvent.Phase.RUNNING_INSTALLER))
            val script = manifest.installScript?.trimIndent()
                ?: error("Tool ${manifest.id} has no valid install steps (installSteps)")

            var result = executeAndReport(
                linuxRuntime.execute(
                    ShellCommand(
                        commandLine = script,
                        environment = baseEnvironment,
                        timeoutMs = 15 * 60 * 1000L,
                    ),
                ),
            )

            # If dpkg interrupted, updates corrupted, or lock issues, auto deep-clean and retry once
            if (!result.isSuccess && (
                result.stderr.contains("dpkg was interrupted") ||
                result.stdout.contains("dpkg was interrupted") ||
                result.stderr.contains("parsing file '/var/lib/dpkg/updates") ||
                result.stdout.contains("parsing file '/var/lib/dpkg/updates") ||
                result.stderr.contains("Could not get lock") ||
                result.stderr.contains("is locked")
            )) {
                emit(InstallEvent.Progress(toolId, "Detected dpkg transaction corruption or leftover locks, auto-repairing and retrying...", 0.60f, InstallEvent.Phase.RUNNING_INSTALLER))
                val fixCmd = "rm -rf /var/lib/dpkg/updates/* /var/lib/dpkg/lock* /var/lib/apt/lists/lock /var/cache/apt/archives/lock 2>/dev/null || true; DEBIAN_FRONTEND=noninteractive dpkg --configure -a; DEBIAN_FRONTEND=noninteractive apt-get --fix-broken install -y 2>/dev/null || true"
                executeAndReport(linuxRuntime.execute(ShellCommand(commandLine = fixCmd, environment = baseEnvironment, timeoutMs = 120_000L)))
                result = executeAndReport(
                    linuxRuntime.execute(
                        ShellCommand(
                            commandLine = script,
                            environment = baseEnvironment,
                            timeoutMs = 15 * 60 * 1000L,
                        ),
                    ),
                )
            }

            if (!result.isSuccess) {
                error(result.stderr.ifBlank { result.stdout }.ifBlank { "Install recipe execution failed" })
            }

            # 4. Create command entry symlinks
            emit(InstallEvent.Progress(toolId, "Generating command entry links...", 0.80f, InstallEvent.Phase.VERIFYING_INSTALLATION))
            val links = if (manifest.commandLinks.isNotEmpty()) {
                manifest.commandLinks
            } else {
                listOf(manifest.id)
            }

            for (linkName in links) {
                val targetPath = "$toolDir/bin/$linkName"
                val linkRes = toolCommandLinker.link(linkName, targetPath, baseEnvironment)
                if (!linkRes.isSuccess) {
                    val fallbackTarget = "/usr/bin/$linkName"
                    toolCommandLinker.link(linkName, fallbackTarget, baseEnvironment)
                }
            }

            # 5. Verify installation
            emit(InstallEvent.Progress(toolId, "Verifying installation result...", 0.90f, InstallEvent.Phase.VERIFYING_INSTALLATION))
            val verifyCmd = manifest.verifyCommand ?: "${links.first()} --version"
            val versionResult = executeAndReport(
                linuxRuntime.execute(ShellCommand(commandLine = verifyCmd, environment = baseEnvironment, timeoutMs = 60_000L)),
            )
            if (!versionResult.isSuccess) {
                # Verify command timeout (exit 124) suggests toolchain corrupted —
                # e.g., java launcher in exec loop: burns CPU, zero output, killed by timeout after 60s.
                # Must NOT treat "file exists/executable bit" as success, else poisoned state
                # gets marked INSTALLED and triggers repeated reinstalls.
                if (versionResult.exitCode == VERIFY_TIMEOUT_EXIT_CODE) {
                    error(
                        "Verify command timed out hanging ($verifyCmd), likely install artifact corrupted (e.g., launcher exec loop), rolled back entire transaction",
                    )
                }
                # Fallback: if verify command fails fast with real exit code (e.g., tool doesnt recognize --version
                # flag), but main binary is in place, deem install success and log warning,
                # avoiding blunt rollback of entire transaction over single command failure.
                val anyBinaryExists = links.any { link ->
                    val checkRes = linuxRuntime.execute(ShellCommand("test -x $toolDir/bin/$link || test -x /opt/taixu/bin/$link || test -x /usr/bin/$link", environment = baseEnvironment, timeoutMs = 5_000L))
                    checkRes.isSuccess
                }
                if (!anyBinaryExists) {
                    error("Verify command failed ($verifyCmd): ${versionResult.stderr.ifBlank { versionResult.stdout }}")
                }
            }

            # 6. Auto-clean heavy install package cache in sandbox (/opt/taixu/imports/<toolId>/archives) on verify success, freeing 1-2GB instantly
            if (manifest.source == "LOCAL") {
                emit(InstallEvent.Progress(toolId, "Cleaning install package cache...", 0.95f, InstallEvent.Phase.VERIFYING_INSTALLATION))
                linuxRuntime.execute(ShellCommand("rm -rf /opt/taixu/imports/$toolId/archives 2>/dev/null || true", environment = baseEnvironment))
            }

            val versionOutput = versionResult.stdout.trim().lineSequence().firstOrNull()?.takeIf { it.isNotBlank() } ?: manifest.version
            emit(InstallEvent.Completed(toolId, versionOutput))
        } catch (cancellation: CancellationException) {
            if (manifest.source == "LOCAL") {
                runCatching {
                    linuxRuntime.execute(ShellCommand("rm -rf /opt/taixu/imports/$toolId 2>/dev/null || true"))
                }
            }
            throw cancellation
        } catch (throwable: Throwable) {
            if (manifest.source == "LOCAL") {
                runCatching {
                    linuxRuntime.execute(ShellCommand("rm -rf /opt/taixu/imports/$toolId 2>/dev/null || true"))
                }
            }
            emit(InstallEvent.RolledBack(toolId))
            emit(InstallEvent.Failed(toolId, throwable.message ?: "Install failed"))
        }
    }

    override suspend fun launch(): CommandResult = execute(manifest.launchCommand ?: manifest.id)

    override suspend fun verify(): CommandResult = execute(manifest.verifyCommand ?: "${manifest.id} --version")

    override suspend fun interactiveSessionConfig(): SessionConfig = SessionConfig(
        commandLine = "exec ${manifest.launchCommand ?: manifest.id}",
        environment = runtimeEnvironment(),
        allowSttyResize = false,
    )

    override suspend fun startService(): ManagedProcess = linuxRuntime.startBackground(
        id = "${toolId}-service",
        command = ShellCommand(
            commandLine = manifest.launchCommand ?: manifest.id,
            environment = runtimeEnvironment(),
        ),
        toolId = toolId,
        type = ProcessType.SERVICE,
    )

    override suspend fun uninstall(deleteData: Boolean): ToolActionResult {
        val toolDir = ToolLayout.toolDirectory(toolId)
        val toolDataDir = ToolLayout.toolDataDirectory(toolId)
        val links = if (manifest.commandLinks.isNotEmpty()) manifest.commandLinks else listOf(manifest.id)

        for (link in links) {
            toolCommandLinker.remove(link, runtimeEnvironment())
        }

        val customUninstall = manifest.uninstallScript
        if (!customUninstall.isNullOrBlank()) {
            linuxRuntime.execute(ShellCommand(customUninstall, environment = runtimeEnvironment()))
        }

        val dataCleanup = if (deleteData) " && rm -rf $toolDataDir" else ""
        val directoryResult = linuxRuntime.execute(ShellCommand("rm -rf $toolDir$dataCleanup"))

        return ToolActionResult(
            success = directoryResult.isSuccess,
            message = if (directoryResult.isSuccess) "Uninstall complete" else directoryResult.stderr.ifBlank { "Uninstall failed" },
        )
    }

    private suspend fun acquire(name: RuntimeName, toolId: String, constraint: String? = null) {
        val result = dependencyManager.acquire(RuntimeRequirement(name, constraint), toolId)
        if (result.isFailure) error(result.errorOrNull()?.message ?: "Dependency install failed: $name")
    }

    private suspend fun execute(command: String) = linuxRuntime.execute(
        ShellCommand(command, environment = runtimeEnvironment()),
    )

    private suspend fun runtimeEnvironment(localPayload: String? = null): Map<String, String> {
        val toolDir = ToolLayout.toolDirectory(toolId)
        val runtimePath = "/root/.local/bin:/opt/taixu/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
        val declaredPath = manifest.environment["PATH"]
        val effectivePath = declaredPath
            ?.replace("\${PATH}", runtimePath)
            ?.replace("\$PATH", runtimePath)
            ?: runtimePath
        val payloadPath = localPayload ?: if (manifest.source == "LOCAL") "/opt/taixu/imports/$toolId" else null
        return providerManager.environment().filterKeys { it != "PATH" } +
            manifest.environment.filterKeys { it != "PATH" } +
            mapOf(
                "TAIXU_TOOL_ID" to toolId,
                "TAIXU_TOOL_DIR" to toolDir,
                "TAIXU_TOOL_DATA" to ToolLayout.toolDataDirectory(toolId),
                "npm_config_prefix" to toolDir,
                "NPM_CONFIG_PREFIX" to toolDir,
                "PATH" to "$toolDir/bin:$effectivePath",
            ) + payloadPath?.let { mapOf("TAIXU_PLUGIN_PAYLOAD" to it) }.orEmpty()
    }

    private fun localUnzipCompatibilityCommand(): String = """
        if ! command -v unzip >/dev/null 2>&1; then
            printf '%s\n' \
                '#!/bin/sh' \
                'set -eu' \
                'archive=' \
                'dest=.' \
                'jar_bin="${'$'}{JAVA_HOME:-}/bin/jar"' \
                'if [ ! -x "${'$'}{jar_bin}" ]; then' \
                '  for candidate in /opt/taixu/toolchains/android/jdk/bin/jar /usr/bin/jar /usr/lib/jvm/default-java/bin/jar; do' \
                '    if [ -x "${'$'}{candidate}" ]; then jar_bin="${'$'}{candidate}"; break; fi' \
                '  done' \
                'fi' \
                'while [ "${'$'}#" -gt 0 ]; do' \
                '  case "${'$'}1" in' \
                '    -q|-qq|-o) shift ;;' \
                '    -d) dest="${'$'}2"; shift 2 ;;' \
                '    -*) shift ;;' \
                '    *) archive="${'$'}1"; shift ;;' \
                '  esac' \
                'done' \
                '[ -n "${'$'}archive" ] || exit 2' \
                '[ -x "${'$'}{jar_bin}" ] || { echo "JDK jar unavailable for ZIP extraction" >&2; exit 127; }' \
                'mkdir -p "${'$'}dest"' \
                '(cd "${'$'}dest" && "${'$'}{jar_bin}" xf "${'$'}archive")' \
                > "${'$'}TAIXU_TOOL_DIR/bin/unzip"
            chmod 755 "${'$'}TAIXU_TOOL_DIR/bin/unzip"
        fi
    """.trimIndent()

    private suspend fun kotlinx.coroutines.flow.FlowCollector<InstallEvent>.executeAndReport(
        result: CommandResult,
    ): CommandResult {
        (result.stdout.lineSequence() + result.stderr.lineSequence())
            .filter { it.isNotBlank() }
            .forEach { line ->
                val scriptedProgress = INSTALLER_PROGRESS_PATTERN.matchEntire(line.trim())
                if (scriptedProgress != null) {
                    val relativeProgress = scriptedProgress.groupValues[1].toFloatOrNull()
                        ?.div(100f)
                        ?.coerceIn(0f, 1f)
                        ?: 0f
                    emit(
                        InstallEvent.Progress(
                            toolId = toolId,
                            message = scriptedProgress.groupValues[2],
                            progress = INSTALLER_PROGRESS_START + relativeProgress * INSTALLER_PROGRESS_SPAN,
                            phase = InstallEvent.Phase.RUNNING_INSTALLER,
                        ),
                    )
                } else {
                    emit(InstallEvent.Output(toolId, line))
                }
            }
        return result
    }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<InstallEvent>.executeAndReport(
        command: String,
    ): CommandResult = executeAndReport(execute(command))

    private fun checkReady() = check(linuxRuntime.state.value is top.wkbin.taixu.core.model.RuntimeState.Ready) {
        "Linux Runtime not ready, please initialize Linux first"
    }

    private companion object {
        const val LOCAL_COPY_PROGRESS_START = 0.02f
        const val LOCAL_COPY_PROGRESS_SPAN = 0.12f
        const val INSTALLER_PROGRESS_START = 0.55f
        const val INSTALLER_PROGRESS_SPAN = 0.23f

        /** Unified exit code for process timeout kill (see ProcessShellExecutor.TIMEOUT_EXIT_CODE). */
        const val VERIFY_TIMEOUT_EXIT_CODE = 124
        val INSTALLER_PROGRESS_PATTERN = Regex("""\[TAIXU_PROGRESS:(\d{1,3})]\s+(.+)""")
    }
}
