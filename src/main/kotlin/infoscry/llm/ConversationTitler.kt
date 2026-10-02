package infoscry.llm

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Writes the short title a stored conversation shows in the reader's history, from its opening
 * question, using the conversation's own locked profile — the same provider, endpoint and model
 * that answered it.
 *
 * Each call builds its own client exactly the way the Ask and Investigate routes build one, so the
 * fake provider that answers a route test answers the title call too. The call is a one-shot
 * non-streaming completion against [LlmCompletionClient], and it is never recorded: it writes no
 * `model_calls` row and touches no `usage_totals`, so what the panel reports stays the cost of the
 * answer. Every failure (provider error, malformed completion, empty result, persistence failure)
 * is silent: the title stays unset and the row falls back to its question in the list endpoints,
 * exactly as the reader sees conversations stored before this change.
 */
class ConversationTitler(
    private val prompt: PromptService,
    private val persist: (conversationId: String, title: String) -> Unit,
) {

    /**
     * Ends a new conversation with a title written from its [openingQuestion].
     *
     * Never throws: the title is a best-effort side effect on a request that has already answered,
     * so no titling failure may fail the answer or surface to the reader. Cancellation is rethrown
     * so a reader who has already gone still unwinds the request promptly.
     */
    suspend fun title(conversationId: String, openingQuestion: String, profile: LlmProfile) {
        // The title is a best-effort dispatch of the opening question, so a profile that is switched off is
        // not called at all: the caller's route already refused it for the answer, and a profile disabled
        // between the answer and this call must not receive the question either.
        if (runCatching { profile.requireDispatchable() }.isFailure) return
        // The client is built *inside* the try because building one can throw — a profile whose endpoint is
        // blank is accepted by the profile and the API while the client constructors refuse it — and this
        // method's contract is that no titling failure reaches the answer: the row keeps its question
        // fallback, exactly as it does when the call itself fails.
        try {
            val client = when (profile.provider) {
                LlmProvider.OPENAI_COMPATIBLE -> OpenAiCompatibleClient(profile, System::getenv)
                LlmProvider.ANTHROPIC -> AnthropicClient(profile, System::getenv)
            }
            // A stalled provider cannot be allowed to hold a finished answer's route open forever:
            // the bounded completion makes a timeout a silent titling failure exactly like any other,
            // while caller cancellation still arrives as CancellationException and propagates.
            val completed = withTimeoutOrNull(TITLE_TIMEOUT_MILLIS) {
                client.complete(
                    LlmRequest(
                        messages = listOf(
                            LlmMessage("system", prompt.titlePrompt()),
                            LlmMessage("user", openingQuestion),
                        ),
                        maxOutputTokens = TITLE_OUTPUT_LIMIT,
                    ),
                )
            }
            completed?.let { result -> normalize(result.text)?.let { title -> persist(conversationId, title) } }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (ignored: Exception) {
            // Silent by design: a failed title call never fails the answer it follows, and a
            // question or answer must never appear in an error path.
        }
    }

    /**
     * One line with whitespace squeezed, length-capped so a runaway completion cannot turn a row
     * into a paragraph. Null when nothing usable came back.
     */
    private fun normalize(raw: String): String? {
        val singleLine = raw.lines().joinToString(" ") { it.trim() }.trim()
        return singleLine.take(TITLE_LENGTH_CAP).takeIf { it.isNotEmpty() }
    }

    private companion object {
        /** Far below any provider minimum; a title fits one short completion. */
        const val TITLE_OUTPUT_LIMIT = 64

        /** The design caps titles at roughly eighty characters. */
        const val TITLE_LENGTH_CAP = 80

        /**
         * A stalled title provider must not hold a finished answer's route open longer than this;
         * a timeout is a silent titling failure and the row keeps its question fallback.
         */
        const val TITLE_TIMEOUT_MILLIS = 5_000L
    }
}