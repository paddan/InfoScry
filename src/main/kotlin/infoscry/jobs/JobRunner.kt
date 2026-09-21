package infoscry.jobs

import infoscry.domain.Job
import infoscry.domain.JobId
import infoscry.domain.JobState
import infoscry.storage.CollectionNotActiveException
import infoscry.storage.CollectionStore
import infoscry.storage.JobStore
import infoscry.storage.MaintenanceInProgressException
import infoscry.storage.MutationCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * How many attempts the process runs at once.
 *
 * The three limits exist because the stages have different bottlenecks: copying and parsing are
 * I/O-bound and two at a time keep a local disk and a SQLite writer busy without thrashing them, OCR
 * spends most of its time in a child process so it gets one worker per couple of cores up to four, and
 * embeddings share one accelerator session, where a second concurrent session competes for the same
 * device memory rather than finishing sooner.
 */
data class JobConcurrency(val jobs: Int, val ocr: Int, val embeddings: Int) {

    init {
        require(jobs >= 1) { "the job limit must be at least one, was $jobs" }
        require(ocr >= 1) { "the OCR limit must be at least one, was $ocr" }
        require(embeddings >= 1) { "the embedding limit must be at least one, was $embeddings" }
    }

    companion object {

        fun defaults(processors: Int = Runtime.getRuntime().availableProcessors()): JobConcurrency {
            require(processors >= 1) { "a machine has at least one processor, was $processors" }
            return JobConcurrency(
                jobs = JOBS,
                ocr = maxOf(1, minOf(OCR_CEILING, processors / 2)),
                embeddings = EMBEDDINGS,
            )
        }

        private const val JOBS = 2
        private const val OCR_CEILING = 4
        private const val EMBEDDINGS = 1
    }
}

/**
 * The worker: it claims queued jobs, runs one attempt each under a bounded number of permits, and
 * records how every attempt ended.
 *
 * The runner is a *supervisor*: an attempt that throws fails its own job and nothing else, so one
 * broken document cannot stop the rest of an import. It is also the only place that decides what an
 * interrupted attempt means, which is what makes the two ends that are not success or failure
 * distinguishable:
 *
 * - a **cancellation request** (the user, or a collection being deleted) ends the job as `CANCELLED`;
 * - a **shutdown** — the scope being cancelled with no request recorded — queues the job again, because
 *   the work is not defective and the next process should finish it.
 *
 * The durable flag decides which of the two it is, not the in-memory coroutine: a kill between setting
 * the flag and cancelling the child leaves a job that recovery reads correctly.
 */
class JobRunner(
    private val store: JobStore,
    private val collections: CollectionStore,
    private val mutations: MutationCoordinator,
    private val handler: JobHandler,
    private val concurrency: JobConcurrency = JobConcurrency.defaults(),
    private val pollIntervalMillis: Long = DEFAULT_POLL_MILLIS,
) : AutoCloseable {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val permits = Semaphore(concurrency.jobs)

    private val attempts = ConcurrentHashMap<JobId, kotlinx.coroutines.Job>()

    /** Guards [dispatcher] and [closing], so starting and closing cannot interleave. */
    private val lifecycle = Any()

    @Volatile
    private var closing = false

    private var dispatcher: kotlinx.coroutines.Job? = null

    /**
     * Begins claiming queued jobs. Calling it twice is a mistake, not a second worker.
     */
    fun start() {
        synchronized(lifecycle) {
            check(!closing) { "the job runner is closed" }
            check(dispatcher == null) { "the job runner is already started" }
            dispatcher = scope.launch { dispatch() }
        }
    }

    /**
     * Records the cancellation request and then interrupts the attempt that is running it.
     *
     * The order is the point: the flag is durable first, so a crash between the two still leaves a job
     * that recovery reads as cancelled rather than one that runs again. The flag is also what the
     * stage boundary checks, so an attempt the in-memory cancel happens to miss still stops at its next
     * stage.
     */
    suspend fun cancel(id: JobId): Job {
        val cancelled = store.cancel(id)
        attempts[id]?.cancel(JobCancelledException(id))
        return cancelled
    }

    /**
     * Stops the worker and hands unfinished attempts back to the queue.
     *
     * A clean shutdown is not a cancellation and not a failure: the attempt's checkpoint is kept and the
     * job is queued again, which is what makes restarting the process safe mid-import. The final sweep
     * catches attempts that were cancelled before they started, which have nothing to record themselves.
     *
     * It blocks until the attempts have finished their cleanup, so it belongs on a shutdown path — the
     * one place that can afford to wait for the queue to be handed back in a resumable state.
     */
    override fun close() {
        synchronized(lifecycle) { closing = true }
        scope.cancel()
        runBlocking {
            dispatcher?.join()
            attempts.values.toList().forEach { it.join() }
        }
        store.resetInterrupted()
    }

    private suspend fun dispatch() {
        var consecutiveFailures = 0
        while (currentCoroutineContext().isActive) {
            // The permit is reserved *before* the claim, so a claim always has a worker behind it. The row
            // says RUNNING and `infoscry jobs` and the web queue read that row: claiming first and waiting
            // for a permit afterwards would mark work running that no stage is executing.
            permits.acquire()
            val claimed = try {
                store.claimNextQueued()
            } catch (cancelled: CancellationException) {
                // Also covers a cancellation while suspended above, so the reserved permit is never lost.
                permits.release()
                throw cancelled
            } catch (failure: Throwable) {
                // A claim that fails is not a reason to stop claiming. Leaving the loop here used to end
                // the worker silently: the process stayed up, the queue stayed full, and nothing said why.
                // The permit is released first, and the loop backs off so a database that is genuinely down
                // is retried calmly instead of in a hot spin.
                permits.release()
                consecutiveFailures++
                LOGGER.atError()
                    .addKeyValue(CONSECUTIVE_FAILURES_FIELD, consecutiveFailures)
                    .setCause(failure)
                    .log("claiming the next job failed; the worker keeps trying")
                delay(backoffMillis(consecutiveFailures))
                continue
            }
            consecutiveFailures = 0
            if (claimed == null) {
                permits.release()
                delay(pollIntervalMillis)
                continue
            }
            launchAttempt(claimed)
        }
    }

    /** How long to wait after [failures] consecutive claim failures: short, then capped. */
    private fun backoffMillis(failures: Int): Long {
        val capped = minOf(failures, MAX_BACKOFF_STEPS)
        return pollIntervalMillis shl (capped - 1)
    }

    /**
     * Registers the attempt before it can run, so a cancellation that arrives a microsecond after the
     * claim still finds the coroutine to interrupt.
     *
     * The attempt owns the permit [dispatch] already reserved for it and releases it when the attempt
     * ends, which is what keeps the number of `RUNNING` rows equal to the number of executing attempts.
     */
    private fun launchAttempt(claimed: Job) {
        val attempt = scope.launch(start = CoroutineStart.LAZY) {
            try {
                runAttempt(claimed)
            } finally {
                permits.release()
            }
        }
        attempts[claimed.id] = attempt
        attempt.invokeOnCompletion { attempts.remove(claimed.id) }
        attempt.start()
    }

    private suspend fun runAttempt(claimed: Job) {
        val current = store.get(claimed.id)
            // The collection was deleted between the claim and the start; its cascade took the job row.
            ?: return
        if (current.cancelRequested) {
            endAttempt(claimed.id) { store.finishCancelled(it) }
            return
        }

        try {
            handler.handle(current, JobStage(claimed.id, store, collections, mutations))
            endAttempt(claimed.id) { store.complete(it) }
        } catch (cancelled: CancellationException) {
            if (store.get(claimed.id)?.cancelRequested == true) {
                endAttempt(claimed.id) { store.finishCancelled(it) }
                LOGGER.atInfo().addKeyValue(JOB_ID_FIELD, claimed.id.value)
                    .log("job attempt cancelled by request")
            } else {
                endAttempt(claimed.id) { store.requeue(it) }
                LOGGER.atInfo().addKeyValue(JOB_ID_FIELD, claimed.id.value)
                    .log("job attempt interrupted; queued again for the next process")
            }
            throw cancelled
        } catch (failure: Throwable) {
            val code = errorCodeFor(failure)
            endAttempt(claimed.id) { store.fail(it, code, errorMessageFor(failure)) }
            LOGGER.atError()
                .addKeyValue(JOB_ID_FIELD, claimed.id.value)
                .addKeyValue(ERROR_CODE_FIELD, code)
                .setCause(failure)
                .log("job attempt failed")
        }
    }

    /**
     * Applies a terminal state only while the attempt is still the running one.
     *
     * A missing row is not a failure to report: a collection deletion cascades its jobs away, and there
     * is nothing left to record. A job that already ended is left alone rather than being overwritten
     * or turned into a second failure.
     */
    private fun endAttempt(id: JobId, record: (JobId) -> Job) {
        val current = store.get(id) ?: return
        if (current.state != JobState.RUNNING) return
        record(id)
    }

    private fun errorCodeFor(failure: Throwable): String = when (failure) {
        is CollectionNotActiveException -> "COLLECTION_NOT_ACTIVE"
        is MaintenanceInProgressException -> "MAINTENANCE_IN_PROGRESS"
        is IllegalArgumentException -> "INVALID_REQUEST"
        is IllegalStateException -> "INVALID_STATE"
        else -> "JOB_FAILED"
    }

    private fun errorMessageFor(failure: Throwable): String =
        failure.message?.takeIf { it.isNotBlank() }
            ?: failure::class.simpleName
            ?: "the attempt failed without a message"

    private companion object {
        const val DEFAULT_POLL_MILLIS = 50L
        const val JOB_ID_FIELD = "job_id"
        const val ERROR_CODE_FIELD = "error_code"
        const val CONSECUTIVE_FAILURES_FIELD = "consecutive_claim_failures"
        const val MAX_BACKOFF_STEPS = 5
    }
}

private val LOGGER = LoggerFactory.getLogger("infoscry.jobs")
