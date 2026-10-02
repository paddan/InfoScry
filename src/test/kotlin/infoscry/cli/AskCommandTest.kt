package infoscry.cli

import infoscry.AppContext
import infoscry.config.AppPaths
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

    @BeforeTest
    fun createDataDirectory() {
        directory = Files.createTempDirectory("infoscry-ask-cli")
        // A new archive has no automatic Default, and this class asks about a missing profile rather
        // than a missing collection, so the collection the request names is created explicitly.
        CliProcess.run("--data-dir", directory.toString(), "collection", "create", "Default")
    }

    @AfterTest
    fun removeDataDirectory() {
        directory.toFile().deleteRecursively()
    }

    @Test
    fun `ask accepts shared json and data directory options`() {
        val result = CliProcess.run(
            "ask", "--data-dir", directory.toString(), "--json",
            "--collection", "Default", "--profile", "missing", "question",
        )

        assertNotEquals(0, result.exitCode)
        assertContains(result.stdout + result.stderr, "no such LLM profile")
        assert(!result.stderr.contains("unknown option"))
    }

    @Test
    fun `ask refuses a profile that is switched off`() {
        val added = CliProcess.run(
            "llm", "add", "--data-dir", directory.toString(),
            "--name", "retired", "--provider", "openai-compatible", "--model", "test-model",
            "--endpoint", "http://127.0.0.1:1",
        )
        assertEquals(0, added.exitCode, "adding the retired profile failed: stderr=${added.stderr}")
        // Switched off the way the profile screen does it: the CLI has no command that retires a profile,
        // and a repaired profile is left in this same state by the migration.
        AppContext.open(AppPaths.of(directory)).use { context ->
            val profile = context.llm.findByName("retired")!!
            context.llm.update(profile.id, profile.copy(enabled = false))
        }

        val result = CliProcess.run(
            "ask", "--data-dir", directory.toString(),
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
}
