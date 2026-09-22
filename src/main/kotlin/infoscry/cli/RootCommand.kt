package infoscry.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.findOrSetObject
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import infoscry.embedding.DocumentEmbedder
import infoscry.embedding.E5Embedder
import java.nio.file.Path

/**
 * The document embedder production wiring, as one function a composition root can hand to the import path.
 *
 * It answers lazily and per process (the pinned model is 1.1 GB and deliberately not a startup
 * requirement), and it returns `null` when the model is not installed so the import stage reports the
 * install remedy per document. The harness substitutes a deterministic fake at this one seam.
 */
internal fun productionImportEmbedder(context: infoscry.AppContext): () -> DocumentEmbedder? =
    E5Embedder.productionDocumentEmbedder(
        modelsDir = context.paths.modelsDir,
        profileDirectory = context.paths.embeddingProfileDir,
    )

/** The help text for the two options every command accepts, in one place. */
internal const val JSON_HELP = "Print stable machine-readable JSON instead of text"
internal const val DATA_DIR_HELP = "Data directory to use (default: ~/.infoscry)"

/**
 * A failure the user can act on.
 *
 * Thrown instead of letting an exception escape, so the terminal shows the message rather than a stack
 * trace: "another InfoScry is already running" is actionable, and eleven frames of
 * `Exception in thread "main"` is not.
 */
class CliFailure(message: String, cause: Exception? = null) : CliktError(message, cause = cause)

/** What the root command parsed, and what every subcommand resolves its own options against. */
data class CliOptions(val json: Boolean, val dataDir: Path)

/**
 * Resolves the two shared options for a subcommand.
 *
 * They are declared on the root *and* on each subcommand, because Clikt parses options per command:
 * `infoscry --json collection list` and `infoscry collection list --json` both have to work, and the
 * second is the spelling people type. Resolving them in one function keeps the precedence identical
 * everywhere — the subcommand's own value wins, then the root's, then the default.
 */
internal fun resolveOptions(parent: CliOptions?, json: Boolean, dataDir: Path?): CliOptions = CliOptions(
    json = json || (parent?.json ?: false),
    dataDir = dataDir ?: parent?.dataDir ?: RootCommand.defaultDataDirectory(),
)

/**
 * `infoscry` — the root command.
 *
 * It owns the two options that apply everywhere, and creates the subcommands. The options are also
 * accepted after a subcommand name; see [resolveOptions].
 */
class RootCommand(
    private val pipeline: (infoscry.AppContext) -> infoscry.jobs.ImportPipeline =
        { context -> infoscry.jobs.ImportPipeline.production(context) },
    private val importEmbedder: (infoscry.AppContext) -> () -> DocumentEmbedder? =
        ::productionImportEmbedder,
) : CliktCommand(name = "infoscry") {

    private val json by option("--json", help = JSON_HELP).flag()

    private val dataDir by option("--data-dir", help = DATA_DIR_HELP).path()

    /**
     * The parsed options, published for the subcommands to read.
     *
     * Reading this property in [run] makes the object exist before any subcommand can ask for it, which
     * is why the parent reads it even though it does not otherwise need it.
     */
    private val options by findOrSetObject {
        CliOptions(json = json, dataDir = dataDir ?: defaultDataDirectory())
    }

    init {
        subcommands(
            LogsCommand(),
            ServeCommand(pipeline, importEmbedder),
            CollectionCommand(),
            JobsCommand(),
            ImportCommand(pipeline, importEmbedder),
            SearchCommand(),
            ReindexCommand(),
            LlmCommand(),
            AskCommand(),
        )
    }

    override fun run() {
        options
        // Clikt runs the parent before the subcommand, so help appears only when nothing was invoked.
        if (currentContext.invokedSubcommand == null) {
            echoFormattedHelp()
        }
    }

    companion object {
        fun defaultDataDirectory(): Path = Path.of(System.getProperty("user.home"), ".infoscry")
    }
}

/**
 * `infoscry collection` — create and list the logical search boundaries.
 *
 * Renaming and deleting exist in the API; the CLI exposes what the published command set names, so a
 * script that creates a collection and imports into it does not have to speak HTTP.
 */
class CollectionCommand : CliktCommand(name = "collection") {

    init {
        subcommands(ListCollectionsCommand(), CreateCollectionCommand())
    }

    override fun run() {
        if (currentContext.invokedSubcommand == null) {
            echoFormattedHelp()
        }
    }
}
