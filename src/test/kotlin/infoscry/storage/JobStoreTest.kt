package infoscry.storage

import infoscry.domain.CollectionId
import infoscry.domain.JobId
import infoscry.domain.JobState
import infoscry.domain.JobType
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The durable job record: what a worker may claim, what a stage may report, and what a restart does to
 * work that was interrupted.
 *
 * These are the guarantees the pipeline is built on, and they are all about what survives a crash, so
 * every assertion here is about persisted state rather than about a return value.
 */
class JobStoreTest {

    private lateinit var dataDir: Path
    private lateinit var database: Database
    private lateinit var jobs: JobStore

    /** A value class cannot be `lateinit`, so the created collection sits behind a plain field. */
    private var createdCollectionId: CollectionId? = null

    private val collectionId: CollectionId
        get() = checkNotNull(createdCollectionId) { "the fixture collection has not been created" }

    @BeforeTest
    fun openDataDirectory() {
        dataDir = Files.createTempDirectory("infoscry-jobs")
        database = Database(dataDir.resolve("infoscry.db"))
        SchemaMigrator(database).migrate()
        jobs = JobStore(database)
        createdCollectionId = CollectionStore(database).create("Acme").id
    }

    @AfterTest
    fun closeDataDirectory() {
        database.close()
        dataDir.toFile().deleteRecursively()
    }

    @Test
    fun `an enqueued job is durable and queued`() {
        val job = jobs.enqueue(JobType.IMPORT, collectionId, payload = """{"paths":["/tmp/a.pdf"]}""", total = 3)

        val stored = jobs.get(job.id)!!
        assertEquals(JobState.QUEUED, stored.state)
        assertEquals(JobType.IMPORT, stored.type)
        assertEquals(collectionId, stored.collectionId)
        assertEquals("""{"paths":["/tmp/a.pdf"]}""", stored.payload)
        assertEquals(3, stored.total)
        assertEquals(0, stored.completed)
        assertFalse(stored.cancelRequested)
        assertNull(jobs.get(JobId.new()), "an unknown id is absent, not an error")
    }

    @Test
    fun `a job for a collection that does not exist is refused`() {
        assertFailsWith<NoSuchElementException> {
            jobs.enqueue(JobType.IMPORT, CollectionId.new())
        }
    }

    @Test
    fun `claiming is a compare-and-set so two workers cannot take one job`() {
        val job = jobs.enqueue(JobType.IMPORT, collectionId)

        assertEquals(JobState.RUNNING, jobs.claim(job.id)?.state)
        assertNull(jobs.claim(job.id), "the state predicate refuses the second claim, not a read-then-write")
    }

    @Test
    fun `the worker takes the oldest queued job first and stops when none are queued`() {
        val first = jobs.enqueue(JobType.IMPORT, collectionId)
        val second = jobs.enqueue(JobType.IMPORT, collectionId)

        assertEquals(first.id, jobs.claimNextQueued()?.id)
        assertEquals(second.id, jobs.claimNextQueued()?.id)
        assertNull(jobs.claimNextQueued(), "an empty queue is not an error")
    }

    @Test
    fun `progress is only accepted from a running job`() {
        val job = jobs.enqueue(JobType.IMPORT, collectionId)

        val refused = assertFailsWith<JobStateException> { jobs.progress(job.id, completed = 1) }
        assertEquals(JobState.QUEUED, refused.state)
        assertTrue(refused.requirement.contains("RUNNING"))

        assertFailsWith<JobStateException> { jobs.complete(job.id) }
        assertFailsWith<JobStateException> { jobs.fail(job.id, "CODE", "message") }
        assertFailsWith<JobStateException> { jobs.finishCancelled(job.id) }
        assertFailsWith<JobStateException> { jobs.requeue(job.id) }
        assertFailsWith<NoSuchJobException> { jobs.progress(JobId.new(), completed = 1) }
    }

    @Test
    fun `a running job reports its stage and counters`() {
        val job = jobs.enqueue(JobType.IMPORT, collectionId, total = 5)
        jobs.claim(job.id)

        val updated = jobs.progress(job.id, stage = "ocr", completed = 2)

        assertEquals("ocr", updated.stage)
        assertEquals(2, updated.completed)
        assertEquals(5, updated.total)

        assertFailsWith<IllegalArgumentException> { jobs.progress(job.id, completed = 6) }
    }

    @Test
    fun `a terminal job accepts nothing more`() {
        val completed = jobs.enqueue(JobType.IMPORT, collectionId)
        jobs.claim(completed.id)
        assertEquals(JobState.COMPLETE, jobs.complete(completed.id).state)

        val failed = jobs.enqueue(JobType.IMPORT, collectionId)
        jobs.claim(failed.id)
        val failure = jobs.fail(failed.id, code = "NEEDS_TESSERACT", message = "Tesseract is not installed")
        assertEquals(JobState.FAILED, failure.state)
        assertEquals("NEEDS_TESSERACT", failure.errorCode)
        assertEquals("Tesseract is not installed", failure.errorMessage)

        assertFailsWith<JobStateException> { jobs.progress(completed.id, completed = 1) }
        assertFailsWith<JobStateException> { jobs.complete(failed.id) }
        assertEquals(0, jobs.resetInterrupted(), "a terminal job is never resurrected")
    }

    @Test
    fun `cancelling a queued job ends it, and cancelling a running job asks its worker to stop`() {
        val queued = jobs.enqueue(JobType.IMPORT, collectionId)
        val cancelled = jobs.cancel(queued.id)
        assertEquals(JobState.CANCELLED, cancelled.state)
        assertTrue(cancelled.cancelRequested)

        val running = jobs.enqueue(JobType.IMPORT, collectionId)
        jobs.claim(running.id)
        val requested = jobs.cancel(running.id)
        assertEquals(
            JobState.RUNNING,
            requested.state,
            "marking a running job cancelled here would lie about a stage a worker may still be inside",
        )
        assertTrue(requested.cancelRequested)

        assertEquals(JobState.CANCELLED, jobs.finishCancelled(running.id).state)
        assertFailsWith<NoSuchJobException> { jobs.cancel(JobId.new()) }
    }

    @Test
    fun `cancelling an already finished job changes nothing`() {
        val job = jobs.enqueue(JobType.IMPORT, collectionId)
        jobs.claim(job.id)
        jobs.complete(job.id)

        val cancelled = jobs.cancel(job.id)

        assertEquals(JobState.COMPLETE, cancelled.state)
        assertFalse(cancelled.cancelRequested)
    }

    @Test
    fun `interrupted work returns to the queue exactly once and keeps its checkpoint`() {
        val job = jobs.enqueue(JobType.IMPORT, collectionId, total = 5)
        jobs.claim(job.id)
        jobs.progress(job.id, stage = "ocr", completed = 2)

        assertEquals(1, jobs.resetInterrupted())

        val requeued = jobs.get(job.id)!!
        assertEquals(JobState.QUEUED, requeued.state)
        assertEquals(2, requeued.completed, "the durable checkpoint is what stops the work being redone")
        assertEquals(5, requeued.total)
        assertNull(requeued.stage, "the stage named the attempt that ended, not the one about to start")

        assertEquals(0, jobs.resetInterrupted(), "a second sweep must not re-examine cleaned work")
        assertEquals(JobState.QUEUED, jobs.get(job.id)!!.state)
    }

    @Test
    fun `a cancellation request survives a crash as a cancellation rather than a rerun`() {
        val job = jobs.enqueue(JobType.IMPORT, collectionId, total = 5)
        jobs.claim(job.id)
        jobs.progress(job.id, stage = "ocr", completed = 1)
        jobs.cancel(job.id)

        assertEquals(1, jobs.resetInterrupted())

        val afterRestart = jobs.get(job.id)!!
        assertEquals(JobState.CANCELLED, afterRestart.state)
        assertTrue(afterRestart.cancelRequested)
    }

    @Test
    fun `an attempt that failed leaves its error behind when it is queued again`() {
        val job = jobs.enqueue(JobType.IMPORT, collectionId, total = 2)
        jobs.claim(job.id)

        val requeued = jobs.requeue(job.id)

        assertEquals(JobState.QUEUED, requeued.state)
        assertNull(requeued.errorCode)
        assertNull(requeued.errorMessage)
        assertEquals(2, requeued.total, "the counters describe the work, not the attempt")
    }

    @Test
    fun `list pages the newest first and refuses a silently defaulted page size`() {
        repeat(3) { jobs.enqueue(JobType.IMPORT, collectionId) }

        val all = jobs.list(limit = 10)
        assertEquals(3, all.size)
        assertEquals(all.take(2), jobs.list(limit = 2), "a page must be a prefix of the stable order")
        assertFailsWith<IllegalArgumentException> { jobs.list(limit = 0) }
        assertFailsWith<IllegalArgumentException> { jobs.list(limit = 10, offset = -1) }
    }

    @Test
    fun `the legal transition table is the one the store enforces`() {
        assertTrue(JobStore.isLegalTransition(JobState.QUEUED, JobState.RUNNING))
        assertTrue(JobStore.isLegalTransition(JobState.QUEUED, JobState.CANCELLED))
        assertTrue(JobStore.isLegalTransition(JobState.RUNNING, JobState.COMPLETE))
        assertTrue(JobStore.isLegalTransition(JobState.RUNNING, JobState.FAILED))
        assertTrue(JobStore.isLegalTransition(JobState.RUNNING, JobState.CANCELLED))
        assertTrue(JobStore.isLegalTransition(JobState.RUNNING, JobState.QUEUED), "an interrupted attempt is queued again")

        assertFalse(JobStore.isLegalTransition(JobState.QUEUED, JobState.COMPLETE))
        assertFalse(JobStore.isLegalTransition(JobState.QUEUED, JobState.FAILED))
        assertFalse(JobStore.isLegalTransition(JobState.COMPLETE, JobState.RUNNING))
        assertFalse(JobStore.isLegalTransition(JobState.CANCELLED, JobState.QUEUED))
    }
}
