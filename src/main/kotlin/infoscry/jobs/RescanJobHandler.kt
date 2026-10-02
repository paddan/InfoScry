package infoscry.jobs

import infoscry.chunk.Chunker
import infoscry.config.AppPaths
import infoscry.domain.Collection
import infoscry.domain.CollectionLifecycle
import infoscry.domain.CollectionId
import infoscry.domain.ContentUnit
import infoscry.domain.ContentUnitId
import infoscry.domain.Document
import infoscry.domain.DocumentId
import infoscry.domain.ExtractionMethod
import infoscry.domain.Job
import infoscry.domain.SourceImageProvenance
import infoscry.domain.SourceLocation
import infoscry.document.PageReviewer
import infoscry.document.RescanPage
import infoscry.document.RescanPageSource
import infoscry.document.RevisionPublicationService
import infoscry.embedding.DocumentEmbedder
import infoscry.extract.OcrUnavailableException
import infoscry.extract.TextNormalizer
import infoscry.library.ManagedLibrary
import infoscry.ocr.ImageLlmException
import infoscry.ocr.OcrComparisonException
import infoscry.ocr.OcrDecisionPolicy
import infoscry.ocr.OcrDispatchAuthority
import infoscry.ocr.OcrDispatchStage
import infoscry.ocr.OcrEndpointScope
import infoscry.ocr.OcrExternalOwner
import infoscry.ocr.OcrOperation
import infoscry.ocr.OcrOperationStage
import infoscry.ocr.OcrPageResult
import infoscry.ocr.OcrProfileRevision
import infoscry.ocr.OcrSettingsSnapshot
import infoscry.ocr.PageComparisonInput
import infoscry.ocr.PageDiagnostics
import infoscry.ocr.PageImage
import infoscry.ocr.PageOcrEngine
import infoscry.ocr.PageReview
import infoscry.ocr.PublicationDisposition
import infoscry.ocr.ReviewerRecommendation
import infoscry.ocr.ReviewerScope
import infoscry.ocr.readingTextHash
import infoscry.storage.CollectionNotActiveException
import infoscry.storage.CollectionStore
import infoscry.storage.DocumentBeingDeletedException
import infoscry.storage.DocumentRevisionStore
import infoscry.storage.DocumentStore
import infoscry.storage.JobStore
import infoscry.storage.MutationCoordinator
import infoscry.storage.OcrOperationStore
import infoscry.storage.OcrReviewStore
import infoscry.storage.PageApproval
import infoscry.storage.PublicationPhase
import infoscry.storage.RevisionPageDraft
import infoscry.storage.RevisionPageText
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

/**
 * One page's outcome as the attempt stages it: the text that would be published for it, and whether a person
 * still has to decide about it.
 */
internal data class StagedCandidatePage(
    val text: String,
    val approval: PageApproval,
    val disposition: PublicationDisposition,
    val confidence: Double?,
    val artifactRelativePath: String?,
    val artifactSha256: String?,
)

/**
 * Reads a published document's pages again and replaces its text through a whole reviewed revision.
 *
 * The attempt is deliberately not the import path with other settings. Four things are its own, and each is
 * why a rescan is a job type rather than a mode:
 *
 * - **Nothing published is touched until a whole revision is approved.** Pages are staged into a *candidate*
 *   revision, so a failed, cancelled or half-read attempt leaves the document's text, its index rows and its
 *   evidence exactly as they were. A page nobody has decided about stays pending, which is a state a document
 *   can be in for weeks without being served as if its reading had been accepted.
 * - **Every durable step is one page.** A page's text is committed when it was read and reviewed, its passages
 *   when they were chunked, its vectors when they were embedded. A restart therefore resumes at the page it
 *   stopped at rather than paying for the document again, and a cancellation is bounded between pages and
 *   provider requests.
 * - **No image leaves the machine without an approval for that scope.** The attempt's dispatch authority is
 *   built from the operation's persisted snapshot, its allowance and the newest approval covering *that*
 *   snapshot, and every dispatch — the reading and the review alike — is counted where it happens.
 * - **A failure is a named state with a remedy, never a substitution.** An engine this build does not have, a
 *   missing embedder, an unreadable page, a stale baseline and an unreachable provider each end the operation
 *   in their own stage with their own code; nothing here tries another engine, and a review failure keeps the
 *   baseline rather than discarding a completed reading.
 */
class RescanJobHandler internal constructor(
    private val paths: AppPaths,
    private val collections: CollectionStore,
    private val documents: DocumentStore,
    private val library: ManagedLibrary,
    private val mutations: MutationCoordinator,
    private val jobs: JobStore,
    private val revisions: DocumentRevisionStore,
    private val operations: OcrOperationStore,
    private val reviews: OcrReviewStore,
    private val publication: RevisionPublicationService,
    private val chunker: Chunker,
    private val documentEmbedder: () -> DocumentEmbedder?,
    /** How the attempt gets the engine its snapshot selects, wired to this attempt's dispatch authority. */
    private val engineFor: (infoscry.ocr.OcrEngine, OcrSettingsSnapshot, OcrDispatchAuthority?) -> PageOcrEngine?,
    /** How the attempt gets the reviewer its snapshot selects, wired to the same kind of authority. */
    private val reviewerFor: (OcrSettingsSnapshot, OcrDispatchAuthority?) -> PageReviewer,
    /** How a snapshotted reviewer revision id is resolved, so the authority can read its scope. */
    private val profileRevisionOf: (String) -> OcrProfileRevision?,
    private val pages: RescanPageSource = RescanPageSource(),
) : JobHandler {

    override suspend fun handle(job: Job, stage: JobStage) {
        val payload = RescanJobPayload.decode(job.payload)
        val operationId = payload.operationId
        // The operation is re-read from the database rather than taken from the payload: it is what a resumed
        // attempt continues, and its snapshot is the attempt's whole authority. A row that is gone means the
        // document or its collection was deleted while this attempt was queued, and there is nothing to do.
        val operation = operations.operation(operationId) ?: return
        if (operation.stage.isTerminal) return

        val collectionId = CollectionId(payload.collectionId)
        val documentId = DocumentId(payload.documentId)
        val collection = collections.get(collectionId)
            ?: throw CollectionNotActiveException(collectionId)
        if (collection.lifecycle != CollectionLifecycle.ACTIVE) throw CollectionNotActiveException(collectionId)
        // A document that is being deleted, or is already gone, owns its own outcome: this attempt publishes
        // nothing for it, and the deletion machine is what accounts for what is left.
        val document = documents.get(documentId) ?: throw DocumentBeingDeletedException(documentId)
        if (documents.isDeletionTarget(documentId)) throw DocumentBeingDeletedException(documentId)

        try {
            runAttempt(job, operation, collection, document, stage)
        } catch (cancelled: CancellationException) {
            // A cancellation request ends the operation; a shutdown does not, because the next process
            // continues the same reading from its committed pages. The durable flag decides which this is.
            if (jobs.get(job.id)?.cancelRequested == true) {
                endOperationOutsideTheAttempt(
                    operationId = operationId,
                    stage = OcrOperationStage.CANCELLED,
                    code = CANCELLED_CODE,
                    message = "this rescan was cancelled; the document still shows its published text",
                )
            }
            throw cancelled
        }
    }

    private suspend fun runAttempt(
        job: Job,
        operation: OcrOperation,
        collection: Collection,
        document: Document,
        stage: JobStage,
    ) {
        val snapshot = operation.snapshot

        // ---- preflight: everything that would make the attempt impossible, before a page is read ----
        val embedder = documentEmbedder()
        if (embedder == null) {
            fail(
                stage,
                operation,
                OcrOperationStage.FAILED,
                EMBEDDING_UNAVAILABLE,
                "the pinned embedding model is not installed, so a replacement could never be published and no " +
                    "page was read; install the model and resume. Diagnostic search, keyword search and source " +
                    "viewing keep working.",
            )
        }
        val managedPath = managedCopyOf(document, collection)
            ?: fail(
                stage,
                operation,
                OcrOperationStage.FAILED,
                MANAGED_COPY_CHANGED,
                "the document's managed copy is missing, or is not the file this archive recorded; it was not " +
                    "read, because managed copies are immutable and the recorded hash is what says so",
            )
        if (!pages.supports(document.mediaType)) {
            fail(
                stage,
                operation,
                OcrOperationStage.FAILED,
                PAGE_IMAGES_UNSUPPORTED,
                pages.unsupportedReason(document.mediaType),
            )
        }
        val transcriptionDispatch = dispatchAuthority(
            operation = operation,
            document = document,
            revisionId = snapshot.transcriptionProfileRevisionId,
            dispatchStage = OcrDispatchStage.TRANSCRIPTION,
        )
        val reviewDispatch = dispatchAuthority(
            operation = operation,
            document = document,
            revisionId = snapshot.reviewProfileRevisionId,
            dispatchStage = OcrDispatchStage.REVIEW,
        )
        val engine = engineFor(snapshot.engine, snapshot, transcriptionDispatch)
            ?: fail(
                stage,
                operation,
                OcrOperationStage.NEEDS_TOOL,
                NEEDS_ENGINE_PREFIX + snapshot.engine.name,
                "no ${snapshot.engine} engine is configured in this build, so no page of this document can be " +
                    "read with the settings this rescan was admitted with; install or configure it and resume. " +
                    "Nothing was read with another engine.",
            )
        // What the engine would read with *right now* is compared with what this operation was admitted
        // with. The snapshot's runtime identity is the key a rescan's reuse rests on, so a tool, a set of
        // weights or an inference server that changed between two attempts makes the pages an earlier attempt
        // committed readings of another runtime: they are not reused, and the attempt stops with the remedy
        // that names what has to happen (a new preview, and an operation admitted under the new identity).
        val runtimeIdentity = engine.runtimeIdentity()
        if (runtimeIdentity != snapshot.runtimeIdentity) {
            fail(
                stage,
                operation,
                OcrOperationStage.FAILED,
                RUNTIME_CHANGED,
                "the engine's runtime is not the one this rescan was admitted with (it is now " +
                    "${runtimeIdentity ?: "undescribed"}), so pages already read may not be reused; preview and " +
                    "admit a new rescan, which will be identified by the runtime in use",
            )
        }
        val baselineRevisionId = operation.baseRevisionId
        if (revisions.activeRevisionId(document.id) != baselineRevisionId) {
            // Another publication replaced the reading this operation was admitted against. Comparing pages
            // against text the document no longer publishes is what the refusal exists to prevent.
            fail(
                stage,
                operation,
                OcrOperationStage.FAILED,
                STALE_BASELINE,
                "the document publishes another reading now than the one this rescan was admitted against, so " +
                    "its pages were not compared with text that has moved; preview and admit a new rescan",
            )
        }
        val baseline: Map<Int, RevisionPageText> = baselineRevisionId
            ?.let { revisionId -> revisions.pages(revisionId).associateBy { page -> page.ordinal } }
            .orEmpty()
        val candidate = operation.candidateRevisionId ?: stage.run(STAGE_STAGE) {
            revisions.openCandidate(document.id, baselineRevisionId, PROVENANCE_RESCAN)
        }.also { opened -> operations.recordCandidateRevision(operation.operationId, opened) }
        val documentArtifactRoot = paths.artifactsDir(collection.id, document.id)
        val pageImages = stage.run(STAGE_STAGE) {
            pages.pages(
                document = document,
                managedPath = managedPath,
                mediaType = document.mediaType,
                attemptDirectory = documentArtifactRoot.resolve(RESCAN_PAGES_DIRECTORY),
                documentArtifactRoot = documentArtifactRoot,
                baseline = baseline,
                renderDpi = snapshot.renderDpi,
            )
        }
        // The pages an earlier attempt of this operation already staged count as this attempt's own starting
        // point: the counter is derived from the candidate, so a resumed attempt never double-counts.
        var committed = revisions.pages(candidate).size
        var failed = 0
        stage.run(STAGE_OCR) {
            operations.advance(operation.operationId, OcrOperationStage.OCR)
            operations.recordProgress(
                operationId = operation.operationId,
                pageTotal = pageImages.size,
                committed = committed,
                failed = 0,
            )
        }
        stage.reportProgress(completed = 0, total = pageImages.size)

        // ---- phase 1: read and review, one page at a time ----
        // Everything this phase can refuse — an engine this build cannot use, a provider that will not answer,
        // a baseline the document moved past — ends the operation with the code that says so and the remedy a
        // person can act on. A per-page failure is *not* one of them: it is recorded against the page and the
        // rest of the document is still read.
        var completed = 0
        try {
        for (page in pageImages) {
            completed++
            stage.reportCurrentItem(pageName(completed, pageImages.size))
            val alreadyStaged = revisions.page(candidate, page.image.ordinal)
                ?.let { staged -> staged.sourceImage?.sha256 == page.image.sha256 } == true
            if (alreadyStaged) {
                // These exact pixels were read under this operation's snapshot before: the reading and the
                // review behind it are durable, and reading the page again would be paid for twice.
                stage.reportProgress(completed, pageImages.size)
                continue
            }
            val outcome = try {
                read(page, engine, snapshot)
            } catch (paused: RescanPaused) {
                // A dispatch was refused because this scope has no approval for another page. The operation's
                // waiting state is already durable, and no further page may be read: this attempt ends here
                // and the approval starts another one with the same snapshot and the same counters.
                pauseForApproval(job, operation, stage)
                return
            }
            // A review refusal is not thrown out of the comparison — an unreachable reviewer makes a review
            // uncertain rather than failing a page — so the durable waiting state is what says the scope was
            // spent. It is checked before the page is staged, because the refusal means no further page of
            // this scope may be sent.
            if (isWaitingForApproval(operation.operationId)) {
                pauseForApproval(job, operation, stage)
                return
            }
            val review = outcome.reading?.let { reading ->
                review(page, reading, snapshot, reviewDispatch, baselineRevisionId)
            }
            if (isWaitingForApproval(operation.operationId)) {
                pauseForApproval(job, operation, stage)
                return
            }
            val stagedPage = outcome.reading?.let { reading -> acceptedPageOf(page, reading, review) }
            stage.run(STAGE_COMMIT) {
                if (stagedPage != null) {
                    revisions.appendPage(candidate, draftOf(page, document, stagedPage, documentArtifactRoot))
                    committed++
                } else {
                    // A page with no usable reading keeps what the document publishes for it. That is staged
                    // as an approved page rather than left out, because leaving it out would drop published
                    // text from the replacement; a page with no baseline keeps nothing, because there is
                    // nothing to keep.
                    page.baseline?.let { baselinePage ->
                        revisions.appendPage(
                            candidate,
                            draftOf(
                                page = page,
                                document = document,
                                stagedPage = StagedCandidatePage(
                                    text = baselinePage.extractedText,
                                    approval = PageApproval.APPROVED,
                                    disposition = PublicationDisposition.KEEP,
                                    confidence = baselinePage.meanConfidence,
                                    artifactRelativePath = baselinePage.artifactRelativePath,
                                    artifactSha256 = baselinePage.artifactSha256,
                                ),
                                documentArtifactRoot = documentArtifactRoot,
                            ),
                        )
                        committed++
                    }
                    failed++
                }
                operations.recordProgress(
                    operationId = operation.operationId,
                    pageTotal = pageImages.size,
                    committed = committed,
                    failed = failed,
                )
            }
            stage.reportProgress(completed, pageImages.size)
        }

        } catch (failed: RescanAttemptFailedException) {
            fail(
                stage = stage,
                operation = operation,
                stageName = if (failed.code.startsWith(NEEDS_ENGINE_PREFIX)) {
                    OcrOperationStage.NEEDS_TOOL
                } else {
                    OcrOperationStage.FAILED
                },
                code = failed.code,
                message = failed.message ?: "this rescan could not continue",
            )
        }

        // ---- phase 2: chunk what was staged ----
        stage.run(STAGE_CHUNK) { operations.advance(operation.operationId, OcrOperationStage.CHUNKING) }
        chunkCandidate(operation.operationId, candidate, document, stage)

        // ---- phase 3: embed the passages that have no vector ----
        stage.run(STAGE_EMBED) { operations.advance(operation.operationId, OcrOperationStage.EMBEDDING) }
        embedCandidate(candidate, embedder, stage)

        // ---- phase 4: publish the whole revision, or leave the document's text alone ----
        stage.run(STAGE_INDEX) { operations.advance(operation.operationId, OcrOperationStage.INDEXING) }
        publishCandidate(operation, candidate, stage)
    }

    // ---- one page ----

    /** What reading one page produced: a usable reading, or nothing this attempt may stage as text. */
    private data class PageRead(val reading: OcrPageResult?)

    /** The page's position as the queue reports it: never a path, and never any of the document's text. */
    private fun pageName(position: Int, total: Int): String = "page $position of $total"

    /**
     * Reads one page, or answers that this page has no usable reading.
     *
     * The engine call happens outside any mutation permit: an inference takes seconds and an external call
     * takes longer, and a permit held across it would stop every other writer — and a collection deletion —
     * for as long as it lasts. An engine that cannot read *any* page (a missing tool, a provider that refuses
     * the image form, an unreachable or refusing provider) is the operation's failure rather than this page's,
     * because one row saying "Surya is not installed" is worth more than five hundred identical page failures.
     * An empty reading is not a reading: a page the engine returned nothing for is left to the baseline, and
     * whether its raster is blank paper is a question only the image can answer.
     */
    private suspend fun read(
        page: RescanPage,
        engine: PageOcrEngine,
        snapshot: OcrSettingsSnapshot,
    ): PageRead = try {
        val reading = engine.transcribe(page.image, snapshot)
        if (reading.text.isBlank() || reading.errorCode != null) {
            LOGGER.atInfo()
                .addKeyValue(ORDINAL_FIELD, page.image.ordinal)
                .addKeyValue(CODE_FIELD, reading.errorCode ?: OcrPageResult.EMPTY_READING_CODE)
                .log("a page came back without a usable reading; the document keeps its published text for it")
            PageRead(null)
        } else {
            PageRead(reading)
        }
    } catch (unavailable: OcrUnavailableException) {
        throw RescanAttemptFailedException(
            unavailable.code,
            "the engine could not read this document: ${unavailable.message}",
        )
    } catch (refused: ImageLlmException) {
        if (refused.code == ImageLlmException.EXTERNAL_DISPATCH_NOT_PERMITTED) {
            // The dispatch was refused because this scope has no approval left, and the refusal already wrote
            // the durable waiting state. It is not a failure of the page or of the attempt: the page was never
            // sent, and the operation waits for a person.
            throw RescanPaused()
        }
        // The provider's own refusal, as a safe code with a curated remedy. Nothing is retried here and no
        // other engine is tried: the code names what a person can do about it.
        throw RescanAttemptFailedException(
            refused.code,
            refused.message ?: "the image-model provider refused this page",
        )
    } catch (failure: IOException) {
        LOGGER.atWarn().addKeyValue(ORDINAL_FIELD, page.image.ordinal).setCause(failure)
            .log("a page could not be read; the document keeps its published text for it")
        PageRead(null)
    }

    /**
     * Judges one page's reading against what the document publishes there, or answers that no reviewer is
     * configured.
     *
     * The comparison may dispatch to an external reviewer, so it too runs outside a permit. Every failure it
     * can report — an unreachable reviewer, a truncated or malformed answer, a wrong page — is this page's
     * outcome: the comparison returns an uncertain review whose disposition keeps the baseline, and the
     * completed reading is never discarded because a reviewer failed. A comparison that cannot be *made*
     * (a baseline the document moved past, another prompt or policy version) is the attempt's failure and is
     * thrown, because a decision taken against other text must not be persisted as this page's.
     */
    private suspend fun review(
        page: RescanPage,
        reading: OcrPageResult,
        snapshot: OcrSettingsSnapshot,
        dispatch: OcrDispatchAuthority?,
        baselineRevisionId: String?,
    ): PageReview? {
        val reviewerRevisionId = snapshot.reviewProfileRevisionId ?: return null
        val baselineText = page.baseline?.extractedText
        val input = PageComparisonInput(
            page = page.image,
            candidateText = reading.text,
            reviewProfileRevisionId = reviewerRevisionId,
            baselineRevisionId = page.baseline?.let { baselineRevisionId },
            baselineText = baselineText,
            baselineTextHash = baselineText?.let(::readingTextHash),
            reviewPromptVersion = snapshot.reviewPromptVersion,
            policyVersion = snapshot.policyVersion,
        )
        return try {
            reviewerFor(snapshot, dispatch).compare(input)
        } catch (stale: OcrComparisonException) {
            throw RescanAttemptFailedException(
                stale.code,
                stale.message ?: "the page could not be compared with the reading the document publishes",
            )
        }
    }

    /**
     * What the candidate page becomes: which text is proposed for it, and whether a person still has to
     * decide.
     *
     * The rules the archive promises, in one place:
     *
     * - A reviewer that recommends the new reading does **not** replace anything in pilot mode: the page is
     *   staged as a proposal, and its text becomes searchable only after a person approves it.
     * - A page that keeps the text it already publishes is staged *approved* with that text: retaining it is
     *   not a decision anybody owes, and a revision that holds what the document already said is complete.
     * - A page with **no** published text is never approved by an engine or a model. Whatever it proposes
     *   stays pending and outside retrieval until a person decides, which is what "an uncertain proposal
     *   outside search" means for a document that had no text there.
     */
    private fun acceptedPageOf(
        page: RescanPage,
        reading: OcrPageResult,
        review: PageReview?,
    ): StagedCandidatePage {
        val baseline = page.baseline
        val disposition = review?.disposition ?: deterministicDisposition(page, reading)
        return when {
            disposition == PublicationDisposition.KEEP && baseline != null -> StagedCandidatePage(
                text = baseline.extractedText,
                approval = PageApproval.APPROVED,
                disposition = disposition,
                confidence = baseline.meanConfidence,
                artifactRelativePath = baseline.artifactRelativePath,
                artifactSha256 = baseline.artifactSha256,
            )

            disposition == PublicationDisposition.APPROVE && baseline != null -> StagedCandidatePage(
                text = reading.text,
                approval = PageApproval.APPROVED,
                disposition = disposition,
                confidence = reading.meanConfidence,
                artifactRelativePath = reading.artifactRelativePath,
                artifactSha256 = reading.artifactSha256,
            )

            else -> StagedCandidatePage(
                text = reading.text,
                approval = PageApproval.PENDING,
                disposition = PublicationDisposition.PROPOSE,
                confidence = reading.meanConfidence,
                artifactRelativePath = reading.artifactRelativePath,
                artifactSha256 = reading.artifactSha256,
            )
        }
    }

    /**
     * What the deterministic checks alone decide about a page with no reviewer configured.
     *
     * An identical non-empty pair keeps the text: there is nothing to replace, and nobody has to confirm that
     * a page still says what it said. Everything else is a proposal, because replacing text without a person
     * is what pilot mode does not do — and the acceptance question cannot be answered here at all, because
     * there is no reviewer revision a measured acceptance could have been about.
     */
    private fun deterministicDisposition(page: RescanPage, reading: OcrPageResult): PublicationDisposition {
        val baselineText = page.baseline?.extractedText
        if (baselineText != null && baselineText.isNotBlank() && baselineText == reading.text) {
            return PublicationDisposition.KEEP
        }
        return OcrDecisionPolicy().disposition(
            diagnostics = PageDiagnostics.of(baselineText, reading.text),
            recommendation = ReviewerRecommendation.UNCERTAIN,
            scope = ReviewerScope(reviewerRevisionId = NO_REVIEWER, reviewPromptVersion = 1),
        )
    }

    /** One staged page, in the form the revision store takes it. */
    private fun draftOf(
        page: RescanPage,
        document: Document,
        stagedPage: StagedCandidatePage,
        documentArtifactRoot: Path,
    ): RevisionPageDraft {
        val normalised = TextNormalizer.normalize(stagedPage.text)
        return RevisionPageDraft(
            ordinal = page.image.ordinal,
            unitId = ContentUnitId(page.image.unitId),
            locator = locatorOf(page, document),
            extractedText = normalised.extracted,
            searchText = normalised.search,
            extractionMethod = ExtractionMethod.OCR,
            meanConfidence = stagedPage.confidence,
            artifactRelativePath = stagedPage.artifactRelativePath,
            artifactSha256 = stagedPage.artifactSha256,
            // Which file the reading was made from, named against the root it actually lives under: the page
            // of a PDF this attempt rendered, or the managed copy that *is* a picture document. The two roots
            // are not interchangeable — a reference that resolved against the artifact root would name a file
            // outside it for a picture — and a reader shown the pixels a reading was made from needs both the
            // reference and the hash it verifies against.
            sourceImage = provenanceOf(page.image, documentArtifactRoot),
            approval = stagedPage.approval,
        )
    }

    /**
     * Which root a page image's provenance resolves against: the attempt's artifacts, or the managed copy.
     *
     * The rule is the file's own location rather than a flag: a page this attempt rendered sits under the
     * document's artifact root, while a picture that is the document sits in the managed-copy directory it was
     * imported into. The stored reference is only resolvable by whoever reads the record back, which is what
     * makes the difference matter rather than cosmetic.
     */
    private fun provenanceOf(image: PageImage, documentArtifactRoot: Path): SourceImageProvenance =
        if (image.imagePath.startsWith(documentArtifactRoot.normalize())) {
            image.artifactProvenance(documentArtifactRoot)
        } else {
            image.managedCopyProvenance()
        }

    private fun locatorOf(page: RescanPage, document: Document): SourceLocation =
        page.baseline?.locator ?: when (document.mediaType) {
            RescanPageSource.PDF_MEDIA_TYPE -> SourceLocation.PdfPage(page.image.ordinal + 1)
            else -> SourceLocation.Image(document.originalFilename)
        }

    // ---- chunking and embedding ----

    /**
     * Chunks every staged page that has no passages yet, one page at a time.
     *
     * The pass reads what the candidate holds rather than what this attempt read, so a resumed attempt still
     * re-chunks what a previous process staged and stopped in the middle of — and a page whose passages exist
     * is not chunked again, which is what keeps re-chunking independent of re-reading.
     */
    private suspend fun chunkCandidate(
        operationId: String,
        candidate: String,
        document: Document,
        stage: JobStage,
    ) {
        val staged = revisions.pages(candidate)
        val chunked = revisions.chunks(candidate).mapTo(mutableSetOf()) { chunk -> chunk.unitOrdinal }
        staged.forEach { page ->
            if (page.ordinal in chunked) return@forEach
            val plan = chunker.chunk(
                unit = ContentUnit(
                    id = page.unitId,
                    documentId = document.id,
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
            stage.run(STAGE_CHUNK) { revisions.recordPageChunks(candidate, page.ordinal, plan.drafts) }
        }
        val pending = revisions.pages(candidate).count { page -> page.approval == PageApproval.PENDING }
        stage.run(STAGE_CHUNK) {
            operations.recordProgress(operationId = operationId, pendingReview = pending)
        }
    }

    /**
     * Embeds every staged passage that has no vector, page by page and in bounded batches.
     *
     * A passage is embedded once: a resumed attempt embeds only the passages a previous process did not reach,
     * and the passages already embedded keep the vectors they were embedded with, which are the ones the index
     * will be staged from.
     */
    private suspend fun embedCandidate(
        candidate: String,
        embedder: DocumentEmbedder,
        stage: JobStage,
    ) {
        val byPage = revisions.chunks(candidate).groupBy { chunk -> chunk.unitOrdinal }
        byPage.forEach { (ordinal, chunks) ->
            val ordered = chunks.sortedBy { chunk -> chunk.ordinal }
            if (ordered.none { chunk -> !chunk.isStaged }) return@forEach
            val pending = ordered.filter { chunk -> !chunk.isStaged }
            val vectors = pending.chunked(EMBED_BATCH).flatMap { batch ->
                val embedded = stage.run(STAGE_EMBED) { embedder.embedDocuments(batch.map { it.text }) }
                require(embedded.size == batch.size) {
                    "the embedder returned ${embedded.size} vectors for ${batch.size} passages"
                }
                embedded.toList()
            }
            stage.run(STAGE_EMBED) {
                var next = 0
                revisions.recordChunkVectors(
                    revisionId = candidate,
                    unitOrdinal = ordinal,
                    // Every passage is written, in order: the ones already embedded keep their own vector, so a
                    // resumed pass neither loses them nor re-embeds them.
                    vectors = ordered.map { chunk -> chunk.embedding ?: vectors[next++] },
                )
            }
        }
    }

    // ---- publication ----

    /**
     * Publishes the candidate as the document's whole revision, or leaves its text exactly where it is.
     *
     * Refusing to publish is a first-class outcome rather than a failure: a revision with pages nobody has
     * decided about may not become the document's text, and the publication says so with a code while the
     * document keeps publishing what it published. That refusal is the state "needs review" is read from, and
     * a publication that throws leaves the previous revision active and searchable — the promise the whole
     * publication protocol exists to keep.
     */
    private suspend fun publishCandidate(operation: OcrOperation, candidate: String, stage: JobStage) {
        val pending = revisions.pages(candidate).count { page -> page.approval == PageApproval.PENDING }
        val publicationId = try {
            publication.publish(
                documentId = operation.documentId,
                baseRevisionId = operation.baseRevisionId,
                candidateRevisionId = candidate,
            )
        } catch (deleted: DocumentBeingDeletedException) {
            throw deleted
        } catch (notActive: CollectionNotActiveException) {
            throw notActive
        } catch (failure: Exception) {
            fail(
                stage,
                operation,
                OcrOperationStage.FAILED,
                PUBLICATION_FAILED,
                failure.message
                    ?: "the replacement could not be published, so the document still shows its published text",
            )
        }
        val intent = revisions.intent(publicationId)
        val published = intent?.phase == PublicationPhase.PUBLISHED
        stage.run(STAGE_RECORD) {
            operations.recordProgress(
                operationId = operation.operationId,
                pendingReview = pending,
            )
            operations.finish(
                operationId = operation.operationId,
                stage = OcrOperationStage.COMPLETE,
                errorCode = if (published) null else (intent?.errorCode ?: AWAITING_REVIEW_CODE),
                errorMessage = if (published) {
                    null
                } else {
                    intent?.errorMessage ?: "$pending page(s) of the replacement are waiting for a decision, " +
                        "so the document still shows its published text"
                },
            )
        }
        if (published) {
            LOGGER.atInfo()
                .addKeyValue(DOCUMENT_FIELD, operation.documentId.value)
                .addKeyValue(OPERATION_FIELD, operation.operationId)
                .log("a rescan published a replacement revision")
        }
    }

    /**
     * Whether the attempt must stop because this operation now waits for an external page approval.
     *
     * The waiting state is durable and is what the refusal wrote; the attempt reads it back rather than
     * tracking a flag of its own, so an approval that arrived while the page was being read — or a pause a
     * previous process left behind — is honored the same way.
     */
    private fun isWaitingForApproval(operationId: String): Boolean =
        operations.operation(operationId)?.stage == OcrOperationStage.AWAITING_APPROVAL

    /**
     * Ends the attempt without failing it, because the work is not defective: it is waiting for a person.
     *
     * The operation keeps its snapshot, its candidate and every page it has already committed, and the job
     * ends as complete with the stage saying what it waits for. An approval queues another attempt that
     * continues from exactly here, which is what makes a job larger than the allowance survivable across a
     * restart — and it is why nothing beyond the approved scope was ever sent.
     */
    private suspend fun pauseForApproval(job: Job, operation: OcrOperation, stage: JobStage) {
        stage.run(STAGE_RECORD) { operations.pause(operation.operationId) }
        stage.run(STAGE_RECORD) { jobs.progress(job.id, stage = JobStore.AWAITING_APPROVAL_STAGE) }
        LOGGER.atInfo()
            .addKeyValue(DOCUMENT_FIELD, operation.documentId.value)
            .addKeyValue(OPERATION_FIELD, operation.operationId)
            .log("a rescan waits for an external page scope to be approved before it reads another page")
    }

    /** Ends the operation with a code and a remedy, and fails the attempt with the same code. */
    private suspend fun fail(
        stage: JobStage,
        operation: OcrOperation,
        stageName: OcrOperationStage,
        code: String,
        message: String,
    ): Nothing {
        stage.run(STAGE_RECORD) {
            operations.finish(
                operationId = operation.operationId,
                stage = stageName,
                errorCode = code,
                errorMessage = message,
            )
        }
        throw RescanAttemptFailedException(code, message)
    }

    // ---- dispatch authority ----

    /**
     * The authority one stage of this attempt dispatches through, or null when that stage is local.
     *
     * A local engine is not counted against the allowance at all: the allowance bounds how many pages *leave
     * this machine*, and a page read by an engine on it left nothing. An external stage is given an authority
     * bound to the operation, the document, the immutable profile revision and the snapshot's own allowance,
     * and it is the only thing that may permit or count a dispatch for that stage.
     */
    private fun dispatchAuthority(
        operation: OcrOperation,
        document: Document,
        revisionId: String?,
        dispatchStage: OcrDispatchStage,
    ): OcrDispatchAuthority? {
        if (revisionId == null) return null
        val revision = profileRevisionOf(revisionId) ?: return null
        if (revision.scope != OcrEndpointScope.EXTERNAL) return null
        return OcrDispatchAuthority(
            operations = operations,
            owner = OcrExternalOwner.operation(operation.operationId),
            documentId = document.id,
            stage = dispatchStage,
            profileRevisionId = revisionId,
            configuredAllowance = operation.snapshot.externalPageLimit,
            snapshotHash = OcrOperationStore.snapshotHashOf(operation.snapshot),
            onExhausted = { account ->
                // The waiting state is persisted *before* the refusal reaches the engine, so the page that
                // would have exceeded the allowance is not sent, and a process restarting later reads the same
                // state rather than re-deciding on its own.
                operations.pause(operation.operationId)
                LOGGER.atInfo()
                    .addKeyValue(DOCUMENT_FIELD, document.id.value)
                    .addKeyValue(OPERATION_FIELD, operation.operationId)
                    .addKeyValue(DISTINCT_PAGES_FIELD, account.distinctPages)
                    .addKeyValue(ALLOWANCE_FIELD, account.allowance)
                    .log("an external page scope is spent; the operation waits for an approval")
            },
        )
    }

    /** The document's managed copy, or null when it is missing or is not the file the archive recorded. */
    private fun managedCopyOf(document: Document, collection: Collection): Path? {
        val path = library.managedPathOf(document)
        if (!Files.isRegularFile(path)) return null
        return if (sha256Of(path) == document.sha256) path else null
    }

    /** Ends an operation outside the attempt's own permits, for the states a stopped attempt owes. */
    private suspend fun endOperationOutsideTheAttempt(
        operationId: String,
        stage: OcrOperationStage,
        code: String,
        message: String,
    ) {
        withContext(NonCancellable) {
            runCatching {
                mutations.awaitMutation {
                    operations.finish(operationId, stage, code, message)
                }
            }.onFailure { failure ->
                // The row may already be gone with its document, and a cleanup that cannot write may not
                // replace the cancellation it is recording.
                LOGGER.atWarn().addKeyValue(OPERATION_FIELD, operationId).setCause(failure)
                    .log("a cancelled rescan could not record its own end")
            }
        }
    }

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

        const val STAGE_STAGE = "stage"
        const val STAGE_OCR = "ocr"
        const val STAGE_COMMIT = "commit"
        const val STAGE_CHUNK = "chunk"
        const val STAGE_EMBED = "embed"
        const val STAGE_INDEX = "index"
        const val STAGE_RECORD = "record"

        const val PROVENANCE_RESCAN = "RESCAN"
        const val RESCAN_PAGES_DIRECTORY = "rescan"
        const val NO_REVIEWER = "no-reviewer"

        const val CANCELLED_CODE = "RESCAN_CANCELLED"
        const val STALE_BASELINE = "OCR_STALE_BASELINE"
        const val AWAITING_REVIEW_CODE = "AWAITING_REVIEW"
        const val MANAGED_COPY_CHANGED = "RESCAN_MANAGED_COPY_CHANGED"
        const val RUNTIME_CHANGED = "RESCAN_ENGINE_RUNTIME_CHANGED"
        const val PAGE_IMAGES_UNSUPPORTED = "PAGE_IMAGES_UNSUPPORTED"
        const val EMBEDDING_UNAVAILABLE = "EMBEDDING_UNAVAILABLE"
        const val PUBLICATION_FAILED = "PUBLICATION_FAILED"
        const val NEEDS_ENGINE_PREFIX = "NEEDS_"

        /** How many passages one embedding step holds, so a long document stays interruptible. */
        const val EMBED_BATCH = 64

        const val HASH_BUFFER_BYTES = 64 * 1024

        const val DOCUMENT_FIELD = "document_id"
        const val OPERATION_FIELD = "operation_id"
        const val ORDINAL_FIELD = "page_ordinal"
        const val CODE_FIELD = "error_code"
        const val DISTINCT_PAGES_FIELD = "distinct_external_pages"
        const val ALLOWANCE_FIELD = "external_page_allowance"
    }
}

/**
 * The attempt stopped because a dispatch was refused for want of an external page approval.
 *
 * It is control flow rather than a failure: the page was never sent, the operation's waiting state is already
 * durable, and the approval starts another attempt that continues from here.
 */
private class RescanPaused : RuntimeException("this rescan waits for an external page scope to be approved")

/** The attempt failed in a way the operation already recorded, so the job fails with the same code. */
internal class RescanAttemptFailedException(val code: String, message: String) : IllegalStateException(message)

private val LOGGER = LoggerFactory.getLogger("infoscry.rescan")

/** How a snapshotted reviewer revision id is resolved for a production attempt's dispatch authority. */
internal fun profileRevisionResolver(profiles: infoscry.storage.OcrProfileStore): (String) -> OcrProfileRevision? =
    { revisionId -> profiles.findRevision(revisionId) }

/**
 * The engine factory a production attempt uses: the two local engines and the image-model engine.
 *
 * The image-model engine is built here rather than in the registry because it is wired per attempt: an
 * external reading dispatches pages against *one* operation's allowance, so the engine that reads them is
 * built with that operation's authority. The local engines are the shared ones, and an engine this build does
 * not have answers null rather than a substitute.
 */
internal fun rescanEngineFactory(
    profiles: infoscry.storage.OcrProfileStore,
    localEngines: infoscry.ocr.PageOcrEngines = infoscry.ocr.PageOcrEngines(
        listOfNotNull(infoscry.extract.TesseractOcr(), infoscry.ocr.SuryaOcr.configured()),
    ),
    lookup: (String) -> String? = System::getenv,
): (infoscry.ocr.OcrEngine, OcrSettingsSnapshot, OcrDispatchAuthority?) -> PageOcrEngine? =
    { kind, _, dispatch ->
        if (kind == infoscry.ocr.OcrEngine.LLM) {
            infoscry.ocr.LlmOcr(
                revisionOf = { revisionId -> profiles.findRevision(revisionId) },
                lookup = lookup,
                permits = dispatch,
                calls = dispatch?.let { authority -> authority::attemptAboutToBeSent },
            )
        } else {
            localEngines.engineFor(kind)
        }
    }

/** The reviewer factory a production attempt uses: the comparison service, bound to one operation's scope. */
internal fun rescanReviewerFactory(
    revisions: DocumentRevisionStore,
    reviews: OcrReviewStore,
    profiles: infoscry.storage.OcrProfileStore,
    lookup: (String) -> String? = System::getenv,
): (OcrSettingsSnapshot, OcrDispatchAuthority?) -> PageReviewer = { snapshot, dispatch ->
    val comparison = infoscry.ocr.OcrComparisonService(
        revisions = revisions,
        reviews = reviews,
        revisionOf = { revisionId -> profiles.findRevision(revisionId) },
        // A store-backed policy: whether a page may replace text without a person is resolved from the
        // archive's accepted validation rows on every decision, so an approval can only exist while the row
        // that grants it does. Pilot mode still replaces nothing.
        policy = OcrDecisionPolicy(snapshot.policyVersion, reviews),
        lookup = lookup,
        permits = dispatch,
        calls = dispatch?.let { authority -> authority::attemptAboutToBeSent },
    )
    PageReviewer { input -> comparison.compare(input) }
}
