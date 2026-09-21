package infoscry.cli

import infoscry.domain.Job
import kotlinx.coroutines.delay

/**
 * Waits until one job ends, on either side of the wire.
 *
 * The local read is a database lookup and the remote one is a request, so the two are passed as one
 * lookup rather than duplicated: what has to be identical is that both wait for a terminal state and
 * neither gives up early. [what] names the job in the messages, so an import and a rebuild say what
 * each of them was waiting for.
 */
internal suspend fun awaitTerminalJob(lookup: suspend () -> Job, what: String): Job {
    val deadline = System.nanoTime() + WAIT_TIMEOUT_NANOS
    while (System.nanoTime() < deadline) {
        val job = try {
            lookup()
        } catch (missing: NoSuchElementException) {
            // The job row can disappear when its collection is deleted. That is a terminal outcome for
            // this command, and saying so beats waiting for a row that will never come back.
            throw CliFailure("the $what job no longer exists: ${missing.message.orEmpty()}")
        }
        if (job.state !in ACTIVE_STATES) return job
        delay(POLL_MILLIS)
    }
    throw CliFailure("the $what did not reach a final state within a day")
}

internal val ACTIVE_STATES: Set<infoscry.domain.JobState> =
    setOf(infoscry.domain.JobState.QUEUED, infoscry.domain.JobState.RUNNING)

internal val WAIT_TIMEOUT_NANOS: Long = 24L * 60 * 60 * 1_000_000_000

internal val POLL_MILLIS: Long = 200L
