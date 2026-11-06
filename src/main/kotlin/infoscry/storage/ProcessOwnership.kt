package infoscry.storage

import java.lang.management.ManagementFactory
import java.net.InetAddress
import java.sql.Connection

/**
 * The claim that this process is the one InfoScry server or command working against a database.
 *
 * It is a session-level advisory lock held on a connection of its own for as long as the claim is open.
 * Postgres releases it when that connection ends, so a killed process never leaves the database claimed and
 * the next start never waits for stale state. The connection's `application_name` names the holder, so a
 * refused second process can say who has it.
 */
class ProcessOwnership private constructor(private val connection: Connection) : AutoCloseable {

    override fun close() {
        runCatching { connection.close() }
    }

    companion object {
        /** The advisory-lock key of process ownership; distinct from the schema-migration key. */
        internal const val OWNERSHIP_LOCK_KEY = 0x1_5C47_0002L

        /** Claims [database], or throws [DatabaseOwnedElsewhere] when another process holds it. */
        fun acquire(database: Database): ProcessOwnership {
            val connection = database.openDedicatedConnection()
            try {
                connection.createStatement().use {
                    it.execute("SET application_name = '${holderName().replace("'", "''")}'")
                }
                val acquired = connection.prepareStatement("SELECT pg_try_advisory_lock(?)").use { statement ->
                    statement.setLong(1, OWNERSHIP_LOCK_KEY)
                    statement.executeQuery().use { rows -> rows.next() && rows.getBoolean(1) }
                }
                if (!acquired) throw DatabaseOwnedElsewhere(database.config.url, holder(connection))
                return ProcessOwnership(connection)
            } catch (failure: Throwable) {
                runCatching { connection.close() }
                throw failure
            }
        }

        /**
         * Who holds the claim, in the 63 characters Postgres keeps of an ASCII `application_name`: the pid and start minute
         * first, the host last, so a long host name is what gets cut.
         */
        private fun holderName(): String {
            val host = runCatching { InetAddress.getLocalHost().hostName }.getOrDefault("unknown host")
            val started = java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm'Z'")
                .withZone(java.time.ZoneOffset.UTC)
                .format(java.time.Instant.ofEpochMilli(ManagementFactory.getRuntimeMXBean().startTime))
            return "infoscry pid ${ProcessHandle.current().pid()} since $started on $host".take(MAX_NAME_LENGTH)
        }

        private const val MAX_NAME_LENGTH = 63

        private fun holder(connection: Connection): String? =
            connection.prepareStatement(
                "SELECT a.application_name FROM pg_locks l JOIN pg_stat_activity a ON a.pid = l.pid " +
                    "WHERE l.locktype = 'advisory' AND l.granted AND l.database = " +
                    "(SELECT oid FROM pg_database WHERE datname = current_database()) " +
                    "AND ((l.classid::bigint << 32) | l.objid::bigint) = ? LIMIT 1",
            ).use { statement ->
                statement.setLong(1, OWNERSHIP_LOCK_KEY)
                statement.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
            }
    }
}

/** Raised when another InfoScry process already works against the database. */
class DatabaseOwnedElsewhere(val url: String, val holder: String?) : IllegalStateException(
    "Another InfoScry process is already using the database at $url" +
        (holder?.let { " ($it)" } ?: "") +
        ". Stop that process before starting a second one, or use its API through the CLI.",
)
