package infoscry.llm

import kotlin.test.Test
import kotlin.test.assertEquals

/** The incremental SSE scanner: splicing, line endings, comments and multi-line data. */
class SseTest {

    @Test
    fun `a single event split one character at a time reassembles`() {
        val scanner = SseScanner()
        val events = mutableListOf<SseEvent>()
        for (ch in "data: hello\n\n") {
            events.addAll(scanner.feed(ch.toString()))
        }
        events.addAll(scanner.finish())
        assertEquals(listOf(SseEvent(type = null, data = "hello")), events)
    }

    @Test
    fun `multi-line data joins the lines with newlines`() {
        val events = SseScanner.scanAll("data: first\ndata: second\n\n")
        assertEquals(listOf(SseEvent(type = null, data = "first\nsecond")), events)
    }

    @Test
    fun `comment lines and blank keep-alives are ignored`() {
        val events = SseScanner.scanAll(": heartbeat\n\n: another\n\ndata: x\n\n")
        assertEquals(listOf(SseEvent(type = null, data = "x")), events)
    }

    @Test
    fun `crlf line endings are accepted`() {
        val events = SseScanner.scanAll("data: a\r\ndata: b\r\n\r\n")
        assertEquals(listOf(SseEvent(type = null, data = "a\nb")), events)
    }

    @Test
    fun `two events in one chunk dispatch as two`() {
        val events = SseScanner.scanAll("data: first\n\ndata: second\n\n")
        assertEquals(
            listOf(
                SseEvent(type = null, data = "first"),
                SseEvent(type = null, data = "second"),
            ),
            events,
        )
    }

    @Test
    fun `the event type field is preserved`() {
        val events = SseScanner.scanAll("event: message\ndata: payload\n\n")
        assertEquals(listOf(SseEvent(type = "message", data = "payload")), events)
    }

    @Test
    fun `finish flushes a trailing event that has no terminating blank line`() {
        val scanner = SseScanner()
        assertEquals(emptyList<SseEvent>(), scanner.feed("data: trailing"))
        assertEquals(listOf(SseEvent(type = null, data = "trailing")), scanner.finish())
        assertEquals(emptyList<SseEvent>(), scanner.finish(), "a second finish must be empty")
    }

    @Test
    fun `feed drains only complete lines and keeps the partial line buffered`() {
        val scanner = SseScanner()
        assertEquals(emptyList<SseEvent>(), scanner.feed("data: par"))
        assertEquals(listOf(SseEvent(type = null, data = "partial")), scanner.feed("tial\n\n"))
        assertEquals(emptyList<SseEvent>(), scanner.finish())
    }
}