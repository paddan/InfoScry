package infoscry.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import infoscry.config.AppPaths
import infoscry.logging.LogFilter
import infoscry.logging.LogLevel
import infoscry.logging.LogReader
import infoscry.logging.LogRecord
import java.nio.file.Path
import java.time.Duration

/**
 * `infoscry logs` — the structured log stream, filtered and rendered for a terminal.
 *
 * The command owns argument parsing and rendering only; selecting and following records is
 * [LogReader]'s job, which is why it can be tested without a terminal. Reading logs never takes the
 * process lock: an operator reads logs while the server is running, which is the whole point.
 */
class LogsCommand : CliktCommand(name = "logs") {

    private val dataDir by option(
        "--data-dir",
        help = "Data directory to read logs from (default: ~/.infoscry)",
    ).path()

    private val follow by option(
        "--follow",
        help = "Keep printing new records, surviving log rotation",
    ).flag()

    private val level by option(
        "--level",
        help = "Minimum level to print: TRACE, DEBUG, INFO, WARN or ERROR (default: INFO)",
    )

    private val job by option("--job", help = "Only records for this job id")

    private val component by option("--component", help = "Only records from this component, e.g. ingest")

    private val since by option("--since", help = "Only records newer than this, e.g. 30m, 12h, 7d")

    override fun run() {
        val root = dataDir ?: defaultDataDir()
        val logsDir = AppPaths.of(root).logsDir
        val filter = LogFilter(
            minimumLevel = level?.let { name ->
                LogLevel.parse(name)
                    ?: throw UsageError("--level must be TRACE, DEBUG, INFO, WARN or ERROR, was '$name'")
            } ?: LogLevel.INFO,
            job = job,
            component = component,
            since = try {
                parseSince(since)
            } catch (invalid: IllegalArgumentException) {
                throw UsageError(invalid.message ?: "invalid --since")
            },
        )

        val reader = LogReader(logsDir)
        val sink: (LogRecord) -> Unit = { record -> echo(record.render()) }
        if (follow) {
            reader.follow(filter, sink)
        } else {
            reader.read(filter).forEach(sink)
        }
    }

    companion object {

        /** The data directory InfoScry uses when none is given. */
        fun defaultDataDir(): Path = Path.of(System.getProperty("user.home"), ".infoscry")

        /**
         * Parses an age window such as `30m`, `12h` or `7d`, or `null` when nothing was given.
         *
         * A value the user meant as a window must never be silently ignored, so anything unparseable
         * is rejected with the accepted forms in the message.
         */
        fun parseSince(text: String?): Duration? {
            val trimmed = text?.trim().orEmpty()
            if (trimmed.isEmpty()) return null
            val unit = trimmed.last()
            val amount = trimmed.dropLast(1).toLongOrNull()
            val multiplier = when (unit) {
                's' -> 1L
                'm' -> 60L
                'h' -> 60L * 60L
                'd' -> 24L * 60L * 60L
                else -> null
            }
            if (amount == null || amount <= 0 || multiplier == null) {
                throw IllegalArgumentException("--since must be a positive window such as 30m, 12h or 7d, was '$text'")
            }
            return Duration.ofSeconds(amount * multiplier)
        }
    }
}
