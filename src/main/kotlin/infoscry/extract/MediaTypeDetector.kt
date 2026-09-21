package infoscry.extract

import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.zip.ZipFile
import org.apache.poi.poifs.filesystem.POIFSFileSystem
import org.apache.tika.detect.DefaultDetector
import org.apache.tika.io.TikaInputStream
import org.apache.tika.metadata.Metadata

/** The media type InfoScry read from a document's bytes. */
@JvmInline
value class DetectedMediaType(val value: String) {

    override fun toString(): String = value
}

/**
 * What a file actually is, decided from its content.
 *
 * Imported material arrives with whatever name it was published under, and a name is not evidence: a
 * scan saved as `.pdf`, a `.docx` that is really plain text, a `.txt` that is really a container. Every
 * decision downstream — which extractor runs, whether OCR is needed, whether a document is importable
 * at all — is therefore made from the bytes.
 *
 * The file's name is deliberately **not** offered to the detector as a hint. A hint would win for exactly
 * the containers whose bytes are ambiguous, which is the case the detection exists to settle.
 */
class MediaTypeDetector(private val detector: DefaultDetector = DefaultDetector()) {

    fun detect(path: Path): DetectedMediaType {
        require(Files.isRegularFile(path)) {
            "media type detection needs a regular file, but $path is not one"
        }
        TikaInputStream.get(path).use { stream ->
            val detected = detector.detect(stream, Metadata()).toString()
            val named = narrowTextContainer(detected, path.fileName?.toString().orEmpty())
            return DetectedMediaType(narrowContainer(named, path))
        }
    }

    /**
     * The precise type of a container the detector could only identify as "some Office file".
     *
     * Tika's OOXML and Word 97 detectors live in its parser modules, which this build deliberately does
     * not carry, so a `.docx` arrives as the generic `application/x-tika-ooxml` and a `.doc` as
     * `application/x-tika-msoffice`. Those generic types name every format of their family, which is not
     * enough to choose a reader — and choosing the wrong one is how a spreadsheet gets cited by paragraph.
     *
     * The answer is already in the container, so it is read from there rather than guessed from a name:
     * the entries a zip holds and the streams a compound file holds are what tell a Word document from a
     * workbook. Nothing is parsed and nothing is extracted; only the container's own index is read.
     */
    private fun narrowContainer(detected: String, path: Path): String = when (detected) {
        OOXML_CONTAINER -> ooxmlType(path) ?: detected
        MSOFFICE_CONTAINER -> compoundFileType(path) ?: detected
        else -> detected
    }

    private fun ooxmlType(path: Path): String? = try {
        ZipFile(path.toFile()).use { zip ->
            when {
                zip.getEntry(WORD_DOCUMENT_ENTRY) != null -> DOCX_MEDIA_TYPE
                zip.getEntry(WORKBOOK_ENTRY) != null || zip.getEntry(WORKBOOK_BINARY_ENTRY) != null ->
                    XLSX_MEDIA_TYPE

                zip.getEntry(PRESENTATION_ENTRY) != null -> PPTX_MEDIA_TYPE
                else -> null
            }
        }
    } catch (failure: Exception) {
        // A container that cannot be opened is not a container this detector can name; the caller sees the
        // generic type and refuses the document by name rather than being told a type nobody read.
        if (failure is InterruptedException) throw failure
        null
    }

    private fun compoundFileType(path: Path): String? = try {
        POIFSFileSystem(path.toFile()).use { filesystem ->
            val root = filesystem.root
            when {
                root.hasEntry(WORD_STREAM) -> DOC_MEDIA_TYPE
                root.hasEntry(WORKBOOK_STREAM) || root.hasEntry(WORKBOOK_OLD_STREAM) -> XLS_MEDIA_TYPE
                root.hasEntry(PRESENTATION_STREAM) -> PPT_MEDIA_TYPE
                else -> null
            }
        }
    } catch (failure: Exception) {
        if (failure is InterruptedException) throw failure
        null
    }

    /**
     * The media type of a text container, narrowed by the name the file was published under.
     *
     * Every text container without a signature of its own — CSV, TSV, Markdown — is reported as
     * `text/plain`, which is true and useless: it says the file is text but not which reader understands
     * its structure, so a `.csv` would be read as prose and cited by line range instead of by spreadsheet
     * range, and the viewer for its rows would never be reached. Content cannot settle that question —
     * bytes that differ only in their separator are the same bytes — so the name is consulted, and only
     * here: after the bytes have already said the file is text, and only to choose between readers that
     * all read text.
     *
     * The rule stays subordinate to the bytes. A container named `.csv` whose content is a zip is still a
     * zip, and content that is not text is never claimed to be.
     */
    private fun narrowTextContainer(detected: String, filename: String): String {
        if (detected.substringBefore(';').trim() != TEXT_PLAIN) return detected
        return when (filename.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
            "csv", "tsv" -> TEXT_CSV
            "md", "markdown" -> TEXT_MARKDOWN
            else -> detected
        }
    }
}

/** The generic type Tika reports for every OOXML container when its parser modules are absent. */
private const val OOXML_CONTAINER = "application/x-tika-ooxml"

/** The generic type Tika reports for every Word 97-2003 compound file. */
private const val MSOFFICE_CONTAINER = "application/x-tika-msoffice"

/** The zip entries that tell one OOXML document family from another. */
private const val WORD_DOCUMENT_ENTRY = "word/document.xml"
private const val WORKBOOK_ENTRY = "xl/workbook.xml"
private const val WORKBOOK_BINARY_ENTRY = "xl/workbook.bin"
private const val PRESENTATION_ENTRY = "ppt/presentation.xml"

/** The streams that tell one compound-file format from another. */
private const val WORD_STREAM = "WordDocument"
private const val WORKBOOK_STREAM = "Workbook"
private const val WORKBOOK_OLD_STREAM = "Book"
private const val PRESENTATION_STREAM = "PowerPoint Document"
