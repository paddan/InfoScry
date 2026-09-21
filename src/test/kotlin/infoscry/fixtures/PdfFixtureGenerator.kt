package infoscry.fixtures

import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.encryption.AccessPermission
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory

/**
 * The PDF fixtures the extractor and the OCR decision are tested against, and the generator that
 * produces them.
 *
 * The fixtures are committed, so a test run needs only PDFBox; the generator exists so the bytes in the
 * repository can be explained and rebuilt. Every property that could carry a timestamp — the document's
 * creation and modification dates, the trailer's document id, the image data — is fixed, which is what
 * makes "generate twice and compare" a meaningful assertion.
 *
 * The fixtures are deliberately shaped around the page-level decisions this task implements:
 *
 * - [TEXT_NAME] is every page's own text layer: a readable document that must never reach OCR.
 * - [MIXED_NAME] is a document whose pages need different treatment — readable pages, pages whose text
 *   layer is empty and therefore need OCR, and a final page whose declared size cannot be rendered, so
 *   the page-level failure path is exercised against a page a reader genuinely cannot produce. PDFBox
 *   does not raise on *damaged content* (it logs a warning and yields empty text), so the page that
 *   stops a reader is one whose raster cannot exist rather than one whose bytes are corrupt; the task
 *   report records that observation.
 * - [PROTECTED_NAME] is opened with a user password and must be refused rather than silently treated as
 *   an empty document.
 *
 * The scanned pages carry geometry rather than drawn text. Rendered glyphs depend on the machine's font
 * rasteriser, and a fixture whose bytes change with the machine cannot be committed as evidence; the
 * property these pages need to have is an empty text layer, which geometry gives exactly.
 */
object PdfFixtureGenerator {

    /** A fixed instant, so repeated generation produces identical bytes. */
    private val FIXED_CALENDAR: Calendar = GregorianCalendar(TimeZone.getTimeZone("UTC")).apply {
        timeInMillis = 1_577_836_800_000L
    }

    /** A fixed trailer id, so the file does not carry the moment it was written. */
    private const val FIXED_DOCUMENT_ID: Long = 0x494e464f53435259L

    private const val PRODUCER = "InfoScry fixture generator"

    /** Body text a reader sees, and long enough that its text layer is never sent to OCR. */
    private val TEXT_LINES: List<String> = listOf(
        "Protokollet sammanfattar överföringarna i ärendet.",
        "Samtliga transaktioner redovisas i bilagan nedan.",
        "Handlingarna är diarieförda och tillgängliga för granskning.",
    )

    /** The password `protected.pdf` is opened with, and the owner password it is protected under. */
    const val USER_PASSWORD: String = "infoscry"

    const val OWNER_PASSWORD: String = "infoscry-owner"

    const val TEXT_NAME: String = "text.pdf"

    const val MIXED_NAME: String = "mixed.pdf"

    const val PROTECTED_NAME: String = "protected.pdf"

    /** The readable pages of [MIXED_NAME], one-based. */
    val MIXED_TEXT_PAGES: List<Int> = listOf(1, 2)

    /** The pages of [MIXED_NAME] whose text layer is empty, so OCR has to read them. One-based. */
    val MIXED_SCANNED_PAGES: List<Int> = listOf(3, 4, 5)

    /** The page of [MIXED_NAME] whose declared size cannot be rendered. One-based. */
    const val MIXED_UNRENDERABLE_PAGE: Int = 6

    /**
     * The size in points of the unrenderable page.
     *
     * Rendering it at any legible resolution asks for a raster far past what an image can hold, which is
     * what makes it the page a reader has to give up on: PDFBox refuses it itself, and so does the
     * extractor's own pixel bound before either allocates anything.
     */
    const val UNRENDERABLE_PAGE_SIDE: Float = 200_000f

    /** The directory `main` writes to when it is given no argument. */
    const val DEFAULT_TARGET_DIRECTORY: String = "src/test/resources/fixtures"

    /** Writes every fixture into [target] and returns what it wrote. */
    fun writeAll(target: Path): List<Path> {
        Files.createDirectories(target)
        return listOf(
            writeText(target.resolve(TEXT_NAME)),
            writeMixed(target.resolve(MIXED_NAME)),
            writeProtected(target.resolve(PROTECTED_NAME)),
        )
    }

    /** `text.pdf`: three pages that all carry usable text. */
    fun writeText(target: Path): Path {
        PDDocument().use { document ->
            stamps(document)
            repeat(3) { index -> addTextPage(document, body = TEXT_LINES.rotate(index)) }
            document.save(target.toFile())
        }
        return target
    }

    /**
     * `mixed.pdf`: readable pages, scanned pages, and one unreadable page.
     *
     * The order matters to the tests that use it: the readable pages come first so a resumed attempt can
     * commit them and then still be asked for the scanned pages that follow.
     */
    fun writeMixed(target: Path): Path {
        PDDocument().use { document ->
            stamps(document)
            MIXED_TEXT_PAGES.forEach { page -> addTextPage(document, body = TEXT_LINES.rotate(page)) }
            MIXED_SCANNED_PAGES.forEach { page -> addScannedPage(document, page) }
            addUnrenderablePage(document)
            document.save(target.toFile())
        }
        return target
    }

    /** `protected.pdf`: two readable pages behind a user password. */
    fun writeProtected(target: Path): Path {
        PDDocument().use { document ->
            stamps(document)
            repeat(2) { index -> addTextPage(document, body = TEXT_LINES.rotate(index)) }
            document.protect(StandardProtectionPolicy(OWNER_PASSWORD, USER_PASSWORD, AccessPermission()))
            document.save(target.toFile())
        }
        return target
    }

    /** Runs the generator: `main [target-directory]`, defaulting to the committed fixture directory. */
    @JvmStatic
    fun main(args: Array<String>) {
        val target = Path.of(args.firstOrNull() ?: DEFAULT_TARGET_DIRECTORY)
        writeAll(target).forEach { println("wrote $it (${Files.size(it)} bytes)") }
    }

    /** Every property that would otherwise record who wrote a file and when. */
    private fun stamps(document: PDDocument) {
        document.documentInformation.apply {
            title = "InfoScry PDF fixture"
            creator = PRODUCER
            producer = PRODUCER
            creationDate = FIXED_CALENDAR
            modificationDate = FIXED_CALENDAR
        }
        document.documentId = FIXED_DOCUMENT_ID
    }

    /** One page whose text layer carries [body], readable without OCR. */
    private fun addTextPage(document: PDDocument, body: List<String>) {
        val page = PDPage(PDRectangle.A4)
        document.addPage(page)
        PDPageContentStream(document, page).use { content ->
            content.beginText()
            content.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 12f)
            content.newLineAtOffset(72f, 760f)
            body.forEach { line ->
                content.showText(line)
                content.newLineAtOffset(0f, -18f)
            }
            content.endText()
        }
    }

    /** One page with an image and no text layer: the scanned page OCR exists for. */
    private fun addScannedPage(document: PDDocument, page: Int) {
        val paper = PDPage(PDRectangle.A4)
        document.addPage(paper)
        val image = LosslessFactory.createFromImage(document, scannedImage(page))
        PDPageContentStream(document, paper).use { content ->
            content.drawImage(image, 0f, 0f, PDRectangle.A4.width, PDRectangle.A4.height)
        }
    }

    /**
     * One page whose declared size is far past anything that can be rendered.
     *
     * The page and its container are valid PDF, and the page has no content of its own, so a reader that
     * only wants its text sees an empty page; a reader that has to render it cannot, because the raster
     * that size implies does not fit in an image. That is the shape a page a reader must give up on has
     * in a real archive — the rest of the file still reads.
     */
    private fun addUnrenderablePage(document: PDDocument) {
        document.addPage(PDPage(PDRectangle(UNRENDERABLE_PAGE_SIDE, UNRENDERABLE_PAGE_SIDE)))
    }

    /** A scan-like page: white paper with rows of dark bars, drawn without a font. */
    private fun scannedImage(page: Int): BufferedImage {
        val width = 595
        val height = 842
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        try {
            graphics.color = Color.WHITE
            graphics.fillRect(0, 0, width, height)
            graphics.color = Color(0x22, 0x22, 0x22)
            for (row in 0 until 24) {
                val y = 60 + row * 30 + page
                val barWidth = 420 - (row % 5) * 40
                graphics.fillRect(80, y, barWidth, 12)
            }
        } finally {
            graphics.dispose()
        }
        return image
    }

    /** The same list, started at [offset], so the readable pages are not identical to each other. */
    private fun List<String>.rotate(offset: Int): List<String> =
        List(size) { index -> this[(index + offset) % size] }
}
