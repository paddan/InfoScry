package infoscry.llm

import infoscry.ask.CitationValidation
import infoscry.ask.CorrectionSnapshot
import infoscry.ask.Evidence
import infoscry.domain.CollectionId
import infoscry.domain.SourceLocation
import infoscry.storage.Database
import infoscry.storage.DuplicateLlmProfileNameException
import infoscry.storage.LlmStore
import infoscry.storage.SchemaMigrator
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Database-level behavior: secrets never reach the database, defaults and capabilities persist. */
class LlmStoreTest {

    private lateinit var directory: Path
    private lateinit var database: Database
    private lateinit var store: LlmStore

    @BeforeTest
    fun openMigratedDatabase() {
        directory = Files.createTempDirectory("infoscry-llm")
        database = Database(directory.resolve("state.db"))
        SchemaMigrator(database).migrate()
        store = LlmStore(database)
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
    fun `profiles round trip`() {
        store.create(profile("local"))

        val loaded = store.findByName("local")
        assertNotNull(loaded)
        assertEquals("local", loaded.name)
        assertEquals("MY_SECRET_KEY", loaded.apiKeyEnvironmentVariable)
    }

    @Test
    fun `duplicate profile names are refused case-insensitively`() {
        store.create(profile("Claude"))
        assertFailsWith<DuplicateLlmProfileNameException> { store.create(profile("claude")) }
    }

    @Test
    fun `the stored row holds the variable name, not a secret value`() {
        store.create(profile("key-holder", apiKeyEnvironmentVariable = "ANTHROPIC_API_KEY"))

        val row = database.read { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT api_key_environment_variable FROM llm_profiles WHERE name = 'key-holder'",
                ).use { it.getString(1) }
            }
        }
        assertEquals("ANTHROPIC_API_KEY", row)
    }

    @Test
    fun `a credential string is rejected as an environment variable name`() {
        // Passing a token as the variable name must be refused by validation, never persisted.
        assertFailsWith<IllegalArgumentException> {
            store.create(profile("bad", apiKeyEnvironmentVariable = "sk-ant-this-is-not-a-name"))
        }
    }

    @Test
    fun `defaults are selected and read back independently per role`() {
        store.create(profile("ask-model"))
        store.create(profile("investigate-model"))

        assertTrue(store.setDefault(LlmPromptRole.ASK, "ask-model"))
        assertTrue(store.setDefault(LlmPromptRole.INVESTIGATE, "investigate-model"))

        assertEquals("ask-model", store.defaultProfileName(LlmPromptRole.ASK))
        assertEquals("investigate-model", store.defaultProfileName(LlmPromptRole.INVESTIGATE))

        // Re-pointing Investigate must not move Ask.
        store.setDefault(LlmPromptRole.INVESTIGATE, "ask-model")
        assertEquals("ask-model", store.defaultProfileName(LlmPromptRole.ASK))
        assertEquals("ask-model", store.defaultProfileName(LlmPromptRole.INVESTIGATE))
    }

    @Test
    fun `setting a default for an unknown profile is refused`() {
        assertFalse(store.setDefault(LlmPromptRole.ASK, "missing"))
    }

    @Test
    fun `a capability probe persists as a measurement`() {
        store.create(profile("probed"))
        store.recordCapability("probed", LlmCapabilityProbe(toolCallingSupported = true, checkedAt = "2026-09-21T10:00:00Z"))

        val loaded = store.findByName("probed")!!
        assertTrue(loaded.toolCallingSupported)
        assertEquals("2026-09-21T10:00:00Z", loaded.capabilityCheckedAt)
    }

    @Test
    fun `prompt overrides are editable and resettable to the shipped default`() {
        val shipped = store.effectiveBody(LlmPromptRole.ASK)
        assertTrue(shipped.isNotBlank(), "a shipped Ask body exists")

        store.savePromptBody(LlmPromptRole.ASK, "custom ask body")
        assertEquals("custom ask body", store.effectiveBody(LlmPromptRole.ASK))

        store.resetPromptBody(LlmPromptRole.ASK)
        assertEquals(shipped, store.effectiveBody(LlmPromptRole.ASK), "resetting restores the shipped default")
    }

    @Test
    fun `Ask call audit keeps initial and correction usage cost and citation ownership separate`() {
        val priced = profile("priced").copy(
            inputPricePerMillion = 2.0,
            outputPricePerMillion = 3.0,
            cacheReadPricePerMillion = 4.0,
        )
        store.create(priced)
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
}
