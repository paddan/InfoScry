package infoscry.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.findObject
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.types.path
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
    private val jsonFlag by option("--json", help = JSON_HELP).flag()
    private val dataDirOption by option("--data-dir", help = DATA_DIR_HELP).path()
    private val parentOptions by findObject<CliOptions>()

    override fun run() {
        val options = resolveOptions(parentOptions, jsonFlag, dataDirOption)
        val context = AppContext.open(AppPaths.of(options.dataDir))
        context.use { open ->
            val selected = open.llm.findByName(profile) ?: throw CliFailure("no such LLM profile")
            val service = AskService(open.search, PromptService(open.llm), client = { p ->
                when (p.provider) {
                    LlmProvider.OPENAI_COMPATIBLE -> OpenAiCompatibleClient(p, System::getenv)
                    LlmProvider.ANTHROPIC -> AnthropicClient(p, System::getenv)
                }
            }, persistence = infoscry.ask.AskPersistence { ask, answer, evidence, initialUsage, initialCitations, retrievalSnapshot, correction ->
                open.llm.persistAsk(ask.collectionId, ask.profile, ask.question, answer, evidence, initialUsage, initialCitations, retrievalSnapshot, correction)
            })
            runBlocking {
                val events = mutableListOf<AskEvent>()
                val streamed = StringBuilder()
                service.ask(AskRequest(CollectionId(open.collectionService.requireActiveByNameOrId(collection).id.value), question, selected)).collect { event ->
                    if (options.json) events += event else when (event) {
                        is AskEvent.Delta -> { streamed.append(event.text); echo(event.text, trailingNewline = false) }
                        is AskEvent.Citation -> echo(" [${event.evidenceId}]")
                        // A correction replaces the streamed text; surface it instead of the uncorrected answer.
                        is AskEvent.Done -> correctedTail(streamed.toString(), event.answer)?.let { echo("\n\nCorrected answer:\n$it") }
                        is AskEvent.Error -> throw CliFailure("${event.code}: ${event.message}")
                        else -> Unit
                    }
                }
                if (options.json) echo(jsonResult(events))
            }
        }
    }

    private fun jsonResult(events: List<AskEvent>): String {
        val answer = events.filterIsInstance<AskEvent.Done>().lastOrNull()?.answer.orEmpty()
        val errors = events.filterIsInstance<AskEvent.Error>().map { it.code }
        return "{\"answer\":${ApiJson.encodeToString(answer)},\"errors\":${ApiJson.encodeToString(errors)}}"
    }
}

/** Returns the corrected answer when it differs from the streamed text, otherwise null. */
internal fun correctedTail(streamed: String, final: String): String? =
    if (final == streamed) null else final
