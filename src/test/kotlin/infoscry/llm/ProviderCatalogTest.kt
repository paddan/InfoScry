package infoscry.llm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ProviderCatalogTest {
    @Test fun `loads the five presets with their endpoints and key variables`() {
        val data = ProviderCatalog.load()
        assertEquals(listOf("OPENAI", "ANTHROPIC", "OLLAMA", "OPENROUTER", "CUSTOM"), data.presets.map { it.id })
        val openai = data.presets.first { it.id == "OPENAI" }
        assertEquals(LlmProvider.OPENAI_COMPATIBLE, openai.provider)
        assertEquals("https://api.openai.com/v1", openai.endpoint)
        assertEquals("OPENAI_API_KEY", openai.apiKeyEnvironmentVariable)
        assertEquals("https://api.anthropic.com", data.presets.first { it.id == "ANTHROPIC" }.endpoint)
        assertEquals(LlmProvider.ANTHROPIC, data.presets.first { it.id == "ANTHROPIC" }.provider)
        assertTrue(data.presets.first { it.id == "OLLAMA" }.apiKeyEnvironmentVariable == null)
    }

    @Test fun `known models carry provider and context`() {
        val data = ProviderCatalog.load()
        val gpt4o = assertNotNull(data.knownModels["gpt-4o"])
        assertEquals(LlmProvider.OPENAI_COMPATIBLE, gpt4o.provider)
        assertEquals(128_000, gpt4o.contextWindow)
        assertEquals(LlmProvider.ANTHROPIC, assertNotNull(data.knownModels["claude-opus-4-8"]).provider)
    }

    @Test fun `presetFor matches an endpoint exactly and returns null otherwise`() {
        val data = ProviderCatalog.load()
        assertEquals("OLLAMA", ProviderCatalog.presetFor(data, "http://localhost:11434/v1")?.id)
        assertEquals("OPENAI", ProviderCatalog.presetFor(data, "https://api.openai.com/v1")?.id)
        assertEquals(null, ProviderCatalog.presetFor(data, "https://unknown.example/v1"))
    }
}