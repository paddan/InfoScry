package infoscry.storage

/**
 * A migration's version is newer than this build understands. Raising it means an older InfoScry
 * would silently write against a schema it does not know, so migration stops instead.
 */
class SchemaVersionTooNewException(val found: Int, val supported: Int) : IllegalStateException(
    "the database schema is version $found but this InfoScry build supports version $supported; " +
        "upgrade InfoScry or restore a database created by this version",
)

/**
 * Applies the numbered migrations under `db/migration/` in ascending order.
 *
 * Migrations are forward-only and idempotent: each one runs inside a transaction that also records
 * `PRAGMA user_version`, so a crash leaves either the previous version or the new one. The list is
 * explicit rather than discovered by scanning the classpath, because a jar cannot be scanned
 * reliably and an unordered migration is a data-loss bug.
 */
class SchemaMigrator(private val database: Database) {

    fun migrate() {
        val currentVersion = database.userVersion()
        if (currentVersion > SUPPORTED_VERSION) {
            throw SchemaVersionTooNewException(found = currentVersion, supported = SUPPORTED_VERSION)
        }

        MIGRATIONS
            .filter { it.version > currentVersion }
            .sortedBy { it.version }
            .forEach(::apply)
    }

    private fun apply(migration: Migration) {
        if (migration.foreignKeysOff) {
            applyWithForeignKeysOff(migration)
        } else {
            applyWithForeignKeysOn(migration)
        }
    }

    /**
     * Runs a migration that must not be checked against foreign keys.
     *
     * Rebuilding a referenced table — the only way SQLite can widen a CHECK constraint — needs the
     * pragma off while the rows are copied, and a `PRAGMA foreign_keys` assignment is a no-op inside
     * a transaction, so the pragma is set on the connection before the migration's own transaction
     * opens and restored after it closes. The copy runs in one transaction of its own, so a crash
     * still leaves either the previous version or the new one.
     */
    private fun applyWithForeignKeysOff(migration: Migration) {
        database.read { connection ->
            connection.createStatement().use { statement -> statement.execute(PRAGMA_FOREIGN_KEYS_OFF) }
        }
        try {
            applyStatements(migration)
        } finally {
            database.read { connection ->
                connection.createStatement().use { statement -> statement.execute(PRAGMA_FOREIGN_KEYS_ON) }
            }
        }
    }

    private fun applyWithForeignKeysOn(migration: Migration) {
        applyStatements(migration)
    }

    private fun applyStatements(migration: Migration) {
        val statements = splitSqlStatements(readResourceText(migration.resource))
        database.transaction { connection ->
            connection.createStatement().use { statement ->
                statements.forEach { sql -> statement.execute(sql) }
            }
            connection.createStatement().use { statement ->
                statement.execute("PRAGMA user_version = ${migration.version}")
            }
            connection.prepareStatement(
                "INSERT INTO schema_version (version, applied_at) VALUES (?, ?)",
            ).use { statement ->
                statement.setInt(1, migration.version)
                statement.setString(2, Instants.now())
                statement.executeUpdate()
            }
        }
    }

    private fun readResourceText(resource: String): String =
        javaClass.classLoader.getResourceAsStream(resource)?.use { it.readBytes().decodeToString() }
            ?: error("migration resource $resource is missing from the classpath")

    private data class Migration(val version: Int, val resource: String, val foreignKeysOff: Boolean = false)

    companion object {
        /** The schema version this build writes and understands. */
        const val SUPPORTED_VERSION = 7

        private const val PRAGMA_FOREIGN_KEYS_OFF = "PRAGMA foreign_keys = OFF"

        private const val PRAGMA_FOREIGN_KEYS_ON = "PRAGMA foreign_keys = ON"

        private val MIGRATIONS = listOf(
            Migration(version = 1, resource = "db/migration/001_core.sql"),
            Migration(version = 2, resource = "db/migration/002_content.sql"),
            Migration(version = 3, resource = "db/migration/003_job_types.sql", foreignKeysOff = true),
            Migration(version = 4, resource = "db/migration/004_llm.sql"),
            Migration(version = 5, resource = "db/migration/005_ask_audit.sql"),
            Migration(version = 6, resource = "db/migration/006_investigate_activity.sql"),
            Migration(version = 7, resource = "db/migration/007_investigate_tool_messages.sql"),
        )
    }
}

/**
 * Splits a migration script into single statements. SQLite's JDBC driver executes one statement per
 * call, so the boundary has to be found here: a semicolon inside a string literal or a `--` comment
 * must not end a statement.
 */
internal fun splitSqlStatements(script: String): List<String> {
    val statements = mutableListOf<String>()
    val current = StringBuilder()
    var inStringLiteral = false
    var index = 0
    while (index < script.length) {
        val character = script[index]
        when {
            !inStringLiteral && character == '-' && script.getOrNull(index + 1) == '-' -> {
                while (index < script.length && script[index] != '\n') index++
            }
            character == '\'' -> {
                inStringLiteral = !inStringLiteral
                current.append(character)
                index++
            }
            character == ';' && !inStringLiteral -> {
                current.toString().trim().takeIf { it.isNotEmpty() }?.let(statements::add)
                current.clear()
                index++
            }
            else -> {
                current.append(character)
                index++
            }
        }
    }
    current.toString().trim().takeIf { it.isNotEmpty() }?.let(statements::add)
    return statements
}
