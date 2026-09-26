package infoscry.llm

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/** Provider catalogs that price per token (OpenRouter-style) are scaled to the per-1M-token unit. */
private const val PRICE_PER_MILLION: Double = 1_000_000.0

/**
 * Endpoint hosts that are this machine. A local server needs no API key and nothing is billed:
 * unknown models there are served as local-and-free instead of unknown-priced.
 */
private val LOOPBACK_HOSTS: Set<String> = setOf("localhost", "127.0.0.1", "::1")

/**
 * One model as the model catalog serves it: the [KnownModel] shape plus `priceKnown`, which tells
 * consumers whether a per-1M-token price is safe to display without a caveat.
 */
@Serializable
data class CatalogModel(
    val id: String,
    val contextWindow: Int? = null,
    val maxOutputTokens: Int? = null,
    val inputPricePerMillion: Double? = null,
    val outputPricePerMillion: Double? = null,
    val cacheReadPricePerMillion: Double? = null,
    val priceKnown: Boolean = false,
)

/** The result of one provider fetch: [live] says the endpoint answered; [models] is always usable. */
@Serializable
data class CatalogResponse(
    val live: Boolean,
    val models: List<CatalogModel>,
)

/**
 * Fetches a provider's `/models` list and normalizes it into [CatalogModel]s.
 *
 * Live metadata (OpenRouter-style `context_length`, `top_provider.max_completion_tokens` and
 * `pricing.*` per-token strings) wins over the curated [KnownModel] table, which fills gaps. The key
 * value is read through [lookup] at call time and appears only in the `Authorization`/`x-api-key`
 * header: it can never reach a response, an error, or a log line, and this service logs nothing.
 *
 * ## Failure discipline
 *
 * A timeout, any exception, a non-2xx status, or an unparsable body all return
 * `CatalogResponse(live = false, models = fallback(...))` — the preset's static list, or the known
 * models for that provider — so a down endpoint degrades to a still-correct static catalog instead of
 * failing the page. `fetch` never throws for a provider problem; only caller cancellation escapes.
 */
class LlmModelCatalog(
    private val catalog: ProviderCatalogData = ProviderCatalog.load(),
    private val httpClient: HttpClient = HttpClient(CIO),
    private val lookup: (String) -> String? = System::getenv,
    private val requestTimeoutMillis: Long = 5_000,
) {

    /**
     * Fetches and normalizes the model list for [provider] at [endpoint], falling back to the static
     * catalog whenever the live call fails in any way. The `Authorization` or `x-api-key` header is
     * sent only when [apiKeyEnvironmentVariable] resolves to a value.
     */
    suspend fun fetch(provider: LlmProvider, endpoint: String, apiKeyEnvironmentVariable: String?): CatalogResponse {
        val body = try {
            withTimeoutOrNull(requestTimeoutMillis) {
                val response = httpClient.get(modelsUrl(provider, endpoint)) {
                    apiKeyEnvironmentVariable?.let { variable ->
                        lookup(variable)?.let { key ->
                            when (provider) {
                                LlmProvider.OPENAI_COMPATIBLE -> header(HttpHeaders.Authorization, "Bearer $key")
                                LlmProvider.ANTHROPIC -> {
                                    header("x-api-key", key)
                                    header("anthropic-version", "2023-06-01")
                                }
                            }
                        }
                    }
                }
                if (response.status.isSuccess()) response.bodyAsText() else null
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            null
        }
        if (body == null) {
            return CatalogResponse(live = false, models = fallback(provider, endpoint))
        }
        return try {
            val data = LlmJson.parseToJsonElement(body).jsonObject["data"] as? JsonArray
            val entries = data?.mapNotNull { element -> element as? JsonObject } ?: emptyList()
            CatalogResponse(live = true, models = normalize(provider, endpoint, entries))
        } catch (failure: Throwable) {
            CatalogResponse(live = false, models = fallback(provider, endpoint))
        }
    }

    /** The provider's model-list URL: `/models` for OpenAI-compatible, `/v1/models` for Anthropic. */
    internal fun modelsUrl(provider: LlmProvider, endpoint: String): String = when (provider) {
        LlmProvider.OPENAI_COMPATIBLE -> "${endpoint.trimEnd('/')}/models"
        LlmProvider.ANTHROPIC -> "${endpoint.trimEnd('/')}/v1/models"
    }

    /**
     * Normalizes one provider `data` list into [CatalogModel]s. Live fields win; the curated table
     * fills gaps for a matching provider. A loopback endpoint with no known price is local-and-free.
     */
    internal fun normalize(provider: LlmProvider, endpoint: String, entries: List<JsonObject>): List<CatalogModel> {
        val loopback = isLoopback(endpoint)
        return entries.mapNotNull { entry -> normalizeEntry(provider, loopback, entry) }
            .sortedBy { it.id }
    }

    /**
     * The static answer for a provider that did not answer: the [ProviderPreset.staticModels] for the
     * endpoint when a preset matches, otherwise the [ProviderCatalogData.knownModels] ids for the
     * provider, with the same curated metadata overlay [normalize] applies (no live fields).
     */
    internal fun fallback(provider: LlmProvider, endpoint: String): List<CatalogModel> {
        val ids = ProviderCatalog.presetFor(catalog, endpoint)?.staticModels ?: knownIdsFor(provider)
        return ids.map { id ->
            val known = knownModel(id, provider)
            CatalogModel(
                id = id,
                contextWindow = known?.contextWindow,
                maxOutputTokens = known?.maxOutputTokens,
                inputPricePerMillion = known?.inputPricePerMillion,
                outputPricePerMillion = known?.outputPricePerMillion,
                cacheReadPricePerMillion = known?.cacheReadPricePerMillion,
                priceKnown = known?.inputPricePerMillion != null && known?.outputPricePerMillion != null,
            )
        }
    }

    private fun normalizeEntry(provider: LlmProvider, loopback: Boolean, entry: JsonObject): CatalogModel? {
        val id = stringId(entry) ?: return null
        val known = knownModel(id, provider)
        val topProvider = entry["top_provider"] as? JsonObject
        val pricing = entry["pricing"] as? JsonObject
        var inputPrice = scaledPrice(pricing, "prompt") ?: known?.inputPricePerMillion
        var outputPrice = scaledPrice(pricing, "completion") ?: known?.outputPricePerMillion
        var cacheReadPrice = scaledPrice(pricing, "input_cache_read") ?: known?.cacheReadPricePerMillion
        if (loopback && inputPrice == null && outputPrice == null) {
            inputPrice = 0.0
            outputPrice = 0.0
            cacheReadPrice = 0.0
        }
        return CatalogModel(
            id = id,
            contextWindow = intField(entry, "context_length") ?: known?.contextWindow,
            maxOutputTokens = intField(topProvider, "max_completion_tokens") ?: known?.maxOutputTokens,
            inputPricePerMillion = inputPrice,
            outputPricePerMillion = outputPrice,
            cacheReadPricePerMillion = cacheReadPrice,
            priceKnown = inputPrice != null && outputPrice != null,
        )
    }

    private fun knownModel(id: String, provider: LlmProvider): KnownModel? =
        catalog.knownModels[id]?.takeIf { it.provider == provider }

    private fun knownIdsFor(provider: LlmProvider): List<String> =
        catalog.knownModels.filterValues { it.provider == provider }.keys.sorted()

    private fun stringId(entry: JsonObject): String? {
        val primitive = entry["id"] as? JsonPrimitive
        if (primitive == null || !primitive.isString) return null
        return primitive.content
    }

    private fun intField(entry: JsonObject?, name: String): Int? {
        val element = if (entry == null) null else entry[name]
        return (element as? JsonPrimitive)?.intOrNull
    }

    /** A `pricing.*` value, which providers write in USD per token; the catalog prices per 1M tokens. */
    private fun scaledPrice(entry: JsonObject?, name: String): Double? {
        val element = if (entry == null) null else entry[name]
        val raw = (element as? JsonPrimitive)?.doubleOrNull
        return if (raw == null) null else raw * PRICE_PER_MILLION
    }

    private fun isLoopback(endpoint: String): Boolean {
        val host = try {
            URI(endpoint).host?.trimStart('[')?.trimEnd(']')
        } catch (_: Exception) {
            null
        }
        return host != null && host in LOOPBACK_HOSTS
    }
}