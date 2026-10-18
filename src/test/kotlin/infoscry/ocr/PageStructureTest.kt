package infoscry.ocr

import infoscry.pdf.PdfModelRules
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class PageStructureTest {

    private val imageSha = "a".repeat(64)

    private fun block(
        order: Int,
        range: TextRange,
        kind: BlockKind = BlockKind.PARAGRAPH,
        level: Int? = null,
        rows: List<TableRow>? = null,
    ) = StructureBlock(kind = kind, level = level, order = order, extracted = range, search = range, rows = rows)

    private fun structure(text: String, vararg blocks: StructureBlock) = PageStructure(
        version = STRUCTURE_VERSION,
        textSha256 = PdfModelRules.sha256Of(text),
        imageSha256 = imageSha,
        parser = "test/1",
        blocks = blocks.toList(),
    )

    @Test
    fun `overlapping block ranges are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            structure("abcdef", block(0, TextRange(0, 4)), block(1, TextRange(3, 6)))
        }
    }

    @Test
    fun `a level outside 1 to 6 or on a non-heading is rejected`() {
        assertFailsWith<IllegalArgumentException> { block(0, TextRange(0, 1), BlockKind.HEADING, level = 0) }
        assertFailsWith<IllegalArgumentException> { block(0, TextRange(0, 1), BlockKind.HEADING, level = 7) }
        assertFailsWith<IllegalArgumentException> { block(0, TextRange(0, 1), BlockKind.PARAGRAPH, level = 1) }
        block(0, TextRange(0, 1), BlockKind.HEADING, level = 6)
    }

    @Test
    fun `rows on a non-table are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            block(0, TextRange(0, 1), BlockKind.PARAGRAPH, rows = listOf(TableRow(emptyList())))
        }
    }

    @Test
    fun `non-unique order is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            structure("ab\ncd", block(0, TextRange(0, 2)), block(0, TextRange(3, 5)))
        }
    }

    @Test
    fun `a range splitting a surrogate pair is rejected against its text`() {
        val text = "a😀b"
        val splitting = structure(text, block(0, TextRange(0, 2)))
        assertFailsWith<IllegalArgumentException> { splitting.verifiedAgainst(text) }
        val whole = structure(text, block(0, TextRange(0, 3)), block(1, TextRange(3, 4)))
        assertSame(whole, whole.verifiedAgainst(text))
    }

    @Test
    fun `non-whitespace outside every block is rejected against its text`() {
        val text = "ab\ncd"
        assertFailsWith<IllegalArgumentException> { structure(text, block(0, TextRange(0, 2))).verifiedAgainst(text) }
    }

    @Test
    fun `a structure made for another text is rejected`() {
        assertFailsWith<IllegalArgumentException> { structure("ab", block(0, TextRange(0, 2))).verifiedAgainst("ac") }
    }
}
