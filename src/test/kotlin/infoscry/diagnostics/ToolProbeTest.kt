package infoscry.diagnostics

import infoscry.extract.writeFakeExecutable
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * What the machine is asked before the pipeline relies on it.
 *
 * The probe is what stands between "the tool is installed" and "the tool can read the language the
 * collection asked for", and those are different facts: a Tesseract without a language pack is installed
 * and useless. The tool is a script here, so every answer — present, absent, unable to list its languages
 * — is produced deliberately instead of being whatever this machine happens to have.
 */
class ToolProbeTest {

    private lateinit var directory: Path

    @BeforeTest
    fun createTemporaryDirectory() {
        directory = Files.createTempDirectory("infoscry-tool-probe")
    }

    @AfterTest
    fun removeTemporaryDirectory() {
        directory.toFile().deleteRecursively()
    }

    @Test
    fun `an installed tool reports its version and the languages it can read`() = runBlocking {
        val tool = writeFakeExecutable(
            directory,
            "tesseract",
            """
            case "${'$'}1" in
              --version) echo "tesseract 5.5.3"; echo " leptonica-1.87.0" ;;
              --list-langs)
                echo 'List of available languages in "/opt/homebrew/share/tessdata/" (163):'
                echo eng
                echo swe
                ;;
            esac
            """.trimIndent(),
        )

        val status = ToolProbe.tesseract(tool.toString())

        assertEquals(ToolProbe.NAME, status.name)
        assertEquals(tool.toString(), status.executable)
        assertTrue(status.available)
        assertEquals("tesseract 5.5.3", status.version, "the tool's own banner line is not a version")
        assertEquals(listOf("eng", "swe"), status.languages)
        assertNull(status.remedy)
        assertContains(status.summary, "tesseract 5.5.3")
    }

    @Test
    fun `a tool that is not installed says what to install`() = runBlocking {
        val missing = directory.resolve("not-installed")

        val status = ToolProbe.tesseract(missing.toString())

        assertFalse(status.available)
        assertNull(status.version)
        assertEquals(emptyList(), status.languages)
        val remedy = requireNotNull(status.remedy)
        assertContains(remedy, "install")
        assertContains(remedy, "tesseract")
        assertContains(status.summary, "not installed")
    }

    @Test
    fun `a tool that cannot report a version is not usable`() = runBlocking {
        val tool = writeFakeExecutable(directory, "tesseract", "exit 1")

        val status = ToolProbe.tesseract(tool.toString())

        assertFalse(status.available)
        assertEquals(emptyList(), status.languages, "a tool that cannot run was asked more questions")
    }

    @Test
    fun `a tool that cannot list its languages is installed and has none to read`() = runBlocking {
        val tool = writeFakeExecutable(
            directory,
            "tesseract",
            """
            case "${'$'}1" in
              --version) echo "tesseract 5.5.3" ;;
              --list-langs) echo "Error opening data file" 1>&2; exit 1 ;;
            esac
            """.trimIndent(),
        )

        val status = ToolProbe.tesseract(tool.toString())

        assertTrue(status.available, "the tool is there; what is missing is a language pack")
        assertEquals("tesseract 5.5.3", status.version)
        assertEquals(emptyList(), status.languages)
        assertContains(status.summary, "0 languages")
    }

    @Test
    fun `a tool that never answers is unusable rather than an exception`() = runBlocking {
        val tool = writeFakeExecutable(directory, "tesseract", "sleep 60")

        val status = ToolProbe.tesseract(tool.toString(), timeout = Duration.ofMillis(700))

        assertFalse(status.available, "a tool that hung was reported as usable")
        assertNotNull(status.remedy, "a tool that cannot be used has to say what to do about it")
        assertEquals(emptyList(), status.languages, "a tool that never answered was asked more questions")
    }
}
