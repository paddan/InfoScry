package infoscry.llm

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/** One selectable provider configuration from [ProviderCatalog.load]. The endpoint is what a profile must hold. */
@Serializable
data class ProviderPreset(
    val id: String,
    val label: String,
    val provider: LlmProvider,
    val endpoint: String,
    val apiKeyEnvironmentVariable: String? = null,
    val staticModels: List<String> = emptyList(),
)

/** Curated settings for a model id a provider lists, keyed by that exact id in [ProviderCatalogData.knownModels]. */
@Serializable
data class KnownModel(
    val provider: LlmProvider,
    val contextWindow: Int? = null,
    val maxOutputTokens: Int? = null,
    val inputPricePerMillion: Double? = null,
    val outputPricePerMillion: Double? = null,
    val cacheReadPricePerMillion: Double? = null,
)

/** The full contents of `/llm/providers.json`. Prices are USD per 1,000,000 tokens; absent fields mean unknown. */
@Serializable
data class ProviderCatalogData(
    val presets: List<ProviderPreset>,
    val knownModels: Map<String, KnownModel> = emptyMap(),
)

/** Loads the provider preset catalog resource and matches presets by endpoint. */
object ProviderCatalog {
    const val RESOURCE_PATH: String = "/llm/providers.json"

    private val json = Json { ignoreUnknownKeys = true }

    fun load(): ProviderCatalogData {
        val stream = ProviderCatalog::class.java.getResourceAsStream(RESOURCE_PATH)
            ?: error("the provider catalog $RESOURCE_PATH is not on the classpath")
        return try {
            stream.use { json.decodeFromString<ProviderCatalogData>(it.readBytes().decodeToString()) }
        } catch (e: Exception) {
            throw IllegalStateException("the provider catalog $RESOURCE_PATH is not valid JSON", e)
        }
    }

    fun presetFor(data: ProviderCatalogData, endpoint: String): ProviderPreset? =
        data.presets.firstOrNull { it.endpoint == endpoint }
}