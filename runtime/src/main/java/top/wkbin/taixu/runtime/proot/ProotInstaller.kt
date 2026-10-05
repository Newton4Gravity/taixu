package top.wkbin.taixu.runtime.proot

import top.wkbin.taixu.core.common.logging.AppLogger
import top.wkbin.taixu.core.common.result.AppError
import top.wkbin.taixu.core.common.result.AppResult
import top.wkbin.taixu.core.common.result.ErrorCode
import top.wkbin.taixu.runtime.ElfInspector
import top.wkbin.taixu.runtime.RuntimePathManager
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Verifies the APK-bundled PRoot runtime.
 *
 * Android 10+ does not allow this target-SDK-34 app to execute code downloaded
 * into filesDir. Both the tracer and its external loader therefore have to be
 * packaged as extracted native libraries; the selected Linux RootFS is downloaded through OCI.
 */
class ProotInstaller(
    private val pathManager: RuntimePathManager,
    private val elfInspector: ElfInspector,
    private val logger: AppLogger,
) {
    suspend fun install(): AppResult<File> = withContext(Dispatchers.IO) {
        try {
            pathManager.ensureDirectories()
            val proot = pathManager.bundledProotFile
            val loader = pathManager.bundledProotLoaderFile
            require(proot.isFile && proot.length() > MIN_RUNTIME_COMPONENT_BYTES) {
                "APK missing ARM64 PRoot main binary (libproot.so)"
            }
            require(
                loader.isFile &&
                    loader.length() > MIN_RUNTIME_COMPONENT_BYTES &&
                    loader.length() <= MAX_LOADER_BYTES,
            ) {
                "APK missing PRoot ARM64 loader (libproot-loader.so). " +
                    "Termux PRoot has no built-in loader; can't just copy proot main binary."
            }
            require(loader.canExecute()) {
                "PRoot loader not executable; must extract from APK nativeLibraryDir, not filesDir"
            }
            elfInspector.requireAarch64(proot)
            elfInspector.requireAarch64(loader)
            validateExecutable(proot)
            logger.i("APK-bundled PRoot tracer and loader validated")
            AppResult.Success(proot)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            logger.e("PRoot runtime validation failed", throwable)
            AppResult.Failure(
                AppError(
                    code = ErrorCode.INSTALLATION_FAILED,
                    message = "PRoot runtime component validation failed: ${throwable.message ?: "unknown error"}",
                    cause = throwable,
                ),
            )
        }
    }

    private fun validateExecutable(file: File) {
        val process = try {
            ProcessBuilder(file.absolutePath, "--version")
                .apply {
                    environment().clear()
                    environment().putAll(pathManager.hostProcessEnvironment())
                }
                .redirectErrorStream(true)
                .start()
        } catch (throwable: Throwable) {
            throw IllegalStateException(
                "Failed to start ${file.absolutePath}: ${throwable.message}",
                throwable,
            )
        }
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()
        require(exitCode == 0) {
            "PRoot main execution failed (exit=$exitCode): ${output.trim().ifBlank { "no output" }}"
        }
        require(output.contains(EXPECTED_PROOT_VERSION)) {
            "PRoot main version mismatch, expected $EXPECTED_PROOT_VERSION"
        }
    }

    private companion object {
        const val MIN_RUNTIME_COMPONENT_BYTES = 4096L
        const val MAX_LOADER_BYTES = 4L * 1024L * 1024L
        const val EXPECTED_PROOT_VERSION = "5.1.107.92"
    }
}
