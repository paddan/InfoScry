package infoscry.ocr

import infoscry.document.PageReviewer
import infoscry.domain.DocumentId
import infoscry.extract.ContentUnitDraft
import infoscry.storage.DocumentRevisionStore
import infoscry.storage.OcrProfileStore
import infoscry.storage.OcrReviewStore
import infoscry.storage.PageApproval
import java.nio.file.Path

/**
 * How one attempt judges the pages it stages: who reviews them, the versions the attempt was admitted with,
 * and the roots a page's image is rebuilt from.
 *
 * An import in check-and-improve mode reads a page image even where the file already carries text, so one page
 * it read has *two* readings — the text layer the file holds and what the engine read from the pixels — and
 * the comparison between them is what a person then decides about. Both readings exist only while the draft is
 * in hand: the staged page keeps the engine's reading, and the page's own text is deliberately not persisted,
 * so the comparison is made here, while the draft is on its way to being staged, rather than by a later phase
 * that would have to find the baseline again.
 *
 * Nothing here decides anything: the rules are [OcrComparisonService]'s, and the review a comparison cannot
 * act on by itself is written by the service into the store it holds. What this type contributes is one
 * page's input — its image, the two readings, and the versions the attempt was admitted with — which is why
 * it is built per document and holds nothing beyond one attempt's settings.
 */
internal class StagedPageReview(
    private val reviewer: PageReviewer,
    private val reviewProfileRevisionId: String,
    private val reviewPromptVersion: Int,
    private val policyVersion: Int,
    private val documentArtifactRoot: Path,
    private val managedCopyRoot: Path,
) {

    /**
     * Compares one reading about to be staged with the text its page carries, and records what a person has
     * to decide about it.
     *
     * A page with no text of its own has no second reading, so there is nothing to compare and no decision
     * anybody owes: the reading is staged as the only reading there is. A reading with no image behind it —
     * a format that produced its text without a raster — has no pixels a reviewer could be shown, and takes
     * the same route. A comparison that cannot be *made* rather than answered (another prompt or policy
     * version than this build runs) is thrown, exactly as the rescan path throws it.
     *
     * @return what the reading may become, as the revision records it. A comparison that found nothing to
     *   decide about approves the page: the staged reading is the text the page already carried, so retaining
     *   it is not a decision anybody owes — and a page nobody has approved would otherwise have no searchable
     *   text for good. A difference this build's policy would replace on an accepted record is approved by
     *   that record too; what pilot mode disposes instead is [PublicationDisposition.PROPOSE], and a proposal
     *   is what stays pending. A page with no comparison at all is pending as well, because a reading nothing
     *   judged is an uncertain proposal rather than text the page already carried.
     */
    suspend fun review(
        documentId: DocumentId,
        pageKey: String,
        ordinal: Int,
        reading: ContentUnitDraft,
    ): PageApproval {
        val directText = reading.directText ?: return PageApproval.PENDING
        val provenance = reading.sourceImage ?: return PageApproval.PENDING
        val page = PageImage.ofProvenance(
            documentId = documentId,
            // The identity the page was *read* under rather than the identity the staged page is about to be
            // given: this page's transcription dispatched under it, and a page that left this machine to be
            // transcribed and again to be reviewed is still one page against the attempt's allowance. A
            // review named with the staged page's new unit id would count the same page twice.
            unitId = pageKey,
            ordinal = ordinal,
            provenance = provenance,
            artifactRoot = documentArtifactRoot,
            managedCopyRoot = managedCopyRoot,
        )
        val review = reviewer.compare(
            PageComparisonInput(
                page = page,
                candidateText = reading.extractedText,
                reviewProfileRevisionId = reviewProfileRevisionId,
                // The baseline is the text the file's own page carries, which is not a reading this archive
                // publishes: the comparison therefore names the text and neither a revision nor a hash —
                // there is no stored page to be verified against, and the schema that a review is written
                // through allows a baseline revision and a baseline hash together or not at all. What names
                // the comparison is the candidate, the page image and the reviewer, and the text the page
                // itself carries is a fact of that immutable file rather than of a row.
                baselineText = directText,
                reviewPromptVersion = reviewPromptVersion,
                policyVersion = policyVersion,
            ),
        )
        return if (review.disposition == PublicationDisposition.PROPOSE) {
            PageApproval.PENDING
        } else {
            PageApproval.APPROVED
        }
    }
}

/**
 * The reviewer factory a production attempt uses: the comparison service, bound to one attempt's scope.
 *
 * It is built per attempt rather than shared, because the authority it dispatches through belongs to one
 * owner, one document and one stage — an import's job or a rescan's operation — and because the policy it
 * consults is the snapshot's own policy version. The same factory serves both attempts: an import's staged
 * page and a rescan's are judged by the same rules, and a second wiring of the comparison service would be a
 * second answer to what a reviewer may do.
 */
internal fun ocrReviewerFactory(
    revisions: DocumentRevisionStore,
    reviews: OcrReviewStore,
    profiles: OcrProfileStore,
    lookup: (String) -> String? = System::getenv,
): (OcrSettingsSnapshot, OcrDispatchAuthority?) -> PageReviewer = { snapshot, dispatch ->
    val comparison = OcrComparisonService(
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
