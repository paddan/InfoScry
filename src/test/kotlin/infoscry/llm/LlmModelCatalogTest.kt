package infoscry.llm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * The model-catalog service: live OpenRouter-style metadata, curated overlay, local-is-free,
 * and the fallback path that keeps the catalog usable when a provider endpoint is down.
 */
class LlmModelCatalogTest {

    private val catalog = LlmModelCatalog()
    private val router: List<JsonObject> = Json.parseToJsonElement("""
        {"data":[{"id":"openai/gpt-4o","context_length":128000,
          "top_provider":{"max_completion_tokens":16384},
          "pricing":{"prompt":"0.0000025","completion":"0.00001","input_cache_read":"0.00000125"}}]}
    """).jsonObject["data"]!!.jsonArray.mapNotNull { element -> element as? JsonObject }

    @Test
    fun `openrouter live metadata scales per-token prices to per-million`() {
        val model = catalog.normalize(LlmProvider.OPENAI_COMPATIBLE, "https://openrouter.ai/api/v1", router).single()
        assertEquals("openai/gpt-4o", model.id)
        assertEquals(128_000, model.contextWindow)
        assertEquals(16_384, model.maxOutputTokens)
        assertEquals(2.5, model.inputPricePerMillion)
        assertEquals(10.0, model.outputPricePerMillion)
        assertEquals(1.25, model.cacheReadPricePerMillion)
        assertTrue(model.priceKnown)
    }

    @Test
    fun `a curated id fills settings and prices from the table`() {
        val model = catalog.normalize(
            LlmProvider.OPENAI_COMPATIBLE, "https://api.openai.com/v1",
            listOf(Json.parseToJsonElement("""{"id":"gpt-4o"}""").jsonObject),
        ).single()
        assertEquals(128_000, model.contextWindow)
        assertEquals(2.5, model.inputPricePerMillion)
        assertTrue(model.priceKnown)
    }

    @Test
    fun `an unknown id yields no fields and priceKnown false`() {
        val model = catalog.normalize(
            LlmProvider.OPENAI_COMPATIBLE, "https://api.openai.com/v1",
            listOf(Json.parseToJsonElement("""{"id":"gpt-99-unknown"}""").jsonObject),
        ).single()
        assertEquals(null, model.contextWindow)
        assertEquals(null, model.inputPricePerMillion)
        assertFalse(model.priceKnown)
    }

    @Test
    fun `a loopback endpoint is local and free`() {
        val model = catalog.normalize(
            LlmProvider.OPENAI_COMPATIBLE, "http://localhost:11434/v1",
            listOf(Json.parseToJsonElement("""{"id":"gemma3:12b"}""").jsonObject),
        ).single()
        assertEquals(0.0, model.inputPricePerMillion)
        assertEquals(0.0, model.outputPricePerMillion)
        assertTrue(model.priceKnown)
    }

    @Test
    fun `a loopback endpoint zeroes even curated prices`() {
        val model = catalog.normalize(
            LlmProvider.OPENAI_COMPATIBLE, "http://localhost:11434/v1",
            listOf(Json.parseToJsonElement("""{"id":"gpt-4o"}""").jsonObject),
        ).single()
        assertEquals(0.0, model.inputPricePerMillion)
        assertEquals(0.0, model.outputPricePerMillion)
        assertEquals(0.0, model.cacheReadPricePerMillion)
        assertTrue(model.priceKnown)
    }

    @Test
    fun `a loopback fallback is local and free too`() {
        val model = catalog.fallback(
            LlmProvider.OPENAI_COMPATIBLE, "http://127.0.0.1:4321/v1",
        ).first { it.id == "gpt-4o" }
        assertEquals(0.0, model.inputPricePerMillion)
        assertEquals(0.0, model.outputPricePerMillion)
        assertEquals(0.0, model.cacheReadPricePerMillion)
        assertTrue(model.priceKnown)
    }

    @Test
    fun `an entry without a string id is skipped`() {
        val entries = listOf(
            Json.parseToJsonElement("""{"missing":"id"}""").jsonObject,
            Json.parseToJsonElement("""{"id":"gpt-4o"}""").jsonObject,
        )
        assertEquals(listOf("gpt-4o"), catalog.normalize(LlmProvider.OPENAI_COMPATIBLE, "https://api.openai.com/v1", entries).map { it.id })
    }

    @Test
    fun `a curated entry with a null price is not priceKnown`() {
        val data = ProviderCatalogData(
            presets = emptyList(),
            knownModels = mapOf("gpt-partial" to KnownModel(provider = LlmProvider.OPENAI_COMPATIBLE, contextWindow = 8_000)),
        )
        val model = LlmModelCatalog(catalog = data).normalize(
            LlmProvider.OPENAI_COMPATIBLE, "https://api.openai.com/v1",
            listOf(Json.parseToJsonElement("""{"id":"gpt-partial"}""").jsonObject),
        ).single()
        assertEquals(8_000, model.contextWindow)
        assertEquals(null, model.inputPricePerMillion)
        assertFalse(model.priceKnown)
    }

    @Test
    fun `models url trims a trailing slash and picks the anthropic path`() {
        assertEquals("https://api.openai.com/v1/models", catalog.modelsUrl(LlmProvider.OPENAI_COMPATIBLE, "https://api.openai.com/v1/"))
        assertEquals("https://api.openai.com/v1/models", catalog.modelsUrl(LlmProvider.OPENAI_COMPATIBLE, "https://api.openai.com/v1"))
        assertEquals("https://api.anthropic.com/v1/models", catalog.modelsUrl(LlmProvider.ANTHROPIC, "https://api.anthropic.com"))
    }

    @Test
    fun `fetch reads the provider list and sends the key from the environment`() = runBlocking {
        val fake = FakeOpenAiServer(listOf(FakeOpenAiResponse(statusCode = 200, body = """{"data":[{"id":"gpt-4o"}]}""")))
        try {
            var seen: String? = null
            val service = LlmModelCatalog(lookup = { if (it == "TEST_KEY") "secret-value" else null })
            val result = service.fetch(LlmProvider.OPENAI_COMPATIBLE, fake.url, "TEST_KEY")
            seen = fake.authorization
            assertTrue(result.live)
            assertEquals(listOf("gpt-4o"), result.models.map { it.id })
            assertEquals("Bearer secret-value", seen)
        } finally {
            fake.close()
        }
    }

    @Test
    fun `fetch falls back to the static list when the provider is unreachable`() = runBlocking {
        val result = LlmModelCatalog().fetch(LlmProvider.OPENAI_COMPATIBLE, "http://127.0.0.1:1", null)
        assertFalse(result.live)
        assertTrue(result.models.any { it.id == "gpt-4o" })
    }

    @Test
    fun `fetch falls back when the body is not JSON`() = runBlocking {
        val fake = FakeOpenAiServer(listOf(FakeOpenAiResponse(statusCode = 200, body = "<html>not json</html>")))
        try {
            val result = LlmModelCatalog().fetch(LlmProvider.OPENAI_COMPATIBLE, fake.url, null)
            assertFalse(result.live)
        } finally {
            fake.close()
        }
    }

    @Test
    fun `fetch falls back when the provider answers with an error status`() = runBlocking {
        val fake = FakeOpenAiServer(listOf(FakeOpenAiResponse(statusCode = 502, body = "<html>bad gateway</html>")))
        try {
            val result = LlmModelCatalog().fetch(LlmProvider.OPENAI_COMPATIBLE, fake.url, null)
            assertFalse(result.live)
        } finally {
            fake.close()
        }
    }

    @Test
    fun `fetch times out and falls back when the provider hangs`() = runBlocking {
        val fake = FakeOpenAiServer(listOf(FakeOpenAiResponse(statusCode = 200, body = """{"data":""", stream = true, declaredLength = 4_096, holdMillis = 500)))
        try {
            val result = LlmModelCatalog(requestTimeoutMillis = 200).fetch(LlmProvider.OPENAI_COMPATIBLE, fake.url, null)
            assertFalse(result.live)
        } finally {
            fake.close()
        }
    }
}