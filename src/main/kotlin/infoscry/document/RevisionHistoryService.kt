package infoscry.document

import infoscry.domain.CollectionId
import infoscry.domain.DocumentId
import infoscry.ocr.OcrOperation
import infoscry.ocr.OcrProfileRevision
import infoscry.ocr.PublicationDisposition
import infoscry.storage.DocumentRevision
import infoscry.storage.DocumentRevisionStore
import infoscry.storage.DocumentStore
import infoscry.storage.OcrOperationStore
import infoscry.storage.OcrReviewStore
import infoscry.storage.PageApproval
import infoscry.storage.PageDigest
import infoscry.storage.RevisionState

/**
 * What the rescan that produced a revision was configured with, as its operation recorded it.
 *
 * Only what the operation's frozen snapshot holds: nothing about an endpoint, a key or a path, and nothing at
 * all for a revision no operation produced — an import's and a restore's carry no engine, and are not given
 * one. [transcriptionModel] and [reviewModel] are the model names of the immutable profile revisions the
 * snapshot named, and are absent when that profile revision no longer resolves.
 */
data class RevisionReading(
    val engine: String,
    val mode: String,
    val language: String,
    val toolVersion: String? = null,
    val modelVersion: String? = null,
    val transcriptionModel: String? = null,
    val reviewModel: String? = null,
)

/**
 * How the pages of one revision differ from the revision it descends from, and who decided each difference.
 *
 * Every page the revision publishes is counted exactly once, so the six counts sum to the pages it published:
 * [unchanged] pages say what the parent's page said; [added] pages have no parent page; [automatic] pages
 * changed by a reviewer decision the archive's own policy approved; [manual] pages changed because a person
 * approved or wrote the text; [unknown] pages changed with no review or decision on record to say which; and
 * [restored] pages differ from the parent's because the revision is an explicit restore, which is a person's
 * request and neither of the other two. [notPublished] counts the revision's pages that never became text.
 */
data class PageChangeCounts(
    val unchanged: Int = 0,
    val added: Int = 0,
    val automatic: Int = 0,
    val manual: Int = 0,
    val unknown: Int = 0,
    val restored: Int = 0,
    val notPublished: Int = 0,
)

/** One published revision as the history shows it: only facts the archive recorded. */
data class RevisionHistoryEntry(
    val revision: DocumentRevision,
    val active: Boolean,
    val pageCount: Int,
    /** When the revision became the document's text, absent for a reading that never went through a publication. */
    val publishedAt: String?,
    /** The revision this one is an explicit restore of, absent for every other revision. */
    val restoredFromRevisionId: String?,
    val reading: RevisionReading?,
    /** How each page of the revision was read: direct text, OCR, or both, as its pages record it. */
    val extractionMethods: List<String>,
    val pageChanges: PageChangeCounts,
)

/**
 * The published text history of one document, for the history view and its restore action.
 *
 * It is a read-only view over SQLite's own records. It invents nothing: where the archive recorded no engine,
 * no instant or no decision, the entry says so by leaving it out or by counting the page as unknown, and it
 * never exposes a filesystem path, an artifact reference or an endpoint.
 */
class RevisionHistoryService(
    private val revisions: DocumentRevisionStore,
    private val documents: DocumentStore,
    private val operations: OcrOperationStore,
    private val reviews: OcrReviewStore,
    private val profileRevisionOf: (String) -> OcrProfileRevision?,
) {

    /**
     * The document's published revisions, oldest first — every one that was ever the document's text.
     *
     * A candidate is a proposal and a withdrawn one is an attempt that never became text, so neither is
     * history; the revision a restore created is, and so is the one it replaced.
     *
     * @throws NoSuchElementException when the document is not in [collectionId].
     */
    fun history(collectionId: CollectionId, documentId: DocumentId): List<RevisionHistoryEntry> {
        documents.get(documentId)?.takeIf { it.collectionId == collectionId }
            ?: throw NoSuchElementException("no document with id ${documentId.value} exists")
        val active = revisions.activeRevisionId(documentId)
        val published = revisions.publishedAt(documentId)
        val restores = revisions.restores(documentId).associateBy { it.newRevisionId }
        val readings = operations.operations(documentId)
            .filter { it.candidateRevisionId != null }
            .associateBy { it.candidateRevisionId!! }
        val all = revisions.revisions(documentId).filter {
            it.state == RevisionState.PUBLISHED || it.state == RevisionState.SUPERSEDED
        }
        val digests = all.associate { it.id to revisions.pageDigests(it.id) }
        return all.map { revision ->
            val pages = digests.getValue(revision.id)
            RevisionHistoryEntry(
                revision = revision,
                active = revision.id == active,
                pageCount = pages.size,
                publishedAt = published[revision.id],
                restoredFromRevisionId = restores[revision.id]?.restoredFromRevisionId,
                reading = readings[revision.id]?.let(::readingOf),
                extractionMethods = pages.filter { it.approval == PageApproval.APPROVED }
                    .mapNotNull { it.extractionMethod?.name }.distinct().sorted(),
                pageChanges = changesOf(
                    documentId = documentId,
                    revision = revision,
                    pages = pages,
                    parentPages = revision.parentRevisionId?.let { parent -> digests[parent] ?: revisions.pageDigests(parent) },
                    isRestore = revision.id in restores,
                ),
            )
        }
    }

    private fun readingOf(operation: OcrOperation): RevisionReading {
        val snapshot = operation.snapshot
        return RevisionReading(
            engine = snapshot.engine.name,
            mode = snapshot.mode.name,
            language = snapshot.language,
            toolVersion = snapshot.toolVersion,
            modelVersion = snapshot.modelVersion,
            transcriptionModel = snapshot.transcriptionProfileRevisionId?.let(profileRevisionOf)?.model,
            reviewModel = snapshot.reviewProfileRevisionId?.let(profileRevisionOf)?.model,
        )
    }

    private fun changesOf(
        documentId: DocumentId,
        revision: DocumentRevision,
        pages: List<PageDigest>,
        parentPages: List<PageDigest>?,
        isRestore: Boolean,
    ): PageChangeCounts {
        var unchanged = 0
        var added = 0
        var automatic = 0
        var manual = 0
        var unknown = 0
        var restored = 0
        var notPublished = 0
        val parentByOrdinal = parentPages?.filter { it.approval == PageApproval.APPROVED }?.associateBy { it.ordinal }
        pages.forEach { page ->
            if (page.approval != PageApproval.APPROVED) {
                notPublished++
                return@forEach
            }
            val before = parentByOrdinal?.get(page.ordinal)?.takeIf { it.unitId == page.unitId }
            when {
                before == null -> added++
                before.textSha256 == page.textSha256 -> unchanged++
                isRestore -> restored++
                else -> when (decisionOf(documentId, revision, page)) {
                    Decision.AUTOMATIC -> automatic++
                    Decision.MANUAL -> manual++
                    Decision.UNKNOWN -> unknown++
                }
            }
        }
        return PageChangeCounts(unchanged, added, automatic, manual, unknown, restored, notPublished)
    }

    private enum class Decision { AUTOMATIC, MANUAL, UNKNOWN }

    /**
     * Who decided that a page's text changed, from the reviews recorded against the reading it replaced.
     *
     * A review whose candidate is exactly this page's text and whose disposition was APPROVE is the policy
     * accepting it without a person; one that was only PROPOSEd became text because a person approved it. Text
     * that matches no review's candidate, on a page that was reviewed, is text a person wrote. A page with no
     * review at all has no record to say either way, and is reported as unknown rather than guessed.
     */
    private fun decisionOf(documentId: DocumentId, revision: DocumentRevision, page: PageDigest): Decision {
        val against = reviews.forPage(documentId, page.unitId.value, page.ordinal)
            .filter { it.baselineRevisionId == revision.parentRevisionId }
        if (against.isEmpty()) return Decision.UNKNOWN
        val matching = against.lastOrNull { it.candidateHash == page.textSha256 } ?: return Decision.MANUAL
        return if (matching.disposition == PublicationDisposition.APPROVE) Decision.AUTOMATIC else Decision.MANUAL
    }
}
