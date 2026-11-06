package infoscry.cli

import infoscry.domain.JobId
import infoscry.domain.JobState
import infoscry.domain.JobType
import infoscry.server.ApiJson
import infoscry.server.JobResponse
import infoscry.server.JobsResponse
import infoscry.storage.JobStore
import io.ktor.client.statement.bodyAsText
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * `infoscry jobs` as a real process.
 *
 * The queue is what an operator looks at when an import seems stuck, so what matters is the exit code
 * and whether the output can be acted on: a job id, what state it is in, how far it came, and why it
 * stopped. Cancelling has to persist, because the CLI that asks for it may be gone before the attempt
 * notices.
 */
class JobsCommandTest {

    private lateinit var dataDir: Path
    private lateinit var server: CliTestServer

    @BeforeTest
    fun createTemporaryDataDirectory() {
        dataDir = Files.createTempDirectory("infoscry-jobs-cli")
        // No worker: these tests put jobs into states themselves and read them back.
        server = CliTestServer(dataDir, worker = false)
    }

    @AfterTest
    fun removeTemporaryDataDirectory() {
        server.close()
        dataDir.toFile().deleteRecursively()
    }

    @Test
    fun `an empty queue is reported as an empty list, not as an error`() {
        val result = server.run(*args("jobs", "--json"))

        assertEquals(0, result.exitCode, result.stderr)
        val listed = ApiJson.decodeFromString<JobsResponse>(result.stdout.trim())
        assertTrue(listed.jobs.isEmpty(), "a fresh data directory has no jobs: ${result.stdout}")
        assertEquals(1, result.stdout.trim().lines().size, "machine-readable output is one line")
    }

    @Test
    fun `a running job is reported with its stage and counters through the server`() {
        run {
            val job = server.context.jobs.enqueue(JobType.IMPORT, total = 5)
            server.context.jobs.claim(job.id)
            server.context.jobs.progress(job.id, stage = "ocr", completed = 2)

            val listed = server.run(*args("jobs", "--json"))
            assertEquals(0, listed.exitCode, listed.stderr)
            val reported = ApiJson.decodeFromString<JobsResponse>(listed.stdout.trim()).jobs.single()

            assertEquals(job.id.value, reported.id.value)
            assertEquals(JobState.RUNNING, reported.state)
            assertEquals("ocr", reported.stage)
            assertEquals(2, reported.completed)
            assertEquals(5, reported.total)

            val text = server.run(*args("jobs"))
            assertEquals(0, text.exitCode, text.stderr)
            assertContains(text.stdout, job.id.value)
            assertContains(text.stdout, "RUNNING")
            assertContains(text.stdout, "ocr")
            assertContains(text.stdout, "2/5")
        }
    }

    @Test
    fun `a job payload never leaves the server`() {
        val marker = "PRIVATE-SOURCE-PATH-MARKER"
        run {
            server.context.jobs.enqueue(JobType.IMPORT, payload = marker, total = 1)

            val body = runBlocking { server.api.get("/api/jobs").bodyAsText() }

            assertContains(body, "\"jobs\"", message = "the queue is still the answer: $body")
            assertFalse(
                body.contains(marker),
                "the payload holds the paths the user selected and is worker-internal: $body",
            )

            val listed = server.run(*args("jobs", "--json"))
            assertEquals(0, listed.exitCode, listed.stderr)
            assertFalse(listed.stdout.contains(marker), "the CLI's shape follows the wire: ${listed.stdout}")
        }
    }

    @Test
    fun `cancelling through the server records the request where the attempt will see it`() {
        run {
            val job = server.context.jobs.enqueue(JobType.IMPORT, total = 5)
            server.context.jobs.claim(job.id)
            server.context.jobs.progress(job.id, stage = "ocr", completed = 2)

            val cancelled = server.run(*args("jobs", "cancel", job.id.value))

            assertEquals(0, cancelled.exitCode, cancelled.stderr)
            assertEquals(1, server.context.jobs.list(limit = 10).size, "the same job is still the only one")
            val stored = server.context.jobs.get(job.id)!!
            assertTrue(stored.cancelRequested, "the request has to be durable in the server")
            assertEquals(
                JobState.RUNNING,
                stored.state,
                "the attempt is still running, so the honest state is RUNNING with a request recorded",
            )
            assertContains(cancelled.stdout, "stops at its next stage")
        }
    }

    @Test
    fun `a failed job explains itself in the listing`() {
        withJobs { jobs ->
            val job = jobs.enqueue(JobType.IMPORT)
            jobs.claim(job.id)
            jobs.fail(job.id, code = "NEEDS_TESSERACT", message = "Tesseract is not installed")
        }

        val listed = server.run(*args("jobs"))

        assertEquals(0, listed.exitCode, listed.stderr)
        assertContains(listed.stdout, "FAILED")
        assertContains(listed.stdout, "NEEDS_TESSERACT")
        assertFalse(listed.stdout.contains("Tesseract is not installed"), "the detail stays in the server log")
    }

    @Test
    fun `the server-attached CLI reports safe error codes and points to logs`() {
        run {
            val job = server.context.jobs.enqueue(JobType.IMPORT)
            server.context.jobs.claim(job.id)
            server.context.jobs.fail(job.id, "JOB_FAILED", "Tesseract is missing from /private/tools/tesseract")

            val listed = server.run(*args("jobs"))

            assertEquals(0, listed.exitCode, listed.stderr)
            assertContains(listed.stdout, "JOB_FAILED")
            assertContains(listed.stdout, "Details are omitted")
            assertFalse(listed.stdout.contains("/private/tools/tesseract"))
        }
    }

    @Test
    fun `cancelling a queued job persists before the command returns`() {
        val jobId = withJobs { jobs -> jobs.enqueue(JobType.IMPORT).id }

        val cancelled = server.run(*args("jobs", "cancel", jobId.value, "--json"))

        assertEquals(0, cancelled.exitCode, cancelled.stderr)
        val response = ApiJson.decodeFromString<JobResponse>(cancelled.stdout.trim())
        assertEquals(JobState.CANCELLED, response.job.state)

        withJobs { jobs ->
            val stored = jobs.get(jobId)!!
            assertEquals(JobState.CANCELLED, stored.state)
            assertTrue(stored.cancelRequested)
        }
    }

    @Test
    fun `cancelling a job that already ended says so without failing`() {
        val jobId = withJobs { jobs ->
            val job = jobs.enqueue(JobType.IMPORT)
            jobs.claim(job.id)
            jobs.complete(job.id)
            job.id
        }

        val result = server.run(*args("jobs", "cancel", jobId.value))

        assertEquals(0, result.exitCode, result.stderr)
        assertContains(result.stdout, "nothing left to cancel")
    }

    @Test
    fun `an unknown job id fails with an explanation instead of a stack trace`() {
        val result = server.run(*args("jobs", "cancel", JobId.new().value))

        assertNotEquals(0, result.exitCode)
        assertContains(result.stderr, "no job with id")
        assertTrue(
            !result.stderr.contains("Exception in thread"),
            "the operator gets the reason, not a stack trace: ${result.stderr}",
        )
    }

    @Test
    fun `a limit is honoured and a full page says so`() {
        withJobs { jobs ->
            repeat(3) { jobs.enqueue(JobType.IMPORT) }
        }

        val limited = server.run(*args("jobs", "--limit", "2", "--json"))

        assertEquals(0, limited.exitCode, limited.stderr)
        assertEquals(2, ApiJson.decodeFromString<JobsResponse>(limited.stdout.trim()).jobs.size)

        val text = server.run(*args("jobs", "--limit", "2"))
        assertContains(text.stderr, "pass --limit for more")

        val rejected = server.run(*args("jobs", "--limit", "0"))
        assertNotEquals(0, rejected.exitCode)
        assertContains(rejected.stderr, "--limit must be positive")
    }

    /** Runs [block] against the server's job store. */
    private fun <T> withJobs(block: (JobStore) -> T): T = block(server.context.jobs)

    private fun args(vararg extra: String): Array<String> = arrayOf(*extra)
}
