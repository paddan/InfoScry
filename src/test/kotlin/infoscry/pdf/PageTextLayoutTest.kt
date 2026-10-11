package infoscry.pdf

import infoscry.server.ApiJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PageTextLayoutTest {

    // Acceptance 2: finite matrices and geometry, positive sizes, quarter-turn rotation.

    @Test
    fun `rectangle rejects non-finite coordinates`() {
        assertFailsWith<IllegalArgumentException> { TextRectangle(Double.NaN, 0.0, 1.0, 1.0) }
        assertFailsWith<IllegalArgumentException> { TextRectangle(0.0, Double.POSITIVE_INFINITY, 1.0, 1.0) }
        assertFailsWith<IllegalArgumentException> { TextRectangle(0.0, 0.0, 1.0, Double.NEGATIVE_INFINITY) }
    }

    @Test
    fun `rectangle rejects zero or negative size`() {
        assertFailsWith<IllegalArgumentException> { box(0.0, 0.0, 0.0, 1.0) }
        assertFailsWith<IllegalArgumentException> { box(0.0, 0.0, 1.0, 0.0) }
        assertFailsWith<IllegalArgumentException> { box(0.0, 0.0, -1.0, 1.0) }
    }

    @Test
    fun `transform rejects non-finite entries`() {
        assertFailsWith<IllegalArgumentException> { ImageToPdf(Double.NaN, 0.0, 0.0, 1.0, 0.0, 0.0) }
        assertFailsWith<IllegalArgumentException> { ImageToPdf(1.0, 0.0, 0.0, 1.0, 0.0, Double.POSITIVE_INFINITY) }
    }

    @Test
    fun `transform rejects a singular matrix that would collapse the page`() {
        assertFailsWith<IllegalArgumentException> { ImageToPdf(a = 1.0, b = 2.0, c = 2.0, d = 4.0, e = 0.0, f = 0.0) }
    }

    @Test
    fun `transform accepts the identity and a flipped y axis`() {
        assertEquals(ImageToPdf(1.0, 0.0, 0.0, 1.0, 0.0, 0.0), ImageToPdf(1.0, 0.0, 0.0, 1.0, 0.0, 0.0))
        assertEquals(PAGE_TRANSFORM, ImageToPdf(6.12, 0.0, 0.0, -15.84, 0.0, 792.0))
    }

    @Test
    fun `geometry rejects non-positive media and crop dimensions`() {
        assertFailsWith<IllegalArgumentException> { PAGE_GEOMETRY.copy(mediaWidth = 0.0) }
        assertFailsWith<IllegalArgumentException> { PAGE_GEOMETRY.copy(mediaHeight = -1.0) }
        assertFailsWith<IllegalArgumentException> { PAGE_GEOMETRY.copy(cropWidth = 0.0) }
        assertFailsWith<IllegalArgumentException> { PAGE_GEOMETRY.copy(cropHeight = -5.0) }
    }

    @Test
    fun `crop box must lie inside the media box`() {
        assertFailsWith<IllegalArgumentException> { PAGE_GEOMETRY.copy(cropX = -1.0) }
        assertFailsWith<IllegalArgumentException> { PAGE_GEOMETRY.copy(cropY = -1.0) }
        assertFailsWith<IllegalArgumentException> { PAGE_GEOMETRY.copy(cropWidth = 613.0) }
        assertFailsWith<IllegalArgumentException> { PAGE_GEOMETRY.copy(cropHeight = 793.0) }
        assertFailsWith<IllegalArgumentException> { PAGE_GEOMETRY.copy(cropX = 10.0, cropWidth = 603.0) }
        // The media box has its own origin: a crop starting at x = 0 lies outside a media box starting at x = 100.
        assertFailsWith<IllegalArgumentException> { PAGE_GEOMETRY.copy(mediaX = 100.0) }
    }

    @Test
    fun `a crop box inside the media box, or flush with its edges, is accepted`() {
        val inset = PAGE_GEOMETRY.copy(cropX = 10.0, cropY = 20.0, cropWidth = 500.0, cropHeight = 600.0)
        assertEquals(500.0, inset.cropWidth)
        assertEquals(PAGE_GEOMETRY, PAGE_GEOMETRY.copy(cropX = 0.0, cropWidth = 612.0))
        val offsetMedia = PAGE_GEOMETRY.copy(mediaX = 100.0, cropX = 100.0, cropWidth = 612.0)
        assertEquals(100.0, offsetMedia.cropX)
    }

    @Test
    fun `geometry rejects non-finite origins and sizes`() {
        assertFailsWith<IllegalArgumentException> { PAGE_GEOMETRY.copy(mediaX = Double.NaN) }
        assertFailsWith<IllegalArgumentException> { PAGE_GEOMETRY.copy(cropY = Double.POSITIVE_INFINITY) }
    }

    @Test
    fun `geometry rejects a non-positive or non-finite user unit`() {
        assertFailsWith<IllegalArgumentException> { PAGE_GEOMETRY.copy(userUnit = 0.0) }
        assertFailsWith<IllegalArgumentException> { PAGE_GEOMETRY.copy(userUnit = -1.0) }
        assertFailsWith<IllegalArgumentException> { PAGE_GEOMETRY.copy(userUnit = Double.NaN) }
    }

    @Test
    fun `geometry accepts exactly the four quarter turns`() {
        listOf(0, 90, 180, 270).forEach { turn -> assertEquals(turn, PAGE_GEOMETRY.copy(rotation = turn).rotation) }
        listOf(45, -90, 360, 91).forEach { turn ->
            assertFailsWith<IllegalArgumentException>("rotation $turn") { PAGE_GEOMETRY.copy(rotation = turn) }
        }
    }

    // Acceptance 2 and 4: structure of the layout record and its hashes.

    @Test
    fun `layout rejects a non-positive version and malformed hashes`() {
        assertFailsWith<IllegalArgumentException> { layoutOf(version = 0) }
        assertFailsWith<IllegalArgumentException> { layoutOf(textSha256 = oracleSha256(CANONICAL_TEXT).uppercase()) }
        assertFailsWith<IllegalArgumentException> { layoutOf(textSha256 = "abc") }
        assertFailsWith<IllegalArgumentException> { layoutOf(imageSha256 = "z".repeat(64)) }
    }

    @Test
    fun `layout rejects a non-positive image size`() {
        assertFailsWith<IllegalArgumentException> { layoutOf(width = 0) }
        assertFailsWith<IllegalArgumentException> { layoutOf(height = -1) }
    }

    @Test
    fun `layout rejects an unsafe positioning version`() {
        assertFailsWith<IllegalArgumentException> { layoutOf(positioningVersion = "") }
        assertFailsWith<IllegalArgumentException> { layoutOf(positioningVersion = "local anchor") }
        assertFailsWith<IllegalArgumentException> { layoutOf(positioningVersion = "v1\nsecret") }
    }

    // Acceptance 3: complete and incomplete definitions.

    @Test
    fun `a complete layout cannot carry a reason code`() {
        assertFailsWith<IllegalArgumentException> { layoutOf(complete = true, reasonCode = "NO_ALIGNMENT") }
    }

    @Test
    fun `an incomplete layout requires a safe reason code`() {
        assertFailsWith<IllegalArgumentException> { layoutOf(complete = false, reasonCode = null, spans = emptyList()) }
        assertFailsWith<IllegalArgumentException> {
            layoutOf(complete = false, reasonCode = "ambiguous match near line two", spans = emptyList())
        }
        assertFailsWith<IllegalArgumentException> {
            layoutOf(complete = false, reasonCode = "no_alignment", spans = emptyList())
        }
    }

    @Test
    fun `an incomplete layout stays constructible and verifiable with a safe reason`() {
        val empty = layoutOf(complete = false, reasonCode = "NO_UNIQUE_ANCHOR", spans = emptyList())
        assertFalse(empty.complete)
        assertEquals("NO_UNIQUE_ANCHOR", empty.reasonCode)
        assertFalse(empty.verifiedBlank, "an unpositioned page is not a verified blank page")
        assertSame(empty, empty.verifiedAgainst(CANONICAL_TEXT))

        val partial = layoutOf(complete = false, reasonCode = "MISSING_LINE", spans = completeWords().take(2))
        assertSame(partial, partial.verifiedAgainst(CANONICAL_TEXT))
    }

    @Test
    fun `spans with duplicate order are rejected`() {
        val duplicate = completeWords().mapIndexed { index, span -> span.copy(order = if (index == 2) 1 else span.order) }
        assertFailsWith<IllegalArgumentException> { layoutOf(spans = duplicate) }
    }

    @Test
    fun `overlapping spans are rejected`() {
        val overlapping = listOf(
            word(0, 5, 0, box(0.0, 0.0, 10.0, 10.0)),
            word(4, 12, 1, box(10.0, 0.0, 10.0, 10.0)),
        )
        assertFailsWith<IllegalArgumentException> { layoutOf(complete = false, reasonCode = "OVERLAP", spans = overlapping) }
    }

    @Test
    fun `order must follow the canonical reading`() {
        val reversed = listOf(
            word(13, 16, 0, box(72.0, 5.0, 14.0, 10.0)),
            word(0, 3, 1, box(4.0, 5.0, 20.0, 10.0)),
        )
        assertFailsWith<IllegalArgumentException> { layoutOf(complete = false, reasonCode = "ORDER", spans = reversed) }
    }

    @Test
    fun `rectangles must be inside the image`() {
        assertFailsWith<IllegalArgumentException> {
            layoutOf(complete = false, reasonCode = "OUT", spans = listOf(word(0, 3, 0, box(-1.0, 0.0, 10.0, 10.0))))
        }
        assertFailsWith<IllegalArgumentException> {
            layoutOf(complete = false, reasonCode = "OUT", spans = listOf(word(0, 3, 0, box(95.0, 0.0, 10.0, 10.0))))
        }
        assertFailsWith<IllegalArgumentException> {
            layoutOf(complete = false, reasonCode = "OUT", spans = listOf(word(0, 3, 0, box(0.0, 45.0, 10.0, 10.0))))
        }
        // The exact image edge is inside the image.
        val edge = layoutOf(complete = false, reasonCode = "EDGE", spans = listOf(word(0, 3, 0, box(90.0, 40.0, 10.0, 10.0))))
        assertEquals(1, edge.spans.size)
    }

    @Test
    fun `span offsets must be non-negative and non-empty`() {
        assertFailsWith<IllegalArgumentException> { word(-1, 3, 0, box(0.0, 0.0, 1.0, 1.0)) }
        assertFailsWith<IllegalArgumentException> { word(3, 3, 0, box(0.0, 0.0, 1.0, 1.0)) }
        assertFailsWith<IllegalArgumentException> { word(5, 3, 0, box(0.0, 0.0, 1.0, 1.0)) }
        assertFailsWith<IllegalArgumentException> { word(0, 3, -1, box(0.0, 0.0, 1.0, 1.0)) }
    }

    // Acceptance 2 and 3: canonical text is a parameter, so the UTF-16 ranges and coverage are checked against it.

    @Test
    fun `text hash mismatch is corrupt provenance`() {
        val layout = layoutOf()
        assertFailsWith<PageTextLayoutProvenanceException> { layout.verifiedAgainst("Åsa betalade 120 kronor!") }
    }

    @Test
    fun `provenance mismatch is a distinct exception, not the structural IllegalArgumentException`() {
        val layout = layoutOf()
        val error = assertFailsWith<PageTextLayoutProvenanceException> { layout.verifiedAgainst("other text") }
        assertTrue(error !is IllegalArgumentException, "a provenance mismatch must not be an IllegalArgumentException")
    }

    @Test
    fun `complete layout covering every non-whitespace character verifies`() {
        val layout = layoutOf()
        assertSame(layout, layout.verifiedAgainst(CANONICAL_TEXT))
    }

    @Test
    fun `an uncovered non-whitespace character cannot be complete`() {
        val missingLast = completeWords().dropLast(1)
        val layout = layoutOf(spans = missingLast)
        assertFailsWith<IllegalArgumentException> { layout.verifiedAgainst(CANONICAL_TEXT) }
    }

    @Test
    fun `whitespace gaps between tokens are permitted`() {
        // completeWords() leaves the spaces at offsets 3, 12 and 16 uncovered; they must not block verification.
        val layout = layoutOf()
        assertSame(layout, layout.verifiedAgainst(CANONICAL_TEXT))
    }

    @Test
    fun `a range may not split a surrogate pair`() {
        val text = "a😀b" // a, U+1F600 (two UTF-16 units), b: length 4
        val good = listOf(
            word(0, 1, 0, box(0.0, 0.0, 10.0, 10.0)),
            word(1, 3, 1, box(10.0, 0.0, 10.0, 10.0)),
            word(3, 4, 2, box(20.0, 0.0, 10.0, 10.0)),
        )
        val layout = layoutOf(text = text, spans = good)
        assertSame(layout, layout.verifiedAgainst(text))
        val endsInsidePair = listOf(
            word(0, 2, 0, box(0.0, 0.0, 10.0, 10.0)),
            word(2, 4, 1, box(10.0, 0.0, 20.0, 10.0)),
        )
        assertFailsWith<IllegalArgumentException> { layoutOf(text = text, spans = endsInsidePair).verifiedAgainst(text) }
        val startsInsidePair = listOf(
            word(0, 1, 0, box(0.0, 0.0, 10.0, 10.0)),
            word(1, 2, 1, box(10.0, 0.0, 10.0, 10.0)),
            word(2, 4, 2, box(20.0, 0.0, 10.0, 10.0)),
        )
        assertFailsWith<IllegalArgumentException> { layoutOf(text = text, spans = startsInsidePair).verifiedAgainst(text) }
    }

    @Test
    fun `ranges must stay inside the canonical text`() {
        val beyond = listOf(word(0, CANONICAL_TEXT.length + 1, 0, box(0.0, 0.0, 10.0, 10.0)))
        assertFailsWith<IllegalArgumentException> {
            layoutOf(complete = false, reasonCode = "OUT_OF_TEXT", spans = beyond).verifiedAgainst(CANONICAL_TEXT)
        }
    }

    @Test
    fun `a WORD span cannot contain whitespace`() {
        val spanning = listOf(word(0, 7, 0, box(0.0, 0.0, 40.0, 10.0)))
        assertFailsWith<IllegalArgumentException> {
            layoutOf(complete = false, reasonCode = "WORD_SPANS_SPACE", spans = spanning).verifiedAgainst(CANONICAL_TEXT)
        }
    }

    @Test
    fun `a LINE span cannot contain a line break but one span per visual line is valid`() {
        val text = "Åsa\nbetalade"
        val oneLineSpanOverBreak = listOf(
            PositionedTextSpan(0, text.length, box(0.0, 0.0, 60.0, 10.0), SpanGranularity.LINE, 0),
        )
        assertFailsWith<IllegalArgumentException> {
            layoutOf(text = text, spans = oneLineSpanOverBreak, complete = false, reasonCode = "MULTI_LINE")
                .verifiedAgainst(text)
        }
        val perLine = listOf(
            PositionedTextSpan(0, 3, box(0.0, 0.0, 30.0, 10.0), SpanGranularity.LINE, 0),
            PositionedTextSpan(4, text.length, box(0.0, 12.0, 60.0, 10.0), SpanGranularity.LINE, 1),
        )
        val layout = layoutOf(text = text, spans = perLine)
        assertSame(layout, layout.verifiedAgainst(text))
    }

    @Test
    fun `a span must carry visible text`() {
        val whitespaceOnly = listOf(word(3, 4, 0, box(0.0, 0.0, 10.0, 10.0)))
        assertFailsWith<IllegalArgumentException> {
            layoutOf(complete = false, reasonCode = "BLANK_SPAN", spans = whitespaceOnly).verifiedAgainst(CANONICAL_TEXT)
        }
    }

    @Test
    fun `empty or whitespace-only text is verified blank only when complete with no spans`() {
        val verifiedEmpty = layoutOf(text = "", spans = emptyList(), complete = true)
        assertTrue(verifiedEmpty.verifiedBlank)
        assertSame(verifiedEmpty, verifiedEmpty.verifiedAgainst(""))

        val verifiedWhitespace = layoutOf(text = "   ", spans = emptyList(), complete = true)
        assertTrue(verifiedWhitespace.verifiedBlank)
        assertSame(verifiedWhitespace, verifiedWhitespace.verifiedAgainst("   "))

        val unverifiedEmpty = layoutOf(text = "", spans = emptyList(), complete = false, reasonCode = "NO_TEXT")
        assertFalse(unverifiedEmpty.verifiedBlank)
    }

    @Test
    fun `non-blank text with no spans cannot be declared complete`() {
        val layout = layoutOf(spans = emptyList(), complete = true)
        assertFailsWith<IllegalArgumentException> { layout.verifiedAgainst(CANONICAL_TEXT) }
    }

    @Test
    fun `an incomplete layout over text with uncovered characters remains valid`() {
        val layout = layoutOf(complete = false, reasonCode = "PARTIAL_COVERAGE", spans = completeWords().take(1))
        assertSame(layout, layout.verifiedAgainst(CANONICAL_TEXT))
    }

    // Acceptance 2: confined artifact references.

    @Test
    fun `layout reference must be a confined relative path`() {
        listOf("", "/abs/layout.json", "../layout.json", "a/../b.json", "a//b.json", "a/./b.json", "a\\b.json", "a/", "a/b\u0000.json")
            .forEach { path ->
                assertFailsWith<IllegalArgumentException>("path '$path'") { LayoutReference(path, OTHER_SHA) }
            }
        assertEquals("artifacts/c/d/layout.json", LayoutReference("artifacts/c/d/layout.json", OTHER_SHA).relativePath)
    }

    @Test
    fun `layout reference hash must be lowercase SHA-256 hex`() {
        assertFailsWith<IllegalArgumentException> { LayoutReference("layout.json", "ABC") }
        assertFailsWith<IllegalArgumentException> { LayoutReference("layout.json", OTHER_SHA.uppercase()) }
    }

    // Acceptance 1 and 4: the contract field names and the ApiJson wire form.

    @Test
    fun `layout serializes the contract field names and round-trips`() {
        val layout = layoutOf()
        val json = ApiJson.encodeToString(PageTextLayout.serializer(), layout)
        listOf("\"version\":1", "\"textSha256\":", "\"imageSha256\":", "\"positioningVersion\":", "\"granularity\":\"WORD\"", "\"endExclusive\":3")
            .forEach { field -> assertTrue(json.contains(field), "missing $field in $json") }
        assertFalse(json.contains("reasonCode"), "a complete layout omits its null reason on the wire")
        assertEquals(layout, ApiJson.decodeFromString(PageTextLayout.serializer(), json))
    }

    @Test
    fun `incomplete layout round-trips with its reason`() {
        val layout = layoutOf(complete = false, reasonCode = "NO_UNIQUE_ANCHOR", spans = emptyList())
        val json = ApiJson.encodeToString(PageTextLayout.serializer(), layout)
        assertTrue(json.contains("\"reasonCode\":\"NO_UNIQUE_ANCHOR\""))
        assertEquals(layout, ApiJson.decodeFromString(PageTextLayout.serializer(), json))
    }

    @Test
    fun `decoding a persisted rectangle revalidates it`() {
        assertFailsWith<IllegalArgumentException> {
            ApiJson.decodeFromString(TextRectangle.serializer(), """{"left":0.0,"top":0.0,"width":0.0,"height":1.0}""")
        }
    }
}
