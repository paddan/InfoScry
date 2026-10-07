package infoscry.storage

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The retirement of the automatic Default collection: which archives lose it and which keep it.
 *
 * Migration 013 removes the row migration 001 seeded, but only while it is still exactly that seed.
 * The state it reads is gone once the migration has run, so each case first builds an archive as the
 * previous build left it (`migrate(upToVersion = 12)`, which runs the seed), inserts the state the
 * case is about, and then migrates the rest of the way. A cleanup that removes used material is the
 * expensive bug here, so the preserved cases assert the collection is still there and not merely that
 * the row was recreated.
 */
class DefaultRetirementTest {

    private val temporaryDirectories = mutableListOf<Path>()

    private val now = "2026-09-27T07:00:00Z"

    @AfterTest
    fun removeTemporaryDirectories() {
        temporaryDirectories.forEach { it.toFile().deleteRecursively() }
    }

    private fun newDatabase(): Database {
        val directory = Files.createTempDirectory("infoscry-default-retirement")
        temporaryDirectories.add(directory)
        return Database(directory.resolve("state.db"))
    }

    /** An archive as a build before this change left it: the seed row exists and 013 has not run. */
    private fun legacyArchive(): Database = newDatabase().also {
        SchemaMigrator(it).migrate(upToVersion = 12)
    }

    private fun collectionIds(database: Database): List<String> = database.read { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT id FROM collections ORDER BY id").use { rows ->
                buildList { while (rows.next()) add(rows.getString(1)) }
            }
        }
    }

    private fun collectionNames(database: Database): Map<String, String> = database.read { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT id, name FROM collections").use { rows ->
                buildMap { while (rows.next()) put(rows.getString(1), rows.getString(2)) }
            }
        }
    }

    private fun insertCollection(database: Database, id: String, name: String) = database.transaction { connection ->
        connection.createStatement().use { statement ->
            statement.execute(
                "INSERT INTO collections (id, name, ocr_languages, lifecycle, created_at, updated_at) " +
                    "VALUES ('$id', '$name', 'eng', 'ACTIVE', '$now', '$now')",
            )
        }
    }

    private fun insertDocument(database: Database, id: String, collectionId: String) = database.transaction { connection ->
        connection.createStatement().use { statement ->
            statement.execute(
                "INSERT INTO documents (id, collection_id, sha256, media_type, original_filename, " +
                    "original_path, size_bytes, status, created_at, updated_at) " +
                    "VALUES ('$id', '$collectionId', '$id-sha', 'application/pdf', 'a.pdf', " +
                    "'/tmp/a.pdf', 3, 'QUEUED', '$now', '$now')",
            )
        }
    }

    private fun insertJob(database: Database, id: String, collectionId: String) = database.transaction { connection ->
        connection.createStatement().use { statement ->
            statement.execute(
                "INSERT INTO jobs (id, collection_id, type, state, created_at, updated_at) " +
                    "VALUES ('$id', '$collectionId', 'IMPORT', 'QUEUED', '$now', '$now')",
            )
        }
    }

    private fun insertConversation(database: Database, id: String, collectionId: String) = database.transaction { connection ->
        connection.createStatement().use { statement ->
            statement.execute(
                "INSERT INTO conversations (id, collection_id, mode, profile_provider, profile_model, " +
                    "profile_name, prompt_version, created_at) VALUES ('$id', '$collectionId', 'ASK', " +
                    "'openai-compatible', 'model', 'profile', 1, '$now')",
            )
        }
    }

    private fun insertDeletion(database: Database, id: String, collectionId: String, phase: String) =
        database.transaction { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    "INSERT INTO deletion_operations (id, collection_id, collection_name, trash_basename, " +
                        "managed_originals_existed, phase, created_at, updated_at) VALUES ('$id', " +
                        "'$collectionId', 'Default', 'trash-$id', 0, '$phase', '$now', '$now')",
                )
            }
        }

    /** Runs migration 013's own statement again, the way a repeated or re-applied cleanup would. */
    private fun reapplyRetirement(database: Database) {
        val script = javaClass.classLoader
            .getResourceAsStream("db/migration/013_retire_automatic_default.sql")!!
            .use { it.readBytes().decodeToString() }
        database.transaction { connection ->
            connection.createStatement().use { statement ->
                splitSqlStatements(script).forEach { statement.execute(it) }
            }
        }
    }

    @Test
    fun `a fresh archive initializes without a default collection`() {
        newDatabase().use { database ->
            SchemaMigrator(database).migrate()

            assertEquals(28, SchemaMigrator.SUPPORTED_VERSION)
            assertEquals(28, database.userVersion())
            assertEquals(emptyList(), collectionIds(database), "a new archive must expose no collection")
        }
    }

    @Test
    fun `an empty legacy default is removed when the archive is opened`() {
        legacyArchive().use { database ->
            assertEquals(listOf("default"), collectionIds(database), "the old build seeded Default")

            SchemaMigrator(database).migrate()

            assertEquals(emptyList(), collectionIds(database))
        }
    }

    @Test
    fun `a populated legacy default is preserved`() {
        legacyArchive().use { database ->
            insertDocument(database, "doc-1", "default")

            SchemaMigrator(database).migrate()

            assertEquals(listOf("default"), collectionIds(database))
        }
    }

    @Test
    fun `a renamed legacy default is preserved`() {
        legacyArchive().use { database ->
            database.transaction { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("UPDATE collections SET name = 'Everything' WHERE id = 'default'")
                }
            }

            SchemaMigrator(database).migrate()

            assertEquals(listOf("default"), collectionIds(database))
            assertEquals("Everything", collectionNames(database)["default"])
        }
    }

    @Test
    fun `a legacy default that owns a job is preserved`() {
        legacyArchive().use { database ->
            insertJob(database, "job-1", "default")

            SchemaMigrator(database).migrate()

            assertEquals(listOf("default"), collectionIds(database))
        }
    }

    @Test
    fun `a legacy default that owns a conversation is preserved`() {
        legacyArchive().use { database ->
            insertConversation(database, "conversation-1", "default")

            SchemaMigrator(database).migrate()

            assertEquals(listOf("default"), collectionIds(database))
        }
    }

    @Test
    fun `a legacy default with an unfinished deletion is preserved`() {
        legacyArchive().use { database ->
            insertDeletion(database, "deletion-1", "default", "PREPARED")

            SchemaMigrator(database).migrate()

            assertEquals(listOf("default"), collectionIds(database))
        }
    }

    @Test
    fun `a user-created collection named Default is never mistaken for the seed`() {
        legacyArchive().use { database ->
            // The person removed the automatic collection and made their own under the same name.
            database.transaction { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("DELETE FROM collections WHERE id = 'default'")
                }
            }
            insertCollection(database, "a-generated-id", "Default")

            SchemaMigrator(database).migrate()

            assertEquals(listOf("a-generated-id"), collectionIds(database))
            assertEquals(mapOf("a-generated-id" to "Default"), collectionNames(database))
        }
    }

    @Test
    fun `reopening an archive and reapplying the cleanup removes nothing more`() {
        legacyArchive().use { database ->
            SchemaMigrator(database).migrate()
            // A person creates a collection named Default of their own after the seed was retired.
            insertCollection(database, "a-generated-id", "Default")

            // A restart migrates again, and a repeated cleanup runs its own statement again.
            SchemaMigrator(database).migrate()
            reapplyRetirement(database)

            assertEquals(listOf("a-generated-id"), collectionIds(database))
            assertTrue(collectionNames(database).containsKey("a-generated-id"))
        }
    }
}
