package infoscry.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.findObject
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import com.ibm.icu.lang.UCharacter
import infoscry.config.AppPaths
import infoscry.correction.CorrectionRuleInput
import infoscry.correction.CorrectionRules
import infoscry.correction.InvalidCorrectionException
import infoscry.domain.Job
import infoscry.domain.JobState
import infoscry.server.CorrectionListWire
import infoscry.jobs.ImportJobHandler
import infoscry.server.ApiJson
import infoscry.server.CorrectionPreviewWire
import java.util.UUID
import kotlinx.coroutines.runBlocking

/**
 * `infoscry collection corrections …` — a collection's standing corrections.
 *
 * Thin over the same service the HTTP routes use; the command exits non-zero when the job it ran did not apply. Saving or removing a rule starts the collection's correction job;
 * with no running server this command runs that job here and waits for it, as `import` does, because a job must not
 * outlive the process that owns the archive.
 */
class CorrectionCommand : CliktCommand(name = "corrections") {

    init {
        subcommands(ListCorrectionsCommand(), AddCorrectionCommand(), RemoveCorrectionCommand(), PreviewCorrectionCommand())
    }

    override fun run() {
        // Each subcommand reads its own arguments.
    }
}

/** `infoscry collection corrections list <collection>` — `find<TAB>replace<TAB>exact|<TAB>matches` per rule. */
class ListCorrectionsCommand : CliktCommand(name = "list") {

    private val collection by argument("collection", help = "The collection's name or id")

    private val jsonFlag by option("--json", help = JSON_HELP).flag()

    private val dataDirOption by option("--data-dir", help = DATA_DIR_HELP).path()

    private val parentOptions by findObject<CliOptions>()

    override fun run() {
        val options = resolveOptions(parentOptions, jsonFlag, dataDirOption)
        try {
            CliSession.connect(AppPaths.of(options.dataDir)).use { session ->
                val list = runBlocking { session.listCorrections(collection) }
                if (options.json) {
                    echo(ApiJson.encodeToString(list))
                } else {
                    // Written raw, like the prompt list: a terminal-formatting echo would expand the tabs a script splits on.
                    list.rules.forEach { rule ->
                        writeStdout("${rule.find}\t${rule.replace}\t${if (rule.exactCase) "exact" else ""}\t${rule.matches}\n")
                    }
                }
            }
        } catch (failure: Exception) {
            throw CliFailure(failure.message ?: "the corrections could not be listed", failure)
        }
    }
}

/** `infoscry collection corrections add <collection> <find> <replace> [--exact-case] [--id <id>]`. */
class AddCorrectionCommand : CliktCommand(name = "add") {

    private val collection by argument("collection", help = "The collection's name or id")

    private val find by argument("find", help = "The misread word or phrase")

    private val replace by argument("replace", help = "What it should read")

    private val exactCase by option("--exact-case", help = "Match only this exact writing").flag()

    private val id by option("--id", help = "The rule's id; by default one derived from the word it finds")

    private val dataDirOption by option("--data-dir", help = DATA_DIR_HELP).path()

    private val parentOptions by findObject<CliOptions>()

    override fun run() {
        val options = resolveOptions(parentOptions, false, dataDirOption)
        val input = CorrectionRuleInput(find, replace, exactCase)
        try {
            CliSession.connect(AppPaths.of(options.dataDir)).use { session ->
                val ruleId = id ?: existingIdOf(runBlocking { session.listCorrections(collection) }, input) ?: UUID.randomUUID().toString()
                echo(describe(runBlocking { session.previewCorrection(collection, input) }))
                runBlocking { session.putCorrection(collection, ruleId, input) }
                echo("Saved correction $ruleId.")
                followJob(session, collection)?.let(::requireCorrectionsApplied)
            }
        } catch (failure: CliFailure) {
            throw failure
        } catch (failure: Exception) {
            throw CliFailure(failure.message ?: "the correction could not be saved", failure)
        }
    }
}

/** `infoscry collection corrections remove <collection> <find-or-id>`; removing a rule that is gone succeeds. */
class RemoveCorrectionCommand : CliktCommand(name = "remove") {

    private val collection by argument("collection", help = "The collection's name or id")

    private val rule by argument("rule", help = "The word the rule finds, or its id")

    private val dataDirOption by option("--data-dir", help = DATA_DIR_HELP).path()

    private val parentOptions by findObject<CliOptions>()

    override fun run() {
        val options = resolveOptions(parentOptions, false, dataDirOption)
        try {
            CliSession.connect(AppPaths.of(options.dataDir)).use { session ->
                val folded = UCharacter.foldCase(rule, UCharacter.FOLD_CASE_DEFAULT)
                val match = runBlocking { session.listCorrections(collection) }.rules.firstOrNull {
                    it.id == rule || UCharacter.foldCase(it.find, UCharacter.FOLD_CASE_DEFAULT) == folded
                }
                if (match == null) {
                    echo("No such correction; nothing to remove.")
                    return
                }
                runBlocking { session.deleteCorrection(collection, match.id) }
                echo("Removed correction ${match.id}.")
                followJob(session, collection)?.let(::requireCorrectionsApplied)
            }
        } catch (failure: CliFailure) {
            throw failure
        } catch (failure: Exception) {
            throw CliFailure(failure.message ?: "the correction could not be removed", failure)
        }
    }
}

/** `infoscry collection corrections preview <collection> <find> <replace> [--exact-case]`; stores nothing. */
class PreviewCorrectionCommand : CliktCommand(name = "preview") {

    private val collection by argument("collection", help = "The collection's name or id")

    private val find by argument("find", help = "The misread word or phrase")

    private val replace by argument("replace", help = "What it should read")

    private val exactCase by option("--exact-case", help = "Match only this exact writing").flag()

    private val jsonFlag by option("--json", help = JSON_HELP).flag()

    private val dataDirOption by option("--data-dir", help = DATA_DIR_HELP).path()

    private val parentOptions by findObject<CliOptions>()

    override fun run() {
        val options = resolveOptions(parentOptions, jsonFlag, dataDirOption)
        try {
            CliSession.connect(AppPaths.of(options.dataDir)).use { session ->
                val preview = runBlocking { session.previewCorrection(collection, CorrectionRuleInput(find, replace, exactCase)) }
                if (options.json) {
                    echo(ApiJson.encodeToString(preview))
                } else {
                    echo(describe(preview))
                    preview.examples.forEach { echo("  ${it.before.oneLine()}  →  ${it.after.oneLine()}") }
                }
            }
        } catch (failure: Exception) {
            throw CliFailure(failure.message ?: "the correction could not be previewed", failure)
        }
    }
}

private fun String.oneLine(): String = replace(Regex("\\s+"), " ").trim()

private fun describe(preview: CorrectionPreviewWire): String {
    val matches = if (preview.matches == 1) "1 match" else "${preview.matches} matches"
    val documents = if (preview.documents == 1) "1 document" else "${preview.documents} documents"
    return "$matches in $documents"
}

/** The id of the collection's rule that finds the same word, so adding a correction again replaces it. */
private fun existingIdOf(list: CorrectionListWire, input: CorrectionRuleInput): String? {
    val key = try {
        CorrectionRules.findKey(CorrectionRules.normalize(input))
    } catch (invalid: InvalidCorrectionException) {
        throw CliFailure(invalid.message.orEmpty(), invalid)
    }
    return list.rules.firstOrNull { CorrectionRules.findKey(CorrectionRuleInput(it.find, it.replace, it.exactCase)) == key }?.id
}

/**
 * Runs the collection's correction job here and waits for it when this process owns the archive; with a server
 * running, the server owns it and this returns `null` at once.
 */
private fun followJob(session: CliSession, collection: String): Job? {
    val local = session as? CliSession.Local ?: return null
    val context = local.context
    val job = context.correctionService.apply(context.collectionService.requireActiveByNameOrId(collection).id)
    ImportJobHandler.attachTo(context, documentEmbedder = productionImportEmbedder(context))
    return runBlocking { awaitTerminalJob(lookup = { session.job(job.id) }, what = "corrections") }
}

/** Fails the command unless [job] applied the corrections, so a script sees the failure in the exit status. */
internal fun requireCorrectionsApplied(job: Job) {
    if (job.state == JobState.COMPLETE) return
    val reason = job.errorCode?.let { " ($it)" }.orEmpty()
    throw CliFailure("the corrections were saved but not applied: the job ended ${job.state.name.lowercase()}$reason")
}
