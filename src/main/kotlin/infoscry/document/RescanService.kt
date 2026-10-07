package infoscry.document

import infoscry.chunk.Chunker
import infoscry.domain.ContentUnit
import infoscry.embedding.DocumentEmbedder
import infoscry.collection.DeletionBlockers
import infoscry.config.AppPaths
import infoscry.domain.CollectionLifecycle
import infoscry.domain.Collection
import infoscry.domain.CollectionId
import infoscry.domain.ContentUnitId
import infoscry.domain.Document
import infoscry.domain.DocumentId
import infoscry.domain.SourceImageRoot
import infoscry.domain.SourceLocation
import infoscry.domain.UnitKind
import infoscry.extract.PageImageSupport
import infoscry.extract.PdfExtractor
import infoscry.extract.PdfPageRenderer
import infoscry.extract.PngPageRenderer
import infoscry.extract.TextNormalizer
import infoscry.jobs.RescanJobPayload
import infoscry.ocr.CollectionOcrSettings
import infoscry.ocr.ExternalDispatchPermitValidator
import infoscry.ocr.OcrCostEstimate
import infoscry.ocr.OcrDispatchAuthority
import infoscry.ocr.OcrEndpointScope
import infoscry.ocr.OcrNamedDestination
import infoscry.ocr.OcrOperation
import infoscry.ocr.OcrOperationStage
import infoscry.ocr.OcrProfileRevision
import infoscry.ocr.OcrProfileRole
import infoscry.ocr.OcrSettingsSnapshot
import infoscry.ocr.PageComparisonInput
import infoscry.ocr.PageImage
import infoscry.ocr.PageReview
import infoscry.ocr.PageImageRenderer
import infoscry.ocr.RescanPreview
import infoscry.ocr.readingTextHash
import infoscry.search.LuceneIndex
import infoscry.storage.CollectionNotActiveException
import infoscry.storage.CollectionStore
import infoscry.storage.DocumentBeingDeletedException
import infoscry.storage.DocumentRevisionStore
import infoscry.storage.DocumentStore
import infoscry.storage.JobStore
import infoscry.storage.MutationCoordinator
import infoscry.storage.OcrOperationConflictException
import infoscry.storage.OcrOperationStore
import infoscry.storage.OcrReviewStore
import infoscry.storage.PageApproval
import infoscry.storage.RescanPreviewOverrides
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

        /** The operation cannot be resumed against the snapshot it was admitted with. */
        const val SNAPSHOT_NOT_RESUMABLE: String = "RESCAN_SNAPSHOT_NOT_RESUMABLE"

        /** Deciding pages is done, but this machine cannot chunk and embed their text, so nothing was published. */
        const val REVIEW_EMBEDDING_UNAVAILABLE: String = "REVIEW_EMBEDDING_UNAVAILABLE"

        /** Chunking or embedding the decided pages failed; the current revision is still the document's text. */
        const val REVIEW_EMBEDDING_FAILED: String = "REVIEW_EMBEDDING_FAILED"
    }
}

/** An engine and profile choice for one rescan, overriding the collection's defaults. */
data class RescanOverrides(
    val engine: infoscry.ocr.OcrEngine? = null,
    val importMode: infoscry.ocr.OcrImportMode? = null,
    val transcriptionProfileId: String? = null,
    val reviewProfileId: String? = null,
    /**
     * The OCR language list for this reading, or null for the collection's. A rescan preview never sets it; a
     * retry with a chosen method may, and it travels through the same resolution as every other choice.
     */
    val language: String? = null,
)



/**
 * Judging one page's comparison, as an attempt uses it.
 *
 * The attempt owns the page, the readings and the staging; the reviewer answers one question about one
 * comparison and publishes nothing, which is why this seam has a single method rather than a service object
 * the handler would have to reach into.
 */
fun interface PageReviewer {

    suspend fun compare(input: PageComparisonInput): PageReview
}

/**
 * One page image an attempt reads, with the reading it is compared against.
 *
 * [baseline] is the page the document *publishes* at this ordinal, or null when it publishes no text there.
 * It is read once, when the attempt starts, and travels with the image so the comparison and the page's unit
 * identity are decided from one reading of the revision rather than from whatever it says later.
 */
data class RescanPage(val image: PageImage, val baseline: RevisionPageText?)

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
    fun pageCount(managedPath: Path, mediaType: String): Int? = when {
        mediaType == PDF_MEDIA_TYPE -> openPdf(managedPath)?.use { it.numberOfPages }
        mediaType in PICTURE_MEDIA_TYPES -> 1
        else -> null
    } ?: throw RescanRefusalException(
        RescanRefusalException.MANAGED_COPY_MISSING,
        "the document's managed copy could not be opened, so its pages are unknown; add the file again to " +
            "restore it",
    )

    /**
     * Renders every page of [document]'s managed copy into [attemptDirectory].
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
                RescanPage(image = image, baseline = baseline[ordinal])
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

        private const val PICTURE_KEY = "image"
        private const val NO_DECLARED_ROTATION = 0

        /** The key the PDF extractor names a page by, so a page with no baseline gets that same identity. */
        private fun pdfPageKey(page: Int): String = "page:$page"
    }
}

/**
 * Admitting, following, approving and cancelling a rescan of one document.
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
 * - **Approval binds a scope, not a person's intent.** An approval names the snapshot hash and the maximum
 *   number of distinct pages, and the dispatch authority reads it from there: a later attempt whose snapshot
 *   differs cannot inherit it.
 */
class RescanService(
    private val paths: AppPaths,
    private val collections: CollectionStore,
    private val documents: DocumentStore,
    private val revisions: DocumentRevisionStore,
    private val jobs: JobStore,
    private val operations: OcrOperationStore,
    private val reviews: OcrReviewStore,
    private val mutations: MutationCoordinator,
    private val blockers: DeletionBlockers,
    private val publication: RevisionPublicationService,
    private val index: () -> LuceneIndex,
    /** Resolving a profile id to the revision it *currently* points at, which is what admission freezes. */
    private val profileOf: (String) -> infoscry.ocr.OcrProfile?,
    private val profileRevisionOf: (String) -> OcrProfileRevision?,
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
    /**
     * The chunker a decided page's text is cut into passages with, or null when this process has none.
     *
     * It is the one an import chunks with, so a person's edit is measured by the same exact tokenizer, prefix,
     * special tokens and repeated header as every other passage and is never truncated.
     */
    private val chunker: () -> Chunker? = { null },
    /** The embedder this process has, or null when there is none; resolved on demand and never cached here. */
    private val embedder: () -> DocumentEmbedder? = { null },
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
        overrides: RescanOverrides = RescanOverrides(),
    ): RescanPreview {
        val collection = requireActiveCollection(collectionId)
        val document = requireDocument(collectionId, documentId)
        val settings = collection.ocrSettings().withOverrides(overrides)
        val snapshot = snapshotFor(settings)
        requireRescanable(document, snapshot)
        val baselineRevisionId = revisions.activeRevisionId(documentId)
        val managedPath = paths.documentDir(collectionId, documentId)
        val pageTotal = pages.pageCount(managedCopyOf(document, managedPath), document.mediaType)
        val destinations = destinationsOf(snapshot, collection)
        val external = snapshot.transcriptionProfileRevisionId?.let { profileRevisionOf(it)?.scope } ==
            OcrEndpointScope.EXTERNAL ||
            snapshot.reviewProfileRevisionId?.let { profileRevisionOf(it)?.scope } == OcrEndpointScope.EXTERNAL
        val upperBound = if (external) pageTotal else 0
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
            // Conservative: a scope whose page total nobody knows needs approval, because there is no bound
            // to check the allowance against.
            approvalRequired = external &&
                (upperBound == null || upperBound > snapshot.externalPageLimit),
            externalAllowance = snapshot.externalPageLimit,
            expiresAt = clock().plus(PREVIEW_LIFETIME).toString(),
        ).also { preview ->
            operations.recordPreview(collectionId.value, documentId, preview)
        }
    }

    /**
     * The reading a choice would make for [collection], validated exactly as a preview validates it.
     *
     * It is the half of [preview] that does not depend on one document: the collection's settings with the
     * choice applied are resolved into the snapshot (profiles must exist and be enabled, the engine's runtime
     * is probed), and the snapshot is then refused when its engine is not in this build, no embedder exists, or
     * an external profile was never measured as image capable or lacks its key. A retry with a chosen OCR
     * method admits through this, so it can never accept a reading a rescan preview would refuse.
     *
     * @throws RescanRefusalException with the same codes a preview answers with.
     */
    suspend fun resolveChosenReading(collection: Collection, overrides: RescanOverrides): OcrSettingsSnapshot {
        val snapshot = snapshotFor(collection.ocrSettings().withOverrides(overrides))
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
            // The snapshot is re-validated against the profiles the settings name *now*, and against every
            // field of the effective settings the preview was taken with: a preview whose profile was
            // repointed, whose engine, mode, language or allowance was edited, or whose reviewer was
            // deselected between being shown and being admitted is a different reading, and admitting it would
            // dispatch a scope and a text nobody looked at.
            requireSnapshotStillResolvable(preview.snapshot, collection)
            requirePreviewStillCurrent(preview, collection)
            requireRescanable(document, preview.snapshot)
            operations.activeOperation(documentId)?.let { running ->
                throw OcrOperationConflictException(documentId, running.stage)
            }
            val admitted = operations.admit(
                collectionId = collectionId.value,
                documentId = documentId,
                baseRevisionId = active,
                snapshot = preview.snapshot,
                requestId = requestId,
                requestHash = requestHash,
                pageTotal = preview.pageTotal,
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
     * Approves an external page scope for one operation, and resumes the attempt that was waiting for it.
     *
     * [expectedSnapshotHash] is what makes this approval *this scope's*: a caller that approves a scope while
     * the operation's snapshot has moved on is refused, because the pages it is authorizing are not the pages
     * it was shown. The authorized maximum is bound to the same hash, so no later attempt of another scope can
     * inherit it.
     */
    suspend fun approveExternal(
        collectionId: CollectionId,
        documentId: DocumentId,
        operationId: String,
        expectedSnapshotHash: String,
        maxDistinctPages: Int,
    ): OcrOperation {
        require(maxDistinctPages >= 1) {
            "an approval authorizes at least one page, was $maxDistinctPages"
        }
        return mutations.withMutation {
            val operation = requireScopedOperation(collectionId, documentId, operationId)
            if (operation.stage.isTerminal) {
                throw RescanRefusalException(
                    RescanRefusalException.SNAPSHOT_NOT_RESUMABLE,
                    "this operation is ${operation.stage}; there is nothing left to approve",
                )
            }
            val snapshotHash = OcrOperationStore.snapshotHashOf(operation.snapshot)
            if (snapshotHash != expectedSnapshotHash) {
                throw StaleRescanPreviewException(
                    operationId,
                    "its settings are not the ones this approval names, so the approval was refused rather " +
                        "than bound to another scope",
                )
            }
            val sent = operations.distinctPageCount(ownerOf(operation))
            if (maxDistinctPages < sent) {
                throw IllegalArgumentException(
                    "the approved scope of $maxDistinctPages pages is smaller than the $sent pages this " +
                        "operation has already sent; an approval can only widen the scope",
                )
            }
            operations.approveExternalScope(
                owner = ownerOf(operation),
                snapshotHash = snapshotHash,
                authorizedDistinctPages = maxDistinctPages,
            )
            // An approval while an attempt is running is *its* approval: the authority resolves the allowance
            // again on every dispatch, so the reading already under way continues past the scope it was
            // waiting on. Starting another attempt for it would be a second worker on one candidate and one
            // set of counters, which is what the claim seam refuses; there is simply nothing to resume.
            if (operations.liveAttempt(operation.operationId) != null) {
                return@withMutation checkNotNull(operations.operation(operation.operationId))
            }
            resumeAttempt(operation)
        }
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

                OcrOperationStage.AWAITING_APPROVAL ->
                    throw IllegalStateException(
                        "this operation waits for external approval; approve a page scope rather than " +
                            "resuming it",
                    )

                else -> Unit
            }
            val collection = requireActiveCollection(collectionId)
            requireSnapshotStillResolvable(operation.snapshot, collection)
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
    suspend fun cancel(collectionId: CollectionId, documentId: DocumentId, operationId: String): OcrOperation {
        val operation = requireScopedOperation(collectionId, documentId, operationId)
        if (operation.stage.isTerminal) return operation
        val jobId = operation.jobId
        if (jobId == null) {
            return operations.finish(
                operationId = operation.operationId,
                stage = OcrOperationStage.CANCELLED,
                errorCode = CANCELLED_CODE,
                errorMessage = "this rescan was cancelled before it read anything",
            )
        }
        val cancelled = jobs.cancel(infoscry.domain.JobId(jobId))
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
            )
        }
    }

    // ---- reviews and decisions ----

    /**
     * The pages of one document that are waiting for a person's decision.
     *
     * Read through the operation so the answer is scoped: another document's proposals are not this
     * operation's to show.
     */
    fun pendingReviews(collectionId: CollectionId, documentId: DocumentId, operationId: String): List<PendingReview> {
        val operation = requireScopedOperation(collectionId, documentId, operationId)
        val candidate = operation.candidateRevisionId ?: return emptyList()
        val staged = revisions.pages(candidate).associateBy { it.ordinal }
        return reviews.pending(documentId).mapNotNull { review ->
            val page = staged[review.ordinal]?.takeIf { review.matchesPending(operation, it) } ?: return@mapNotNull null
            PendingReview(review, imageAvailable = pageImageFile(collectionId, documentId, page, review) != null)
        }
    }

    /**
     * One pending page's candidate text, for the person who has to decide about it.
     *
     * Only a page that has a proposal *and* has not been decided is readable here, and the text is the staged
     * candidate's own — never the published reading, and never a page of another operation. A candidate past
     * [MAX_CANDIDATE_CHARS] is cut and says so: the hash still names the whole text, so a decision is bound to
     * what was staged rather than to the excerpt shown.
     *
     * @throws NoSuchElementException for anything that is not a pending page of this operation, in one answer
     *   that names neither the page's text nor any stored identifier.
     * @throws DocumentBeingDeletedException when the document is on its way out.
     */
    fun pendingCandidate(
        collectionId: CollectionId,
        documentId: DocumentId,
        operationId: String,
        unitId: String,
    ): PendingCandidateReading {
        val (operation, page, _) = pendingPage(collectionId, documentId, operationId, unitId)
        val text = page.extractedText
        val bounded = boundedText(text)
        return PendingCandidateReading(
            unitId = page.unitId.value,
            ordinal = page.ordinal,
            candidateHash = page.textSha256 ?: readingTextHash(text),
            baselineRevisionId = operation.baseRevisionId,
            text = bounded,
            totalChars = text.length,
            truncated = bounded.length < text.length,
        )
    }

    /**
     * The image one pending page's reading was made from: the managed artifact the comparison used.
     *
     * The bytes are read once, from the artifact the page's own record names inside its own document's
     * directory, and served only when their SHA-256 is both the one the page recorded and the one the review
     * was made against and they are a raster this build allow-lists. Nothing here accepts or reveals a path.
     *
     * @throws NoSuchElementException for anything that is not a pending page with an intact image.
     */
    fun pendingImage(
        collectionId: CollectionId,
        documentId: DocumentId,
        operationId: String,
        unitId: String,
    ): PendingPageImage {
        val (_, page, review) = pendingPage(collectionId, documentId, operationId, unitId)
        val file = pageImageFile(collectionId, documentId, page, review) ?: throw noPendingPage()
        val bytes = try {
            Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS).use { it.readNBytes(MAX_IMAGE_BYTES + 1) }
        } catch (_: IOException) {
            throw noPendingPage()
        }
        if (bytes.size > MAX_IMAGE_BYTES || sha256Hex(bytes) != review.imageSha256) throw noPendingPage()
        val mediaType = rasterMediaTypeOf(bytes) ?: throw noPendingPage()
        return PendingPageImage(bytes, mediaType)
    }

    private fun noPendingPage() = NoSuchElementException("no pending review exists for that page of this operation")

    private fun pendingPage(
        collectionId: CollectionId,
        documentId: DocumentId,
        operationId: String,
        unitId: String,
    ): Triple<OcrOperation, RevisionPageText, PageReview> {
        val operation = requireScopedOperation(collectionId, documentId, operationId)
        requireNotBeingDeleted(documentId)
        val candidate = operation.candidateRevisionId ?: throw noPendingPage()
        val page = revisions.pageForUnit(candidate, ContentUnitId(unitId)) ?: throw noPendingPage()
        val review = reviews.pending(documentId).firstOrNull { it.matchesPending(operation, page) }
            ?: throw noPendingPage()
        return Triple(operation, page, review)
    }

    /** Whether this proposal is the one waiting on this staged page: same page, same baseline, same text, undecided. */
    private fun PageReview.matchesPending(operation: OcrOperation, page: RevisionPageText): Boolean =
        page.approval == PageApproval.PENDING &&
            unitId == page.unitId.value &&
            ordinal == page.ordinal &&
            baselineRevisionId == operation.baseRevisionId &&
            candidateHash == (page.textSha256 ?: readingTextHash(page.extractedText))

    /**
     * The file a page's reading was made from, or null when it is not there, not a plain file inside its own
     * document's directory, too large to serve, or not the image the review was made against.
     *
     * The hash is *not* computed here: this is the cheap answer a listing gives for every page, and
     * [pendingImage] is what verifies the bytes it serves.
     */
    private fun pageImageFile(
        collectionId: CollectionId,
        documentId: DocumentId,
        page: RevisionPageText,
        review: PageReview,
    ): Path? {
        val provenance = page.sourceImage ?: return null
        if (provenance.sha256 != review.imageSha256) return null
        val root = when (provenance.root) {
            SourceImageRoot.ARTIFACTS -> paths.artifactsDir(collectionId, documentId)
            SourceImageRoot.MANAGED_COPY -> paths.documentDir(collectionId, documentId)
        }
        return try {
            val relative = Path.of(provenance.relativePath)
            if (relative.isAbsolute) return null
            val base = root.toAbsolutePath().normalize()
            val file = base.resolve(relative).normalize()
            if (file == base || !file.startsWith(base)) return null
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return null
            // A directory of the path that is a link could still lead out: the real location has to be inside too.
            if (!file.toRealPath().startsWith(base.toRealPath())) return null
            file.takeIf { Files.size(it) <= MAX_IMAGE_BYTES }
        } catch (_: IOException) {
            null
        } catch (_: java.nio.file.InvalidPathException) {
            null
        }
    }

    private fun boundedText(text: String): String {
        if (text.length <= MAX_CANDIDATE_CHARS) return text
        val end = if (Character.isHighSurrogate(text[MAX_CANDIDATE_CHARS - 1])) MAX_CANDIDATE_CHARS - 1 else MAX_CANDIDATE_CHARS
        return text.substring(0, end)
    }

    private fun sha256Hex(bytes: ByteArray): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    /** The allow-listed media type the leading bytes of an image name, or null when they are none of them. */
    private fun rasterMediaTypeOf(bytes: ByteArray): String? {
        fun startsWith(vararg prefix: Int) =
            bytes.size >= prefix.size && prefix.indices.all { bytes[it].toInt() and 0xFF == prefix[it] }
        return when {
            startsWith(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) -> "image/png"
            startsWith(0xFF, 0xD8, 0xFF) -> "image/jpeg"
            startsWith('G'.code, 'I'.code, 'F'.code, '8'.code) -> "image/gif"
            startsWith('B'.code, 'M'.code) -> "image/bmp"
            startsWith('R'.code, 'I'.code, 'F'.code, 'F'.code) && bytes.size >= WEBP_HEADER_BYTES &&
                String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"
            startsWith('I'.code, 'I'.code, 0x2A, 0x00) || startsWith('M'.code, 'M'.code, 0x00, 0x2A) -> "image/tiff"
            else -> null
        }
    }

    /**
     * Applies explicit page decisions to an operation's candidate and answers with the operation.
     *
     * A decision names the page and the hash of the text it was taken against, and the guard is the page's own
     * stored hash: a decision taken against another reading — because another rescan staged a page in between,
     * or because the page was decided somewhere else — is refused rather than applied to text it did not see.
     *
     * The three choices mean what the review UI says:
     *
     * - [ReviewChoice.USE_NEW] approves the candidate reading that was proposed.
     * - [ReviewChoice.EDIT] approves the person's own text, which replaces the candidate's.
     * - [ReviewChoice.KEEP] retains what the document publishes. That is only meaningful for a page that
     *   publishes something: a page with no baseline has no text to keep, and the decision is refused with the
     *   reason rather than approving the candidate's text under a "keep" label.
     */
    fun decideReviews(
        collectionId: CollectionId,
        documentId: DocumentId,
        operationId: String,
        requestId: String,
        expectedRevisionId: String,
        decisions: List<ReviewDecision>,
        documentWide: ReviewChoice? = null,
    ): ReviewDecisionResult {
        require(requestId.isNotBlank()) { "a review decision needs a request id" }
        val operation = requireScopedOperation(collectionId, documentId, operationId)
        // The revision guard runs before anything else, so a decision taken against another reading is
        // answered as that conflict whatever state the operation happens to be in.
        requireDecisionsApplyToActiveReading(documentId, expectedRevisionId)
        val candidate = operation.candidateRevisionId
            ?: throw IllegalArgumentException("this operation has staged no replacement to decide about")
        val applied = mutableListOf<AppliedDecision>()
        val staged = revisions.pages(candidate).associateBy { it.ordinal }
        // A document-wide choice is resolved against the server-side pending set rather than against a list the
        // caller sent, so an old tab cannot decide pages that appeared after it was rendered; the revision
        // guard above is what keeps that set the one the caller named.
        val resolved = if (documentWide != null) {
            pendingOrdinals(candidate).map { ordinal ->
                val page = staged.getValue(ordinal)
                ReviewDecision(
                    unitId = page.unitId.value,
                    ordinal = ordinal,
                    candidateHash = page.textSha256 ?: readingTextHash(page.extractedText),
                    choice = documentWide,
                )
            }
        } else {
            decisions
        }
        resolved.forEach { decision ->
            val page = staged[decision.ordinal]
                ?: throw IllegalArgumentException(
                    "this operation staged no page at ordinal ${decision.ordinal}",
                )
            if (page.unitId.value != decision.unitId) {
                throw IllegalArgumentException(
                    "ordinal ${decision.ordinal} of this document is not the page this decision names",
                )
            }
            val storedHash = page.textSha256 ?: readingTextHash(page.extractedText)
            if (storedHash != decision.candidateHash) {
                throw StaleCandidateDecisionException(
                    "the text of page ${decision.ordinal} is not the text this decision was taken against, so " +
                        "it was refused rather than applied to a reading nobody saw",
                )
            }
            applied += applyDecision(operation, candidate, page, decision)
        }
        // The stored backlog is re-derived from the candidate's own pages, so deciding the last proposal
        // reads as "nothing waits for a person" rather than leaving the count the staging pass wrote.
        return ReviewDecisionResult(
            operation = operations.recordProgress(
                operation.operationId,
                pageTotal = operation.pageTotal,
                pendingReview = pendingOrdinals(candidate).size,
            ),
            applied = applied,
        )
    }

    /**
     * Admits the publication of an operation's decided candidate, and answers what happened to it.
     *
     * The candidate is published as one whole revision, so a page still awaiting a decision refuses the
     * publication with the reason instead of dropping that page's text: the archive is never served a
     * half-reviewed document.
     */
    suspend fun publishDecisions(
        collectionId: CollectionId,
        documentId: DocumentId,
        operationId: String,
        expectedRevisionId: String,
    ): PublicationDecisionResult {
        val operation = requireScopedOperation(collectionId, documentId, operationId)
        requireDecisionsApplyToActiveReading(documentId, expectedRevisionId)
        val candidate = operation.candidateRevisionId
            ?: throw IllegalArgumentException("this operation has staged no replacement to publish")
        // A page a person kept or edited carries text the attempt never chunked or embedded. They are made
        // here, before the publication boundary and outside any permit, so a model that fails leaves the
        // document's text exactly as it was and the decisions recorded for the next try.
        completeDecidedPages(documentId, candidate)
        val publicationId = publication.publish(
            documentId = documentId,
            baseRevisionId = operation.baseRevisionId,
            candidateRevisionId = candidate,
        )
        val intent = revisions.intent(publicationId)
        val published = intent?.phase == infoscry.storage.PublicationPhase.PUBLISHED
        // A published candidate has no page left to decide; a refused one still has its backlog. Either way the
        // stored count is what the candidate holds now, so a finished operation never keeps holding its document.
        operations.recordProgress(operation.operationId, pendingReview = pendingOrdinals(candidate).size)
        val finished = if (published) {
            operations.finish(operation.operationId, OcrOperationStage.COMPLETE)
        } else {
            operations.finish(
                operationId = operation.operationId,
                stage = OcrOperationStage.COMPLETE,
                errorCode = intent?.errorCode ?: AWAITING_REVIEW_CODE,
                errorMessage = intent?.errorMessage ?: "the replacement is waiting for a decision on its pages",
            )
        }
        return PublicationDecisionResult(
            operation = finished,
            publicationId = publicationId,
            phase = intent?.phase?.name ?: "UNKNOWN",
            errorCode = intent?.errorCode,
        )
    }

    // ---- internals ----

    /**
     * Gives every approved page of the candidate the passages and vectors the publication requires.
     *
     * Use new publishes a page the attempt already chunked and embedded, so it needs nothing. Keep existing and
     * Edit text replace the staged page with text that has neither, and both are chunked here the same way:
     * from the page's own text with the import's exact tokenizer, so a kept page is measured by the current
     * tokenizer rather than trusting the vectors of an older embedding, and an edit is never cut to fit. Only
     * what is missing is done, which is what lets a retry after a failed embedding resume without repeating
     * the pages that already have their vectors.
     *
     * Nothing here changes what readers search: the candidate is not published until the whole set exists.
     *
     * @throws RescanRefusalException when this process cannot chunk or embed, or the embedder fails; the
     *   words are curated and never carry the provider's own.
     */
    private suspend fun completeDecidedPages(documentId: DocumentId, candidate: String) {
        val pages = revisions.pages(candidate)
        // A page still owed a decision refuses the whole publication on its own; nothing is worth computing.
        if (pages.any { page -> page.approval == PageApproval.PENDING }) return
        val chunksByPage = revisions.chunks(candidate).groupBy { chunk -> chunk.unitOrdinal }
        val unchunked = pages.filter { page -> page.approval == PageApproval.APPROVED && page.ordinal !in chunksByPage }
        val unembedded = chunksByPage.values.any { chunks -> chunks.any { chunk -> !chunk.isStaged } }
        if (unchunked.isEmpty() && !unembedded) return

        // Resolved outside any permit: the first call can load a model. No embedder is a refusal rather than a
        // reason to measure or embed some other way.
        val resolvedEmbedder = try {
            embedder()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            null
        }
        val resolvedChunker = chunker()
        if (resolvedEmbedder == null || resolvedChunker == null) {
            throw RescanRefusalException(
                RescanRefusalException.REVIEW_EMBEDDING_UNAVAILABLE,
                "this machine has no embedding model available, so the decided pages cannot be indexed and " +
                    "nothing was published; the document still shows its current text and your decisions are " +
                    "kept. Keyword search and source viewing are unaffected",
            )
        }
        try {
            withContext(Dispatchers.IO) {
                unchunked.forEach { page ->
                    val plan = resolvedChunker.chunk(
                        unit = ContentUnit(
                            id = page.unitId,
                            documentId = documentId,
                            ordinal = page.ordinal,
                            locator = page.locator,
                            extractedText = page.extractedText,
                            searchText = page.searchText,
                            artifactRelativePath = page.artifactRelativePath,
                            artifactSha256 = page.artifactSha256,
                            meanConfidence = page.meanConfidence,
                            extractionMethod = page.extractionMethod,
                        ),
                        maxSequenceTokens = Chunker.DEFAULT_MAX_SEQUENCE_TOKENS,
                        overlapTokens = Chunker.DEFAULT_OVERLAP_TOKENS,
                    )
                    revisions.recordPageChunks(candidate, page.ordinal, plan.drafts)
                }
                revisions.chunks(candidate).groupBy { chunk -> chunk.unitOrdinal }.forEach { (ordinal, chunks) ->
                    val ordered = chunks.sortedBy { chunk -> chunk.ordinal }
                    val missing = ordered.filter { chunk -> !chunk.isStaged }
                    if (missing.isEmpty()) return@forEach
                    val vectors = missing.chunked(EMBED_BATCH).flatMap { batch ->
                        val embedded = resolvedEmbedder.embedDocuments(batch.map { chunk -> chunk.text })
                        check(embedded.size == batch.size) {
                            "the embedder returned ${embedded.size} vectors for ${batch.size} passages"
                        }
                        embedded
                    }
                    var next = 0
                    // Every passage of the page is written together, in order: the ones already embedded keep
                    // the vector they have.
                    revisions.recordChunkVectors(
                        candidate,
                        ordinal,
                        ordered.map { chunk -> chunk.embedding ?: vectors[next++] },
                    )
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // What the model threw may carry a provider's text, so it goes to the log as a type only.
            org.slf4j.LoggerFactory.getLogger("infoscry.document").atWarn()
                .addKeyValue("document", documentId.value)
                .addKeyValue("error", failure::class.simpleName)
                .log("the passages of decided pages could not be chunked and embedded")
            throw RescanRefusalException(
                RescanRefusalException.REVIEW_EMBEDDING_FAILED,
                "the decided pages could not be chunked and embedded, so nothing was published; the document " +
                    "still shows its current text and your decisions are kept. Publish again to retry",
            )
        }
    }

    private fun applyDecision(
        operation: OcrOperation,
        candidate: String,
        page: RevisionPageText,
        decision: ReviewDecision,
    ): AppliedDecision {
        when (decision.choice) {
            ReviewChoice.USE_NEW -> {
                revisions.recordPageApproval(candidate, page.ordinal, PageApproval.APPROVED)
                return AppliedDecision(decision.ordinal, decision.choice.name, page.unitId.value)
            }

            ReviewChoice.EDIT -> {
                val edit = decision.text?.takeIf { it.isNotBlank() }
                    ?: throw IllegalArgumentException("an edited page needs the text the person wrote")
                val normalised = TextNormalizer.normalize(edit)
                revisions.appendPage(
                    candidate,
                    infoscry.storage.RevisionPageDraft(
                        ordinal = page.ordinal,
                        unitId = page.unitId,
                        locator = page.locator,
                        extractedText = normalised.extracted,
                        searchText = normalised.search,
                        extractionMethod = page.extractionMethod,
                        meanConfidence = page.meanConfidence,
                        artifactRelativePath = page.artifactRelativePath,
                        artifactSha256 = page.artifactSha256,
                        sourceImage = page.sourceImage,
                        // An edit is a person's decision, which is the one thing that may approve a page.
                        approval = PageApproval.APPROVED,
                    ),
                )
                return AppliedDecision(decision.ordinal, decision.choice.name, page.unitId.value)
            }

            ReviewChoice.KEEP -> {
                val baseline = operation.baseRevisionId
                    ?.let { revisionId -> revisions.page(revisionId, page.ordinal) }
                    ?: throw IllegalArgumentException(
                        "page ${page.ordinal} publishes no text, so there is nothing to keep: a page with no " +
                            "baseline has to be read again or excluded deliberately",
                    )
                revisions.appendPage(
                    candidate,
                    infoscry.storage.RevisionPageDraft(
                        ordinal = page.ordinal,
                        unitId = baseline.unitId,
                        locator = baseline.locator,
                        extractedText = baseline.extractedText,
                        searchText = baseline.searchText,
                        extractionMethod = baseline.extractionMethod,
                        meanConfidence = baseline.meanConfidence,
                        artifactRelativePath = baseline.artifactRelativePath,
                        artifactSha256 = baseline.artifactSha256,
                        sourceImage = baseline.sourceImage,
                        approval = PageApproval.APPROVED,
                    ),
                )
                return AppliedDecision(decision.ordinal, decision.choice.name, page.unitId.value)
            }
        }
    }

    private fun pendingOrdinals(candidate: String): List<Int> =
        revisions.pages(candidate).filter { it.approval == PageApproval.PENDING }.map { it.ordinal }

    private fun requireDecisionsApplyToActiveReading(documentId: DocumentId, expectedRevisionId: String) {
        val active = revisions.activeRevisionId(documentId)
        if (active != expectedRevisionId) {
            throw StaleActiveRevisionException(
                "the document publishes ${active ?: "no revision"} now, not the reading this decision names, " +
                    "so it was refused rather than applied to text nobody saw",
            )
        }
    }

    private suspend fun resumeAttempt(operation: OcrOperation): OcrOperation {
        val collectionId = CollectionId(operation.collectionId)
        // The attempt is claimed and the job created in one write: an operation another attempt already owns
        // refuses rather than queueing a second one, so two workers cannot read the same pages into the same
        // candidate and count the same external pages twice.
        return operations.startAttempt(operation.operationId, OcrOperationStage.PREFLIGHT) {
            jobs.enqueue(
                type = infoscry.domain.JobType.RESCAN,
                collectionId = collectionId.takeIf { collections.get(it) != null },
                payload = RescanJobPayload(
                    collectionId = operation.collectionId,
                    documentId = operation.documentId.value,
                    operationId = operation.operationId,
                ).encode(),
                total = 1,
            ).id.value
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
        requireProfile(snapshot.reviewProfileRevisionId, OcrProfileRole.REVIEW)
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
    private suspend fun snapshotFor(settings: CollectionOcrSettings): OcrSettingsSnapshot {
        val transcription = settings.transcriptionProfileId?.let { profileId ->
            requireProfileRevision(profileId, OcrProfileRole.TRANSCRIPTION)
        }
        val review = settings.reviewProfileId?.let { profileId ->
            requireProfileRevision(profileId, OcrProfileRole.REVIEW)
        }
        val snapshot = OcrSettingsSnapshot.of(
            settings = settings,
            extractorVersion = infoscry.extract.EXTRACTOR_SCHEMA_VERSION,
            transcriptionProfileRevisionId = transcription?.revisionId,
            reviewProfileRevisionId = review?.revisionId,
            toolVersion = null,
            modelVersion = null,
            renderDpi = DEFAULT_RENDER_DPI,
        )
        // What the engine would read with right now is probed *here*, before the attempt exists, so the
        // fingerprint a resumed attempt looks its checkpoints up by is the one its own runtime produced: a
        // changed tool, model or inference server is then a different key rather than a silent reuse. The
        // probe is asked with no dispatch authority, which is what keeps a capability question from being able
        // to send anything.
        val identity = engines(settings.engine, snapshot, null)?.runtimeIdentity()
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
     * Re-resolves the snapshot's profile revisions as they stand now, and refuses a snapshot whose scope has
     * moved.
     *
     * This is the check that makes "editing a default must not change a running job" hold in both directions:
     * a job already admitted keeps its revisions, and a job that has not been admitted yet may not be admitted
     * against a preview whose scope an edit changed.
     */
    private fun requireSnapshotStillResolvable(snapshot: OcrSettingsSnapshot, collection: Collection) {
        listOfNotNull(snapshot.transcriptionProfileRevisionId, snapshot.reviewProfileRevisionId).forEach { id ->
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
        val scope = collection.ocrSettings()
        if (scope.engine != snapshot.engine && snapshot.engine == infoscry.ocr.OcrEngine.LLM) {
            throw StaleRescanPreviewException(
                "collection",
                "the collection's engine is ${scope.engine} now, not ${snapshot.engine}",
            )
        }
    }

    /**
     * Whether the settings a preview showed are still the settings this collection would produce.
     *
     * This is the whole of what a person agreed to, re-resolved at the moment it is acted on. The preview's
     * own overrides are re-applied first, so "the collection's engine was edited" and "this preview never
     * used the collection's engine" stay two different answers: an overridden field cannot drift, because
     * re-applying the override yields exactly what was shown. Everything not overridden is compared field by
     * field, and a profile slot is compared as the revision it resolves to *now*, because a profile repointed
     * at another revision is a different reading whether or not its id changed.
     *
     * The allowance is part of this on purpose: a preview shown with no external pages allowed, admitted after
     * the collection was raised to send twenty, would send pages the person never agreed to.
     */
    private fun requirePreviewStillCurrent(preview: infoscry.storage.OcrOperationStore.StoredPreview, collection: Collection) {
        val shown = preview.snapshot
        val current = collection.ocrSettings().withOverrides(preview.overrides.asOverrides())

        fun refuse(field: String, was: Any?, now: Any?): Nothing = throw StaleRescanPreviewException(
            preview.previewId,
            "the collection's $field is $now now, not the $was this preview showed, so the reading it described " +
                "cannot be produced any more; preview again",
        )

        if (current.engine != shown.engine) refuse("engine", shown.engine, current.engine)
        if (current.importMode != shown.mode) refuse("import mode", shown.mode, current.importMode)
        if (current.language != shown.language) refuse("OCR language", shown.language, current.language)
        if (current.externalPageLimit != shown.externalPageLimit) {
            refuse("external page allowance", shown.externalPageLimit, current.externalPageLimit)
        }
        val transcription = selectedRevisionOf(current.transcriptionProfileId)
        if (transcription != shown.transcriptionProfileRevisionId) {
            refuse(
                "transcription profile",
                shown.transcriptionProfileRevisionId ?: "none",
                transcription ?: "none",
            )
        }
        val review = selectedRevisionOf(current.reviewProfileId)
        if (review != shown.reviewProfileRevisionId) {
            refuse("review profile", shown.reviewProfileRevisionId ?: "none", review ?: "none")
        }
    }

    /**
     * The profile revision a profile slot resolves to right now, or null when the slot is empty or its
     * profile cannot be dispatched to.
     *
     * A profile that was switched off resolves to nothing rather than throwing here: the question this
     * answers is "is this the same selection the person was shown", and a deselected or disabled profile is a
     * different answer, not a missing one.
     */
    private fun selectedRevisionOf(profileId: String?): String? =
        profileId?.let { id -> profileOf(id)?.takeIf { profile -> profile.enabled }?.revision?.revisionId }

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
        collection: Collection,
    ): List<OcrNamedDestination> = buildList {
        listOf(
            OcrProfileRole.TRANSCRIPTION to snapshot.transcriptionProfileRevisionId,
            OcrProfileRole.REVIEW to snapshot.reviewProfileRevisionId,
        ).forEach { (role, revisionId) ->
            if (revisionId == null) return@forEach
            val revision = profileRevisionOf(revisionId) ?: return@forEach
            add(
                OcrNamedDestination(
                    role = role.name,
                    engine = if (role == OcrProfileRole.TRANSCRIPTION) snapshot.engine else infoscry.ocr.OcrEngine.LLM,
                    scope = revision.scope,
                    endpoint = revision.endpoint,
                    model = revision.model,
                    profileRevisionId = revision.revisionId,
                ),
            )
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
    ): Pair<OcrCostEstimate?, String?> {
        if (!external) return null to "nothing is sent off this machine, so there is no external cost"
        if (pageTotal == null) {
            return null to "the document's page count is unknown, so no estimate can be made"
        }
        val revisions = listOfNotNull(snapshot.transcriptionProfileRevisionId, snapshot.reviewProfileRevisionId)
            .mapNotNull { revisionId -> profileRevisionOf(revisionId) }
            .filter { revision -> revision.scope == OcrEndpointScope.EXTERNAL }
        if (revisions.isEmpty()) return null to "no external profile is selected"
        val unpriced = revisions.firstOrNull { it.inputPricePerMillion <= 0.0 || it.outputPricePerMillion <= 0.0 }
        if (unpriced != null) {
            return null to "profile revision ${unpriced.revisionId} names no input or output price, so the " +
                "cost of sending $pageTotal pages is unavailable"
        }
        val perMillion = revisions.sumOf { revision ->
            revision.inputPricePerMillion + revision.outputPricePerMillion
        }
        val reviewsPerPage = if (snapshot.reviewProfileRevisionId != null) 1 else 0
        val tokensPerPage = ASSUMED_TOKENS_PER_PAGE
        val amount = pageTotal.toDouble() * (1 + reviewsPerPage) * tokensPerPage * perMillion / 1_000_000.0
        return OcrCostEstimate(
            amountUsd = amount,
            basis = "$pageTotal pages × ${1 + reviewsPerPage} call(s) per page at an assumed $tokensPerPage " +
                "tokens each, priced at $perMillion per million tokens",
        ) to null
    }

    private fun ownerOf(operation: OcrOperation) =
        infoscry.ocr.OcrExternalOwner.operation(operation.operationId)

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

        /**
         * What one page image is assumed to cost in tokens when an estimate is made.
         *
         * It is an assumption and the estimate says so: a page image's token cost depends on the provider's
         * tiling, which this build cannot know before the first page is sent. A number that is *only* based on
         * recorded prices and a named assumption is still an estimate; inventing one without either is not.
         */
        const val ASSUMED_TOKENS_PER_PAGE: Int = 2_000

        const val HASH_BUFFER_BYTES: Int = 64 * 1024

        /** The most characters of one candidate page a review read returns; the rest is reported, not sent. */
        const val MAX_CANDIDATE_CHARS: Int = 262_144

        /** The largest page image a review read serves. Rendered pages are bounded well below this. */
        const val MAX_IMAGE_BYTES: Int = 32 * 1024 * 1024

        /** Bytes needed to tell a WebP container (`RIFF`, a size, `WEBP`). */
        const val WEBP_HEADER_BYTES: Int = 12

        /** How many passages one embedding call holds, so a long page stays interruptible. */
        const val EMBED_BATCH: Int = 64

        const val CANCELLED_CODE: String = "RESCAN_CANCELLED"

        const val AWAITING_REVIEW_CODE: String = "AWAITING_REVIEW"

        private fun hexHash(fields: List<String>): String =
            HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(fields.joinToString("\n").toByteArray(Charsets.UTF_8)),
            )
    }
}

/** One explicit page decision: which page, the text it was taken against, and what to do with it. */
data class ReviewDecision(
    val unitId: String,
    val ordinal: Int,
    /** The hash of the candidate text this decision was taken against. */
    val candidateHash: String,
    val choice: ReviewChoice,
    /** The person's own text, required exactly for [ReviewChoice.EDIT]. */
    val text: String? = null,
) {

    init {
        require(unitId.isNotBlank()) { "a review decision names the page it is about" }
        require(ordinal >= 0) { "a review decision names a page ordinal, was $ordinal" }
        require(candidateHash.isNotBlank()) { "a review decision names the text it was taken against" }
        require((choice == ReviewChoice.EDIT) == (text != null)) {
            "an edited page carries the person's text, and no other decision does"
        }
    }
}

/** What a person may do with one proposed page. */
enum class ReviewChoice {
    KEEP,
    USE_NEW,
    EDIT,
}

/** One proposal waiting for a person, and whether an intact-by-record image of its page can be requested. */
data class PendingReview(val review: PageReview, val imageAvailable: Boolean)

/** One pending page's candidate text as a review read returns it: bounded, with what is needed to spot staleness. */
data class PendingCandidateReading(
    val unitId: String,
    val ordinal: Int,
    /** The hash of the *whole* candidate text; a decision is guarded by it. */
    val candidateHash: String,
    val baselineRevisionId: String?,
    val text: String,
    val totalChars: Int,
    val truncated: Boolean,
)

/** One page image's verified bytes and the allow-listed media type they were recognised as. */
class PendingPageImage(val bytes: ByteArray, val mediaType: String)

/** One decision as it was applied, so the caller can show what happened to each page. */
data class AppliedDecision(val ordinal: Int, val choice: String, val unitId: String)

/** The outcome of one decision batch, with the operation as it stands after it. */
data class ReviewDecisionResult(val operation: OcrOperation, val applied: List<AppliedDecision>)

/** What admitting a publication of decided pages answered. */
data class PublicationDecisionResult(
    val operation: OcrOperation,
    val publicationId: String,
    val phase: String,
    val errorCode: String?,
)

/** A decision was taken against another reading of the page than the one the operation holds. */
class StaleCandidateDecisionException(message: String) : IllegalStateException(message)

/** A decision names a document revision the document no longer publishes. */
class StaleActiveRevisionException(message: String) : IllegalStateException(message)

/** The stored overrides of a preview, as the choice a preview request made. */
private fun RescanPreviewOverrides?.asOverrides(): RescanOverrides = RescanOverrides(
    engine = this?.engine,
    importMode = this?.importMode,
    transcriptionProfileId = this?.transcriptionProfileId,
    reviewProfileId = this?.reviewProfileId,
)

/** The collection's OCR settings with a caller's overrides applied. */
private fun CollectionOcrSettings.withOverrides(overrides: RescanOverrides): CollectionOcrSettings =
    CollectionOcrSettings(
        language = overrides.language ?: language,
        engine = overrides.engine ?: engine,
        importMode = overrides.importMode ?: importMode,
        transcriptionProfileId = overrides.transcriptionProfileId ?: transcriptionProfileId,
        reviewProfileId = overrides.reviewProfileId ?: reviewProfileId,
        externalPageLimit = externalPageLimit,
    )
