package top.wkbin.taixu.runtime.tools

import top.wkbin.taixu.core.common.files.SafeFileTree
import top.wkbin.taixu.core.common.result.AppError
import top.wkbin.taixu.core.common.result.AppResult
import top.wkbin.taixu.core.common.result.ErrorCode
import top.wkbin.taixu.core.tools.ToolActionResult
import top.wkbin.taixu.core.tools.ToolRuntimeAdapter
import top.wkbin.taixu.runtime.LinuxRuntime
import top.wkbin.taixu.runtime.RuntimePathManager
import top.wkbin.taixu.runtime.shell.ShellCommand
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class HelloToolInstaller(
    private val linuxRuntime: LinuxRuntime,
    private val pathManager: RuntimePathManager,
) : ToolRuntimeAdapter {
    override val toolId: String = "hello-tool"

    override fun install(): Flow<InstallEvent> = flow {
        emit(InstallEvent.Started(toolId))
        val distroId = linuxRuntime.activeDistroId.value
        val targetDir = File(pathManager.taixuToolsDir(distroId), toolId)
        val stagingDir = File(pathManager.taixuRootDir(distroId), ".staging-$toolId")
        try {
            if (linuxRuntime.state.value !is top.wkbin.taixu.core.model.RuntimeState.Ready) {
                throw IllegalStateException("Linux Runtime not ready, please initialize Linux first")
            }
            emit(InstallEvent.Progress(toolId, "Creating install transaction", 0.25f, InstallEvent.Phase.PREPARING))
            SafeFileTree.delete(stagingDir)
            val binDir = File(stagingDir, "bin")
            binDir.mkdirs()
            val executable = File(binDir, "hello")
            executable.writeText("#!/bin/sh\necho 'Hello TaiXu'\n")
            executable.setExecutable(true, false)
            emit(InstallEvent.Progress(toolId, "Verifying hello command", 0.85f, InstallEvent.Phase.VERIFYING_INSTALLATION))
            SafeFileTree.delete(targetDir)
            if (!stagingDir.renameTo(targetDir)) {
                throw IllegalStateException("Failed to commit install transaction")
            }
            val result = linuxRuntime.execute(ShellCommand(ToolLayout.toolBinary(toolId, "hello")))
            if (!result.isSuccess || result.stdout.trim() != "Hello TaiXu") {
                throw IllegalStateException("Verification failed: ${result.stderr.ifBlank { "Output mismatch" }}")
            }
            emit(InstallEvent.Completed(toolId))
        } catch (cancellation: CancellationException) {
            SafeFileTree.delete(stagingDir)
            SafeFileTree.delete(targetDir)
            throw cancellation
        } catch (throwable: Throwable) {
            SafeFileTree.delete(stagingDir)
            SafeFileTree.delete(targetDir)
            emit(InstallEvent.RolledBack(toolId))
            emit(InstallEvent.Failed(toolId, throwable.message ?: "Install failed"))
        }
    }

    override suspend fun uninstall(deleteData: Boolean): ToolActionResult = runCatching {
        SafeFileTree.delete(File(pathManager.taixuToolsDir(linuxRuntime.activeDistroId.value), "hello-tool"))
    }.fold(
        onSuccess = { ToolActionResult(true, "Uninstall completed") },
        onFailure = { ToolActionResult(false, "Failed to uninstall hello-tool: ${it.message.orEmpty()}") },
    )

    override suspend fun launch() = linuxRuntime.execute(
        ShellCommand(ToolLayout.toolBinary(toolId, "hello")),
    )

    override suspend fun verify() = launch()
}
