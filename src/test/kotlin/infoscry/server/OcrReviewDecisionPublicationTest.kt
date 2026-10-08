package infoscry.server

import infoscry.chunk.Chunker
import infoscry.chunk.WhitespaceTokenCounter
import infoscry.document.SwitchableEmbedder
import infoscry.jobs.FakePageEngine
import infoscry.jobs.RecordingReviewer
import infoscry.jobs.RescanHarness
import infoscry.ocr.readingTextHash
import infoscry.storage.PageApproval
import infoscry.storage.RevisionState
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Publishing a person's Keep existing and Edit text decisions.
 *
 * Use new publishes the candidate page the attempt already chunked and embedded. Keep existing and Edit text
 * replace the staged page with text the attempt never chunked, so publishing them has to chunk it with the
 * exact tokenizer and embed it first — and only after that may searchable text change. Every assertion is
 * about the routes and what the archive then serves: the publication, the search, the source and the passages
 * the new revision holds.
 */
class OcrReviewDecisionPublicationTest {

    private lateinit var dataDir: Path
    private lateinit var archive: Path
    private lateinit var harness: ApiTestServer
    private lateinit var picture: RescanHarness.Picture
    private lateinit var operationId: String
    private lateinit var candidateRevisionId: String
    private val embedder = SwitchableEmbedder()

    @BeforeTest
    fun startServer() {
        dataDir = Files.createTempDirectory("infoscry-ocr-decision-publication")
        RescanHarness(dataDir).use { seeded ->
            picture = seeded.importPicture(BASELINE)
            val attempt = seeded.rescan(
                picture,
                engine = FakePageEngine(readings = listOf(CANDIDATE)),
                reviewer = RecordingReviewer(),
                // A review profile with an approved external scope: the page is a pilot proposal a person decides.
                reviewRevisionId = seeded.reviewRevisionId,
                externalPageLimit = 1,
            )
            operationId = attempt.operation.operationId
            candidateRevisionId = assertNotNull(attempt.operation.candidateRevisionId)
            assertEquals(1, attempt.operation.pendingReviewCount, "the seeded page is a pending proposal")
            archive = seeded.archiveDir
        }
        harness = ApiTestServer(archive, rescanEmbedder = { true }, restoreEmbedder = embedder.provider)
        harness.context.attachChunker(Chunker(WhitespaceTokenCounter(prefixTokens = 2, specialTokens = 1)))
    }

    @AfterTest
    fun stopServer() {
        harness.close()
        dataDir.toFile().deleteRecursively()
    }

    private val prefix: String
        get() = "/api/collections/default/documents/${picture.documentId.value}/ocr"

    private fun candidateHash(): String {
        val page = harness.context.revisions.pages(candidateRevisionId).single()
        return page.textSha256 ?: readingTextHash(page.extractedText)
    }

    private suspend fun decide(choice: String, text: String? = null): HttpResponse {
        val textField = if (text == null) "" else ""","text":"$text""""
        return harness.request(
            HttpMethod.Post,
            "$prefix/review-decisions?operationId=$operationId",
            """{"requestId":"decide-${System.nanoTime()}","expectedRevisionId":"${picture.baselineRevisionId}",""" +
                """"decisions":[{"unitId":"${picture.unitId}","ordinal":0,"candidateHash":"${candidateHash()}",""" +
                """"choice":"$choice"$textField}]}""",
            Credential.CSRF,
        )
    }

    private suspend fun publish(): HttpResponse = harness.request(
        HttpMethod.Post,
        "$prefix/publish-decisions?operationId=$operationId",
        """{"expectedRevisionId":"${picture.baselineRevisionId}"}""",
        Credential.CSRF,
    )

    private suspend fun search(query: String): String =
        harness.get("/api/search?collection=Default&q=$query&mode=keyword").bodyAsText()

    private suspend fun source(): String =
        harness.get("/api/collections/default/sources/${picture.unitId}").bodyAsText()

    private fun activeRevision(): String = assertNotNull(harness.context.revisions.activeRevisionId(picture.documentId))

    @Test
    fun `keeping the existing text publishes it and search and the source still show the baseline`() = runBlocking {
        val decided = decide("KEEP")
        assertEquals(HttpStatusCode.OK, decided.status, decided.bodyAsText())

        val published = publish()

        val body = published.bodyAsText()
        assertEquals(HttpStatusCode.Accepted, published.status, body)
        assertEquals("PUBLISHED", Json.parseToJsonElement(body).jsonObject.getValue("phase").jsonPrimitive.content, body)
        assertEquals(candidateRevisionId, activeRevision(), "the decided candidate is the document's text now")
        assertContains(search("baseline"), "baseline reading")
        assertFalse(search("xylophone").contains("xylophone"), "the rejected proposal is not searchable")
        assertContains(source(), "baseline reading")
        assertFalse(source().contains("xylophone"))
        val chunks = harness.context.revisions.chunks(candidateRevisionId)
        assertTrue(chunks.isNotEmpty() && chunks.all { it.isStaged }, "every passage has its own vector")
    }

    @Test
    fun `an edited page publishes the person's text in passages that each fit the model window`() = runBlocking {
        val edited = (1..1_200).joinToString(" ") { "word$it" } + " zebraquartz"

        val decided = decide("EDIT", edited)
        assertEquals(HttpStatusCode.OK, decided.status, decided.bodyAsText())
        val published = publish()

        val body = published.bodyAsText()
        assertEquals(HttpStatusCode.Accepted, published.status, body)
        assertEquals("PUBLISHED", Json.parseToJsonElement(body).jsonObject.getValue("phase").jsonPrimitive.content, body)
        assertEquals(candidateRevisionId, activeRevision())
        assertContains(search("zebraquartz"), "zebraquartz", message = "the tail of the edit is searchable")
        assertContains(search("word1"), "word1")
        assertContains(source(), "zebraquartz")
        assertFalse(search("xylophone").contains("xylophone"), "the rejected proposal is not searchable")
        assertFalse(search("baseline").contains("baseline reading"), "the replaced reading is not searchable")
        val chunks = harness.context.revisions.chunks(candidateRevisionId)
        assertTrue(chunks.size > 1, "a long edit needs more than one passage, was ${chunks.size}")
        assertTrue(
            chunks.all { it.tokenCount <= Chunker.DEFAULT_MAX_SEQUENCE_TOKENS },
            "every passage fits the window including prefix and special tokens",
        )
        assertTrue(chunks.all { it.isStaged })
        assertEquals(
            edited.replace(Regex("\\s+"), " ").trim(),
            harness.context.revisions.pages(candidateRevisionId).single().extractedText.replace(Regex("\\s+"), " ").trim(),
            "nothing was truncated",
        )
        assertTrue(chunks.last().endOffset >= edited.length - 1, "the passages reach the end of the text")
    }

    @Test
    fun `a failed embedding refuses the publication, keeps the current text and keeps the decision`() = runBlocking {
        embedder.failure = IllegalStateException("provider said: secret detail")
        assertEquals(HttpStatusCode.OK, decide("EDIT", "edited tail zebraquartz").status)

        val refused = publish()

        val body = refused.bodyAsText()
        assertEquals(HttpStatusCode.Conflict, refused.status, body)
        assertContains(body, "REVIEW_EMBEDDING_FAILED")
        assertFalse(body.contains("secret detail"), "a provider's words are not repeated: $body")
        assertEquals(picture.baselineRevisionId, activeRevision(), "the current revision is still active")
        assertContains(search("baseline"), "baseline reading")
        assertFalse(search("zebraquartz").contains("zebraquartz"), "nothing of the edit became searchable")
        assertEquals(RevisionState.CANDIDATE, harness.context.revisions.revision(candidateRevisionId)?.state)
        assertEquals(
            PageApproval.APPROVED,
            harness.context.revisions.pages(candidateRevisionId).single().approval,
            "the person's decision is not lost",
        )

        // The decision is still there to publish once the accelerator answers again.
        embedder.failure = null
        val retried = publish()
        assertEquals(HttpStatusCode.Accepted, retried.status, retried.bodyAsText())
        assertEquals(candidateRevisionId, activeRevision())
        assertContains(search("zebraquartz"), "zebraquartz")
    }

    @Test
    fun `no embedder refuses the publication without substituting another way of embedding`() = runBlocking {
        embedder.available = false
        assertEquals(HttpStatusCode.OK, decide("KEEP").status)

        val refused = publish()

        val body = refused.bodyAsText()
        assertEquals(HttpStatusCode.Conflict, refused.status, body)
        assertContains(body, "REVIEW_EMBEDDING_UNAVAILABLE")
        assertEquals(picture.baselineRevisionId, activeRevision())
        assertContains(search("baseline"), "baseline reading")
        assertEquals(0, embedder.calls.get(), "nothing was embedded")
    }

    private companion object {
        const val BASELINE = "baseline reading"
        const val CANDIDATE = "candidate xylophone"
    }
}
