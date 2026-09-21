package infoscry.extract

import infoscry.domain.SourceLocation
import java.awt.Dimension
import java.awt.Graphics2D
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import java.util.Locale
import javax.imageio.ImageIO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import org.apache.poi.hslf.usermodel.HSLFSlideShow
import org.apache.poi.hslf.usermodel.HSLFTextParagraph
import org.apache.poi.hssf.usermodel.HSSFWorkbook
import org.apache.poi.hwpf.HWPFDocument
import org.apache.poi.hwpf.usermodel.Paragraph
import org.apache.poi.poifs.filesystem.POIFSFileSystem
import org.apache.poi.sl.usermodel.Notes
import org.apache.poi.sl.usermodel.Placeholder
import org.apache.poi.sl.usermodel.Shape
import org.apache.poi.sl.usermodel.SimpleShape
import org.apache.poi.sl.usermodel.TextShape
import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.DataFormatter
import org.apache.poi.ss.usermodel.Row
import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.ss.usermodel.Workbook
import org.apache.poi.xslf.usermodel.XMLSlideShow
import org.apache.poi.xslf.usermodel.XSLFSheet
import org.apache.poi.xslf.usermodel.XSLFSlide
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.apache.poi.xwpf.usermodel.IBodyElement
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.apache.poi.xwpf.usermodel.XWPFParagraph
import org.apache.poi.xwpf.usermodel.XWPFTable

/** Word processing documents in the OOXML container: `.docx`. */
internal const val DOCX_MEDIA_TYPE: String =
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

/** Word processing documents in the Word 97 binary format: `.doc`. */
internal const val DOC_MEDIA_TYPE: String = "application/msword"

/** Spreadsheets in the OOXML container: `.xlsx`. */
internal const val XLSX_MEDIA_TYPE: String =
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

/** Spreadsheets in the BIFF8 binary format: `.xls`. */
internal const val XLS_MEDIA_TYPE: String = "application/vnd.ms-excel"

/** Presentations in the OOXML container: `.pptx`. */
internal const val PPTX_MEDIA_TYPE: String =
    "application/vnd.openxmlformats-officedocument.presentationml.presentation"

/** Presentations in the PowerPoint 97 binary format: `.ppt`. */
internal const val PPT_MEDIA_TYPE: String = "application/vnd.ms-powerpoint"

/**
 * The largest Office container an extractor will hand to POI.
 *
 * Every reader here holds the parsed document in memory — POI has no streaming mode for page-level or
 * slide-level work — so the file's size is the closest predictor of what the process will need. A
 * refusal is worse than reading a document and better than an out-of-memory kill that takes every other
 * job in the process with it, and the same bound covers a `.docx` whose zip expands far beyond its
 * size.
 */
internal const val MAX_OFFICE_DOCUMENT_BYTES: Long = 64L * 1024 * 1024

/** Which container family a document is read from. The registry knows it; the bytes cannot say. */
enum class OfficeFormat {

    /** The OOXML family: XWPF, XSSF, XSLF. */
    OOXML,

    /** The binary family of Word 97 and Office 97-2003: HWPF, HSSF, HSLF. */
    LEGACY,
}

/**
 * Word documents, cited by heading section.
 *
 * A Word document's own structure is a chain of headings, so the unit is a section: its heading path is
 * what a reader clicks through and its paragraph range is what they can count. Paragraphs are numbered in
 * reading order over the whole document — body paragraphs and the paragraphs inside table cells both
 * count, because both are paragraphs a reader sees and an index that skipped the second kind would point
 * at the wrong place in any document with a table above it.
 *
 * Tables are cited as a row: a row is the smallest part of a table that means anything, since a cell
 * without its neighbouring labels is not evidence. The row's text keeps the cells in reading order and
 * the row's paragraph range covers the cell paragraphs it was read from.
 *
 * Headings are recognised the way Word records them: an outline level when the document carries one, or
 * a heading style name. A document whose styles were flattened — which is what some converters do — has
 * no headings left at all, and then the whole document is one section with an empty heading path, which
 * is a truthful citation rather than a guess.
 */
class WordExtractor(private val format: OfficeFormat) : DocumentExtractor {

    override val supportedMediaTypes: Set<String> =
        if (format == OfficeFormat.OOXML) setOf(DOCX_MEDIA_TYPE) else setOf(DOC_MEDIA_TYPE)

    override fun extract(input: ExtractionInput): Flow<ExtractionEvent> = flow {
        if (refuseOversized(input, Files.size(input.managedPath))) return@flow
        val units = when (format) {
            OfficeFormat.OOXML -> Files.newInputStream(input.managedPath).use { stream ->
                XWPFDocument(stream).use { document -> wordUnits(ooxmlBlocks(document)) }
            }

            OfficeFormat.LEGACY -> Files.newInputStream(input.managedPath).use { stream ->
                HWPFDocument(POIFSFileSystem(stream)).use { document -> wordUnits(legacyBlocks(document)) }
            }
        }
        emitOfficeUnits(input, units.asSequence()) { wordMetadata(format) }
    }
}

/**
 * Spreadsheets, cited by sheet and cell range.
 *
 * A spreadsheet has no prose to cite, so the unit is a range of rows: the header row above every batch of
 * data rows, the sheet's real name, and the exact A1 bounds. Row numbers are the ones a reader sees in
 * the file, which is why a blank row still advances the count even though it is not worth a unit.
 *
 * Cell values are the values a reader would see: the display form the cell's own number format produces.
 * A formula cell is read through its **stored result**, never by evaluating anything — evaluating would
 * run the workbook's formulas (and, for the legacy format, its macros) on material nobody has vouched
 * for, and a cached value is what the document itself claims the answer is.
 */
class SpreadsheetExtractor(
    private val format: OfficeFormat,
    private val rowsPerUnit: Int = DEFAULT_ROWS_PER_UNIT,
) : DocumentExtractor {

    init {
        require(rowsPerUnit >= 1) { "a spreadsheet unit needs at least one row, was $rowsPerUnit" }
    }

    override val supportedMediaTypes: Set<String> =
        if (format == OfficeFormat.OOXML) setOf(XLSX_MEDIA_TYPE) else setOf(XLS_MEDIA_TYPE)

    override fun extract(input: ExtractionInput): Flow<ExtractionEvent> = flow {
        if (refuseOversized(input, Files.size(input.managedPath))) return@flow
        val sheets = mutableListOf<OfficeUnit>()
        var sheetCount = 0
        val open: (Workbook) -> Unit = { workbook ->
            workbook.forEach { sheet ->
                sheetCount++
                sheets.addAll(sheetUnits(sheet))
            }
        }
        when (format) {
            OfficeFormat.OOXML -> Files.newInputStream(input.managedPath)
                .use { stream -> XSSFWorkbook(stream).use(open) }

            OfficeFormat.LEGACY -> Files.newInputStream(input.managedPath)
                .use { stream -> HSSFWorkbook(stream).use(open) }
        }
        emitOfficeUnits(input, sheets.asSequence()) {
            mapOf(
                EXTRACTOR_METADATA to SPREADSHEET_METADATA,
                FORMAT_METADATA to format.metadataName(),
                SHEETS_METADATA to sheetCount.toString(),
            )
        }
    }

    /**
     * One sheet's units, in reading order.
     *
     * The header is the first row with a non-blank cell: a sheet whose first rows are empty still has a
     * header, and calling an empty row one would repeat nothing above every unit. A sheet with no data
     * rows below its header has no units — the same reason a CSV with only a header has none.
     */
    private fun sheetUnits(sheet: Sheet): List<OfficeUnit> {
        val formatter = DataFormatter(Locale.ROOT)
        val rows = sheet.map { row -> SpreadsheetRow(row.rowNum, rowCells(row, formatter)) }
        val headerIndex = rows.indexOfFirst { row -> row.cells.any { it.isNotBlank() } }
        if (headerIndex < 0) return emptyList()
        val header = rows[headerIndex].cells
        val units = mutableListOf<OfficeUnit>()
        var batch = mutableListOf<SpreadsheetRow>()

        fun closeBatch() {
            if (batch.isEmpty()) return
            val first = batch.first().number + 1
            val last = batch.last().number + 1
            val columns = maxOf(header.size, batch.maxOf { it.cells.size }, 1)
            val locator = SourceLocation.SpreadsheetRange(
                sheet = sheet.sheetName,
                startCell = "A$first",
                endCell = "${columnLetter(columns)}$last",
            )
            val key = "rows:${sheet.sheetName}:$first-$last"
            val ordinal = units.size
            val extracted = csvText(batch.map { it.cells })
            val search = tableSearchText(header, batch.map { it.cells })
            units.add(
                OfficeUnit(key = key, ordinal = ordinal, locator = locator) {
                    ContentUnitDraft(
                        locator = locator,
                        extractedText = TextNormalizer.normalize(extracted).extracted,
                        searchText = TextNormalizer.normalize(search).search,
                    )
                },
            )
            batch = mutableListOf()
        }

        rows.drop(headerIndex + 1).forEach { row ->
            if (row.cells.all { it.isBlank() }) return@forEach
            batch.add(row)
            if (batch.size >= rowsPerUnit) closeBatch()
        }
        closeBatch()
        return units
    }

    /** One row of a sheet, with the row number a reader counts it at. */
    private data class SpreadsheetRow(val number: Int, val cells: List<String>)

    companion object {

        /** How many data rows one spreadsheet unit holds unless a caller asks for another batch size. */
        const val DEFAULT_ROWS_PER_UNIT: Int = 200
    }
}

/**
 * One row's values, by column index.
 *
 * The row is walked by index rather than by its own iterator so that a value keeps its column: a formula
 * in column C printed as the first cell of its row would line up with the wrong header.
 */
private fun rowCells(row: Row, formatter: DataFormatter): List<String> {
    val columns = row.lastCellNum.toInt()
    if (columns <= 0) return emptyList()
    return (0 until columns).map { index ->
        val cell = row.getCell(index)
        if (cell == null) "" else displayValue(cell, formatter)
    }
}

/**
 * Presentations, cited by slide.
 *
 * A slide is the unit because a slide is what a reader turns to: its number is the only stable position a
 * presentation has. The text is every text shape on the slide in shape order, followed by the slide's
 * speaker notes, since notes are stored with the slide and are often where the reasoning lives.
 *
 * Each slide also gets a rendered preview artifact, which is what a source viewer needs to show the slide
 * as it looks rather than as its text reads. Rendering needs fonts and a graphics stack that a headless
 * machine may not have, so a failed render is recorded as a warning while the slide's text is still
 * delivered: the alternative would lose indexable evidence because of a missing font.
 */
class PresentationExtractor(private val format: OfficeFormat) : DocumentExtractor {

    override val supportedMediaTypes: Set<String> =
        if (format == OfficeFormat.OOXML) setOf(PPTX_MEDIA_TYPE) else setOf(PPT_MEDIA_TYPE)

    override fun extract(input: ExtractionInput): Flow<ExtractionEvent> = flow {
        if (refuseOversized(input, Files.size(input.managedPath))) return@flow
        val units = mutableListOf<OfficeUnit>()
        val warnings = mutableListOf<String>()
        var slideCount = 0
        val where = artifactDirectory(input)
        when (format) {
            OfficeFormat.OOXML -> Files.newInputStream(input.managedPath).use { stream ->
                XMLSlideShow(stream).use { show ->
                    show.slides.forEachIndexed { index, slide ->
                        slideCount++
                        units.add(
                            slideUnit(
                                number = index + 1,
                                pageSize = show.pageSize,
                                shapeTexts = slide.shapes.mapNotNull { shapeText(it) },
                                notes = xslfNotesText(slide),
                                draw = { graphics -> slide.draw(graphics) },
                                where = where,
                                warnings = warnings,
                            ),
                        )
                    }
                }
            }

            OfficeFormat.LEGACY -> Files.newInputStream(input.managedPath).use { stream ->
                HSLFSlideShow(stream).use { show ->
                    show.slides.forEachIndexed { index, slide ->
                        slideCount++
                        units.add(
                            slideUnit(
                                number = index + 1,
                                pageSize = show.pageSize,
                                shapeTexts = slide.shapes.mapNotNull { shapeText(it) },
                                notes = hslfNotesText(slide.notes),
                                draw = { graphics -> slide.draw(graphics) },
                                where = where,
                                warnings = warnings,
                            ),
                        )
                    }
                }
            }
        }
        emitOfficeUnits(input, units.asSequence()) {
            buildMap {
                put(EXTRACTOR_METADATA, PRESENTATION_METADATA)
                put(FORMAT_METADATA, format.metadataName())
                put(SLIDES_METADATA, slideCount.toString())
                if (warnings.isNotEmpty()) put(RENDERING_WARNINGS_METADATA, warnings.size.toString())
            }
        }
    }

    /**
     * One slide's unit: its text, and a preview rendered inside the same permit as the artifact it writes.
     *
     * An empty slide still gets a unit. A slide number is a position a citation can honestly carry even
     * when the slide holds no words, and skipping it would renumber every slide after it.
     */
    private fun slideUnit(
        number: Int,
        pageSize: Dimension,
        shapeTexts: List<String>,
        notes: String?,
        draw: (Graphics2D) -> Unit,
        where: Path,
        warnings: MutableList<String>,
    ): OfficeUnit {
        val locator = SourceLocation.Slide(number)
        val key = "slide:$number"
        val text = (shapeTexts.filter { it.isNotBlank() } + listOfNotNull(notes?.takeIf { it.isNotBlank() }))
            .joinToString("\n")
        return OfficeUnit(key = key, ordinal = number - 1, locator = locator) {
            val preview = renderPreview(number, pageSize, draw, where, warnings)
            val normalised = TextNormalizer.normalize(text)
            ContentUnitDraft(
                locator = locator,
                extractedText = normalised.extracted,
                searchText = normalised.search,
                artifactRelativePath = preview?.relativePath,
                artifactSha256 = preview?.sha256,
            )
        }
    }

    private fun renderPreview(
        number: Int,
        pageSize: Dimension,
        draw: (Graphics2D) -> Unit,
        where: Path,
        warnings: MutableList<String>,
    ): Preview? = try {
        val width = pageSize.width.coerceAtLeast(1)
        val height = pageSize.height.coerceAtLeast(1)
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        try {
            draw(graphics)
        } finally {
            graphics.dispose()
        }
        Files.createDirectories(where)
        val name = String.format(Locale.ROOT, "slide-%06d.png", number)
        val target = where.resolve(name)
        // A half-written preview would be referenced by a unit that claims it is complete, so the file is
        // written under a temporary name and moved into place before the unit is delivered.
        val temporary = Files.createTempFile(where, "$name.", ".part")
        try {
            if (!ImageIO.write(image, "png", temporary.toFile())) {
                throw IllegalStateException("no PNG writer is available")
            }
            Files.move(temporary, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
        Preview(
            relativePath = "$PREVIEW_DIRECTORY/$name",
            sha256 = sha256Of(target),
        )
    } catch (failure: Throwable) {
        // A missing font or a headless graphics stack is not a reason to lose the slide's text.
        if (failure is InterruptedException) throw failure
        warnings.add("slide $number: ${failure::class.simpleName}")
        null
    }

    private data class Preview(val relativePath: String, val sha256: String)

    companion object {

        /** Where previews live under a document's artifact root. */
        const val PREVIEW_DIRECTORY: String = "preview"
    }
}

/** The metadata key naming which container family a document was read from. */
internal const val FORMAT_METADATA: String = "format"

/** The metadata key counting the sheets in a spreadsheet. */
internal const val SHEETS_METADATA: String = "sheets"

/** The metadata key counting the slides in a presentation. */
internal const val SLIDES_METADATA: String = "slides"

/** The metadata key counting previews that could not be rendered. */
internal const val RENDERING_WARNINGS_METADATA: String = "rendering_warnings"

internal const val WORD_METADATA: String = "word"

internal const val SPREADSHEET_METADATA: String = "spreadsheet"

internal const val PRESENTATION_METADATA: String = "presentation"

private fun OfficeFormat.metadataName(): String = when (this) {
    OfficeFormat.OOXML -> "ooxml"
    OfficeFormat.LEGACY -> "legacy"
}

/** What a Word document reports about itself: which extractor read it, and which family it came from. */
private fun wordMetadata(format: OfficeFormat): Map<String, String> = mapOf(
    EXTRACTOR_METADATA to WORD_METADATA,
    FORMAT_METADATA to format.metadataName(),
)

/**
 * One citable unit a format can still produce, with everything the pipeline needs before the expensive
 * part runs.
 *
 * [produce] is the expensive part — rendering a slide, formatting a worksheet's cells — and it is called
 * inside the unit boundary, so the artifact it writes and the checkpoint that describes it commit
 * together. [key] and [ordinal] are already known at that point, which is what lets a resumed attempt
 * skip committed units without doing their work.
 */
internal class OfficeUnit(
    val key: String,
    val ordinal: Int,
    val locator: SourceLocation,
    val produce: () -> ContentUnitDraft,
)

/**
 * Emits one document's units under the boundary protocol every extractor owes the pipeline.
 *
 * One permit per unit, taken before the unit is produced and released after the collector has committed
 * it; one more for the finished event. The flow stays unbuffered with no background producer, so nothing
 * runs after the permit is released.
 */
internal suspend fun FlowCollector<ExtractionEvent>.emitOfficeUnits(
    input: ExtractionInput,
    units: Sequence<OfficeUnit>,
    metadata: () -> Map<String, String>,
) {
    var total = 0
    units.forEach { unit ->
        input.boundary.unit {
            total++
            if (input.isCommitted(unit.key)) return@unit
            emit(ExtractionEvent.UnitReady(key = unit.key, ordinal = unit.ordinal, unit = unit.produce()))
        }
    }
    input.boundary.unit { emit(ExtractionEvent.Finished(metadata = metadata(), totalUnits = total)) }
}

/**
 * Refuses an Office document that is larger than the extractors will parse, and says whether it did.
 *
 * A document refused before its first unit has no unit to fail, so the failure names the document
 * instead. The key is the same one the HTML extractor uses, because the meaning is the same: this
 * document is too large to be read honestly, and a resumed attempt must not report it twice.
 */
private suspend fun FlowCollector<ExtractionEvent>.refuseOversized(
    input: ExtractionInput,
    size: Long,
): Boolean {
    if (size <= MAX_OFFICE_DOCUMENT_BYTES) return false
    if (!input.isCommitted(DOCUMENT_TOO_LARGE_KEY)) {
        input.boundary.unit {
            emit(
                ExtractionEvent.UnitFailed(
                    key = DOCUMENT_TOO_LARGE_KEY,
                    ordinal = 0,
                    code = DOCUMENT_TOO_LARGE_CODE,
                ),
            )
        }
    }
    return true
}

/** One block of a Word document: a paragraph or a table row, and the paragraphs it covers. */
private data class WordBlock(
    val startParagraph: Int,
    val endParagraph: Int,
    val text: String,
    val headingLevel: Int?,
    val headingTitle: String?,
)

/**
 * Turns a document's blocks into one unit per heading section.
 *
 * Sections close on the next heading of any level, so each block belongs to exactly one unit: a citation
 * names one place rather than the two that nesting would produce.
 */
private fun wordUnits(blocks: List<WordBlock>): List<OfficeUnit> {
    val units = mutableListOf<OfficeUnit>()
    val stack = ArrayDeque<Pair<Int, String>>()
    var headingPath: List<String> = emptyList()
    var texts = mutableListOf<String>()
    var startParagraph = 0
    var endParagraph = 0

    fun close() {
        if (texts.isEmpty()) return
        val locator = SourceLocation.WordSection(
            headingPath = headingPath,
            paragraphStart = startParagraph,
            paragraphEnd = endParagraph,
        )
        val text = texts.joinToString("\n")
        val key = "section:${units.size}"
        units.add(OfficeUnit(key = key, ordinal = units.size, locator = locator) {
            val normalised = TextNormalizer.normalize(text)
            ContentUnitDraft(locator, normalised.extracted, normalised.search)
        })
        texts = mutableListOf()
    }

    blocks.forEach { block ->
        val level = block.headingLevel
        val title = block.headingTitle
        if (level != null && title != null) {
            close()
            while (stack.isNotEmpty() && stack.last().first >= level) stack.removeLast()
            stack.addLast(level to title)
            headingPath = stack.map { it.second }
        }
        if (texts.isEmpty()) startParagraph = block.startParagraph
        endParagraph = block.endParagraph
        texts.add(block.text)
    }
    close()
    return units
}

/**
 * The blocks of an OOXML document, in reading order.
 *
 * A table row counts the paragraphs inside its cells, because that is how Word numbers them; the row is
 * cited as one block whose text keeps the cells in reading order.
 */
private fun ooxmlBlocks(document: XWPFDocument): List<WordBlock> {
    val blocks = mutableListOf<WordBlock>()
    var paragraphNumber = 0
    document.bodyElements.forEach { element ->
        when (element) {
            is XWPFParagraph -> {
                paragraphNumber++
                val text = element.text.trim()
                if (text.isNotEmpty()) {
                    blocks.add(
                        WordBlock(
                            startParagraph = paragraphNumber,
                            endParagraph = paragraphNumber,
                            text = text,
                            headingLevel = ooxmlHeadingLevel(document, element),
                            headingTitle = text,
                        ),
                    )
                }
            }

            is XWPFTable -> element.rows.forEach { row ->
                val cells = row.tableCells.map { cell -> cell.text.trim() }
                val cellParagraphs = row.tableCells.sumOf { cell -> cell.paragraphs.size }.coerceAtLeast(1)
                val start = paragraphNumber + 1
                paragraphNumber += cellParagraphs
                val text = cells.filter { it.isNotEmpty() }.joinToString(CELL_SEPARATOR)
                if (text.isNotEmpty()) {
                    blocks.add(
                        WordBlock(start, paragraphNumber, text, headingLevel = null, headingTitle = null),
                    )
                }
            }

            else -> Unit
        }
    }
    return blocks
}

/**
 * The blocks of a Word 97 document, in reading order.
 *
 * A legacy table ends its row with a paragraph that carries no text and exists only to close the row, so
 * that paragraph is not counted: it is the row's punctuation, not a paragraph a reader sees. Counting it
 * would push every paragraph after the first table out of step with the file.
 */
private fun legacyBlocks(document: HWPFDocument): List<WordBlock> {
    val blocks = mutableListOf<WordBlock>()
    val styles = document.styleSheet
    val range = document.range
    var paragraphNumber = 0
    var rowStart = 0
    var rowCells = mutableListOf<String>()

    for (index in 0 until range.numParagraphs()) {
        val paragraph = range.getParagraph(index)
        val text = legacyText(paragraph)
        if (paragraph.isInTable) {
            if (paragraph.isTableRowEnd) {
                val rowText = rowCells.filter { it.isNotEmpty() }.joinToString(CELL_SEPARATOR)
                if (rowText.isNotEmpty()) {
                    blocks.add(WordBlock(rowStart, paragraphNumber, rowText, headingLevel = null, headingTitle = null))
                }
                rowCells = mutableListOf()
                continue
            }
            if (rowCells.isEmpty()) rowStart = paragraphNumber + 1
            paragraphNumber++
            rowCells.add(text)
            continue
        }
        paragraphNumber++
        if (text.isNotEmpty()) {
            val styleName = styles.getStyleDescription(paragraph.styleIndex.toInt())?.name
            blocks.add(
                WordBlock(
                    startParagraph = paragraphNumber,
                    endParagraph = paragraphNumber,
                    text = text,
                    headingLevel = WordHeadingStyles.levelOf(styleId = null, styleName = styleName, outlineLevel = null),
                    headingTitle = text,
                ),
            )
        }
    }
    return blocks
}

/** A legacy paragraph's text without the terminators the binary format stores it with. */
private fun legacyText(paragraph: Paragraph): String =
    paragraph.text().trimEnd('\r', '\n', '\u0007', ' ')

/**
 * Which heading level a Word style stands for, or `null` when it is body text.
 *
 * Word records a heading twice over: as an outline level on the paragraph and as a style whose name
 * carries the level. Either is enough, and a document that has neither — because a converter flattened
 * its styles — has no headings to find.
 */
internal object WordHeadingStyles {

    /** The outline level Word writes; 9 means body text, so only 0..8 name a heading. */
    private const val FIRST_OUTLINE_LEVEL = 0

    private const val LAST_OUTLINE_LEVEL = 8

    /**
     * The level [styleId] or [styleName] names, preferring the document's own [outlineLevel].
     *
     * The style name is checked after the outline level because the name is the weaker signal: a
     * translated Word writes `Rubrik 1` where an English one writes `Heading 1`, and both mean level 1.
     */
    fun levelOf(styleId: String?, styleName: String?, outlineLevel: Int?): Int? {
        if (outlineLevel != null && outlineLevel in FIRST_OUTLINE_LEVEL..LAST_OUTLINE_LEVEL) {
            return outlineLevel + 1
        }
        listOfNotNull(styleId, styleName).forEach { candidate ->
            levelFromName(candidate)?.let { return it }
        }
        return null
    }

    private fun levelFromName(candidate: String): Int? {
        val cleaned = candidate.trim().lowercase(Locale.ROOT).replace(WHITESPACE, "").replace("-", "")
        return HEADING.matchEntire(cleaned)?.groupValues?.get(1)?.toInt()
    }

    private val WHITESPACE = Regex("\\s+")

    /** `Heading1` and `Rubrik 2` both name a level; nothing else does. */
    private val HEADING = Regex("^(?:$HEADING_WORDS)([1-9])$")

    /**
     * The words the common European Word releases put in a heading style's name.
     *
     * A style name is what a document carries regardless of how its styles were built, so recognising the
     * level means recognising the word in the language of the Word that wrote it. The list is the languages
     * the project targets; a document in a language outside it keeps its text and simply has no heading
     * sections, which is the same outcome as a document whose styles were flattened.
     */
    private const val HEADING_WORDS = "heading|rubrik|überschrift|titre|título|titolo|kop"
}

private fun ooxmlHeadingLevel(document: XWPFDocument, paragraph: XWPFParagraph): Int? {
    val styleId = paragraph.styleID
    val styleName = styleId?.let { id -> document.styles?.getStyle(id)?.name }
    val outlineLevel = paragraph.ctpPr?.outlineLvl?.`val`?.toInt()
    return WordHeadingStyles.levelOf(styleId = styleId, styleName = styleName, outlineLevel = outlineLevel)
}

/** The separator between the cells of a table row, so a row reads as one line of labels and values. */
private const val CELL_SEPARATOR = " | "

/**
 * A cell's value as a reader would see it.
 *
 * A formula cell is read from its stored result and formatted with the cell's own number format. Asking
 * POI to evaluate instead would run the workbook's formulas over material nobody has vouched for, and it
 * would also change the answer: what the document claims is the cached value.
 */
private fun displayValue(cell: Cell, formatter: DataFormatter): String = when (cell.cellType) {
    CellType.FORMULA -> storedResult(cell, formatter)
    else -> formatter.formatCellValue(cell)
}

private fun storedResult(cell: Cell, formatter: DataFormatter): String = when (cell.cachedFormulaResultType) {
    CellType.NUMERIC -> {
        val style = cell.cellStyle
        formatter.formatRawCellContents(cell.numericCellValue, style.dataFormat.toInt(), style.dataFormatString)
    }

    CellType.STRING -> cell.stringCellValue
    CellType.BOOLEAN -> cell.booleanCellValue.toString()
    else -> ""
}

/**
 * The text a shape carries, or `null` for a shape that has none.
 *
 * Both shape families implement the same interface, so this is the one place that decides what counts as
 * a shape's text.
 */
private fun shapeText(shape: Shape<*, *>): String? =
    (shape as? TextShape<*, *>)?.text?.trim()?.takeIf { it.isNotEmpty() }

/**
 * The speaker notes of an OOXML slide, without the layout text the notes master contributes.
 *
 * A notes slide that POI creates starts out holding the notes master's own prompts — "Click to edit
 * Master text styles", a date field, a slide number — and those are layout, not something anyone wrote
 * about this document. Text the notes master also shows is therefore skipped, together with the date,
 * footer, and slide-number placeholders, which are never notes.
 */
private fun xslfNotesText(slide: XSLFSlide): String? {
    val notes = slide.notes ?: return null
    val masterTexts = masterShapeTexts(notes.masterSheet)
    val texts = notes.shapes
        .filterNot { shape -> isLayoutPlaceholder(shape) }
        .mapNotNull { shape -> shapeText(shape) }
        .filterNot { text -> masterTexts.contains(text) }
        .filter { it.isNotBlank() }
        .joinToString("\n")
    return texts.takeIf { it.isNotBlank() }
}

private fun masterShapeTexts(master: XSLFSheet?): Set<String> =
    master?.shapes?.mapNotNull { shape -> shapeText(shape) }?.toSet().orEmpty()

/**
 * Whether a shape is a date, footer, or slide number: the parts of a layout a reader never wrote.
 *
 * The placeholder questions live on [SimpleShape] rather than on the base shape, because only a shape
 * that can be a placeholder has one.
 */
private fun isLayoutPlaceholder(shape: Shape<*, *>): Boolean {
    val simple = shape as? SimpleShape<*, *> ?: return false
    if (!simple.isPlaceholder) return false
    return when (simple.placeholder) {
        Placeholder.DATETIME, Placeholder.FOOTER, Placeholder.SLIDE_NUMBER -> true
        else -> false
    }
}

/**
 * The speaker notes of a legacy slide.
 *
 * HSLF exposes a paragraph's text through a static method rather than on the paragraph, and a slide
 * without notes has no notes object at all, so both cases are handled here.
 */
private fun hslfNotesText(notes: Notes<*, *>?): String? {
    if (notes == null) return null
    val groups = @Suppress("UNCHECKED_CAST")
    (notes as? org.apache.poi.hslf.usermodel.HSLFNotes)?.textParagraphs ?: return null
    val masterTexts = hslfMasterTexts(notes)
    val texts = groups.map { paragraphs -> HSLFTextParagraph.getText(paragraphs).trim() }
        .filterNot { text -> masterTexts.contains(text) }
        .filter { it.isNotBlank() }
        .joinToString("\n")
    return texts.takeIf { it.isNotBlank() }
}

private fun hslfMasterTexts(notes: Notes<*, *>): Set<String> {
    val master = (notes as? org.apache.poi.hslf.usermodel.HSLFNotes)?.masterSheet ?: return emptySet()
    return master.shapes.mapNotNull { shape -> shapeText(shape) }.toSet()
}

/** Where a document's previews are written. */
private fun artifactDirectory(input: ExtractionInput): Path =
    input.artifactRoot.resolve(PresentationExtractor.PREVIEW_DIRECTORY)

private fun sha256Of(path: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path).use { stream ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return HexFormat.of().formatHex(digest.digest())
}
