package infoscry.extract

import java.nio.file.Files
import java.nio.file.Path
import org.apache.pdfbox.Loader
import org.apache.pdfbox.io.IOUtils

/** Counts source pages before reading, or returns null when the format or container cannot be counted. */
interface PageCounter {
    fun pageCount(path: Path): Int?

    /** Lets a stored document supply its already detected media type without changing the shared contract. */
    fun pageCount(path: Path, mediaType: String): Int? = pageCount(path)

    /** Pages that will be read, which is the document's pages less those whose own text is kept. */
    fun readablePageCount(path: Path): Int? = pageCount(path)

    /** [readablePageCount] for a document whose media type is already known. */
    fun readablePageCount(path: Path, mediaType: String): Int? = pageCount(path, mediaType)
}

/** The production count for supported page-image documents. */
class DefaultPageCounter(
    private val mediaTypeOf: (Path) -> String? = { path ->
        runCatching { MediaTypeDetector().detect(path).value }.getOrNull()
    },
) : PageCounter {
    override fun pageCount(path: Path): Int? = mediaTypeOf(path)?.let { mediaType -> pageCount(path, mediaType) }

    override fun readablePageCount(path: Path): Int? =
        mediaTypeOf(path)?.let { mediaType -> readablePageCount(path, mediaType) }

    override fun readablePageCount(path: Path, mediaType: String): Int? = when (mediaType) {
        PDF_MEDIA_TYPE -> readPdfPageCount(path)
        else -> pageCount(path, mediaType)
    }

    override fun pageCount(path: Path, mediaType: String): Int? = when (mediaType) {
        PDF_MEDIA_TYPE -> countPdf(path)
        in PICTURE_MEDIA_TYPES -> if (Files.isRegularFile(path)) 1 else null
        else -> null
    }

    private fun countPdf(path: Path): Int? = try {
        if (!Files.isRegularFile(path)) return null
        Loader.loadPDF(path.toFile(), IOUtils.createTempFileOnlyStreamCache()).use { pdf -> pdf.numberOfPages }
    } catch (_: Exception) {
        null
    }

    /** The pages [PdfPageSelector] would read; a PDF that cannot be opened has no count. */
    private fun readPdfPageCount(path: Path): Int? = try {
        if (!Files.isRegularFile(path)) return null
        Loader.loadPDF(path.toFile(), IOUtils.createTempFileOnlyStreamCache()).use { pdf ->
            PdfPageSelector().pagesToRead(pdf).size
        }
    } catch (_: Exception) {
        null
    }

    companion object {
        const val PDF_MEDIA_TYPE = "application/pdf"
        val PICTURE_MEDIA_TYPES = setOf("image/png", "image/jpeg", "image/tiff")
    }
}
