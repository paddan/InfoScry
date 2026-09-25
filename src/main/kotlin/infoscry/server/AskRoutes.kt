package infoscry.server

import infoscry.AppContext
import infoscry.ask.AskEvent
import infoscry.ask.AskRequest
import infoscry.ask.AskService
import infoscry.domain.CollectionId
import infoscry.llm.AnthropicClient
import infoscry.llm.LlmProfile
import infoscry.llm.OpenAiCompatibleClient
import infoscry.llm.PromptService
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respondOutputStream
import java.nio.charset.StandardCharsets
import io.ktor.server.routing.Routing
import io.ktor.server.routing.post
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable data class AskApiRequest(val collection: String, val question: String, val profile: String)

fun Routing.configureAskRoutes(context: AppContext) {
    post("/api/ask") {
        call.handle {
            val body = call.receiveJson<AskApiRequest>()
            val collection = context.collectionService.requireActiveByNameOrId(body.collection)
            val profile = context.llm.findByName(body.profile) ?: throw NoSuchElementException("no such LLM profile")
            val service = AskService(
                context.search,
                PromptService(context.llm),
                client = { selected ->
                    when (selected.provider) {
                        infoscry.llm.LlmProvider.OPENAI_COMPATIBLE -> OpenAiCompatibleClient(selected, System::getenv)
                        infoscry.llm.LlmProvider.ANTHROPIC -> AnthropicClient(selected, System::getenv)
                    }
                },
                persistence = infoscry.ask.AskPersistence { ask, answer, evidence, initialUsage, initialCitations, retrievalSnapshot, correction ->
                    context.llm.persistAsk(ask.collectionId, ask.profile, ask.question, answer, evidence, initialUsage, initialCitations, retrievalSnapshot, correction)
                },
            )
            call.respondOutputStream(ContentType.Text.EventStream, HttpStatusCode.OK) {
                service.ask(AskRequest(CollectionId(collection.id.value), body.question, profile)).collect { event ->
                    write("data: ${ApiJson.encodeToString(event.toWire())}\n\n".toByteArray(StandardCharsets.UTF_8)); flush()
                }
            }
        }
    }
}

@Serializable
internal data class AskWire(
    val type: String,
    val text: String? = null,
    val id: String? = null,
    val valid: Boolean? = null,
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val code: String? = null,
    val message: String? = null,
    // Optional and empty by default so delta/usage/citation/error keep their exact shapes; NEVER
    // keeps even `done` unchanged while no evidence exists.
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val evidence: List<EvidenceWire> = emptyList(),
)

internal fun AskEvent.toWire() = when (this) {
    is AskEvent.Delta -> AskWire("delta", text = text)
    is AskEvent.Usage -> AskWire("usage", inputTokens = inputTokens, outputTokens = outputTokens)
    is AskEvent.Citation -> AskWire("citation", id = evidenceId, valid = valid)
    is AskEvent.Done -> AskWire("done", text = answer, evidence = evidence.map {
        EvidenceWire(
            id = it.id,
            documentId = it.documentId,
            unitId = it.unitId,
            locator = it.locator,
            locatorLabel = it.locatorLabel,
        )
    })
    is AskEvent.Error -> AskWire("error", code = code, message = message)
}
