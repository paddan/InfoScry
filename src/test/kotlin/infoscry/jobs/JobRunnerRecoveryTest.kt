package infoscry.jobs

import infoscry.domain.JobId
import infoscry.domain.JobState
import infoscry.storage.CollectionStore
import infoscry.storage.Database
import infoscry.storage.JobStore
import infoscry.storage.MutationCoordinator
import infoscry.storage.SchemaMigrator
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/**
 * The Review Focus item for this task: a process killed between two durable stages must leave work that
 * the next process resumes without inventing a second job and without redoing a committed unit.
 *
 * "Killed" means killed: [JobHarness] is a real child JVM running the real [JobRunner], and it is
 * terminated forcibly at a named instant. The durable state it leaves behind — not a simulated one —
 * is what this test recovers from. Every unit the attempt executes appends a line to one log file, so
 * "executed once" is a countable fact rather than an inference from the checkpoint counters.
 */
class JobRunnerRecoveryTest {

    private lateinit var dataDir: Path
    private lateinit var database: Database
    private lateinit var jobs: JobStore

    @BeforeTest
    fun openDataDirectory() {
        dataDir = Files.createTempDirectory("infoscry-job-recovery")
        database = Database(dataDir.resolve("infoscry.db"))
        SchemaMigrator(database).migrate()
        jobs = JobStore(database)
    }

    @AfterTest
    fun closeDataDirectory() {
        database.close()
        dataDir.toFile().deleteRecursively()
    }

    @Test
    fun `a killed attempt resumes at its checkpoint without executing a committed unit twice`() {
        val log = dataDir.resolve("executed.log")
        val jobId = runHarnessToKill(units = 3, stopAfter = StopAfter.AFTER_FIRST_UNIT, log = log)

        assertEquals(listOf("unit 1"), executedUnits(log), "the first unit committed before the kill")
        assertEquals(
            JobState.RUNNING,
            jobs.get(jobId)!!.state,
            "a killed process leaves the attempt looking running, which is what recovery has to repair",
        )

        assertEquals(1, jobs.resetInterrupted())
        assertEquals(0, jobs.resetInterrupted(), "recovery is idempotent: a second sweep finds nothing to move")

        val requeued = jobs.get(jobId)!!
        assertEquals(JobState.QUEUED, requeued.state)
        assertEquals(1, requeued.completed, "the durable checkpoint is where the next attempt starts")
        assertEquals(3, requeued.total)
        assertNull(requeued.errorCode, "an interrupted attempt is not a failed one")

        finishInProcess(jobId, units = 3, log = log)

        assertEquals(listOf("unit 1", "unit 2", "unit 3"), executedUnits(log), "every unit ran exactly once")
        val completed = jobs.get(jobId)!!
        assertEquals(JobState.COMPLETE, completed.state)
        assertEquals(3, completed.completed)
        assertEquals(1, jobs.list(limit = 10).size, "recovery must not add a second job row")
    }

    @Test
    fun `two kills across one job leave one row and one execution per unit`() {
        val log = dataDir.resolve("executed.log")
        val jobId = runHarnessToKill(units = 3, stopAfter = StopAfter.AFTER_FIRST_UNIT, log = log)
        assertEquals(1, jobs.resetInterrupted())

        runHarnessToKill(units = 3, stopAfter = StopAfter.AFTER_SECOND_UNIT, log = log, jobId = jobId)

        assertEquals(listOf("unit 1", "unit 2"), executedUnits(log))
        assertEquals(1, jobs.list(limit = 10).size, "the second attempt must continue the job, not add one")
        assertEquals(1, jobs.resetInterrupted())

        finishInProcess(jobId, units = 3, log = log)

        assertEquals(listOf("unit 1", "unit 2", "unit 3"), executedUnits(log), "no unit ran twice")
        assertEquals(1, jobs.list(limit = 10).size)
        assertEquals(JobState.COMPLETE, jobs.get(jobId)!!.state)
        assertEquals(3, jobs.get(jobId)!!.completed)
    }

    @Test
    fun `a cancellation request recorded before a kill is honoured instead of re-run`() {
        val log = dataDir.resolve("executed.log")
        val jobId = runHarnessToKill(units = 3, stopAfter = StopAfter.CANCEL_REQUESTED, log = log)

        val interrupted = jobs.get(jobId)!!
        assertEquals(JobState.RUNNING, interrupted.state, "the worker had not observed the request yet")
        assertTrue(interrupted.cancelRequested, "the request is durable before the worker sees it")

        assertEquals(1, jobs.resetInterrupted())

        val ended = jobs.get(jobId)!!
        assertEquals(
            JobState.CANCELLED,
            ended.state,
            "a crash between the request and the observation must not turn cancelled work into running work",
        )
        assertTrue(ended.cancelRequested)
        assertEquals(1, ended.completed, "what the attempt committed stays committed")

        assertNull(jobs.claimNextQueued(), "a cancelled job is not work for the next process")
        assertEquals(listOf("unit 1"), executedUnits(log))
    }

    /** Runs the attempt to completion with the same unit loop, on this side of the process boundary. */
    private fun finishInProcess(jobId: JobId, units: Int, log: Path) {
        val runner = JobRunner(
            store = jobs,
            collections = CollectionStore(database),
            mutations = MutationCoordinator(),
            handler = JobHarness.unitHandler(log, units),
            pollIntervalMillis = POLL_MILLIS,
        )
        try {
            runner.start()
            awaitState(jobId, JobState.COMPLETE)
        } finally {
            runner.close()
        }
    }

    /**
     * Starts the harness as a real child process, waits for the instant it reports, and kills it there.
     * Returns the job the harness ran, so the test can go on recovering that exact job.
     */
    private fun runHarnessToKill(
        units: Int,
        stopAfter: StopAfter,
        log: Path,
        jobId: JobId? = null,
    ): JobId {
        val command = mutableListOf(
            javaExecutable(),
            // The SQLite driver loads a native library; without this the JDK warns on every run.
            "--enable-native-access=ALL-UNNAMED",
            "-cp",
            System.getProperty("java.class.path"),
            JobHarness::class.java.name,
            "--data-dir",
            dataDir.toString(),
            "--units",
            units.toString(),
            "--log",
            log.toString(),
            "--stop-after",
            stopAfter.name,
        )
        jobId?.let { command += listOf("--job-id", it.value) }

        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = Collections.synchronizedList(mutableListOf<String>())
        val pump = Thread {
            process.inputReader().useLines { lines -> lines.forEach { output += it } }
        }
        pump.isDaemon = true
        pump.start()

        awaitHarnessInstant(output, stopAfter, process)
        val reported = output.firstOrNull { it.startsWith(JOB_PREFIX) }?.removePrefix(JOB_PREFIX)?.trim()
        process.destroyForcibly()
        process.waitFor()

        val harnessJob = JobId(jobId?.value ?: reported ?: error("the harness never reported a job id: $output"))
        if (jobId != null) assertEquals(jobId, harnessJob, "the harness must continue the job it was given")
        return harnessJob
    }

    private fun awaitHarnessInstant(output: MutableList<String>, stopAfter: StopAfter, process: Process) {
        val wanted = "HARNESS READY ${stopAfter.name}"
        val deadline = System.nanoTime() + HARNESS_TIMEOUT_NANOS
        var reached = output.contains(wanted)
        while (!reached && System.nanoTime() < deadline && process.isAlive) {
            Thread.sleep(POLL_MILLIS)
            reached = output.contains(wanted)
        }
        if (!reached) {
            process.destroyForcibly()
            process.waitFor()
            throw AssertionError(
                "the harness never reached $stopAfter; output was:\n${output.joinToString("\n")}",
            )
        }
    }

    private fun awaitState(id: JobId, state: JobState) {
        val deadline = System.nanoTime() + HARNESS_TIMEOUT_NANOS
        while (System.nanoTime() < deadline) {
            val job = jobs.get(id)
            if (job?.state == state) return
            if (job != null && job.state != JobState.RUNNING && job.state != JobState.QUEUED) {
                error("job ${id.value} ended as ${job.state} instead of $state: $job")
            }
            runBlocking { delay(POLL_MILLIS) }
        }
        error("job ${id.value} never reached $state; it is ${jobs.get(id)}")
    }

    private fun executedUnits(log: Path): List<String> =
        if (Files.exists(log)) Files.readAllLines(log).filter { it.isNotBlank() } else emptyList()

    private fun javaExecutable(): String =
        Path.of(System.getProperty("java.home"), "bin", "java").toString()

    private companion object {
        const val POLL_MILLIS = 10L
        const val HARNESS_TIMEOUT_NANOS = 60_000_000_000L
        const val JOB_PREFIX = "HARNESS JOB "
    }
}

private typealias StopAfter = JobHarness.StopAfter
