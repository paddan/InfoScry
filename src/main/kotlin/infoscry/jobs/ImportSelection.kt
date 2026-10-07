package infoscry.jobs

import infoscry.extract.ExtractorRegistry
import infoscry.extract.MediaTypeDetector
import infoscry.extract.UnsupportedMediaTypeException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

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
 * 1. the collection's ignore patterns (ticket 07), snapshotted into the import's payload at admission, which also
 *    stop the folder walk from entering an ignored directory;
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
     * Whether the selected [source] is imported under [ignore] and [extensions]. [relativePath] is what [ignore] is
     * matched against: the path below the folder the file was found in, or the file's own name when it was named directly. [exists] is false for a path that no longer
     * exists, which has no content to inspect: it is judged by its name alone and then becomes a missing-source item.
     */
    fun admits(
        source: Path,
        relativePath: String,
        exists: Boolean,
        ignore: IgnorePatterns,
        extensions: ExtensionFilter,
    ): Boolean {
        // The collection's ignore patterns come first, so an ignored file is never weighed against an include or
        // exclude list, and never opened. A folder walk has already dropped what these patterns ignore; this check is
        // what judges a file named directly, and the walk consults the same list through [walk].
        if (ignore.ignores(relativePath, isDirectory = false)) return false
        if (!extensions.admits(source.fileName?.toString().orEmpty())) return false
        return !exists || admitsContent(source)
    }

    /** A regular file the walk found, with the path below the walked folder that ignore patterns are matched against. */
    class Found(val path: Path, val relativePath: String)

    /**
     * The regular files under [root], sorted by path string for a stable import order, without the ones an ignore
     * pattern removes.
     *
     * This is the folder walk's half of the ignore decision, so the same [ignore] list that [admits] applies to a file
     * also stops the walk itself: an ignored directory (`.git/`, `node_modules/`) is skipped as a subtree and nothing
     * inside it is listed or looked at. [entered] is told each directory the walk descends into (below [root]), which
     * is how a test observes that.
     *
     * With [recursive] the walk descends the whole tree; without it the maximum depth is [root]'s own level. Symbolic
     * links are never followed: a link is read as a link, so it is not a regular file here.
     */
    fun walk(
        root: Path,
        recursive: Boolean,
        ignore: IgnorePatterns,
        entered: (String) -> Unit = {},
    ): List<Found> {
        val found = mutableListOf<Found>()
        Files.walkFileTree(
            root,
            setOf(),
            if (recursive) Int.MAX_VALUE else 1,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(directory: Path, attributes: BasicFileAttributes): FileVisitResult {
                    if (directory == root) return FileVisitResult.CONTINUE
                    val relative = relativeTo(root, directory)
                    if (ignore.ignores(relative, isDirectory = true)) return FileVisitResult.SKIP_SUBTREE
                    entered(relative)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                    if (attributes.isRegularFile) {
                        val relative = relativeTo(root, file)
                        if (!ignore.ignores(relative, isDirectory = false)) found.add(Found(file, relative))
                    }
                    return FileVisitResult.CONTINUE
                }
            },
        )
        return found.sortedBy { it.path.toString() }
    }

    private fun relativeTo(root: Path, path: Path): String = root.relativize(path).joinToString("/")

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
