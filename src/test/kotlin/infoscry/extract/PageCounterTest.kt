package infoscry.extract

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage

class PageCounterTest {
    @Test
    fun `counts each supported image as one page`() {
        val image = Files.createTempFile("page-counter", ".png")
        try {
            Files.write(image, byteArrayOf(1, 2, 3))
            val counter = DefaultPageCounter { "image/png" }
            assertEquals(1, counter.pageCount(image))
        } finally {
            Files.deleteIfExists(image)
        }
    }

    @Test
    fun `counts PDF pages and leaves unreadable or unsupported files unknown`() {
        val pdf = Files.createTempFile("page-counter", ".pdf")
        val unsupported = Files.createTempFile("page-counter", ".txt")
        val malformed = Files.createTempFile("page-counter", ".bad.pdf")
        try {
            PDDocument().use { document ->
                document.addPage(PDPage())
                document.addPage(PDPage())
                document.save(pdf.toFile())
            }
            Files.writeString(malformed, "not a PDF")
            val counter = DefaultPageCounter { path ->
                when (path) {
                    pdf -> DefaultPageCounter.PDF_MEDIA_TYPE
                    malformed -> DefaultPageCounter.PDF_MEDIA_TYPE
                    unsupported -> "text/plain"
                    else -> null
                }
            }

            assertEquals(2, counter.pageCount(pdf))
            assertNull(counter.pageCount(malformed))
            assertNull(counter.pageCount(unsupported))
        } finally {
            Files.deleteIfExists(pdf)
            Files.deleteIfExists(unsupported)
            Files.deleteIfExists(malformed)
        }
    }

    @Test
    fun `a pdf reports how many of its pages need reading`() {
        val pdf = Files.createTempFile("page-counter-readable", ".pdf")
        try {
            // Two pages with no text layer and no image: each has no usable text, so each must be read.
            PDDocument().use { document ->
                document.addPage(PDPage())
                document.addPage(PDPage())
                document.save(pdf.toFile())
            }
            val counter = DefaultPageCounter { DefaultPageCounter.PDF_MEDIA_TYPE }

            assertEquals(2, counter.pageCount(pdf))
            assertEquals(2, counter.readablePageCount(pdf))
        } finally {
            Files.deleteIfExists(pdf)
        }
    }
}
