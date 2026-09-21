package infoscry.config

import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * The single-writer lock on one data directory.
 *
 * InfoScry has one authoritative SQLite database, one Lucene index, and one set of running jobs, so a
 * second process writing to the same directory would corrupt the state the product is built on. The
 * lock is a real file lock rather than a PID file: the operating system releases it even when a
 * process is killed, so a crash cannot leave the archive permanently locked.
 *
 * A second acquisition fails immediately with [ProcessLockUnavailable] instead of waiting: the caller
 * has another route (talk to the running server over its loopback API), and a silent wait would look
 * like a hang.
 */
class ProcessLock private constructor(
    val path: Path,
    private val channel: FileChannel,
    private val lock: FileLock,
) : AutoCloseable {

    override fun close() {
        runCatching { lock.release() }
        runCatching { channel.close() }
    }

    companion object {

        /** Takes the lock at [path], or throws [ProcessLockUnavailable] when another holder has it. */
        fun acquire(path: Path): ProcessLock {
            path.parent?.let { Files.createDirectories(it) }
            val channel = FileChannel.open(
                path,
                setOf(StandardOpenOption.CREATE, StandardOpenOption.WRITE),
                *PrivatePermissions.fileAttributes(),
            )
            val lock = try {
                channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                // The same JVM already holds it: `tryLock` reports that as an exception rather than a
                // null return, and it means the same thing to the caller.
                null
            } catch (failure: java.io.IOException) {
                runCatching { channel.close() }
                throw failure
            }
            if (lock == null) {
                runCatching { channel.close() }
                throw ProcessLockUnavailable(path)
            }
            return ProcessLock(path, channel, lock)
        }
    }
}

/** Raised when another InfoScry process already owns the data directory. */
class ProcessLockUnavailable(val path: Path) : IllegalStateException(
    "Another InfoScry process is already running and holds $path. " +
        "Stop that process before starting a second one, or use its loopback API through the CLI.",
)
