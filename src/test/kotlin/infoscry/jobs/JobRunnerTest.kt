package infoscry.jobs

import infoscry.domain.CollectionId
import infoscry.domain.Job
import infoscry.domain.JobId
import infoscry.domain.JobState
import infoscry.domain.JobType
import infoscry.storage.CollectionStore
import infoscry.storage.Database
import infoscry.storage.JobStore
import infoscry.storage.MutationCoordinator
import infoscry.storage.SchemaMigrator
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * The worker: what it claims, how many attempts it runs at once, and what it records when an attempt
 * ends — including the two ends that are not success or failure, namely cancellation and shutdown.
 */
class JobRunnerTest {

    private lateinit var dataDir: Path
    private lateinit var database: Database
    private lateinit var jobs: JobStore
    private lateinit var collections: CollectionStore
    private lateinit var mutations: MutationCoordinator

    /** A value class cannot be `lateinit`, so the created collection sits behind a plain field. */
    private var createdCollectionId: CollectionId? = null

    private val collectionId: CollectionId
        get() = checkNotNull(createdCollectionId) { "the fixture collection has not been created" }

    @BeforeTest
    fun openDataDirectory() {
        dataDir = Files.createTempDirectory("infoscry-runner")
        database = Database(dataDir.resolve("infoscry.db"))
        SchemaMigrator(database).migrate()
        jobs = JobStore(database)
        collections = CollectionStore(database)
        mutations = MutationCoordinator()
        createdCollectionId = collections.create("Acme").id
    }

    @AfterTest
    fun closeDataDirectory() {
        database.close()
        dataDir.toFile().deleteRecursively()
    }

    @Test
    fun `a queued job is claimed, run and completed`() = runBlocking {
        val handled = CompletableDeferred<Job>()
        runner(testHandler { job, _ -> handled.complete(job) }).use { runner ->
            runner.start()
            val job = jobs.enqueue(JobType.IMPORT, collectionId, total = 2)

            assertEquals(job.id, handled.await().id)
            assertEquals(JobState.COMPLETE, awaitState(job.id, JobState.COMPLETE).state)
        }
    }

    @Test
    fun `one failing handler fails only its own job`() = runBlocking {
        val secondRan = CompletableDeferred<Unit>()
        runner(
            testHandler { job, _ ->
                if (job.payload == "fail") throw IllegalStateException("the extractor broke")
                secondRan.complete(Unit)
            },
        ).use { runner ->
            runner.start()
            val failing = jobs.enqueue(JobType.IMPORT, collectionId, payload = "fail")
            val working = jobs.enqueue(JobType.IMPORT, collectionId, payload = "work")

            secondRan.await()

            val failed = awaitState(failing.id, JobState.FAILED)
            assertEquals("INVALID_STATE", failed.errorCode)
            assertEquals("the extractor broke", failed.errorMessage)
            assertEquals(JobState.COMPLETE, awaitState(working.id, JobState.COMPLETE).state)
        }
    }

    @Test
    fun `no more than the configured number of attempts run at once`() = runBlocking {
        val inside = AtomicInteger()
        val peak = AtomicInteger()
        val twoInside = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        runner(
            testHandler { _, _ ->
                val now = inside.incrementAndGet()
                peak.updateAndGet { previous -> maxOf(previous, now) }
                if (now == 2) twoInside.complete(Unit)
                withTimeout(TIMEOUT_MILLIS) { release.await() }
                inside.decrementAndGet()
            },
            concurrency = JobConcurrency(jobs = 2, ocr = 1, embeddings = 1),
        ).use { runner ->
            runner.start()
            val queued = (1..3).map { jobs.enqueue(JobType.IMPORT, collectionId) }

            withTimeout(TIMEOUT_MILLIS) { twoInside.await() }
            assertEquals(2, peak.get(), "the third attempt has to wait for a permit")
            assertEquals(
                2,
                jobs.list(limit = 10).count { it.state == JobState.RUNNING },
                "a claim has to have a worker behind it, so no row may say RUNNING without an attempt",
            )
            assertEquals(
                1,
                jobs.list(limit = 10).count { it.state == JobState.QUEUED },
                "the third job stays queued until a permit is free instead of looking running",
            )
            release.complete(Unit)

            queued.forEach { awaitState(it.id, JobState.COMPLETE) }
            assertEquals(2, peak.get(), "the bound is a ceiling, not a target")
        }
    }

    @Test
    fun `a cancelled attempt stops at its next stage and keeps what it committed`() = runBlocking {
        val firstStageCommitted = CompletableDeferred<JobId>()
        val observedCancellation = AtomicReference<Throwable?>()
        runner(
            testHandler { job, stage ->
                stage.reportProgress(1, 3)
                firstStageCommitted.complete(job.id)
                try {
                    // The next stage is where the cancellation has to be observed: the runner cancels the
                    // attempt, and the durable flag is what the stage checks before doing more work.
                    stage.run("second unit") { error("the cancelled attempt must not run another unit") }
                    error("the cancelled attempt must not return normally")
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    observedCancellation.set(cancelled)
                    throw cancelled
                }
            },
        ).use { runner ->
            runner.start()
            val job = jobs.enqueue(JobType.IMPORT, collectionId, total = 3)
            firstStageCommitted.await()

            val requested = runner.cancel(job.id)
            assertEquals(JobState.RUNNING, requested.state)
            assertTrue(requested.cancelRequested)

            val ended = awaitState(job.id, JobState.CANCELLED)
            assertTrue(ended.cancelRequested)
            assertNotNull(observedCancellation.get(), "the attempt has to be interrupted, not abandoned")
            assertEquals(1, ended.completed, "the committed unit is what the next attempt will skip")
        }
    }

    @Test
    fun `a job cancelled before it is claimed never runs`() = runBlocking {
        val ran = AtomicInteger()
        runner(testHandler { _, _ -> ran.incrementAndGet() }).use { runner ->
            val job = jobs.enqueue(JobType.IMPORT, collectionId)

            runner.cancel(job.id)
            runner.start()
            delay(POLL_SETTLE_MILLIS)

            assertEquals(JobState.CANCELLED, jobs.get(job.id)!!.state)
            assertEquals(0, ran.get())
        }
    }

    @Test
    fun `a stage waits for exclusive maintenance instead of being refused`() = runBlocking {
        val maintenanceStarted = CompletableDeferred<Unit>()
        val releaseMaintenance = CompletableDeferred<Unit>()
        val handlerReachedStage = CompletableDeferred<Unit>()
        val stageEntered = CompletableDeferred<Unit>()

        val maintenance = launch {
            mutations.withExclusiveMaintenance("test maintenance") {
                maintenanceStarted.complete(Unit)
                releaseMaintenance.await()
            }
        }
        maintenanceStarted.await()

        runner(
            testHandler { _, stage ->
                handlerReachedStage.complete(Unit)
                stage.run("work") { stageEntered.complete(Unit) }
            },
        ).use { runner ->
            runner.start()
            val job = jobs.enqueue(JobType.IMPORT, collectionId)
            handlerReachedStage.await()

            delay(POLL_SETTLE_MILLIS)

            assertFalse(
                stageEntered.isCompleted,
                "a job stage waits for maintenance to drain; being refused with MAINTENANCE_IN_PROGRESS " +
                    "would leave the job failed for something it cannot control",
            )
            assertEquals(
                JobState.RUNNING,
                jobs.get(job.id)!!.state,
                "the attempt is still the running one while it waits",
            )

            releaseMaintenance.complete(Unit)
            assertEquals(JobState.COMPLETE, awaitState(job.id, JobState.COMPLETE).state)
        }
        maintenance.join()
    }

    @Test
    fun `a clean shutdown queues the attempt again with its checkpoint intact`() = runBlocking {
        val committed = CompletableDeferred<JobId>()
        val runner = runner(
            testHandler { job, stage ->
                stage.reportProgress(1, 3)
                committed.complete(job.id)
                // Stands in for a stage that is still inside a slow platform call when the process is
                // asked to stop: the attempt cannot finish, and it must not be reported as a failure.
                CompletableDeferred<Unit>().await()
            },
        )
        runner.start()
        val job = jobs.enqueue(JobType.IMPORT, collectionId, total = 3)
        committed.await()

        runner.close()

        val requeued = jobs.get(job.id)!!
        assertEquals(JobState.QUEUED, requeued.state)
        assertFalse(requeued.cancelRequested)
        assertEquals(1, requeued.completed, "the checkpoint is what stops the reopened job redoing work")
        assertNull(requeued.errorCode)
    }

    @Test
    fun `starting the runner twice is refused`() {
        runner(testHandler { _, _ -> }).use { runner ->
            runner.start()
            assertFailsWith<IllegalStateException> { runner.start() }
        }
    }

    @Test
    fun `a job whose record disappears while it runs is neither resurrected nor failed`() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val laterRan = CompletableDeferred<Unit>()
        runner(
            testHandler { job, _ ->
                if (job.payload == "removed") {
                    release.await()
                    return@testHandler
                }
                laterRan.complete(Unit)
            },
        ).use { runner ->
            runner.start()
            val removed = jobs.enqueue(JobType.IMPORT, collectionId, payload = "removed")
            awaitState(removed.id, JobState.RUNNING)

            // What a collection deletion leaves behind: the job row is cascaded away while a worker is
            // still inside the attempt. There is nothing left to record and nothing to retry.
            database.transaction { connection ->
                connection.prepareStatement("DELETE FROM jobs WHERE id = ?").use { statement ->
                    statement.setString(1, removed.id.value)
                    statement.executeUpdate()
                }
            }
            assertNull(jobs.get(removed.id))
            release.complete(Unit)

            val later = jobs.enqueue(JobType.IMPORT, collectionId, payload = "later")
            laterRan.await()

            assertEquals(JobState.COMPLETE, awaitState(later.id, JobState.COMPLETE).state)
            assertNull(jobs.get(removed.id), "a removed job is not resurrected by its attempt ending")
        }
    }

    @Test
    fun `the ocr and embedding limits are derived from the machine`() {
        assertEquals(2, JobConcurrency.defaults(processors = 8).jobs)
        assertEquals(1, JobConcurrency.defaults(processors = 1).ocr, "at least one OCR worker")
        assertEquals(2, JobConcurrency.defaults(processors = 4).ocr)
        assertEquals(4, JobConcurrency.defaults(processors = 32).ocr, "the ceiling is four OCR workers")
        assertEquals(1, JobConcurrency.defaults(processors = 32).embeddings, "one embedding session at a time")
        assertFailsWith<IllegalArgumentException> { JobConcurrency(jobs = 0, ocr = 1, embeddings = 1) }
    }

    @Test
    fun `a claim that fails does not end the worker`() = runBlocking {
        // A claim can fail on a real machine -- a busy database, a transient permission problem. What must
        // not happen is the worker quietly giving up: it stays alive with a full queue and no attempts, and
        // nothing in the queue says why nothing is moving.
        val flaky = FlakyClaimStore(database, failures = 3)
        val queued = flaky.enqueue(JobType.IMPORT, collectionId, total = 1)
        val runner = JobRunner(
            store = flaky,
            collections = collections,
            mutations = mutations,
            handler = testHandler { _, stage -> stage.run("unit") { } },
            concurrency = JobConcurrency(jobs = 1, ocr = 1, embeddings = 1),
            pollIntervalMillis = POLL_INTERVAL_MILLIS,
        )
        runner.start()
        try {
            assertEquals(JobState.COMPLETE, awaitState(queued.id, JobState.COMPLETE).state)
            assertTrue(flaky.claims.get() > 3, "the worker stopped claiming after a failure: ${flaky.claims.get()}")
        } finally {
            runner.close()
        }
    }

    /** A queue whose first [failures] claims fail, so the worker has to survive them. */
    private class FlakyClaimStore(database: Database, private val failures: Int) : JobStore(database) {

        val claims = AtomicInteger()

        override fun claimNextQueued(): Job? {
            val attempt = claims.incrementAndGet()
            if (attempt <= failures) throw IllegalStateException("the queue could not be read (attempt $attempt)")
            return super.claimNextQueued()
        }
    }

    private fun testHandler(block: suspend (Job, JobStage) -> Unit): JobHandler = JobHandler(block)

    private fun runner(
        handler: JobHandler,
        concurrency: JobConcurrency = JobConcurrency(jobs = 2, ocr = 1, embeddings = 1),
    ): JobRunner = JobRunner(
        store = jobs,
        collections = collections,
        mutations = mutations,
        handler = handler,
        concurrency = concurrency,
        pollIntervalMillis = POLL_INTERVAL_MILLIS,
    )

    private suspend fun awaitState(id: JobId, state: JobState): Job {
        val deadline = System.nanoTime() + TIMEOUT_MILLIS * 1_000_000
        while (System.nanoTime() < deadline) {
            val job = jobs.get(id)
            if (job?.state == state) return job
            if (job != null && job.state != JobState.RUNNING && job.state != JobState.QUEUED) {
                error("job ${id.value} ended as ${job.state} instead of $state: $job")
            }
            delay(AWAIT_POLL_MILLIS)
        }
        error("job ${id.value} never reached $state; it is ${jobs.get(id)}")
    }

    private companion object {
        const val POLL_INTERVAL_MILLIS = 5L
        const val AWAIT_POLL_MILLIS = 5L
        const val POLL_SETTLE_MILLIS = 250L
        const val TIMEOUT_MILLIS = 10_000L
    }
}
