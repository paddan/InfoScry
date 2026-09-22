package infoscry.llm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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
    fun providerMeasurementIncludesModelAndAnthropicEnvelope() {
        val profile = LlmProfile("id", "cheap", LlmProvider.ANTHROPIC, "model-x", 10_000, 20, 0.0, 0.0, 0.0, true, "http://localhost")
        val request = LlmRequest(listOf(LlmMessage("user", "å")), maxOutputTokens = 20)
        val measured = RequestBudget(profile.contextWindow, safetyMargin = 10).measure(profile, request)
        assert(measured.serializedBytes > request.messages.single().content.toByteArray().size)
        assertEquals(30, measured.reservedTokens)
    }
}
