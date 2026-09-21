package infoscry.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.findObject
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import infoscry.AppContext
import infoscry.config.AppPaths
import infoscry.config.ProcessLockUnavailable
import infoscry.config.RuntimeInfo
import infoscry.domain.DocumentStatus
import infoscry.logging.LoggingBootstrap
import infoscry.search.SearchMode
import infoscry.search.SearchOutcome
import infoscry.server.ApiJson
import infoscry.server.PRODUCT_NAME
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking

/** What `infoscry search --json` reports, in the shape the API reports it in. */
@kotlinx.serialization.Serializable
data class SearchJson(
    val hits: List<SearchHitJson>,
    val staleFiltered: Int,
)

@kotlinx.serialization.Serializable
data class SearchHitJson(
    val documentId: String,
    val unitId: String,
    val chunkOrdinal: Int,
    val locatorLabel: String,
    val matchedBy: List<String>,
    val text: String,
)

/**
 * `infoscry search` — look inside one collection.
 *
 * A read does not write, so it does not need the data directory's lock: when a server owns the
 * directory, this command asks it; when nothing does, it opens the archive itself, reads, and closes
 * it again. Either way it names one collection, because search reads one collection at a time.
 */
class SearchCommand : CliktCommand(name = "search") {

    private val collection by option(
        "--collection",
        help = "Collection to search, by name or id",
    ).required()

    private val query by argument(
        name = "query",
        help = "What to look for",
    )

    private val mode by option("--mode", help = "keyword, semantic, or hybrid (the default)")

    private val mediaTypes by option(
        "--media-type",
        help = "Restrict to a media type; repeatable",
    ).multiple()

    private val pathContains by option("--path", help = "Restrict to documents whose file or source path contains this")

    private val textContains by option(
        "--text",
        help = "Restrict to documents whose title, author or language probe contains this",
    )

    private val importedFrom by option("--from", help = "Restrict to documents imported from this instant")

    private val importedUntil by option("--until", help = "Restrict to documents imported until this instant")

    private val statuses by option("--status", help = "Restrict to a document status; repeatable").multiple()

    private val ocrOnly by option("--ocr-only", help = "Restrict to content that was OCR-derived").flag()

    private val limit by option("--limit", help = "How many hits to print (30 by default)").int().default(30)

    private val jsonFlag by option("--json", help = JSON_HELP).flag()

    private val dataDirOption by option("--data-dir", help = DATA_DIR_HELP).path()

    private val parentOptions by findObject<CliOptions>()

    override fun run() {
        val options = resolveOptions(parentOptions, jsonFlag, dataDirOption)
        val paths = AppPaths.of(options.dataDir)
        val outcome = RuntimeInfo.discover(paths.runtimeFile)?.let { remote ->
            searchThroughServer(remote)
        } ?: searchHere(paths)
        report(outcome, options)
    }

    /** Asks the server that owns the data directory, so the lock is never contended for a read. */
    private fun searchThroughServer(runtime: RuntimeInfo): SearchOutcome {
        LoopbackApi(runtime).use { api ->
            return runBlocking {
                api.search(collection = collection, query = query, mode = mode, filters = buildFilters(), limit = limit)
            }
        }
    }

    /** Opens the archive for this one read: nobody else owns the directory. */
    private fun searchHere(paths: AppPaths): SearchOutcome {
        LoggingBootstrap.useLogsDirectory(paths)
        val context = try {
            AppContext.open(paths)
        } catch (unavailable: ProcessLockUnavailable) {
            throw CliFailure(unavailable.message ?: "another InfoScry process owns ${paths.root}", unavailable)
        }
        context.use { open ->
            return runBlocking {
                val collection = open.collectionService.requireActiveByNameOrId(collection)
                open.search.search(
                    queryText = query,
                    mode = infoscry.server.parseSearchMode(mode),
                    filters = buildFilters(collectionId = collection.id),
                )
            }
        }
    }

    private fun buildFilters(collectionId: infoscry.domain.CollectionId? = null) = infoscry.search.SearchFilters(
        collectionId = collectionId,
        mediaTypes = mediaTypes.toSet(),
        filenameOrPathContains = pathContains,
        titleAuthorOrLanguageContains = textContains,
        importedFrom = importedFrom,
        importedUntil = importedUntil,
        statuses = statuses.map { DocumentStatus.valueOf(it) }.toSet(),
        ocrOnly = ocrOnly,
    )

    private fun report(outcome: SearchOutcome, options: CliOptions) {
        if (options.json) {
            echo(
                ApiJson.encodeToString(
                    SearchJson(
                        hits = outcome.hits.take(limit).map { hit ->
                            SearchHitJson(
                                documentId = hit.documentId.value,
                                unitId = hit.unitId.value,
                                chunkOrdinal = hit.chunkOrdinal,
                                locatorLabel = hit.locatorLabel,
                                matchedBy = hit.matchedBy.map { it.name },
                                text = hit.text,
                            )
                        },
                        staleFiltered = outcome.staleFiltered,
                    ),
                ),
            )
            return
        }
        outcome.hits.take(limit).forEach { hit ->
            echo("[${hit.matchedBy.joinToString("|") { it.name }}] ${hit.locatorLabel}")
            echo("  ${hit.text.lines().firstOrNull()?.take(160).orEmpty()}")
        }
        echo("${outcome.hits.size} hit(s); ${outcome.staleFiltered} index row(s) no longer had a document.")
        if (outcome.hits.isEmpty()) {
            echo("No hits in $collection.", err = true)
        }
    }
}
