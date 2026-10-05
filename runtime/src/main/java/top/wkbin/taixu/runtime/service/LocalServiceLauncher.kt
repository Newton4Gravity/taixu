package top.wkbin.taixu.runtime.service

import top.wkbin.taixu.runtime.LinuxRuntime
import top.wkbin.taixu.runtime.shell.ManagedProcess
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration.Companion.milliseconds

data class LocalServiceSpec(
    val serviceId: String,
    val port: Int,
    val path: String = "/",
    val startupTimeoutMs: Long = DEFAULT_STARTUP_TIMEOUT_MS,
    val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
) {
    init {
        require(SERVICE_ID.matches(serviceId)) { "Invalid local service ID: $serviceId" }
    }
    init {
        require(port in 1..65535) { "Invalid local service port: $port" }
        require(path.startsWith('/')) { "Local service path must start with /" }
        require(path.length <= 512 && path.none(Char::isISOControl)) { "Invalid local service path" }
        require(startupTimeoutMs > 0) { "Local service startup timeout must be > 0" }
        require(pollIntervalMs > 0) { "Local service poll interval must be > 0" }
    }

    companion object {
        // Gateway startup wait: first launch may need compile/download deps, 20s often not enough on slow devices,
        // extended to 10 min; if process actually crashes, wait logic detects immediately and errors, no need to wait full timeout.
        const val DEFAULT_STARTUP_TIMEOUT_MS = 10 * 60 * 1000L
        const val DEFAULT_POLL_INTERVAL_MS = 200L
        private val SERVICE_ID = Regex("[a-z0-9][a-z0-9-]{1,63}")
    }
}

data class LocalServiceHandle(
    val spec: LocalServiceSpec,
    val process: ManagedProcess,
) {
    val url: String get() = "http://localhost:${spec.port}${spec.path}"
}

class LocalServiceStartException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

interface LocalServiceLauncher {
    suspend fun start(
        spec: LocalServiceSpec,
        startProcess: suspend () -> ManagedProcess,
    ): LocalServiceHandle

    suspend fun stop(serviceId: String): Boolean
    suspend fun stopAll()
}

class LocalServiceLauncherImpl(
    private val linuxRuntime: LinuxRuntime,
) : LocalServiceLauncher {
    private val mutex = Mutex()
    private val services = LinkedHashMap<String, LocalServiceHandle>()

    override suspend fun start(
        spec: LocalServiceSpec,
        startProcess: suspend () -> ManagedProcess,
    ): LocalServiceHandle = mutex.withLock {
        stopLocked(spec.serviceId)
        val process = try {
            startProcess()
        } catch (throwable: Throwable) {
            throw LocalServiceStartException("Failed to start local service: ${spec.serviceId}", throwable)
        }

        try {
            awaitPort(spec, process)
            LocalServiceHandle(spec, process).also { services[spec.serviceId] = it }
        } catch (timeout: TimeoutCancellationException) {
            linuxRuntime.stopBackground(process.id)
            throw LocalServiceStartException(
                "Local service not ready within ${spec.startupTimeoutMs / 1000}s: ${spec.serviceId}",
                timeout,
            )
        } catch (cancellation: CancellationException) {
            linuxRuntime.stopBackground(process.id)
            throw cancellation
        } catch (throwable: Throwable) {
            linuxRuntime.stopBackground(process.id)
            if (throwable is LocalServiceStartException) throw throwable
            throw LocalServiceStartException(
                "Local service not ready within ${spec.startupTimeoutMs / 1000}s: ${spec.serviceId}",
                throwable,
            )
        }
    }

    override suspend fun stop(serviceId: String): Boolean = mutex.withLock {
        stopLocked(serviceId)
    }

    override suspend fun stopAll() = mutex.withLock {
        services.values.forEach { handle -> linuxRuntime.stopBackground(handle.process.id) }
        services.clear()
    }

    private suspend fun stopLocked(serviceId: String): Boolean {
        val handle = services.remove(serviceId) ?: return false
        linuxRuntime.stopBackground(handle.process.id)
        return true
    }

    private suspend fun awaitPort(spec: LocalServiceSpec, process: ManagedProcess) {
        withTimeout(spec.startupTimeoutMs.milliseconds) {
            while (true) {
                coroutineContext.ensureActive()
                if (!process.session.isAlive) {
                    throw LocalServiceStartException("Local service process exited: ${spec.serviceId}")
                }
                if (isPortOpen(spec.port)) return@withTimeout
                delay(spec.pollIntervalMs.milliseconds)
            }
        }
    }

    private suspend fun isPortOpen(port: Int): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", port), SOCKET_CONNECT_TIMEOUT_MS.toInt())
            }
            true
        }.getOrDefault(false)
    }

    private companion object {
        const val SOCKET_CONNECT_TIMEOUT_MS = 250L
    }
}
