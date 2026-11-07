package infoscry.image

import infoscry.domain.NormalizedBox
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PageTextContextTest {

    private val image = NormalizedBox(0.2, 0.30, 0.6, 0.50)

    private fun line(text: String, top: Double, left: Double = 0.2, right: Double = 0.6, height: Double = 0.02) =
        TextLine(text, NormalizedBox(left, top, right, top + height))

    @Test
    fun `the line below the picture is its caption`() {
        val lines = listOf(
            line("Figur 3. Anna Svensson 1923", top = 0.51),
            line("An unrelated paragraph far down the page.", top = 0.80),
        )

        assertEquals("Figur 3. Anna Svensson 1923", PageTextContext.captionFor(image, lines))
    }

    @Test
    fun `a figure line above the picture wins over a plain line below it`() {
        val lines = listOf(
            line("Bild 2", top = 0.27),
            line("Some text that merely follows the picture.", top = 0.51),
        )

        assertEquals("Bild 2", PageTextContext.captionFor(image, lines))
    }

    @Test
    fun `a plain line below is used when nothing starts like a caption, and below beats above`() {
        val lines = listOf(
            line("Text above the picture.", top = 0.27),
            line("Text below the picture.", top = 0.51),
        )

        assertEquals("Text below the picture.", PageTextContext.captionFor(image, lines))
        assertEquals("Text above the picture.", PageTextContext.captionFor(image, lines.take(1)))
    }

    @Test
    fun `a caption of up to three contiguous lines is joined`() {
        val lines = listOf(
            line("Fig. 4 The harbour", top = 0.51),
            line("seen from the north", top = 0.535),
            line("in the autumn of 1923", top = 0.56),
            line("A fourth line is not part of it", top = 0.585),
        )

        assertEquals(
            "Fig. 4 The harbour seen from the north in the autumn of 1923",
            PageTextContext.captionFor(image, lines),
        )
    }

    @Test
    fun `a line five percent below the picture is not a caption`() {
        val lines = listOf(line("Figur 1. Too far away", top = 0.55))

        assertNull(PageTextContext.captionFor(image, lines))
    }

    @Test
    fun `a line that overlaps the picture by thirty percent of its width is not a caption`() {
        // The picture spans 0.2..0.6; the line spans 0.48..0.88, so 0.12 of its 0.40 width lies under the picture.
        val lines = listOf(line("Figur 1. Beside the picture", top = 0.51, left = 0.48, right = 0.88))

        assertNull(PageTextContext.captionFor(image, lines))
    }

    @Test
    fun `a word that only starts like a figure label is not a label`() {
        val lines = listOf(
            line("Figures of the harvest were kept elsewhere.", top = 0.27),
            line("Table of contents", top = 0.51),
        )

        // Neither is a label, so the plain line below is used.
        assertEquals("Table of contents", PageTextContext.captionFor(image, lines))
        assertTrue(PageTextContext.isLabel("Fig. 2: The ship"))
        assertTrue(PageTextContext.isLabel("tab 3"))
        assertTrue(PageTextContext.isLabel("FOTO: Anna"))
        assertTrue(PageTextContext.isLabel("Photo 7"))
        assertTrue(!PageTextContext.isLabel("Figures of the harvest"))
        assertTrue(!PageTextContext.isLabel("Table of contents"))
        assertTrue(!PageTextContext.isLabel("Photograph albums"))
    }

    @Test
    fun `a line drawn over the picture is not a caption`() {
        val lines = listOf(line("Written across the picture", top = 0.40))

        assertNull(PageTextContext.captionFor(image, lines))
    }

    @Test
    fun `context excludes the caption, is nearest first, stays within the limit and is cut at a word`() {
        val caption = "Figur 3. Anna Svensson 1923"
        val near = "The nearest sentence above the picture."
        val next = "A second sentence a little further away."
        val filler = (1..40).joinToString(" ") { "word$it" }
        val lines = listOf(
            line(caption, top = 0.51),
            line(near, top = 0.28),
            line(next, top = 0.24),
            line(filler, top = 0.60),
            line(filler, top = 0.65),
            line(filler, top = 0.70),
        )

        val context = PageTextContext.contextFor(image, lines, exclude = caption)!!

        assertTrue(context.length <= PageTextContext.MAX_CONTEXT_CHARS, "was ${context.length}")
        assertTrue(!context.contains("Anna Svensson"), "the caption is not repeated in the context")
        assertTrue(context.startsWith(near), context)
        assertTrue(context.indexOf(near) < context.indexOf(next))
        assertTrue(context.indexOf(next) < context.indexOf("word1"))
        val lastWord = context.substringAfterLast(' ')
        assertTrue(lastWord in (1..40).map { "word$it" }, "ends on a whole word: $lastWord")
    }

    @Test
    fun `context of a page with no other text is absent`() {
        assertNull(PageTextContext.contextFor(image, emptyList(), exclude = null))
        val only = line("Fig. 1 Only the caption", top = 0.51)
        assertNull(PageTextContext.contextFor(image, listOf(only), exclude = only.text))
    }

    @Test
    fun `one very long line never exceeds the limit and is cut at a word`() {
        val long = (1..400).joinToString(" ") { "token$it" }

        val context = PageTextContext.contextFor(image, listOf(line(long, top = 0.51)), exclude = null)!!

        assertTrue(context.length <= PageTextContext.MAX_CONTEXT_CHARS)
        assertTrue(long.startsWith(context))
        assertEquals(' ', long[context.length], "cut at a word boundary")
    }

    @Test
    fun `a whole paragraph is body text, not a caption`() {
        val paragraph = (1..80).joinToString(" ") { "sentence$it" }

        assertNull(PageTextContext.captionFor(image, listOf(line(paragraph, top = 0.51))))
        assertTrue(paragraph.length > PageTextContext.MAX_CAPTION_LINE_CHARS)
        // It is still context.
        assertTrue(PageTextContext.contextFor(image, listOf(line(paragraph, top = 0.51)), exclude = null)!!.startsWith("sentence1 "))
    }
}
