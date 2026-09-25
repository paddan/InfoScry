package infoscry.cli

import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class AskCommandTest {
    private lateinit var directory: java.nio.file.Path

    @BeforeTest
    fun createDataDirectory() {
        directory = Files.createTempDirectory("infoscry-ask-cli")
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
    fun `a corrected answer is surfaced instead of the streamed text`() {
        assertEquals("corrected [S1]", correctedTail("initial [S999]", "corrected [S1]"))
        assertEquals(null, correctedTail("same text [S1]", "same text [S1]"))
    }
}
