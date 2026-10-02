package infoscry.storage

import infoscry.domain.Collection
import infoscry.domain.CollectionId
import infoscry.domain.CollectionLifecycle
import infoscry.ocr.CollectionOcrSettings
import infoscry.ocr.OcrEngine
import infoscry.ocr.OcrImportMode
import infoscry.ocr.requireOcrLanguages
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException

/** A collection name is already used, compared case-insensitively. */
class DuplicateCollectionNameException(val name: String) :
    IllegalStateException("a collection named '$name' already exists")

/**
 * A deletion was not confirmed with the collection's exact name. Deleting a collection removes its
 * managed originals and index entries, so it must never happen from a mistyped or stale request.
 */
class CollectionConfirmationMismatchException(val actualName: String, val confirmedName: String) :
    IllegalStateException(
        "deleting the collection '$actualName' requires confirming that exact name, " +
            "but '$confirmedName' was given",
    )

/**
 * Persistence for collections. Every instant is written by [Instants]; names are stored trimmed and
 * are unique case-insensitively, which the schema's unique index enforces.
 */
class CollectionStore(private val database: Database) {

    fun create(
        name: String,
        description: String? = null,
        ocrLanguages: String = DEFAULT_OCR_LANGUAGES,
    ): Collection {
        val trimmedName = requireName(name)
        // The one spelling of the languages rule, shared with the collection service's settings edit and with
        // the fingerprint that would otherwise reject the value much later. A value this refuses is one no
        // attempt could key a reading by, so it is refused here rather than stored and discovered by the next
        // import.
        val languages = requireOcrLanguages(ocrLanguages)
        val now = Instants.now()
        val collection = Collection(
            id = CollectionId.new(),
            name = trimmedName,
            ocrLanguages = languages,
            createdAt = now,
            updatedAt = now,
            description = description?.trim()?.ifEmpty { null },
        )

        database.transaction { connection ->
            try {
                connection.prepareStatement(
                    "INSERT INTO collections (id, name, description, ocr_languages, lifecycle, " +
                        "created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                ).use { statement ->
                    statement.setString(1, collection.id.value)
                    statement.setString(2, collection.name)
                    statement.setString(3, collection.description)
                    statement.setString(4, collection.ocrLanguages)
                    statement.setString(5, collection.lifecycle.name)
                    statement.setString(6, collection.createdAt)
                    statement.setString(7, collection.updatedAt)
                    statement.executeUpdate()
                }
            } catch (failure: SQLException) {
                failure.rethrowAsNameConflict(collection.name)
            }
        }
        return collection
    }

    /**
     * All collections ordered by name, case-insensitively. Ordering by name rather than by creation
     * time keeps the list stable when two collections are created inside the same millisecond.
     */
    fun list(): List<Collection> = database.read { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("$SELECT_COLLECTIONS ORDER BY name COLLATE NOCASE").use { rows ->
                buildList { while (rows.next()) add(rows.toCollection()) }
            }
        }
    }

    /**
     * [list] with each collection's number of documents, counted in the same query that reads the
     * rows. A separate count query per collection would let the listing show a document that a
     * concurrent import added after its own row was read.
     */
    fun listWithDocumentCounts(): List<Collection> = database.read { connection ->
        connection.createStatement().use { statement ->
            statement
                .executeQuery("$SELECT_COLLECTIONS_WITH_DOCUMENT_COUNT ORDER BY c.name COLLATE NOCASE")
                .use { rows ->
                    buildList {
                        while (rows.next()) {
                            add(rows.toCollection().copy(documentCount = rows.getInt("document_count")))
                        }
                    }
                }
        }
    }

    fun get(id: CollectionId): Collection? = database.read { connection ->
        selectById(connection, id)
    }

    fun rename(id: CollectionId, newName: String): Collection {
        val trimmedName = requireName(newName)
        return database.transaction { connection ->
            val existing = selectById(connection, id)
                ?: throw NoSuchElementException("no collection with id ${id.value}")
            val updatedAt = Instants.now()
            try {
                connection.prepareStatement(
                    "UPDATE collections SET name = ?, updated_at = ? WHERE id = ?",
                ).use { statement ->
                    statement.setString(1, trimmedName)
                    statement.setString(2, updatedAt)
                    statement.setString(3, id.value)
                    statement.executeUpdate()
                }
            } catch (failure: SQLException) {
                failure.rethrowAsNameConflict(trimmedName)
            }
            existing.copy(name = trimmedName, updatedAt = updatedAt)
        }
    }

    /** Updates the OCR languages used when future import jobs snapshot this collection's settings. */
    fun updateOcrLanguages(id: CollectionId, ocrLanguages: String): Collection {
        val trimmedLanguages = requireOcrLanguages(ocrLanguages)
        return database.transaction { connection ->
            val existing = selectById(connection, id)
                ?: throw NoSuchElementException("no collection with id ${id.value}")
            val updatedAt = Instants.now()
            connection.prepareStatement(
                "UPDATE collections SET ocr_languages = ?, updated_at = ? WHERE id = ?",
            ).use { statement ->
                statement.setString(1, trimmedLanguages)
                statement.setString(2, updatedAt)
                statement.setString(3, id.value)
                statement.executeUpdate()
            }
            existing.copy(ocrLanguages = trimmedLanguages, updatedAt = updatedAt)
        }
    }

    /**
     * Writes every OCR setting of a collection in one statement, so a reader never sees half an edit.
     *
     * [settings] is the whole effective set rather than the fields that changed, because the settings are
     * only meaningful together: an engine without the profile it reads through is not a collection state.
     * Already queued payloads are untouched — this changes what the *next* attempt snapshots.
     */
    fun updateOcrSettings(id: CollectionId, settings: CollectionOcrSettings): Collection =
        database.transaction { connection ->
            val existing = selectById(connection, id)
                ?: throw NoSuchElementException("no collection with id ${id.value}")
            val updatedAt = Instants.now()
            connection.prepareStatement(
                "UPDATE collections SET ocr_languages = ?, ocr_engine = ?, ocr_import_mode = ?, " +
                    "ocr_transcription_profile_id = ?, ocr_review_profile_id = ?, " +
                    "ocr_external_page_limit = ?, updated_at = ? WHERE id = ?",
            ).use { statement ->
                statement.setString(1, settings.language)
                statement.setString(2, settings.engine.name)
                statement.setString(3, settings.importMode.name)
                statement.setString(4, settings.transcriptionProfileId)
                statement.setString(5, settings.reviewProfileId)
                statement.setInt(6, settings.externalPageLimit)
                statement.setString(7, updatedAt)
                statement.setString(8, id.value)
                statement.executeUpdate()
            }
            existing.copy(
                ocrLanguages = settings.language,
                ocrEngine = settings.engine,
                ocrImportMode = settings.importMode,
                ocrTranscriptionProfileId = settings.transcriptionProfileId,
                ocrReviewProfileId = settings.reviewProfileId,
                ocrExternalPageLimit = settings.externalPageLimit,
                updatedAt = updatedAt,
            )
        }

    /**
     * Deletes a collection row and cascades to its documents and jobs. Returns whether the
     * collection existed, and refuses to act unless [confirmName] is the collection's own name.
     *
     * This is the primitive; a deletion that must also move files and remove index entries is
     * driven by the durable deletion phases built on top of it.
     */
    fun delete(id: CollectionId, confirmName: String): Boolean = database.transaction { connection ->
        val existing = selectById(connection, id) ?: return@transaction false
        if (existing.name != confirmName.trim()) {
            throw CollectionConfirmationMismatchException(
                actualName = existing.name,
                confirmedName = confirmName,
            )
        }
        connection.prepareStatement("DELETE FROM collections WHERE id = ?").use { statement ->
            statement.setString(1, id.value)
            statement.executeUpdate()
        }
        true
    }

    private fun selectById(connection: Connection, id: CollectionId): Collection? =
        connection.prepareStatement("$SELECT_COLLECTIONS WHERE id = ?").use { statement ->
            statement.setString(1, id.value)
            statement.executeQuery().use { rows ->
                if (rows.next()) rows.toCollection() else null
            }
        }

    private fun requireName(name: String): String {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "collection name must not be blank" }
        return trimmed
    }

    /**
     * The schema's unique index on `name COLLATE NOCASE` is the authority on duplicate names, so the
     * store translates its violation instead of racing a `SELECT` before every insert.
     */
    private fun SQLException.rethrowAsNameConflict(name: String): Nothing =
        if (message?.contains("UNIQUE", ignoreCase = true) == true) {
            throw DuplicateCollectionNameException(name)
        } else {
            throw this
        }

    private fun ResultSet.toCollection(): Collection = Collection(
        id = CollectionId(getString("id")),
        name = getString("name"),
        ocrLanguages = getString("ocr_languages"),
        createdAt = getString("created_at"),
        updatedAt = getString("updated_at"),
        description = getString("description"),
        lifecycle = CollectionLifecycle.valueOf(getString("lifecycle")),
        ocrEngine = OcrEngine.valueOf(getString("ocr_engine")),
        ocrImportMode = OcrImportMode.valueOf(getString("ocr_import_mode")),
        ocrTranscriptionProfileId = getString("ocr_transcription_profile_id"),
        ocrReviewProfileId = getString("ocr_review_profile_id"),
        ocrExternalPageLimit = getInt("ocr_external_page_limit"),
    )

    companion object {
        /** The identifier of the legacy collection the first migration created. */
        val DEFAULT_ID: CollectionId = CollectionId("default")

        const val DEFAULT_OCR_LANGUAGES = "eng"

        private const val SELECT_COLLECTIONS =
            "SELECT id, name, description, ocr_languages, lifecycle, created_at, updated_at, " +
                "ocr_engine, ocr_import_mode, ocr_transcription_profile_id, ocr_review_profile_id, " +
                "ocr_external_page_limit FROM collections"

        /**
         * The same columns plus a document count, joined and grouped so one round trip answers the
         * whole listing. `COUNT(d.id)` counts matched document rows and not collections, so a
         * collection without documents is listed with 0 rather than dropped.
         */
        private const val SELECT_COLLECTIONS_WITH_DOCUMENT_COUNT =
            "SELECT c.id, c.name, c.description, c.ocr_languages, c.lifecycle, c.created_at, " +
                "c.updated_at, c.ocr_engine, c.ocr_import_mode, c.ocr_transcription_profile_id, " +
                "c.ocr_review_profile_id, c.ocr_external_page_limit, COUNT(d.id) AS document_count " +
                "FROM collections c LEFT JOIN documents d ON d.collection_id = c.id GROUP BY c.id"
    }
}
