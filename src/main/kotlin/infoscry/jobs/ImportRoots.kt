package infoscry.jobs

import infoscry.domain.ApiFaultException
import infoscry.domain.ApiStatus
import infoscry.domain.CollectionId
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

/** A path an import named is outside every import root and the collection's upload area. */
class ImportPathNotAllowedException : ApiFaultException(
    ApiStatus.FORBIDDEN,
    "IMPORT_PATH_NOT_ALLOWED",
    "imports read only from the server's import roots and from files uploaded to the collection",
)

/** One import root as a client sees it: its position and its own name, never more of the server's layout. */
@Serializable
data class ImportRootView(val index: Int, val name: String, val path: String)

/** One entry of a browsed directory. [path] is the absolute path an import names. */
@Serializable
data class ImportRootEntry(val name: String, val path: String, val directory: Boolean, val sizeBytes: Long? = null)

@Serializable
data class ImportRootListing(val root: ImportRootView, val path: String, val entries: List<ImportRootEntry>)

/**
 * Where on the server an import may read from: the directories an administrator lists in
 * `INFOSCRY_IMPORT_ROOTS`, and each collection's own upload area.
 *
 * A path is judged by its real path, with symbolic links resolved, so a link inside a root that leads out of it
 * is outside. Originals are only read, never modified.
 */
class ImportRoots(configured: List<Path>, private val uploadsDir: Path) {

    /** The roots that exist, as real paths, in the order configured. */
    val roots: List<Path> = configured.mapNotNull { root ->
        runCatching { root.toAbsolutePath().toRealPath() }.getOrNull()?.takeIf { Files.isDirectory(it) }
            ?: run {
                LOGGER.atWarn().addKeyValue("import_root", root.toString()).log("an import root does not exist and is skipped")
                null
            }
    }

    /** The upload area of [collectionId]: what its uploads are stored under, and an implicit root for its imports. */
    fun uploadArea(collectionId: CollectionId): Path = uploadsDir.resolve(safeSegment(collectionId.value))

    /** Refuses unless every one of [paths] lies inside a root or [collectionId]'s upload area. */
    fun requireAllowed(collectionId: CollectionId, paths: List<String>) {
        val allowed = roots + listOfNotNull(runCatching { uploadArea(collectionId).toRealPath() }.getOrNull())
        paths.forEach { raw ->
            val real = realPathOf(raw) ?: throw ImportPathNotAllowedException()
            if (allowed.none { real.startsWith(it) }) throw ImportPathNotAllowedException()
        }
    }

    fun views(): List<ImportRootView> = roots.mapIndexed { index, root -> ImportRootView(index, root.fileName?.toString() ?: root.toString(), root.toString()) }

    /** The folders and files of [relative] below root [index]; `..` and links that leave the root are refused or left out. */
    fun browse(index: Int, relative: String): ImportRootListing {
        val root = roots.getOrNull(index) ?: throw NoSuchElementException("no import root $index")
        val segments = relative.split('/').filter { it.isNotEmpty() && it != "." }
        require(segments.none { it == ".." }) { "a browsed path may not leave its root" }
        val directory = segments.fold(root) { path, segment -> path.resolve(segment) }
        val real = runCatching { directory.toRealPath() }.getOrNull()
        if (real == null || !real.startsWith(root) || !Files.isDirectory(real)) throw NoSuchElementException("no such folder in this import root")
        val entries = Files.list(real).use { stream ->
            stream.toList().mapNotNull { entry ->
                val target = runCatching { entry.toRealPath() }.getOrNull() ?: return@mapNotNull null
                if (!target.startsWith(root)) return@mapNotNull null
                val name = entry.fileName.toString()
                if (name.startsWith(".")) return@mapNotNull null
                val isDirectory = Files.isDirectory(target)
                ImportRootEntry(
                    name = name,
                    path = entry.toString(),
                    directory = isDirectory,
                    sizeBytes = if (isDirectory) null else runCatching { Files.size(target) }.getOrNull(),
                )
            }
        }.sortedWith(compareBy({ !it.directory }, { it.name.lowercase() }))
        return ImportRootListing(views()[index], segments.joinToString("/"), entries)
    }

    /** Removes upload areas older than [maxAge]; their imports have their managed copies by then. */
    fun sweepUploads(maxAge: Duration = UPLOAD_RETENTION, now: Instant = Instant.now()): Int {
        if (!Files.isDirectory(uploadsDir)) return 0
        var removed = 0
        Files.list(uploadsDir).use { collections ->
            collections.toList().filter { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }.forEach { collection ->
                Files.list(collection).use { uploads ->
                    uploads.toList().forEach { upload ->
                        val modified = runCatching { Files.getLastModifiedTime(upload, LinkOption.NOFOLLOW_LINKS).toInstant() }.getOrNull()
                        if (modified != null && modified.isBefore(now.minus(maxAge))) {
                            upload.toFile().deleteRecursively()
                            removed++
                        }
                    }
                }
            }
        }
        return removed
    }

    /** The real path of [raw], or for a path that does not exist, its parent's real path with its own name. */
    private fun realPathOf(raw: String): Path? {
        val path = runCatching { Path.of(raw) }.getOrNull()?.takeIf { it.isAbsolute } ?: return null
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return runCatching { path.toRealPath() }.getOrNull()
        val parent = path.parent ?: return null
        return runCatching { parent.toRealPath().resolve(path.fileName) }.getOrNull()
    }

    companion object {
        val UPLOAD_RETENTION: Duration = Duration.ofDays(7)
        private val LOGGER = LoggerFactory.getLogger("infoscry.import")

        /** Where the roots come from; tests replace it, as they replace the database. */
        @Volatile
        var configured: () -> List<Path> = { fromEnvironment() }

        /** `INFOSCRY_IMPORT_ROOTS`: absolute directories separated by the platform's path separator. */
        fun fromEnvironment(environment: Map<String, String> = System.getenv()): List<Path> =
            environment["INFOSCRY_IMPORT_ROOTS"].orEmpty().split(File.pathSeparatorChar)
                .map(String::trim).filter(String::isNotEmpty).map { Path.of(it) }

        /** A collection id as one directory name: ids are UUIDs, and anything else is refused rather than mangled. */
        internal fun safeSegment(value: String): String {
            require(value.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "not a usable collection id for an upload area" }
            return value
        }
    }
}
