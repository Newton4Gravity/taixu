package top.wkbin.taixu.runtime.pty

import top.wkbin.taixu.runtime.shell.LinuxSession
import top.wkbin.taixu.runtime.shell.ProcessLinuxSession
import top.wkbin.taixu.runtime.shell.SessionConfig

interface PtyManager {
    /** True when the JNI forkpty backend is loadable on this device. */
    val nativeAvailable: Boolean

    /** Debian `script`-based backend (fallback). */
    suspend fun open(
        command: List<String>,
        hostEnvironment: Map<String, String>,
        config: SessionConfig,
        resize: (suspend (columns: Int, rows: Int) -> Unit)? = null,
        cleanup: suspend () -> Unit = {},
    ): LinuxSession

    /** Real PTY backend: JNI forkpty, command execs directly on pty slave. */
    suspend fun openNative(
        command: List<String>,
        hostEnvironment: Map<String, String>,
        config: SessionConfig,
        cleanup: suspend () -> Unit = {},
    ): LinuxSession
}

/**
 * Android-compatible PTY backend using Debian's util-linux `script` command.
 * It records the PTY slave path so resize can be applied with `stty -F`.
 * Used only when the JNI forkpty library cannot be loaded.
 */
class ScriptPtyManager() : PtyManager {
    override val nativeAvailable: Boolean get() = false

    override suspend fun open(
        command: List<String>,
        hostEnvironment: Map<String, String>,
        config: SessionConfig,
        resize: (suspend (columns: Int, rows: Int) -> Unit)?,
        cleanup: suspend () -> Unit,
    ): LinuxSession = ProcessLinuxSession(
        command = command,
        hostEnvironment = hostEnvironment,
        allowSttyResize = config.allowSttyResize,
        resizeCallback = resize,
        cleanupCallback = cleanup,
    )

    override suspend fun openNative(
        command: List<String>,
        hostEnvironment: Map<String, String>,
        config: SessionConfig,
        cleanup: suspend () -> Unit,
    ): LinuxSession = error("native PTY backend unavailable")
}

/** Primary backend: prefers JNI forkpty, falls back to script backend when library missing. */
class NativePtyManager(
    private val scriptFallback: ScriptPtyManager,
) : PtyManager {
    override val nativeAvailable: Boolean get() = NativePty.tryLoad()

    override suspend fun open(
        command: List<String>,
        hostEnvironment: Map<String, String>,
        config: SessionConfig,
        resize: (suspend (columns: Int, rows: Int) -> Unit)?,
        cleanup: suspend () -> Unit,
    ): LinuxSession = scriptFallback.open(command, hostEnvironment, config, resize, cleanup)

    override suspend fun openNative(
        command: List<String>,
        hostEnvironment: Map<String, String>,
        config: SessionConfig,
        cleanup: suspend () -> Unit,
    ): LinuxSession {
        if (!NativePty.tryLoad()) {
            throw IllegalStateException("JNI forkpty backend unavailable, use compatibility backend")
        }
        return NativePtySession(command, hostEnvironment, config, cleanup)
    }
}
