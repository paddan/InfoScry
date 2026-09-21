package infoscry.logging

import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant

/**
 * Reads the structured log files of one data directory.
 *
 * The reader never takes the process lock: a running server owns the data directory, and reading the
 * log is exactly what an operator needs to do while it runs.
 *
 * Two modes, because they need different guarantees:
 * - [read] answers "show me the records": it reads the active file and every rotated one, oldest file
 *   first, and applies the filter to what it finds.
 * - [follow] answers "show me what happens next": it follows the active file only, remembering how far
 *   it has read. Rotation is detected by the active file becoming shorter than the remembered
 *   position, which is what a fresh file looks like; the renamed file was already printed and is
 *   deliberately not re-read, so no line is ever printed twice.
 */
class LogReader(
    private val logsDir: Path,
    private val now: () -> Instant = Instant::now,
) {

    /** Every record currently in the log files, oldest first, that [filter] accepts. */
    fun read(filter: LogFilter): List<LogRecord> {
        val moment = now()
        return logFiles()
            .flatMap { file -> readFile(file) }
            .filter { filter.accepts(it, moment) }
            .sortedBy { it.timestamp }
    }

    /**
     * Prints existing records and then new ones until [keepGoing] says to stop.
     *
     * [pause] is called with the interval to wait between polls; the CLI sleeps, a test drives the
     * filesystem instead of sleeping, so the rotation behaviour is deterministic without timing.
     */
    fun follow(
        filter: LogFilter,
        sink: (LogRecord) -> Unit,
        pause: (Long) -> Unit = { Thread.sleep(it) },
        keepGoing: () -> Boolean = { true },
    ) {
        val active = logsDir.resolve(ACTIVE_FILE)
        var position = 0L
        while (true) {
            position = printNewRecords(active, position, filter, sink)
            pause(FOLLOW_INTERVAL_MILLIS)
            if (!keepGoing()) return
        }
    }

    /** The log files of this data directory, oldest modified first. */
    private fun logFiles(): List<Path> {
        if (!Files.isDirectory(logsDir)) return emptyList()
        return Files.list(logsDir).use { entries ->
            entries
                .filter { Files.isRegularFile(it) && isLogFile(it.fileName.toString()) }
                .sorted(compareBy({ Files.getLastModifiedTime(it).toMillis() }, { it.fileName.toString() }))
                .toList()
        }
    }

    private fun isLogFile(name: String): Boolean = name.startsWith(FILE_PREFIX) && name.endsWith(FILE_SUFFIX)

    private fun readFile(file: Path): List<LogRecord> =
        try {
            Files.readAllLines(file, StandardCharsets.UTF_8).mapNotNull(LogRecord::parse)
        } catch (_: java.io.IOException) {
            // A file that vanished between listing and reading, or one we may not read, is not an
            // error an operator asked about: the next poll will pick up whatever exists then.
            emptyList()
        }

    /** Reads what is new in [file] after [from] and returns the new position. */
    private fun printNewRecords(
        file: Path,
        from: Long,
        filter: LogFilter,
        sink: (LogRecord) -> Unit,
    ): Long {
        if (!Files.isRegularFile(file)) return 0L
        val size = try {
            Files.size(file)
        } catch (_: java.io.IOException) {
            return 0L
        }
        // A shorter file than we remembered means rotation replaced the active file with an empty one.
        val start = if (size < from) 0L else from
        if (size == start) return start

        val window = minOf(size - start, MAX_READ_BYTES)
        val buffer = ByteArray(window.toInt())
        val read = FileChannel.open(file, StandardOpenOption.READ).use { channel ->
            channel.position(start)
            channel.read(java.nio.ByteBuffer.wrap(buffer))
        }
        if (read <= 0) return start

        val text = String(buffer, 0, read, StandardCharsets.UTF_8)
        val lastNewline = text.lastIndexOf('\n')
        // Nothing complete to consume yet: a writer is mid-line. Re-reading from the same position
        // next poll costs nothing. A window with no newline at all and a full read means a single line
        // larger than the window, so it is consumed as one record rather than blocking the reader.
        val complete = when {
            lastNewline >= 0 -> text.substring(0, lastNewline)
            read.toLong() == MAX_READ_BYTES -> text
            else -> return start
        }
        val consumed = complete.toByteArray(StandardCharsets.UTF_8).size.toLong() + if (lastNewline >= 0) 1 else 0

        val moment = now()
        complete.split('\n')
            .mapNotNull(LogRecord::parse)
            .filter { filter.accepts(it, moment) }
            .forEach(sink)
        return start + consumed
    }

    companion object {
        /** The file a running InfoScry appends to; rotated files take numbered names beside it. */
        const val ACTIVE_FILE = "infoscry.log"

        const val FOLLOW_INTERVAL_MILLIS = 200L

        private const val FILE_PREFIX = "infoscry"
        private const val FILE_SUFFIX = ".log"
        private const val MAX_READ_BYTES = 4L * 1024 * 1024
    }
}
