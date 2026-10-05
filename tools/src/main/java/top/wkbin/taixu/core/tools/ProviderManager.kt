package top.wkbin.taixu.core.tools

import kotlinx.coroutines.flow.first
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

open class ProviderManager {
    private val providerRepository: ProviderRepository?

    constructor(providerRepository: ProviderRepository) {
        this.providerRepository = providerRepository
    }

    constructor() {
        this.providerRepository = null
    }

    open suspend fun environment(): Map<String, String> {
        val repo = providerRepository ?: return emptyMap()
        val provider = repo.provider.first().trim().lowercase()
        val apiKey = repo.readApiKey()
        val baseUrl = ProviderEndpointPolicy.normalizeUrl(repo.baseUrl.first())
        val model = repo.model.first().trim()
        val environment = linkedMapOf<String, String>()
        val variable = when (provider) {
            "openai" -> "OPENAI_API_KEY"
            "anthropic", "claude" -> "ANTHROPIC_API_KEY"
            "google", "gemini" -> "GEMINI_API_KEY"
            "deepseek" -> "DEEPSEEK_API_KEY"
            "openrouter" -> "OPENROUTER_API_KEY"
            else -> null
        }
        if (!apiKey.isNullOrBlank() && variable != null) environment[variable] = apiKey
        if (baseUrl.isNotBlank() && ProviderEndpointPolicy.isSafeBaseUrl(baseUrl)) {
            environment["LINUXAI_BASE_URL"] = baseUrl
            providerEnvironmentName(provider, "BASE_URL")?.let { environment[it] = baseUrl }
        }
        if (model.isNotBlank()) {
            environment["LINUXAI_MODEL"] = model
            providerEnvironmentName(provider, "MODEL")?.let { environment[it] = model }
        }
        return environment
    }

    private fun providerEnvironmentName(provider: String, suffix: String): String? = when (provider) {
        "openai" -> "OPENAI_$suffix"
        "anthropic", "claude" -> "ANTHROPIC_$suffix"
        "google", "gemini" -> "GEMINI_$suffix"
        "deepseek" -> "DEEPSEEK_$suffix"
        "openrouter" -> "OPENROUTER_$suffix"
        else -> null
    }
}

object ProviderEndpointPolicy {
    private val DASH_REGEX = Regex("[\u2010\u2011\u2012\u2013\u2014\u2015\u2212\uFE58\uFE63\uFF0D\u00AD]")
    private val ZERO_WIDTH_REGEX = Regex("[\u200B\u200C\u200D\uFEFF]")

    /**
     * Normalizes input URL by cleaning fullwidth/Unicode symbols, spaces, and protocol headers:
     * 1. Removes zero-width chars and non-breaking spaces;
     * 2. Normalizes various Unicode hyphens/dashes (e.g., U+2011, U+2013, U+FF0D) to ASCII '-';
     * 3. Converts fullwidth colon, slash, period to standard ASCII chars;
     * 4. If no explicit http:// or https://, auto-completes based on host traits (private/local default http://, public domains default https://).
     */
    fun normalizeUrl(raw: String): String {
        var clean = raw.trim()
            .replace(ZERO_WIDTH_REGEX, "")
            .replace("\u00A0", " ")
            .replace(DASH_REGEX, "-")
            .replace('\uFF1A', ':')
            .replace('\uFF0F', '/')
            .replace('\u3002', '.')
            .replace('\uFF0E', '.')
        val hasExplicitScheme = clean.contains("://") || clean.startsWith("javascript:", ignoreCase = true) || clean.startsWith("data:", ignoreCase = true) || clean.startsWith("about:", ignoreCase = true) || clean.startsWith("file:", ignoreCase = true)
        if (clean.isNotBlank() && !hasExplicitScheme) {
            val hostPart = clean.substringBefore('/').substringBefore(':').lowercase()
            clean = if (isLocalOrPrivateHost(hostPart)) "http://$clean" else "https://$clean"
        }
        return clean
    }

    fun isSafeBaseUrl(value: String): Boolean {
        val normalized = normalizeUrl(value)
        if (normalized.isBlank()) return false

        val httpUrl = normalized.toHttpUrlOrNull()
        if (httpUrl != null) {
            if (httpUrl.username.isNotEmpty() || httpUrl.password.isNotEmpty()) return false
            val host = httpUrl.host.lowercase()
            if (host.isBlank()) return false
            return httpUrl.scheme.equals("https", ignoreCase = true) ||
                httpUrl.scheme.equals("http", ignoreCase = true)
        }

        val uri = runCatching { java.net.URI(normalized) }.getOrNull() ?: return false
        if (uri.userInfo != null || uri.host.isNullOrBlank()) return false
        return uri.scheme.equals("https", ignoreCase = true) ||
            uri.scheme.equals("http", ignoreCase = true)
    }

    private fun isLocalOrPrivateHost(host: String): Boolean {
        val h = host.trim().lowercase().removePrefix("[").removeSuffix("]")
        if (h in LOOPBACK_HOSTS) return true
        if (h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".internal") || h.endsWith(".home.arpa")) return true
        val ipv4Parts = h.split('.').mapNotNull { it.toIntOrNull() }
        if (ipv4Parts.size == 4 && ipv4Parts.all { it in 0..255 }) {
            val (a, b, _, _) = ipv4Parts
            if (a == 127) return true
            if (a == 10) return true
            if (a == 192 && b == 168) return true
            if (a == 172 && b in 16..31) return true
            if (a == 100 && b in 64..127) return true
            if (a == 169 && b == 254) return true
        }
        return false
    }

    private val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "::1")
}
