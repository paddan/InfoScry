package infoscry.storage

import infoscry.domain.DocumentId
import infoscry.domain.JobState
import infoscry.ocr.OcrDispatchStage
import infoscry.ocr.OcrExternalAccount
import infoscry.ocr.OcrExternalOwner
import infoscry.ocr.OcrExternalOwnerKind
import infoscry.ocr.OcrOperation
import infoscry.ocr.OcrOperationStage
import infoscry.ocr.OcrSettingsSnapshot
import infoscry.ocr.RescanPreview
import infoscry.ocr.operationHash
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.util.UUID
import kotlinx.serialization.json.Json

/** The request id of an admission that another admission with the same id already made differently. */
class OcrRequestConflictException(val requestId: String) : IllegalStateException(
    "request '$requestId' was already used to admit a rescan of this document with a different request, " +
        "so nothing was admitted; use the operation that request id already names, or send a new request id",
)

/** A rescan was admitted against a preview that is no longer the document's state, or is too old. */
class StaleRescanPreviewException(val previewId: String, reason: String) : IllegalStateException(
    "rescan preview '$previewId' is no longer valid: $reason",
)

/** A rescan was requested for a document another operation of this archive is already reading. */
class OcrOperationConflictException(val documentId: DocumentId, val stage: OcrOperationStage) :
    IllegalStateException(
        "document ${documentId.value} is already under a rescan operation in stage $stage; " +
            "cancel or finish it before admitting another one",
    )

/**
 * A resume would start a second attempt of an operation that already has one.
 *
 * The operation is the unit of ownership and an attempt is its only owner at a time: two attempts of one
 * operation would read the same pages into the same candidate and count the same external pages twice. The
 * refusal is durable rather than a lock in a process — the attempt the operation names is the answer — so a
 * second caller is refused even when it never saw the first.
 */
class OcrAttemptInProgressException(val operationId: String, val jobId: String) : IllegalStateException(
    "rescan operation $operationId is already being attempted by job $jobId; wait for it to pause or finish " +
        "instead of starting a second attempt of the same reading",
)

/**
 * The durable rescan operations of this archive, their previews, and bounded external-page accounting.
 *
 * Every question this store answers is a question about *persisted* facts, which is the whole reason it is a
 * store and not a service:
 *
 * - **"What is this document doing?"** The operation row is the answer, and it is read from the database
 *   rather than from the process that started it, because an attempt may have ended long ago.
 * - **"Is this request the one that was already admitted?"** [(collection, document, requestId)] is unique,
 *   and a repeat with the same body returns that operation while a repeat with a different body is refused.
 *   A caller that retried a POST after a timeout gets its own operation back rather than a second reading.
 * - **"May this page leave the machine?"** The distinct-page row and the call row are written in the same
 *   transaction as the answer, so two concurrent dispatches cannot both see "one page left of the eight
 *   allowed" and both send. The decision and the count are one write. The operation's frozen snapshot owns
 *   the bound, so resuming never resets the counter or changes the admitted scope.
 *
 * Nothing here holds page text, a filesystem path or a provider message. The snapshot is settings; the
 * counters are counts; the error code is a safe code beside a curated remedy.
 */
class OcrOperationStore(private val database: Database) {

    /** Composes operation, document and job writes into one SQLite commit. */
    fun <T> atomically(block: () -> T): T = database.transaction { block() }

    // ---- previews ----

    /**
     * Writes one preview, and returns it.
     *
     * A preview is a durable statement about a document at a moment: the revision a rescan would replace and
     * the hash of the managed bytes it would read. Admission reads it back and re-validates against the
     * document, so a preview that no longer describes the document is refused rather than silently admitted.
     */
    fun recordPreview(
        collectionId: String,
        documentId: DocumentId,
        preview: RescanPreview,
        costEstimateJson: String? = null,
        overridesJson: String? = null,
    ): RescanPreview {
        require(collectionId.isNotBlank()) { "a preview belongs to a collection" }
        database.transaction { connection ->
            connection.prepareStatement(
                "INSERT INTO ocr_rescan_previews (preview_id, collection_id, document_id, base_revision_id, " +
                    "managed_sha256, snapshot, snapshot_hash, page_total, external_page_upper_bound, " +
                    "approval_required, cost_estimate, created_at, expires_at, overrides) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            ).use { statement ->
                statement.setString(1, preview.previewId)
                statement.setString(2, collectionId)
                statement.setString(3, documentId.value)
                statement.setString(4, preview.baseRevisionId)
                statement.setString(5, preview.managedHash)
                statement.setString(6, SNAPSHOT_JSON.encodeToString(OcrSettingsSnapshot.serializer(), preview.snapshot))
                statement.setString(7, preview.snapshotHash)
                if (preview.pageTotal == null) {
                    statement.setNull(8, java.sql.Types.INTEGER)
                } else {
                    statement.setInt(8, preview.pageTotal)
                }
                if (preview.externalPageUpperBound == null) {
                    statement.setNull(9, java.sql.Types.INTEGER)
                } else {
                    statement.setInt(9, preview.externalPageUpperBound)
                }
                // Keep the baseline column for older database schemas; new previews never advertise a
                // mid-run pause.
                statement.setInt(10, 0)
                statement.setString(11, costEstimateJson)
                statement.setString(12, Instants.now())
                statement.setString(13, preview.expiresAt)
                statement.setString(14, overridesJson)
                statement.executeUpdate()
            }
        }
        return preview
    }

    /** One preview as it was written, or null when no preview with that id exists. */
    fun preview(previewId: String): StoredPreview? = database.read { connection ->
        connection.prepareStatement(
            "SELECT collection_id, document_id, base_revision_id, managed_sha256, snapshot, snapshot_hash, " +
                "page_total, external_page_upper_bound, expires_at, overrides " +
                "FROM ocr_rescan_previews WHERE preview_id = ?",
        ).use { statement ->
            statement.setString(1, previewId)
            statement.executeQuery().use { rows -> if (rows.next()) rows.toStoredPreview(previewId) else null }
        }
    }

    /** One preview as the store holds it: the state admission re-validates against. */
    data class StoredPreview(
        val previewId: String,
        val collectionId: String,
        val documentId: DocumentId,
        val baseRevisionId: String?,
        val managedSha256: String,
        val snapshot: OcrSettingsSnapshot,
        val snapshotHash: String,
        val pageTotal: Int?,
        val externalPageUpperBound: Int?,
        val expiresAt: String,
    )

    // ---- operations ----

    /**
     * Admits one operation, or answers with the operation this request id already names.
     *
     * The two outcomes are one call because they are one decision: a caller that repeats a request — after a
     * timeout, or because a person clicked twice — has to get the operation it already has, and a caller that
     * reuses the request id for a different reading has to be refused. That decision is made from the
     * persisted row rather than from anything the caller says about itself.
     *
     * **One document, one reading** is checked here as well, inside this transaction and therefore against
     * the same write lock the insert takes: a service-level check followed by an insert is two transactions,
     * so two admissions could both see "no active operation" and both write one. The schema's own partial
     * unique index states the invariant again, which is why a conflict that ever got past this check fails
     * the insert instead of quietly becoming a second reader of one document.
     *
     * @throws OcrRequestConflictException when the request id names another reading.
     * A prior operation holding the document is ended as cancelled in this same transaction, so a crash
     * cannot leave the document released without its replacement admitted.
     */
    fun admit(
        collectionId: String,
        documentId: DocumentId,
        baseRevisionId: String?,
        snapshot: OcrSettingsSnapshot,
        requestId: String,
        requestHash: String,
        pageTotal: Int?,
        jobId: String?,
    ): OcrOperation = database.transaction { connection ->
        connection.selectRequestHash(collectionId, documentId, requestId)?.let { existingHash ->
            if (existingHash != requestHash) throw OcrRequestConflictException(requestId)
            return@transaction checkNotNull(connection.selectOperationByRequest(collectionId, documentId, requestId))
        }
        connection.selectActiveOperation(documentId)?.let { holding ->
            connection.prepareStatement(
                "UPDATE ocr_operations SET stage = ?, error_code = ?, error_message = ?, current_job_id = NULL, " +
                    "updated_at = ? WHERE operation_id = ?",
            ).use { statement ->
                statement.setString(1, OcrOperationStage.CANCELLED.name)
                statement.setString(2, RESCAN_CANCELLED_CODE)
                statement.setString(3, "a newer rescan replaced this operation")
                statement.setString(4, Instants.now())
                statement.setString(5, holding.operationId)
                check(statement.executeUpdate() == 1) {
                    "the active operation ${holding.operationId} changed during replacement"
                }
            }
        }
        val id = "ocr-" + UUID.randomUUID()
        val now = Instants.now()
        connection.prepareStatement(
            "INSERT INTO ocr_operations (operation_id, collection_id, document_id, current_job_id, " +
                "base_revision_id, candidate_revision_id, snapshot, snapshot_hash, stage, page_total, " +
                "pages_committed, pages_failed, error_code, error_message, request_id, request_hash, " +
                "created_at, updated_at) VALUES (?, ?, ?, ?, ?, NULL, ?, ?, ?, ?, 0, 0, NULL, NULL, ?, ?, ?, ?)",
        ).use { statement ->
            statement.setString(1, id)
            statement.setString(2, collectionId)
            statement.setString(3, documentId.value)
            statement.setString(4, jobId)
            statement.setString(5, baseRevisionId)
            statement.setString(6, SNAPSHOT_JSON.encodeToString(OcrSettingsSnapshot.serializer(), snapshot))
            statement.setString(7, snapshotHashOf(snapshot))
            statement.setString(8, OcrOperationStage.PREFLIGHT.name)
            if (pageTotal == null) {
                statement.setNull(9, java.sql.Types.INTEGER)
            } else {
                statement.setInt(9, pageTotal)
            }
            statement.setString(10, requestId)
            statement.setString(11, requestHash)
            statement.setString(12, now)
            statement.setString(13, now)
            statement.executeUpdate()
        }
        connection.readOperationById(id)
    }

    /**
     * The hash of the request body an admission recorded for one request id, or null when that id is unused.
     *
     * The hash is read separately from the operation because it is what decides whether a repeated request id
     * is the same request: an id with the same body returns the operation that exists, and an id with another
     * body is a conflict rather than a second reading.
     */
    fun requestHash(collectionId: String, documentId: DocumentId, requestId: String): String? =
        database.read { connection -> connection.selectRequestHash(collectionId, documentId, requestId) }

    /** One operation, or null when no operation with that id exists. Scope is the caller's check. */
    fun operation(operationId: String): OcrOperation? = database.read { connection ->
        selectOperation(
            connection,
            "$SELECT_OPERATION WHERE operation_id = ?",
        ) { statement -> statement.setString(1, operationId) }
    }

    /** The operation a request id already names for one document, or null. */
    fun operationForRequest(
        collectionId: String,
        documentId: DocumentId,
        requestId: String,
    ): OcrOperation? = database.read { connection ->
        connection.selectOperationByRequest(collectionId, documentId, requestId)
    }

    /**
     * The operation that currently holds one document, or null.
     *
     * This is what refuses an overlapping rescan: a document may be under one reading at a time, and the
     * "already running" answer is a stage the document's own row states rather than a lock in a process. The
     * predicate is the schema's own partial unique index, said in SQL rather than in Kotlin so the two cannot
     * drift: every stage still running, plus the legacy completed-review rows that startup cleanup retires.
     */
    fun activeOperation(documentId: DocumentId): OcrOperation? = database.read { connection ->
        connection.selectActiveOperation(documentId)
    }

    /** One document's operations, newest first, for the details and history views. */
    fun operations(documentId: DocumentId): List<OcrOperation> = database.read { connection ->
        connection.selectOperations(documentId)
    }

    /** The newest operation for each requested document, fetched with one query. */
    fun latestForDocuments(documentIds: Collection<DocumentId>): Map<DocumentId, OcrOperation> {
        val ids = documentIds.distinct()
        if (ids.isEmpty()) return emptyMap()
        val placeholders = ids.joinToString(",") { "?" }
        return database.read { connection ->
            val latest = LinkedHashMap<DocumentId, OcrOperation>()
            connection.prepareStatement(
                "$SELECT_OPERATION WHERE document_id IN ($placeholders) " +
                    "ORDER BY document_id, created_at DESC, rowid DESC",
            ).use { statement ->
                ids.forEachIndexed { index, id -> statement.setString(index + 1, id.value) }
                statement.executeQuery().use { rows ->
                    while (rows.next()) {
                        val operation = rows.toOperation(connection)
                        latest.putIfAbsent(operation.documentId, operation)
                    }
                }
            }
            latest
        }
    }

    /** Operations needing startup recovery, including terminal rows whose paired document write was interrupted. */
    fun operationsNeedingStartupCleanup(): List<OcrOperation> = database.read { connection ->
        val found = mutableListOf<OcrOperation>()
        connection.prepareStatement(
            "$SELECT_OPERATION WHERE ($HOLDS_DOCUMENT) OR " +
                "(stage = '${OcrOperationStage.FAILED.name}' AND error_code = 'INTERRUPTED') OR " +
                "stage = '${OcrOperationStage.COMPLETE.name}'",
        ).use { statement ->
            statement.executeQuery().use { rows -> while (rows.next()) found += rows.toOperation(connection) }
        }
        found
    }

    /**
     * Fails a held operation left behind after its attempt ended. The persisted stage and owner are checked
     * again in the update so a concurrent worker cannot be failed from a stale cleanup snapshot.
     */
    fun failInterruptedIfUnowned(
        operationId: String,
        expectedStage: OcrOperationStage,
        expectedJobId: String?,
    ): Boolean {
        require(expectedStage.holdsDocument) { "only a held operation can be marked interrupted" }
        return database.transaction { connection ->
            connection.prepareStatement(
                "UPDATE ocr_operations SET stage = ?, error_code = 'INTERRUPTED', " +
                    "error_message = 'the previous reading was interrupted; start it again', " +
                    "current_job_id = NULL, updated_at = ? WHERE operation_id = ? AND stage = ? " +
                    "AND current_job_id IS ?",
            ).use { statement ->
                statement.setString(1, OcrOperationStage.FAILED.name)
                statement.setString(2, Instants.now())
                statement.setString(3, operationId)
                statement.setString(4, expectedStage.name)
                statement.setString(5, expectedJobId)
                statement.executeUpdate() == 1
            }
        }
    }

    /** Clears a completed operation's pending count only while its candidate and count still match. */
    fun clearPendingReviewIfComplete(operationId: String, candidateRevisionId: String?, expectedCount: Int): Boolean =
        database.transaction { connection ->
            connection.prepareStatement(
                "UPDATE ocr_operations SET pending_review_count = 0, updated_at = ? WHERE operation_id = ? " +
                    "AND stage = ? AND candidate_revision_id IS ? AND pending_review_count = ?",
            ).use { statement ->
                statement.setString(1, Instants.now())
                statement.setString(2, operationId)
                statement.setString(3, OcrOperationStage.COMPLETE.name)
                statement.setString(4, candidateRevisionId)
                statement.setInt(5, expectedCount)
                statement.executeUpdate() == 1
            }
        }

    /**
     * Moves an operation to [stage], clearing a previous failure's code when the stage is a working one.
     *
     * A stage is set after the work behind it is committed, which is why this is the only way the stage
     * changes: an operation that says CHUNKING has already staged every page it read.
     */
    fun advance(operationId: String, stage: OcrOperationStage): OcrOperation = database.transaction { connection ->
        update(
            connection,
            "UPDATE ocr_operations SET stage = ?, error_code = NULL, error_message = NULL, updated_at = ? " +
                "WHERE operation_id = ?",
            stage.name,
            Instants.now(),
            operationId,
        )
        connection.readOperationById(operationId)
    }

    /** Records the candidate revision this operation stages into, once, when it opens it. */
    fun recordCandidateRevision(operationId: String, candidateRevisionId: String): OcrOperation =
        database.transaction { connection ->
            update(
                connection,
                "UPDATE ocr_operations SET candidate_revision_id = ?, updated_at = ? WHERE operation_id = ?",
                candidateRevisionId,
                Instants.now(),
                operationId,
            )
            connection.readOperationById(operationId)
        }

    /** Names the attempt that owns the operation now, or clears it when an attempt ended. */
    fun recordAttempt(operationId: String, jobId: String?): OcrOperation = database.transaction { connection ->
        update(
            connection,
            "UPDATE ocr_operations SET current_job_id = ?, updated_at = ? WHERE operation_id = ?",
            jobId,
            Instants.now(),
            operationId,
        )
        connection.readOperationById(operationId)
    }

    /**
     * Starts the one attempt an operation may have at a time, or refuses because it already has one.
     *
     * A resume means "continue this reading" and would otherwise queue its own attempt: two attempts of one
     * operation stage the same pages into the same candidate and count the same
     * external pages, so one of them would read over the other's committed work. The ownership handover and
     * the claim are therefore one write — the update names the attempt only while the operation names none —
     * and [enqueue] runs inside the same transaction, so the attempt it creates exists exactly when the
     * operation was claimed for it. The row is written first and the caller's own attempt is what the
     * operation answers with afterwards, so the loser of a race is refused rather than quietly started.
     *
     * Ownership is asked of the *jobs* table rather than of the pointer alone, because a pointer outlives the
     * attempt it names: a job cancelled while it was queued, or a job whose process died before its handler
     * cleared the pointer, leaves an operation naming work nobody is doing. Such a row does not own the
     * operation and must not refuse a resume forever — an attempt that is `QUEUED` or `RUNNING` does.
     *
     * [enqueue] is given no connection: an inner transaction composes with this one as a savepoint, so the
     * job it creates and the claim that names it are one unit of work.
     *
     * @throws OcrAttemptInProgressException when another live attempt already owns the operation.
     */
    fun startAttempt(operationId: String, stage: OcrOperationStage, enqueue: () -> String): OcrOperation =
        database.transaction { connection ->
            val current = connection.readOperationById(operationId)
            current.jobId?.let { owner ->
                if (connection.jobIsLive(owner)) throw OcrAttemptInProgressException(operationId, owner)
            }
            val jobId = enqueue()
            // The claim is checked by its update count, which the shared `update` helper does not return:
            // this statement is what decides whether the attempt was claimed at all, so the count is the point
            // rather than an afterthought. The pointer may only be replaced while it names nothing, or the
            // attempt that just ended: a live one was refused above.
            val claimed = connection.prepareStatement(
                "UPDATE ocr_operations SET current_job_id = ?, stage = ?, error_code = NULL, " +
                    "error_message = NULL, updated_at = ? WHERE operation_id = ? AND " +
                    "(current_job_id IS NULL OR current_job_id = ?)",
            ).use { statement ->
                statement.setString(1, jobId)
                statement.setString(2, stage.name)
                statement.setString(3, Instants.now())
                statement.setString(4, operationId)
                statement.setString(5, current.jobId)
                statement.executeUpdate()
            }
            check(claimed == 1) {
                "operation $operationId named no live attempt but was not claimable, which means its row " +
                    "changed inside one transaction"
            }
            connection.readOperationById(operationId)
        }

    /** Whether the job an operation names is still an attempt that exists: queued to run, or running now. */
    private fun Connection.jobIsLive(jobId: String): Boolean =
        prepareStatement("SELECT 1 FROM jobs WHERE id = ? AND state IN (?, ?)").use { statement ->
            statement.setString(1, jobId)
            statement.setString(2, JobState.QUEUED.name)
            statement.setString(3, JobState.RUNNING.name)
            statement.executeQuery().use { rows -> rows.next() }
        }

    /** Records a known page total, discovered once the container was opened. */
    fun recordPageTotal(operationId: String, pageTotal: Int?): OcrOperation = database.transaction { connection ->
        if (pageTotal == null) {
            update(
                connection,
                "UPDATE ocr_operations SET updated_at = ? WHERE operation_id = ?",
                Instants.now(),
                operationId,
            )
        } else {
            update(
                connection,
                "UPDATE ocr_operations SET page_total = ?, updated_at = ? WHERE operation_id = ?",
                pageTotal,
                Instants.now(),
                operationId,
            )
        }
        connection.readOperationById(operationId)
    }

    /**
     * Records how far the attempt has come: the pages it has committed, the pages it could not read, and the
     * pages waiting for a decision.
     *
     * The counters are *set* rather than incremented, and that is what makes a restart honest. An attempt that
     * is killed and resumed sees the pages an earlier attempt committed as its own starting point, so an
     * incremented counter would double-count them; a set counter is derived from what the candidate revision
     * actually holds. Absent arguments leave what the row has, so a caller that only learned one of them does
     * not have to restate the rest.
     */
    fun recordProgress(
        operationId: String,
        pageTotal: Int? = null,
        committed: Int? = null,
        failed: Int? = null,
        pendingReview: Int? = null,
    ): OcrOperation = database.transaction { connection ->
        require(committed == null || committed >= 0) { "a committed page count is not negative" }
        require(failed == null || failed >= 0) { "a failed page count is not negative" }
        require(pendingReview == null || pendingReview >= 0) { "a pending review count is not negative" }
        val current = connection.readOperationById(operationId)
        update(
            connection,
            "UPDATE ocr_operations SET page_total = ?, pages_committed = ?, pages_failed = ?, " +
                "pending_review_count = ?, updated_at = ? WHERE operation_id = ?",
            pageTotal ?: current.pageTotal,
            committed ?: current.pagesCommitted,
            failed ?: current.pagesFailed,
            pendingReview ?: current.pendingReviewCount,
            Instants.now(),
            operationId,
        )
        connection.readOperationById(operationId)
    }

    /** Ends an operation with a safe code and a curated remedy, or with [OcrOperationStage.COMPLETE]. */
    fun finish(
        operationId: String,
        stage: OcrOperationStage,
        errorCode: String? = null,
        errorMessage: String? = null,
    ): OcrOperation = database.transaction { connection ->
        require(stage.isTerminal) { "an operation ends in a terminal stage, was $stage" }
        update(
            connection,
            "UPDATE ocr_operations SET stage = ?, error_code = ?, error_message = ?, current_job_id = NULL, " +
                "updated_at = ? WHERE operation_id = ?",
            stage.name,
            errorCode,
            errorMessage,
            Instants.now(),
            operationId,
        )
        connection.readOperationById(operationId)
    }

    /** Retained for legacy-row compatibility; new OCR attempts do not enter a paused approval state. */
    fun pause(operationId: String, stage: OcrOperationStage = OcrOperationStage.AWAITING_APPROVAL): OcrOperation =
        database.transaction { connection ->
            require(stage == OcrOperationStage.AWAITING_APPROVAL) {
                "a paused operation waits for approval, was $stage"
            }
            update(
                connection,
                "UPDATE ocr_operations SET stage = ?, current_job_id = NULL, updated_at = ? WHERE operation_id = ?",
                stage.name,
                Instants.now(),
                operationId,
            )
            connection.readOperationById(operationId)
        }

    // ---- external-page admission ----

    /**
     * Asks to send one page, counting it, and answers whether it may go.
     *
     * The decision and the count are one transaction, which is what makes the allowance a bound rather than a
     * hope: two dispatches of the last allowed page cannot both read "one left" and both proceed. A page that
     * already counted is admitted again without another count — the same page leaving twice is one page —
     * which is also why resuming never resets the counter.
     *
     * The allowance comes from the persisted snapshot, so every resumed attempt keeps the same bound.
     */
    fun authorizePage(
        owner: OcrExternalOwner,
        documentId: DocumentId,
        unitId: String,
        ordinal: Int,
        allowance: Int,
    ): Boolean {
        require(unitId.isNotBlank()) { "a dispatched page names itself" }
        require(ordinal >= 0) { "a dispatched page has an ordinal, was $ordinal" }
        require(allowance >= 0) { "an external page allowance is not negative, was $allowance" }
        return database.transaction { connection ->
            if (connection.pageAlreadySent(owner, documentId, unitId)) return@transaction true
            if (connection.countSentPages(owner) >= allowance) return@transaction false
            connection.prepareStatement(
                "INSERT OR IGNORE INTO ocr_external_pages (owner_kind, owner_id, document_id, unit_id, " +
                    "ordinal, first_sent_at) VALUES (?, ?, ?, ?, ?, ?)",
            ).use { statement ->
                statement.setString(1, owner.kind.name)
                statement.setString(2, owner.id)
                statement.setString(3, documentId.value)
                statement.setString(4, unitId)
                statement.setInt(5, ordinal)
                statement.setString(6, Instants.now())
                statement.executeUpdate()
            }
            true
        }
    }

    /**
     * Counts one provider call, retries included.
     *
     * Separate from [authorizePage] because the two are different quantities: a call is what was paid for and
     * a page is what left the machine. The page count and provider-call count remain separate measures.
     */
    fun recordCall(
        owner: OcrExternalOwner,
        documentId: DocumentId,
        unitId: String,
        ordinal: Int,
        stage: OcrDispatchStage,
    ) {
        database.transaction { connection ->
            connection.prepareStatement(
                "INSERT INTO ocr_external_calls (owner_kind, owner_id, document_id, unit_id, ordinal, stage, " +
                    "calls, updated_at) VALUES (?, ?, ?, ?, ?, ?, 1, ?) " +
                    "ON CONFLICT (owner_kind, owner_id, document_id, unit_id, stage) DO UPDATE SET " +
                    "calls = calls + 1, updated_at = excluded.updated_at",
            ).use { statement ->
                statement.setString(1, owner.kind.name)
                statement.setString(2, owner.id)
                statement.setString(3, documentId.value)
                statement.setString(4, unitId)
                statement.setInt(5, ordinal)
                statement.setString(6, stage.name)
                statement.setString(7, Instants.now())
                statement.executeUpdate()
            }
        }
    }


    /**
     * The live attempt that owns one operation, or null when nobody is working on it.
     *
     * This is the question a caller asks before queueing a resume: a second live attempt would stage duplicate
     * work into the same candidate and count the same external pages.
     */
    fun liveAttempt(operationId: String): String? = database.read { connection ->
        connection.readOperationById(operationId).jobId?.takeIf { owner -> connection.jobIsLive(owner) }
    }

    /** One owner's call count, retries included, without resolving an allowance. */
    fun callCount(owner: OcrExternalOwner): Int = database.read { connection -> connection.countCalls(owner) }

    /** One owner's distinct-page count, without resolving an allowance. */
    fun distinctPageCount(owner: OcrExternalOwner): Int =
        database.read { connection -> connection.countSentPages(owner) }

    // ---- mapping ----

    private fun Connection.readOperationById(id: String): OcrOperation =
        selectOperation(this, "$SELECT_OPERATION WHERE operation_id = ?") { statement ->
            statement.setString(1, id)
        } ?: throw NoSuchElementException("no rescan operation with id $id exists")

    private fun Connection.selectRequestHash(
        collectionId: String,
        documentId: DocumentId,
        requestId: String,
    ): String? = prepareStatement(
        "SELECT request_hash FROM ocr_operations WHERE collection_id = ? AND document_id = ? AND request_id = ?",
    ).use { statement ->
        statement.setString(1, collectionId)
        statement.setString(2, documentId.value)
        statement.setString(3, requestId)
        statement.executeQuery().use { rows -> if (rows.next()) rows.getString("request_hash") else null }
    }

    private fun Connection.selectOperationByRequest(
        collectionId: String,
        documentId: DocumentId,
        requestId: String,
    ): OcrOperation? = selectOperation(
        this,
        "$SELECT_OPERATION WHERE collection_id = ? AND document_id = ? AND request_id = ?",
    ) { statement ->
        statement.setString(1, collectionId)
        statement.setString(2, documentId.value)
        statement.setString(3, requestId)
    }

    private fun Connection.selectOperations(documentId: DocumentId): List<OcrOperation> {
        val found = mutableListOf<OcrOperation>()
        prepareStatement("$SELECT_OPERATION WHERE document_id = ? ORDER BY created_at DESC, rowid DESC")
            .use { statement ->
                statement.setString(1, documentId.value)
                statement.executeQuery().use { rows -> while (rows.next()) found += rows.toOperation(this) }
            }
        return found
    }

    /** The row that holds this document, as [activeOperation]'s predicate states it in SQL. */
    private fun Connection.selectActiveOperation(documentId: DocumentId): OcrOperation? =
        selectOperation(this, "$SELECT_OPERATION WHERE document_id = ? AND ($HOLDS_DOCUMENT) " +
            "ORDER BY created_at DESC, rowid DESC LIMIT 1") { statement ->
            statement.setString(1, documentId.value)
        }

    private fun selectOperation(
        connection: Connection,
        sql: String,
        bind: (PreparedStatement) -> Unit,
    ): OcrOperation? = connection.prepareStatement(sql).use { statement ->
        bind(statement)
        statement.executeQuery().use { rows -> if (rows.next()) rows.toOperation(connection) else null }
    }

    private fun ResultSet.toOperation(connection: Connection): OcrOperation {
        val operationId = getString("operation_id")
        val snapshot = SNAPSHOT_JSON.decodeFromString(OcrSettingsSnapshot.serializer(), getString("snapshot"))
        val owner = OcrExternalOwner(OcrExternalOwnerKind.OPERATION, operationId)
        return OcrOperation(
            operationId = operationId,
            collectionId = getString("collection_id"),
            documentId = DocumentId(getString("document_id")),
            jobId = getString("current_job_id"),
            baseRevisionId = getString("base_revision_id"),
            candidateRevisionId = getString("candidate_revision_id"),
            snapshot = snapshot,
            stage = OcrOperationStage.valueOf(getString("stage")),
            pageTotal = getInt("page_total").takeIf { !wasNull() },
            pagesCommitted = getInt("pages_committed"),
            pagesFailed = getInt("pages_failed"),
            pendingReviewCount = getInt("pending_review_count"),
            external = OcrExternalAccount(
                distinctPages = connection.countSentPages(owner),
                calls = connection.countCalls(owner),
                allowance = snapshot.externalPageLimit,
            ),
            errorCode = getString("error_code"),
            errorMessage = getString("error_message"),
            requestId = getString("request_id"),
            createdAt = getString("created_at"),
            updatedAt = getString("updated_at"),
        )
    }

    private fun ResultSet.toStoredPreview(previewId: String): StoredPreview = StoredPreview(
        previewId = previewId,
        collectionId = getString("collection_id"),
        documentId = DocumentId(getString("document_id")),
        baseRevisionId = getString("base_revision_id"),
        managedSha256 = getString("managed_sha256"),
        snapshot = SNAPSHOT_JSON.decodeFromString(OcrSettingsSnapshot.serializer(), getString("snapshot")),
        snapshotHash = getString("snapshot_hash"),
        pageTotal = getInt("page_total").takeIf { !wasNull() },
        externalPageUpperBound = getInt("external_page_upper_bound").takeIf { !wasNull() },
        expiresAt = getString("expires_at"),
    )

    private fun Connection.pageAlreadySent(owner: OcrExternalOwner, documentId: DocumentId, unitId: String): Boolean =
        prepareStatement(
            "SELECT 1 FROM ocr_external_pages WHERE owner_kind = ? AND owner_id = ? AND document_id = ? " +
                "AND unit_id = ?",
        ).use { statement ->
            statement.setString(1, owner.kind.name)
            statement.setString(2, owner.id)
            statement.setString(3, documentId.value)
            statement.setString(4, unitId)
            statement.executeQuery().use { rows -> rows.next() }
        }

    private fun Connection.countSentPages(owner: OcrExternalOwner): Int = count(
        "SELECT COUNT(*) FROM ocr_external_pages WHERE owner_kind = ? AND owner_id = ?",
        owner,
    )

    private fun Connection.countCalls(owner: OcrExternalOwner): Int = count(
        "SELECT COALESCE(SUM(calls), 0) FROM ocr_external_calls WHERE owner_kind = ? AND owner_id = ?",
        owner,
    )

    private fun Connection.count(sql: String, owner: OcrExternalOwner): Int =
        prepareStatement(sql).use { statement ->
            statement.setString(1, owner.kind.name)
            statement.setString(2, owner.id)
            statement.executeQuery().use { rows ->
                rows.next()
                rows.getInt(1)
            }
        }

    private fun update(connection: Connection, sql: String, vararg parameters: Any?) {
        connection.prepareStatement(sql).use { statement ->
            parameters.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.executeUpdate()
        }
    }

    companion object {

        private const val RESCAN_CANCELLED_CODE = "RESCAN_CANCELLED"

        /** The canonical hash stored with a preview, used to reject changed rescan settings. */
        fun snapshotHashOf(snapshot: OcrSettingsSnapshot): String =
            operationHash(SNAPSHOT_JSON.encodeToString(OcrSettingsSnapshot.serializer(), snapshot))

        private const val SELECT_OPERATION =
            "SELECT operation_id, collection_id, document_id, current_job_id, base_revision_id, " +
                "candidate_revision_id, snapshot, snapshot_hash, stage, page_total, pages_committed, " +
                "pages_failed, pending_review_count, error_code, error_message, request_id, created_at, " +
                "updated_at FROM ocr_operations"

        private val SNAPSHOT_JSON = Json { ignoreUnknownKeys = true }

        /**
         * The SQL form of "this operation holds its document", matching the schema's partial unique index.
         *
         * A stage that is neither terminal nor COMPLETE is still working or waiting, and a COMPLETE
         * operation with pages nobody has decided about has not finished with the document's reading. The
         * two forms are written side by side on purpose: the index is the invariant, this is the question
         * every caller asks, and a drift between them would be an admission that the database then refuses.
         */
        private const val HOLDS_DOCUMENT =
            "stage IN ('PREFLIGHT', 'AWAITING_APPROVAL', 'OCR', 'REVIEW', 'CHUNKING', 'EMBEDDING', " +
                "'INDEXING') OR (stage = 'COMPLETE' AND pending_review_count > 0)"
    }
}
