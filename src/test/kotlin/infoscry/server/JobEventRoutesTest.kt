package infoscry.server

import infoscry.domain.Job
import infoscry.domain.JobId
import infoscry.domain.JobState
import infoscry.domain.JobType
import io.ktor.client.request.header
import io.ktor.client.request.get
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readUTF8Line
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.decodeFromString

/**
 * `GET /api/jobs/{id}/events` — the server-sent-events stream that lets the jobs page follow one job
 * without polling the list.
 *
 * Every read is bounded ([STREAM_TIMEOUT_MILLIS]) and stops as soon as the expected event arrives, so
 * a regression turns into a fast failure instead of a hung suite. A stream that should have ended is
 * awaited with `withTimeout`, never `withTimeoutOrNull` — the timeout throwing is the failure.
 */
class JobEventRoutesTest {

    private lateinit var dataDir: Path
    private lateinit var harness: ApiTestServer

    @BeforeTest
    fun startServer() {
        dataDir = Files.createTempDirectory("infoscry-job-event-routes")
        harness = ApiTestServer(dataDir)
    }

    @AfterTest
    fun stopServer() {
        harness.close()
        dataDir.toFile().deleteRecursively()
    }

    @Test
    fun `an unknown job id is a 404`() = runBlocking {
        val response = harness.get("/api/jobs/${JobId.new().value}/events")

        assertEquals(HttpStatusCode.NotFound, response.status, response.bodyAsText())
    }

    @Test
    fun `connecting to a queued job emits its current state first`() = runBlocking {
        val job = harness.context.jobs.enqueue(JobType.IMPORT, total = 3)

        withTimeout(STREAM_TIMEOUT_MILLIS) {
            harness.client.prepareGet(harness.url + eventsPath(job)).execute { response ->
                assertEquals(HttpStatusCode.OK, response.status)
                val event = assertNotNull(
                    readEvent(response.bodyAsChannel()),
                    "the stream must begin with the job's current state",
                )
                assertEquals(1L, event.id, "the first event of a fresh stream has id 1")
                val wire = decode(event.data)
                assertEquals(job.id.value, wire.jobId)
                assertEquals(JobState.QUEUED, wire.state)
                assertEquals(3, wire.total)
            }
        }
    }

    @Test
    fun `advancing a running job through the store produces a further event on the open stream`() = runBlocking {
        val job = harness.context.jobs.enqueue(JobType.IMPORT, total = 5)
        harness.context.jobs.claim(job.id)

        withTimeout(STREAM_TIMEOUT_MILLIS) {
            harness.client.prepareGet(harness.url + eventsPath(job)).execute { response ->
                val channel = response.bodyAsChannel()
                val first = assertNotNull(readEvent(channel), "the stream must emit the current state first")
                assertEquals(JobState.RUNNING, decode(first.data).state)
                assertEquals(0, decode(first.data).completed)

                harness.context.jobs.progress(job.id, stage = "ocr", completed = 2)

                val second = assertNotNull(readEvent(channel), "a store change must produce a further event")
                assertEquals(2L, second.id, "event ids must increase strictly on the same stream")
                val progress = decode(second.data)
                assertEquals(JobState.RUNNING, progress.state)
                assertEquals("ocr", progress.stage)
                assertEquals(2, progress.completed)
                assertEquals(5, progress.total)
            }
        }
    }

    @Test
    fun `a job that reaches a terminal state emits a final event and the stream then ends`() = runBlocking {
        val job = harness.context.jobs.enqueue(JobType.IMPORT, total = 2)
        harness.context.jobs.claim(job.id)

        withTimeout(STREAM_TIMEOUT_MILLIS) {
            harness.client.prepareGet(harness.url + eventsPath(job)).execute { response ->
                val channel = response.bodyAsChannel()
                assertNotNull(readEvent(channel), "the stream must emit the current state first")

                harness.context.jobs.complete(job.id)

                val final = assertNotNull(readEvent(channel), "a terminal transition must produce an event")
                assertEquals(JobState.COMPLETE, decode(final.data).state)

                val after = withTimeout(2_000) { readEvent(channel) }
                assertNull(after, "the stream must close once the terminal event has been sent")
            }
        }
    }

    @Test
    fun `a reconnect with Last-Event-ID resumes after the client's last event`() = runBlocking {
        val job = harness.context.jobs.enqueue(JobType.IMPORT)

        val firstId = withTimeout(STREAM_TIMEOUT_MILLIS) {
            harness.client.prepareGet(harness.url + eventsPath(job)).execute { response ->
                assertNotNull(readEvent(response.bodyAsChannel()), "the stream must emit the current state first").id
            }
        }
        assertEquals(1L, firstId)

        harness.context.jobs.claim(job.id)

        withTimeout(STREAM_TIMEOUT_MILLIS) {
            harness.client.prepareGet(harness.url + eventsPath(job)) {
                header("Last-Event-ID", firstId.toString())
            }.execute { response ->
                val resumed = assertNotNull(
                    readEvent(response.bodyAsChannel()),
                    "a reconnect must re-send the current state so the client's view is corrected",
                )
                assertEquals(2L, resumed.id, "the resumed stream must continue the sequence past the client's last id")
                assertEquals(JobState.RUNNING, decode(resumed.data).state, "the re-sent state must be the current one")
            }
        }
    }

    @Test
    fun `malformed negative and overflowing Last-Event-ID values are rejected`() = runBlocking {
        val job = harness.context.jobs.enqueue(JobType.IMPORT)

        listOf("not-a-number", "-1", "9223372036854775807", "9223372036854775808").forEach { lastEventId ->
            val response = harness.client.get(harness.url + eventsPath(job)) {
                header("Last-Event-ID", lastEventId)
            }
            assertEquals(HttpStatusCode.BadRequest, response.status, "Last-Event-ID='$lastEventId': ${response.bodyAsText()}")
        }
    }

    @Test
    fun `idle close reconnects with Last-Event-ID and a current state snapshot`() = runBlocking {
        harness.close()
        harness = ApiTestServer(dataDir, jobEventIdleDeadlineMillis = 450L)
        val job = harness.context.jobs.enqueue(JobType.IMPORT, total = 5)
        harness.context.jobs.claim(job.id)

        val firstId = withTimeout(STREAM_TIMEOUT_MILLIS) {
            harness.client.prepareGet(harness.url + eventsPath(job)).execute { response ->
                assertEquals(HttpStatusCode.OK, response.status)
                val event = assertNotNull(readEvent(response.bodyAsChannel()), "the stream must send its opening snapshot")
                assertEquals(1L, event.id)
                event.id
            }
        }
        val idleResponse = withTimeout(STREAM_TIMEOUT_MILLIS) {
            harness.client.prepareGet(harness.url + eventsPath(job)) {
                header("Last-Event-ID", firstId.toString())
            }.execute { response ->
                val channel = response.bodyAsChannel()
                val event = assertNotNull(readEvent(channel))
                assertEquals(firstId + 1, event.id)
                assertNull(readEvent(channel), "the idle deadline must close the stream")
                event.id
            }
        }
        assertEquals(2L, idleResponse)

        harness.context.jobs.progress(job.id, stage = "ocr", completed = 2)
        withTimeout(STREAM_TIMEOUT_MILLIS) {
            harness.client.prepareGet(harness.url + eventsPath(job)) {
                header("Last-Event-ID", idleResponse.toString())
            }.execute { response ->
                val resumed = assertNotNull(
                    readEvent(response.bodyAsChannel()),
                    "the reconnect must emit a fresh durable snapshot after the idle close",
                )
                assertEquals(3L, resumed.id)
                val wire = decode(resumed.data)
                assertEquals(JobState.RUNNING, wire.state)
                assertEquals("ocr", wire.stage)
                assertEquals(2, wire.completed)
            }
        }
    }

    @Test
    fun `the job events stream is a read and needs no credential`() = runBlocking {
        val job = harness.context.jobs.enqueue(JobType.IMPORT)

        // No bearer and no CSRF token: reads are loopback-only and need no credential (Security.kt).
        withTimeout(STREAM_TIMEOUT_MILLIS) {
            harness.client.prepareGet(harness.url + eventsPath(job)).execute { response ->
                assertEquals(HttpStatusCode.OK, response.status)
                val first = assertNotNull(readEvent(response.bodyAsChannel()), "the stream must open without a credential")
                assertEquals(1L, first.id, "the first event of a fresh stream has id 1")
            }
        }
    }

    @Test
    fun `the stream payload carries no paths and no redaction-sensitive fields`() = runBlocking {
        val payloadPath = "/var/infoscry/private/a.pdf"
        val failurePath = "/opt/tesseract/bin/tesseract"
        val job = harness.context.jobs.enqueue(JobType.IMPORT, payload = """{"paths":["$payloadPath"]}""")
        harness.context.jobs.claim(job.id)
        harness.context.jobs.fail(job.id, code = "NEEDS_TESSERACT", message = "Tesseract is not installed at $failurePath")

        withTimeout(STREAM_TIMEOUT_MILLIS) {
            harness.client.prepareGet(harness.url + eventsPath(job)).execute { response ->
                val channel = response.bodyAsChannel()
                val event = assertNotNull(readEvent(channel), "the stream must emit the current state first")
                val data = event.data
                val wire = decode(data)
                assertEquals(JobState.FAILED, wire.state)
                assertEquals("NEEDS_TESSERACT", wire.errorCode, "the error code crosses; it is meant to be actionable")
                assertFalse(data.contains(payloadPath), "the job's payload must never cross the stream")
                assertFalse(data.contains(failurePath), "the failure message must never cross the stream")
                assertFalse(data.contains(harness.dataDir.toString()), "the stream must never name this machine's data directory")

                // Nothing on the redaction-sensitive field list may appear as a payload field name.
                val sensitiveNames = listOf(
                    "payload",
                    "errorMessage",
                    "error_message",
                    "content",
                    "excerpt",
                    "document_text",
                    "question",
                    "prompt",
                    "answer",
                    "api_key",
                    "authorization",
                )
                sensitiveNames.forEach { name ->
                    assertFalse(data.contains("\"$name\""), "the payload must not carry a $name field")
                }

                val after = withTimeout(2_000) { readEvent(channel) }
                assertNull(after, "the stream must close once the terminal failure event has been sent")
            }
        }
    }

    private fun eventsPath(job: Job) = "/api/jobs/${job.id.value}/events"

    private fun decode(data: String): JobEventWire = ApiJson.decodeFromString<JobEventWire>(data)

    private suspend fun readEvent(channel: ByteReadChannel): SseEvent? {
        val idLine = channel.readUTF8Line() ?: return null
        require(idLine.startsWith("id: ")) { "expected an SSE id line, got '$idLine'" }
        val dataLine = channel.readUTF8Line() ?: return null
        require(dataLine.startsWith("data: ")) { "expected an SSE data line, got '$dataLine'" }
        channel.readUTF8Line() // the blank line that ends the event
        return SseEvent(id = idLine.removePrefix("id: ").toLong(), data = dataLine.removePrefix("data: "))
    }

    private data class SseEvent(val id: Long, val data: String)

    private companion object {
        /** Any single bounded read may take this long; a regression fails fast instead of hanging the suite. */
        const val STREAM_TIMEOUT_MILLIS = 10_000L
    }
}
