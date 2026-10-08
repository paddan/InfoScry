package infoscry.server

import infoscry.domain.CollectionId
import infoscry.jobs.FakePageEngine
import infoscry.jobs.RecordingReviewer
import infoscry.jobs.RescanHarness
import infoscry.storage.RevisionState
import io.ktor.client.call.body
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * What the review surface reads of a staged rescan, and what it is never allowed to read.
 *
 * The archive is seeded through the real rescan attempt before the server starts: one picture document whose
 * page was read again, differs from the published text and is therefore a pending proposal with a durable
 * image. Everything asserted is about the routes: which ids are resolved under which scope, which pages may be
 * read at all, which headers an image carries, and that the decided-but-unpublished signal is the archive's
 * own state rather than something a browser tab remembers.
 */
class OcrReviewRoutesTest {

    private lateinit var dataDir: Path
    private lateinit var archive: Path
    private lateinit var harness: ApiTestServer
    private lateinit var picture: RescanHarness.Picture
    private lateinit var neighbour: RescanHarness.Picture
    private lateinit var operationId: String
    private lateinit var candidateRevisionId: String

    @BeforeTest
    fun startServer() {
        dataDir = Files.createTempDirectory("infoscry-ocr-review-routes")
        stage(READING)
    }

    @AfterTest
    fun stopServer() {
        harness.close()
        dataDir.toFile().deleteRecursively()
    }

    /** (Re)builds the archive with one pending proposal whose candidate text is [reading]. */
    private fun stage(reading: String) {
        if (this::harness.isInitialized) harness.close()
        dataDir.toFile().deleteRecursively()
        dataDir = Files.createTempDirectory("infoscry-ocr-review-routes")
        RescanHarness(dataDir).use { seeded ->
            picture = seeded.importPicture("first reading")
            neighbour = seeded.importAnotherPicture("a neighbouring document")
            val attempt = seeded.rescan(
                picture,
                engine = FakePageEngine(readings = listOf(reading)),
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
        recordProposal()
        harness = ApiTestServer(archive, rescanEmbedder = { true })
    }

    /**
     * Writes the durable review the production comparison service writes for a page it proposes.
     *
     * The harness's reviewer is a stand-in that answers without persisting, so the proposal the review
     * surface reads is recorded here exactly as [infoscry.ocr.OcrComparisonService] would record it.
     */
    private fun recordProposal() {
        infoscry.AppContext.open(archive).use { context ->
            val page = context.revisions.pages(candidateRevisionId).single()
            val candidateHash = page.textSha256 ?: infoscry.ocr.readingTextHash(page.extractedText)
            val baselineHash = infoscry.ocr.readingTextHash("first reading")
            val operation = checkNotNull(context.ocrOperations.operation(operationId))
            val reviewerRevision = checkNotNull(operation.snapshot.reviewProfileRevisionId ?: "reviewer-revision-1")
            context.ocrReviews.record(
                infoscry.ocr.PageReview(
                    fingerprint = infoscry.ocr.OcrReviewFingerprint.of(
                        documentId = picture.documentId.value,
                        unitId = page.unitId.value,
                        ordinal = page.ordinal,
                        baselineRevisionId = picture.baselineRevisionId,
                        baselineTextHash = baselineHash,
                        candidateHash = candidateHash,
                        reviewProfileRevisionId = reviewerRevision,
                        reviewPromptVersion = 1,
                        policyVersion = 1,
                    ),
                    documentId = picture.documentId,
                    unitId = page.unitId.value,
                    ordinal = page.ordinal,
                    imageSha256 = checkNotNull(page.sourceImage).sha256,
                    baselineRevisionId = picture.baselineRevisionId,
                    baselineTextHash = baselineHash,
                    candidateHash = candidateHash,
                    recommendation = infoscry.ocr.ReviewerRecommendation.NEW_BETTER,
                    disposition = infoscry.ocr.PublicationDisposition.PROPOSE,
                    confidence = 0.5,
                    reasons = listOf(
                        infoscry.ocr.ReviewReason(
                            code = "ALIGNED_DIFFERENCE",
                            origin = infoscry.ocr.ReasonOrigin.REVIEWER,
                            baselineSpan = infoscry.ocr.TextSpan(0, 5),
                            candidateSpan = infoscry.ocr.TextSpan(0, 5),
                            explanation = "the image supports this reading",
                        ),
                    ),
                    reviewerRevisionId = reviewerRevision,
                    reviewerModelVersion = "fake-vision-1",
                    reviewPromptVersion = 1,
                    policyVersion = 1,
                    outcomeCode = null,
                    searchable = false,
                ),
            )
        }
    }

    private fun restartServer() {
        harness.close()
        harness = ApiTestServer(archive, rescanEmbedder = { true })
    }

    private fun prefix(documentId: String = picture.documentId.value, collection: String = "default"): String =
        "/api/collections/$collection/documents/$documentId/ocr"

    private val unitId: String get() = picture.unitId

    private suspend fun json(response: HttpResponse): JsonObject =
        Json.parseToJsonElement(response.bodyAsText()).jsonObject

    private suspend fun reviews(): JsonObject {
        val response = harness.get("${prefix()}/reviews?operationId=$operationId")
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return json(response)
    }

    private suspend fun operation(): JsonObject {
        val response = harness.get("${prefix()}/operations/$operationId")
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return json(response)
    }

    private suspend fun candidate(unit: String = unitId, op: String = operationId, path: String = prefix()) =
        harness.get("$path/reviews/$unit/candidate?operationId=$op")

    private suspend fun image(unit: String = unitId, op: String = operationId, path: String = prefix()) =
        harness.get("$path/reviews/$unit/image?operationId=$op")

    private suspend fun decideAll(): HttpResponse = harness.request(
        HttpMethod.Post,
        "${prefix()}/review-decisions?operationId=$operationId",
        """{"requestId":"decide-${System.nanoTime()}","expectedRevisionId":"${picture.baselineRevisionId}","documentWide":"USE_NEW"}""",
        Credential.CSRF,
    )

    private suspend fun publish(): HttpResponse = harness.request(
        HttpMethod.Post,
        "${prefix()}/publish-decisions?operationId=$operationId",
        """{"expectedRevisionId":"${picture.baselineRevisionId}"}""",
        Credential.CSRF,
    )

    private fun managedCopy(): Path {
        val directory = harness.context.paths.documentDir(CollectionId("default"), picture.documentId)
        return Files.list(directory).use { entries -> entries.filter { Files.isRegularFile(it) }.toList().single() }
    }

    // ---- gap 1: the page image and the candidate text ----

    @Test
    fun `the reviews list says whether a page has an image and carries no text`() = runBlocking {
        val body = reviews()

        val review = body.getValue("reviews").jsonArray.single().jsonObject
        assertEquals(true, review.getValue("imageAvailable").jsonPrimitive.boolean)
        assertFalse(body.toString().contains("second"), "the list never carries candidate text")
    }

    @Test
    fun `a pending page's candidate is served as bounded json with its hash and its baseline`() = runBlocking {
        val listed = reviews().getValue("reviews").jsonArray.single().jsonObject

        val response = candidate()

        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val body = json(response)
        assertEquals(unitId, body.getValue("unitId").jsonPrimitive.content)
        assertEquals(0, body.getValue("ordinal").jsonPrimitive.int)
        assertEquals(READING, body.getValue("text").jsonPrimitive.content, "markup is data, returned verbatim")
        assertEquals(
            listed.getValue("candidateHash").jsonPrimitive.content,
            body.getValue("candidateHash").jsonPrimitive.content,
            "the hash a decision is guarded by is the one the candidate was served under",
        )
        assertEquals(picture.baselineRevisionId, body.getValue("baselineRevisionId").jsonPrimitive.content)
        assertEquals(READING.length, body.getValue("totalChars").jsonPrimitive.int)
        assertEquals(false, body.getValue("truncated").jsonPrimitive.boolean)
        assertTrue(response.headers[HttpHeaders.ContentType].orEmpty().startsWith("application/json"))
        assertEquals("nosniff", response.headers["X-Content-Type-Options"])
        assertContains(response.headers[HttpHeaders.CacheControl].orEmpty(), "no-store")
    }

    @Test
    fun `a candidate longer than the bound is cut and says so`() = runBlocking {
        stage("y".repeat(300_000))

        val body = json(candidate())

        assertEquals(true, body.getValue("truncated").jsonPrimitive.boolean)
        assertEquals(300_000, body.getValue("totalChars").jsonPrimitive.int)
        assertTrue(body.getValue("text").jsonPrimitive.content.length <= 262_144)
        // The hash still names the whole text, so a decision is bound to what was staged, not to the excerpt.
        assertEquals(
            reviews().getValue("reviews").jsonArray.single().jsonObject.getValue("candidateHash").jsonPrimitive.content,
            body.getValue("candidateHash").jsonPrimitive.content,
        )
    }

    @Test
    fun `the page image is served with a fixed type and no way to sniff it or keep it`() = runBlocking {
        val response = image()

        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        assertEquals("image/png", response.headers[HttpHeaders.ContentType])
        assertEquals("nosniff", response.headers["X-Content-Type-Options"])
        assertContains(response.headers[HttpHeaders.CacheControl].orEmpty(), "no-store")
        assertEquals(null, response.headers[HttpHeaders.ContentDisposition], "no filename is derived from stored data")
        assertTrue(
            response.body<ByteArray>().contentEquals(Files.readAllBytes(picture.sourceFile)),
            "the bytes are the immutable image the comparison used",
        )
    }

    @Test
    fun `an image whose bytes are no longer the recorded ones is refused`() = runBlocking {
        val copy = managedCopy()
        copy.toFile().setWritable(true)
        Files.write(copy, byteArrayOf(1, 2, 3, 4))

        val response = image()

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertFalse(response.bodyAsText().contains(dataDir.toString()), "no path in an error")
    }

    @Test
    fun `an image that is not one of the allowed raster types is refused whatever its recorded hash`() = runBlocking {
        // Replace the managed copy with bytes that are not an image and re-record nothing: the hash differs, so
        // this also proves that a stored value is never reflected as a content type.
        val copy = managedCopy()
        copy.toFile().setWritable(true)
        Files.write(copy, "<html><script>alert(1)</script></html>".toByteArray())

        val response = image()

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertFalse(response.bodyAsText().contains("<script>"))
    }

    @Test
    fun `unknown ids and unknown pages are not found without saying anything about the archive`() = runBlocking {
        val responses = listOf(
            candidate(unit = "no-such-unit"),
            image(unit = "no-such-unit"),
            candidate(op = "no-such-operation"),
            image(op = "no-such-operation"),
            candidate(path = prefix(documentId = "no-such-document")),
            image(path = prefix(documentId = "no-such-document")),
        )

        responses.forEach { response ->
            val text = response.bodyAsText()
            assertEquals(HttpStatusCode.NotFound, response.status, text)
            assertFalse(text.contains(READING), "no candidate text: $text")
            assertFalse(text.contains(dataDir.toString()), "no path: $text")
            assertFalse(text.contains(candidateRevisionId), "no revision id: $text")
        }
    }

    @Test
    fun `another collection or another document cannot read this operation's pages`() = runBlocking {
        assertEquals(HttpStatusCode.Created, harness.createCollection("elsewhere").status)

        val responses = listOf(
            candidate(path = prefix(collection = "elsewhere")),
            image(path = prefix(collection = "elsewhere")),
            candidate(path = prefix(documentId = neighbour.documentId.value)),
            image(path = prefix(documentId = neighbour.documentId.value)),
            // The neighbour's own page id under this operation is not one of this operation's pages either.
            candidate(unit = neighbour.unitId),
            image(unit = neighbour.unitId),
        )

        responses.forEach { response ->
            val text = response.bodyAsText()
            assertEquals(HttpStatusCode.NotFound, response.status, text)
            assertFalse(text.contains(READING), text)
        }
    }

    @Test
    fun `the operation id is required and a page that was already decided is no longer served`() = runBlocking {
        val missing = harness.get("${prefix()}/reviews/$unitId/candidate")
        assertEquals(HttpStatusCode.BadRequest, missing.status, missing.bodyAsText())
        assertEquals(HttpStatusCode.OK, candidate().status)

        val decided = decideAll()
        assertEquals(HttpStatusCode.OK, decided.status, decided.bodyAsText())

        assertEquals(HttpStatusCode.NotFound, candidate().status)
        assertEquals(HttpStatusCode.NotFound, image().status)
        assertEquals(0, reviews().getValue("total").jsonPrimitive.int, "a decided page is not pending any more")
    }

    @Test
    fun `a published page is no longer served either`() = runBlocking {
        decideAll()
        val published = publish()
        assertEquals(HttpStatusCode.Accepted, published.status, published.bodyAsText())

        assertEquals(HttpStatusCode.NotFound, candidate().status)
        assertEquals(HttpStatusCode.NotFound, image().status)
    }

    @Test
    fun `a document that is being deleted is refused like the other review routes`() = runBlocking {
        val deletionId = java.util.UUID.randomUUID().toString()
        val now = infoscry.storage.Instants.now()
        harness.context.database.transaction { connection ->
            connection.prepareStatement(
                "INSERT INTO deletion_operations (id, collection_id, collection_name, trash_basename, " +
                    "managed_originals_existed, phase, kind, created_at, updated_at) VALUES (?, ?, ?, ?, 1, " +
                    "'PREPARED', 'DOCUMENT', ?, ?)",
            ).use { statement ->
                statement.setString(1, deletionId)
                statement.setString(2, "default")
                statement.setString(3, "seeded")
                statement.setString(4, ".deleted-${java.util.UUID.randomUUID()}")
                statement.setString(5, now)
                statement.setString(6, now)
                statement.executeUpdate()
            }
            connection.prepareStatement(
                "INSERT INTO document_deletion_targets (operation_id, document_id, managed_existed) VALUES (?, ?, 1)",
            ).use { statement ->
                statement.setString(1, deletionId)
                statement.setString(2, picture.documentId.value)
                statement.executeUpdate()
            }
        }

        val responses = listOf(candidate(), image())

        responses.forEach { response ->
            assertEquals(HttpStatusCode.Conflict, response.status, response.bodyAsText())
            assertContains(response.bodyAsText(), "DOCUMENT_BEING_DELETED")
        }
    }

    @Test
    fun `the new routes are reads only and take no path`() = runBlocking {
        val post = harness.request(
            HttpMethod.Post,
            "${prefix()}/reviews/$unitId/candidate?operationId=$operationId",
            "{}",
            Credential.CSRF,
        )
        val traversal = harness.get("${prefix()}/reviews/..%2F..%2Fsecret/image?operationId=$operationId")

        assertTrue(post.status == HttpStatusCode.NotFound || post.status == HttpStatusCode.MethodNotAllowed, "${post.status}")
        assertEquals(HttpStatusCode.NotFound, traversal.status)
    }

    @Test
    fun `a decision taken against stale text is still refused by its hash`() = runBlocking {
        val stale = harness.request(
            HttpMethod.Post,
            "${prefix()}/review-decisions?operationId=$operationId",
            """{"requestId":"stale-1","expectedRevisionId":"${picture.baselineRevisionId}","decisions":[{"unitId":"$unitId","ordinal":0,"candidateHash":"${"0".repeat(64)}","choice":"USE_NEW"}]}""",
            Credential.CSRF,
        )

        assertEquals(HttpStatusCode.Conflict, stale.status, stale.bodyAsText())
        assertContains(stale.bodyAsText(), "STALE_REVIEW_DECISION")
    }

    // ---- gap 2: decided but unpublished ----

    @Test
    fun `nothing is decided before a decision and the signal says so everywhere`() = runBlocking {
        val op = operation()
        assertEquals(1, op.getValue("pendingReviewCount").jsonPrimitive.int)
        assertEquals(0, op.getValue("decidedUnpublishedCount").jsonPrimitive.int)
        assertEquals(0, reviews().getValue("decidedUnpublishedCount").jsonPrimitive.int)
    }

    @Test
    fun `deciding every page leaves a publishable decision that survives a reload and ends with a publication`() =
        runBlocking {
            val decided = decideAll()
            assertEquals(HttpStatusCode.OK, decided.status, decided.bodyAsText())
            val answered = json(decided).getValue("operation").jsonObject
            assertEquals(0, answered.getValue("pendingReviewCount").jsonPrimitive.int)
            assertEquals(1, answered.getValue("decidedUnpublishedCount").jsonPrimitive.int)

            fun assertPublishable(op: JsonObject) {
                assertEquals(0, op.getValue("pendingReviewCount").jsonPrimitive.int)
                assertEquals(1, op.getValue("decidedUnpublishedCount").jsonPrimitive.int)
            }
            assertPublishable(operation())
            val listed = json(harness.get("${prefix()}/operations")).getValue("operations").jsonArray
            assertPublishable(listed.single { it.jsonObject.getValue("operationId").jsonPrimitive.content == operationId }.jsonObject)
            val review = reviews()
            assertEquals(0, review.getValue("pendingReviewCount").jsonPrimitive.int)
            assertEquals(1, review.getValue("decidedUnpublishedCount").jsonPrimitive.int)

            // A person who reloads the page — or a server that restarted — finds the same state.
            restartServer()
            assertPublishable(operation())
            assertEquals(1, reviews().getValue("decidedUnpublishedCount").jsonPrimitive.int)

            val published = publish()
            assertEquals(HttpStatusCode.Accepted, published.status, published.bodyAsText())
            assertEquals("PUBLISHED", json(published).getValue("phase").jsonPrimitive.content)

            val after = operation()
            assertEquals(0, after.getValue("pendingReviewCount").jsonPrimitive.int)
            assertEquals(0, after.getValue("decidedUnpublishedCount").jsonPrimitive.int, "published decisions are not owed")
            assertEquals(0, reviews().getValue("decidedUnpublishedCount").jsonPrimitive.int)
            assertEquals(
                RevisionState.PUBLISHED,
                harness.context.revisions.revision(candidateRevisionId)?.state,
            )
            restartServer()
            assertEquals(0, operation().getValue("decidedUnpublishedCount").jsonPrimitive.int)
        }

    @Test
    fun `publishing with nothing decided still refuses and leaves the published text alone`() = runBlocking {
        val published = publish()

        val body = published.bodyAsText()
        assertEquals(HttpStatusCode.Accepted, published.status, body)
        assertFalse(body.contains("\"phase\":\"PUBLISHED\""), body)
        assertContains(body, "REVISION_AWAITING_REVIEW")
        assertEquals(picture.baselineRevisionId, harness.context.revisions.activeRevisionId(picture.documentId))
        val op = operation()
        assertEquals(1, op.getValue("pendingReviewCount").jsonPrimitive.int)
        assertEquals(0, op.getValue("decidedUnpublishedCount").jsonPrimitive.int)
    }

    private companion object {
        /** Markup on purpose: OCR output is untrusted data, and the route must return it as JSON text. */
        const val READING = "<script>alert('second reading')</script>"
    }
}
