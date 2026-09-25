package infoscry.llm

import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString

class RequestBudgetTest {
    @Test
    fun measuresEscapedSerializedMessagesAndReservesOutput() {
        val budget = RequestBudget(contextWindow = 1_000, safetyMargin = 10)
        val request = LlmRequest(
            messages = listOf(LlmMessage("system", "å\""), LlmMessage("user", "question")),
            maxOutputTokens = 20,
        )
        val measured = budget.measure(request)
        assertEquals(1_000, measured.contextWindow)
        assertEquals(30, measured.reservedTokens)
        assert(measured.serializedBytes > request.messages.sumOf { it.content.length })
        assert(budget.fit(request))
    }

    @Test
    fun refusesQuestionOnlyOverflowWithoutProviderCall() {
        val budget = RequestBudget(contextWindow = 10, safetyMargin = 1)
        val request = LlmRequest(listOf(LlmMessage("user", "this is far too long")), maxOutputTokens = 1)
        assertFailsWith<ContextBudgetExceeded> { budget.requireFits(request) }
    }

    @Test
    fun theMeasuredEnvelopeCountsToolExchanges() {
        val profile = LlmProfile("id", "cheap", LlmProvider.ANTHROPIC, "model-x", 10_000, 20, 0.0, 0.0, 0.0, true, "http://localhost")
        val budget = RequestBudget(profile.contextWindow, safetyMargin = 10)
        val withTools = LlmRequest(
            messages = listOf(
                LlmMessage("user", "Run both."),
                LlmMessage(
                    role = "assistant",
                    content = "",
                    toolCalls = listOf(
                        ToolCall("call_1", "find", """{"query":"nightfall"}"""),
                        ToolCall("call_2", "count", """{"year":2020}"""),
                    ),
                ),
                LlmMessage("tool", """[{"title":"x"}]""", toolCallId = "call_1"),
                LlmMessage("tool", "42", toolCallId = "call_2"),
            ),
            maxOutputTokens = 20,
        )
        val plain = LlmRequest(
            messages = listOf(
                LlmMessage("user", "Run both."),
                LlmMessage("assistant", ""),
                LlmMessage("user", """[{"title":"x"}]"""),
                LlmMessage("user", "42"),
            ),
            maxOutputTokens = 20,
        )

        val measuredWithTools = budget.measure(profile, withTools)
        val measuredPlain = budget.measure(profile, plain)
        assertTrue(
            measuredWithTools.serializedBytes > measuredPlain.serializedBytes,
            "a tool exchange must weigh more than the same text as plain messages",
        )
        val envelopeBytes = LlmJson.encodeToString(buildAnthropicMessagesRequest(profile.model, withTools))
            .toByteArray(StandardCharsets.UTF_8).size
        assertEquals(envelopeBytes, measuredWithTools.serializedBytes, "the measured bytes must be the shared envelope")
    }

    @Test
    fun providerMeasurementIncludesModelAndAnthropicEnvelope() {
        val profile = LlmProfile("id", "cheap", LlmProvider.ANTHROPIC, "model-x", 10_000, 20, 0.0, 0.0, 0.0, true, "http://localhost")
        val request = LlmRequest(listOf(LlmMessage("user", "å")), maxOutputTokens = 20)
        val measured = RequestBudget(profile.contextWindow, safetyMargin = 10).measure(profile, request)
        assert(measured.serializedBytes > request.messages.single().content.toByteArray().size)
        assertEquals(30, measured.reservedTokens)
    }
}
