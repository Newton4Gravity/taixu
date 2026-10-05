package top.wkbin.taixu.runtime

import top.wkbin.taixu.core.common.result.AppResult
import top.wkbin.taixu.core.model.InstalledDistro
import top.wkbin.taixu.core.model.RuntimeState
import top.wkbin.taixu.runtime.shell.CommandResult
import top.wkbin.taixu.runtime.shell.InteractiveLaunchSpec
import top.wkbin.taixu.runtime.shell.LinuxSession
import top.wkbin.taixu.runtime.shell.ManagedProcess
import top.wkbin.taixu.runtime.shell.ProcessType
import top.wkbin.taixu.runtime.shell.SessionConfig
import top.wkbin.taixu.runtime.shell.ShellCommand
import java.io.File
import kotlinx.coroutines.flow.StateFlow

interface LinuxRuntime {
    val state: StateFlow<RuntimeState>
    val activeDistroId: StateFlow<String>
    val installedDistros: StateFlow<List<InstalledDistro>>

    suspend fun initialize(request: RuntimeInstallRequest = RuntimeInstallRequest("ubuntu")): AppResult<Unit>
    suspend fun restoreInstalledState(): Boolean
    suspend fun updateRootfs(distroId: String? = null): AppResult<Unit>
    suspend fun checkRootfsUpdate(distroId: String? = null): AppResult<RootfsUpdateInfo>
    suspend fun healthCheck(distroId: String? = null): RuntimeHealth

    suspend fun switchActiveDistro(distroId: String): AppResult<Unit>
    suspend fun installDistro(request: RuntimeInstallRequest, onProgress: suspend (DownloadProgress) -> Unit = {}): AppResult<Unit>
    suspend fun importDistro(request: RuntimeInstallRequest, archive: File): AppResult<Unit> =
        AppResult.Failure(top.wkbin.taixu.core.common.result.AppError(
            top.wkbin.taixu.core.common.result.ErrorCode.INSTALLATION_FAILED,
            "Current runtime does not support RootFS import",
        ))
    suspend fun uninstallDistro(distroId: String): AppResult<Unit>
    /** Remove the active distro and its installed tools while preserving /workspace. */
    suspend fun resetSandbox(distroId: String? = null): AppResult<Unit>
    fun refreshInstalledDistros()

    suspend fun execute(command: ShellCommand, distroId: String? = null): CommandResult
    suspend fun startSession(config: SessionConfig = SessionConfig(), distroId: String? = null): LinuxSession

    /**
     * Build host argv/env for an interactive console owned by Termux TerminalSession JNI,
     * without opening our NativePty. Used by the matrix terminal UI.
     */
    suspend fun buildInteractiveLaunch(
        config: SessionConfig = SessionConfig(),
        distroId: String? = null,
    ): InteractiveLaunchSpec = error("Current runtime does not support interactive terminal launch spec")

    suspend fun startBackground(
        id: String,
        command: ShellCommand,
        toolId: String? = null,
        type: ProcessType = ProcessType.SERVICE,
        distroId: String? = null,
    ): ManagedProcess

    suspend fun stopBackground(id: String): Boolean
    fun listBackground(): List<ManagedProcess>
    suspend fun cleanupDeadBackground(): Int
    fun observeBackgroundLogs(idOrToolId: String): kotlinx.coroutines.flow.Flow<List<String>> = kotlinx.coroutines.flow.emptyFlow()
    fun getBackgroundLogs(idOrToolId: String): List<String> = emptyList()
    fun clearBackgroundLogs(idOrToolId: String) = Unit

    suspend fun shutdown()
    /** Execute file cleanup only while runtime launches and installation are excluded. */
    suspend fun withStorageCleanup(block: suspend () -> Unit) {
        error("Current runtime does not support safe storage cleanup")
    }
    fun rootfsPath(distroId: String? = null): File
    fun rootfsVersion(distroId: String? = null): String? = null
    fun workspacePath(): File
}
