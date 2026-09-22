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
    fun `migration is idempotent`() {
        newDatabase().use { database ->
            SchemaMigrator(database).migrate()
            SchemaMigrator(database).migrate()

            assertEquals(5, database.userVersion())
            assertEquals(5, SchemaMigrator.SUPPORTED_VERSION)
        }
    }

    @Test
    fun `migration creates the core tables and seeds the default collection`() {
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
                        "content_units",
                        "chunks",
                        "extraction_checkpoints",
                        "document_extractions",
                        "document_chunking",
                        "llm_profiles",
                        "app_defaults",
                        "prompt_overrides",
                        "conversations",
                        "messages",
                        "model_calls",
                        "citations",
                        "usage_totals",
                    ),
                ),
                "missing tables, found $tables",
            )

            assertEquals(5, count(database, "schema_version"))
            assertEquals(1, count(database, "collections"))
            assertEquals(listOf("Default", "eng", "ACTIVE"), database.read { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery(
                        "SELECT name, ocr_languages, lifecycle FROM collections",
                    ).use { rows ->
                        rows.next()
                        listOf(rows.getString(1), rows.getString(2), rows.getString(3))
                    }
                }
            })
        }
    }

    @Test
    fun `migration refuses a database written by newer code`() {
        newDatabase().use { database ->
            SchemaMigrator(database).migrate()
            database.setUserVersion(6)

            val failure = assertFailsWith<SchemaVersionTooNewException> {
                SchemaMigrator(database).migrate()
            }

            assertEquals(6, failure.found)
            assertEquals(5, failure.supported)
            assertTrue(
                failure.message!!.contains("6") && failure.message!!.contains("5"),
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
            assertEquals(1, count(database, "collections"))
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
