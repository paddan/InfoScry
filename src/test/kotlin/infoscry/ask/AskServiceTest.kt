package infoscry.ask

import infoscry.llm.LlmCompletion
import infoscry.llm.LlmCompletionClient
import infoscry.llm.LlmMessage
import infoscry.llm.LlmRequest
import infoscry.llm.TokenUsage
import kotlin.test.Test
import kotlin.test.assertEquals

/** Boundary contract tests use a captured provider rather than a network endpoint. */
class AskServiceTest {
    @Test
    fun citationCorrectionUsesExactlyOneNonStreamingCallAndCarriesUsage() {
        val calls = mutableListOf<LlmRequest>()
        val provider = object : LlmCompletionClient {
            override suspend fun complete(request: LlmRequest): LlmCompletion {
                calls += request
                return LlmCompletion("fixed [S1]", TokenUsage(7, 3))
            }
        }
        val result = kotlinx.coroutines.runBlocking {
            provider.complete(LlmRequest(listOf(LlmMessage("user", "fix [S999]")), maxOutputTokens = 20))
        }
        assertEquals(1, calls.size)
        assertEquals("fixed [S1]", result.text)
        assertEquals(7, result.usage.inputTokens)
    }
}
