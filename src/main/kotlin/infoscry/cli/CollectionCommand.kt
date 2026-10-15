package infoscry.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.findObject
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.path
import infoscry.collection.CollectionPrompts
import infoscry.collection.RolePrompts
import infoscry.jobs.IgnorePatterns
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import infoscry.config.AppPaths
import infoscry.domain.Collection
import infoscry.llm.CollectionPrompt
import infoscry.llm.LlmPromptRole
import infoscry.server.ApiJson
import infoscry.server.CollectionPromptsWire
import infoscry.server.CollectionResponse
import infoscry.server.CollectionsResponse
import infoscry.server.PromptDefaultWire
import infoscry.server.PromptWire
import infoscry.server.RolePromptsWire
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable

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

/**
 * `infoscry collection prompt …` — the Ask and Investigate prompts a collection can use.
 *
 * Thin over the same service the HTTP routes use. A prompt is named by its id or its name, case-insensitively;
 * `Default` is the shipped prompt every collection can always select. Prompt bodies never appear in messages.
 */
class PromptCommand : CliktCommand(name = "prompt") {

    init {
        subcommands(
            ListPromptsCommand(),
            ShowPromptCommand(),
            CreatePromptCommand(),
            UpdatePromptCommand(),
            DeletePromptCommand(),
            UsePromptCommand(),
        )
    }

    override fun run() {
        // Each subcommand reads its own arguments.
    }
}

private val ROLE_CHOICES = mapOf("ask" to LlmPromptRole.ASK, "investigate" to LlmPromptRole.INVESTIGATE)

private const val ROLE_HELP = "The prompt's role: ask or investigate"

private const val PROMPT_HELP = "The prompt's name or id (name is case-insensitive); Default is the shipped prompt"

/**
 * `infoscry collection prompt list <collection> [--role ask|investigate]` — one line per prompt:
 * `name<TAB>version<TAB>(default)`, the last field empty unless the prompt is the role's default. Without `--role`
 * each line starts with the role. `--json` prints every role regardless of `--role`.
 */
class ListPromptsCommand : CliktCommand(name = "list") {

    private val collection by argument("collection", help = "The collection's name or id")

    private val role by option("--role", help = ROLE_HELP).choice(ROLE_CHOICES, ignoreCase = true)

    private val jsonFlag by option("--json", help = JSON_HELP).flag()

    private val dataDirOption by option("--data-dir", help = DATA_DIR_HELP).path()

    private val parentOptions by findObject<CliOptions>()

    override fun run() {
        val options = resolveOptions(parentOptions, jsonFlag, dataDirOption)

        try {
            CliSession.connect(AppPaths.of(options.dataDir)).use { session ->
                val prompts = runBlocking { session.listPrompts(collection) }
                if (options.json) {
                    echo(ApiJson.encodeToString(prompts.toWire()))
                } else {
                    printPromptLines(prompts, role)
                }
            }
        } catch (failure: Exception) {
            throw CliFailure(failure.message ?: "the prompts could not be listed", failure)
        }
    }
}

/** `infoscry collection prompt show <collection> --role … [--prompt …]` — the body alone, or the prompt with `--json`. */
class ShowPromptCommand : CliktCommand(name = "show") {

    private val collection by argument("collection", help = "The collection's name or id")

    private val role by option("--role", help = ROLE_HELP).choice(ROLE_CHOICES, ignoreCase = true).required()

    private val prompt by option("--prompt", help = PROMPT_HELP)

    private val jsonFlag by option("--json", help = JSON_HELP).flag()

    private val dataDirOption by option("--data-dir", help = DATA_DIR_HELP).path()

    private val parentOptions by findObject<CliOptions>()

    override fun run() {
        val options = resolveOptions(parentOptions, jsonFlag, dataDirOption)

        try {
            CliSession.connect(AppPaths.of(options.dataDir)).use { session ->
                val selected = selectPrompt(runBlocking { session.listPrompts(collection) }, role, prompt, collection)
                if (options.json) echo(ApiJson.encodeToString(selected.toWire())) else writeExact(selected.body)
            }
        } catch (failure: Exception) {
            throw CliFailure(failure.message ?: "the prompt could not be shown", failure)
        }
    }
}

/** `infoscry collection prompt create <collection> --role … --name … --file <path|->` — a new prompt. */
class CreatePromptCommand : CliktCommand(name = "create") {

    private val collection by argument("collection", help = "The collection's name or id")

    private val role by option("--role", help = ROLE_HELP).choice(ROLE_CHOICES, ignoreCase = true).required()

    private val name by option("--name", help = "The prompt's name, unique per collection and role").required()

    private val file by option("--file", help = "Read the body from this file; - reads standard input").required()

    private val jsonFlag by option("--json", help = JSON_HELP).flag()

    private val dataDirOption by option("--data-dir", help = DATA_DIR_HELP).path()

    private val parentOptions by findObject<CliOptions>()

    override fun run() {
        val options = resolveOptions(parentOptions, jsonFlag, dataDirOption)

        try {
            val body = readPromptBody(file)
            CliSession.connect(AppPaths.of(options.dataDir)).use { session ->
                val created = runBlocking { session.createPrompt(collection, role, name, body) }
                if (options.json) {
                    echo(ApiJson.encodeToString(created.toWire()))
                } else {
                    echo("Saved ${role.name.lowercase(Locale.ROOT)} prompt '${created.name}' (${created.id}) in '$collection'.")
                }
            }
        } catch (failure: Exception) {
            throw CliFailure(failure.message ?: "the prompt could not be created", failure)
        }
    }
}

/**
 * `infoscry collection prompt update <collection> --role … --prompt … [--name …] [--file …]` — a change to the name,
 * the body, or both. An omitted value keeps its current one; naming neither is refused.
 */
class UpdatePromptCommand : CliktCommand(name = "update") {

    private val collection by argument("collection", help = "The collection's name or id")

    private val role by option("--role", help = ROLE_HELP).choice(ROLE_CHOICES, ignoreCase = true).required()

    private val prompt by option("--prompt", help = PROMPT_HELP).required()

    private val name by option("--name", help = "The prompt's new name")

    private val file by option("--file", help = "Read the new body from this file; - reads standard input")

    private val jsonFlag by option("--json", help = JSON_HELP).flag()

    private val dataDirOption by option("--data-dir", help = DATA_DIR_HELP).path()

    private val parentOptions by findObject<CliOptions>()

    override fun run() {
        val options = resolveOptions(parentOptions, jsonFlag, dataDirOption)
        if (name == null && file == null) throw CliFailure("name --name or --file to change, or both")

        try {
            val newBody = file?.let { readPromptBody(it) }
            CliSession.connect(AppPaths.of(options.dataDir)).use { session ->
                val current = selectPrompt(runBlocking { session.listPrompts(collection) }, role, prompt, collection)
                val saved = runBlocking {
                    session.updatePrompt(collection, role, current.id, name ?: current.name, newBody ?: current.body)
                }
                if (options.json) {
                    echo(ApiJson.encodeToString(saved.toWire()))
                } else {
                    echo("Updated prompt '${saved.name}' (version ${saved.version}).")
                }
            }
        } catch (failure: Exception) {
            throw CliFailure(failure.message ?: "the prompt could not be updated", failure)
        }
    }
}

/**
 * `infoscry collection prompt delete <collection> --role … --prompt …` — removes a stored prompt. A prompt that is
 * already gone is not an error: the command reports that nothing was deleted and exits 0.
 */
class DeletePromptCommand : CliktCommand(name = "delete") {

    private val collection by argument("collection", help = "The collection's name or id")

    private val role by option("--role", help = ROLE_HELP).choice(ROLE_CHOICES, ignoreCase = true).required()

    private val prompt by option("--prompt", help = PROMPT_HELP).required()

    private val jsonFlag by option("--json", help = JSON_HELP).flag()

    private val dataDirOption by option("--data-dir", help = DATA_DIR_HELP).path()

    private val parentOptions by findObject<CliOptions>()

    override fun run() {
        val options = resolveOptions(parentOptions, jsonFlag, dataDirOption)

        try {
            CliSession.connect(AppPaths.of(options.dataDir)).use { session ->
                val found = findPrompt(runBlocking { session.listPrompts(collection) }.roleOf(role), prompt)
                if (found != null) runBlocking { session.deletePrompt(collection, found.id) }
                if (options.json) {
                    echo(ApiJson.encodeToString(DeletedPromptBody(found?.id ?: prompt, deleted = found != null)))
                } else if (found != null) {
                    echo("Deleted prompt '${found.name}' from '$collection'.")
                } else {
                    echo("No ${role.name.lowercase(Locale.ROOT)} prompt named or identified by '$prompt'; nothing to delete.")
                }
            }
        } catch (failure: Exception) {
            throw CliFailure(failure.message ?: "the prompt could not be deleted", failure)
        }
    }
}

/** `infoscry collection prompt use <collection> --role … --prompt …` — selects the role's default; `Default` resets it. */
class UsePromptCommand : CliktCommand(name = "use") {

    private val collection by argument("collection", help = "The collection's name or id")

    private val role by option("--role", help = ROLE_HELP).choice(ROLE_CHOICES, ignoreCase = true).required()

    private val prompt by option("--prompt", help = PROMPT_HELP).required()

    private val jsonFlag by option("--json", help = JSON_HELP).flag()

    private val dataDirOption by option("--data-dir", help = DATA_DIR_HELP).path()

    private val parentOptions by findObject<CliOptions>()

    override fun run() {
        val options = resolveOptions(parentOptions, jsonFlag, dataDirOption)

        try {
            CliSession.connect(AppPaths.of(options.dataDir)).use { session ->
                val target = selectPrompt(runBlocking { session.listPrompts(collection) }, role, prompt, collection)
                val selected = runBlocking { session.setPromptDefault(collection, role, target.id) }
                if (options.json) {
                    echo(ApiJson.encodeToString(PromptDefaultWire(role, selected)))
                } else {
                    echo("'${target.name}' is now the ${role.name.lowercase(Locale.ROOT)} default for '$collection'.")
                }
            }
        } catch (failure: Exception) {
            throw CliFailure(failure.message ?: "the default prompt could not be set", failure)
        }
    }
}

/** The `--json` answer of `delete`; [deleted] is false when there was nothing to delete. */
@Serializable
private data class DeletedPromptBody(val promptId: String, val deleted: Boolean)

/** The prompt of [role] named or identified by [reference], or the role's default when [reference] is null. */
private fun selectPrompt(prompts: CollectionPrompts, role: LlmPromptRole, reference: String?, collection: String): CollectionPrompt {
    val rolePrompts = prompts.roleOf(role)
    if (reference == null) {
        return rolePrompts.prompts.firstOrNull { it.id == rolePrompts.defaultId } ?: rolePrompts.prompts.first()
    }
    return findPrompt(rolePrompts, reference)
        ?: throw CliFailure("no ${role.name.lowercase(Locale.ROOT)} prompt named or identified by '$reference' in '$collection'")
}

private fun findPrompt(rolePrompts: RolePrompts, reference: String): CollectionPrompt? {
    val wanted = reference.trim()
    return rolePrompts.prompts.firstOrNull { it.id == wanted }
        ?: rolePrompts.prompts.firstOrNull { it.name.trim().lowercase(Locale.ROOT) == wanted.lowercase(Locale.ROOT) }
}

private fun CollectionPrompts.roleOf(role: LlmPromptRole): RolePrompts = when (role) {
    LlmPromptRole.ASK -> ask
    LlmPromptRole.INVESTIGATE -> investigate
}

/** Reads a prompt body: `-` is standard input. The text is UTF-8, and CRLF line endings are stored as LF. */
private fun readPromptBody(file: String): String {
    val text = try {
        if (file == "-") String(System.`in`.readBytes(), Charsets.UTF_8) else Files.readString(Path.of(file))
    } catch (failure: IOException) {
        throw CliFailure("the prompt file '$file' could not be read as UTF-8 text", failure)
    }
    return text.replace("\r\n", "\n")
}

private fun printPromptLines(prompts: CollectionPrompts, only: LlmPromptRole?) {
    LlmPromptRole.entries.filter { only == null || it == only }.forEach { role ->
        val rolePrompts = prompts.roleOf(role)
        rolePrompts.prompts.forEach { prompt ->
            val columns = listOf(prompt.name, prompt.version.toString(), if (prompt.id == rolePrompts.defaultId) "(default)" else "")
            writeExact((if (only == null) listOf(role.name.lowercase(Locale.ROOT)) + columns else columns).joinToString("\t"))
        }
    }
}

/**
 * Writes [text] exactly as given, tabs included. Clikt's terminal expands a tab to spaces, which would break the
 * TAB-separated columns of `list` and change a prompt body that contains a tab.
 */
private fun writeExact(text: String) {
    println(text)
}

private fun CollectionPrompts.toWire() = CollectionPromptsWire(coreRules, ask.toWire(), investigate.toWire())

private fun RolePrompts.toWire() = RolePromptsWire(defaultId, prompts.map { it.toWire() })

private fun CollectionPrompt.toWire() = PromptWire(id, name, body, version, builtIn)
