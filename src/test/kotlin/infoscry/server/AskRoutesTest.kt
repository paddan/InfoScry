package infoscry.server

import infoscry.ask.AskEvent
import infoscry.ask.CitationValidation
import infoscry.ask.Evidence
import infoscry.domain.Document
import infoscry.domain.DocumentId
import infoscry.domain.DocumentStatus
import infoscry.domain.SourceLocation
import infoscry.extract.ContentUnitDraft
import infoscry.extract.ExtractionFingerprint
import infoscry.extract.ExtractionSettings
import infoscry.llm.LlmProfile
import infoscry.llm.LlmProvider
import infoscry.llm.TokenUsage
import infoscry.storage.Instants
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import java.nio.file.Files
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.runBlocking

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

    @Test
    fun `stored Ask history lists its questions with their evidence and stays inside its collection`() = runBlocking {
        val dataDir = Files.createTempDirectory("infoscry-ask-history")
        try {
            ApiTestServer(dataDir).use { harness ->
                val profile = LlmProfile(
                    id = UUID.randomUUID().toString(),
                    name = "asker",
                    provider = LlmProvider.OPENAI_COMPATIBLE,
                    model = "model",
                    contextWindow = 10_000,
                    maxOutputTokens = 64,
                    inputPricePerMillion = 1.0,
                    outputPricePerMillion = 2.0,
                    cacheReadPricePerMillion = 0.0,
                    enabled = true,
                    endpoint = "https://provider.invalid/v1",
                )
                harness.context.llm.create(profile)
                val collection = harness.context.collectionService.requireActiveByNameOrId("Default")
                val other = harness.context.collectionService.create("Other")

                val documentId = DocumentId.new()
                val locator = SourceLocation.TextLines(4, 4)
                val now = Instants.now()
                harness.context.documents.insert(
                    Document(
                        id = documentId,
                        collectionId = collection.id,
                        sha256 = "sha-ask-history",
                        mediaType = "text/plain",
                        originalFilename = "notes.txt",
                        sourcePath = "tmp/original/notes.txt",
                        sizeBytes = 1L,
                        status = DocumentStatus.COMPLETE,
                        title = "notes",
                        author = null,
                        language = null,
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
                val unit = harness.context.content.commitExtractedUnit(
                    documentId = documentId,
                    fingerprint = ExtractionFingerprint.of(
                        "sha-ask-history",
                        ExtractionSettings(ocrLanguages = "eng", extractorSchemaVersion = "ask-history-test"),
                    ),
                    key = "unit-0",
                    ordinal = 0,
                    draft = ContentUnitDraft(locator = locator, extractedText = "Mira signed it.", searchText = "Mira signed it."),
                    artifactRoot = harness.context.paths.libraryDir,
                ).unit
                harness.context.llm.persistAsk(
                    collectionId = collection.id,
                    profile = profile,
                    question = "Who signed it?",
                    answer = "Mira signed it [S1].",
                    evidence = listOf(
                        Evidence(
                            id = "S1",
                            collectionId = collection.id.value,
                            documentId = documentId.value,
                            unitId = unit.id.value,
                            locator = locator,
                            locatorLabel = locator.describe(),
                            text = "Mira signed it.",
                        ),
                    ),
                    initialUsage = TokenUsage(inputTokens = 11, outputTokens = 3, cacheReadTokens = 0),
                    initialCitations = CitationValidation(valid = listOf("S1"), invalid = emptyList()),
                )

                val response = harness.request(
                    HttpMethod.Get,
                    "/api/collections/${collection.id.value}/asks",
                    credential = Credential.BEARER,
                )
                assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
                val body = response.bodyAsText()
                assertContains(body, "Who signed it?", message = "the stored question must be listed")
                assertContains(body, "Mira signed it [S1].", message = "the stored answer must be listed")
                assertContains(body, "\"id\":\"S1\"", message = "the answer's evidence must come back with it")
                assertContains(body, documentId.value, message = "the evidence must name the document a citation opens")
                assertContains(body, "\"inputTokens\":11")
                assertFalse(body.contains(profile.endpoint), "history must not expose provider endpoints")
                assertFalse(body.contains("artifact_relative_path"), "history must not expose artifact paths")

                val otherList = harness.request(
                    HttpMethod.Get,
                    "/api/collections/${other.id.value}/asks",
                    credential = Credential.BEARER,
                )
                assertEquals(HttpStatusCode.OK, otherList.status, otherList.bodyAsText())
                assertFalse(
                    otherList.bodyAsText().contains("Who signed it?"),
                    "one collection's questions must not appear in another collection's history",
                )
            }
        } finally {
            dataDir.toFile().deleteRecursively()
        }
    }
}
