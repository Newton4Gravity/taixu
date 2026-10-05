package top.wkbin.taixu.core.tools

import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import top.wkbin.taixu.core.database.AiModelRepository
import top.wkbin.taixu.core.model.AiModelProfileBundle
import top.wkbin.taixu.core.model.AiModelProfileExport
import java.util.UUID

/**
 * AI model profile import/export codec: sole implementation of entity <-> export JSON mapping.
 * Shared by Settings and Onboarding to ensure consistent fault tolerance rules.
 */
class AiProfileBackupCodec(
    private val aiModelDao: AiModelRepository,
    private val providerRepository: ProviderRepository,
    private val profileWriter: AiProfileWriter,
) {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
        coerceInputValues = true
    }

    suspend fun exportAll(includeApiKeys: Boolean): String {
        val models = aiModelDao.observeAll().first()
        val bundle = AiModelProfileBundle(
            schemaVersion = 1,
            exportedAt = System.currentTimeMillis(),
            source = "TaiXu",
            profiles = models.map { entityToExport(it, readKeys(it, includeApiKeys)) },
        )
        return json.encodeToString(bundle)
    }

    suspend fun exportSingle(modelId: String, includeApiKeys: Boolean): String? {
        val entity = aiModelDao.findById(modelId) ?: return null
        return json.encodeToString(entityToExport(entity, readKeys(entity, includeApiKeys)))
    }

    /** Compatible with Bundle / single profile / profile array JSON shapes */
    fun parseProfiles(rawJson: String): Result<List<AiModelProfileExport>> {
        val trimmed = rawJson.trim()
        if (trimmed.isBlank()) return Result.failure(IllegalArgumentException("Import content empty"))
        return runCatching {
            when {
                trimmed.startsWith("{") -> {
                    val bundleResult = runCatching { json.decodeFromString<AiModelProfileBundle>(trimmed) }
                    if (bundleResult.isSuccess && bundleResult.getOrThrow().profiles.isNotEmpty()) {
                        bundleResult.getOrThrow().profiles
                    } else {
                        val single = json.decodeFromString<AiModelProfileExport>(trimmed)
                        listOf(single)
                    }
                }
                trimmed.startsWith("[") -> json.decodeFromString<List<AiModelProfileExport>>(trimmed)
                else -> throw IllegalArgumentException("Invalid JSON format, content must start with { or [")
            }
        }
    }

    /** Import profiles one by one and return count of successfully imported */
    suspend fun importProfiles(rawJson: String): Result<Int> {
        val parseResult = parseProfiles(rawJson)
        if (parseResult.isFailure) return Result.failure(parseResult.exceptionOrNull() ?: RuntimeException("JSON parse failed"))
        val profiles = parseResult.getOrThrow()
        if (profiles.isEmpty()) return Result.failure(IllegalArgumentException("No valid model profiles detected"))

        val existing = aiModelDao.observeAll().first()
        val importedIds = mutableListOf<String>()

        for (profile in profiles) {
            val modelStr = profile.model.trim()
            val providerStr = profile.provider.trim().ifBlank { "Custom" }
            if (modelStr.isBlank() && profile.name.isBlank()) continue

            val modelId = profile.id?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
            val keys = profileWriter.parseApiKeys(
                (profile.apiKeys + listOfNotNull(profile.apiKey)).joinToString("\n"),
            )
            val nameStr = profile.name.trim().ifBlank {
                val firstModel = modelStr.split(",").firstOrNull()?.trim().orEmpty()
                firstModel.ifBlank { providerStr }
            }

            profileWriter.upsertProfile(
                AiProfileWriter.UpsertRequest(
                    id = modelId,
                    name = nameStr,
                    provider = providerStr,
                    model = modelStr.ifBlank { nameStr },
                    baseUrl = profile.baseUrl,
                    apiKey = keys.joinToString("\n"),
                    requestsPerMinutePerKey = profile.requestsPerMinutePerKey,
                    temperature = profile.temperature,
                    maxTokens = profile.maxTokens,
                    topP = profile.topP,
                    reasoningMode = profile.reasoningMode,
                    reasoningEffort = profile.reasoningEffort,
                    toolCallMode = profile.toolCallMode,
                    contextTokens = profile.contextTokens,
                    customHeaders = profile.customHeaders,
                    pureChatMode = profile.pureChatMode,
                    visionEnabled = profile.visionEnabled,
                    imageGenerationEnabled = profile.imageGenerationEnabled,
                    responseApiEnabled = profile.responseApiEnabled,
                    promptCachingEnabled = profile.promptCachingEnabled,
                    promptCacheTtl1h = profile.promptCacheTtl1h,
                ),
            )
            importedIds.add(modelId)
        }

        // Consistent with existing behavior: only set first imported as active if none were active before
        if (existing.none { it.isActive } && importedIds.isNotEmpty()) {
            aiModelDao.clearActive()
            aiModelDao.setActive(importedIds.first())
        }

        return if (importedIds.isNotEmpty()) Result.success(importedIds.size)
        else Result.failure(IllegalArgumentException("No valid models parsed"))
    }

    private suspend fun readKeys(entity: top.wkbin.taixu.core.database.AiModelEntity, includeApiKeys: Boolean): List<String> {
        return if (includeApiKeys && entity.secretRef.isNotBlank()) {
            providerRepository.readModelApiKeys(entity.secretRef)
        } else {
            emptyList()
        }
    }

    private fun entityToExport(
        entity: top.wkbin.taixu.core.database.AiModelEntity,
        keys: List<String>,
    ) = AiModelProfileExport(
        id = entity.id,
        name = entity.name,
        provider = entity.provider,
        model = entity.model,
        baseUrl = entity.baseUrl,
        apiKeys = keys,
        apiKey = keys.firstOrNull(),
        requestsPerMinutePerKey = entity.requestsPerMinutePerKey,
        temperature = entity.temperature,
        maxTokens = entity.maxTokens,
        topP = entity.topP,
        reasoningMode = entity.reasoningMode,
        reasoningEffort = entity.reasoningEffort,
        toolCallMode = entity.toolCallMode,
        contextTokens = entity.contextTokens,
        customHeaders = entity.customHeaders,
        pureChatMode = entity.pureChatMode,
        visionEnabled = entity.visionEnabled,
        imageGenerationEnabled = entity.imageGenerationEnabled,
        responseApiEnabled = entity.responseApiEnabled,
        promptCachingEnabled = entity.promptCachingEnabled,
        promptCacheTtl1h = entity.promptCacheTtl1h,
    )
}
