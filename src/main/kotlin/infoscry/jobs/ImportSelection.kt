package infoscry.jobs

import infoscry.extract.ExtractorRegistry
import infoscry.extract.MediaTypeDetector
import infoscry.extract.UnsupportedMediaTypeException
import java.nio.file.Path

/**
 * The one decision that stands between a file an import selected and anything durable about it: import it,
 * or skip it.
 *
 * A file is imported when the pipeline has an extractor for what its bytes are. For support, the bytes decide,
 * not the name: the same detector and the same registry that the import reads the managed copy with are asked here,
 * so a file admitted here is one the reader will be handed, and a file refused here is one no reader claims.
 * A refused file leaves nothing behind: it is not copied, it is not queued as an item, and it is not counted
 * among the import's files, so it appears nowhere a person could see it.
 *
 * The decision runs in a fixed order, and a file removed by any step is skipped the same way:
 *
 * 1. the collection's ignore patterns (ticket 07, not yet implemented; it goes first, in [admits]);
 * 2. the import's extension filter (ticket 06), which needs only the name and so applies to a missing source too;
 * 3. whether the detected content has an extractor (ticket 05), which needs the bytes and so is skipped for a
 *    missing source.
 *
 * A file that cannot be inspected at all is admitted rather than skipped. The copy then reports the reason
 * against that file's own item, as it always has, so an unreadable file is never silently dropped.
 */
internal class ImportSelection(
    private val detector: MediaTypeDetector,
    private val registry: ExtractorRegistry,
) {

    /**
     * Whether the selected [source] is imported under [extensions]. [exists] is false for a path that no longer
     * exists, which has no content to inspect: it is judged by its name alone and then becomes a missing-source item.
     */
    fun admits(source: Path, exists: Boolean, extensions: ExtensionFilter): Boolean {
        // Ticket 07 (per-collection ignore patterns) is applied here, before the extension filter, so an ignored
        // file is never weighed against an include or exclude list.
        if (!extensions.admits(source.fileName?.toString().orEmpty())) return false
        return !exists || admitsContent(source)
    }

    /** Whether the content of the regular file at [file] has an extractor. Files that cannot be inspected are admitted. */
    private fun admitsContent(file: Path): Boolean {
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
