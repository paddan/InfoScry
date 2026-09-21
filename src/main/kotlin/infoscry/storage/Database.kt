package infoscry.storage

import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The authoritative SQLite store for one InfoScry data directory.
 *
 * One process owns one database file, so this class owns a single JDBC connection behind a lock:
 * SQLite serializes writers anyway, and keeping the connection inside the JVM turns the write lock
 * into a JVM lock instead of a file lock. Concurrent callers queue instead of failing with
 * `SQLITE_BUSY`.
 *
 * Every connection is opened with the pragmas the product depends on:
 * `foreign_keys = ON` (off by default in SQLite, and the cascades in the schema rely on it),
 * `journal_mode = WAL` so readers do not block the writer, `busy_timeout` so a competing process
 * waits instead of failing, and `synchronous = NORMAL`, which is the durable setting appropriate to
 * WAL for a local application.
 */
class Database(val path: Path) : AutoCloseable {

    private val lock = ReentrantLock()

    internal val connection: Connection =
        DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}")

    init {
        connection.createStatement().use { statement ->
            statement.execute("PRAGMA foreign_keys = ON")
            statement.execute("PRAGMA busy_timeout = $BUSY_TIMEOUT_MILLIS")
            statement.execute("PRAGMA journal_mode = WAL")
            statement.execute("PRAGMA synchronous = NORMAL")
        }
    }

    /** Runs [block] against the connection without opening a transaction. */
    fun <T> read(block: (Connection) -> T): T = lock.withLock { block(connection) }

    /**
     * Runs [block] in a transaction: commits when it returns, rolls back when it throws. The caller
     * sees its own failure; a failure while rolling back is attached to it as suppressed rather than
     * replacing it.
     */
    fun <T> transaction(block: (Connection) -> T): T = lock.withLock {
        val previousAutoCommit = connection.autoCommit
        connection.autoCommit = false
        try {
            val result = block(connection)
            connection.commit()
            result
        } catch (failure: Throwable) {
            try {
                connection.rollback()
            } catch (rollbackFailure: Exception) {
                failure.addSuppressed(rollbackFailure)
            }
            throw failure
        } finally {
            connection.autoCommit = previousAutoCommit
        }
    }

    /** The schema version SQLite records in the database header. */
    fun userVersion(): Int = read { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA user_version").use { rows ->
                rows.next()
                rows.getInt(1)
            }
        }
    }

    /** Writes `PRAGMA user_version`, which SQLite does not accept as a bound parameter. */
    fun setUserVersion(version: Int) {
        require(version >= 0) { "schema version must not be negative, was $version" }
        read { connection ->
            connection.createStatement().use { statement ->
                statement.execute("PRAGMA user_version = $version")
            }
        }
    }

    override fun close() {
        lock.withLock { connection.close() }
    }

    companion object {
        const val BUSY_TIMEOUT_MILLIS = 5_000
    }
}

/**
 * Timestamps are written by the persistence boundary, so the domain stays free of a clock and of a
 * platform date type. Every instant is an ISO-8601 UTC string, which sorts lexicographically in the
 * same order it occurred.
 */
object Instants {
    fun now(): String = Instant.now().toString()
}
