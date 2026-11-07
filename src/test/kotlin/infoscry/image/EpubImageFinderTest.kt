package infoscry.image

import infoscry.domain.SourceLocation
import infoscry.storage.CaptionSource
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class EpubImageFinderTest {

    private lateinit var directory: Path

    @BeforeTest
    fun createDirectory() {
        directory = createTempDirectory("infoscry-epub-finder")
    }

    @AfterTest
    fun deleteDirectory() {
        directory.toFile().deleteRecursively()
    }

    private val finder = EpubImageFinder()

    private fun section(spine: Int, path: List<String> = emptyList()) =
        FinderFixtures.parent(SourceLocation.EbookSection("Chapter", path, spine), spine)

    private fun imagesOf(file: Path, parent: ParentUnit): ParentImages =
        finder.open(file).use { it.imagesOf(parent) }

    private fun epub(vararg chapters: String, extra: Map<String, ByteArray> = emptyMap()): Path {
        val file = directory.resolve("book.epub")
        ZipOutputStream(Files.newOutputStream(file)).use { zip ->
            fun put(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
            put("mimetype", "application/epub+zip".toByteArray())
            put(
                "META-INF/container.xml",
                """<?xml version="1.0"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
                </container>""".toByteArray(),
            )
            val manifest = chapters.indices.joinToString("\n") {
                """<item id="c$it" href="chapter$it.xhtml" media-type="application/xhtml+xml"/>"""
            }
            val spine = chapters.indices.joinToString("\n") { """<itemref idref="c$it"/>""" }
            put(
                "OEBPS/content.opf",
                """<?xml version="1.0"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>T</dc:title><dc:identifier id="id">x</dc:identifier></metadata>
                  <manifest>$manifest</manifest><spine>$spine</spine>
                </package>""".toByteArray(),
            )
            chapters.forEachIndexed { index, body ->
                put(
                    "OEBPS/chapter$index.xhtml",
                    """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>c</title></head><body>$body</body></html>"""
                        .toByteArray(StandardCharsets.UTF_8),
                )
            }
            put("OEBPS/images/harbour.png", FinderFixtures.png(300, 200, 1))
            put("OEBPS/images/town.jpg", FinderFixtures.jpeg(300, 200, 2))
            extra.forEach { (name, bytes) -> put(name, bytes) }
        }
        return file
    }

    @Test
    fun `a figcaption is the caption when the alt is empty`() {
        val file = epub(
            """<p>Text</p><figure><img src="images/harbour.png" alt=""/><figcaption>Harbour 1920</figcaption></figure>""",
        )

        val found = imagesOf(file, section(0)).images.single()

        assertEquals("Harbour 1920", found.caption)
        assertEquals(CaptionSource.FIGCAPTION, found.captionSource)
        assertEquals(ImageSourceKind.EMBEDDED, found.kind)
        assertEquals(null, found.candidate.box)
        assertEquals("image/png", assertIs<ImageBytes.Encoded>(found.candidate.bytes).mediaType)
        assertEquals(300, found.candidate.widthPx)
    }

    @Test
    fun `alt wins over the figcaption when present`() {
        val file = epub(
            """<p>Text</p><figure><img src="images/town.jpg" alt="Town square"/><figcaption>Harbour 1920</figcaption></figure>""",
        )

        val found = imagesOf(file, section(0)).images.single()

        assertEquals("Town square", found.caption)
        assertEquals(CaptionSource.ALT_TEXT, found.captionSource)
        assertEquals("image/jpeg", assertIs<ImageBytes.Encoded>(found.candidate.bytes).mediaType)
    }

    @Test
    fun `an image without alt or figcaption has no caption`() {
        val file = epub("""<p>Text</p><img src="images/harbour.png"/>""")

        val found = imagesOf(file, section(0)).images.single()

        assertEquals(null, found.caption)
        assertEquals(null, found.captionSource)
    }

    @Test
    fun `images belong to the chapter and heading section they appear in`() {
        val file = epub(
            """<h1>One</h1><p>a</p><img src="images/harbour.png" alt="x"/><h1>Two</h1><p>b</p><img src="images/town.jpg" alt="y"/>""",
            """<p>other chapter</p><img src="images/town.jpg" alt="z"/>""",
        )

        assertEquals(listOf("x"), imagesOf(file, section(0, listOf("One"))).images.map { it.caption })
        assertEquals(listOf("y"), imagesOf(file, section(0, listOf("Two"))).images.map { it.caption })
        assertEquals(listOf("z"), imagesOf(file, section(1)).images.map { it.caption })
        assertEquals(ParentImages.NONE, imagesOf(file, section(5)))
    }

    @Test
    fun `a picture only chapter holds all its images`() {
        val file = epub("""<img src="images/harbour.png" alt="a"/><img src="images/town.jpg" alt="b"/>""")

        assertEquals(listOf("a", "b"), imagesOf(file, section(0)).images.map { it.caption })
    }

    @Test
    fun `a src that leaves the archive or is missing is skipped and the others still return`() {
        val file = epub(
            """<p>Text</p><img src="../../../etc/passwd" alt="no"/><img src="images/missing.png" alt="gone"/><img src="images/harbour.png" alt="ok"/>""",
        )

        val result = imagesOf(file, section(0))

        assertEquals(listOf("ok"), result.images.map { it.caption })
    }

    @Test
    fun `an image that cannot be decoded fails alone`() {
        val file = epub(
            """<p>Text</p><img src="images/bad.png" alt="bad"/><img src="images/harbour.png" alt="ok"/>""",
            extra = mapOf("OEBPS/images/bad.png" to ByteArray(50) { 3 }),
        )

        val result = imagesOf(file, section(0))

        assertEquals(listOf(ImageRejectedException.IMAGE_DECODE_FAILED), result.failures)
        assertEquals(listOf("ok"), result.images.map { it.caption })
    }

    @Test
    fun `a locator the finder does not own has no images`() {
        val file = epub("""<p>Text</p><img src="images/harbour.png" alt="a"/>""")

        assertEquals(ParentImages.NONE, imagesOf(file, FinderFixtures.parent(SourceLocation.Slide(1))))
    }

    @Test
    fun `an svg is counted as a vector skip and is neither an image nor a failure`() {
        val file = epub(
            """<p>Text</p><img src="images/chart.svg" alt="chart"/><img src="images/harbour.png" alt="ok"/>""",
            extra = mapOf("OEBPS/images/chart.svg" to """<?xml version="1.0"?><svg xmlns="http://www.w3.org/2000/svg"/>""".toByteArray()),
        )

        val result = imagesOf(file, section(0))

        assertEquals(listOf("ok"), result.images.map { it.caption })
        assertEquals(1, result.vectorSkipped)
        assertEquals(emptyList(), result.failures)
    }

    @Test
    fun `a chapter with many sections is read once, so the archive budget is not spent per section`() {
        val sections = 12
        val body = (1..sections).joinToString("") { n ->
            """<h1>Part $n</h1><p>${"words ".repeat(400)}</p><img src="images/harbour.png" alt="a$n"/>"""
        }
        val file = epub(body)
        val chapter = java.util.zip.ZipFile(file.toFile()).use { it.getEntry("OEBPS/chapter0.xhtml").size }
        val image = java.util.zip.ZipFile(file.toFile()).use { it.getEntry("OEBPS/images/harbour.png").size }
        val meta = java.util.zip.ZipFile(file.toFile()).use {
            it.getEntry("OEBPS/content.opf").size + it.getEntry("META-INF/container.xml").size
        }
        // One chapter read, every image read, and room for a single extra read of the chapter - not twelve.
        val budget = meta + 2 * chapter + sections * image
        val bounded = EpubImageFinder(infoscry.extract.ArchiveLimits(maxTotalBytes = budget))

        val results = bounded.open(file).use { source ->
            (1..sections).map { n -> source.imagesOf(section(0, listOf("Part $n"))) }
        }

        results.forEachIndexed { index, result ->
            assertEquals(emptyList(), result.failures, "section ${index + 1}")
            assertEquals(listOf("a${index + 1}"), result.images.map { it.caption })
        }
    }
}
