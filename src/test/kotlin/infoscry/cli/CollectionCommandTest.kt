package infoscry.cli

import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.Json
import infoscry.llm.LlmPromptRole
import infoscry.llm.ShippedPrompts
import infoscry.server.ApiJson
import infoscry.server.PromptWire
import infoscry.server.CollectionResponse
import infoscry.server.CollectionsResponse
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
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
            stdin = "Piped body.".toByteArray(Charsets.UTF_8),
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

    @Test
    fun `show prints the stored body byte for byte, with no added newline`() {
        CliProcess.run(*args("collection", "create", "Acme"))
        val body = "Svenska: åäö — kept.\n\tIndented line\n"
        val file = dataDir.resolve("body.txt")
        Files.write(file, body.toByteArray(Charsets.UTF_8))
        CliProcess.run(*args("collection", "prompt", "create", "Acme", "--role", "ask", "--name", "Exact", "--file", file.toString()))

        val shown = showBytes("Acme", "--role", "ask", "--prompt", "Exact")

        assertTrue(shown.contentEquals(body.toByteArray(Charsets.UTF_8)), "show must print the body exactly: ${String(shown, Charsets.UTF_8)}")
    }

    @Test
    fun `show redirected to a file and sent back with update keeps the version`() {
        CliProcess.run(*args("collection", "create", "Acme"))
        val original = dataDir.resolve("original.txt")
        Files.write(original, "Line one\nLine two\n".toByteArray(Charsets.UTF_8))
        CliProcess.run(*args("collection", "prompt", "create", "Acme", "--role", "ask", "--name", "Round", "--file", original.toString()))

        val roundTrip = dataDir.resolve("round.txt")
        Files.write(roundTrip, showBytes("Acme", "--role", "ask", "--prompt", "Round"))
        val updated = CliProcess.run(*args("collection", "prompt", "update", "Acme", "--role", "ask", "--prompt", "Round", "--file", roundTrip.toString()))
        val listed = CliProcess.run(*args("collection", "prompt", "list", "Acme", "--role", "ask"))

        assertEquals(0, updated.exitCode, updated.stderr)
        assertContains(listed.stdout, "Round\t1\t")
        assertTrue(!listed.stdout.contains("Round\t2"), "a round trip must not create a new version: ${listed.stdout}")
    }

    @Test
    fun `a file that is not valid UTF-8 is refused, not stored with replacement characters`() {
        CliProcess.run(*args("collection", "create", "Acme"))
        val bad = dataDir.resolve("bad.txt")
        Files.write(bad, byteArrayOf(0x41, 0xFF.toByte(), 0x42))

        val created = CliProcess.run(*args("collection", "prompt", "create", "Acme", "--role", "ask", "--name", "Bad", "--file", bad.toString()))

        assertNotEquals(0, created.exitCode)
        assertContains(created.stderr, "not valid UTF-8")
        assertTrue(!CliProcess.run(*args("collection", "prompt", "list", "Acme", "--role", "ask")).stdout.contains("Bad"))
    }

    @Test
    fun `standard input that is not valid UTF-8 is refused`() {
        CliProcess.run(*args("collection", "create", "Acme"))

        val created = CliProcess.run(
            *args("collection", "prompt", "create", "Acme", "--role", "ask", "--name", "Piped", "--file", "-"),
            stdin = byteArrayOf(0x41, 0xFF.toByte()),
        )

        assertNotEquals(0, created.exitCode)
        assertContains(created.stderr, "not valid UTF-8")
    }

    /** The exact bytes `show` writes to standard output, read without the line handling of [CliProcess.run]. */
    private fun showBytes(vararg extra: String): ByteArray {
        val process = CliProcess.start(*args("collection", "prompt", "show", *extra))
        process.outputStream.close()
        val bytes = process.inputStream.readBytes()
        val errors = process.errorStream.readBytes()
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "show did not finish")
        assertEquals(0, process.exitValue(), String(errors, Charsets.UTF_8))
        return bytes
    }

    @Test
    fun `corrections add then list shows the rule`() {
        CliProcess.run(*args("collection", "create", "Default"))

        val added = CliProcess.run(*args("collection", "corrections", "add", "Default", "spinners", "Spinnars"))
        val listed = CliProcess.run(*args("collection", "corrections", "list", "Default"))

        assertEquals(0, added.exitCode, added.stderr)
        assertTrue("0 matches in 0 documents" in added.stdout, added.stdout)
        assertEquals(0, listed.exitCode, listed.stderr)
        assertEquals("spinners\tSpinnars\t\t0", listed.stdout.trim())
    }

    @Test
    fun `adding the same correction twice keeps one rule`() {
        CliProcess.run(*args("collection", "create", "Default"))

        CliProcess.run(*args("collection", "corrections", "add", "Default", "spinners", "Spinnars"))
        val again = CliProcess.run(*args("collection", "corrections", "add", "Default", "spinners", "Spinnars"))
        val listed = CliProcess.run(*args("collection", "corrections", "list", "Default"))

        assertEquals(0, again.exitCode, again.stderr)
        assertEquals(1, listed.stdout.trim().lines().size, listed.stdout)
    }

    @Test
    fun `removing a correction twice succeeds both times`() {
        CliProcess.run(*args("collection", "create", "Default"))
        CliProcess.run(*args("collection", "corrections", "add", "Default", "spinners", "Spinnars"))

        val first = CliProcess.run(*args("collection", "corrections", "remove", "Default", "spinners"))
        val second = CliProcess.run(*args("collection", "corrections", "remove", "Default", "spinners"))
        val listed = CliProcess.run(*args("collection", "corrections", "list", "Default"))

        assertEquals(0, first.exitCode, first.stderr)
        assertEquals(0, second.exitCode, second.stderr)
        assertEquals("", listed.stdout.trim())
    }

    @Test
    fun `a correction preview stores nothing`() {
        CliProcess.run(*args("collection", "create", "Default"))

        val preview = CliProcess.run(*args("collection", "corrections", "preview", "Default", "spinners", "Spinnars"))
        val listed = CliProcess.run(*args("collection", "corrections", "list", "Default"))

        assertEquals(0, preview.exitCode, preview.stderr)
        assertTrue("0 matches in 0 documents" in preview.stdout, preview.stdout)
        assertEquals("", listed.stdout.trim())
    }

    @Test
    fun `corrections in any script round-trip through the command line`() {
        CliProcess.run(*args("collection", "create", "Default"))

        CliProcess.run(*args("collection", "corrections", "add", "Default", "åsa", "Åsa", "--exact-case"))
        CliProcess.run(*args("collection", "corrections", "add", "Default", "東京", "東亰"))
        val listed = CliProcess.run(*args("collection", "corrections", "list", "Default", "--json"))

        assertEquals(0, listed.exitCode, listed.stderr)
        val rules = Json.parseToJsonElement(listed.stdout.trim()).jsonObject.getValue("rules").jsonArray.map { it.jsonObject }
        assertEquals(listOf("åsa" to "Åsa", "東京" to "東亰"), rules.map { it.getValue("find").jsonPrimitive.content to it.getValue("replace").jsonPrimitive.content })
        assertEquals(true, rules.first().getValue("exactCase").jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `an invalid correction is refused with a non-zero exit`() {
        CliProcess.run(*args("collection", "create", "Default"))

        val refused = CliProcess.run(*args("collection", "corrections", "add", "Default", "--", "-x", "y"))

        assertTrue(refused.exitCode != 0)
    }

    private fun args(vararg extra: String): Array<String> =
        (listOf("--data-dir", dataDir.toString()) + extra).toTypedArray()
}
