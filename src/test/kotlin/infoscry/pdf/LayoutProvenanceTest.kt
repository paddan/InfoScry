package infoscry.pdf

import infoscry.AppContext
import infoscry.chunk.Chunker
import infoscry.chunk.WhitespaceTokenCounter
import infoscry.document.RescanPage
import infoscry.domain.ContentUnitId
import infoscry.domain.Document
import infoscry.domain.DocumentId
import infoscry.domain.ExtractionMethod
import infoscry.domain.SourceLocation
import infoscry.embedding.TestDocumentEmbedder
import infoscry.extract.TextNormalizer
import infoscry.jobs.JobStage
import infoscry.jobs.RescanJobHandler
import infoscry.jobs.RescanJobPayload
import infoscry.ocr.CandidateRevisionPhases
import infoscry.ocr.OcrEngine
import infoscry.ocr.OcrImportMode
import infoscry.ocr.OcrPageResult
import infoscry.ocr.OcrSettingsSnapshot
import infoscry.ocr.OcrWordBox
import infoscry.ocr.PageImage
import infoscry.ocr.PageOcrEngine
import infoscry.server.PageReviewFixture
import infoscry.storage.PageApproval
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

/**
 * Layout artifacts and revision provenance (ticket 04 of the selectable-PDF plan): a layout is bound to the
 * *final, normalized* text a page commits, unselected/failed pages and Restore inherit a matching layout and
 * producer without ever fabricating one from equal bytes, and the write/commit boundary converges under
 * interruption without a repeated successful transcription.
 */
class LayoutProvenanceTest {

    private lateinit var root: Path
    private lateinit var context: AppContext
    private lateinit var fixture: PageReviewFixture
    private lateinit var document: Document
    private lateinit var image: PageImage
    private lateinit var phases: CandidateRevisionPhases

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("infoscry-layout-provenance")
        context = AppContext.open(root)
        fixture = PageReviewFixture(context, root)
        document = fixture.pdf(count = 1)
        image = realPageImage(document)
        phases = CandidateRevisionPhases(
            revisions = context.revisions,
            chunker = Chunker(WhitespaceTokenCounter(prefixTokens = 2, specialTokens = 1)),
            operations = null,
            publication = context.revisionPublication,
        )
    }

    @AfterTest
    fun tearDown() {
        context.close()
        root.toFile().deleteRecursively()
    }

    // ---- normalization binding (acceptance vector 1) ----

    @Test
    fun `a layout is bound to the final normalized text, not the engine's raw reading`() = runBlocking {
        val raw = "  Åsa betalade 120 kronor.  \u0000"
        val reading = OcrPageResult(raw, engine = OcrEngine.TESSERACT, imageSha256 = image.sha256, boxes = emptyList())
        val staged = phases.acceptedPageOf(
            page = rescanPage(image, baseline = null),
            reading = reading,
            engine = OcrEngine.TESSERACT,
            languages = "eng",
            positioningEngine = null,
            documentArtifactRoot = artifactRoot(),
        )
        val reference = assertNotNull(staged.layoutReference, "an image was given, so some layout — complete or not — must be built")
        val normalizedExpected = TextNormalizer.normalize(raw).extracted
        val layout = PageTextLayoutStore.read(artifactRoot(), reference, normalizedExpected)
        assertEquals(PdfModelRules.sha256Of(normalizedExpected), layout.textSha256)
        // The raw provider string must never be what the layout is verifiable against.
        kotlin.test.assertFailsWith<PageTextLayoutProvenanceException> {
            PageTextLayoutStore.read(artifactRoot(), reference, raw)
        }
    }

    @Test
    fun `Tesseract reuses its own TSV boxes, never invoking a separate positioning pass`() = runBlocking {
        val text = "hello world"
        val anchors = listOf(OcrWordBox("hello", 4, 5, 20, 10), OcrWordBox("world", 28, 5, 20, 10))
        val reading = OcrPageResult(text, engine = OcrEngine.TESSERACT, imageSha256 = image.sha256, boxes = anchors)
        val secondary = CountingPositioningEngine()
        val staged = phases.acceptedPageOf(
            page = rescanPage(image, baseline = null),
            reading = reading,
            engine = OcrEngine.TESSERACT,
            languages = "eng",
            positioningEngine = secondary,
            documentArtifactRoot = artifactRoot(),
        )
        assertEquals(0, secondary.calls, "Tesseract's own reading already has boxes; no separate pass is needed")
        val reference = assertNotNull(staged.layoutReference)
        val layout = PageTextLayoutStore.read(artifactRoot(), reference, text)
        assertEquals(true, layout.complete, "both words are unique anchors, so alignment should fully place them")
    }

    @Test
    fun `Surya's reading uses a separate local Tesseract-like pass to position its text`() = runBlocking {
        val text = "hello world"
        val anchors = listOf(OcrWordBox("hello", 4, 5, 20, 10), OcrWordBox("world", 28, 5, 20, 10))
        // Surya's own reading carries no boxes at all.
        val reading = OcrPageResult(text, engine = OcrEngine.SURYA, imageSha256 = image.sha256, boxes = emptyList())
        val secondary = CountingPositioningEngine(anchors)
        val staged = phases.acceptedPageOf(
            page = rescanPage(image, baseline = null),
            reading = reading,
            engine = OcrEngine.SURYA,
            languages = "eng",
            positioningEngine = secondary,
            documentArtifactRoot = artifactRoot(),
        )
        assertEquals(1, secondary.calls, "Surya's text has no boxes, so the local pass must run exactly once")
        val reference = assertNotNull(staged.layoutReference)
        val layout = PageTextLayoutStore.read(artifactRoot(), reference, text)
        assertEquals(true, layout.complete)
    }

    @Test
    fun `no local Tesseract pass available records an incomplete layout, never a failed page`() = runBlocking {
        val text = "hello world"
        val reading = OcrPageResult(text, engine = OcrEngine.SURYA, imageSha256 = image.sha256, boxes = emptyList())
        val staged = phases.acceptedPageOf(
            page = rescanPage(image, baseline = null),
            reading = reading,
            engine = OcrEngine.SURYA,
            languages = "eng",
            positioningEngine = null,
            documentArtifactRoot = artifactRoot(),
        )
        assertEquals(text, staged.text, "a missing positioning pass must not affect the accepted page text")
        // With PDF geometry present but no anchors to align against, the aligner reports an honest incomplete
        // layout (no spans, a safe reason) rather than nothing at all — ticket 04's acceptance vector 6 and
        // CONTRACTS.md's "page's complete = false with a reason code" are both explicit that missing/ambiguous
        // positioning is *recorded*, not silently dropped.
        val reference = assertNotNull(staged.layoutReference)
        val layout = PageTextLayoutStore.read(artifactRoot(), reference, text)
        assertEquals(false, layout.complete)
        assertEquals(emptyList(), layout.spans)
    }

    // ---- selected-page and unselected/failed-page inheritance (acceptance vector 4) ----

    @Test
    fun `a page whose new reading matches its baseline text inherits the baseline's layout without recomputing`() = runBlocking {
        val text = "unchanged text"
        val baselineLayout = writeLayout(text, complete = true)
        val baseline = fixture.page(0, text, ExtractionMethod.OCR, null).copy(layoutReference = baselineLayout)
        val secondary = CountingPositioningEngine()
        val staged = phases.acceptedPageOf(
            page = rescanPage(image, baseline = toPageText(baseline)),
            reading = OcrPageResult(text, engine = OcrEngine.SURYA, imageSha256 = image.sha256),
            engine = OcrEngine.SURYA,
            languages = "eng",
            positioningEngine = secondary,
            documentArtifactRoot = artifactRoot(),
        )
        assertEquals(baselineLayout, staged.layoutReference, "identical bytes inherit the existing layout exactly")
        assertEquals(0, secondary.calls, "an unchanged page must not be repositioned")
    }

    @Test
    fun `a page whose new reading matches its baseline but the baseline carried no layout stays without one`() = runBlocking {
        val text = "unchanged text, never positioned"
        val baseline = fixture.page(0, text, ExtractionMethod.OCR, null)
        val secondary = CountingPositioningEngine(listOf(OcrWordBox("unchanged", 0, 0, 10, 10)))
        val staged = phases.acceptedPageOf(
            page = rescanPage(image, baseline = toPageText(baseline)),
            reading = OcrPageResult(text, engine = OcrEngine.SURYA, imageSha256 = image.sha256),
            engine = OcrEngine.SURYA,
            languages = "eng",
            positioningEngine = secondary,
            documentArtifactRoot = artifactRoot(),
        )
        assertNull(staged.layoutReference, "identical bytes must never fabricate a layout nobody positioned")
        assertEquals(0, secondary.calls)
    }

    // ---- Restore inheritance (acceptance vector 4), through the real SQL copy path ----

    @Test
    fun `Restore copies a page's layout reference exactly, through DocumentRevisionStore`() {
        val text = "restorable page"
        val layout = writeLayout(text, complete = true)
        val draft = fixture.page(0, text, ExtractionMethod.OCR, null).copy(layoutReference = layout)
        val published = fixture.publish(document, listOf(draft), OcrEngine.TESSERACT)
        // Change the active revision so the historical one is restorable.
        val secondDraft = fixture.page(0, "a newer reading", ExtractionMethod.OCR, null)
        fixture.publish(document, listOf(secondDraft), OcrEngine.TESSERACT)

        val restore = context.revisions.stageRestore(
            document.collectionId, document.id, "restore-1", "a".repeat(64), context.revisions.activeRevisionId(document.id)!!, published,
        )
        val restoredPage = assertNotNull(context.revisions.page(restore.newRevisionId, 0))
        assertEquals(layout, restoredPage.layoutReference, "Restore must inherit the exact historical layout reference")
    }

    @Test
    fun `Restore of a page with no recorded layout stays without one, never inferring from equal text`() {
        val text = "never positioned"
        val draft = fixture.page(0, text, ExtractionMethod.OCR, null)
        val published = fixture.publish(document, listOf(draft), OcrEngine.TESSERACT)
        fixture.publish(document, listOf(fixture.page(0, "newer text", ExtractionMethod.OCR, null)), OcrEngine.TESSERACT)

        val restore = context.revisions.stageRestore(
            document.collectionId, document.id, "restore-2", "b".repeat(64), context.revisions.activeRevisionId(document.id)!!, published,
        )
        val restoredPage = assertNotNull(context.revisions.page(restore.newRevisionId, 0))
        assertNull(restoredPage.layoutReference)
    }

    @Test
    fun `a preserved page whose recorded producer carries a different layout is rejected`() {
        val text = "preserved page"
        val layoutA = writeLayout(text, complete = true)
        val layoutB = writeLayout(text, complete = false, reasonCode = "PDF_TEXT_ALIGNMENT_AMBIGUOUS")
        val origin = fixture.publish(document, listOf(fixture.page(0, text, ExtractionMethod.OCR, null).copy(layoutReference = layoutA)), OcrEngine.TESSERACT)
        val candidate = context.revisions.openCandidate(document.id, origin, "preserved")
        val originPage = assertNotNull(context.revisions.page(origin, 0))
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            context.revisions.appendPage(
                candidate,
                fixture.page(0, text, ExtractionMethod.OCR, null).copy(
                    unitId = originPage.unitId,
                    producerRevisionId = origin,
                    layoutReference = layoutB,
                ),
            )
        }
    }

    // ---- crash-boundary convergence (acceptance vector 5) ----

    @Test
    fun `interrupting after the layout artifact is written but before the page commits converges without a repeated successful read`() = runBlocking {
        val engine = CountingReadingEngine("stable text")
        val operation = operation(document)
        kotlin.test.assertFailsWith<CancellationException> {
            runRescan(document, engine, operation, afterStaged = { throw CancellationException("simulated crash after artifact write") })
        }
        assertEquals(1, engine.calls, "the interrupted attempt read the page exactly once")
        val candidateBefore = context.ocrOperations.operation(operation)?.candidateRevisionId
        // Nothing was committed for this page yet: the row the crash happened before does not exist.
        assertEquals(null, candidateBefore?.let { context.revisions.page(it, 0) })

        runRescan(document, engine, operation)
        assertEquals(2, engine.calls, "an uncommitted page is read again on resume; that is expected and not a double-commit")
        val candidate = assertNotNull(context.ocrOperations.operation(operation)?.candidateRevisionId)
        val committed = assertNotNull(context.revisions.page(candidate, 0))
        assertNotNull(committed.layoutReference, "the resumed attempt must finish with a committed layout reference")

        // Running the resumed attempt a second time converges: still no further read, same committed state.
        runRescan(document, engine, operation)
        assertEquals(2, engine.calls, "a page already committed under this candidate must not be read a third time")
        val convergedAgain = assertNotNull(context.revisions.page(candidate, 0))
        assertEquals(committed.layoutReference, convergedAgain.layoutReference)
    }

    @Test
    fun `interrupting after the page is committed resumes without reading or repositioning it again`() = runBlocking {
        val engine = CountingReadingEngine("stable text")
        val operation = operation(document)
        kotlin.test.assertFailsWith<CancellationException> {
            runRescan(document, engine, operation, afterCommitted = { throw CancellationException("simulated crash after commit") })
        }
        assertEquals(1, engine.calls)
        val candidate = assertNotNull(context.ocrOperations.operation(operation)?.candidateRevisionId)
        val firstCommit = assertNotNull(context.revisions.page(candidate, 0))

        // Resume twice; a committed page must never be read or repositioned again.
        runRescan(document, engine, operation)
        runRescan(document, engine, operation)
        assertEquals(1, engine.calls, "a committed page must not be dispatched to the engine again")
        val afterResume = assertNotNull(context.revisions.page(candidate, 0))
        assertEquals(firstCommit.layoutReference, afterResume.layoutReference, "convergence: the resumed layout reference is unchanged")
    }

    // ---- fixtures ----

    private fun artifactRoot(): Path = context.paths.artifactsDir(document.collectionId, document.id)

    private fun realPageImage(document: Document): PageImage {
        val path = Files.createDirectories(context.paths.artifactsDir(document.collectionId, document.id)).resolve("page-000001.png")
        ImageIO.write(BufferedImage(IMAGE_WIDTH, IMAGE_HEIGHT, BufferedImage.TYPE_INT_RGB), "png", path.toFile())
        return PageImage(
            documentId = document.id,
            unitId = "page:1",
            ordinal = 0,
            imageRoot = path.parent,
            imageReference = path.fileName.toString(),
            artifactRoot = path.parent,
            sha256 = sha256Of(path),
            width = IMAGE_WIDTH,
            height = IMAGE_HEIGHT,
            renderDpi = 150,
            rotationDegrees = 0,
            pdfGeometry = PAGE_GEOMETRY,
            pdfTransform = PAGE_TRANSFORM,
        )
    }

    private fun rescanPage(image: PageImage, baseline: infoscry.storage.RevisionPageText?) = RescanPage(
        ordinal = 0, unitId = "page:1", image = image, baseline = baseline, read = true,
    )

    private fun toPageText(draft: infoscry.storage.RevisionPageDraft): infoscry.storage.RevisionPageText =
        infoscry.storage.RevisionPageText(
            revisionId = "synthetic", ordinal = draft.ordinal, unitId = draft.unitId, locator = draft.locator,
            extractedText = draft.extractedText, searchText = draft.searchText, textSha256 = null,
            extractionMethod = draft.extractionMethod, meanConfidence = draft.meanConfidence,
            artifactRelativePath = draft.artifactRelativePath, artifactSha256 = draft.artifactSha256,
            approval = draft.approval, sourceImage = draft.sourceImage, producerRevisionId = draft.producerRevisionId,
            layoutReference = draft.layoutReference,
        )

    private fun writeLayout(text: String, complete: Boolean, reasonCode: String? = null): LayoutReference =
        PageTextLayoutStore.write(artifactRoot(), layoutOf(text = text, complete = complete, reasonCode = reasonCode, spans = if (complete) completeWords() else emptyList()))

    private fun snapshot(renderDpi: Int? = null) = OcrSettingsSnapshot(
        OcrEngine.SURYA, OcrImportMode.FILL_MISSING, "eng", "test", runtimeIdentity = "test-page-engine", renderDpi = renderDpi,
    )

    private fun operation(document: Document): String = context.ocrOperations.admit(
        document.collectionId.value, document.id, context.revisions.activeRevisionId(document.id), snapshot(),
        "reading-${java.util.UUID.randomUUID()}", "a".repeat(64), 1, null, managedSha256 = document.sha256,
    ).operationId

    private fun runRescan(
        document: Document,
        engine: PageOcrEngine,
        operation: String,
        afterStaged: suspend () -> Unit = {},
        afterCommitted: suspend () -> Unit = {},
    ) = runBlocking {
        val job = context.jobs.enqueue(
            infoscry.domain.JobType.RESCAN, document.collectionId,
            RescanJobPayload(document.collectionId.value, document.id.value, operation).encode(), total = 1,
        )
        val claimed = assertNotNull(context.jobs.claim(job.id))
        val handler = RescanJobHandler(
            context.paths, context.collections, context.documents, context.library, context.mutations, context.jobs,
            context.revisions, context.ocrOperations, context.revisionPublication,
            Chunker(WhitespaceTokenCounter(prefixTokens = 2, specialTokens = 1)), { TestDocumentEmbedder() },
            { _, _, _ -> engine }, { context.ocrProfiles.findRevision(it) },
            afterPageCommitted = { _, _ -> afterCommitted() },
            afterPageStaged = { _, _ -> afterStaged() },
        )
        handler.handle(claimed, JobStage(claimed.id, context.jobs, context.collections, context.mutations))
        context.jobs.complete(claimed.id)
    }

    private fun sha256Of(path: Path): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)))

    /** A positioning-only engine: records how often it is asked, and answers fixed anchors or none. */
    private class CountingPositioningEngine(private val anchors: List<OcrWordBox>? = null) : PageOcrEngine {
        override val engine = OcrEngine.TESSERACT
        var calls = 0
        override suspend fun transcribe(page: PageImage, settings: OcrSettingsSnapshot): OcrPageResult =
            throw UnsupportedOperationException("this fake is for positionAnchors only")
        override suspend fun positionAnchors(page: PageImage, languages: String): List<OcrWordBox>? {
            calls++
            return anchors
        }
    }

    /** A reading engine that counts every dispatched call, for crash/resume convergence checks. */
    private class CountingReadingEngine(private val text: String) : PageOcrEngine {
        override val engine = OcrEngine.SURYA
        var calls = 0
        override suspend fun runtimeIdentity(): String = "test-page-engine"
        override suspend fun transcribe(page: PageImage, settings: OcrSettingsSnapshot): OcrPageResult {
            calls++
            return OcrPageResult(text, engine = engine, imageSha256 = page.sha256)
        }
    }

    private companion object {
        const val IMAGE_WIDTH = 100
        const val IMAGE_HEIGHT = 50
    }
}
