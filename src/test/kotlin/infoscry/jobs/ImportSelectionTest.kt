package infoscry.jobs

import infoscry.extract.ExtractorRegistry
import infoscry.extract.MediaTypeDetector
import infoscry.extract.TextualFallbackExtractor
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The order of the selection seam: collection ignore list, then include or exclude, then what the bytes are. */
class ImportSelectionTest {

    private lateinit var directory: Path
    private val selection = ImportSelection(MediaTypeDetector(), ExtractorRegistry(emptyList(), TextualFallbackExtractor()))

    @BeforeTest
    fun createDirectory() {
        directory = Files.createTempDirectory("infoscry-selection")
    }

    @AfterTest
    fun removeDirectory() {
        directory.toFile().deleteRecursively()
    }

    @Test
    fun `the ignore list is decided first, before the extension filter and before any content is read`() {
        // A path that exists but cannot be inspected as a file is admitted by the content step ("unreadable files are
        // admitted"), so a false answer for it can only come from a step that ran before the content step.
        val uninspectable = directory.resolve("scratch.tmp").also { Files.createDirectory(it) }
        val ignore = IgnorePatterns.of(listOf("*.tmp"))
        val include = ExtensionFilter.of(include = listOf("tmp"), exclude = emptyList())

        assertFalse(selection.admits(uninspectable, "scratch.tmp", exists = true, ignore, include))
        assertTrue(
            selection.admits(uninspectable, "scratch.tmp", exists = true, IgnorePatterns.NONE, include),
            "without the ignore list the same file passes the filter and is admitted by the content step",
        )
    }

    @Test
    fun `an ignored missing source is skipped and does not become a missing-source item`() {
        val gone = directory.resolve(".DS_Store")

        assertFalse(selection.admits(gone, ".DS_Store", exists = false, IgnorePatterns.of(IgnorePatterns.DEFAULTS), ExtensionFilter.NONE))
        assertTrue(selection.admits(gone, ".DS_Store", exists = false, IgnorePatterns.NONE, ExtensionFilter.NONE))
    }

    @Test
    fun `the extension filter still applies to a file the ignore list keeps`() {
        val exclude = ExtensionFilter.of(include = emptyList(), exclude = listOf("log"))

        assertFalse(selection.admits(directory.resolve("a.log"), "a.log", exists = false, IgnorePatterns.of(listOf("*.tmp")), exclude))
    }
}
