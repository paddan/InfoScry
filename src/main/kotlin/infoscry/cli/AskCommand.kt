package infoscry.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.findObject
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import infoscry.AppContext
import infoscry.ask.AskEvent
import infoscry.ask.AskRequest
import infoscry.ask.AskService
import infoscry.config.AppPaths
import infoscry.domain.CollectionId
import infoscry.llm.AnthropicClient
import infoscry.llm.LlmProvider
import infoscry.llm.OpenAiCompatibleClient
import infoscry.llm.PromptService
import infoscry.server.ApiJson
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.collect

class AskCommand : CliktCommand(name = "ask") {
    private val collection by option("--collection").required()
    private val profile by option("--profile").required()
    private val question by argument()
    private val parentOptions by findObject<CliOptions>()

    override fun run() {
        val options = resolveOptions(parentOptions, false, null)
        val context = AppContext.open(AppPaths.of(options.dataDir))
        context.use { open ->
            val selected = open.llm.findByName(profile) ?: throw CliFailure("no such LLM profile")
            val service = AskService(open.search, PromptService(open.llm), client = { p ->
                when (p.provider) {
                    LlmProvider.OPENAI_COMPATIBLE -> OpenAiCompatibleClient(p, System::getenv)
                    LlmProvider.ANTHROPIC -> AnthropicClient(p, System::getenv)
                }
            }, persistence = infoscry.ask.AskPersistence { ask, answer, evidence, initialUsage, initialCitations, correction ->
                open.llm.persistAsk(ask.collectionId, ask.profile, ask.question, answer, evidence, initialUsage, initialCitations, correction)
            })
            runBlocking {
                val events = mutableListOf<AskEvent>()
                service.ask(AskRequest(CollectionId(open.collectionService.requireActiveByNameOrId(collection).id.value), question, selected)).collect { event ->
                    if (options.json) events += event else when (event) {
                        is AskEvent.Delta -> echo(event.text, trailingNewline = false)
                        is AskEvent.Citation -> echo(" [${event.evidenceId}]")
                        is AskEvent.Error -> throw CliFailure("${event.code}: ${event.message}")
                        else -> Unit
                    }
                }
                if (options.json) echo(jsonResult(events))
            }
        }
    }

    private fun jsonEvent(event: AskEvent): String = when (event) {
        is AskEvent.Delta -> "{\"type\":\"delta\",\"text\":${ApiJson.encodeToString(event.text)}}"
        is AskEvent.Usage -> "{\"type\":\"usage\",\"input\":${event.inputTokens},\"output\":${event.outputTokens}}"
        is AskEvent.Citation -> "{\"type\":\"citation\",\"id\":${ApiJson.encodeToString(event.evidenceId)},\"valid\":${event.valid}}"
        is AskEvent.Done -> "{\"type\":\"done\",\"text\":${ApiJson.encodeToString(event.answer)}}"
        is AskEvent.Error -> "{\"type\":\"error\",\"code\":${ApiJson.encodeToString(event.code)}}"
    }

    private fun jsonResult(events: List<AskEvent>): String {
        val answer = events.filterIsInstance<AskEvent.Done>().lastOrNull()?.answer.orEmpty()
        val errors = events.filterIsInstance<AskEvent.Error>().map { it.code }
        return "{\"answer\":${ApiJson.encodeToString(answer)},\"errors\":${ApiJson.encodeToString(errors)}}"
    }
}
