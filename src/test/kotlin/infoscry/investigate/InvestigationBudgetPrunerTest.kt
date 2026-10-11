package infoscry.investigate

import infoscry.llm.*
import kotlinx.serialization.json.*
import kotlin.test.*

class InvestigationBudgetPrunerTest {
    @Test fun `current thinking exchanges stay intact even when their opaque payload exceeds budget`() {
        val profile = LlmProfile("id", "native", LlmProvider.ANTHROPIC, "model", 9000, 2048, 0.0, 0.0, 0.0, true)
        val content = AnthropicAssistantContent(listOf(buildJsonObject { put("type", "thinking"); put("thinking", "x".repeat(10000)); put("signature", "signed") }))
        val messages = listOf(LlmMessage("system", "rules"), LlmMessage("user", "question"), LlmMessage("assistant", "", toolCalls = listOf(ToolCall("c1", "search", "{}")), assistantContent = content), LlmMessage("tool", "result", toolCallId = "c1"))
        val budget = RequestBudget(profile.contextWindow)
        val native = NativeReasoning.AnthropicThinkingBudget(1024)
        val pruned = pruneToFit(profile, budget, messages, listOf(0 to 1, 1 to 2, 2 to 4), emptyList(), 2048, reasoning = native)
        assertEquals(messages, pruned.messages)
        assertTrue(pruned.omittedGroupIds.isEmpty())
        assertFalse(budget.measure(profile, LlmRequest(pruned.messages, maxOutputTokens = 2048, reasoning = native)).fits)
    }
}
