package top.wkbin.taixu.runtime

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import top.wkbin.taixu.core.datastore.SshPreferences
import top.wkbin.taixu.core.model.RuntimeState
import top.wkbin.taixu.runtime.service.LocalServiceLauncher
import top.wkbin.taixu.runtime.service.LocalServiceSpec
import top.wkbin.taixu.runtime.shell.ManagedProcess
import top.wkbin.taixu.runtime.shell.ProcessType
import top.wkbin.taixu.runtime.shell.ShellCommand

data class SshRuntimeConfig(
    val port: Int = DEFAULT_SSH_PORT,
    val authorizedKeys: String = "",
    val passwordAuthEnabled: Boolean = false,
) {
    init {
        require(port in 1024..65535) { "SSH port must be between 1024..65535" }
    }

    companion object {
        const val DEFAULT_SSH_PORT = 8022
    }
}

sealed interface SshServiceState {
    data class Stopped(val distroId: String, val installed: Boolean = false) : SshServiceState
    data class Installing(val distroId: String) : SshServiceState
    data class Starting(val distroId: String) : SshServiceState
    data class Running(
        val distroId: String,
        val port: Int,
        val host: String,
    ) : SshServiceState
    data class Failed(val distroId: String, val message: String) : SshServiceState
}

/** Owns the built-in OpenSSH server for the currently active Linux distribution. */
class SshServiceManager(
    private val context: Context,
    private val linuxRuntime: LinuxRuntime,
    private val preferences: SshPreferences,
    private val serviceLauncher: LocalServiceLauncher,
) {
    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val serviceMutex = Mutex()
    private val observing = AtomicBoolean(false)
    private val _state = MutableStateFlow<SshServiceState>(SshServiceState.Stopped("ubuntu"))
    val state: StateFlow<SshServiceState> = _state.asStateFlow()

    private var serviceProcess: ManagedProcess? = null
    private var serviceDistroId: String? = null
    private var monitorJob: Job? = null
    private var activePort: Int? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    /** Idempotently restores SSH when the runtime becomes ready or the active distro changes. */
    fun startObserving() {
        if (!observing.compareAndSet(false, true)) return
        managerScope.launch {
            combine(linuxRuntime.state, linuxRuntime.activeDistroId) { runtimeState, distroId ->
                (runtimeState is RuntimeState.Ready) to distroId
            }
                .distinctUntilChanged()
                .collectLatest { (ready, distroId) ->
                    serviceMutex.withLock {
                        if (serviceDistroId != null && serviceDistroId != distroId) stopLocked()
                    }
                    if (!ready) {
                        _state.value = SshServiceState.Stopped(distroId)
                        return@collectLatest
                    }
                    refresh(distroId)
                    if (preferences.enabled(distroId).first()) {
                        try {
                            start(distroId)
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (_: Throwable) {
                            // start() publishes the actionable failure through state.
                        }
                    }
                }
        }
    }

    suspend fun setEnabled(enabled: Boolean) {
        val distroId = linuxRuntime.activeDistroId.value
        if (enabled) {
            start(distroId)
            preferences.setEnabled(distroId, true)
        } else {
            preferences.setEnabled(distroId, false)
            stop()
        }
    }

    suspend fun setPort(port: Int) {
        require(port in 1024..65535) { "SSH port must be between 1024..65535" }
        val distroId = linuxRuntime.activeDistroId.value
        preferences.setPort(distroId, port)
        restartIfRunning(distroId)
    }

    suspend fun setAuthorizedKeys(keys: String) {
        val distroId = linuxRuntime.activeDistroId.value
        val normalized = SshCommandFactory.normalizeAuthorizedKeys(keys)
        preferences.setAuthorizedKeys(distroId, normalized)
        val passwordAvailable = preferences.passwordAuthEnabled(distroId).first() &&
            preferences.passwordConfigured(distroId).first()
        if (normalized.isBlank() && !passwordAvailable) {
            preferences.setEnabled(distroId, false)
            if (serviceDistroId == distroId) stop()
        } else {
            restartIfRunning(distroId)
        }
    }

    suspend fun setPassword(password: String) {
        val distroId = linuxRuntime.activeDistroId.value
        val normalized = SshCommandFactory.normalizePassword(password)
        preferences.setPassword(distroId, normalized)
        preferences.setPasswordAuthEnabled(distroId, true)
        restartIfRunning(distroId)
    }

    suspend fun setPasswordAuthEnabled(enabled: Boolean) {
        val distroId = linuxRuntime.activeDistroId.value
        if (enabled) {
            check(preferences.passwordConfigured(distroId).first()) { "Please set SSH login password first" }
        } else {
            check(preferences.authorizedKeys(distroId).first().isNotBlank() || !preferences.enabled(distroId).first()) {
                "Please add SSH public key first, or disable SSH service before disabling password auth"
            }
        }
        preferences.setPasswordAuthEnabled(distroId, enabled)
        restartIfRunning(distroId)
    }

    suspend fun clearPassword() {
        val distroId = linuxRuntime.activeDistroId.value
        preferences.setPasswordAuthEnabled(distroId, false)
        preferences.setPassword(distroId, null)
        if (preferences.authorizedKeys(distroId).first().isBlank()) {
            preferences.setEnabled(distroId, false)
            if (serviceDistroId == distroId) stop()
        } else {
            restartIfRunning(distroId)
        }
    }

    suspend fun refresh(distroId: String = linuxRuntime.activeDistroId.value) {
        if (serviceDistroId == distroId && serviceProcess?.session?.isAlive == true) return
        val installed = linuxRuntime.execute(
            ShellCommand(
                commandLine = "command -v sshd >/dev/null 2>&1",
                timeoutMs = PROBE_TIMEOUT_MS,
            ),
            distroId,
        ).isSuccess
        _state.value = SshServiceState.Stopped(distroId, installed)
    }

    suspend fun start(distroId: String = linuxRuntime.activeDistroId.value) = serviceMutex.withLock {
        check(linuxRuntime.state.value is RuntimeState.Ready) { "Linux runtime not ready" }
        val current = serviceProcess
        if (serviceDistroId == distroId && current?.session?.isAlive == true) return@withLock
        stopLocked()

        val config = readConfig(distroId)
        val authorizedKeys = SshCommandFactory.normalizeAuthorizedKeys(config.authorizedKeys)
        val password = if (config.passwordAuthEnabled) {
            preferences.readPassword(distroId)?.let(SshCommandFactory::normalizePassword)
                ?: error("Please set SSH login password first")
        } else {
            null
        }
        check(authorizedKeys.isNotBlank() || password != null) {
            "Please set SSH login password or add at least one public key"
        }

        try {
            val installed = linuxRuntime.execute(
                ShellCommand("command -v sshd >/dev/null 2>&1", timeoutMs = PROBE_TIMEOUT_MS),
                distroId,
            ).isSuccess
            if (!installed) {
                _state.value = SshServiceState.Installing(distroId)
                val install = linuxRuntime.execute(
                    ShellCommand(
                        commandLine = SshCommandFactory.installCommand,
                        timeoutMs = INSTALL_TIMEOUT_MS,
                    ),
                    distroId,
                )
                check(install.isSuccess) {
                    (install.stderr.ifBlank { install.stdout }).trim().ifBlank { "OpenSSH install failed" }
                }
            }

            _state.value = SshServiceState.Starting(distroId)
            val configure = linuxRuntime.execute(
                ShellCommand(
                    commandLine = SshCommandFactory.configureCommand(
                        config.copy(authorizedKeys = authorizedKeys),
                        password,
                        // Always listen on all IPv4 interfaces: both localhost 127.0.0.1 and LAN devices can connect,
                        // and connection won't break when Wi-Fi IP changes; readiness probe hits 127.0.0.1.
                        listenAddress = "0.0.0.0",
                    ),
                    timeoutMs = CONFIGURE_TIMEOUT_MS,
                ),
                distroId,
            )
            check(configure.isSuccess) {
                (configure.stderr.ifBlank { configure.stdout }).trim().ifBlank { "SSH config validation failed" }
            }

            // Stop possibly stale sshd still holding port (after host proot killed, sandbox
            // sshd may become orphan and keep listening), wait for port release before
            // binding to avoid "Address already in use".
            cleanupStaleSshd(distroId, config.port)

            val handle = serviceLauncher.start(
                LocalServiceSpec(
                    serviceId = SERVICE_ID,
                    port = config.port,
                    startupTimeoutMs = STARTUP_TIMEOUT_MS,
                ),
            ) {
                linuxRuntime.startBackground(
                    id = PROCESS_ID,
                    toolId = LOG_ID,
                    type = ProcessType.SERVICE,
                    distroId = distroId,
                    command = ShellCommand(
                        commandLine = SshCommandFactory.startCommand,
                        timeoutMs = Long.MAX_VALUE,
                    ),
                )
            }
            serviceProcess = handle.process
            serviceDistroId = distroId
            activePort = config.port
            _state.value = SshServiceState.Running(
                distroId,
                config.port,
                host = localIpv4Address() ?: "Device LAN IP",
            )
            acquireWakeLock()
            acquireWifiLock()
            monitor(handle.process, distroId)
        } catch (cancellation: CancellationException) {
            _state.value = SshServiceState.Stopped(distroId)
            throw cancellation
        } catch (throwable: Throwable) {
            serviceProcess = null
            serviceDistroId = null
            _state.value = SshServiceState.Failed(distroId, throwable.message ?: "SSH service start failed")
            throw throwable
        }
    }

    suspend fun stop() = serviceMutex.withLock {
        val distroId = serviceDistroId ?: linuxRuntime.activeDistroId.value
        stopLocked()
        refresh(distroId)
    }

    fun logs(): Flow<List<String>> = linuxRuntime.observeBackgroundLogs(LOG_ID)

    private suspend fun restartIfRunning(distroId: String) {
        if (serviceDistroId == distroId && serviceProcess?.session?.isAlive == true) {
            serviceMutex.withLock { stopLocked() }
            start(distroId)
        }
    }

    private suspend fun stopLocked() {
        monitorJob?.cancel()
        monitorJob = null
        val distroId = serviceDistroId
        val port = activePort
        try {
            serviceLauncher.stop(SERVICE_ID)
            if (distroId != null && port != null) cleanupStaleSshd(distroId, port)
        } finally {
            serviceProcess = null
            serviceDistroId = null
            activePort = null
            releaseWakeLock()
        }
    }

    private suspend fun readConfig(distroId: String) = SshRuntimeConfig(
        port = preferences.port(distroId).first(),
        authorizedKeys = preferences.authorizedKeys(distroId).first(),
        passwordAuthEnabled = preferences.passwordAuthEnabled(distroId).first(),
    )

    private fun monitor(process: ManagedProcess, distroId: String) {
        monitorJob?.cancel()
        monitorJob = managerScope.launch {
            while (isActive && process.session.isAlive) delay(PROCESS_POLL_INTERVAL_MS)
            if (isActive && serviceProcess === process) {
                serviceProcess = null
                serviceDistroId = null
                releaseWakeLock()
                val detail = linuxRuntime.getBackgroundLogs(LOG_ID).takeLast(3).joinToString("\n")
                _state.value = SshServiceState.Failed(
                    distroId,
                    detail.ifBlank { "SSH service process exited" },
                )
            }
        }
    }

    /** Clear possibly stale sshd still holding port, and wait for port to be truly released. */
    private suspend fun cleanupStaleSshd(distroId: String, port: Int) {
        try {
            linuxRuntime.execute(
                ShellCommand(
                    commandLine = KILL_STALE_SSHD_COMMAND,
                    timeoutMs = KILL_STALE_TIMEOUT_MS,
                ),
                distroId,
            )
        } catch (_: Throwable) {
            // Runtime may be shutting down; best effort, port release wait below is fallback.
        }
        val freed = withTimeoutOrNull(PORT_FREE_TIMEOUT_MS) {
            while (isPortBusy(port)) delay(PORT_POLL_INTERVAL_MS)
            true
        }
        check(freed == true) { "SSH port $port still occupied by old process, cannot start" }
    }

    private suspend fun isPortBusy(port: Int): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", port), PORT_CONNECT_TIMEOUT_MS)
            }
        }.isSuccess
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = runCatching {
            context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        }.getOrNull() ?: return
        val lock = wakeLock ?: pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "Taixu::LinuxRuntime",
        ).also { it.setReferenceCounted(false) }
        wakeLock = lock
        runCatching { lock.acquire(WAKE_LOCK_TIMEOUT_MS) }
    }

    private fun acquireWifiLock() {
        if (wifiLock?.isHeld == true) return
        val wifi = runCatching {
            context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        }.getOrNull() ?: return
        val lock = wifiLock ?: wifi.createWifiLock(
            // WIFI_MODE_FULL_HIGH_PERF deprecated in API 34+, but FULL_LOW_LATENCY has different semantics (low latency != high throughput),
            // keep original mode to preserve behavior; system optimizes scheduling on Android 13+.
            @Suppress("DEPRECATION") WifiManager.WIFI_MODE_FULL_HIGH_PERF,
            "Taixu::LinuxRuntime",
        ).also { it.setReferenceCounted(false) }
        wifiLock = lock
        runCatching { lock.acquire() }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { lock ->
            runCatching { if (lock.isHeld) lock.release() }
        }
        wakeLock = null
        wifiLock?.let { lock ->
            runCatching { if (lock.isHeld) lock.release() }
        }
        wifiLock = null
    }

    companion object {
        const val LOG_ID = "system-ssh"
        private const val SERVICE_ID = "taixu-ssh"
        private const val PROCESS_ID = "taixu-sshd"
        private const val PROBE_TIMEOUT_MS = 15_000L
        private const val INSTALL_TIMEOUT_MS = 10 * 60 * 1000L
        private const val CONFIGURE_TIMEOUT_MS = 30_000L
        // PRoot first sshd launch may exceed 15s on slow devices; process early exit
        // still fails LocalServiceLauncher immediately, so extended port wait won't mask crashes.
        private const val STARTUP_TIMEOUT_MS = 60_000L
        private const val PROCESS_POLL_INTERVAL_MS = 1_000L
        private const val KILL_STALE_TIMEOUT_MS = 5_000L
        private const val PORT_FREE_TIMEOUT_MS = 5_000L
        private const val PORT_POLL_INTERVAL_MS = 100L
        private const val PORT_CONNECT_TIMEOUT_MS = 250
        private const val WAKE_LOCK_TIMEOUT_MS = 12 * 60 * 60 * 1000L

        // End sshd session: graceful terminate, wait for fallback, then force kill; pidfile as fallback.
        val KILL_STALE_SSHD_COMMAND = """
            if command -v pkill >/dev/null 2>&1; then
              pkill -f 'ssh[d] .*taixu_sshd_config' 2>/dev/null || true
            fi
            if test -f /tmp/taixu-sshd.pid; then
              kill "${'$'}(cat /tmp/taixu-sshd.pid 2>/dev/null)" 2>/dev/null || true
            fi
            sleep 0.2
            if command -v pkill >/dev/null 2>&1; then
              pkill -9 -f 'ssh[d] .*taixu_sshd_config' 2>/dev/null || true
            fi
        """.trimIndent()

        fun localIpv4Address(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList().asSequence() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress && it.isSiteLocalAddress }
                ?.hostAddress
        }.getOrNull()
    }
}

internal object SshCommandFactory {
    val installCommand: String = """
        set -eu
        if command -v sshd >/dev/null 2>&1; then exit 0; fi
        if command -v apt-get >/dev/null 2>&1; then
          export DEBIAN_FRONTEND=noninteractive
          apt-get update -y
          apt-get install -y --no-install-recommends openssh-server
        elif command -v apk >/dev/null 2>&1; then
          apk add --no-cache openssh
        elif command -v pacman >/dev/null 2>&1; then
          pacman -Sy --noconfirm --needed openssh
        elif command -v dnf >/dev/null 2>&1; then
          dnf install -y openssh-server
        elif command -v zypper >/dev/null 2>&1; then
          zypper --non-interactive install openssh
        elif command -v xbps-install >/dev/null 2>&1; then
          xbps-install -Sy openssh
        else
          echo 'Current distro has no supported package manager' >&2
          exit 65
        fi
        command -v sshd >/dev/null 2>&1
    """.trimIndent()

    const val startCommand: String =
        "SSHD=\$(command -v sshd); exec \"\$SSHD\" -D -e -f /etc/ssh/taixu_sshd_config"

    fun configureCommand(
        config: SshRuntimeConfig,
        password: String? = null,
        listenAddress: String? = null,
    ): String {
        val keys = normalizeAuthorizedKeys(config.authorizedKeys)
        val normalizedPassword = if (config.passwordAuthEnabled) {
            normalizePassword(password ?: error("Please set SSH login password first"))
        } else {
            null
        }
        check(keys.isNotBlank() || normalizedPassword != null) { "Please set SSH login password or add at least one public key" }
        val resolvedListenAddress = listenAddress ?: "0.0.0.0"
        val passwordAuth = if (normalizedPassword != null) "yes" else "no"
        val permitRootLogin = if (normalizedPassword != null) "yes" else "prohibit-password"
        val sshdConfig = """
            Port ${config.port}
            ListenAddress $resolvedListenAddress
            Protocol 2
            HostKey /etc/ssh/ssh_host_ed25519_key
            HostKey /etc/ssh/ssh_host_rsa_key
            PermitRootLogin $permitRootLogin
            PubkeyAuthentication yes
            AuthorizedKeysFile .ssh/authorized_keys
            PasswordAuthentication $passwordAuth
            KbdInteractiveAuthentication no
            ChallengeResponseAuthentication no
            PermitEmptyPasswords no
            UsePAM no
            X11Forwarding no
            AllowAgentForwarding no
            AllowTcpForwarding yes
            GatewayPorts no
            PermitUserEnvironment no
            UseDNS no
            TCPKeepAlive yes
            PidFile /tmp/taixu-sshd.pid
            Subsystem sftp internal-sftp
            ClientAliveInterval 60
            ClientAliveCountMax 3
            LoginGraceTime 30
            MaxAuthTries 5
            LogLevel VERBOSE
        """.trimIndent() + "\n"
        val keysBase64 = Base64.getEncoder().encodeToString((keys + "\n").toByteArray())
        val configBase64 = Base64.getEncoder().encodeToString(sshdConfig.toByteArray())
        val passwordCommand = normalizedPassword?.let {
            val credentialBase64 = Base64.getEncoder().encodeToString("root:$it\n".toByteArray())
            "printf '%s' '$credentialBase64' | base64 -d | chpasswd"
        }.orEmpty()
        return """
            set -eu
            umask 077
            mkdir -p /root/.ssh /run/sshd /etc/ssh
            printf '%s' '$keysBase64' | base64 -d > /root/.ssh/authorized_keys
            printf '%s' '$configBase64' | base64 -d > /etc/ssh/taixu_sshd_config
            chmod 700 /root/.ssh
            chmod 600 /root/.ssh/authorized_keys /etc/ssh/taixu_sshd_config
            $passwordCommand
            ssh-keygen -A
            SSHD=${'$'}(command -v sshd)
            "${'$'}SSHD" -t -f /etc/ssh/taixu_sshd_config
        """.trimIndent()
    }

    fun normalizeAuthorizedKeys(value: String): String {
        require(value.length <= MAX_AUTHORIZED_KEYS_BYTES) { "SSH public key content too long" }
        val lines = value.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        require(lines.size <= MAX_AUTHORIZED_KEYS) { "Maximum $MAX_AUTHORIZED_KEYS SSH public keys allowed" }
        lines.forEach { line ->
            require(AUTHORIZED_KEY.matches(line)) { "Invalid SSH public key format: ${line.take(32)}" }
        }
        return lines.distinct().joinToString("\n")
    }

    fun normalizePassword(value: String): String {
        require(value.length in MIN_PASSWORD_LENGTH..MAX_PASSWORD_LENGTH) {
            "SSH password length must be between $MIN_PASSWORD_LENGTH..$MAX_PASSWORD_LENGTH characters"
        }
        require(value.none(Char::isISOControl) && ':' !in value) { "SSH password cannot contain control characters or colon" }
        return value
    }

    private const val MAX_AUTHORIZED_KEYS = 20
    private const val MAX_AUTHORIZED_KEYS_BYTES = 16 * 1024
    private const val MIN_PASSWORD_LENGTH = 8
    private const val MAX_PASSWORD_LENGTH = 128
    private val AUTHORIZED_KEY = Regex(
        "^(ssh-ed25519|ssh-rsa|ecdsa-sha2-nistp(?:256|384|521)|sk-ssh-ed25519@openssh\\.com|" +
            "sk-ecdsa-sha2-nistp256@openssh\\.com) [A-Za-z0-9+/]+={0,3}(?: [^\\r\\n]*)?$",
    )
}
