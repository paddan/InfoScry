package infoscry.cli

import infoscry.server.ApiJson
import infoscry.server.CollectionResponse
import infoscry.server.CollectionsResponse
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * `infoscry collection` as a real process.
 *
 * What a script depends on is the exit code and the shape of the output, so both are asserted through
 * the real command line: success is `0`, a refusal is not, and machine-readable output is one parseable
 * line with no diagnostics mixed into it.
 */
class CollectionCommandTest {

    private lateinit var dataDir: Path

    @BeforeTest
    fun createTemporaryDataDirectory() {
        dataDir = Files.createTempDirectory("infoscry-cli")
    }

    @AfterTest
    fun removeTemporaryDataDirectory() {
        dataDir.toFile().deleteRecursively()
    }

    @Test
    fun `list prints the collections that exist as JSON`() {
        CliProcess.run(*args("collection", "create", "Default"))

        val result = CliProcess.run(*args("collection", "list", "--json"))

        assertEquals(0, result.exitCode, result.stderr)
        val listed = ApiJson.decodeFromString<CollectionsResponse>(result.stdout.trim())
        assertTrue(listed.collections.any { it.name == "Default" })
    }

    @Test
    fun `machine-readable output is the only thing on stdout`() {
        val result = CliProcess.run(*args("collection", "list", "--json"))

        assertEquals(1, result.stdout.trim().lines().size, "stdout carried: ${result.stdout}")
        assertTrue(result.stdout.trim().startsWith("{"))
    }

    @Test
    fun `the data directory may be given after the subcommand`() {
        CliProcess.run(*args("collection", "create", "Default"))
        val result = CliProcess.run(
            "collection",
            "list",
            "--json",
            "--data-dir",
            dataDir.toString(),
        )

        assertEquals(0, result.exitCode, result.stderr)
        assertContains(result.stdout, "Default")
    }

    @Test
    fun `create makes a collection that list then shows`() {
        val created = CliProcess.run(*args("collection", "create", "Project Nightfall", "--json"))

        assertEquals(0, created.exitCode, created.stderr)
        val response = ApiJson.decodeFromString<CollectionResponse>(created.stdout.trim())
        assertEquals("Project Nightfall", response.collection.name)

        val listed = CliProcess.run(*args("collection", "list", "--json"))
        assertContains(listed.stdout, "Project Nightfall")
    }

    @Test
    fun `a duplicate name fails with an explanation instead of a stack trace`() {
        CliProcess.run(*args("collection", "create", "Acme"))

        val duplicate = CliProcess.run(*args("collection", "create", "acme"))

        assertNotEquals(0, duplicate.exitCode)
        assertContains(duplicate.stderr, "already exists")
        assertTrue(
            !duplicate.stderr.contains("Exception in thread"),
            "the operator gets the reason, not a stack trace: ${duplicate.stderr}",
        )
    }

    @Test
    fun `the human-readable list names each collection with its id and OCR languages`() {
        CliProcess.run(*args("collection", "create", "Acme"))

        val listed = CliProcess.run(*args("collection", "list"))

        assertEquals(0, listed.exitCode, listed.stderr)
        assertContains(listed.stdout, "Acme")
        assertContains(listed.stdout, "ocr: eng")
    }

    @Test
    fun `ignore list shows the defaults of a new collection`() {
        CliProcess.run(*args("collection", "create", "Acme"))

        val listed = CliProcess.run(*args("collection", "ignore", "Acme", "list"))

        assertEquals(0, listed.exitCode, listed.stderr)
        assertEquals(
            listOf(".DS_Store", "._*", "Thumbs.db", "desktop.ini", "~$*", "*.tmp", ".git/", "node_modules/"),
            listed.stdout.trim().lines(),
        )
    }

    @Test
    fun `ignore set replaces the list and list --json reads it back`() {
        CliProcess.run(*args("collection", "create", "Acme"))
        CliProcess.run(*args("collection", "create", "Other"))

        val saved = CliProcess.run(*args("collection", "ignore", "Acme", "set", "*.bak", "!keep.bak"))
        val listed = CliProcess.run(*args("collection", "ignore", "Acme", "list", "--json"))
        val untouched = CliProcess.run(*args("collection", "ignore", "Other", "list"))

        assertEquals(0, saved.exitCode, saved.stderr)
        assertEquals("""{"patterns":["*.bak","!keep.bak"]}""", listed.stdout.trim())
        assertContains(untouched.stdout, ".DS_Store")
    }

    @Test
    fun `ignore set can read the patterns from a file and can clear the list`() {
        CliProcess.run(*args("collection", "create", "Acme"))
        val file = dataDir.resolve("patterns.txt")
        Files.writeString(file, "# mine\n*.bak\n\n")

        val fromFile = CliProcess.run(*args("collection", "ignore", "Acme", "set", "--file", file.toString()))
        val afterFile = CliProcess.run(*args("collection", "ignore", "Acme", "list", "--json"))
        val cleared = CliProcess.run(*args("collection", "ignore", "Acme", "set", "--clear"))
        val afterClear = CliProcess.run(*args("collection", "ignore", "Acme", "list", "--json"))

        assertEquals(0, fromFile.exitCode, fromFile.stderr)
        assertEquals("""{"patterns":["# mine","*.bak"]}""", afterFile.stdout.trim())
        assertEquals(0, cleared.exitCode, cleared.stderr)
        assertEquals("""{"patterns":[]}""", afterClear.stdout.trim())
    }

    @Test
    fun `ignore set refuses an invalid pattern, no patterns at all, and an unknown collection`() {
        CliProcess.run(*args("collection", "create", "Acme"))

        val invalid = CliProcess.run(*args("collection", "ignore", "Acme", "set", "*.bak", "!"))
        val nothing = CliProcess.run(*args("collection", "ignore", "Acme", "set"))
        val unknown = CliProcess.run(*args("collection", "ignore", "Nowhere", "list"))

        assertNotEquals(0, invalid.exitCode)
        assertNotEquals(0, nothing.exitCode, "an empty set must say --clear, not silently empty the list")
        assertNotEquals(0, unknown.exitCode)
        assertContains(CliProcess.run(*args("collection", "ignore", "Acme", "list")).stdout, ".DS_Store")
    }

    private fun args(vararg extra: String): Array<String> =
        (listOf("--data-dir", dataDir.toString()) + extra).toTypedArray()
}
