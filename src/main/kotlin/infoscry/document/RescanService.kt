package infoscry.document

import infoscry.collection.DeletionBlockers
import infoscry.config.AppPaths
import infoscry.domain.CollectionLifecycle
import infoscry.domain.Collection
import infoscry.domain.CollectionId
import infoscry.domain.Document
import infoscry.domain.DocumentId
import infoscry.extract.PageCounter
import infoscry.extract.DefaultPageCounter
import infoscry.extract.PdfExtractor
import infoscry.extract.PdfPageRenderer
import infoscry.extract.PdfPageSelector
import infoscry.extract.PngPageRenderer
import infoscry.extract.TextNormalizer
import infoscry.jobs.RescanJobPayload
import infoscry.ocr.CollectionOcrSettings
import infoscry.ocr.OcrCostEstimate
import infoscry.ocr.OcrDispatchAuthority
import infoscry.ocr.OcrEndpointScope
import infoscry.ocr.OcrNamedDestination
import infoscry.ocr.OcrOperation
import infoscry.ocr.OcrOperationStage
import infoscry.ocr.OcrProfileRevision
import infoscry.ocr.OcrProfileRole
import infoscry.ocr.OcrSettingsSnapshot
import infoscry.ocr.ReadingMethod
import infoscry.ocr.ReadingMethodCatalog
import infoscry.ocr.ReadingMethodUnavailableException
import infoscry.ocr.PageImage
import infoscry.ocr.PageImageRenderer
import infoscry.ocr.RescanPreview
import infoscry.storage.CollectionNotActiveException
import infoscry.storage.CollectionStore
import infoscry.storage.DocumentBeingDeletedException
import infoscry.storage.DocumentRevisionStore
import infoscry.storage.DocumentStore
import infoscry.storage.JobStore
import infoscry.storage.MutationCoordinator
import infoscry.storage.OcrOperationConflictException
import infoscry.storage.OcrOperationStore
import infoscry.storage.RevisionPageText
import infoscry.storage.StaleRescanPreviewException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.HexFormat
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.pdfbox.Loader
import org.apache.pdfbox.io.IOUtils
import org.apache.pdfbox.pdmodel.PDDocument

/**
 * A refusal a rescan request can be answered with, in this project's own vocabulary.
 *
 * [code] is a safe code and [message] is a curated remedy: a rescan is refused with what a person has to do
 * — install Surya, probe the profile, renew the preview — and never with a provider's or a tool's words.
 */
class RescanRefusalException(val code: String, message: String) : IllegalStateException(message) {

    companion object {

        /** The document's managed copy is not the file this archive recorded. */
        const val MANAGED_COPY_CHANGED: String = "RESCAN_MANAGED_COPY_CHANGED"

        /** The document's managed copy is gone, so there are no bytes to read. */
        const val MANAGED_COPY_MISSING: String = "RESCAN_MANAGED_COPY_MISSING"

        /** This format has no pages to render, so it cannot be read again from page images. */
        const val PAGE_IMAGES_UNSUPPORTED: String = "PAGE_IMAGES_UNSUPPORTED"

        /** A picture declares a raster past what this build may hand to a model. */
        const val PAGE_RASTER_UNBOUNDED: String = "PAGE_RASTER_UNBOUNDED"

        /** This build has no such engine, so the settings cannot be honoured. */
        const val ENGINE_UNAVAILABLE: String = "RESCAN_ENGINE_UNAVAILABLE"

        /** The profile revision a rescan would dispatch to is not one that may be dispatched to. */
        const val PROFILE_UNAVAILABLE: String = "RESCAN_PROFILE_UNAVAILABLE"

        /** The external profile was never measured as able to read an image. */
        const val PROFILE_UNMEASURED: String = "RESCAN_PROFILE_UNMEASURED"

        /** The document's pages cannot be embedded, so a replacement could never be published. */
        const val NO_EMBEDDER: String = "RESCAN_EMBEDDING_UNAVAILABLE"

        /** There is no unpublished, undecided candidate to discard. */
        const val NOTHING_TO_DISCARD: String = "RESCAN_NOTHING_TO_DISCARD"

        /** The operation cannot be resumed against the snapshot it was admitted with. */
        const val SNAPSHOT_NOT_RESUMABLE: String = "RESCAN_SNAPSHOT_NOT_RESUMABLE"

        /** Deciding pages is done, but this machine cannot chunk and embed their text, so nothing was published. */
        const val REVIEW_EMBEDDING_UNAVAILABLE: String = "REVIEW_EMBEDDING_UNAVAILABLE"

        /** Chunking or embedding the decided pages failed; the current revision is still the document's text. */
        const val REVIEW_EMBEDDING_FAILED: String = "REVIEW_EMBEDDING_FAILED"
    }
}

/**
 * One page image an attempt reads, with the reading it is compared against.
 *
 * [baseline] is the page the document *publishes* at this ordinal, or null when it publishes no text there.
 * It is read once, when the attempt starts, and travels with the image so the comparison and the page's unit
 * identity are decided from one reading of the revision rather than from whatever it says later.
 */
data class RescanPage(val image: PageImage, val baseline: RevisionPageText?, val read: Boolean = true)

/**
 * How many pages a managed document has, and how many of them a rescan reads.
 *
 * A page is read when the PDF page selector says its own text cannot be trusted, or when the document publishes
 * no text for it at all. Every other page keeps its published text, so it is not sent to any engine.
 */
data class PageCensus(val documentPages: Int, val pagesToRead: Int)

/**
 * The page images of one managed document, produced the way a page-image reading needs them.
 *
 * Two things about it are deliberate:
 *
 * - **The page is named by the baseline revision's own unit id.** A page of a published document already has
 *   a stable identity, and a reading of that page is about that page: an extraction key like `page:3` is not
 *   a revision unit id, and a comparison refuses a page that names another identity than the revision holds.
 *   A page with no baseline at all gets the extractor's own key for it, which is the identity a first import
 *   of the same page would have produced.
 * - **Nothing here is written into published content.** The images are written under the document's artifact
 *   root (they outlive the attempt, because a person has to be able to look at the pixels a reading was made
 *   from), and every other effect belongs to the caller.
 */
class RescanPageSource(
    private val draw: PdfPageRenderer = PngPageRenderer,
    private val maxRenderedPixels: Long = PdfExtractor.MAX_RENDERED_PIXELS,
    private val pageCounter: PageCounter = DefaultPageCounter(),
    private val selector: PdfPageSelector = PdfPageSelector(),
) {

    /** Whether this media type has pages that can be rendered and read. */
    fun supports(mediaType: String): Boolean = mediaType in SUPPORTED_MEDIA_TYPES

    /** Why a format cannot be rescanned, in the words the API and the CLI repeat. */
    fun unsupportedReason(mediaType: String): String =
        "$mediaType has no page images to read, so it cannot be read again from them; only PDF and picture " +
            "documents can be rescanned, and this document's own extraction is unchanged"

    /**
     * How many page images the managed copy has, or null when the container will not say.
     *
     * The count is what a preview shows and what makes a preflight allowance conservative, so it is read from
     * the container's own header rather than by rendering anything.
     */
    fun pageCount(managedPath: Path, mediaType: String): Int? = pageCounter.pageCount(managedPath, mediaType)
        ?: throw RescanRefusalException(
        RescanRefusalException.MANAGED_COPY_MISSING,
        "the document's managed copy could not be opened, so its pages are unknown; add the file again to " +
            "restore it",
    )

    /**
     * How many pages the managed copy has and how many a rescan reads, with [baseline] the pages the document
     * publishes now (keyed by ordinal).
     *
     * A PDF is opened once and judged page by page with the same selector the import uses. A picture is one page
     * and is always read.
     *
     * @throws RescanRefusalException when the container cannot be opened or measured.
     */
    fun census(
        managedPath: Path,
        mediaType: String,
        baseline: Map<Int, RevisionPageText>,
    ): PageCensus = when {
        mediaType == PDF_MEDIA_TYPE -> pdfCensus(managedPath, baseline)
        mediaType in PICTURE_MEDIA_TYPES -> {
            // A picture is one page and is always read; pageCount refuses a picture that is not there.
            pageCount(managedPath, mediaType)
            PageCensus(documentPages = ORDINAL_OF_THE_ONLY_PAGE + 1, pagesToRead = ORDINAL_OF_THE_ONLY_PAGE + 1)
        }
        else -> throw RescanRefusalException(
            RescanRefusalException.PAGE_IMAGES_UNSUPPORTED,
            unsupportedReason(mediaType),
        )
    }

    private fun pdfCensus(managedPath: Path, baseline: Map<Int, RevisionPageText>): PageCensus {
        val pdf = openPdf(managedPath) ?: throw RescanRefusalException(
            RescanRefusalException.MANAGED_COPY_MISSING,
            PDF_NOT_OPENED_MESSAGE,
        )
        return pdf.use { container ->
            val pagesToRead = (1..container.numberOfPages).count { page -> readsPage(container, page, baseline) }
            PageCensus(documentPages = container.numberOfPages, pagesToRead = pagesToRead)
        }
    }

    /**
     * Whether an attempt reads [page] (1-based): the selector says its text cannot be trusted, or the document
     * publishes no text for it, so there is nothing to keep.
     */
    private fun readsPage(container: PDDocument, page: Int, baseline: Map<Int, RevisionPageText>): Boolean =
        baseline[page - 1] == null || selector.shouldRead(container, page)

    /**
     * Renders every page of [document]'s managed copy into [attemptDirectory].
     *
     * Every page is rendered, including those a rescan does not read: a [RescanPage] carries its image for the
     * reading's provenance and for the staged page's identity. Only [RescanPage.read] decides whether an engine
     * is called.
     *
     * @throws RescanRefusalException when the container cannot be opened, when its copy is not the bytes the
     *   archive recorded, or when a picture cannot be read within the raster bound.
     */
    fun pages(
        document: Document,
        managedPath: Path,
        mediaType: String,
        attemptDirectory: Path,
        documentArtifactRoot: Path,
        baseline: Map<Int, RevisionPageText>,
        renderDpi: Int? = null,
    ): List<RescanPage> = when {
        mediaType == PDF_MEDIA_TYPE -> pdfPages(
            document = document,
            managedPath = managedPath,
            attemptDirectory = attemptDirectory,
            documentArtifactRoot = documentArtifactRoot,
            baseline = baseline,
            renderDpi = renderDpi,
        )

        mediaType in PICTURE_MEDIA_TYPES -> listOf(
            picturePage(
                document = document,
                managedPath = managedPath,
                documentArtifactRoot = documentArtifactRoot,
                baseline = baseline,
            ),
        )

        else -> throw RescanRefusalException(
            RescanRefusalException.PAGE_IMAGES_UNSUPPORTED,
            unsupportedReason(mediaType),
        )
    }

    private fun pdfPages(
        document: Document,
        managedPath: Path,
        attemptDirectory: Path,
        documentArtifactRoot: Path,
        baseline: Map<Int, RevisionPageText>,
        renderDpi: Int?,
    ): List<RescanPage> {
        val pdf = openPdf(managedPath)
            ?: throw RescanRefusalException(
                RescanRefusalException.MANAGED_COPY_MISSING,
                "the document's managed copy could not be opened, so no page of it can be read; add the file " +
                    "again to restore it",
            )
        return pdf.use { container ->
            val renderer = PageImageRenderer(draw, maxRenderedPixels)
            val directory = Files.createDirectories(attemptDirectory.resolve(PageImageRenderer.PAGES_DIRECTORY))
            (1..container.numberOfPages).map { page ->
                val ordinal = page - 1
                val image = try {
                    renderer.render(
                        document = container,
                        page = page,
                        requestedDpi = renderDpi,
                        directory = directory,
                        artifactRoot = documentArtifactRoot,
                        documentId = document.id,
                        // The page's identity is the one the document already published for it, and the
                        // extractor's own key only where nothing was published.
                        unitId = baseline[ordinal]?.unitId?.value ?: pdfPageKey(page),
                    )
                } catch (failure: IOException) {
                    null
                } ?: throw RescanRefusalException(
                    RescanRefusalException.PAGE_RASTER_UNBOUNDED,
                    "page $page of this document declares a size that cannot be rendered within the raster " +
                        "bound this build reads at any legible resolution, so it was not read; a rescan cannot " +
                        "produce an image for it",
                )
                RescanPage(
                    image = image,
                    baseline = baseline[ordinal],
                    read = readsPage(container, page, baseline),
                )
            }
        }
    }

    private fun picturePage(
        document: Document,
        managedPath: Path,
        documentArtifactRoot: Path,
        baseline: Map<Int, RevisionPageText>,
    ): RescanPage {
        val directory = managedPath.toAbsolutePath().normalize().parent
            ?: throw RescanRefusalException(
                RescanRefusalException.MANAGED_COPY_MISSING,
                "the document's managed copy has no directory of its own, so its picture cannot be read",
            )
        val image = try {
            PageImage.ofFile(
                documentId = document.id,
                unitId = baseline[ORDINAL_OF_THE_ONLY_PAGE]?.unitId?.value ?: PICTURE_KEY,
                ordinal = ORDINAL_OF_THE_ONLY_PAGE,
                imageRoot = directory,
                imageReference = managedPath.fileName.toString(),
                artifactRoot = documentArtifactRoot,
                // A picture is not a rendering of anything, so it declares no resolution.
                renderDpi = null,
                rotationDegrees = NO_DECLARED_ROTATION,
            )
        } catch (unreadable: IOException) {
            throw RescanRefusalException(
                RescanRefusalException.MANAGED_COPY_MISSING,
                "the document's managed copy could not be measured as a picture, so its page cannot be read",
            )
        } catch (invalid: IllegalArgumentException) {
            throw RescanRefusalException(
                RescanRefusalException.MANAGED_COPY_CHANGED,
                "the document's managed copy is not a picture this build can read, so its page cannot be read",
            )
        }
        val pixels = image.width?.toLong()?.times(image.height ?: 0) ?: 0L
        if (pixels > maxRenderedPixels) {
            throw RescanRefusalException(
                RescanRefusalException.PAGE_RASTER_UNBOUNDED,
                "this picture declares ${image.width}x${image.height} pixels, past the $maxRenderedPixels one " +
                    "page image may hold; it was not read, because reading it would decode a raster this " +
                    "process cannot bound",
            )
        }
        return RescanPage(image = image, baseline = baseline[ORDINAL_OF_THE_ONLY_PAGE])
    }

    private fun openPdf(managedPath: Path): PDDocument? = try {
        if (!Files.isRegularFile(managedPath)) {
            null
        } else {
            Loader.loadPDF(managedPath.toFile(), IOUtils.createTempFileOnlyStreamCache())
        }
    } catch (unreadable: IOException) {
        null
    }

    companion object {

        const val PDF_MEDIA_TYPE: String = "application/pdf"

        /** The picture media types this build can read a page image from, as the picture reader's own set. */
        val PICTURE_MEDIA_TYPES: Set<String> = setOf("image/png", "image/jpeg", "image/tiff")

        val SUPPORTED_MEDIA_TYPES: Set<String> = PICTURE_MEDIA_TYPES + PDF_MEDIA_TYPE

        /** The ordinal of the only page of a picture document. */
        const val ORDINAL_OF_THE_ONLY_PAGE: Int = 0

        private const val PDF_NOT_OPENED_MESSAGE =
            "the document's managed copy could not be opened, so its pages are unknown; add the file again to " +
                "restore it"

        private const val PICTURE_KEY = "image"
        private const val NO_DECLARED_ROTATION = 0

        /** The key the PDF extractor names a page by, so a page with no baseline gets that same identity. */
        private fun pdfPageKey(page: Int): String = "page:$page"
    }
}

/**
 * Admitting, following and cancelling a rescan of one document.
 *
 * The service is the admission boundary of the feature, and it exists because every one of those steps has to
 * happen against persisted state rather than against what a caller believes:
 *
 * - **The preview is durable.** A preview is what a person agreed to — these bytes, this baseline, this
 *   engine and model set, this page scope — and admission revalidates all of it. Editing a collection default
 *   between opening the dialog and pressing the button therefore invalidates the preview instead of silently
 *   admitting a different reading.
 * - **The snapshot is frozen at admission.** The collection's engine, import mode, profile *revisions*, policy
 *   and external allowance are resolved once and written into the operation, so an edit afterwards changes
 *   future operations only and a resumed attempt reads with what it was admitted with.
 * - **One reading per document.** A document already under an operation in a working stage refuses another,
 *   which is what keeps two candidates from being staged against the same baseline.
 * - **A repeated request is one operation.** The request id and the request's own body are both recorded, so a
 *   retry after a timeout returns the operation that exists and a reused id with another body conflicts.
 */
class RescanService(
    private val paths: AppPaths,
    private val collections: CollectionStore,
    private val documents: DocumentStore,
    private val revisions: DocumentRevisionStore,
    private val jobs: JobStore,
    /** Signals a live worker attempt after persisting the cancellation request. */
    private val cancelAttempt: suspend (infoscry.domain.JobId) -> infoscry.domain.Job = { id -> jobs.cancel(id) },
    private val operations: OcrOperationStore,
    private val mutations: MutationCoordinator,
    private val blockers: DeletionBlockers,
    private val publication: RevisionPublicationService,
    /** Resolving a profile id to the revision it *currently* points at, which is what admission freezes. */
    private val profileOf: (String) -> infoscry.ocr.OcrProfile?,
    private val profileRevisionOf: (String) -> OcrProfileRevision?,
    private val methodCatalog: ReadingMethodCatalog? = null,
    /**
     * How an attempt gets the engine its snapshot selects, so admission can validate prerequisites — and probe
     * the runtime a reading would be identified by — without sending anything.
     *
     * The third argument is the attempt's dispatch authority, and null means "this call may not leave the
     * machine and is not counted against anything": the authority is passed whole rather than as its permit
     * face because an engine wires both of its roles, the permit question and the per-attempt call count only
     * a client can report.
     */
    private val engines: (infoscry.ocr.OcrEngine, OcrSettingsSnapshot, OcrDispatchAuthority?) ->
    infoscry.ocr.PageOcrEngine?,
    /** Whether a page could be embedded at all; null means the pinned model is not installed. */
    private val embedderAvailable: () -> Boolean,
    private val keyAvailable: (String?) -> Boolean = { variable -> variable == null || System.getenv(variable) != null },
    private val pages: RescanPageSource = RescanPageSource(),
    private val clock: () -> Instant = Instant::now,
) {

    // ---- preview ----

    /**
     * What a rescan of one document would do, without reading or sending anything.
     *
     * @throws RescanRefusalException when the document cannot be rescanned at all — another format, a managed
     *   copy that is not the recorded bytes, a missing engine, an unmeasured external profile, or no embedder.
     */
    suspend fun preview(
        collectionId: CollectionId,
        documentId: DocumentId,
        method: ReadingMethod,
    ): RescanPreview {
        val collection = requireActiveCollection(collectionId)
        val document = requireDocument(collectionId, documentId)
        methodCatalog?.require(collectionId, method)
        pageImagesRefusal(document)?.let { throw it }
        val settings = CollectionOcrSettings(collection.ocrLanguages, method)
        val baselineRevisionId = revisions.activeRevisionId(documentId)
        val baseline = baselineRevisionId
            ?.let { revisionId -> revisions.pages(revisionId).associateBy { page -> page.ordinal } }
            .orEmpty()
        // The pages a rescan reads, not all the pages: the dialog's count, the cost and the external allowance
        // all describe what is actually sent, and the pages that keep their published text are not in them.
        val census = pages.census(
            managedCopyOf(document, paths.documentDir(collectionId, documentId)),
            document.mediaType,
            baseline,
        )
        val pageTotal = census.pagesToRead
        val snapshot = snapshotFor(settings, externalPageLimit = if (method is ReadingMethod.Llm &&
            profileOf(method.profileId)?.revision?.scope == OcrEndpointScope.EXTERNAL
        ) pageTotal else 0)
        requireRescanable(document, snapshot)
        val destinations = destinationsOf(snapshot)
        val external = snapshot.transcriptionProfileRevisionId?.let { profileRevisionOf(it)?.scope } == OcrEndpointScope.EXTERNAL
        val upperBound = pageTotal
        val previewId = "preview-" + UUID.randomUUID()
        val snapshotHash = OcrOperationStore.snapshotHashOf(snapshot)
        val estimate = costEstimateOf(snapshot, pageTotal, external)
        return RescanPreview(
            previewId = previewId,
            documentId = documentId,
            baseRevisionId = baselineRevisionId,
            managedHash = document.sha256,
            snapshot = snapshot,
            snapshotHash = snapshotHash,
            pageTotal = pageTotal,
            externalPageUpperBound = upperBound,
            destinations = destinations,
            costEstimate = estimate.first,
            costUnavailableReason = estimate.second,
            externalAllowance = snapshot.externalPageLimit,
            expiresAt = clock().plus(PREVIEW_LIFETIME).toString(),
            documentPages = census.documentPages,
        ).also { preview ->
            operations.recordPreview(
                collectionId.value,
                documentId,
                preview,
                overridesJson = null,
            )
        }
    }

    /** Resolves and validates the method chosen for a retry against this collection's settings. */
    suspend fun resolveChosenReading(
        collection: Collection,
        method: ReadingMethod,
        externalPageLimit: Int = 0,
    ): OcrSettingsSnapshot {
        methodCatalog?.require(collection.id, method)
        val snapshot = snapshotFor(
            CollectionOcrSettings(collection.ocrLanguages, method),
            externalPageLimit = externalPageLimit,
        )
        requireReadingUsable(snapshot)
        return snapshot
    }

    // ---- admission ----

    /**
     * Admits a rescan of one document against a preview, and queues the attempt that reads it.
     *
     * @throws StaleRescanPreviewException when the preview no longer describes the document — another revision
     *   is published now, the managed bytes are not the ones it named, or it has expired.
     * @throws infoscry.storage.OcrRequestConflictException when the request id was already used with another
     *   body; the same request id and body returns the operation that exists.
     */
    suspend fun admitRescan(
        collectionId: CollectionId,
        documentId: DocumentId,
        previewId: String,
        requestId: String,
    ): OcrOperation {
        require(requestId.isNotBlank()) { "a rescan needs a request id" }
        require(previewId.isNotBlank()) { "a rescan needs the preview it was shown" }
        return mutations.withMutation {
            blockers.requireMutationsAllowed()
            val collection = requireActiveCollection(collectionId)
            val document = requireDocument(collectionId, documentId)
            requireNotBeingDeleted(documentId)
            val requestHash = admissionHash(collectionId, documentId, previewId)
            // A repeated request is decided from the durable row before anything else: a caller that retried
            // the same POST after a timeout gets its own operation back, and a caller that reused the request
            // id for another reading is refused rather than given a second one.
            if (operations.requestHash(collectionId.value, documentId, requestId) != null) {
                if (operations.requestHash(collectionId.value, documentId, requestId) != requestHash) {
                    throw infoscry.storage.OcrRequestConflictException(requestId)
                }
                return@withMutation checkNotNull(
                    operations.operationForRequest(collectionId.value, documentId, requestId),
                )
            }
            requireSnapshotResumable(document)
            val preview = operations.preview(previewId)
                ?: throw StaleRescanPreviewException(previewId, "no preview with that id is recorded")
            if (preview.collectionId != collectionId.value || preview.documentId != documentId) {
                // A preview of another document is answered exactly like one that does not exist: a response
                // may not confirm an id the caller did not scope its request to.
                throw StaleRescanPreviewException(previewId, "it was made for another document")
            }
            if (Instant.parse(preview.expiresAt).isBefore(clock())) {
                throw StaleRescanPreviewException(previewId, "it has expired")
            }
            if (preview.managedSha256 != document.sha256) {
                throw StaleRescanPreviewException(previewId, "the document's bytes are not the ones it named")
            }
            val active = revisions.activeRevisionId(documentId)
            if (preview.baseRevisionId != active) {
                throw StaleRescanPreviewException(
                    previewId,
                    "the document publishes ${active ?: "no revision"} now, not the reading it named",
                )
            }
            // The snapshot is revalidated against the selected profile revision and collection settings at
            // admission, so a changed method or profile cannot silently replace the choice shown in preview.
            requireSnapshotStillResolvable(preview.snapshot)
            requirePreviewStillCurrent(preview, collection)
            requireRescanable(document, preview.snapshot)
            // Signal a live worker before the store transaction closes its operation. The transaction below
            // still enforces replacement atomically, including a concurrent admission that wins this race.
            operations.activeOperation(documentId)?.let { previous ->
                cancelWithinMutation(collectionId, documentId, previous.operationId)
            }
            val admitted = operations.admit(
                collectionId = collectionId.value,
                documentId = documentId,
                baseRevisionId = active,
                snapshot = preview.snapshot,
                requestId = requestId,
                requestHash = requestHash,
                // A document whose pages all keep their published text reads none, which an operation cannot
                // record as a positive total; the attempt records the document's page count when it starts.
                pageTotal = preview.pageTotal?.takeIf { it > 0 },
                jobId = null,
            )
            // Admission is durable before the attempt exists: the attempt is created by the claim, in the same
            // write that names it, so a crash between the two leaves an operation without an attempt — the
            // state resume starts from — rather than an attempt whose operation was never written.
            resumeAttempt(admitted)
        }
    }

    // ---- reading and control ----

    /** One operation, readable only under the collection and document the caller named. */
    fun operation(collectionId: CollectionId, documentId: DocumentId, operationId: String): OcrOperation =
        requireScopedOperation(collectionId, documentId, operationId)

    /** One document's operations, newest first, for the details and history views. */
    fun operationsOf(collectionId: CollectionId, documentId: DocumentId): List<OcrOperation> {
        requireDocument(collectionId, documentId)
        return operations.operations(documentId)
    }

    /**
     * Queues another attempt for an operation that stopped — a cancellation, a failure, a missing tool.
     *
     * The snapshot and the counters are the operation's own and are not touched: a resume either continues the
     * reading that was admitted or is refused, because reading with another engine, endpoint or prompt is a
     * different operation and has to be previewed as one.
     */
    suspend fun resume(collectionId: CollectionId, documentId: DocumentId, operationId: String): OcrOperation =
        mutations.withMutation {
            val operation = requireScopedOperation(collectionId, documentId, operationId)
            when (operation.stage) {
                OcrOperationStage.COMPLETE ->
                    throw IllegalArgumentException("this operation is complete; there is nothing to resume")

                else -> Unit
            }
            // The operation keeps the snapshot it was admitted with: a collection default edited since then does
            // not change a reading that is already running or waiting, so only its profile revisions are checked.
            requireActiveCollection(collectionId)
            requireSnapshotStillResolvable(operation.snapshot)
            val document = requireDocument(collectionId, documentId)
            requireNotBeingDeleted(documentId)
            requireSnapshotResumable(document)
            requireRescanable(document, operation.snapshot)
            operations.activeOperation(documentId)?.let { running ->
                if (running.operationId != operation.operationId) {
                    throw OcrOperationConflictException(documentId, running.stage)
                }
            }
            resumeAttempt(operation)
        }

    /**
     * Records a cancellation of one operation and interrupts the attempt that is running it.
     *
     * The request is durable before anything is signalled, so it survives this process. Where the operation is
     * waiting rather than running — no attempt owns it — it ends as cancelled here; where an attempt owns it,
     * the attempt ends it, bounded between pages and provider requests, because only the attempt knows what it
     * has committed. A publication whose authority has moved is past the point a cancellation can undo, which
     * is why the attempt's own stage is what decides.
     */
    suspend fun cancel(collectionId: CollectionId, documentId: DocumentId, operationId: String): OcrOperation =
        mutations.withMutation { cancelWithinMutation(collectionId, documentId, operationId) }

    private suspend fun cancelWithinMutation(
        collectionId: CollectionId,
        documentId: DocumentId,
        operationId: String,
    ): OcrOperation {
        val operation = requireScopedOperation(collectionId, documentId, operationId)
        if (operation.stage.isTerminal) {
            if (operation.stage == OcrOperationStage.CANCELLED) markDocumentCancelledIfLatest(operation)
            return operation
        }
        val jobId = operation.jobId
        if (jobId == null) {
            val cancelled = operations.finish(
                operationId = operation.operationId,
                stage = OcrOperationStage.CANCELLED,
                errorCode = CANCELLED_CODE,
                errorMessage = "this rescan was cancelled before it read anything",
            )
            markDocumentCancelledIfLatest(cancelled)
            return cancelled
        }
        val cancelled = cancelAttempt(infoscry.domain.JobId(jobId))
        return if (cancelled.state == infoscry.domain.JobState.RUNNING) {
            // The attempt owns the end of the operation, bounded between pages: it is the only thing that
            // knows what it committed, and a stage written here could claim a page that never got there.
            operations.recordAttempt(operation.operationId, jobId)
        } else {
            operations.finish(
                operationId = operation.operationId,
                stage = OcrOperationStage.CANCELLED,
                errorCode = CANCELLED_CODE,
                errorMessage = "this rescan was cancelled before it read anything",
            ).also(::markDocumentCancelledIfLatest)
        }
    }

    /** A queued or not-yet-created attempt has no worker to publish its terminal document status. */
    private fun markDocumentCancelledIfLatest(operation: OcrOperation) {
        if (operations.operations(operation.documentId).firstOrNull()?.operationId != operation.operationId) return
        if (documents.isDeletionTarget(operation.documentId)) return
        documents.updateStatus(
            operation.documentId,
            infoscry.domain.DocumentStatus.CANCELLED,
            CANCELLED_CODE,
            "this rescan was cancelled; the document still shows its published text",
        )
    }

    // ---- internals ----

    private suspend fun resumeAttempt(operation: OcrOperation): OcrOperation {
        val collectionId = CollectionId(operation.collectionId)
        // The attempt is claimed and the job created in one write: an operation another attempt already owns
        // refuses rather than queueing a second one, so two workers cannot read the same pages into the same
        // candidate and count the same external pages twice.
        return operations.startAttempt(operation.operationId, OcrOperationStage.PREFLIGHT) {
            val queued = jobs.enqueue(
                type = infoscry.domain.JobType.RESCAN,
                collectionId = collectionId.takeIf { collections.get(it) != null },
                payload = RescanJobPayload(
                    collectionId = operation.collectionId,
                    documentId = operation.documentId.value,
                    operationId = operation.operationId,
                ).encode(),
                total = 1,
            )
            // The document's status and the queued attempt are one durable transition. Otherwise a completed
            // document can briefly be shown as Done while its operation already reports Reading 0/N.
            documents.updateStatus(operation.documentId, infoscry.domain.DocumentStatus.QUEUED)
            queued.id.value
        }
    }

    private fun requireScopedOperation(
        collectionId: CollectionId,
        documentId: DocumentId,
        operationId: String,
    ): OcrOperation {
        requireDocument(collectionId, documentId)
        val operation = operations.operation(operationId)
        if (operation == null || operation.documentId != documentId || operation.collectionId != collectionId.value) {
            // Another collection's or document's operation is answered exactly like one that does not exist.
            throw NoSuchElementException("no rescan operation with id $operationId exists")
        }
        return operation
    }

    private fun requireActiveCollection(collectionId: CollectionId): Collection {
        val collection = collections.get(collectionId)
            ?: throw NoSuchElementException("no collection with id ${collectionId.value} exists")
        if (collection.lifecycle != CollectionLifecycle.ACTIVE) throw CollectionNotActiveException(collectionId)
        return collection
    }

    private fun requireDocument(collectionId: CollectionId, documentId: DocumentId): Document =
        documents.get(documentId)?.takeIf { it.collectionId == collectionId }
            ?: throw NoSuchElementException("no document with id ${documentId.value} exists")

    private fun requireNotBeingDeleted(documentId: DocumentId) {
        if (documents.isDeletionTarget(documentId)) throw DocumentBeingDeletedException(documentId)
    }

    /**
     * Whether this document can be read again from page images at all.
     *
     * The checks run before anything is dispatched: a format with no pages, a picture whose raster is past the
     * bound, an engine this build does not have, an external profile nobody measured, and a missing embedder
     * are all refusals with a remedy — and none of them sends a page anywhere.
     */
    private fun requireRescanable(document: Document, snapshot: OcrSettingsSnapshot) {
        pageImagesRefusal(document)?.let { throw it }
        requireReadingUsable(snapshot)
    }

    /**
     * The refusal for a document whose format has no page images to read, or null when it has them.
     *
     * Public because a retry with a chosen OCR method asks the same question per document, and a second
     * list of readable formats would drift from this one.
     */
    fun pageImagesRefusal(document: Document): RescanRefusalException? =
        if (pages.supports(document.mediaType)) {
            null
        } else {
            RescanRefusalException(
                RescanRefusalException.PAGE_IMAGES_UNSUPPORTED,
                pages.unsupportedReason(document.mediaType),
            )
        }

    /**
     * Whether a reading's settings can be honoured on this machine at all: its engine exists, a replacement
     * could be embedded, and every external profile it dispatches to was measured as able to read an image and
     * has its key. None of it sends a page anywhere.
     */
    private fun requireReadingUsable(snapshot: OcrSettingsSnapshot) {
        if (engines(snapshot.engine, snapshot, null) == null) {
            throw RescanRefusalException(
                RescanRefusalException.ENGINE_UNAVAILABLE,
                "no ${snapshot.engine} engine is configured in this build, so a rescan with the settings this " +
                    "collection selects cannot read a page; configure the engine (or select another one) and " +
                    "preview again. Nothing was read with another engine.",
            )
        }
        if (!embedderAvailable()) {
            throw RescanRefusalException(
                RescanRefusalException.NO_EMBEDDER,
                "the pinned embedding model is not installed, so a replacement could never be published and the " +
                    "document's text would be read for nothing; install the model and preview again. Diagnostic " +
                    "search and source viewing keep working.",
            )
        }
        requireProfile(snapshot.transcriptionProfileRevisionId, OcrProfileRole.TRANSCRIPTION)
    }

    /**
     * Whether an external profile may be dispatched to at all.
     *
     * An unmeasured profile is the case this refuses: "sending page images to an endpoint nobody checked can
     * read one" is not something an admission may assume, and the capability check is what answers it with a
     * synthetic image rather than with a person's document.
     */
    private fun requireProfile(revisionId: String?, role: OcrProfileRole) {
        if (revisionId == null) return
        val revision = profileRevisionOf(revisionId)
            ?: throw RescanRefusalException(
                RescanRefusalException.PROFILE_UNAVAILABLE,
                "the OCR profile revision this rescan would use no longer exists, so no page can be sent " +
                    "through it; select a profile that exists and preview again",
            )
        if (revision.scope != OcrEndpointScope.EXTERNAL) return
        if (!revision.imageCapabilitySupported) {
            throw RescanRefusalException(
                RescanRefusalException.PROFILE_UNMEASURED,
                "the ${role.name.lowercase()} profile this rescan would send pages to has never been measured " +
                    "as able to read an image; run the profile's image capability check first, because a " +
                    "rescan sends page images to it",
            )
        }
        if (!keyAvailable(revision.apiKeyEnvironmentVariable)) {
            throw RescanRefusalException(
                RescanRefusalException.PROFILE_UNAVAILABLE,
                "the environment variable the ${role.name.lowercase()} profile names for its key is not set, " +
                    "so no page was sent; set it and preview again",
            )
        }
    }

    /** The snapshot of one attempt: the settings' engine, mode, allowance and the revisions they name now. */
    private suspend fun snapshotFor(
        settings: CollectionOcrSettings,
        externalPageLimit: Int = 0,
    ): OcrSettingsSnapshot {
        val profileId = (settings.defaultMethod as? ReadingMethod.Llm)?.profileId
        val transcription = profileId?.let {
            requireProfileRevision(it, OcrProfileRole.TRANSCRIPTION)
        }
        val snapshot = OcrSettingsSnapshot.of(
            settings = settings,
            extractorVersion = infoscry.extract.EXTRACTOR_SCHEMA_VERSION,
            transcriptionProfileRevisionId = transcription?.revisionId,
            toolVersion = null,
            modelVersion = null,
            renderDpi = DEFAULT_RENDER_DPI,
        ).copy(externalPageLimit = externalPageLimit)
        // What the engine would read with right now is probed *here*, before the attempt exists, so the
        // fingerprint a resumed attempt looks its checkpoints up by is the one its own runtime produced: a
        // changed tool, model or inference server is then a different key rather than a silent reuse. The
        // probe is asked with no dispatch authority, which is what keeps a capability question from being able
        // to send anything.
        val identity = engines(snapshot.engine, snapshot, null)?.runtimeIdentity()
        return snapshot.copy(runtimeIdentity = identity)
    }

    private fun requireProfileRevision(profileId: String, role: OcrProfileRole): OcrProfileRevision {
        val profile = profileOf(profileId)
            ?: throw RescanRefusalException(
                RescanRefusalException.PROFILE_UNAVAILABLE,
                "no OCR profile with id $profileId exists, so the ${role.name.lowercase()} slot cannot be " +
                    "resolved for a rescan",
            )
        if (!profile.enabled) {
            throw RescanRefusalException(
                RescanRefusalException.PROFILE_UNAVAILABLE,
                "the OCR profile '${profile.name}' is switched off, so a rescan cannot dispatch to it",
            )
        }
        return profile.revision
    }

    /**
     * Re-resolves the snapshot's profile revisions as they stand now, and refuses a snapshot whose profile
     * revision has moved.
     *
     * This is the check that makes "editing a default must not change a running job" hold in both directions:
     * a job already admitted keeps its revisions, and a job that has not been admitted yet may not be admitted
     * against a preview whose scope an edit changed.
     *
     * It deliberately does not compare the snapshot's engine with the collection's. The collection's drift
     * for a preview is decided by [requirePreviewStillCurrent].
     */
    private fun requireSnapshotStillResolvable(snapshot: OcrSettingsSnapshot) {
        listOfNotNull(snapshot.transcriptionProfileRevisionId).forEach { id ->
            val revision = profileRevisionOf(id)
                ?: throw StaleRescanPreviewException(
                    id,
                    "a profile revision it names no longer exists, so it is not the reading that was previewed",
                )
            if (revision.revisionId != id) {
                throw StaleRescanPreviewException(
                    id,
                    "the profile it names points at another revision now, so it is not the reading that was " +
                        "previewed",
                )
            }
        }
    }

    /** Revalidates the explicit method and language shown in the preview, independent of collection defaults. */
    private fun requirePreviewStillCurrent(preview: infoscry.storage.OcrOperationStore.StoredPreview, collection: Collection) {
        val shown = preview.snapshot
        val current = collection.ocrSettings()

        fun refuse(field: String, was: Any?, now: Any?): Nothing = throw StaleRescanPreviewException(
            preview.previewId,
            "the collection's $field is $now now, not the $was this preview showed, so the reading it described " +
                "cannot be produced any more; preview again",
        )

        if (current.language != shown.language) refuse("OCR language", shown.language, current.language)
        val selectedMethod = when (shown.engine) {
            infoscry.ocr.OcrEngine.TESSERACT -> ReadingMethod.Tesseract
            infoscry.ocr.OcrEngine.SURYA -> ReadingMethod.Surya
            infoscry.ocr.OcrEngine.LLM -> {
                val revisionId = shown.transcriptionProfileRevisionId
                    ?: refuse("transcription profile", "none", "missing")
                val revision = profileRevisionOf(revisionId)
                    ?: refuse("transcription profile", revisionId, "missing")
                val activeRevisionId = profileOf(revision.profileId)?.revision?.revisionId
                if (activeRevisionId != revisionId) {
                    refuse("transcription profile", revisionId, activeRevisionId ?: "unavailable")
                }
                ReadingMethod.Llm(revision.profileId)
            }
        }
        try {
            methodCatalog?.require(collection.id, selectedMethod)
        } catch (unavailable: ReadingMethodUnavailableException) {
            refuse("reading method", selectedMethod.id, "unavailable")
        }
    }

    /**
     * Whether the document may still be read again at all, as the lifecycle sees it now.
     *
     * A document being deleted is the case this refuses: a rescan of it would stage a candidate for a document
     * whose rows are on their way out, and the deletion is the thing that owns what happens next.
     */
    private fun requireSnapshotResumable(document: Document) {
        if (documents.isDeletionTarget(document.id)) throw DocumentBeingDeletedException(document.id)
    }

    private fun managedCopyOf(document: Document, documentDirectory: Path): Path {
        if (!Files.isDirectory(documentDirectory)) {
            throw RescanRefusalException(
                RescanRefusalException.MANAGED_COPY_MISSING,
                "the document's managed copy is gone, so nothing can be read again; add the file again to " +
                    "restore it",
            )
        }
        val files = Files.list(documentDirectory).use { entries ->
            entries.filter { Files.isRegularFile(it) }.toList()
        }
        val copy = files.singleOrNull() ?: throw RescanRefusalException(
            RescanRefusalException.MANAGED_COPY_MISSING,
            "the document's managed copy is gone, so nothing can be read again; add the file again to restore it",
        )
        val actual = sha256Of(copy)
        if (actual != document.sha256) {
            throw RescanRefusalException(
                RescanRefusalException.MANAGED_COPY_CHANGED,
                "the document's managed copy is not the file this archive recorded (its hash differs), so it " +
                    "was not read; managed copies are immutable, and comparing the two hashes is what says so",
            )
        }
        return copy
    }

    /** What an external dispatch would be made through, named so a person can see where pages would go. */
    private fun destinationsOf(
        snapshot: OcrSettingsSnapshot,
    ): List<OcrNamedDestination> = buildList {
        snapshot.transcriptionProfileRevisionId?.let { revisionId ->
            profileRevisionOf(revisionId)?.let { revision ->
                add(
                    OcrNamedDestination(
                        role = OcrProfileRole.TRANSCRIPTION.name,
                        engine = snapshot.engine,
                        scope = revision.scope,
                        endpoint = revision.endpoint,
                        model = revision.model,
                        profileRevisionId = revision.revisionId,
                    ),
                )
            }
        }
        if (snapshot.engine != infoscry.ocr.OcrEngine.LLM) {
            add(
                OcrNamedDestination(
                    role = OcrProfileRole.TRANSCRIPTION.name,
                    engine = snapshot.engine,
                    scope = OcrEndpointScope.LOCAL,
                    endpoint = "",
                    model = snapshot.engine.name,
                ),
            )
        }
    }

    /**
     * What the pages would cost, or why no estimate can be made.
     *
     * A missing price is not a zero price. When either the input or the output price of a profile the attempt
     * would dispatch through is unset, the estimate is unavailable with the reason, because an amount computed
     * from a price nobody recorded would be a guess presented as a number. What is *not* guessed either is the
     * token count: the basis says which assumption it used.
     */
    private fun costEstimateOf(
        snapshot: OcrSettingsSnapshot,
        pageTotal: Int?,
        external: Boolean,
    ): Pair<OcrCostEstimate?, String?> =
        infoscry.document.costEstimateOf(snapshot, pageTotal, external, profileRevisionOf)

    private fun admissionHash(collectionId: CollectionId, documentId: DocumentId, previewId: String): String =
        hexHash(listOf("collection=${collectionId.value}", "document=${documentId.value}", "preview=$previewId"))

    private fun sha256Of(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(HASH_BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return HexFormat.of().formatHex(digest.digest())
    }

    private companion object {

        /** How long a preview may be used: long enough to read a page count, short enough to be current. */
        val PREVIEW_LIFETIME: Duration = Duration.ofMinutes(15)

        /** The resolution a rescan renders at, and what its own snapshot records. */
        const val DEFAULT_RENDER_DPI: Int = 200

        const val HASH_BUFFER_BYTES: Int = 64 * 1024

        const val CANCELLED_CODE: String = "RESCAN_CANCELLED"

        private fun hexHash(fields: List<String>): String =
            HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(fields.joinToString("\n").toByteArray(Charsets.UTF_8)),
            )
    }
}
