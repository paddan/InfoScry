package infoscry.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.findObject
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import infoscry.server.ApiJson
import infoscry.server.AskApiRequest
import kotlinx.coroutines.runBlocking

/** `infoscry ask` — one question to a collection, answered by the server and streamed here. */
class AskCommand : CliktCommand(name = "ask") {
    private val collection by option("--collection").required()
    private val profile by option("--profile").required()
    private val promptRef by option("--prompt", help = "The Ask prompt's name or id (default: the collection default)")
    private val question by argument()
    private val jsonFlag by option("--json", help = JSON_HELP).flag()
    private val parentOptions by findObject<CliOptions>()

    override fun run() {
        val options = resolveOptions(parentOptions, jsonFlag, null)
        val streamed = StringBuilder()
        var answer: String? = null
        val errors = mutableListOf<String>()
        try {
            CliSession.connect().use { session ->
                runBlocking {
                    session.api.ask(AskApiRequest(collection, question, profile, promptRef)) { event ->
                        when (event.type) {
                            "delta" -> {
                                streamed.append(event.text.orEmpty())
                                if (!options.json) echo(event.text.orEmpty(), trailingNewline = false)
                            }
                            "citation" -> if (!options.json) echo(" [${event.id}]")
                            "done" -> {
                                answer = event.text.orEmpty()
                                if (!options.json) correctedTail(streamed.toString(), answer.orEmpty())?.let { echo("\n\nCorrected answer:\n$it") }
                            }
                            "error" -> {
                                errors += event.code.orEmpty()
                                if (!options.json) throw CliFailure("${event.code}: ${event.message}")
                            }
                        }
                    }
                }
            }
        } catch (refused: RemoteApiFailure) {
            throw CliFailure(refused.message, refused)
        }
        if (options.json) {
            echo("{\"answer\":${ApiJson.encodeToString(answer.orEmpty())},\"errors\":${ApiJson.encodeToString(errors.toList())}}")
        }
    }
}

/** Returns the corrected answer when it differs from the streamed text, otherwise null. */
internal fun correctedTail(streamed: String, final: String): String? =
    if (final == streamed) null else final
