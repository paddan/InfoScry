package infoscry.image

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import infoscry.storage.ContentAddressedFile
import javax.imageio.ImageIO
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ImageArtifactsTest {

    private lateinit var root: Path

    @BeforeTest
    fun createRoot() {
        root = createTempDirectory("infoscry-image-artifacts")
    }

    @AfterTest
    fun deleteRoot() {
        root.toFile().deleteRecursively()
    }

    @Test
    fun `writing the same encoded jpeg twice returns the same result and leaves one file each`() {
        val artifacts = ImageArtifacts()
        val jpeg = jpegBytes(200, 150)
        val bytes = ImageBytes.Encoded(jpeg, "image/jpeg")

        val first = artifacts.write(root, bytes)
        val second = artifacts.write(root, bytes)

        assertEquals(first, second)
        assertEquals(listOf(first.relativePath), filesIn(root.resolve("images")))
        assertEquals(listOf(first.thumbnailRelativePath), filesIn(root.resolve("images/thumbs")))
        assertTrue(first.relativePath.endsWith(".jpg"), first.relativePath)
        assertEquals("image/jpeg", first.mediaType)
        assertEquals(200, first.widthPx)
        assertEquals(150, first.heightPx)
        assertEquals(emptyList(), temporaryFiles())
    }

    @Test
    fun `an encoded jpeg within the bounds is stored as its original bytes`() {
        val jpeg = jpegBytes(200, 150)
        val written = ImageArtifacts().write(root, ImageBytes.Encoded(jpeg, "image/jpeg"))

        assertTrue(root.resolve(written.relativePath).readBytes().contentEquals(jpeg))
        assertEquals(ContentAddressedFile.sha256Of(jpeg), written.sha256)
        assertEquals("images/${written.sha256}.jpg", written.relativePath)
        assertEquals("images/thumbs/${written.sha256}.jpg", written.thumbnailRelativePath)
    }

    @Test
    fun `a raster is written as png under its hash`() {
        val written = ImageArtifacts().write(root, ImageBytes.Raster(solid(120, 120, Color.RED)))

        val file = root.resolve(written.relativePath)
        assertTrue(file.exists())
        assertEquals("image/png", written.mediaType)
        assertTrue(written.relativePath.endsWith(".png"), written.relativePath)
        assertEquals(PNG_SIGNATURE.toList(), file.readBytes().take(PNG_SIGNATURE.size).toList())
        assertEquals(ContentAddressedFile.sha256Of(file.readBytes()), written.sha256)
        assertEquals(120, written.widthPx)
        assertEquals(120, written.heightPx)
    }

    @Test
    fun `a large raster gets a thumbnail with its longest side at 320 pixels`() {
        val written = ImageArtifacts().write(root, ImageBytes.Raster(solid(4000, 3000, Color.BLUE)))

        val thumbnail = root.resolve(written.thumbnailRelativePath)
        val bytes = thumbnail.readBytes()
        assertEquals(listOf(0xFF.toByte(), 0xD8.toByte()), bytes.take(2), "a thumbnail is a JPEG")
        val decoded = ImageIO.read(thumbnail.toFile())
        assertEquals(320, decoded.width)
        assertEquals(240, decoded.height)
    }

    @Test
    fun `an encoded image above the byte bound is refused as too large`() {
        val artifacts = ImageArtifacts(maxImageBytes = 10)

        val error = assertFailsWith<ImageRejectedException> {
            artifacts.write(root, ImageBytes.Encoded(jpegBytes(200, 150), "image/jpeg"))
        }

        assertEquals("IMAGE_TOO_LARGE", error.code)
        assertEquals(emptyList(), filesIn(root.resolve("images")))
    }

    @Test
    fun `bytes that are not an image are refused as a decode failure`() {
        val error = assertFailsWith<ImageRejectedException> {
            ImageArtifacts().write(root, ImageBytes.Encoded("not an image".toByteArray(), "image/png"))
        }

        assertEquals("IMAGE_DECODE_FAILED", error.code)
        assertEquals(emptyList(), filesIn(root.resolve("images")))
    }

    @Test
    fun `an encoded image whose raster is above the pixel bound is reduced, not refused`() {
        val artifacts = ImageArtifacts(maxRenderedPixels = 100_000)

        val written = artifacts.write(root, ImageBytes.Encoded(jpegBytes(400, 300), "image/jpeg"))

        assertEquals("image/png", written.mediaType)
        assertTrue(written.widthPx.toLong() * written.heightPx <= 100_000L, "${written.widthPx}x${written.heightPx}")
        assertTrue(root.resolve(written.relativePath).exists())
    }

    @Test
    fun `a raster above the pixel bound is reduced, not refused`() {
        val artifacts = ImageArtifacts(maxRenderedPixels = 100_000)

        val written = artifacts.write(root, ImageBytes.Raster(solid(400, 300, Color.GREEN)))

        assertTrue(written.widthPx.toLong() * written.heightPx <= 100_000L, "${written.widthPx}x${written.heightPx}")
        assertEquals("image/png", written.mediaType)
    }

    @Test
    fun `a stale temporary file from a killed run does not block the write`() {
        val artifacts = ImageArtifacts()
        val bytes = ImageBytes.Encoded(jpegBytes(200, 150), "image/jpeg")
        val first = artifacts.write(root, bytes)
        val stale = root.resolve("images/${first.sha256}.jpg.1234.tmp")
        Files.write(stale, byteArrayOf(1, 2, 3))

        val second = artifacts.write(root, bytes)

        assertEquals(first, second)
        assertTrue(root.resolve(second.relativePath).exists())
        assertEquals(listOf("images/${first.sha256}.jpg.1234.tmp"), temporaryFiles())
    }

    @Test
    fun `a declared media type that disagrees with the bytes follows the bytes`() {
        val jpeg = jpegBytes(200, 150)

        val written = ImageArtifacts().write(root, ImageBytes.Encoded(jpeg, "image/png"))

        assertTrue(written.relativePath.endsWith(".jpg"), written.relativePath)
        assertEquals("image/jpeg", written.mediaType)
        assertTrue(root.resolve(written.relativePath).readBytes().contentEquals(jpeg))
    }

    @Test
    fun `bytes in a format other than jpeg, png or gif are written as png`() {
        val output = ByteArrayOutputStream()
        ImageIO.write(solid(200, 150, Color.CYAN), "bmp", output)

        val written = ImageArtifacts().write(root, ImageBytes.Encoded(output.toByteArray(), "image/jpeg"))

        assertTrue(written.relativePath.endsWith(".png"), written.relativePath)
        assertEquals("image/png", written.mediaType)
        assertEquals(PNG_SIGNATURE.toList(), root.resolve(written.relativePath).readBytes().take(PNG_SIGNATURE.size))
    }

    @Test
    fun `thumbnailFor writes a jpeg thumbnail for an existing image file`() {
        val source = root.resolve("source.png")
        ImageIO.write(solid(640, 160, Color.ORANGE), "png", source.toFile())

        val relative = ImageArtifacts().thumbnailFor(root, source, "b".repeat(64))

        assertEquals("images/thumbs/${"b".repeat(64)}.jpg", relative)
        val thumbnail = ImageIO.read(root.resolve(relative).toFile())
        assertEquals(320, thumbnail.width)
        assertEquals(80, thumbnail.height)
    }

    private fun solid(width: Int, height: Int, color: Color): BufferedImage {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        graphics.color = color
        graphics.fillRect(0, 0, width, height)
        graphics.dispose()
        return image
    }

    private fun jpegBytes(width: Int, height: Int): ByteArray {
        val output = ByteArrayOutputStream()
        ImageIO.write(solid(width, height, Color.DARK_GRAY), "jpg", output)
        return output.toByteArray()
    }

    /** The regular files directly inside [directory], as paths relative to the artifact root. */
    private fun filesIn(directory: Path): List<String> {
        if (!Files.isDirectory(directory)) return emptyList()
        return Files.list(directory).use { entries ->
            entries.filter { Files.isRegularFile(it) }
                .map { root.relativize(it).toString().replace('\\', '/') }
                .toList()
        }
    }

    /** The `*.tmp` files under the artifact root, relative to it. */
    private fun temporaryFiles(): List<String> =
        Files.walk(root).use { paths ->
            paths.filter { it.fileName.toString().endsWith(".tmp") }
                .map { root.relativize(it).toString().replace('\\', '/') }
                .toList()
        }

    private companion object {
        val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())
    }
}
