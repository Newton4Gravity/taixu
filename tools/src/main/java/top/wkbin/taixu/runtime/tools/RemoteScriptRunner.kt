package top.wkbin.taixu.runtime.tools

import top.wkbin.taixu.runtime.LinuxRuntime
import top.wkbin.taixu.runtime.shell.CommandResult
import top.wkbin.taixu.runtime.shell.ShellCommand
import java.net.URI
import kotlinx.coroutines.delay

/**
 * Downloads an official installer into the Linux runtime before executing it.
 *
 * The script is never piped directly from curl into a shell: it is stored with
 * mode 0700, checked for non-empty content, and removed through a shell trap
 * on every exit path. URLs are deliberately allow-listed because these are
 * application-owned installers, not arbitrary manifest commands.
 */
class RemoteScriptRunner(
    private val linuxRuntime: LinuxRuntime,
) {
    suspend fun run(
        spec: RemoteScriptSpec,
        environment: Map<String, String> = emptyMap(),
    ): CommandResult {
        val uri = runCatching { URI(spec.url) }.getOrElse {
            throw IllegalArgumentException("Official installer script URL invalid", it)
        }
        require(uri.scheme.equals("https", ignoreCase = true)) {
            "Official installer script must use HTTPS"
        }
        require(uri.userInfo == null && uri.fragment == null && (uri.port == -1 || uri.port == 443)) {
            "Official installer script URL disallows userinfo, fragment, or non-standard port"
        }
        require(uri.host.orEmpty().lowercase() in ALLOWED_HOSTS) {
            "Disallows executing unregistered installer script source: ${uri.host.orEmpty()}"
        }
        require(SAFE_NAME.matches(spec.name)) { "Invalid installer script name" }

        // Installer scripts contain large downloads (e.g., Hermes 236MB git shallow clone), mobile network/proxy truncation is occasional failure; official scripts have cleanup logic for interrupted clones, retry is safe.
        val attempts = spec.retries + 1
        for (attempt in 0 until attempts) {
            val result = linuxRuntime.execute(
                ShellCommand(
                    commandLine = buildCommand(spec),
                    environment = environment,
                    timeoutMs = INSTALLER_TIMEOUT_MS,
                ),
            )
            if (result.isSuccess || attempt == attempts - 1) return result
            delay(RETRY_DELAY_MS)
        }
        error("unreachable")
    }

    private fun buildCommand(spec: RemoteScriptSpec): String {
        val scriptPath = "/tmp/taixu-installer-${spec.name}.sh"
        val quotedUrl = shellQuote(spec.url)
        val quotedPath = shellQuote(scriptPath)
        val arguments = spec.arguments.joinToString(" ") { shellQuote(it) }
        return buildString {
            append("set -eu; umask 077; ")
            append("script_path=$quotedPath; ")
            append("trap 'rm -f \"\$script_path\"' EXIT HUP INT TERM; ")
            append("curl -fsSL --connect-timeout 10 --max-time 60 --max-redirs 0 --proto '=https' --tlsv1.2 $quotedUrl -o \"\$script_path\"; ")
            append("test -s \"\$script_path\"; chmod 700 \"\$script_path\"; ")
            spec.sha256?.let { checksum ->
                require(SHA256.matches(checksum)) { "Installer script SHA-256 format invalid" }
                append("printf '%s  %s\\n' ${shellQuote(checksum)} \"\$script_path\" | sha256sum -c -; ")
            }
            append("bash \"\$script_path\"")
            if (arguments.isNotBlank()) append(" $arguments")
        }
    }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\\\"'\\\"'")}'"

    companion object {
        // Official installer scripts need npm install / binary downloads (OpenClaw has many deps, via proxy may exceed 10 min), far exceeding single command default 30s timeout.
        private const val INSTALLER_TIMEOUT_MS = 20 * 60 * 1000L
        private const val RETRY_DELAY_MS = 3_000L
        private val SAFE_NAME = Regex("[a-z0-9-]{1,32}")
        private val SHA256 = Regex("[a-fA-F0-9]{64}")
        private val ALLOWED_HOSTS = setOf(
            "chatgpt.com",
        )
    }
}

data class RemoteScriptSpec(
    val name: String,
    val url: String,
    val arguments: List<String> = emptyList(),
    val sha256: String? = null,
    val retries: Int = 0,
) {
    val host: String
        get() = runCatching {
            java.net.URI(url).host.orEmpty().lowercase()
        }.getOrDefault("")
}
