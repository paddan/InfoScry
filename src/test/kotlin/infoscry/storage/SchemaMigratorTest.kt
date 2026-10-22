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
    fun `a fresh database gets the baseline at version three`() {
        newDatabase().use { database ->
            assertEquals(0, database.userVersion())

            SchemaMigrator(database).migrate()

            assertEquals(3, database.userVersion())
            assertEquals(3, SchemaMigrator.SUPPORTED_VERSION)
            assertEquals(1, count(database, "schema_version"))
        }
    }

    @Test
    fun `migration is idempotent on an already migrated database`() {
        newDatabase().use { database ->
            SchemaMigrator(database).migrate()
            SchemaMigrator(database).migrate()

            assertEquals(3, database.userVersion())
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
                        "collection_prompts",
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

    /**
     * The baseline as version 2 shipped it: every line the baseline marks `-- schema 3`, and every block between
     * `-- schema 3 begin` and `-- schema 3 end`, is version 3's, and a job could not be CORRECTIONS.
     */
    private fun versionTwoBaseline(): String {
        val baseline = requireNotNull(javaClass.classLoader.getResourceAsStream("db/migration/001_baseline.sql"))
            .use { it.readBytes().decodeToString() }
        val withoutBlocks = Regex("(?s)-- schema 3 begin.*?-- schema 3 end\\n").replace(baseline, "")
        val withoutLines = withoutBlocks.lines().filterNot { it.trimEnd().endsWith("-- schema 3") }.joinToString("\n")
        val versionTwo = withoutLines.replace("'SELECTABLE_PDF', 'CORRECTIONS'", "'SELECTABLE_PDF'")
        check(versionTwo != baseline && "CORRECTIONS" !in versionTwo && "reading_extracted_text" !in versionTwo) {
            "the baseline no longer marks its version 3 additions"
        }
        return versionTwo
    }

    /** A database exactly as version 2 created it, with a collection, a job and one of its import items. */
    private fun versionTwoDatabase(): Database {
        val database = newDatabase()
        database.transaction { connection ->
            connection.createStatement().use { statement ->
                splitSqlStatements(versionTwoBaseline()).forEach(statement::execute)
                statement.execute("PRAGMA user_version = 2")
                statement.execute("INSERT INTO schema_version (version, applied_at) VALUES (2, '2026-01-01T00:00:00Z')")
                val now = "2026-01-01T00:00:00Z"
                statement.execute(
                    "INSERT INTO collections (id, name, ocr_languages, lifecycle, created_at, updated_at) " +
                        "VALUES ('c1', 'Case', 'swe', 'ACTIVE', '$now', '$now')",
                )
                statement.execute(
                    "INSERT INTO jobs (id, collection_id, type, state, created_at, updated_at) " +
                        "VALUES ('j1', 'c1', 'IMPORT', 'COMPLETE', '$now', '$now')",
                )
                statement.execute(
                    "INSERT INTO import_items (id, job_id, item_key, source_path, outcome, created_at, updated_at) " +
                        "VALUES ('i1', 'j1', 'k1', '/tmp/a.txt', 'IMPORTED', '$now', '$now')",
                )
            }
        }
        return database
    }

    /** The baseline as version 1 shipped it: a collection's engine could not be NONE. */
    private fun versionOneDatabase(): Database {
        val database = newDatabase()
        val baseline = versionTwoBaseline()
        val versionOne = baseline.replace(
            "CHECK (ocr_engine IN ('TESSERACT', 'SURYA', 'LLM', 'NONE'))",
            "CHECK (ocr_engine IN ('TESSERACT', 'SURYA', 'LLM'))",
        )
        check(versionOne != baseline) { "the baseline no longer carries the version 2 engine constraint" }
        database.transaction { connection ->
            connection.createStatement().use { statement ->
                splitSqlStatements(versionOne).forEach(statement::execute)
                statement.execute("PRAGMA user_version = 1")
                statement.execute("INSERT INTO schema_version (version, applied_at) VALUES (1, '2026-01-01T00:00:00Z')")
            }
        }
        val now = "2026-01-01T00:00:00Z"
        database.transaction { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    "INSERT INTO ocr_profile_revisions (revision_id, profile_id, sequence, provider, model, " +
                        "context_window, max_output_tokens, input_price_per_million, output_price_per_million, " +
                        "created_at) VALUES ('r1', 'p1', 1, 'OPENAI_COMPATIBLE', 'vision', 1000, 100, 0, 0, '$now')",
                )
                statement.execute(
                    "INSERT INTO ocr_profiles (id, name, enabled, current_revision_id, created_at, updated_at) " +
                        "VALUES ('p1', 'Vision', 1, 'r1', '$now', '$now')",
                )
                statement.execute(
                    "INSERT INTO collections (id, name, ocr_languages, lifecycle, created_at, updated_at, " +
                        "ocr_engine, ocr_transcription_profile_id) " +
                        "VALUES ('c1', 'Case', 'swe', 'ACTIVE', '$now', '$now', 'LLM', 'p1')",
                )
                statement.execute(
                    "INSERT INTO collection_prompts (id, collection_id, role, name, body, version, created_at, " +
                        "updated_at) VALUES ('q1', 'c1', 'ASK', 'Mine', 'Answer briefly.', 1, '$now', '$now')",
                )
                statement.execute("UPDATE collections SET ask_prompt_id = 'q1' WHERE id = 'c1'")
                statement.execute(
                    "INSERT INTO documents (id, collection_id, sha256, media_type, original_filename, " +
                        "original_path, size_bytes, status, created_at, updated_at) VALUES ('d1', 'c1', 'abc', " +
                        "'application/pdf', 'a.pdf', '/tmp/a.pdf', 3, 'QUEUED', '$now', '$now')",
                )
            }
        }
        return database
    }

    private fun collectionColumns(database: Database): List<String?> = database.read { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT ocr_engine, ocr_transcription_profile_id, ask_prompt_id, ocr_languages FROM collections " +
                    "WHERE id = 'c1'",
            ).use { rows ->
                rows.next()
                (1..4).map(rows::getString)
            }
        }
    }

    private fun foreignKeyViolations(database: Database): Int = database.read { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA foreign_key_check").use { rows ->
                var violations = 0
                while (rows.next()) violations++
                violations
            }
        }
    }

    private fun setEngineToNone(database: Database) = database.transaction { connection ->
        connection.createStatement().use { statement ->
            statement.execute("UPDATE collections SET ocr_engine = 'NONE', ocr_transcription_profile_id = NULL")
        }
    }

    @Test
    fun `a version 1 database upgrades to version 3 with every row and reference intact`() {
        versionOneDatabase().use { database ->
            val counts = listOf("collections", "collection_prompts", "documents", "ocr_profiles").map { count(database, it) }

            SchemaMigrator(database).migrate()

            assertEquals(3, database.userVersion())
            assertEquals(counts, listOf("collections", "collection_prompts", "documents", "ocr_profiles").map { count(database, it) })
            assertEquals(listOf("LLM", "p1", "q1", "swe"), collectionColumns(database))
            assertEquals(0, foreignKeyViolations(database))
            assertEquals("1", pragma(database, "foreign_keys"), "foreign keys were left switched off")
            assertEquals(3, count(database, "schema_version"))
        }
    }

    @Test
    fun `a foreign key violation already in the database does not block the upgrade`() {
        versionOneDatabase().use { database ->
            database.read { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("PRAGMA foreign_keys = OFF")
                    statement.execute(
                        "INSERT INTO jobs (id, collection_id, type, state, created_at, updated_at) " +
                            "VALUES ('orphan', 'gone', 'IMPORT', 'FAILED', '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z')",
                    )
                    statement.execute("PRAGMA foreign_keys = ON")
                }
            }

            SchemaMigrator(database).migrate()

            assertEquals(3, database.userVersion())
            assertEquals(1, foreignKeyViolations(database), "the upgrade changed the violation it found")
        }
    }

    @Test
    fun `an upgraded database stores NONE as a collection's engine`() {
        versionOneDatabase().use { database ->
            SchemaMigrator(database).migrate()

            setEngineToNone(database)

            assertEquals("NONE", collectionColumns(database).first())
        }
    }

    @Test
    fun `upgrading twice changes nothing`() {
        versionOneDatabase().use { database ->
            SchemaMigrator(database).migrate()
            SchemaMigrator(database).migrate()

            assertEquals(3, database.userVersion())
            assertEquals(3, count(database, "schema_version"))
            assertEquals(listOf("LLM", "p1", "q1", "swe"), collectionColumns(database))
        }
    }

    @Test
    fun `a failure inside the upgrade leaves version 1 intact`() {
        versionOneDatabase().use { database ->
            assertFailsWith<IllegalStateException> {
                SchemaMigrator(database, afterCopy = { error("interrupted") }).migrate()
            }

            assertEquals(1, database.userVersion())
            assertEquals(listOf("LLM", "p1", "q1", "swe"), collectionColumns(database))
            assertFalse("collections_v2" in tableNames(database))
            assertEquals("1", pragma(database, "foreign_keys"), "foreign keys were left switched off")
            assertFailsWith<java.sql.SQLException> { setEngineToNone(database) }

            SchemaMigrator(database).migrate()

            assertEquals(3, database.userVersion())
            setEngineToNone(database)
        }
    }

    @Test
    fun `a new database starts at version 3 and accepts NONE`() {
        newDatabase().use { database ->
            SchemaMigrator(database).migrate()

            assertEquals(3, database.userVersion())
            val now = "2026-01-01T00:00:00Z"
            database.transaction { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        "INSERT INTO collections (id, name, ocr_languages, lifecycle, created_at, updated_at, " +
                            "ocr_engine) VALUES ('c1', 'Case', 'eng', 'ACTIVE', '$now', '$now', 'NONE')",
                    )
                }
            }
        }
    }

    private fun columnNames(database: Database, table: String): List<String> = database.read { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA table_info($table)").use { rows ->
                buildList { while (rows.next()) add(rows.getString("name")) }
            }
        }
    }

    private fun insertCorrectionsJob(database: Database) = database.transaction { connection ->
        connection.createStatement().use { statement ->
            statement.execute(
                "INSERT INTO jobs (id, collection_id, type, state, created_at, updated_at) " +
                    "VALUES ('j2', 'c1', 'CORRECTIONS', 'QUEUED', '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z')",
            )
        }
    }

    @Test
    fun `a version 2 database upgrades to version 3 with its jobs and import items intact`() {
        versionTwoDatabase().use { database ->
            SchemaMigrator(database).migrate()

            assertEquals(3, database.userVersion())
            assertEquals(1, count(database, "jobs"))
            assertEquals(1, count(database, "import_items"))
            assertEquals(0, foreignKeyViolations(database))
            assertEquals("1", pragma(database, "foreign_keys"), "foreign keys were left switched off")
            assertTrue("collection_corrections" in tableNames(database))
            listOf("page_text_revisions", "content_units").forEach { table ->
                assertTrue(columnNames(database, table).containsAll(listOf("reading_extracted_text", "reading_search_text")), table)
            }
            assertTrue(columnNames(database, "document_revisions").containsAll(listOf("corrections_fingerprint", "corrections_applied")))
            insertCorrectionsJob(database)
            assertEquals(2, count(database, "jobs"))
        }
    }

    @Test
    fun `upgrading version 2 twice changes nothing`() {
        versionTwoDatabase().use { database ->
            SchemaMigrator(database).migrate()
            SchemaMigrator(database).migrate()

            assertEquals(3, database.userVersion())
            assertEquals(2, count(database, "schema_version"))
            assertEquals(1, count(database, "jobs"))
        }
    }

    @Test
    fun `a failure inside the version 3 upgrade leaves version 2 intact`() {
        versionTwoDatabase().use { database ->
            assertFailsWith<IllegalStateException> {
                SchemaMigrator(database, afterCopy = { error("interrupted") }).migrate()
            }

            assertEquals(2, database.userVersion())
            assertEquals(1, count(database, "jobs"))
            assertFalse("collection_corrections" in tableNames(database))
            assertFailsWith<java.sql.SQLException> { insertCorrectionsJob(database) }

            SchemaMigrator(database).migrate()

            assertEquals(3, database.userVersion())
            insertCorrectionsJob(database)
        }
    }

    @Test
    fun `a page reading is stored whole or not at all`() {
        newDatabase().use { database ->
            SchemaMigrator(database).migrate()
            val now = "2026-01-01T00:00:00Z"
            database.transaction { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        "INSERT INTO collections (id, name, ocr_languages, lifecycle, created_at, updated_at) " +
                            "VALUES ('c1', 'Case', 'swe', 'ACTIVE', '$now', '$now')",
                    )
                    statement.execute(
                        "INSERT INTO documents (id, collection_id, sha256, media_type, original_filename, " +
                            "original_path, size_bytes, status, created_at, updated_at) VALUES ('d1', 'c1', 'abc', " +
                            "'text/plain', 'a.txt', '/tmp/a.txt', 3, 'QUEUED', '$now', '$now')",
                    )
                }
            }
            assertFailsWith<java.sql.SQLException> {
                database.transaction { connection ->
                    connection.createStatement().use { statement ->
                        statement.execute(
                            "INSERT INTO content_units (id, document_id, ordinal, locator_type, locator, extracted_text, " +
                                "search_text, created_at, updated_at, reading_extracted_text) VALUES ('u1', 'd1', 0, " +
                                "'TEXT', '{}', 'Spinnars', 'Spinnars', '$now', '$now', 'spinners')",
                        )
                    }
                }
            }
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
