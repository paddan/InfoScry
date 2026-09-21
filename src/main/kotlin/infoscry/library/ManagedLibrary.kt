package infoscry.library

import infoscry.config.AppPaths
import infoscry.config.PrivatePermissions
import infoscry.domain.CollectionId
import infoscry.domain.Document
import infoscry.domain.DocumentId
import infoscry.domain.DocumentStatus
import infoscry.storage.DocumentStore
import infoscry.storage.DuplicateDocumentException
import infoscry.storage.Instants
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.io.IOException

/** Whether an import stored new bytes or matched a document the collection already holds. */
enum class ManagedImportOutcome {
    CREATED,
    DUPLICATE,
}

/**
 * The result of managing one source file.
 *
 * [managedPath] is the managed copy inside the data directory, not the user's source file; the
 * source path is kept as metadata on [Document.sourcePath]. Callers that display a citation open
 * the managed copy, which is why it survives the source being moved or deleted.
 */
data class ManagedImport(
    val outcome: ManagedImportOutcome,
    val document: Document,
    val managedPath: Path,
)

/**
 * The immutable managed library: InfoScry's own copy of every imported source file.
 *
 * The contract this class exists for:
 *
 * - the source file is only ever read, never written or deleted;
 * - the same bytes are stored once per collection, and twice across collections;
 * - a document directory appears only after the bytes are fully copied, so a failure or a crash
 *   never leaves a half-written original or a row pointing at one;
 * - the copy is streamed once while being hashed, so an import costs one read pass and bounded
 *   memory regardless of file size.
 *
 * The unique `(collection_id, sha256)` index is the authority on duplicates: the lookup before
 * copying avoids the work in the common case, and a losing race is resolved by the index and by
 * removing the directory this call created.
 *
 * The document is left in [DocumentStatus.COPYING]: the bytes are durable but extraction has not
 * started, and the import job owns the transition out of that state.
 */
class ManagedLibrary(
    private val paths: AppPaths,
    private val documents: DocumentStore,
) {

    /**
     * Copies [source] into the managed library for [collectionId].
     *
     * **Crash vs. failure invariant:** the caller may assume that a `QUEUED` or `COPYING` document
     * whose managed original is absent on disk represents a partially completed import that the
     * import job (Task 8) should resume by re-copying the file. That case arises from a process
     * crash between committing the database row and moving the file into place, and is durable state
     * this class cannot fix. An ordinary exception, by contrast, never leaves such a ghost: the
     * compensating actions below ensure the database row and the on-disk file are always removed.
     */
    fun importFile(collectionId: CollectionId, source: Path): ManagedImport {
        val canonicalSource = source.toAbsolutePath().normalize()
        if (!Files.exists(canonicalSource)) {
            throw NoSuchFileException(canonicalSource.toString())
        }
        require(Files.isRegularFile(canonicalSource)) {
            "only regular files can be imported, but $canonicalSource is not one"
        }

        val temporary = Files.createTempFile(
            paths.tempDir,
            "import-",
            ".part",
            *PrivatePermissions.fileAttributes(),
        )
        try {
            val copied = copyWhileHashing(canonicalSource, temporary)
            return publish(collectionId, canonicalSource, temporary, copied)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun publish(
        collectionId: CollectionId,
        source: Path,
        temporary: Path,
        copied: CopiedBytes,
    ): ManagedImport {
        documents.findBySha256(collectionId, copied.sha256)?.let { existing ->
            return ManagedImport(ManagedImportOutcome.DUPLICATE, existing, managedPathOf(existing))
        }

        val documentId = DocumentId.new()
        val extension = extensionOf(source.fileName.toString())
        val managedPath = paths.originalFile(collectionId, documentId, extension)
        val now = Instants.now()
        val document = Document(
            id = documentId,
            collectionId = collectionId,
            sha256 = copied.sha256,
            mediaType = Files.probeContentType(source) ?: DEFAULT_MEDIA_TYPE,
            originalFilename = source.fileName.toString(),
            sourcePath = source.toString(),
            sizeBytes = copied.sizeBytes,
            status = DocumentStatus.COPYING,
            createdAt = now,
            updatedAt = now,
        )

        paths.createDocumentDirectories(collectionId, documentId)
        try {
            moveInto(temporary, managedPath)
            documents.insert(document)
        } catch (duplicate: DuplicateDocumentException) {
            discardDocumentDirectory(collectionId, documentId)
            val existing = documents.findBySha256(collectionId, copied.sha256) ?: throw duplicate
            return ManagedImport(ManagedImportOutcome.DUPLICATE, existing, managedPathOf(existing))
        } catch (failure: Throwable) {
            // Compensating action: the move may have succeeded but the row insert failed (or the
            // move itself failed). Remove the document row if it was created and discard the
            // managed directory so a failed import leaves neither a row nor a file.
            documents.delete(documentId)
            discardDocumentDirectory(collectionId, documentId)
            throw failure
        }
        return ManagedImport(ManagedImportOutcome.CREATED, document, managedPath)
    }

    /**
     * The managed copy a stored document has, derived from the identifiers and its stored filename.
     *
     * Public because a reader needs it as much as an importer does: the source viewer opens the managed
     * copy at a citation, and the import path uses it to find the bytes of a document it is resuming.
     */
    fun managedPathOf(document: Document): Path = paths.originalFile(
        document.collectionId,
        document.id,
        extensionOf(document.originalFilename),
    )

    private fun copyWhileHashing(source: Path, target: Path): CopiedBytes {
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        Files.newInputStream(source).use { input ->
            Files.newOutputStream(target).use { output ->
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                    output.write(buffer, 0, read)
                    size += read
                }
            }
        }
        return CopiedBytes(sha256 = digest.digest().toHex(), sizeBytes = size)
    }

    /**
     * Moves the finished copy into place. A rename on the same filesystem is atomic, which is what
     * makes the managed original appear complete or not at all. Filesystems that cannot rename
     * atomically still get a move rather than a copy, because the scratch directory and the library
     * are both inside the data directory.
     */
    private fun moveInto(temporary: Path, managedPath: Path) {
        try {
            Files.move(temporary, managedPath, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary, managedPath)
        }
    }

    /**
     * Removes a document directory whose row was never committed, and the collection directory if
     * this import created it and nothing else uses it.
     *
     * Cleanup never replaces the failure that caused it: the row does not exist, so whatever is left
     * behind is unreferenced scratch, while the caller's exception is the one that has to reach them.
     */
    private fun discardDocumentDirectory(collectionId: CollectionId, documentId: DocumentId) {
        val documentDir = paths.documentDir(collectionId, documentId)
        try {
            if (Files.isDirectory(documentDir)) {
                Files.walk(documentDir).use { entries ->
                    entries.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
                }
            }
        } catch (_: IOException) {
            // Unreferenced leftover; the original failure is more useful than this one.
        }
        try {
            Files.deleteIfExists(paths.collectionDir(collectionId))
        } catch (_: DirectoryNotEmptyException) {
            // Another document already lives in this collection.
        }
    }

    private fun extensionOf(filename: String): String {
        val extension = filename.substringAfterLast('.', missingDelimiterValue = "")
            .lowercase()
            .filter { it.isLetterOrDigit() }
        return if (extension.isEmpty() || extension.length > MAX_EXTENSION_LENGTH) {
            DEFAULT_EXTENSION
        } else {
            extension
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private data class CopiedBytes(val sha256: String, val sizeBytes: Long)

    private companion object {
        const val COPY_BUFFER_BYTES = 64 * 1024
        const val MAX_EXTENSION_LENGTH = 16

        /** Used when neither the platform nor the extension tells us what the bytes are. */
        const val DEFAULT_MEDIA_TYPE = "application/octet-stream"

        /** Used when the source filename carries no usable extension. */
        const val DEFAULT_EXTENSION = "bin"
    }
}
