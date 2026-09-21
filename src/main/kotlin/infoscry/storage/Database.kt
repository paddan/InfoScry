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

    /**
     * How deep the current thread is inside [transaction]. The single connection is only reachable
     * while holding [lock], so only the thread that owns the lock can be inside a transaction scope,
     * which makes a thread-local depth the exact nesting state.
     */
    private val transactionDepth = ThreadLocal.withInitial { 0 }

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
     *
     * Nesting composes rather than committing early: an inner [transaction] opens a SAVEPOINT inside
     * the outermost transaction, so the whole composition commits or rolls back as one unit. Product
     * steps that compose store methods which each open their own transaction depend on this -- for
     * example collection deletion ("marks the collection `DELETING`, cancels its jobs, and inserts
     * `PREPARED`") and per-unit import commits.
     */
    fun <T> transaction(block: (Connection) -> T): T = lock.withLock {
        val depth = transactionDepth.get()
        if (depth == 0) outermostTransaction(block) else savepointTransaction(depth, block)
    }

    private fun <T> outermostTransaction(block: (Connection) -> T): T {
        val previousAutoCommit = connection.autoCommit
        connection.autoCommit = false
        transactionDepth.set(1)
        try {
            val result = block(connection)
            connection.commit()
            return result
        } catch (failure: Throwable) {
            try {
                connection.rollback()
            } catch (rollbackFailure: Exception) {
                failure.addSuppressed(rollbackFailure)
            }
            throw failure
        } finally {
            transactionDepth.set(0)
            connection.autoCommit = previousAutoCommit
        }
    }

    private fun <T> savepointTransaction(depth: Int, block: (Connection) -> T): T {
        val savepoint = "infoscry_savepoint_$depth"
        execute("SAVEPOINT $savepoint")
        transactionDepth.set(depth + 1)
        try {
            val result = block(connection)
            execute("RELEASE SAVEPOINT $savepoint")
            return result
        } catch (failure: Throwable) {
            try {
                execute("ROLLBACK TO SAVEPOINT $savepoint")
                execute("RELEASE SAVEPOINT $savepoint")
            } catch (rollbackFailure: Exception) {
                failure.addSuppressed(rollbackFailure)
            }
            throw failure
        } finally {
            transactionDepth.set(depth)
        }
    }

    private fun execute(sql: String) {
        connection.createStatement().use { it.execute(sql) }
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
