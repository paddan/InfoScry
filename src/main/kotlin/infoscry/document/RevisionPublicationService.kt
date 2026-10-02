package infoscry.document

import infoscry.chunk.Chunker
import infoscry.domain.Chunk
import infoscry.domain.ChunkId
import infoscry.domain.CollectionId
import infoscry.domain.CollectionLifecycle
import infoscry.domain.DocumentId
import infoscry.search.DocumentRow
import infoscry.search.LuceneIndex
import infoscry.search.RevisionLease
import infoscry.search.RevisionSnapshotGate
import infoscry.search.RevisionScope
import infoscry.storage.CollectionNotActiveException
import infoscry.storage.DocumentBeingDeletedException
import infoscry.storage.DocumentRevisionStore
import infoscry.storage.DocumentStore
import infoscry.storage.MaintenanceInProgressException
import infoscry.storage.CollectionStore
import infoscry.storage.MutationCoordinator
import infoscry.storage.PageApproval
import infoscry.storage.PublicationIntent
import infoscry.storage.PublicationPhase
import infoscry.storage.RevisionChunk
import infoscry.storage.RevisionPage
import infoscry.storage.RevisionPageText
import infoscry.storage.RevisionState
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory

/**
 * One instant a publication can be parked at, in the order they happen.
 *
 * These are the instants the fault-injection tests stop at, and they are the service's own protocol
 * rather than a second copy of it. Each handoff the contract names has an instant on either side of it:
 *
 * - the staged index rows exist but are not durable — [ROWS_STAGED]; they are durable and the SQLite
 *   authority has not moved — [STAGED_COMMITTED],
 * - the authority has moved and readers still lease the previous snapshot — [AUTHORITATIVE]; the
 *   snapshot that names the target is published — [SNAPSHOT_PUBLISHED],
 * - the attempt is durably complete — [PUBLISHED] — and the rows of the reading it replaced are gone —
 *   [CLEANED].
 */
enum class PublicationStep {
    INTENT_PERSISTED,
    ROWS_STAGED,
    STAGED_COMMITTED,
    AUTHORITATIVE,
    SNAPSHOT_PUBLISHED,
    PUBLISHED,
    CLEANED,
}

/** What a startup's roll-forward of unfinished publications found, in the order the intents were read. */
data class PublicationRecoveryReport(
    val completed: List<String>,
    val discarded: List<String>,
)

/**
 * Publishes a staged candidate revision of one document, and finishes the publications a previous
 * process left behind.
 *
 * There is no transaction spanning SQLite and Lucene, and this class does not pretend there is one. What
 * it has instead is a protocol whose every intermediate state is one recovery can read:
 *
 * 1. The intent is persisted PREPARED. Its durable artifacts are the candidate revision's own pages,
 *    chunks and vectors, so the attempt is complete on its own and the index has not been touched: the
 *    candidate's rows do not exist anywhere a reader could find them.
 * 2. Inside one short shared boundary — a mutation permit, then the snapshot gate's write lock — the
 *    document's deletion state, its collection's lifecycle and the revision the caller named as the base
 *    are rechecked, the candidate's rows are written and committed, and the SQLite transaction that makes
 *    the target authoritative also records that authority moved. Writing the rows only here is what keeps
 *    a candidate unexposable: no reader can acquire a snapshot while the gate is held, and a reader that
 *    already held one is inside the searcher it leased, which the new rows are not in yet.
 * 3. The snapshot that hides the reading being replaced is published, so new readers see the new text and
 *    readers already inside a lease keep seeing the old one. Then the attempt is marked PUBLISHED — which
 *    is why a failure after the authority commit is never reported as a failure: recovery completes it.
 * 4. The rows of the reading that was replaced are removed only after the readers that could still be
 *    reading them have drained. SQLite keeps the text of every revision either way.
 *
 * Every step is idempotent, so recovery is the same code path as publication rather than a second one.
 */
class RevisionPublicationService(
    private val revisions: DocumentRevisionStore,
    private val documents: DocumentStore,
    private val collections: CollectionStore,
    private val mutations: MutationCoordinator,
    private val index: () -> LuceneIndex,
    private val gate: RevisionSnapshotGate = RevisionSnapshotGate(),
    private val cleanupDrainMillis: Long = CLEANUP_DRAIN_MILLIS,
) {

    /** The revision scope a reader that starts now must search under. */
    fun revisionScope(): RevisionScope = gate.current()

    /** Takes a reader's lease on the snapshot that is current now. */
    fun acquire(): RevisionLease = gate.acquire()

    /**
     * Admits a publication of `candidateRevisionId` over `baseRevisionId` and drives it to completion.
     *
     * @return the opaque identifier of the durable operation, whose phase, error code and message are
     *   read back from `revision_publications` rather than from this call's lifetime.
     */
    suspend fun publish(
        documentId: DocumentId,
        baseRevisionId: String?,
        candidateRevisionId: String,
        observe: (PublicationStep) -> Unit = {},
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

        // 1a. A target that cannot become a published reading is refused durably, in the words of what it
        //     is waiting for. Nothing is staged, nothing is hidden, and the baseline is untouched.
        val refusal = refusalFor(candidateRevisionId)
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

        val pages = revisions.pages(candidateRevisionId)
        val chunks = revisions.chunks(candidateRevisionId)

        // 1b. Persist the intent. Its durable artifacts are already the revision's own pages, chunks and
        //     vectors, so the PREPARED attempt is complete on its own: nothing has to be reconstructed and
        //     the index has not been touched, which is what lets recovery either finish or discard it
        //     without guessing.
        val intent = revisions.prepare(
            documentId = documentId,
            collectionId = document.collectionId,
            baseRevisionId = baseRevisionId,
            targetRevisionId = candidateRevisionId,
        )
        observe(PublicationStep.INTENT_PERSISTED)
        // The candidate is hidden before a row of it exists. Hiding it here rather than at the switch below is
        // what keeps a failure in the middle of the boundary from exposing rows nothing owns: a staged commit
        // that never becomes authoritative leaves rows in the index, and a scope that had not hidden them would
        // hand those rows to the next reader. The switch then only has to reveal what it publishes.
        gate.handoff { gate.current().hiding(candidateRevisionId) }
        // Whether this attempt has written index rows that only its own tag keeps track of. It is set before
        // the write rather than after it: a staging step that fails halfway leaves buffered changes in the
        // writer, and removing them by the same tag in the same writer is what keeps them from surfacing later.
        var candidateRowsMayExist = false
        try {
            // 2. The shared publication boundary. It is the only place the candidate's rows are written,
            //    which is what makes them unexposable by construction rather than by a filter: no reader
            //    can acquire a snapshot while the boundary holds the gate, and a reader that already held
            //    one is reading the searcher it leased, which the new rows are not in yet.
            mutations.withMutation {
                gate.handoff {
                    // Rechecked under the handoff rather than merely under the shared permit: two publications
                    // can hold that permit at once, and the reading named as the base can only be trusted once
                    // no other handoff is moving it.
                    recheck(documentId, document.collectionId, baseRevisionId)
                    // The boundary is entered before the rows exist, so a rebuild cannot have swapped the
                    // generation underneath them: exclusive maintenance and this permit exclude each other.
                    candidateRowsMayExist = true
                    stage(intent, pages, chunks)
                    observe(PublicationStep.ROWS_STAGED)
                    index().commit()
                    observe(PublicationStep.STAGED_COMMITTED)
                    revisions.makeAuthoritative(intent, revisionPages(pages, chunks))
                    observe(PublicationStep.AUTHORITATIVE)
                    // The replaced reading's rows go before the switch, while no reader can be inside: a
                    // reading that carries no revision tag — an import's rows, or an archive the migration
                    // backfilled — cannot be hidden by id, so removing it is the only way to keep it from
                    // being served beside the reading that replaced it.
                    if (baseRevisionId != null) {
                        index().deleteSupersededRows(documentId, candidateRevisionId)
                    }
                    gate.current().revealing(candidateRevisionId).hiding(baseRevisionId)
                }
            }

            // 3. New readers see the target from here on; readers inside a lease keep the old snapshot.
            observe(PublicationStep.SNAPSHOT_PUBLISHED)
            revisions.markPublished(intent.id)
            observe(PublicationStep.PUBLISHED)

            // 4. The replaced reading's rows wait for the readers that may still be inside them.
            cleanup(intent)
            observe(PublicationStep.CLEANED)
            return intent.id
        } catch (failure: Throwable) {
            if (authorityMoved(intent.id)) {
                // The database already says the target revision is published, so the reading this document
                // serves has already changed. Reporting a failure would tell the caller the old reading still
                // wins, which is no longer true. The honest answer is the operation's own identifier, and the
                // publication is finished here if it still can be: every step is idempotent, so running the
                // recovery path now is the same work a later startup would do, only sooner.
                LOGGER.atWarn()
                    .addKeyValue(PUBLICATION_FIELD, intent.id)
                    .addKeyValue(DOCUMENT_FIELD, documentId.value)
                    .setCause(failure)
                    .log("publication authority is durable; finishing it rather than reporting a failure")
                // Whether the archive is already coherent is one question, and the scope answers it in one word:
                // is the target revision revealed? The boundary reveals it only after the rows are committed,
                // the authority has moved and the replaced reading's rows are removed, all in the same handoff —
                // so a revealed target means the index, the snapshot and the database already agree and the
                // failure was bookkeeping afterwards; refusing readers then would punish a coherent archive.
                // A hidden target means the switch never happened: SQLite already serves the new reading while
                // search still serves the old one, which is the one state that must refuse. It refuses *before*
                // the roll-forward below rather than after it, because the roll-forward stages and commits rows
                // outside a handoff, and a reader admitted in that window would see the old reading beside the
                // new source text. Completing the publication unseals the gate once its switch has happened.
                val coherent = gate.current().reveals(intent.targetRevisionId)
                if (!coherent) {
                    gate.seal("an authoritative publication could not be finished; restart to complete it")
                }
                try {
                    // Finishing it is index work like any other, so it goes through the same permit: exclusive
                    // maintenance must not have a roll-forward committed underneath it.
                    mutations.withMutation { complete(intent, observe) }
                } catch (finishing: Throwable) {
                    LOGGER.atWarn()
                        .addKeyValue(PUBLICATION_FIELD, intent.id)
                        .addKeyValue(DOCUMENT_FIELD, documentId.value)
                        .setCause(finishing)
                        .log(
                            if (coherent) {
                                "a publication's bookkeeping failed after the reading it publishes was switched " +
                                    "in; readers keep being served"
                            } else {
                                "a publication whose authority is durable could not be finished in this process; " +
                                    "reads are refused until recovery completes it"
                            },
                        )
                }
                return intent.id
            }
            discard(intent, PUBLICATION_FAILED, messageOf(failure), removeRows = candidateRowsMayExist)
            throw failure
        }
    }

    /**
     * Resolves every publication attempt a previous process left unfinished.
     *
     * This runs before the context is handed out, so no reader and no worker can observe the state it
     * repairs. The decision is one read: an intent that never recorded authority did not move it, so the
     * reading the document already published wins and the staged rows go away; an intent that recorded
     * authority is completed, because the database and the index must agree and the database is the one
     * that says so.
     */
    suspend fun recoverUnfinished(observe: (PublicationStep) -> Unit = {}): PublicationRecoveryReport {
        val completed = mutableListOf<String>()
        val discarded = mutableListOf<String>()
        // An attempt whose authority moved and whose cleanup never ran is not unfinished — it is
        // published — but its replaced reading's rows are still in the index and no in-process snapshot
        // survives to keep them hidden. Removing them is what makes the index say exactly what the
        // database says before the first reader is served.
        for (intent in revisions.intentsAwaitingCleanup()) {
            // An attempt that never reached PUBLISHED is still resolved below, from its own intent; only a
            // published one is a cleanup that is owed and nothing else will do.
            if (intent.publishedAt == null) continue
            if (intent.baseRevisionId != null) {
                index().deleteSupersededRows(intent.documentId, intent.targetRevisionId)
            }
            revisions.markCleaned(intent.id)
        }
        for (intent in revisions.unfinishedIntents()) {
            if (intent.authoritativeAt == null) {
                LOGGER.atInfo()
                    .addKeyValue(PUBLICATION_FIELD, intent.id)
                    .addKeyValue(DOCUMENT_FIELD, intent.documentId.value)
                    .log("discarding a publication intent that never moved authority; the old revision wins")
                discard(
                    intent,
                    PUBLICATION_NOT_AUTHORITATIVE,
                    PUBLICATION_NOT_AUTHORITATIVE_MESSAGE,
                    removeRows = true,
                )
                discarded += intent.id
            } else {
                LOGGER.atInfo()
                    .addKeyValue(PUBLICATION_FIELD, intent.id)
                    .addKeyValue(DOCUMENT_FIELD, intent.documentId.value)
                    .log("completing a publication whose authority is already durable")
                complete(intent, observe)
                completed += intent.id
            }
        }
        return PublicationRecoveryReport(completed = completed, discarded = discarded)
    }

    /** Finishes a publication whose authority is already durable, from its own staged artifacts. */
    private suspend fun complete(intent: PublicationIntent, observe: (PublicationStep) -> Unit) {
        val pages = revisions.pages(intent.targetRevisionId)
        val chunks = revisions.chunks(intent.targetRevisionId)
        if (pages.isEmpty() || chunks.any { !it.isStaged }) {
            // The intent was admitted only with a complete set of artifacts, so this is a damaged archive
            // rather than a state to interpret: finishing it would publish a document with missing text.
            throw IllegalStateException(
                "publication ${intent.id} recorded authority but its revision " +
                    "${intent.targetRevisionId} no longer holds complete artifacts",
            )
        }
        stage(intent, pages, chunks)
        observe(PublicationStep.ROWS_STAGED)
        index().commit()
        observe(PublicationStep.STAGED_COMMITTED)
        gate.handoff {
            // The same order as a live publication, for the same reason: the replaced reading is removed
            // before the snapshot stops hiding it, so a reading that carries no revision tag cannot be
            // served beside the one that replaced it.
            if (intent.baseRevisionId != null) {
                index().deleteSupersededRows(intent.documentId, intent.targetRevisionId)
            }
            gate.current().revealing(intent.targetRevisionId).hiding(intent.baseRevisionId)
        }
        // The snapshot now names the reading the database already serves, so a refusal raised by an earlier
        // attempt at this publication is over and readers may be served again.
        gate.unseal()
        observe(PublicationStep.SNAPSHOT_PUBLISHED)
        revisions.markPublished(intent.id)
        observe(PublicationStep.PUBLISHED)
        cleanup(intent)
        observe(PublicationStep.CLEANED)
    }

    /**
     * Gives up on an intent that never moved authority, leaving the reading it was replacing in place.
     *
     * The staged rows are removed by their own revision tag, so a publication that reached the boundary
     * and was killed after its staged commit leaves the document exactly as it was; an attempt that never
     * wrote a row has nothing to remove and the call is a no-op.
     */
    /**
     * Gives up on an intent that never moved authority, leaving the reading it was replacing in place.
     *
     * [removeRows] says whether this attempt can have written index rows of its own: an attempt that never
     * reached its staging step has none, and an attempt that did is cleaned by its own revision tag. Removing
     * rows writes to the index, so that half needs the staging permit — under exclusive maintenance the
     * attempt is left PREPARED instead, because the candidate is hidden by the scope and the next startup
     * removes its rows before anyone is served. An attempt with nothing to remove is abandoned immediately,
     * which is what the maintenance case gets: a publication that never wrote a row has nothing to clean up.
     */
    private suspend fun discard(
        intent: PublicationIntent,
        code: String,
        message: String,
        removeRows: Boolean,
    ): Boolean {
        if (!removeRows) {
            gate.handoff { gate.current().revealing(intent.targetRevisionId) }
            revisions.abandon(intent.id, PublicationPhase.ABANDONED, code, message)
            return true
        }
        try {
            mutations.withMutation {
                index().deleteStagedRevision(intent.documentId, intent.targetRevisionId)
            }
        } catch (maintenance: MaintenanceInProgressException) {
            LOGGER.atWarn()
                .addKeyValue(PUBLICATION_FIELD, intent.id)
                .addKeyValue(DOCUMENT_FIELD, intent.documentId.value)
                .setCause(maintenance)
                .log("maintenance holds the index; leaving a discarded publication's rows for recovery")
            return false
        }
        gate.handoff { gate.current().revealing(intent.targetRevisionId) }
        revisions.abandon(intent.id, PublicationPhase.ABANDONED, code, message)
        return true
    }

    /** Removes the rows of the reading the target replaced, once no reader can still be inside them. */
    private suspend fun cleanup(intent: PublicationIntent) {
        if (intent.baseRevisionId == null) {
            // Nothing was replaced, so there is nothing whose rows could be a second reading.
            revisions.markCleaned(intent.id)
            gate.handoff { gate.current().revealing(intent.targetRevisionId) }
            return
        }
        if (!gate.drainReaders(cleanupDrainMillis)) {
            LOGGER.atWarn()
                .addKeyValue(PUBLICATION_FIELD, intent.id)
                .log(
                    "readers were still inside the previous revision after $cleanupDrainMillis ms; " +
                        "its rows are left in place for a rebuild or the next startup to remove",
                )
            return
        }
        index().deleteSupersededRows(intent.documentId, intent.targetRevisionId)
        revisions.markCleaned(intent.id)
        gate.handoff { gate.current().revealing(intent.baseRevisionId).revealing(intent.targetRevisionId) }
    }

    /**
     * The reasons a candidate cannot be published yet, as a code and an actionable sentence.
     *
     * A page that has not been approved is not a failure of the publication: it is a document waiting for
     * a review decision, and publishing the approved pages alone would silently drop the rest of the
     * document. Every page of the revision therefore has to be approved before any of it is published.
     */
    private fun refusalFor(revisionId: String): Pair<String, String>? {
        val pages = revisions.pages(revisionId)
        if (pages.isEmpty() || pages.any { it.approval != PageApproval.APPROVED }) {
            return AWAITING_REVIEW_CODE to
                "the replacement's text has not been fully approved yet; no page was published and the " +
                "document still shows its current text"
        }
        val chunks = revisions.chunks(revisionId)
        if (chunks.isEmpty()) {
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

    /** Writes the candidate's rows into the index. Uncommitted: the boundary commits them. */
    private suspend fun stage(intent: PublicationIntent, pages: List<RevisionPageText>, chunks: List<RevisionChunk>) {
        index().stageDocument(stagedRows(intent, pages, chunks))
    }

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

    private fun authorityMoved(intentId: String): Boolean =
        revisions.intent(intentId)?.authoritativeAt != null

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

        /**
         * How long the rows of a replaced reading wait for the readers that may still be inside them.
         *
         * A reader is one search, so this is generous; running out is not an error, because the rows are
         * hidden by the scope either way and both a rebuild and the next startup remove them.
         */
        const val CLEANUP_DRAIN_MILLIS: Long = 5_000
    }
}

private val LOGGER = LoggerFactory.getLogger("infoscry.document")

private const val PUBLICATION_FIELD: String = "publication"
private const val DOCUMENT_FIELD: String = "document"
