package infoscry.server

import infoscry.ask.RetrievalSnapshot
import infoscry.domain.CollectionId
import infoscry.domain.ContentUnitId
import infoscry.domain.Document
import infoscry.domain.DocumentId
import infoscry.domain.DocumentStatus
import infoscry.domain.ExtractionMethod
import infoscry.domain.SourceLocation
import infoscry.extract.ContentUnitDraft
import infoscry.extract.ExtractionFingerprint
import infoscry.extract.ExtractionSettings
import infoscry.llm.LlmProfile
import infoscry.llm.LlmProvider
import infoscry.search.vectorFor
import infoscry.storage.Instants
import infoscry.storage.PageApproval
import infoscry.storage.RevisionChunkDraft
import infoscry.storage.RevisionPageDraft
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import java.nio.file.Files
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Investigate history reads the evidence ledger back the way Ask history reads its citations: each entry
 * with the excerpt it saved and the revision it was read from, never rewritten to the text a document
 * publishes now and never dropped because a replacement removed its unit.
 */
class InvestigationEvidenceHistoryTest {

    @Test
    fun `saved Investigate evidence keeps its excerpt and its revision and never shows current text as old evidence`() = runBlocking {
        val dataDir = Files.createTempDirectory("infoscry-investigate-evidence-provenance")
        try {
            ApiTestServer(dataDir).use { harness ->
                val profile = LlmProfile(
                    id = UUID.randomUUID().toString(),
                    name = "evidence-investigator",
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
                val collection = harness.context.collectionService.create("Default")

                val (replacedDocument, replacedUnit) = seedDocument(harness, collection.id, "replaced-notes.txt", "the page as it was published")
                val (keptDocument, keptUnit) = seedDocument(harness, collection.id, "kept-notes.txt", "the kept live page text")
                val recordedRevision =
                    assertNotNull(harness.context.revisions.recordPublishedContent(replacedDocument, "TEST_IMPORT"))

                // A real publication that names a page of its own removes the unit the ledger holds.
                val replacementText = "the page as it was replaced"
                val candidate = harness.context.revisions.openCandidate(replacedDocument, recordedRevision, "TEST_REPLACEMENT")
                harness.context.revisions.appendPage(
                    candidate,
                    RevisionPageDraft(
                        ordinal = 0,
                        unitId = ContentUnitId.new(),
                        locator = SourceLocation.TextLines(1, 1),
                        extractedText = replacementText,
                        searchText = replacementText,
                        extractionMethod = ExtractionMethod.OCR,
                        approval = PageApproval.APPROVED,
                        chunks = listOf(
                            RevisionChunkDraft(
                                ordinal = 0,
                                text = replacementText,
                                startOffset = 0,
                                endOffset = replacementText.length,
                                tokenCount = 5,
                                tokenStart = 0,
                                tokenEnd = 4,
                                embedding = vectorFor(replacementText),
                            ),
                        ),
                    ),
                )
                harness.context.revisionPublication.publish(replacedDocument, recordedRevision, candidate)
                assertEquals(null, harness.context.content.readUnit(replacedUnit), "the replacement has to remove the cited unit")

                val conversationId = harness.context.llm.persistInvestigateConversation(
                    collectionId = collection.id,
                    profile = profile,
                    promptVersion = 1,
                    retrievalSnapshot = RetrievalSnapshot.value(),
                )
                harness.context.llm.persistInvestigateMessage(conversationId, 0, "user", "What did the page say?")
                val locator = SourceLocation.TextLines(1, 1)
                fun ledger(id: String, unit: ContentUnitId, excerpt: String, revision: String?) =
                    harness.context.llm.persistEvidenceLedgerEntry(
                        conversationId, id, unit.value, ApiJson.encodeToString(SourceLocation.serializer(), locator),
                        excerpt, messageSeq = 1, revisionId = revision,
                    )
                ledger("S1", replacedUnit, "the excerpt S1 saved", recordedRevision)
                ledger("S2", keptUnit, "the excerpt S2 saved", null)
                ledger("S3", replacedUnit, "the excerpt S3 saved", null)

                val response = harness.request(
                    HttpMethod.Get,
                    "/api/collections/${collection.id.value}/investigations/$conversationId",
                    credential = Credential.BEARER,
                )
                assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
                val body = response.bodyAsText()
                val evidence = ApiJson.parseToJsonElement(body).jsonObject.getValue("investigation").jsonObject
                    .getValue("evidence").jsonArray.map { it.jsonObject }
                    .associateBy { it.getValue("id").jsonPrimitive.content }

                assertEquals(setOf("S1", "S2", "S3"), evidence.keys, "evidence whose unit a replacement removed is not dropped: $body")
                assertEquals("the excerpt S1 saved", evidence.getValue("S1").getValue("excerpt").jsonPrimitive.content)
                assertEquals("the excerpt S2 saved", evidence.getValue("S2").getValue("excerpt").jsonPrimitive.content)
                assertEquals("the excerpt S3 saved", evidence.getValue("S3").getValue("excerpt").jsonPrimitive.content)
                assertEquals(recordedRevision, evidence.getValue("S1").getValue("revisionId").jsonPrimitive.content)
                assertFalse(evidence.getValue("S2").containsKey("revisionId"), "no recorded revision means revision unknown")
                assertFalse(evidence.getValue("S3").containsKey("revisionId"), "a removed unit with no recorded revision is revision unknown")
                assertEquals(replacedDocument.value, evidence.getValue("S1").getValue("documentId").jsonPrimitive.content)
                assertEquals(
                    replacedDocument.value,
                    evidence.getValue("S3").getValue("documentId").jsonPrimitive.content,
                    "the revision that once held the unit places evidence the replacement removed",
                )
                assertEquals(keptDocument.value, evidence.getValue("S2").getValue("documentId").jsonPrimitive.content)
                assertFalse(body.contains("the kept live page text"), "current text must not render as old evidence: $body")
                assertFalse(body.contains(replacementText), "the replacing text must not render as old evidence: $body")
                assertNull(evidence.getValue("S2")["text"])
            }
        } finally {
            dataDir.toFile().deleteRecursively()
        }
    }

    private fun seedDocument(
        harness: ApiTestServer,
        collectionId: CollectionId,
        filename: String,
        text: String,
    ): Pair<DocumentId, ContentUnitId> {
        val documentId = DocumentId.new()
        val now = Instants.now()
        harness.context.documents.insert(
            Document(
                id = documentId,
                collectionId = collectionId,
                sha256 = "sha-$filename",
                mediaType = "text/plain",
                originalFilename = filename,
                sourcePath = "tmp/original/$filename",
                sizeBytes = 1L,
                status = DocumentStatus.COMPLETE,
                title = filename,
                author = null,
                language = null,
                createdAt = now,
                updatedAt = now,
            ),
        )
        val unit = harness.context.content.commitExtractedUnit(
            documentId = documentId,
            fingerprint = ExtractionFingerprint.of(
                "sha-$filename",
                ExtractionSettings(ocrLanguages = "eng", extractorSchemaVersion = "investigate-evidence-test"),
            ),
            key = "unit-0",
            ordinal = 0,
            draft = ContentUnitDraft(
                locator = SourceLocation.TextLines(1, 1),
                extractedText = text,
                searchText = text,
                method = ExtractionMethod.DIRECT_TEXT,
            ),
            artifactRoot = harness.context.paths.libraryDir,
        ).unit
        return documentId to unit.id
    }
}
