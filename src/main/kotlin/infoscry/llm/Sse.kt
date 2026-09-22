package infoscry.llm

/**
 * One complete SSE event: an optional type from an `event:` field and the data payload. The payload is
 * the join of all `data:` lines with newlines, exactly as the SSE spec defines. A keep-alive comment
 * line produces no event; an empty `data:` line contributes an empty line to the payload.
 */
data class SseEvent(val type: String?, val data: String)

/**
 * An incremental Server-Sent Events scanner.
 *
 * Provider streams arrive as chunks, and a single event can be split across arbitrarily many of them.
 * [feed] appends a chunk and returns every event that became complete as a result; [finish] flushes a
 * trailing unterminated event. Both `\n` and `\r\n` line endings are accepted. Comment lines (starting
 * with `:`) and unknown fields (`id`, `retry`, ...) are ignored; `event:` sets the event type and
 * `data:` appends to the payload.
 */
class SseScanner {

    private val buffer = StringBuilder()
    private val dataLines = ArrayList<String>()
    private var eventType: String? = null

    /** Appends [chunk] and returns the events that became complete from its contents. */
    fun feed(chunk: String): List<SseEvent> {
        buffer.append(chunk)
        val events = ArrayList<SseEvent>()
        var newlineIndex = buffer.indexOf("\n")
        while (newlineIndex >= 0) {
            var line = buffer.substring(0, newlineIndex)
            if (line.endsWith("\r")) line = line.removeSuffix("\r")
            buffer.delete(0, newlineIndex + 1)
            handleLine(line, events)
            newlineIndex = buffer.indexOf("\n")
        }
        return events
    }

    /** Flushes a trailing line that has no terminating newline and returns the final event, if any. */
    fun finish(): List<SseEvent> {
        val events = ArrayList<SseEvent>()
        if (buffer.isNotEmpty()) {
            var line = buffer.toString()
            if (line.endsWith("\r")) line = line.removeSuffix("\r")
            buffer.setLength(0)
            handleLine(line, events)
        }
        dispatchPending(events)
        return events
    }

    /** Scans a complete SSE body in one call. */
    companion object {
        fun scanAll(body: String): List<SseEvent> {
            val scanner = SseScanner()
            val events = ArrayList<SseEvent>()
            events.addAll(scanner.feed(body))
            events.addAll(scanner.finish())
            return events
        }
    }

    private fun handleLine(line: String, events: MutableList<SseEvent>) {
        if (line.isEmpty()) {
            dispatchPending(events)
            return
        }
        if (line.startsWith(":")) return // comment / keep-alive

        val colon = line.indexOf(':')
        val field = if (colon < 0) line else line.substring(0, colon)
        val value = if (colon < 0) { "" } else {
            val raw = line.substring(colon + 1)
            if (raw.startsWith(" ")) raw.substring(1) else raw
        }
        if (field == "data") {
            dataLines.add(value)
        } else if (field == "event") {
            eventType = value
        }
        // Every other field (id, retry, unknown) is ignored per the SSE spec.
    }

    private fun dispatchPending(events: MutableList<SseEvent>) {
        if (dataLines.isNotEmpty() || eventType != null) {
            val data = dataLines.joinToString("\n")
            events.add(SseEvent(type = eventType, data = data))
        }
        dataLines.clear()
        eventType = null
    }
}