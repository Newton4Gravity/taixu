package top.wkbin.taixu.runtime

import android.os.Build
import android.app.ActivityManager
import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import top.wkbin.taixu.core.network.DownloadEvent
import top.wkbin.taixu.core.network.DownloadRequest
import top.wkbin.taixu.core.network.FileDownloader
import top.wkbin.taixu.runtime.service.LocalServiceLauncher
import top.wkbin.taixu.runtime.service.LocalServiceSpec
import top.wkbin.taixu.runtime.shell.ManagedProcess
import top.wkbin.taixu.runtime.shell.ProcessType
import top.wkbin.taixu.runtime.shell.ShellCommand

data class LocalGgufModel(
    val fileName: String,
    val sizeBytes: Long,
    val modifiedAt: Long,
)

sealed interface LocalModelTransfer {
    data object Started : LocalModelTransfer
    data class Progress(val copiedBytes: Long, val totalBytes: Long?) : LocalModelTransfer
    data object Verifying : LocalModelTransfer
    data class Completed(val model: LocalGgufModel) : LocalModelTransfer
}

sealed interface LocalLlmServiceState {
    data object Stopped : LocalLlmServiceState
    data class Starting(val fileName: String) : LocalLlmServiceState
    data class Running(val fileName: String, val endpoint: String) : LocalLlmServiceState
    data class Failed(val message: String) : LocalLlmServiceState
}

/**
 * Owns GGUF files and the llama-server process for the active distro.
 *
 * Model files live below /opt/taixu/data inside the sandbox, so they stay isolated per distro
 * and are already covered by the existing trusted PRoot bind mount.
 */
class LocalLlmManager(
    private val context: Context,
    private val pathManager: RuntimePathManager,
    private val linuxRuntime: LinuxRuntime,
    private val fileDownloader: FileDownloader,
    private val serviceLauncher: LocalServiceLauncher,
) {
    private val _models = MutableStateFlow<List<LocalGgufModel>>(emptyList())
    val models: StateFlow<List<LocalGgufModel>> = _models.asStateFlow()

    private val _serviceState = MutableStateFlow<LocalLlmServiceState>(LocalLlmServiceState.Stopped)
    val serviceState: StateFlow<LocalLlmServiceState> = _serviceState.asStateFlow()

    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val serviceMutex = Mutex()
    private var serviceProcess: ManagedProcess? = null
    private var serviceMonitorJob: Job? = null

    init {
        managerScope.launch {
            linuxRuntime.activeDistroId.drop(1).collect {
                stop()
                refresh()
            }
        }
    }

    fun refresh() {
        val directory = modelDirectory()
        directory.mkdirs()
        _models.value = directory.listFiles()
            .orEmpty()
            .asSequence()
            .filter { it.isFile && it.extension.equals(GGUF_EXTENSION, ignoreCase = true) }
            .map { LocalGgufModel(it.name, it.length(), it.lastModified()) }
            .sortedByDescending(LocalGgufModel::modifiedAt)
            .toList()
    }

    fun download(url: String, sha256: String? = null): Flow<LocalModelTransfer> = flow {
        val fileName = fileNameFromUrl(url)
        val destination = resolveModelFile(fileName)
        fileDownloader.download(
            DownloadRequest(
                url = url.trim(),
                destination = destination,
                sha256 = sha256?.trim()?.takeIf(String::isNotEmpty),
                maxBytes = MAX_MODEL_BYTES,
            ),
        ).collect { event ->
            when (event) {
                DownloadEvent.Started -> emit(LocalModelTransfer.Started)
                is DownloadEvent.Progress -> emit(
                    LocalModelTransfer.Progress(event.downloadedBytes, event.totalBytes),
                )
                DownloadEvent.Verifying -> emit(LocalModelTransfer.Verifying)
                is DownloadEvent.Completed -> {
                    try {
                        validateGguf(event.file)
                    } catch (throwable: Throwable) {
                        event.file.delete()
                        throw throwable
                    }
                    refresh()
                    emit(LocalModelTransfer.Completed(requireModel(event.file.name)))
                }
            }
        }
    }

    fun import(
        displayName: String,
        input: InputStream,
        totalBytes: Long? = null,
    ): Flow<LocalModelTransfer> = flow {
        val destination = resolveModelFile(displayName)
        val partial = File("${destination.absolutePath}.import.part")
        emit(LocalModelTransfer.Started)
        try {
            var copied = 0L
            input.use { source ->
                partial.outputStream().buffered().use { output ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        copied += read
                        require(copied <= MAX_MODEL_BYTES) { "GGUF file exceeds 32 GB limit" }
                        output.write(buffer, 0, read)
                        emit(LocalModelTransfer.Progress(copied, totalBytes))
                    }
                }
            }
            emit(LocalModelTransfer.Verifying)
            validateGguf(partial)
            commit(partial, destination)
            refresh()
            emit(LocalModelTransfer.Completed(requireModel(destination.name)))
        } catch (cancellation: CancellationException) {
            partial.delete()
            throw cancellation
        } catch (throwable: Throwable) {
            partial.delete()
            throw throwable
        }
    }.flowOn(Dispatchers.IO)

    fun importPath(path: String): Flow<LocalModelTransfer> {
        val source = File(path.trim()).canonicalFile
        require(source.isFile && source.canRead()) { "File does not exist or is not readable: ${source.absolutePath}" }
        require(source.extension.equals(GGUF_EXTENSION, ignoreCase = true)) { "Please select a .gguf model file" }
        return import(source.name, FileInputStream(source), source.length())
    }

    suspend fun start(fileName: String) = serviceMutex.withLock {
        check(Build.SUPPORTED_ABIS.any { it.equals("arm64-v8a", ignoreCase = true) }) {
            "Current device is not ARM64, cannot run local llama.cpp inference engine"
        }
        val model = resolveModelFile(fileName)
        require(model.isFile) { "Model file not found: $fileName" }
        validateGguf(model)

        val currentState = _serviceState.value
        val currentProcess = serviceProcess
        if (currentState is LocalLlmServiceState.Running && currentProcess?.session?.isAlive == true) {
            check(currentState.fileName == model.name) {
                "A local model is already running, please stop it before starting another"
            }
            return@withLock
        }

        serviceMonitorJob?.cancel()
        serviceMonitorJob = null
        serviceProcess = null
        _serviceState.value = LocalLlmServiceState.Starting(model.name)
        try {
            val probe = linuxRuntime.execute(
                ShellCommand(
                    commandLine = "command -v llama-server >/dev/null 2>&1 || test -x /opt/taixu/bin/llama-server",
                    timeoutMs = ENGINE_PROBE_TIMEOUT_MS,
                ),
            )
            check(probe.isSuccess) { "llama.cpp inference engine not installed, please install from Tools first" }
            val guestModel = "$GUEST_MODEL_DIRECTORY/${model.name}"
            val contextSize = if (deviceRamBytes() < SIX_GIB) LOW_MEMORY_CONTEXT_SIZE else DEFAULT_CONTEXT_SIZE
            val threads = Runtime.getRuntime().availableProcessors().coerceIn(MIN_INFERENCE_THREADS, MAX_INFERENCE_THREADS)
            val handle = serviceLauncher.start(
                LocalServiceSpec(
                    serviceId = SERVICE_ID,
                    port = SERVICE_PORT,
                    path = "/v1",
                    startupTimeoutMs = SERVICE_START_TIMEOUT_MS,
                ),
            ) {
                linuxRuntime.startBackground(
                    id = SERVICE_PROCESS_ID,
                    toolId = TOOL_ID,
                    type = ProcessType.SERVICE,
                    command = ShellCommand(
                        commandLine = "exec llama-server -m ${shellQuote(guestModel)} --host 127.0.0.1 --port $SERVICE_PORT --ctx-size $contextSize --parallel 1 --threads $threads --threads-batch $threads",
                        environment = mapOf(
                            "LD_LIBRARY_PATH" to "/opt/taixu/tools/llama-cpp/lib/release",
                        ),
                        timeoutMs = Long.MAX_VALUE,
                    ),
                )
            }
            serviceProcess = handle.process
            _serviceState.value = LocalLlmServiceState.Running(model.name, handle.url)
            monitorService(handle.process)
        } catch (cancellation: CancellationException) {
            serviceProcess = null
            _serviceState.value = LocalLlmServiceState.Stopped
            throw cancellation
        } catch (throwable: Throwable) {
            serviceProcess = null
            _serviceState.value = LocalLlmServiceState.Failed(
                throwable.message ?: "Failed to start local model service",
            )
            throw throwable
        }
    }

    suspend fun stop() = serviceMutex.withLock {
        serviceMonitorJob?.cancel()
        serviceMonitorJob = null
        serviceProcess = null
        serviceLauncher.stop(SERVICE_ID)
        _serviceState.value = LocalLlmServiceState.Stopped
    }

    suspend fun delete(fileName: String) = withContext(Dispatchers.IO) {
        val runningName = (_serviceState.value as? LocalLlmServiceState.Running)?.fileName
        require(runningName != fileName) { "Please stop the running model first" }
        val target = resolveModelFile(fileName)
        check(!target.exists() || target.delete()) { "Failed to delete model: $fileName" }
        File("${target.absolutePath}.part").delete()
        refresh()
    }

    private fun modelDirectory(): File = File(
        pathManager.taixuDataDir(linuxRuntime.activeDistroId.value),
        "llama-cpp/models",
    )

    private fun resolveModelFile(rawName: String): File {
        val safeName = rawName.substringAfterLast('/').substringAfterLast('\\').trim()
        require(SAFE_FILE_NAME.matches(safeName)) { "Invalid model file name" }
        require(safeName.endsWith(".$GGUF_EXTENSION", ignoreCase = true)) { "Model must be a .gguf file" }
        val directory = modelDirectory().apply { mkdirs() }.canonicalFile
        val target = File(directory, safeName).canonicalFile
        require(target.parentFile == directory) { "Model path traversal detected" }
        return target
    }

    private fun fileNameFromUrl(url: String): String {
        val value = url.trim()
        require(value.startsWith("https://", ignoreCase = true)) { "Download URL must use HTTPS" }
        val path = runCatching { URI(value).path }.getOrNull().orEmpty()
        return path.substringAfterLast('/').takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("Download URL missing GGUF file name")
    }

    private fun requireModel(fileName: String): LocalGgufModel =
        _models.value.first { it.fileName == fileName }

    private fun validateGguf(file: File) {
        require(file.length() >= GGUF_HEADER_BYTES) { "File too small, not a valid GGUF model" }
        val magic = ByteArray(GGUF_MAGIC.size)
        file.inputStream().use { input ->
            check(input.read(magic) == magic.size && magic.contentEquals(GGUF_MAGIC)) {
                "File header is not GGUF, downloaded content may be login page or error response"
            }
        }
    }

    private fun commit(source: File, destination: File) {
        runCatching {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }.getOrElse {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"

    private fun deviceRamBytes(): Long = ActivityManager.MemoryInfo().also { info ->
        context.getSystemService(ActivityManager::class.java)?.getMemoryInfo(info)
    }.totalMem

    private fun monitorService(process: ManagedProcess) {
        serviceMonitorJob = managerScope.launch {
            while (process.session.isAlive) {
                delay(SERVICE_MONITOR_INTERVAL_MS)
            }
            serviceMutex.withLock {
                if (serviceProcess === process) {
                    serviceLauncher.stop(SERVICE_ID)
                    serviceProcess = null
                    serviceMonitorJob = null
                    _serviceState.value = LocalLlmServiceState.Stopped
                }
            }
        }
    }

    companion object {
        const val TOOL_ID = "llama-cpp"
        const val SERVICE_PORT = 8080
        const val BASE_URL = "http://127.0.0.1:$SERVICE_PORT/v1"
        private const val SERVICE_ID = "llama-cpp"
        private const val SERVICE_PROCESS_ID = "llama-cpp-server"
        private const val GUEST_MODEL_DIRECTORY = "/opt/taixu/data/llama-cpp/models"
        private const val GGUF_EXTENSION = "gguf"
        private const val COPY_BUFFER_BYTES = 1024 * 1024
        private const val GGUF_HEADER_BYTES = 24L
        private const val MAX_MODEL_BYTES = 32L * 1024L * 1024L * 1024L
        private const val ENGINE_PROBE_TIMEOUT_MS = 10_000L
        private const val SERVICE_START_TIMEOUT_MS = 5 * 60_000L
        private const val SERVICE_MONITOR_INTERVAL_MS = 1_000L
        private const val LOW_MEMORY_CONTEXT_SIZE = 2048
        private const val DEFAULT_CONTEXT_SIZE = 4096
        private const val MIN_INFERENCE_THREADS = 2
        private const val MAX_INFERENCE_THREADS = 4
        private const val SIX_GIB = 6L * 1024L * 1024L * 1024L
        private val GGUF_MAGIC = byteArrayOf('G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte())
        private val SAFE_FILE_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._+() -]{0,239}")
    }
}
