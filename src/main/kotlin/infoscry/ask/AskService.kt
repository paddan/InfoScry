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
fun interface AskPersistence { fun save(request: AskRequest, answer: String, evidence: List<Evidence>, citations: CitationValidation, inputTokens: Long, outputTokens: Long) }

class AskService(
    private val search: SearchService,
    private val prompt: PromptService,
    private val client: (LlmProfile) -> LlmStreamingClient,
    private val persistence: AskPersistence,
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
                request.profile,
            )
            // requireFits is deliberately immediately before stream: an irreducible failure makes zero calls.
            if (!budget.measure(request.profile, packed.request).fits) throw ContextBudgetExceeded()
            val answer = StringBuilder()
            var usageInput = 0L
            var usageOutput = 0L
            val modelClient = client(request.profile)
            modelClient.stream(packed.request).collect { event ->
                when (event) {
                    is LlmEvent.TextDelta -> { answer.append(event.text); emit(AskEvent.Delta(event.text)) }
                    is LlmEvent.Usage -> {
                        usageInput += event.usage.inputTokens; usageOutput += event.usage.outputTokens
                        emit(AskEvent.Usage(event.usage.inputTokens, event.usage.outputTokens))
                    }
                    is LlmEvent.Completed, is LlmEvent.ToolCallReady -> Unit
                }
            }
            var finalAnswer = answer.toString()
            var validation = CitationValidator().validate(finalAnswer, packed.evidences)
            if (validation.invalid.isNotEmpty()) {
                val correction = LlmRequest(
                    messages = listOf(
                        infoscry.llm.LlmMessage("system", "Return the answer with citations only from these allowed IDs: ${packed.evidences.joinToString { it.id }}. Do not add any other citation."),
                        infoscry.llm.LlmMessage("user", finalAnswer),
                    ), maxOutputTokens = request.profile.maxOutputTokens,
                )
                if (!budget.measure(request.profile, correction).fits) throw ContextBudgetExceeded()
                if (modelClient !is LlmCompletionClient) throw IllegalStateException("the configured provider cannot perform citation correction")
                val correctionResult = modelClient.complete(correction)
                usageInput += correctionResult.usage.inputTokens
                usageOutput += correctionResult.usage.outputTokens
                finalAnswer = correctionResult.text
                validation = CitationValidator().validate(finalAnswer, packed.evidences)
            }
            validation.valid.forEach { emit(AskEvent.Citation(it, true)) }
            validation.invalid.forEach { emit(AskEvent.Citation(it, false)) }
            persistence.save(request, finalAnswer, packed.evidences, validation, usageInput, usageOutput)
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
