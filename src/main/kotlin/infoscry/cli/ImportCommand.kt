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
import infoscry.ocr.OcrEndpointScope
import infoscry.ocr.OcrExternalOwner
import infoscry.ocr.OcrOperationStage
import infoscry.server.ApiJson
import infoscry.server.PRODUCT_NAME
import infoscry.storage.ImportItem
import infoscry.storage.ImportItemOutcome
import infoscry.storage.JobStore
import infoscry.storage.MaintenanceInProgressException
import infoscry.storage.OcrOperationStore
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
    val items: List<ImportItemResult>,
)

@Serializable
data class ImportItemResult(
    val id: String,
    val jobId: infoscry.domain.JobId,
    val documentId: infoscry.domain.DocumentId?,
    val sourcePath: String?,
    val sourceName: String?,
    val outcome: ImportItemOutcome,
    val errorCode: String?,
    val errorMessage: String?,
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

    private val recursiveFlag by option(
        "--recursive",
        help = "Recurse into subdirectories when importing a directory",
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
            // a blocking program, and this is the one suspension it has before its own job loop starts. The
            // snapshot records the reading engine's runtime for the same reason — a resumed attempt reads
            // under the runtime it was admitted with, never one discovered later.
            val snapshot = runBlocking {
                open.ocr.withProbedRuntime(
                    open.ocr.snapshotFor(
                        settings = collection.ocrSettings(),
                        extractorVersion = infoscry.extract.EXTRACTOR_SCHEMA_VERSION,
                        renderDpi = infoscry.extract.PdfExtractor.DEFAULT_RENDER_DPI,
                    ),
                )
            }
            val settings = runBlocking { ToolProbe.extractionSettings(collection.ocrLanguages) }
                .forOcrSettings(snapshot)
            val payload = ImportJobPayload.of(
                collectionId = collection.id,
                sources = requested,
                settings = settings,
                recursive = recursiveFlag,
                ocr = snapshot,
            )
            val job = try {
                runBlocking {
                    open.mutations.withMutation {
                        // The same gate the API route passes: an unresolved unsafe deletion refuses new work
                        // here too, so a standalone import cannot add documents an operator has to repair
                        // around first.
                        open.collectionService.requireMutationsAllowed()
                        open.jobs.enqueue(
                            type = JobType.IMPORT,
                            collectionId = collection.id,
                            payload = payload.encode(),
                            total = 0,
                        )
                    }
                }
            } catch (maintenance: MaintenanceInProgressException) {
                throw CliFailure("MAINTENANCE_IN_PROGRESS: ${maintenance.message.orEmpty()}", maintenance)
            }
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
            val items = open.importItems.listForJob(job.id)
            // What this import did with pages that leave the machine, and what has to happen before more of them
            // may: a command that reported a clean success while a scope waited for a person would be telling
            // the person the archive did something it did not do.
            reportExternalAdmission(open, job.id, payload, items)
            report(
                job = finished,
                items = items,
                options = options,
                executedHere = true,
                // The standalone path holds the payload, so the remedy can name the exact hash an approval has
                // to carry rather than a placeholder.
                snapshotHash = payload.ocr?.let(OcrOperationStore::snapshotHashOf),
            )
        }
    }

    /** Enqueues the import on the server that owns the data directory. */
    private fun importThroughServer(runtime: RuntimeInfo, requested: List<String>, options: CliOptions) {
        LoopbackApi(runtime).use { api ->
            val accepted = try {
                runBlocking { api.enqueueImport(collection, requested, recursiveFlag) }
            } catch (failure: RemoteApiFailure) {
                throw CliFailure("${failure.code}: ${failure.message}", failure)
            }
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
        snapshotHash: String? = null,
    ) {
        val counts = items.groupingBy { it.outcome }.eachCount()
        val imported = counts[ImportItemOutcome.IMPORTED] ?: 0
        val duplicates = counts[ImportItemOutcome.DUPLICATE] ?: 0
        val failed = counts[ImportItemOutcome.FAILED] ?: 0

        // A job that stopped for an external page approval has *not* finished: what it did is exactly the part
        // of the import that needed nothing external, and the rest is waiting for a person. Reporting a result
        // here would tell them the archive did something it deliberately did not — nothing beyond the approved
        // scope was sent — so the requirement and its remedy are what this prints.
        if (job.stage == JobStore.AWAITING_APPROVAL_STAGE) {
            val waiting = items.count { it.outcome != ImportItemOutcome.IMPORTED }
            echo("$waiting of ${items.size} file(s) wait for an external page approval before they can be read.", err = true)
            echo(waitingApprovalRemedy(job.id.value, snapshotHash), err = true)
            throw CliFailure(
                "the import stopped before sending a page its external scope did not cover; approve the scope " +
                    "and run the import again — the files it already imported are skipped, and nothing beyond " +
                    "the approved scope was sent",
            )
        }

        if (options.json) {
            echo(
                ApiJson.encodeToString(
                    ImportResult(
                        jobId = job.id.value,
                        state = job.state.name,
                        imported = imported,
                        duplicates = duplicates,
                        failed = failed,
                        items = items.map { item ->
                            ImportItemResult(
                                id = item.id,
                                jobId = item.jobId,
                                documentId = item.documentId,
                                sourcePath = item.sourcePath.takeIf(String::isNotEmpty),
                                sourceName = item.sourceName,
                                outcome = item.outcome,
                                errorCode = item.errorCode,
                                errorMessage = item.errorMessage,
                            )
                        },
                    ),
                ),
            )
        } else {
            items.forEach { item -> echo(describe(item)) }
            echo("Imported $imported, duplicate $duplicates, failed $failed of ${items.size}.")
            if (job.state == JobState.FAILED) {
                val detail = if (executedHere) " ${job.errorMessage.orEmpty()}" else
                    ". Detailed failure text is omitted by the local API; see the server log."
                echo("The job failed: ${job.errorCode ?: "JOB_FAILED"}$detail".trim(), err = true)
            }
        }

        if (job.state == JobState.CANCELLED) {
            throw CliFailure("the import was cancelled; ${items.size - failed} document(s) had been processed")
        }
        if (job.state == JobState.FAILED) {
            val detail = if (executedHere) " ${job.errorMessage.orEmpty()}" else
                ". Detailed failure text is omitted by the local API; see the server log."
            throw CliFailure(
                "the import failed: ${job.errorCode ?: "JOB_FAILED"}$detail".trim(),
            )
        }
        if (failed > 0) {
            throw CliFailure("$failed of ${items.size} document(s) could not be imported")
        }
        if (executedHere && job.state != JobState.COMPLETE) {
            throw CliFailure("the import ended as ${job.state} without processing every document")
        }
    }

    /**
     * Reports what left the machine, and fails when an admission a person owes is outstanding.
     *
     * The standalone import owns its process until the job ends, so this is the moment the truth is known
     * rather than guessed. Two things are reported: the external-page counters of the *job* — one allowance
     * covers every file of one import — and the remedy for an operation that is waiting for an approval.
     * More pages sent than the allowance authorized is a failure of the command, because the archive has done
     * something no person approved; a scope that is exactly spent is a warning, because the work is done.
     */
    private fun reportExternalAdmission(
        context: AppContext,
        jobId: JobId,
        payload: ImportJobPayload,
        items: List<ImportItem>,
    ) {
        val snapshot = payload.ocr
        val external = snapshot != null && listOfNotNull(
            snapshot.transcriptionProfileRevisionId,
            snapshot.reviewProfileRevisionId,
        ).any { revisionId -> context.ocrProfiles.findRevision(revisionId)?.scope == OcrEndpointScope.EXTERNAL }
        if (external) {
            val owner = OcrExternalOwner.job(jobId.value)
            val account = context.ocrOperations.allowanceFor(
                owner = owner,
                configuredAllowance = snapshot!!.externalPageLimit,
                snapshotHash = OcrOperationStore.snapshotHashOf(snapshot),
            )
            echo(
                "External pages: ${account.distinctPages} distinct page(s) sent, allowance ${account.allowance}, " +
                    "${account.calls} provider call(s) (a page transcribed and then reviewed is one page and " +
                    "two calls).",
            )
            if (account.distinctPages > account.allowance) {
                echo(approvalRemedy(jobId.value, snapshot.externalPageLimit, account.distinctPages), err = true)
                throw CliFailure(
                    "this import sent ${account.distinctPages} page(s) while only ${account.allowance} were " +
                        "authorized; the scope has to be approved before more pages may leave this machine",
                )
            }
        }

        // A rescan of an imported document — a person's later action, or another process's — can also be left
        // waiting for an approval, and an import that reported success while one was would be hiding it.
        val waiting = items.mapNotNull { item -> item.documentId }
            .distinct()
            .flatMap { documentId -> context.ocrOperations.operations(documentId) }
            .filter { operation -> operation.stage == OcrOperationStage.AWAITING_APPROVAL }
        if (waiting.isEmpty()) return
        waiting.forEach { operation ->
            echo(
                "Approval needed: document ${operation.documentId.value} has sent " +
                    "${operation.external.distinctPages} page(s) and waits before it reads another.",
                err = true,
            )
            echo(approvalRemedy(operation), err = true)
        }
        throw CliFailure(
            "${waiting.size} rescan(s) are waiting for an external page approval; nothing beyond the approved " +
                "scope was sent",
        )
    }

    /**
     * The remedy for an import that stopped for an external page approval, as a command a person can run.
     *
     * The approval is bound to the job's own OCR selection — that binding is what stops one scope's approval
     * from covering another — and this standalone path knows the hash because the payload is its own. The
     * remote path never receives the payload, so it names what the body has to carry rather than inventing a
     * hash no approval would match.
     */
    private fun waitingApprovalRemedy(jobId: String, snapshotHash: String?): String =
        "Approve the import's scope: POST /api/jobs/$jobId/approve-external with the runtime bearer token and " +
            "body {\"expectedSnapshotHash\":\"${snapshotHash ?: "the job's OCR selection hash"}\"," +
            "\"maxDistinctPages\":<the number of distinct pages you authorize>}"

    /**
     * The remedy for an unapproved external scope, as a command a person can run.
     *
     * It names the exact call, the snapshot hash the approval has to bind to, and the collection and document
     * it belongs to, because an approval that named another scope would be refused — that refusal is the point
     * of binding one to a snapshot hash.
     */
    private fun approvalRemedy(operation: infoscry.ocr.OcrOperation): String =
        "Approve the scope it was admitted with: POST " +
            "/api/collections/${operation.collectionId}/documents/${operation.documentId.value}/ocr/operations/" +
            "${operation.operationId}/approve-external with the runtime bearer token and body " +
            "{\"expectedSnapshotHash\":\"${OcrOperationStore.snapshotHashOf(operation.snapshot)}\"," +
            "\"maxDistinctPages\":<the number of distinct pages you authorize>}"

    private fun approvalRemedy(jobId: String, allowance: Int, sent: Int): String =
        "Approve the import's scope: POST /api/jobs/$jobId/approve-external with the runtime bearer token and " +
            "body {\"expectedSnapshotHash\":\"<the job's snapshot hash>\",\"maxDistinctPages\":<more than " +
            "$sent, the pages already sent with an allowance of $allowance>}"

    private fun describe(item: ImportItem): String = buildString {
        append(item.outcome)
        append("  ")
        append(item.sourcePath.ifEmpty { item.sourceName ?: "selected source (path not reported)" })
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
