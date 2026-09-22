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
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.post
import kotlinx.coroutines.flow.collect
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
            )
            val events = mutableListOf<AskEvent>()
            service.ask(AskRequest(CollectionId(collection.id.value), body.question, profile)).collect { events += it }
            val payload = events.joinToString("") { event -> "data: ${ApiJson.encodeToString(event.toWire())}\n\n" }
            call.respondText(payload, ContentType.Text.EventStream, HttpStatusCode.OK)
        }
    }
}

@Serializable private data class AskWire(val type: String, val text: String? = null, val id: String? = null, val valid: Boolean? = null, val code: String? = null, val message: String? = null)
private fun AskEvent.toWire() = when (this) {
    is AskEvent.Delta -> AskWire("delta", text = text)
    is AskEvent.Usage -> AskWire("usage", text = "$inputTokens/$outputTokens")
    is AskEvent.Citation -> AskWire("citation", id = evidenceId, valid = valid)
    is AskEvent.Done -> AskWire("done", text = answer)
    is AskEvent.Error -> AskWire("error", code = code, message = message)
}
