package infoscry.image

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ImageProbeVectorTest {

    @Test
    fun `emf wmf pict and svg are vector drawings`() {
        val emf = ByteArray(100).also { it[0] = 1; it[40] = 0x20; it[41] = 0x45; it[42] = 0x4D; it[43] = 0x46 }
        val placeableWmf = byteArrayOf(0xD7.toByte(), 0xCD.toByte(), 0xC6.toByte(), 0x9A.toByte(), 0, 0, 0, 0)
        val standardWmf = byteArrayOf(1, 0, 9, 0, 0, 3, 0, 0)
        val pict = ByteArray(40).also { it[10] = 0x00; it[11] = 0x11; it[12] = 0x02; it[13] = 0xFF.toByte() }
        assertTrue(ImageProbe.isVector(emf))
        assertTrue(ImageProbe.isVector(placeableWmf))
        assertTrue(ImageProbe.isVector(standardWmf))
        assertTrue(ImageProbe.isVector(pict))
        assertTrue(ImageProbe.isVector("<svg xmlns=\"http://www.w3.org/2000/svg\"/>".toByteArray()))
        assertTrue(ImageProbe.isVector("\uFEFF<?xml version=\"1.0\"?>\n<svg/>".toByteArray()))
    }

    @Test
    fun `a raster picture or arbitrary bytes are not vector drawings`() {
        assertFalse(ImageProbe.isVector(FinderFixtures.png(300, 200)))
        assertFalse(ImageProbe.isVector(FinderFixtures.jpeg(300, 200)))
        assertFalse(ImageProbe.isVector(ByteArray(64) { 7 }))
        assertFalse(ImageProbe.isVector(ByteArray(0)))
        assertFalse(ImageProbe.isVector("<html><body>not an svg</body></html>".toByteArray()))
    }
}
