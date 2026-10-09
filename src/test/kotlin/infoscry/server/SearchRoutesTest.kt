package infoscry.server

import infoscry.AppContext
import infoscry.document.PublicationStep
import infoscry.domain.Chunk
import infoscry.domain.ChunkId
import infoscry.domain.CollectionId
import infoscry.domain.ContentUnitId
import infoscry.domain.Document
import infoscry.domain.DocumentId
import infoscry.domain.DocumentStatus
import infoscry.domain.ExtractionMethod
import infoscry.domain.SourceLocation
import infoscry.extract.ContentUnitDraft
import infoscry.extract.ExtractionFingerprint
import infoscry.extract.ExtractionSettings
import infoscry.search.DocumentRow
import infoscry.search.vectorFor
import infoscry.storage.Instants
import infoscry.storage.PageApproval
import infoscry.storage.RevisionChunkDraft
import infoscry.storage.RevisionPageDraft
import io.ktor.client.statement.bodyAsText
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpHeaders.ContentRange
import io.ktor.http.HttpHeaders.ContentDisposition
import io.ktor.http.HttpHeaders.Range
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
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The search and citation boundary over a real socket: what each search answers, and which status each
 * refusal carries.
 *
 * The search endpoints are where the archive's core promise shows up — a hit is a citation that opens a
 * unit, and neither may leak more than the citation needs. Two classes of refusal exist on purpose: the
 * caller's own mistake is a 4xx, and an environment the caller cannot fix is a 503 with the remedy. The
 * tests below seed a deterministic index through the server's own context and drive the wire, because
 * the contract is the status and the body, not a handler called directly.
 */
class SearchRoutesTest {

    private lateinit var dataDir: Path
    private lateinit var harness: ApiTestServer

    /** The id of the Default collection this class creates: a new archive has no automatic one. */
    private var defaultCollectionId: CollectionId = CollectionId("unset")

    @BeforeTest
    fun startServer() {
        dataDir = Files.createTempDirectory("infoscry-search-routes")
        harness = ApiTestServer(dataDir)
        defaultCollectionId = runBlocking { harness.context.collectionService.create("Default").id }
    }

    @AfterTest
    fun stopServer() {
        harness.close()
        dataDir.toFile().deleteRecursively()
    }

    // ---- The collection boundary ----

    @Test
    fun `a search without a collection is refused as a bad request`() = runBlocking {
        val response = harness.get("/api/search?q=nightfall")

        assertEquals(HttpStatusCode.BadRequest, response.status, response.bodyAsText())
        assertContains(response.bodyAsText(), "collection is required")
    }

    @Test
    fun `invalid and reversed search date filters return actionable bad requests without echoing input`() = runBlocking {
        val invalid = harness.get("/api/search?collection=Default&q=nightfall&from=private-input")
        assertEquals(HttpStatusCode.BadRequest, invalid.status, invalid.bodyAsText())
        assertContains(invalid.bodyAsText(), "from must be an ISO date or ISO instant")
        assertFalse(invalid.bodyAsText().contains("private-input"))

        val reversed = harness.get("/api/search?collection=Default&q=nightfall&from=2026-03-02&until=2026-03-01")
        assertEquals(HttpStatusCode.BadRequest, reversed.status, reversed.bodyAsText())
        assertContains(reversed.bodyAsText(), "from must be on or before until")
    }

    @Test
    fun `a search against an unknown collection is refused as not found`() = runBlocking {
        val response = harness.get("/api/search?collection=nobody&q=nightfall")

        assertEquals(HttpStatusCode.NotFound, response.status, response.bodyAsText())
    }

    // ---- The response shape: hits and empty results ----

    @Test
    fun `a keyword search returns hits without ever exposing document text`() = runBlocking {
        // Three units in one document: the response must name the matching chunk's citation and snippet,
        // and must not carry the other two units' text — a hit is a citation, not a document.
        val (documentId, _) = seedUnit(document = "evidence.txt", text = "the nightfall report is sealed")
        seedUnit(document = "evidence.txt", text = "the alibi was never tested")
        seedUnit(document = "evidence.txt", text = "the ledger shows a pattern of transfers")

        val response = harness.get("/api/search?collection=Default&q=nightfall&mode=keyword")

        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val body = response.bodyAsText()
        assertContains(body, "nightfall")
        assertFalse(body.contains("the alibi was never tested"), "one hit carried another unit's text")
        assertFalse(body.contains("the ledger shows"), "one hit carried another unit's text")
        assertFalse(body.contains("\"extractedText\""), "the search response exposes extracted document text")
        assertContains(body, "\"locator\":")
        assertContains(body, "\"unitId\":")
        assertContains(body, "\"title\":\"evidence.txt\"")
        assertContains(body, documentId.value)
    }

    @Test
    fun `a keyword search with no matches is a stable empty result, not an error`() = runBlocking {
        val response = harness.get("/api/search?collection=Default&q=zzzzzz&mode=keyword")

        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        // The body is the same shape with an empty list: not null, not an error object.
        assertEquals("""{"hits":[],"staleFiltered":0}""", response.bodyAsText())
    }

    @Test
    fun `POST search reads the same contract as GET`() = runBlocking {
        val response = harness.request(
            HttpMethod.Post,
            "/api/search",
            body = """{"query":"nightfall","filters":{"collection":"Default","mode":"keyword"}}""",
            credential = Credential.CSRF,
        )

        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        assertContains(response.bodyAsText(), "\"hits\":[]")
    }

    @Test
    fun `a malformed POST search body is refused as a bad request`() = runBlocking {
        val response = harness.request(
            HttpMethod.Post,
            "/api/search",
            body = """{"query":42}""",
            credential = Credential.CSRF,
        )

        assertEquals(HttpStatusCode.BadRequest, response.status, response.bodyAsText())
        assertContains(response.bodyAsText(), "INVALID_REQUEST")
    }

    // ---- The two status classes ----

    @Test
    fun `hybrid search without a model is a service-unavailable carrying the install remedy`() = runBlocking {
        // The default mode is hybrid, which embeds the query; a temporary data directory has no model,
        // so the answer is the environment class: 503 with the remedy that fixes it. The query has a
        // lexical foothold, so it reaches the embedding half instead of being answered "nothing here
        // says this". The keyword half is a separate, caller-readable success, which the keyword tests
        // above pin.
        seedUnit(document = "evidence.txt", text = "the nightfall report is sealed")

        val response = harness.get("/api/search?collection=Default&q=nightfall")

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status, response.bodyAsText())
        val body = response.bodyAsText()
        assertContains(body, "MODEL_NOT_INSTALLED")
        assertContains(body, "embeddingModel")
    }

    @Test
    fun `a hybrid query with no lexical foothold is an empty result, not the model remedy`() = runBlocking {
        seedUnit(document = "evidence.txt", text = "the nightfall report is sealed")

        // "zzzzzzzz" shares no prefix with any indexed term, so the semantic half never runs and the
        // missing model never gets to refuse: the archive's true answer is that nothing says this.
        val response = harness.get("/api/search?collection=Default&q=zzzzzzzz")

        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        assertEquals("""{"hits":[],"staleFiltered":0}""", response.bodyAsText())
    }

    @Test
    fun `an over-long query is a bad request carrying the shortener`() = runBlocking {
        // The body carries the query, because a 1,200-word query in the query string would hit a
        // URI-length cap before the server could apply its own bound.
        val longQuery = (0 until 1_200).map { "word" }.joinToString(" ")
        val response = harness.request(
            HttpMethod.Post,
            "/api/search",
            body = """{"query":"$longQuery","filters":{"collection":"Default","mode":"keyword"}}""",
            credential = Credential.CSRF,
        )

        assertEquals(HttpStatusCode.BadRequest, response.status, response.bodyAsText())
        assertContains(response.bodyAsText(), "QUERY_TOO_LONG")
    }

    /**
     * A sealed revision snapshot is an environment the caller can retry, not a wrong answer.
     *
     * The seal is what the publication service raises when an authoritative publication could not be made
     * coherent in this process. Every read under it throws, and this route is where the exception becomes
     * the 503 the caller sees, so the mapping is asserted here with a gate given to the very context the
     * server runs on.
     */
    @Test
    fun `a sealed revision snapshot is answered as a retryable service unavailable`() = runBlocking {
        seedUnit(document = "sealed.txt", text = "the nightfall report is sealed")
        harness.context.revisionSnapshots.seal("an authoritative publication could not be finished")
        try {
            val response = harness.get("/api/search?collection=Default&q=nightfall&mode=keyword")

            assertEquals(HttpStatusCode.ServiceUnavailable, response.status, response.bodyAsText())
            assertContains(response.bodyAsText(), "REVISION_SNAPSHOT_UNAVAILABLE")
        } finally {
            harness.context.revisionSnapshots.unseal()
        }
    }

    // ---- The publication seal over the whole read boundary ----

    /**
     * Ticket 02e, scenarios 1–3 on the wire: a publication failed at its authority commit and again at
     * the roll-forward's staged commit refuses both halves of a read — search *and* the live source —
     * instead of letting the old index answer beside the new text, and completing the recovery reopens
     * both on the authoritative revision.
     *
     * The failure is injected through the service's own step hook with a deterministic counter: the first
     * STAGED_COMMITTED belongs to the publication, the second to the completion that runs because its
     * authority already moved. While that completion cannot finish, SQLite serves the replacement and the
     * index still holds the replaced reading, so any 200 here would be the mixture the seal exists for.
     */
    @Test
    fun `while a publication cannot switch, search and the live source refuse instead of mixing readings`() =
        runBlocking {
            val (documentId, unitId) = seedUnit(document = "sealed-source.txt", text = "the original wording")
            val context = harness.context
            val base = assertNotNull(context.revisions.recordPublishedContent(documentId, "TEST_SEED"))
            val candidate = context.revisions.openCandidate(documentId, base, "TEST_REPLACEMENT")
            context.revisions.appendPage(
                candidate,
                RevisionPageDraft(
                    ordinal = 0,
                    unitId = unitId,
                    locator = SourceLocation.TextLines(1, 1),
                    extractedText = "the replacement wording",
                    searchText = "the replacement wording",
                    extractionMethod = ExtractionMethod.OCR,
                    approval = PageApproval.APPROVED,
                    chunks = listOf(
                        RevisionChunkDraft(
                            ordinal = 0,
                            text = "the replacement wording",
                            startOffset = 0,
                            endOffset = "the replacement wording".length,
                            tokenCount = 5,
                            tokenStart = 0,
                            tokenEnd = 4,
                            embedding = vectorFor("the replacement wording"),
                        ),
                    ),
                ),
            )

            val observations = mutableListOf<PublicationStep>()
            val operation = context.revisionPublication.publish(documentId, base, candidate) { step ->
                observations += step
                val count = observations.count { it == step }
                if (step == PublicationStep.AUTHORITATIVE && count == 1) {
                    throw IllegalStateException("injected failure at the authority commit")
                }
                if (step == PublicationStep.STAGED_COMMITTED && count == 2) {
                    throw IllegalStateException("injected failure at the roll-forward's staged commit")
                }
            }
            assertEquals(1, observations.count { it == PublicationStep.AUTHORITATIVE }, observations.toString())
            assertEquals(2, observations.count { it == PublicationStep.STAGED_COMMITTED }, observations.toString())

            val collectionId = harness.collectionIdOf("Default")

            // Search refuses rather than answering from the index rows of the replaced reading…
            val search = harness.get("/api/search?collection=Default&q=replacement&mode=keyword")
            assertEquals(HttpStatusCode.ServiceUnavailable, search.status, search.bodyAsText())
            assertContains(search.bodyAsText(), "REVISION_SNAPSHOT_UNAVAILABLE")

            // …and the live source read refuses rather than answering from SQLite text the database has
            // already moved — the pair "old index with new text" the ticket exists to forbid.
            val live = harness.get("/api/collections/$collectionId/sources/${unitId.value}")
            assertEquals(HttpStatusCode.ServiceUnavailable, live.status, live.bodyAsText())
            assertContains(live.bodyAsText(), "REVISION_SNAPSHOT_UNAVAILABLE")
            assertFalse(
                live.bodyAsText().contains("the replacement wording"),
                "the source route served the text the switch has not published: ${live.bodyAsText()}",
            )

            // A request that already names a revision finishes against that revision's immutable text —
            // the spec's rule for saved evidence, which is coherent whatever the index is doing.
            val pinned = harness.get("/api/collections/$collectionId/sources/${unitId.value}?revision=$base")
            assertEquals(HttpStatusCode.OK, pinned.status, pinned.bodyAsText())
            assertContains(pinned.bodyAsText(), "the original wording")

            // Scenario 3: completing the recovery reopens reads on the authoritative revision.
            val recovery = context.revisionPublication.recoverUnfinished()
            assertEquals(listOf(operation), recovery.completed)

            val served = harness.get("/api/search?collection=Default&q=replacement&mode=keyword")
            assertEquals(HttpStatusCode.OK, served.status, served.bodyAsText())
            assertContains(served.bodyAsText(), "the replacement wording")
            assertContains(served.bodyAsText(), "\"revisionId\":\"$candidate\"")
            val original = harness.get("/api/search?collection=Default&q=original&mode=keyword")
            assertEquals(HttpStatusCode.OK, original.status, original.bodyAsText())
            assertEquals("""{"hits":[],"staleFiltered":0}""", original.bodyAsText())

            val liveAfter = harness.get("/api/collections/$collectionId/sources/${unitId.value}")
            assertEquals(HttpStatusCode.OK, liveAfter.status, liveAfter.bodyAsText())
            assertContains(liveAfter.bodyAsText(), "the replacement wording")
            assertContains(liveAfter.bodyAsText(), "\"qualityScore\":", message = "a source page carries its text-quality score")
            val pinnedAfter = harness.get("/api/collections/$collectionId/sources/${unitId.value}?revision=$base")
            assertEquals(HttpStatusCode.OK, pinnedAfter.status, pinnedAfter.bodyAsText())
            assertContains(pinnedAfter.bodyAsText(), "the original wording")
        }

    // ---- Content units, and why a deleted collection's unit is not served ----

    @Test
    fun `a content unit from a live collection is served and one a collection no longer has is not found`() =
        runBlocking {
            val (_, unitId) = seedUnit(document = "notes.txt", text = "notes about the harbour")
            val collectionId = harness.collectionIdOf("Default")
            val response = harness.get("/api/collections/$collectionId/sources/${unitId.value}")

            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            assertContains(response.bodyAsText(), "notes about the harbour")

            // The deletion is admitted, then followed: its phases run after the response, and the
            // assertion below is about the state the finished deletion leaves behind.
            val defaultCollectionId = harness.collectionIdOf("Default")
            val admitted = harness.admitCollectionDeletion(defaultCollectionId, "Default")
            harness.awaitDeletion(admitted.operationId)

            val afterDelete = harness.get("/api/collections/$collectionId/sources/${unitId.value}")
            assertEquals(HttpStatusCode.NotFound, afterDelete.status, afterDelete.bodyAsText())
        }

    @Test
    fun `a live source read names the neighbouring pages and its place in the document`() = runBlocking {
        val (documentId, first) = seedUnit(document = "scan.pdf", text = "page one text")
        val context = harness.context
        fun commit(ordinal: Int, text: String) = context.content.commitExtractedUnit(
            documentId = documentId,
            fingerprint = ExtractionFingerprint.of(
                "sha-scan.pdf",
                ExtractionSettings(ocrLanguages = "eng", extractorSchemaVersion = "search-routes-test"),
            ),
            key = "unit-$ordinal",
            ordinal = ordinal,
            draft = ContentUnitDraft(
                locator = SourceLocation.TextLines(1, 1),
                extractedText = text,
                searchText = text,
                method = ExtractionMethod.DIRECT_TEXT,
            ),
            artifactRoot = context.paths.libraryDir,
        ).unit.id
        val second = commit(1, "page two text")
        val third = commit(2, "page three text")
        val collectionId = harness.collectionIdOf("Default")

        suspend fun read(id: ContentUnitId) =
            Json.parseToJsonElement(harness.get("/api/collections/$collectionId/sources/${id.value}").bodyAsText()).jsonObject

        val one = read(first)
        assertEquals(null, one["previousId"])
        assertEquals(second.value, one["nextId"]?.jsonPrimitive?.content)
        assertEquals(1, one["position"]?.jsonPrimitive?.int)
        assertEquals(3, one["unitCount"]?.jsonPrimitive?.int)

        val two = read(second)
        assertEquals(first.value, two["previousId"]?.jsonPrimitive?.content)
        assertEquals(third.value, two["nextId"]?.jsonPrimitive?.content)
        assertEquals(2, two["position"]?.jsonPrimitive?.int)

        val three = read(third)
        assertEquals(second.value, three["previousId"]?.jsonPrimitive?.content)
        assertEquals(null, three["nextId"])
        assertEquals(3, three["position"]?.jsonPrimitive?.int)
    }

    @Test
    fun `a source read can name the revision an excerpt came from`() = runBlocking {
        val (documentId, unitId) = seedUnit(document = "replaced.txt", text = "the original wording")
        val context = harness.context
        val base = assertNotNull(context.revisions.recordPublishedContent(documentId, "TEST_SEED"))
        val candidate = context.revisions.openCandidate(documentId, base, "TEST_REPLACEMENT")
        context.revisions.appendPage(
            candidate,
            RevisionPageDraft(
                ordinal = 0,
                unitId = unitId,
                locator = SourceLocation.TextLines(1, 1),
                extractedText = "the replacement wording",
                searchText = "the replacement wording",
                extractionMethod = ExtractionMethod.OCR,
                approval = PageApproval.APPROVED,
                chunks = listOf(
                    RevisionChunkDraft(
                        ordinal = 0,
                        text = "the replacement wording",
                        startOffset = 0,
                        endOffset = "the replacement wording".length,
                        tokenCount = 5,
                        tokenStart = 0,
                        tokenEnd = 4,
                        embedding = FloatArray(768) { 0.01f },
                    ),
                ),
            ),
        )
        context.revisionPublication.publish(documentId, base, candidate)

        val collectionId = harness.collectionIdOf("Default")
        val live = harness.get("/api/collections/$collectionId/sources/${unitId.value}")
        assertContains(live.bodyAsText(), "the replacement wording")

        // A saved excerpt names the revision it was taken from, so it keeps showing its own text.
        val fromRevision = harness.get(
            "/api/collections/$collectionId/sources/${unitId.value}?revision=$base",
        )
        assertEquals(HttpStatusCode.OK, fromRevision.status, fromRevision.bodyAsText())
        assertContains(fromRevision.bodyAsText(), "the original wording")

        val unknown = harness.get(
            "/api/collections/$collectionId/sources/${unitId.value}?revision=does-not-exist",
        )
        assertEquals(HttpStatusCode.NotFound, unknown.status, unknown.bodyAsText())
    }

    @Test
    fun `a search hit names the revision its text came from and a legacy hit names none`() = runBlocking {
        val (documentId, _) = seedUnit(document = "evidence.txt", text = "the original wording")
        val context = harness.context

        // The seeded rows are what the import path writes: rows with no revision tag, which belong to no
        // revision. They are reported as such rather than resolved to whatever the document publishes now,
        // because that would attribute text to a revision that never held it.
        val legacy = harness.get("/api/search?collection=Default&q=original&mode=keyword")
        assertEquals(HttpStatusCode.OK, legacy.status, legacy.bodyAsText())
        assertContains(legacy.bodyAsText(), "the original wording")
        assertFalse(legacy.bodyAsText().contains("revisionId"), legacy.bodyAsText())

        val base = assertNotNull(context.revisions.recordPublishedContent(documentId, "TEST_SEED"))
        val candidate = context.revisions.openCandidate(documentId, base, "TEST_REPLACEMENT")
        context.revisions.appendPage(
            candidate,
            RevisionPageDraft(
                ordinal = 0,
                unitId = ContentUnitId("unit-of-${documentId.value}"),
                locator = SourceLocation.TextLines(1, 1),
                extractedText = "the replacement wording",
                searchText = "the replacement wording",
                extractionMethod = ExtractionMethod.OCR,
                approval = PageApproval.APPROVED,
                chunks = listOf(
                    RevisionChunkDraft(
                        ordinal = 0,
                        text = "the replacement wording",
                        startOffset = 0,
                        endOffset = "the replacement wording".length,
                        tokenCount = 5,
                        tokenStart = 0,
                        tokenEnd = 4,
                        embedding = vectorFor("the replacement wording"),
                    ),
                ),
            ),
        )
        context.revisionPublication.publish(documentId, base, candidate)

        val published = harness.get("/api/search?collection=Default&q=replacement&mode=keyword")
        assertEquals(HttpStatusCode.OK, published.status, published.bodyAsText())
        assertContains(published.bodyAsText(), "the replacement wording")
        assertContains(published.bodyAsText(), "\"revisionId\":\"$candidate\"")
    }

    @Test
    fun `a saved excerpt is served from its own revision after a replacement removed its unit`() = runBlocking {
        val (documentId, unitId) = seedUnit(document = "replaced.txt", text = "the original wording")
        val context = harness.context
        val base = assertNotNull(context.revisions.recordPublishedContent(documentId, "TEST_SEED"))
        // The replacement names a page of its own rather than the one it replaces, which is what removes
        // the unit the citation holds from the document's published reading.
        val replacementUnitId = ContentUnitId.new()
        val candidate = context.revisions.openCandidate(documentId, base, "TEST_REPLACEMENT")
        context.revisions.appendPage(
            candidate,
            RevisionPageDraft(
                ordinal = 0,
                unitId = replacementUnitId,
                locator = SourceLocation.TextLines(1, 1),
                extractedText = "the replacement wording",
                searchText = "the replacement wording",
                extractionMethod = ExtractionMethod.OCR,
                approval = PageApproval.APPROVED,
                chunks = listOf(
                    RevisionChunkDraft(
                        ordinal = 0,
                        text = "the replacement wording",
                        startOffset = 0,
                        endOffset = "the replacement wording".length,
                        tokenCount = 5,
                        tokenStart = 0,
                        tokenEnd = 4,
                        embedding = vectorFor("the replacement wording"),
                    ),
                ),
            ),
        )
        context.revisionPublication.publish(documentId, base, candidate)

        val collectionId = harness.collectionIdOf("Default")
        // The unit the citation names is no longer part of the document, so the live read finds nothing...
        val live = harness.get("/api/collections/$collectionId/sources/${unitId.value}")
        assertEquals(HttpStatusCode.NotFound, live.status, live.bodyAsText())

        // ...and the revision the excerpt came from still answers with the text it held.
        val excerpt = harness.get("/api/collections/$collectionId/sources/${unitId.value}?revision=$base")
        assertEquals(HttpStatusCode.OK, excerpt.status, excerpt.bodyAsText())
        assertContains(excerpt.bodyAsText(), "the original wording")
        assertContains(excerpt.bodyAsText(), "\"revisionId\":\"$base\"")
        assertContains(excerpt.bodyAsText(), "\"ordinal\":0")

        // A revision that never held this unit is refused rather than answered with the current text.
        val foreign = harness.get("/api/collections/$collectionId/sources/${unitId.value}?revision=$candidate")
        assertEquals(HttpStatusCode.NotFound, foreign.status, foreign.bodyAsText())
    }

    /**
     * A revision that is not a published reading may not be read here even when the caller knows its
     * identifier and it holds the very unit the citation names.
     *
     * The state check in the source route's revision lookup is what refuses this: without it, the
     * candidate's page is what `pageForUnit` finds, and the response is a 200 carrying text no reader may
     * see. A withdrawn candidate keeps its staged pages and its identifier, so it is refused by the same
     * check rather than by the pages having gone away.
     */
    @Test
    fun `a draft revision is refused like a foreign id even when it holds the page`() = runBlocking {
        val (documentId, unitId) = seedUnit(document = "draft.txt", text = "the published wording")
        val context = harness.context
        val base = assertNotNull(context.revisions.recordPublishedContent(documentId, "TEST_SEED"))
        val draft = "the unreviewed replacement wording"
        // The candidate names the same unit as the reading the document publishes, so nothing but its
        // state keeps its text out of the citation path.
        val candidate = context.revisions.openCandidate(documentId, base, "TEST_REPLACEMENT")
        context.revisions.appendPage(
            candidate,
            RevisionPageDraft(
                ordinal = 0,
                unitId = unitId,
                locator = SourceLocation.TextLines(1, 1),
                extractedText = draft,
                searchText = draft,
                extractionMethod = ExtractionMethod.OCR,
                approval = PageApproval.APPROVED,
                chunks = listOf(
                    RevisionChunkDraft(
                        ordinal = 0,
                        text = draft,
                        startOffset = 0,
                        endOffset = draft.length,
                        tokenCount = 5,
                        tokenStart = 0,
                        tokenEnd = 4,
                        embedding = vectorFor(draft),
                    ),
                ),
            ),
        )
        val collectionId = harness.collectionIdOf("Default")

        val candidateRead = harness.get(
            "/api/collections/$collectionId/sources/${unitId.value}?revision=$candidate",
        )
        assertEquals(HttpStatusCode.NotFound, candidateRead.status, candidateRead.bodyAsText())
        assertFalse(
            candidateRead.bodyAsText().contains(draft),
            "a candidate's text must not be reachable: ${candidateRead.bodyAsText()}",
        )

        // A withdrawn candidate keeps its identifier and its staged pages, so the refusal has to come
        // from the state it is in rather than from anything having been removed.
        context.revisions.withdrawCandidate(candidate)
        val withdrawnRead = harness.get(
            "/api/collections/$collectionId/sources/${unitId.value}?revision=$candidate",
        )
        assertEquals(HttpStatusCode.NotFound, withdrawnRead.status, withdrawnRead.bodyAsText())
        assertFalse(
            withdrawnRead.bodyAsText().contains(draft),
            "a withdrawn revision's text must not be reachable: ${withdrawnRead.bodyAsText()}",
        )

        // The published reading the same unit belongs to is still served, so the two refusals above are
        // about the state of the revision that was named and not about the unit being unreadable.
        val publishedRead = harness.get(
            "/api/collections/$collectionId/sources/${unitId.value}?revision=$base",
        )
        assertEquals(HttpStatusCode.OK, publishedRead.status, publishedRead.bodyAsText())
        assertContains(publishedRead.bodyAsText(), "the published wording")
        val liveRead = harness.get("/api/collections/$collectionId/sources/${unitId.value}")
        assertEquals(HttpStatusCode.OK, liveRead.status, liveRead.bodyAsText())
        assertContains(liveRead.bodyAsText(), "the published wording")
    }

    @Test
    fun `a source unit cannot be read through another collection`() = runBlocking {
        val (_, unitId) = seedUnit(document = "private.txt", text = "private evidence")
        harness.createCollection("Other")

        val response = harness.get(
            "/api/collections/${harness.collectionIdOf("Other")}/sources/${unitId.value}",
        )

        assertEquals(HttpStatusCode.NotFound, response.status, response.bodyAsText())
    }

    @Test
    fun `an unknown content unit is not found`() = runBlocking {
        val response = harness.get(
            "/api/collections/${harness.collectionIdOf("Default")}/sources/does-not-exist",
        )

        assertEquals(HttpStatusCode.NotFound, response.status, response.bodyAsText())
    }

    @Test
    fun `source text is returned in bounded pages without exposing artifact paths`() = runBlocking {
        val (_, unitId) = seedUnit(document = "long.txt", text = "x".repeat(70_000))
        val collectionId = harness.collectionIdOf("Default")

        val firstPage = harness.get("/api/collections/$collectionId/sources/${unitId.value}?limit=1000")
        val nextPage = harness.get("/api/collections/$collectionId/sources/${unitId.value}?offset=1000&limit=1000")

        assertEquals(HttpStatusCode.OK, firstPage.status, firstPage.bodyAsText())
        assertContains(firstPage.bodyAsText(), "\"totalChars\":70000")
        assertContains(firstPage.bodyAsText(), "\"offset\":0")
        assertContains(firstPage.bodyAsText(), "\"truncated\":true")
        assertFalse(firstPage.bodyAsText().contains("artifactRelativePath"))
        assertEquals(HttpStatusCode.OK, nextPage.status, nextPage.bodyAsText())
        assertContains(nextPage.bodyAsText(), "\"offset\":1000")
        assertEquals(1000, ApiJson.parseToJsonElement(nextPage.bodyAsText()).jsonObject["text"]!!.jsonPrimitive.content.length)
    }

    @Test
    fun `default source pages never split a surrogate pair`() = runBlocking {
        val original = "a".repeat(16_383) + "😀" + "tail"
        val (_, unitId) = seedUnit(document = "unicode.txt", text = original)
        val collectionId = harness.collectionIdOf("Default")

        val firstResponse = harness.get("/api/collections/$collectionId/sources/${unitId.value}")
        assertEquals(HttpStatusCode.OK, firstResponse.status, firstResponse.bodyAsText())
        val first = ApiJson.decodeFromString<SourceContentResponse>(firstResponse.bodyAsText())
        val nextOffset = first.offset + first.text.length
        val secondResponse = harness.get("/api/collections/$collectionId/sources/${unitId.value}?offset=$nextOffset")
        assertEquals(HttpStatusCode.OK, secondResponse.status, secondResponse.bodyAsText())
        val second = ApiJson.decodeFromString<SourceContentResponse>(secondResponse.bodyAsText())

        assertTrue(first.text.length <= 16_384)
        assertFalse(Character.isHighSurrogate(first.text.last()))
        assertFalse(Character.isLowSurrogate(second.text.first()))
        assertEquals(original, first.text + second.text)
    }

    @Test
    fun `invalid source ranges are rejected as bad requests`() = runBlocking {
        val (_, unitId) = seedUnit(document = "notes.txt", text = "notes")
        val collectionId = harness.collectionIdOf("Default")

        val negativeOffset = harness.get("/api/collections/$collectionId/sources/${unitId.value}?offset=-1")
        val excessiveLimit = harness.get("/api/collections/$collectionId/sources/${unitId.value}?limit=65537")
        val malformedRange = harness.get("/api/collections/$collectionId/sources/${unitId.value}?offset=abc")
        val beyondSource = harness.get("/api/collections/$collectionId/sources/${unitId.value}?offset=6")

        assertEquals(HttpStatusCode.BadRequest, negativeOffset.status, negativeOffset.bodyAsText())
        assertEquals(HttpStatusCode.BadRequest, excessiveLimit.status, excessiveLimit.bodyAsText())
        assertEquals(HttpStatusCode.BadRequest, malformedRange.status, malformedRange.bodyAsText())
        assertEquals(HttpStatusCode.BadRequest, beyondSource.status, beyondSource.bodyAsText())
    }

    @Test
    fun `managed original is served only through its collection and opaque document id`() = runBlocking {
        val (documentId, _) = seedUnit(document = "source.txt", text = "managed source")
        val collectionId = harness.collectionIdOf("Default")
        harness.createCollection("Other")
        val original = harness.context.paths.originalFile(defaultCollectionId, documentId, "txt")
        Files.createDirectories(original.parent)
        Files.writeString(original, "original bytes")

        val response = harness.get("/api/collections/$collectionId/documents/${documentId.value}/original")
        val crossCollection = harness.get(
            "/api/collections/${harness.collectionIdOf("Other")}/documents/${documentId.value}/original",
        )

        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        assertEquals("application/octet-stream", response.headers[HttpHeaders.ContentType]?.substringBefore(';'))
        assertContains(response.headers[ContentDisposition].orEmpty(), "attachment")
        assertEquals("original bytes", response.bodyAsText())
        assertEquals(HttpStatusCode.NotFound, crossCollection.status, crossCollection.bodyAsText())

        val ranged = harness.client.get(
            harness.url + "/api/collections/$collectionId/documents/${documentId.value}/original",
        ) { header(Range, "bytes=2-5") }
        assertEquals(HttpStatusCode.PartialContent, ranged.status, ranged.bodyAsText())
        assertEquals("igin", ranged.bodyAsText())
        assertEquals("bytes 2-5/14", ranged.headers[ContentRange])

        val outOfBounds = harness.client.get(
            harness.url + "/api/collections/$collectionId/documents/${documentId.value}/original",
        ) { header(Range, "bytes=99-") }
        assertEquals(HttpStatusCode.RequestedRangeNotSatisfiable, outOfBounds.status, outOfBounds.bodyAsText())
        assertEquals("bytes */14", outOfBounds.headers[ContentRange])

        val emptySuffix = harness.client.get(
            harness.url + "/api/collections/$collectionId/documents/${documentId.value}/original",
        ) { header(Range, "bytes=-") }
        assertEquals(HttpStatusCode.RequestedRangeNotSatisfiable, emptySuffix.status, emptySuffix.bodyAsText())
    }

    @Test
    fun `active browser document originals download while PDF and raster images stay inline`() = runBlocking {
        val collectionId = harness.collectionIdOf("Default")
        val cases = listOf(
            Triple("untrusted.html", "text/html", "attachment"),
            Triple("untrusted.svg", "image/svg+xml", "attachment"),
            Triple("untrusted.å", "text/html", "attachment"),
            Triple("document.pdf", "application/pdf", "inline"),
            Triple("picture.png", "image/png", "inline"),
        )

        for ((filename, mediaType, disposition) in cases) {
            val (documentId, _) = seedUnit(document = filename, text = "source", mediaType = mediaType)
            val extension = filename.substringAfterLast('.')
            val original = harness.context.paths.originalFile(defaultCollectionId, documentId, extension)
            Files.createDirectories(original.parent)
            Files.writeString(original, "source bytes")

            val response = harness.get("/api/collections/$collectionId/documents/${documentId.value}/original")

            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            assertContains(response.headers[ContentDisposition].orEmpty(), disposition)
            assertTrue(response.headers[ContentDisposition].orEmpty().all { it.code < 128 })
            assertEquals(
                if (disposition == "inline") mediaType else "application/octet-stream",
                response.headers[HttpHeaders.ContentType]?.substringBefore(';'),
            )
            assertEquals("nosniff", response.headers["X-Content-Type-Options"])
        }
    }

    // ---- A nominal reindex accept: 202 with a job ----

    @Test
    fun `reindex is accepted as a job`() = runBlocking {
        val response = harness.request(
            HttpMethod.Post,
            "/api/reindex",
            body = """{"collection":"Default"}""",
            credential = Credential.BEARER,
        )

        assertEquals(HttpStatusCode.Accepted, response.status, response.bodyAsText())
        val body = response.bodyAsText()
        assertContains(body, "\"accepted\":true")
        assertContains(body, "\"jobId\":")
    }

    /** Seeds one unit in the Default collection and indexes it, returning the document and unit ids. */
    private suspend fun seedUnit(
        document: String,
        text: String,
        mediaType: String = "text/plain",
    ): Pair<DocumentId, ContentUnitId> {
        val context = harness.context
        val collection = defaultCollectionId
        val now = Instants.now()
        val documentId = DocumentId.new()
        context.documents.insert(
            Document(
                id = documentId,
                collectionId = collection,
                sha256 = "sha-$document:$text",
                mediaType = mediaType,
                originalFilename = document,
                sourcePath = "tmp/original/$document",
                sizeBytes = 1L,
                status = DocumentStatus.COMPLETE,
                title = document,
                author = null,
                language = null,
                createdAt = now,
                updatedAt = now,
            ),
        )
        val committed = context.content.commitExtractedUnit(
            documentId = documentId,
            fingerprint = ExtractionFingerprint.of(
                "sha-$document",
                ExtractionSettings(ocrLanguages = "eng", extractorSchemaVersion = "search-routes-test"),
            ),
            key = "unit-0",
            ordinal = 0,
            draft = ContentUnitDraft(
                locator = SourceLocation.TextLines(1, 1),
                extractedText = text,
                searchText = text,
                method = ExtractionMethod.DIRECT_TEXT,
            ),
            artifactRoot = context.paths.libraryDir,
        )
        val unitId = committed.unit.id
        val chunk = Chunk(
            id = ChunkId.new(),
            contentUnitId = unitId,
            ordinal = 0,
            text = text,
            startOffset = 0,
            endOffset = text.length,
            tokenCount = text.length,
            tokenStart = 0,
            tokenEnd = (text.length - 1).coerceAtLeast(0),
        )
        context.index().replaceDocument(
            listOf(
                DocumentRow(
                    collectionId = collection,
                    documentId = documentId,
                    unitId = unitId,
                    locator = SourceLocation.TextLines(1, 1),
                    locatorLabel = "line 1",
                    chunk = chunk,
                    vector = vectorFor(text),
                ),
            ),
        )
        return documentId to unitId
    }
}
