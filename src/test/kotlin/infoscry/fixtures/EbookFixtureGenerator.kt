package infoscry.fixtures

import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO

/**
 * The e-book fixtures the reader tests extract, and the generator that produces them.
 *
 * The two files are committed, so a test run parses exactly the bytes a reader can also inspect; the
 * generator exists so those bytes can be explained and rebuilt. What the generator guarantees is the
 * *content*, not the exact bytes: a zip's compressed stream follows the JDK's deflate implementation, so
 * regenerating on another JDK can legitimately produce a different file. The tests therefore assert what
 * the extraction reads — chapters, headings, metadata, the picture-only chapter — rather than comparing
 * the container byte for byte, which is the same reason the OCR image fixture is not compared.
 *
 * The EPUB is deliberately built to exercise the parts of the format a reader has to get right and a
 * naive reader gets wrong:
 *
 * - the `mimetype` entry comes first and is **stored**, not deflated, which is what makes the file an
 *   EPUB by its own index rather than by its name;
 * - the package document lives under `OEBPS/` rather than at the root, so an href is only meaningful
 *   relative to the OPF;
 * - chapter two holds an `h1` with a nested `h2`, so one chapter has to become two citable sections;
 * - the first chapter holds a footnote `aside`, which is part of the section a reader would cite;
 * - the third chapter has **no text at all** and one picture, which is the shape that has to reach OCR
 *   instead of being indexed as an empty section;
 * - the navigation document names the chapters, so a citation can say "Andra kapitlet" instead of naming
 *   a file.
 *
 * The FB2 is the same idea in the other family: a title, an author, and two nested sections, so the
 * fictionbook reader's own nesting is exercised rather than one flat body.
 */
object EbookFixtureGenerator {

    /** The EPUB file the tests read. */
    const val EPUB_NAME: String = "sample.epub"

    /** The FictionBook file the tests read. */
    const val FB2_NAME: String = "sample.fb2"

    /** Where the committed fixtures live. */
    const val DEFAULT_TARGET_DIRECTORY: String = "src/test/resources/fixtures"

    /** The book's title, which a citation shows and the metadata has to carry. */
    const val TITLE: String = "Nattens protokoll"

    /** The book's author, in the order the package lists it. */
    const val AUTHOR: String = "Anna Lind"

    /** The book's publisher. */
    const val PUBLISHER: String = "Arkivförlaget"

    /** The book's language, which is the kind of fact a collection filter can use. */
    const val LANGUAGE: String = "sv"

    /** The book's identifier. */
    const val IDENTIFIER: String = "urn:isbn:978-91-0000000-0"

    /** The first chapter's title, as the navigation document spells it. */
    const val FIRST_CHAPTER: String = "Första kapitlet"

    /** The second chapter's title, as the navigation document spells it. */
    const val SECOND_CHAPTER: String = "Andra kapitlet"

    /** The second chapter's subsection, which is the section a citation has to be able to name. */
    const val SECOND_SECTION: String = "Avsnittet om överföringarna"

    /** The third chapter's title: the one with no text of its own. */
    const val THIRD_CHAPTER: String = "Bilderna"

    /** A sentence from the first chapter that a search test can look for. */
    const val FIRST_CHAPTER_SENTENCE: String = "Protokollet är daterat den fjärde mars."

    /** The footnote's text, which belongs to the section that carries it. */
    const val FOOTNOTE_TEXT: String = "Se bilaga två för de fullständiga beloppen."

    /** A sentence from the second chapter's subsection. */
    const val SECOND_SECTION_SENTENCE: String = "Överföringarna godkändes av två personer."

    /** A sentence from the FB2 body. */
    const val FB2_SENTENCE: String = "Kapitlet redogör för den inledande utredningen."

    /** Writes both fixtures into [target] and returns what it wrote. */
    fun writeAll(target: Path): List<Path> {
        Files.createDirectories(target)
        return listOf(
            writeEpub(target.resolve(EPUB_NAME)),
            writeFb2(target.resolve(FB2_NAME)),
        )
    }

    /** `sample.epub`: three chapters, one of which is a picture with no text. */
    fun writeEpub(target: Path): Path {
        Files.newOutputStream(target).use { file ->
            ZipOutputStream(file).use { zip ->
                stored(zip, "mimetype", "application/epub+zip")
                zip.writeEntry("META-INF/container.xml", CONTAINER)
                zip.writeEntry("OEBPS/content.opf", packageDocument())
                zip.writeEntry("OEBPS/nav.xhtml", navDocument())
                zip.writeEntry("OEBPS/chapter1.xhtml", firstChapter())
                zip.writeEntry("OEBPS/chapter2.xhtml", secondChapter())
                zip.writeEntry("OEBPS/chapter3.xhtml", thirdChapter())
                zip.writeEntry("OEBPS/images/plate.png", picture())
                zip.writeEntry("OEBPS/images/facsimile.png", picture())
            }
        }
        return target
    }

    /** `sample.fb2`: one body with a title and a nested section. */
    fun writeFb2(target: Path): Path {
        Files.writeString(target, fb2Document(), StandardCharsets.UTF_8)
        return target
    }

    /**
     * A zip entry every reader sees as an EPUB: first in the container, uncompressed, with its CRC.
     *
     * The format requires exactly this, and a reader that only looked at names would not notice; a test
     * fixture that got it wrong would be evidence about the fixture rather than about the reader.
     */
    private fun stored(zip: ZipOutputStream, name: String, content: String) {
        val bytes = content.toByteArray(StandardCharsets.UTF_8)
        val entry = ZipEntry(name).apply {
            method = ZipEntry.STORED
            size = bytes.size.toLong()
            compressedSize = bytes.size.toLong()
            crc = CRC32().apply { update(bytes) }.value
            time = FIXED_TIME_MILLIS
        }
        zip.putNextEntry(entry)
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun ZipOutputStream.writeEntry(name: String, text: String) {
        writeEntry(name, text.toByteArray(StandardCharsets.UTF_8))
    }

    private fun ZipOutputStream.writeEntry(name: String, payload: ByteArray) {
        val entry = ZipEntry(name).apply { time = FIXED_TIME_MILLIS }
        putNextEntry(entry)
        write(payload)
        closeEntry()
    }

    /** A small picture: enough for an image-only chapter to have something to hand to the tool. */
    private fun picture(): ByteArray {
        val image = BufferedImage(48, 24, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        try {
            graphics.color = Color.WHITE
            graphics.fillRect(0, 0, 48, 24)
            graphics.color = Color.BLACK
            graphics.drawString("s. 3", 6, 16)
        } finally {
            graphics.dispose()
        }
        val output = java.io.ByteArrayOutputStream()
        check(ImageIO.write(image, "png", output)) { "no PNG writer is available" }
        image.flush()
        return output.toByteArray()
    }

    private fun packageDocument(): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bookid">
          <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
            <dc:title>$TITLE</dc:title>
            <dc:creator>$AUTHOR</dc:creator>
            <dc:publisher>$PUBLISHER</dc:publisher>
            <dc:language>$LANGUAGE</dc:language>
            <dc:identifier id="bookid">$IDENTIFIER</dc:identifier>
          </metadata>
          <manifest>
            <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
            <item id="c1" href="chapter1.xhtml" media-type="application/xhtml+xml"/>
            <item id="c2" href="chapter2.xhtml" media-type="application/xhtml+xml"/>
            <item id="c3" href="chapter3.xhtml" media-type="application/xhtml+xml"/>
          </manifest>
          <spine>
            <itemref idref="c1"/>
            <itemref idref="c2"/>
            <itemref idref="c3"/>
          </spine>
        </package>
    """.trimIndent()

    private fun navDocument(): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
          <head><title>Innehåll</title></head>
          <body>
            <nav epub:type="toc"><ol>
              <li><a href="chapter1.xhtml">$FIRST_CHAPTER</a></li>
              <li><a href="chapter2.xhtml">$SECOND_CHAPTER</a></li>
              <li><a href="chapter3.xhtml">$THIRD_CHAPTER</a></li>
            </ol></nav>
          </body>
        </html>
    """.trimIndent()

    private fun firstChapter(): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
          <head><title>$FIRST_CHAPTER</title></head>
          <body>
            <h1>$FIRST_CHAPTER</h1>
            <p>$FIRST_CHAPTER_SENTENCE</p>
            <aside epub:type="footnote"><p>$FOOTNOTE_TEXT</p></aside>
          </body>
        </html>
    """.trimIndent()

    private fun secondChapter(): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <html xmlns="http://www.w3.org/1999/xhtml">
          <head><title>$SECOND_CHAPTER</title></head>
          <body>
            <h1>$SECOND_CHAPTER</h1>
            <p>Kapitlet inleds med en översikt.</p>
            <h2>$SECOND_SECTION</h2>
            <p>$SECOND_SECTION_SENTENCE</p>
            <img src="images/plate.png" alt="Plansch"/>
          </body>
        </html>
    """.trimIndent()

    private fun thirdChapter(): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <html xmlns="http://www.w3.org/1999/xhtml">
          <head><title>$THIRD_CHAPTER</title></head>
          <body>
            <img src="images/facsimile.png" alt="Faksimil"/>
          </body>
        </html>
    """.trimIndent()

    private fun fb2Document(): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0">
          <description>
            <title-info>
              <book-title>$TITLE</book-title>
              <author><first-name>Anna</first-name><last-name>Lind</last-name></author>
              <lang>$LANGUAGE</lang>
            </title-info>
            <document-info><id>$IDENTIFIER</id></document-info>
          </description>
          <body>
            <section>
              <title><p>$FIRST_CHAPTER</p></title>
              <p>$FB2_SENTENCE</p>
              <section>
                <title><p>$SECOND_SECTION</p></title>
                <p>$SECOND_SECTION_SENTENCE</p>
              </section>
            </section>
          </body>
        </FictionBook>
    """.trimIndent()

    /**
     * The instant every entry claims.
     *
     * A fixed time keeps a regeneration on the same machine identical, which is what makes a diff of the
     * committed fixture worth reading.
     */
    private const val FIXED_TIME_MILLIS: Long = 1_577_836_800_000L

    private val CONTAINER = """
        <?xml version="1.0" encoding="UTF-8"?>
        <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
          <rootfiles>
            <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
          </rootfiles>
        </container>
    """.trimIndent()

    /** Runs the generator: `main [target-directory]`, defaulting to the committed fixture directory. */
    @JvmStatic
    fun main(args: Array<String>) {
        val target = Path.of(args.firstOrNull() ?: DEFAULT_TARGET_DIRECTORY)
        writeAll(target).forEach { println("wrote $it (${Files.size(it)} bytes)") }
    }
}
