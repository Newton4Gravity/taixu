package top.wkbin.taixu.core.tools

import kotlinx.coroutines.flow.first
import top.wkbin.taixu.core.database.AiModelEntity
import top.wkbin.taixu.core.database.AiModelRepository
import java.util.UUID

/**
 * Unified AI model profile writer: handles secretRef generation, Key persistence, active profile maintenance.
 * Settings / Chat / Onboarding model save/delete must go through this class to avoid per-site entity assembly.
 */
class AiProfileWriter(
    private val aiModelDao: AiModelRepository,
    private val providerRepository: ProviderRepository,
) {

    /** Parse multi-line Key text into deduplicated Key list */
    fun parseApiKeys(raw: String): List<String> = raw
        .lineSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()
        .toList()

    data class UpsertRequest(
        val id: String? = null,
        val name: String,
        val provider: String,
        /** Single model or comma-separated multi-model string */
        val model: String,
        val baseUrl: String,
        /** Multi-line Key text; empty preserves existing Keys for this profile */
        val apiKey: String = "",
        val requestsPerMinutePerKey: Int = 0,
        val temperature: Float? = null,
        val maxTokens: Int? = null,
        val topP: Float? = null,
        val reasoningMode: String? = null,
        val reasoningEffort: String? = null,
        val toolCallMode: String? = null,
        val contextTokens: Int? = null,
        /** Per-model compaction budget override: recent token cap to keep when compaction triggers (null = disabled). */
        val compactionKeepRecentTokens: Int? = null,
        /** Per-model compaction budget override: tokens reserved for LLM response (null = built-in default). */
        val compactionReserveTokens: Int? = null,
        val customHeaders: String = "",
        val pureChatMode: Boolean = false,
        val visionEnabled: Boolean = true,
        val imageGenerationEnabled: Boolean = false,
        val responseApiEnabled: Boolean = false,
        /** Anthropic Prompt Caching toggle (default on). */
        val promptCachingEnabled: Boolean = true,
        /** Whether to use 1-hour cache TTL. */
        val promptCacheTtl1h: Boolean = false,
    )

    suspend fun upsertProfile(request: UpsertRequest) {
        val existing = aiModelDao.observeAll().first()
        val old = request.id?.let { aiModelDao.findById(it) }
        val modelId = request.id ?: UUID.randomUUID().toString()
        val secretRef = old?.secretRef?.takeIf { it.isNotBlank() } ?: "model_${modelId.replace("-", "")}"
        val submittedKeys = parseApiKeys(request.apiKey)
        val existingKeys = old?.let { providerRepository.readModelApiKeys(secretRef) }.orEmpty()
        // No active profiles, or editing current active profile: clear active flag before write
        if (existing.none { it.isActive } || old?.isActive == true) aiModelDao.clearActive()
        aiModelDao.upsert(
            AiModelEntity(
                id = modelId,
                name = request.name.trim(),
                provider = request.provider.trim(),
                model = request.model.trim(),
                baseUrl = request.baseUrl.trim(),
                secretRef = secretRef,
                isActive = old?.isActive ?: existing.none { it.isActive },
                createdAt = old?.createdAt ?: System.currentTimeMillis(),
                temperature = request.temperature,
                maxTokens = request.maxTokens,
                topP = request.topP,
                reasoningMode = request.reasoningMode?.ifBlank { null },
                reasoningEffort = request.reasoningEffort?.ifBlank { null },
                toolCallMode = request.toolCallMode?.ifBlank { null },
                contextTokens = request.contextTokens,
                compactionKeepRecentTokens = request.compactionKeepRecentTokens,
                compactionReserveTokens = request.compactionReserveTokens,
                customHeaders = request.customHeaders.trim(),
                pureChatMode = request.pureChatMode,
                visionEnabled = request.visionEnabled,
                imageGenerationEnabled = request.imageGenerationEnabled,
                responseApiEnabled = request.responseApiEnabled,
                promptCachingEnabled = request.promptCachingEnabled,
                promptCacheTtl1h = request.promptCacheTtl1h,
                apiKeyCount = submittedKeys.ifEmpty { existingKeys }.size,
                requestsPerMinutePerKey = request.requestsPerMinutePerKey.coerceAtLeast(0),
            ),
        )
        if (submittedKeys.isNotEmpty()) providerRepository.setModelApiKeys(secretRef, submittedKeys)
    }

    suspend fun deleteProfile(id: String) {
        aiModelDao.findById(id)?.secretRef?.takeIf { it.isNotBlank() }?.let { providerRepository.removeModelApiKey(it) }
        aiModelDao.delete(id)
    }
}
