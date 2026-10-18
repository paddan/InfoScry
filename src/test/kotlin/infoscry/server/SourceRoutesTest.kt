package infoscry.server

import infoscry.domain.Document
import infoscry.ocr.BlockDraft
import infoscry.ocr.BlockKind
import infoscry.ocr.CellDraft
import infoscry.ocr.PageStructureDraft
import infoscry.ocr.PageStructureStore
import infoscry.ocr.StructureDerivation
import infoscry.storage.RevisionPageDraft
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*

class SourceRoutesTest {
    private lateinit var root: Path
    private lateinit var server: ApiTestServer
    private lateinit var fixture: PageReviewFixture

    @BeforeTest fun setUp() {
        root = Files.createTempDirectory("infoscry-source-routes")
        server = ApiTestServer(root)
        fixture = PageReviewFixture(server.context, root)
    }
    @AfterTest fun tearDown() { server.close(); root.toFile().deleteRecursively() }

    @Test fun `structureIsServedForWindow`() = runBlocking {
        val document = fixture.pdf()
        fixture.publish(document, listOf(structured(document, 0, layout)))
        val full = source(document)
        val text = full["text"]!!.jsonPrimitive.content
        val blocks = full.structure()
        assertEquals(listOf("HEADING", "TABLE", "PAGE_FOOTER"), blocks.map { it["kind"]!!.jsonPrimitive.content })
        val heading = blocks[0]
        val table = blocks[1]
        assertEquals("Quarterly report", text.substring(heading.int("start"), heading.int("end")))
        assertEquals(1, heading.int("level"))
        val firstRow = table["rows"]!!.jsonArray[0].jsonObject["cells"]!!.jsonArray
        assertTrue(firstRow[0].jsonObject["header"]!!.jsonPrimitive.boolean)
        assertEquals("Ada", text.substring(table.rows(1)[0].int("start"), table.rows(1)[0].int("end")))

        // A window that starts in the heading's last characters and ends inside the table: the heading and the
        // table overlap it, the footer does not, and every range is measured from the window's start.
        val from = heading.int("end") - 4
        val to = table.int("start") + 6
        val window = source(document, "offset=$from&limit=${to - from}")
        assertEquals(text.substring(from, to), window["text"]!!.jsonPrimitive.content)
        val served = window.structure()
        assertEquals(listOf("HEADING", "TABLE"), served.map { it["kind"]!!.jsonPrimitive.content })
        assertEquals(0, served[0].int("start"))
        assertEquals(4, served[0].int("end"))
        assertEquals(heading.int("end") + 1 - from, served[1].int("start"))
        assertEquals(to - from, served[1].int("end"))
        for (cell in served[1].rows(0)) {
            assertTrue(cell.int("start") in 0..(to - from) && cell.int("end") in 0..(to - from))
        }
    }

    @Test fun `noStructureFieldAbsent`() = runBlocking {
        val document = fixture.pdf()
        fixture.publish(document, listOf(fixture.page(0, "plain page")))
        val body = source(document)
        assertEquals("plain page", body["text"]!!.jsonPrimitive.content)
        assertFalse(body.containsKey("structure"), "a page without structure must not carry the field")
    }

    @Test fun `a revision-pinned read serves the structure of that revision`() = runBlocking {
        val document = fixture.pdf()
        val first = fixture.publish(document, listOf(structured(document, 0, layout)))
        fixture.publish(document, listOf(fixture.page(0, "later plain reading")))
        val pinned = source(document, "revision=$first")
        assertEquals(3, pinned.structure().size)
        assertEquals("PAGE_FOOTER", pinned.structure()[2]["kind"]!!.jsonPrimitive.content)
        assertFalse(source(document).containsKey("structure"))
    }

    private val layout = PageStructureDraft(
        parser = "markdown/1",
        blocks = listOf(
            BlockDraft(BlockKind.HEADING, "Quarterly report", level = 1),
            BlockDraft(
                BlockKind.TABLE,
                "",
                rows = listOf(
                    listOf(CellDraft("Name", header = true), CellDraft("Score", header = true)),
                    listOf(CellDraft("Ada", header = false), CellDraft("10", header = false)),
                ),
            ),
            BlockDraft(BlockKind.PAGE_FOOTER, "Page 1 of 3"),
        ),
    )

    private fun structured(document: Document, ordinal: Int, draft: PageStructureDraft): RevisionPageDraft {
        val derived = StructureDerivation.derive(draft, "a".repeat(64))
        val reference = PageStructureStore.write(
            server.context.paths.artifactsDir(document.collectionId, document.id), ordinal, derived.structure,
        )
        return fixture.page(ordinal, derived.extractedText).copy(structureReference = reference)
    }

    private suspend fun source(document: Document, query: String = ""): JsonObject {
        val suffix = if (query.isEmpty()) "" else "?$query"
        val response = server.get(
            "/api/collections/${document.collectionId.value}/sources/${document.id.value}-0$suffix",
        )
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return Json.parseToJsonElement(response.bodyAsText()).jsonObject
    }

    private fun JsonObject.structure(): List<JsonObject> =
        this["structure"]!!.jsonObject["blocks"]!!.jsonArray.map { it.jsonObject }

    private fun JsonObject.int(key: String): Int = this[key]!!.jsonPrimitive.int

    private fun JsonObject.rows(index: Int): List<JsonObject> =
        this["rows"]!!.jsonArray[index].jsonObject["cells"]!!.jsonArray.map { it.jsonObject }
}
