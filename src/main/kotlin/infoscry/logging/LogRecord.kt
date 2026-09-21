package infoscry.logging

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeParseException

/** The severities InfoScry records, in the order a `--level` filter compares them. */
enum class LogLevel(val rank: Int) {
    TRACE(0),
    DEBUG(1),
    INFO(2),
    WARN(3),
    ERROR(4);

    companion object {
        /** The level named by [text], case-insensitively, or `null` when the name is unknown. */
        fun parse(text: String): LogLevel? =
            entries.firstOrNull { it.name.equals(text.trim(), ignoreCase = true) }
    }
}

/**
 * One structured log record, as it is written to disk and read back.
 *
 * The record is deliberately a plain value: the CLI, the web log panel, and tests all read the same
 * shape, and nothing about the on-disk form depends on Logback being the writer.
 */
data class LogRecord(
    val timestamp: Instant,
    val level: LogLevel,
    val logger: String,
    val message: String,
    val component: String? = null,
    val job: String? = null,
    val document: String? = null,
    val fields: Map<String, String> = emptyMap(),
    val exception: String? = null,
) {

    /** The terminal form: readable at a glance, without the JSON punctuation. */
    fun render(): String = buildString {
        append(timestamp)
        append(' ')
        append(level.name.padEnd(5))
        append(' ')
        append(component ?: logger)
        job?.let { append(" job=").append(it) }
        document?.let { append(" document=").append(it) }
        append(" - ")
        append(message)
        exception?.let { append(System.lineSeparator()).append(it) }
    }

    companion object {

        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Parses one line of the JSON log file, or `null` when the line is not a complete record.
         *
         * A reader must tolerate partial lines: the active file is being appended to while
         * `infoscry logs` reads it, and a crash can leave a half-written line behind.
         */
        fun parse(line: String): LogRecord? {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return null
            val element = try {
                json.parseToJsonElement(trimmed)
            } catch (_: IllegalArgumentException) {
                return null
            }
            val obj = element as? JsonObject ?: return null
            val timestamp = obj.stringOrNull("timestamp")?.let { text ->
                try {
                    Instant.parse(text)
                } catch (_: DateTimeParseException) {
                    null
                }
            } ?: return null
            val level = obj.stringOrNull("level")?.let(LogLevel::parse) ?: return null
            val logger = obj.stringOrNull("logger") ?: return null
            val message = obj.stringOrNull("message") ?: return null

            return LogRecord(
                timestamp = timestamp,
                level = level,
                logger = logger,
                message = message,
                component = obj.stringOrNull(LogFields.COMPONENT),
                job = obj.stringOrNull(LogFields.JOB),
                document = obj.stringOrNull(LogFields.DOCUMENT),
                fields = (obj["fields"] as? JsonObject).orEmptyFields(),
                exception = obj.stringOrNull("exception"),
            )
        }

        private fun JsonObject.stringOrNull(name: String): String? =
            (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

        private fun JsonObject?.orEmptyFields(): Map<String, String> =
            this?.entries?.mapNotNull { (name, value) ->
                (value as? JsonPrimitive)?.let { name to it.content }
            }?.toMap().orEmpty()
    }
}

/**
 * The selection `infoscry logs` applies to the record stream.
 *
 * The age window is compared against a caller-supplied `now`, so a test can pin time and the CLI can
 * use the wall clock without the filter owning a clock.
 */
data class LogFilter(
    val minimumLevel: LogLevel = LogLevel.INFO,
    val job: String? = null,
    val component: String? = null,
    val since: Duration? = null,
) {

    fun accepts(record: LogRecord, now: Instant): Boolean {
        if (record.level.rank < minimumLevel.rank) return false
        if (job != null && record.job != job) return false
        if (component != null && record.component != component) return false
        if (since != null && record.timestamp.isBefore(now.minus(since))) return false
        return true
    }
}
