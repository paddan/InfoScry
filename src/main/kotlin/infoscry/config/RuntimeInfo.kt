package infoscry.config

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.SecureRandom
import java.util.Base64

/**
 * The per-launch bearer token that lets a local CLI use a running server's API.
 *
 * The token is a credential, so it lives in a type that cannot be printed: [toString] returns
 * [REDACTED] instead of the token. A plain `String` would leak through any `"$value"` in a log
 * message, an exception, or a generated `toString()` — and the name-based log redaction cannot help
 * there, because it only sees fields the caller bothered to name.
 */
@JvmInline
@Serializable
value class BearerToken(val value: String) {

    init {
        require(value.isNotBlank()) { "a bearer token must not be blank" }
    }

    /** Never the token itself: this is what a log line, an exception, or a debugger shows. */
    override fun toString(): String = REDACTED

    companion object {

        /** 256 bits, which is the size that makes guessing it pointless. */
        const val BYTES = 32

        /** The text that stands in for a token. Kept identical to the log placeholder. */
        const val REDACTED = "[REDACTED]"

        private val random = SecureRandom()

        /** A fresh token: URL-safe, unpadded, 256 bits from the platform's random source. */
        fun new(): BearerToken {
            val bytes = ByteArray(BYTES)
            random.nextBytes(bytes)
            return BearerToken(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes))
        }
    }
}

/**
 * How a CLI process finds and authenticates to a running InfoScry server.
 *
 * The file exists only while the server runs. It is private (`0600`) and carries a per-launch
 * [BearerToken], so a second local user cannot use it, and the token never has to be configured by
 * hand.
 *
 * [discover] deliberately validates liveness instead of trusting the file: a killed server leaves the
 * file behind, and handing a stale port and token to the CLI would produce confusing connection
 * failures. A file whose pid no longer exists is reported as "no server", never as a server.
 */
@Serializable
data class RuntimeInfo(
    val pid: Long,
    val port: Int,
    val bearerToken: BearerToken,
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
        try {
            openTemporaryForWrite(temporary).use { channel ->
                val bytes = ByteBuffer.wrap(json.encodeToString(this).toByteArray(Charsets.UTF_8))
                while (bytes.hasRemaining()) channel.write(bytes)
            }
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (failure: Throwable) {
            // The temporary file holds the token, so a failed write may not leave it behind where
            // another local user could read it. Losing this error to the cleanup would hide the cause.
            runCatching { Files.deleteIfExists(temporary) }
            throw failure
        }
        PrivatePermissions.hardenFile(file)
    }

    companion object {

        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Opens the token-bearing temporary file for writing with the private mode applied from the
         * moment of creation.
         *
         * The creation attributes only cover a file this call creates, so an existing leftover — one
         * a killed process left behind, already holding a token — is hardened as well before anything
         * is written into it.
         */
        internal fun openTemporaryForWrite(path: Path): FileChannel {
            val channel = FileChannel.open(
                path,
                setOf(
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                ),
                *PrivatePermissions.fileAttributes(),
            )
            PrivatePermissions.hardenFile(path)
            return channel
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
            // The token cannot be blank: [BearerToken] refuses to exist without one, so a blank token
            // fails decoding and never reaches here.
            return info.takeIf { it.pid > 0 && it.port in 1..MAX_PORT }
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
