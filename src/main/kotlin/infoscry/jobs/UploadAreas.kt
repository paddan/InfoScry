package infoscry.jobs

import infoscry.domain.ApiFaultException
import infoscry.domain.ApiStatus
import infoscry.domain.CollectionId
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

/** A path an import named is outside the collection's upload area. */
class ImportPathNotAllowedException : ApiFaultException(
    ApiStatus.FORBIDDEN,
    "IMPORT_PATH_NOT_ALLOWED",
    "imports read only from files uploaded to the collection",
)

/**
 * Where an import reads from: the files people upload from their own computers, kept in one upload area per
 * collection until imported. The server never imports from its own folders.
 *
 * A path is judged by its real path, with symbolic links resolved, so nothing outside the collection's upload
 * area can be named, also not through a link. Uploads are only read, never modified.
 */
class UploadAreas(private val uploadsDir: Path) {

    /** The upload area of [collectionId]: what its uploads are stored under, and the only place its imports read. */
    fun uploadArea(collectionId: CollectionId): Path = uploadsDir.resolve(safeSegment(collectionId.value))

    /** Refuses unless every one of [paths] lies inside [collectionId]'s upload area. */
    fun requireAllowed(collectionId: CollectionId, paths: List<String>) {
        val area = runCatching { uploadArea(collectionId).toRealPath() }.getOrNull() ?: throw ImportPathNotAllowedException()
        paths.forEach { raw ->
            val real = realPathOf(raw) ?: throw ImportPathNotAllowedException()
            if (!real.startsWith(area)) throw ImportPathNotAllowedException()
        }
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

        /** A collection id as one directory name: ids are UUIDs, and anything else is refused rather than mangled. */
        internal fun safeSegment(value: String): String {
            require(value.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "not a usable collection id for an upload area" }
            return value
        }
    }
}
