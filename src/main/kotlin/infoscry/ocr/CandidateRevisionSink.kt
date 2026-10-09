package infoscry.ocr

import infoscry.domain.ContentUnitId
import infoscry.domain.DocumentId
import infoscry.domain.SourceLocation
import infoscry.extract.ExtractionEvent
import infoscry.extract.ExtractionFingerprint
import infoscry.extract.ExtractionSink
import infoscry.storage.DocumentRevisionStore
import infoscry.storage.PageApproval
import infoscry.storage.RevisionPageDraft

/**
 * Commits one attempt's pages into a candidate revision rather than into published content.
 *
 * A rescan is not an import. Its reading has to be stageable, reviewable and discardable without the
 * document's published text, its chunks or the live index noticing, which is exactly what the isolated
 * candidate sink provides: `openCandidate` and `appendPage` write revision-owned page text and nothing
 * else, so a candidate is invisible until the publication service publishes it and a withdrawal costs
 * nothing but the work already done.
 *
 * Four things it deliberately does not do:
 *
 * - **No chunks and no embeddings.** A page is staged with its text; turning that text into passages and
 *   vectors is a later stage of a durable operation, and a staging that embedded as a side effect would
 *   hold the mutation permit for the accelerator and make a resume depend on it.
 * - **No approvals.** Every page it stages is pending: what may replace published text is a review decision,
 *   and an attempt is not one. A page the attempt's own comparison found nothing to decide about is approved
 *   by that attempt once the page exists — the staged reading is the text the page already carried, which is
 *   not a decision either — and never by this sink.
 * - **No progress and no completion.** The caller owns the operation's progress, and the pages it reads
 *   back are the answer to "how far did this get". The flow is collected inline, so a page is staged before
 *   the next event is delivered and therefore before any progress the caller writes afterwards.
 * - **No failure rows.** A page an engine could not read is simply absent from the candidate, which is
 *   what leaves the baseline in place for it; the code that says why is the job's own record.
 *
 * A page keeps its content-unit identity when it is the *same* page of the same document: the same ordinal
 * and the same locator. A page at another position, or one of another kind, is a new page and gets a new
 * identity, because reusing an identifier would claim a citation to the old page still opens this one.
 */
class CandidateRevisionSink(
    private val revisions: DocumentRevisionStore,
    private val documentId: DocumentId,
    private val provenance: String,
    private val baselineRevisionId: String? = revisions.activeRevisionId(documentId),
    /** The OCR selection of the import or retry this reading belongs to, recorded on the candidate it opens. */
    private val readingSnapshot: OcrSettingsSnapshot? = null,
) : ExtractionSink {

    /** The pages the reading being replaced holds, by ordinal: what a staged page may inherit from. */
    private val baselinePages: Map<Int, Pair<ContentUnitId, SourceLocation>> =
        baselineRevisionId
            ?.let { revisionId -> revisions.pages(revisionId) }
            ?.associate { page -> page.ordinal to (page.unitId to page.locator) }
            .orEmpty()

    /**
     * The keys this sink has staged, by the reading they belong to.
     *
     * A key names a unit of one document read under one fingerprint, so the two travel with it: a sink that
     * answered with every key it ever staged would tell a reading under new settings that pages it never
     * read were already committed, and those pages would be skipped. A sink is normally built per attempt —
     * where both are then always the same pair — but nothing in this type's construction makes that true,
     * so the answer is scoped to the pair rather than assumed from it.
     */
    private val staged = mutableMapOf<Pair<DocumentId, ExtractionFingerprint>, MutableSet<String>>()

    private var candidate: String? = null

    override val storesUnits: Boolean = true

    /** Candidate pages are approved by the confirmed reading and publish together when extraction completes. */
    override val stagesForReview: Boolean = true

    /** The candidate this sink stages into, or `null` while it has staged nothing. */
    override val candidateRevisionId: String? get() = candidate

    /**
     * The keys staged for *this* reading of *this* document.
     *
     * A key staged for another fingerprint, or for another document, is not this reading's and is not
     * answered as committed. A candidate staged by an *earlier process* under the same fingerprint is: the
     * page names the extractor key and the reading it was staged under, so the sink adopts that candidate
     * instead of opening a second one, and its staged pages are not read — or paid for — again.
     */
    override suspend fun committedKeys(
        documentId: DocumentId,
        fingerprint: ExtractionFingerprint,
    ): Set<String> {
        if (candidate == null) {
            revisions.resumableCandidate(documentId, fingerprint.value)?.let { revisionId ->
                candidate = revisionId
                staged.getOrPut(documentId to fingerprint) { mutableSetOf() } += revisions.stagedKeys(revisionId)
            }
        }
        return staged[documentId to fingerprint]?.toSet().orEmpty()
    }

    /**
     * The pages this attempt staged that nobody has decided about.
     *
     * Every page this sink stages is pending — what may replace published text is a review decision, and an
     * attempt is not one — so the count is read back from the candidate, which is also what a resumed
     * attempt sees.
     */
    override suspend fun awaitingDecision(documentId: DocumentId): Int {
        val revision = candidate ?: return 0
        return revisions.pages(revision).count { page -> page.approval == PageApproval.PENDING }
    }

    override suspend fun deliver(
        documentId: DocumentId,
        fingerprint: ExtractionFingerprint,
        event: ExtractionEvent,
    ) = deliver(documentId, fingerprint, event, approval = null)

    override suspend fun deliver(
        documentId: DocumentId,
        fingerprint: ExtractionFingerprint,
        event: ExtractionEvent,
        approval: PageApproval?,
    ) {
        require(documentId == this.documentId) {
            "this sink stages the pages of ${this.documentId.value}, not of ${documentId.value}"
        }
        if (event !is ExtractionEvent.UnitReady) return
        val revision = candidate
            ?: revisions.openCandidate(
                documentId,
                baselineRevisionId,
                provenance,
                fingerprint.value,
                readingSnapshot,
            ).also { opened ->
                candidate = opened
            }
        revisions.appendPage(revision, pageFor(event, approval ?: PageApproval.APPROVED))
        staged.getOrPut(documentId to fingerprint) { mutableSetOf() } += event.key
    }

    /**
     * Gives up on the candidate without publishing it.
     *
     * The caller calls this when the attempt failed or was cancelled. The revision keeps its identifier and
     * the pages it already staged, so an attempt that resumes may continue against the same candidate; what
     * it cannot do is become the document's text. Nothing published changes either way.
     */
    fun withdraw() {
        candidate?.let { revisionId -> revisions.withdrawCandidate(revisionId) }
    }

    /** One delivered page as a revision page, in the published form the store takes it in. */
    private fun pageFor(event: ExtractionEvent.UnitReady, approval: PageApproval): RevisionPageDraft {
        val draft = event.unit
        val baseline = baselinePages[event.ordinal]?.takeIf { (_, locator) -> locator == draft.locator }
        return RevisionPageDraft(
            ordinal = event.ordinal,
            unitId = baseline?.first ?: ContentUnitId.new(),
            locator = draft.locator,
            extractedText = draft.extractedText,
            searchText = draft.searchText,
            extractionMethod = draft.method,
            meanConfidence = draft.meanConfidence,
            artifactRelativePath = draft.artifactRelativePath,
            artifactSha256 = draft.artifactSha256,
            // The image this page's reading was made from travels into the durable page, so a reviewer — and
            // a comparison that has to show the image a candidate was read from — can tell a reading of a
            // reduced copy from a reading of the whole one instead of attributing the first to the second.
            sourceImage = draft.sourceImage,
            approval = approval,
            unitKey = event.key,
        )
    }
}
