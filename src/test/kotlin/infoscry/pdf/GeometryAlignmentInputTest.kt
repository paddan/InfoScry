package infoscry.pdf

import infoscry.domain.DocumentId
import infoscry.ocr.OcrWordBox
import infoscry.ocr.PageImage
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GeometryAlignmentInputTest {

    private fun pageImage(width: Int? = IMAGE_WIDTH, height: Int? = IMAGE_HEIGHT) = PageImage(
        documentId = DocumentId("document-1"),
        unitId = "unit-1",
        ordinal = 0,
        imageRoot = Path.of("images"),
        imageReference = "page-0.png",
        artifactRoot = Path.of("artifacts"),
        sha256 = IMAGE_SHA,
        width = width,
        height = height,
        renderDpi = 300,
        rotationDegrees = 0,
    )

    private fun input(
        image: PageImage = pageImage(),
        positioningVersion: String = "local-anchor-1",
    ) = GeometryAlignmentInput(
        canonicalText = CANONICAL_TEXT,
        image = image,
        geometry = PAGE_GEOMETRY,
        transform = PAGE_TRANSFORM,
        anchors = listOf(OcrWordBox("Åsa", 4, 5, 20, 10)),
        positioningVersion = positioningVersion,
    )

    @Test
    fun `alignment needs a measured raster`() {
        assertFailsWith<IllegalArgumentException> { input(image = pageImage(width = null, height = null)) }
        assertEquals("local-anchor-1", input().positioningVersion)
    }

    @Test
    fun `alignment version is a safe token`() {
        assertFailsWith<IllegalArgumentException> { input(positioningVersion = "") }
        assertFailsWith<IllegalArgumentException> { input(positioningVersion = "local anchor") }
        assertFailsWith<IllegalArgumentException> { input(positioningVersion = "v1/../../x") }
    }
}
