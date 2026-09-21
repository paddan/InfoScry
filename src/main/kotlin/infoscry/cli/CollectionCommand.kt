package infoscry.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.findObject
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import infoscry.config.AppPaths
import infoscry.domain.Collection
import infoscry.server.ApiJson
import infoscry.server.CollectionResponse
import infoscry.server.CollectionsResponse
import kotlinx.coroutines.runBlocking

/** `infoscry collection list` — the collections this data directory holds. */
class ListCollectionsCommand : CliktCommand(name = "list") {

    private val jsonFlag by option("--json", help = JSON_HELP).flag()

    private val dataDirOption by option("--data-dir", help = DATA_DIR_HELP).path()

    private val parentOptions by findObject<CliOptions>()

    override fun run() {
        val options = resolveOptions(parentOptions, jsonFlag, dataDirOption)

        try {
            CliSession.connect(AppPaths.of(options.dataDir)).use { session ->
                val collections = runBlocking { session.listCollections() }
                if (options.json) {
                    echo(ApiJson.encodeToString(CollectionsResponse(collections)))
                } else if (collections.isEmpty()) {
                    echo("No collections.")
                } else {
                    collections.forEach { echo(describe(it)) }
                }
            }
        } catch (failure: Exception) {
            throw CliFailure(failure.message ?: "the collections could not be listed", failure)
        }
    }

    private fun describe(collection: Collection): String = buildString {
        append(collection.name)
        append("  (")
        append(collection.id.value)
        append(')')
        append("  ocr: ")
        append(collection.ocrLanguages)
        collection.description?.let { append("  ").append(it) }
    }
}

/** `infoscry collection create <name>` — a new logical search boundary. */
class CreateCollectionCommand : CliktCommand(name = "create") {

    private val name by argument("name", help = "The collection's name, unique case-insensitively")

    private val description by option("--description", help = "What the collection is for")

    private val jsonFlag by option("--json", help = JSON_HELP).flag()

    private val dataDirOption by option("--data-dir", help = DATA_DIR_HELP).path()

    private val parentOptions by findObject<CliOptions>()

    override fun run() {
        val options = resolveOptions(parentOptions, jsonFlag, dataDirOption)

        try {
            CliSession.connect(AppPaths.of(options.dataDir)).use { session ->
                val created = runBlocking { session.createCollection(name, description) }
                if (options.json) {
                    echo(ApiJson.encodeToString(CollectionResponse(created)))
                } else {
                    echo("Created collection '${created.name}' (${created.id.value}).")
                    if (session.isRemote) {
                        // Worth saying: the collection lives in the running server, not in this
                        // short-lived process, so it survives this command finishing.
                        echo("Added through the running server.", err = true)
                    }
                }
            }
        } catch (failure: Exception) {
            throw CliFailure(failure.message ?: "the collection could not be created", failure)
        }
    }
}
