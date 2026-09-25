package infoscry.server

import infoscry.AppContext
import infoscry.domain.Job
import infoscry.domain.JobId
import infoscry.domain.JobState
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.respondOutputStream
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/**
 * One job's state on the events stream: the fields the jobs page renders, and nothing the redaction
 * rules protect. [Job.payload] and [Job.errorMessage] can carry paths — the import request names the
 * files the user picked, and the worker's failure note names the tool or the file that failed — so
 * they stay out of every event. The error code crosses, because a caller is meant to act on it.
 */
@Serializable
internal data class JobEventWire(
    val jobId: String,
    val state: JobState,
    val stage: String? = null,
    val completed: Int = 0,
    val total: Int = 0,
    val errorCode: String? = null,
    val cancelRequested: Boolean = false,
)

private fun Job.toJobEventWire() = JobEventWire(
    jobId = id.value,
    state = state,
    stage = stage,
    completed = completed,
    total = total,
    errorCode = errorCode,
    cancelRequested = cancelRequested,
)

private fun JobState.isTerminal(): Boolean =
    this == JobState.COMPLETE || this == JobState.FAILED || this == JobState.CANCELLED

/**
 * `GET /api/jobs/{id}/events` — one job's state and progress as server-sent events.
 *
 * A read, so it needs no credential: the loopback guard already rejects foreign hosts, and everything
 * the stream can say a caller could read from the data directory anyway.
 *
 * The stream begins with the job's current state, so a client that connects mid-run is immediately
 * correct, then emits an event whenever the job's row changes. The store is polled ([JOB_EVENT_POLL_INTERVAL_MILLIS])
 * rather than observed, so two changes inside one interval collapse into the event for the state that
 * won; the resource to preserve with a store is the durable state, not the tick.
 *
 * Every event carries an opaque, monotonically increasing per-stream `id:`. The server keeps no
 * history, so an id only means something while it names an event this job's stream emitted; the one
 * guarantee with a `Last-Event-ID` is that the sequence continues past the client's last id — the
 * current state is re-sent under the next id, never an event the client already has. Missed events are
 * healed by the snapshot because state and progress are cumulative counters, not deltas.
 */
fun Routing.configureJobEventRoutes(context: AppContext, idleDeadlineMillis: Long = DEFAULT_JOB_EVENT_IDLE_DEADLINE_MILLIS) {
    get("/api/jobs/{id}/events") {
        call.handle {
            val jobId = call.jobId()
            val job = context.jobs.get(jobId)
                ?: throw NoSuchElementException("no job with id ${jobId.value}")
            call.streamJobEvents(context, jobId, job, idleDeadlineMillis = idleDeadlineMillis)
        }
    }
}

private suspend fun ApplicationCall.streamJobEvents(
    context: AppContext,
    jobId: JobId,
    initial: Job,
    idleDeadlineMillis: Long,
) {
    val lastEventId = request.headers[LAST_EVENT_ID_HEADER]?.let { raw ->
        raw.toLongOrNull()
            ?.takeIf { it >= 0 && it < Long.MAX_VALUE }
            ?: throw BadRequestException("Last-Event-ID must be a non-negative integer below ${Long.MAX_VALUE}")
    } ?: 0L
    respondOutputStream(ContentType.Text.EventStream, HttpStatusCode.OK) {
        // Ktor 3.2 has no HttpHeaders.LastEventID constant, so the header is named explicitly.
        var sequence = lastEventId
        fun writeEvent(job: Job): Boolean {
            if (sequence == Long.MAX_VALUE) return false
            sequence += 1
            write("id: $sequence\ndata: ${ApiJson.encodeToString(job.toJobEventWire())}\n\n".toByteArray(StandardCharsets.UTF_8))
            flush()
            return sequence < Long.MAX_VALUE
        }

        if (!writeEvent(initial)) return@respondOutputStream
        if (initial.state.isTerminal()) return@respondOutputStream

        var previous = initial
        var idleDeadlineNanos = System.nanoTime() + idleDeadlineMillis * 1_000_000L
        while (true) {
            delay(JOB_EVENT_POLL_INTERVAL_MILLIS)
            val current = context.jobs.get(jobId) ?: return@respondOutputStream
            if (current != previous) {
                previous = current
                val canContinue = writeEvent(current)
                if (current.state.isTerminal() || !canContinue) return@respondOutputStream
                // Idle means "no event emitted for the bound": only an emit pushes the deadline out,
                // never the poll itself. A job that stays quiet past the bound closes the stream, and
                // the client's EventSource reconnects with Last-Event-ID to pick up from here.
                idleDeadlineNanos = System.nanoTime() + idleDeadlineMillis * 1_000_000L
            } else if (System.nanoTime() >= idleDeadlineNanos) {
                return@respondOutputStream
            }
        }
    }
}

/** How often the stream asks the store whether the job changed. */
private const val JOB_EVENT_POLL_INTERVAL_MILLIS = 300L

/**
 * How long the stream stays open without emitting anything, when the caller does not name a bound.
 *
 * 30 seconds is long enough not to churn on a busy job and short enough that a client without a job
 * does not sit on a live socket forever. Past the bound the stream closes; the jobs page is required
 * to reconnect with `Last-Event-ID` and to fall back to a refetch after a disconnect (Task 23 Step 3),
 * so the close costs a reconnect, never a stuck page.
 */
const val DEFAULT_JOB_EVENT_IDLE_DEADLINE_MILLIS = 30_000L

private const val LAST_EVENT_ID_HEADER = "Last-Event-ID"
