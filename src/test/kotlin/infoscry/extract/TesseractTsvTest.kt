package infoscry.extract

import infoscry.ocr.BlockDraft
import infoscry.ocr.BlockKind
import infoscry.ocr.PixelBox
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Paragraph grouping of Tesseract's TSV: lines inside a paragraph are one block, and a paragraph's box is the
 * union of the words it holds, so a citation can be shown on the paragraph it came from.
 */
class TesseractTsvTest {

    @Test
    fun `groups paragraphs with union boxes`() {
        val reading = TesseractTsv.parse(TSV)

        assertEquals(
            listOf(
                BlockDraft(
                    kind = BlockKind.PARAGRAPH,
                    text = "första raden\nandra",
                    bbox = PixelBox(left = 42, top = 52, right = 370, bottom = 141),
                ),
                BlockDraft(
                    kind = BlockKind.PARAGRAPH,
                    text = "nytt stycke",
                    bbox = PixelBox(left = 44, top = 160, right = 330, bottom = 201),
                ),
                BlockDraft(
                    kind = BlockKind.PARAGRAPH,
                    text = "tredje",
                    bbox = PixelBox(left = 500, top = 52, right = 700, bottom = 93),
                ),
            ),
            reading.paragraphs,
        )
    }

    private companion object {

        /** Two blocks and three paragraphs; the rows are shuffled to show the grouping is by key, not by order. */
        val TSV: String = listOf(
            row(level = 1, left = 0, top = 0, width = 900, height = 240, conf = "-1"),
            word(block = 1, par = 1, line = 1, word = 1, left = 44, top = 52, width = 214, height = 41, text = "första"),
            word(block = 1, par = 2, line = 1, word = 1, left = 44, top = 160, width = 120, height = 41, text = "nytt"),
            word(block = 1, par = 1, line = 1, word = 2, left = 270, top = 52, width = 100, height = 41, text = "raden"),
            word(block = 2, par = 1, line = 1, word = 1, left = 500, top = 52, width = 200, height = 41, text = "tredje"),
            word(block = 1, par = 1, line = 2, word = 1, left = 42, top = 100, width = 150, height = 41, text = "andra"),
            word(block = 1, par = 2, line = 1, word = 2, left = 180, top = 160, width = 150, height = 41, text = "stycke"),
        ).joinToString("\n")

        fun word(
            block: Int,
            par: Int,
            line: Int,
            word: Int,
            left: Int,
            top: Int,
            width: Int,
            height: Int,
            text: String,
        ): String = row(
            level = 5,
            block = block,
            par = par,
            line = line,
            word = word,
            left = left,
            top = top,
            width = width,
            height = height,
            conf = "95.000000",
            text = text,
        )

        fun row(
            level: Int,
            block: Int = 0,
            par: Int = 0,
            line: Int = 0,
            word: Int = 0,
            left: Int,
            top: Int,
            width: Int,
            height: Int,
            conf: String,
            text: String = "",
        ): String = listOf(
            level.toString(), "1", block.toString(), par.toString(), line.toString(), word.toString(),
            left.toString(), top.toString(), width.toString(), height.toString(), conf, text,
        ).joinToString("\t")
    }
}
