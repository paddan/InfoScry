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
 * Creates the database schema from the baseline script `db/migration/001_baseline.sql`, and upgrades a
 * version 1 database to version 2.
 *
 * The baseline always describes the current schema and creates a new database at [SUPPORTED_VERSION]
 * directly. The one upgrade step, from 1 to 2, rebuilds `collections` so a collection's engine may be
 * `NONE` (No OCR); SQLite cannot change a CHECK constraint in place. Each step runs inside one transaction
 * that also records `PRAGMA user_version`, so a crash leaves either the old version intact or the new one
 * complete, and a database already at [SUPPORTED_VERSION] is left alone. A database whose `user_version`
 * is newer than this build understands is refused rather than written to, because an older InfoScry would
 * silently write against a schema it does not know.
 *
 * [afterCopy] runs inside the upgrade transaction after the rows are copied; tests use it to interrupt the
 * step.
 */
class SchemaMigrator(
    private val database: Database,
    private val afterCopy: () -> Unit = {},
) {

    /** Creates the baseline schema in an empty database; does nothing when it is already there. */
    fun migrate() {
        val currentVersion = database.userVersion()
        if (currentVersion > SUPPORTED_VERSION) {
            throw SchemaVersionTooNewException(found = currentVersion, supported = SUPPORTED_VERSION)
        }
        if (currentVersion == SUPPORTED_VERSION) return
        if (currentVersion == 1) {
            upgradeToVersion2()
            return
        }

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

    /**
     * Rebuilds `collections` with the version 2 definition, the procedure SQLite documents for changing a
     * constraint: create, copy, drop, rename, and check the foreign keys before committing.
     *
     * Foreign-key enforcement is a connection setting SQLite ignores inside a transaction, so it is switched
     * off before the transaction and always switched back on afterwards. Without that, dropping the table
     * would cascade into every document, job and prompt of every collection.
     */
    private fun upgradeToVersion2() {
        database.read { connection -> connection.createStatement().use { it.execute("PRAGMA foreign_keys = OFF") } }
        try {
            database.transaction { connection ->
                connection.createStatement().use { statement ->
                    // Violations the archive already had are not this step's to judge; only new ones stop it.
                    val violationsBefore = foreignKeyViolations(statement)
                    statement.execute(COLLECTIONS_VERSION_2)
                    statement.execute(
                        "INSERT INTO collections_v2 ($COLLECTION_COLUMNS) SELECT $COLLECTION_COLUMNS FROM collections",
                    )
                    afterCopy()
                    statement.execute("DROP TABLE collections")
                    statement.execute("ALTER TABLE collections_v2 RENAME TO collections")
                    statement.execute("CREATE UNIQUE INDEX collections_name_unique ON collections (name COLLATE NOCASE)")
                    val violationsAfter = foreignKeyViolations(statement)
                    check(violationsAfter.all { (key, count) -> count <= (violationsBefore[key] ?: 0) }) {
                        "upgrading the schema to version 2 would break a foreign key"
                    }
                    statement.execute("PRAGMA user_version = 2")
                }
                connection.prepareStatement(
                    "INSERT INTO schema_version (version, applied_at) VALUES (?, ?)",
                ).use { statement ->
                    statement.setInt(1, 2)
                    statement.setString(2, Instants.now())
                    statement.executeUpdate()
                }
            }
        } finally {
            database.read { connection -> connection.createStatement().use { it.execute("PRAGMA foreign_keys = ON") } }
        }
    }

    /**
     * How many foreign-key violations the database has, per table and referenced table. Counted rather than
     * listed by row, because rebuilding `collections` may renumber its own rows.
     */
    private fun foreignKeyViolations(statement: java.sql.Statement): Map<String, Int> =
        statement.executeQuery("PRAGMA foreign_key_check").use { rows ->
            buildMap { while (rows.next()) merge("${rows.getString(1)}->${rows.getString(3)}", 1, Int::plus) }
        }

    private fun readBaseline(): String =
        javaClass.classLoader.getResourceAsStream(BASELINE_RESOURCE)?.use { it.readBytes().decodeToString() }
            ?: error("schema resource $BASELINE_RESOURCE is missing from the classpath")

    companion object {
        /** The schema version this build writes and understands. */
        const val SUPPORTED_VERSION = 2

        /** Every column of `collections`, in the order both versions declare them. */
        private const val COLLECTION_COLUMNS =
            "id, name, description, ocr_languages, lifecycle, created_at, updated_at, ocr_engine, " +
                "ocr_import_mode, ocr_transcription_profile_id, ocr_review_profile_id, ocr_external_page_limit, " +
                "ask_prompt_id, investigate_prompt_id"

        /** `collections` as version 2 defines it, frozen here so the upgrade does not move with the baseline. */
        private val COLLECTIONS_VERSION_2 = """
            CREATE TABLE collections_v2 (
                id TEXT NOT NULL PRIMARY KEY,
                name TEXT NOT NULL CHECK (length(trim(name)) > 0),
                description TEXT,
                ocr_languages TEXT NOT NULL DEFAULT 'eng' CHECK (length(trim(ocr_languages)) > 0),
                lifecycle TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (lifecycle IN ('ACTIVE', 'DELETING')),
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                ocr_engine TEXT NOT NULL DEFAULT 'TESSERACT'
                    CHECK (ocr_engine IN ('TESSERACT', 'SURYA', 'LLM', 'NONE')),
                ocr_import_mode TEXT NOT NULL DEFAULT 'FILL_MISSING'
                    CHECK (ocr_import_mode IN ('FILL_MISSING', 'CHECK_AND_IMPROVE')),
                ocr_transcription_profile_id TEXT REFERENCES ocr_profiles (id),
                ocr_review_profile_id TEXT REFERENCES ocr_profiles (id),
                ocr_external_page_limit INTEGER NOT NULL DEFAULT 0 CHECK (ocr_external_page_limit >= 0),
                ask_prompt_id TEXT REFERENCES collection_prompts (id) ON DELETE SET NULL,
                investigate_prompt_id TEXT REFERENCES collection_prompts (id) ON DELETE SET NULL
            )
        """.trimIndent()

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
