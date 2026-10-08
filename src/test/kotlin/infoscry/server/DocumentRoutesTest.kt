package infoscry.server

import infoscry.document.RetryPrerequisites
import infoscry.domain.Collection
import infoscry.domain.CollectionId
import infoscry.domain.CollectionLifecycle
import infoscry.domain.Document
import infoscry.domain.DocumentId
import infoscry.domain.DocumentStatus
import infoscry.domain.ExtractionMethod
import infoscry.domain.JobType
import infoscry.domain.SourceLocation
import infoscry.domain.UnitKind
import infoscry.embedding.GpuRuntime
import infoscry.extract.TesseractOcr
import infoscry.extract.ContentUnitDraft
import infoscry.extract.ExtractionFingerprint
import infoscry.extract.ExtractionSettings
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * The paginated document listing over a real socket: what a page contains, how the total behaves, and
 * what must never leave the server.
 *
 * The privacy assertions run against the raw response body rather than a decoded object, because a
 * decoded [DocumentsResponse] could not express the leak being guarded against: the endpoint building
 * that object already decided what is not part of the product surface.
 */
class DocumentRoutesTest {

    private lateinit var dataDir: Path
    private lateinit var harness: ApiTestServer

    @BeforeTest
    fun startServer() {
        dataDir = Files.createTempDirectory("infoscry-documents-routes")
        // Retry admission is exercised by the summary's transition test; the prerequisites are injected
        // so it asserts on the archive's behaviour rather than on what this machine happens to have.
        harness = ApiTestServer(dataDir, retryPrerequisites = availablePrerequisites())
    }

    @AfterTest
    fun stopServer() {
        harness.close()
        dataDir.toFile().deleteRecursively()
    }

    @Test
    fun `an empty collection lists zero documents`() = runBlocking {
        val id = newCollection()

        val response = harness.get("/api/collections/$id/documents")

        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val page = ApiJson.decodeFromString<DocumentsResponse>(response.bodyAsText())
        assertEquals(emptyList(), page.documents)
        assertEquals(0, page.total)
    }

    @Test
    fun `documents are paginated and the total does not depend on the page size`() = runBlocking {
        val id = CollectionId(newCollection())
        repeat(205) { index -> harness.context.documents.insert(document(index, id)) }

        val first = page(id, "?limit=2")
        assertEquals(listOf("doc-204", "doc-203"), first.documents.map { it.id.value })
        assertEquals(205, first.total)

        val second = page(id, "?limit=2&offset=2")
        assertEquals(listOf("doc-202", "doc-201"), second.documents.map { it.id.value })
        assertEquals(205, second.total, "the total counts the collection, not the page")

        val tail = page(id, "?limit=50&offset=200")
        assertEquals(5, tail.documents.size)
        assertEquals(205, tail.total)

        val clamped = page(id, "?limit=500")
        assertEquals(200, clamped.documents.size, "a limit above 200 is clamped, not honoured or refused")
        assertEquals(205, clamped.total)

        val small = page(id, "?limit=10")
        assertEquals(10, small.documents.size)
        assertEquals(205, small.total)
    }

    @Test
    fun `the response omits the source path, the hash and the error message`() = runBlocking {
        val id = CollectionId(newCollection())
        harness.context.documents.insert(
            document(0, id).copy(
                sha256 = "aeac0c37f2f81db1c2e13d68aa5c4f7b14c0f9e1",
                sourcePath = "/private/evidence/quarterly-figures.pdf",
                title = "Quarterly figures",
                author = "Finance",
                language = "en",
            ),
        )
        harness.context.documents.insert(
            document(1, id).copy(
                status = DocumentStatus.FAILED,
                errorCode = "PDF_PARSE_FAILED",
                errorMessage = "could not parse page 2 of the 2025 budget worksheet",
            ),
        )

        val response = harness.get("/api/collections/${id.value}/documents")
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val body = response.bodyAsText()

        assertFalse(body.contains("sourcePath"), "the domain field sourcePath must never be serialized")
        assertFalse(body.contains("original_path"), "the wire name of sourcePath must never appear")
        assertFalse(body.contains("sha256"), "the content hash must never be serialized")
        assertFalse(body.contains("errorMessage"), "errorMessage can carry document text and is omitted")
        assertFalse(body.contains("2025 budget worksheet"), "error message text must not reach the response")
        assertFalse(body.contains("/private/evidence"), "the filesystem path itself must not reach the response")

        listOf(
            "originalFilename",
            "mediaType",
            "sizeBytes",
            "status",
            "title",
            "author",
            "language",
            "errorCode",
            "createdAt",
            "updatedAt",
        ).forEach { field ->
            assertContains(body, field, message = "the list row needs $field to render")
        }
    }

    @Test
    fun `an unknown collection is not found`() = runBlocking {
        val response = harness.get("/api/collections/does-not-exist/documents")

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertContains(response.bodyAsText(), "NOT_FOUND")
    }

    @Test
    fun `an invalid limit or offset is a typed bad request`() = runBlocking {
        val id = newCollection()
        val base = "/api/collections/$id/documents"

        listOf("?limit=abc", "?limit=1.5", "?limit=0", "?limit=-3", "?offset=abc", "?offset=-1")
            .forEach { query ->
                val response = harness.get(base + query)
                assertEquals(HttpStatusCode.BadRequest, response.status, "query $query")
                assertContains(response.bodyAsText(), "INVALID_REQUEST", message = "query $query must be a typed bad request")
            }

        assertEquals(HttpStatusCode.OK, harness.get("$base?limit=1&offset=0").status)
    }

    @Test
    fun `filters and the total describe the same criteria before any paging`() = runBlocking {
        val id = CollectionId(newCollection())
        repeat(120) { index ->
            harness.context.documents.insert(
                documentAt(
                    id = "doc-%03d".format(index),
                    collectionId = id,
                    filename = "report-%03d.pdf".format(index),
                    status = if (index in 90..94) DocumentStatus.FAILED else DocumentStatus.COMPLETE,
                    createdAt = "2026-01-01T00:00:00.%03dZ".format(index),
                ),
            )
        }

        // One hundred rows match the term and five of them failed. The page is drawn from the matching
        // rows: an offset of ten lands on the eleventh match, not on the eleventh document of the
        // collection with the term applied afterwards.
        val filtered = page(id, "?q=report-0&limit=5&offset=10")
        assertEquals(
            listOf("doc-089", "doc-088", "doc-087", "doc-086", "doc-085"),
            filtered.documents.map { it.id.value },
        )
        assertEquals(100, filtered.total, "the total counts the criteria, not the collection")

        val unfiltered = page(id, "?limit=5&offset=10")
        assertEquals(
            listOf("doc-109", "doc-108", "doc-107", "doc-106", "doc-105"),
            unfiltered.documents.map { it.id.value },
        )
        assertEquals(120, unfiltered.total)

        val failed = page(id, "?q=report-0&status=FAILED")
        assertEquals(
            listOf("doc-094", "doc-093", "doc-092", "doc-091", "doc-090"),
            failed.documents.map { it.id.value },
        )
        assertEquals(5, failed.total)

        assertEquals(5, page(id, "?status=FAILED").total)
        assertEquals(5, page(id, "?q=report-0&status=failed").total, "a status name is matched case-insensitively")
        assertEquals(100, page(id, "?q=report-0").total)
    }

    @Test
    fun `filename search is literal, case-insensitive, and never reads the external source path`() = runBlocking {
        val id = CollectionId(newCollection())
        listOf("a_b.txt", "axb.txt", "100% done.txt", "back\\slash.txt", "plain.txt").forEachIndexed { index, name ->
            harness.context.documents.insert(documentAt("doc-$index", id, name))
        }
        harness.context.documents.insert(
            documentAt(
                id = "doc-other",
                collectionId = id,
                filename = "other.pdf",
                sourcePath = "/private/evidence/unique-source-token.pdf",
            ),
        )

        assertEquals(listOf("a_b.txt"), filenames(id, "_"), "an underscore matches itself")
        assertEquals(listOf("a_b.txt"), filenames(id, "A_B"), "the filename match is case-insensitive")
        assertEquals(emptyList(), filenames(id, "a%b"), "a wildcard the reader typed matches itself")
        assertEquals(emptyList(), filenames(id, "a_b_c"))
        assertEquals(listOf("100% done.txt"), filenames(id, "%"))
        assertEquals(listOf("back\\slash.txt"), filenames(id, "\\"))
        assertEquals(emptyList(), filenames(id, "unique-source-token"), "the source path is not searched")
        assertEquals(emptyList(), filenames(id, "private/evidence"))
        assertEquals(listOf("axb.txt"), filenames(id, "axb"), "the escaped underscore does not widen the match")
    }

    @Test
    fun `sorting is total, so tied dates and filenames cannot repeat or drop a row`() = runBlocking {
        val id = CollectionId(newCollection())
        val names = listOf("beta.pdf", "alpha.pdf", "beta.pdf", "gamma.pdf", "alpha.pdf")
        names.forEachIndexed { index, name ->
            harness.context.documents.insert(
                documentAt("doc-$index", id, name, createdAt = TIED_DATE),
            )
        }

        assertEquals(listOf("doc-0", "doc-1", "doc-2", "doc-3", "doc-4"), idsOf(page(id, "?sort=newest")))
        assertEquals(listOf("doc-0", "doc-1", "doc-2", "doc-3", "doc-4"), idsOf(page(id, "?sort=oldest")))
        assertEquals(
            listOf("doc-1", "doc-4", "doc-0", "doc-2", "doc-3"),
            idsOf(page(id, "?sort=name-asc")),
            "ties fall back to the document id, not to whatever the file returns first",
        )
        assertEquals(listOf("doc-3", "doc-0", "doc-2", "doc-1", "doc-4"), idsOf(page(id, "?sort=name-desc")))

        // Page boundaries inside those ties still partition the rows exactly once.
        val paged = listOf(
            idsOf(page(id, "?sort=name-asc&limit=2")),
            idsOf(page(id, "?sort=name-asc&limit=2&offset=2")),
            idsOf(page(id, "?sort=name-asc&limit=2&offset=4")),
        ).flatten()
        assertEquals(listOf("doc-1", "doc-4", "doc-0", "doc-2", "doc-3"), paged)
    }

    @Test
    fun `an unknown sort or status is a typed bad request`() = runBlocking {
        val id = newCollection()
        val base = "/api/collections/$id/documents"

        listOf("?sort=sideways", "?status=NOT_A_STATUS", "?status=FAILED&status=nonsense").forEach { query ->
            val response = harness.get(base + query)
            assertEquals(HttpStatusCode.BadRequest, response.status, "query $query")
            assertContains(response.bodyAsText(), "INVALID_REQUEST", message = "query $query must be a typed bad request")
        }

        assertEquals(HttpStatusCode.OK, harness.get("$base?sort=Name-Desc&status=failed").status)
    }

    @Test
    fun `the scoped detail carries safe metadata, the curated error and the first reader source`() = runBlocking {
        val id = CollectionId(newCollection())
        val documentId = DocumentId("doc-detail")
        harness.context.documents.insert(
            documentAt(
                id = documentId.value,
                collectionId = id,
                filename = "budget.pdf",
                status = DocumentStatus.FAILED,
                errorCode = "UNSUPPORTED_MEDIA_TYPE",
                errorMessage = "could not read sheet 2 of the 2025 budget worksheet",
            ),
        )
        val firstUnit = commitUnit(documentId, ordinal = 0, text = "Budget summary.")
        val secondUnit = commitUnit(documentId, ordinal = 1, text = "Second page.")

        val response = harness.get("/api/collections/${id.value}/documents/${documentId.value}")
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val body = response.bodyAsText()
        assertFalse(body.contains("original_path"), "the wire name of sourcePath must never appear")
        assertFalse(body.contains("sha256"), "the content hash must never be serialized")
        assertFalse(body.contains("2025 budget worksheet"), "the stored error text may carry document text")
        assertFalse(body.contains("/private/evidence"), "the filesystem path itself must not reach the response")

        val detail = ApiJson.decodeFromString<DocumentDetail>(body)
        assertEquals(documentId.value, detail.document.id.value)
        assertEquals("budget.pdf", detail.document.originalFilename)
        assertEquals(DocumentStatus.FAILED, detail.document.status)
        assertEquals("UNSUPPORTED_MEDIA_TYPE", detail.document.errorCode)
        assertEquals(
            "the pipeline has no extractor for this kind of file",
            detail.errorMessage,
            "the message beside a code is InfoScry's own sentence for that code",
        )
        assertEquals(
            firstUnit,
            detail.sourceId,
            "opening a document starts at its first unit, not at its newest one",
        )
        assertNotEquals(secondUnit, detail.sourceId)
        assertEquals(2, harness.context.content.listUnits(documentId, afterOrdinal = -1, limit = 10).size)
    }

    @Test
    fun `a document without readable content reports no reader source`() = runBlocking {
        val id = CollectionId(newCollection())
        harness.context.documents.insert(documentAt("doc-empty", id, "scan.pdf", status = DocumentStatus.QUEUED))

        val detail = ApiJson.decodeFromString<DocumentDetail>(
            harness.get("/api/collections/${id.value}/documents/doc-empty").bodyAsText(),
        )

        assertNull(detail.sourceId)
        assertNull(detail.errorMessage)
    }

    @Test
    fun `another collection's document is not found and leaks nothing`() = runBlocking {
        val mine = CollectionId(newCollection())
        harness.createCollection("Other", Credential.BEARER)
        val other = CollectionId(harness.collectionIdOf("Other"))
        harness.context.documents.insert(documentAt("doc-other", other, "secret-plans.pdf"))

        val crossCollection = harness.get("/api/collections/${mine.value}/documents/doc-other")
        assertEquals(HttpStatusCode.NotFound, crossCollection.status)
        val body = crossCollection.bodyAsText()
        assertContains(body, "NOT_FOUND")
        assertFalse(body.contains("secret-plans"), "not-found must not describe the other collection's document")
        assertFalse(body.contains(other.value), "not-found must not name the other collection")
        listOf("originalFilename", "mediaType", "sizeBytes", "createdAt").forEach { field ->
            assertFalse(body.contains(field), "a refusal carries the envelope, not a document row: $field")
        }

        val unknown = harness.get("/api/collections/${mine.value}/documents/does-not-exist")
        assertEquals(HttpStatusCode.NotFound, unknown.status)
        assertContains(unknown.bodyAsText(), "NOT_FOUND")
    }

    @Test
    fun `the listing needs no credential, like every other read route`() = runBlocking {
        val id = newCollection()

        val response = harness.request(
            HttpMethod.Get,
            "/api/collections/$id/documents",
            body = null,
            credential = Credential.NONE,
        )

        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
    }

    @Test
    fun `a row and its detail carry the attempt's own counts, and its method counters stay apart`() = runBlocking {
        val id = CollectionId(newCollection())
        val documentId = DocumentId("doc-progress")
        harness.context.documents.insert(
            documentAt(documentId.value, id, "scan.pdf", status = DocumentStatus.OCR),
        )
        harness.context.content.recordProgress(documentId, fingerprintOf(documentId), UnitKind.PAGE, 40)
        commitUnit(documentId, ordinal = 0, text = "Page one.")
        commitUnit(documentId, ordinal = 1, text = "Page two.", method = ExtractionMethod.OCR)
        harness.context.content.commitFailedUnit(
            documentId = documentId,
            fingerprint = fingerprintOf(documentId),
            key = "page-3",
            ordinal = 2,
            code = "OCR_FAILED",
        )

        val listed = page(id, "?q=scan").documents.single()
        val progress = assertNotNull(listed.progress, "a row shows the progress of its document's attempt")
        assertEquals(UnitKind.PAGE, progress.unitKind, "the count is in the units the document is made of")
        assertEquals(40, progress.totalUnits, "the denominator is the total the extractor announced")
        assertEquals(2, progress.processedUnits)
        assertEquals(1, progress.failedUnits)
        assertEquals(1, progress.directTextUnits)
        assertEquals(1, progress.ocrUnits)

        val detail = ApiJson.decodeFromString<DocumentDetail>(
            harness.get("/api/collections/${id.value}/documents/${documentId.value}").bodyAsText(),
        )
        assertEquals(progress, detail.progress, "the detail and the row read the same durable counts")
        assertEquals(1, detail.warnings.size)
        assertContains(detail.warnings.single(), "OCR tool ran")
    }

    @Test
    fun `a count with no announced total is shown without a denominator`() = runBlocking {
        val id = CollectionId(newCollection())
        val documentId = DocumentId("doc-open")
        harness.context.documents.insert(
            documentAt(documentId.value, id, "minutes.txt", status = DocumentStatus.EXTRACTING),
        )
        commitUnit(documentId, ordinal = 0, text = "Only unit.")

        val detail = ApiJson.decodeFromString<DocumentDetail>(
            harness.get("/api/collections/${id.value}/documents/${documentId.value}").bodyAsText(),
        )

        val progress = assertNotNull(detail.progress)
        assertNull(progress.totalUnits, "nobody announced a total, so there is no denominator to divide by")
        assertEquals(1, progress.processedUnits)
    }

    @Test
    fun `a document whose units carry no method reports unknown, not zero`() = runBlocking {
        val id = CollectionId(newCollection())
        val documentId = DocumentId("doc-legacy")
        harness.context.documents.insert(documentAt(documentId.value, id, "old.pdf"))
        commitUnit(documentId, ordinal = 0, text = "Page one.")
        // What an archive written before the method was recorded looks like: the column did not exist, so
        // the unit has no value in it rather than a value that happens to be direct text.
        harness.context.database.transaction { connection ->
            connection.prepareStatement("UPDATE content_units SET extraction_method = NULL").use {
                it.executeUpdate()
            }
        }

        val detail = ApiJson.decodeFromString<DocumentDetail>(
            harness.get("/api/collections/${id.value}/documents/${documentId.value}").bodyAsText(),
        )

        val progress = assertNotNull(detail.progress)
        assertEquals(1, progress.processedUnits)
        assertNull(progress.directTextUnits, "an unknown method is not zero units of direct text")
        assertNull(progress.ocrUnits)
    }

    @Test
    fun `the detail's warnings are InfoScry's own sentences, one per failed code`() = runBlocking {
        val id = CollectionId(newCollection())
        val documentId = DocumentId("doc-warnings")
        harness.context.documents.insert(
            documentAt(documentId.value, id, "partial.pdf", status = DocumentStatus.COMPLETE_WITH_WARNINGS),
        )
        commitUnit(documentId, ordinal = 0, text = "Page one.")
        listOf(1, 2).forEach { ordinal ->
            harness.context.content.commitFailedUnit(
                documentId = documentId,
                fingerprint = fingerprintOf(documentId),
                key = "page-${ordinal + 1}",
                ordinal = ordinal,
                code = "OCR_FAILED",
            )
        }

        val body = harness.get("/api/collections/${id.value}/documents/${documentId.value}").bodyAsText()
        val detail = ApiJson.decodeFromString<DocumentDetail>(body)

        assertEquals(2, assertNotNull(detail.progress).failedUnits)
        assertEquals(
            listOf(
                "the OCR tool ran but could not read part of this document; the rest of it was extracted",
            ),
            detail.warnings,
            "two pages with the same code are one warning, in InfoScry's words rather than the code's",
        )
        assertFalse(body.contains("OCR_FAILED"), "a warning is a sentence, not the code it came from")
    }

    @Test
    fun `a missing ocr tool gives the remedy and leaves the readable pages openable`() = runBlocking {
        val id = CollectionId(newCollection())
        val documentId = DocumentId("doc-needs-tool")
        harness.context.documents.insert(
            documentAt(
                id = documentId.value,
                collectionId = id,
                filename = "scan.pdf",
                status = DocumentStatus.NEEDS_TOOL,
                errorCode = TesseractOcr.NEEDS_TESSERACT_CODE,
                errorMessage = "could not read page 4 of the 2025 budget scan",
            ),
        )
        val sourceId = commitUnit(documentId, ordinal = 0, text = "A page with its own text layer.")

        val body = harness.get("/api/collections/${id.value}/documents/${documentId.value}").bodyAsText()
        val detail = ApiJson.decodeFromString<DocumentDetail>(body)

        assertEquals(
            "the OCR tool (Tesseract) is not installed, so pages without a text layer cannot be read",
            detail.errorMessage,
            "a missing prerequisite has to name its own remedy",
        )
        assertEquals(sourceId, detail.sourceId, "the pages that were read stay openable")
        assertFalse(body.contains("2025 budget scan"), "the stored text may quote the document")
    }

    @Test
    fun `a gpu failure keeps its diagnostic, its remedy and the source view`() = runBlocking {
        val id = CollectionId(newCollection())
        val documentId = DocumentId("doc-no-gpu")
        harness.context.documents.insert(
            documentAt(
                id = documentId.value,
                collectionId = id,
                filename = "report.pdf",
                status = DocumentStatus.FAILED,
                errorCode = GpuRuntime.GPU_UNAVAILABLE_CODE,
                errorMessage = "the CoreML runtime reported an error",
            ),
        )
        val sourceId = commitUnit(documentId, ordinal = 0, text = "Extracted before embedding.")

        val detail = ApiJson.decodeFromString<DocumentDetail>(
            harness.get("/api/collections/${id.value}/documents/${documentId.value}").bodyAsText(),
        )

        assertEquals(GpuRuntime.remedy(), detail.errorMessage, "the failure keeps the remedy, not the raw error")
        assertEquals(sourceId, detail.sourceId, "the text that was extracted is still readable without a GPU")
        assertEquals(1, assertNotNull(detail.progress).processedUnits)
    }

    @Test
    fun `a complete document carries a document-level quality score in its row and its detail`() = runBlocking {
        val id = CollectionId(newCollection())
        val documentId = DocumentId("doc-quality")
        harness.context.documents.insert(documentAt(id = documentId.value, collectionId = id, filename = "letter.txt"))
        commitUnit(documentId, ordinal = 0, text = "Dear reader, the letter arrived on Monday.")
        commitUnit(documentId, ordinal = 1, text = "We will answer before the end of the week.")

        val listing = harness.get("/api/collections/${id.value}/documents")
        assertEquals(HttpStatusCode.OK, listing.status, listing.bodyAsText())
        assertContains(listing.bodyAsText(), "\"qualityScore\":")
        val row = ApiJson.decodeFromString<DocumentsResponse>(listing.bodyAsText()).documents.single()
        assertScoreInRange(row.qualityScore)

        val detailResponse = harness.get("/api/collections/${id.value}/documents/${documentId.value}")
        assertEquals(HttpStatusCode.OK, detailResponse.status, detailResponse.bodyAsText())
        assertContains(detailResponse.bodyAsText(), "\"qualityScore\":")
        val detail = ApiJson.decodeFromString<DocumentDetail>(detailResponse.bodyAsText())
        assertScoreInRange(detail.qualityScore)
        assertEquals(row.qualityScore, detail.qualityScore, "the row and the detail score the same text")
    }

    @Test
    fun `only a complete document with judgeable text carries a quality score key`() = runBlocking {
        val id = CollectionId(newCollection())
        harness.context.documents.insert(documentAt(id = "doc-queued", collectionId = id, filename = "queued.txt", status = DocumentStatus.QUEUED))
        commitUnit(DocumentId("doc-queued"), ordinal = 0, text = "Text that must not be scored yet.")
        harness.context.documents.insert(documentAt(id = "doc-blank", collectionId = id, filename = "blank.txt"))
        commitUnit(DocumentId("doc-blank"), ordinal = 0, text = "   \n  ")

        val listing = harness.get("/api/collections/${id.value}/documents")
        assertEquals(HttpStatusCode.OK, listing.status, listing.bodyAsText())
        val rows = ApiJson.parseToJsonElement(listing.bodyAsText()).jsonObject["documents"]!!.jsonArray
            .associateBy { it.jsonObject["originalFilename"]!!.jsonPrimitive.content }
        assertFalse("qualityScore" in rows.getValue("queued.txt").jsonObject, "a queued row has no score key")
        assertFalse("qualityScore" in rows.getValue("blank.txt").jsonObject, "an all-blank complete row has no score key")
    }

    private fun assertScoreInRange(score: Double?) {
        assertNotNull(score, "a complete document with readable text has a quality score")
        assertTrue(score in 0.0..100.0, "the quality score is 0 to 100, was $score")
    }

    private suspend fun newCollection(): String {
        harness.createCollection("Docs", Credential.BEARER)
        return harness.collectionIdOf("Docs")
    }

    @Test
    fun `the summary counts attention statuses that sit beyond the first page`() = runBlocking {
        val id = CollectionId(newCollection())
        // 75 documents; the only two that failed are the two oldest, so they fall on the second 50-row
        // page of the newest-first listing. The acceptance vector: 75 documents, 2 failed on the second
        // page, summary failed = 2.
        repeat(75) { index ->
            val status = if (index in 0..1) DocumentStatus.FAILED else DocumentStatus.COMPLETE
            harness.context.documents.insert(document(index, id).copy(status = status))
        }

        val firstPage = page(id, "")
        assertEquals(50, firstPage.documents.size)
        assertEquals(75, firstPage.total)
        assertFalse(
            firstPage.documents.any { it.status == DocumentStatus.FAILED },
            "the attention statuses must be on the second page for this test to mean anything",
        )
        val secondPage = page(id, "?limit=50&offset=50")
        assertEquals(25, secondPage.documents.size)
        assertEquals(2, secondPage.documents.count { it.status == DocumentStatus.FAILED })

        val summary = summary(id.value)

        assertEquals(75, summary.total, "the summary counts the collection, not the 50 rows a page holds")
        assertEquals(2, summary.byStatus[DocumentStatus.FAILED], "failed beyond the first page still count")
        assertEquals(73, summary.byStatus[DocumentStatus.COMPLETE])
        assertEquals(
            DocumentStatus.entries.toSet(),
            summary.byStatus.keys,
            "every current status is present, including the ones no document has",
        )
        assertEquals(summary.total, summary.byStatus.values.sum(), "the total is the same snapshot")
    }

    @Test
    fun `the summary is the collection's own snapshot, never a filter or another collection's`() = runBlocking {
        val id = CollectionId(newCollection())
        harness.createCollection("Other", Credential.BEARER)
        val other = CollectionId(harness.collectionIdOf("Other"))
        repeat(9) { index ->
            harness.context.documents.insert(
                documentAt(
                    id = "mine-%03d".format(index),
                    collectionId = id,
                    filename = "invoice-%03d.pdf".format(index),
                    status = if (index in 0..2) DocumentStatus.FAILED else DocumentStatus.COMPLETE,
                ),
            )
        }
        repeat(6) { index ->
            harness.context.documents.insert(
                documentAt("theirs-%03d".format(index), other, "plans-$index.pdf", status = DocumentStatus.FAILED),
            )
        }
        // A file an import queued but has not published as a document yet: file-only, so it is not a
        // document and the summary must not count it.
        val job = harness.context.jobs.enqueue(type = JobType.IMPORT, collectionId = id, total = 1)
        harness.context.importItems.queue(job.id, "pending-1", "/private/evidence/not-published-yet.txt")

        val plain = summary(id.value)
        // Listing filters belong to the listing: neither the summary's own query string nor a filtered
        // listing call may change what the aggregate reports.
        val filteredListing = page(id, "?q=invoice&status=FAILED")
        assertEquals(3, filteredListing.total, "the listing filters its own rows")
        val withQuery = summary(id.value, "?q=invoice&status=FAILED&limit=1&sort=name-asc")

        assertEquals(plain, withQuery, "query parameters must not change the collection-wide summary")
        assertEquals(9, plain.total, "another collection's documents and file-only import items do not count")
        assertEquals(3, plain.byStatus[DocumentStatus.FAILED])
        assertEquals(6, plain.byStatus[DocumentStatus.COMPLETE])

        val theirs = summary(other.value)
        assertEquals(6, theirs.total, "each collection is summarized as its own")
        assertEquals(6, theirs.byStatus[DocumentStatus.FAILED])
        assertEquals(0, theirs.byStatus[DocumentStatus.COMPLETE])
    }

    @Test
    fun `an empty collection's summary is zero and unknown or deleting collections are not found`() = runBlocking {
        val id = newCollection()

        val empty = summary(id)
        assertEquals(0, empty.total)
        assertEquals(
            DocumentStatus.entries.associateWith { 0 },
            empty.byStatus,
            "an empty collection has a zero for every status, not a missing one",
        )

        val unknown = harness.get("/api/collections/does-not-exist/documents/summary")
        assertEquals(HttpStatusCode.NotFound, unknown.status, unknown.bodyAsText())
        assertContains(unknown.bodyAsText(), "NOT_FOUND")

        markDeleting(CollectionId(id))
        val deleting = harness.get("/api/collections/$id/documents/summary")
        assertEquals(HttpStatusCode.NotFound, deleting.status, deleting.bodyAsText())
        assertContains(deleting.bodyAsText(), "NOT_FOUND")
    }

    @Test
    fun `the summary path answers the summary rather than a document id`() = runBlocking {
        val id = CollectionId(newCollection())
        harness.context.documents.insert(documentAt("doc-real", id, "real.pdf"))

        // `/summary` is a static route beside `{documentId}`; it must resolve as the aggregate and never
        // as a document that happens to be called "summary" (which would be a 404 here).
        val response = harness.get("/api/collections/${id.value}/documents/summary")
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val summary = ApiJson.decodeFromString<DocumentSummaryResponse>(response.bodyAsText())
        assertEquals(1, summary.total)
        assertEquals(1, summary.byStatus[DocumentStatus.COMPLETE])

        // The document-id route still answers its own documents beside the new static route.
        val detail = harness.get("/api/collections/${id.value}/documents/doc-real")
        assertEquals(HttpStatusCode.OK, detail.status, detail.bodyAsText())
    }

    @Test
    fun `the next summary after an import, a retry and a deletion reflects the state and carries no text`() =
        runBlocking {
            val id = CollectionId(newCollection())
            val sources = Files.createDirectories(dataDir.resolve("sources"))

            // Import: a managed document enters the pipeline in COPYING, then one attempt finishes it.
            val failedSource = sources.resolve("quarterly-figures.pdf")
            Files.writeString(failedSource, "the quarterly figures")
            val failed = harness.context.library.importFile(id, failedSource).document
            harness.context.documents.updateStatus(
                failed.id,
                DocumentStatus.FAILED,
                "PDF_PARSE_FAILED",
                "could not parse page 2 of the secret quarterly figures",
            )
            val doneSource = sources.resolve("minutes.pdf")
            Files.writeString(doneSource, "meeting minutes")
            val done = harness.context.library.importFile(id, doneSource).document
            harness.context.documents.updateStatus(done.id, DocumentStatus.COMPLETE)
            assertEquals(2, summary(id.value).total)
            assertEquals(1, summary(id.value).byStatus[DocumentStatus.FAILED])

            val importing = sources.resolve("half-copied.pdf")
            Files.writeString(importing, "still importing")
            val importingDocument = harness.context.library.importFile(id, importing).document
            val afterImport = summary(id.value)
            assertEquals(3, afterImport.total, "the import's new document is in the next summary")
            assertEquals(1, afterImport.byStatus[DocumentStatus.COPYING])

            // Retry: admission moves the failed document into the attempt's own queued state.
            val retry = harness.request(
                HttpMethod.Post,
                "/api/collections/${id.value}/documents/retry",
                body = """{"documentIds":["${failed.id.value}"]}""",
                credential = Credential.BEARER,
            )
            assertEquals(HttpStatusCode.Accepted, retry.status, retry.bodyAsText())
            val afterRetry = summary(id.value)
            assertEquals(0, afterRetry.byStatus[DocumentStatus.FAILED], "the retry left FAILED behind")
            assertEquals(1, afterRetry.byStatus[DocumentStatus.QUEUED])
            assertEquals(3, afterRetry.total, "a status transition adds and removes nothing")

            // Deletion: once the operation reaches its terminal phase, the row is gone for good.
            val deletion = harness.request(
                HttpMethod.Post,
                "/api/collections/${id.value}/documents/delete",
                body = """{"documentIds":["${importingDocument.id.value}"],"confirmed":true}""",
                credential = Credential.BEARER,
            )
            assertEquals(HttpStatusCode.Accepted, deletion.status, deletion.bodyAsText())
            val admitted = ApiJson.decodeFromString<DeleteDocumentsResponse>(deletion.bodyAsText())
            assertEquals("DONE", harness.awaitDeletion(admitted.operationId).phase)

            val body = harness.get("/api/collections/${id.value}/documents/summary").bodyAsText()
            val afterDeletion = ApiJson.decodeFromString<DocumentSummaryResponse>(body)
            assertEquals(2, afterDeletion.total, "the deleted document is out of the next summary")
            assertEquals(1, afterDeletion.byStatus[DocumentStatus.QUEUED])
            assertEquals(1, afterDeletion.byStatus[DocumentStatus.COMPLETE])
            assertEquals(0, afterDeletion.byStatus[DocumentStatus.COPYING])

            // The summary is counts and nothing else: no paths, no filenames, no stored text.
            assertFalse(body.contains("original_path"), "the wire name of sourcePath must never appear")
            assertFalse(body.contains("sourcePath"), "the domain field sourcePath must never be serialized")
            assertFalse(body.contains("sha256"), "the content hash must never be serialized")
            assertFalse(body.contains("errorMessage"), "a stored error message can carry document text")
            assertFalse(body.contains("secret quarterly figures"), "the stored error text must not reach the response")
            assertFalse(body.contains("quarterly-figures.pdf"), "a filename must not reach the summary")
            assertFalse(body.contains("sources"), "no source path may reach the summary")
        }

    /** One collection's summary as the route answers it, optionally with query parameters. */
    private suspend fun summary(reference: String, query: String = ""): DocumentSummaryResponse {
        val response = harness.get("/api/collections/$reference/documents/summary$query")
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return ApiJson.decodeFromString(response.bodyAsText())
    }

    /** A collection whose lifecycle says deletion, for the guard every collection read shares. */
    private fun markDeleting(collectionId: CollectionId) {
        harness.context.database.transaction { connection ->
            connection.prepareStatement("UPDATE collections SET lifecycle = ? WHERE id = ?").use { statement ->
                statement.setString(1, CollectionLifecycle.DELETING.name)
                statement.setString(2, collectionId.value)
                statement.executeUpdate()
            }
        }
    }

    private suspend fun page(id: CollectionId, query: String): DocumentsResponse =
        ApiJson.decodeFromString<DocumentsResponse>(
            harness.get("/api/collections/${id.value}/documents$query").bodyAsText(),
        )

    private fun document(index: Int, collectionId: CollectionId): Document = Document(
        id = DocumentId("doc-$index"),
        collectionId = collectionId,
        sha256 = "sha256-of-doc-$index",
        mediaType = "application/pdf",
        originalFilename = "report-$index.pdf",
        sourcePath = "/private/evidence/report-$index.pdf",
        sizeBytes = 1024L * (index + 1),
        status = DocumentStatus.COMPLETE,
        title = "Report $index",
        author = "Author $index",
        language = "en",
        createdAt = "2026-01-01T00:00:00.${"%03d".format(index)}Z",
        updatedAt = "2026-01-01T00:00:00.${"%03d".format(index)}Z",
    )

    /** One document with the exact facts a criterion test needs; the defaults are the common case. */
    private fun documentAt(
        id: String,
        collectionId: CollectionId,
        filename: String,
        status: DocumentStatus = DocumentStatus.COMPLETE,
        createdAt: String = "2026-01-01T00:00:00.000Z",
        sourcePath: String = "/private/evidence/$filename",
        errorCode: String? = null,
        errorMessage: String? = null,
    ): Document = Document(
        id = DocumentId(id),
        collectionId = collectionId,
        sha256 = "sha256-of-$id",
        mediaType = "application/pdf",
        originalFilename = filename,
        sourcePath = sourcePath,
        sizeBytes = 2048L,
        status = status,
        title = null,
        author = null,
        language = null,
        errorCode = errorCode,
        errorMessage = errorMessage,
        createdAt = createdAt,
        updatedAt = createdAt,
    )

    /** One committed unit, so a detail read has a real reader source to name; returns the unit's id. */
    private fun commitUnit(
        documentId: DocumentId,
        ordinal: Int,
        text: String,
        method: ExtractionMethod = ExtractionMethod.DIRECT_TEXT,
    ): String =
        harness.context.content.commitExtractedUnit(
            documentId = documentId,
            fingerprint = fingerprintOf(documentId),
            key = "unit-$ordinal",
            ordinal = ordinal,
            draft = ContentUnitDraft(
                locator = SourceLocation.PdfPage(ordinal + 1),
                extractedText = text,
                searchText = text,
                method = method,
            ),
            artifactRoot = harness.context.paths.libraryDir,
        ).unit.id.value

    /** The attempt these tests commit under: the one a document's own bytes and settings fingerprint. */
    private fun fingerprintOf(documentId: DocumentId): ExtractionFingerprint = ExtractionFingerprint.of(
        "sha256-of-${documentId.value}",
        ExtractionSettings(ocrLanguages = "eng", extractorSchemaVersion = DOCUMENTS_ROUTES_SCHEMA),
    )

    private suspend fun filenames(id: CollectionId, term: String): List<String> =
        page(id, "?q=${URLEncoder.encode(term, StandardCharsets.UTF_8)}").documents.map { it.originalFilename }

    private fun idsOf(response: DocumentsResponse): List<String> = response.documents.map { it.id.value }

    private companion object {
        /** One instant every tied document shares, so only the id can order them. */
        const val TIED_DATE = "2026-03-01T09:00:00.000Z"

        const val DOCUMENTS_ROUTES_SCHEMA = "documents-routes-test"

        /** A machine that has everything a retry could need, so admission is the archive's own answer. */
        fun availablePrerequisites(): suspend (Collection) -> RetryPrerequisites = { collection ->
            RetryPrerequisites(
                settings = ExtractionSettings(ocrLanguages = collection.ocrLanguages),
                ocrToolAvailable = true,
                ebookToolAvailable = true,
                embeddingModelAvailable = true,
            )
        }
    }
}