package infoscry.image

import infoscry.domain.SourceLocation
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class StandaloneImageFinderTest {

    private lateinit var directory: Path

    @BeforeTest
    fun createDirectory() {
        directory = createTempDirectory("infoscry-standalone-finder")
    }

    @AfterTest
    fun deleteDirectory() {
        directory.toFile().deleteRecursively()
    }

    private val finder = StandaloneImageFinder()

    private fun imagesOf(file: Path, parent: ParentUnit): ParentImages =
        finder.open(file).use { it.imagesOf(parent) }

    @Test
    fun `a png yields one standalone image under its image unit`() {
        val bytes = FinderFixtures.png(320, 240)
        val file = Files.write(directory.resolve("scene.png"), bytes)
        val parent = FinderFixtures.parent(SourceLocation.Image("scene.png"))

        val result = imagesOf(file, parent)

        val found = result.images.single()
        assertEquals(parent, found.parent)
        assertEquals(ImageSourceKind.STANDALONE, found.kind)
        assertEquals(320, found.candidate.widthPx)
        assertEquals(240, found.candidate.heightPx)
        assertEquals(null, found.candidate.box)
        assertEquals(null, found.caption)
        val encoded = assertIs<ImageBytes.Encoded>(found.candidate.bytes)
        assertEquals("image/png", encoded.mediaType)
        assertTrue(bytes.contentEquals(encoded.bytes))
        assertEquals(false, result.scanned)
    }

    @Test
    fun `a tiff is carried as pixels`() {
        val file = directory.resolve("scan.tif")
        Files.write(file, FinderFixtures.encode(FinderFixtures.picture(200, 100), "tiff"))

        val found = imagesOf(file, FinderFixtures.parent(SourceLocation.Image("scan.tif"))).images.single()

        assertNotNull(assertIs<ImageBytes.Raster>(found.candidate.bytes).image)
        assertEquals(200, found.candidate.widthPx)
    }

    @Test
    fun `a file that is not an image fails and a foreign locator has none`() {
        val file = Files.write(directory.resolve("bad.png"), ByteArray(40) { 1 })

        assertEquals(
            listOf(ImageRejectedException.IMAGE_DECODE_FAILED),
            imagesOf(file, FinderFixtures.parent(SourceLocation.Image("bad.png"))).failures,
        )
        assertEquals(ParentImages.NONE, imagesOf(file, FinderFixtures.parent(SourceLocation.PdfPage(1))))
    }
}
