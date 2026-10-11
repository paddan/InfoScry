package infoscry.pdf

import infoscry.config.AppPaths
import infoscry.domain.CollectionId
import infoscry.domain.DocumentId
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * A layout sidecar's own bytes are not the bytes its reference names, or cannot be read or parsed back at
 * all. This is a storage-level integrity failure — the file this process wrote, or the directory it lives
 * under, is damaged or was tampered with — distinct from [PageTextLayoutProvenanceException], which is about
 * a layout that is well-formed and intact but belongs to different content (different canonical text, or a
 * different reading image) than the caller is about to use it for.
 */
class PageTextLayoutStoreCorruptException(message: String) : IllegalStateException(message)

/**
 * Writes and reads back the normalized text-geometry sidecars [PageTextLayout] describes (ticket 04 of the
 * selectable-PDF plan).
 *
 * A layout artifact is content-addressed: its file name is the SHA-256 of its own serialized bytes, under a
 * `layout/` directory inside the document's own artifact root — the same root [infoscry.ocr.PageImage]'s
 * renders and [infoscry.extract.TesseractOcr]'s TSV already live under, kept in its own subdirectory so the
 * two kinds of reference are never confused with each other.
 *
 * Content-addressing is what makes [write] naturally idempotent and resumable: two attempts that compute the
 * same layout (the common case — a page re-read after a crash reproduces the same text and the same local
 * alignment) write the same file under the same name, so a repeat is a no-op rather than a second file or a
 * race. The write itself goes through a per-call temporary name and an atomic rename, so a reader can never
 * observe a half-written sidecar at the name a reference might already commit to.
 *
 * [read] re-verifies the bytes against the reference's own hash (catching a sidecar directory that was
 * damaged or edited outside this store) and then, through [PageTextLayout.verifiedAgainst], against the
 * canonical text the caller is about to use the layout for (catching a reference reused for the wrong
 * content). The two failures are deliberately different exception types: the first is this store's own
 * [PageTextLayoutStoreCorruptException], the second is 02's [PageTextLayoutProvenanceException], which
 * [verifyImageMatch] also throws for the symmetric case — a layout whose recorded image hash does not match
 * the actual bytes of the reading image the caller loaded for this page, which [PageTextLayout.verifiedAgainst]
 * itself cannot check because it is never handed the image.
 *
 * All resolution is confined to the root an artifact was produced under: [LayoutReference] itself already
 * refuses a relative path with a `..`, an absolute path or a control character, and [read] additionally
 * refuses a path that would only *resolve* outside the root through a symlink planted somewhere along it —
 * the one escape a purely lexical check cannot see.
 */
object PageTextLayoutStore {

    /** The directory layout sidecars live under, inside a document's own artifact root. */
    private const val LAYOUT_DIRECTORY: String = "layout"

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Writes [layout]'s bytes under [root] (a document's artifact root) and returns a reference to them.
     *
     * Writing the same layout twice — the resume case — reuses the existing file without rewriting it: the
     * name is the content's own hash, so there is nothing to compare before deciding the write is already
     * done. A write that is attempted while the file does not yet exist, or exists with other bytes (this
     * should not happen for a content-addressed name, but a damaged file is not ruled out), always goes
     * through a temporary file and an atomic rename, so the name a reference is about to be committed under
     * is either the old complete file or the new one, never a partial write.
     */
    fun write(root: Path, layout: PageTextLayout): LayoutReference {
        val bytes = json.encodeToString(PageTextLayout.serializer(), layout).toByteArray(Charsets.UTF_8)
        val sha256 = sha256Of(bytes)
        val normalizedRoot = root.toAbsolutePath().normalize()
        val relativePath = "$LAYOUT_DIRECTORY/$sha256.json"
        val target = normalizedRoot.resolve(relativePath)
        if (!alreadyWritten(target, sha256)) {
            Files.createDirectories(checkNotNull(target.parent))
            val temporary = target.resolveSibling("${target.fileName}.${UUID.randomUUID()}.tmp")
            try {
                Files.write(temporary, bytes)
                moveIntoPlace(temporary, target)
            } finally {
                Files.deleteIfExists(temporary)
            }
        }
        return LayoutReference(relativePath, sha256)
    }

    /** Writes through [paths]' own artifact-root resolution, for a caller that only has collection/document ids. */
    fun write(paths: AppPaths, collectionId: CollectionId, documentId: DocumentId, layout: PageTextLayout): LayoutReference =
        write(paths.artifactsDir(collectionId, documentId), layout)

    /**
     * Reads the layout [reference] names back from under [root], verified against [canonicalText].
     *
     * @throws PageTextLayoutStoreCorruptException when the file is missing, unreadable, not well-formed JSON,
     *   or its bytes do not match [reference]'s own recorded hash.
     * @throws PageTextLayoutProvenanceException when the bytes are intact but [PageTextLayout.verifiedAgainst]
     *   finds the layout's text hash does not match [canonicalText].
     */
    fun read(root: Path, reference: LayoutReference, canonicalText: String): PageTextLayout {
        val normalizedRoot = root.toAbsolutePath().normalize()
        val resolved = resolveConfined(normalizedRoot, reference.relativePath)
        val bytes = try {
            Files.readAllBytes(resolved)
        } catch (unreadable: IOException) {
            throw PageTextLayoutStoreCorruptException(
                "the layout artifact this reference names could not be read",
            )
        }
        if (sha256Of(bytes) != reference.sha256) {
            throw PageTextLayoutStoreCorruptException(
                "the layout artifact's bytes do not match the hash its reference names",
            )
        }
        val layout = try {
            json.decodeFromString(PageTextLayout.serializer(), bytes.toString(Charsets.UTF_8))
        } catch (malformed: SerializationException) {
            throw PageTextLayoutStoreCorruptException("the layout artifact is not a well-formed layout record")
        } catch (malformed: IllegalArgumentException) {
            throw PageTextLayoutStoreCorruptException("the layout artifact is not a well-formed layout record")
        }
        return layout.verifiedAgainst(canonicalText)
    }

    /** Reads through [paths]' own artifact-root resolution, for a caller that only has collection/document ids. */
    fun read(paths: AppPaths, collectionId: CollectionId, documentId: DocumentId, reference: LayoutReference, canonicalText: String): PageTextLayout =
        read(paths.artifactsDir(collectionId, documentId), reference, canonicalText)

    /**
     * [layout], proven to belong to the reading image whose actual bytes hash to [actualImageSha256].
     *
     * [PageTextLayout.verifiedAgainst] proves the text half of provenance; it is never handed image bytes, so
     * it cannot check this half. A caller that loads the reading image for a page calls this immediately
     * afterwards, with the hash it independently computed over that image's own bytes — never a hash merely
     * copied from a provenance record — so a layout reused against the wrong picture is caught here rather
     * than trusted.
     *
     * @throws PageTextLayoutProvenanceException when [PageTextLayout.imageSha256] does not match [actualImageSha256].
     */
    fun verifyImageMatch(layout: PageTextLayout, actualImageSha256: String): PageTextLayout {
        if (!layout.imageSha256.equals(actualImageSha256, ignoreCase = true)) {
            throw PageTextLayoutProvenanceException(
                "a layout is verified against the reading image it was made from, not against another one",
            )
        }
        return layout
    }

    private fun alreadyWritten(target: Path, expectedSha256: String): Boolean {
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return false
        return try {
            sha256Of(Files.readAllBytes(target)) == expectedSha256
        } catch (unreadable: IOException) {
            false
        }
    }

    private fun moveIntoPlace(temporary: Path, target: Path) {
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (unsupported: AtomicMoveNotSupportedException) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /**
     * The path [relativePath] names under [root], refused unless it both lexically and, where the file
     * already exists, really (through its canonical filesystem path) stays inside [root].
     *
     * [LayoutReference]'s own validation already rejects `..`, an absolute path and a control character, so
     * the lexical check here is redundant for a reference this store itself produced; it is kept as the
     * boundary's own defense for a reference read back from a row nothing here wrote. The real-path check is
     * the one a lexical check cannot do at all: a symlink planted inside the root, pointing outside it, has a
     * perfectly clean relative name and still has to be refused.
     */
    private fun resolveConfined(root: Path, relativePath: String): Path {
        val resolved = root.resolve(relativePath).normalize()
        require(resolved != root && resolved.startsWith(root)) {
            "a layout reference may not name a file outside the artifact root it was produced under"
        }
        if (Files.exists(resolved, LinkOption.NOFOLLOW_LINKS)) {
            val realResolved = resolved.toRealPath()
            val realRoot = root.toRealPath()
            require(realResolved.startsWith(realRoot)) {
                "a layout reference may not resolve, through a symlink, outside the artifact root it was produced under"
            }
        }
        return resolved
    }

    private fun sha256Of(bytes: ByteArray): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
}
