package infoscry.logging

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxyUtil
import ch.qos.logback.core.encoder.EncoderBase
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.charset.StandardCharsets
import java.time.Instant

/**
 * Writes one JSON object per line: the on-disk form of a log record.
 *
 * The shape is the one the spec names — `timestamp`, `level`, `logger`, `message`, optional
 * `component`/`job`/`document`, optional `exception` — plus a `fields` object for every other
 * structured value. Keeping component, job and document as top-level keys is what lets
 * `infoscry logs --component ingest --job <id>` filter without an operator having to know the shape.
 *
 * Values are redacted by name here as well as in [SensitiveDataTurboFilter]: the filter can rewrite
 * the MDC, but a named argument (`addKeyValue`) only exists in the event, so the encoder is the last
 * place it can be stopped.
 */
class JsonLogEncoder : EncoderBase<ILoggingEvent>() {

    /** No header: every line is a complete record, so a reader can start anywhere. */
    override fun headerBytes(): ByteArray? = null

    override fun footerBytes(): ByteArray? = null

    override fun encode(event: ILoggingEvent): ByteArray {
        val component = event.mdcPropertyMap[LogFields.COMPONENT]
        val job = event.mdcPropertyMap[LogFields.JOB]
        val document = event.mdcPropertyMap[LogFields.DOCUMENT]

        val json = buildJsonObject {
            put("timestamp", Instant.ofEpochMilli(event.timeStamp).toString())
            put("level", event.level.levelStr)
            put("logger", event.loggerName)
            component?.let { put(LogFields.COMPONENT, SensitiveFields.redact(LogFields.COMPONENT, it)) }
            job?.let { put(LogFields.JOB, SensitiveFields.redact(LogFields.JOB, it)) }
            document?.let { put(LogFields.DOCUMENT, SensitiveFields.redact(LogFields.DOCUMENT, it)) }
            put("message", event.formattedMessage)

            val fields = structuredFields(event)
            if (fields.isNotEmpty()) {
                put("fields", buildJsonObject { fields.forEach { (name, value) -> put(name, value) } })
            }

            event.throwableProxy?.let { put("exception", ThrowableProxyUtil.asString(it)) }
        }

        return (json.toString() + "\n").toByteArray(StandardCharsets.UTF_8)
    }

    /**
     * Every structured value other than the three top-level ones, redacted by name. The MDC comes
     * first so a named argument with the same name cannot be used to smuggle a value past the filter's
     * redaction of the MDC entry.
     */
    private fun structuredFields(event: ILoggingEvent): Map<String, String> {
        val fields = linkedMapOf<String, String>()
        event.mdcPropertyMap.forEach { (name, value) ->
            if (name !in TOP_LEVEL_FIELDS) {
                fields[name] = SensitiveFields.redact(name, value)
            }
        }
        event.keyValuePairs?.forEach { pair ->
            val name = pair.key
            if (name != null && name !in TOP_LEVEL_FIELDS) {
                fields[name] = SensitiveFields.redact(name, pair.value?.toString() ?: "")
            }
        }
        return fields
    }

    private companion object {
        val TOP_LEVEL_FIELDS = setOf(LogFields.COMPONENT, LogFields.JOB, LogFields.DOCUMENT)
    }
}
