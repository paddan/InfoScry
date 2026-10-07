package infoscry.storage

import infoscry.domain.ContentUnitId
import infoscry.domain.ExtractionMethod
import infoscry.domain.SourceImageProvenance
import infoscry.domain.SourceImageRoot
import infoscry.domain.SourceLocation
import java.nio.file.Files
import java.nio.file.Path
import java.sql.SQLException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The shape a page's source-image provenance may take, at every place it can be written or read.
 *
 * Ticket 03b: a reference a reviewer-facing route serves an image from must be root-confined, a row must
 * never carry half a provenance, and a row that somehow does must not read back as "no image".
 */
class SourceImageProvenanceTest {

    private val temporaryDirectories = mutableListOf<Path>()

    private val now = "2026-10-07T07:00:00Z"

    @AfterTest
    fun removeTemporaryDirectories() {
        temporaryDirectories.forEach { it.toFile().deleteRecursively() }
    }

    private fun provenance(
        relativePath: String,
        root: SourceImageRoot = SourceImageRoot.ARTIFACTS,
    ) = SourceImageProvenance(
        root = root,
        relativePath = relativePath,
        sha256 = "a".repeat(64),
        width = 100,
        height = 200,
        renderVersion = 1,
    )

    private fun archive(): Pair<Database, DocumentRevisionStore> {
        val directory = Files.createTempDirectory("infoscry-provenance").also(temporaryDirectories::add)
        val database = Database(directory.resolve("state.db"))
        SchemaMigrator(database).migrate()
        database.transaction { connection ->
            connection.createStatement().use { statement ->
                listOf(
                    "INSERT INTO collections (id, name, ocr_languages, lifecycle, created_at, updated_at) " +
                        "VALUES ('c1', 'Case', 'eng', 'ACTIVE', '$now', '$now')",
                    "INSERT INTO documents (id, collection_id, sha256, media_type, original_filename, " +
                        "original_path, size_bytes, status, created_at, updated_at) VALUES ('d1', 'c1', " +
                        "'${"a".repeat(64)}', 'image/png', 'scan.png', '/tmp/scan.png', 42, 'COMPLETE', " +
                        "'$now', '$now')",
                    "INSERT INTO document_revisions (id, document_id, parent_revision_id, state, " +
                        "provenance, created_at) VALUES ('r1', 'd1', NULL, 'CANDIDATE', 'RESCAN', '$now')",
                ).forEach(statement::execute)
            }
        }
        return database to DocumentRevisionStore(database, ContentStore(database))
    }

    private fun insertPage(database: Database, ordinal: Int, columns: String, values: String) =
        database.transaction { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    "INSERT INTO page_text_revisions (revision_id, ordinal, unit_id, locator, extracted_text, " +
                        "search_text, approval, created_at, $columns) VALUES ('r1', $ordinal, 'unit-$ordinal', " +
                        "'{\"type\":\"pdf_page\",\"page\":1}', 'text', 'text', 'PENDING', '$now', $values)",
                )
            }
        }

    // ---- The record refuses a reference that is not root-confined -----------------------------------

    @Test
    fun `an absolute, parent-relative or root-escaping reference is refused by the record`() {
        listOf(
            "/etc/passwd",
            "/",
            "..",
            "../outside.png",
            "pages/../../outside.png",
            "pages/../page.png",
            ".",
            "./",
            "pages/./page.png",
            "..\\outside.png",
            "\\windows\\absolute.png",
            "C:\\drive\\page.png",
            "pages/\u0000page.png",
        ).forEach { reference ->
            assertFailsWith<IllegalArgumentException>("accepted the reference '$reference'") {
                provenance(reference)
            }
            assertFailsWith<IllegalArgumentException>("accepted '$reference' for a managed copy") {
                provenance(reference, SourceImageRoot.MANAGED_COPY)
            }
        }
    }

    @Test
    fun `an ordinary relative reference is kept as given`() {
        listOf("fingerprint/pages/page-000001.png", "scan.png", "a..b.png", "pages/.hidden.png").forEach { reference ->
            assertEquals(reference, provenance(reference).relativePath)
        }
    }

    // ---- The schema refuses what the record would refuse, so nothing can be written around the store ----

    @Test
    fun `the schema refuses an absolute or root-escaping reference`() {
        val (database, _) = archive()
        database.use {
            listOf("/etc/passwd", "../outside.png", "pages/../../outside.png", "..", ".", "C:\\page.png").forEachIndexed {
                    index, reference ->
                assertFailsWith<SQLException>("the schema accepted the reference '$reference'") {
                    insertPage(
                        database,
                        index,
                        "source_image_root, source_image_relative_path, source_image_sha256, " +
                            "source_image_width, source_image_height, source_image_render_version",
                        "'ARTIFACTS', '${reference.replace("'", "''")}', '${"a".repeat(64)}', 10, 10, 1",
                    )
                }
            }
        }
    }

    @Test
    fun `the schema refuses a row that carries only part of a provenance`() {
        val (database, _) = archive()
        database.use {
            val partial = listOf(
                "source_image_root" to "'ARTIFACTS'",
                "source_image_relative_path" to "'attempt/pages/page-000001.png'",
                "source_image_sha256" to "'${"a".repeat(64)}'",
                "source_image_width" to "10",
                "source_image_height" to "10",
                "source_image_render_version" to "1",
            )
            // Each column alone: a path without a hash, a hash without a path, dimensions without either.
            partial.forEachIndexed { index, (column, value) ->
                assertFailsWith<SQLException>("the schema accepted a row with only $column") {
                    insertPage(database, index, column, value)
                }
            }
            val complete = partial.toMap()
            // A path without its hash, root or rendering version, one column at a time.
            listOf("source_image_root", "source_image_sha256", "source_image_render_version").forEachIndexed { index, left ->
                val kept = complete.filterKeys { column -> column != left && column != "source_image_width" }
                    .filterKeys { column -> column != "source_image_height" }
                assertFailsWith<SQLException>("the schema accepted a provenance missing $left") {
                    insertPage(database, 10 + index, kept.keys.joinToString(", "), kept.values.joinToString(", "))
                }
            }
            // Dimensions are a pair, and they are never a claim about an image that is not named.
            val dimensionless = complete.filterKeys { column ->
                column != "source_image_width" && column != "source_image_height"
            }
            assertFailsWith<SQLException>("the schema accepted a width without a height") {
                insertPage(
                    database,
                    20,
                    (dimensionless.keys + "source_image_width").joinToString(", "),
                    (dimensionless.values + "10").joinToString(", "),
                )
            }
            assertFailsWith<SQLException>("the schema accepted dimensions beside no image") {
                insertPage(database, 21, "source_image_width, source_image_height", "10, 10")
            }
        }
    }

    @Test
    fun `a page with no provenance and a page with a whole one are both accepted`() {
        val (database, revisions) = archive()
        database.use {
            insertPage(database, 0, "source_image_root", "NULL")
            insertPage(
                database,
                1,
                "source_image_root, source_image_relative_path, source_image_sha256, source_image_width, " +
                    "source_image_height, source_image_render_version",
                "'ARTIFACTS', 'attempt/pages/page-000001.png', '${"a".repeat(64)}', 10, 10, 1",
            )
            assertNull(assertNotNull(revisions.page("r1", 0)).sourceImage)
            assertEquals("attempt/pages/page-000001.png", assertNotNull(revisions.page("r1", 1)).sourceImage?.relativePath)
        }
    }

    // ---- A row the schema would refuse must not read back as "no provenance" ------------------------------

    @Test
    fun `a row with only part of a provenance fails loudly instead of reading back as no image`() {
        val (database, revisions) = archive()
        database.use {
            // Only a connection that has switched the constraints off can write such a row; the read still may
            // not take it for an absent one, because that would hide an artifact nobody can reopen.
            database.transaction { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("PRAGMA ignore_check_constraints = ON")
                    statement.execute(
                        "INSERT INTO page_text_revisions (revision_id, ordinal, unit_id, locator, " +
                            "extracted_text, search_text, approval, created_at, source_image_sha256, " +
                            "source_image_width, source_image_height) VALUES ('r1', 0, 'unit-0', " +
                            "'{\"type\":\"pdf_page\",\"page\":1}', 'text', 'text', 'PENDING', '$now', " +
                            "'${"a".repeat(64)}', 10, 10)",
                    )
                    statement.execute(
                        "INSERT INTO page_text_revisions (revision_id, ordinal, unit_id, locator, " +
                            "extracted_text, search_text, approval, created_at, source_image_root, " +
                            "source_image_relative_path) VALUES ('r1', 1, 'unit-1', " +
                            "'{\"type\":\"pdf_page\",\"page\":2}', 'text', 'text', 'PENDING', '$now', " +
                            "'ARTIFACTS', 'attempt/pages/page-000002.png')",
                    )
                    statement.execute(
                        "INSERT INTO page_text_revisions (revision_id, ordinal, unit_id, locator, " +
                            "extracted_text, search_text, approval, created_at, source_image_root, " +
                            "source_image_relative_path, source_image_sha256, source_image_render_version) " +
                            "VALUES ('r1', 2, 'unit-2', '{\"type\":\"pdf_page\",\"page\":3}', 'text', 'text', " +
                            "'PENDING', '$now', 'ARTIFACTS', '/etc/passwd', '${"a".repeat(64)}', 1)",
                    )
                    statement.execute("PRAGMA ignore_check_constraints = OFF")
                }
            }

            (0..2).forEach { ordinal ->
                assertFailsWith<IllegalStateException>("page $ordinal read back as something other than corrupt") {
                    revisions.page("r1", ordinal)
                }
            }
            assertFailsWith<IllegalStateException> { revisions.pages("r1") }
        }
    }

    // ---- The store writes the whole record and nothing else -------------------------------------------

    @Test
    fun `a page staged with a whole provenance reads back exactly`() {
        val (database, revisions) = archive()
        database.use {
            val written = provenance("attempt/pages/page-000004.png")
            revisions.appendPage(
                "r1",
                RevisionPageDraft(
                    ordinal = 3,
                    unitId = ContentUnitId.new(),
                    locator = SourceLocation.PdfPage(4),
                    extractedText = "read",
                    searchText = "read",
                    extractionMethod = ExtractionMethod.OCR,
                    sourceImage = written,
                ),
            )

            assertEquals(written, assertNotNull(revisions.page("r1", 3)).sourceImage)
        }
    }
}
