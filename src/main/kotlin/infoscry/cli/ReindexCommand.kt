package infoscry.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.findObject
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import infoscry.domain.Job
import infoscry.domain.JobId
import infoscry.domain.JobState
import infoscry.server.ApiJson
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable

/** What `infoscry reindex --json` reports when it did not wait for the outcome. */
@Serializable
data class ReindexAccepted(
    val accepted: Boolean,
    val jobId: String,
    val state: String,
)

/** What `infoscry reindex --json --wait` reports once the rebuild ended. */
@Serializable
data class ReindexResultJson(
    val jobId: String,
    val state: String,
    val documents: Int,
)

/**
 * `infoscry reindex` — ask the server to rebuild the search index from the persisted text.
 *
 * The rebuild is a job the server runs under exclusive maintenance; `--wait` follows it to the end.
 */
class ReindexCommand : CliktCommand(name = "reindex") {

    private val collection by option(
        "--collection",
        help = "Rebuild one collection only, by name or id; the default rebuilds every collection",
    )

    private val waitFlag by option("--wait", help = "Wait for the rebuild to finish").flag()

    private val jsonFlag by option("--json", help = JSON_HELP).flag()

    private val parentOptions by findObject<CliOptions>()

    override fun run() {
        val options = resolveOptions(parentOptions, jsonFlag, null)
        try {
            CliSession.connect().use { session ->
                val api = session.api
                val accepted = runBlocking { api.enqueueReindex(collection) }
                if (!waitFlag) {
                    reportAccepted(accepted.jobId, accepted.state, options)
                    return
                }
                val finished = runBlocking {
                    awaitTerminalJob(lookup = { api.getJob(JobId(accepted.jobId)) }, what = "reindex")
                }
                report(finished, options)
            }
        } catch (remote: RemoteApiFailure) {
            throw CliFailure("${remote.code}: ${remote.message}", remote)
        }
    }

    private fun reportAccepted(jobId: String, state: String, options: CliOptions) {
        if (options.json) {
            echo(
                ApiJson.encodeToString(
                    ReindexAccepted(accepted = true, jobId = jobId, state = state),
                ),
            )
            return
        }
        echo("Accepted job $jobId ($state).")
        echo("Use `infoscry jobs` or `infoscry reindex --wait` to watch it.", err = true)
    }

    private fun report(job: Job, options: CliOptions) {
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
                "the rebuild failed: ${job.errorCode ?: "JOB_FAILED"}; see the server log for the detail",
            )
        }
        if (job.state != JobState.COMPLETE) {
            throw CliFailure("the rebuild ended as ${job.state} without completing")
        }
    }
}
