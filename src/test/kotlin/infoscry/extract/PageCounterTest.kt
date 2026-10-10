package infoscry.extract

import java.awt.image.BufferedImage
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts

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
    @Test
    fun `mixed PDF keeps clean text pages and counts only the scanned page for reading`() {
        val pdf = Files.createTempFile("page-counter-mixed", ".pdf")
        try {
            PDDocument().use { document ->
                val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)
                repeat(3) { ordinal ->
                    val page = PDPage()
                    document.addPage(page)
                    if (ordinal == 1) {
                        val image = LosslessFactory.createFromImage(document, BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB))
                        PDPageContentStream(document, page).use { stream ->
                            stream.drawImage(image, 0f, 0f, page.mediaBox.width, page.mediaBox.height)
                        }
                    } else {
                        PDPageContentStream(document, page).use { stream ->
                            stream.beginText()
                            stream.setFont(font, 12f)
                            stream.newLineAtOffset(40f, 700f)
                            stream.showText("Ordinary digital text remains readable and needs no image reading.")
                            stream.endText()
                        }
                    }
                }
                document.save(pdf.toFile())
            }
            val counter = DefaultPageCounter { DefaultPageCounter.PDF_MEDIA_TYPE }
            assertEquals(3, counter.pageCount(pdf))
            assertEquals(1, counter.readablePageCount(pdf))
            assertEquals(1, counter.readablePageCount(pdf, DefaultPageCounter.PDF_MEDIA_TYPE))
        } finally {
            Files.deleteIfExists(pdf)
        }
    }

}
