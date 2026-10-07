package infoscry.document

import infoscry.collection.DeletionBlockers
import infoscry.domain.CollectionId
import infoscry.domain.CollectionLifecycle
import infoscry.domain.DocumentId
import infoscry.embedding.DocumentEmbedder
import infoscry.storage.CollectionNotActiveException
import infoscry.storage.CollectionStore
import infoscry.storage.DocumentBeingDeletedException
import infoscry.storage.DocumentRevisionStore
import infoscry.storage.DocumentStore
import infoscry.storage.MaintenanceInProgressException
import infoscry.storage.MutationCoordinator
import infoscry.storage.OcrOperationConflictException
import infoscry.storage.OcrOperationStore
import infoscry.storage.PageApproval
import infoscry.storage.RestorePhase
import infoscry.storage.RevisionRestoreRecord
import infoscry.storage.RevisionState
import java.security.MessageDigest
import java.util.HexFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

/**
 * One instant a restore can be parked at, in the order they happen.
 *
 * The first two are the restore's own — its candidate exists and is staged, then its passages are embedded —
 * and the ones between [INTENT_PERSISTED] and [CLEANED] are the publication protocol's, relayed unchanged under
 * the same names ([PublicationStep]), so a test that kills a restore at one of them is killing the shared
 * boundary, not a copy of it. [RECORDED] is the instant the restore request itself says it is published.
 */
enum class RestoreStep {
    STAGED,
    EMBEDDED,
    INTENT_PERSISTED,
    ROWS_STAGED,
    STAGED_COMMITTED,
    AUTHORITATIVE,
    SNAPSHOT_PUBLISHED,
    PUBLISHED,
    CLEANED,
    RECORDED,
}

/**
 * A restore that cannot be done as asked, in this project's own vocabulary.
 *
 * [code] is a safe code and [message] a curated sentence: neither carries a provider's words, a path or any
 * text of a document. [unavailable] says the refusal is the machine's rather than the request's, which is the
 * difference between a 503 and a 409.
 */
class RestoreRefusalException(
    val code: String,
    message: String,
    val unavailable: Boolean = false,
    cause: Throwable? = null,
) : IllegalStateException(message, cause) {

    companion object {
        /** The revision named is the one the document already publishes. */
        const val ALREADY_ACTIVE: String = "RESTORE_ALREADY_ACTIVE"

        /** The document's collection is being deleted. */
        const val COLLECTION_NOT_ACTIVE: String = "COLLECTION_NOT_ACTIVE"

        /** The revision named published no text, so restoring it would publish nothing. */
        const val NOTHING_TO_RESTORE: String = "RESTORE_NOTHING_PUBLISHED"

        /** The embedder failed; the current revision is still the document's text. */
        const val EMBEDDING_FAILED: String = "RESTORE_EMBEDDING_FAILED"

        /** This machine has no embedder, and a restore never substitutes another way of embedding. */
        const val EMBEDDING_UNAVAILABLE: String = "RESTORE_EMBEDDING_UNAVAILABLE"

        /** Another restore of the same document has not finished. */
        const val IN_PROGRESS: String = "RESTORE_IN_PROGRESS"

        /** The publication protocol refused or failed the restored reading. */
        const val PUBLICATION_REFUSED: String = "RESTORE_PUBLICATION_REFUSED"

        /** A process ended before the restore was published; the previous text won. */
        const val INTERRUPTED: String = "RESTORE_INTERRUPTED"

        /** The document was deleted while the restore was staged. */
        const val DOCUMENT_BEING_DELETED: String = "DOCUMENT_BEING_DELETED"
    }
}

/** A request id was reused for a restore with another body: nothing was admitted. */
class RestoreRequestConflictException(val requestId: String) : IllegalStateException(
    "request '$requestId' was already used for a different restore of this document, so nothing was " +
        "restored; use the operation that request id already names, or send a new request id",
)

/**
 * Restores a historical revision of a document as a new publication.
 *
 * A restore is not a rollback and not a rescan. History is immutable, so the historical revision is never
 * reactivated: a *new* revision is staged whose pages are copies of that revision's page texts (same stable
 * unit ids, same recorded provenance, same page images — nothing is read again, so no OCR engine is reachable
 * from here), its passages are embedded with the embedder this process has, and it is published through
 * [RevisionPublicationService]. That is the same recoverable protocol a rescan publishes through — PREPARED
 * intent, staged index commit, SQLite authority, reader-snapshot switch — so a restore has no consistency
 * boundary of its own and can be neither more nor less coherent than a rescan.
 *
 * What it adds to that protocol is admission and a request record:
 *
 * - **Admission is one short shared-permit step**, which is the same gate every other writer uses. Under it the
 *   collection's lifecycle, the document's deletion state and the maintenance gate are checked, the revision
 *   the caller was looking at must still be the document's text, and a conflicting operation refuses the
 *   request. The permit is released before embedding — a slow model call is never made while holding it — and
 *   the publication boundary re-checks all of it under its own permit, which is where a deletion that landed
 *   in between wins and a restore cannot resurrect what it removed.
 * - **A failure leaves the previous text alone.** The candidate is withdrawn and the request records why; the
 *   active revision, the search index and every source read are exactly as they were.
 * - **The request is a durable record** (`revision_restores`): the same request id and body is the same
 *   operation, another body under that id conflicts, and a record still in flight after a crash is resolved at
 *   startup from the publication protocol's own answer ([recoverInterrupted]).
 */
class RevisionRestoreService(
    private val revisions: DocumentRevisionStore,
    private val documents: DocumentStore,
    private val collections: CollectionStore,
    private val mutations: MutationCoordinator,
    private val blockers: DeletionBlockers,
    private val operations: OcrOperationStore,
    private val publication: RevisionPublicationService,
    /** The embedder this process has, or null when there is none; resolved on demand and never cached here. */
    private val embedder: () -> DocumentEmbedder?,
) {

    /**
     * Serializes admission, so two callers repeating one request id cannot both stage a candidate. It is held
     * for the short admission step only; embedding and publication run outside it.
     */
    private val admission = Mutex()

    /**
     * Restores [restoreRevisionId] as a new published revision of [documentId] and answers with the request.
     *
     * @param expectedRevisionId the revision the caller was looking at; anything else is a stale caller.
     * @throws StaleActiveRevisionException when the document publishes another revision now.
     * @throws NoSuchElementException when the collection, the document or the revision is not in this scope —
     *   one answer for a missing id and a foreign one.
     * @throws RestoreRefusalException when the request is understood and the archive's state says no.
     * @throws DocumentBeingDeletedException when the document is a deletion's target.
     * @throws RestoreRequestConflictException when the request id was used for another restore.
     */
    suspend fun restore(
        collectionId: CollectionId,
        documentId: DocumentId,
        requestId: String,
        expectedRevisionId: String,
        restoreRevisionId: String,
        observe: (RestoreStep) -> Unit = {},
    ): RevisionRestoreRecord {
        require(requestId.isNotBlank()) { "a restore needs a request id" }
        require(expectedRevisionId.isNotBlank()) { "a restore names the revision it was taken against" }
        require(restoreRevisionId.isNotBlank()) { "a restore names the revision to restore" }
        val requestHash = hashOf(collectionId, documentId, expectedRevisionId, restoreRevisionId)

        // Resolved outside any permit: the first call can load a model, which is not something to do while the
        // archive's writers are waiting on this gate.
        val resolved = try {
            embedder()
        } catch (failure: Exception) {
            LOGGER.atWarn().addKeyValue(ERROR_FIELD, failure::class.simpleName).log("the embedder could not be resolved")
            null
        }

        val admitted = admission.withLock {
            mutations.withMutation {
                admit(collectionId, documentId, requestId, requestHash, expectedRevisionId, restoreRevisionId, resolved != null)
            }
        }
        if (admitted.replay) return replayed(admitted.record)
        val record = admitted.record
        // Past this point the candidate exists, so every way out resolves the request.
        try {
            observe(RestoreStep.STAGED)
            embedOrRefuse(record, checkNotNull(resolved))
            observe(RestoreStep.EMBEDDED)
            val publicationId = publication.publish(documentId, expectedRevisionId, record.newRevisionId) { step ->
                observe(RestoreStep.valueOf(step.name))
            }
            val intent = revisions.intent(publicationId)
            if (intent?.authoritativeAt != null) {
                // The database says the restored reading is the document's text, whatever bookkeeping is left.
                revisions.finishRestore(record.restoreId, RestorePhase.PUBLISHED)
                observe(RestoreStep.RECORDED)
                return checkNotNull(revisions.restore(record.restoreId))
            }
            val code = intent?.errorCode ?: RestoreRefusalException.PUBLICATION_REFUSED
            val message = intent?.errorMessage
                ?: "the restored reading could not be published, so the document still shows its current text"
            throw RestoreRefusalException(code, message)
        } catch (failure: Throwable) {
            throw resolveFailure(record, failure)
        }
    }

    /**
     * Resolves every restore request a previous process left in flight, before anything is served.
     *
     * It runs after [RevisionPublicationService.recoverUnfinished], so every publication that could be finished
     * or discarded already has been, and the only question left is what the request itself was left as: if the
     * database already serves its revision it is published (the process died between the authority and the
     * request's own record), and otherwise the previous text won and the request failed as interrupted.
     *
     * @return the identifiers of the requests it resolved.
     */
    suspend fun recoverInterrupted(): List<String> = revisions.unfinishedRestores().map { record ->
        val state = revisions.revision(record.newRevisionId)?.state
        if (state == RevisionState.PUBLISHED || state == RevisionState.SUPERSEDED) {
            revisions.finishRestore(record.restoreId, RestorePhase.PUBLISHED)
        } else {
            revisions.withdrawCandidate(record.newRevisionId)
            revisions.finishRestore(
                record.restoreId,
                RestorePhase.FAILED,
                RestoreRefusalException.INTERRUPTED,
                INTERRUPTED_MESSAGE,
            )
        }
        record.restoreId
    }

    /** One restore request, readable only under the collection and document the caller named. */
    fun restoreOf(collectionId: CollectionId, documentId: DocumentId, restoreId: String): RevisionRestoreRecord {
        requireDocument(collectionId, documentId)
        return revisions.restore(restoreId)?.takeIf { it.documentId == documentId && it.collectionId == collectionId }
            // Another document's or collection's request is answered exactly like one that does not exist.
            ?: throw NoSuchElementException("no restore with id $restoreId exists")
    }

    // ---- admission ----

    private class Admitted(val record: RevisionRestoreRecord, val replay: Boolean)

    private fun admit(
        collectionId: CollectionId,
        documentId: DocumentId,
        requestId: String,
        requestHash: String,
        expectedRevisionId: String,
        restoreRevisionId: String,
        embedderAvailable: Boolean,
    ): Admitted {
        blockers.requireMutationsAllowed()
        val collection = collections.get(collectionId)
            ?: throw NoSuchElementException("no collection with id ${collectionId.value} exists")
        requireDocument(collectionId, documentId)
        // A repeated request is decided from the durable record before anything about the document's present
        // state: a caller that retried after a timeout gets its own operation back, and one that reused the id
        // for another restore is refused rather than given a second one.
        revisions.restoreForRequest(collectionId, documentId, requestId)?.let { existing ->
            if (existing.requestHash != requestHash) throw RestoreRequestConflictException(requestId)
            return Admitted(existing, replay = true)
        }
        if (collection.lifecycle != CollectionLifecycle.ACTIVE) {
            throw RestoreRefusalException(
                RestoreRefusalException.COLLECTION_NOT_ACTIVE,
                "this collection is being deleted, so no restore may be admitted for its documents",
            )
        }
        if (documents.isDeletionTarget(documentId)) throw DocumentBeingDeletedException(documentId)

        // Only a revision that was published can be restored, and a revision of another document, of another
        // collection, a candidate and one that does not exist are one answer.
        val source = revisions.revision(restoreRevisionId)
            ?.takeIf { it.documentId == documentId }
            ?.takeIf { it.state == RevisionState.PUBLISHED || it.state == RevisionState.SUPERSEDED }
            ?: throw NoSuchElementException("no revision with id $restoreRevisionId exists for this document")
        val active = revisions.activeRevisionId(documentId)
        if (active != expectedRevisionId) {
            throw StaleActiveRevisionException(
                "the document publishes ${active ?: "no revision"} now, not the reading this restore names, so it " +
                    "was refused rather than applied to text nobody saw",
            )
        }
        if (source.id == active) {
            throw RestoreRefusalException(
                RestoreRefusalException.ALREADY_ACTIVE,
                "that revision is already the document's text, so there is nothing to restore",
            )
        }
        // A rescan owns the document while it reads and decides; a restore under it would move the reading
        // its candidate was staged against.
        operations.activeOperation(documentId)?.let { running ->
            throw OcrOperationConflictException(documentId, running.stage)
        }
        if (revisions.inFlightRestore(documentId) != null) {
            throw RestoreRefusalException(
                RestoreRefusalException.IN_PROGRESS,
                "another restore of this document has not finished; wait for it to finish",
            )
        }
        if (revisions.pages(source.id).none { it.approval == PageApproval.APPROVED }) {
            throw RestoreRefusalException(
                RestoreRefusalException.NOTHING_TO_RESTORE,
                "that revision published no text, so restoring it would publish nothing",
            )
        }
        if (!embedderAvailable) {
            throw RestoreRefusalException(
                RestoreRefusalException.EMBEDDING_UNAVAILABLE,
                "this machine has no embedding model available, so a reading cannot be restored; keyword search " +
                    "and source viewing are unaffected and the document still shows its current text",
                unavailable = true,
            )
        }
        return Admitted(
            revisions.stageRestore(
                collectionId = collectionId,
                documentId = documentId,
                requestId = requestId,
                requestHash = requestHash,
                expectedRevisionId = expectedRevisionId,
                sourceRevisionId = source.id,
            ),
            replay = false,
        )
    }

    /** What repeating a request answers: the operation as it stands, and a failure with the failure it was. */
    private fun replayed(record: RevisionRestoreRecord): RevisionRestoreRecord {
        if (record.phase != RestorePhase.FAILED) return record
        throw RestoreRefusalException(
            code = record.errorCode ?: RestoreRefusalException.PUBLICATION_REFUSED,
            message = record.errorMessage ?: "this restore failed, and the document still shows its current text",
            unavailable = record.errorCode == RestoreRefusalException.EMBEDDING_FAILED,
        )
    }

    // ---- embedding ----

    /**
     * [embed], with a failure of the model said in this project's own words.
     *
     * What the embedder threw may carry a provider's text, so it is kept as the cause for the log and never
     * reaches the caller: the refusal's code and its curated sentence are the whole answer.
     */
    private suspend fun embedOrRefuse(record: RevisionRestoreRecord, embedder: DocumentEmbedder) {
        try {
            embed(record, embedder)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            LOGGER.atWarn()
                .addKeyValue(RESTORE_FIELD, record.restoreId)
                .addKeyValue(ERROR_FIELD, failure::class.simpleName)
                .log("the passages of a restored reading could not be embedded")
            throw RestoreRefusalException(
                RestoreRefusalException.EMBEDDING_FAILED,
                "the passages of that reading could not be embedded, so nothing was restored and the document " +
                    "still shows its current text; try again",
                unavailable = true,
                cause = failure,
            )
        }
    }

    /**
     * Embeds every passage of the candidate, one page at a time and in bounded batches.
     *
     * A page's vectors are written together, so a failure leaves whole pages embedded or not at all, and the
     * candidate is withdrawn by the caller either way: a restore is not resumed, it is asked for again.
     */
    private suspend fun embed(record: RevisionRestoreRecord, embedder: DocumentEmbedder) {
        withContext(Dispatchers.IO) {
            revisions.chunks(record.newRevisionId).groupBy { it.unitOrdinal }.forEach { (ordinal, chunks) ->
                val ordered = chunks.sortedBy { it.ordinal }
                val vectors = ordered.chunked(EMBED_BATCH).flatMap { batch ->
                    val embedded = embedder.embedDocuments(batch.map { it.text })
                    check(embedded.size == batch.size) {
                        "the embedder returned ${embedded.size} vectors for ${batch.size} passages"
                    }
                    embedded
                }
                revisions.recordChunkVectors(record.newRevisionId, ordinal, vectors)
            }
        }
    }

    // ---- failure ----

    /**
     * Ends the request that [failure] interrupted, and returns what the caller is to be told.
     *
     * Resolving the request happens even when the failure is a cancellation: a request left in flight would
     * block every later restore of the document until the next startup. The one thing that is never
     * overwritten is a publication whose authority already moved — the database says the restore is the
     * document's text, so that is what the request says too.
     */
    private suspend fun resolveFailure(record: RevisionRestoreRecord, failure: Throwable): Throwable =
        withContext(NonCancellable) {
            val state = revisions.revision(record.newRevisionId)?.state
            if (state == RevisionState.PUBLISHED || state == RevisionState.SUPERSEDED) {
                revisions.finishRestore(record.restoreId, RestorePhase.PUBLISHED)
                return@withContext failure
            }
            val answer = answerFor(record, failure)
            val code = (answer as? RestoreRefusalException)?.code ?: failure.codeOrNull()
            revisions.withdrawCandidate(record.newRevisionId)
            revisions.finishRestore(
                record.restoreId,
                RestorePhase.FAILED,
                code ?: RestoreRefusalException.PUBLICATION_REFUSED,
                (answer as? RestoreRefusalException)?.message ?: failure.curatedMessage(),
            )
            answer
        }

    /** What the caller is told about [failure]: a typed refusal where one exists, curated words otherwise. */
    private fun answerFor(record: RevisionRestoreRecord, failure: Throwable): Throwable = when (failure) {
        is RestoreRefusalException -> failure

        is CancellationException, is DocumentBeingDeletedException, is MaintenanceInProgressException,
        is NoSuchElementException,
        -> failure

        is CollectionNotActiveException -> RestoreRefusalException(
            RestoreRefusalException.COLLECTION_NOT_ACTIVE,
            "this collection is being deleted, so no restore may be admitted for its documents",
            cause = failure,
        )

        else -> {
            LOGGER.atWarn()
                .addKeyValue(RESTORE_FIELD, record.restoreId)
                .addKeyValue(ERROR_FIELD, failure::class.simpleName)
                .log("a restore failed and the document still shows its current text")
            val active = revisions.activeRevisionId(record.documentId)
            if (active != record.expectedRevisionId) {
                StaleActiveRevisionException(
                    "the document publishes ${active ?: "no revision"} now, not the reading this restore names, " +
                        "so it was refused rather than applied to text nobody saw",
                )
            } else {
                RestoreRefusalException(
                    RestoreRefusalException.PUBLICATION_REFUSED,
                    "the restored reading could not be published, so the document still shows its current text",
                    cause = failure,
                )
            }
        }
    }

    /** The code a failure that is not a refusal is recorded under, when it names one itself. */
    private fun Throwable.codeOrNull(): String? = when (this) {
        is DocumentBeingDeletedException -> RestoreRefusalException.DOCUMENT_BEING_DELETED
        is MaintenanceInProgressException -> MAINTENANCE_CODE
        is CancellationException -> RestoreRefusalException.INTERRUPTED
        is NoSuchElementException -> NOT_FOUND_CODE
        else -> null
    }

    private fun Throwable.curatedMessage(): String = when (this) {
        is DocumentBeingDeletedException -> "the document was deleted while the restore was staged; nothing was restored"
        is MaintenanceInProgressException -> "maintenance was running, so nothing was restored; retry once it has finished"
        is CancellationException -> INTERRUPTED_MESSAGE
        is NoSuchElementException -> "the document no longer exists, so nothing was restored"
        else -> "the restored reading could not be published, so the document still shows its current text"
    }

    private fun requireDocument(collectionId: CollectionId, documentId: DocumentId) {
        documents.get(documentId)?.takeIf { it.collectionId == collectionId }
            ?: throw NoSuchElementException("no document with id ${documentId.value} exists")
    }

    private fun hashOf(collectionId: CollectionId, documentId: DocumentId, expected: String, restoring: String): String =
        HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(
                listOf(collectionId.value, documentId.value, expected, restoring).joinToString("\u0000")
                    .toByteArray(Charsets.UTF_8),
            ),
        )

    private companion object {
        /** How many passages one embedding call holds, so a long page stays interruptible. */
        const val EMBED_BATCH = 64

        const val INTERRUPTED_MESSAGE: String =
            "the restore was interrupted before it became the document's text; the document still shows the " +
                "text it published before"

        const val MAINTENANCE_CODE: String = "MAINTENANCE_IN_PROGRESS"
        const val NOT_FOUND_CODE: String = "NOT_FOUND"
        const val RESTORE_FIELD = "restore"
        const val ERROR_FIELD = "error"
    }
}

private val LOGGER = LoggerFactory.getLogger("infoscry.document")
