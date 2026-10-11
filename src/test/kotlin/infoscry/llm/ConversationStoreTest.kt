package infoscry.llm

import infoscry.ask.CitationValidation
import infoscry.ask.CorrectionSnapshot
import infoscry.ask.Evidence
import infoscry.domain.CollectionId
import infoscry.domain.SourceLocation
import infoscry.storage.CollectionStore
import infoscry.storage.ConversationStore
import infoscry.storage.Database
import infoscry.storage.LlmProfileStore
import infoscry.storage.SchemaMigrator
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Ask's durable audit trail: usage, cost, and citation ownership survive a correction. */
class ConversationStoreTest {

    private lateinit var directory: Path
    private lateinit var database: Database
    private lateinit var profiles: LlmProfileStore
    private lateinit var store: ConversationStore

    @BeforeTest
    fun openMigratedDatabase() {
        directory = Files.createTempDirectory("infoscry-conversations")
        database = Database(directory.resolve("state.db"))
        SchemaMigrator(database).migrate()
        profiles = LlmProfileStore(database)
        store = ConversationStore(database, profiles)
        // A new archive has no automatic Default collection, and these tests name one: create it the way
        // a person would rather than seeding a row the product no longer creates.
        CollectionStore(database).create("Default")
    }

    @AfterTest
    fun closeAndRemoveDatabase() {
        database.close()
        directory.toFile().deleteRecursively()
    }

    private fun profile(
        name: String,
        apiKeyEnvironmentVariable: String? = "MY_SECRET_KEY",
    ) = LlmProfile(
        id = UUID.randomUUID().toString(),
        name = name,
        provider = LlmProvider.OPENAI_COMPATIBLE,
        model = "model",
        contextWindow = 128_000,
        maxOutputTokens = 4_096,
        inputPricePerMillion = 0.0,
        outputPricePerMillion = 0.0,
        cacheReadPricePerMillion = 0.0,
        enabled = true,
        apiKeyEnvironmentVariable = apiKeyEnvironmentVariable,
    )

    @Test
    fun `Ask call audit keeps initial and correction usage cost and citation ownership separate`() {
        val priced = profile("priced").copy(
            inputPricePerMillion = 2.0,
            outputPricePerMillion = 3.0,
            cacheReadPricePerMillion = 4.0,
        )
        profiles.create(priced)
        val collectionId = database.read { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT id FROM collections WHERE name = 'Default'").use { rows ->
                    rows.next(); CollectionId(rows.getString(1))
                }
            }
        }
        val evidence = Evidence(
            id = "S1", collectionId = collectionId.value, documentId = "document", unitId = "unit",
            locator = SourceLocation.TextLines(1, 1), locatorLabel = "lines 1", text = "evidence",
        )

        store.persistAsk(
            collectionId, priced, "question", "initial [S999]", listOf(evidence),
            initialUsage = TokenUsage(11, 13, 17),
            initialCitations = CitationValidation(emptyList(), listOf("S999")),
            retrievalSnapshot = "{\"mode\":\"HYBRID\",\"topHits\":30,\"maxEvidence\":12}",
            correction = CorrectionSnapshot(
                answer = "corrected [S1]",
                usage = TokenUsage(19, 23, 29),
                citations = CitationValidation(listOf("S1"), emptyList()),
            ),
        )

        val calls = database.read { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT id, input_tokens, output_tokens, cache_read_tokens, cost_usd, correction_of FROM model_calls ORDER BY correction_of IS NULL DESC").use { rows ->
                    buildList {
                        while (rows.next()) add(listOf(rows.getString(1), rows.getLong(2), rows.getLong(3), rows.getLong(4), rows.getDouble(5), rows.getString(6)))
                    }
                }
            }
        }
        assertEquals(2, calls.size)
        assertEquals(listOf(11L, 13L, 17L, 0.000129, null), calls[0].drop(1))
        assertEquals(listOf(19L, 23L, 29L, 0.000223, calls[0][0]), calls[1].drop(1))
        assertEquals("corrected [S1]", database.read { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT content FROM messages WHERE role = 'assistant'").use { rows -> rows.next(); rows.getString(1) }
            }
        })
        val correctionAudit = database.read { connection ->
            connection.prepareStatement("SELECT model_call_id, evidence_id, returned_id, supplied, invalid_marker FROM citations WHERE model_call_id = ? ORDER BY supplied DESC").use { statement ->
                statement.setString(1, calls[1][0] as String)
                statement.executeQuery().use { rows ->
                    buildList { while (rows.next()) add(listOf(rows.getString(1), rows.getString(2), rows.getString(3), rows.getInt(4), rows.getInt(5))) }
                }
            }
        }
        assertEquals(listOf(
            listOf(calls[1][0], "S1", null, 1, 0),
            listOf(calls[1][0], "S1", "S1", 0, 0),
        ), correctionAudit)
        assertEquals(listOf("invalid:S999", "S999", 0, 0, 1), database.read { connection ->
            connection.prepareStatement("SELECT source_unit_id, returned_id, validated, supplied, invalid_marker FROM citations WHERE model_call_id = ? AND invalid_marker = 1").use { statement ->
                statement.setString(1, calls[0][0] as String)
                statement.executeQuery().use { rows ->
                    rows.next(); listOf(rows.getString(1), rows.getString(2), rows.getInt(3), rows.getInt(4), rows.getInt(5))
                }
            }
        })
        assertEquals(listOf(2L, 30L, 36L, 46L, 0.000352), database.read { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT calls, input_tokens, output_tokens, cache_read_tokens, cost_usd FROM usage_totals WHERE profile_id = '${priced.id}'").use { rows ->
                    rows.next(); listOf(rows.getLong(1), rows.getLong(2), rows.getLong(3), rows.getLong(4), rows.getDouble(5))
                }
            }
        })
    }

    @Test
    fun `an Ask snapshot stores the prompt version and retrieval settings and can reopen a cited source`() {
        val askProfile = profile("snapshot")
        profiles.create(askProfile)
        val collectionId = database.read { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT id FROM collections WHERE name = 'Default'").use { rows -> rows.next(); CollectionId(rows.getString(1)) }
            }
        }
        profiles.savePromptBody(LlmPromptRole.ASK, "custom ask body")
        val evidence = Evidence(
            id = "S1", collectionId = collectionId.value, documentId = "document", unitId = "unit-42",
            locator = SourceLocation.TextLines(3, 4), locatorLabel = "lines 3-4", text = "evidence",
        )

        store.persistAsk(
            collectionId, askProfile, "question", "answer [S1]", listOf(evidence),
            initialUsage = TokenUsage(1, 2, 0),
            initialCitations = CitationValidation(listOf("S1"), emptyList()),
            retrievalSnapshot = "{\"mode\":\"HYBRID\"}",
        )

        val conversation = database.read { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT prompt_version, retrieval_snapshot FROM conversations").use { rows ->
                    rows.next(); listOf(rows.getInt(1), rows.getString(2))
                }
            }
        }
        assertEquals(2, conversation[0], "the user override's version must be snapshotted")
        assertEquals("{\"mode\":\"HYBRID\"}", conversation[1])

        val reopened = database.read { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT source_unit_id, locator_json FROM citations WHERE validated = 1").use { rows ->
                    rows.next(); rows.getString(1) to rows.getString(2)
                }
            }
        }
        assertEquals("unit-42", reopened.first)
        assertEquals(SourceLocation.TextLines(3, 4), Json.decodeFromString<SourceLocation>(reopened.second))
    }
}
