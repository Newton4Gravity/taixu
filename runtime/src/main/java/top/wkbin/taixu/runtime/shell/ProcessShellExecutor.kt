package top.wkbin.taixu.runtime.shell

import top.wkbin.taixu.runtime.RuntimePathManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

class ProcessShellExecutor(
    private val pathManager: RuntimePathManager,
) : ShellExecutor {

    override suspend fun execute(
        command: List<String>,
        workingDirectory: File?,
        environment: Map<String, String>,
        timeoutMs: Long,
        onOutput: ((String) -> Unit)?,
    ): CommandResult = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        val process = ProcessBuilder(command)
            .apply {
                workingDirectory?.let { directory(it) }
                // Keep the PRoot host environment deterministic. In particular,
                // never inherit Termux's LD_PRELOAD or a stale PROOT_LOADER.
                environment().clear()
                environment().putAll(pathManager.hostProcessEnvironment())
                environment().putAll(environment)
            }
            .redirectErrorStream(false)
            .start()

        // 🌟 Key fix: proactively close child process stdin to prevent interactive scripts/commands from blocking indefinitely waiting for keyboard input
        runCatching { process.outputStream.close() }

        val stdoutDeferred = async(Dispatchers.IO) {
            readFully(process.inputStream, onOutput)
        }
        val stderrDeferred = async(Dispatchers.IO) {
            readFully(process.errorStream, onOutput)
        }

        try {
            val exitCode = withTimeout(timeoutMs) {
                runInterruptible(Dispatchers.IO) { process.waitFor() }
            }
            val (stdout, stderr) = listOf(stdoutDeferred, stderrDeferred).awaitAll().let { values ->
                values[0] to values[1]
            }
            CommandResult(
                exitCode = exitCode,
                stdout = stdout,
                stderr = stderr,
                durationMs = System.currentTimeMillis() - startedAt,
            )
        } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
            process.destroyForcibly()
            // Wait for process tree to truly die: after PRoot force-kill, ptraced npm/node are asynchronously
            // reaped by kernel; if rollback deletes dirs immediately, may hit still-writing leftover processes.
            runInterruptible(Dispatchers.IO) {
                process.waitFor(PROCESS_TEARDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            }
            // Preserve output produced before the timeout. Cancelling the
            // readers here discarded the only useful diagnostic from long
            // setup scripts (APT/Gradle/Flutter), leaving just the timeout
            // sentence in the installation dialog.
            val partialStdout = runCatching {
                withTimeoutOrNull(PROCESS_TEARDOWN_TIMEOUT_MS) { stdoutDeferred.await() }.orEmpty()
            }.getOrDefault("")
            val partialStderr = runCatching {
                withTimeoutOrNull(PROCESS_TEARDOWN_TIMEOUT_MS) { stderrDeferred.await() }.orEmpty()
            }.getOrDefault("")
            CommandResult(
                exitCode = TIMEOUT_EXIT_CODE,
                stdout = partialStdout,
                stderr = buildString {
                    if (partialStderr.isNotBlank()) {
                        append(partialStderr.trimEnd())
                        append('\n')
                    }
                    append("Command timed out after ${timeoutMs}ms")
                },
                durationMs = System.currentTimeMillis() - startedAt,
            )
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            // User cancelled build: force-kill PRoot process tree to prevent Gradle from running in background
            process.destroyForcibly()
            throw cancellation
        } finally {
            process.destroy()
        }
    }

    private suspend fun readFully(
        stream: java.io.InputStream,
        onOutput: ((String) -> Unit)? = null,
    ): String = try {
        stream.use { input ->
            val kept = ByteArrayOutputStream(MAX_CAPTURE_BYTES)
            val buffer = ByteArray(READ_BUFFER_BYTES)
            var totalBytes = 0L
            // 🔒 Drain loop must never be killed by consumer exception: stdout/stderr two reader coroutines
            // concurrently call same onOutput; if callback throws uncaught exception (e.g., shared
            // StringBuilder data race), this coroutine dies -> host pipe unread -> child blocks on
            // full kernel pipe buffer (~64KB) -> write() blocks forever -> entire build tree hangs,
            // logs frozen halfway. Hence callback exceptions caught here: consecutive failures just
            // disable callback, drain must continue.
            var callback = onOutput
            var consecutiveCallbackFailures = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                totalBytes += read
                val remaining = MAX_CAPTURE_BYTES - kept.size()
                if (remaining > 0) kept.write(buffer, 0, minOf(read, remaining))
                if (callback != null && read > 0) {
                    val chunk = String(buffer, 0, read, Charsets.UTF_8)
                    try {
                        callback(chunk)
                        consecutiveCallbackFailures = 0
                    } catch (cancellation: kotlinx.coroutines.CancellationException) {
                        throw cancellation
                    } catch (t: Throwable) {
                        consecutiveCallbackFailures++
                        if (consecutiveCallbackFailures >= MAX_CALLBACK_FAILURES) {
                            // On sustained callback crash, give up streaming (CommandResult still retains full output),
                            // but MUST NOT stop reading pipe — that would hang running Gradle/Flutter.
                            callback = null
                        }
                    }
                }
            }
            buildString {
                append(kept.toString(Charsets.UTF_8.name()))
                if (totalBytes > kept.size()) {
                    append("\n\n[Process output truncated: total ")
                    append(totalBytes)
                    append(" bytes, only first ")
                    append(kept.size())
                    append(" bytes]")
                }
            }
        }
    } catch (cancellation: kotlinx.coroutines.CancellationException) {
        throw cancellation
    } catch (io: java.io.IOException) {
        // Timeout teardown closes the process streams from this thread while a
        // reader is blocked in read(); Android surfaces that as
        // InterruptedIOException. Treat it as EOF: the timeout already produced
        // the authoritative CommandResult, and the reader failure must not
        // override it through structured-concurrency propagation.
        ""
    }

    private companion object {
        const val TIMEOUT_EXIT_CODE = 124
        const val PROCESS_TEARDOWN_TIMEOUT_MS = 1_000L
        const val MAX_CAPTURE_BYTES = 2 * 1024 * 1024
        const val READ_BUFFER_BYTES = 16 * 1024

        /** Disable streaming callback after this many consecutive onOutput exceptions; retain drain and full output capture. */
        const val MAX_CALLBACK_FAILURES = 8
    }
}
