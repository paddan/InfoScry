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
            connection.execute("CREATE TABLE probe (id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY, note TEXT NOT NULL)")
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

    @Test
    fun `a read on another thread sees committed state while a transaction is still open`() {
        newDatabase().use { database ->
            database.prepareProbeTable()
            database.transaction { connection -> insertNote(connection, "committed") }

            val seenDuringWrite = database.transaction { connection ->
                insertNote(connection, "pending")
                // The read must neither wait for this transaction nor see its uncommitted row.
                val reader = java.util.concurrent.CompletableFuture.supplyAsync { database.notes() }
                reader.get(5, java.util.concurrent.TimeUnit.SECONDS)
            }

            assertEquals(listOf("committed"), seenDuringWrite)
            assertEquals(listOf("committed", "pending"), database.notes())
        }
    }

    @Test
    fun `a read inside a transaction sees the transaction's own writes`() {
        newDatabase().use { database ->
            database.prepareProbeTable()

            database.transaction { connection ->
                insertNote(connection, "mine")
                assertEquals(listOf("mine"), database.notes())
            }
        }
    }

    @Test
    fun `a second process claim is refused until the first is released`() {
        val directory = Files.createTempDirectory("infoscry-ownership")
        temporaryDirectories.add(directory)
        Database(directory).use { first ->
            Database(directory).use { second ->
                val claim = ProcessOwnership.acquire(first)
                val refused = assertFailsWith<DatabaseOwnedElsewhere> { ProcessOwnership.acquire(second) }
                kotlin.test.assertTrue(
                    refused.message!!.contains("pid ${ProcessHandle.current().pid()}"),
                    "the refusal names the holder: ${refused.message}",
                )

                claim.close()
                ProcessOwnership.acquire(second).close()
                // Claiming again after a release is the normal restart, not an error.
                ProcessOwnership.acquire(first).close()
            }
        }
    }
}
