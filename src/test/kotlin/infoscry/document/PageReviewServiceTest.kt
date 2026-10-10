package infoscry.document

import infoscry.AppContext
import infoscry.domain.ExtractionMethod
import infoscry.extract.PdfPageRenderer
import infoscry.extract.PngPageRenderer
import infoscry.ocr.OcrEngine
import infoscry.server.PageReviewFixture
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.*

class PageReviewServiceTest {
    private lateinit var root: Path
    private lateinit var context: AppContext
    private lateinit var fixture: PageReviewFixture
    @BeforeTest fun setUp() {
        root = Files.createTempDirectory("infoscry-page-review-service")
        context = AppContext.open(root)
        fixture = PageReviewFixture(context, root)
    }
    @AfterTest fun tearDown() { context.close(); root.toFile().deleteRecursively() }

    @Test fun `metadata reads bounded text without rendering and original render retries converge atomically`() {
        val document = fixture.pdf()
        val revision = fixture.publish(document, listOf(fixture.page(1, "bounded page")))
        val original = context.library.managedPathOf(document)
        val originalBytes = Files.readAllBytes(original)
        var renders = 0
        val renderer = PdfPageRenderer { pdf, page, dpi, directory ->
            renders++
            val written = PngPageRenderer.render(pdf, page, dpi, directory)
            if (renders == 1) throw IOException("simulated interruption")
            written
        }
        val service = service(renderer)
        assertEquals("bounded", service.review(document.collectionId, document.id, 1, limit = 7).text)
        assertEquals(0, renders, "metadata must not render")
        assertFailsWith<PageReviewUnavailableException> { service.image(document.collectionId, document.id, 1, revision, "original") }
        val recovered = service.image(document.collectionId, document.id, 1, revision, "original")
        val repeated = service.image(document.collectionId, document.id, 1, revision, "original")
        assertEquals(recovered, repeated)
        assertEquals(2, renders, "the successful render is cached after one interrupted attempt")
        assertNotNull(ImageIO.read(recovered.path.toFile()))
        assertContentEquals(originalBytes, Files.readAllBytes(original))
        Files.walk(context.paths.artifactsDir(document.collectionId, document.id)).use { paths ->
            assertFalse(paths.anyMatch { it.fileName.toString().startsWith(".render-") }, "retry leaves no partial publication")
        }
    }

    @Test fun `a truncated cached raster is regenerated rather than served as a successful image`() {
        val document = fixture.pdf()
        var renders = 0
        val service = service(PdfPageRenderer { pdf, page, dpi, directory ->
            renders++
            PngPageRenderer.render(pdf, page, dpi, directory)
        })
        val generated = service.image(document.collectionId, document.id, 0, null, "original")
        Files.write(generated.path, Files.readAllBytes(generated.path).take(33).toByteArray())
        val repaired = service.image(document.collectionId, document.id, 0, null, "original")
        assertEquals(2, renders, "a header-only cached PNG must be regenerated")
        assertNotNull(ImageIO.read(repaired.path.toFile()))
    }

    @Test fun `OCR text with unknown provenance does not claim an image or configured method`() {
        val document = fixture.pdf()
        fixture.publish(document, listOf(fixture.page(0, "old OCR", ExtractionMethod.OCR)), OcrEngine.SURYA)
        val review = service().review(document.collectionId, document.id, 0)
        assertEquals("none", review.imageKind)
        assertNull(review.readingMethod)
        assertEquals("old OCR", review.text)
    }

    @Test fun `a recorded OCR artifact whose bytes changed becomes unavailable while its text is still readable`() {
        val document = fixture.pdf()
        val image = fixture.ocrImage(document)
        val revision = fixture.publish(document, listOf(fixture.page(0, "recorded OCR", ExtractionMethod.OCR, image)))
        Files.writeString(context.paths.artifactsDir(document.collectionId, document.id).resolve(image.relativePath), "changed")
        val service = service()
        assertEquals("recorded OCR", service.review(document.collectionId, document.id, 0, revision).text)
        val failure = assertFailsWith<PageReviewUnavailableException> {
            service.image(document.collectionId, document.id, 0, revision, "ocr")
        }
        assertEquals("PAGE_REVIEW_IMAGE_UNAVAILABLE", failure.code)
    }

    @Test fun `verified physical counts and generated renders survive original removal and reuse across service instances`() {
        val document = fixture.pdf()
        val revision = fixture.publish(document, listOf(fixture.page(0, "text remains")))
        val firstService = service()
        assertEquals(3, firstService.review(document.collectionId, document.id, 0).documentPages)
        val cached = firstService.image(document.collectionId, document.id, 0, revision, "original")
        Files.delete(context.library.managedPathOf(document))
        val reopened = service()
        val text = reopened.review(document.collectionId, document.id, 1, revision)
        assertEquals(3, text.documentPages)
        assertNull(text.unitId)
        assertEquals(cached, reopened.image(document.collectionId, document.id, 0, revision, "original"))
        assertFailsWith<PageReviewUnavailableException> {
            reopened.image(document.collectionId, document.id, 1, revision, "original")
        }
    }

    @Test fun `latest page method reads only its producer snapshot without unrelated operation history or accounting`() {
        val document = fixture.pdf()
        val old = fixture.publish(document, listOf(fixture.page(0, "older", ExtractionMethod.OCR, fixture.ocrImage(document))))
        operationForRevision(document, old, OcrEngine.TESSERACT, "older")
        val latest = fixture.publish(document, listOf(fixture.page(0, "latest", ExtractionMethod.OCR,
            fixture.ocrImage(document, "latest.png"))))
        operationForRevision(document, latest, OcrEngine.SURYA, "latest")
        context.database.transaction { connection ->
            connection.createStatement().use { sql ->
                sql.executeUpdate("UPDATE ocr_operations SET snapshot = 'unreadable unrelated history' WHERE request_id = 'older'")
                sql.execute("DROP TABLE ocr_external_pages")
                sql.execute("DROP TABLE ocr_external_calls")
            }
        }
        assertEquals("Surya", service().review(document.collectionId, document.id, 0).readingMethod)
    }

    @Test fun `restored page keeps historical producer method instead of the replaced revision method`() {
        val document = fixture.pdf()
        val old = fixture.publish(document, listOf(fixture.page(0, "historical", ExtractionMethod.OCR,
            fixture.ocrImage(document))), OcrEngine.TESSERACT)
        val latest = fixture.publish(document, listOf(fixture.page(0, "newer", ExtractionMethod.OCR,
            fixture.ocrImage(document, "newer.png"))), OcrEngine.SURYA)
        val restore = context.revisions.stageRestore(document.collectionId, document.id, "restore-page", "a".repeat(64), latest, old)
        context.revisions.pages(restore.newRevisionId).forEach { page ->
            context.revisions.recordChunkVectors(restore.newRevisionId, page.ordinal,
                infoscry.embedding.TestDocumentEmbedder().embedDocuments(listOf(page.extractedText)))
        }
        kotlinx.coroutines.runBlocking { context.revisionPublication.publish(document.id, latest, restore.newRevisionId) }
        val restored = service().review(document.collectionId, document.id, 0)
        assertEquals(old, context.revisions.page(restore.newRevisionId, 0)?.producerRevisionId)
        assertEquals("historical", restored.text)
        assertEquals("Tesseract", restored.readingMethod)
    }

    @Test fun `metadata slices OCR images and cached originals avoid repeated original hash and page census`() {
        val document = fixture.pdf()
        val revision = fixture.publish(document, listOf(fixture.page(0, "long enough for paging", ExtractionMethod.OCR,
            fixture.ocrImage(document))))
        var hashes = 0
        var censuses = 0
        val source = object : infoscry.extract.PageCounter {
            override fun pageCount(path: Path): Int? = error("stored media type must be used")
            override fun pageCount(path: Path, mediaType: String): Int? {
                censuses++
                return infoscry.extract.DefaultPageCounter().pageCount(path, mediaType)
            }
        }
        fun reader() = PageReviewService(context.paths, context.documents, context.revisions, context.library,
            context.ocrOperations, { context.ocrProfiles.findRevision(it) }, pageCounter = source,
            originalSha256Of = { path ->
                hashes++
                java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)))
            })
        val service = reader()
        repeat(3) { offset -> service.review(document.collectionId, document.id, 0, revision, offset, 2) }
        repeat(2) { service.image(document.collectionId, document.id, 0, revision, "ocr") }
        assertEquals(1, hashes, "physical count verifies managed bytes once, not once per slice or OCR image")
        assertEquals(1, censuses)
        service.image(document.collectionId, document.id, 0, revision, "original")
        assertEquals(2, hashes, "a missing original-page render verifies its own source before generation")
        repeat(2) { service.image(document.collectionId, document.id, 0, revision, "original") }
        reader().review(document.collectionId, document.id, 0, revision)
        assertEquals(2, hashes, "verified generated artifacts and persisted counts do not reread the original")
        assertEquals(1, censuses)
    }

    @Test fun `an unchanged invalid original is not rehashed on every unknown-total text slice and replacement can restore the census`() {
        val document = fixture.pdf()
        val revision = fixture.publish(document, listOf(fixture.page(0, "saved text", ExtractionMethod.OCR,
            fixture.ocrImage(document))))
        val original = context.library.managedPathOf(document)
        val intact = Files.readAllBytes(original)
        Files.writeString(original, "changed original")
        var hashes = 0
        val reader = PageReviewService(context.paths, context.documents, context.revisions, context.library,
            context.ocrOperations, { context.ocrProfiles.findRevision(it) }, originalSha256Of = { path ->
                hashes++
                java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)))
            })
        repeat(3) { offset -> assertNull(reader.review(document.collectionId, document.id, 0, revision, offset, 1).documentPages) }
        assertEquals(1, hashes, "unchanged invalid original must not cost its full byte size for every text slice")
        Files.write(original, intact)
        assertEquals(3, reader.review(document.collectionId, document.id, 0, revision).documentPages)
        assertEquals(2, hashes, "a changed original identity permits census recovery")
    }

    private fun operationForRevision(document: infoscry.domain.Document, revision: String, engine: OcrEngine, request: String) {
        val operation = context.ocrOperations.admit(document.collectionId.value, document.id, null,
            infoscry.ocr.OcrSettingsSnapshot(engine, infoscry.ocr.OcrImportMode.FILL_MISSING, "eng", "test"),
            request, "a".repeat(64), 1, null)
        context.ocrOperations.recordCandidateRevision(operation.operationId, revision)
        context.database.transaction { connection ->
            connection.prepareStatement("UPDATE ocr_operations SET stage = 'COMPLETE' WHERE operation_id = ?").use {
                it.setString(1, operation.operationId); it.executeUpdate()
            }
        }
    }

    private fun service(draw: PdfPageRenderer = PngPageRenderer) = PageReviewService(
        context.paths, context.documents, context.revisions, context.library, context.ocrOperations,
        { context.ocrProfiles.findRevision(it) }, draw,
    )
}
