package infoscry.extract

import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
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
            return DetectedMediaType(narrowTextContainer(detected, path.fileName?.toString().orEmpty()))
        }
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
