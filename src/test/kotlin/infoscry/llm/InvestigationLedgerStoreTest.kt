package infoscry.llm

import infoscry.domain.CollectionId
import infoscry.domain.DocumentId
import infoscry.storage.CollectionStore
import infoscry.storage.ContentStore
import infoscry.storage.Database
import infoscry.storage.InvestigationLedgerStore
import infoscry.storage.LlmProfileStore
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Investigate's turn-by-turn ledger (Task 4b-2): conversations, messages, model calls, tool calls, and evidence. */
class InvestigationLedgerStoreTest {

    private lateinit var directory: Path
    private lateinit var database: Database
    private lateinit var profiles: LlmProfileStore
    private lateinit var store: InvestigationLedgerStore

    @BeforeTest
    fun openMigratedDatabase() {
        directory = Files.createTempDirectory("infoscry-investigation-ledger")
        database = Database(directory.resolve("state.db"))
        SchemaMigrator(database).migrate()
        profiles = LlmProfileStore(database)
        store = InvestigationLedgerStore(database, profiles)
        // A new archive has no automatic Default collection, and these tests name one: create it the way
        // a person would rather than seeding a row the product no longer creates.
        CollectionStore(database).create("Default")
    }

    @AfterTest
    fun closeAndRemoveDatabase() {
        database.close()
        directory.toFile().deleteRecursively()
    }

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
        profiles.create(savedProfile)
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
        profiles.create(pricedProfile())
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
    fun `the ledger stores the revision with the evidence and reads it back`() {
        val savedProfile = pricedProfile()
        profiles.create(savedProfile)
        val convId = store.persistInvestigateConversation(defaultCollectionId(), savedProfile, promptVersion = 1, retrievalSnapshot = "{}")
        store.persistEvidenceLedgerEntry(convId, "S1", "unit-1", "{\"page\":1}", "excerpt one", messageSeq = 1, revisionId = "revision-a")
        store.persistEvidenceLedgerEntry(convId, "S2", "unit-2", "{\"page\":2}", "excerpt two", messageSeq = 1)

        val entries = assertNotNull(store.loadInvestigateHistory(convId)).evidence.associateBy { it.evidenceId }

        assertEquals("revision-a", entries.getValue("S1").revisionId)
        assertEquals("excerpt one", entries.getValue("S1").excerpt)
        assertNull(entries.getValue("S2").revisionId, "evidence written without a revision reads back as revision unknown")
    }

    @Test
    fun `historical evidence resolves its document after publication removes the current unit`() {
        val collectionId = defaultCollectionId()
        val otherCollectionId = CollectionStore(database).create("Other").id
        val documentId = DocumentId("historical-document")
        val oldRevisionId = "historical-revision"
        val newRevisionId = "replacement-revision"
        val oldUnitId = "removed-unit"
        val foreignDocumentId = "foreign-document"
        val foreignRevisionId = "foreign-revision"
        val now = "2026-10-10T12:00:00Z"
        database.transaction { connection ->
            connection.prepareStatement(
                "INSERT INTO documents (id,collection_id,sha256,media_type,original_filename,original_path," +
                    "size_bytes,status,created_at,updated_at) VALUES (?,?,?,?,?,?,0,'COMPLETE',?,?)",
            ).use { statement ->
                statement.setString(1, documentId.value); statement.setString(2, collectionId.value)
                statement.setString(3, "historical-hash"); statement.setString(4, "application/pdf")
                statement.setString(5, "history.pdf"); statement.setString(6, "managed/history.pdf")
                statement.setString(7, now); statement.setString(8, now); statement.executeUpdate()
            }
            connection.prepareStatement(
                "INSERT INTO document_revisions (id,document_id,state,provenance,created_at) VALUES (?,?,?,?,?)",
            ).use { statement ->
                statement.setString(1, oldRevisionId); statement.setString(2, documentId.value)
                statement.setString(3, "SUPERSEDED"); statement.setString(4, "import"); statement.setString(5, now)
                statement.executeUpdate()
                statement.setString(1, newRevisionId); statement.setString(2, documentId.value)
                statement.setString(3, "PUBLISHED"); statement.setString(4, "rescan"); statement.setString(5, now)
                statement.executeUpdate()
            }
            connection.prepareStatement(
                "INSERT INTO page_text_revisions (revision_id,ordinal,unit_id,locator,extracted_text,search_text," +
                    "approval,created_at) VALUES (?,0,?,'{\"page\":1}','historical text','historical text','APPROVED',?)",
            ).use { statement ->
                statement.setString(1, oldRevisionId); statement.setString(2, oldUnitId); statement.setString(3, now)
                statement.executeUpdate()
            }
            connection.prepareStatement(
                "INSERT INTO document_active_revisions (document_id,revision_id,updated_at) VALUES (?,?,?)",
            ).use { statement ->
                statement.setString(1, documentId.value); statement.setString(2, newRevisionId)
                statement.setString(3, now); statement.executeUpdate()
            }
            connection.prepareStatement(
                "INSERT INTO content_units (id,document_id,ordinal,locator_type,locator,extracted_text,search_text," +
                    "created_at,updated_at) VALUES (?, ?, 0, 'PDF_PAGE', '{\"page\":1}', 'historical text', 'historical text', ?, ?)",
            ).use { statement ->
                statement.setString(1, oldUnitId); statement.setString(2, documentId.value)
                statement.setString(3, now); statement.setString(4, now); statement.executeUpdate()
            }
            connection.prepareStatement(
                "INSERT INTO documents (id,collection_id,sha256,media_type,original_filename,original_path," +
                    "size_bytes,status,created_at,updated_at) VALUES (?, ?, 'foreign-hash', 'application/pdf'," +
                    "'foreign.pdf', 'managed/foreign.pdf', 0, 'COMPLETE', ?, ?)",
            ).use { statement ->
                statement.setString(1, foreignDocumentId); statement.setString(2, otherCollectionId.value)
                statement.setString(3, now); statement.setString(4, now); statement.executeUpdate()
            }
            connection.prepareStatement(
                "INSERT INTO document_revisions (id,document_id,state,provenance,created_at) VALUES (?,?,?,'import',?)",
            ).use { statement ->
                statement.setString(1, foreignRevisionId); statement.setString(2, foreignDocumentId)
                statement.setString(3, "SUPERSEDED"); statement.setString(4, now); statement.executeUpdate()
            }
            connection.prepareStatement(
                "INSERT INTO page_text_revisions (revision_id,ordinal,unit_id,locator,extracted_text,search_text," +
                    "approval,created_at) VALUES (?,0,'foreign-unit','{\"page\":1}','private','private','APPROVED',?)",
            ).use { statement ->
                statement.setString(1, foreignRevisionId); statement.setString(2, now); statement.executeUpdate()
            }
        }

        // Publication of the empty replacement removes the obsolete current unit while both immutable page
        // revision rows remain available to resolve citation provenance.
        ContentStore(database).publishRevision(documentId, emptyList())
        val savedProfile = pricedProfile()
        profiles.create(savedProfile)
        val conversationId = store.persistInvestigateConversation(
            collectionId, savedProfile, promptVersion = 1, retrievalSnapshot = "{}",
        )
        store.persistEvidenceLedgerEntry(conversationId, "S1", oldUnitId, "{\"page\":1}", "historical excerpt", revisionId = oldRevisionId)
        store.persistEvidenceLedgerEntry(conversationId, "S2", "foreign-unit", "{\"page\":1}", "foreign excerpt", revisionId = foreignRevisionId)
        store.persistEvidenceLedgerEntry(conversationId, "S3", oldUnitId, "{\"page\":1}", "unversioned excerpt")
        store.persistEvidenceLedgerEntry(conversationId, "S4", "unknown-unit", "{\"page\":1}", "unknown excerpt")

        val evidence = assertNotNull(store.loadInvestigateHistory(conversationId)).evidence.associateBy { it.evidenceId }

        assertEquals(oldRevisionId, evidence.getValue("S1").revisionId)
        assertEquals(documentId.value, evidence.getValue("S1").documentId)
        assertEquals("", evidence.getValue("S2").documentId, "a revision in another collection must stay unresolved")
        assertNull(evidence.getValue("S3").revisionId, "unversioned evidence remains excerpt-only")
        assertEquals(documentId.value, evidence.getValue("S3").documentId,
            "an unversioned unit can resolve only through its unique preserved document relationship")
        assertNull(evidence.getValue("S4").revisionId)
        assertEquals("", evidence.getValue("S4").documentId, "unknown provenance must not guess a document")
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
    fun `evidence ledger entries round trip their introducing message seq`() {
        val savedProfile = pricedProfile()
        profiles.create(savedProfile)
        val convId = store.persistInvestigateConversation(defaultCollectionId(), savedProfile, promptVersion = 1, retrievalSnapshot = "{}")
        store.persistEvidenceLedgerEntry(convId, "S1", "unit-1", "{}", "excerpt one", messageSeq = 2)
        store.persistEvidenceLedgerEntry(convId, "S2", "unit-2", "{}", "excerpt two")
        val history = store.loadInvestigateHistory(convId)
        assertEquals(listOf("S1" to 2, "S2" to null), history!!.evidence.map { it.evidenceId to it.messageSeq })
        assertEquals(listOf("S1", "S2"), history.evidenceIds)
    }

    @Test
    fun `superseded investigate messages are excluded from reloaded history`() {
        val savedProfile = pricedProfile()
        profiles.create(savedProfile)
        val convId = store.persistInvestigateConversation(defaultCollectionId(), savedProfile, promptVersion = 1, retrievalSnapshot = "{}")
        store.persistInvestigateMessage(convId, 0, "user", "question")
        store.persistInvestigateMessage(convId, 1, LlmMessage("assistant", "draft [S9]"), superseded = true)
        store.persistInvestigateMessage(convId, 2, "assistant", "adopted [S1]")
        val history = store.loadInvestigateHistory(convId)
        assertEquals(listOf("question", "adopted [S1]"), history!!.messages.map { it.content })
        assertEquals(listOf(0, 2), history.messageSeqs)
        assertEquals(3, history.nextMessageSeq, "the superseded row keeps its durable seq")
    }

    @Test
    fun `legacy evidence without a message seq is associated with its tool result on reload`() {
        val savedProfile = pricedProfile()
        profiles.create(savedProfile)
        val convId = store.persistInvestigateConversation(defaultCollectionId(), savedProfile, promptVersion = 1, retrievalSnapshot = "{}")
        val call = ToolCall("call-1", "read_content_unit", "{\"contentUnitId\":\"unit-1\"}")
        store.persistInvestigateMessage(convId, 0, "user", "find x")
        store.persistInvestigateMessage(convId, 1, LlmMessage("assistant", "", toolCalls = listOf(call)))
        store.persistInvestigateMessage(
            convId, 2,
            LlmMessage("tool", """{"evidence":[{"evidenceId":"S1","text":"excerpt"}]}""", toolCallId = "call-1"),
        )
        store.persistInvestigateMessage(convId, 3, "assistant", "answer [S1]")
        // Written the pre-migration way: the ledger row has no introducing seq.
        store.persistEvidenceLedgerEntry(convId, "S1", "unit-1", "{}", "excerpt")
        val history = store.loadInvestigateHistory(convId)
        assertEquals(2, history!!.evidence.single().messageSeq, "the introducing tool result's seq is recovered")
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
        profiles.create(pricedProfile())
        val persisted = profiles.findByName("priced")!!
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

        profiles.create(pricedProfile())
        val persisted = profiles.findByName("priced")!!
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
    fun `a conversation snapshot that still holds a credential reopens without it`() {
        val secret = "hunter2-not-a-real-credential"
        profiles.create(pricedProfile())
        val conversationId = store.persistInvestigateConversation(
            collectionId = defaultCollectionId(),
            profile = profiles.findByName("priced")!!,
            promptVersion = 1,
            retrievalSnapshot = "{}",
        )
        // A snapshot written around the profile type that refuses such a URL, the way a hand edit or a future
        // bug would write it: reopening the conversation is what such a row used to fail.
        database.transaction { connection ->
            connection.prepareStatement("UPDATE conversations SET profile_endpoint = ? WHERE id = ?").use { statement ->
                statement.setString(1, "https://user:$secret@api.example.com/v1")
                statement.setString(2, conversationId)
                statement.executeUpdate()
            }
        }

        val history = assertNotNull(store.loadInvestigateHistory(conversationId))

        assertEquals("https://api.example.com/v1", history.profile.endpoint, "the credential is dropped")
        assertFalse(history.profile.endpoint.contains(secret), "the reopened snapshot carries no credential")
        // The row the snapshot was taken from is clean and enabled — a person may have repaired it since the
        // conversation was created — so the switched-off state a continue reads has to be computed from the
        // snapshot itself, and the refusal names the address this conversation stored.
        assertTrue(profiles.findByName("priced")!!.enabled, "the profile row itself is still usable")
        assertFalse(
            history.profile.enabled,
            "a conversation whose stored address had to be repaired is not dispatched to until it is reviewed",
        )
        val refusal = assertFailsWith<IllegalArgumentException> { history.profile.requireDispatchable() }
        assertTrue(
            refusal.message.orEmpty().contains("https://api.example.com/v1"),
            "the refusal names the conversation's stored address as the reason: ${refusal.message}",
        )
    }

    @Test
    fun `updateUsageTotals increments counters`() {
        val p = pricedProfile()
        profiles.create(p)
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
