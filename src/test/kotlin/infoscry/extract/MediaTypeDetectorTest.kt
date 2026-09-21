package infoscry.extract

import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What a file *is*, decided from its bytes rather than its name.
 *
 * Every case here gives the file a deliberately wrong extension. Imported material arrives with whatever
 * name it was published under, and a swap of an extension is the single cheapest way to make the
 * pipeline do the wrong thing: run a PDF extractor over an image, or hand a spreadsheet to a text reader.
 */
class MediaTypeDetectorTest {

    private lateinit var directory: Path
    private val detector = MediaTypeDetector()

    @BeforeTest
    fun createTemporaryDirectory() {
        directory = Files.createTempDirectory("infoscry-detect")
    }

    @AfterTest
    fun removeTemporaryDirectory() {
        directory.toFile().deleteRecursively()
    }

    @Test
    fun `a binary workbook is not claimed as a spreadsheet it is not`() {
        val file = directory.resolve("register.xlsb")
        writeBinaryWorkbook(file)

        val detected = detector.detect(file).value

        assertEquals(
            "application/x-tika-ooxml",
            detected,
            "a binary workbook is a container this build cannot read, so it is left to the generic type",
        )
        assertFalse(
            detected == XLSX_MEDIA_TYPE,
            "an .xlsb would be handed to XSSFWorkbook, which throws at the user instead of refusing it",
        )
    }

    private fun writeBinaryWorkbook(target: Path) {
        ZipOutputStream(Files.newOutputStream(target)).use { zip ->
            zip.putNextEntry(ZipEntry("[Content_Types].xml"))
            zip.write("<Types/>".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("xl/workbook.bin"))
            zip.write(byteArrayOf(1, 2, 3))
            zip.closeEntry()
        }
    }

    @Test
    fun `a png named like a pdf is detected as a png`() {
        val file = directory.resolve("report.pdf")
        writePng(file)

        assertEquals("image/png", detector.detect(file).value)
    }

    @Test
    fun `plain text named like a word document is detected as text`() {
        val file = directory.resolve("minutes.docx")
        Files.writeString(file, "Nämnden beslutade att överklaga.\n")

        assertEquals("text/plain", detector.detect(file).value)
    }

    @Test
    fun `a text container is narrowed by the name it was published under`() {
        val spreadsheet = directory.resolve("register.csv")
        Files.writeString(spreadsheet, "id,name\n1,Anna\n")
        val markdown = directory.resolve("anteckningar.md")
        Files.writeString(markdown, "# Rubrik\nText.\n")
        val prose = directory.resolve("protokoll.txt")
        Files.writeString(prose, "Bara text.\n")

        assertEquals("text/csv", detector.detect(spreadsheet).value)
        assertEquals("text/markdown", detector.detect(markdown).value)
        assertEquals("text/plain", detector.detect(prose).value)
    }

    @Test
    fun `a zip archive named like a csv is still a zip container`() {
        val file = directory.resolve("register.csv")
        writeZip(file, "entry.txt", "inside")

        assertTrue(
            detector.detect(file).value.contains("zip"),
            "the name overruled the bytes: ${detector.detect(file).value}",
        )
    }

    @Test
    fun `a zip archive named like a text file is detected as a zip container`() {
        val file = directory.resolve("notes.txt")
        writeZip(file, "entries/one.txt", "inside")

        assertEquals("application/zip", detector.detect(file).value)
    }

    @Test
    fun `bytes with no recognisable signature are not claimed to be text`() {
        val file = directory.resolve("data.csv")
        val bytes = ByteArray(256) { (it and 0xFF).toByte() }
        Files.write(file, bytes)

        val detected = detector.detect(file).value

        assertFalse(detected.startsWith("text/"), "garbage was reported as text: $detected")
        assertFalse(detected == "text/csv", "garbage was reported as a spreadsheet: $detected")
    }

    @Test
    fun `detection reads the file and not its name`() {
        val textNamedZip = directory.resolve("archive.txt")
        writeZip(textNamedZip, "entry.txt", "inside")
        val zipNamedText = directory.resolve("archive.zip")
        Files.writeString(zipNamedText, "just words\n")

        assertTrue(detector.detect(textNamedZip).value.contains("zip"))
        assertEquals("text/plain", detector.detect(zipNamedText).value)
    }

    private fun writePng(file: Path) {
        val image = BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB)
        image.setRGB(0, 0, 0x102030)
        Files.newOutputStream(file).use { ImageIO.write(image, "png", it) }
    }

    private fun writeZip(file: Path, entryName: String, content: String) {
        Files.newOutputStream(file).use { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry(entryName))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
    }
}
