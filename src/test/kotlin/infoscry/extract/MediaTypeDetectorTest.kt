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

    // ---- E-books: a container the bytes describe only as "a zip" or "some binary" ------------------

    @Test
    fun `an epub is named by its own mimetype entry rather than by its extension`() {
        val file = directory.resolve("book.zip")
        writeEpub(file, epub + "\n")

        assertEquals("application/epub+zip", detector.detect(file).value)
    }

    @Test
    fun `a kepub is the same container as an epub`() {
        val file = directory.resolve("book.kepub.epub")
        writeEpub(file, epub)

        assertEquals("application/epub+zip", detector.detect(file).value)
    }

    @Test
    fun `an ibooks file is named by the type it declares`() {
        val file = directory.resolve("book.ibooks")
        writeEpub(file, "application/x-ibooks+zip")

        assertEquals("application/x-ibooks+zip", detector.detect(file).value)
    }

    @Test
    fun `an epub whose mimetype entry is missing is recognised by its container index`() {
        val file = directory.resolve("book.epub")
        writeZip(file, "META-INF/container.xml", "<container/>")

        assertEquals(
            "application/epub+zip",
            detector.detect(file).value,
            "a book whose mimetype entry is missing is still the book its own index describes",
        )
    }

    @Test
    fun `a zipped fictionbook is named by the document it wraps`() {
        val file = directory.resolve("book.fbz")
        writeZip(file, "book/sample.fb2", "<FictionBook/>")

        assertEquals("application/x-fictionbook+zip", detector.detect(file).value)
    }

    @Test
    fun `a fictionbook is detected from its own root element`() {
        val file = directory.resolve("book.dat")
        Files.writeString(
            file,
            """<?xml version="1.0" encoding="UTF-8"?>
               <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0"><body/></FictionBook>
            """.trimIndent(),
        )

        assertEquals("application/x-fictionbook+xml", detector.detect(file).value)
    }

    @Test
    fun `a kindle book is named from its magic rather than its name`() {
        val file = directory.resolve("book.dat")
        Files.write(file, mobipocketBytes())

        assertEquals("application/x-mobipocket-ebook", detector.detect(file).value)
    }

    @Test
    fun `a legacy e-book whose bytes name nothing is routed by the name it was published under`() {
        val file = directory.resolve("book.lit")
        Files.write(file, ByteArray(256) { index -> (index and 0xFF).toByte() })

        assertEquals(
            "application/x-infoscry-ebook",
            detector.detect(file).value,
            "a converter is the only reader for this family, and the name is the only evidence there is",
        )
    }

    @Test
    fun `a legacy e-book extension does not claim bytes that are a readable format`() {
        val file = directory.resolve("photo.lit")
        writePng(file)

        assertEquals(
            "image/png",
            detector.detect(file).value,
            "the name overruled the bytes for a format this build can read itself",
        )
    }

    @Test
    fun `an unnameable binary with an unknown name stays unnamed`() {
        val file = directory.resolve("data.xyz")
        Files.write(file, ByteArray(256) { index -> (index and 0xFF).toByte() })

        assertEquals("application/octet-stream", detector.detect(file).value)
    }

    @Test
    fun `a plain zip is still a plain zip and not claimed as a book`() {
        val file = directory.resolve("archive.epub")
        writeZip(file, "notes.txt", "just words")

        assertEquals("application/zip", detector.detect(file).value)
    }

    /** An EPUB as the format requires it: the mimetype entry first, holding the declared type. */
    private fun writeEpub(file: Path, declaredMimetype: String) {
        Files.newOutputStream(file).use { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("mimetype"))
                zip.write(declaredMimetype.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("META-INF/container.xml"))
                zip.write("<container/>".toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
    }

    /** A Palm database header that says BOOKMOBI, which is what makes a file a Mobipocket container. */
    private fun mobipocketBytes(): ByteArray {
        val bytes = ByteArray(512)
        "BOOKMOBI".toByteArray(Charsets.US_ASCII).copyInto(bytes, destinationOffset = 60)
        return bytes
    }

    private val epub: String = "application/epub+zip"

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
