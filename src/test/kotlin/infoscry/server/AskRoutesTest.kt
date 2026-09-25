package infoscry.server

import infoscry.ask.AskEvent
import infoscry.ask.Evidence
import infoscry.domain.SourceLocation
import kotlin.test.Test
import kotlin.test.assertEquals

class AskRoutesTest {
    @Test
    fun `usage event keeps numeric input and output fields`() {
        val json = ApiJson.encodeToString((AskEvent.Usage(11, 13)).toWire())

        assertEquals("{\"type\":\"usage\",\"inputTokens\":11,\"outputTokens\":13}", json)
    }

    @Test
    fun `the SSE sequence keeps the stable delta citation done contract`() {
        val events = listOf(
            AskEvent.Delta("partial"),
            AskEvent.Usage(1, 2),
            AskEvent.Citation("S1", true),
            AskEvent.Citation("S999", false),
            AskEvent.Done("final [S1]", emptyList()),
            AskEvent.Error("ASK_FAILED", "failed"),
        )

        assertEquals(
            listOf("delta", "usage", "citation", "citation", "done", "error"),
            events.map { ApiJson.encodeToString(it.toWire()).substringAfter("\"type\":\"").substringBefore('"') },
        )
    }

    @Test
    fun `the done event carries the evidence wire with its locator and omits the excerpt`() {
        val json = ApiJson.encodeToString(
            AskEvent.Done(
                "final [S1]",
                listOf(
                    Evidence(
                        id = "S1",
                        collectionId = "col-1",
                        documentId = "doc-1",
                        unitId = "unit-7",
                        locator = SourceLocation.WordSection(listOf("Chapter 1"), 2, 4),
                        locatorLabel = "Chapter 1, paragraphs 2-4",
                        text = "excerpt text",
                    ),
                ),
            ).toWire(),
        )

        assertEquals(
            """{"type":"done","text":"final [S1]","evidence":[{"id":"S1","documentId":"doc-1","unitId":"unit-7","locator":{"type":"word_section","headingPath":["Chapter 1"],"paragraphStart":2,"paragraphEnd":4},"locatorLabel":"Chapter 1, paragraphs 2-4"}]}""",
            json,
        )
    }
}
