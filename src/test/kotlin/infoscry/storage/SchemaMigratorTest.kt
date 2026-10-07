package infoscry.storage

import infoscry.domain.ContentUnitId
import infoscry.domain.ExtractionMethod
import infoscry.domain.SourceImageProvenance
import infoscry.domain.SourceImageRoot
import infoscry.domain.SourceLocation
import infoscry.llm.endpointCarriesUserInfo
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SchemaMigratorTest {

    private val temporaryDirectories = mutableListOf<Path>()

    private fun newDatabase(): Database {
        val directory = Files.createTempDirectory("infoscry-schema")
        temporaryDirectories.add(directory)
        return Database(directory.resolve("state.db"))
    }

    @AfterTest
    fun removeTemporaryDirectories() {
        temporaryDirectories.forEach { it.toFile().deleteRecursively() }
    }

    private fun count(database: Database, table: String): Int = database.read { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT count(*) FROM $table").use { rows ->
                rows.next()
                rows.getInt(1)
            }
        }
    }

    private fun tableNames(database: Database): List<String> = database.read { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table'").use { rows ->
                buildList { while (rows.next()) add(rows.getString(1)) }
            }
        }
    }

    /** Whether each conversation is marked, by id, as one whose snapshot migration 020 had to repair. */
    private fun repairedSnapshots(database: Database): Map<String, Boolean> = database.read { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT id, profile_endpoint_repaired FROM conversations").use { rows ->
                buildMap { while (rows.next()) put(rows.getString(1), rows.getInt(2) != 0) }
            }
        }
    }

    private fun pragma(database: Database, name: String): String = database.read { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA $name").use { rows ->
                rows.next()
                rows.getString(1)
            }
        }
    }

    @Test
    fun `migration is idempotent`() {
        newDatabase().use { database ->
            SchemaMigrator(database).migrate()
            SchemaMigrator(database).migrate()

            assertEquals(28, database.userVersion())
            assertEquals(28, SchemaMigrator.SUPPORTED_VERSION)
        }
    }

    @Test
    fun `migration creates the core tables and exposes no collection in a new archive`() {
        newDatabase().use { database ->
            SchemaMigrator(database).migrate()

            val tables = tableNames(database)
            assertTrue(
                tables.containsAll(
                    listOf(
                        "schema_version",
                        "collections",
                        "documents",
                        "jobs",
                        "import_items",
                        "deletion_operations",
                        "document_deletion_targets",
                        "content_units",
                        "chunks",
                        "extraction_checkpoints",
                        "document_extractions",
                        "document_extraction_progress",
                        "document_chunking",
                        "llm_profiles",
                        "ocr_profiles",
                        "ocr_profile_revisions",
                        "app_defaults",
                        "prompt_overrides",
                        "conversations",
                        "messages",
                        "model_calls",
                        "citations",
                        "usage_totals",
                        "tool_calls",
                        "evidence_ledger",
                        "request_eligibility",
                        "request_omissions",
                        "limit_events",
                    ),
                ),
                "missing tables, found $tables",
            )

            assertEquals(28, count(database, "schema_version"))
            assertEquals(0, count(database, "collections"), "a new archive must expose no collection")
        }
    }

    /**
     * Scenario 1 of ticket 02d: provenance is one new nullable column, never a second excerpt
     * column, and the excerpts a legacy archive already stored come back byte for byte.
     * Legacy rows name no revision — unknown provenance is reported, never invented.
     */
    @Test
    fun `the evidence ledger gains a revision column while its stored excerpts survive byte for byte`() {
        newDatabase().use { database ->
            val now = "2026-10-06T07:00:00Z"
            SchemaMigrator(database).migrate(upToVersion = 27)
            database.transaction { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        "INSERT INTO collections (id, name, ocr_languages, lifecycle, created_at, updated_at) " +
                            "VALUES ('c1', 'Case', 'eng', 'ACTIVE', '$now', '$now')",
                    )
                    statement.execute(
                        "INSERT INTO conversations (id, collection_id, mode, profile_provider, profile_model, " +
                            "profile_name, prompt_version, created_at) VALUES " +
                            "('conv1', 'c1', 'INVESTIGATE', 'OPENAI_COMPATIBLE', 'model', 'prof', 3, '$now')",
                    )
                    // One excerpt with the characters escaping rewrites, one with a message_seq: both must
                    // survive the migration untouched.
                    statement.execute(
                        "INSERT INTO evidence_ledger (conversation_id, evidence_id, source_unit_id, locator_json, excerpt) " +
                            "VALUES ('conv1', 'S1', 'unit-1', '{\"type\":\"text_lines\":\"start\":1,\"end\":1}', " +
                            "'a < b & c > d — exact bytes')",
                    )
                    statement.execute(
                        "INSERT INTO evidence_ledger (conversation_id, evidence_id, source_unit_id, locator_json, excerpt, message_seq) " +
                            "VALUES ('conv1', 'S2', 'unit-2', '{}', 'second excerpt', 4)",
                    )
                }
            }

            SchemaMigrator(database).migrate()

            val columns = database.read { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT name FROM pragma_table_info('evidence_ledger')").use { rows ->
                        buildList { while (rows.next()) add(rows.getString(1)) }
                    }
                }
            }
            assertEquals(
                listOf("conversation_id", "evidence_id", "source_unit_id", "locator_json", "excerpt", "message_seq", "revision_id"),
                columns,
                "migration 028 must add exactly one revision column and no further excerpt column, got $columns",
            )

            data class Row(val excerpt: String, val messageSeq: Int?, val revisionId: String?)
            val rows = database.read { connection ->
                connection.prepareStatement(
                    "SELECT excerpt, message_seq, revision_id FROM evidence_ledger ORDER BY evidence_id",
                ).use { statement ->
                    statement.executeQuery().use { results ->
                        buildList {
                            while (results.next()) {
                                // `wasNull` reports on the column read last, so the seq's null-ness has to be
                                // captured before any other column is touched.
                                val seq = results.getInt("message_seq")
                                val seqWasNull = results.wasNull()
                                val excerpt = results.getString("excerpt")
                                val revisionId = results.getString("revision_id")
                                add(
                                    Row(
                                        excerpt = excerpt,
                                        messageSeq = if (seqWasNull) null else seq,
                                        revisionId = revisionId,
                                    ),
                                )
                            }
                        }
                    }
                }
            }
            assertEquals(listOf("a < b & c > d — exact bytes", "second excerpt"), rows.map { it.excerpt })
            assertEquals(listOf(null, 4), rows.map { it.messageSeq })
            assertTrue(
                rows.all { it.revisionId == null },
                "rows written before the migration must name no revision, got ${rows.map { it.revisionId }}",
            )
        }
    }

    /**
     * Scenario 4 of ticket 02d: migration 028 runs, the conversations and evidence it was applied to
     * keep their counts, and a collection delete still cascades through conversations to evidence.
     * (Collection scoping of saved history is pinned by InvestigationRoutesTest's
     * `saved history is readable only through its collection`.)
     */
    @Test
    fun `migration 028 preserves conversations and evidence and collection deletion still cascades`() {
        newDatabase().use { database ->
            val now = "2026-10-06T07:00:00Z"
            SchemaMigrator(database).migrate(upToVersion = 27)
            database.transaction { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        "INSERT INTO collections (id, name, ocr_languages, lifecycle, created_at, updated_at) " +
                            "VALUES ('c1', 'Case', 'eng', 'ACTIVE', '$now', '$now')",
                    )
                    listOf("conv1", "conv2").forEach { conv ->
                        statement.execute(
                            "INSERT INTO conversations (id, collection_id, mode, profile_provider, profile_model, " +
                                "profile_name, prompt_version, created_at) VALUES " +
                                "('$conv', 'c1', 'INVESTIGATE', 'OPENAI_COMPATIBLE', 'model', 'prof', 3, '$now')",
                        )
                    }
                    listOf("'conv1', 'S1'" to "unit-1", "'conv1', 'S2'" to "unit-2", "'conv2', 'S3'" to "unit-3")
                        .forEach { (keys, unit) ->
                            statement.execute(
                                "INSERT INTO evidence_ledger (conversation_id, evidence_id, source_unit_id, locator_json, excerpt) " +
                                    "VALUES ($keys, '$unit', '{}', 'kept excerpt')",
                            )
                        }
                }
            }

            SchemaMigrator(database).migrate()

            assertEquals("28", pragma(database, "user_version"), "migration 028 must have been applied")
            assertEquals(2, count(database, "conversations"), "the migration must not drop conversations")
            assertEquals(3, count(database, "evidence_ledger"), "the migration must not drop evidence")

            // Deletion is still enforced through the migrated schema: a collection delete reaches
            // its conversations and their evidence rows through the same foreign keys as before.
            database.transaction { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("DELETE FROM collections WHERE id = 'c1'")
                }
            }
            assertEquals(0, count(database, "conversations"))
            assertEquals(0, count(database, "evidence_ledger"))
        }
    }

    @Test
    fun `migration refuses a database written by newer code`() {
        newDatabase().use { database ->
            SchemaMigrator(database).migrate()
            val future = SchemaMigrator.SUPPORTED_VERSION + 1
            database.setUserVersion(future)

            val failure = assertFailsWith<SchemaVersionTooNewException> {
                SchemaMigrator(database).migrate()
            }

            assertEquals(future, failure.found)
            assertEquals(SchemaMigrator.SUPPORTED_VERSION, failure.supported)
            assertTrue(
                failure.message!!.contains("$future") && failure.message!!.contains("${SchemaMigrator.SUPPORTED_VERSION}"),
                "message should name both versions, was ${failure.message}",
            )
        }
    }

    @Test
    fun `connections run with foreign keys wal and normal synchronous mode`() {
        newDatabase().use { database ->
            SchemaMigrator(database).migrate()

            assertEquals("1", pragma(database, "foreign_keys"))
            assertEquals("wal", pragma(database, "journal_mode"))
            assertEquals("5000", pragma(database, "busy_timeout"))
            assertEquals("1", pragma(database, "synchronous"))
        }
    }

    @Test
    fun `deleting a collection cascades documents and jobs but keeps deletion records`() {
        newDatabase().use { database ->
            SchemaMigrator(database).migrate()
            val now = "2026-01-01T00:00:00Z"
            database.transaction { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        "INSERT INTO collections (id, name, ocr_languages, lifecycle, created_at, updated_at) " +
                            "VALUES ('c1', 'Case', 'eng', 'ACTIVE', '$now', '$now')",
                    )
                    statement.execute(
                        "INSERT INTO documents (id, collection_id, sha256, media_type, original_filename, " +
                            "original_path, size_bytes, status, created_at, updated_at) VALUES ('d1', 'c1', 'abc', " +
                            "'application/pdf', 'a.pdf', '/tmp/a.pdf', 3, 'QUEUED', '$now', '$now')",
                    )
                    statement.execute(
                        "INSERT INTO jobs (id, type, state, created_at, updated_at) " +
                            "VALUES ('j1', 'IMPORT', 'QUEUED', '$now', '$now')",
                    )
                    statement.execute(
                        "INSERT INTO jobs (id, collection_id, type, state, created_at, updated_at) " +
                            "VALUES ('j2', 'c1', 'IMPORT', 'QUEUED', '$now', '$now')",
                    )
                    statement.execute(
                        "INSERT INTO import_items (id, job_id, item_key, source_path, outcome, created_at, updated_at) " +
                            "VALUES ('i1', 'j1', 'a.pdf', '/tmp/a.pdf', 'PENDING', '$now', '$now')",
                    )
                    statement.execute(
                        "INSERT INTO import_items (id, job_id, item_key, source_path, outcome, created_at, updated_at) " +
                            "VALUES ('i2', 'j2', 'b.pdf', '/tmp/b.pdf', 'PENDING', '$now', '$now')",
                    )
                    statement.execute(
                        "INSERT INTO deletion_operations (id, collection_id, collection_name, trash_basename, " +
                            "managed_originals_existed, phase, created_at, updated_at) VALUES ('op1', 'c1', 'Case', " +
                            "'trash-op1', 1, 'PREPARED', '$now', '$now')",
                    )
                }
            }

            database.transaction { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("DELETE FROM collections WHERE id = 'c1'")
                }
            }

            assertEquals(0, count(database, "documents"))
            assertEquals(0, count(database, "collections"))
            assertEquals(1, count(database, "deletion_operations"))
            assertEquals(1, count(database, "jobs"))
            assertEquals(1, count(database, "import_items"))
            assertEquals("c1", database.read { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT collection_id FROM deletion_operations").use { rows ->
                        rows.next()
                        rows.getString(1)
                    }
                }
            })
        }
    }

    @Test
    fun `a legacy collection gains the OCR defaults and keeps its language`() {
        newDatabase().use { database ->
            val now = "2026-09-30T07:00:00Z"
            SchemaMigrator(database).migrate(upToVersion = 16)
            database.transaction { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        "INSERT INTO collections (id, name, ocr_languages, lifecycle, created_at, updated_at) " +
                            "VALUES ('c1', 'Case', 'deu+eng', 'ACTIVE', '$now', '$now')",
                    )
                }
            }

            SchemaMigrator(database).migrate()

            database.transaction { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery(
                        "SELECT ocr_languages, ocr_engine, ocr_import_mode, ocr_transcription_profile_id, " +
                            "ocr_review_profile_id, ocr_external_page_limit FROM collections WHERE id = 'c1'",
                    ).use { rows ->
                        assertTrue(rows.next())
                        assertEquals("deu+eng", rows.getString("ocr_languages"), "the language an archive had stays")
                        assertEquals("TESSERACT", rows.getString("ocr_engine"))
                        assertEquals("FILL_MISSING", rows.getString("ocr_import_mode"))
                        assertNull(rows.getString("ocr_transcription_profile_id"))
                        assertNull(rows.getString("ocr_review_profile_id"), "no reviewer is selected implicitly")
                        assertEquals(0, rows.getInt("ocr_external_page_limit"))
                    }
                }
            }
        }
    }

    @Test
    fun `the OCR schema refuses values the application would reject`() {
        newDatabase().use { database ->
            SchemaMigrator(database).migrate()
            val now = "2026-09-30T07:00:00Z"

            // Every one of these is a value the store's own validation refuses, so the schema has to
            // refuse it too: nothing may be written around the application's rules.
            val refused = listOf(
                "INSERT INTO collections (id, name, ocr_languages, lifecycle, created_at, updated_at, " +
                    "ocr_engine) VALUES ('c1', 'Case', 'eng', 'ACTIVE', '$now', '$now', 'VISION')",
                "INSERT INTO collections (id, name, ocr_languages, lifecycle, created_at, updated_at, " +
                    "ocr_import_mode) VALUES ('c1', 'Case', 'eng', 'ACTIVE', '$now', '$now', 'IMPROVE_ALWAYS')",
                "INSERT INTO collections (id, name, ocr_languages, lifecycle, created_at, updated_at, " +
                    "ocr_external_page_limit) VALUES ('c1', 'Case', 'eng', 'ACTIVE', '$now', '$now', -1)",
                "INSERT INTO ocr_profile_revisions (revision_id, profile_id, sequence, provider, model, " +
                    "api_key_environment_variable, context_window, max_output_tokens, " +
                    "input_price_per_million, output_price_per_million, created_at) VALUES ('r1', 'p1', 1, " +
                    "'OPENAI_COMPATIBLE', 'model', 'sk-live-not-a-name', 32000, 4096, 0.0, 0.0, '$now')",
                "INSERT INTO ocr_profile_revisions (revision_id, profile_id, sequence, provider, model, " +
                    "context_window, max_output_tokens, input_price_per_million, " +
                    "output_price_per_million, created_at) VALUES ('r1', 'p1', 1, 'GEMINI', 'model', " +
                    "32000, 4096, 0.0, 0.0, '$now')",
                "INSERT INTO ocr_profile_revisions (revision_id, profile_id, sequence, provider, model, " +
                    "context_window, max_output_tokens, input_price_per_million, " +
                    "output_price_per_million, created_at) VALUES ('r1', 'p1', 1, 'OPENAI_COMPATIBLE', " +
                    "'model', 32000, 4096, -1.0, 0.0, '$now')",
                "INSERT INTO ocr_profile_revisions (revision_id, profile_id, sequence, provider, model, " +
                    "context_window, max_output_tokens, input_price_per_million, " +
                    "output_price_per_million, created_at) VALUES ('r1', 'p1', 0, 'OPENAI_COMPATIBLE', " +
                    "'model', 32000, 4096, 0.0, 0.0, '$now')",
            )
            refused.forEach { statement ->
                assertFailsWith<java.sql.SQLException>("the schema must refuse: $statement") {
                    database.transaction { connection ->
                        connection.createStatement().use { it.execute(statement) }
                    }
                }
            }
            assertEquals(0, count(database, "collections"))
            assertEquals(0, count(database, "ocr_profile_revisions"))
        }
    }

    @Test
    fun `a revision page staged before source images were recorded keeps them absent and the archive works`() {
        newDatabase().use { database ->
            val now = "2026-09-30T07:00:00Z"
            // An archive as version 18 left it, with a candidate revision already staged in it.
            SchemaMigrator(database).migrate(upToVersion = 18)
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
                        "INSERT INTO page_text_revisions (revision_id, ordinal, unit_id, locator, " +
                            "extracted_text, search_text, approval, created_at) VALUES ('r1', 0, 'unit-1', " +
                            "'{\"type\":\"pdf_page\",\"page\":1}', 'staged before this schema', " +
                            "'staged before this schema', 'PENDING', '$now')",
                    ).forEach(statement::execute)
                }
            }

            SchemaMigrator(database).migrate()

            val revisions = DocumentRevisionStore(database, ContentStore(database))
            val staged = assertNotNull(revisions.page("r1", 0), "the migrated archive lost its staged page")
            assertEquals("staged before this schema", staged.extractedText)
            assertNull(
                staged.sourceImage,
                "a page staged before source images were recorded was given a source image",
            )
            assertEquals(
                0,
                database.read { connection ->
                    connection.createStatement().use { statement ->
                        statement.executeQuery(
                            "SELECT count(*) FROM page_text_revisions WHERE revision_id = 'r1' AND " +
                                "(source_image_root IS NOT NULL OR source_image_relative_path IS NOT NULL OR " +
                                "source_image_sha256 IS NOT NULL OR source_image_width IS NOT NULL OR " +
                                "source_image_height IS NOT NULL OR source_image_render_version IS NOT NULL)",
                        ).use { rows ->
                            rows.next()
                            rows.getInt(1)
                        }
                    }
                },
                "a migrated row carries a source image column that was never written",
            )

            // And the migrated archive still works: a page staged now names the pixels it was read from.
            revisions.appendPage(
                "r1",
                RevisionPageDraft(
                    ordinal = 1,
                    unitId = ContentUnitId.new(),
                    locator = SourceLocation.PdfPage(2),
                    extractedText = "read from a rendered page",
                    searchText = "read from a rendered page",
                    extractionMethod = ExtractionMethod.OCR,
                    sourceImage = SourceImageProvenance(
                        root = SourceImageRoot.ARTIFACTS,
                        relativePath = "attempt/pages/page-000002.png",
                        sha256 = "b".repeat(64),
                        width = 1200,
                        height = 1600,
                        renderVersion = 1,
                    ),
                ),
            )

            val read = assertNotNull(revisions.page("r1", 1)).sourceImage
            assertEquals(SourceImageRoot.ARTIFACTS, assertNotNull(read).root)
            assertEquals("attempt/pages/page-000002.png", read.relativePath)
            assertEquals("b".repeat(64), read.sha256)
            assertEquals(1200, read.width)
            assertEquals(1600, read.height)
            assertEquals(1, read.renderVersion)
        }
    }

    /**
     * The raw SQL boundary: a half-present core provenance (root/path/hash/render version), a
     * half-measured image (width without height) and a reference that would leave its named root are all
     * refused by the schema itself, while the two shapes the store writes — fully present and entirely
     * absent — still land, on insert and on update alike.
     */
    @Test
    fun `raw sql cannot write half-present or root-escaping source image provenance`() {
        newDatabase().use { database ->
            val now = "2026-09-30T07:00:00Z"
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
                            "provenance, created_at) VALUES ('r1', 'd1', NULL, 'CANDIDATE', 'TEST', '$now')",
                    ).forEach(statement::execute)
                }
            }

            fun pageInsert(ordinal: Int, columns: String = "", values: String = ""): String =
                "INSERT INTO page_text_revisions (revision_id, ordinal, unit_id, locator, extracted_text, " +
                    "search_text${columns.takeIf { it.isNotEmpty() }?.let { part -> ", $part" } ?: ""}, " +
                    "approval, created_at) VALUES ('r1', $ordinal, 'unit-$ordinal', " +
                    "'{\"type\":\"pdf_page\",\"page\":${ordinal + 1}}', 'the reading', 'the reading'" +
                    "${values.takeIf { it.isNotEmpty() }?.let { part -> ", $part" } ?: ""}, 'PENDING', '$now')"

            val hash = "a".repeat(64)
            val refused = listOf(
                // The core present without its hash.
                pageInsert(
                    0,
                    "source_image_root, source_image_relative_path, source_image_render_version",
                    "'ARTIFACTS', 'attempt/pages/page-000001.png', 1",
                ),
                // The hash without the reference it names a file by.
                pageInsert(1, "source_image_root, source_image_sha256, source_image_render_version", "'ARTIFACTS', '$hash', 1"),
                // The reference without the root it resolves against.
                pageInsert(2, "source_image_relative_path, source_image_sha256, source_image_render_version", "'attempt/pages/page-000003.png', '$hash', 1"),
                // The rendering without the artifact it produced.
                pageInsert(3, "source_image_root, source_image_render_version", "'ARTIFACTS', 1"),
                // A width with no height: half a measurement is not a measurement.
                pageInsert(
                    4,
                    "source_image_root, source_image_relative_path, source_image_sha256, source_image_width, source_image_render_version",
                    "'ARTIFACTS', 'attempt/pages/page-000005.png', '$hash', 1200, 1",
                ),
                // An absolute reference: a page image is relative to the root its provenance names.
                pageInsert(
                    5,
                    "source_image_root, source_image_relative_path, source_image_sha256, source_image_render_version",
                    "'ARTIFACTS', '/etc/passwd', '$hash', 1",
                ),
                // A reference that climbs out of its named root.
                pageInsert(
                    6,
                    "source_image_root, source_image_relative_path, source_image_sha256, source_image_render_version",
                    "'ARTIFACTS', '../../outside.png', '$hash', 1",
                ),
                // A reference that cancels its way out of its named root.
                pageInsert(
                    7,
                    "source_image_root, source_image_relative_path, source_image_sha256, source_image_render_version",
                    "'MANAGED_COPY', 'attempt/../../outside.png', '$hash', 1",
                ),
                // A reference that names the root itself rather than a file inside it.
                pageInsert(
                    8,
                    "source_image_root, source_image_relative_path, source_image_sha256, source_image_render_version",
                    "'ARTIFACTS', '.', '$hash', 1",
                ),
            )
            refused.forEach { statement ->
                val failure = assertFailsWith<java.sql.SQLException>("the schema must refuse: $statement") {
                    database.transaction { connection ->
                        connection.createStatement().use { it.execute(statement) }
                    }
                }
                assertTrue(
                    failure.message!!.contains("CHECK constraint failed"),
                    "the refusal must be the provenance constraint, not a syntax error: ${failure.message}",
                )
            }

            // The two shapes a write may take still land: the whole provenance, or none of it.
            database.transaction { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        pageInsert(
                            9,
                            "source_image_root, source_image_relative_path, source_image_sha256, " +
                                "source_image_width, source_image_height, source_image_render_version",
                            "'ARTIFACTS', 'attempt/pages/page-000010.png', '$hash', 1200, 1600, 1",
                        ),
                    )
                    statement.execute(pageInsert(10, "", ""))
                }
            }
            assertEquals(2, count(database, "page_text_revisions"))

            // And an update may not widen a complete provenance into a half-present one either.
            val heightWidened = assertFailsWith<java.sql.SQLException> {
                database.transaction { connection ->
                    connection.createStatement().use { statement ->
                        statement.execute(
                            "UPDATE page_text_revisions SET source_image_height = NULL " +
                                "WHERE revision_id = 'r1' AND ordinal = 9",
                        )
                    }
                }
            }
            assertTrue(
                heightWidened.message!!.contains("CHECK constraint failed"),
                "the refusal must be the provenance constraint: ${heightWidened.message}",
            )
            val escapingWidened = assertFailsWith<java.sql.SQLException> {
                database.transaction { connection ->
                    connection.createStatement().use { statement ->
                        statement.execute(
                            "UPDATE page_text_revisions SET source_image_relative_path = '../moved.png' " +
                                "WHERE revision_id = 'r1' AND ordinal = 9",
                        )
                    }
                }
            }
            assertTrue(
                escapingWidened.message!!.contains("CHECK constraint failed"),
                "the refusal must be the provenance constraint: ${escapingWidened.message}",
            )
        }
    }

    /**
     * An archive as version 19 left it — rows written directly, as the build that stored them could —
     * migrates with its page text intact: an entirely absent provenance stays valid, a complete and
     * confined provenance survives byte for byte under its own root, and a half-present or escaping one
     * is cleared by the migration's audit rather than read back as a silent absence or kept as a
     * reference that names nothing.
     */
    @Test
    fun `a legacy archive's unusable source image provenance is cleared while its pages stay readable`() {
        newDatabase().use { database ->
            val now = "2026-09-30T07:00:00Z"
            SchemaMigrator(database).migrate(upToVersion = 19)
            database.transaction { connection ->
                connection.createStatement().use { statement ->
                    listOf(
                        "INSERT INTO collections (id, name, ocr_languages, lifecycle, created_at, updated_at) " +
                            "VALUES ('c1', 'Case', 'eng', 'ACTIVE', '$now', '$now')",
                        "INSERT INTO documents (id, collection_id, sha256, media_type, original_filename, " +
                            "original_path, size_bytes, status, created_at, updated_at) VALUES ('d1', 'c1', " +
                            "'${"a".repeat(64)}', 'application/pdf', 'scan.pdf', '/tmp/scan.pdf', 42, " +
                            "'COMPLETE', '$now', '$now')",
                        "INSERT INTO document_revisions (id, document_id, parent_revision_id, state, " +
                            "provenance, created_at) VALUES ('r1', 'd1', NULL, 'PUBLISHED', 'LEGACY', '$now')",
                        // A text-layer page: no image was ever observed, and all-absent provenance stays valid.
                        "INSERT INTO page_text_revisions (revision_id, ordinal, unit_id, locator, " +
                            "extracted_text, search_text, approval, created_at) VALUES ('r1', 0, 'unit-0', " +
                            "'{\"type\":\"pdf_page\",\"page\":1}', 'the text layer page', " +
                            "'the text layer page', 'APPROVED', '$now')",
                        // A half-present core provenance a legacy write left behind: root and hash, no
                        // reference — demonstrably unusable, since a hash beside no reference names no file.
                        "INSERT INTO page_text_revisions (revision_id, ordinal, unit_id, locator, " +
                            "extracted_text, search_text, source_image_root, source_image_sha256, " +
                            "source_image_render_version, approval, created_at) VALUES ('r1', 1, 'unit-1', " +
                            "'{\"type\":\"pdf_page\",\"page\":2}', 'the half-present page', " +
                            "'the half-present page', 'ARTIFACTS', '${"b".repeat(64)}', 1, 'APPROVED', '$now')",
                        // A complete provenance whose reference climbs out of its named root: it names a
                        // file no root of this document holds, so it is unusable rather than evidence.
                        "INSERT INTO page_text_revisions (revision_id, ordinal, unit_id, locator, " +
                            "extracted_text, search_text, source_image_root, source_image_relative_path, " +
                            "source_image_sha256, source_image_render_version, approval, created_at) VALUES " +
                            "('r1', 2, 'unit-2', '{\"type\":\"pdf_page\",\"page\":3}', 'the escaping page', " +
                            "'the escaping page', 'ARTIFACTS', '../../outside.png', '${"c".repeat(64)}', 1, " +
                            "'APPROVED', '$now')",
                        // A picture that is the document, read as itself: the managed-copy root.
                        "INSERT INTO page_text_revisions (revision_id, ordinal, unit_id, locator, " +
                            "extracted_text, search_text, source_image_root, source_image_relative_path, " +
                            "source_image_sha256, source_image_width, source_image_height, " +
                            "source_image_render_version, approval, created_at) VALUES ('r1', 3, 'unit-3', " +
                            "'{\"type\":\"image\",\"name\":\"scan.png\"}', 'the as-is picture', 'the as-is picture', " +
                            "'MANAGED_COPY', 'scan.png', '${"d".repeat(64)}', 1200, 1600, 1, 'APPROVED', '$now')",
                        // A page this pipeline rendered: the artifacts root, under its attempt directory.
                        "INSERT INTO page_text_revisions (revision_id, ordinal, unit_id, locator, " +
                            "extracted_text, search_text, source_image_root, source_image_relative_path, " +
                            "source_image_sha256, source_image_width, source_image_height, " +
                            "source_image_render_version, approval, created_at) VALUES ('r1', 4, 'unit-4', " +
                            "'{\"type\":\"pdf_page\",\"page\":5}', 'the rendered page', 'the rendered page', " +
                            "'ARTIFACTS', 'attempt/pages/page-000005.png', '${"e".repeat(64)}', 800, 600, 2, " +
                            "'APPROVED', '$now')",
                    ).forEach(statement::execute)
                }
            }

            SchemaMigrator(database).migrate()

            val revisions = DocumentRevisionStore(database, ContentStore(database))
            // Every page is still readable, with exactly the text the archive held: the audit clears
            // provenance, never a revision and never a page.
            assertEquals(listOf(0, 1, 2, 3, 4), revisions.pages("r1").map { it.ordinal })
            assertEquals(
                listOf(
                    "the text layer page",
                    "the half-present page",
                    "the escaping page",
                    "the as-is picture",
                    "the rendered page",
                ),
                revisions.pages("r1").map { it.extractedText },
                "the migration changed what a page says",
            )

            // The all-absent row stays a valid absence — no image was observed for it.
            assertNull(revisions.page("r1", 0)?.sourceImage)

            // The two unusable rows had their provenance cleared: every column is NULL in the archive
            // itself, so the answer is a recorded fact rather than a read path guessing.
            val cleared = database.read { connection ->
                connection.prepareStatement(
                    "SELECT count(*) FROM page_text_revisions WHERE revision_id = 'r1' AND ordinal IN (1, 2) " +
                        "AND (source_image_root IS NOT NULL OR source_image_relative_path IS NOT NULL OR " +
                        "source_image_sha256 IS NOT NULL OR source_image_width IS NOT NULL OR " +
                        "source_image_height IS NOT NULL OR source_image_render_version IS NOT NULL)",
                ).use { statement ->
                    statement.executeQuery().use { rows ->
                        rows.next()
                        rows.getInt(1)
                    }
                }
            }
            assertEquals(0, cleared, "a demonstrably unusable legacy provenance survived the audit")
            assertNull(revisions.page("r1", 1)?.sourceImage)
            assertNull(revisions.page("r1", 2)?.sourceImage)

            // The two usable rows survive byte for byte, each under the distinct root its reference names.
            val asIs = assertNotNull(revisions.page("r1", 3)?.sourceImage)
            assertEquals(SourceImageRoot.MANAGED_COPY, asIs.root)
            assertEquals("scan.png", asIs.relativePath)
            assertEquals("d".repeat(64), asIs.sha256)
            assertEquals(1200, asIs.width)
            assertEquals(1600, asIs.height)
            assertEquals(1, asIs.renderVersion)
            val derived = assertNotNull(revisions.page("r1", 4)?.sourceImage)
            assertEquals(SourceImageRoot.ARTIFACTS, derived.root)
            assertEquals("attempt/pages/page-000005.png", derived.relativePath)
            assertEquals("e".repeat(64), derived.sha256)
            assertEquals(800, derived.width)
            assertEquals(600, derived.height)
            assertEquals(2, derived.renderVersion)
        }
    }

    /**
     * A malformed row cannot silently read back as absent provenance. The store is the read boundary
     * this build owns, so at the schema version that could still hold such a row — before the
     * constraints and the migration's audit — reading it is an error naming the rule, not a quiet
     * `null` that would report a half-written provenance as "no image was observed".
     */
    @Test
    fun `a malformed source image row cannot read back as absent provenance`() {
        newDatabase().use { database ->
            val now = "2026-09-30T07:00:00Z"
            SchemaMigrator(database).migrate(upToVersion = 19)
            database.transaction { connection ->
                connection.createStatement().use { statement ->
                    listOf(
                        "INSERT INTO collections (id, name, ocr_languages, lifecycle, created_at, updated_at) " +
                            "VALUES ('c1', 'Case', 'eng', 'ACTIVE', '$now', '$now')",
                        "INSERT INTO documents (id, collection_id, sha256, media_type, original_filename, " +
                            "original_path, size_bytes, status, created_at, updated_at) VALUES ('d1', 'c1', " +
                            "'${"a".repeat(64)}', 'application/pdf', 'scan.pdf', '/tmp/scan.pdf', 42, " +
                            "'COMPLETE', '$now', '$now')",
                        "INSERT INTO document_revisions (id, document_id, parent_revision_id, state, " +
                            "provenance, created_at) VALUES ('r1', 'd1', NULL, 'CANDIDATE', 'LEGACY', '$now')",
                        // Root and hash set, reference absent: the shape a half-written row has, which the
                        // 019-era schema admitted because presence was all-or-nothing only in Kotlin.
                        "INSERT INTO page_text_revisions (revision_id, ordinal, unit_id, locator, " +
                            "extracted_text, search_text, source_image_root, source_image_sha256, " +
                            "source_image_render_version, approval, created_at) VALUES ('r1', 0, 'unit-0', " +
                            "'{\"type\":\"pdf_page\",\"page\":1}', 'the malformed row', " +
                            "'the malformed row', 'ARTIFACTS', '${"b".repeat(64)}', 1, 'PENDING', '$now')",
                    ).forEach(statement::execute)
                }
            }

            val revisions = DocumentRevisionStore(database, ContentStore(database))
            val failure = assertFailsWith<IllegalStateException> {
                revisions.page("r1", 0)
            }
            assertTrue(
                failure.message!!.contains("source image"),
                "the refusal should name the provenance rule, was ${failure.message}",
            )
        }
    }

    /**
     * Every non-null value of the four columns a legacy archive could have stored a credentialed endpoint in.
     *
     * The pair is the column's SQL name and the value it holds, so a failure can name the column without
     * printing an address — after this migration none of them carries a credential, and before it one does.
     */
    private fun storedEndpoints(database: Database): List<Pair<String, String>> = database.read { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT 'llm_profiles.endpoint', endpoint FROM llm_profiles WHERE endpoint IS NOT NULL " +
                    "UNION ALL SELECT 'conversations.profile_endpoint', profile_endpoint FROM conversations " +
                    "WHERE profile_endpoint IS NOT NULL " +
                    "UNION ALL SELECT 'model_calls.endpoint', endpoint FROM model_calls " +
                    "WHERE endpoint IS NOT NULL " +
                    "UNION ALL SELECT 'ocr_profile_revisions.endpoint', endpoint FROM ocr_profile_revisions " +
                    "WHERE endpoint IS NOT NULL",
            ).use { rows ->
                buildList { while (rows.next()) add(rows.getString(1) to rows.getString(2)) }
            }
        }
    }

    @Test
    fun `a credential a legacy archive stored in an endpoint is removed and its profile is switched off`() {
        newDatabase().use { database ->
            val now = "2026-09-30T07:00:00Z"
            val secret = "hunter2-not-a-real-credential"
            // An archive as version 19 left it: a profile whose endpoint carries a credential, a conversation
            // and a model call that snapshotted that address, and an OCR profile whose revision reads through
            // one. The rows are written directly because that is how the build that stored them wrote them:
            // today's profile types refuse such a URL, so going through one is not possible any more.
            SchemaMigrator(database).migrate(upToVersion = 19)
            database.transaction { connection ->
                connection.createStatement().use { statement ->
                    listOf(
                        "INSERT INTO collections (id, name, ocr_languages, lifecycle, created_at, updated_at) " +
                            "VALUES ('c1', 'Case', 'eng', 'ACTIVE', '$now', '$now')",
                        "INSERT INTO llm_profiles (id, name, provider, endpoint, model, " +
                            "api_key_environment_variable, context_window, max_output_tokens, " +
                            "supports_tool_calling, input_price_per_million, output_price_per_million, " +
                            "cache_read_price_per_million, enabled, created_at, updated_at) VALUES " +
                            "('legacy-credentialed', 'Legacy credentialed', 'OPENAI_COMPATIBLE', " +
                            "'https://user:$secret@127.0.0.1:11434/v1', 'model', 'MY_SECRET_KEY', 128000, 4096, " +
                            "0, 0.0, 0.0, 0.0, 1, '$now', '$now')",
                        // An authority holding two `@`s: the credential ends at the *last* one, exactly as the
                        // lenient parse these rows are read back through decides it, so the host survives.
                        "INSERT INTO llm_profiles (id, name, provider, endpoint, model, " +
                            "api_key_environment_variable, context_window, max_output_tokens, " +
                            "supports_tool_calling, input_price_per_million, output_price_per_million, " +
                            "cache_read_price_per_million, enabled, created_at, updated_at) VALUES " +
                            "('legacy-two-at-signs', 'Legacy two at signs', 'OPENAI_COMPATIBLE', " +
                            "'https://first@second@host.example.com/v1', 'model', null, 128000, 4096, " +
                            "0, 0.0, 0.0, 0.0, 1, '$now', '$now')",
                        "INSERT INTO llm_profiles (id, name, provider, endpoint, model, " +
                            "api_key_environment_variable, context_window, max_output_tokens, " +
                            "supports_tool_calling, input_price_per_million, output_price_per_million, " +
                            "cache_read_price_per_million, enabled, created_at, updated_at) VALUES " +
                            "('legacy-clean', 'Legacy clean', 'OPENAI_COMPATIBLE', " +
                            "'https://api.example.com/v1', 'model', null, 128000, 4096, " +
                            "0, 0.0, 0.0, 0.0, 1, '$now', '$now')",
                        // An `@` in the *path* is not userinfo: the authority ends at the first `/`, so an
                        // `@` after it belongs to the path and the value must survive byte for byte.
                        "INSERT INTO llm_profiles (id, name, provider, endpoint, model, " +
                            "api_key_environment_variable, context_window, max_output_tokens, " +
                            "supports_tool_calling, input_price_per_million, output_price_per_million, " +
                            "cache_read_price_per_million, enabled, created_at, updated_at) VALUES " +
                            "('legacy-at-in-path', 'Legacy at in path', 'OPENAI_COMPATIBLE', " +
                            "'https://host/a@b/v1', 'model', null, 128000, 4096, " +
                            "0, 0.0, 0.0, 0.0, 1, '$now', '$now')",
                        // And neither is one in the *query*: this address has a `?` before its `/`, so the
                        // authority is `host` and the `@` after the `?` is query text. An authority found by
                        // looking for the first `/` instead would swallow the query, take that `@` for a
                        // credential and rewrite this address into a different host (`https://b/c`).
                        "INSERT INTO llm_profiles (id, name, provider, endpoint, model, " +
                            "api_key_environment_variable, context_window, max_output_tokens, " +
                            "supports_tool_calling, input_price_per_million, output_price_per_million, " +
                            "cache_read_price_per_million, enabled, created_at, updated_at) VALUES " +
                            "('legacy-at-in-query', 'Legacy at in query', 'OPENAI_COMPATIBLE', " +
                            "'https://host?email=a@b/c', 'model', null, 128000, 4096, " +
                            "0, 0.0, 0.0, 0.0, 1, '$now', '$now')",
                        "INSERT INTO conversations (id, collection_id, mode, profile_provider, " +
                            "profile_endpoint, profile_model, profile_name, prompt_version, retrieval_snapshot, " +
                            "created_at) VALUES ('legacy-conversation', 'c1', 'ASK', 'OPENAI_COMPATIBLE', " +
                            "'https://user:$secret@127.0.0.1:11434/v1', 'model', 'Legacy credentialed', 1, " +
                            "'{}', '$now')",
                        // A conversation whose snapshot is repaired while the profile it names is not: 'Legacy clean'
                        // stays enabled, so the only thing that stops this snapshot from dispatching to the address
                        // the migration rewrote is the marker the cleanup leaves on the row.
                        "INSERT INTO conversations (id, collection_id, mode, profile_provider, " +
                            "profile_endpoint, profile_model, profile_name, prompt_version, retrieval_snapshot, " +
                            "created_at) VALUES ('repaired-snapshot', 'c1', 'INVESTIGATE', 'OPENAI_COMPATIBLE', " +
                            "'https://user:$secret@clean.example.com/v1', 'model', 'Legacy clean', 1, " +
                            "'{}', '$now')",
                        "INSERT INTO conversations (id, collection_id, mode, profile_provider, " +
                            "profile_endpoint, profile_model, profile_name, prompt_version, retrieval_snapshot, " +
                            "created_at) VALUES ('clean-conversation', 'c1', 'ASK', 'OPENAI_COMPATIBLE', " +
                            "'https://api.example.com/v1', 'model', 'Legacy clean', 1, " +
                            "'{}', '$now')",
                        "INSERT INTO model_calls (id, conversation_id, provider, endpoint, model, profile_name, " +
                            "prompt_version, requested_at, response_at, status, input_tokens, output_tokens, " +
                            "cache_read_tokens, cost_usd) VALUES ('legacy-call', 'legacy-conversation', " +
                            "'OPENAI_COMPATIBLE', 'https://user:$secret@host.example.com/v1', 'model', " +
                            "'Legacy credentialed', 1, '$now', '$now', 'SUCCEEDED', 1, 1, 0, 0.0)",
                        "INSERT INTO ocr_profile_revisions (revision_id, profile_id, sequence, provider, " +
                            "endpoint, model, api_key_environment_variable, context_window, max_output_tokens, " +
                            "input_price_per_million, output_price_per_million, created_at) VALUES " +
                            "('legacy-revision', 'legacy-ocr', 1, 'OPENAI_COMPATIBLE', " +
                            "'https://user:$secret@vision.example.com/v1', 'vision-model', 'MY_SECRET_KEY', " +
                            "32000, 4096, 0.0, 0.0, '$now')",
                        "INSERT INTO ocr_profile_revisions (revision_id, profile_id, sequence, provider, " +
                            "endpoint, model, context_window, max_output_tokens, input_price_per_million, " +
                            "output_price_per_million, created_at) VALUES ('clean-revision', 'clean-ocr', 1, " +
                            "'OPENAI_COMPATIBLE', 'https://vision.internal.example.com/v1', 'vision-model', " +
                            "32000, 4096, 0.0, 0.0, '$now')",
                        // A profile whose *current* revision is clean but whose older revision carried a
                        // credential: the profile is still stopped, because a job or a collection that
                        // snapshotted the older revision would dispatch through that address.
                        "INSERT INTO ocr_profile_revisions (revision_id, profile_id, sequence, provider, " +
                            "endpoint, model, api_key_environment_variable, context_window, max_output_tokens, " +
                            "input_price_per_million, output_price_per_million, created_at) VALUES " +
                            "('mixed-old-revision', 'mixed-ocr', 1, 'OPENAI_COMPATIBLE', " +
                            "'https://user:$secret@vision-old.example.com/v1', 'vision-model', 'MY_SECRET_KEY', " +
                            "32000, 4096, 0.0, 0.0, '$now')",
                        "INSERT INTO ocr_profile_revisions (revision_id, profile_id, sequence, provider, " +
                            "endpoint, model, context_window, max_output_tokens, input_price_per_million, " +
                            "output_price_per_million, created_at) VALUES ('mixed-current-revision', " +
                            "'mixed-ocr', 2, 'OPENAI_COMPATIBLE', 'https://vision-current.example.com/v1', " +
                            "'vision-model', 32000, 4096, 0.0, 0.0, '$now')",
                        "INSERT INTO ocr_profiles (id, name, enabled, current_revision_id, created_at, " +
                            "updated_at) VALUES ('legacy-ocr', 'Legacy vision', 1, 'legacy-revision', " +
                            "'$now', '$now')",
                        "INSERT INTO ocr_profiles (id, name, enabled, current_revision_id, created_at, " +
                            "updated_at) VALUES ('mixed-ocr', 'Mixed vision', 1, 'mixed-current-revision', " +
                            "'$now', '$now')",
                        "INSERT INTO ocr_profiles (id, name, enabled, current_revision_id, created_at, " +
                            "updated_at) VALUES ('clean-ocr', 'Clean vision', 1, 'clean-revision', " +
                            "'$now', '$now')",
                    ).forEach(statement::execute)
                }
            }

            SchemaMigrator(database).migrate()

            // Nothing in the archive still carries the credential, in any of the four columns: the snapshots
            // are cleaned like the profile's own address, and every repaired value keeps what was never
            // secret — the scheme, host, port, path, query and fragment.
            val endpoints = storedEndpoints(database)
            assertEquals(13, endpoints.size, "no endpoint was dropped, only repaired: ${endpoints.map { it.first }}")
            // The two addresses whose `@` is not a credential are byte-identical after the migration, not
            // merely still valid: a rewrite of either would have changed which host is named.
            assertTrue(endpoints.contains("llm_profiles.endpoint" to "https://host/a@b/v1"))
            assertTrue(endpoints.contains("llm_profiles.endpoint" to "https://host?email=a@b/c"))
            assertTrue(endpoints.contains("ocr_profile_revisions.endpoint" to "https://vision-current.example.com/v1"))
            assertEquals(
                emptyList(),
                endpoints.filter { (_, value) -> endpointCarriesUserInfo(value) }.map { it.first },
                "userinfo survives in the columns that carried it",
            )
            assertEquals(
                emptyList(),
                endpoints.filter { (_, value) -> value.contains(secret) }.map { it.first },
                "the stored credential survives, so the archive still holds what this migration removed",
            )
            assertTrue(endpoints.contains("conversations.profile_endpoint" to "https://127.0.0.1:11434/v1"))
            assertTrue(endpoints.contains("model_calls.endpoint" to "https://host.example.com/v1"))

            assertFalse(
                tableNames(database).contains("endpoint_userinfo_repair"),
                "the repair plan is not left behind in the finished schema",
            )

            // The repair is invisible in the cleaned address — there is no longer a credential in it for the read
            // path to find — so the row records the fact itself, and only for the rows the cleanup touched.
            assertEquals(
                mapOf("legacy-conversation" to true, "repaired-snapshot" to true, "clean-conversation" to false),
                repairedSnapshots(database),
                "exactly the conversations whose stored address was repaired are marked repaired",
            )
            // And the read path believes the marker: 'Legacy clean' was never repaired and is still enabled, yet
            // the conversation that locked the repaired address reopens as a switched-off profile, so a continue
            // through it is refused instead of dispatching to an address the migration chose for somebody.
            assertFalse(
                assertNotNull(LlmStore(database).loadInvestigateHistory("repaired-snapshot")).profile.enabled,
                "a migrated snapshot reopens switched off even when its own profile row is clean and enabled",
            )

            // And what the credential broke was reading: both profile listings load the archived rows, and the
            // address each of them now reports is the one that was stored without its credential.
            val profiles = LlmStore(database).list().associateBy { profile -> profile.name }
            assertEquals(5, profiles.size, "a legacy profile is listed, never skipped")
            assertEquals("https://127.0.0.1:11434/v1", profiles.getValue("Legacy credentialed").endpoint)
            assertEquals("https://host.example.com/v1", profiles.getValue("Legacy two at signs").endpoint)
            assertFalse(
                profiles.getValue("Legacy credentialed").enabled,
                "a repaired address is not dispatched to until a person reviews it",
            )
            assertFalse(profiles.getValue("Legacy two at signs").enabled, "every repaired profile is switched off")
            assertEquals("https://api.example.com/v1", profiles.getValue("Legacy clean").endpoint)
            assertTrue(
                profiles.getValue("Legacy clean").enabled,
                "a profile whose address was never repaired keeps its switch",
            )
            // An `@` in the path or in the query is not a credential, so nothing was repaired for these two:
            // the address is the one that was stored and the profile is not switched off.
            assertEquals("https://host/a@b/v1", profiles.getValue("Legacy at in path").endpoint)
            assertTrue(
                profiles.getValue("Legacy at in path").enabled,
                "an `@` in the path is not a credential, so no profile is stopped for it",
            )
            assertEquals("https://host?email=a@b/c", profiles.getValue("Legacy at in query").endpoint)
            assertTrue(
                profiles.getValue("Legacy at in query").enabled,
                "an `@` in the query is not a credential, so no profile is stopped for it",
            )

            val ocrProfiles = OcrProfileStore(database).list().associateBy { profile -> profile.name }
            assertEquals("https://vision.example.com/v1", ocrProfiles.getValue("Legacy vision").revision.endpoint)
            assertFalse(ocrProfiles.getValue("Legacy vision").enabled, "the owner of a repaired revision is stopped")
            assertEquals("https://vision.internal.example.com/v1", ocrProfiles.getValue("Clean vision").revision.endpoint)
            assertTrue(ocrProfiles.getValue("Clean vision").enabled, "an untouched revision keeps its profile usable")

            // A clean current revision does not save a profile that owns a repaired older one: a snapshot
            // could still dispatch through the older address, so the profile is stopped and every revision
            // the migration touched has lost its credential while the current one is left exactly as stored.
            assertFalse(
                ocrProfiles.getValue("Mixed vision").enabled,
                "a profile owning any repaired revision is stopped, not only one pointing at it",
            )
            assertEquals(
                "https://vision-current.example.com/v1",
                ocrProfiles.getValue("Mixed vision").revision.endpoint,
                "the current revision of a stopped profile is not rewritten",
            )
            assertEquals(
                "https://vision-old.example.com/v1",
                assertNotNull(OcrProfileStore(database).findRevision("mixed-old-revision")).endpoint,
                "the older revision's credential is gone",
            )
        }
    }

    @Test
    fun `an OCR profile cannot reference a revision its own row does not own`() {
        newDatabase().use { database ->
            SchemaMigrator(database).migrate()
            val now = "2026-09-30T07:00:00Z"

            val failure = assertFailsWith<java.sql.SQLException> {
                database.transaction { connection ->
                    connection.createStatement().use { statement ->
                        statement.execute(
                            "INSERT INTO ocr_profiles (id, name, enabled, current_revision_id, created_at, " +
                                "updated_at) VALUES ('p1', 'Vision', 1, 'missing-revision', '$now', '$now')",
                        )
                    }
                }
            }

            // The profile's current revision is a real row or the transaction does not commit: a profile
            // that pointed at nothing would fail later, when a job tried to read its model.
            assertTrue(failure.message!!.contains("FOREIGN KEY"), "was ${failure.message}")
            assertEquals(0, count(database, "ocr_profiles"))
        }
    }

    /** Migration 028 is the evidence revision provenance column; the schema version moves with it. */
    @Test
    fun `sql splitting keeps quoted semicolons and drops comments`() {
        val script = """
            -- the default collection; inserted once
            CREATE TABLE a (name TEXT NOT NULL DEFAULT 'x;y');
            CREATE TABLE b (
                id TEXT NOT NULL -- trailing comment; still a comment
            );
        """.trimIndent()

        val statements = splitSqlStatements(script)

        assertEquals(2, statements.size)
        assertEquals("CREATE TABLE a (name TEXT NOT NULL DEFAULT 'x;y')", statements[0])
        assertTrue(statements[1].startsWith("CREATE TABLE b ("), "was ${statements[1]}")
        assertTrue(statements[1].contains("id TEXT NOT NULL"), "was ${statements[1]}")
        assertTrue(statements[1].endsWith(")"), "was ${statements[1]}")
        assertFalse(statements.any { it.contains("--") }, "comments must be stripped")
    }
}
