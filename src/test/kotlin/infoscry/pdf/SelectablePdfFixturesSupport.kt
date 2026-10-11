package infoscry.pdf

import infoscry.AppContext
import infoscry.domain.CollectionId
import infoscry.domain.ContentUnitId
import infoscry.domain.DocumentId
import infoscry.domain.ExtractionMethod
import infoscry.domain.SourceImageProvenance
import infoscry.domain.SourceImageRoot
import infoscry.domain.SourceLocation
import infoscry.embedding.TestDocumentEmbedder
import infoscry.jobs.JobStage
import infoscry.jobs.SelectablePdfJobHandler
import infoscry.storage.PageApproval
import infoscry.storage.RevisionChunkDraft
import infoscry.storage.RevisionPageDraft
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertNotNull
import kotlinx.coroutines.runBlocking

/** Shared test support for ticket 06's service/recovery suites: fixtures and a manual job-run helper. */
internal object SelectablePdfTestFixtures {

    val EMBEDDER = TestDocumentEmbedder()

    val PAGE_GEOMETRY: PdfPageGeometry = WriterFixtures.geometry(612.0 to 792.0, CropRect(0.0, 0.0, 612.0, 792.0))

    data class Fixture(val collectionId: CollectionId, val documentId: DocumentId, val revisionId: String)

    /** Imports a one-page native-text PDF and publishes it as the document's first revision. */
    fun publishNativePdf(
        context: AppContext,
        dataDir: Path,
        text: String = "Hello selectable world",
        collectionId: CollectionId = CollectionId("default"),
    ): Fixture {
        val sourcesDir = Files.createDirectories(dataDir.resolve("sources"))
        val source = sourcesDir.resolve("native-${System.nanoTime()}.pdf")
        WriterFixtures.writeSourcePdf(
            source,
            listOf(SourcePage(PAGE_GEOMETRY, SourceContent.NativeText(listOf(TextRun(text, 72.0, 700.0, 14.0))))),
        )
        val documentId = runBlocking { context.library.importFile(collectionId, source) }.document.id
        val revisionId = context.revisions.openCandidate(documentId, null, "TEST_IMPORT")
        context.revisions.appendPage(
            revisionId,
            RevisionPageDraft(
                ordinal = 0,
                unitId = ContentUnitId.new(),
                locator = SourceLocation.TextLines(1, 1),
                extractedText = text,
                searchText = text.lowercase(),
                extractionMethod = ExtractionMethod.DIRECT_TEXT,
                approval = PageApproval.APPROVED,
                chunks = listOf(
                    RevisionChunkDraft(
                        ordinal = 0, text = text, startOffset = 0, endOffset = text.length,
                        tokenCount = 5, tokenStart = 0, tokenEnd = 4,
                        embedding = EMBEDDER.embedDocuments(listOf(text)).single(),
                    ),
                ),
            ),
        )
        runBlocking { context.revisionPublication.publish(documentId, null, revisionId) }
        return Fixture(collectionId, documentId, revisionId)
    }

    /**
     * Imports a two-page PDF whose first page is native text and whose second page is a scanned page
     * with a complete local layout, and publishes it. The OCR page proves a mixed-mode build without
     * ever naming, importing or configuring an OCR engine anywhere in this fixture or the production
     * code it exercises: if selectable-PDF generation ever needed one, this would fail for lack of one.
     */
    fun publishMixedNativeAndOcrPdf(
        context: AppContext,
        dataDir: Path,
        nativeText: String = "Native page one",
        ocrText: String = "Scanned page two",
        collectionId: CollectionId = CollectionId("default"),
    ): Fixture {
        val sourcesDir = Files.createDirectories(dataDir.resolve("sources"))
        val source = sourcesDir.resolve("mixed-${System.nanoTime()}.pdf")
        val ocrInk = CropRect(72.0, 700.0, 200.0, 14.0)
        WriterFixtures.writeSourcePdf(
            source,
            listOf(
                SourcePage(PAGE_GEOMETRY, SourceContent.NativeText(listOf(TextRun(nativeText, 72.0, 700.0, 14.0)))),
                SourcePage(PAGE_GEOMETRY, SourceContent.Scan(inks = listOf(ocrInk))),
            ),
        )
        val documentId = runBlocking { context.library.importFile(collectionId, source) }.document.id
        val renderedImage: BufferedImage = WriterFixtures.render(source, 1, 200)
        val imageFile = Files.createDirectories(context.paths.artifactsDir(collectionId, documentId)).resolve("page-1.png")
        WriterFixtures.writePng(renderedImage, imageFile)
        val imageSha256 = WriterFixtures.sha256(imageFile)
        // One LINE span over the whole sentence: wordSpans would build a WORD span, which may not
        // contain whitespace, and this fixture's single ink box covers the full (space-separated) line.
        val layout = WriterFixtures.layoutFor(
            PAGE_GEOMETRY, ocrText,
            listOf(PlainSpan(0, ocrText.length, ocrInk, SpanGranularity.LINE)),
            imageFile, renderedImage.width, renderedImage.height,
        )
        val layoutReference = PageTextLayoutStore.write(context.paths, collectionId, documentId, layout)
        val sourceImage = SourceImageProvenance(
            root = SourceImageRoot.ARTIFACTS,
            relativePath = "page-1.png",
            sha256 = imageSha256,
            width = renderedImage.width,
            height = renderedImage.height,
            renderVersion = 1,
        )

        val revisionId = context.revisions.openCandidate(documentId, null, "TEST_IMPORT")
        context.revisions.appendPage(
            revisionId,
            RevisionPageDraft(
                ordinal = 0,
                unitId = ContentUnitId.new(),
                locator = SourceLocation.TextLines(1, 1),
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
                unitId = ContentUnitId.new(),
                locator = SourceLocation.TextLines(2, 2),
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
        return Fixture(collectionId, documentId, revisionId)
    }

    /**
     * Imports a two-page PDF whose second page was read by OCR but never got a positioning artifact —
     * the real, documented gap a plain first import can leave (ticket 04's STATUS note). Publishes it so
     * the build sees an OCR page with committed text and image but no layout reference at all.
     */
    fun publishOcrPageWithMissingLayout(
        context: AppContext,
        dataDir: Path,
        nativeText: String = "Native page one",
        ocrText: String = "Scanned page two",
        collectionId: CollectionId = CollectionId("default"),
    ): Fixture {
        val sourcesDir = Files.createDirectories(dataDir.resolve("sources"))
        val source = sourcesDir.resolve("partial-${System.nanoTime()}.pdf")
        WriterFixtures.writeSourcePdf(
            source,
            listOf(
                SourcePage(PAGE_GEOMETRY, SourceContent.NativeText(listOf(TextRun(nativeText, 72.0, 700.0, 14.0)))),
                SourcePage(PAGE_GEOMETRY, SourceContent.Scan(inks = listOf(CropRect(72.0, 700.0, 200.0, 14.0)))),
            ),
        )
        val documentId = runBlocking { context.library.importFile(collectionId, source) }.document.id
        val renderedImage: BufferedImage = WriterFixtures.render(source, 1, 200)
        val imageFile = Files.createDirectories(context.paths.artifactsDir(collectionId, documentId)).resolve("page-1.png")
        WriterFixtures.writePng(renderedImage, imageFile)
        val sourceImage = SourceImageProvenance(
            root = SourceImageRoot.ARTIFACTS,
            relativePath = "page-1.png",
            sha256 = WriterFixtures.sha256(imageFile),
            width = renderedImage.width,
            height = renderedImage.height,
            renderVersion = 1,
        )

        val revisionId = context.revisions.openCandidate(documentId, null, "TEST_IMPORT")
        context.revisions.appendPage(
            revisionId,
            RevisionPageDraft(
                ordinal = 0,
                unitId = ContentUnitId.new(),
                locator = SourceLocation.TextLines(1, 1),
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
                unitId = ContentUnitId.new(),
                locator = SourceLocation.TextLines(2, 2),
                extractedText = ocrText,
                searchText = ocrText.lowercase(),
                extractionMethod = ExtractionMethod.OCR,
                approval = PageApproval.APPROVED,
                sourceImage = sourceImage,
                layoutReference = null,
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
        return Fixture(collectionId, documentId, revisionId)
    }

    /** Imports and publishes a plain-text (non-PDF) document, for the media-type ineligibility tests. */
    fun publishPlainTextDocument(context: AppContext, dataDir: Path, text: String = "not a pdf"): Fixture {
        val collectionId = CollectionId("default")
        val sourcesDir = Files.createDirectories(dataDir.resolve("sources"))
        val source = sourcesDir.resolve("plain-${System.nanoTime()}.txt")
        Files.writeString(source, text)
        val documentId = runBlocking { context.library.importFile(collectionId, source) }.document.id
        val revisionId = context.revisions.openCandidate(documentId, null, "TEST_IMPORT")
        context.revisions.appendPage(
            revisionId,
            RevisionPageDraft(
                ordinal = 0,
                unitId = ContentUnitId.new(),
                locator = SourceLocation.TextLines(1, 1),
                extractedText = text,
                searchText = text,
                extractionMethod = ExtractionMethod.DIRECT_TEXT,
                approval = PageApproval.APPROVED,
                chunks = listOf(
                    RevisionChunkDraft(
                        ordinal = 0, text = text, startOffset = 0, endOffset = text.length,
                        tokenCount = 5, tokenStart = 0, tokenEnd = 4,
                        embedding = EMBEDDER.embedDocuments(listOf(text)).single(),
                    ),
                ),
            ),
        )
        runBlocking { context.revisionPublication.publish(documentId, null, revisionId) }
        return Fixture(collectionId, documentId, revisionId)
    }

    /** Claims and runs the next queued job through [SelectablePdfJobHandler], exactly as the real runner would. */
    fun runNextSelectablePdfJob(context: AppContext) {
        val job = assertNotNull(context.jobs.claimNextQueued(), "a selectable-PDF job was expected to be queued")
        val handler = SelectablePdfJobHandler(context.selectablePdfService)
        val stage = JobStage(job.id, context.jobs, context.collections, context.mutations)
        try {
            runBlocking { handler.handle(job, stage) }
            context.jobs.complete(job.id)
        } catch (failure: Throwable) {
            context.jobs.fail(job.id, "JOB_FAILED", failure.message ?: failure::class.simpleName.orEmpty())
            throw failure
        }
    }

    /** True while at least one SELECTABLE_PDF job is queued or running for [documentId]/[revisionId]. */
    fun hasQueuedOrRunningJob(context: AppContext, documentId: DocumentId, revisionId: String): Boolean {
        val row = context.selectablePdfStore.get(documentId, revisionId) ?: return false
        val jobId = row.jobId ?: return false
        val job = context.jobs.get(infoscry.domain.JobId(jobId)) ?: return false
        return job.state == infoscry.domain.JobState.QUEUED || job.state == infoscry.domain.JobState.RUNNING
    }
}
