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
 * Brings the database schema to [SUPPORTED_VERSION]: an empty database gets the baseline
 * `db/migration/001_baseline.sql` (version 1), and each later numbered script in [STEPS] is applied in order
 * to a database below its version.
 *
 * Everything runs in one transaction that also records each applied version in `schema_version`, so a crash
 * leaves the database at the version it started from, never between two. A database already at
 * [SUPPORTED_VERSION] is left alone, and one whose version is newer than this build understands is refused
 * rather than written to.
 *
 * Two processes starting against the same database at once are serialized by a transaction-scoped
 * advisory lock, so the second sees the first one's schema instead of failing halfway through its own.
 */
class SchemaMigrator(private val database: Database) {

    /** Applies every step above the database's version, up to [upTo] (tests stop early to build an older schema). */
    fun migrate(upTo: Int = SUPPORTED_VERSION) {
        require(upTo in 1..SUPPORTED_VERSION) { "no schema version $upTo" }
        database.transaction { connection ->
            connection.prepareStatement("SELECT pg_advisory_xact_lock(?)").use { statement ->
                statement.setLong(1, MIGRATION_LOCK_KEY)
                statement.executeQuery().close()
            }
            val currentVersion = database.schemaVersion()
            if (currentVersion > SUPPORTED_VERSION) {
                throw SchemaVersionTooNewException(found = currentVersion, supported = SUPPORTED_VERSION)
            }
            for (version in (currentVersion + 1)..upTo) {
                connection.createStatement().use { statement ->
                    splitSqlStatements(readStep(STEPS[version - 1])).forEach { sql -> statement.execute(sql) }
                }
                connection.prepareStatement(
                    "INSERT INTO schema_version (version, applied_at) VALUES (?, ?)",
                ).use { statement ->
                    statement.setInt(1, version)
                    statement.setString(2, Instants.now())
                    statement.executeUpdate()
                }
            }
        }
    }

    private fun readStep(resource: String): String =
        javaClass.classLoader.getResourceAsStream(resource)?.use { it.readBytes().decodeToString() }
            ?: error("schema resource $resource is missing from the classpath")

    companion object {
        /**
         * The schema scripts in version order: the baseline is version 1 and describes a new database; each
         * later script changes a database at the version before it and must keep the rows it already holds.
         */
        private val STEPS = listOf(
            "db/migration/001_baseline.sql",
            "db/migration/002_import_roots.sql",
            "db/migration/003_drop_import_roots.sql",
            "db/migration/004_images.sql",
            "db/migration/005_caption_source_page_text.sql",
        )

        /** The schema version this build writes and understands. */
        val SUPPORTED_VERSION: Int = STEPS.size

        /** The advisory-lock key that serializes schema creation; distinct from [ProcessOwnership]'s key. */
        private const val MIGRATION_LOCK_KEY = 0x1_5C47_0001L
    }
}

/**
 * Splits a SQL script into single statements, so a failure names the statement that failed: a semicolon
 * inside a string literal, a `$$`-quoted function body or a `--` comment must not end a statement.
 */
internal fun splitSqlStatements(script: String): List<String> {
    val statements = mutableListOf<String>()
    val current = StringBuilder()
    var inStringLiteral = false
    var index = 0
    while (index < script.length) {
        val character = script[index]
        when {
            !inStringLiteral && character == '$' && script.getOrNull(index + 1) == '$' -> {
                val end = script.indexOf("$$", index + 2)
                require(end >= 0) { "an unterminated $$ body in the schema script" }
                current.append(script, index, end + 2)
                index = end + 2
            }
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
