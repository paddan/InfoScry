package infoscry.cli

import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

class AskCommandTest {
    private lateinit var directory: java.nio.file.Path
    private lateinit var cli: CliTestServer

    @BeforeTest
    fun createDataDirectory() {
        directory = Files.createTempDirectory("infoscry-ask-cli")
        cli = CliTestServer(directory)
        // A new archive has no automatic Default, and this class asks about a missing profile rather
        // than a missing collection, so the collection the request names is created explicitly.
        cli.run("collection", "create", "Default")
    }

    @AfterTest
    fun removeDataDirectory() {
        cli.close()
        directory.toFile().deleteRecursively()
    }

    @Test
    fun `ask accepts the shared json option`() {
        val result = cli.run(
            "ask", "--json",
            "--collection", "Default", "--profile", "missing", "question",
        )

        assertNotEquals(0, result.exitCode)
        assertContains(result.stdout + result.stderr, "no such LLM profile")
        assert(!result.stderr.contains("unknown option"))
    }

    @Test
    fun `ask refuses a profile that is switched off`() {
        val added = cli.run(
            "llm", "add",
            "--name", "retired", "--provider", "openai-compatible", "--model", "test-model",
            "--endpoint", "http://127.0.0.1:1",
        )
        assertEquals(0, added.exitCode, "adding the retired profile failed: stderr=${added.stderr}")
        // Switched off the way the profile screen does it: the CLI has no command that retires a profile,
        // and a repaired profile is left in this same state by the migration.
        cli.context.let { context ->
            val profile = context.llm.findByName("retired")!!
            context.llm.update(profile.id, profile.copy(enabled = false))
        }

        val result = cli.run(
            "ask",
            "--collection", "Default", "--profile", "retired", "question",
        )

        assertNotEquals(0, result.exitCode)
        assertContains(result.stdout + result.stderr, "disabled")
        assertFalse(result.stderr.contains("Exception in thread"), "a refusal is a message, not a stack trace")
    }

    @Test
    fun `a corrected answer is surfaced instead of the streamed text`() {
        assertEquals("corrected [S1]", correctedTail("initial [S999]", "corrected [S1]"))
        assertEquals(null, correctedTail("same text [S1]", "same text [S1]"))
    }

    @Test
    fun `ask --prompt Unknown fails with a message saying the prompt does not exist`() {
        addProfile("asker", endpoint = "http://127.0.0.1:1")

        val result = cli.run(
            "ask",
            "--collection", "Default", "--profile", "asker", "--prompt", "Unknown", "question",
        )

        assertNotEquals(0, result.exitCode)
        assertContains(result.stdout + result.stderr, "no prompt with that name or id")
        assertFalse(result.stderr.contains("Exception in thread"), "a refusal is a message, not a stack trace")
    }

    @Test
    fun `ask --prompt Short resolves the stored prompt instead of refusing it`() {
        // The answer itself needs a query embedder, which the CLI process does not have under test, so this
        // checks only that the named prompt is found. Composing its body into the request is covered by the
        // Ask service tests.
        val short = directory.resolve("short.txt")
        Files.writeString(short, "Answer in one sentence.")
        val created = cli.run(
            "collection", "prompt", "create", "Default",
            "--role", "ask", "--name", "Short", "--file", short.toString(),
        )
        assertEquals(0, created.exitCode, created.stderr)
        addProfile("asker", endpoint = "http://127.0.0.1:1")

        val result = cli.run(
            "ask",
            "--collection", "Default", "--profile", "asker", "--prompt", "Short", "question",
        )

        assertFalse(
            (result.stdout + result.stderr).contains("no Ask prompt named"),
            "a stored prompt must be found by name: ${result.stderr}",
        )
    }

    private fun addProfile(name: String, endpoint: String) {
        val added = cli.run(
            "llm", "add",
            "--name", name, "--provider", "openai-compatible", "--model", "test-model",
            "--endpoint", endpoint,
        )
        assertEquals(0, added.exitCode, "adding the profile failed: stderr=${added.stderr}")
    }
}
