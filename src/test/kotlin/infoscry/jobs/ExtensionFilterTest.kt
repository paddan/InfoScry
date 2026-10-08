package infoscry.jobs

import infoscry.domain.CollectionId
import infoscry.extract.ExtractionSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The extension filter an import carries: how a list is normalised, what it admits, and how the payload keeps it. */
class ExtensionFilterTest {

    @Test
    fun `an empty filter admits every file`() {
        assertTrue(ExtensionFilter.NONE.admits("anything.at.all"))
        assertTrue(ExtensionFilter.NONE.admits("README"))
        assertTrue(ExtensionFilter.of(emptyList(), emptyList()).admits("report.pdf"))
    }

    @Test
    fun `an include list admits only its extensions`() {
        val filter = ExtensionFilter.of(include = listOf("pdf", "docx"), exclude = emptyList())

        assertTrue(filter.admits("report.pdf"))
        assertTrue(filter.admits("letter.docx"))
        assertFalse(filter.admits("notes.txt"))
        assertFalse(filter.admits("README"), "a file with no extension is not in an include list")
    }

    @Test
    fun `an exclude list admits everything else`() {
        val filter = ExtensionFilter.of(include = emptyList(), exclude = listOf("tmp", "log"))

        assertFalse(filter.admits("scratch.tmp"))
        assertFalse(filter.admits("run.log"))
        assertTrue(filter.admits("report.pdf"))
        assertTrue(filter.admits("README"))
    }

    @Test
    fun `extensions are matched case-insensitively with or without the leading dot`() {
        val included = ExtensionFilter.of(include = listOf(".PDF", "Docx"), exclude = emptyList())
        assertTrue(included.admits("Report.pdf"))
        assertTrue(included.admits("LETTER.DOCX"))

        val excluded = ExtensionFilter.of(include = emptyList(), exclude = listOf(".LOG"))
        assertFalse(excluded.admits("run.log"))
        assertFalse(excluded.admits("RUN.Log"))
    }

    @Test
    fun `only the last extension of a name counts`() {
        val filter = ExtensionFilter.of(include = listOf("gz"), exclude = emptyList())

        assertTrue(filter.admits("backup.tar.gz"))
        assertFalse(filter.admits("backup.gz.txt"))
    }

    @Test
    fun `a dot-file with no further extension has no extension`() {
        val include = ExtensionFilter.of(include = listOf("gitignore"), exclude = emptyList())
        assertFalse(include.admits(".gitignore"))

        val exclude = ExtensionFilter.of(include = emptyList(), exclude = listOf("gitignore"))
        assertTrue(exclude.admits(".gitignore"))
    }

    @Test
    fun `include and exclude together are refused`() {
        assertFailsWith<IllegalArgumentException> {
            ExtensionFilter.of(include = listOf("pdf"), exclude = listOf("log"))
        }
    }

    @Test
    fun `a blank extension is refused rather than read as no filter`() {
        assertFailsWith<IllegalArgumentException> { ExtensionFilter.of(include = listOf(""), exclude = emptyList()) }
        assertFailsWith<IllegalArgumentException> { ExtensionFilter.of(include = listOf("."), exclude = emptyList()) }
        assertFailsWith<IllegalArgumentException> { ExtensionFilter.of(include = emptyList(), exclude = listOf("  ")) }
    }

    @Test
    fun `an extension that names a path is refused`() {
        assertFailsWith<IllegalArgumentException> {
            ExtensionFilter.of(include = listOf("docs/pdf"), exclude = emptyList())
        }
    }

    @Test
    fun `repeated extensions collapse to one entry`() {
        val filter = ExtensionFilter.of(include = listOf("PDF", ".pdf", "pdf"), exclude = emptyList())

        assertEquals(listOf("pdf"), filter.include)
    }

    @Test
    fun `the payload keeps the filter it was queued with`() {
        val filter = ExtensionFilter.of(include = listOf("pdf"), exclude = emptyList())
        val payload = ImportJobPayload.of(
            collectionId = CollectionId("default"),
            sources = listOf("/data/inbox"),
            settings = ExtractionSettings(ocrLanguages = "eng"),
            recursive = true,
            extensions = filter,
        )

        val decoded = ImportJobPayload.decode(payload.encode())

        assertEquals(filter, decoded.extensions)
        assertTrue(decoded.extensions.admits("report.pdf"))
        assertFalse(decoded.extensions.admits("notes.txt"))
    }

    @Test
    fun `a payload written before the filter existed reads as no filter`() {
        val legacy = ImportJobPayload.of(
            collectionId = CollectionId("default"),
            sources = listOf("/data/inbox"),
            settings = ExtractionSettings(ocrLanguages = "eng"),
        ).encode()
        assertFalse(legacy.contains("extensions"), "an empty filter is not written to the payload")

        assertEquals(ExtensionFilter.NONE, ImportJobPayload.decode(legacy).extensions)
    }
}
