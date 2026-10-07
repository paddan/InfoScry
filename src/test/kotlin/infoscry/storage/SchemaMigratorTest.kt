package infoscry.storage

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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

    private fun pragma(database: Database, name: String): String = database.read { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA $name").use { rows ->
                rows.next()
                rows.getString(1)
            }
        }
    }

    @Test
    fun `a fresh database gets the baseline at version one`() {
        newDatabase().use { database ->
            assertEquals(0, database.userVersion())

            SchemaMigrator(database).migrate()

            assertEquals(1, database.userVersion())
            assertEquals(1, SchemaMigrator.SUPPORTED_VERSION)
            assertEquals(1, count(database, "schema_version"))
        }
    }

    @Test
    fun `migration is idempotent on an already migrated database`() {
        newDatabase().use { database ->
            SchemaMigrator(database).migrate()
            SchemaMigrator(database).migrate()

            assertEquals(1, database.userVersion())
            assertEquals(1, count(database, "schema_version"), "the baseline must not be applied twice")
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

            assertEquals(0, count(database, "collections"), "a new archive must expose no collection")
        }
    }

    @Test
    fun `the evidence ledger holds its excerpt and revision and the resolver lookup is indexed`() {
        newDatabase().use { database ->
            SchemaMigrator(database).migrate()

            val columns = database.read { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("PRAGMA table_info(evidence_ledger)").use { rows ->
                        buildList { while (rows.next()) add(rows.getString("name")) }
                    }
                }
            }
            assertTrue(columns.containsAll(listOf("excerpt", "revision_id")), "ledger provenance columns, found $columns")

            val plan = database.read { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery(
                        "EXPLAIN QUERY PLAN SELECT h.document_id FROM page_text_revisions p " +
                            "JOIN document_revisions h ON h.id = p.revision_id WHERE p.unit_id = 'unit' LIMIT 1",
                    ).use { rows -> buildList { while (rows.next()) add(rows.getString("detail")) }.joinToString("; ") }
                }
            }
            assertTrue(plan.contains("page_text_revisions_unit"), "the citation-to-document resolver must not scan every page: $plan")
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
    fun `the page table refuses a reference outside its root and a document owns its pages`() {
        newDatabase().use { database ->
            SchemaMigrator(database).migrate()
            val now = "2026-10-07T07:00:00Z"
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
                            "extracted_text, search_text, approval, created_at, source_image_root, " +
                            "source_image_relative_path, source_image_sha256, source_image_render_version) " +
                            "VALUES ('r1', 0, 'unit-0', '{\"type\":\"pdf_page\",\"page\":1}', 't', 't', " +
                            "'PENDING', '$now', 'ARTIFACTS', 'fp/pages/page-000001.png', '${"b".repeat(64)}', 1)",
                    ).forEach(statement::execute)
                }
            }

            assertFailsWith<java.sql.SQLException> {
                database.transaction { connection ->
                    connection.createStatement().use { statement ->
                        statement.execute(
                            "INSERT INTO page_text_revisions (revision_id, ordinal, unit_id, locator, " +
                                "extracted_text, search_text, approval, created_at, source_image_root, " +
                                "source_image_relative_path, source_image_sha256, source_image_render_version) " +
                                "VALUES ('r1', 1, 'unit-1', '{\"type\":\"pdf_page\",\"page\":2}', 't', 't', " +
                                "'PENDING', '$now', 'ARTIFACTS', '/abs/page.png', '${"d".repeat(64)}', 1)",
                        )
                    }
                }
            }
            assertEquals(1, count(database, "page_text_revisions"))

            database.transaction { connection ->
                connection.createStatement().use { statement -> statement.execute("DELETE FROM documents") }
            }
            assertEquals(0, count(database, "page_text_revisions"), "deleting a document must remove its pages")
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
