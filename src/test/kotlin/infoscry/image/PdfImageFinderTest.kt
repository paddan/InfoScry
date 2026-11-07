package infoscry.image

import infoscry.domain.SourceLocation
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDFormContentStream
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.PDResources
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceGray
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.apache.pdfbox.util.Matrix

class PdfImageFinderTest {

    private lateinit var directory: Path

    @BeforeTest
    fun createDirectory() {
        directory = createTempDirectory("infoscry-pdf-finder")
    }

    @AfterTest
    fun deleteDirectory() {
        directory.toFile().deleteRecursively()
    }

    private val finder = PdfImageFinder()

    private fun page(number: Int = 1) = FinderFixtures.parent(SourceLocation.PdfPage(number), number - 1)

    private fun imagesOf(pdf: Path, parent: ParentUnit): ParentImages =
        finder.open(pdf).use { it.imagesOf(parent) }

    private fun pdf(draw: (PDDocument, PDPage) -> Unit): Path {
        val file = directory.resolve("doc-${System.nanoTime()}.pdf")
        PDDocument().use { document ->
            val page = PDPage(PDRectangle.LETTER)
            document.addPage(page)
            draw(document, page)
            document.save(file.toFile())
        }
        return file
    }

    private fun jpegImage(document: PDDocument, width: Int = 400, height: Int = 300, seed: Int = 0) =
        JPEGFactory.createFromImage(document, FinderFixtures.picture(width, height, seed))

    @Test
    fun `a jpeg drawn on a page keeps its original bytes and its box`() {
        var original = ByteArray(0)
        val file = pdf { document, page ->
            val image = jpegImage(document)
            original = image.cosObject.createRawInputStream().use { it.readAllBytes() }
            PDPageContentStream(document, page).use { it.drawImage(image, 100f, 400f, 200f, 150f) }
        }

        val result = imagesOf(file, page())

        val found = result.images.single()
        assertEquals(ImageSourceKind.EMBEDDED, found.kind)
        assertEquals(false, result.scanned)
        assertEquals(emptyList(), result.failures)
        assertEquals(400, found.candidate.widthPx)
        assertEquals(300, found.candidate.heightPx)
        assertFalse(found.candidate.isMask)
        val bytes = assertIs<ImageBytes.Encoded>(found.candidate.bytes)
        assertEquals("image/jpeg", bytes.mediaType)
        assertTrue(original.contentEquals(bytes.bytes))
        val box = checkNotNull(found.candidate.box)
        assertEquals(100.0 / 612, box.left, 0.001)
        assertEquals(300.0 / 612, box.right, 0.001)
        assertEquals(1 - 550.0 / 792, box.top, 0.001)
        assertEquals(1 - 400.0 / 792, box.bottom, 0.001)
        assertEquals(null, found.caption)
    }

    @Test
    fun `the same image drawn twice on a page is found twice in drawing order`() {
        val file = pdf { document, page ->
            val image = jpegImage(document)
            PDPageContentStream(document, page).use {
                it.drawImage(image, 50f, 600f, 100f, 75f)
                it.drawImage(image, 300f, 100f, 100f, 75f)
            }
        }

        val images = imagesOf(file, page()).images

        assertEquals(2, images.size)
        assertTrue(checkNotNull(images[0].candidate.box).top < checkNotNull(images[1].candidate.box).top)
    }

    @Test
    fun `an image inside a form xobject is found`() {
        val file = pdf { document, page ->
            val image = jpegImage(document)
            val form = PDFormXObject(document)
            form.bBox = PDRectangle(0f, 0f, 200f, 150f)
            form.resources = PDResources()
            PDFormContentStream(form).use { it.drawImage(image, 0f, 0f, 200f, 150f) }
            PDPageContentStream(document, page).use { stream ->
                stream.saveGraphicsState()
                stream.transform(Matrix.getTranslateInstance(100f, 100f))
                stream.drawForm(form)
                stream.restoreGraphicsState()
            }
        }

        val found = imagesOf(file, page()).images.single()

        val box = checkNotNull(found.candidate.box)
        assertEquals(100.0 / 612, box.left, 0.001)
        assertEquals(300.0 / 612, box.right, 0.001)
        assertEquals(1 - 250.0 / 792, box.top, 0.001)
    }

    @Test
    fun `a page whose only image covers ninety five percent is scanned and has no image`() {
        val file = pdf { document, page ->
            val image = jpegImage(document, 600, 800)
            PDPageContentStream(document, page).use { it.drawImage(image, 0f, 0f, 612f, 792f) }
        }

        val result = imagesOf(file, page())

        assertTrue(result.scanned)
        assertEquals(emptyList(), result.images)
        assertEquals(emptyList(), result.failures)
    }

    @Test
    fun `a scan with a small logo returns the logo and is scanned`() {
        val file = pdf { document, page ->
            val scan = jpegImage(document, 600, 800)
            val logo = jpegImage(document, 200, 200, seed = 3)
            PDPageContentStream(document, page).use {
                it.drawImage(scan, 0f, 0f, 612f, 792f)
                it.drawImage(logo, 40f, 700f, 80f, 80f)
            }
        }

        val result = imagesOf(file, page())

        assertTrue(result.scanned)
        assertEquals(200, result.images.single().candidate.widthPx)
    }

    @Test
    fun `an image with a soft mask yields only the base image`() {
        val file = pdf { document, page ->
            val withAlpha = BufferedImage(300, 300, BufferedImage.TYPE_INT_ARGB)
            for (y in 0 until 300) {
                for (x in 0 until 300) withAlpha.setRGB(x, y, ((x % 256) shl 24) or (0x336699 + y))
            }
            val image = LosslessFactory.createFromImage(document, withAlpha)
            assertTrue(image.softMask != null)
            PDPageContentStream(document, page).use { it.drawImage(image, 100f, 100f, 200f, 200f) }
        }

        val result = imagesOf(file, page())

        val found = result.images.single()
        assertFalse(found.candidate.isMask)
        assertIs<ImageBytes.Raster>(found.candidate.bytes)
        assertEquals(300, found.candidate.widthPx)
    }

    @Test
    fun `an image that cannot be decoded fails alone and the other images still return`() {
        val file = pdf { document, page ->
            val broken = PDImageXObject(
                document,
                ByteArrayInputStream(ByteArray(64) { (it * 31).toByte() }),
                COSName.JBIG2_DECODE,
                400,
                300,
                1,
                PDDeviceGray.INSTANCE,
            )
            val good = jpegImage(document)
            PDPageContentStream(document, page).use {
                it.drawImage(broken, 50f, 500f, 200f, 150f)
                it.drawImage(good, 300f, 100f, 200f, 150f)
            }
        }

        val result = imagesOf(file, page())

        assertEquals(listOf(ImageRejectedException.IMAGE_DECODE_FAILED), result.failures)
        assertEquals(1, result.images.size)
        assertIs<ImageBytes.Encoded>(result.images.single().candidate.bytes)
    }

    @Test
    fun `a locator the finder does not own or a page the document lacks has no images`() {
        val file = pdf { document, page ->
            PDPageContentStream(document, page).use { it.drawImage(jpegImage(document), 0f, 0f, 100f, 100f) }
        }

        assertEquals(ParentImages.NONE, imagesOf(file, page(2)))
        assertEquals(ParentImages.NONE, imagesOf(file, FinderFixtures.parent(SourceLocation.Slide(1))))
    }

    @Test
    fun `a rotated page reports the box as displayed`() {
        val file = pdf { document, page ->
            page.rotation = 90
            PDPageContentStream(document, page).use { it.drawImage(jpegImage(document), 0f, 692f, 100f, 100f) }
        }

        val box = checkNotNull(imagesOf(file, page()).images.single().candidate.box)

        // The top-left corner of the unrotated page is displayed at the top right.
        assertTrue(box.right > 0.99 && box.top < 0.01, "was $box")
    }

    @Test
    fun `an image drawn outside the page has no visible box and is not returned`() {
        val file = pdf { document, page ->
            PDPageContentStream(document, page).use { it.drawImage(jpegImage(document), 1000f, 1000f, 100f, 100f) }
        }

        assertEquals(emptyList(), imagesOf(file, page()).images)
    }
}
