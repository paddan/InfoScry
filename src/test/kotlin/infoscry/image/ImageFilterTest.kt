package infoscry.image

import infoscry.domain.NormalizedBox
import java.awt.Color
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImageFilterTest {

    private val encoded = ImageBytes.Encoded(byteArrayOf(1, 2, 3), "image/png")

    @Test
    fun `an image whose shortest side is under 96 pixels is too small`() {
        val candidate = ImageCandidate(encoded, widthPx = 95, heightPx = 400, box = null, isMask = false)

        assertEquals(SkipReason.TOO_SMALL, ImageFilter.skipReason(candidate, decoded = null))
    }

    @Test
    fun `an image at exactly the minimum side is kept`() {
        val candidate = ImageCandidate(encoded, widthPx = 96, heightPx = 96, box = null, isMask = false)

        assertNull(ImageFilter.skipReason(candidate, decoded = null))
    }

    @Test
    fun `a page region covering under one percent of the page is too small`() {
        val box = NormalizedBox(0.0, 0.0, 0.09, 0.1)
        val candidate = ImageCandidate(encoded, widthPx = 400, heightPx = 400, box = box, isMask = false)

        assertEquals(SkipReason.TOO_SMALL, ImageFilter.skipReason(candidate, decoded = null))
    }

    @Test
    fun `a soft or stencil mask is skipped`() {
        val candidate = ImageCandidate(encoded, widthPx = 400, heightPx = 400, box = null, isMask = true)

        assertEquals(SkipReason.MASK, ImageFilter.skipReason(candidate, decoded = null))
    }

    @Test
    fun `an image of one uniform colour is skipped`() {
        val white = solid(200, 200, Color.WHITE)
        val candidate = ImageCandidate(ImageBytes.Raster(white), widthPx = 200, heightPx = 200, box = null, isMask = false)

        assertEquals(SkipReason.UNIFORM, ImageFilter.skipReason(candidate, decoded = white))
    }

    @Test
    fun `an image with real variation is kept`() {
        val striped = BufferedImage(200, 200, BufferedImage.TYPE_INT_RGB)
        for (x in 0 until 200) for (y in 0 until 200) striped.setRGB(x, y, if (x % 2 == 0) 0x000000 else 0xFFFFFF)
        val candidate = ImageCandidate(ImageBytes.Raster(striped), widthPx = 200, heightPx = 200, box = null, isMask = false)

        assertNull(ImageFilter.skipReason(candidate, decoded = striped))
    }

    @Test
    fun `a region covering at least 90 percent of the page is scanned`() {
        assertTrue(ImageFilter.isScanned(NormalizedBox(0.02, 0.02, 0.98, 0.98)))
    }

    @Test
    fun `a region covering less than 90 percent of the page is not scanned`() {
        assertFalse(ImageFilter.isScanned(NormalizedBox(0.05, 0.05, 0.97, 0.97)))
    }

    @Test
    fun `an image without a page box is not scanned`() {
        assertFalse(ImageFilter.isScanned(null))
    }

    private fun solid(width: Int, height: Int, color: Color): BufferedImage {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        graphics.color = color
        graphics.fillRect(0, 0, width, height)
        graphics.dispose()
        return image
    }
}
