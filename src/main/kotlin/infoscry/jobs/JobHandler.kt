package infoscry.jobs

import infoscry.domain.CollectionLifecycle
import infoscry.domain.Job
import infoscry.domain.JobId
import infoscry.domain.JobType
import infoscry.domain.JobState
import infoscry.storage.CollectionNotActiveException
import infoscry.storage.CollectionStore
import infoscry.storage.JobStore
import infoscry.storage.MutationCoordinator
import kotlinx.coroutines.CancellationException

/**
 * What one kind of job does, one attempt at a time.
 *
 * The contract a handler has to keep, because the rest of the runner depends on it:
 *
 * - **Work in bounded stages.** Everything that writes goes through [JobStage.run], which holds the
 *   shared mutation permit for the duration of that one unit and releases it afterwards. One unit is
 *   one page, one document, one commit — never "the whole import". Collection deletion waits for the
 *   permit to be released, and it has no timeout, so a stage that holds one across an unbounded wait
 *   stops every other writer in the process.
 * - **Make slow waits outside a permit.** A platform call or a child process that can take minutes
 *   belongs on either side of a stage, not inside it.
 * - **Map your own timeouts to failures.** A `TimeoutCancellationException` that escapes is read as a
 *   cancellation, not as a failure, because the runner cannot tell it from one. A handler that times an
 *   operation out has to convert it to a typed failure with an actionable code.
 * - **Let cancellation through.** Swallowing a [CancellationException] makes a cancelled attempt look
 *   like a successful one.
 */
fun interface JobHandler {

    suspend fun handle(job: Job, stage: JobStage)
}

/**
 * Hands each job to the handler its type names.
 *
 * The runner claims one job at a time and takes one handler, so a second job type reaches the product
 * through this dispatch rather than through a second runner: two runners would be two claim loops over
 * the same queue, and the queue's ordering is what keeps a runner's permits sane.
 */
class DispatchingJobHandler(private val byType: Map<JobType, JobHandler>) : JobHandler {

    override suspend fun handle(job: Job, stage: JobStage) {
        val handler = byType[job.type]
            ?: throw IllegalArgumentException("no handler for job type ${job.type}")
        handler.handle(job, stage)
    }
}

/**
 * The attempt the runner cancelled, as the attempt itself sees it.
 *
 * It is a [CancellationException] on purpose: the work was not defective, it was interrupted, and every
 * cooperating suspend call in the attempt unwinds with it.
 */
class JobCancelledException(val jobId: JobId) :
    CancellationException("job ${jobId.value} was cancelled")

/**
 * The stage boundary of one running attempt: the only way a handler writes, and the point where it has
 * to notice that it should stop.
 *
 * A stage checks three things after it is admitted — the job's own cancellation request, the job still
 * running, and its collection still usable — because a deletion can start while an attempt is in the
 * middle of an import. The check happens *inside* the permit, so a tombstone that lands while the
 * attempt waits for the gate is seen before the attempt writes anything more.
 */
class JobStage internal constructor(
    private val jobId: JobId,
    private val store: JobStore,
    private val collections: CollectionStore,
    private val mutations: MutationCoordinator,
) {

    /**
     * Runs one bounded unit of mutation under a shared permit.
     *
     * A job stage waits for exclusive maintenance instead of being refused: maintenance lasts minutes
     * and the job resumes at its next durable checkpoint anyway, so refusing would fail work for
     * something the handler cannot control.
     */
    suspend fun <T> run(name: String, block: suspend () -> T): T = mutations.awaitMutation {
        assertAttemptMayContinue()
        store.progress(jobId, stage = name)
        block()
    }

    /**
     * Reports how far the attempt has come. The counters are the checkpoint a reopened job resumes from,
     * so a handler reports them after the work they describe is durable.
     */
    suspend fun reportProgress(completed: Int, total: Int) {
        mutations.awaitMutation {
            assertAttemptMayContinue()
            store.progress(jobId, completed = completed, total = total)
        }
    }

    /**
     * Reports progress from inside exclusive maintenance, and rechecks the attempt while there.
     *
     * The maintenance owner — the reindex job — cannot use [reportProgress], because waiting for
     * admission would mean waiting for itself. It is allowed to write its own progress without
     * admission, because it already holds the only permit, and this is also where it notices that
     * the job was cancelled: every document it reports is one more document it may stop after.
     */
    suspend fun reportWhileMaintaining(stage: String, completed: Int, total: Int) {
        assertAttemptMayContinue()
        store.progress(jobId, stage = stage, completed = completed, total = total)
    }

    private fun assertAttemptMayContinue() {
        val job = store.get(jobId)
            // The row is gone, which means its collection was deleted while the attempt was running.
            // There is nothing left to work on and nothing to report: the attempt is over.
            ?: throw JobCancelledException(jobId)
        if (job.cancelRequested || job.state != JobState.RUNNING) throw JobCancelledException(jobId)

        val collectionId = job.collectionId ?: return
        val collection = collections.get(collectionId)
            ?: throw CollectionNotActiveException(collectionId)
        if (collection.lifecycle != CollectionLifecycle.ACTIVE) {
            throw CollectionNotActiveException(collectionId)
        }
    }
}
