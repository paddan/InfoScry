package infoscry.jobs

import infoscry.extract.ExtractorRegistry
import infoscry.extract.MediaTypeDetector
import infoscry.extract.UnsupportedMediaTypeException
import java.nio.file.Path

/**
 * The one decision that stands between a file an import selected and anything durable about it: import it,
 * or skip it.
 *
 * A file is imported when the pipeline has an extractor for what its bytes are. The bytes decide, not the
 * name: the same detector and the same registry that the import reads the managed copy with are asked here,
 * so a file admitted here is one the reader will be handed, and a file refused here is one no reader claims.
 * A refused file leaves nothing behind: it is not copied, it is not queued as an item, and it is not counted
 * among the import's files, so it appears nowhere a person could see it.
 *
 * This is the seam the file filters extend. Any further decision about a candidate file (extension
 * include and exclude lists, ignore patterns) belongs here, before the import copies anything.
 *
 * A file that cannot be inspected at all is admitted rather than skipped. The copy then reports the reason
 * against that file's own item, as it always has, so an unreadable file is never silently dropped.
 */
internal class ImportSelection(
    private val detector: MediaTypeDetector,
    private val registry: ExtractorRegistry,
) {

    /** Whether the regular file at [file] is imported. Files that are not regular files are never asked. */
    fun admits(file: Path): Boolean {
        val mediaType = try {
            detector.detect(file).value
        } catch (failure: Exception) {
            if (failure is InterruptedException) throw failure
            return true
        }
        return try {
            registry.select(mediaType)
            true
        } catch (unsupported: UnsupportedMediaTypeException) {
            false
        }
    }
}
