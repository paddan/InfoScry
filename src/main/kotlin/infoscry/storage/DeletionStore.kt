package infoscry.storage

import infoscry.domain.CollectionId
import infoscry.domain.CollectionLifecycle
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet

/**
 * The phases of a collection deletion, in the order they run.
 *
 * Each phase names work that has already been done when the phase is durable, so recovery can resume
 * from the recorded phase instead of guessing:
 *
 * - `PREPARED` — the collection is tombstoned, its jobs are asked to stop, and this record exists;
 * - `FILES_MOVED` — its managed directory has been renamed into the trash;
 * - `DB_DELETED` — its rows (and its documents' rows and jobs' rows) are gone;
 * - `INDEX_DELETED` — its search entries are gone;
 * - `DONE` — the trash has been purged and nothing of the collection remains.
 */
enum class DeletionPhase {
    PREPARED,
    FILES_MOVED,
    DB_DELETED,
    INDEX_DELETED,
    DONE,
}

/**
 * The durable record of one collection deletion.
 *
 * The row outlives the collection it describes — it is deliberately not a foreign key — because it is
 * what recovery reads to find files that were parked and rows that were half-removed. Only the trash
 * *basename* is stored: the parent directory is derivable from the data layout, and storing an absolute
 * path would break as soon as the data directory moved.
 *
 * [id] is a plain string rather than a typed domain identifier: this record is an internal recovery
 * artifact, not an entity the API or the domain exposes.
 */
data class DeletionOperation(
    val id: String,
    val collectionId: CollectionId,
    val collectionName: String,
    val trashBasename: String,
    val managedOriginalsExisted: Boolean,
    val phase: DeletionPhase,
    val createdAt: String,
    val updatedAt: String,
    val lastError: String? = null,
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

    fun get(id: String): DeletionOperation? = database.read { connection ->
        select(connection, "$SELECT_OPERATIONS WHERE id = ?") { it.setString(1, id) }
    }

    /** Operations that still need work, oldest first, so recovery finishes what was started. */
    fun listUnfinished(): List<DeletionOperation> = database.read { connection ->
        connection.prepareStatement("$SELECT_OPERATIONS WHERE phase <> ? ORDER BY created_at, id").use { statement ->
            statement.setString(1, DeletionPhase.DONE.name)
            statement.executeQuery().use { rows -> rows.readAll() }
        }
    }

    fun unfinishedFor(collectionId: CollectionId): List<DeletionOperation> = database.read { connection ->
        connection.prepareStatement(
            "$SELECT_OPERATIONS WHERE collection_id = ? AND phase <> ? ORDER BY created_at, id",
        ).use { statement ->
            statement.setString(1, collectionId.value)
            statement.setString(2, DeletionPhase.DONE.name)
            statement.executeQuery().use { rows -> rows.readAll() }
        }
    }

    /** Moves an operation to [phase]. A successful step clears the error a previous attempt recorded. */
    fun advance(id: String, phase: DeletionPhase): DeletionOperation {
        val updatedAt = Instants.now()
        val changed = database.transaction { connection ->
            connection.prepareStatement(
                "UPDATE deletion_operations SET phase = ?, last_error = NULL, updated_at = ? WHERE id = ?",
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
    fun recordError(id: String, message: String) {
        val updatedAt = Instants.now()
        database.transaction { connection ->
            connection.prepareStatement(
                "UPDATE deletion_operations SET last_error = ?, updated_at = ? WHERE id = ?",
            ).use { statement ->
                statement.setString(1, message)
                statement.setString(2, updatedAt)
                statement.setString(3, id)
                statement.executeUpdate()
            }
        }
    }

    private fun insert(connection: Connection, operation: DeletionOperation) {
        connection.prepareStatement(
            "INSERT INTO deletion_operations (id, collection_id, collection_name, trash_basename, " +
                "managed_originals_existed, phase, last_error, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
        ).use { statement ->
            statement.setString(1, operation.id)
            statement.setString(2, operation.collectionId.value)
            statement.setString(3, operation.collectionName)
            statement.setString(4, operation.trashBasename)
            statement.setInt(5, if (operation.managedOriginalsExisted) 1 else 0)
            statement.setString(6, operation.phase.name)
            statement.setString(7, operation.lastError)
            statement.setString(8, operation.createdAt)
            statement.setString(9, operation.updatedAt)
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
    }

    private fun select(
        connection: Connection,
        sql: String,
        bind: (PreparedStatement) -> Unit,
    ): DeletionOperation? = connection.prepareStatement(sql).use { statement ->
        bind(statement)
        statement.executeQuery().use { rows -> if (rows.next()) rows.toOperation() else null }
    }

    private fun ResultSet.readAll(): List<DeletionOperation> =
        buildList { while (next()) add(toOperation()) }

    private fun ResultSet.toOperation(): DeletionOperation = DeletionOperation(
        id = getString("id"),
        collectionId = CollectionId(getString("collection_id")),
        collectionName = getString("collection_name"),
        trashBasename = getString("trash_basename"),
        managedOriginalsExisted = getInt("managed_originals_existed") == 1,
        phase = DeletionPhase.valueOf(getString("phase")),
        createdAt = getString("created_at"),
        updatedAt = getString("updated_at"),
        lastError = getString("last_error"),
    )

    private companion object {
        const val SELECT_OPERATIONS =
            "SELECT id, collection_id, collection_name, trash_basename, managed_originals_existed, " +
                "phase, last_error, created_at, updated_at FROM deletion_operations"
    }
}
