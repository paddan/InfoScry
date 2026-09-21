package infoscry.extract

import java.nio.file.Files
import java.nio.file.Path
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
            return DetectedMediaType(detector.detect(stream, Metadata()).toString())
        }
    }
}
