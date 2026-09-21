package infoscry.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.findObject
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.path
import infoscry.AppContext
import infoscry.config.AppPaths
import infoscry.config.ProcessLockUnavailable
import infoscry.embedding.DocumentEmbedder
import infoscry.config.RuntimeInfo
import infoscry.diagnostics.ToolProbe
import infoscry.domain.Job
import infoscry.domain.JobId
import infoscry.domain.JobState
import infoscry.domain.JobType
import infoscry.jobs.ImportJobHandler
import infoscry.jobs.ImportJobPayload
import infoscry.jobs.ImportPipeline
import infoscry.logging.LoggingBootstrap
import infoscry.server.ApiJson
import infoscry.server.PRODUCT_NAME
import infoscry.storage.ImportItem
import infoscry.storage.ImportItemOutcome
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable

/** What `infoscry import --json` reports when it did not wait for the outcome. */
@Serializable
data class ImportAccepted(
    val accepted: Boolean,
    val jobId: String,
    val state: String,
    val executedHere: Boolean,
)

/** What `infoscry import --json --wait` reports once the import ended. */
@Serializable
data class ImportResult(
    val jobId: String,
    val state: String,
    val imported: Int,
    val duplicates: Int,
    val failed: Int,
    val items: List<ImportItem>,
)

/**
 * `infoscry import` — put files into a collection.
 *
 * Where the work happens is decided by who owns the data directory, not by preference. If a server is
 * running, this command hands the request over and answers with the job it was accepted as; if nothing
 * owns the directory, the command takes the lock itself and runs the import **in this process**, because a
 * job must never outlive the process that owns its archive — a detached worker would be a second writer
 * with no way to report back. That is why a foreground import waits regardless of `--wait`: it holds the
 * only key to the archive, and giving the key back before the work is done is what leaves an import
 * ownerless.
 */
class ImportCommand(
    private val pipeline: (AppContext) -> ImportPipeline = { context -> ImportPipeline.production(context) },
    private val importEmbedder: (AppContext) -> () -> DocumentEmbedder? = ::productionImportEmbedder,
) : CliktCommand(name = "import") {

    private val collection by option(
        "--collection",
        help = "Collection to import into, by name or id",
    ).required()

    private val sources by argument(
        name = "paths",
        help = "Files or directories to import",
    ).multiple(required = true)

    private val waitFlag by option(
        "--wait",
        help = "Wait for the import to finish and report every document",
    ).flag()

    private val jsonFlag by option("--json", help = JSON_HELP).flag()

    private val dataDirOption by option("--data-dir", help = DATA_DIR_HELP).path()

    private val parentOptions by findObject<CliOptions>()

    override fun run() {
        val options = resolveOptions(parentOptions, jsonFlag, dataDirOption)
        val paths = AppPaths.of(options.dataDir)
        val requested = canonicalSources(sources)

        val remote = RuntimeInfo.discover(paths.runtimeFile)
        if (remote != null) {
            importThroughServer(remote, requested, options)
            return
        }
        importHere(paths, requested, options)
    }

    /**
     * Runs the import in this process, holding the data directory for as long as it takes.
     *
     * The lock is the reason this cannot be a fire-and-forget: only the process that holds it may write,
     * so the command stays alive until every document is done and then reports each result itself.
     */
    private fun importHere(paths: AppPaths, requested: List<String>, options: CliOptions) {
        LoggingBootstrap.useLogsDirectory(paths)
        val context = try {
            AppContext.open(paths)
        } catch (unavailable: ProcessLockUnavailable) {
            // Somebody took the directory between the discovery above and here — a server that is starting,
            // most likely. Asking it is the right answer; failing with "already running" would be true but
            // useless.
            val appeared = RuntimeInfo.discover(paths.runtimeFile)
                ?: throw CliFailure(
                    unavailable.message ?: "another InfoScry process owns ${paths.root}",
                    unavailable,
                )
            importThroughServer(appeared, requested, options)
            return
        }

        context.use { open ->
            echo(
                "No $PRODUCT_NAME server owns ${paths.root}; importing in this process and holding the " +
                    "data directory until it finishes.",
                err = true,
            )
            val collection = open.collectionService.requireActiveByNameOrId(collection)
            // The tool's version is asked for once here, before the job exists, because it is part of what
            // the job's checkpoints are keyed by. `runBlocking` rather than a suspend command: the CLI is
            // a blocking program, and this is the one suspension it has before its own job loop starts.
            val settings = runBlocking { ToolProbe.extractionSettings(collection.ocrLanguages) }
            val payload = ImportJobPayload.of(
                collectionId = collection.id,
                sources = requested,
                settings = settings,
            )
            val job = open.jobs.enqueue(
                type = JobType.IMPORT,
                collectionId = collection.id,
                payload = payload.encode(),
                total = 0,
            )
            // The worker is attached after the job exists, so there is no window where the runner is
            // claiming from a queue this command has not filled yet.
            ImportJobHandler.attachTo(open, pipeline(open), documentEmbedder = importEmbedder(open))
            echo("Importing ${requested.size} path(s) as job ${job.id.value}.")
            val finished = runBlocking {
                awaitTerminalJob(
                    lookup = {
                        open.jobs.get(job.id)
                            ?: throw NoSuchElementException("no job with id ${job.id.value}")
                    },
                    what = "import",
                )
            }
            report(finished, open.importItems.listForJob(job.id), options, executedHere = true)
        }
    }

    /** Enqueues the import on the server that owns the data directory. */
    private fun importThroughServer(runtime: RuntimeInfo, requested: List<String>, options: CliOptions) {
        LoopbackApi(runtime).use { api ->
            val accepted = runBlocking { api.enqueueImport(collection, requested) }
            if (!waitFlag) {
                reportAccepted(accepted.job.id, accepted.job.state, options, executedHere = false)
                return
            }
            val finished = runBlocking { awaitTerminalJob(lookup = { api.getJob(accepted.job.id) }, what = "import") }
            val items = runBlocking { api.importItems(accepted.job.id) }
            report(finished, items, options, executedHere = false)
        }
    }

    private fun reportAccepted(jobId: JobId, state: JobState, options: CliOptions, executedHere: Boolean) {
        if (options.json) {
            echo(
                ApiJson.encodeToString(
                    ImportAccepted(
                        accepted = true,
                        jobId = jobId.value,
                        state = state.name,
                        executedHere = executedHere,
                    ),
                ),
            )
            return
        }
        val where = if (executedHere) "in this process" else "by the running server"
        echo("Accepted job ${jobId.value} ($state), running $where.")
        if (executedHere) {
            echo("Use `infoscry jobs` to watch it.", err = true)
        } else {
            echo("Use `infoscry jobs` or `infoscry import --wait` to watch it.", err = true)
        }
    }

    /**
     * Prints one line per document and exits nonzero when any of them failed.
     *
     * The exit code is the contract a script reads, so a partial import must not look like a successful
     * one: the warnings are printed, and the command still fails.
     */
    private fun report(
        job: Job,
        items: List<ImportItem>,
        options: CliOptions,
        executedHere: Boolean,
    ) {
        val counts = items.groupingBy { it.outcome }.eachCount()
        val imported = counts[ImportItemOutcome.IMPORTED] ?: 0
        val duplicates = counts[ImportItemOutcome.DUPLICATE] ?: 0
        val failed = counts[ImportItemOutcome.FAILED] ?: 0

        if (options.json) {
            echo(
                ApiJson.encodeToString(
                    ImportResult(
                        jobId = job.id.value,
                        state = job.state.name,
                        imported = imported,
                        duplicates = duplicates,
                        failed = failed,
                        items = items,
                    ),
                ),
            )
        } else {
            items.forEach { item -> echo(describe(item)) }
            echo("Imported $imported, duplicate $duplicates, failed $failed of ${items.size}.")
            if (job.state == JobState.FAILED) {
                echo("The job failed: ${job.errorCode} ${job.errorMessage.orEmpty()}".trim(), err = true)
            }
        }

        if (job.state == JobState.CANCELLED) {
            throw CliFailure("the import was cancelled; ${items.size - failed} document(s) had been processed")
        }
        if (job.state == JobState.FAILED) {
            throw CliFailure(
                "the import failed: ${job.errorCode ?: "JOB_FAILED"} ${job.errorMessage.orEmpty()}".trim(),
            )
        }
        if (failed > 0) {
            throw CliFailure("$failed of ${items.size} document(s) could not be imported")
        }
        if (executedHere && job.state != JobState.COMPLETE) {
            throw CliFailure("the import ended as ${job.state} without processing every document")
        }
    }

    private fun describe(item: ImportItem): String = buildString {
        append(item.outcome)
        append("  ")
        append(item.sourcePath)
        item.documentId?.let { document ->
            append("  -> document ")
            append(document.value)
        }
        item.errorCode?.let { code ->
            append("  ")
            append(code)
            item.errorMessage?.let { message -> append(": ").append(message) }
        }
    }

    /** Fails before anything is queued when a named path does not exist: a typo is not a durable job. */
    private fun canonicalSources(paths: List<String>): List<String> {
        require(paths.isNotEmpty()) { "an import needs at least one path" }
        return paths.map { raw ->
            val given = Path.of(raw)
            if (!Files.exists(given)) throw CliFailure("no such file or directory: $raw")
            given.toAbsolutePath().normalize().toString()
        }
    }



}
