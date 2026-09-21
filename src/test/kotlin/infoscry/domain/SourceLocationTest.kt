package infoscry.domain

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SourceLocationTest {

    private val json = Json

    private fun assertRoundTrips(location: SourceLocation): String {
        val encoded = json.encodeToString(SourceLocation.serializer(), location)
        assertEquals(location, json.decodeFromString(SourceLocation.serializer(), encoded))
        return encoded
    }

    @Test
    fun `pdf page round trips and pages are one based`() {
        val location: SourceLocation = SourceLocation.PdfPage(14)
        val encoded = json.encodeToString(SourceLocation.serializer(), location)
        assertEquals(location, json.decodeFromString(SourceLocation.serializer(), encoded))
        assertFailsWith<IllegalArgumentException> { SourceLocation.PdfPage(0) }
    }

    @Test
    fun `pdf page uses a stable discriminator and rejects non positive pages`() {
        assertTrue(assertRoundTrips(SourceLocation.PdfPage(1)).contains("\"type\":\"pdf_page\""))
        assertFailsWith<IllegalArgumentException> { SourceLocation.PdfPage(-1) }
    }

    @Test
    fun `image round trips and requires a name`() {
        assertRoundTrips(SourceLocation.Image("scan-01.png"))
        assertFailsWith<IllegalArgumentException> { SourceLocation.Image("  ") }
    }

    @Test
    fun `word section round trips and requires an ordered paragraph range`() {
        val location = SourceLocation.WordSection(
            headingPath = listOf("Findings", "Offshore accounts"),
            paragraphStart = 8,
            paragraphEnd = 12,
        )
        assertRoundTrips(location)
        assertFailsWith<IllegalArgumentException> {
            SourceLocation.WordSection(listOf("Findings"), paragraphStart = 12, paragraphEnd = 8)
        }
        assertFailsWith<IllegalArgumentException> {
            SourceLocation.WordSection(listOf("Findings"), paragraphStart = -1, paragraphEnd = 4)
        }
    }

    @Test
    fun `word section rejects blank heading entries`() {
        assertFailsWith<IllegalArgumentException> {
            SourceLocation.WordSection(listOf("Findings", " "), paragraphStart = 1, paragraphEnd = 2)
        }
    }

    @Test
    fun `spreadsheet range round trips and rejects blank sheet or cells`() {
        val location = SourceLocation.SpreadsheetRange(
            sheet = "Transfers",
            startCell = "B17",
            endCell = "F24",
        )
        assertRoundTrips(location)
        assertFailsWith<IllegalArgumentException> {
            SourceLocation.SpreadsheetRange(" ", "B17", "F24")
        }
        assertFailsWith<IllegalArgumentException> {
            SourceLocation.SpreadsheetRange("Transfers", "", "F24")
        }
        assertFailsWith<IllegalArgumentException> {
            SourceLocation.SpreadsheetRange("Transfers", "B17", "  ")
        }
    }

    @Test
    fun `slide round trips and slides are one based`() {
        assertRoundTrips(SourceLocation.Slide(3))
        assertFailsWith<IllegalArgumentException> { SourceLocation.Slide(0) }
    }

    @Test
    fun `text lines round trip and line ranges are one based and ordered`() {
        assertRoundTrips(SourceLocation.TextLines(start = 10, end = 24))
        assertFailsWith<IllegalArgumentException> { SourceLocation.TextLines(start = 0, end = 24) }
        assertFailsWith<IllegalArgumentException> { SourceLocation.TextLines(start = 24, end = 10) }
    }

    @Test
    fun `html section round trips including the document root`() {
        assertRoundTrips(SourceLocation.HtmlSection(listOf("Charges", "Count two")))
        assertRoundTrips(SourceLocation.HtmlSection(emptyList()))
        assertFailsWith<IllegalArgumentException> { SourceLocation.HtmlSection(listOf("")) }
    }

    @Test
    fun `ebook section round trips with and without a chapter`() {
        val withChapter = SourceLocation.EbookSection(
            chapter = "Chapter 7",
            headingPath = listOf("Financial Records"),
            spineIndex = 6,
        )
        assertRoundTrips(withChapter)
        assertRoundTrips(SourceLocation.EbookSection(null, emptyList(), spineIndex = 0))
        assertFailsWith<IllegalArgumentException> {
            SourceLocation.EbookSection(" ", emptyList(), spineIndex = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            SourceLocation.EbookSection(null, emptyList(), spineIndex = -1)
        }
    }

    @Test
    fun `every variant decodes from its own encoding`() {
        val locations = listOf(
            SourceLocation.PdfPage(2),
            SourceLocation.Image("page.png"),
            SourceLocation.WordSection(listOf("A"), 1, 2),
            SourceLocation.SpreadsheetRange("Sheet1", "A1", "B2"),
            SourceLocation.Slide(4),
            SourceLocation.TextLines(1, 2),
            SourceLocation.HtmlSection(listOf("A")),
            SourceLocation.EbookSection("Chapter 1", listOf("A"), 0),
        )
        locations.forEach { assertRoundTrips(it) }
    }
}
