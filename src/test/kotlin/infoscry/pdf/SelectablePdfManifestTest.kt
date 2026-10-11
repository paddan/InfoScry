package infoscry.pdf

import infoscry.server.ApiJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SelectablePdfManifestTest {

    private val fontHash = oracleSha256("font bytes")

    // Acceptance 3: unavailable pages always carry a safe reason; other coverage never does.

    @Test
    fun `a page result carries a safe reason exactly when it is unavailable`() {
        assertFailsWith<IllegalArgumentException> { PdfPageResult(0, PdfPageCoverage.UNAVAILABLE, null) }
        assertFailsWith<IllegalArgumentException> { PdfPageResult(0, PdfPageCoverage.NATIVE, "NO_ALIGNMENT") }
        assertFailsWith<IllegalArgumentException> { PdfPageResult(0, PdfPageCoverage.UNAVAILABLE, "no alignment here") }
        assertFailsWith<IllegalArgumentException> { PdfPageResult(-1, PdfPageCoverage.NATIVE, null) }
        assertEquals("NO_ALIGNMENT", PdfPageResult(3, PdfPageCoverage.UNAVAILABLE, "NO_ALIGNMENT").reasonCode)
    }

    // Acceptance 4: manifest identity and contiguous physical pages.

    @Test
    fun `manifest pages are contiguous physical pages from zero`() {
        assertFailsWith<IllegalArgumentException> {
            SelectablePdfManifest("rev-1", "selectable-pdf-renderer-1", fontHash, emptyList())
        }
        assertFailsWith<IllegalArgumentException> {
            SelectablePdfManifest(
                "rev-1",
                "selectable-pdf-renderer-1",
                fontHash,
                listOf(PdfPageResult(0, PdfPageCoverage.NATIVE, null), PdfPageResult(2, PdfPageCoverage.NATIVE, null)),
            )
        }
        assertEquals(
            2,
            SelectablePdfManifest(
                "rev-1",
                "selectable-pdf-renderer-1",
                fontHash,
                listOf(PdfPageResult(0, PdfPageCoverage.NATIVE, null), PdfPageResult(1, PdfPageCoverage.OCR, null)),
            ).pages.size,
        )
    }

    @Test
    fun `manifest identity, renderer and font hash are validated`() {
        val pages = listOf(PdfPageResult(0, PdfPageCoverage.NATIVE, null))
        assertFailsWith<IllegalArgumentException> { SelectablePdfManifest(" ", "selectable-pdf-renderer-1", fontHash, pages) }
        assertFailsWith<IllegalArgumentException> { SelectablePdfManifest("rev-1", "bad renderer", fontHash, pages) }
        assertFailsWith<IllegalArgumentException> { SelectablePdfManifest("rev-1", "selectable-pdf-renderer-1", "ABC", pages) }
    }

    // Acceptance 4: the coverage counts separate selectable, blank and unavailable pages.

    @Test
    fun `coverage counts separate selectable, blank and unavailable pages`() {
        val manifest = SelectablePdfManifest(
            "rev-1",
            "selectable-pdf-renderer-1",
            fontHash,
            listOf(
                PdfPageResult(0, PdfPageCoverage.NATIVE, null),
                PdfPageResult(1, PdfPageCoverage.OCR, null),
                PdfPageResult(2, PdfPageCoverage.BLANK, null),
                PdfPageResult(3, PdfPageCoverage.UNAVAILABLE, "NO_ALIGNMENT"),
            ),
        )
        assertEquals(2, manifest.selectablePageCount)
        assertEquals(1, manifest.blankPageCount)
        assertEquals(listOf(PdfPageResult(3, PdfPageCoverage.UNAVAILABLE, "NO_ALIGNMENT")), manifest.unavailablePageResults)
    }

    @Test
    fun `manifest round-trips through ApiJson and omits null page reasons`() {
        val manifest = SelectablePdfManifest(
            "rev-1",
            "selectable-pdf-renderer-1",
            fontHash,
            listOf(PdfPageResult(0, PdfPageCoverage.NATIVE, null), PdfPageResult(1, PdfPageCoverage.UNAVAILABLE, "NO_ALIGNMENT")),
        )
        val json = ApiJson.encodeToString(SelectablePdfManifest.serializer(), manifest)
        assertTrue(json.contains("""{"ordinal":0,"coverage":"NATIVE"}"""), json)
        assertEquals(manifest, ApiJson.decodeFromString(SelectablePdfManifest.serializer(), json))
    }
}
