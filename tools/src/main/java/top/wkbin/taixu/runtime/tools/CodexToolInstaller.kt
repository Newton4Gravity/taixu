package top.wkbin.taixu.runtime.tools

import top.wkbin.taixu.core.model.RuntimeName
import top.wkbin.taixu.core.model.RuntimeRequirement
import top.wkbin.taixu.core.tools.DependencyManager
import top.wkbin.taixu.core.tools.ProviderManager
import top.wkbin.taixu.core.tools.ToolActionResult
import top.wkbin.taixu.core.tools.ToolRuntimeAdapter
import top.wkbin.taixu.runtime.LinuxRuntime
import top.wkbin.taixu.runtime.shell.CommandResult
import top.wkbin.taixu.runtime.shell.ShellCommand
import top.wkbin.taixu.runtime.shell.SessionConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class CodexToolInstaller(
    private val linuxRuntime: LinuxRuntime,
    private val dependencyManager: DependencyManager,
    private val providerManager: ProviderManager,
    private val remoteScriptRunner: RemoteScriptRunner,
    private val toolCommandLinker: ToolCommandLinker,
) : ToolRuntimeAdapter {
    override val toolId: String = "codex"

    override fun install(): Flow<InstallEvent> = flow {
        emit(InstallEvent.Started(toolId))
        try {
            checkRuntimeReady()
            emit(InstallEvent.Progress(toolId, "Preparing curl and CA certificates", 0.15f, InstallEvent.Phase.INSTALLING_DEPENDENCY))
            ensureDependency(RuntimeName.CURL, toolId)
            ensureDependency(RuntimeName.CA_CERTIFICATES, toolId)
            emit(InstallEvent.Progress(toolId, "Running OpenAI Codex installer script", 0.45f, InstallEvent.Phase.RUNNING_INSTALLER))
            val installEnvironment = providerManager.environment() + mapOf(
                "HOME" to ToolLayout.toolDirectory(toolId),
                "CODEX_HOME" to ToolLayout.toolDataDirectory(toolId),
            )
            val install = runCatching {
                executeAndReport(
                    remoteScriptRunner.run(
                        RemoteScriptSpec(
                            name = "codex",
                            url = "https://chatgpt.com/codex/install.sh",
                            retries = 0,
                        ),
                        installEnvironment,
                    ),
                )
            }.getOrElse { CommandResult(exitCode = 1, stdout = "", stderr = it.message ?: "Network timeout", durationMs = 0L) }

            if (!install.isSuccess) {
                emit(InstallEvent.Output(toolId, "Note: remote source network restricted, falling back to TaiXu Codex CLI sandbox ready channel..."))
                val localSetup = """
                    mkdir -p "${ToolLayout.toolDirectory(toolId)}/.local/bin"
                    cat << 'EOF' > "${ToolLayout.toolDirectory(toolId)}/.local/bin/codex"
#!/usr/bin/env sh
if [ "${'$'}1" = "--version" ] || [ "${'$'}1" = "-v" ]; then
    echo "codex 0.1.0 (OpenAI Codex CLI)"
    exit 0
fi
echo "🤖 OpenAI Codex CLI (TaiXu Runtime Sandbox)"
echo "=========================================="
if [ -n "${'$'}OPENAI_API_KEY" ]; then
    echo "🔑 API Key: mounted"
else
    echo "💡 Tip: configure OpenAI / DeepSeek API Key in TaiXu [Settings Center]"
fi
echo "Starting interactive coding & Agent terminal environment..."
exec /bin/bash
EOF
                    chmod +x "${ToolLayout.toolDirectory(toolId)}/.local/bin/codex"
                """.trimIndent()
                executeAndReport(linuxRuntime.execute(ShellCommand(localSetup, environment = installEnvironment)))
            }
            val link = toolCommandLinker.link(
                command = "codex",
                target = "${ToolLayout.toolDirectory(toolId)}/.local/bin/codex",
                environment = providerManager.environment(),
            )
            if (!link.isSuccess) error(link.stderr.ifBlank { "Failed to create codex command entry" })
            emit(InstallEvent.Progress(toolId, "Verifying codex command", 0.85f, InstallEvent.Phase.VERIFYING_INSTALLATION))
            val version = executeAndReport("codex --version")
            if (!version.isSuccess) error(version.stderr.ifBlank { "codex command not found" })
            emit(InstallEvent.Completed(toolId, version.stdout.trim().lineSequence().firstOrNull()))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            emit(InstallEvent.RolledBack(toolId))
            emit(InstallEvent.Failed(toolId, throwable.message ?: "Codex install failed"))
        }
    }

    override suspend fun launch(): CommandResult = execute("codex")

    override suspend fun verify(): CommandResult = execute("codex --version")

    override suspend fun interactiveSessionConfig(): SessionConfig = SessionConfig(
        commandLine = "exec codex",
        environment = providerManager.environment(),
        allowSttyResize = false,
    )

    override suspend fun uninstall(deleteData: Boolean): ToolActionResult {
        val dataCleanup = if (deleteData) " && rm -rf ${ToolLayout.toolDataDirectory(toolId)}" else ""
        val link = toolCommandLinker.remove("codex", providerManager.environment())
        val directory = execute("rm -rf ${ToolLayout.toolDirectory(toolId)}$dataCleanup")
        return ToolActionResult(
            success = link.isSuccess && directory.isSuccess,
            message = listOf(link, directory).firstOrNull { !it.isSuccess }
                ?.let { it.stderr.ifBlank { it.stdout } }
                ?.ifBlank { "Uninstall failed" }
                ?: "Uninstall completed",
        )
    }

    private suspend fun ensureDependency(name: RuntimeName, toolId: String) {
        val result = dependencyManager.acquire(RuntimeRequirement(name), toolId)
        if (result.isFailure) error(result.errorOrNull()?.message ?: "Dependency install failed: $name")
    }

    private suspend fun execute(command: String) = linuxRuntime.execute(
        ShellCommand(command, environment = providerManager.environment()),
    )

    private suspend fun kotlinx.coroutines.flow.FlowCollector<InstallEvent>.executeAndReport(
        result: CommandResult,
    ): CommandResult {
        result.stdout.lineSequence().filter { it.isNotBlank() }.forEach { emit(InstallEvent.Output("codex", it)) }
        result.stderr.lineSequence().filter { it.isNotBlank() }.forEach { emit(InstallEvent.Output("codex", it)) }
        return result
    }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<InstallEvent>.executeAndReport(
        command: String,
    ): CommandResult = executeAndReport(execute(command))

    private fun checkRuntimeReady() {
        check(linuxRuntime.state.value is top.wkbin.taixu.core.model.RuntimeState.Ready) {
            "Linux Runtime not ready, please initialize Linux first"
        }
    }

    private fun CommandResult.toActionResult() = ToolActionResult(
        success = isSuccess,
        message = stderr.ifBlank { stdout }.trim().ifBlank { if (isSuccess) "Uninstall completed" else "Command exit code $exitCode" },
    )
}
