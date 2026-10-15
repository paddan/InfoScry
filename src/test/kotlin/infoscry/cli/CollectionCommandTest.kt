package infoscry.cli

import infoscry.llm.LlmPromptRole
import infoscry.llm.ShippedPrompts
import infoscry.server.ApiJson
import infoscry.server.PromptWire
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

    @Test
    fun `create from file then list shows it`() {
        CliProcess.run(*args("collection", "create", "Acme"))
        val file = dataDir.resolve("short.txt")
        Files.writeString(file, "Answer in one sentence.")

        val created = CliProcess.run(*args("collection", "prompt", "create", "Acme", "--role", "ask", "--name", "Short", "--file", file.toString()))
        val listed = CliProcess.run(*args("collection", "prompt", "list", "Acme", "--role", "ask"))

        assertEquals(0, created.exitCode, created.stderr)
        assertEquals(0, listed.exitCode, listed.stderr)
        assertContains(listed.stdout, "Short\t1")
    }

    @Test
    fun `create from stdin with dash`() {
        CliProcess.run(*args("collection", "create", "Acme"))

        val created = CliProcess.run(
            *args("collection", "prompt", "create", "Acme", "--role", "ask", "--name", "Piped", "--file", "-"),
            stdin = "Piped body.",
        )
        val shown = CliProcess.run(*args("collection", "prompt", "show", "Acme", "--role", "ask", "--prompt", "Piped"))

        assertEquals(0, created.exitCode, created.stderr)
        assertEquals("Piped body.", shown.stdout.trim())
    }

    @Test
    fun `a CRLF file is stored with LF and re-sending the same text is not a new version`() {
        CliProcess.run(*args("collection", "create", "Acme"))
        val crlf = dataDir.resolve("crlf.txt")
        Files.writeString(crlf, "Line one\r\nLine two")
        val lf = dataDir.resolve("lf.txt")
        Files.writeString(lf, "Line one\nLine two")

        val created = CliProcess.run(*args("collection", "prompt", "create", "Acme", "--role", "ask", "--name", "Lines", "--file", crlf.toString()))
        val shown = CliProcess.run(*args("collection", "prompt", "show", "Acme", "--role", "ask", "--prompt", "Lines", "--json"))
        val updated = CliProcess.run(*args("collection", "prompt", "update", "Acme", "--role", "ask", "--prompt", "Lines", "--file", lf.toString()))
        val afterUpdate = CliProcess.run(*args("collection", "prompt", "show", "Acme", "--role", "ask", "--prompt", "Lines", "--json"))

        assertEquals(0, created.exitCode, created.stderr)
        assertEquals("Line one\nLine two", ApiJson.decodeFromString<PromptWire>(shown.stdout.trim()).body)
        assertEquals(0, updated.exitCode, updated.stderr)
        assertEquals(1, ApiJson.decodeFromString<PromptWire>(afterUpdate.stdout.trim()).version)
    }

    @Test
    fun `show without --prompt prints the collection default body`() {
        CliProcess.run(*args("collection", "create", "Acme"))
        val file = dataDir.resolve("short.txt")
        Files.writeString(file, "Answer in one sentence.")
        CliProcess.run(*args("collection", "prompt", "create", "Acme", "--role", "ask", "--name", "Short", "--file", file.toString()))

        val before = CliProcess.run(*args("collection", "prompt", "show", "Acme", "--role", "ask"))
        CliProcess.run(*args("collection", "prompt", "use", "Acme", "--role", "ask", "--prompt", "Short"))
        val after = CliProcess.run(*args("collection", "prompt", "show", "Acme", "--role", "ask"))

        assertEquals(ShippedPrompts.body(LlmPromptRole.ASK).trim(), before.stdout.trim())
        assertEquals("Answer in one sentence.", after.stdout.trim())
    }

    @Test
    fun `use Default resets the collection to the shipped prompt`() {
        CliProcess.run(*args("collection", "create", "Acme"))
        val file = dataDir.resolve("short.txt")
        Files.writeString(file, "Answer in one sentence.")
        CliProcess.run(*args("collection", "prompt", "create", "Acme", "--role", "ask", "--name", "Short", "--file", file.toString()))
        CliProcess.run(*args("collection", "prompt", "use", "Acme", "--role", "ask", "--prompt", "Short"))

        val reset = CliProcess.run(*args("collection", "prompt", "use", "Acme", "--role", "ask", "--prompt", "Default"))
        val shown = CliProcess.run(*args("collection", "prompt", "show", "Acme", "--role", "ask"))
        val listed = CliProcess.run(*args("collection", "prompt", "list", "Acme", "--role", "ask"))

        assertEquals(0, reset.exitCode, reset.stderr)
        assertEquals(ShippedPrompts.body(LlmPromptRole.ASK).trim(), shown.stdout.trim())
        assertContains(listed.stdout, "Default\t1\t(default)")
    }

    @Test
    fun `delete twice exits 0 and the Default prompt cannot be deleted`() {
        CliProcess.run(*args("collection", "create", "Acme"))
        val file = dataDir.resolve("short.txt")
        Files.writeString(file, "Answer in one sentence.")
        CliProcess.run(*args("collection", "prompt", "create", "Acme", "--role", "ask", "--name", "Short", "--file", file.toString()))

        val first = CliProcess.run(*args("collection", "prompt", "delete", "Acme", "--role", "ask", "--prompt", "Short"))
        val second = CliProcess.run(*args("collection", "prompt", "delete", "Acme", "--role", "ask", "--prompt", "Short"))
        val defaultDelete = CliProcess.run(*args("collection", "prompt", "delete", "Acme", "--role", "ask", "--prompt", "Default"))
        val listed = CliProcess.run(*args("collection", "prompt", "list", "Acme", "--role", "ask"))

        assertEquals(0, first.exitCode, first.stderr)
        assertEquals(0, second.exitCode, second.stderr)
        assertNotEquals(0, defaultDelete.exitCode)
        assertTrue(!listed.stdout.contains("Short"), "the deleted prompt must not be listed: ${listed.stdout}")
    }

    private fun args(vararg extra: String): Array<String> =
        (listOf("--data-dir", dataDir.toString()) + extra).toTypedArray()
}
