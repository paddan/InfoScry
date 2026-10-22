package infoscry.ocr

import infoscry.storage.ContentAddressedFile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Where a page's structure artifact lives under the document's artifact root, and the hash of its stored bytes. */
@kotlinx.serialization.Serializable
data class StructureReference(val relativePath: String, val sha256: String)

/**
 * Writes and reads back the per-page structure artifact (`structure/page-NNNNNN.<sha>.structure.json.gz`).
 *
 * Like [infoscry.pdf.PageTextLayoutStore] the file name carries the hash of its own bytes, so writing the
 * same structure again is a no-op and a reader never sees a half-written file (temporary name, atomic move).
 * The structure is an optional extra on top of the page's text: [read] returns `null` for any damage or
 * mismatch, and the caller then shows and cites the page without structure and reads it again on the next
 * rescan, rather than failing.
 */
object PageStructureStore {

    private const val DIRECTORY: String = "structure"

    private val json = Json { ignoreUnknownKeys = true }

    fun write(artifactRoot: Path, ordinal: Int, structure: PageStructure): StructureReference {
        val bytes = gzip(json.encodeToString(PageStructure.serializer(), structure).toByteArray(Charsets.UTF_8))
        val sha256 = sha256Of(bytes)
        val root = artifactRoot.toAbsolutePath().normalize()
        val relativePath = String.format(Locale.ROOT, "$DIRECTORY/page-%06d.%s.structure.json.gz", ordinal, sha256)
        val target = root.resolve(relativePath)
        ContentAddressedFile.writeOnce(target, bytes, sha256)
        return StructureReference(relativePath, sha256)
    }

    /** The structure [reference] names, or `null` when it is missing, damaged, or not made for [extractedText]. */
    fun read(artifactRoot: Path, reference: StructureReference, extractedText: String): PageStructure? {
        val root = artifactRoot.toAbsolutePath().normalize()
        return try {
            val resolved = confined(root, reference.relativePath) ?: return null
            val bytes = Files.readAllBytes(resolved)
            if (sha256Of(bytes) != reference.sha256) return null
            val text = GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }.toString(Charsets.UTF_8)
            json.decodeFromString(PageStructure.serializer(), text).verifiedAgainst(extractedText)
        } catch (unreadable: IOException) {
            null
        } catch (malformed: SerializationException) {
            null
        } catch (invalid: IllegalArgumentException) {
            null
        }
    }

    private fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    /** The path under [root], or null when it escapes the root lexically or through a symlink. */
    private fun confined(root: Path, relativePath: String): Path? {
        if (relativePath.any { it.isISOControl() }) return null
        val resolved = try {
            root.resolve(relativePath).normalize()
        } catch (invalid: InvalidPathException) {
            return null
        }
        if (resolved == root || !resolved.startsWith(root)) return null
        if (Files.exists(resolved, LinkOption.NOFOLLOW_LINKS) &&
            !resolved.toRealPath().startsWith(root.toRealPath())
        ) {
            return null
        }
        return resolved
    }

    private fun sha256Of(bytes: ByteArray): String = ContentAddressedFile.sha256Of(bytes)
}
