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
 * The document is a document deletion's target, so no stage may write it any more.
 *
 * Raised where the write happens rather than by each caller, because there are many writers — a status
 * transition in the import handler, one in the extraction sink, one in the embedding stage — and a
 * guard a caller can forget is not a guard. The document's row may already be gone, which is why this
 * is distinct from [NoSuchElementException]: the resume must treat it as "this file is cancelled", not
 * as "the document does not exist".
 *
 * The durable record behind it is the deletion target, which outlives the document it names, so the
 * answer stays true after the deletion has finished and the identifiers can never be reused.
 */
class DocumentBeingDeletedException(val documentId: DocumentId) :
    IllegalStateException("document ${documentId.value} is being deleted and cannot be written")

/**
 * Persistence for documents. The row-level constraints are the authority: the unique
 * `(collection_id, sha256)` index decides duplicates, and the foreign key to `collections` decides
 * whether a collection exists, so no caller has to race a `SELECT` before writing. The collection's
 * lifecycle is checked in the same statement for the same reason.
 */
/**
 * The document-level criteria a search can be restricted by, in the storage vocabulary.
 *
 * [filenameOrPathContains] and [titleAuthorOrLanguageContains] match anywhere in the named columns,
 * case-insensitively via SQLite's LIKE; [ocrOnly] keeps only documents that have at least one content
 * unit read by OCR (a unit whose mean confidence is recorded). Dates are ISO-8601 UTC strings, which
 * compare lexicographically.
 */
data class DocumentCriterion(
    val collectionId: CollectionId? = null,
    val mediaTypes: Set<String> = emptySet(),
    val filenameOrPathContains: String? = null,
    val titleAuthorOrLanguageContains: String? = null,
    val importedFrom: String? = null,
    val importedUntil: String? = null,
    val statuses: Set<DocumentStatus> = emptySet(),
    val ocrOnly: Boolean = false,
)

/**
 * The order one collection's document page comes back in.
 *
 * Every clause ends in the document id, so documents that share an import date or a filename still come
 * back in one repeatable order: a page boundary that falls inside such a tie cannot repeat one row and
 * drop another.
 */
enum class DocumentSort(internal val orderBy: String) {
    NEWEST("created_at DESC, id"),
    OLDEST("created_at ASC, id"),
    NAME_ASC("original_filename COLLATE NOCASE ASC, id"),
    NAME_DESC("original_filename COLLATE NOCASE DESC, id"),
}

/**
 * The criteria a collection's document listing is restricted to, in the storage vocabulary.
 *
 * [filenameContains] is a literal, case-insensitive contains match on the original filename alone: the
 * external source path is not a product surface, so it is neither searched nor returned, and a `%`, `_`
 * or backslash the reader typed matches itself rather than widening the search.
 */
data class DocumentListing(
    val collectionId: CollectionId,
    val filenameContains: String? = null,
    val statuses: Set<DocumentStatus> = emptySet(),
    val sort: DocumentSort = DocumentSort.NEWEST,
)

class DocumentStore(private val database: Database) {

    /**
     * The document ids matching [criterion], in no particular order.
     *
     * This is the pre-retrieval restriction half of a search: the Lucene query itself is narrowed to
     * these ids, so a criterion can never be applied after the fact. An empty result means no document
     * passes the criteria and the search is empty without touching the index.
     */
    fun findIds(criterion: DocumentCriterion): List<DocumentId> = database.read { connection ->
        connection.prepareStatement(criterionSql(criterion)).use { statement ->
            var index = 1
            criterion.collectionId?.let { collectionId ->
                statement.setString(index++, collectionId.value)
            }
            criterion.mediaTypes.forEach { mediaType -> statement.setString(index++, mediaType) }
            criterion.filenameOrPathContains?.let { contains ->
                val pattern = likePattern(contains)
                statement.setString(index++, pattern)
                statement.setString(index++, pattern)
            }
            criterion.titleAuthorOrLanguageContains?.let { contains ->
                val pattern = likePattern(contains)
                statement.setString(index++, pattern)
                statement.setString(index++, pattern)
                statement.setString(index++, pattern)
            }
            criterion.importedFrom?.let { statement.setString(index++, it) }
            criterion.importedUntil?.let { statement.setString(index++, it) }
            criterion.statuses.forEach { status -> statement.setString(index++, status.name) }
            statement.executeQuery().use { rows ->
                buildList { while (rows.next()) add(DocumentId(rows.getString(1))) }
            }
        }
    }

    private fun criterionSql(criterion: DocumentCriterion): String = buildString {
        append("SELECT id FROM documents d WHERE 1=1")
        criterion.collectionId?.let { append(" AND d.collection_id = ?") }
        if (criterion.mediaTypes.isNotEmpty()) {
            append(" AND d.media_type IN (")
            append(criterion.mediaTypes.joinToString(",") { "?" })
            append(")")
        }
        if (criterion.filenameOrPathContains != null) {
            append(" AND (d.original_filename LIKE ? ESCAPE '\\' OR d.original_path LIKE ? ESCAPE '\\')")
        }
        if (criterion.titleAuthorOrLanguageContains != null) {
            append(
                " AND (d.title LIKE ? ESCAPE '\\' OR d.author LIKE ? ESCAPE '\\' " +
                    "OR d.language LIKE ? ESCAPE '\\')",
            )
        }
        criterion.importedFrom?.let { append(" AND d.created_at >= ?") }
        criterion.importedUntil?.let { append(" AND d.created_at <= ?") }
        if (criterion.statuses.isNotEmpty()) {
            append(" AND d.status IN (")
            append(criterion.statuses.joinToString(",") { "?" })
            append(")")
        }
        if (criterion.ocrOnly) {
            append(
                " AND EXISTS (SELECT 1 FROM content_units cu " +
                    "WHERE cu.document_id = d.id AND cu.mean_confidence IS NOT NULL)",
            )
        }
    }

    /** Wraps a contains-match in LIKE wildcards, escaping the user's own LIKE metacharacters. */
    private fun likePattern(contains: String): String =
        "%" + contains.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"


    /**
     * Inserts [document], and lets the caller record a durable reference to it in the same commit.
     *
     * [onDocumentCreated] runs inside this insert's own transaction, immediately after the row exists and
     * before the commit. That is what makes a link written there outlive a crash between "this row exists"
     * and whatever the caller does next: the row and the reference are either both there or both gone. It is
     * called only for a document this call created — a refused insert throws instead, so nobody can claim a
     * document that was never written.
     */
    fun insert(
        document: Document,
        onDocumentCreated: (Connection, DocumentId) -> Unit = { _, _ -> },
    ): Document {
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
            onDocumentCreated(connection, document.id)
        }
        return document
    }

    fun get(id: DocumentId): Document? = database.read { connection -> selectById(connection, id) }

    /**
     * Whether anything is deleting or has deleted [documentId].
     *
     * The target row outlives the document, so this is true for a resumed stage that still holds the id
     * of a document whose row is already gone. Every write boundary that names a document reads it
     * before it writes, which is what stops a deletion and a resume from resurrecting each other's work.
     */
    fun isDeletionTarget(id: DocumentId): Boolean = database.read { connection -> isDeletionTarget(connection, id) }

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
     * One page of a collection's documents under [listing].
     *
     * The criteria are applied in SQL, before the limit: a page holds rows that passed the filter, never
     * the filter's result taken from an unfiltered page. [countListing] applies the same criteria, so the
     * total a caller displays always describes exactly the rows it asked for.
     */
    fun listListing(listing: DocumentListing, limit: Int, offset: Int = 0): List<Document> {
        require(limit > 0) { "limit must be positive, was $limit" }
        require(offset >= 0) { "offset must not be negative, was $offset" }
        val (where, parameters) = listingWhere(listing)
        return database.read { connection ->
            connection.prepareStatement(
                "$SELECT_DOCUMENTS WHERE $where ORDER BY ${listing.sort.orderBy} LIMIT ? OFFSET ?",
            ).use { statement ->
                parameters.forEachIndexed { index, value -> statement.setString(index + 1, value) }
                statement.setInt(parameters.size + 1, limit)
                statement.setInt(parameters.size + 2, offset)
                statement.executeQuery().use { rows ->
                    buildList { while (rows.next()) add(rows.toDocument()) }
                }
            }
        }
    }

    /** How many documents [listing] matches, under the criteria [listListing] pages through. */
    fun countListing(listing: DocumentListing): Int {
        val (where, parameters) = listingWhere(listing)
        return database.read { connection ->
            connection.prepareStatement("SELECT COUNT(*) FROM documents WHERE $where").use { statement ->
                parameters.forEachIndexed { index, value -> statement.setString(index + 1, value) }
                statement.executeQuery().use { rows ->
                    rows.next()
                    rows.getInt(1)
                }
            }
        }
    }

    /** The `WHERE` clause and its bound values that a listing's page and its total share. */
    private fun listingWhere(listing: DocumentListing): Pair<String, List<String>> {
        val clause = StringBuilder("collection_id = ?")
        val parameters = mutableListOf(listing.collectionId.value)
        listing.filenameContains?.let { contains ->
            clause.append(" AND original_filename LIKE ? ESCAPE '\\'")
            parameters += likePattern(contains)
        }
        if (listing.statuses.isNotEmpty()) {
            clause.append(" AND status IN (").append(listing.statuses.joinToString(",") { "?" }).append(")")
            listing.statuses.forEach { status -> parameters += status.name }
        }
        return clause.toString() to parameters
    }

    /**
     * Moves a document to [status]. Passing null error values clears any previous error, so a
     * document that later succeeds does not keep a stale failure attached to it.
     *
     * The write is guarded against a document deletion in the same statement, so a status transition
     * and the deletion cannot both win: the row is written only while nothing is deleting the document.
     * When nothing matched, the distinction matters — a missing row is an error, a target of a deletion
     * is a cancellation — so the guard is read back inside the same transaction to say which it was.
     */
    fun updateStatus(
        id: DocumentId,
        status: DocumentStatus,
        errorCode: String? = null,
        errorMessage: String? = null,
    ): Document {
        val updatedAt = Instants.now()
        val changed = database.transaction { connection ->
            val updated = connection.prepareStatement(
                "UPDATE documents SET status = ?, error_code = ?, error_message = ?, updated_at = ? " +
                    "WHERE id = ? AND NOT EXISTS " +
                    "(SELECT 1 FROM document_deletion_targets WHERE document_id = ?)",
            ).use { statement ->
                statement.setString(1, status.name)
                statement.setString(2, errorCode)
                statement.setString(3, errorMessage)
                statement.setString(4, updatedAt)
                statement.setString(5, id.value)
                statement.setString(6, id.value)
                statement.executeUpdate()
            }
            if (updated == 0 && isDeletionTarget(connection, id)) throw DocumentBeingDeletedException(id)
            updated
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

    private fun isDeletionTarget(connection: Connection, id: DocumentId): Boolean =
        connection.prepareStatement("SELECT 1 FROM document_deletion_targets WHERE document_id = ?").use { statement ->
            statement.setString(1, id.value)
            statement.executeQuery().use { rows -> rows.next() }
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
