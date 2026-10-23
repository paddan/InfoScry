package infoscry.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.findObject
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import infoscry.config.AppPaths
import infoscry.glossary.GlossaryRules
import infoscry.server.ApiJson
import infoscry.server.GlossaryGroupWire
import infoscry.server.GlossaryTermWire
import java.util.UUID
import kotlinx.coroutines.runBlocking

/**
 * `infoscry collection glossary …` — a collection's names with the same meaning.
 *
 * Thin over the same service the HTTP routes use. Nothing runs in the background: a change applies from the next
 * search and the next Ask or Investigate request.
 */
class GlossaryCommand : CliktCommand(name = "glossary") {

    init {
        subcommands(ListGlossaryCommand(), AddGlossaryCommand(), RemoveGlossaryCommand())
    }

    override fun run() {
        // Each subcommand reads its own arguments.
    }
}

/** `infoscry collection glossary list <collection>` — `id<TAB>term<TAB>term…` per group, prefix terms marked `*`. */
class ListGlossaryCommand : CliktCommand(name = "list") {

    private val collection by argument("collection", help = "The collection's name or id")

    private val jsonFlag by option("--json", help = JSON_HELP).flag()

    private val dataDirOption by option("--data-dir", help = DATA_DIR_HELP).path()

    private val parentOptions by findObject<CliOptions>()

    override fun run() {
        val options = resolveOptions(parentOptions, jsonFlag, dataDirOption)
        try {
            CliSession.connect(AppPaths.of(options.dataDir)).use { session ->
                val list = runBlocking { session.listGlossary(collection) }
                if (options.json) {
                    echo(ApiJson.encodeToString(list))
                } else {
                    // Written raw: a terminal-formatting echo would expand the tabs a script splits on.
                    list.groups.forEach { group ->
                        writeStdout((listOf(group.id) + group.terms.map { it.text + if (it.prefix) "*" else "" }).joinToString("\t") + "\n")
                    }
                }
            }
        } catch (failure: Exception) {
            throw CliFailure(failure.message ?: "the glossary could not be listed", failure)
        }
    }
}

/**
 * `infoscry collection glossary add <collection> <term>... [--prefix <term>]... [--group <id>]`.
 *
 * Without `--group`, a group that already has exactly these terms is left as it is, so the command can be run again.
 */
class AddGlossaryCommand : CliktCommand(name = "add") {

    private val collection by argument("collection", help = "The collection's name or id")

    private val terms by argument("terms", help = "Two or more names with the same meaning").multiple(required = true)

    private val prefixes by option("--prefix", help = "One of the terms that also matches words starting with it").multiple()

    private val group by option("--group", help = "The id of the group to replace; by default a new group")

    private val dataDirOption by option("--data-dir", help = DATA_DIR_HELP).path()

    private val parentOptions by findObject<CliOptions>()

    override fun run() {
        val options = resolveOptions(parentOptions, false, dataDirOption)
        val cleaned = terms.map(GlossaryRules::clean)
        val prefixed = prefixes.map(GlossaryRules::clean).toSet()
        prefixed.firstOrNull { it !in cleaned }?.let {
            throw CliFailure("--prefix must name one of the terms")
        }
        val wire = cleaned.map { GlossaryTermWire(it, it in prefixed) }
        try {
            CliSession.connect(AppPaths.of(options.dataDir)).use { session ->
                val existing = if (group == null) {
                    runBlocking { session.listGlossary(collection) }.groups.firstOrNull { it.sameTermsAs(wire) }
                } else {
                    null
                }
                val id = existing?.id ?: group ?: UUID.randomUUID().toString()
                if (existing == null) runBlocking { session.putGlossaryGroup(collection, id, wire) }
                echo("Saved glossary group $id.")
            }
        } catch (failure: CliFailure) {
            throw failure
        } catch (failure: Exception) {
            throw CliFailure(failure.message ?: "the glossary group could not be saved", failure)
        }
    }

    private fun GlossaryGroupWire.sameTermsAs(other: List<GlossaryTermWire>): Boolean = terms.toSet() == other.toSet()
}

/** `infoscry collection glossary remove <collection> <group-id>`; removing a group that is gone succeeds. */
class RemoveGlossaryCommand : CliktCommand(name = "remove") {

    private val collection by argument("collection", help = "The collection's name or id")

    private val group by argument("group", help = "The group's id, as `list` prints it")

    private val dataDirOption by option("--data-dir", help = DATA_DIR_HELP).path()

    private val parentOptions by findObject<CliOptions>()

    override fun run() {
        val options = resolveOptions(parentOptions, false, dataDirOption)
        try {
            CliSession.connect(AppPaths.of(options.dataDir)).use { session ->
                runBlocking { session.deleteGlossaryGroup(collection, group) }
                echo("Removed glossary group $group.")
            }
        } catch (failure: Exception) {
            throw CliFailure(failure.message ?: "the glossary group could not be removed", failure)
        }
    }
}
