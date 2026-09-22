package infoscry.ask

import infoscry.domain.CollectionId
import infoscry.llm.ContextBudgetExceeded
import infoscry.llm.LlmError
import infoscry.llm.LlmEvent
import infoscry.llm.LlmProfile
import infoscry.llm.LlmRequest
import infoscry.llm.LlmStreamingClient
import infoscry.llm.PromptService
import infoscry.llm.RequestBudget
import infoscry.search.SearchFilters
import infoscry.search.SearchMode
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
fun interface AskPersistence { fun save(request: AskRequest, answer: String, evidence: List<Evidence>, citations: CitationValidation) }

class AskService(
    private val search: SearchService,
    private val prompt: PromptService,
    private val client: (LlmProfile) -> LlmStreamingClient,
    private val persistence: AskPersistence = AskPersistence { _, _, _, _ -> },
    private val collectionInstructions: (CollectionId) -> String? = { null },
) {
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
            )
            // requireFits is deliberately immediately before stream: an irreducible failure makes zero calls.
            budget.requireFits(packed.request)
            val answer = StringBuilder()
            var usageInput = 0L
            var usageOutput = 0L
            client(request.profile).stream(packed.request).collect { event ->
                when (event) {
                    is LlmEvent.TextDelta -> { answer.append(event.text); emit(AskEvent.Delta(event.text)) }
                    is LlmEvent.Usage -> {
                        usageInput += event.usage.inputTokens; usageOutput += event.usage.outputTokens
                        emit(AskEvent.Usage(event.usage.inputTokens, event.usage.outputTokens))
                    }
                    is LlmEvent.Completed, is LlmEvent.ToolCallReady -> Unit
                }
            }
            val validation = CitationValidator().validate(answer.toString(), packed.evidences)
            validation.valid.forEach { emit(AskEvent.Citation(it, true)) }
            validation.invalid.forEach { emit(AskEvent.Citation(it, false)) }
            persistence.save(request, answer.toString(), packed.evidences, validation)
            emit(AskEvent.Done(answer.toString(), packed.evidences))
        } catch (budgetFailure: ContextBudgetExceeded) {
            emit(AskEvent.Error("CONTEXT_BUDGET_EXCEEDED", "the request is too large for the configured model"))
        } catch (failure: LlmError) {
            emit(AskEvent.Error("LLM_REQUEST_FAILED", "the configured model could not answer the question"))
        } catch (failure: Exception) {
            emit(AskEvent.Error("ASK_FAILED", "the question could not be answered"))
        }
    }
}
