package infoscry.pdf

import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.font.FontRenderContext
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType0Font
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory
import org.apache.pdfbox.pdmodel.graphics.state.RenderingMode

/**
 * Synthetic fixtures for the selectable-PDF placement probe (ticket 01).
 *
 * The ground truth in this file is written from the layout the author specifies: baselines, flow gaps and
 * the advance widths of the probe font, measured by AWT, which is a different implementation from PDFBox. No
 * value below is read back from PDFBox, so a probe that echoes its own output cannot satisfy it.
 *
 * Page: US Letter (612 x 792 pt), rendered to a 200 DPI reading image (1700 x 2200 px). Two columns start at
 * x = 72 pt and x = 326 pt. The visible page is drawn with [PROBE_FONT_RESOURCE]; the visible page also
 * contains a light, small line that a local recogniser is expected to miss.
 *
 * Vertical convention for every rectangle in this fixture: a glyph's box spans 0.8 em above its baseline and
 * 0.2 em below it (height = font size). It is a convention of the fixture, not PDFBox's metric; the probe
 * converts its extracted baseline with the same convention, so the comparison reduces to the baseline.
 *
 * Font identity: Caveat Regular 2.000 (Impallari Type), `src/test/resources/pdf/fonts/Caveat.ttf`, SHA-256
 * 0bdb6b660482d31531b3945849fba5916b3ef8695da7024a9e6b9ee3c4157988, 403648 bytes. Licence (from its name table,
 * nameID 13): SIL Open Font License 1.1, http://scripts.sil.org/OFL. The same file is committed for OCR
 * fixtures under `src/test/resources/fixtures/ocr/`. The full OFL text is not present on this machine, so no
 * licence file is shipped next to it yet. The face covers U+00C5 U+00C4 U+00D6 U+00E5 U+00E4 U+00F6 (checked
 * with AWT `Font.canDisplay`).
 */
internal object SelectablePdfFixtures {

    const val PAGE_WIDTH_PT: Double = 612.0
    const val PAGE_HEIGHT_PT: Double = 792.0
    const val RENDER_DPI: Int = 200
    const val PIXELS_PER_POINT: Double = RENDER_DPI / 72.0
    const val PROBE_FONT_RESOURCE: String = "/pdf/fonts/Caveat.ttf"
    const val PAGE_IMAGE_RESOURCE: String = "/pdf/probe-page-200dpi.png"
    const val TESSERACT_TSV_RESOURCE: String = "/pdf/probe-page-tesseract-swe-eng-psm3.tsv"
    const val SURYA_BLOCKS_RESOURCE: String = "/pdf/probe-page-surya-blocks-simulated.json"

    /** Reading-order canonical text, typed out by hand so that the layout below can be checked against it. */
    val CANONICAL_TEXT: String = """
        Åsa Lindqvist
        betalade 120 kronor
        till Åsa.
        Den storkostnad var hög.
        Ärendet avslutades den 3 maj.
        Åsa Lindqvist
        Örebro 2 maj
        och så åäö ÅÄÖ
        Summa 1250
        Total amount: 1250 SEK
    """.trimIndent()

    private const val LEFT_X: Double = 72.0
    private const val RIGHT_X: Double = 326.0
    private const val BODY_SIZE: Double = 14.0
    private const val SMALL_SIZE: Double = 10.0
    const val TOP_FRACTION: Double = 0.8
    private val FONT_CONTEXT = FontRenderContext(null, true, true)

    /**
     * One drawn token. [visible] is what the page image shows; [canonical] is what the published reading
     * says. They differ for a corrected number (`l20` read as `120`), for a split or merged word (`ochså`
     * read as `och så`) and for a hyphen at a line end (`stor-` read as `stor`). Non-space characters of
     * [canonical] map one-to-one onto the characters of [visible] with a trailing hyphen removed.
     */
    internal data class Piece(
        val visible: String,
        val canonical: String = visible,
        val size: Double = BODY_SIZE,
        /** Horizontal space before this piece, in points. Zero joins it to the piece before. */
        val gap: Double = 6.0,
        /** Canonical separator after this piece: a space, a newline at a line end, or nothing for a join. */
        val after: String = " ",
        val gray: Boolean = false,
    ) {
        init {
            require(canonical.count { !it.isWhitespace() } == visible.removeSuffix("-").length) {
                "canonical '$canonical' must map one-to-one onto visible '$visible'"
            }
        }
    }

    /** A line of pieces in one column, given by its left edge and its baseline in top-down page points. */
    internal data class Line(val columnX: Double, val baseline: Double, val pieces: List<Piece>)

    /** The fixture's layout, as specified. Lines are listed in reading order: left column, then right. */
    internal val LINES: List<Line> = listOf(
        Line(LEFT_X, 120.0, listOf(Piece("Åsa"), Piece("Lindqvist", after = "\n"))),
        Line(
            LEFT_X,
            142.0,
            listOf(
                Piece("bet", gap = 0.0, after = ""), // split word: drawn as two tokens without a space
                Piece("alade", gap = 0.0),
                Piece("l20", canonical = "120"), // corrected number: the image shows a lower-case l
                Piece("kronor", after = "\n"),
            ),
        ),
        Line(LEFT_X, 164.0, listOf(Piece("till"), Piece("Åsa.", after = "\n"))),
        Line(LEFT_X, 186.0, listOf(Piece("Den"), Piece("stor-", canonical = "stor", after = ""))),
        Line(LEFT_X, 208.0, listOf(Piece("kostnad"), Piece("var"), Piece("hög.", after = "\n"))),
        // The line a local recogniser is expected to miss: small and light grey, but present in the reading.
        Line(
            LEFT_X,
            230.0,
            listOf(
                Piece("Ärendet", size = SMALL_SIZE, gray = true),
                Piece("avslutades", size = SMALL_SIZE, gray = true),
                Piece("den", size = SMALL_SIZE, gray = true),
                Piece("3", size = SMALL_SIZE, gray = true),
                Piece("maj.", size = SMALL_SIZE, gray = true, after = "\n"),
            ),
        ),
        Line(RIGHT_X, 120.0, listOf(Piece("Åsa"), Piece("Lindqvist", after = "\n"))),
        Line(RIGHT_X, 142.0, listOf(Piece("Örebro"), Piece("2"), Piece("maj", after = "\n"))),
        Line(
            RIGHT_X,
            164.0,
            listOf(
                Piece("ochså", canonical = "och så"), // merged word: two canonical words, one drawn token
                Piece("åäö"),
                Piece("ÅÄÖ", after = "\n"),
            ),
        ),
        Line(RIGHT_X, 186.0, listOf(Piece("Summa"), Piece("1250", after = "\n"))),
        Line(RIGHT_X, 208.0, listOf(Piece("Total"), Piece("amount:"), Piece("1250"), Piece("SEK", after = "\n"))),
    )

    /** A rectangle in top-down page points. */
    data class Rect(val left: Double, val top: Double, val width: Double, val height: Double) {
        val right: Double get() = left + width
        val bottom: Double get() = top + height
    }

    /** One non-space canonical character with the rectangle it is drawn in. */
    data class TruthGlyph(val offset: Int, val char: Char, val rect: Rect)

    /** A run of non-space canonical characters from one drawn piece: one positioned span. */
    data class TruthSpan(
        val start: Int,
        val endExclusive: Int,
        val text: String,
        val baseline: Double,
        val left: Double,
        val size: Double,
        val rect: Rect,
    )

    /** A whitespace-delimited canonical word, with the spans that cover it in reading order. */
    data class TruthWord(val text: String, val start: Int, val endExclusive: Int, val spans: List<TruthSpan>)

    data class Layout(
        val text: String,
        val glyphs: List<TruthGlyph>,
        val spans: List<TruthSpan>,
        val words: List<TruthWord>,
    )

    /** Built once from [LINES]; every rectangle is computed from the specified layout and AWT advances only. */
    val layout: Layout by lazy { buildLayout() }

    private fun buildLayout(): Layout {
        val text = StringBuilder()
        val glyphs = mutableListOf<TruthGlyph>()
        val spans = mutableListOf<TruthSpan>()
        for (line in LINES) {
            var cursor = line.columnX
            for ((index, piece) in line.pieces.withIndex()) {
                val left = if (index == 0) cursor else cursor + piece.gap
                cursor = left + advance(piece.visible, piece.size)
                placePiece(piece, left, line.baseline, text, glyphs, spans)
                text.append(piece.after)
            }
        }
        val reading = text.toString().trimEnd('\n')
        check(reading == CANONICAL_TEXT) {
            "fixture layout does not produce the typed canonical text:\n$reading\n!=\n$CANONICAL_TEXT"
        }
        return Layout(reading, glyphs, spans, words(reading, spans))
    }

    private fun placePiece(
        piece: Piece,
        left: Double,
        baseline: Double,
        text: StringBuilder,
        glyphs: MutableList<TruthGlyph>,
        spans: MutableList<TruthSpan>,
    ) {
        var visibleIndex = 0
        var runStart = -1
        var runVisibleStart = 0
        var runText = StringBuilder()
        fun closeRun(visibleEnd: Int) {
            if (runStart < 0) return
            val runLeft = left + advance(piece.visible.substring(0, runVisibleStart), piece.size)
            val runWidth = advance(piece.visible.substring(runVisibleStart, visibleEnd), piece.size)
            spans += TruthSpan(
                start = runStart,
                endExclusive = runStart + runText.length,
                text = runText.toString(),
                baseline = baseline,
                left = runLeft,
                size = piece.size,
                rect = Rect(runLeft, baseline - TOP_FRACTION * piece.size, runWidth, piece.size),
            )
            runStart = -1
            runText = StringBuilder()
        }
        for (char in piece.canonical) {
            if (char.isWhitespace()) {
                closeRun(visibleIndex)
                text.append(char)
                continue
            }
            val prefix = piece.visible.substring(0, visibleIndex)
            val glyphLeft = left + advance(prefix, piece.size)
            val glyphWidth = advance(prefix + piece.visible[visibleIndex], piece.size) - advance(prefix, piece.size)
            if (runStart < 0) {
                runStart = text.length
                runVisibleStart = visibleIndex
            }
            glyphs += TruthGlyph(
                offset = text.length,
                char = char,
                rect = Rect(glyphLeft, baseline - TOP_FRACTION * piece.size, glyphWidth, piece.size),
            )
            runText.append(char)
            text.append(char)
            visibleIndex++
        }
        closeRun(visibleIndex)
    }

    private fun words(reading: String, spans: List<TruthSpan>): List<TruthWord> {
        val words = mutableListOf<TruthWord>()
        var start = -1
        for (index in 0..reading.length) {
            val boundary = index == reading.length || reading[index].isWhitespace()
            if (!boundary && start < 0) start = index
            if (boundary && start >= 0) {
                val wordSpans = spans.filter { it.start >= start && it.endExclusive <= index }
                words += TruthWord(reading.substring(start, index), start, index, wordSpans)
                start = -1
            }
        }
        return words
    }

    /** The advance width of [text] at [size] points, measured by AWT with fractional metrics. */
    fun advance(text: String, size: Double): Double {
        if (text.isEmpty()) return 0.0
        return probeFont.deriveFont(size.toFloat()).getStringBounds(text, FONT_CONTEXT).width
    }

    /** The probe font, loaded from the committed resource so that no machine-installed face is used. */
    val probeFont: Font by lazy { Font.createFont(Font.TRUETYPE_FONT, probeFontBytes().inputStream()) }

    fun probeFontBytes(): ByteArray =
        SelectablePdfFixtures::class.java.getResourceAsStream(PROBE_FONT_RESOURCE)
            ?.use { it.readBytes() }
            ?: error("missing probe font resource $PROBE_FONT_RESOURCE")

    /** Draws the visible page at 200 DPI. Every piece is drawn at the position the layout specifies. */
    fun renderVisiblePage(): BufferedImage {
        val width = Math.round(PAGE_WIDTH_PT * PIXELS_PER_POINT).toInt()
        val height = Math.round(PAGE_HEIGHT_PT * PIXELS_PER_POINT).toInt()
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        try {
            graphics.color = Color.WHITE
            graphics.fillRect(0, 0, width, height)
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            graphics.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
            graphics.scale(PIXELS_PER_POINT, PIXELS_PER_POINT)
            for (line in LINES) {
                var cursor = line.columnX
                for ((index, piece) in line.pieces.withIndex()) {
                    val left = if (index == 0) cursor else cursor + piece.gap
                    cursor = left + advance(piece.visible, piece.size)
                    graphics.color = if (piece.gray) Color(150, 150, 150) else Color.BLACK
                    graphics.font = probeFont.deriveFont(piece.size.toFloat())
                    graphics.drawString(piece.visible, left.toFloat(), line.baseline.toFloat())
                }
            }
        } finally {
            graphics.dispose()
        }
        return image
    }

    /** The truth runs the probe writes as invisible text, each at its own left edge and baseline. */
    fun placedRuns(): List<PlacedRun> = layout.spans.map {
        PlacedRun(text = it.text, left = it.left, baseline = it.baseline, size = it.size, width = it.rect.width)
    }

    /**
     * A PDF that already carries an invisible text layer which is wrong: the same visible page, with stale
     * words drawn over the first real words. The stale layer must not survive a rebuild.
     */
    fun staleInputPdf(readingImage: BufferedImage): ByteArray {
        val stale = listOf(
            PlacedRun("Asa", 72.0, 120.0, BODY_SIZE),
            PlacedRun("Lindkvist", 110.0, 120.0, BODY_SIZE),
            PlacedRun("betalade", 72.0, 142.0, BODY_SIZE),
            PlacedRun("102", 140.0, 142.0, BODY_SIZE),
            PlacedRun("Sven", 72.0, 400.0, BODY_SIZE),
        )
        PDDocument().use { document ->
            val page = PDPage(PDRectangle(PAGE_WIDTH_PT.toFloat(), PAGE_HEIGHT_PT.toFloat()))
            document.addPage(page)
            val image = LosslessFactory.createFromImage(document, readingImage)
            val font = PDType0Font.load(document, ByteArrayInputStream(probeFontBytes()), true)
            PDPageContentStream(document, page).use { stream ->
                stream.drawImage(image, 0f, 0f, PAGE_WIDTH_PT.toFloat(), PAGE_HEIGHT_PT.toFloat())
                for (run in stale) {
                    writeInvisibleRun(stream, font, run)
                }
            }
            return ByteArrayOutputStream().also { document.save(it) }.toByteArray()
        }
    }

    /**
     * One run of canonical text written at a top-down position, in points. When [width] is given, the run is
     * horizontally scaled so that its advance equals that width: the visible token's box, which is the
     * geometry a corrected or merged word has, since its canonical glyphs need not advance like the drawn ones.
     */
    data class PlacedRun(
        val text: String,
        val left: Double,
        val baseline: Double,
        val size: Double,
        val width: Double? = null,
    )

    /**
     * Writes one run as invisible text (`Tr 3`) in its own text object, at an absolute position.
     *
     * Each run gets its own `BT`/`ET` block because `Td` moves are relative to the previous line origin, so
     * chaining runs in one block would place each run relative to the last instead of at its own edge.
     */
    fun writeInvisibleRun(stream: PDPageContentStream, font: PDType0Font, run: PlacedRun) {
        val width = run.width
        // Horizontal scaling (Tz) is text state that outlives ET, so it is set for every run and reset after it.
        val scale = if (width == null) 100.0 else width / (font.getStringWidth(run.text) / 1000.0 * run.size) * 100.0
        stream.beginText()
        stream.setFont(font, run.size.toFloat())
        stream.setHorizontalScaling(scale.toFloat())
        stream.setRenderingMode(RenderingMode.NEITHER)
        stream.newLineAtOffset(run.left.toFloat(), (PAGE_HEIGHT_PT - run.baseline).toFloat())
        stream.showText(run.text)
        stream.endText()
        stream.setHorizontalScaling(100f)
    }
}
