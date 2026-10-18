package infoscry.ocr

/**
 * Turns an image model's Markdown reading into a [PageStructureDraft] (`markdown/1`).
 *
 * The parser is line based on purpose. The transcription prompt asks for a small, fixed set of Markdown forms
 * (headings, list items, pipe tables, formulas, fenced code and the page header and footer markers), and a
 * reader that recognises exactly those forms can be checked against the text directly. Anything it does not
 * recognise stays text, in order, so no words are lost to a layout guess. No Markdown library is involved, so
 * nothing new is added to the build.
 *
 * Emphasis and code markers (`**`, `__`, `_`, backticks) are notation rather than page text, so their pairs are
 * removed. Everything else is kept as the model wrote it.
 *
 * A reading is never refused over its layout. The line reader accepts any input, and as a last resort an
 * unexpected failure falls back to paragraphs split at blank lines, marked degraded.
 */
object MarkdownStructureParser {

    const val PARSER: String = "markdown/1"

    fun parse(markdown: String): PageStructureDraft = try {
        PageStructureDraft(PARSER, LineReader(markdown.lines()).read(), degraded = false)
    } catch (failure: RuntimeException) {
        PageStructureDraft(
            PARSER,
            paragraphsOf(markdown.lines()) { it }.map { BlockDraft(BlockKind.PARAGRAPH, it) },
            degraded = true,
        )
    }

    private val ATX_HEADING = Regex("""^ {0,3}(#{1,6})(?:[ \t]+(.*?))?[ \t]*$""")
    private val CLOSING_HASHES = Regex("""[ \t]+#+$""")
    private val FENCE = Regex("""^ {0,3}(`{3,}|~{3,}).*$""")
    private val BULLET = Regex("""^ {0,3}[-*+][ \t]+(.*)$""")
    private val ORDERED = Regex("""^ {0,3}\d{1,9}[.)][ \t]+(.*)$""")
    private val SEPARATOR = Regex("""\s*\|?\s*:?-+:?\s*(\|\s*:?-+:?\s*)*\|?\s*""")
    private val INLINE_FORMULA = Regex("""^\s*\$([^$]+)\$\s*$""")
    private val BOLD = Regex("""\*\*(.+?)\*\*""")
    private val UNDERSCORE_EMPHASIS = Regex("""(?<![\p{L}\p{N}_])_{1,2}(?=\S)(.+?)(?<=\S)_{1,2}(?![\p{L}\p{N}_])""")
    private val CODE_SPAN = Regex("""`([^`]+)`""")

    private const val PAGE_HEADER_OPEN = "<!-- page-header -->"
    private const val PAGE_HEADER_CLOSE = "<!-- /page-header -->"
    private const val PAGE_FOOTER_OPEN = "<!-- page-footer -->"
    private const val PAGE_FOOTER_CLOSE = "<!-- /page-footer -->"

    private fun inline(text: String): String = text
        .replace(BOLD) { it.groupValues[1] }
        .replace(UNDERSCORE_EMPHASIS) { it.groupValues[1] }
        .replace(CODE_SPAN) { it.groupValues[1] }

    /** Paragraphs are runs of lines split at blank lines, each trimmed at its ends. */
    private fun paragraphsOf(lines: List<String>, transform: (String) -> String): List<String> {
        val paragraphs = ArrayList<String>()
        val current = ArrayList<String>()
        fun flush() {
            val text = current.joinToString("\n").trim()
            if (text.isNotEmpty()) paragraphs += transform(text)
            current.clear()
        }
        for (line in lines) {
            if (line.isBlank()) flush() else current += line
        }
        flush()
        return paragraphs
    }

    /** The cells of a table row: the outer pipes are optional, and `\|` is a literal pipe inside a cell. */
    private fun cellsOf(line: String): List<String> {
        val trimmed = line.trim()
        var body = trimmed.removePrefix("|")
        if (body.endsWith("|") && !body.endsWith("\\|")) body = body.dropLast(1)
        val cells = ArrayList<String>()
        val current = StringBuilder()
        var index = 0
        while (index < body.length) {
            val character = body[index]
            if (character == '\\' && index + 1 < body.length && body[index + 1] == '|') {
                current.append('|')
                index += 2
                continue
            }
            if (character == '|') {
                cells += current.toString().trim()
                current.clear()
            } else {
                current.append(character)
            }
            index++
        }
        cells += current.toString().trim()
        return cells
    }

    /** Reads the lines in order. Every step consumes at least one line, so the reader always makes progress. */
    private class LineReader(private val lines: List<String>) {

        private val blocks = ArrayList<BlockDraft>()
        private var index = 0

        fun read(): List<BlockDraft> {
            while (index < lines.size) {
                if (lines[index].isBlank()) {
                    index++
                } else {
                    consume()
                }
            }
            return blocks
        }

        private fun consume() {
            val line = lines[index].trim()
            when {
                line == PAGE_HEADER_CLOSE || line == PAGE_FOOTER_CLOSE -> index++
                pageMarker() -> Unit
                fencedCode() -> Unit
                heading() -> Unit
                formula() -> Unit
                table() -> Unit
                listItem() -> Unit
                else -> paragraph()
            }
        }

        /**
         * A running header or footer: the text between an open and a close marker, one block per paragraph. The
         * marker may sit on one line with its text (`<!-- page-header -->Title<!-- /page-header -->`). Markers are
         * notation, so they never reach the text; text around them is kept.
         */
        private fun pageMarker(): Boolean {
            val line = lines[index].trim()
            val kind = when {
                line.startsWith(PAGE_HEADER_OPEN) -> BlockKind.PAGE_HEADER
                line.startsWith(PAGE_FOOTER_OPEN) -> BlockKind.PAGE_FOOTER
                else -> return false
            }
            val open = if (kind == BlockKind.PAGE_HEADER) PAGE_HEADER_OPEN else PAGE_FOOTER_OPEN
            val close = if (kind == BlockKind.PAGE_HEADER) PAGE_HEADER_CLOSE else PAGE_FOOTER_CLOSE
            val afterOpen = line.removePrefix(open)
            val closeAt = afterOpen.indexOf(close)
            if (closeAt >= 0) {
                markedBlocks(kind, listOf(afterOpen.substring(0, closeAt)))
                paragraphBlocks(listOf(afterOpen.substring(closeAt + close.length)))
                index++
                return true
            }
            val end = (index + 1 until lines.size).firstOrNull { lines[it].trim() == close }
            if (end == null) {
                // An unclosed marker is notation without content: it is dropped and the text after it is read as usual.
                paragraphBlocks(listOf(afterOpen))
                index++
                return true
            }
            markedBlocks(kind, listOf(afterOpen) + lines.subList(index + 1, end))
            index = end + 1
            return true
        }

        private fun markedBlocks(kind: BlockKind, marked: List<String>) {
            paragraphsOf(marked) { inline(it) }.forEach { blocks += BlockDraft(kind, it) }
        }

        private fun paragraphBlocks(text: List<String>) {
            paragraphsOf(text) { inline(it) }.forEach { blocks += BlockDraft(BlockKind.PARAGRAPH, it) }
        }

        /** Fenced code keeps its lines verbatim. A fence that is never closed runs to the end, as CommonMark does. */
        private fun fencedCode(): Boolean {
            val opening = FENCE.matchEntire(lines[index]) ?: return false
            val fence = opening.groupValues[1]
            val character = fence.first()
            var end = index + 1
            while (end < lines.size && !closesFence(lines[end], character, fence.length)) end++
            blocks += BlockDraft(BlockKind.CODE, lines.subList(index + 1, end).joinToString("\n"))
            index = minOf(end + 1, lines.size)
            return true
        }

        private fun closesFence(line: String, character: Char, length: Int): Boolean {
            val trimmed = line.trim()
            return trimmed.length >= length && trimmed.all { it == character }
        }

        private fun heading(): Boolean {
            val match = ATX_HEADING.matchEntire(lines[index]) ?: return false
            index++
            val text = inline(match.groupValues[2].replace(CLOSING_HASHES, "").trim())
            if (text.isNotEmpty()) blocks += BlockDraft(BlockKind.HEADING, text, level = match.groupValues[1].length)
            return true
        }

        /** `$$` opens a display formula that runs to the next line ending in `$$`; a single `$...$` line is one formula. */
        private fun formula(): Boolean {
            val line = lines[index].trim()
            if (line.startsWith("$$")) {
                val end = displayEnd(index) ?: return false
                val pieces = (index..end).map { lines[it].trim() }.toMutableList()
                pieces[0] = pieces[0].removePrefix("$$")
                pieces[pieces.size - 1] = pieces.last().removeSuffix("$$")
                val latex = pieces.joinToString("\n").trim()
                blocks += BlockDraft(BlockKind.FORMULA, latex, latex = latex)
                index = end + 1
                return true
            }
            val match = INLINE_FORMULA.matchEntire(line) ?: return false
            val latex = match.groupValues[1].trim()
            blocks += BlockDraft(BlockKind.FORMULA, latex, latex = latex)
            index++
            return true
        }

        /** The line that closes a display formula opened at [start], or null when none closes it before a blank line. */
        private fun displayEnd(start: Int): Int? {
            val first = lines[start].trim()
            if (first.length >= 4 && first.endsWith("$$")) return start
            for (end in start + 1 until lines.size) {
                if (lines[end].isBlank()) return null
                if (lines[end].trim().endsWith("$$")) return end
            }
            return null
        }

        /** A table is a header row, a separator row with the same number of cells, then rows up to a blank line. */
        private fun table(): Boolean {
            if (!tableStartsAt(index)) return false
            val rows = ArrayList<List<CellDraft>>()
            rows += cellsOf(lines[index]).map { CellDraft(inline(it), header = true) }
            var end = index + 2
            while (end < lines.size && lines[end].isNotBlank() && lines[end].contains('|')) {
                rows += cellsOf(lines[end]).map { CellDraft(inline(it), header = false) }
                end++
            }
            blocks += BlockDraft(BlockKind.TABLE, "", rows = rows)
            index = end
            return true
        }

        /**
         * A list item keeps its ordinal, since the number is page text ("12. Beslut" is not "Beslut"). A bullet
         * glyph is notation and is dropped.
         */
        private fun listItem(): Boolean {
            val line = lines[index]
            val text = BULLET.matchEntire(line)?.let { inline(it.groupValues[1].trim()) }
                ?: ORDERED.matchEntire(line)?.let { inline(line.trim()) }
                ?: return false
            index++
            if (text.isNotEmpty()) blocks += BlockDraft(BlockKind.LIST_ITEM, text)
            return true
        }

        /** Everything else: lines up to the next blank line or the start of another block, kept in order. */
        private fun paragraph() {
            val collected = arrayListOf(lines[index])
            index++
            while (index < lines.size && !lines[index].isBlank() && !startsBlock(index)) {
                collected += lines[index]
                index++
            }
            val text = inline(collected.joinToString("\n").trim())
            if (text.isNotEmpty()) blocks += BlockDraft(BlockKind.PARAGRAPH, text)
        }

        /** Whether the line at [at] starts a block that a paragraph must end before. Mirrors the recognisers above. */
        private fun startsBlock(at: Int): Boolean {
            val line = lines[at]
            val trimmed = line.trim()
            return trimmed.startsWith(PAGE_HEADER_OPEN) || trimmed.startsWith(PAGE_FOOTER_OPEN) ||
                trimmed == PAGE_HEADER_CLOSE || trimmed == PAGE_FOOTER_CLOSE ||
                FENCE.matches(line) ||
                ATX_HEADING.matches(line) ||
                BULLET.matches(line) || ORDERED.matches(line) ||
                INLINE_FORMULA.matches(trimmed) ||
                (trimmed.startsWith("$$") && displayEnd(at) != null) ||
                tableStartsAt(at)
        }

        private fun tableStartsAt(at: Int): Boolean {
            val separator = lines.getOrNull(at + 1) ?: return false
            val header = lines[at]
            return header.contains('|') && SEPARATOR.matches(separator.trim()) &&
                cellsOf(header).size == cellsOf(separator).size
        }
    }
}
