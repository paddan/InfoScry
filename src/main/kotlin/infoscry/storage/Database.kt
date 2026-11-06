package infoscry.storage

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.sql.Connection
import java.sql.SQLException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Where InfoScry's PostgreSQL database is and how to sign in to it.
 *
 * The password is never part of [toString], so a configuration that ends up in a log or an error message
 * does not carry it.
 */
class DatabaseConfig(val url: String, val user: String, private val password: String?) {
    internal fun password(): String? = password

    override fun toString(): String = "DatabaseConfig(url=$url, user=$user)"

    companion object {
        const val URL_VARIABLE = "INFOSCRY_DATABASE_URL"
        const val USER_VARIABLE = "INFOSCRY_DATABASE_USER"
        const val PASSWORD_VARIABLE = "INFOSCRY_DATABASE_PASSWORD"
        const val DEFAULT_URL = "jdbc:postgresql://127.0.0.1:5432/infoscry"
        const val DEFAULT_USER = "infoscry"

        /** Reads the configuration from the environment, the only place InfoScry takes a secret from. */
        fun fromEnvironment(environment: Map<String, String> = System.getenv()): DatabaseConfig =
            DatabaseConfig(
                url = environment[URL_VARIABLE]?.takeIf { it.isNotBlank() } ?: DEFAULT_URL,
                user = environment[USER_VARIABLE]?.takeIf { it.isNotBlank() } ?: DEFAULT_USER,
                password = environment[PASSWORD_VARIABLE],
            )
    }
}

/**
 * InfoScry's authoritative store: one PostgreSQL database reached through a connection pool.
 *
 * [read] borrows a pooled connection for its block, so reads never wait for a write. [transaction] binds one
 * connection to the calling thread for its outermost block and commits or rolls back on it; while it runs,
 * [read] and nested [transaction] calls on that thread use the same connection, so they see the
 * transaction's own writes.
 *
 * Transactions still run one at a time in the process, as they did when Postgres had one writer: a store that
 * reads a row and writes a decision based on it relies on no other writer in between. Concurrent writers
 * come with the multi-user edit rules, not with this store.
 */
class Database(val config: DatabaseConfig, maximumPoolSize: Int = DEFAULT_POOL_SIZE) : AutoCloseable {

    private val pool: HikariDataSource = HikariDataSource(
        HikariConfig().apply {
            jdbcUrl = config.url
            username = config.user
            password = config.password()
            this.maximumPoolSize = maximumPoolSize
            minimumIdle = 1
            poolName = "infoscry-db"
            isAutoCommit = true
            connectionTimeout = CONNECTION_TIMEOUT_MILLIS
            initializationFailTimeout = CONNECTION_TIMEOUT_MILLIS
        },
    )

    private val writeLock = ReentrantLock()

    /** The connection the current thread's [read] or [transaction] is using, if it is inside one. */
    private val bound = ThreadLocal<Connection?>()

    /**
     * How deep the current thread is inside [transaction]: 0 outside, 1 in the outermost block, more inside
     * savepoints.
     */
    private val transactionDepth = ThreadLocal.withInitial { 0 }

    /** Runs [block] against a connection without opening a transaction. */
    fun <T> read(block: (Connection) -> T): T {
        bound.get()?.let { return block(it) }
        return pool.connection.use { connection ->
            applyActor(connection)
            bound.set(connection)
            try {
                block(connection)
            } finally {
                bound.remove()
            }
        }
    }

    /**
     * Runs [block] in a transaction: commits when it returns, rolls back when it throws. The caller
     * sees its own failure; a failure while rolling back is attached to it as suppressed rather than
     * replacing it.
     *
     * Nesting composes rather than committing early: an inner [transaction] opens a SAVEPOINT inside
     * the outermost transaction, so the whole composition commits or rolls back as one unit. Product
     * steps that compose store methods which each open their own transaction depend on this -- for
     * example collection deletion marks the collection `DELETING`, cancels its jobs and inserts its
     * deletion record as one unit.
     */
    fun <T> transaction(block: (Connection) -> T): T = writeLock.withLock {
        val depth = transactionDepth.get()
        if (depth == 0) outermostTransaction(block) else savepointTransaction(depth, block)
    }

    private fun <T> outermostTransaction(block: (Connection) -> T): T {
        val outer = bound.get()
        val connection = outer ?: pool.connection.also(::applyActor)
        val previousAutoCommit = connection.autoCommit
        connection.autoCommit = false
        bound.set(connection)
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
            runCatching { connection.autoCommit = previousAutoCommit }
            if (outer == null) {
                bound.remove()
                connection.close()
            }
        }
    }

    private fun <T> savepointTransaction(depth: Int, block: (Connection) -> T): T {
        val connection = checkNotNull(bound.get()) { "a nested transaction must run on its outer connection" }
        val savepoint = connection.setSavepoint("infoscry_savepoint_$depth")
        transactionDepth.set(depth + 1)
        try {
            val result = block(connection)
            connection.releaseSavepoint(savepoint)
            return result
        } catch (failure: Throwable) {
            try {
                connection.rollback(savepoint)
                connection.releaseSavepoint(savepoint)
            } catch (rollbackFailure: Exception) {
                failure.addSuppressed(rollbackFailure)
            }
            throw failure
        } finally {
            transactionDepth.set(depth)
        }
    }

    /**
     * The actor each physical connection was last told about, so the setting is sent only when it changes.
     * Keyed by the driver's connection, which outlives the pool's per-checkout proxies.
     */
    private val actorOfConnection = java.util.Collections.synchronizedMap(java.util.WeakHashMap<Any, String>())

    /**
     * Tells [connection] who is acting ([Actor]), as the session setting `infoscry.actor` that the schema's
     * attribution triggers read. An empty value means nobody in person.
     */
    private fun applyActor(connection: Connection) {
        val actor = Actor.current()?.value.orEmpty()
        val key: Any = runCatching { connection.unwrap(org.postgresql.PGConnection::class.java) }.getOrDefault(connection)
        if (actorOfConnection[key] == actor) return
        connection.prepareStatement("SELECT set_config('infoscry.actor', ?, false)").use { statement ->
            statement.setString(1, actor)
            statement.executeQuery().close()
        }
        actorOfConnection[key] = actor
    }

    /** The highest applied schema version, or 0 for a database without the schema. */
    fun schemaVersion(): Int = read { connection ->
        val exists = connection.createStatement().use { statement ->
            statement.executeQuery("SELECT to_regclass('schema_version') IS NOT NULL").use { rows ->
                rows.next()
                rows.getBoolean(1)
            }
        }
        if (!exists) {
            0
        } else {
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT coalesce(max(version), 0) FROM schema_version").use { rows ->
                    rows.next()
                    rows.getInt(1)
                }
            }
        }
    }

    /** Opens a connection outside the pool, for a holder that keeps it for the process lifetime. */
    internal fun openDedicatedConnection(): Connection =
        java.sql.DriverManager.getConnection(config.url, config.user, config.password())

    override fun close() {
        pool.close()
    }

    companion object {
        const val DEFAULT_POOL_SIZE = 10
        private const val CONNECTION_TIMEOUT_MILLIS = 10_000L

        /** SQLSTATE for a unique-constraint violation. */
        const val UNIQUE_VIOLATION = "23505"

        /** SQLSTATE for a foreign-key violation. */
        const val FOREIGN_KEY_VIOLATION = "23503"

        /** Whether [failure], or an exception it wraps, is a unique-constraint violation. */
        fun isUniqueViolation(failure: Throwable): Boolean =
            generateSequence(failure) { it.cause }.any { it is SQLException && it.sqlState == UNIQUE_VIOLATION }

        /** Whether [failure], or an exception it wraps, is a foreign-key violation. */
        fun isForeignKeyViolation(failure: Throwable): Boolean =
            generateSequence(failure) { it.cause }.any { it is SQLException && it.sqlState == FOREIGN_KEY_VIOLATION }
    }
}

/**
 * Timestamps are written by the persistence boundary, so the domain stays free of a clock and of a
 * platform date type. Every instant is an ISO-8601 UTC string, which sorts lexicographically in the
 * same order it occurred.
 *
 * The fraction is always three digits, including for a whole second: the default [Instant.toString]
 * drops a zero fraction, and then `2026-01-01T00:00:00Z` compares greater than
 * `2026-01-01T00:00:00.123Z` because `Z` sorts above `.`. Rows ordered by such a string would put a
 * whole second after its own fractions, which a listing that writes rows in a tight loop does hit.
 */
object Instants {
    fun now(): String = format(Instant.now())

    /** Formats one instant in the fixed-width form [now] writes; a test pins the whole-second case. */
    internal fun format(instant: Instant): String = ISO_MILLIS.format(instant)

    private val ISO_MILLIS: DateTimeFormatter =
        DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)
}
