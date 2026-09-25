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
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
        val created = store.create(profile(" local "))

        val loaded = store.findByName("local")
        assertNotNull(loaded)
        assertEquals("local", loaded.name)
        assertEquals("local", created.name)
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
    fun `updating a profile preserves its id and clears capability when endpoint or model changes`() {
        val original = profile("update-me").copy(endpoint = "https://one.example", toolCallingMeasured = true, capabilityCheckedAt = "2026-09-22T10:00:00Z")
        store.create(original)
        store.recordCapability(original.name, LlmCapabilityProbe(true, "2026-09-22T10:00:00Z"))

        val updated = store.update(original.id, original.copy(name = "renamed", model = "new-model"))

        assertNotNull(updated)
        assertEquals(original.id, updated.id)
        assertEquals("renamed", updated.name)
        assertNull(updated.toolCallingMeasured)
        assertNull(updated.capabilityCheckedAt)
    }

    @Test
    fun `updating unrelated fields preserves measured capability`() {
        val original = profile("update-me").copy(toolCallingMeasured = true, capabilityCheckedAt = "2026-09-22T10:00:00Z")
        store.create(original)
        store.recordCapability(original.name, LlmCapabilityProbe(true, "2026-09-22T10:00:00Z"))

        val updated = store.update(original.id, original.copy(name = "renamed"))

        assertEquals(true, updated?.toolCallingMeasured)
        assertEquals(original.capabilityCheckedAt, updated?.capabilityCheckedAt)
    }

    @Test
    fun `changing provider also clears measured capability`() {
        val original = profile("provider-change")
        store.create(original)
        store.recordCapability(original.name, LlmCapabilityProbe(true, "2026-09-22T10:00:00Z"))

        val updated = store.update(original.id, original.copy(provider = LlmProvider.ANTHROPIC))

        assertNull(updated?.toolCallingMeasured)
        assertNull(updated?.capabilityCheckedAt)
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
        store.create(askProfile)
        val collectionId = database.read { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT id FROM collections WHERE name = 'Default'").use { rows -> rows.next(); CollectionId(rows.getString(1)) }
            }
        }
        store.savePromptBody(LlmPromptRole.ASK, "custom ask body")
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

// ---- Investigate persistence (Task 4b-2) ----

    private fun defaultCollectionId(): CollectionId = database.read { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT id FROM collections WHERE name = 'Default'").use { rows ->
                rows.next(); CollectionId(rows.getString(1))
            }
        }
    }

    private fun countTable(table: String): Int = database.read { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT count(*) FROM $table").use { rows ->
                rows.next(); rows.getInt(1)
            }
        }
    }

    @Test
    fun `persistInvestigateConversation creates a row and returns an id`() {
        val convId = store.persistInvestigateConversation(
            defaultCollectionId(), pricedProfile(), "INVESTIGATE", 1, "{\"mode\":\"HYBRID\"}",
        )
        assertEquals(1, countTable("conversations"))
        database.read { connection ->
            connection.prepareStatement("SELECT mode, collection_id, prompt_version FROM conversations WHERE id = ?").use { s ->
                s.setString(1, convId)
                s.executeQuery().use { rows ->
                    assertTrue(rows.next())
                    assertEquals("INVESTIGATE", rows.getString("mode"))
                    assertEquals("1", rows.getString("prompt_version"))
                }
            }
        }
    }

    @Test
    fun `persistInvestigateMessage writes a message row`() {
        val convId = store.persistInvestigateConversation(defaultCollectionId(), pricedProfile(), promptVersion = 1, retrievalSnapshot = "{}")
        store.persistInvestigateMessage(convId, 0, "user", "hello")
        store.persistInvestigateMessage(convId, 1, "assistant", "hi")
        assertEquals(2, countTable("messages"))
    }

    @Test
    fun `investigate tool exchange retains provider call ids after reload`() {
        val savedProfile = pricedProfile()
        store.create(savedProfile)
        val convId = store.persistInvestigateConversation(defaultCollectionId(), savedProfile, promptVersion = 1, retrievalSnapshot = "{}")
        val call = ToolCall("call-1", "search_collection", "{\"query\":\"x\"}")
        val expected = listOf(
            LlmMessage("user", "find x"),
            LlmMessage("assistant", "", toolCalls = listOf(call)),
            LlmMessage("tool", "{\"results\":[]}", toolCallId = call.id),
            LlmMessage("assistant", "done"),
        )
        expected.forEachIndexed { seq, message -> store.persistInvestigateMessage(convId, seq, message) }

        assertEquals(expected, store.loadInvestigateHistory(convId)?.messages)
    }

    @Test
    fun `legacy investigate tool rows without call ids are omitted on reload`() {
        store.create(pricedProfile())
        val convId = store.persistInvestigateConversation(defaultCollectionId(), pricedProfile(), promptVersion = 1, retrievalSnapshot = "{}")
        store.persistInvestigateMessage(convId, 0, "user", "find x")
        store.persistInvestigateMessage(convId, 1, "assistant", "")
        store.persistInvestigateMessage(convId, 2, "tool", "legacy result")
        store.persistInvestigateMessage(convId, 3, "assistant", "done")

        val history = store.loadInvestigateHistory(convId)
        assertEquals(listOf(LlmMessage("user", "find x"), LlmMessage("assistant", "done")),
            history?.messages)
        assertEquals(4, history?.nextMessageSeq)
    }

    @Test
    fun `persistInvestigateModelCall writes usage and cost`() {
        val convId = store.persistInvestigateConversation(defaultCollectionId(), pricedProfile(), promptVersion = 1, retrievalSnapshot = "{}")
        val callId = store.persistInvestigateModelCall(
            convId, "OPENAI_COMPATIBLE", null, "model", "priced", 1,
            "SUCCEEDED", 100L, 50L, 0L, 0.00035,
        )
        assertEquals(1, countTable("model_calls"))
        database.read { connection ->
            connection.prepareStatement("SELECT status, input_tokens, output_tokens, cost_usd FROM model_calls WHERE id = ?").use { s ->
                s.setString(1, callId)
                s.executeQuery().use { rows ->
                    assertTrue(rows.next())
                    assertEquals("SUCCEEDED", rows.getString("status"))
                    assertEquals(100L, rows.getLong("input_tokens"))
                    assertEquals(50L, rows.getLong("output_tokens"))
                    assertEquals(0.00035, rows.getDouble("cost_usd"))
                }
            }
        }
    }

    @Test
    fun `persistInvestigateToolCall writes a tool call row`() {
        val convId = store.persistInvestigateConversation(defaultCollectionId(), pricedProfile(), promptVersion = 1, retrievalSnapshot = "{}")
        val callId = store.persistInvestigateModelCall(convId, "OPENAI_COMPATIBLE", null, "model", "priced", 1, "SUCCEEDED", 10L, 5L, 0L, 0.0)
        store.persistInvestigateToolCall(convId, callId, "search_collection", "{\"query\":\"x\"}", "SUCCESS", 42L)
        assertEquals(1, countTable("tool_calls"))
        database.read { connection ->
            connection.prepareStatement("SELECT tool_name, arguments_json, result_code, duration_ms FROM tool_calls").use { s ->
                s.executeQuery().use { rows ->
                    assertTrue(rows.next())
                    assertEquals("search_collection", rows.getString("tool_name"))
                    assertEquals("{\"query\":\"x\"}", rows.getString("arguments_json"))
                    assertEquals("SUCCESS", rows.getString("result_code"))
                    assertEquals(42L, rows.getLong("duration_ms"))
                }
            }
        }
    }

    @Test
    fun `evidence ledger entries are durable within a conversation`() {
        val convId = store.persistInvestigateConversation(defaultCollectionId(), pricedProfile(), promptVersion = 1, retrievalSnapshot = "{}")
        store.persistEvidenceLedgerEntry(convId, "S1", "unit-1", "{\"page\":1}", "excerpt one")
        store.persistEvidenceLedgerEntry(convId, "S2", "unit-2", "{\"page\":2}", "excerpt two")
        assertEquals(2, countTable("evidence_ledger"))
        database.read { connection ->
            connection.prepareStatement("SELECT evidence_id, source_unit_id, excerpt FROM evidence_ledger WHERE conversation_id = ? ORDER BY evidence_id").use { s ->
                s.setString(1, convId)
                s.executeQuery().use { rows ->
                    assertTrue(rows.next())
                    assertEquals("S1", rows.getString("evidence_id"))
                    assertEquals("unit-1", rows.getString("source_unit_id"))
                    assertEquals("excerpt one", rows.getString("excerpt"))
                    assertTrue(rows.next())
                    assertEquals("S2", rows.getString("evidence_id"))
                }
            }
        }
    }

    @Test
    fun `request eligibility records which evidence ids were in a model call`() {
        val convId = store.persistInvestigateConversation(defaultCollectionId(), pricedProfile(), promptVersion = 1, retrievalSnapshot = "{}")
        store.persistEvidenceLedgerEntry(convId, "S1", "unit-1", "{}", "excerpt")
        store.persistEvidenceLedgerEntry(convId, "S2", "unit-2", "{}", "excerpt")
        val callId = store.persistInvestigateModelCall(convId, "OPENAI_COMPATIBLE", null, "m", "priced", 1, "SUCCEEDED", 1L, 1L, 0L, 0.0)
        store.persistRequestEligibility(callId, convId, listOf("S1", "S2"))
        assertEquals(2, countTable("request_eligibility"))
    }

    @Test
    fun `request omissions record which groups were pruned`() {
        val convId = store.persistInvestigateConversation(defaultCollectionId(), pricedProfile(), promptVersion = 1, retrievalSnapshot = "{}")
        val callId = store.persistInvestigateModelCall(convId, "OPENAI_COMPATIBLE", null, "m", "priced", 1, "SUCCEEDED", 1L, 1L, 0L, 0.0)
        store.persistRequestOmissions(callId, convId, listOf("turn-0", "exchange-1"))
        assertEquals(2, countTable("request_omissions"))
        database.read { connection ->
            connection.prepareStatement("SELECT group_label FROM request_omissions WHERE model_call_id = ? ORDER BY group_label").use { s ->
                s.setString(1, callId)
                s.executeQuery().use { rows ->
                    assertTrue(rows.next())
                    assertEquals("exchange-1", rows.getString("group_label"))
                    assertTrue(rows.next())
                    assertEquals("turn-0", rows.getString("group_label"))
                }
            }
        }
    }

    @Test
    fun `limit events are durable`() {
        val convId = store.persistInvestigateConversation(defaultCollectionId(), pricedProfile(), promptVersion = 1, retrievalSnapshot = "{}")
        store.persistLimitEvent(convId, "MAX_TOOL_CALLS", "limit of 20 tool calls reached")
        store.persistLimitEvent(convId, "CONTEXT_BUDGET_EXCEEDED", "irreducible request too large")
        assertEquals(2, countTable("limit_events"))
        database.read { connection ->
            connection.prepareStatement("SELECT event_type, message FROM limit_events WHERE conversation_id = ? ORDER BY event_type").use { s ->
                s.setString(1, convId)
                s.executeQuery().use { rows ->
                    assertTrue(rows.next())
                    assertEquals("CONTEXT_BUDGET_EXCEEDED", rows.getString("event_type"))
                    assertTrue(rows.next())
                    assertEquals("MAX_TOOL_CALLS", rows.getString("event_type"))
                }
            }
        }
    }

    @Test
    fun `an investigate conversation loads back with its snapshot messages and evidence ids`() {
        store.create(pricedProfile())
        val persisted = store.findByName("priced")!!
        val conversationId = store.persistInvestigateConversation(
            collectionId = defaultCollectionId(),
            profile = persisted,
            promptVersion = 3,
            retrievalSnapshot = "{\"mode\":\"HYBRID\",\"topHits\":30}",
        )
        store.persistInvestigateMessage(conversationId, 0, "user", "what happened")
        store.persistInvestigateMessage(conversationId, 1, "assistant", "the ledger shows a pattern")
        store.persistEvidenceLedgerEntry(conversationId, "S1", "unit-1", "{\"startLine\":1}", "first excerpt")
        store.persistEvidenceLedgerEntry(conversationId, "S2", "unit-2", "{\"startLine\":2}", "second excerpt")

        val history = store.loadInvestigateHistory(conversationId)

        assertNotNull(history)
        assertEquals(defaultCollectionId(), history.collectionId)
        assertEquals("priced", history.profile.name)
        assertEquals(LlmProvider.OPENAI_COMPATIBLE, history.profile.provider)
        assertEquals("model", history.profile.model)
        assertEquals(10_000, history.profile.contextWindow, "fields beyond the snapshot come from the live profile")
        assertEquals(3, history.promptVersion)
        assertEquals("{\"mode\":\"HYBRID\",\"topHits\":30}", history.retrievalSnapshot)
        assertEquals(
            listOf(LlmMessage("user", "what happened"), LlmMessage("assistant", "the ledger shows a pattern")),
            history.messages,
        )
        assertEquals(listOf("S1", "S2"), history.evidenceIds)
    }

    @Test
    fun `loadInvestigateHistory is null for unknown and non-investigate conversations`() {
        assertNull(store.loadInvestigateHistory("missing"))

        store.create(pricedProfile())
        val persisted = store.findByName("priced")!!
        val askConversation = store.persistInvestigateConversation(
            collectionId = defaultCollectionId(),
            profile = persisted,
            mode = "ASK",
            promptVersion = 1,
            retrievalSnapshot = "{}",
        )
        assertNull(store.loadInvestigateHistory(askConversation))
    }

    @Test
    fun `updateUsageTotals increments counters`() {
        val p = pricedProfile()
        store.create(p)
        store.updateUsageTotals(p.id, 1, 100L, 50L, 0L, 0.00035)
        store.updateUsageTotals(p.id, 1, 100L, 50L, 0L, 0.00035)
        database.read { connection ->
            connection.prepareStatement("SELECT calls, input_tokens, output_tokens, cost_usd FROM usage_totals WHERE profile_id = ?").use { s ->
                s.setString(1, p.id)
                s.executeQuery().use { rows ->
                    assertTrue(rows.next())
                    assertEquals(2, rows.getInt("calls"))
                    assertEquals(200L, rows.getLong("input_tokens"))
                    assertEquals(100L, rows.getLong("output_tokens"))
                    assertEquals(0.0007, rows.getDouble("cost_usd"))
                }
            }
        }
    }

    private fun pricedProfile() = LlmProfile(
        id = UUID.randomUUID().toString(),
        name = "priced",
        provider = LlmProvider.OPENAI_COMPATIBLE,
        model = "model",
        contextWindow = 10_000,
        maxOutputTokens = 20,
        inputPricePerMillion = 2.0,
        outputPricePerMillion = 3.0,
        cacheReadPricePerMillion = 4.0,
        enabled = true,
    )
}
