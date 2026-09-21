package infoscry.fixtures

import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

/**
 * The image fixture the OCR tests read, and the generator that produces it.
 *
 * The fixture is committed, so a test run needs no font work and no image tooling; the generator exists
 * so the bytes in the repository can be explained and rebuilt. Unlike the PDF fixtures it is deliberately
 * *not* compared byte for byte when regenerated: glyph rasterisation depends on the machine's font stack
 * and the JDK's rasteriser, and a fixture whose bytes change with the machine cannot be committed as a
 * reproducible artefact. What matters here is only what the letters say, because the property under test
 * is that OCR reads both languages from one image.
 *
 * The text is chosen for what the reading has to prove: a Swedish line with a non-ASCII letter
 * (`ä`, which passes through normalisation), an English line, and one line mixing both languages, so a
 * language pack that is missing or wrongly passed shows up as a missing word rather than as an empty
 * reading.
 */
object OcrFixtureGenerator {

    /** The committed fixture's name. */
    const val IMAGE_NAME: String = "ocr-swe-eng.png"

    /** Where the committed fixtures live. */
    const val DEFAULT_TARGET_DIRECTORY: String = "src/test/resources/fixtures"

    /** The one Swedish word the fixture is asserted by: it carries the letter `ä`. */
    const val SWEDISH_WORD: String = "hemligstämplad"

    /** The one English word the fixture is asserted by. */
    const val ENGLISH_WORD: String = "scanned"

    /** The lines the fixture draws, in the order they are drawn. */
    val LINES: List<String> = listOf(
        "Rapporten är hemligstämplad.",
        "InfoScry reads scanned pages.",
        "Svenska och engelska i samma bild.",
    )

    private const val WIDTH: Int = 1200
    private const val HEIGHT: Int = 340
    private const val MARGIN: Int = 40
    private const val FONT_SIZE: Int = 58
    private const val LINE_SPACING: Int = 88

    /** Writes the fixture into [target] and returns what it wrote. */
    fun writeAll(target: Path): List<Path> {
        Files.createDirectories(target)
        return listOf(writeImage(target.resolve(IMAGE_NAME)))
    }

    /**
     * `ocr-swe-eng.png`: three lines of black text on white, at a size Tesseract reads reliably.
     *
     * The page is plain on purpose. A fixture with a border, a rotation, or a background texture would
     * measure Tesseract's preprocessing rather than this project's use of it, and a page that fails to
     * read would not say which of the two went wrong.
     */
    fun writeImage(target: Path): Path {
        val image = BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        try {
            graphics.setRenderingHint(
                RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON,
            )
            graphics.color = Color.WHITE
            graphics.fillRect(0, 0, WIDTH, HEIGHT)
            graphics.color = Color.BLACK
            graphics.font = Font(Font.SANS_SERIF, Font.PLAIN, FONT_SIZE)
            LINES.forEachIndexed { index, line ->
                graphics.drawString(line, MARGIN, MARGIN + FONT_SIZE + index * LINE_SPACING)
            }
        } finally {
            graphics.dispose()
        }
        check(ImageIO.write(image, "png", target.toFile())) { "no PNG writer is available" }
        image.flush()
        return target
    }

    /** Runs the generator: `main [target-directory]`, defaulting to the committed fixture directory. */
    @JvmStatic
    fun main(args: Array<String>) {
        val target = Path.of(args.firstOrNull() ?: DEFAULT_TARGET_DIRECTORY)
        writeAll(target).forEach { println("wrote $it (${Files.size(it)} bytes)") }
    }
}
