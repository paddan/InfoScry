package infoscry.ocr

import infoscry.chunk.Chunker
import infoscry.domain.ContentUnit
import infoscry.domain.ContentUnitId
import infoscry.domain.Document
import infoscry.domain.ExtractionMethod
import infoscry.domain.SourceImageProvenance
import infoscry.domain.SourceLocation
import infoscry.document.RescanPage
import infoscry.document.RescanPageSource
import infoscry.document.RevisionPublicationService
import infoscry.embedding.DocumentEmbedder
import infoscry.extract.TextNormalizer
import infoscry.jobs.JobStage
import infoscry.storage.CollectionNotActiveException
import infoscry.storage.DocumentBeingDeletedException
import infoscry.storage.DocumentRevisionStore
import infoscry.storage.OcrOperationStore
import infoscry.storage.PageApproval
import infoscry.storage.PublicationPhase
import infoscry.storage.RevisionPageDraft
import java.nio.file.Path

/**
 * One page's outcome as the attempt stages it: the text that would be published for it, and whether a person
 * still has to decide about it.
 */
internal data class StagedCandidatePage(
    val text: String,
    val approval: PageApproval,
    val confidence: Double?,
    val artifactRelativePath: String?,
    val artifactSha256: String?,
)

/**
 * What happens to a reading between being read and being published: the page a reading becomes, and the
 * phases a whole candidate revision goes through — chunking, embedding and publication.
 *
 * It is one component rather than private members of an attempt because every attempt that reads pages to
 * build a revision needs exactly these steps and exactly these rules: an attempt decides what a page's
 * reading proposes, stages it, chunks and embeds the passages that have no vector yet, and publishes the
 * whole revision or leaves the document's text alone. The store writes are the attempt's own durable
 * checkpoints, so every step is taken under the attempt's [JobStage] and is one step in the job's progress.
 *
 * Two attempts use it, and what they do with a pass is their own: a rescan records every step in its
 * operation row and finishes that operation with the publication's outcome, while a check-and-improve import
 * records nothing here and reads the outcome from the publication attempt itself, because what it owes a
 * reader is its document's status rather than an operation's stage.
 *
 * What is deliberately not here is any choice about *how* a page is read or reviewed, which engines and
 * providers may be dispatched to, and whether an attempt may continue: those belong to the attempt that
 * holds the operation's snapshot and its dispatch authority.
 */
internal class CandidateRevisionPhases(
    private val revisions: DocumentRevisionStore,
    private val chunker: Chunker,
    /**
     * The operations whose progress these phases record, or null when the attempt keeps none.
     *
     * A rescan's progress is its operation row, and every pass here writes to it so a resumed attempt can
     * see where the previous process got to. An import keeps its own record — a job's progress and a
     * document's status — and has no operation row to write, which is why this is a dependency an attempt
     * may not have rather than one it must.
     */
    private val operations: OcrOperationStore? = null,
    private val publication: RevisionPublicationService,
) {

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
     * - With **no** review profile configured ([reviewerConfigured] is false) there is no reviewer who could
     *   ever approve a page, so the deterministic rule is the decision: a page whose reading is identical to
     *   its published non-blank text keeps that text (approved), and every other page — with or without a
     *   published text — is staged *approved* with the new reading. This is the product owner's decision for
     *   a collection that runs without a reviewer; nothing is left pending that nobody could ever resolve.
     *
     * With a reviewer configured, the first three rules are the whole decision, and nothing changes for it.
     */
    fun acceptedPageOf(page: RescanPage, reading: OcrPageResult): StagedCandidatePage {
        val baseline = page.baseline
        val baselineText = baseline?.extractedText
        return if (baseline != null && !baselineText.isNullOrBlank() && baselineText == reading.text) {
            StagedCandidatePage(
                text = baselineText,
                approval = PageApproval.APPROVED,
                confidence = baseline.meanConfidence,
                artifactRelativePath = baseline.artifactRelativePath,
                artifactSha256 = baseline.artifactSha256,
            )
        } else {
            StagedCandidatePage(
                text = reading.text,
                approval = PageApproval.APPROVED,
                confidence = reading.meanConfidence,
                artifactRelativePath = reading.artifactRelativePath,
                artifactSha256 = reading.artifactSha256,
            )
        }
    }

    /** One staged page, in the form the revision store takes it. */
    fun draftOf(
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
     *
     * [operationId] is the row this pass records its pending-review count in, or null when the attempt keeps
     * none; an import's own record is its job and its document's status.
     */
    suspend fun chunkCandidate(
        operationId: String?,
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
        // An attempt with no operation row has nowhere to record it: what an import's reading owes is read
        // from its own document's status, so there is nothing to write here.
        if (operationId == null) return
        val store = requireNotNull(operations) { "an attempt that records a chunking pass names its operation" }
        stage.run(STAGE_CHUNK) {
            store.recordProgress(operationId = operationId, pendingReview = pending)
        }
    }

    /**
     * Embeds every staged passage that has no vector, page by page and in bounded batches.
     *
     * A passage is embedded once: a resumed attempt embeds only the passages a previous process did not reach,
     * and the passages already embedded keep the vectors they were embedded with, which are the ones the index
     * will be staged from.
     */
    suspend fun embedCandidate(
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
     *
     * @return whether the candidate became the document's text; a refusal is recorded against the operation
     *   here, and the caller is left to say so in its own terms.
     */
    suspend fun publishCandidate(operation: OcrOperation, candidate: String, stage: JobStage): Boolean {
        // A publication recorded against an operation names the store it is recorded in; an attempt that
        // keeps no operation publishes its reading through the service itself and records the outcome on
        // its own document instead.
        val store = requireNotNull(operations) { "a publication recorded against an operation names its store" }
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
            failAttempt(
                operations = store,
                stage = stage,
                operationId = operation.operationId,
                stageName = OcrOperationStage.FAILED,
                code = PUBLICATION_FAILED,
                message = failure.message
                    ?: "the replacement could not be published, so the document still shows its published text",
            )
        }
        val intent = revisions.intent(publicationId)
        val published = intent?.phase == PublicationPhase.PUBLISHED
        stage.run(STAGE_RECORD) {
            store.recordProgress(
                operationId = operation.operationId,
                pendingReview = 0,
            )
            store.finish(
                operationId = operation.operationId,
                stage = if (published) OcrOperationStage.COMPLETE else OcrOperationStage.FAILED,
                errorCode = if (published) null else (intent?.errorCode ?: PUBLICATION_REFUSED),
                errorMessage = if (published) null else (intent?.errorMessage ?: PUBLICATION_REFUSED_MESSAGE),
            )
        }
        return published
    }
}

/** The step labels these phases report through; each is one stage in the job's own progress. */
private const val STAGE_CHUNK = "chunk"
private const val STAGE_EMBED = "embed"
private const val STAGE_RECORD = "record"

private const val PUBLICATION_REFUSED = "PUBLICATION_REFUSED"
private const val PUBLICATION_REFUSED_MESSAGE = "the replacement was not published; the existing text is unchanged"

/** The code a publication that could not publish at all ends the operation with. */
private const val PUBLICATION_FAILED = "PUBLICATION_FAILED"

/** How many passages one embedding step holds, so a long document stays interruptible. */
private const val EMBED_BATCH = 64

/** The attempt failed in a way the operation already recorded, so the job fails with the same code. */
internal class RescanAttemptFailedException(val code: String, message: String) : IllegalStateException(message)

/**
 * Ends the operation with a code and a remedy, and fails the attempt with the same code.
 *
 * The code and the message are persisted before the exception is thrown, so an attempt that ends this way is
 * a named state with a remedy rather than a message a caller has to guess the meaning of.
 */
internal suspend fun failAttempt(
    operations: OcrOperationStore,
    stage: JobStage,
    operationId: String,
    stageName: OcrOperationStage,
    code: String,
    message: String,
): Nothing {
    stage.run(STAGE_RECORD) {
        operations.finish(
            operationId = operationId,
            stage = stageName,
            errorCode = code,
            errorMessage = message,
        )
    }
    throw RescanAttemptFailedException(code, message)
}
