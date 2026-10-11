package infoscry.pdf

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PdfWriteModelsTest {

    private val reading: Path = Path.of("reading", "page-0.png")

    private fun nativePage(ordinal: Int) =
        PdfPageInput(ordinal, CANONICAL_TEXT, null, null, PAGE_GEOMETRY, PdfPageMode.NATIVE)

    private fun manifest(vararg pages: PdfPageResult) = SelectablePdfManifest(
        revisionId = "rev-1",
        rendererVersion = "selectable-pdf-renderer-1",
        fontHash = oracleSha256("font bytes"),
        pages = pages.toList(),
    )

    // Acceptance 3 and the writer modes: each mode carries exactly the inputs it needs.

    @Test
    fun `NATIVE pages copy the original and take no added layer`() {
        assertFailsWith<IllegalArgumentException> {
            PdfPageInput(0, CANONICAL_TEXT, null, layoutOf(), PAGE_GEOMETRY, PdfPageMode.NATIVE)
        }
        assertFailsWith<IllegalArgumentException> {
            PdfPageInput(0, CANONICAL_TEXT, reading, null, PAGE_GEOMETRY, PdfPageMode.NATIVE)
        }
        assertEquals(PdfPageMode.NATIVE, nativePage(0).mode)
    }

    @Test
    fun `OCR pages need a complete layout that belongs to their text and geometry`() {
        fun ocr(layout: PageTextLayout?, text: String? = CANONICAL_TEXT, image: Path? = reading, geometry: PdfPageGeometry = PAGE_GEOMETRY) =
            PdfPageInput(0, text, image, layout, geometry, PdfPageMode.OCR)

        assertFailsWith<IllegalArgumentException> { ocr(layout = null) }
        assertFailsWith<IllegalArgumentException> {
            ocr(layout = layoutOf(complete = false, reasonCode = "NO_ALIGNMENT", spans = emptyList()))
        }
        assertFailsWith<IllegalArgumentException> { ocr(layout = layoutOf(), geometry = PAGE_GEOMETRY.copy(rotation = 90)) }
        assertFailsWith<PageTextLayoutProvenanceException> { ocr(layout = layoutOf(text = "Ett annat stycke."), text = CANONICAL_TEXT) }
        assertFailsWith<IllegalArgumentException> { ocr(layout = layoutOf(), image = null) }
        assertFailsWith<IllegalArgumentException> { ocr(layout = layoutOf(), text = null) }
        assertEquals(PdfPageMode.OCR, ocr(layout = layoutOf()).mode)
    }

    @Test
    fun `a layout is never paired with text it was not made from, in any mode`() {
        assertFailsWith<PageTextLayoutProvenanceException> {
            PdfPageInput(0, "Ett annat stycke.", null, layoutOf(complete = false, reasonCode = "NO_ALIGNMENT", spans = emptyList()), PAGE_GEOMETRY, PdfPageMode.UNAVAILABLE)
        }
    }

    @Test
    fun `BLANK requires verified blankness, not an empty-looking reading`() {
        assertFailsWith<IllegalArgumentException> {
            PdfPageInput(0, CANONICAL_TEXT, reading, null, PAGE_GEOMETRY, PdfPageMode.BLANK)
        }
        // A layout that places any text is not blank, even if the canonical reading is empty.
        assertFailsWith<IllegalArgumentException> {
            PdfPageInput(0, null, reading, layoutOf(), PAGE_GEOMETRY, PdfPageMode.BLANK)
        }
        assertEquals(PdfPageMode.BLANK, PdfPageInput(0, "   ", null, null, PAGE_GEOMETRY, PdfPageMode.BLANK).mode)
        val verifiedEmpty = layoutOf(text = "", spans = emptyList(), complete = true)
        assertEquals(PdfPageMode.BLANK, PdfPageInput(0, "", reading, verifiedEmpty, PAGE_GEOMETRY, PdfPageMode.BLANK).mode)
    }

    @Test
    fun `UNAVAILABLE keeps a page without trusting an incomplete layout`() {
        assertEquals(
            PdfPageMode.UNAVAILABLE,
            PdfPageInput(0, CANONICAL_TEXT, reading, null, PAGE_GEOMETRY, PdfPageMode.UNAVAILABLE).mode,
        )
        val incomplete = layoutOf(complete = false, reasonCode = "NO_ALIGNMENT", spans = emptyList())
        assertEquals(
            PdfPageMode.UNAVAILABLE,
            PdfPageInput(0, CANONICAL_TEXT, reading, incomplete, PAGE_GEOMETRY, PdfPageMode.UNAVAILABLE).mode,
        )
    }

    @Test
    fun `UNAVAILABLE never carries a complete layout it cannot verify`() {
        // Canonical text is null, so no provenance check runs; a complete layout would be unverified trust.
        assertFailsWith<IllegalArgumentException> {
            PdfPageInput(0, null, reading, layoutOf(complete = true), PAGE_GEOMETRY, PdfPageMode.UNAVAILABLE)
        }
        // No regression: a missing layout and an incomplete layout both stay valid without canonical text.
        assertEquals(
            PdfPageMode.UNAVAILABLE,
            PdfPageInput(0, null, reading, null, PAGE_GEOMETRY, PdfPageMode.UNAVAILABLE).mode,
        )
        val incomplete = layoutOf(complete = false, reasonCode = "NO_ALIGNMENT", spans = emptyList())
        assertEquals(
            PdfPageMode.UNAVAILABLE,
            PdfPageInput(0, null, reading, incomplete, PAGE_GEOMETRY, PdfPageMode.UNAVAILABLE).mode,
        )
    }

    @Test
    fun `page ordinals are non-negative`() {
        assertFailsWith<IllegalArgumentException> { nativePage(-1) }
    }

    // Writer request: every physical page, in order, never the original.

    private val original: Path = Path.of("in", "original.pdf")
    private val output: Path = Path.of("out", "selectable.pdf")

    @Test
    fun `write request requires every physical page in contiguous ordinal order`() {
        assertFailsWith<IllegalArgumentException> { PdfWriteRequest(original, "rev-1", emptyList(), output) }
        assertFailsWith<IllegalArgumentException> {
            PdfWriteRequest(original, "rev-1", listOf(nativePage(0), nativePage(2)), output)
        }
        assertFailsWith<IllegalArgumentException> {
            PdfWriteRequest(original, "rev-1", listOf(nativePage(1), nativePage(0)), output)
        }
        assertEquals(2, PdfWriteRequest(original, "rev-1", listOf(nativePage(0), nativePage(1)), output).pages.size)
    }

    @Test
    fun `write request never targets the original file`() {
        assertFailsWith<IllegalArgumentException> {
            PdfWriteRequest(original, "rev-1", listOf(nativePage(0)), original)
        }
        assertFailsWith<IllegalArgumentException> {
            PdfWriteRequest(original, "rev-1", listOf(nativePage(0)), Path.of("in", "..", "in", "original.pdf"))
        }
    }

    @Test
    fun `write request needs a revision identity`() {
        assertFailsWith<IllegalArgumentException> { PdfWriteRequest(original, " ", listOf(nativePage(0)), output) }
    }

    @Test
    fun `write result requires a real file size and a SHA-256`() {
        val manifest = manifest(PdfPageResult(0, PdfPageCoverage.NATIVE, null))
        assertFailsWith<IllegalArgumentException> { PdfWriteResult(manifest, oracleSha256("pdf bytes"), 0L) }
        assertFailsWith<IllegalArgumentException> { PdfWriteResult(manifest, "not-a-hash", 1234L) }
        assertEquals(1234L, PdfWriteResult(manifest, oracleSha256("pdf bytes"), 1234L).bytes)
    }
}
