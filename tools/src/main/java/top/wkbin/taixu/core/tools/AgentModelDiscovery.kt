package top.wkbin.taixu.core.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request

class AgentModelDiscovery(
    private val http: OkHttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun discover(
        provider: AgentProviderDefinition,
        baseUrl: String,
        apiKey: String?,
    ): List<String> = withContext(Dispatchers.IO) {
        val cleanBaseUrl = ProviderEndpointPolicy.normalizeUrl(baseUrl)
        val url = when {
            provider.id == "custom" -> "${cleanBaseUrl.trimEnd('/')}/models"
            cleanBaseUrl.isNotBlank() && cleanBaseUrl.trimEnd('/') != provider.baseUrl.trimEnd('/') -> "${cleanBaseUrl.trimEnd('/')}/models"
            provider.modelsUrl.isNotBlank() -> provider.modelsUrl
            else -> "${provider.baseUrl.trimEnd('/')}/models"
        }
        val targetUrl = ProviderEndpointPolicy.normalizeUrl(url)
        require(targetUrl.isNotBlank() && ProviderEndpointPolicy.isSafeBaseUrl(targetUrl)) { "Model discovery URL unsafe or empty" }
        val isAnthropic = provider.protocol == ProviderProtocol.ANTHROPIC ||
            targetUrl.contains("api.anthropic.com") ||
            provider.name.contains("anthropic", ignoreCase = true) ||
            provider.name.contains("claude", ignoreCase = true)
        val request = Request.Builder().url(targetUrl)
            .apply {
                when {
                    isAnthropic -> {
                        // Anthropic models endpoint uses x-api-key not Bearer
                        if (!apiKey.isNullOrBlank()) header("x-api-key", apiKey)
                        header("anthropic-version", "2023-06-01")
                    }
                    else -> if (!apiKey.isNullOrBlank()) header("Authorization", "Bearer $apiKey")
                }
            }
            .get().build()
        http.newCall(request).execute().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) {
                throw ModelDiscoveryResponseException("Failed to fetch models HTTP ${response.code}: ${body.take(200)}")
            }
            val mediaType = response.body.contentType()
            if (looksLikeHtml(body)) {
                throw ModelDiscoveryResponseException(
                    "Model endpoint returned HTML not JSON (HTTP ${response.code}, Content-Type: ${mediaType ?: "unknown"})",
                )
            }
            val root = runCatching { json.parseToJsonElement(body) as? JsonObject }
                .getOrElse { cause -> throw ModelDiscoveryResponseException("Model endpoint returned unparsable JSON", cause) }
                ?: throw ModelDiscoveryResponseException("Model endpoint JSON root is not an object")
            val ids = runCatching {
                root["data"]?.jsonArray?.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.content }
                    ?: root["models"]?.jsonArray?.mapNotNull { item ->
                        val obj = item.jsonObject
                        obj["name"]?.jsonPrimitive?.content ?: obj["model"]?.jsonPrimitive?.content
                    }.orEmpty()
            }.getOrElse { cause ->
                throw ModelDiscoveryResponseException("Model endpoint JSON structure unexpected", cause)
            }
            ids.filter(::isAgentModel).distinct().sorted()
        }
    }

    private fun looksLikeHtml(body: String): Boolean {
        val prefix = body.trimStart().take(32).lowercase()
        return prefix.startsWith("<!doctype html") || prefix.startsWith("<html")
    }

    private fun isAgentModel(id: String): Boolean {
        val value = id.lowercase()
        return MEDIA_OR_NON_CHAT.none { token -> value.contains(token) }
    }

    private companion object {
        val MEDIA_OR_NON_CHAT = listOf(
            "embedding", "embed-", "rerank", "whisper", "tts", "speech", "audio",
            "image", "imagen", "dall-e", "flux", "stable-diffusion", "recraft",
            "video", "veo", "sora", "moderation", "guard", "classifier",
        )
    }
}

class ModelDiscoveryResponseException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)
