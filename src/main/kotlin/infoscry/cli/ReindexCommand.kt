package infoscry.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.findObject
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import infoscry.AppContext
import infoscry.config.AppPaths
import infoscry.config.ProcessLockUnavailable
import infoscry.config.RuntimeInfo
import infoscry.domain.Job
import infoscry.domain.JobId
import infoscry.domain.JobState
import infoscry.domain.JobType
import infoscry.jobs.ImportJobHandler
import infoscry.logging.LoggingBootstrap
import infoscry.server.ApiJson
import infoscry.server.PRODUCT_NAME
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable

/** What `infoscry reindex --json` reports when it did not wait for the outcome. */
@Serializable
data class ReindexAccepted(
    val accepted: Boolean,
    val jobId: String,
    val state: String,
    val executedHere: Boolean,
)

/** What `infoscry reindex --json --wait` reports once the rebuild ended. */
@Serializable
data class ReindexResultJson(
    val jobId: String,
    val state: String,
    val documents: Int,
)

/**
 * `infoscry reindex` — rebuild the search index from the persisted text.
 *
 * The rebuild is exclusive maintenance for its whole duration, so the same ownership rule an import
 * follows decides where it runs: a server that owns the data directory is asked to run it, and a
 * directory nobody owns is opened here, in this process, which holds the lock until the rebuild is
 * done. A detached rebuild would be a second writer over an archive that is being rebuilt under it.
 */
class ReindexCommand : CliktCommand(name = "reindex") {

    private val collection by option(
        "--collection",
        help = "Rebuild one collection only, by name or id; the default rebuilds every collection",
    )

    private val waitFlag by option("--wait", help = "Wait for the rebuild to finish").flag()

    private val jsonFlag by option("--json", help = JSON_HELP).flag()

    private val dataDirOption by option("--data-dir", help = DATA_DIR_HELP).path()

    private val parentOptions by findObject<CliOptions>()

    override fun run() {
        val options = resolveOptions(parentOptions, jsonFlag, dataDirOption)
        val paths = AppPaths.of(options.dataDir)

        val remote = RuntimeInfo.discover(paths.runtimeFile)
        if (remote != null) {
            reindexThroughServer(remote, options)
            return
        }
        reindexHere(paths, options)
    }

    /**
     * Runs the rebuild in this process, holding the data directory for as long as it takes.
     *
     * The worker is attached after the job exists, exactly as an import does, so the runner never
     * claims from a queue this command has not filled.
     */
    private fun reindexHere(paths: AppPaths, options: CliOptions) {
        LoggingBootstrap.useLogsDirectory(paths)
        val context = try {
            AppContext.open(paths)
        } catch (unavailable: ProcessLockUnavailable) {
            val appeared = RuntimeInfo.discover(paths.runtimeFile)
                ?: throw CliFailure(
                    unavailable.message ?: "another InfoScry process owns ${paths.root}",
                    unavailable,
                )
            reindexThroughServer(appeared, options)
            return
        }

        context.use { open ->
            echo(
                "No $PRODUCT_NAME server owns ${paths.root}; rebuilding the index in this process and " +
                    "holding the data directory until it finishes.",
                err = true,
            )
            val collectionId = collection?.let { reference ->
                open.collectionService.requireActiveByNameOrId(reference).id.value
            }
            val job = open.jobs.enqueue(
                type = JobType.REINDEX,
                collectionId = collectionId?.let { infoscry.domain.CollectionId(it) },
                payload = infoscry.jobs.ReindexJobPayload(collectionId).encode(),
                total = 0,
            )
            // Attaching the runner is what starts it; it owns both job types, so the rebuild is
            // claimed by the same worker that would claim an import.
            ImportJobHandler.attachTo(open)
            echo("Rebuilding the index as job ${job.id.value}.")
            val finished = runBlocking {
                awaitTerminalJob(
                    lookup = { open.jobs.get(job.id) ?: throw NoSuchElementException("no job with id ${job.id.value}") },
                    what = "reindex",
                )
            }
            report(finished, options, executedHere = true)
        }
    }

    /** Enqueues the rebuild on the server that owns the data directory. */
    private fun reindexThroughServer(runtime: RuntimeInfo, options: CliOptions) {
        LoopbackApi(runtime).use { api ->
            val accepted = runBlocking { api.enqueueReindex(collection) }
            if (!waitFlag) {
                reportAccepted(accepted.jobId, accepted.state, options, executedHere = false)
                return
            }
            val finished = runBlocking {
                awaitTerminalJob(lookup = { api.getJob(JobId(accepted.jobId)) }, what = "reindex")
            }
            report(finished, options, executedHere = false)
        }
    }

    private fun reportAccepted(jobId: String, state: String, options: CliOptions, executedHere: Boolean) {
        if (options.json) {
            echo(
                ApiJson.encodeToString(
                    ReindexAccepted(accepted = true, jobId = jobId, state = state, executedHere = executedHere),
                ),
            )
            return
        }
        val where = if (executedHere) "in this process" else "by the running server"
        echo("Accepted job $jobId ($state), running $where.")
        echo("Use `infoscry jobs` or `infoscry reindex --wait` to watch it.", err = true)
    }

    private fun report(job: Job, options: CliOptions, executedHere: Boolean) {
        if (options.json) {
            echo(
                ApiJson.encodeToString(
                    ReindexResultJson(
                        jobId = job.id.value,
                        state = job.state.name,
                        documents = job.completed,
                    ),
                ),
            )
            return
        }
        echo("Rebuilt the index for ${job.completed} document(s); the job ended as ${job.state}.")
        if (job.state == JobState.CANCELLED) {
            throw CliFailure("the rebuild was cancelled; the index it replaced is still the one being served")
        }
        if (job.state == JobState.FAILED) {
            throw CliFailure(
                "the rebuild failed: ${job.errorCode ?: "JOB_FAILED"} ${job.errorMessage.orEmpty()}".trim(),
            )
        }
        if (executedHere && job.state != JobState.COMPLETE) {
            throw CliFailure("the rebuild ended as ${job.state} without completing")
        }
    }
}
