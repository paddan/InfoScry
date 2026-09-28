package infoscry.storage

import infoscry.domain.CollectionId
import infoscry.domain.CollectionLifecycle
import infoscry.domain.DocumentId
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet

/**
 * The phases of a deletion, in the order they run.
 *
 * Each phase names work that has already been done when the phase is durable, so recovery can resume
 * from the recorded phase instead of guessing. The phases are the same for both kinds of deletion
 * because the work is the same shape — park files, remove rows, remove index entries, purge — and only
 * what is parked differs:
 *
 * - `PREPARED` — the target is tombstoned, its work is asked to stop, and this record exists;
 * - `FILES_MOVED` — its managed directory (a collection's, or each targeted document's) is parked;
 * - `DB_DELETED` — its rows (a collection and its documents, or the targeted documents) are gone;
 * - `INDEX_DELETED` — its search entries are gone;
 * - `DONE` — the parked files are purged and nothing of the target remains.
 */
enum class DeletionPhase {
    PREPARED,
    FILES_MOVED,
    DB_DELETED,
    INDEX_DELETED,
    DONE,
}

/**
 * What a deletion removes: a whole collection, or chosen documents from one.
 *
 * The kind is what the operation's target fields mean, and it is persisted so recovery knows which
 * deletion machine — and which parking layout — an operation it did not admit belongs to.
 */
enum class DeletionKind {
    COLLECTION,
    DOCUMENT,
}

/**
 * One document a document deletion removes, and whether it had a managed directory when the deletion
 * was admitted.
 *
 * `managedExisted` is read once, at admission, because it is the fact recovery needs later: a document
 * whose directory was never created is finished by deleting its rows, while one whose directory has
 * vanished is a state that must not be resolved by discarding files.
 *
 * [sourcePath] is the path the target was imported from, read from its document row while that row still
 * exists. It is what lets a running operation find the documents a raced import created from the same
 * path after admission: see [documentsOnTargetPaths].
 * It is null for targets recorded before this column existed, and for a collection deletion, which has
 * no document targets at all.
 */
data class DocumentDeletionTarget(
    val documentId: DocumentId,
    val managedExisted: Boolean,
    val sourcePath: String? = null,
)

/**
 * One document a running deletion's recorded target paths still lead to.
 *
 * It is a candidate rather than a decision: whether the document has a managed directory, and whether it
 * has to be removed at all, is what the deletion service settles from it.
 */
data class DerivedDocumentTarget(val documentId: DocumentId, val sourcePath: String)

/**
 * The durable record of one deletion.
 *
 * The row outlives the collection it describes — it is deliberately not a foreign key — because it is
 * what recovery reads to find files that were parked and rows that were half-removed. Only the trash
 * *basename* is stored: the parent directory is derivable from the data layout, and storing an absolute
 * path would break as soon as the data directory moved.
 *
 * [id] is a plain string rather than a typed domain identifier: this record is an internal recovery
 * artifact, not an entity the API or the domain exposes.
 *
 * [lastError] is the message for whoever reads the log, and it may name absolute paths inside the data
 * directory; [errorCode] is the small stable vocabulary that may cross the wire instead.
 */
data class DeletionOperation(
    val id: String,
    val collectionId: CollectionId,
    val collectionName: String,
    val trashBasename: String,
    val managedOriginalsExisted: Boolean,
    val phase: DeletionPhase,
    val kind: DeletionKind = DeletionKind.COLLECTION,
    /**
     * The documents a [DeletionKind.DOCUMENT] operation removes, in a stable order; empty for a
     * collection deletion, whose target is the collection itself.
     */
    val targets: List<DocumentDeletionTarget> = emptyList(),
    val createdAt: String,
    val updatedAt: String,
    val lastError: String? = null,
    val errorCode: String? = null,
) {
    init {
        require(id.isNotBlank()) { "DeletionOperation.id must not be blank" }
        require(collectionName.isNotBlank()) { "DeletionOperation.collectionName must not be blank" }
        require(trashBasename.isNotBlank()) { "DeletionOperation.trashBasename must not be blank" }
    }
}

/**
 * Persistence for collection deletions, including the tombstone transaction.
 *
 * [begin] keeps three tables consistent in one transaction — the collection's lifecycle, its jobs'
 * cancellation requests, and the deletion record — because a crash between them would leave either a
 * collection that is being deleted without a record of how to finish, or a record without a tombstone
 * to keep new work out. `Database.transaction` nests with savepoints, so the individual stores' own
 * transactions take part in this one.
 */
class DeletionStore(private val database: Database) {

    /** Tombstones the collection, requests cancellation of its jobs, and records [operation]. */
    fun begin(operation: DeletionOperation): DeletionOperation = database.transaction { connection ->
        insert(connection, operation)
        tombstone(connection, operation.collectionId)
        operation
    }

    /**
     * Records a document deletion and everything that must be durable before any file or row is touched.
     *
     * Three writes in one transaction, because a crash between them would leave either guardless targets
     * or unguarded work: the operation itself, one row per target (which is the guard every write path
     * reads), and the per-file disposition that tells a running or resumed import that this file's
     * document is gone. The documents' own rows are *not* touched here — the phases do that — so a
     * failure after admission still leaves a complete, resumable operation.
     */
    fun beginDocument(operation: DeletionOperation): DeletionOperation = database.transaction { connection ->
        insert(connection, operation)
        recordTargets(connection, operation)
        cancelTargetingItems(connection, operation)
        operation
    }

    fun get(id: String): DeletionOperation? = database.read { connection ->
        select(connection, "$SELECT_OPERATIONS WHERE id = ?") { it.setString(1, id) }
    }

    /** Operations that still need work, oldest first, so recovery finishes what was started. */
    fun listUnfinished(kind: DeletionKind? = null): List<DeletionOperation> = database.read { connection ->
        val sql = if (kind == null) {
            "$SELECT_OPERATIONS WHERE phase <> ? ORDER BY created_at, id"
        } else {
            "$SELECT_OPERATIONS WHERE phase <> ? AND kind = ? ORDER BY created_at, id"
        }
        connection.prepareStatement(sql).use { statement ->
            statement.setString(1, DeletionPhase.DONE.name)
            if (kind != null) statement.setString(2, kind.name)
            statement.executeQuery().use { rows -> rows.readAll(connection) }
        }
    }

    fun unfinishedFor(
        collectionId: CollectionId,
        kind: DeletionKind = DeletionKind.COLLECTION,
    ): List<DeletionOperation> = database.read { connection ->
        connection.prepareStatement(
            "$SELECT_OPERATIONS WHERE collection_id = ? AND phase <> ? AND kind = ? ORDER BY created_at, id",
        ).use { statement ->
            statement.setString(1, collectionId.value)
            statement.setString(2, DeletionPhase.DONE.name)
            statement.setString(3, kind.name)
            statement.executeQuery().use { rows -> rows.readAll(connection) }
        }
    }

    /**
     * Whether anything is deleting [documentId].
     *
     * The answer stays true after the deletion finishes, and that is the point: the target row outlives
     * the document it names, so a resumed import that still holds an item for the deleted document reads
     * "do not write this" rather than discovering an empty table. Document identifiers are never reused,
     * so the row cannot ever name a different document.
     */
    fun isDeletionTarget(documentId: DocumentId): Boolean = database.read { connection ->
        connection.prepareStatement("SELECT 1 FROM document_deletion_targets WHERE document_id = ?").use { statement ->
            statement.setString(1, documentId.value)
            statement.executeQuery().use { rows -> rows.next() }
        }
    }

    /** Moves an operation to [phase]. A successful step clears the error a previous attempt recorded. */
    fun advance(id: String, phase: DeletionPhase): DeletionOperation {
        val updatedAt = Instants.now()
        val changed = database.transaction { connection ->
            connection.prepareStatement(
                "UPDATE deletion_operations SET phase = ?, last_error = NULL, error_code = NULL, " +
                    "updated_at = ? WHERE id = ?",
            ).use { statement ->
                statement.setString(1, phase.name)
                statement.setString(2, updatedAt)
                statement.setString(3, id)
                statement.executeUpdate()
            }
        }
        if (changed == 0) throw NoSuchElementException("no deletion operation with id $id")
        return get(id) ?: throw NoSuchElementException("no deletion operation with id $id")
    }

    /**
     * Records why a phase could not be completed, leaving the phase itself untouched: the phase says
     * what is true on disk, and the error says what stopped the next step. Recovery retries the same
     * phase, so an operation is never advanced past work that did not happen.
     */
    fun recordError(id: String, errorCode: String, message: String) {
        val updatedAt = Instants.now()
        database.transaction { connection ->
            connection.prepareStatement(
                "UPDATE deletion_operations SET last_error = ?, error_code = ?, updated_at = ? WHERE id = ?",
            ).use { statement ->
                statement.setString(1, message)
                statement.setString(2, errorCode)
                statement.setString(3, updatedAt)
                statement.setString(4, id)
                statement.executeUpdate()
            }
        }
    }

    private fun insert(connection: Connection, operation: DeletionOperation) {
        connection.prepareStatement(
            "INSERT INTO deletion_operations (id, collection_id, collection_name, trash_basename, " +
                "managed_originals_existed, phase, kind, last_error, error_code, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        ).use { statement ->
            statement.setString(1, operation.id)
            statement.setString(2, operation.collectionId.value)
            statement.setString(3, operation.collectionName)
            statement.setString(4, operation.trashBasename)
            statement.setInt(5, if (operation.managedOriginalsExisted) 1 else 0)
            statement.setString(6, operation.phase.name)
            statement.setString(7, operation.kind.name)
            statement.setString(8, operation.lastError)
            statement.setString(9, operation.errorCode)
            statement.setString(10, operation.createdAt)
            statement.setString(11, operation.updatedAt)
            statement.executeUpdate()
        }
    }

    private fun recordTargets(connection: Connection, operation: DeletionOperation) {
        connection.prepareStatement(
            "INSERT INTO document_deletion_targets (operation_id, document_id, managed_existed, source_path) " +
                "VALUES (?, ?, ?, ?)",
        ).use { statement ->
            operation.targets.forEach { target ->
                statement.setString(1, operation.id)
                statement.setString(2, target.documentId.value)
                statement.setInt(3, if (target.managedExisted) 1 else 0)
                statement.setString(4, target.sourcePath)
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    /**
     * Records documents a running operation has to remove as well, because a target's path led to them.
     *
     * Idempotent, so a sweep that runs again after a resume records nothing new. The row does two things:
     * it makes the document a durable deletion target — every later write for it is refused as "being
     * deleted", which is what stops an import that is still holding it from publishing it — and it puts it
     * in the operation's target list, so a resumed attempt's phases, existence checks and purge account for
     * it. The path is recorded with it for the same reason a caller's target records its own: a later sweep
     * has to recognise documents this one already claimed.
     */
    fun recordDerivedTargets(operationId: String, targets: List<DocumentDeletionTarget>) {
        if (targets.isEmpty()) return
        database.transaction { connection ->
            connection.prepareStatement(
                "INSERT INTO document_deletion_targets (operation_id, document_id, managed_existed, " +
                    "source_path) VALUES (?, ?, ?, ?) ON CONFLICT (operation_id, document_id) DO NOTHING",
            ).use { statement ->
                targets.forEach { target ->
                    statement.setString(1, operationId)
                    statement.setString(2, target.documentId.value)
                    statement.setInt(3, if (target.managedExisted) 1 else 0)
                    statement.setString(4, target.sourcePath)
                    statement.addBatch()
                }
                statement.executeBatch()
            }
        }
    }

    /**
     * The documents of this operation's collection that its target paths still lead to.
     *
     * Two situations qualify, and they are the two ways a document can appear at a path a deletion owns
     * while the deletion is unfinished:
     *
     * - it was **created after the operation was admitted**: an import whose copy started before admission
     *   can publish its row a moment after it, and reading that document would publish what the deletion
     *   decided against. Timestamps are fixed-width ISO-8601, so the comparison is the order they occurred
     *   in;
     * - it is **the document an import item of one of those paths created** and this operation cancelled
     *   that item. This is the case admission cannot see: the item was read as pending, and its copy
     *   finished just before or during admission, so the document's row is older than the operation while
     *   the work that produced it is exactly what the deletion cancelled. `item.created_document` is what
     *   separates that document from one the file merely *found* — a duplicate imported earlier by
     *   something else, which the deleted path does not own.
     *
     * Only the operation's own collection is read, and only documents are returned: nothing here removes
     * anything, and a path is never refused again once the operation is done.
     */
    fun documentsOnTargetPaths(
        operation: DeletionOperation,
        paths: List<String>,
    ): List<DerivedDocumentTarget> {
        if (paths.isEmpty()) return emptyList()
        val placeholders = paths.joinToString(",") { "?" }
        return database.read { connection ->
            connection.prepareStatement(
                "SELECT d.id, d.original_path FROM documents d " +
                    "WHERE d.collection_id = ? AND d.original_path IN ($placeholders) " +
                    "AND (d.created_at > ? OR EXISTS (" +
                    "SELECT 1 FROM import_items item JOIN jobs job ON job.id = item.job_id " +
                    "WHERE item.document_id = d.id AND item.outcome = ? AND item.created_document = 1 " +
                    "AND item.source_path = d.original_path AND job.collection_id = d.collection_id)) " +
                    "ORDER BY d.created_at, d.id",
            ).use { statement ->
                statement.setString(1, operation.collectionId.value)
                paths.forEachIndexed { index, path -> statement.setString(index + 2, path) }
                statement.setString(paths.size + 2, operation.createdAt)
                statement.setString(paths.size + 3, ImportItemOutcome.CANCELLED.name)
                statement.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) {
                            add(DerivedDocumentTarget(DocumentId(rows.getString(1)), rows.getString(2)))
                        }
                    }
                }
            }
        }
    }

    /**
     * Marks the import work that would put one of the deleted documents back as `CANCELLED`.
     *
     * This is the durable disposition the import handler obeys, and there are two kinds of row it has to
     * cover, because a file can reach a deleted document by two routes:
     *
     * - an item that already holds one of the deleted documents — the item keeps its document reference
     *   until the foreign key clears it, and its outcome says the file is not to be copied again;
     * - a `PENDING` item of a job of this collection that is still queued or running, whose *source path*
     *   is one of the deleted documents' original paths — whether or not it already names a document. A
     *   queued attempt that has not copied the file yet would otherwise copy it again and create a
     *   genuinely new document under a new identifier; and one that copied it a moment ago (the copy and
     *   this admission race) would read and publish that new document, which is the same resurrection by
     *   another route. The files whose bytes were already stored are the first case; this is the file that
     *   has not finished.
     *
     * Other items of the same job are untouched, so the rest of a multi-file import keeps running.
     *
     * What this deliberately does not cover: a *different* path that holds the same bytes. Those bytes
     * are a new import of a source the user still has, not the deleted target — deduplication is per
     * `(collection, sha256)`, and the honest answer is that it becomes a new document.
     */
    private fun cancelTargetingItems(connection: Connection, operation: DeletionOperation) {
        val documentIds = operation.targets.map { it.documentId }
        if (documentIds.isEmpty()) return
        val updatedAt = Instants.now()
        val placeholders = documentIds.joinToString(",") { "?" }
        connection.prepareStatement(
            "UPDATE import_items SET outcome = 'CANCELLED', updated_at = ? WHERE document_id IN ($placeholders)",
        ).use { statement ->
            statement.setString(1, updatedAt)
            documentIds.forEachIndexed { index, documentId -> statement.setString(index + 2, documentId.value) }
            statement.executeUpdate()
        }
        connection.prepareStatement(
            "UPDATE import_items SET outcome = 'CANCELLED', updated_at = ? " +
                "WHERE outcome = 'PENDING' " +
                "AND job_id IN (SELECT id FROM jobs WHERE collection_id = ? " +
                "AND state IN ('QUEUED', 'RUNNING')) " +
                "AND source_path IN (SELECT original_path FROM documents WHERE id IN ($placeholders))",
        ).use { statement ->
            statement.setString(1, updatedAt)
            statement.setString(2, operation.collectionId.value)
            documentIds.forEachIndexed { index, documentId -> statement.setString(index + 3, documentId.value) }
            statement.executeUpdate()
        }
    }

    private fun tombstone(connection: Connection, collectionId: CollectionId) {
        val updatedAt = Instants.now()
        connection.prepareStatement(
            "UPDATE collections SET lifecycle = ?, updated_at = ? WHERE id = ?",
        ).use { statement ->
            statement.setString(1, CollectionLifecycle.DELETING.name)
            statement.setString(2, updatedAt)
            statement.setString(3, collectionId.value)
            statement.executeUpdate()
        }
        // The durable cancellation request, which the job runner observes. Marking a job CANCELLED
        // here would lie about a stage a worker may still be inside; the request is the honest form.
        connection.prepareStatement(
            "UPDATE jobs SET cancel_requested = 1, updated_at = ? " +
                "WHERE collection_id = ? AND state IN ('QUEUED', 'RUNNING')",
        ).use { statement ->
            statement.setString(1, updatedAt)
            statement.setString(2, collectionId.value)
            statement.executeUpdate()
        }
        // The files that never reached a document are cancelled work too: a queued import that has not
        // copied this file yet would otherwise copy from a collection that is being removed, and the
        // queue view would keep reporting the file as still pending rather than as cancelled. The rows
        // themselves disappear with the collection's jobs; what this records is the honest disposition
        // for as long as they are readable.
        connection.prepareStatement(
            "UPDATE import_items SET outcome = 'CANCELLED', updated_at = ? " +
                "WHERE outcome = 'PENDING' AND document_id IS NULL " +
                "AND job_id IN (SELECT id FROM jobs WHERE collection_id = ? " +
                "AND state IN ('QUEUED', 'RUNNING'))",
        ).use { statement ->
            statement.setString(1, updatedAt)
            statement.setString(2, collectionId.value)
            statement.executeUpdate()
        }
    }

    private fun select(
        connection: Connection,
        sql: String,
        bind: (PreparedStatement) -> Unit,
    ): DeletionOperation? = connection.prepareStatement(sql).use { statement ->
        bind(statement)
        statement.executeQuery().use { rows -> if (rows.next()) rows.toOperation(connection) else null }
    }

    private fun ResultSet.readAll(connection: Connection): List<DeletionOperation> =
        buildList { while (next()) add(toOperation(connection)) }

    private fun ResultSet.toOperation(connection: Connection): DeletionOperation = DeletionOperation(
        id = getString("id"),
        collectionId = CollectionId(getString("collection_id")),
        collectionName = getString("collection_name"),
        trashBasename = getString("trash_basename"),
        managedOriginalsExisted = getInt("managed_originals_existed") == 1,
        phase = DeletionPhase.valueOf(getString("phase")),
        kind = DeletionKind.valueOf(getString("kind")),
        targets = targets(connection, getString("id")),
        createdAt = getString("created_at"),
        updatedAt = getString("updated_at"),
        lastError = getString("last_error"),
        errorCode = getString("error_code"),
    )

    private fun targets(connection: Connection, operationId: String): List<DocumentDeletionTarget> =
        connection.prepareStatement(
            "SELECT document_id, managed_existed, source_path FROM document_deletion_targets " +
                "WHERE operation_id = ? ORDER BY document_id",
        ).use { statement ->
            statement.setString(1, operationId)
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        add(
                            DocumentDeletionTarget(
                                documentId = DocumentId(rows.getString("document_id")),
                                managedExisted = rows.getInt("managed_existed") == 1,
                                sourcePath = rows.getString("source_path"),
                            ),
                        )
                    }
                }
            }
        }

    private companion object {
        const val SELECT_OPERATIONS =
            "SELECT id, collection_id, collection_name, trash_basename, managed_originals_existed, " +
                "phase, kind, last_error, error_code, created_at, updated_at FROM deletion_operations"
    }
}
