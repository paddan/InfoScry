package infoscry.jobs

import infoscry.domain.ApiFaultException
import infoscry.domain.ApiStatus
import infoscry.domain.CollectionId
import infoscry.storage.ImportRootStore
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

/** Where an import root comes from: added under Admin, or listed in `INFOSCRY_IMPORT_ROOTS` when the server started. */
@Serializable
enum class ImportRootSource { ADMIN, ENVIRONMENT }

/**
 * One import root as a client sees it. [available] is false when its folder is gone or unreadable; nothing is
 * browsed or imported from it then. [addedBy] is the display name of whoever added it under Admin.
 */
@Serializable
data class ImportRootView(
    val id: String,
    val name: String,
    val path: String,
    val source: ImportRootSource,
    val available: Boolean,
    val addedAt: String? = null,
    val addedBy: String? = null,
)

/** One entry of a browsed directory. [path] is the absolute path an import names. */
@Serializable
data class ImportRootEntry(val name: String, val path: String, val directory: Boolean, val sizeBytes: Long? = null)

@Serializable
data class ImportRootListing(val root: ImportRootView, val path: String, val entries: List<ImportRootEntry>)

/** An environment root is the server's configuration, so it is changed where it was set rather than under Admin. */
class ImportRootFromEnvironmentException : ApiFaultException(
    ApiStatus.CONFLICT,
    "IMPORT_ROOT_FROM_ENVIRONMENT",
    "this folder is listed in the server's INFOSCRY_IMPORT_ROOTS; remove it there and restart the server",
)

/**
 * Where on the server an import may read from: the directories an administrator adds under Admin (kept in
 * [store]), any listed in `INFOSCRY_IMPORT_ROOTS`, and each collection's own upload area.
 *
 * A path is judged by its real path, with symbolic links resolved, so a link inside a root that leads out of it
 * is outside. Roots are read for every decision, so one added or removed under Admin counts at once. Originals
 * are only read, never modified.
 */
class ImportRoots(
    private val store: ImportRootStore,
    configured: List<Path>,
    private val uploadsDir: Path,
    private val dataDir: Path,
) {

    private class Root(val view: ImportRootView, val real: Path?)

    /** The environment's roots, as given; each is judged again whenever it is used, like a stored one. */
    private val environmentRoots: List<Path> = configured.map { it.toAbsolutePath().normalize() }

    init {
        environmentRoots.filter { realDirectory(it) == null }.forEach { root ->
            LOGGER.atWarn().addKeyValue("import_root", root.toString()).log("an import root does not exist and is skipped")
        }
    }

    private fun current(): List<Root> {
        val fromEnvironment = environmentRoots.mapIndexed { index, configured ->
            val real = realDirectory(configured)
            val path = (real ?: configured).toString()
            Root(ImportRootView("environment-$index", nameOf(path), path, ImportRootSource.ENVIRONMENT, real != null), real)
        }
        val seen = fromEnvironment.map { it.view.path }.toMutableSet()
        val stored = store.list().filter { seen.add(it.path) }.map { root ->
            // A stored path is a real path; if it now resolves elsewhere (a folder replaced by a link), it is not used.
            val real = realDirectory(Path.of(root.path))?.takeIf { it.toString() == root.path }
            Root(ImportRootView(root.id, nameOf(root.path), root.path, ImportRootSource.ADMIN, real != null, root.addedAt, root.addedBy), real)
        }
        return fromEnvironment + stored
    }

    /** The upload area of [collectionId]: what its uploads are stored under, and an implicit root for its imports. */
    fun uploadArea(collectionId: CollectionId): Path = uploadsDir.resolve(safeSegment(collectionId.value))

    /** Refuses unless every one of [paths] lies inside a root or [collectionId]'s upload area. */
    fun requireAllowed(collectionId: CollectionId, paths: List<String>) {
        val allowed = current().mapNotNull { it.real } + listOfNotNull(runCatching { uploadArea(collectionId).toRealPath() }.getOrNull())
        paths.forEach { raw ->
            val real = realPathOf(raw) ?: throw ImportPathNotAllowedException()
            if (allowed.none { real.startsWith(it) }) throw ImportPathNotAllowedException()
        }
    }

    fun views(): List<ImportRootView> = current().map { it.view }

    /**
     * Adds the folder at [rawPath] as a root and answers it with whether this call added it; a folder that is
     * already a root, by its real path, is answered as it is.
     */
    fun add(rawPath: String): Pair<ImportRootView, Boolean> {
        val trimmed = rawPath.trim()
        val given = runCatching { Path.of(trimmed) }.getOrNull()
        require(trimmed.isNotEmpty() && given != null && given.isAbsolute) { "give the folder's full path on the server, starting with /" }
        val real = realDirectory(given) ?: throw IllegalArgumentException("there is no folder at $trimmed on the server")
        require(Files.isReadable(real)) { "the server may not read the folder $trimmed" }
        require(real.parent != null) { "the whole file system cannot be an import folder; choose a folder inside it" }
        val data = runCatching { dataDir.toRealPath() }.getOrElse { dataDir.toAbsolutePath().normalize() }
        require(!real.startsWith(data)) { "the server's own data directory cannot be an import folder" }
        current().firstOrNull { it.view.path == real.toString() }?.let { return it.view to false }
        val (_, created) = store.add(real.toString())
        return current().first { it.view.path == real.toString() }.view to created
    }

    /** Removes the root [id] added under Admin; removing one that is already gone changes nothing. */
    fun remove(id: String) {
        if (environmentRoots.indices.any { id == "environment-$it" }) throw ImportRootFromEnvironmentException()
        store.remove(id)
    }

    /** The folders and files of [relative] below root [id]; `..` and links that leave the root are refused or left out. */
    fun browse(id: String, relative: String): ImportRootListing {
        val current = current().firstOrNull { it.view.id == id } ?: throw NoSuchElementException("no such import folder")
        val root = current.real ?: throw NoSuchElementException("the import folder ${current.view.path} is not available on the server")
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
        return ImportRootListing(current.view, segments.joinToString("/"), entries)
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

    private fun realDirectory(path: Path): Path? =
        runCatching { path.toAbsolutePath().toRealPath() }.getOrNull()?.takeIf { Files.isDirectory(it) }

    private fun nameOf(path: String): String = Path.of(path).fileName?.toString() ?: path

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

        /** The roots from the environment; tests replace it, as they replace the database. */
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
