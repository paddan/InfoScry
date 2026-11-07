package infoscry.storage

import infoscry.domain.ContentUnitId
import infoscry.domain.DocumentId
import infoscry.domain.NormalizedBox
import infoscry.domain.SourceLocation
import infoscry.image.ImageExtractionMode
import infoscry.image.ImageSourceKind
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ImageStoreTest {

    private lateinit var directory: Path
    private lateinit var database: Database
    private lateinit var store: ImageStore

    private val documentId = DocumentId("doc-1")
    private val sha = "a".repeat(64)
    private val otherSha = "b".repeat(64)

    @BeforeTest
    fun openMigratedDatabase() {
        directory = Files.createTempDirectory("infoscry-images")
        database = Database(directory.resolve("state.db"))
        SchemaMigrator(database).migrate()
        store = ImageStore(database)
        execute(
            "INSERT INTO collections (id, name, created_at, updated_at) VALUES ('c', 'C', 'x', 'x')",
            "INSERT INTO documents (id, collection_id, sha256, media_type, original_filename, original_path, " +
                "size_bytes, status, created_at, updated_at) VALUES ('doc-1', 'c', 'h', 'application/pdf', " +
                "'d.pdf', '/d.pdf', 1, 'COMPLETE', 'x', 'x')",
        )
    }

    @AfterTest
    fun closeAndRemoveDatabase() {
        database.close()
        directory.toFile().deleteRecursively()
    }

    private fun execute(vararg statements: String) = database.transaction { connection ->
        connection.createStatement().use { statement -> statements.forEach { statement.executeUpdate(it) } }
    }

    private fun count(table: String): Int = database.read { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT count(*) FROM $table").use { rows ->
                rows.next()
                rows.getInt(1)
            }
        }
    }

    private fun image(unit: String, hash: String = sha, box: NormalizedBox? = NormalizedBox(0.1, 0.2, 0.5, 0.6)) =
        StoredImage(
            documentId = documentId,
            unitId = ContentUnitId(unit),
            sha256 = hash,
            parentUnitId = ContentUnitId("page-1"),
            kind = ImageSourceKind.PAGE_REGION,
            relativePath = "images/$hash.png",
            thumbnailRelativePath = "images/$hash-thumb.jpg",
            mediaType = "image/png",
            widthPx = 640,
            heightPx = 480,
            box = box,
            caption = "A figure",
            captionSource = CaptionSource.LAYOUT_CAPTION,
        )

    /** A revision of the document with one page row per (ordinal, unit, source image sha), all image regions. */
    private fun revision(id: String, vararg pages: Triple<Int, String, String>) {
        execute(
            "INSERT INTO document_revisions (id, document_id, state, provenance, created_at) " +
                "VALUES ('$id', 'doc-1', 'PUBLISHED', 'test', 'x')",
        )
        val locator = kotlinx.serialization.json.Json.encodeToString(
            SourceLocation.serializer(),
            SourceLocation.ImageRegion(SourceLocation.PdfPage(1), 1, null),
        ).replace("'", "''")
        pages.forEach { (ordinal, unit, hash) ->
            execute(
                "INSERT INTO page_text_revisions (revision_id, ordinal, unit_id, locator, extracted_text, search_text, " +
                    "approval, created_at, source_image_root, source_image_relative_path, source_image_sha256, " +
                    "source_image_render_version) VALUES ('$id', $ordinal, '$unit', '$locator', 'A figure', " +
                    "'A figure', 'APPROVED', 'x', 'ARTIFACTS', 'images/$hash.png', '$hash', 1)",
            )
        }
    }

    @Test
    fun `recording the same image twice leaves one row and reads it back`() {
        val first = image("u1")
        store.record(first)
        store.record(first)

        assertEquals(1, count("document_images"))
        assertEquals(first, store.image(documentId, first.unitId, sha))
        assertNull(store.image(documentId, ContentUnitId("missing"), sha))
    }

    @Test
    fun `an image without a box reads back without one`() {
        val whole = image("u1", box = null).copy(kind = ImageSourceKind.STANDALONE, caption = null, captionSource = null)
        store.record(whole)

        assertEquals(whole, store.image(documentId, whole.unitId, sha))
    }

    @Test
    fun `committing the same checkpoint twice leaves one row`() {
        val checkpoint = ImageCheckpoint(ContentUnitId("page-1"), ImageCheckpointOutcome.DONE, 2, null)
        repeat(2) { store.commitCheckpoint(documentId, "t", ImageExtractionMode.ALL, "v1", checkpoint) }

        assertEquals(1, count("image_extraction_checkpoints"))
        assertEquals(
            mapOf(ContentUnitId("page-1") to checkpoint),
            store.checkpoints(documentId, "t", ImageExtractionMode.ALL, "v1"),
        )
        assertEquals(emptyMap(), store.checkpoints(documentId, "t", ImageExtractionMode.ALL, "v2"), "another extractor version")
        assertEquals(emptyMap(), store.checkpoints(documentId, "t", ImageExtractionMode.EMBEDDED, "v1"), "another mode")
        assertEquals(emptyMap(), store.checkpoints(documentId, "other", ImageExtractionMode.ALL, "v1"), "another target")
    }

    @Test
    fun `a later checkpoint for the same parent replaces the earlier one`() {
        val parent = ContentUnitId("page-1")
        store.commitCheckpoint(documentId, "t", ImageExtractionMode.ALL, "v1", ImageCheckpoint(parent, ImageCheckpointOutcome.FAILED, 0, "boom"))
        val done = ImageCheckpoint(parent, ImageCheckpointOutcome.DONE, 1, null)
        store.commitCheckpoint(documentId, "t", ImageExtractionMode.ALL, "v1", done)

        assertEquals(mapOf(parent to done), store.checkpoints(documentId, "t", ImageExtractionMode.ALL, "v1"))
    }

    @Test
    fun `imagesOf returns only images whose unit has an image page row in that revision, in ordinal order`() {
        store.record(image("u1"))
        store.record(image("u2", hash = otherSha))
        store.record(image("u3", hash = "c".repeat(64)))
        revision("rev-1", Triple(1_000_000_001, "u2", otherSha), Triple(1_000_000_000, "u1", sha))
        revision("rev-2", Triple(1_000_000_000, "u1", sha))

        assertEquals(listOf("u1", "u2"), store.imagesOf("rev-1").map { it.unitId.value })
        assertEquals(listOf("u1"), store.imagesOf("rev-2").map { it.unitId.value })
        assertEquals(emptyList(), store.imagesOf("no-such-revision"))
    }

    @Test
    fun `deleting the document removes its images and checkpoints`() {
        store.record(image("u1"))
        store.commitCheckpoint(
            documentId, "t", ImageExtractionMode.EMBEDDED, "v1",
            ImageCheckpoint(ContentUnitId("page-1"), ImageCheckpointOutcome.DONE, 1, null),
        )

        execute("DELETE FROM documents WHERE id = 'doc-1'")

        assertEquals(0, count("document_images"))
        assertEquals(0, count("image_extraction_checkpoints"))
    }
}
