package infoscry.pdf

import infoscry.AppContext
import infoscry.chunk.Chunker
import infoscry.chunk.WhitespaceTokenCounter
import infoscry.domain.CollectionId
import infoscry.domain.DocumentId
import infoscry.domain.ExtractionMethod
import infoscry.domain.JobType
import infoscry.embedding.TestDocumentEmbedder
import infoscry.jobs.JobStage
import infoscry.jobs.RescanJobHandler
import infoscry.jobs.RescanJobPayload
import infoscry.jobs.seedCollectionWithId
import infoscry.ocr.OcrEngine
import infoscry.ocr.OcrImportMode
import infoscry.ocr.OcrPageResult
import infoscry.ocr.OcrSettingsSnapshot
import infoscry.ocr.OcrWordBox
import infoscry.ocr.PageImage
import infoscry.ocr.PageOcrEngine
import infoscry.ocr.RescanSelection
import infoscry.pdf.SelectablePdfTestFixtures.EMBEDDER
import infoscry.pdf.SelectablePdfTestFixtures.PAGE_GEOMETRY
import infoscry.pdf.SelectablePdfTestFixtures.runNextSelectablePdfJob
import infoscry.pdf.WriterFixtures.glyphs
import infoscry.pdf.WriterFixtures.sha256
import infoscry.pdf.WriterFixtures.text
import infoscry.pdf.WriterFixtures.writePng
import infoscry.pdf.WriterFixtures.writeSourcePdf
import infoscry.storage.PageApproval
import infoscry.storage.RevisionChunkDraft
import infoscry.storage.RevisionPageDraft
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.Loader

/**
 * Integrated acceptance for the selectable-PDF feature (ticket 09 of the selectable-PDF plan), run
 * through the real local job pipeline rather than unit mocks.
 *
 * Every assertion here independently re-opens the actual produced bytes with PDFBox and reads them back
 * — this file never trusts the service's or writer's own report of what it wrote. It is scoped to what
 * [docs/implementation-status.md] calls the "automatable acceptance vectors
 * using fake/local providers": no Tesseract/Surya/LLM/CoreML process is named, configured or imported
 * anywhere below. Crash/retry/deletion/late-completion convergence (acceptance vector 3) is already
 * covered, through the same real pipeline, by [SelectablePdfRecoveryTest] and [SelectablePdfServiceTest];
 * this file adds what those do not: independent byte-level inspection, and the selected-page rescan /
 * Restore identity sequence (acceptance vector 2).
 */
class SelectablePdfLifecycleTest {

    private lateinit var dataDir: Path

    @BeforeTest
    fun openDataDirectory() {
        dataDir = Files.createTempDirectory("infoscry-selectable-pdf-lifecycle")
        AppContext.open(dataDir).use { it.seedCollectionWithId("default", "Default") }
    }

    @AfterTest
    fun removeDataDirectory() {
        dataDir.toFile().deleteRecursively()
    }

    // ---- Acceptance vector 1: independent byte-level inspection of the real pipeline's output -------------------

    @Test
    fun `the real pipeline's output independently verifies text, self-consistent OCR positions, no duplicate layer, an unchanged original and every physical page, and repeat GET POST never duplicates the artifact`() {
        AppContext.open(dataDir).use { context ->
            val collectionId = CollectionId("default")
            val nativeText = "Native page one"
            val ocrText = "Scanned page two"
            val ocrInk = CropRect(72.0, 700.0, 200.0, 14.0)
            val sourcesDir = Files.createDirectories(dataDir.resolve("sources"))
            val source = sourcesDir.resolve("mixed-${System.nanoTime()}.pdf")
            writeSourcePdf(
                source,
                listOf(
                    SourcePage(PAGE_GEOMETRY, SourceContent.NativeText(listOf(TextRun(nativeText, 72.0, 700.0, 14.0)))),
                    SourcePage(PAGE_GEOMETRY, SourceContent.Scan(inks = listOf(ocrInk))),
                ),
            )
            val originalSha256Before = sha256(source)

            val documentId = runBlocking { context.library.importFile(collectionId, source) }.document.id
            val renderedImage: BufferedImage = WriterFixtures.render(source, 1, 200)
            val imageFile = Files.createDirectories(context.paths.artifactsDir(collectionId, documentId)).resolve("page-1.png")
            writePng(renderedImage, imageFile)
            // One LINE span over the whole sentence: the fixture's single ink box covers the full,
            // space-separated line, exactly as infoscry.pdf.SelectablePdfTestFixtures's own mixed fixture does.
            val layout = WriterFixtures.layoutFor(
                PAGE_GEOMETRY, ocrText,
                listOf(PlainSpan(0, ocrText.length, ocrInk, SpanGranularity.LINE)),
                imageFile, renderedImage.width, renderedImage.height,
            )
            val layoutReference = PageTextLayoutStore.write(context.paths, collectionId, documentId, layout)
            val sourceImage = infoscry.domain.SourceImageProvenance(
                root = infoscry.domain.SourceImageRoot.ARTIFACTS,
                relativePath = "page-1.png",
                sha256 = sha256(imageFile),
                width = renderedImage.width,
                height = renderedImage.height,
                renderVersion = 1,
            )

            val revisionId = context.revisions.openCandidate(documentId, null, "TEST_IMPORT")
            context.revisions.appendPage(
                revisionId,
                RevisionPageDraft(
                    ordinal = 0,
                    unitId = infoscry.domain.ContentUnitId.new(),
                    locator = infoscry.domain.SourceLocation.TextLines(1, 1),
                    extractedText = nativeText,
                    searchText = nativeText.lowercase(),
                    extractionMethod = ExtractionMethod.DIRECT_TEXT,
                    approval = PageApproval.APPROVED,
                    chunks = listOf(
                        RevisionChunkDraft(
                            ordinal = 0, text = nativeText, startOffset = 0, endOffset = nativeText.length,
                            tokenCount = 5, tokenStart = 0, tokenEnd = 4,
                            embedding = EMBEDDER.embedDocuments(listOf(nativeText)).single(),
                        ),
                    ),
                ),
            )
            context.revisions.appendPage(
                revisionId,
                RevisionPageDraft(
                    ordinal = 1,
                    unitId = infoscry.domain.ContentUnitId.new(),
                    locator = infoscry.domain.SourceLocation.TextLines(2, 2),
                    extractedText = ocrText,
                    searchText = ocrText.lowercase(),
                    extractionMethod = ExtractionMethod.OCR,
                    approval = PageApproval.APPROVED,
                    sourceImage = sourceImage,
                    layoutReference = layoutReference,
                    chunks = listOf(
                        RevisionChunkDraft(
                            ordinal = 0, text = ocrText, startOffset = 0, endOffset = ocrText.length,
                            tokenCount = 5, tokenStart = 0, tokenEnd = 4,
                            embedding = EMBEDDER.embedDocuments(listOf(ocrText)).single(),
                        ),
                    ),
                ),
            )
            runBlocking { context.revisionPublication.publish(documentId, null, revisionId) }

            runNextSelectablePdfJob(context)
            val ready = context.selectablePdfService.status(collectionId, documentId, revisionId)
            assertEquals(PdfArtifactState.READY, ready.state)
            assertEquals(2, ready.totalPages)
            assertEquals(2, ready.selectablePages, "both the native and the OCR page are selectable")
            val key = assertNotNull(ready.key)

            val file = context.selectablePdfService.file(collectionId, documentId, revisionId, key)
            val firstBytes = Files.readAllBytes(file)

            // Independent inspection: this test opens the produced bytes with PDFBox itself, not through
            // any production helper that already asserted the manifest it is checking.
            Loader.loadPDF(file.toFile()).use { pdf ->
                assertEquals(2, pdf.numberOfPages, "no physical page was lost")
            }
            assertEquals(nativeText, text(file, 0).trim().replace(Regex("\\s+"), " "), "the native page's exact text is copyable")
            val ocrExtracted = text(file, 1).trim().replace(Regex("\\s+"), " ")
            assertEquals(ocrText, ocrExtracted, "the OCR page's exact published text is copyable, once")
            assertFalse(ocrExtracted.contains(ocrText + ocrText), "no duplicate text layer")

            // Self-consistency (CONTRACTS.md, ticket 01's frozen tolerance): every written OCR glyph's box lies
            // inside its span's own rectangle, expanded by the writer's fixed 1.5pt inset — there is no
            // independent ground-truth OCR box to compare against, only the writer's own promise to itself.
            val ocrGlyphs = glyphs(file, 1)
            assertTrue(ocrGlyphs.isNotEmpty(), "the OCR page wrote a positioned glyph run")
            val inset = SelectablePdfWriter.CONTENT_INSET_POINTS
            for (glyph in ocrGlyphs) {
                assertTrue(
                    glyph.left >= ocrInk.left - inset && glyph.left + glyph.width <= ocrInk.left + ocrInk.width + inset,
                    "glyph '${glyph.char}' horizontal box [${glyph.left}, ${glyph.left + glyph.width}] " +
                        "exceeds its span's rectangle [${ocrInk.left}, ${ocrInk.left + ocrInk.width}] by more than ${inset}pt",
                )
                assertTrue(
                    glyph.baseline >= ocrInk.top - inset && glyph.baseline <= ocrInk.top + ocrInk.height + inset,
                    "glyph '${glyph.char}' baseline ${glyph.baseline} falls outside its span's rectangle " +
                        "[${ocrInk.top}, ${ocrInk.top + ocrInk.height}] by more than ${inset}pt",
                )
            }

            assertEquals(originalSha256Before, sha256(source), "the managed original is never modified by generation")
            val managedOriginal = context.library.managedPathOf(context.documents.get(documentId)!!)
            assertEquals(originalSha256Before, sha256(managedOriginal), "the managed copy used for generation is byte-identical to the imported original")

            // Repeat GET status and POST create/retry: no duplicate artifact file, no duplicate job.
            repeat(3) {
                val repeatedStatus = context.selectablePdfService.status(collectionId, documentId, revisionId)
                assertEquals(ready, repeatedStatus, "repeated GET status answers the same verified identity")
            }
            repeat(3) {
                val repeatedEnsure = context.selectablePdfService.ensure(collectionId, documentId, revisionId, retry = false)
                assertEquals(ready, repeatedEnsure, "repeated POST create answers the same verified identity")
            }
            val repeatedRetry = context.selectablePdfService.ensure(collectionId, documentId, revisionId, retry = true)
            assertEquals(ready, repeatedRetry, "a READY artifact is never rebuilt even when retry is explicitly requested")
            assertEquals(1, countSelectablePdfJobs(context), "no repeated GET/POST ever enqueues a second job")
            assertTrue(firstBytes.contentEquals(Files.readAllBytes(context.selectablePdfService.file(collectionId, documentId, revisionId, key))), "the served bytes never change across repeats")
        }
    }

    // ---- Acceptance vector 2: a selected-page rescan changes only its own layer; historical exports keep their
    //      own files; Restore inherits the restored revision's own layout, not the latest unrelated export -------

    @Test
    fun `a selected-page rescan with a counted fake engine changes only that page's export layer, never re-invokes the engine on repeat, and Restore inherits the restored revision's own layout`() {
        AppContext.open(dataDir, restoreEmbedder = { TestDocumentEmbedder() }).use { context ->
            val fixture = SelectablePdfTestFixtures.publishMixedNativeAndOcrPdf(
                context, dataDir, nativeText = "Native page one", ocrText = "Scanned page two",
            )
            runNextSelectablePdfJob(context)
            val readyA = context.selectablePdfService.status(fixture.collectionId, fixture.documentId, fixture.revisionId)
            assertEquals(PdfArtifactState.READY, readyA.state)
            val keyA = assertNotNull(readyA.key)
            val bytesA = Files.readAllBytes(
                context.selectablePdfService.file(fixture.collectionId, fixture.documentId, fixture.revisionId, keyA),
            )
            assertEquals("Scanned page two", text(
                context.selectablePdfService.file(fixture.collectionId, fixture.documentId, fixture.revisionId, keyA), 1,
            ).trim().replace(Regex("\\s+"), " "))

            // Rescan only physical page 1 (the OCR page) with a fake engine that returns its own word boxes —
            // exactly how Tesseract's reading already carries boxes (LayoutProvenanceTest) — so the new page
            // gets a genuinely complete, selectable layout without this test or the production path it drives
            // ever naming or configuring Tesseract, Surya or an LLM.
            val newOcrText = "Rescanned OCR reading"
            val engine = CountingWordBoxEngine(newOcrText)
            val document = assertNotNull(context.documents.get(fixture.documentId))
            val operationId = context.ocrOperations.admit(
                fixture.collectionId.value, fixture.documentId, fixture.revisionId,
                OcrSettingsSnapshot(OcrEngine.TESSERACT, OcrImportMode.FILL_MISSING, "eng", "test", renderDpi = 150),
                "reading-${UUID.randomUUID()}", "a".repeat(64), 1, null,
                selection = RescanSelection.explicit(listOf(1)), managedSha256 = document.sha256,
            ).operationId
            runRescan(context, document.collectionId, fixture.documentId, operationId, engine)
            assertEquals(1, engine.calls, "exactly the one selected page was dispatched to the fake engine")

            val revisionB = assertNotNull(context.revisions.activeRevisionId(fixture.documentId))
            assertNotEquals(fixture.revisionId, revisionB, "the rescan published a new revision")
            runNextSelectablePdfJob(context)
            val readyB = context.selectablePdfService.status(fixture.collectionId, fixture.documentId, revisionB)
            assertEquals(PdfArtifactState.READY, readyB.state, "the rescanned page's new boxes produce a complete, selectable layout")
            assertEquals(2, readyB.selectablePages)
            val keyB = assertNotNull(readyB.key)
            assertNotEquals(keyA, keyB, "each revision keeps its own artifact key")
            val fileB = context.selectablePdfService.file(fixture.collectionId, fixture.documentId, revisionB, keyB)
            assertEquals("Native page one", text(fileB, 0).trim().replace(Regex("\\s+"), " "), "the untouched native page's layer is unchanged")
            assertEquals(newOcrText, text(fileB, 1).trim().replace(Regex("\\s+"), " "), "only the rescanned page's layer reflects the new reading")

            // A's own historical export is completely unaffected by B.
            val stillA = context.selectablePdfService.status(fixture.collectionId, fixture.documentId, fixture.revisionId)
            assertEquals(readyA, stillA, "the historical revision's own artifact status is unchanged")
            val fileAAgain = context.selectablePdfService.file(fixture.collectionId, fixture.documentId, fixture.revisionId, keyA)
            assertTrue(bytesA.contentEquals(Files.readAllBytes(fileAAgain)), "A's own file is byte-for-byte unchanged by B's generation")

            // Repeating ensure/status for B never re-invokes the engine: generation never calls a transcription
            // provider, and this is proven here by an actual call counter, not merely by omission.
            repeat(3) { context.selectablePdfService.status(fixture.collectionId, fixture.documentId, revisionB) }
            repeat(3) { context.selectablePdfService.ensure(fixture.collectionId, fixture.documentId, revisionB, retry = false) }
            context.selectablePdfService.ensure(fixture.collectionId, fixture.documentId, revisionB, retry = true)
            assertEquals(1, engine.calls, "no repeated GET/POST against the export ever dispatches the engine again")

            // Restore A: the new current revision's own export must inherit A's own layout, not B's — and
            // must not reuse either A's or B's existing file under a new, unrelated identity.
            val restored = runBlocking {
                context.revisionRestore.restore(
                    fixture.collectionId, fixture.documentId, "restore-${UUID.randomUUID()}", revisionB, fixture.revisionId,
                )
            }
            val revisionC = restored.newRevisionId
            assertNotEquals(revisionB, revisionC)
            assertNotEquals(fixture.revisionId, revisionC)
            assertEquals(revisionC, context.revisions.activeRevisionId(fixture.documentId), "the restored reading is current")
            runNextSelectablePdfJob(context)
            val readyC = context.selectablePdfService.status(fixture.collectionId, fixture.documentId, revisionC)
            assertEquals(PdfArtifactState.READY, readyC.state)
            val keyC = assertNotNull(readyC.key)
            assertNotEquals(keyA, keyC, "a restored revision keeps its own distinct identity even though its content equals A's")
            assertNotEquals(keyB, keyC)
            val fileC = context.selectablePdfService.file(fixture.collectionId, fixture.documentId, revisionC, keyC)
            assertEquals("Native page one", text(fileC, 0).trim().replace(Regex("\\s+"), " "))
            assertEquals(
                "Scanned page two", text(fileC, 1).trim().replace(Regex("\\s+"), " "),
                "the restored current export carries A's own OCR reading, not B's unrelated 'Rescanned OCR reading'",
            )
            assertEquals(1, engine.calls, "Restore never re-invokes the transcription engine either")

            // B's own historical export is in turn unaffected by the restore.
            val stillB = context.selectablePdfService.status(fixture.collectionId, fixture.documentId, revisionB)
            assertEquals(readyB, stillB)
        }
    }

    // ---- helpers --------------------------------------------------------------------------------------------------

    private fun countSelectablePdfJobs(context: AppContext): Int =
        context.jobs.list(limit = 1000).count { it.type == JobType.SELECTABLE_PDF }

    private fun runRescan(
        context: AppContext,
        collectionId: CollectionId,
        documentId: DocumentId,
        operationId: String,
        engine: PageOcrEngine,
    ) {
        val job = context.jobs.enqueue(
            JobType.RESCAN, collectionId, RescanJobPayload(collectionId.value, documentId.value, operationId).encode(), total = 1,
        )
        val claimed = assertNotNull(context.jobs.claim(job.id))
        val handler = RescanJobHandler(
            context.paths, context.collections, context.documents, context.library, context.mutations, context.jobs,
            context.revisions, context.ocrOperations, context.revisionPublication,
            Chunker(WhitespaceTokenCounter(prefixTokens = 2, specialTokens = 1)), { TestDocumentEmbedder() },
            { _, _, _ -> engine }, { null },
        )
        runBlocking { handler.handle(claimed, JobStage(claimed.id, context.jobs, context.collections, context.mutations)) }
        context.jobs.complete(claimed.id)
    }

    /**
     * A local, Tesseract-shaped fake: it carries its own word boxes exactly as a real Tesseract reading
     * does (LayoutProvenanceTest), so the published text is positioned without any separate alignment
     * pass. Boxes are computed from the *real* rendered [PageImage] the real pipeline hands it, never
     * precomputed against an assumed image size, and it counts every dispatch so a test can prove
     * generation and its repeats never call it again.
     */
    private class CountingWordBoxEngine(private val newText: String) : PageOcrEngine {
        override val engine = OcrEngine.TESSERACT
        var calls = 0

        override suspend fun transcribe(page: PageImage, settings: OcrSettingsSnapshot): OcrPageResult {
            calls++
            val width = checkNotNull(page.width)
            val height = checkNotNull(page.height)
            val words = newText.split(" ")
            val slot = width / words.size
            var cursor = 0
            val boxes = words.map { word ->
                val box = OcrWordBox(word, cursor + 2, height / 2, (slot - 4).coerceAtLeast(1), (height / 8).coerceAtLeast(4))
                cursor += slot
                box
            }
            return OcrPageResult(newText, engine, page.sha256, boxes = boxes)
        }
    }
}
