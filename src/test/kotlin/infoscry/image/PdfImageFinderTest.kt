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
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import infoscry.storage.CaptionSource
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

    @Test
    fun `one image drawn twenty times is decoded once and shares its bytes`() {
        val file = pdf { document, page ->
            val image = LosslessFactory.createFromImage(document, FinderFixtures.picture(300, 300))
            PDPageContentStream(document, page).use { stream ->
                repeat(20) { stream.drawImage(image, 10f + it * 5f, 100f + it * 20f, 100f, 100f) }
            }
        }

        val images = imagesOf(file, page()).images

        assertEquals(20, images.size)
        assertTrue(images.all { it.candidate.bytes === images[0].candidate.bytes })
        assertEquals(20, images.map { it.candidate.box }.toSet().size)
    }

    @Test
    fun `images past the byte budget stop collection with IMAGE_TOO_LARGE once`() {
        val file = pdf { document, page ->
            PDPageContentStream(document, page).use { stream ->
                repeat(4) {
                    val image = LosslessFactory.createFromImage(document, FinderFixtures.picture(300, 300, it))
                    stream.drawImage(image, 10f, 100f + it * 100f, 90f, 90f)
                }
            }
        }

        // Each raster holds 300 * 300 * 4 = 360 000 bytes; the budget allows two of them.
        val result = PdfImageFinder(maxParentBytes = 800_000).open(file).use { it.imagesOf(page()) }

        assertEquals(2, result.images.size)
        assertEquals(listOf(ImageRejectedException.IMAGE_TOO_LARGE), result.failures)
    }

    @Test
    fun `a failing xobject drawn three times records one failure and later images still return`() {
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
                repeat(3) { n -> it.drawImage(broken, 50f, 100f + n * 100f, 100f, 80f) }
                it.drawImage(good, 300f, 100f, 100f, 80f)
            }
        }

        val result = imagesOf(file, page())

        assertEquals(listOf(ImageRejectedException.IMAGE_DECODE_FAILED), result.failures)
        assertEquals(1, result.images.size)
    }

    @Test
    fun `a stencil mask is counted as a mask without decoding its pixels`() {
        val file = pdf { document, page ->
            val bits = BufferedImage(600, 400, BufferedImage.TYPE_BYTE_BINARY)
            val stencil = LosslessFactory.createFromImage(document, bits)
            stencil.cosObject.setBoolean(COSName.IMAGE_MASK, true)
            assertTrue(stencil.isStencil)
            PDPageContentStream(document, page).use { it.drawImage(stencil, 100f, 100f, 300f, 200f) }
        }

        val found = imagesOf(file, page()).images.single()

        assertTrue(found.candidate.isMask)
        assertEquals(SkipReason.MASK, ImageFilter.skipReason(found.candidate, null))
        assertEquals(600, found.candidate.widthPx)
        // A decoded stencil would be a 600 by 400 raster; the placeholder is one pixel.
        assertEquals(1, assertIs<ImageBytes.Raster>(found.candidate.bytes).image.width)
    }

    private val helvetica = PDType1Font(Standard14Fonts.FontName.HELVETICA)

    private fun PDPageContentStream.line(text: String, x: Float, y: Float, size: Float = 12f) {
        beginText()
        setFont(helvetica, size)
        newLineAtOffset(x, y)
        showText(text)
        endText()
    }

    @Test
    fun `the text line under an image is its page-text caption and the nearby text is its context`() {
        val file = pdf { document, page ->
            val image = jpegImage(document)
            PDPageContentStream(document, page).use {
                it.drawImage(image, 100f, 400f, 200f, 150f)
                it.line("Figure 2. The harbour in 1923", 100f, 384f)
                it.line("The steamship company ran three ferries from the harbour.", 100f, 580f)
                it.line("A paragraph far away about something else entirely.", 100f, 100f)
            }
        }

        val found = imagesOf(file, page()).images.single()

        assertEquals("Figure 2. The harbour in 1923", found.caption)
        assertEquals(CaptionSource.PAGE_TEXT, found.captionSource)
        val context = checkNotNull(found.context)
        assertTrue(context.startsWith("The steamship company ran three ferries"), context)
        assertTrue("something else entirely" in context)
        assertFalse("harbour in 1923" in context, "the caption is not repeated in the context")
        assertTrue(context.length <= PageTextContext.MAX_CONTEXT_CHARS)
    }

    @Test
    fun `an image on a page with no text has no caption and no context`() {
        val file = pdf { document, page ->
            PDPageContentStream(document, page).use { it.drawImage(jpegImage(document), 100f, 400f, 200f, 150f) }
        }

        val found = imagesOf(file, page()).images.single()

        assertEquals(null, found.caption)
        assertEquals(null, found.captionSource)
        assertEquals(null, found.context)
    }

    @Test
    fun `a caption under a picture is found on a page displayed rotated by ninety degrees`() {
        // Displayed 792 wide and 612 tall. The picture covers display x 100..300 and y 100..250; the caption sits
        // below it, upright in the display, which the content stream draws with a rotated text matrix.
        val file = pdf { document, page ->
            page.rotation = 90
            val image = jpegImage(document)
            PDPageContentStream(document, page).use {
                it.drawImage(image, 100f, 100f, 150f, 200f)
                it.beginText()
                it.setFont(helvetica, 12f)
                it.setTextMatrix(Matrix.getRotateInstance(Math.PI / 2, 264f, 100f))
                it.showText("Fig. 7 Rotated page caption")
                it.endText()
            }
        }

        val found = imagesOf(file, page()).images.single()

        val box = checkNotNull(found.candidate.box)
        assertEquals(100.0 / 792, box.left, 0.002)
        assertEquals(250.0 / 612, box.bottom, 0.002)
        assertEquals("Fig. 7 Rotated page caption", found.caption)
    }

    @Test
    fun `a caption under a picture is found on a page displayed rotated by one hundred and eighty degrees`() {
        // Displayed 612 wide and 792 tall: picture at display x 100..300, y 100..250, caption below it.
        val file = pdf { document, page ->
            page.rotation = 180
            PDPageContentStream(document, page).use {
                it.drawImage(jpegImage(document), 312f, 100f, 200f, 150f)
                it.beginText()
                it.setFont(helvetica, 12f)
                it.setTextMatrix(Matrix.getRotateInstance(Math.PI, 512f, 264f))
                it.showText("Fig. 8 Upside down page caption")
                it.endText()
            }
        }

        val found = imagesOf(file, page()).images.single()

        val box = checkNotNull(found.candidate.box)
        assertEquals(100.0 / 612, box.left, 0.002)
        assertEquals(250.0 / 792, box.bottom, 0.002)
        assertEquals("Fig. 8 Upside down page caption", found.caption)
    }

    @Test
    fun `a caption under a picture is found on a page displayed rotated by two hundred and seventy degrees`() {
        // Displayed 792 wide and 612 tall: picture at display x 100..300, y 100..250, caption below it.
        val file = pdf { document, page ->
            page.rotation = 270
            PDPageContentStream(document, page).use {
                it.drawImage(jpegImage(document), 362f, 492f, 150f, 200f)
                it.beginText()
                it.setFont(helvetica, 12f)
                it.setTextMatrix(Matrix.getRotateInstance(3 * Math.PI / 2, 348f, 692f))
                it.showText("Fig. 9 Quarter turn page caption")
                it.endText()
            }
        }

        val found = imagesOf(file, page()).images.single()

        val box = checkNotNull(found.candidate.box)
        assertEquals(100.0 / 792, box.left, 0.002)
        assertEquals(250.0 / 612, box.bottom, 0.002)
        assertEquals("Fig. 9 Quarter turn page caption", found.caption)
    }

    @Test
    fun `one caption line belongs to one image when two images sit around it`() {
        val file = pdf { document, page ->
            val upper = jpegImage(document)
            val lower = jpegImage(document, seed = 5)
            PDPageContentStream(document, page).use {
                it.drawImage(upper, 100f, 500f, 200f, 150f)
                it.line("Figure 1. Between the two pictures", 100f, 484f)
                it.drawImage(lower, 100f, 320f, 200f, 150f)
            }
        }

        val images = imagesOf(file, page()).images

        assertEquals(2, images.size)
        assertEquals(1, images.count { it.caption == "Figure 1. Between the two pictures" })
        assertEquals(1, images.count { it.caption == null })
    }

    @Test
    fun `a caption is found on a page with a crop box that does not start at the origin`() {
        val file = pdf { document, page ->
            page.cropBox = PDRectangle(50f, 100f, 400f, 500f)
            val image = jpegImage(document)
            PDPageContentStream(document, page).use {
                it.drawImage(image, 100f, 400f, 200f, 150f)
                it.line("Photo 5. Inside a cropped page", 100f, 384f)
            }
        }

        assertEquals("Photo 5. Inside a cropped page", imagesOf(file, page()).images.single().caption)
    }
}
