package infoscry.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.turbo.TurboFilter
import ch.qos.logback.core.spi.FilterReply
import org.slf4j.MDC
import org.slf4j.Marker

/**
 * Replaces the value of every sensitive field in the event's MDC before any appender encodes it.
 *
 * The MDC is the only structured channel a [TurboFilter] can both read and rewrite: Logback hands a
 * filter the format string and the positional arguments, not the event, so a named argument added with
 * `addKeyValue` cannot be rewritten here. That is why key-value pairs are redacted by name in
 * [JsonLogEncoder] as well — the filter covers the MDC for every sink, and the encoder covers the
 * fields the filter cannot reach.
 *
 * Redaction happens in the thread's own MDC map, which is what the appenders of this event are about to
 * read, so console and file output see the same placeholder.
 */
class SensitiveDataTurboFilter : TurboFilter() {

    override fun decide(
        marker: Marker?,
        logger: Logger,
        level: Level,
        format: String?,
        params: Array<out Any?>?,
        throwable: Throwable?,
    ): FilterReply {
        val contextMap = MDC.getCopyOfContextMap() ?: return FilterReply.NEUTRAL
        for (name in contextMap.keys) {
            if (SensitiveFields.isSensitive(name)) {
                MDC.put(name, SensitiveFields.PLACEHOLDER)
            }
        }
        return FilterReply.NEUTRAL
    }
}
