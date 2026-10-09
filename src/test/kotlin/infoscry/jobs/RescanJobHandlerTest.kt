package infoscry.jobs

import infoscry.AppContext
import infoscry.chunk.Chunker
import infoscry.chunk.WhitespaceTokenCounter
import infoscry.document.RescanRefusalException
import infoscry.document.RescanService
import infoscry.domain.CollectionId
import infoscry.domain.ContentUnitId
import infoscry.domain.DocumentId
import infoscry.domain.DocumentStatus
import infoscry.domain.ExtractionMethod
import infoscry.domain.JobId
import infoscry.domain.JobState
import infoscry.domain.JobType
import infoscry.domain.SourceLocation
import infoscry.embedding.DocumentEmbedder
import infoscry.embedding.TestDocumentEmbedder
import infoscry.extract.ContentUnitDraft
import infoscry.extract.DocumentExtractor
import infoscry.extract.ExtractionEvent
import infoscry.extract.ExtractionInput
import infoscry.extract.ExtractionSettings
import infoscry.extract.OCR_FAILED_CODE
import infoscry.llm.LlmProvider
import infoscry.ocr.CollectionOcrSettings
import infoscry.ocr.ExternalDispatchPermitRequest
import infoscry.ocr.ImageLlmException
import infoscry.ocr.OcrDispatchAuthority
import infoscry.ocr.OcrDispatchStage
import infoscry.ocr.OcrEndpointScope
import infoscry.ocr.OcrEngine
import infoscry.ocr.OcrExternalOwner
import infoscry.ocr.OcrOperation
import infoscry.ocr.OcrOperationStage
import infoscry.ocr.OcrPageResult
import infoscry.ocr.OcrProfileRevision
import infoscry.ocr.OcrSettingsSnapshot
import infoscry.ocr.ReadingMethod
import infoscry.ocr.PageDispatchIdentity
import infoscry.ocr.PageImage
import infoscry.ocr.PageOcrEngine
import infoscry.storage.PageApproval
import infoscry.storage.RevisionState
import infoscry.storage.StaleRescanPreviewException
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * What one rescan attempt does to a document that is already published.
 *
 * The tests drive the real stores, the real candidate revision, the real publication protocol and the real
 * crash-resume rules on a temporary archive; what is stood in for is the page-reading engine. Everything
 * these tests assert is
 * therefore about the archive's own promises: what is committed per page, what may be published, what a
 * failed or cancelled attempt leaves behind, and exactly which pages were sent where.
 */
class RescanJobHandlerTest {

    @Test
    fun `a rescan publishes a differing reading when the whole replacement is ready`() {
        withHarness { harness ->
            val picture = harness.importPicture(text = "first reading")
            val engine = FakePageEngine(readings = listOf("second reading"))

            // The completed candidate replaces the text only after every phase is ready.
            val result = harness.rescan(picture, engine = engine)

            // Nothing is pending; the whole replacement is published in one step.
            assertEquals(OcrOperationStage.COMPLETE, result.operation.stage)
            assertNull(result.operation.errorCode, "the approved replacement was refused: ${result.operation.errorCode}")
            assertEquals(0, result.operation.pendingReviewCount)
            assertEquals(listOf(PageApproval.APPROVED), result.candidateApprovals)
            assertEquals("second reading", result.publishedText)
            assertNotEqualsId(picture.baselineRevisionId, assertNotNull(result.activeRevisionId))
        }
    }

    @Test
    fun `cancellation after publication finalizes the authoritative reading as complete`() {
        withHarness { harness ->
            val picture = harness.importPicture(text = "first reading")

            val result = harness.rescan(
                picture,
                engine = FakePageEngine(readings = listOf("second reading")),
                afterPublication = { context, jobId -> context.jobs.cancel(jobId) },
            )

            assertEquals(OcrOperationStage.COMPLETE, result.operation.stage)
            assertEquals("second reading", result.publishedText)
            assertEquals(infoscry.domain.DocumentStatus.COMPLETE, result.documentStatus)
        }
    }

    @Test
    fun `an explicit rescan of a format without page images is refused with the clear reason`() {
        withHarness { harness ->
            val documentId = harness.importTextDocument()

            val refusal = harness.previewRefusal(documentId)

            val exception = assertIs<RescanRefusalException>(
                refusal,
                "a rescan of a format without page images was not refused with the project's own refusal: $refusal",
            )
            assertEquals(RescanRefusalException.PAGE_IMAGES_UNSUPPORTED, exception.code)
            assertContains(
                exception.message.orEmpty(),
                "text/plain has no page images to read, so it cannot be read again from them",
                message = "the refusal must keep the clear reason a person acts on",
            )
            // The refusal is about the format, not about this document's reading: the ordinary extraction the
            // import published is untouched by the rescan that could not be asked for.
            AppContext.open(harness.archiveDir).use { context ->
                val document = assertNotNull(context.documents.get(documentId))
                assertEquals("text/plain", document.mediaType)
                assertEquals(DocumentStatus.COMPLETE, document.status)
            }
        }
    }

    @Test
    fun `a language change between preview and admission is refused`() {
        withHarness { harness ->
            val picture = harness.importPicture(text = "first reading")
            val previewId = harness.previewFor(picture)
            harness.updateSelection { settings -> settings.copy(language = "swe") }

            val refusal = harness.admitRefusal(picture, previewId, requestId = "stale-language")

            assertTrue(
                refusal is StaleRescanPreviewException,
                "a language change was admitted against the preview it invalidated: $refusal",
            )
            // The same preview, admitted against the settings it was taken with, is still admitted.
            val freshPreviewId = harness.previewFor(picture)
            assertNull(harness.admitRefusal(picture, freshPreviewId, requestId = "unchanged"))
        }
    }

    @Test
    fun `a collection default change does not invalidate an explicitly selected rescan method`() {
        withHarness { harness ->
            val picture = harness.importPicture(text = "first reading")
            val previewId = harness.previewFor(picture, ReadingMethod.Surya)
            harness.updateSelection { settings -> settings.copy(defaultMethod = ReadingMethod.Tesseract) }

            val refusal = harness.admitRefusal(picture, previewId, requestId = "explicit-surya")

            assertNull(refusal, "the collection's prefill default replaced the method the person selected")
        }
    }

    @Test
    fun `a new admission replaces the previous attempt for the same document`() {
        withHarness { harness ->
            val picture = harness.importPicture(text = "first reading")
            AppContext.open(harness.archiveDir).use { context ->
                val first = harness.admitOnly(context, picture)
                val second = harness.admitOnly(context, picture)

                assertTrue(first.operationId != second.operationId, "a new request reused the old operation id")
                assertEquals(OcrOperationStage.CANCELLED, context.ocrOperations.operation(first.operationId)?.stage)
                assertEquals(second.operationId, context.ocrOperations.activeOperation(picture.documentId)?.operationId)
            }
        }
    }

    @Test
    fun `admitting a rescan marks its document queued`() {
        withHarness { harness ->
            val picture = harness.importPicture(text = "first reading")
            AppContext.open(harness.archiveDir).use { context ->
                harness.admitOnly(context, picture)
                assertEquals(DocumentStatus.QUEUED, context.documents.get(picture.documentId)?.status)
            }
        }
    }

    @Test
    fun `a second attempt reuses the page it already read instead of paying for it again`() {
        withHarness { harness ->
            val picture = harness.importPicture(text = "first reading")
            val engine = FakePageEngine(readings = listOf("second reading"))

            val first = harness.rescan(picture, engine = engine)
            assertEquals(1, engine.calls)

            // A resumed attempt continues the same operation from its durable page checkpoint.
            val second = harness.resumeRescan(first.operation.operationId, engine, picture)

            assertEquals(1, engine.calls, "a committed page was read a second time")
            assertEquals(
                first.operation.candidateRevisionId,
                second.operation.candidateRevisionId,
                "a resume stages into the candidate the operation already records",
            )
        }
    }

    @Test
    fun `a reading that keeps the page approved publishes unchanged text`() {
        withHarness { harness ->
            val picture = harness.importPicture(text = "first reading")
            // The engine reads the same text the page already carries: nothing differs, so nothing has to be
            // decided and the revision is complete on its own.
            val engine = FakePageEngine(readings = listOf("first reading"))

            val result = harness.rescan(picture, engine = engine)

            assertEquals(OcrOperationStage.COMPLETE, result.operation.stage)
            assertEquals(0, result.operation.pendingReviewCount)
            assertEquals("first reading", harness.publishedTextOf(picture))
        }
    }

    @Test
    fun `an unreachable provider fails the operation and keeps the published reading`() {
        withHarness { harness ->
            val picture = harness.importPicture(text = "first reading")
            val engine = FakePageEngine(failWith = ImageLlmException.PROVIDER_UNAVAILABLE)

            val result = harness.rescan(picture, engine = engine)

            assertEquals(OcrOperationStage.FAILED, result.operation.stage)
            assertEquals(ImageLlmException.PROVIDER_UNAVAILABLE, result.operation.errorCode)
            assertEquals("first reading", harness.publishedTextOf(picture))
            assertEquals(picture.baselineRevisionId, harness.activeRevisionOf(picture))
        }
    }

    @Test
    fun `an absent embedding model fails before a page is read`() {
        withHarness { harness ->
            val picture = harness.importPicture(text = "first reading")
            val engine = FakePageEngine(readings = listOf("second reading"))

            val result = harness.rescan(picture, engine = engine, embedder = null)

            assertEquals(OcrOperationStage.FAILED, result.operation.stage)
            assertEquals("EMBEDDING_UNAVAILABLE", result.operation.errorCode)
            assertEquals(0, engine.calls, "a rescan that cannot publish may not read pages first")
            assertEquals("first reading", harness.publishedTextOf(picture))
        }
    }

    @Test
    fun `a moved original changes nothing and a managed copy that changed fails without being read`() {
        withHarness { harness ->
            val picture = harness.importPicture(text = "first reading")
            // The reading matches what is published, so this attempt is a no-op that leaves the document
            // released: the subject here is the managed copy, not the review backlog the other tests cover.
            val engine = FakePageEngine(readings = listOf("first reading"))
            // The user's own file is moved away after the import. A rescan reads the archive's managed copy, so
            // nothing about it depends on where the original is — which is the whole point of a managed copy.
            Files.move(harness.sourceFileOf(picture), harness.sourceFileOf(picture).resolveSibling("moved.png"))

            val moved = harness.rescan(picture, engine = engine)
            assertEquals(1, engine.calls)
            assertEquals("first reading", moved.publishedText)

            // The managed copy is then replaced by other bytes. Every reading would be attributed to bytes the
            // archive never recorded, so this is a safe failure and not a reading of the new file.
            val resumed = harness.resumeAfterCorrupting(picture, engine)
            assertEquals(OcrOperationStage.FAILED, resumed.operation.stage)
            assertEquals("RESCAN_MANAGED_COPY_CHANGED", resumed.operation.errorCode)
            assertEquals("first reading", resumed.publishedText)
        }
    }

    @Test
    fun `a cancellation during the first page leaves no candidate page and the published text intact`() {
        withHarness { harness ->
            val picture = harness.importPicture(text = "first reading")
            val result = harness.rescan(
                picture = picture,
                // The engine cancels the job while it is reading, which is the window a cancellation request
                // has to be honoured in.
                engine = FakePageEngine(readings = listOf("second reading")),
                beforePageCommit = { context, jobId -> context.jobs.cancel(jobId) },
            )

            assertEquals(OcrOperationStage.CANCELLED, result.operation.stage)
            assertNull(result.candidatePage, "a cancelled attempt may not commit a page it was stopped in")
            assertEquals("first reading", result.publishedText)
            assertEquals("first reading", result.activeRevisionId?.let { revisionId -> result.publishedText })
            assertEquals(1, result.indexedChunks)
        }
    }

    @Test
    fun `a collection deleted while a page is being read commits nothing`() {
        withHarness { harness ->
            val picture = harness.importPicture(text = "first reading")
            val result = harness.rescan(
                picture = picture,
                engine = FakePageEngine(readings = listOf("second reading")),
                beforePageCommit = { context, _ ->
                    // The deletion is admitted while the page is being read: the commit that follows must not
                    // stage anything, and nothing may resurrect the document.
                    context.collectionService.requestDeletion(CollectionId("default"), "Default")
                },
            )

            // Admitting a deletion cancels the collection's jobs durably, so the attempt stops at its next
            // stage — the page it had just read is never committed, nothing is published, and the deletion
            // machine is what owns the document from here on.
            assertTrue(
                result.failure is kotlinx.coroutines.CancellationException,
                "expected the attempt to stop on the deletion, was ${result.failure}",
            )
            assertEquals(OcrOperationStage.CANCELLED, result.operation.stage)
            assertEquals(1, result.engine?.calls ?: 0)
            // The attempt committed nothing, and the document is the deletion's from here on: no revision of it
            // is published and nothing it read survives as a candidate.
            assertNull(result.candidatePage)
            assertNull(result.activeRevisionId, "the deleted document has no active reading")
        }
    }

    private fun assertNotEqualsId(first: String, second: String) {
        assertTrue(first != second, "the active revision did not move: $first")
    }

    private fun withHarness(block: (RescanHarness) -> Unit) {
        val directory = Files.createTempDirectory("infoscry-rescan")
        try {
            RescanHarness(directory).use(block)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    internal companion object {
        /** The code the publication refuses a revision whose pages are not all approved with. */
        const val REVISION_AWAITING_REVIEW_CODE = "REVISION_AWAITING_REVIEW"

    }
}

/** A page-reading engine a test can dictate: what it reads, or how it fails. */
internal class FakePageEngine(
    private val readings: List<String> = emptyList(),
    private val failWith: String? = null,
    private val onRead: suspend () -> Unit = {},
) : PageOcrEngine {

    override val engine: OcrEngine = OcrEngine.SURYA

    /** How many pages the engine was asked to read, so a test can prove a page was not read twice. */
    var calls: Int = 0
        private set

    /** What the engine was handed, in order, so a test can assert on the pages it was asked about. */
    val pages: MutableList<PageImage> = mutableListOf()

    override suspend fun transcribe(page: PageImage, settings: OcrSettingsSnapshot): OcrPageResult {
        calls++
        pages += page
        onRead()
        if (failWith != null) {
            throw ImageLlmException(failWith, "the fake engine refused this page")
        }
        val text = readings.getOrElse((calls - 1).coerceAtMost(readings.size - 1).coerceAtLeast(0)) { "" }
        return OcrPageResult(
            text = text,
            engine = engine,
            imageSha256 = page.sha256,
            modelVersion = "fake-surya-1",
        )
    }

    override suspend fun runtimeIdentity(): String? = "fake-surya-1"
}

/**
 * A temporary archive with one published picture document, and the rescan services pointed at it.
 *
 * The document is imported through the real import path with a stand-in extractor, so its managed copy, its
 * committed unit, its chunks and its first published revision are the archive's own records rather than rows
 * this harness wrote itself. Everything a rescan then does is compared against those.
 */
internal class RescanHarness(private val directory: Path) : AutoCloseable {

    private val harness: Harness = Harness(directory)

    private val admissions = java.util.concurrent.atomic.AtomicInteger()

    /** The archive itself, which is the directory a second process opens. */
    val archiveDir: Path get() = harness.dataDir

    /** One imported picture: the document, its published revision, its page's unit id and its source file. */
    data class Picture(
        val documentId: DocumentId,
        val baselineRevisionId: String,
        val unitId: String,
        val sourceFile: Path,
    )

    /**
     * What one attempt left behind, read back from the archive before its process is closed.
     *
     * The values are read rather than the connection kept open, because the point of every assertion here is
     * what is *durable*: a value read back after the attempt's process is gone is exactly that.
     */
    data class Attempt(
        val operation: OcrOperation,
        val candidatePage: infoscry.storage.RevisionPageText?,
        val candidateApprovals: List<PageApproval>,
        val activeRevisionId: String?,
        val publishedText: String,
        val indexedChunks: Int,
        val documentStatus: infoscry.domain.DocumentStatus?,
        val engine: FakePageEngine?,
        /** What the attempt threw, if anything, so a test can assert on how it stopped. */
        val failure: Throwable?,
    )

    fun importPicture(text: String): Picture {        val file = harness.sourcesDir.resolve("page.png")
        writePicture(file)
        val run = harness.importDurably(listOf(file), PictureUnits(text))
        val documentId = run.items.single().documentId!!
        AppContext.open(harness.dataDir).use { context ->
            val revisionId = context.revisions.activeRevisionId(documentId)!!
            val page = context.revisions.pages(revisionId).single()
            return Picture(documentId, revisionId, page.unitId.value, file)
        }
    }

    /** A second, different picture document in the same collection, for the tests that need a neighbour. */
    fun importAnotherPicture(text: String): Picture {
        val file = harness.sourcesDir.resolve("another-page.png")
        val image = BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB)
        for (x in 0 until 8) {
            for (y in 0 until 8) image.setRGB(x, y, 0xEEEEEE)
        }
        ImageIO.write(image, "png", file.toFile())
        val run = harness.importDurably(listOf(file), PictureUnits(text))
        val documentId = run.items.single().documentId!!
        AppContext.open(harness.dataDir).use { context ->
            val revisionId = context.revisions.activeRevisionId(documentId)!!
            val page = context.revisions.pages(revisionId).single()
            return Picture(documentId, revisionId, page.unitId.value, file)
        }
    }

    /** One imported plain-text document: a format without page images, so a rescan has nothing to read again. */
    fun importTextDocument(): DocumentId {
        val file = harness.sourcesDir.resolve("notes.txt")
        Files.writeString(file, "Ordinary notes about the meeting\n")
        val run = harness.importDurably(listOf(file), RecordingUnits(units = 1))
        return assertNotNull(run.items.single().documentId)
    }

    /** What one rescan preview answered for [documentId], or the refusal it was refused with. */
    fun previewRefusal(documentId: DocumentId): Throwable? =
        AppContext.open(harness.dataDir).use { context ->
            runBlocking {
                runCatching {
                    rescanServiceOf(context, FakePageEngine()).preview(
            CollectionId("default"), documentId, ReadingMethod.Surya,
        )
                }.exceptionOrNull()
            }
        }

    /** The text the document publishes now, as its active revision holds it. */
    fun publishedTextOf(picture: Picture): String = AppContext.open(harness.dataDir).use { context ->
        val revisionId = context.revisions.activeRevisionId(picture.documentId)!!
        context.revisions.pages(revisionId).single().extractedText
    }

    fun activeRevisionOf(picture: Picture): String = AppContext.open(harness.dataDir).use { context ->
        context.revisions.activeRevisionId(picture.documentId)!!
    }

    /** How many of the document's chunks the search index holds, which is what a reader would find. */
    fun indexedChunksOf(picture: Picture): Int = AppContext.open(harness.dataDir).use { context ->
        context.index().chunkCount(CollectionId("default"), picture.documentId)
    }

    fun managedCopyOf(picture: Picture): Path = harness.managedOriginal(picture.documentId)

    /** The file the user imported, so a test can move it away and prove a rescan does not need it. */
    fun sourceFileOf(picture: Picture): Path = picture.sourceFile

    /**
     * An attempt admitted while the managed copy was intact and run after it changed.
     *
     * The order is deliberate: the preview looks at the copy's hash, so the corruption has to land between
     * admission and the reading to test what the *attempt* does about it — which is refuse, rather than
     * attribute a reading to bytes the archive never recorded.
     */
    fun resumeAfterCorrupting(picture: Picture, engine: FakePageEngine): Attempt =
        AppContext.open(harness.dataDir).use { context ->
            val operationId = admitFor(context, picture)
            Files.write(managedCopyOf(picture), byteArrayOf(1, 2, 3, 4))
            runAttempt(context, operationId, engine, TestDocumentEmbedder(), null, picture)
        }

    /** One whole rescan: preview, admit, and the attempt the admission queued. */
    fun rescan(
        picture: Picture,
        engine: FakePageEngine,
        embedder: DocumentEmbedder? = TestDocumentEmbedder(),
        beforePageCommit: (suspend (AppContext, JobId) -> Unit)? = null,
        afterPublication: (suspend (AppContext, JobId) -> Unit)? = null,
    ): Attempt = AppContext.open(harness.dataDir).use { context ->
        val operationId = admitFor(context, picture)
        runAttempt(context, operationId, engine, embedder, beforePageCommit, picture, afterPublication)
    }

    /** Another attempt of an operation the archive already holds. */
    fun resumeRescan(operationId: String, engine: FakePageEngine, picture: Picture): Attempt =
        AppContext.open(harness.dataDir).use { context ->
            runAttempt(context, operationId, engine, TestDocumentEmbedder(), null, picture)
        }

    /** Writes the collection's local reading-method default. */
    private fun writeSelection(context: AppContext) {
        val collection = assertNotNull(context.collections.get(CollectionId("default")))
        context.collections.updateOcrSettings(
            CollectionId("default"),
            collection.ocrSettings().copy(language = "eng", defaultMethod = ReadingMethod.Surya),
        )
    }

    /** Rewrites the collection's OCR selection from what it holds now, for the stale-preview tests. */
    fun updateSelection(change: (CollectionOcrSettings) -> CollectionOcrSettings) {
        AppContext.open(harness.dataDir).use { context ->
            val current = assertNotNull(context.collections.get(CollectionId("default"))).ocrSettings()
            context.collections.updateOcrSettings(CollectionId("default"), change(current))
        }
    }

    /** One preview of [picture] using the chosen reading method. */
    fun previewFor(picture: Picture, method: ReadingMethod = ReadingMethod.Surya): String =
        AppContext.open(harness.dataDir).use { context ->
            writeSelection(context)
            runBlocking {
                rescanServiceOf(context, FakePageEngine())
                    .preview(CollectionId("default"), picture.documentId, method)
            }.previewId
        }

    /** One admission against a preview taken against the selection the tests name. */
    fun admitOnly(picture: Picture): OcrOperation = AppContext.open(harness.dataDir).use { context ->
        admitOnly(context, picture)
    }

    fun admitOnly(context: AppContext, picture: Picture): OcrOperation {
        writeSelection(context)
        val service = rescanServiceOf(context, FakePageEngine())
        val preview = runBlocking { service.preview(CollectionId("default"), picture.documentId, ReadingMethod.Surya) }
        return runBlocking {
            service.admitRescan(
                collectionId = CollectionId("default"),
                documentId = picture.documentId,
                previewId = preview.previewId,
                requestId = "rescan-request-${admissions.incrementAndGet()}",
            )
        }
    }

    /** What one admission of a fresh preview answered, or the refusal it was refused with. */
    fun admitRefusal(picture: Picture, requestId: String): Throwable? =
        AppContext.open(harness.dataDir).use { context ->
            val service = rescanServiceOf(context, FakePageEngine())
            runBlocking {
                runCatching {
                    val preview = service.preview(CollectionId("default"), picture.documentId, ReadingMethod.Surya)
                    service.admitRescan(
                        collectionId = CollectionId("default"),
                        documentId = picture.documentId,
                        previewId = preview.previewId,
                        requestId = requestId,
                    )
                }.exceptionOrNull()
            }
        }

    /** What one admission of a named preview answered, or the refusal it was refused with. */
    fun admitRefusal(picture: Picture, previewId: String, requestId: String): Throwable? =
        AppContext.open(harness.dataDir).use { context ->
            runBlocking {
                runCatching {
                    rescanServiceOf(context, FakePageEngine()).admitRescan(
                        collectionId = CollectionId("default"),
                        documentId = picture.documentId,
                        previewId = previewId,
                        requestId = requestId,
                    )
                }.exceptionOrNull()
            }
        }

    // ---- internals ----

    private fun admitFor(context: AppContext, picture: Picture): String {
        writeSelection(context)
        val engine = FakePageEngine()
        val service = rescanServiceOf(context, engine)
        return runBlocking {
            val preview = service.preview(CollectionId("default"), picture.documentId, ReadingMethod.Surya)
            service.admitRescan(
                collectionId = CollectionId("default"),
                documentId = picture.documentId,
                previewId = preview.previewId,
                // A distinct request id per admission: the same id is the same request, which is exactly what
                // the routes' idempotency rests on and not what these tests are about.
                requestId = "rescan-request-${admissions.incrementAndGet()}",
            ).operationId
        }
    }

    private fun runAttempt(
        context: AppContext,
        operationId: String,
        engine: FakePageEngine,
        embedder: DocumentEmbedder?,
        beforePageCommit: (suspend (AppContext, JobId) -> Unit)?,
        picture: Picture,
        afterPublication: (suspend (AppContext, JobId) -> Unit)? = null,
    ): Attempt {
        val job = runBlocking {
            context.jobs.enqueue(
                type = JobType.RESCAN,
                collectionId = CollectionId("default"),
                payload = RescanJobPayload(
                    collectionId = "default",
                    documentId = picture.documentId.value,
                    operationId = operationId,
                ).encode(),
                total = 1,
            )
        }
        val claimed = assertNotNull(context.jobs.claim(job.id))
        val before = assertNotNull(context.ocrOperations.operation(operationId))
        // The page is read first and the hook runs before the page is committed, which is the window a
        // cancellation request or a collection deletion has to be honoured in.
        val effective = if (beforePageCommit == null) {
            engine
        } else {
            HookedEngine(engine, context, claimed.id, beforePageCommit)
        }
        val handler = RescanJobHandler(
            paths = context.paths,
            collections = context.collections,
            documents = context.documents,
            library = context.library,
            mutations = context.mutations,
            jobs = context.jobs,
            revisions = context.revisions,
            operations = context.ocrOperations,
            publication = context.revisionPublication,
            chunker = Chunker(WhitespaceTokenCounter(prefixTokens = 2, specialTokens = 1)),
            documentEmbedder = { embedder },
            engineFor = { _, _, _ -> effective },
            profileRevisionOf = { revisionId -> context.ocrProfiles.findRevision(revisionId) },
            afterCandidatePublished = { jobId -> afterPublication?.invoke(context, jobId) },
        )
        val failure = runBlocking {
            runCatching {
                handler.handle(
                    assertNotNull(context.jobs.get(claimed.id)),
                    JobStage(claimed.id, context.jobs, context.collections, context.mutations),
                )
            }.exceptionOrNull()
        }
        runBlocking {
            // The runner's own endings, so the archive is left as a stopped attempt leaves it: a cancelled job
            // is cancelled, and an attempt that paused or finished completes.
            val running = context.jobs.get(claimed.id)
            if (running != null && running.state == JobState.RUNNING) {
                if (running.cancelRequested) {
                    context.jobs.finishCancelled(claimed.id)
                } else {
                    context.jobs.complete(claimed.id)
                }
            }
        }
        // A collection deletion cascades the document and its operations away, so a row that is gone is an
        // answer rather than a missing one: it is what "this document is not one this attempt owns" means.
        // A deleted collection cascades the document and its operations away, so a row that is gone is an
        // answer rather than a missing one; the last state this attempt recorded stands in for it.
        val operation = context.ocrOperations.operation(operationId) ?: before.copy(
            stage = OcrOperationStage.CANCELLED,
        )
        val candidate = if (context.ocrOperations.operation(operationId) == null) {
            null
        } else {
            operation.candidateRevisionId
        }
        val pages = candidate?.let { revisionId -> context.revisions.pages(revisionId) }.orEmpty()
        val active = context.revisions.activeRevisionId(picture.documentId)
        return Attempt(
            operation = operation,
            candidatePage = pages.singleOrNull(),
            candidateApprovals = pages.map { page -> page.approval },
            activeRevisionId = active,
            publishedText = active
                ?.let { revisionId -> context.revisions.pages(revisionId).singleOrNull() }
                ?.extractedText
                .orEmpty(),
            indexedChunks = context.index().chunkCount(CollectionId("default"), picture.documentId),
            documentStatus = context.documents.get(picture.documentId)?.status,
            engine = engine,
            failure = failure,
        )
    }

    private fun rescanServiceOf(context: AppContext, engine: PageOcrEngine?): RescanService = RescanService(
        paths = context.paths,
        collections = context.collections,
        documents = context.documents,
        revisions = context.revisions,
        jobs = context.jobs,
        operations = context.ocrOperations,
        mutations = context.mutations,
        blockers = context.blockers,
        publication = context.revisionPublication,
        profileOf = { profileId -> context.ocrProfiles.findById(profileId) },
        profileRevisionOf = { revisionId -> context.ocrProfiles.findRevision(revisionId) },
        engines = { _, _, _ -> engine },
        embedderAvailable = { true },
    )

    private fun writePicture(file: Path) {
        val image = BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB)
        for (x in 0 until 8) {
            for (y in 0 until 8) image.setRGB(x, y, 0xFFFFFF)
        }
        ImageIO.write(image, "png", file.toFile())
    }

    override fun close() {
        harness.close()
    }
}

/**
 * A page engine that calls back between the reading and its commit.
 *
 * That instant is where a cancellation request or a collection deletion lands in a real attempt: the page's
 * reading has come back and the commit is still ahead. Only a test can hold an attempt exactly there.
 */
private class HookedEngine(
    private val delegate: FakePageEngine,
    private val context: AppContext,
    private val jobId: JobId,
    private val afterRead: suspend (AppContext, JobId) -> Unit,
) : PageOcrEngine {

    override val engine: OcrEngine get() = delegate.engine

    /** The delegate's own identity: an attempt compares it with the one its operation was admitted with. */
    override suspend fun runtimeIdentity(): String? = delegate.runtimeIdentity()

    override suspend fun transcribe(page: PageImage, settings: OcrSettingsSnapshot): OcrPageResult {
        val reading = delegate.transcribe(page, settings)
        afterRead(context, jobId)
        return reading
    }
}

/** An extractor for a picture document: one page, whose text the test dictates. */
internal class PictureUnits(private val text: String) : DocumentExtractor {

    override val supportedMediaTypes: Set<String> = setOf("image/png")

    override fun extract(input: ExtractionInput): kotlinx.coroutines.flow.Flow<ExtractionEvent> =
        kotlinx.coroutines.flow.flow {
            if (!input.isCommitted(KEY)) {
                input.boundary.unit {
                    emit(
                        ExtractionEvent.UnitReady(
                            key = KEY,
                            ordinal = 0,
                            unit = ContentUnitDraft(
                                locator = SourceLocation.Image(input.originalFilename),
                                extractedText = text,
                                searchText = text,
                                method = ExtractionMethod.OCR,
                            ),
                        ),
                    )
                }
            }
            input.boundary.unit {
                emit(ExtractionEvent.Finished(metadata = emptyMap(), totalUnits = 1))
            }
        }

    private companion object {
        const val KEY = "image"
    }
}
