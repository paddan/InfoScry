package infoscry.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.findObject
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import infoscry.jobs.IgnorePatterns
import java.nio.file.Files
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

/**
 * `infoscry collection ignore <collection> list|set` — the collection's ignore patterns.
 *
 * Every import into the collection applies the list before anything else, so system and temporary files are never
 * attempted. The syntax is `.gitignore`'s (see `docs/cli.md`). A new collection starts with defaults.
 */
class IgnoreCommand : CliktCommand(name = "ignore") {

    val collection by argument("collection", help = "The collection's name or id")

    init {
        subcommands(ListIgnoreCommand(), SetIgnoreCommand())
    }

    override fun run() {
        // The subcommand reads the collection from this command.
    }
}

private fun CliktCommand.ignoreTarget(): String = (currentContext.parent?.command as IgnoreCommand).collection

/** `infoscry collection ignore <collection> list` — one pattern per line, or `{"patterns":[...]}` with `--json`. */
class ListIgnoreCommand : CliktCommand(name = "list") {

    private val jsonFlag by option("--json", help = JSON_HELP).flag()

    private val dataDirOption by option("--data-dir", help = DATA_DIR_HELP).path()

    private val parentOptions by findObject<CliOptions>()

    override fun run() {
        val options = resolveOptions(parentOptions, jsonFlag, dataDirOption)
        val target = ignoreTarget()

        try {
            CliSession.connect(AppPaths.of(options.dataDir)).use { session ->
                printPatterns(runBlocking { session.ignorePatterns(target) }, options.json)
            }
        } catch (failure: Exception) {
            throw CliFailure(failure.message ?: "the ignore patterns could not be read", failure)
        }
    }
}

/**
 * `infoscry collection ignore <collection> set [PATTERN...] [--file F] [--clear]` — replaces the whole list.
 *
 * Patterns come from the arguments, from a file (one per line), or both; `--clear` empties the list. Naming nothing
 * is refused rather than treated as clearing, so a forgotten argument never silently removes the defaults.
 */
class SetIgnoreCommand : CliktCommand(name = "set") {

    private val patterns by argument("pattern", help = "A pattern; quote it so the shell leaves * and ? alone").multiple()

    private val file by option("--file", help = "Read patterns from this file, one per line").path(mustExist = true, canBeDir = false)

    private val clear by option("--clear", help = "Empty the list: nothing is ignored").flag()

    private val jsonFlag by option("--json", help = JSON_HELP).flag()

    private val dataDirOption by option("--data-dir", help = DATA_DIR_HELP).path()

    private val parentOptions by findObject<CliOptions>()

    override fun run() {
        val options = resolveOptions(parentOptions, jsonFlag, dataDirOption)
        val target = ignoreTarget()
        val lines = patterns + (file?.let { Files.readAllLines(it) } ?: emptyList())
        if (clear && lines.isNotEmpty()) throw CliFailure("--clear cannot be combined with patterns or --file")
        if (!clear && lines.isEmpty()) {
            throw CliFailure("name at least one pattern or --file, or use --clear to ignore nothing")
        }

        try {
            // Validated here as well as by the service, so a bad pattern is refused before a server is contacted.
            IgnorePatterns.of(lines)
            CliSession.connect(AppPaths.of(options.dataDir)).use { session ->
                printPatterns(runBlocking { session.setIgnorePatterns(target, lines) }, options.json)
            }
        } catch (failure: Exception) {
            throw CliFailure(failure.message ?: "the ignore patterns could not be saved", failure)
        }
    }
}

private fun CliktCommand.printPatterns(patterns: List<String>, json: Boolean) {
    if (json) {
        echo(ApiJson.encodeToString(infoscry.server.IgnorePatternsBody(patterns)))
    } else {
        patterns.forEach { echo(it) }
    }
}
