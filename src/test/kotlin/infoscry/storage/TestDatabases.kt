package infoscry.storage

import infoscry.config.AppPaths
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import org.junit.jupiter.api.extension.AfterAllCallback
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * The test suite's Postgres: one ParadeDB container per test JVM, and one database per data directory or
 * database path a test names.
 *
 * Tests used to give every temporary directory its own SQLite file, and many reopen that file to see what a
 * restarted process sees. [configFor] keeps that shape: the same path always names the same database, which
 * is cloned from a migrated template the first time it is asked for, so a test opens a ready schema in
 * milliseconds. [TestDatabaseExtension] drops the databases a test class created once the class is done.
 */
object TestDatabases {
    /** The image the multi-user server spec pins, which the pg_search spike measured. */
    const val IMAGE = "docker.io/paradedb/paradedb:0.26.5-pg17"

    private const val TEMPLATE = "infoscry_template"

    private val container: PostgreSQLContainer by lazy {
        PostgreSQLContainer(DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("postgres"))
            // Durability is irrelevant for a throwaway database and costs most of the suite's time.
            .withCommand("postgres", "-c", "fsync=off", "-c", "synchronous_commit=off", "-c", "full_page_writes=off",
                "-c", "max_connections=1000")
            .also { it.start() }
    }

    private val created = mutableSetOf<String>()
    private val createdSinceMark = mutableSetOf<String>()
    private var templateReady = false

    /**
     * Set in a child JVM a test starts with [environmentFor]: the child opens its parent's database from the
     * environment instead of starting a container of its own.
     */
    const val CHILD_VARIABLE = "INFOSCRY_TEST_CHILD_DATABASE"

    private val inChild: Boolean = System.getenv(CHILD_VARIABLE) == "1"

    /** The configuration of the database that [path] names, created from the template on first use. */
    @Synchronized
    fun configFor(path: Path): DatabaseConfig {
        if (inChild) return DatabaseConfig.fromEnvironment()
        val name = nameFor(path)
        if (name !in created) {
            ensureTemplate()
            admin { it.createStatement().use { s -> s.execute("CREATE DATABASE $name TEMPLATE $TEMPLATE STRATEGY FILE_COPY") } }
            created += name
            createdSinceMark += name
        }
        return config(name)
    }

    /** A database without the schema, for the tests of schema creation itself. */
    @Synchronized
    fun emptyConfig(path: Path): DatabaseConfig {
        val name = nameFor(path) + "_empty"
        if (name !in created) {
            admin { it.createStatement().use { s -> s.execute("CREATE DATABASE $name") } }
            created += name
            createdSinceMark += name
        }
        return config(name)
    }

    /** The environment a child JVM needs to open the same database for [path]. */
    fun environmentFor(path: Path): Map<String, String> {
        val config = configFor(path)
        return mapOf(
            DatabaseConfig.URL_VARIABLE to config.url,
            DatabaseConfig.USER_VARIABLE to config.user,
            DatabaseConfig.PASSWORD_VARIABLE to container.password,
            CHILD_VARIABLE to "1",
        )
    }

    /**
     * The environment for a child JVM started with [command]: the database of its `--data-dir`, or else of
     * the first argument naming a directory this JVM already gave a database, or else of the first argument
     * that is an existing directory. A command naming none gets nothing.
     */
    @Synchronized
    fun environmentForCommand(command: List<String>): Map<String, String> {
        val dataDir = command.indexOf("--data-dir").takeIf { it >= 0 }?.let { command.getOrNull(it + 1) }
            ?: command.firstOrNull { it.startsWith("--data-dir=") }?.removePrefix("--data-dir=")
            ?: command.firstOrNull { arg -> runCatching { nameFor(Path.of(arg)) in created }.getOrDefault(false) }
            ?: command.firstOrNull { arg -> runCatching { java.nio.file.Files.isDirectory(Path.of(arg)) }.getOrDefault(false) }
        return dataDir?.let { environmentFor(Path.of(it)) } ?: emptyMap()
    }

    /** Drops every database created since the last call, closing whatever connections a test left open. */
    @Synchronized
    fun dropCreatedSinceMark() {
        if (inChild || createdSinceMark.isEmpty()) return
        admin { connection ->
            createdSinceMark.forEach { name ->
                connection.createStatement().use { it.execute("DROP DATABASE IF EXISTS $name WITH (FORCE)") }
                created -= name
            }
        }
        createdSinceMark.clear()
    }

    /**
     * A data directory's database is named by the directory; a database path is named by its file, except
     * `infoscry.db` inside a data directory, which is the same database the data directory names.
     */
    private fun nameFor(path: Path): String {
        val absolute = path.toAbsolutePath().normalize()
        val key = if (absolute.fileName?.toString() == "infoscry.db") absolute.parent else absolute
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toString().toByteArray())
        return "t_" + digest.take(12).joinToString("") { "%02x".format(it) }
    }

    private fun config(name: String): DatabaseConfig =
        DatabaseConfig(
            url = "jdbc:postgresql://${container.host}:${container.firstMappedPort}/$name",
            user = container.username,
            password = container.password,
        )

    private fun ensureTemplate() {
        if (templateReady) return
        admin { connection ->
            connection.createStatement().use { s ->
                s.execute("DROP DATABASE IF EXISTS $TEMPLATE WITH (FORCE)")
                s.execute("CREATE DATABASE $TEMPLATE")
            }
        }
        Database(config(TEMPLATE), maximumPoolSize = 1).use { SchemaMigrator(it).migrate() }
        templateReady = true
    }

    private fun <T> admin(block: (Connection) -> T): T =
        DriverManager.getConnection(
            "jdbc:postgresql://${container.host}:${container.firstMappedPort}/postgres",
            container.username,
            container.password,
        ).use(block)
}

/** Gives the child JVM this builder starts the database of the data directory its command names. */
fun ProcessBuilder.withTestDatabase(): ProcessBuilder = apply {
    environment().putAll(TestDatabases.environmentForCommand(command()))
}

/** A database for [path], opened the way a test used to open the SQLite file at that path. */
fun Database(path: Path): Database = Database(TestDatabases.configFor(path), maximumPoolSize = 4)

/**
 * Installed for every test class through JUnit's extension autodetection: data directories opened through
 * [infoscry.AppContext] get the database [TestDatabases] keeps for them, and each class's databases are
 * dropped when it is done.
 */
class TestDatabaseExtension : BeforeAllCallback, AfterAllCallback {
    override fun beforeAll(context: ExtensionContext) {
        DatabaseResolver.resolve = { paths: AppPaths -> TestDatabases.configFor(paths.root) }
    }

    override fun afterAll(context: ExtensionContext) {
        // Nested classes finish inside their outer class, whose databases are still in use.
        if (context.requiredTestClass.isAnnotationPresent(org.junit.jupiter.api.Nested::class.java)) return
        TestDatabases.dropCreatedSinceMark()
    }
}
