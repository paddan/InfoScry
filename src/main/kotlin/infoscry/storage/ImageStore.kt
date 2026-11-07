package infoscry.storage

import infoscry.domain.ContentUnitId
import infoscry.domain.DocumentId
import infoscry.domain.NormalizedBox
import infoscry.image.ImageExtractionMode
import infoscry.image.ImageSourceKind
import java.sql.ResultSet
import java.sql.Types

/** Where an image's caption came from. */
enum class CaptionSource { ALT_TEXT, FIGCAPTION, LAYOUT_CAPTION }

/**
 * The immutable metadata of one image unit: the managed file and its thumbnail (paths relative to the document's
 * artifact root), its size, where it sits on its parent when that is known, and its caption.
 *
 * Identified by [documentId], [unitId] and [sha256], the same triple a revision's `image_region` page row names
 * through its source image.
 */
data class StoredImage(
    val documentId: DocumentId,
    val unitId: ContentUnitId,
    val sha256: String,
    val parentUnitId: ContentUnitId,
    val kind: ImageSourceKind,
    val relativePath: String,
    val thumbnailRelativePath: String,
    val mediaType: String,
    val widthPx: Int,
    val heightPx: Int,
    val box: NormalizedBox?,
    val caption: String?,
    val captionSource: CaptionSource?,
)

/** How a look for images in one parent unit ended. */
enum class ImageCheckpointOutcome { DONE, FAILED }

/** What looking for images in one parent unit found, so a resumed extraction does not look again. */
data class ImageCheckpoint(
    val parentUnitId: ContentUnitId,
    val outcome: ImageCheckpointOutcome,
    val imageCount: Int,
    val warning: String?,
)

/**
 * Persistence for image metadata and image-extraction checkpoints.
 *
 * Both writes are upserts: repeating one with the same values changes nothing, and an interrupted extraction
 * that runs again converges on the same rows. Rows belong to their document and go with it.
 */
class ImageStore(private val database: Database) {

    /** Stores [image]'s metadata, replacing an earlier row for the same document, unit and file. */
    fun record(image: StoredImage) {
        database.transaction { connection ->
            connection.prepareStatement(
                "INSERT INTO document_images (document_id, unit_id, artifact_sha256, parent_unit_id, source_kind, " +
                    "artifact_relative_path, thumbnail_relative_path, media_type, width_px, height_px, " +
                    "box_left, box_top, box_right, box_bottom, caption, caption_source, created_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                    "ON CONFLICT (document_id, unit_id, artifact_sha256) DO UPDATE SET " +
                    "parent_unit_id = excluded.parent_unit_id, source_kind = excluded.source_kind, " +
                    "artifact_relative_path = excluded.artifact_relative_path, " +
                    "thumbnail_relative_path = excluded.thumbnail_relative_path, " +
                    "media_type = excluded.media_type, width_px = excluded.width_px, " +
                    "height_px = excluded.height_px, box_left = excluded.box_left, box_top = excluded.box_top, " +
                    "box_right = excluded.box_right, box_bottom = excluded.box_bottom, " +
                    "caption = excluded.caption, caption_source = excluded.caption_source",
            ).use { statement ->
                statement.setString(1, image.documentId.value)
                statement.setString(2, image.unitId.value)
                statement.setString(3, image.sha256)
                statement.setString(4, image.parentUnitId.value)
                statement.setString(5, image.kind.name)
                statement.setString(6, image.relativePath)
                statement.setString(7, image.thumbnailRelativePath)
                statement.setString(8, image.mediaType)
                statement.setInt(9, image.widthPx)
                statement.setInt(10, image.heightPx)
                val box = image.box
                if (box == null) {
                    (11..14).forEach { statement.setNull(it, Types.DOUBLE) }
                } else {
                    statement.setDouble(11, box.left)
                    statement.setDouble(12, box.top)
                    statement.setDouble(13, box.right)
                    statement.setDouble(14, box.bottom)
                }
                statement.setString(15, image.caption)
                statement.setString(16, image.captionSource?.name)
                statement.setString(17, Instants.now())
                statement.executeUpdate()
            }
        }
    }

    /** The image with the given identity, or null when none is recorded. */
    fun image(documentId: DocumentId, unitId: ContentUnitId, sha256: String): StoredImage? =
        database.read { connection ->
            connection.prepareStatement(
                "$SELECT_IMAGES WHERE i.document_id = ? AND i.unit_id = ? AND i.artifact_sha256 = ?",
            ).use { statement ->
                statement.setString(1, documentId.value)
                statement.setString(2, unitId.value)
                statement.setString(3, sha256)
                statement.executeQuery().use { rows -> if (rows.next()) rows.toImage() else null }
            }
        }

    /**
     * The images of one revision, in reading order: the revision's `image_region` page rows joined to their
     * metadata on (document, unit, source image). A unit with no page row in the revision is not its image, and
     * a page row whose metadata is not recorded yields nothing.
     */
    fun imagesOf(revisionId: String): List<StoredImage> = database.read { connection ->
        connection.prepareStatement(
            "$SELECT_IMAGES " +
                "JOIN document_revisions r ON r.document_id = i.document_id " +
                "JOIN page_text_revisions p ON p.revision_id = r.id AND p.unit_id = i.unit_id " +
                "AND p.source_image_sha256 = i.artifact_sha256 " +
                "WHERE r.id = ? AND (p.locator::jsonb ->> 'type') = 'image_region' ORDER BY p.ordinal",
        ).use { statement ->
            statement.setString(1, revisionId)
            statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.toImage()) } }
        }
    }

    /**
     * The checkpoints committed for [documentId] under [target] and [mode] by an extractor of
     * [extractorVersion], by parent unit. A checkpoint of another extractor version is not returned, so a new
     * extractor looks again.
     */
    fun checkpoints(
        documentId: DocumentId,
        target: String,
        mode: ImageExtractionMode,
        extractorVersion: String,
    ): Map<ContentUnitId, ImageCheckpoint> = database.read { connection ->
        connection.prepareStatement(
            "SELECT parent_unit_id, outcome, image_count, warning FROM image_extraction_checkpoints " +
                "WHERE document_id = ? AND target = ? AND mode = ? AND extractor_version = ?",
        ).use { statement ->
            statement.setString(1, documentId.value)
            statement.setString(2, target)
            statement.setString(3, mode.checkpointName())
            statement.setString(4, extractorVersion)
            statement.executeQuery().use { rows ->
                buildMap {
                    while (rows.next()) {
                        val parent = ContentUnitId(rows.getString("parent_unit_id"))
                        put(
                            parent,
                            ImageCheckpoint(
                                parentUnitId = parent,
                                outcome = ImageCheckpointOutcome.valueOf(rows.getString("outcome")),
                                imageCount = rows.getInt("image_count"),
                                warning = rows.getString("warning"),
                            ),
                        )
                    }
                }
            }
        }
    }

    /** Records [checkpoint]; repeating it with the same values changes nothing, and a later one replaces it. */
    fun commitCheckpoint(
        documentId: DocumentId,
        target: String,
        mode: ImageExtractionMode,
        extractorVersion: String,
        checkpoint: ImageCheckpoint,
    ) {
        database.transaction { connection ->
            connection.prepareStatement(
                "INSERT INTO image_extraction_checkpoints (document_id, target, parent_unit_id, mode, " +
                    "extractor_version, outcome, image_count, warning, committed_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                    "ON CONFLICT (document_id, target, parent_unit_id, mode) DO UPDATE SET " +
                    "extractor_version = excluded.extractor_version, outcome = excluded.outcome, " +
                    "image_count = excluded.image_count, warning = excluded.warning",
            ).use { statement ->
                statement.setString(1, documentId.value)
                statement.setString(2, target)
                statement.setString(3, checkpoint.parentUnitId.value)
                statement.setString(4, mode.checkpointName())
                statement.setString(5, extractorVersion)
                statement.setString(6, checkpoint.outcome.name)
                statement.setInt(7, checkpoint.imageCount)
                statement.setString(8, checkpoint.warning)
                statement.setString(9, Instants.now())
                statement.executeUpdate()
            }
        }
    }

    /** A checkpoint exists only for a mode that extracts something. */
    private fun ImageExtractionMode.checkpointName(): String {
        require(this != ImageExtractionMode.OFF) { "no checkpoint is kept for a run that extracts no images" }
        return name
    }

    private fun ResultSet.toImage(): StoredImage {
        val left = getDouble("box_left")
        val box = if (wasNull()) {
            null
        } else {
            NormalizedBox(left, getDouble("box_top"), getDouble("box_right"), getDouble("box_bottom"))
        }
        return StoredImage(
            documentId = DocumentId(getString("document_id")),
            unitId = ContentUnitId(getString("unit_id")),
            sha256 = getString("artifact_sha256"),
            parentUnitId = ContentUnitId(getString("parent_unit_id")),
            kind = ImageSourceKind.valueOf(getString("source_kind")),
            relativePath = getString("artifact_relative_path"),
            thumbnailRelativePath = getString("thumbnail_relative_path"),
            mediaType = getString("media_type"),
            widthPx = getInt("width_px"),
            heightPx = getInt("height_px"),
            box = box,
            caption = getString("caption"),
            captionSource = getString("caption_source")?.let(CaptionSource::valueOf),
        )
    }

    private companion object {
        const val SELECT_IMAGES =
            "SELECT i.document_id, i.unit_id, i.artifact_sha256, i.parent_unit_id, i.source_kind, " +
                "i.artifact_relative_path, i.thumbnail_relative_path, i.media_type, i.width_px, i.height_px, " +
                "i.box_left, i.box_top, i.box_right, i.box_bottom, i.caption, i.caption_source " +
                "FROM document_images i"
    }
}
