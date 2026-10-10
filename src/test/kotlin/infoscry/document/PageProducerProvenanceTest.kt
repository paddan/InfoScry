package infoscry.document

import infoscry.AppContext
import infoscry.chunk.Chunker
import infoscry.chunk.WhitespaceTokenCounter
import infoscry.domain.*
import infoscry.embedding.TestDocumentEmbedder
import infoscry.jobs.JobStage
import infoscry.jobs.RescanJobHandler
import infoscry.jobs.RescanJobPayload
import infoscry.ocr.*
import infoscry.server.PageReviewFixture
import infoscry.storage.RevisionPageDraft
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

class PageProducerProvenanceTest {
    private lateinit var root: Path
    private lateinit var context: AppContext
    private lateinit var fixture: PageReviewFixture
    @BeforeTest fun setUp() {
        root = Files.createTempDirectory("infoscry-page-producer")
        context = AppContext.open(root)
        fixture = PageReviewFixture(context, root)
    }
    @AfterTest fun tearDown() { context.close(); root.toFile().deleteRecursively() }

    @Test fun `successful identical image and text reread uses the new engine rather than inferring preservation`() {
        val document = picture()
        val image = managedProvenance(document)
        fixture.publish(document, listOf(imagePage(document, "identical text", image)), OcrEngine.TESSERACT)
        runRescan(document, TestEngine("identical text"))
        assertEquals("identical text", context.pageReviewService.review(document.collectionId, document.id, 0).text)
        assertEquals("Surya", context.pageReviewService.review(document.collectionId, document.id, 0).readingMethod)
    }

    @Test fun `failed image reread retains the original producer method instead of the failed engine`() {
        val document = picture()
        val image = managedProvenance(document)
        fixture.publish(document, listOf(imagePage(document, "preserved text", image)), OcrEngine.TESSERACT)
        runRescan(document, TestEngine(fail = true))
        assertEquals("preserved text", context.pageReviewService.review(document.collectionId, document.id, 0).text)
        assertEquals("Tesseract", context.pageReviewService.review(document.collectionId, document.id, 0).readingMethod)
    }

    @Test fun `failed PDF reading preserves the exact historical source image and method`() {
        val document = fixture.pdf()
        val image = fixture.ocrImage(document)
        fixture.publish(document, listOf(fixture.page(0, "old PDF OCR", ExtractionMethod.OCR, image)), OcrEngine.TESSERACT)
        runRescan(document, TestEngine(fail = true))
        val active = assertNotNull(context.revisions.activeRevisionId(document.id))
        assertEquals(image, context.revisions.page(active, 0)?.sourceImage)
        assertEquals("Tesseract", context.pageReviewService.review(document.collectionId, document.id, 0).readingMethod)
    }

    @Test fun `a page without recorded producer remains unknown despite a configured revision snapshot`() {
        val document = picture()
        val candidate = context.revisions.openCandidate(document.id, null, "unknown producer", readingSnapshot = snapshot(OcrEngine.TESSERACT))
        context.revisions.appendPage(candidate, imagePage(document, "unknown producer", managedProvenance(document)))
        runBlocking { context.revisionPublication.publish(document.id, null, candidate) }
        assertNull(context.pageReviewService.review(document.collectionId, document.id, 0).readingMethod)
    }

    @Test fun `committed page survives interrupted attempt and resumes without another engine read`() {
        val document = picture()
        fixture.publish(document, listOf(imagePage(document, "same text", managedProvenance(document))), OcrEngine.TESSERACT)
        val engine = TestEngine("same text")
        val operation = operation(document)
        assertFailsWith<CancellationException> {
            runRescan(document, engine, operation, afterCommit = { throw CancellationException("simulated shutdown") })
        }
        val candidate = assertNotNull(context.ocrOperations.operation(operation)?.candidateRevisionId)
        assertEquals(candidate, context.revisions.page(candidate, 0)?.producerRevisionId)
        // A worker retry resumes the durable candidate. Full startup intentionally retires stale operations.
        runRescan(document, engine, operation)
        context.close()
        context = AppContext.open(root)
        assertEquals(candidate, context.revisions.page(candidate, 0)?.producerRevisionId)
        assertEquals(1, engine.calls, "committed reading must not be dispatched again")
        assertEquals("Surya", context.pageReviewService.review(document.collectionId, document.id, 0).readingMethod)
    }

    @Test fun `repeated append preserves producer and rejects foreign unpublished or mismatched origins atomically`() {
        val document = fixture.pdf()
        val page = fixture.page(0, "first producer", ExtractionMethod.OCR, fixture.ocrImage(document))
        val origin = fixture.publish(document, listOf(page), OcrEngine.TESSERACT)
        val candidate = context.revisions.openCandidate(document.id, origin, "preserved")
        val preserved = page.copy(unitId = assertNotNull(context.revisions.page(origin, 0)).unitId, producerRevisionId = origin)
        repeat(2) { context.revisions.appendPage(candidate, preserved) }
        assertEquals(origin, context.revisions.page(candidate, 0)?.producerRevisionId)
        val unrelated = picture()
        val foreign = context.revisions.openCandidate(unrelated.id, null, "foreign")
        val unpublished = context.revisions.openCandidate(document.id, origin, "not published")
        listOf(foreign, unpublished).forEach { invalid ->
            assertFailsWith<IllegalArgumentException> {
                context.revisions.appendPage(candidate, preserved.copy(producerRevisionId = invalid))
            }
        }
        assertFailsWith<IllegalArgumentException> {
            context.revisions.appendPage(candidate, preserved.copy(extractedText = "different output"))
        }
        assertEquals("first producer", context.revisions.page(candidate, 0)?.extractedText)
        assertEquals(origin, context.revisions.page(candidate, 0)?.producerRevisionId)
    }

    @Test fun `restore copies an unknown producer without using its source revision snapshot`() {
        val document = fixture.pdf()
        val old = context.revisions.openCandidate(document.id, null, "unknown", readingSnapshot = snapshot(OcrEngine.TESSERACT))
        context.revisions.appendPage(old, fixture.page(0, "legacy text", ExtractionMethod.OCR, fixture.ocrImage(document)))
        runBlocking { context.revisionPublication.publish(document.id, null, old) }
        val latest = fixture.publish(document, listOf(fixture.page(0, "new text", ExtractionMethod.OCR,
            fixture.ocrImage(document, "new.png"))), OcrEngine.SURYA)
        val restore = context.revisions.stageRestore(document.collectionId, document.id, "restore-unknown", "b".repeat(64), latest, old)
        assertNull(context.revisions.page(restore.newRevisionId, 0)?.producerRevisionId)
        context.revisions.recordChunkVectors(restore.newRevisionId, 0, TestDocumentEmbedder().embedDocuments(listOf("legacy text")))
        runBlocking { context.revisionPublication.publish(document.id, latest, restore.newRevisionId) }
        assertEquals(restore.newRevisionId, context.revisions.activeRevisionId(document.id))
        assertNull(context.pageReviewService.review(document.collectionId, document.id, 0).readingMethod)
    }

    @Test fun `later failed rescan at another DPI cannot overwrite a prior rescan image`() {
        val document = fixture.pdf(count = 1)
        runRescan(document, TestEngine("first reading"), operation(document, renderDpi = 90))
        val first = assertNotNull(context.revisions.activeRevisionId(document.id))
        val image = assertNotNull(context.revisions.page(first, 0)?.sourceImage)
        val path = context.paths.artifactsDir(document.collectionId, document.id).resolve(image.relativePath)
        val originalBytes = Files.readAllBytes(path)
        val firstImage = context.pageReviewService.image(document.collectionId, document.id, 0, first, "ocr")
        runRescan(document, TestEngine(fail = true), operation(document, renderDpi = 150))
        val latest = assertNotNull(context.revisions.activeRevisionId(document.id))
        assertNotEquals(first, latest)
        assertEquals(image, context.revisions.page(latest, 0)?.sourceImage)
        assertContentEquals(originalBytes, Files.readAllBytes(path), "another operation must not overwrite historical pixels")
        assertEquals(firstImage, context.pageReviewService.image(document.collectionId, document.id, 0, first, "ocr"))
        assertEquals(firstImage, context.pageReviewService.image(document.collectionId, document.id, 0, latest, "ocr"))
        assertEquals(first, context.revisions.page(latest, 0)?.producerRevisionId)
    }

    @Test fun `PDF checkpoint retry reuses its verified raster without rendering or reading again`() {
        val document = fixture.pdf(count = 1)
        val operation = operation(document, renderDpi = 90)
        val engine = TestEngine("committed reading")
        var renders = 0
        val pageSource = RescanPageSource(draw = infoscry.extract.PdfPageRenderer { renderer, page, dpi, directory ->
            renders++
            infoscry.extract.PngPageRenderer.render(renderer, page, dpi, directory)
        })
        assertFailsWith<CancellationException> {
            runRescan(document, engine, operation, pageSource = pageSource,
                afterCommit = { throw CancellationException("interrupted after checkpoint") })
        }
        val candidate = assertNotNull(context.ocrOperations.operation(operation)?.candidateRevisionId)
        val image = assertNotNull(context.revisions.page(candidate, 0)?.sourceImage)
        val path = context.paths.artifactsDir(document.collectionId, document.id).resolve(image.relativePath)
        val originalBytes = Files.readAllBytes(path)
        runRescan(document, engine, operation, pageSource = pageSource)
        assertEquals(1, renders, "a committed raster must not be generated again")
        assertEquals(1, engine.calls)
        assertEquals(candidate, context.revisions.activeRevisionId(document.id))
        assertContentEquals(originalBytes, Files.readAllBytes(path))
        context.pageReviewService.image(document.collectionId, document.id, 0, candidate, "ocr")
    }

    @Test fun `checkpoint retry repairs a changed raster before trusting its committed reading`() {
        val document = fixture.pdf(count = 1)
        val operation = operation(document, renderDpi = 90)
        val engine = TestEngine("committed reading")
        var renders = 0
        val pageSource = RescanPageSource(draw = infoscry.extract.PdfPageRenderer { renderer, page, dpi, directory ->
            renders++
            infoscry.extract.PngPageRenderer.render(renderer, page, dpi, directory)
        })
        assertFailsWith<CancellationException> {
            runRescan(document, engine, operation, pageSource = pageSource,
                afterCommit = { throw CancellationException("interrupted after checkpoint") })
        }
        val candidate = assertNotNull(context.ocrOperations.operation(operation)?.candidateRevisionId)
        val image = assertNotNull(context.revisions.page(candidate, 0)?.sourceImage)
        val path = context.paths.artifactsDir(document.collectionId, document.id).resolve(image.relativePath)
        val originalBytes = Files.readAllBytes(path)
        // Keep a valid image header/dimensions but replace the pixels, so hash verification is essential.
        ImageIO.write(BufferedImage(image.width!!, image.height!!, BufferedImage.TYPE_INT_RGB), "png", path.toFile())
        assertFalse(originalBytes.contentEquals(Files.readAllBytes(path)))
        runRescan(document, engine, operation, pageSource = pageSource)
        assertEquals(2, renders)
        assertEquals(1, engine.calls, "restoring the same raster must reuse the durable reading")
        assertContentEquals(originalBytes, Files.readAllBytes(path))
        context.pageReviewService.image(document.collectionId, document.id, 0, candidate, "ocr")
    }

    private fun picture(): Document {
        val source = Files.createDirectories(root.resolve("sources")).resolve("picture.png")
        ImageIO.write(BufferedImage(5, 7, BufferedImage.TYPE_INT_RGB), "png", source.toFile())
        val collection = context.collections.list().firstOrNull() ?: context.collections.create("Default")
        return context.library.importFile(collection.id, source).document
    }
    private fun managedProvenance(document: Document) = SourceImageProvenance(
        SourceImageRoot.MANAGED_COPY, context.library.managedPathOf(document).fileName.toString(), document.sha256, 5, 7, 1,
    )
    private fun imagePage(document: Document, text: String, image: SourceImageProvenance): RevisionPageDraft =
        fixture.page(0, text, ExtractionMethod.OCR, image).copy(locator = SourceLocation.Image(document.originalFilename))

    private fun snapshot(engine: OcrEngine = OcrEngine.SURYA, renderDpi: Int? = null) = OcrSettingsSnapshot(engine,
        OcrImportMode.FILL_MISSING, "eng", "test", runtimeIdentity = "test-page-engine", renderDpi = renderDpi)
    private fun operation(document: Document, renderDpi: Int? = null): String = context.ocrOperations.admit(
        document.collectionId.value, document.id, context.revisions.activeRevisionId(document.id), snapshot(renderDpi = renderDpi),
        "reading-${java.util.UUID.randomUUID()}", "a".repeat(64), 1, null, managedSha256 = document.sha256,
    ).operationId

    private fun runRescan(document: Document, engine: TestEngine, operation: String = operation(document),
                          afterCommit: suspend () -> Unit = {}, pageSource: RescanPageSource = RescanPageSource()) = runBlocking {
        val job = context.jobs.enqueue(JobType.RESCAN, document.collectionId,
            RescanJobPayload(document.collectionId.value, document.id.value, operation).encode(), total = 1)
        val claimed = assertNotNull(context.jobs.claim(job.id))
        val handler = RescanJobHandler(context.paths, context.collections, context.documents, context.library,
            context.mutations, context.jobs, context.revisions, context.ocrOperations, context.revisionPublication,
            Chunker(WhitespaceTokenCounter(prefixTokens = 2, specialTokens = 1)), { TestDocumentEmbedder() },
            { _, _, _ -> engine }, { context.ocrProfiles.findRevision(it) }, pages = pageSource,
            afterPageCommitted = { _, _ -> afterCommit() })
        handler.handle(claimed, JobStage(claimed.id, context.jobs, context.collections, context.mutations))
        context.jobs.complete(claimed.id)
    }
    private class TestEngine(private val text: String = "", private val fail: Boolean = false) : PageOcrEngine {
        override val engine = OcrEngine.SURYA
        var calls = 0
        override suspend fun runtimeIdentity(): String = "test-page-engine"
        override suspend fun transcribe(page: PageImage, settings: OcrSettingsSnapshot): OcrPageResult {
            calls++
            if (fail) throw java.io.IOException("synthetic page failure")
            return OcrPageResult(text, engine = engine, imageSha256 = page.sha256)
        }
    }
}
