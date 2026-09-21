package infoscry.storage

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The transaction boundary itself. Product tables are irrelevant here, so these tests use a scratch
 * `probe` table and assert what becomes durable after nesting.
 */
class DatabaseTest {

    private val temporaryDirectories = mutableListOf<Path>()

    private fun newDatabase(): Database {
        val directory = Files.createTempDirectory("infoscry-database")
        temporaryDirectories.add(directory)
        return Database(directory.resolve("state.db"))
    }

    @AfterTest
    fun removeTemporaryDirectories() {
        temporaryDirectories.forEach { it.toFile().deleteRecursively() }
    }

    private fun Database.prepareProbeTable() {
        transaction { connection ->
            connection.execute("CREATE TABLE probe (id INTEGER PRIMARY KEY, note TEXT NOT NULL)")
        }
    }

    private fun insertNote(connection: Connection, note: String) {
        connection.execute("INSERT INTO probe (note) VALUES ('$note')")
    }

    private fun notes(connection: Connection): List<String> =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT note FROM probe ORDER BY id").use { rows ->
                buildList { while (rows.next()) add(rows.getString(1)) }
            }
        }

    private fun Connection.execute(sql: String) {
        createStatement().use { it.execute(sql) }
    }

    private fun Database.notes(): List<String> = read { connection -> notes(connection) }

    @Test
    fun `nested transaction work is visible before and durable after the outer commit`() {
        newDatabase().use { database ->
            database.prepareProbeTable()

            database.transaction { connection ->
                insertNote(connection, "outer")

                database.transaction { inner ->
                    insertNote(inner, "inner")
                    assertEquals(listOf("outer", "inner"), notes(inner))
                }

                assertEquals(listOf("outer", "inner"), notes(connection))
            }

            assertEquals(listOf("outer", "inner"), database.notes())
        }
    }

    @Test
    fun `outer rollback discards an inner transaction that returned normally`() {
        newDatabase().use { database ->
            database.prepareProbeTable()

            val failure = assertFailsWith<IllegalStateException> {
                database.transaction { connection ->
                    insertNote(connection, "outer")
                    database.transaction { inner -> insertNote(inner, "inner") }
                    throw IllegalStateException("outer failed after the inner scope returned")
                }
            }

            assertEquals("outer failed after the inner scope returned", failure.message)
            assertEquals(emptyList(), database.notes())
        }
    }

    @Test
    fun `a caught inner failure keeps the outer work and restores the nesting depth`() {
        newDatabase().use { database ->
            database.prepareProbeTable()

            database.transaction { connection ->
                insertNote(connection, "outer")

                val innerFailure = runCatching {
                    database.transaction { inner ->
                        insertNote(inner, "inner")
                        throw IllegalArgumentException("inner failed")
                    }
                }.exceptionOrNull()

                assertEquals("inner failed", innerFailure?.message)
                assertEquals(listOf("outer"), notes(connection))

                // The depth must be back where it was: a later sibling scope nests again, and the
                // failed savepoint must not be reused.
                database.transaction { inner -> insertNote(inner, "after") }
                assertEquals(listOf("outer", "after"), notes(connection))
            }

            assertEquals(listOf("outer", "after"), database.notes())
        }
    }

    @Test
    fun `three levels of nesting roll back as one unit`() {
        newDatabase().use { database ->
            database.prepareProbeTable()

            assertFailsWith<IllegalStateException> {
                database.transaction { connection ->
                    insertNote(connection, "outer")
                    database.transaction { middle ->
                        insertNote(middle, "middle")
                        database.transaction { inner -> insertNote(inner, "inner") }
                        assertEquals(listOf("outer", "middle", "inner"), notes(middle))
                        throw IllegalStateException("middle failed")
                    }
                }
            }

            assertEquals(emptyList(), database.notes())
        }
    }
}
