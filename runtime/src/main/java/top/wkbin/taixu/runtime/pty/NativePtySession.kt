package top.wkbin.taixu.runtime.pty

import top.wkbin.taixu.runtime.shell.LinuxSession
import top.wkbin.taixu.runtime.shell.SessionConfig
import top.wkbin.taixu.runtime.shell.TerminalOutput
import top.wkbin.taixu.runtime.shell.TerminalStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Real PTY session: JNI forkpty opens master/slave, command execs directly on slave
 * (setsid + controlling terminal), app reads/writes via master fd. Matches Termux PTY semantics:
 * Ctrl+C / job control / SIGWINCH resize / raw mode all work.
 */
class NativePtySession(
    command: List<String>,
    hostEnvironment: Map<String, String>,
    private val config: SessionConfig,
    private val cleanupCallback: suspend () -> Unit = {},
) : LinuxSession {

    private val pair: IntArray = NativePty.openAndExec(
        command.toTypedArray(),
        hostEnvironment.entries.map { "${it.key}=${it.value}" }.toTypedArray(),
        "/",
        config.columns,
        config.rows,
    )
    private val masterFd: Int = pair[0]
    private val childPid: Int = pair[1]

    private val outputChannel = Channel<TerminalOutput>(OUTPUT_BUFFER_CAPACITY)
    private val closed = AtomicBoolean(false)
    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val utf8Decoder = IncrementalUtf8Decoder(8192)

    private val readerJob: Job = sessionScope.launch {
        val buffer = ByteArray(8192)
        try {
            while (!closed.get()) {
                val n = NativePty.readFd(masterFd, buffer)
                if (n < 0) break
                if (n > 0) {
                    val text = decodeUtf8(buffer, n)
                    if (text.isNotEmpty()) {
                        outputChannel.send(TerminalOutput(TerminalStream.STDOUT, text))
                    }
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (io: IOException) {
            // fd closed by another thread during close, treat as EOF
        }
        decodeUtf8End().takeIf { it.isNotEmpty() }?.let {
            outputChannel.send(TerminalOutput(TerminalStream.STDOUT, it))
        }
        NativePty.waitPid(childPid)
        outputChannel.close()
    }

    /** Streaming UTF-8 decode to avoid multi-byte char truncation across reads. */
    private fun decodeUtf8(buffer: ByteArray, length: Int): String {
        return utf8Decoder.decode(buffer, length)
    }

    private fun decodeUtf8End(): String = utf8Decoder.finish()

    override val output: Flow<TerminalOutput> = outputChannel.receiveAsFlow()

    override val pid: Long? get() = childPid.toLong()

    override val isAlive: Boolean
        get() = !closed.get() && NativePty.killPid(childPid, 0) == 0

    override suspend fun write(data: ByteArray) = withContext(Dispatchers.IO) {
        if (!closed.get()) {
            var offset = 0
            while (offset < data.size) {
                val written = NativePty.writeFd(masterFd, data, offset, data.size - offset)
                if (written <= 0) break
                offset += written
            }
        }
    }

    override suspend fun resize(columns: Int, rows: Int) = withContext(Dispatchers.IO) {
        if (!closed.get()) {
            NativePty.resizeFd(masterFd, columns.coerceIn(20, 400), rows.coerceIn(5, 200))
        }
    }

    override suspend fun interrupt() = write(byteArrayOf(3))

    override suspend fun close() = withContext(Dispatchers.IO) {
        if (closed.compareAndSet(false, true)) {
            readerJob.cancel()
            sessionScope.cancel()
            // SIGHUP for graceful shell exit; proot --kill-on-exit handles entire process tree.
            NativePty.killPid(childPid, 1)
            // Hard kill fallback: setsid makes -pid cover entire session process group.
            NativePty.killPid(childPid, 9)
            NativePty.waitPid(childPid)
            NativePty.closeFd(masterFd)
            outputChannel.close()
            runCatching { cleanupCallback() }
        }
    }

    companion object {
        // Output buffer: ~8KB/frame × 1024 ≈ 8MB cap, send backpressure prevents data loss under high load.
        const val OUTPUT_BUFFER_CAPACITY = 1024
    }
}
