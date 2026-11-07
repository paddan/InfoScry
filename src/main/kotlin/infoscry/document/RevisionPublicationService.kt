package infoscry.document

import infoscry.chunk.Chunker
import infoscry.domain.Chunk
import infoscry.domain.ChunkId
import infoscry.domain.CollectionId
import infoscry.domain.CollectionLifecycle
import infoscry.domain.DocumentId
import infoscry.image.ImageIdentity
import infoscry.search.DocumentRow
import infoscry.search.SearchIndex
import infoscry.storage.CollectionNotActiveException
import infoscry.storage.CollectionStore
import infoscry.storage.Database
import infoscry.storage.DocumentBeingDeletedException
import infoscry.storage.DocumentRevisionStore
import infoscry.storage.DocumentStore
import infoscry.storage.MutationCoordinator
import infoscry.storage.PageApproval
import infoscry.storage.PublicationIntent
import infoscry.storage.PublicationPhase
import infoscry.storage.RevisionChunk
import infoscry.storage.RevisionPage
import infoscry.storage.RevisionPageText
import infoscry.storage.RevisionState
import org.slf4j.LoggerFactory

/** What a startup's resolution of unfinished publication records found, in the order they were read. */
data class PublicationRecoveryReport(
    val completed: List<String>,
    val discarded: List<String>,
)

/**
 * What [RevisionPublicationService] calls once a revision's publication is committed: the one seam the
 * selectable-PDF feature hooks to queue a derived artifact, without this file depending on that package.
 *
 * Every implementation must be safe to call more than once for the same revision (a startup calls it again
 * for a publication whose process died before the hook ran) and must never throw: a failure here must never
 * be mistaken for a failure of the publication it follows.
 */
fun interface RevisionPublishedHook {
    fun onPublished(documentId: DocumentId, collectionId: CollectionId, revisionId: String)

    companion object {
        val NONE = RevisionPublishedHook { _, _, _ -> }
    }
}

/**
 * Publishes a staged candidate revision of one document.
 *
 * The candidate's pages, chunks and vectors are already stored with it, so publishing is one database
 * transaction: recheck that the document and its collection are still live and that the base is still the
 * published reading, record the publication, replace the document's searchable rows with the candidate's,
 * make the candidate the document's text, and mark the publication done. Search reads the same database, so
 * a reader sees either the whole previous reading or the whole new one, and a failure anywhere leaves the
 * previous reading in place with nothing to repair.
 *
 * What happens after the commit — the published hook — is recorded by `cleaned_at`: a publication whose
 * hook may not have run is finished by [recoverUnfinished] at the next start.
 */
class RevisionPublicationService(
    private val database: Database,
    private val revisions: DocumentRevisionStore,
    private val documents: DocumentStore,
    private val collections: CollectionStore,
    private val mutations: MutationCoordinator,
    private val index: () -> SearchIndex,
    /**
     * Called once a revision's publication is committed, for import, rescan and restore alike — see
     * [RevisionPublishedHook]. The default does nothing; production wires the selectable-PDF service's
     * queueing here.
     */
    private val onPublished: RevisionPublishedHook = RevisionPublishedHook.NONE,
) {

    /**
     * Publishes `candidateRevisionId` over `baseRevisionId`.
     *
     * @return the identifier of the durable publication record, whose phase, error code and message are
     *   read back from `revision_publications`.
     */
    suspend fun publish(
        documentId: DocumentId,
        baseRevisionId: String?,
        candidateRevisionId: String,
    ): String {
        val target = revisions.revision(candidateRevisionId)
            ?: throw IllegalArgumentException("no revision with id $candidateRevisionId exists")
        require(target.documentId == documentId) {
            "revision $candidateRevisionId belongs to document ${target.documentId.value}, " +
                "not ${documentId.value}"
        }
        require(target.state == RevisionState.CANDIDATE) {
            "revision $candidateRevisionId is ${target.state}; only a candidate can be published"
        }
        val document = documents.get(documentId)
            ?: throw NoSuchElementException("no document with id ${documentId.value} exists")

        // The base the caller names has to be the reading the document publishes, or this publication
        // would replace text that a different one is still the parent of.
        val published = revisions.activeRevisionId(documentId)
        require(published == baseRevisionId) {
            "document ${documentId.value} publishes ${published ?: "no revision"}, " +
                "not ${baseRevisionId ?: "none"}; the candidate was staged against another reading"
        }

        // A target that cannot become a published reading is refused durably, in the words of what it is
        // waiting for. Nothing changes and the baseline is untouched.
        val refusal = refusalFor(candidateRevisionId, baseRevisionId)
        if (refusal != null) {
            val refused = revisions.prepare(
                documentId = documentId,
                collectionId = document.collectionId,
                baseRevisionId = baseRevisionId,
                targetRevisionId = candidateRevisionId,
            )
            revisions.abandon(refused.id, PublicationPhase.REFUSED, refusal.first, refusal.second)
            return refused.id
        }

        // The pages this attempt publishes are the target's approved ones. For a replacement that is every
        // page of the target, because a single unapproved page refused it above; for an initial import it is
        // the pages nobody owes a decision about.
        val pages = publishablePages(candidateRevisionId)
        val chunks = revisions.chunks(candidateRevisionId)

        val intent = try {
            mutations.withMutation {
                database.transaction {
                    recheck(documentId, document.collectionId, baseRevisionId)
                    val intent = revisions.prepare(
                        documentId = documentId,
                        collectionId = document.collectionId,
                        baseRevisionId = baseRevisionId,
                        targetRevisionId = candidateRevisionId,
                    )
                    // The index refuses a document with no passage; a picture-only reading has nothing to search, and
                    // whatever the replaced reading made searchable is removed in the same transaction.
                    if (chunks.isEmpty()) {
                        index().deleteDocument(documentId)
                    } else {
                        index().replaceDocument(stagedRows(intent, pages, chunks))
                    }
                    revisions.makeAuthoritative(intent, revisionPages(pages, chunks))
                    revisions.markPublished(intent.id)
                    intent
                }
            }
        } catch (failure: Throwable) {
            // The transaction left nothing behind; the failed attempt is still recorded, so the document's
            // history and the caller's status read say why the reading did not change.
            runCatching {
                val failed = revisions.prepare(
                    documentId = documentId,
                    collectionId = document.collectionId,
                    baseRevisionId = baseRevisionId,
                    targetRevisionId = candidateRevisionId,
                )
                revisions.abandon(failed.id, PublicationPhase.ABANDONED, PUBLICATION_FAILED, messageOf(failure))
            }
            throw failure
        }

        afterCommit(intent)
        return intent.id
    }

    /**
     * Resolves every publication record a previous process left unfinished.
     *
     * A publication commits as a whole, so the only unfinished states are a record of a refusal or failure
     * whose final write never happened, which is abandoned, and a committed publication whose hook may not
     * have run, which is finished now.
     */
    suspend fun recoverUnfinished(): PublicationRecoveryReport {
        val completed = mutableListOf<String>()
        val discarded = mutableListOf<String>()
        for (intent in revisions.intentsAwaitingCleanup()) {
            if (intent.publishedAt == null) continue
            afterCommit(intent)
            completed += intent.id
        }
        for (intent in revisions.unfinishedIntents()) {
            if (intent.authoritativeAt != null) {
                revisions.markPublished(intent.id)
                afterCommit(intent)
                completed += intent.id
            } else {
                LOGGER.atInfo()
                    .addKeyValue(PUBLICATION_FIELD, intent.id)
                    .addKeyValue(DOCUMENT_FIELD, intent.documentId.value)
                    .log("discarding a publication record that never took effect; the old revision wins")
                revisions.abandon(
                    intent.id,
                    PublicationPhase.ABANDONED,
                    PUBLICATION_NOT_AUTHORITATIVE,
                    PUBLICATION_NOT_AUTHORITATIVE_MESSAGE,
                )
                discarded += intent.id
            }
        }
        return PublicationRecoveryReport(completed = completed, discarded = discarded)
    }

    /** The work owed after a committed publication: the published hook, then the record that it ran. */
    private fun afterCommit(intent: PublicationIntent) {
        // A derived-artifact feature (selectable PDF) queues its own work from here; a failure in it must
        // never be mistaken for a failure of this publication, which has already succeeded.
        onPublished.onPublished(intent.documentId, intent.collectionId, intent.targetRevisionId)
        revisions.markCleaned(intent.id)
    }

    /**
     * The pages of a target revision this archive may publish.
     *
     * Only an approved page is text somebody accepted. A replacement's approval is decided as a whole by
     * [refusalFor], so nothing is filtered away there; an initial import publishes the pages it has approved,
     * and this is where the pages that still owe a decision are left out of the reading.
     */
    private fun publishablePages(revisionId: String): List<RevisionPageText> =
        revisions.pages(revisionId).filter { page -> page.approval == PageApproval.APPROVED }

    /**
     * The reasons a candidate cannot be published yet, as a code and an actionable sentence.
     *
     * A page that has not been approved is not a failure of the publication: it is a document waiting for a
     * review decision. What it costs depends on what is being replaced.
     *
     * A *replacement* publishes every page of the target or none of them: its base revision is already
     * serving readers, and publishing the approved pages alone would silently drop text a reader can search
     * today. One unapproved page therefore refuses the whole replacement.
     *
     * An *initial* import has no earlier reading to protect. The spec lets it publish the pages it has
     * approved while the rest have image access and pending-review state but no searchable text, so it is
     * refused only when no page at all is approved: a reading nobody has decided about is not text to serve.
     */
    private fun refusalFor(revisionId: String, baseRevisionId: String?): Pair<String, String>? {
        val pages = revisions.pages(revisionId)
        val approved = pages.count { page -> page.approval == PageApproval.APPROVED }
        if (approved == 0 || (baseRevisionId != null && approved != pages.size)) {
            return AWAITING_REVIEW_CODE to if (baseRevisionId == null) {
                "no page of this reading has been approved yet; nothing was published and every page of it " +
                    "stays pending review"
            } else {
                "the replacement's text has not been fully approved yet; no page was published and the " +
                    "document still shows its current text"
            }
        }
        val chunks = revisions.chunks(revisionId)
        if (chunks.isEmpty() && !isPictureOnly(pages)) {
            return ARTIFACTS_INCOMPLETE_CODE to
                "the replacement has no indexed passages yet; it was not published"
        }
        if (chunks.any { !it.isStaged }) {
            return ARTIFACTS_INCOMPLETE_CODE to
                "the replacement's passages have not all been embedded yet; the document still shows its " +
                    "current text"
        }
        val tooLong = chunks.firstOrNull { it.tokenCount > Chunker.DEFAULT_MAX_SEQUENCE_TOKENS }
        if (tooLong != null) {
            return PASSAGE_TOO_LONG_CODE to
                "a passage of the replacement is ${tooLong.tokenCount} tokens, more than the " +
                    "${Chunker.DEFAULT_MAX_SEQUENCE_TOKENS} the embedding model accepts; it was not published"
        }
        return null
    }

    /**
     * Rechecks, under the boundary's permit, everything that could have changed while the rows were
     * staged. A deletion or a tombstoned collection wins: the whole point of rechecking them here is that
     * a publication which started before one is not allowed to resurrect what the deletion removed.
     */
    private fun recheck(documentId: DocumentId, collectionId: CollectionId, baseRevisionId: String?) {
        if (documents.isDeletionTarget(documentId)) {
            throw DocumentBeingDeletedException(documentId)
        }
        val collection = collections.get(collectionId)
        if (collection == null || collection.lifecycle != CollectionLifecycle.ACTIVE) {
            throw CollectionNotActiveException(collectionId)
        }
        val published = revisions.activeRevisionId(documentId)
        if (published != baseRevisionId) {
            throw IllegalStateException(
                "document ${documentId.value} published ${published ?: "no revision"} while a replacement " +
                    "of ${baseRevisionId ?: "none"} was staged; the replacement was not published",
            )
        }
    }

    private fun isPictureOnly(pages: List<RevisionPageText>): Boolean =
        ImageIdentity.isPictureOnly(pages.map { it.ordinal to it.extractedText.isBlank() })

    private fun stagedRows(
        intent: PublicationIntent,
        pages: List<RevisionPageText>,
        chunks: List<RevisionChunk>,
    ): List<DocumentRow> {
        val byOrdinal = chunks.groupBy { it.unitOrdinal }
        val rows = ArrayList<DocumentRow>(chunks.size)
        for (page in pages) {
            for (chunk in byOrdinal[page.ordinal].orEmpty()) {
                rows += DocumentRow(
                    collectionId = intent.collectionId,
                    documentId = intent.documentId,
                    unitId = page.unitId,
                    locator = page.locator,
                    locatorLabel = page.locator.describe(),
                    chunk = Chunk(
                        id = ChunkId.new(),
                        contentUnitId = page.unitId,
                        ordinal = chunk.ordinal,
                        text = chunk.text,
                        startOffset = chunk.startOffset,
                        endOffset = chunk.endOffset,
                        tokenCount = chunk.tokenCount,
                        tokenStart = chunk.tokenStart,
                        tokenEnd = chunk.tokenEnd,
                    ),
                    vector = checkNotNull(chunk.embedding) {
                        "a staged passage of revision ${intent.targetRevisionId} has no vector"
                    },
                    revisionId = intent.targetRevisionId,
                )
            }
        }
        return rows
    }

    /** The pages and their chunks as the published content takes them, keyed by page ordinal. */
    private fun revisionPages(
        pages: List<RevisionPageText>,
        chunks: List<RevisionChunk>,
    ): List<RevisionPage> {
        val byOrdinal = chunks.groupBy { it.unitOrdinal }
        return pages.map { page ->
            RevisionPage(
                unitId = page.unitId,
                ordinal = page.ordinal,
                locator = page.locator,
                extractedText = page.extractedText,
                searchText = page.searchText,
                extractionMethod = page.extractionMethod,
                meanConfidence = page.meanConfidence,
                artifactRelativePath = page.artifactRelativePath,
                artifactSha256 = page.artifactSha256,
                structureRelativePath = page.structureReference?.relativePath,
                structureSha256 = page.structureReference?.sha256,
                reading = page.reading,
                sourceImage = page.sourceImage,
                chunks = byOrdinal[page.ordinal].orEmpty().map { chunk ->
                    infoscry.chunk.ChunkDraft(
                        ordinal = chunk.ordinal,
                        text = chunk.text,
                        startOffset = chunk.startOffset,
                        endOffset = chunk.endOffset,
                        tokenCount = chunk.tokenCount,
                        tokenStart = chunk.tokenStart,
                        tokenEnd = chunk.tokenEnd,
                    )
                },
            )
        }
    }

    private fun messageOf(failure: Throwable): String =
        failure.message ?: failure::class.simpleName ?: "the publication could not be completed"

    companion object {
        /** The code a publication carries when its text is waiting for a review decision. */
        const val AWAITING_REVIEW_CODE: String = "REVISION_AWAITING_REVIEW"

        /** The code a publication carries when its passages are not complete enough to index. */
        const val ARTIFACTS_INCOMPLETE_CODE: String = "REVISION_ARTIFACTS_INCOMPLETE"

        /** The code a publication carries when a passage does not fit the embedding model's window. */
        const val PASSAGE_TOO_LONG_CODE: String = "REVISION_PASSAGE_TOO_LONG"

        /** The code an intent carries when it is abandoned because its authority never moved. */
        const val PUBLICATION_NOT_AUTHORITATIVE: String = "PUBLICATION_NOT_AUTHORITATIVE"

        const val PUBLICATION_FAILED: String = "PUBLICATION_FAILED"

        const val PUBLICATION_NOT_AUTHORITATIVE_MESSAGE: String =
            "the publication did not take effect; the document still shows the text it published before"
    }
}

private val LOGGER = LoggerFactory.getLogger("infoscry.document")

private const val PUBLICATION_FIELD: String = "publication"
private const val DOCUMENT_FIELD: String = "document"
