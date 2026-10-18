package infoscry.storage

import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID

/**
 * The write path every content-addressed artifact store shares: a file whose name carries the SHA-256 of its
 * own bytes is written at most once, through a temporary sibling and an atomic rename, so a repeat is a no-op
 * and a reader never sees a half-written file at a name a reference may already commit to.
 */
internal object ContentAddressedFile {

    /** Lowercase hex SHA-256 of [bytes]. */
    fun sha256Of(bytes: ByteArray): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    /** Writes [bytes] to [target] unless it already holds exactly them; [target]'s name must embed their hash. */
    fun writeOnce(target: Path, bytes: ByteArray, sha256: String) {
        if (alreadyWritten(target, sha256)) return
        Files.createDirectories(checkNotNull(target.parent))
        val temporary = target.resolveSibling("${target.fileName}.${UUID.randomUUID()}.tmp")
        try {
            Files.write(temporary, bytes)
            moveIntoPlace(temporary, target)
        } finally {
            Files.deleteIfExists(temporary)
        }
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
}
