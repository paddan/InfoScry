package infoscry.llm

import infoscry.investigate.*
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Validation and serialization of the secret-free profile type. */
class LlmProfilesTest {

    private val valid = LlmProfile(
        id = "profile-1",
        name = "Claude investigation",
        provider = LlmProvider.ANTHROPIC,
        model = "claude-sonnet-4",
        contextWindow = 200_000,
        maxOutputTokens = 32_000,
        inputPricePerMillion = 3.0,
        outputPricePerMillion = 15.0,
        cacheReadPricePerMillion = 0.3,
        enabled = true,
        apiKeyEnvironmentVariable = "ANTHROPIC_API_KEY",
    )

    @Test
    fun `effort overrides validate every cap and native provider contract`() {
        val invalid = listOf(
            InvestigationLimits(0, 1, 10), InvestigationLimits(51, 1, 10),
            InvestigationLimits(1, -1, 10), InvestigationLimits(1, 101, 10),
            InvestigationLimits(1, 1, 9), InvestigationLimits(1, 1, 1801),
        )
        invalid.forEach { limits ->
            assertFailsWith<IllegalArgumentException> {
                valid.copy(effortOverrides = mapOf(EffortLevel.LOW to EffortPreset(limits)))
            }
        }
        listOf(0, 1023, valid.maxOutputTokens).forEach { tokens ->
            assertFailsWith<IllegalArgumentException> {
                valid.copy(effortOverrides = mapOf(EffortLevel.HIGH to EffortPreset(InvestigationLimits(), NativeReasoning.AnthropicThinkingBudget(tokens))))
            }
        }
        val thinking = mapOf(EffortLevel.HIGH to EffortPreset(InvestigationLimits(), NativeReasoning.AnthropicThinkingBudget(1024)))
        val accepted = valid.copy(effortOverrides = thinking)
        assertFailsWith<IllegalArgumentException> { accepted.copy(maxOutputTokens = 1024) }
        assertFailsWith<IllegalArgumentException> { accepted.copy(provider = LlmProvider.OPENAI_COMPATIBLE) }
        assertFailsWith<IllegalArgumentException> {
            valid.copy(effortOverrides = mapOf(EffortLevel.LOW to EffortPreset(InvestigationLimits(), NativeReasoning.OpenAiReasoningEffort("high"))))
        }
        listOf("low", "medium", "high").forEach { value ->
            valid.copy(provider = LlmProvider.OPENAI_COMPATIBLE, effortOverrides = mapOf(EffortLevel.LOW to EffortPreset(InvestigationLimits(), NativeReasoning.OpenAiReasoningEffort(value))))
        }
        assertFailsWith<IllegalArgumentException> {
            valid.copy(provider = LlmProvider.OPENAI_COMPATIBLE, effortOverrides = mapOf(EffortLevel.LOW to EffortPreset(InvestigationLimits(), NativeReasoning.OpenAiReasoningEffort("xhigh"))))
        }
    }

    @Test
    fun `a profile validates its bounds and its environment variable name`() {
        assertFailsWith<IllegalArgumentException> { valid.copy(contextWindow = 0) }
        assertFailsWith<IllegalArgumentException> { valid.copy(maxOutputTokens = 0) }
        assertFailsWith<IllegalArgumentException> { valid.copy(inputPricePerMillion = -1.0) }
        assertFailsWith<IllegalArgumentException> { valid.copy(apiKeyEnvironmentVariable = "not a name!") }
        // A lower-case env var name is valid; an entirely blank model is not.
        assertTrue { valid.copy(apiKeyEnvironmentVariable = "openai_api_key").apiKeyEnvironmentVariable != null }
        assertFailsWith<IllegalArgumentException> { valid.copy(model = " ") }
    }

    @Test
    fun `an endpoint must parse as an absolute URL when provided`() {
        assertTrue { valid.copy(endpoint = "https://api.example.com/v1").endpoint.isNotBlank() }
        assertFailsWith<IllegalArgumentException> { valid.copy(endpoint = "not a url") }
    }

    @Test
    fun `an endpoint carrying userinfo credentials is refused without repeating them`() {
        val secret = "hunter2-not-a-real-credential"

        val refused = assertFailsWith<IllegalArgumentException> {
            valid.copy(endpoint = "https://user:$secret@api.example.com/v1")
        }

        // The key belongs in the environment variable; this endpoint is returned verbatim by the profile
        // API, so a URL carrying a credential is refused, and the failure does not repeat the value either.
        assertFalse(refused.message.orEmpty().contains(secret), "a URL that carried a credential is not echoed")
        // A path or query that merely contains an `@` is not credentials.
        assertTrue { valid.copy(endpoint = "https://api.example.com/a@b/v1?token=abc").endpoint.isNotBlank() }
    }

    @Test
    fun `serialization carries the variable name, never a secret value`() {
        val json = Json { encodeDefaults = true }
        val encoded = json.encodeToString(valid)

        assertTrue(encoded.contains("ANTHROPIC_API_KEY"), "the variable name is present for the caller")
        assertFalse(encoded.contains("sk-ant-"), "a credential must never be serialized")
    }

    @Test
    fun `capability is a measured probe, not a declared checkbox`() {
        val unchecked = valid.copy(toolCallingMeasured = null)
        assertFalse(unchecked.toolCallingSupported, "a profile without a measurement is not tool capable")

        assertTrue(valid.copy(toolCallingMeasured = true).toolCallingSupported)
        assertFalse(valid.copy(toolCallingMeasured = false).toolCallingSupported)
    }

    @Test
    fun `key presence is exposed as a boolean, and only from the injected lookup`() {
        val secrets = mapOf("ANTHROPIC_API_KEY" to "sk-ant-super-secret")
        assertTrue(valid.keyAvailable { secrets[it] }, "presence is reported when the variable is set")
        assertFalse(valid.keyAvailable { null }, "absence is reported, not the value")
    }
}
