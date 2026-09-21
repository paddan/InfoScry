package infoscry.config

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.SecureRandom
import java.util.Base64

/**
 * How a CLI process finds and authenticates to a running InfoScry server.
 *
 * The file exists only while the server runs. It is private (`0600`) and contains a per-launch bearer
 * token, so a second local user cannot use it, and the token never has to be configured by hand.
 *
 * [discover] deliberately validates liveness instead of trusting the file: a killed server leaves the
 * file behind, and handing a stale port and token to the CLI would produce confusing connection
 * failures. A file whose pid no longer exists is reported as "no server", never as a server.
 */
@Serializable
data class RuntimeInfo(
    val pid: Long,
    val port: Int,
    val bearerToken: String,
) {

    /** Whether the process that wrote this information is still running. */
    val isLive: Boolean
        get() {
            val handle = ProcessHandle.of(pid)
            return handle.isPresent && handle.get().isAlive
        }

    /**
     * Writes the information atomically and privately, so a reader never sees a partial file and no
     * other user can read the token.
     */
    fun writeTo(file: Path) {
        val temporary = file.resolveSibling("${file.fileName}.tmp")
        Files.writeString(
            temporary,
            json.encodeToString(this),
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING,
        )
        try {
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING)
        }
        PrivatePermissions.hardenFile(file)
    }

    companion object {

        /** The token is 256 bits, which is the size that makes guessing it pointless. */
        const val TOKEN_BYTES = 32

        private val json = Json { ignoreUnknownKeys = true }

        private val random = SecureRandom()

        /** A fresh bearer token: URL-safe, unpadded, 256 bits from the platform's random source. */
        fun newToken(): String {
            val bytes = ByteArray(TOKEN_BYTES)
            random.nextBytes(bytes)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }

        /** The information in [file], or `null` when there is none or it is unusable. */
        fun read(file: Path): RuntimeInfo? {
            if (!Files.isRegularFile(file)) return null
            val text = try {
                Files.readString(file)
            } catch (_: java.io.IOException) {
                return null
            }
            val info = try {
                json.decodeFromString<RuntimeInfo>(text)
            } catch (_: IllegalArgumentException) {
                return null
            }
            return info.takeIf { it.pid > 0 && it.port in 1..MAX_PORT && it.bearerToken.isNotBlank() }
        }

        /** The information in [file] only when the process it names is still running. */
        fun discover(file: Path): RuntimeInfo? = read(file)?.takeIf { it.isLive }

        /** Removes the file. Absent is the same as removed: the server may not have written one yet. */
        fun delete(file: Path) {
            runCatching { Files.deleteIfExists(file) }
        }

        private const val MAX_PORT = 65_535
    }
}
