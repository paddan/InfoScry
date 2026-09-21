package infoscry.fixtures

import java.awt.Dimension
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.Date
import java.util.Optional
import org.apache.poi.hslf.usermodel.HSLFSlideShow
import org.apache.poi.hssf.usermodel.HSSFWorkbook
import org.apache.poi.sl.usermodel.Placeholder
import org.apache.poi.xslf.usermodel.XMLSlideShow
import org.apache.poi.xssf.usermodel.XSSFFormulaEvaluator
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.apache.poi.hssf.usermodel.HSSFFormulaEvaluator

/**
 * The Office fixtures the extractors are tested against, and the generator that produces them.
 *
 * The fixtures are committed, so a test run needs neither a converter nor a writer; the generator exists
 * so the bytes in the repository can be explained and rebuilt. Five of the six are written by POI itself
 * and are byte-stable: every document property that could carry a timestamp is set to
 * [FIXED_INSTANT], which is what makes "generate twice and compare" a meaningful assertion.
 *
 * `sample.doc` is the exception, and the reason is worth stating. POI has no writer for the Word 97
 * binary format — `HWPFDocument` can only be opened from an existing file — so the legacy document is
 * converted from the generated `sample.docx` by a system converter: LibreOffice when it is installed
 * (which keeps the heading styles), otherwise macOS `textutil` (which flattens every paragraph to
 * `Normal`). A maintainer regenerates it with [main]; everywhere else the committed file is simply read.
 * Because the two converters disagree about styles, the extractor tests assert what the committed file
 * actually contains rather than pretending the pair is identical.
 */
object OfficeFixtureGenerator {

    /** A fixed instant, so repeated generation produces identical bytes. */
    private val FIXED_INSTANT: Date = Date(1_577_836_800_000L)

    /** The instant as core properties want it. */
    private val FIXED_OPTIONAL: Optional<Date> = Optional.of(FIXED_INSTANT)

    /** Every property that would otherwise record who wrote a file and when. */
    private const val AUTHOR = "InfoScry fixture generator"

    private const val WORD_HEADING_ONE = "Inledning"

    private const val WORD_BODY = "Rapporten sammanfattar överföringarna i ärendet."

    private const val WORD_HEADING_TWO = "Bilaga"

    private const val WORD_TABLE_HEADER_LEFT = "Mottagare"

    private const val WORD_TABLE_HEADER_RIGHT = "Belopp"

    private const val WORD_TABLE_VALUE_LEFT = "Anna Öberg"

    private const val WORD_TABLE_VALUE_RIGHT = "1200"

    private const val TRANSFERS_SHEET = "Transfers"
    private const val SUMMARY_SHEET = "Sammanfattning"

    /** Speaker notes on the first slide of the OOXML fixture. */
    private const val NOTES_TEXT = "Kom ihåg att citera källan."

    /**
     * Writes all six fixtures into [target] and returns what it wrote.
     *
     * Files that already exist are overwritten, except `sample.doc` when no converter is available, which
     * is left in place with a printed note: the committed file is still the fixture the tests read.
     */
    fun writeAll(target: Path): List<Path> {
        Files.createDirectories(target)
        val written = mutableListOf<Path>()
        written.add(writeWordOoxml(target.resolve(WORD_OOXML_NAME)))
        written.add(writeWorkbookOoxml(target.resolve(WORKBOOK_OOXML_NAME)))
        written.add(writeSlidesOoxml(target.resolve(SLIDES_OOXML_NAME)))
        written.add(writeWorkbookLegacy(target.resolve(WORKBOOK_LEGACY_NAME)))
        written.add(writeSlidesLegacy(target.resolve(SLIDES_LEGACY_NAME)))
        writeWordLegacy(target.resolve(WORD_LEGACY_NAME), target.resolve(WORD_OOXML_NAME))?.let(written::add)
        return written
    }

    /** `sample.docx`: headings, a paragraph, and a table whose cells carry the values. */
    fun writeWordOoxml(target: Path): Path {
        XWPFDocument().use { document ->
            stamps(document)
            heading(document, WORD_HEADING_ONE, level = 1)
            document.createParagraph().createRun().setText(WORD_BODY)
            heading(document, WORD_HEADING_TWO, level = 2)
            val table = document.createTable(2, 2)
            table.getRow(0).getCell(0).text = WORD_TABLE_HEADER_LEFT
            table.getRow(0).getCell(1).text = WORD_TABLE_HEADER_RIGHT
            table.getRow(1).getCell(0).text = WORD_TABLE_VALUE_LEFT
            table.getRow(1).getCell(1).text = WORD_TABLE_VALUE_RIGHT
            Files.newOutputStream(target).use { document.write(it) }
        }
        normalizeZipTimestamps(target)
        return target
    }

    /** `sample.xlsx`: two sheets, a repeated header row, and formulas with stored results. */
    fun writeWorkbookOoxml(target: Path): Path {
        XSSFWorkbook().use { workbook ->
            stamps(workbook)
            val transfers = workbook.createSheet(TRANSFERS_SHEET)
            transfers.createRow(0).apply {
                createCell(0).setCellValue("Datum")
                createCell(1).setCellValue("Mottagare")
                createCell(2).setCellValue("Belopp")
            }
            transfers.createRow(1).apply {
                createCell(0).setCellValue("2020-01-02")
                createCell(1).setCellValue(WORD_TABLE_VALUE_LEFT)
                createCell(2).setCellValue(1200.0)
            }
            transfers.createRow(2).apply {
                createCell(0).setCellValue("2020-01-03")
                createCell(1).setCellValue("Bo Lind")
                createCell(2).setCellValue(800.0)
            }
            val total = transfers.createRow(3).createCell(2)
            total.cellFormula = "SUM(C2:C3)"

            val summary = workbook.createSheet(SUMMARY_SHEET)
            summary.createRow(0).apply {
                createCell(0).setCellValue("Post")
                createCell(1).setCellValue("Värde")
            }
            summary.createRow(1).apply {
                createCell(0).setCellValue("Summa")
                createCell(1).cellFormula = "SUM($TRANSFERS_SHEET!C2:C3)"
            }
            // The stored result is written once here so the fixture carries a cached value; the extractor
            // reads that value and never evaluates a formula.
            XSSFFormulaEvaluator(workbook).evaluateAll()
            Files.newOutputStream(target).use { workbook.write(it) }
        }
        normalizeZipTimestamps(target)
        return target
    }

    /** `sample.pptx`: two slides, one with speaker notes. */
    fun writeSlidesOoxml(target: Path): Path {
        XMLSlideShow().use { show ->
            stamps(show)
            val first = show.createSlide()
            first.createTextBox().setText("Översikt")
            first.createTextBox().setText("Två överföringar i ärendet.")
            // POI cannot fill a notes placeholder on a slide it created (that write is silently dropped),
            // but it can add a text box to the notes slide, which is where a person's notes live anyway.
            show.getNotesSlide(first)?.createTextBox()?.setText(NOTES_TEXT)
            val second = show.createSlide()
            second.createTextBox().setText("Bilaga")
            Files.newOutputStream(target).use { show.write(it) }
        }
        normalizeZipTimestamps(target)
        return target
    }

    /** `sample.xls`: the same workbook in the legacy binary format. */
    fun writeWorkbookLegacy(target: Path): Path {
        HSSFWorkbook().use { workbook ->
            workbook.createInformationProperties()
            val summary = workbook.summaryInformation
            summary.createDateTime = FIXED_INSTANT
            summary.lastSaveDateTime = FIXED_INSTANT
            summary.author = AUTHOR
            val transfers = workbook.createSheet(TRANSFERS_SHEET)
            transfers.createRow(0).apply {
                createCell(0).setCellValue("Datum")
                createCell(1).setCellValue("Mottagare")
                createCell(2).setCellValue("Belopp")
            }
            transfers.createRow(1).apply {
                createCell(0).setCellValue("2020-01-02")
                createCell(1).setCellValue(WORD_TABLE_VALUE_LEFT)
                createCell(2).setCellValue(1200.0)
            }
            transfers.createRow(2).apply {
                createCell(0).setCellValue("2020-01-03")
                createCell(1).setCellValue("Bo Lind")
                createCell(2).setCellValue(800.0)
            }
            transfers.createRow(3).createCell(2).cellFormula = "SUM(C2:C3)"
            val summarySheet = workbook.createSheet(SUMMARY_SHEET)
            summarySheet.createRow(0).apply {
                createCell(0).setCellValue("Post")
                createCell(1).setCellValue("Värde")
            }
            summarySheet.createRow(1).apply {
                createCell(0).setCellValue("Summa")
                createCell(1).cellFormula = "SUM($TRANSFERS_SHEET!C2:C3)"
            }
            HSSFFormulaEvaluator(workbook).evaluateAll()
            Files.newOutputStream(target).use { workbook.write(it) }
        }
        return target
    }

    /** `sample.ppt`: the same slides in the legacy binary format, without speaker notes. */
    fun writeSlidesLegacy(target: Path): Path {
        HSLFSlideShow().use { show ->
            show.createInformationProperties()
            val summary = show.summaryInformation
            summary.createDateTime = FIXED_INSTANT
            summary.lastSaveDateTime = FIXED_INSTANT
            summary.author = AUTHOR
            show.pageSize = Dimension(720, 540)
            val first = show.createSlide()
            first.createTextBox().setText("Översikt")
            first.createTextBox().setText("Två överföringar i ärendet.")
            // HSLF cannot create a notes slide (only read one that is already there), so the legacy
            // fixture carries no speaker notes and the notes path is proven with the OOXML fixture.
            val second = show.createSlide()
            second.createTextBox().setText("Bilaga")
            Files.newOutputStream(target).use { show.write(it) }
        }
        return target
    }

    /**
     * `sample.doc`, converted from the generated `sample.docx`.
     *
     * Returns `null` when neither converter is installed, leaving the committed file in place — that is
     * the expected case on a machine that only runs the tests.
     */
    fun writeWordLegacy(target: Path, source: Path): Path? {
        if (!Files.exists(source)) {
            println("OfficeFixtureGenerator: $source does not exist, so $target is left as it is")
            return null
        }
        val work = Files.createTempDirectory("infoscry-legacy-doc")
        try {
            when {
                onPath("soffice") -> convertWithSoffice(source, work, target)
                onPath("textutil") -> convertWithTextutil(source, target)
                else -> {
                    println(
                        "OfficeFixtureGenerator: neither soffice nor textutil is installed, so " +
                            "$target is left as it is",
                    )
                    return null
                }
            }
        } finally {
            work.toFile().deleteRecursively()
        }
        check(Files.exists(target)) { "the converter did not produce $target" }
        return target
    }

    /** Runs the generator: `main [target-directory]`, defaulting to the committed fixture directory. */
    @JvmStatic
    fun main(args: Array<String>) {
        val target = Path.of(args.firstOrNull() ?: DEFAULT_TARGET_DIRECTORY)
        val written = writeAll(target)
        written.forEach { println("wrote $it (${Files.size(it)} bytes)") }
        if (written.size < 6) {
            println(
                "Only ${written.size} of 6 fixtures were written: the legacy .doc needs LibreOffice or " +
                    "textutil, and the committed file is used until one is available.",
            )
        }
    }

    /** The directory `main` writes to when it is given no argument. */
    const val DEFAULT_TARGET_DIRECTORY: String = "src/test/resources/fixtures"

    const val WORD_OOXML_NAME: String = "sample.docx"

    const val WORD_LEGACY_NAME: String = "sample.doc"

    const val WORKBOOK_OOXML_NAME: String = "sample.xlsx"

    const val WORKBOOK_LEGACY_NAME: String = "sample.xls"

    const val SLIDES_OOXML_NAME: String = "sample.pptx"

    const val SLIDES_LEGACY_NAME: String = "sample.ppt"

    private fun heading(document: XWPFDocument, title: String, level: Int) {
        val paragraph = document.createParagraph()
        paragraph.style = "Heading$level"
        paragraph.createRun().setText(title)
    }

    private fun stamps(document: XWPFDocument) {
        document.properties.coreProperties.apply {
            setCreated(FIXED_OPTIONAL)
            setModified(FIXED_OPTIONAL)
            setCreator(AUTHOR)
            setLastModifiedByUser(AUTHOR)
        }
    }

    private fun stamps(workbook: XSSFWorkbook) {
        workbook.properties.coreProperties.apply {
            setCreated(FIXED_OPTIONAL)
            setModified(FIXED_OPTIONAL)
            setCreator(AUTHOR)
            setLastModifiedByUser(AUTHOR)
        }
    }

    private fun stamps(show: XMLSlideShow) {
        show.properties.coreProperties.apply {
            setCreated(FIXED_OPTIONAL)
            setModified(FIXED_OPTIONAL)
            setCreator(AUTHOR)
            setLastModifiedByUser(AUTHOR)
        }
    }

    private fun onPath(command: String): Boolean = System.getenv("PATH").orEmpty()
        .split(File.pathSeparator)
        .any { directory -> Files.isExecutable(Path.of(directory, command)) }

    /** LibreOffice keeps the heading styles, so it is preferred when it is installed. */
    private fun convertWithSoffice(source: Path, work: Path, target: Path) {
        val status = ProcessBuilder(
            "soffice",
            "--headless",
            "--norestore",
            "--convert-to",
            "doc",
            "--outdir",
            work.toString(),
            source.toString(),
        ).redirectErrorStream(true).start().let { process ->
            process.inputStream.readAllBytes()
            process.waitFor()
        }
        check(status == 0) { "soffice failed converting $source (exit $status)" }
        Files.copy(work.resolve("${source.fileName.toString().substringBeforeLast('.')}.doc"), target, REPLACE)
    }

    /** macOS `textutil` flattens heading styles to `Normal`; the extractor tests account for that. */
    private fun convertWithTextutil(source: Path, target: Path) {
        val status = ProcessBuilder(
            "textutil",
            "-convert",
            "doc",
            "-output",
            target.toString(),
            source.toString(),
        ).redirectErrorStream(true).start().let { process ->
            process.inputStream.readAllBytes()
            process.waitFor()
        }
        check(status == 0) { "textutil failed converting $source (exit $status)" }
    }

    private val REPLACE = java.nio.file.StandardCopyOption.REPLACE_EXISTING

    /**
     * Rewrites an OOXML container with every entry stamped at the same instant.
     *
     * POI's OOXML writers put the current clock into each zip entry, so two runs a second apart produce
     * different bytes even when the document is identical. That stamp says nothing about the document's
     * content — the core properties carry the document's own dates, and those are fixed — so it is
     * normalised here, which is what makes "generate twice and compare the bytes" an assertion about the
     * document rather than about the clock.
     */
    private fun normalizeZipTimestamps(path: Path) {
        val entries = mutableListOf<Pair<String, ByteArray>>()
        java.util.zip.ZipFile(path.toFile()).use { zip ->
            zip.entries().asSequence().forEach { entry ->
                entries.add(entry.name to zip.getInputStream(entry).readAllBytes())
            }
        }
        val normalized = Files.createTempFile(path.parent, "normalized", ".zip")
        try {
            java.util.zip.ZipOutputStream(Files.newOutputStream(normalized)).use { output ->
                entries.forEach { (name, bytes) ->
                    val entry = java.util.zip.ZipEntry(name)
                    entry.setTimeLocal(FIXED_LOCAL_TIME)
                    output.putNextEntry(entry)
                    output.write(bytes)
                    output.closeEntry()
                }
            }
            Files.move(normalized, path, REPLACE)
        } finally {
            Files.deleteIfExists(normalized)
        }
    }

    /** The stamp every OOXML entry carries, in local time so the bytes do not depend on the time zone. */
    private val FIXED_LOCAL_TIME = java.time.LocalDateTime.of(2020, 1, 1, 0, 0, 0)
}
