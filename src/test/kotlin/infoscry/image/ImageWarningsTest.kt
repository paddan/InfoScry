package infoscry.image

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImageWarningsTest {

    @Test
    fun `the warning a person can act on outranks the ones that describe a limit`() {
        assertEquals(
            "NEEDS_SURYA_LAYOUT",
            ImageWarnings.mostImportant(listOf("IMAGE_LIMIT_REACHED", "NEEDS_SURYA_LAYOUT", "IMAGE_DECODE_FAILED")),
        )
        assertEquals("IMAGE_LIMIT_REACHED", ImageWarnings.mostImportant(listOf("IMAGE_DECODE_FAILED", "IMAGE_LIMIT_REACHED")))
        assertEquals("SOMETHING_NEW", ImageWarnings.mostImportant(listOf("SOMETHING_NEW")))
        assertNull(ImageWarnings.mostImportant(emptyList()))
    }

    @Test
    fun `the document detail's message for an image code is the image sentence`() {
        assertEquals(
            ImageWarnings.messageFor("IMAGE_LIMIT_REACHED"),
            infoscry.jobs.ImportJobHandler.messageFor("IMAGE_LIMIT_REACHED"),
        )
    }

    @Test
    fun `every code the stage reports has its own sentence and Surya names its remedy`() {
        val codes = listOf(
            "NEEDS_SURYA_LAYOUT", "IMAGE_LIMIT_REACHED", "IMAGE_DECODE_FAILED", "IMAGE_TOO_LARGE", "TOO_MANY_IMAGES",
            "IMAGE_PARENT_UNREADABLE", "IMAGE_EXTRACTION_FAILED", "IMAGE_SOURCE_UNREADABLE", "IMAGE_PARENT_ORDINAL_TOO_LATE",
        )
        val messages = codes.map(ImageWarnings::messageFor)
        assertEquals(codes.size, messages.toSet().size)
        assertTrue("uv" in ImageWarnings.messageFor("NEEDS_SURYA_LAYOUT"))
    }
}
