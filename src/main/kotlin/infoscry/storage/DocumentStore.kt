package infoscry.storage

import infoscry.domain.CollectionId
import infoscry.domain.CollectionLifecycle
import infoscry.domain.Document
import infoscry.domain.DocumentId
import infoscry.domain.DocumentStatus
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException

/**
 * The collection already holds a document with these bytes. The unique constraint is
 * `(collectionId, sha256)`, so the same bytes in another collection are not a conflict.
 */
class DuplicateDocumentException(val collectionId: CollectionId, val sha256: String) :
    IllegalStateException("collection ${collectionId.value} already contains a document with sha256 $sha256")

/**
 * The collection is being deleted, so it may not receive documents any more.
 *
 * Raised by the publish boundary itself rather than by a caller's precondition. A deletion tombstone and
 * a document insert can race — an import that started before the tombstone is still holding bytes it
 * wants to publish — so the guard has to be part of the write, which is what [DocumentStore.insert]
 * does. A caller additionally rechecks the lifecycle after it is admitted, to avoid doing the work at
 * all when it can see the tombstone early.
 */
class CollectionNotActiveException(val collectionId: CollectionId) :
    IllegalStateException(
        "collection ${collectionId.value} is being deleted and cannot receive documents",
    )

/**
 * Persistence for documents. The row-level constraints are the authority: the unique
 * `(collection_id, sha256)` index decides duplicates, and the foreign key to `collections` decides
 * whether a collection exists, so no caller has to race a `SELECT` before writing. The collection's
 * lifecycle is checked in the same statement for the same reason.
 */
class DocumentStore(private val database: Database) {

    fun insert(document: Document): Document {
        database.transaction { connection ->
            connection.prepareStatement(INSERT_DOCUMENT).use { statement ->
                bindDocument(statement, document)
                val inserted = try {
                    statement.executeUpdate()
                } catch (failure: SQLException) {
                    failure.rethrowAsConstraintViolation(document)
                }
                if (inserted == 0) throw refusalFor(connection, document)
            }
        }
        return document
    }

    fun get(id: DocumentId): Document? = database.read { connection -> selectById(connection, id) }

    fun findBySha256(collectionId: CollectionId, sha256: String): Document? =
        database.read { connection ->
            connection.prepareStatement(
                "$SELECT_DOCUMENTS WHERE collection_id = ? AND sha256 = ?",
            ).use { statement ->
                statement.setString(1, collectionId.value)
                statement.setString(2, sha256)
                statement.executeQuery().use { rows ->
                    if (rows.next()) rows.toDocument() else null
                }
            }
        }

    /** Documents of one collection, newest first, ordered by id so a tie is still deterministic. */
    fun listByCollection(collectionId: CollectionId, limit: Int, offset: Int = 0): List<Document> {
        require(limit > 0) { "limit must be positive, was $limit" }
        require(offset >= 0) { "offset must not be negative, was $offset" }
        return database.read { connection ->
            connection.prepareStatement(
                "$SELECT_DOCUMENTS WHERE collection_id = ? ORDER BY created_at DESC, id LIMIT ? OFFSET ?",
            ).use { statement ->
                statement.setString(1, collectionId.value)
                statement.setInt(2, limit)
                statement.setInt(3, offset)
                statement.executeQuery().use { rows ->
                    buildList { while (rows.next()) add(rows.toDocument()) }
                }
            }
        }
    }

    fun countByCollection(collectionId: CollectionId): Int = database.read { connection ->
        connection.prepareStatement("SELECT COUNT(*) FROM documents WHERE collection_id = ?").use { statement ->
            statement.setString(1, collectionId.value)
            statement.executeQuery().use { rows ->
                rows.next()
                rows.getInt(1)
            }
        }
    }

    /**
     * Moves a document to [status]. Passing null error values clears any previous error, so a
     * document that later succeeds does not keep a stale failure attached to it.
     */
    fun updateStatus(
        id: DocumentId,
        status: DocumentStatus,
        errorCode: String? = null,
        errorMessage: String? = null,
    ): Document {
        val updatedAt = Instants.now()
        val changed = database.transaction { connection ->
            connection.prepareStatement(
                "UPDATE documents SET status = ?, error_code = ?, error_message = ?, updated_at = ? " +
                    "WHERE id = ?",
            ).use { statement ->
                statement.setString(1, status.name)
                statement.setString(2, errorCode)
                statement.setString(3, errorMessage)
                statement.setString(4, updatedAt)
                statement.setString(5, id.value)
                statement.executeUpdate()
            }
        }
        if (changed == 0) throw NoSuchElementException("no document with id ${id.value}")
        return get(id) ?: throw NoSuchElementException("no document with id ${id.value}")
    }

    fun delete(id: DocumentId): Boolean = database.transaction { connection ->
        connection.prepareStatement("DELETE FROM documents WHERE id = ?").use { statement ->
            statement.setString(1, id.value)
            statement.executeUpdate() > 0
        }
    }

    private fun selectById(connection: Connection, id: DocumentId): Document? =
        connection.prepareStatement("$SELECT_DOCUMENTS WHERE id = ?").use { statement ->
            statement.setString(1, id.value)
            statement.executeQuery().use { rows ->
                if (rows.next()) rows.toDocument() else null
            }
        }

    private fun SQLException.rethrowAsConstraintViolation(document: Document): Nothing = when {
        message?.contains("UNIQUE", ignoreCase = true) == true ->
            throw DuplicateDocumentException(document.collectionId, document.sha256)
        message?.contains("FOREIGN KEY", ignoreCase = true) == true ->
            throw NoSuchElementException("no collection with id ${document.collectionId.value}")
        else -> throw this
    }

    private fun bindDocument(statement: PreparedStatement, document: Document) {
        statement.setString(1, document.id.value)
        statement.setString(2, document.collectionId.value)
        statement.setString(3, document.sha256)
        statement.setString(4, document.mediaType)
        statement.setString(5, document.originalFilename)
        statement.setString(6, document.sourcePath)
        statement.setLong(7, document.sizeBytes)
        statement.setString(8, document.status.name)
        statement.setString(9, document.title)
        statement.setString(10, document.author)
        statement.setString(11, document.language)
        statement.setString(12, document.errorCode)
        statement.setString(13, document.errorMessage)
        statement.setString(14, document.createdAt)
        statement.setString(15, document.updatedAt)
        // The guard's own parameters: which collection must still be active for the row to appear.
        statement.setString(16, document.collectionId.value)
        statement.setString(17, CollectionLifecycle.ACTIVE.name)
    }

    /**
     * Why the guarded insert matched no row.
     *
     * Both a missing collection and a tombstoned one match nothing, and the two callers deserve
     * different answers: the first is a bug in the caller, the second is a deletion that won the race.
     * Reading the lifecycle back inside the same transaction is what tells them apart.
     */
    private fun refusalFor(connection: Connection, document: Document): RuntimeException {
        val lifecycle = connection.prepareStatement(SELECT_LIFECYCLE).use { statement ->
            statement.setString(1, document.collectionId.value)
            statement.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
        }
        return when (lifecycle) {
            null -> NoSuchElementException("no collection with id ${document.collectionId.value}")
            CollectionLifecycle.ACTIVE.name -> IllegalStateException(
                "the guarded insert matched no row although collection ${document.collectionId.value} " +
                    "is ${CollectionLifecycle.ACTIVE}",
            )

            else -> CollectionNotActiveException(document.collectionId)
        }
    }

    private fun ResultSet.toDocument(): Document = Document(
        id = DocumentId(getString("id")),
        collectionId = CollectionId(getString("collection_id")),
        sha256 = getString("sha256"),
        mediaType = getString("media_type"),
        originalFilename = getString("original_filename"),
        sourcePath = getString("original_path"),
        sizeBytes = getLong("size_bytes"),
        status = DocumentStatus.valueOf(getString("status")),
        title = getString("title"),
        author = getString("author"),
        language = getString("language"),
        errorCode = getString("error_code"),
        errorMessage = getString("error_message"),
        createdAt = getString("created_at"),
        updatedAt = getString("updated_at"),
    )

    private companion object {
        const val DOCUMENT_COLUMNS =
            "id, collection_id, sha256, media_type, original_filename, original_path, size_bytes, " +
                "status, title, author, language, error_code, error_message, created_at, updated_at"

        const val SELECT_DOCUMENTS = "SELECT $DOCUMENT_COLUMNS FROM documents"

        const val SELECT_LIFECYCLE = "SELECT lifecycle FROM collections WHERE id = ?"

        /**
         * The insert is guarded by the collection's lifecycle, so a deletion tombstone and a publish
         * cannot both win: the row exists only if the collection was still `ACTIVE` at the instant of
         * the write. A plain `INSERT` would let an import that read the lifecycle earlier publish into
         * a collection whose deletion had already started.
         */
        const val INSERT_DOCUMENT =
            "INSERT INTO documents ($DOCUMENT_COLUMNS) " +
                "SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ? " +
                "WHERE EXISTS (SELECT 1 FROM collections WHERE id = ? AND lifecycle = ?)"
    }
}
