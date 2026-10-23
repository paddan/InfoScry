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

    private val profile = LlmProfile("id", "p", LlmProvider.OPENAI_COMPATIBLE, "model", 100_000, 100, 0.0, 0.0, 0.0, true)
    private val collection = infoscry.domain.CollectionId("c")
    private fun group(id: String, vararg texts: String) = infoscry.glossary.GlossaryGroup(
        id, collection, infoscry.glossary.GlossaryRules.normalize(texts.map { infoscry.glossary.GlossaryTermInput(it) }), "t", "t",
    )
    private val stockholm = group("s", "Stockholm", "Sthlm")
    private val kth = group("k", "KTH", "Kungliga Tekniska högskolan")
    private val glossary = infoscry.glossary.GlossaryPrompt(infoscry.glossary.Glossary(collection, listOf(stockholm, kth)), investigate = true)
    private val messages = listOf(
        LlmMessage("system", "rules"),
        LlmMessage("user", "Vad hände i Sthlm?"),
        LlmMessage("assistant", "", toolCalls = listOf(ToolCall("c1", "search", "{}"))),
        LlmMessage("tool", "rektor på KTH " + "x".repeat(400), toolCallId = "c1"),
    )
    private val groups = listOf(0 to 1, 1 to 2)

    @Test fun `the question's and the tool results' groups ride on the system message`() {
        val pruned = pruneWithGlossary(glossary, profile, RequestBudget(profile.contextWindow), messages, groups, emptyList(), 100)

        val system = pruned.messages.first().content
        assertTrue(system.startsWith("rules\n\nGlossary for this collection."))
        assertTrue(system.contains(infoscry.glossary.GlossaryPrompt.INVESTIGATE_NOTE))
        assertTrue(system.contains("- Stockholm = Sthlm"))
        assertTrue(system.contains("- KTH = Kungliga Tekniska högskolan"))
        assertEquals(messages.drop(1), pruned.messages.drop(1))
    }

    @Test fun `a tool result is kept and its group left out when only the result fits`() {
        val withQuestion = messages.toMutableList().also { it[0] = LlmMessage("system", glossary.withBlock("rules", listOf(stockholm))) }
        val exact = RequestBudget(100_000, safetyMargin = 0).measure(profile, LlmRequest(withQuestion, emptyList(), maxOutputTokens = 100)).serializedBytes
        val budget = RequestBudget(exact + 100 + 1_024)

        val pruned = pruneWithGlossary(glossary, profile, budget, messages, groups, emptyList(), 100)

        assertEquals(withQuestion, pruned.messages)
        assertTrue(pruned.omittedGroupIds.isEmpty())
    }

    @Test fun `without a glossary it prunes exactly as before`() {
        val budget = RequestBudget(profile.contextWindow)
        assertEquals(
            pruneToFit(profile, budget, messages, groups, emptyList(), 100),
            pruneWithGlossary(null, profile, budget, messages, groups, emptyList(), 100),
        )
    }
}
