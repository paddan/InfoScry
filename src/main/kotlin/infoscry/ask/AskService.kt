package infoscry.ask

import infoscry.domain.CollectionId
import infoscry.llm.ContextBudgetExceeded
import infoscry.llm.LlmError
import infoscry.llm.LlmEvent
import infoscry.llm.LlmProfile
import infoscry.llm.LlmRequest
import infoscry.llm.LlmStreamingClient
import infoscry.llm.LlmCompletionClient
import infoscry.llm.PromptService
import infoscry.llm.RequestBudget
import infoscry.search.SearchFilters
import infoscry.search.SearchMode
import infoscry.search.SearchOutcome
import infoscry.search.SearchService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

data class AskRequest(val collectionId: CollectionId, val question: String, val profile: LlmProfile)

sealed interface AskEvent {
    data class Delta(val text: String) : AskEvent
    data class Usage(val inputTokens: Long, val outputTokens: Long) : AskEvent
    data class Citation(val evidenceId: String, val valid: Boolean) : AskEvent
    data class Done(val answer: String, val evidence: List<Evidence>) : AskEvent
    data class Error(val code: String, val message: String) : AskEvent
}

/** Minimal persistence seam; production wiring can store the immutable evidence/citation snapshot. */
data class CorrectionSnapshot(
    val answer: String,
    val usage: infoscry.llm.TokenUsage,
    val citations: CitationValidation,
)

/** The Ask-specific retrieval boundary keeps the orchestration independently testable. */
fun interface AskSearch {
    fun search(question: String, mode: SearchMode, filters: SearchFilters): SearchOutcome
}

fun interface AskPersistence {
    fun save(
        request: AskRequest,
        answer: String,
        evidence: List<Evidence>,
        initialUsage: infoscry.llm.TokenUsage,
        initialCitations: CitationValidation,
        correction: CorrectionSnapshot?,
    )
}

class AskService(
    private val search: AskSearch,
    private val prompt: PromptService,
    private val client: (LlmProfile) -> LlmStreamingClient,
    private val persistence: AskPersistence,
    private val collectionInstructions: (CollectionId) -> String? = { null },
) {
    constructor(
        search: SearchService,
        prompt: PromptService,
        client: (LlmProfile) -> LlmStreamingClient,
        persistence: AskPersistence,
        collectionInstructions: (CollectionId) -> String? = { null },
    ) : this(AskSearch(search::search), prompt, client, persistence, collectionInstructions)

    fun ask(request: AskRequest): Flow<AskEvent> = flow {
        if (request.question.isBlank()) {
            emit(AskEvent.Error("INVALID_REQUEST", "question must not be blank")); return@flow
        }
        try {
            val outcome = search.search(request.question, SearchMode.HYBRID, SearchFilters(request.collectionId))
            val budget = RequestBudget(request.profile.contextWindow)
            val packed = ContextPacker().pack(
                request.question,
                prompt.composeAsk(collectionInstructions(request.collectionId)),
                outcome.hits.take(30),
                budget,
                request.profile.maxOutputTokens,
                request.profile,
            )
            // requireFits is deliberately immediately before stream: an irreducible failure makes zero calls.
            if (!budget.measure(request.profile, packed.request).fits) throw ContextBudgetExceeded()
            val answer = StringBuilder()
            var initialUsage = infoscry.llm.TokenUsage(0, 0)
            val modelClient = client(request.profile)
            modelClient.stream(packed.request).collect { event ->
                when (event) {
                    is LlmEvent.TextDelta -> { answer.append(event.text); emit(AskEvent.Delta(event.text)) }
                    is LlmEvent.Usage -> {
                        initialUsage = initialUsage + event.usage
                        emit(AskEvent.Usage(event.usage.inputTokens, event.usage.outputTokens))
                    }
                    is LlmEvent.Completed, is LlmEvent.ToolCallReady -> Unit
                }
            }
            var finalAnswer = answer.toString()
            val initialCitations = CitationValidator().validate(finalAnswer, packed.evidences)
            var validation = initialCitations
            var correctionSnapshot: CorrectionSnapshot? = null
            if (validation.invalid.isNotEmpty()) {
                val correction = LlmRequest(
                    messages = listOf(
                        infoscry.llm.LlmMessage("system", "Return the answer with citations only from these allowed IDs: ${packed.evidences.joinToString { it.id }}. Do not add any other citation. Evidence follows:\n" + packed.evidences.joinToString("\n") { "<evidence id=\"${it.id}\" locator=\"${it.locatorLabel}\">${it.text}</evidence>" }),
                        infoscry.llm.LlmMessage("user", finalAnswer),
                    ), maxOutputTokens = request.profile.maxOutputTokens,
                )
                if (!budget.measure(request.profile, correction, stream = false).fits) throw ContextBudgetExceeded()
                if (modelClient !is LlmCompletionClient) throw IllegalStateException("the configured provider cannot perform citation correction")
                val correctionResult = modelClient.complete(correction)
                finalAnswer = correctionResult.text
                correctionSnapshot = CorrectionSnapshot(
                    answer = finalAnswer,
                    usage = correctionResult.usage,
                    citations = CitationValidator().validate(finalAnswer, packed.evidences),
                )
                validation = correctionSnapshot.citations
            }
            validation.valid.forEach { emit(AskEvent.Citation(it, true)) }
            validation.invalid.forEach { emit(AskEvent.Citation(it, false)) }
            persistence.save(request, finalAnswer, packed.evidences, initialUsage, initialCitations, correctionSnapshot)
            emit(AskEvent.Done(finalAnswer, packed.evidences))
        } catch (budgetFailure: ContextBudgetExceeded) {
            emit(AskEvent.Error("CONTEXT_BUDGET_EXCEEDED", "the request is too large for the configured model"))
        } catch (failure: LlmError) {
            emit(AskEvent.Error("LLM_REQUEST_FAILED", "the configured model could not answer the question"))
        } catch (failure: Exception) {
            emit(AskEvent.Error("ASK_FAILED", "the question could not be answered"))
        }
    }
}

private operator fun infoscry.llm.TokenUsage.plus(other: infoscry.llm.TokenUsage) =
    infoscry.llm.TokenUsage(
        inputTokens + other.inputTokens,
        outputTokens + other.outputTokens,
        cacheReadTokens + other.cacheReadTokens,
    )
