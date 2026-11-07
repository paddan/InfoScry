package infoscry.jobs

import infoscry.domain.ContentUnitId
import infoscry.domain.Document
import infoscry.domain.ExtractionMethod
import infoscry.domain.SourceImageProvenance
import infoscry.domain.SourceImageRoot
import infoscry.domain.SourceLocation
import infoscry.extract.TextNormalizer
import infoscry.image.ImageUnitTarget
import infoscry.image.WrittenImage
import infoscry.storage.DocumentRevisionStore
import infoscry.storage.PageApproval
import infoscry.storage.RevisionPageDraft

/**
 * Delivers the image stage's units into a rescan's candidate revision, as approved pages the candidate itself
 * produced. An image unit is never pending review, and its checkpoints belong to the candidate they were
 * extracted for ([target] is the candidate's id), so a new rescan looks at every selected parent again.
 *
 * Delivery is idempotent: a page is written by ordinal, so a repeat replaces itself, and `appendPage` moves an
 * image unit that comes back at another ordinal instead of leaving two pages for one unit.
 */
class CandidateImageTarget(
    private val revisions: DocumentRevisionStore,
    private val candidate: String,
    private val document: Document,
) : ImageUnitTarget {

    init {
        // A candidate belongs to one document; delivering another document's images into it would corrupt it.
        require(revisions.revision(candidate)?.documentId == document.id) {
            "revision $candidate is not a revision of document ${document.id.value}"
        }
    }

    override val target: String = candidate

    override suspend fun deliver(
        ordinal: Int,
        unitId: ContentUnitId,
        locator: SourceLocation.ImageRegion,
        caption: String,
        image: WrittenImage,
        provenanceRoot: SourceImageRoot,
    ) {
        val text = TextNormalizer.normalize(caption)
        revisions.appendPage(
            candidate,
            RevisionPageDraft(
                ordinal = ordinal,
                unitId = unitId,
                locator = locator,
                extractedText = text.extracted,
                searchText = text.search,
                extractionMethod = ExtractionMethod.DIRECT_TEXT,
                sourceImage = SourceImageProvenance(
                    root = provenanceRoot,
                    relativePath = image.relativePath,
                    sha256 = image.sha256,
                    width = image.widthPx,
                    height = image.heightPx,
                    renderVersion = SinkImageTarget.IMAGE_RENDER_VERSION,
                ),
                approval = PageApproval.APPROVED,
                unitKey = SinkImageTarget.keyOf(unitId),
                producerRevisionId = candidate,
            ),
        )
    }

    override suspend fun retainImages(fromOrdinal: Int, toOrdinal: Int, keep: Set<ContentUnitId>) {
        revisions.removeImagePagesExcept(candidate, fromOrdinal, toOrdinal, keep)
    }
}
