package top.wkbin.taixu.runtime.bridge.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import com.flyfishxu.kadb.Kadb
import com.flyfishxu.kadb.cert.KadbCert
import com.flyfishxu.kadb.cert.OkioFilePrivateKeyStore
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okio.Path.Companion.toPath
import top.wkbin.taixu.core.datastore.RuntimePreferences

/**
 * App-embedded wireless ADB client.
 *
 * Android wireless debug port changes each time it's enabled, so both connection and pairing
 * ports are auto-discovered via mDNS.
 * ADB private key persisted in app private directory; as long as user doesn't revoke authorization
 * in system settings or clear app data, a single successful pairing code entry enables auto-reconnect on subsequent launches.
 *
 * mDNS discovery uses Android NsdManager classic API (discoverServices + resolveService),
 * not Android 14's registerServiceInfoCallback, to avoid known onServiceUpdated infinite recursion
 * StackOverflowError (NSD Binder callbacks on some devices repeatedly trigger onServiceUpdated
 * for same service, and call stack doesn't cross threads, causing synchronous infinite recursion).
 * All callbacks handled asynchronously via coroutine dispatcher.
 */
class EmbeddedAdbManager(
    private val context: Context,
    private val preferences: RuntimePreferences,
    private val pathManager: top.wkbin.taixu.runtime.RuntimePathManager,
) {
    sealed interface ConnectionState {
        data object Disconnected : ConnectionState
        data object Discovering : ConnectionState
        data object Pairing : ConnectionState
        data object Connecting : ConnectionState
        data class Connected(val host: String, val port: Int) : ConnectionState
        data class Failed(val message: String) : ConnectionState
    }

    data class DiscoveryState(
        val running: Boolean = false,
        val pairingEndpoints: List<Endpoint> = emptyList(),
        val connectEndpoints: List<Endpoint> = emptyList(),
    )

    data class Endpoint(val name: String, val host: String, val port: Int)

    data class ShellOutcome(
        val exitCode: Int?,
        val output: String,
        val success: Boolean,
    )

    data class LogcatRequest(
        val packageName: String = "",
        val tag: String = "",
        val priority: Char = 'V',
        val keyword: String = "",
        val lines: Int = 200,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    private val nsdManager: NsdManager =
        context.applicationContext.getSystemService(NsdManager::class.java)
    private val wifiManager = context.applicationContext.getSystemService(WifiManager::class.java)
    private val multicastLock = wifiManager.createMulticastLock("taixu-wireless-adb-mdns").apply {
        setReferenceCounted(false)
    }

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()
    private val _discovery = MutableStateFlow(DiscoveryState())
    val discovery: StateFlow<DiscoveryState> = _discovery.asStateFlow()

    private var client: Kadb? = null

    // Prevent concurrent resolveService (NsdManager doesn't allow multiple simultaneous resolves)
    private val resolving = AtomicBoolean(false)

    // Discovered endpoint cache; key = serviceName
    private val pairingEndpointMap = ConcurrentHashMap<String, Endpoint>()
    private val connectEndpointMap = ConcurrentHashMap<String, Endpoint>()

    // Active discovery consumer tags; mDNS starts when set non-empty, releases resources when empty
    private val discoveryConsumers = ConcurrentHashMap.newKeySet<String>()

    // NSD listener references, need to unregister on stop
    private var pairingDiscoveryListener: NsdManager.DiscoveryListener? = null
    private var connectDiscoveryListener: NsdManager.DiscoveryListener? = null
    private val discoveryStarted = AtomicBoolean(false)

    private val keyDir: File get() = File(context.filesDir, "adb").apply { mkdirs() }
    private val privateKeyFile: File get() = File(keyDir, "kadb-private-key.pem")

    init {
        KadbCert.configure(OkioFilePrivateKeyStore(privateKeyFile.absolutePath.toPath()))
        KadbCert.ensureReady()
        // On-demand discovery: no longer unconditionally starts mDNS on cold start,
        // completely avoids Android 17 (API 37) unauthorized NSD_PICKER popup and idle battery drain.
    }

    // ── Discovery ────────────────────────────────────────────────────────────────

    /**
     * Starts mDNS discovery on demand.
     * Supports multiple components registering references via [consumerTag]; system resources only consumed when at least one active consumer exists.
     */
    fun startDiscovery(consumerTag: String = TAG_MANUAL) {
        discoveryConsumers.add(consumerTag)
        startDiscoveryInternal()
    }

    /**
     * Unregisters discovery demand for specified consumer; when all consumers release, immediately unregisters system listener and releases MulticastLock.
     */
    fun stopDiscovery(consumerTag: String = TAG_MANUAL) {
        discoveryConsumers.remove(consumerTag)
        if (discoveryConsumers.isEmpty()) {
            stopDiscoveryInternal()
        }
    }

    /**
     * Explicitly force refresh discovery endpoints (e.g., user clicks "rescan" button in UI).
     */
    fun restartDiscovery() {
        stopDiscoveryInternal()
        pairingEndpointMap.clear()
        connectEndpointMap.clear()
        publishDiscoveryState()
        startDiscoveryInternal()
    }

    private fun startDiscoveryInternal() {
        if (discoveryStarted.getAndSet(true)) return
        runCatching {
            if (!multicastLock.isHeld) multicastLock.acquire()
            _state.value = ConnectionState.Discovering
            _discovery.value = DiscoveryState(running = true)

            pairingDiscoveryListener = buildDiscoveryListener(isPairing = true)
            connectDiscoveryListener = buildDiscoveryListener(isPairing = false)

            nsdManager.discoverServices(
                SERVICE_PAIRING,
                NsdManager.PROTOCOL_DNS_SD,
                pairingDiscoveryListener,
            )
            nsdManager.discoverServices(
                SERVICE_CONNECT,
                NsdManager.PROTOCOL_DNS_SD,
                connectDiscoveryListener,
            )
        }.onFailure { error ->
            discoveryStarted.set(false)
            _state.value = ConnectionState.Failed(error.userMessage("Failed to start mDNS auto-discovery"))
            Log.w(TAG, "startDiscovery failed", error)
        }
    }

    private fun stopDiscoveryInternal() {
        if (!discoveryStarted.getAndSet(false)) return
        runCatching { pairingDiscoveryListener?.let { nsdManager.stopServiceDiscovery(it) } }
        runCatching { connectDiscoveryListener?.let { nsdManager.stopServiceDiscovery(it) } }
        pairingDiscoveryListener = null
        connectDiscoveryListener = null
        if (multicastLock.isHeld) multicastLock.release()
        _discovery.value = DiscoveryState(running = false)
    }

    /**
     * Builds NsdManager.DiscoveryListener.
     *
     * Design constraint: all callbacks dispatched asynchronously via scope.launch to coroutines,
     * callback bodies don't call any NSD APIs, completely avoiding synchronous recursion crashes.
     */
    private fun buildDiscoveryListener(isPairing: Boolean): NsdManager.DiscoveryListener =
        object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.d(TAG, "NSD discovery started: $serviceType (pairing=$isPairing)")
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.d(TAG, "NSD discovery stopped: $serviceType")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                // Minimal action only, real logic handled asynchronously
                scope.launch { onDiscoveryError("onStartDiscoveryFailed errorCode=$errorCode") }
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "onStopDiscoveryFailed errorCode=$errorCode")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                // Return immediately; resolve runs async in coroutine, never call any NSD API here
                scope.launch { resolveAsync(serviceInfo, isPairing) }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                scope.launch {
                    val name = serviceInfo.serviceName ?: return@launch
                    if (isPairing) pairingEndpointMap.remove(name)
                    else connectEndpointMap.remove(name)
                    publishDiscoveryState()
                    Log.d(TAG, "NSD service lost: $name (pairing=$isPairing)")
                }
            }
        }

    /**
     * Async resolve NsdServiceInfo to get host + port.
     * Uses deprecated but stable resolveService API across all versions, no onServiceUpdated recursion issue.
     * Atomic flag serialization protection: NsdManager doesn't support concurrent resolve.
     */
    @Suppress("DEPRECATION")
    private suspend fun resolveAsync(serviceInfo: NsdServiceInfo, isPairing: Boolean) =
        withContext(Dispatchers.IO) {
            // If resolve already in progress, skip
            if (!resolving.compareAndSet(false, true)) return@withContext
            try {
                val resolved = resolveServiceSuspend(serviceInfo) ?: return@withContext
                val host = resolved.host?.hostAddress?.takeIf { it.isNotBlank() } ?: return@withContext
                val port = resolved.port.takeIf { it in VALID_PORTS } ?: return@withContext
                val name = resolved.serviceName ?: serviceInfo.serviceName ?: return@withContext
                val endpoint = Endpoint(name = name, host = host, port = port)
                if (isPairing) pairingEndpointMap[name] = endpoint
                else connectEndpointMap[name] = endpoint
                publishDiscoveryState()
                Log.i(TAG, "NSD resolved: $name @ $host:$port (pairing=$isPairing)")

                // If already paired and connect endpoint found, auto-attempt connection
                if (!isPairing && client == null && preferences.adbPairedOnce.first()) {
                    connect()
                }
            } finally {
                resolving.set(false)
            }
        }

    /**
     * Wraps NsdManager.resolveService as suspend function.
     * Returns null if resolve fails or times out.
     */
    @Suppress("DEPRECATION")
    private suspend fun resolveServiceSuspend(serviceInfo: NsdServiceInfo): NsdServiceInfo? =
        withTimeoutOrNull(RESOLVE_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                nsdManager.resolveService(
                    serviceInfo,
                    object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                            Log.w(TAG, "resolveService failed errorCode=$errorCode for ${info.serviceName}")
                            if (cont.isActive) cont.resume(null) {}
                        }

                        override fun onServiceResolved(info: NsdServiceInfo) {
                            if (cont.isActive) cont.resume(info) {}
                        }
                    },
                )
            }
        }

    private fun onDiscoveryError(reason: String) {
        Log.w(TAG, "mDNS discovery error: $reason")
        if (client == null) {
            _state.value = ConnectionState.Failed("mDNS auto-discovery failed, ensure Wi-Fi and wireless debugging are enabled")
        }
    }

    private fun publishDiscoveryState() {
        _discovery.value = DiscoveryState(
            running = discoveryStarted.get(),
            pairingEndpoints = pairingEndpointMap.values.toList(),
            connectEndpoints = connectEndpointMap.values.toList(),
        )
    }

    // ── Pairing ────────────────────────────────────────────────────────────────

    /** Performs real TLS + SPAKE2 pairing using mDNS-discovered pairing port. */
    suspend fun pair(pairingCode: String): Result<Unit> = pair(null, pairingCode)

    /** Explicit port only as compatibility fallback when mDNS unavailable. */
    suspend fun pair(pairingPort: Int?, pairingCode: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            require(pairingCode.matches(PAIRING_CODE)) { "Pairing code must be 6 digits" }
            val endpoint = pairingEndpoint(pairingPort)
            _state.value = ConnectionState.Pairing
            Kadb.pair(endpoint.host, endpoint.port, pairingCode, "TaiXu")
            preferences.setAdbPairedOnce(true)
            Log.i(TAG, "wireless adb pairing succeeded via ${endpoint.host}:${endpoint.port}")

            val connectEndpoint = awaitConnectEndpoint(endpoint.host)
            if (connectEndpoint != null) {
                connectTo(connectEndpoint)
            } else {
                // Pairing completed and key saved; will auto-reconnect when connect service appears later.
                _state.value = ConnectionState.Discovering
            }
        }.onFailure { error ->
            _state.value = ConnectionState.Failed(error.userMessage("Wireless ADB pairing failed"))
            Log.w(TAG, "wireless adb pairing failed", error)
        }.map { }
    }

    // ── Connection ────────────────────────────────────────────────────────────────

    /** Prefers connecting to specified/current mDNS endpoint; excludes pairing port, auto-falls back to 127.0.0.1 loopback on LAN IP failure. */
    suspend fun connect(explicitPort: Int? = null): Result<Unit> = mutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                val discovered = connectEndpointMap.values.toList()
                val pairingPorts = pairingEndpointMap.values.map { it.port }.toSet()
                val savedPort = preferences.adbWirelessPort.first()
                val candidates = buildList {
                    if (explicitPort != null && explicitPort in VALID_PORTS) {
                        add(Endpoint("explicit", LOOPBACK, explicitPort))
                    }
                    addAll(discovered)
                    if (savedPort in VALID_PORTS && savedPort !in pairingPorts && none { it.port == savedPort }) {
                        add(Endpoint("saved", LOOPBACK, savedPort))
                    }
                }
                require(candidates.isNotEmpty()) {
                    "No wireless debug connection port found, ensure system wireless debugging is enabled, or manually enter 5-digit connection port"
                }
                var lastError: Throwable? = null
                for (endpoint in candidates) {
                    try {
                        connectTo(endpoint)
                        return@runCatching
                    } catch (error: Throwable) {
                        lastError = error
                    }
                }
                throw lastError ?: IllegalStateException("No available wireless debug endpoints")
            }.onFailure { error ->
                _state.value = ConnectionState.Failed(error.userMessage("Wireless ADB connection failed"))
                Log.w(TAG, "wireless adb connect failed", error)
            }.map { }
        }
    }

    private fun connectTo(endpoint: Endpoint) {
        closeClient()
        _state.value = ConnectionState.Connecting
        // Try endpoint.host; if not 127.0.0.1 and fails, auto-try 127.0.0.1 loopback
        val hostsToTry = if (endpoint.host != LOOPBACK) listOf(endpoint.host, LOOPBACK) else listOf(LOOPBACK)
        var lastError: Throwable? = null
        for (h in hostsToTry) {
            val connected = try {
                Kadb.create(
                    host = h,
                    port = endpoint.port,
                    connectTimeout = CONNECT_TIMEOUT_MS,
                    socketTimeout = SHELL_TIMEOUT_MS,
                )
            } catch (e: Throwable) {
                lastError = e
                continue
            }
            try {
                val probe = connected.shell("echo ok")
                check(probe.output.trim() == "ok") { "ADB link probe failed" }
                client = connected
                _state.value = ConnectionState.Connected(h, endpoint.port)
                scope.launch {
                    preferences.setAdbWirelessPort(endpoint.port)
                    persistAdbEndpoint(h, endpoint.port)
                }
                Log.i(TAG, "embedded adb connected on $h:${endpoint.port}")
                return
            } catch (error: Throwable) {
                connected.close()
                lastError = error
            }
        }
        throw lastError ?: IllegalStateException("Failed to connect to port ${endpoint.port}")
    }

    fun disconnect() {
        closeClient()
        _state.value = ConnectionState.Disconnected
        scope.launch { clearAdbEndpoint() }
    }

    private fun persistAdbEndpoint(host: String, port: Int) {
        runCatching {
            val distroIds = pathManager.listInstalledDistroIds()
            for (distroId in distroIds) {
                val taixuRoot = pathManager.taixuRootDir(distroId)
                if (taixuRoot.exists()) {
                    File(taixuRoot, ".adb-port").writeText(port.toString())
                    File(taixuRoot, ".adb-host").writeText(host)
                }
            }
        }.onFailure { Log.w(TAG, "Failed to persist adb endpoint to sandboxes", it) }
    }

    private fun clearAdbEndpoint() {
        runCatching {
            val distroIds = pathManager.listInstalledDistroIds()
            for (distroId in distroIds) {
                val taixuRoot = pathManager.taixuRootDir(distroId)
                if (taixuRoot.exists()) {
                    File(taixuRoot, ".adb-port").delete()
                    File(taixuRoot, ".adb-host").delete()
                }
            }
        }.onFailure { Log.w(TAG, "Failed to clear adb endpoint from sandboxes", it) }
    }

    // ── Shell / Logcat ───────────────────────────────────────────────────────

    suspend fun executeShell(command: String, explicitPort: Int? = null): ShellOutcome {
        val currentClient = client
        val currentConnectedPort = (_state.value as? ConnectionState.Connected)?.port
        if (currentClient == null || (explicitPort != null && currentConnectedPort != explicitPort)) {
            val connection = if (explicitPort != null) connect(explicitPort) else connect()
            if (connection.isFailure) {
                return ShellOutcome(
                    null,
                    connection.exceptionOrNull()?.userMessage("Embedded ADB not ready").orEmpty(),
                    false,
                )
            }
        }
        return mutex.withLock {
            withContext(Dispatchers.IO) {
                val current = client
                try {
                    val response = requireNotNull(current).shell(command)
                    // Kadb buffers entire shell output in memory: logcat -t 2000 / full dumpsys
                    // Can reach several MB; without limit, multiple string copies occupy 256MB Java heap,
                    // triggering target footprint OOM. Same value as harness ToolExecutor.MAX_HOST_OUTPUT_CHARS.
                    val output = if (response.allOutput.length > MAX_SHELL_OUTPUT_CHARS) {
                        response.allOutput.take(MAX_SHELL_OUTPUT_CHARS) +
                            "\n[ADB shell output truncated: original ${response.allOutput.length} chars, " +
                            "only first $MAX_SHELL_OUTPUT_CHARS retained; narrow output range and retry]"
                    } else {
                        response.allOutput
                    }
                    ShellOutcome(response.exitCode, output, response.exitCode == 0)
                } catch (error: Throwable) {
                    closeClient()
                    _state.value = ConnectionState.Failed(error.userMessage("ADB execution failed"))
                    ShellOutcome(null, error.userMessage("ADB execution failed"), false)
                }
            }
        }
    }

    /** Captures target app logs; keyword filtered in-process to avoid injecting user text into shell. */
    suspend fun captureLogcat(request: LogcatRequest, explicitPort: Int? = null): ShellOutcome {
        require(request.packageName.isBlank() || PACKAGE_NAME.matches(request.packageName)) { "Invalid package name format" }
        require(request.tag.isBlank() || LOGCAT_TAG.matches(request.tag)) { "Invalid Logcat Tag format" }
        require(request.priority.uppercaseChar() in PRIORITIES) { "Invalid log priority" }
        val lines = request.lines.coerceIn(1, MAX_LOG_LINES)
        val tagArgs = if (request.tag.isBlank()) {
            shellQuote("*:${request.priority.uppercaseChar()}")
        } else {
            "${shellQuote("${request.tag}:${request.priority.uppercaseChar()}")} ${shellQuote("*:S")}"
        }
        val logcat = "/system/bin/logcat -d -v threadtime -t $lines"
        val command = if (request.packageName.isBlank()) {
            "$logcat $tagArgs"
        } else {
            "pid=\$(/system/bin/pidof ${shellQuote(request.packageName)} | /system/bin/cut -d' ' -f1); " +
                "if [ -z \"\$pid\" ]; then echo 'Target app not running: ${request.packageName}'; exit 3; fi; " +
                "$logcat --pid=\"\$pid\" $tagArgs"
        }
        val outcome = executeShell(command, explicitPort)
        if (!outcome.success || request.keyword.isBlank()) return outcome
        val filtered = outcome.output.lineSequence()
            .filter { it.contains(request.keyword, ignoreCase = true) }
            .joinToString("\n")
        return outcome.copy(output = filtered)
    }

    suspend fun clearLogcat(): ShellOutcome = executeShell("/system/bin/logcat -c")

    suspend fun installApk(apk: File): Result<String> = mutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                val current = client ?: error("Embedded ADB not connected, enable wireless debugging and complete pairing first")
                current.install(apk)
                "Installation successful"
            }
        }
    }

    // ── Internal Utils ─────────────────────────────────────────────────────────────

    private fun pairingEndpoint(explicitPort: Int?): Endpoint {
        if (explicitPort != null) {
            require(explicitPort in VALID_PORTS) { "Invalid pairing port" }
            return pairingEndpointMap.values.firstOrNull { it.port == explicitPort }
                ?: Endpoint("manual", LOOPBACK, explicitPort)
        }
        return pairingEndpointMap.values.firstOrNull()
            ?: error("No pairing port found: enable 'Pair with pairing code' in system Wireless Debugging")
    }

    private suspend fun awaitConnectEndpoint(preferredHost: String): Endpoint? =
        connectEndpointMap.values.preferHost(preferredHost)
            ?: withTimeoutOrNull(CONNECT_DISCOVERY_TIMEOUT_MS) {
                discovery.first { it.connectEndpoints.isNotEmpty() }.connectEndpoints.preferHost(preferredHost)
            }

    private fun Collection<Endpoint>.preferHost(host: String): Endpoint? =
        firstOrNull { it.host == host } ?: firstOrNull()

    private fun closeClient() {
        runCatching { client?.close() }
        client = null
    }

    private fun Throwable.userMessage(prefix: String): String {
        val raw = message.orEmpty()
        val friendlyMessage = when {
            raw.contains("Failure in SSL library", ignoreCase = true) || raw.contains("ssl", ignoreCase = true) ->
                "TLS handshake failed (wireless debug connect port changed, or connected to pairing port). Ensure system wireless debugging is enabled, verify port, or re-pair."
            raw.contains("Connection refused", ignoreCase = true) ->
                "Connection refused (port not listening), ensure system wireless debugging is enabled and port is correct."
            raw.contains("ETIMEDOUT", ignoreCase = true) || raw.contains("timed out", ignoreCase = true) ->
                "Connection timeout, ensure phone is on Wi-Fi and wireless debugging is enabled."
            raw.isNotBlank() -> raw
            else -> javaClass.simpleName
        }
        return "$prefix：$friendlyMessage"
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    companion object {
        const val TAG_MANUAL = "manual"
        const val TAG_NOTIFICATION = "adb_notification"
        const val TAG_DEVELOPER_UI = "developer_ui"

        private const val TAG = "EmbeddedAdb"
        private const val LOOPBACK = "127.0.0.1"
        const val CONNECT_TIMEOUT_MS = 5_000
        const val SHELL_TIMEOUT_MS = 30_000
        const val CONNECT_DISCOVERY_TIMEOUT_MS = 10_000L
        const val RESOLVE_TIMEOUT_MS = 8_000L
        const val MAX_LOG_LINES = 5_000

        /** Shell output char hard limit (same as harness ToolExecutor.MAX_HOST_OUTPUT_CHARS, prevents Java heap OOM). */
        const val MAX_SHELL_OUTPUT_CHARS = 200_000

        // Android wireless debug mDNS service types
        const val SERVICE_PAIRING = "_adb-tls-pairing._tcp."
        const val SERVICE_CONNECT = "_adb-tls-connect._tcp."

        val VALID_PORTS = 1024..65535
        val PAIRING_CODE = Regex("\\d{6}")
        val PACKAGE_NAME = Regex("^[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+$")
        val LOGCAT_TAG = Regex("^[A-Za-z0-9_.-]{1,80}$")
        val PRIORITIES = setOf('V', 'D', 'I', 'W', 'E', 'F')
    }
}
