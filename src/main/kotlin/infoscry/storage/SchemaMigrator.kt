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
 * Creates the database schema from the baseline script `db/migration/001_baseline.sql`.
 *
 * The baseline always describes the current schema and creates a new database at [SUPPORTED_VERSION]
 * directly, in one transaction that also records the version in `schema_version`, so a crash leaves either
 * an empty database or a complete one. A database already at [SUPPORTED_VERSION] is left alone, and one
 * whose version is newer than this build understands is refused rather than written to.
 *
 * Two processes starting against the same empty database at once are serialized by a transaction-scoped
 * advisory lock, so the second sees the first one's schema instead of failing halfway through its own.
 */
class SchemaMigrator(private val database: Database) {

    /** Creates the baseline schema in an empty database; does nothing when it is already there. */
    fun migrate() {
        database.transaction { connection ->
            connection.prepareStatement("SELECT pg_advisory_xact_lock(?)").use { statement ->
                statement.setLong(1, MIGRATION_LOCK_KEY)
                statement.executeQuery().close()
            }
            val currentVersion = database.schemaVersion()
            if (currentVersion > SUPPORTED_VERSION) {
                throw SchemaVersionTooNewException(found = currentVersion, supported = SUPPORTED_VERSION)
            }
            if (currentVersion == SUPPORTED_VERSION) return@transaction
            connection.createStatement().use { statement ->
                splitSqlStatements(readBaseline()).forEach { sql -> statement.execute(sql) }
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

        /** The advisory-lock key that serializes schema creation; distinct from [ProcessOwnership]'s key. */
        private const val MIGRATION_LOCK_KEY = 0x1_5C47_0001L

        private const val BASELINE_RESOURCE = "db/migration/001_baseline.sql"
    }
}

/**
 * Splits a SQL script into single statements, so a failure names the statement that failed: a semicolon
 * inside a string literal or a `--` comment must not end a statement.
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
