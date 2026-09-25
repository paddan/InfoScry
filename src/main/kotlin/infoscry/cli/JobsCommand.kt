package infoscry.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.findObject
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import infoscry.config.AppPaths
import infoscry.domain.Job
import infoscry.domain.JobId
import infoscry.domain.JobState
import infoscry.server.ApiJson
import infoscry.server.JobResponse
import infoscry.server.JobsResponse
import kotlinx.coroutines.runBlocking

/** How many jobs one page of `infoscry jobs` shows when the caller does not ask for a size. */
internal const val DEFAULT_JOB_PAGE = 100

/**
 * `infoscry jobs` — what the queue is doing, and how to stop one of it.
 *
 * The published command set is `infoscry jobs` (list) and `infoscry jobs cancel <job-id>`, so the
 * action is an optional argument rather than a subcommand group: Clikt 5 does not let a command with
 * subcommands run its own action when none is given, and the bare command has to list.
 */
class JobsCommand : CliktCommand(name = "jobs") {

    private val action by argument(
        name = "action",
        help = "Omit to list jobs; 'cancel' records a cancellation request for <job-id>",
    ).optional()

    private val jobId by argument(name = "job-id", help = "The job to cancel, as `infoscry jobs` prints it")
        .optional()

    private val limit by option(
        "--limit",
        help = "How many jobs to show, newest first (default: $DEFAULT_JOB_PAGE)",
    ).int().default(DEFAULT_JOB_PAGE)

    private val jsonFlag by option("--json", help = JSON_HELP).flag()

    private val dataDirOption by option("--data-dir", help = DATA_DIR_HELP).path()

    private val parentOptions by findObject<CliOptions>()

    override fun run() {
        val options = resolveOptions(parentOptions, jsonFlag, dataDirOption)
        when (action) {
            null -> list(options)
            "cancel" -> cancel(options, jobId ?: throw CliFailure("`infoscry jobs cancel` needs a job id"))
            else -> throw CliFailure("unknown jobs action '$action'; omit it to list, or use 'cancel <job-id>'")
        }
    }

    private fun list(options: CliOptions) {
        // A usage mistake is the caller's to fix, so it is reported the way every other refusal is
        // rather than as a stack trace.
        if (limit <= 0) throw CliFailure("--limit must be positive, was $limit")

        try {
            CliSession.connect(AppPaths.of(options.dataDir)).use { session ->
                val jobs = runBlocking { session.listJobs(limit) }
                if (options.json) {
                    echo(ApiJson.encodeToString(JobsResponse(jobs.map { it.toApiView() })))
                } else if (jobs.isEmpty()) {
                    echo("No jobs.")
                } else {
                    jobs.forEach { echo(describe(it, redactDetails = session.isRemote)) }
                    if (jobs.size == limit) {
                        echo("Showing the $limit most recent jobs; pass --limit for more.", err = true)
                    }
                }
            }
        } catch (failure: Exception) {
            throw CliFailure(failure.message ?: "the jobs could not be listed", failure)
        }
    }

    private fun cancel(options: CliOptions, id: String) {
        try {
            CliSession.connect(AppPaths.of(options.dataDir)).use { session ->
                val job = runBlocking { session.cancelJob(JobId(id)) }
                if (options.json) {
                    echo(ApiJson.encodeToString(JobResponse(job.toApiView())))
                } else {
                    echo(describeCancellation(job))
                }
            }
        } catch (failure: Exception) {
            throw CliFailure(failure.message ?: "job $id could not be cancelled", failure)
        }
    }

    private fun describeCancellation(job: Job): String = when (job.state) {
        JobState.RUNNING ->
            "Cancellation recorded for ${job.id.value}; the running attempt stops at its next stage."
        JobState.CANCELLED -> "Cancelled ${job.id.value}."
        else -> "Job ${job.id.value} is ${job.state}; there is nothing left to cancel."
    }

    private fun describe(job: Job, redactDetails: Boolean = false): String = buildString {
        append(job.id.value)
        append("  ")
        append(job.type)
        append("  ")
        append(job.state)
        append("  ")
        append(job.stage ?: "-")
        append("  ")
        append(job.completed)
        append('/')
        append(job.total)
        job.errorCode?.let { code ->
            append("  ")
            append(code)
            if (redactDetails) {
                append("  Details are intentionally omitted from the local API; see the server log.")
            } else {
                job.errorMessage?.let { append(": ").append(it) }
            }
        }
    }

    private fun Job.toApiView() = infoscry.server.JobApiView(
        id = id,
        type = type,
        state = state,
        createdAt = createdAt,
        updatedAt = updatedAt,
        collectionId = collectionId,
        stage = stage,
        completed = completed,
        total = total,
        errorCode = errorCode,
        cancelRequested = cancelRequested,
    )
}
