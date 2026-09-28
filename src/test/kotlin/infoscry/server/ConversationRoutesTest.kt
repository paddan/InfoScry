package infoscry.server

import infoscry.ask.CitationValidation
import infoscry.ask.Evidence
import infoscry.ask.RetrievalSnapshot
import infoscry.domain.SourceLocation
import infoscry.llm.LlmProfile
import infoscry.llm.LlmProvider
import infoscry.llm.TokenUsage
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import java.nio.file.Files
import java.sql.Connection
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * The conversation delete route over the real HTTP boundary: the 204 success with every cascaded
 * child gone, the two not-found answers, and the request-guard's uncredentialed rejection.
 */
class ConversationRoutesTest {

    /** Every child table the schema cascades from a conversation row, named by their shared key column. */
    private val childTables = listOf(
        "messages",
        "model_calls",
        "citations",
        "tool_calls",
        "evidence_ledger",
        "request_eligibility",
        "request_omissions",
        "limit_events",
    )

    @Test
    fun `deleting a conversation in its collection removes it and every cascaded child`() = runBlocking {
        val dataDir = Files.createTempDirectory("infoscry-delete-conversation")
        try {
            ApiTestServer(dataDir).use { harness ->
                val collection = harness.context.collectionService.create("Default")
                val profile = createProfile(harness, "delete-investigator")

                // An Investigate conversation with every child the schema keeps: messages, a model
                // call, a tool call on it, ledger evidence, request eligibility and omission rows,
                // and a limit event.
                val investigateId = harness.context.llm.persistInvestigateConversation(
                    collectionId = collection.id,
                    profile = profile,
                    promptVersion = 1,
                    retrievalSnapshot = RetrievalSnapshot.value(),
                )
                harness.context.llm.persistInvestigateMessage(investigateId, 0, "user", "Who signed it?")
                harness.context.llm.persistInvestigateMessage(investigateId, 1, "assistant", "Mira signed it.")
                val modelCallId = harness.context.llm.persistInvestigateModelCall(
                    conversationId = investigateId,
                    provider = LlmProvider.OPENAI_COMPATIBLE.name,
                    endpoint = "https://provider.invalid/v1",
                    model = "delete-model",
                    profileName = "delete-investigator",
                    promptVersion = 1,
                    status = "SUCCEEDED",
                    inputTokens = 10,
                    outputTokens = 5,
                    cacheReadTokens = 0,
                    costUsd = 0.0001,
                )
                harness.context.llm.persistInvestigateToolCall(investigateId, modelCallId, "search_collection", "{}", "SUCCESS", 12L)
                harness.context.llm.persistEvidenceLedgerEntry(
                    investigateId, "S1", "unit-seed",
                    """{"type":"text_lines","start":4,"end":4}""", "Mira signed it.",
                )
                harness.context.llm.persistRequestEligibility(modelCallId, investigateId, listOf("S1"))
                harness.context.llm.persistRequestOmissions(modelCallId, investigateId, listOf("BUDGET"))
                harness.context.llm.persistLimitEvent(investigateId, "BUDGET_LIMIT", "the budget was exceeded")

                // A stored Ask answer with its own messages, model call and citation rows.
                val askId = harness.context.llm.persistAsk(
                    collectionId = collection.id,
                    profile = profile,
                    question = "Who signed it?",
                    answer = "Mira signed it [S1].",
                    evidence = listOf(
                        Evidence(
                            id = "S1",
                            collectionId = collection.id.value,
                            documentId = "doc-seed",
                            unitId = "unit-seed",
                            locator = SourceLocation.TextLines(4, 4),
                            locatorLabel = "lines 4-4",
                            text = "Mira signed it.",
                        ),
                    ),
                    initialUsage = TokenUsage(11, 3, 0),
                    initialCitations = CitationValidation(valid = listOf("S1"), invalid = emptyList()),
                )

                harness.context.database.read { connection ->
                    assertTrue(conversationExists(connection, askId), "the Ask conversation must be seeded")
                    listOf("messages", "model_calls", "citations").forEach { table ->
                        assertTrue(childCount(connection, table, askId) > 0, "$table must hold seeded Ask rows")
                    }
                    assertTrue(conversationExists(connection, investigateId), "the Investigate conversation must be seeded")
                    listOf("messages", "model_calls", "tool_calls", "evidence_ledger", "request_eligibility", "request_omissions", "limit_events").forEach { table ->
                        assertTrue(childCount(connection, table, investigateId) > 0, "$table must hold seeded Investigate rows")
                    }
                }

                for (conversationId in listOf(askId, investigateId)) {
                    val response = harness.request(
                        HttpMethod.Delete,
                        "/api/collections/${collection.id.value}/conversations/$conversationId",
                        credential = Credential.CSRF,
                    )
                    assertEquals(HttpStatusCode.NoContent, response.status, response.bodyAsText())
                    assertEquals("", response.bodyAsText())

                    harness.context.database.read { connection ->
                        assertFalse(conversationExists(connection, conversationId), "the conversation row must be deleted")
                        childTables.forEach { table ->
                            assertEquals(0, childCount(connection, table, conversationId), "the cascade must remove $table rows")
                        }
                    }
                }
            }
        } finally {
            dataDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `deleting an unknown conversation id answers not-found and removes nothing`() = runBlocking {
        val dataDir = Files.createTempDirectory("infoscry-delete-unknown")
        try {
            ApiTestServer(dataDir).use { harness ->
                val collection = harness.context.collectionService.create("Default")

                val response = harness.request(
                    HttpMethod.Delete,
                    "/api/collections/${collection.id.value}/conversations/no-such-conversation",
                    credential = Credential.CSRF,
                )

                assertEquals(HttpStatusCode.NotFound, response.status, response.bodyAsText())
                assertTrue(response.bodyAsText().contains("NOT_FOUND"), response.bodyAsText())
                val remaining = harness.context.database.read { connection -> conversationCount(connection) }
                assertEquals(0, remaining, "a not-found delete must not create or remove anything")
            }
        } finally {
            dataDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `deleting a conversation through another collection's id answers not-found and leaves it intact`() = runBlocking {
        val dataDir = Files.createTempDirectory("infoscry-delete-foreign")
        try {
            ApiTestServer(dataDir).use { harness ->
                val collection = harness.context.collectionService.create("Default")
                val other = harness.context.collectionService.create("Other")
                val profile = createProfile(harness, "delete-owner")
                val conversationId = harness.context.llm.persistInvestigateConversation(
                    collectionId = collection.id,
                    profile = profile,
                    promptVersion = 1,
                    retrievalSnapshot = RetrievalSnapshot.value(),
                )
                harness.context.llm.persistInvestigateMessage(conversationId, 0, "user", "Who signed it?")
                harness.context.llm.persistLimitEvent(conversationId, "CANCELLED", "the reader stopped it")

                val response = harness.request(
                    HttpMethod.Delete,
                    "/api/collections/${other.id.value}/conversations/$conversationId",
                    credential = Credential.CSRF,
                )

                assertEquals(HttpStatusCode.NotFound, response.status, response.bodyAsText())
                harness.context.database.read { connection ->
                    assertTrue(conversationExists(connection, conversationId), "the other collection's delete must not remove the conversation")
                    assertEquals(1, childCount(connection, "messages", conversationId))
                    assertEquals(1, childCount(connection, "limit_events", conversationId))
                }
                val ownerList = harness.request(
                    HttpMethod.Get,
                    "/api/collections/${collection.id.value}/investigations",
                    credential = Credential.CSRF,
                )
                assertTrue(ownerList.bodyAsText().contains(conversationId), "the owning collection's list must still show the conversation")
            }
        } finally {
            dataDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a delete without the mutation credential is rejected`() = runBlocking {
        val dataDir = Files.createTempDirectory("infoscry-delete-uncredentialed")
        try {
            ApiTestServer(dataDir).use { harness ->
                val collection = harness.context.collectionService.create("Default")

                val response = harness.request(
                    HttpMethod.Delete,
                    "/api/collections/${collection.id.value}/conversations/anything",
                    credential = Credential.NONE,
                )

                assertEquals(HttpStatusCode.Unauthorized, response.status, response.bodyAsText())
                assertTrue(response.bodyAsText().contains("MUTATION_REQUIRES_CREDENTIALS"), response.bodyAsText())
                assertEquals(0, harness.context.database.read { connection -> conversationCount(connection) })
            }
        } finally {
            dataDir.toFile().deleteRecursively()
        }
    }

    private fun createProfile(harness: ApiTestServer, name: String): LlmProfile = LlmProfile(
        id = UUID.randomUUID().toString(),
        name = name,
        provider = LlmProvider.OPENAI_COMPATIBLE,
        model = "delete-model",
        contextWindow = 10_000,
        maxOutputTokens = 64,
        inputPricePerMillion = 1.0,
        outputPricePerMillion = 2.0,
        cacheReadPricePerMillion = 0.0,
        enabled = true,
        endpoint = "https://provider.invalid/v1",
    ).also { harness.context.llm.create(it) }
}

private fun conversationCount(connection: Connection): Int =
    connection.createStatement().use { statement ->
        statement.executeQuery("SELECT COUNT(*) FROM conversations").use { rows ->
            rows.next(); rows.getInt(1)
        }
    }

private fun conversationExists(connection: Connection, conversationId: String): Boolean =
    connection.prepareStatement("SELECT 1 FROM conversations WHERE id = ?").use { statement ->
        statement.setString(1, conversationId)
        statement.executeQuery().use { it.next() }
    }

private fun childCount(connection: Connection, table: String, conversationId: String): Int =
    connection.prepareStatement("SELECT COUNT(*) FROM $table WHERE conversation_id = ?").use { statement ->
        statement.setString(1, conversationId)
        statement.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
    }