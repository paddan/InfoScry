package infoscry.storage

/**
 * The database schema version is newer than this build understands. Raising it means an older InfoScry
 * would silently write against a schema it does not know, so migration stops instead.
 */
class SchemaVersionTooNewException(val found: Int, val supported: Int) : IllegalStateException(
    "the database schema is version $found but this InfoScry build supports version $supported; " +
        "upgrade InfoScry or restore a database created by this version",
)

/**
 * Creates the database schema from the single baseline script `db/migration/001_baseline.sql`.
 *
 * There is one schema version and no upgrade path: the baseline is edited in place when the schema
 * changes, and a database that already has it is left alone. The baseline runs inside one transaction
 * that also records `PRAGMA user_version`, so a crash leaves either an empty database or a complete
 * one. A database whose `user_version` is newer than this build understands is refused rather than
 * written to, because an older InfoScry would silently write against a schema it does not know.
 */
class SchemaMigrator(private val database: Database) {

    /** Creates the baseline schema in an empty database; does nothing when it is already there. */
    fun migrate() {
        val currentVersion = database.userVersion()
        if (currentVersion > SUPPORTED_VERSION) {
            throw SchemaVersionTooNewException(found = currentVersion, supported = SUPPORTED_VERSION)
        }
        if (currentVersion == SUPPORTED_VERSION) return

        val statements = splitSqlStatements(readBaseline())
        database.transaction { connection ->
            connection.createStatement().use { statement ->
                statements.forEach { sql -> statement.execute(sql) }
            }
            connection.createStatement().use { statement ->
                statement.execute("PRAGMA user_version = $SUPPORTED_VERSION")
            }
            connection.prepareStatement(
                "INSERT INTO schema_version (version, applied_at) VALUES (?, ?)",
            ).use { statement ->
                statement.setInt(1, SUPPORTED_VERSION)
                statement.setString(2, Instants.now())
                statement.executeUpdate()
            }
        }
    }

    private fun readBaseline(): String =
        javaClass.classLoader.getResourceAsStream(BASELINE_RESOURCE)?.use { it.readBytes().decodeToString() }
            ?: error("schema resource $BASELINE_RESOURCE is missing from the classpath")

    companion object {
        /** The schema version this build writes and understands. */
        const val SUPPORTED_VERSION = 1

        private const val BASELINE_RESOURCE = "db/migration/001_baseline.sql"
    }
}

/**
 * Splits a SQL script into single statements. SQLite's JDBC driver executes one statement per
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
