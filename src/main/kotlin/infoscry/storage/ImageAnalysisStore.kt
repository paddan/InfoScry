package infoscry.storage

import infoscry.account.UserId
import infoscry.domain.ContentUnitId
import infoscry.domain.DocumentId
import infoscry.image.analysis.AnalysisOutcome
import infoscry.image.analysis.ImageAnalysis
import infoscry.image.analysis.ImageAnalysisKey
import infoscry.image.analysis.ImageTag
import infoscry.image.analysis.ImageTagEdit
import infoscry.image.analysis.TagEditAction
import java.sql.ResultSet
import kotlinx.serialization.json.Json

/**
 * Persistence for image analyses and the tag edits people made on top of them.
 *
 * An analysis is keyed by everything that decided it — the image, the profile revision, the prompt version, the
 * language and the appearance choice — so [record] is an upsert: a repeated run converges on one row per key,
 * and a `FAILED` row is replaced by the next attempt's `DONE` result. An edit is keyed by its own [editId], so
 * a repeated request changes nothing. Both tables belong to their document and go with it.
 */
class ImageAnalysisStore(private val database: Database) {

    /** The stored analysis for exactly this key, or null when it has never been made. */
    fun get(key: ImageAnalysisKey): ImageAnalysis? = database.read { connection ->
        connection.prepareStatement("$SELECT_ANALYSES WHERE ${KEY_MATCH}").use { statement ->
            bindKey(statement, key)
            statement.executeQuery().use { rows -> if (rows.next()) rows.toAnalysis() else null }
        }
    }

    /**
     * Stores [analysis], replacing the row for the same key if one exists.
     *
     * The replacement is what makes a retry possible: a `FAILED` row describes the inputs, not a permanent
     * verdict, so the next attempt's result takes its place.
     */
    fun record(analysis: ImageAnalysis) {
        database.transaction { connection ->
            connection.prepareStatement(
                "INSERT INTO image_analyses (document_id, unit_id, image_sha256, profile_revision_id, " +
                    "prompt_version, language, identify_appearance, outcome, description, tags, caption, " +
                    "page_context, error_code, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?) " +
                    "ON CONFLICT (document_id, unit_id, image_sha256, profile_revision_id, prompt_version, " +
                    "language, identify_appearance) DO UPDATE SET outcome = excluded.outcome, " +
                    "description = excluded.description, tags = excluded.tags, " +
                    "caption = excluded.caption, page_context = excluded.page_context, " +
                    "error_code = excluded.error_code, created_at = excluded.created_at",
            ).use { statement ->
                val key = analysis.key
                statement.setString(1, key.documentId.value)
                statement.setString(2, key.unitId.value)
                statement.setString(3, key.imageSha256)
                statement.setString(4, key.profileRevisionId)
                statement.setInt(5, key.promptVersion)
                statement.setString(6, key.language)
                statement.setInt(7, if (key.identifyAppearance) 1 else 0)
                statement.setString(8, analysis.outcome.name)
                statement.setString(9, analysis.description)
                statement.setString(10, Json.encodeToString(analysis.tags))
                statement.setString(11, analysis.caption)
                statement.setString(12, analysis.pageContext)
                statement.setString(13, analysis.errorCode)
                statement.setString(14, Instants.now())
                statement.executeUpdate()
            }
        }
    }

    /**
     * The most recent completed analysis of this image, whatever profile, prompt, language or appearance choice
     * produced it.
     *
     * It is what an image-only rescan keeps: a picture that was described before is not described again just
     * because the run that finds its images again carries no analysis choice.
     */
    fun latestDone(documentId: DocumentId, unitId: ContentUnitId, imageSha256: String): ImageAnalysis? =
        database.read { connection ->
            connection.prepareStatement(
                "$SELECT_ANALYSES WHERE document_id = ? AND unit_id = ? AND image_sha256 = ? AND outcome = 'DONE' " +
                    "ORDER BY created_at DESC, recorded_seq DESC LIMIT 1",
            ).use { statement ->
                statement.setString(1, documentId.value)
                statement.setString(2, unitId.value)
                statement.setString(3, imageSha256)
                statement.executeQuery().use { rows -> if (rows.next()) rows.toAnalysis() else null }
            }
        }

    /**
     * The most recent analysis of this image unit, completed or failed, whatever inputs produced it.
     *
     * It is what lets the viewer tell "not described yet" from "the last attempt to describe it failed": the
     * effective tags still come from the latest completed analysis, but a failure after it is worth marking.
     */
    fun latest(documentId: DocumentId, unitId: ContentUnitId): ImageAnalysis? = database.read { connection ->
        connection.prepareStatement(
            "$SELECT_ANALYSES WHERE document_id = ? AND unit_id = ? ORDER BY created_at DESC, recorded_seq DESC LIMIT 1",
        ).use { statement ->
            statement.setString(1, documentId.value)
            statement.setString(2, unitId.value)
            statement.executeQuery().use { rows -> if (rows.next()) rows.toAnalysis() else null }
        }
    }

    /**
     * The display name of each edit's author, by edit id.
     *
     * The edit row stores who made it; the name a person reads lives on the account. One join answers both
     * what the viewer lists and the attribution beside it, and a deleted account (which the schema keeps)
     * still resolves.
     */
    fun editAuthorNames(documentId: DocumentId, unitId: ContentUnitId): Map<String, String> = database.read { connection ->
        connection.prepareStatement(
            "SELECT e.edit_id, u.display_name FROM image_tag_edits e " +
                "LEFT JOIN users u ON u.id = e.created_by WHERE e.document_id = ? AND e.unit_id = ?",
        ).use { statement ->
            statement.setString(1, documentId.value)
            statement.setString(2, unitId.value)
            statement.executeQuery().use { rows ->
                buildMap { while (rows.next()) rows.getString(2)?.let { name -> put(rows.getString(1), name) } }
            }
        }
    }

    /** The edits of one image unit, in the order people made them. */
    fun edits(documentId: DocumentId, unitId: ContentUnitId): List<ImageTagEdit> = database.read { connection ->
        connection.prepareStatement("$SELECT_EDITS WHERE document_id = ? AND unit_id = ? ORDER BY edit_seq").use { statement ->
            statement.setString(1, documentId.value)
            statement.setString(2, unitId.value)
            statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.toEdit()) } }
        }
    }

    /**
     * Stores [edit] and returns the edit that is stored under its id.
     *
     * A repeated request with the same id is a no-op that answers the first one's result: the request may be
     * retried by a client that never saw the response, and a second identical edit must not move the version or
     * apply twice.
     */
    fun addEdit(edit: ImageTagEdit): ImageTagEdit = database.transaction { connection ->
        insertEdit(connection, edit)
        checkNotNull(editById(connection, edit.documentId, edit.unitId, edit.editId)) {
            "an inserted tag edit is readable in the transaction that wrote it"
        }
    }

    /**
     * Stores [edit] only if the unit is still at [expectedVersion], with the check and the insert as one
     * transaction.
     *
     * The unit's edits are serialized by a transaction-scoped advisory lock, so two editors who both read
     * version N cannot both write: the second one, after waiting for the lock, sees the first one's edit as
     * the current version and its own N is refused. A repeated [ImageTagEdit.editId] is the first request's
     * answer, returned without moving the version.
     */
    fun addEditIfVersion(edit: ImageTagEdit, expectedVersion: Long?): ImageTagEdit = database.transaction { connection ->
        connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))").use { statement ->
            statement.setString(1, "image-tags:${edit.documentId.value}:${edit.unitId.value}")
            statement.executeQuery().close()
        }
        editById(connection, edit.documentId, edit.unitId, edit.editId)?.let { return@transaction it }
        infoscry.domain.requireVersion(expectedVersion, versionIn(connection, edit.documentId, edit.unitId), null)
        insertEdit(connection, edit)
        checkNotNull(editById(connection, edit.documentId, edit.unitId, edit.editId)) {
            "an inserted tag edit is readable in the transaction that wrote it"
        }
    }

    private fun insertEdit(connection: java.sql.Connection, edit: ImageTagEdit) {
        connection.prepareStatement(
            "INSERT INTO image_tag_edits (document_id, unit_id, edit_id, action, tag, created_by, created_at) " +
                "VALUES (?, ?, ?, ?, ?::jsonb, ?, ?) ON CONFLICT (document_id, unit_id, edit_id) DO NOTHING",
        ).use { statement ->
            statement.setString(1, edit.documentId.value)
            statement.setString(2, edit.unitId.value)
            statement.setString(3, edit.editId)
            statement.setString(4, edit.action.name)
            statement.setString(5, Json.encodeToString(edit.tag))
            statement.setString(6, edit.createdBy?.value)
            statement.setString(7, edit.createdAt)
            statement.executeUpdate()
        }
    }

    private fun versionIn(connection: java.sql.Connection, documentId: DocumentId, unitId: ContentUnitId): Long =
        connection.prepareStatement(
            "SELECT coalesce(max(edit_seq), 0) FROM image_tag_edits WHERE document_id = ? AND unit_id = ?",
        ).use { statement ->
            statement.setString(1, documentId.value)
            statement.setString(2, unitId.value)
            statement.executeQuery().use { rows -> rows.next(); rows.getLong(1) }
        }

    /**
     * The version an edit of this image is made from.
     *
     * It moves with every edit of the unit, and only with those, so two editors who both read version N cannot
     * both write: the second one's N is stale and its edit is refused instead of silently overwriting the
     * first one's decision.
     */
    fun editsVersion(documentId: DocumentId, unitId: ContentUnitId): Long = database.read { connection ->
        connection.prepareStatement(
            "SELECT coalesce(max(edit_seq), 0) FROM image_tag_edits WHERE document_id = ? AND unit_id = ?",
        ).use { statement ->
            statement.setString(1, documentId.value)
            statement.setString(2, unitId.value)
            statement.executeQuery().use { rows ->
                rows.next()
                rows.getLong(1)
            }
        }
    }

    private fun editById(connection: java.sql.Connection, documentId: DocumentId, unitId: ContentUnitId, editId: String): ImageTagEdit? =
        connection.prepareStatement("$SELECT_EDITS WHERE document_id = ? AND unit_id = ? AND edit_id = ?").use { statement ->
            statement.setString(1, documentId.value)
            statement.setString(2, unitId.value)
            statement.setString(3, editId)
            statement.executeQuery().use { rows -> if (rows.next()) rows.toEdit() else null }
        }

    private fun bindKey(statement: java.sql.PreparedStatement, key: ImageAnalysisKey) {
        statement.setString(1, key.documentId.value)
        statement.setString(2, key.unitId.value)
        statement.setString(3, key.imageSha256)
        statement.setString(4, key.profileRevisionId)
        statement.setInt(5, key.promptVersion)
        statement.setString(6, key.language)
        statement.setInt(7, if (key.identifyAppearance) 1 else 0)
    }

    private fun ResultSet.toAnalysis(): ImageAnalysis = ImageAnalysis(
        key = ImageAnalysisKey(
            documentId = DocumentId(getString("document_id")),
            unitId = ContentUnitId(getString("unit_id")),
            imageSha256 = getString("image_sha256"),
            profileRevisionId = getString("profile_revision_id"),
            promptVersion = getInt("prompt_version"),
            language = getString("language"),
            identifyAppearance = getInt("identify_appearance") != 0,
        ),
        outcome = AnalysisOutcome.valueOf(getString("outcome")),
        description = getString("description"),
        tags = Json.decodeFromString<List<ImageTag>>(getString("tags")),
        errorCode = getString("error_code"),
        caption = getString("caption"),
        pageContext = getString("page_context"),
    )

    private fun ResultSet.toEdit(): ImageTagEdit = ImageTagEdit(
        editId = getString("edit_id"),
        documentId = DocumentId(getString("document_id")),
        unitId = ContentUnitId(getString("unit_id")),
        action = TagEditAction.valueOf(getString("action")),
        tag = Json.decodeFromString<ImageTag>(getString("tag")),
        createdBy = getString("created_by")?.let(::UserId),
        createdAt = getString("created_at"),
    )

    private companion object {
        const val SELECT_ANALYSES =
            "SELECT document_id, unit_id, image_sha256, profile_revision_id, prompt_version, language, " +
                "identify_appearance, outcome, description, tags::text AS tags, caption, page_context, error_code " +
                "FROM image_analyses"

        const val KEY_MATCH =
            "document_id = ? AND unit_id = ? AND image_sha256 = ? AND profile_revision_id = ? AND " +
                "prompt_version = ? AND language = ? AND identify_appearance = ?"

        const val SELECT_EDITS =
            "SELECT document_id, unit_id, edit_id, action, tag::text AS tag, created_by, created_at " +
                "FROM image_tag_edits"
    }
}
