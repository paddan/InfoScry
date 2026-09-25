package infoscry.investigate

import infoscry.ask.RetrievalSnapshot
import infoscry.domain.CollectionId
import infoscry.domain.ContentUnitId
import infoscry.domain.Document
import infoscry.domain.DocumentId
import infoscry.domain.DocumentStatus
import infoscry.domain.SourceLocation
import infoscry.extract.ContentUnitDraft
import infoscry.extract.ExtractionFingerprint
import infoscry.extract.ExtractionSettings
import infoscry.llm.LlmCompletion
import infoscry.llm.LlmCompletionClient
import infoscry.llm.LlmEvent
import infoscry.llm.LlmMessage
import infoscry.llm.LlmProfile
import infoscry.llm.LlmProvider
import infoscry.llm.LlmRequest
import infoscry.llm.LlmStreamingClient
import infoscry.llm.PromptService
import infoscry.llm.TokenUsage
import infoscry.search.SearchFilters
import infoscry.search.SearchMode
import infoscry.search.SearchOutcome
import infoscry.storage.CollectionStore
import infoscry.storage.ContentStore
import infoscry.storage.Database
import infoscry.storage.DocumentStore
import infoscry.storage.Instants
import infoscry.storage.LlmStore
import infoscry.storage.SchemaMigrator
import java.nio.file.Files
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class InvestigationServiceTest {
    private val directory = Files.createTempDirectory("infoscry-investigate-service")
    private val database = Database(directory.resolve("state.db"))
    private val store = LlmStore(database)
    private val contentStore = ContentStore(database)
    private val documentStore = DocumentStore(database)

    init { SchemaMigrator(database).migrate() }

    @AfterTest
    fun close() {
        database.close()
        directory.toFile().deleteRecursively()
    }

    private fun profile(contextWindow: Int = 10000) = LlmProfile(
        id = "profile", name = "test", provider = LlmProvider.OPENAI_COMPATIBLE,
        model = "model", contextWindow = contextWindow, maxOutputTokens = 20,
        inputPricePerMillion = 2.0, outputPricePerMillion = 3.0, cacheReadPricePerMillion = 4.0,
        enabled = true,
    )

    private fun tools(collectionId: CollectionId) = InvestigationTools(
        collectionId, FakeSearch(collectionId), contentStore, documentStore,
    )

    private fun service(
        tools: InvestigationTools,
        provider: InvestigateProvider,
        persistence: InvestigatePersistence = FakePersistence(),
        turnTimeoutMs: Long = InvestigationTimeouts.TURN_TIMEOUT_MS,
    ) = InvestigationService(
        tools,
        PromptService(store),
        promptVersion = 1,
        streamingClient = { provider },
        persistence = persistence,
        turnTimeoutMs = turnTimeoutMs,
    )

    private fun request() = InvestigateRequest(CollectionId("collection"), "question", profile())

    @Test
    fun `blank question emits INVALID_REQUEST and makes no calls`() = runBlocking {
        val provider = ScriptedProvider()
        val sv = service(tools(CollectionId("collection")), provider)

        val events = sv.investigate(InvestigateRequest(CollectionId("collection"), "   ", profile())).toList()

        assertEquals("INVALID_REQUEST", assertIs<InvestigateEvent.Error>(events.single()).code)
        assertEquals(0, provider.streamRequests.size)
    }

    @Test
    fun `simple question with no tool calls produces a Done event`() = runBlocking {
        val provider = ScriptedProvider(
            LlmEvent.TextDelta("answer"), LlmEvent.Usage(TokenUsage(1L, 2L)), LlmEvent.Completed,
        )
        val sv = service(tools(CollectionId("collection")), provider)

        val events = sv.investigate(request()).toList()

        assertTrue(events.any { it is InvestigateEvent.Delta }, "expected at least one text delta")
    }

    @Test
    fun `starting a conversation emits Started with the created id and persists the locked snapshot`() = runBlocking {
        val provider = ScriptedProvider(LlmEvent.TextDelta("answer"), LlmEvent.Usage(TokenUsage(1L, 1L)), LlmEvent.Completed)
        val persistence = FakePersistence()
        val sv = service(tools(CollectionId("collection")), provider, persistence = persistence)

        val events = sv.investigate(request()).toList()

        val started = events.filterIsInstance<InvestigateEvent.Started>()
        assertEquals("conv-id", started.single().conversationId, "the id the persistence double returned must surface as Started")
        assertTrue(events.first() is InvestigateEvent.Started, "Started must be the first event, before any Delta")
        val created = persistence.created.single()
        assertEquals(CollectionId("collection"), created.collectionId)
        assertEquals(profile(), created.profile)
        assertEquals(1, created.promptVersion)
        assertEquals(RetrievalSnapshot.value(), created.retrievalSnapshot, "the locked retrieval snapshot must be the shared helper's value, not \"{}\"")
        val appended = persistence.appended.single().second
        assertEquals(CollectionId("collection"), appended.collectionId)
        assertEquals(profile(), appended.profile)
        assertEquals(1, appended.promptVersion)
        assertEquals(RetrievalSnapshot.value(), appended.retrievalSnapshot)
    }

    @Test
    fun `continuing a known conversation seeds its prior messages and appends the turn`() = runBlocking {
        val history = InvestigateHistory(
            collectionId = CollectionId("collection"),
            profile = profile(),
            promptVersion = 1,
            retrievalSnapshot = RetrievalSnapshot.value(),
            messages = listOf(
                LlmMessage("user", "prior question"),
                LlmMessage("assistant", "prior answer"),
            ),
            evidenceIds = emptyList(),
        )
        val persistence = FakePersistence(mapOf("conv-1" to history))
        val provider = ScriptedProvider(LlmEvent.TextDelta("answer"), LlmEvent.Usage(TokenUsage(1L, 1L)), LlmEvent.Completed)
        val sv = service(tools(CollectionId("collection")), provider, persistence = persistence)

        val events = sv.investigate(InvestigateRequest(CollectionId("collection"), "follow-up", profile(), conversationId = "conv-1")).toList()

        val firstRequest = provider.streamRequests[0]
        assertTrue(firstRequest.messages.any { it.content == "prior answer" }, "the outbound request must seed the prior answer")
        assertTrue(firstRequest.messages.any { it.content == "prior question" }, "the outbound request must seed the prior question")
        assertTrue(firstRequest.messages.any { it.content == "follow-up" }, "the outbound request must carry the current question")
        assertEquals("conv-1", persistence.appended.single().first, "a continue must append to the same conversation")
        assertEquals("conv-1", assertIs<InvestigateEvent.Started>(events.first()).conversationId, "a continue surfaces its id as the first event too")
        assertEquals(listOf("conv-1"), persistence.loaded)
        assertTrue(persistence.created.isEmpty(), "a continue must not create a new conversation")
        assertTrue(events.any { it is InvestigateEvent.Done }, "expected a final answer")
    }

    @Test
    fun `continuing an unknown conversation emits CONVERSATION_NOT_FOUND without provider calls`() = runBlocking {
        val provider = ScriptedProvider()
        val persistence = FakePersistence()
        val sv = service(tools(CollectionId("collection")), provider, persistence = persistence)

        val events = sv.investigate(InvestigateRequest(CollectionId("collection"), "question", profile(), conversationId = "missing")).toList()

        assertEquals("CONVERSATION_NOT_FOUND", assertIs<InvestigateEvent.Error>(events.single()).code)
        assertEquals(0, provider.streamRequests.size, "an unknown conversation must make zero provider calls")
        assertEquals(0, provider.completionRequests.size)
        assertEquals(listOf("missing"), persistence.loaded)
        assertTrue(persistence.created.isEmpty(), "an unknown conversation must not create anything")
        assertTrue(persistence.appended.isEmpty(), "an unknown conversation must not be appended to")
    }

    @Test
    fun `a continue whose collection or profile disagrees with the locked snapshot uses the snapshot`() = runBlocking {
        val locked = profile()
        val history = InvestigateHistory(
            collectionId = CollectionId("collection"),
            profile = locked,
            promptVersion = 1,
            retrievalSnapshot = RetrievalSnapshot.value(),
            messages = listOf(LlmMessage("user", "prior question")),
            evidenceIds = emptyList(),
        )
        val persistence = FakePersistence(mapOf("conv-1" to history))
        val provider = ScriptedProvider(LlmEvent.TextDelta("answer"), LlmEvent.Usage(TokenUsage(1L, 1L)), LlmEvent.Completed)
        val sv = service(tools(CollectionId("collection")), provider, persistence = persistence)

        val disagreeing = InvestigateRequest(CollectionId("other"), "question", profile(contextWindow = 4000), conversationId = "conv-1")
        sv.investigate(disagreeing).toList()

        val appended = persistence.appended.single().second
        assertEquals(CollectionId("collection"), appended.collectionId, "the locked collection must win over the request")
        assertEquals(locked, appended.profile, "the locked profile must win over the request")
        assertTrue(persistence.created.isEmpty(), "a continue must not create a new conversation")
    }

    @Test
    fun `a continued turn allocates evidence ids strictly beyond the history's highest`() = runBlocking {
        val collection = CollectionStore(database).create("Investigate collection").id
        val documentId = insertDocument(collection)
        val unitA = addUnit(documentId, ordinal = 0, "unit-a", "A".repeat(400))
        val history = InvestigateHistory(
            collectionId = collection,
            profile = profile(),
            promptVersion = 1,
            retrievalSnapshot = RetrievalSnapshot.value(),
            messages = listOf(LlmMessage("user", "prior question"), LlmMessage("assistant", "prior answer")),
            evidenceIds = listOf("S1", "S2"),
        )
        val persistence = FakePersistence(mapOf("conv-1" to history))
        val provider = PerRoundProvider(
            listOf(
                listOf(LlmEvent.ToolCallReady(infoscry.llm.ToolCall("c1", "read_content_unit", """{"contentUnitId":"${unitA.value}"}""")), LlmEvent.Completed),
                listOf(LlmEvent.TextDelta("answer"), LlmEvent.Usage(TokenUsage(1L, 1L)), LlmEvent.Completed),
            ),
        )
        val sv = service(tools(collection), provider, persistence = persistence)

        sv.investigate(InvestigateRequest(collection, "follow-up", profile(), conversationId = "conv-1")).toList()

        val ids = persistence.appended.single().second.evidenceEntries.map { it.evidenceId }
        assertTrue(ids.isNotEmpty(), "the continued turn must allocate evidence, got $ids")
        assertTrue(
            ids.all { it.removePrefix("S").toIntOrNull()!! > history.evidenceIds.mapNotNull { id -> id.removePrefix("S").toIntOrNull() }.max() },
            "new ids must be strictly greater than the history's highest, never reused, got $ids",
        )
    }

    @Test
    fun `a provider failure maps to LLM_REQUEST_FAILED`() = runBlocking {
        val provider = FailingProvider
        val sv = service(tools(CollectionId("collection")), provider)

        val events = sv.investigate(request()).toList()

        assertEquals("LLM_REQUEST_FAILED", assertIs<InvestigateEvent.Error>(events.last()).code)
    }

    @Test
    fun `irreducible budget overflow makes zero provider calls`() = runBlocking {
        val provider = ScriptedProvider(LlmEvent.TextDelta("unused"), LlmEvent.Usage(TokenUsage(1L, 1L)), LlmEvent.Completed)
        val sv = service(tools(CollectionId("collection")), provider)

        val events = sv.investigate(request(profile = profile(contextWindow = 1))).toList()

        assertTrue(provider.streamRequests.isEmpty(), "expected zero streaming calls")
        assertEquals("CONTEXT_BUDGET_EXCEEDED", assertIs<InvestigateEvent.Error>(events.last()).code)
    }

    @Test
    fun `repeated identical tool calls are detected and stopped`() = runBlocking {
        val tc1 = LlmEvent.ToolCallReady(infoscry.llm.ToolCall("c1", "search_collection", """{"query":"same"}"""))
        val tc2 = LlmEvent.ToolCallReady(infoscry.llm.ToolCall("c2", "search_collection", """{"query":"same"}"""))
        val provider = ScriptedProvider(tc1, tc2, LlmEvent.TextDelta("ok"), LlmEvent.Usage(TokenUsage(1L, 1L)), LlmEvent.Completed)
        val sv = service(tools(CollectionId("collection")), provider)

        val events = sv.investigate(request()).toList()

        val errors = events.filterIsInstance<InvestigateEvent.Error>()
        assertTrue(errors.any { it.code == "REPEATED_TOOL_CALL" }, "expected REPEATED_TOOL_CALL error, got $errors")
        assertTrue(provider.streamRequests.size <= 1, "the turn must stop at the repeated call instead of burning the budget on more rounds, got ${provider.streamRequests.size} stream calls")
        assertTrue(events.none { it is InvestigateEvent.Done }, "a repeat-stopped turn must not produce a final answer")
    }

    @Test
    fun `unknown tool name returns UNKNOWN_TOOL failure`() = runBlocking {
        val tc = LlmEvent.ToolCallReady(infoscry.llm.ToolCall("c1", "nonexistent", "{}"))
        val provider = ScriptedProvider(tc, LlmEvent.TextDelta("ok"), LlmEvent.Usage(TokenUsage(1L, 1L)), LlmEvent.Completed)
        val sv = service(tools(CollectionId("collection")), provider)

        val events = sv.investigate(request()).toList()

        val results = events.filterIsInstance<InvestigateEvent.ToolResult>()
        assertTrue(results.any { it.resultCode == "UNKNOWN_TOOL" }, "expected UNKNOWN_TOOL result, got $results")
    }

    @Test
    fun `tool calls produce ToolCall and ToolResult events`() = runBlocking {
        val tc = LlmEvent.ToolCallReady(infoscry.llm.ToolCall("c1", "search_collection", """{"query":"q"}"""))
        val provider = ScriptedProvider(tc, LlmEvent.TextDelta("answer"), LlmEvent.Usage(TokenUsage(1L, 1L)), LlmEvent.Completed)
        val sv = service(tools(CollectionId("collection")), provider)

        val events = sv.investigate(request()).toList()

        val toolCalls = events.filterIsInstance<InvestigateEvent.ToolCall>()
        val toolResults = events.filterIsInstance<InvestigateEvent.ToolResult>()
        assertTrue(toolCalls.isNotEmpty(), "expected ToolCall events")
        assertTrue(toolResults.isNotEmpty(), "expected ToolResult events")
    }

    private fun request(profile: LlmProfile) = InvestigateRequest(CollectionId("collection"), "question", profile)

    @Test
    fun `a never-ending tool-call stream stops at the MAX_TOOL_CALLS limit`() = runBlocking {
        val calls = (1..21).map { i -> infoscry.llm.ToolCall("c$i", "search_collection", """{"query":"q$i"}""") }
        val streamed = calls.map { LlmEvent.ToolCallReady(it) } + LlmEvent.Completed
        val provider = ScriptedProvider(*streamed.toTypedArray())
        val persistence = FakePersistence()
        val sv = service(tools(CollectionId("collection")), provider, persistence = persistence)

        val events = sv.investigate(request()).toList()

        val errors = events.filterIsInstance<InvestigateEvent.Error>()
        assertTrue(errors.any { it.code == "MAX_TOOL_CALLS" }, "expected MAX_TOOL_CALLS error, got $errors")
        assertTrue(provider.streamRequests.size <= 2, "all 21 calls arrive in the first stream, so the turn must stop there, got ${provider.streamRequests.size} stream calls")
        assertTrue(events.none { it is InvestigateEvent.Done }, "a limit-stopped turn must not produce a final answer")
        val snapshot = persistence.appended.single().second
        assertEquals(20, snapshot.modelCalls.single().toolCalls.size, "the limit-stopped round must still be persisted with its tool calls, got ${snapshot.modelCalls}")
    }

    @Test
    fun `a tool-calling turn stops at the MAX_ROUNDS limit`() = runBlocking {
        val provider = PerRoundProvider(
            (1..11).map { i ->
                listOf(LlmEvent.ToolCallReady(infoscry.llm.ToolCall("c$i", "search_collection", """{"query":"q$i"}""")), LlmEvent.Completed)
            },
        )
        val sv = service(tools(CollectionId("collection")), provider)

        val events = sv.investigate(request()).toList()

        val errors = events.filterIsInstance<InvestigateEvent.Error>()
        assertTrue(errors.any { it.code == "MAX_ROUNDS" }, "expected MAX_ROUNDS error, got $errors")
        assertTrue(provider.streamRequests.size <= 11, "expected at most 11 stream calls, got ${provider.streamRequests.size}")
        assertTrue(events.none { it is InvestigateEvent.Done }, "a limit-stopped turn must not produce a final answer")
    }

    @Test
    fun `tool calls travel as real toolCalls messages with matching tool results`() = runBlocking {
        val tc = LlmEvent.ToolCallReady(infoscry.llm.ToolCall("c1", "search_collection", """{"query":"q"}"""))
        val provider = ScriptedProvider(tc, LlmEvent.Completed)
        val sv = service(tools(CollectionId("collection")), provider)

        sv.investigate(request()).toList()

        assertTrue(provider.streamRequests.size >= 2, "expected a follow-up request")
        val followUp = provider.streamRequests[1]
        val callMessage = followUp.messages.firstOrNull { it.toolCalls.isNotEmpty() }
        assertTrue(callMessage != null, "expected an assistant tool-call message in the follow-up request")
        val seenRole = callMessage!!.role
        assertEquals("assistant", seenRole)
        val toolResult = followUp.messages.firstOrNull { it.role == "tool" && it.toolCallId == callMessage.toolCalls[0].id }
        assertTrue(toolResult != null, "expected a tool result message matching the tool call id")
    }

    @Test
    fun `the model receives the same citation id as the evidence ledger`() = runBlocking {
        val collection = CollectionStore(database).create("Cited collection").id
        val documentId = insertDocument(collection)
        val unitId = addUnit(documentId, 0, "citation-source", "The meeting began at noon.")
        val provider = ScriptedProvider(
            LlmEvent.ToolCallReady(infoscry.llm.ToolCall("read-1", "read_content_unit", """{"contentUnitId":"${unitId.value}"}""")),
            LlmEvent.Completed,
        )
        val persistence = FakePersistence()

        service(tools(collection), provider, persistence = persistence).investigate(
            InvestigateRequest(collection, "When did it begin?", profile()),
        ).toList()

        val result = provider.streamRequests[1].messages.single { it.role == "tool" }
        assertTrue(result.content.contains("\"evidenceId\":\"S1\""), result.content)
        assertEquals("S1", persistence.appended.single().second.evidenceEntries.single().evidenceId)
    }

    @Test
    fun `an answer citing a missing evidence id gets exactly one correction and stays invalid`() = runBlocking {
        val provider = ScriptedProvider(
            LlmEvent.TextDelta("based on [S999]"),
            LlmEvent.Usage(TokenUsage(1L, 1L)),
            LlmEvent.Completed,
            completion = LlmCompletion("still [S999]", TokenUsage(0, 0)),
        )
        val persistence = FakePersistence()
        val sv = service(tools(CollectionId("collection")), provider, persistence = persistence)

        val events = sv.investigate(request()).toList()

        assertEquals(1, provider.completionRequests.size, "expected exactly one correction call")
        val citations = events.filterIsInstance<InvestigateEvent.Citation>()
        assertTrue(citations.any { it.evidenceId == "S999" && !it.valid }, "expected S999 to be reported as an invalid citation, got $citations")
        val snapshot = persistence.appended.single().second
        assertTrue(snapshot.evidenceEntries.none { it.evidenceId == "S999" }, "an invalid citation must never enter the evidence ledger")
    }

    @Test
    fun `cancelling mid-turn stops further provider calls`() = runBlocking {
        val tc = LlmEvent.ToolCallReady(infoscry.llm.ToolCall("c1", "search_collection", """{"query":"q"}"""))
        val provider = ScriptedProvider(tc, LlmEvent.Completed)
        val sv = service(tools(CollectionId("collection")), provider)

        lateinit var job: Job
        job = launch { sv.investigate(request()).collect { job.cancel() } }
        job.join()

        val callsWhenCancelled = provider.streamRequests.size
        delay(100)
        assertEquals(callsWhenCancelled, provider.streamRequests.size, "provider must not be streamed again after cancellation")
        assertTrue(callsWhenCancelled <= 2, "cancellation must stop the turn promptly, got $callsWhenCancelled stream calls")
    }

    @Test
    fun `pruning removes multiple oldest groups in oldest-first order`() = runBlocking {
        fun q(i: Int) = "q$i" + "x".repeat(700)
        val provider = PerRoundProvider(
            listOf(
                listOf(LlmEvent.ToolCallReady(infoscry.llm.ToolCall("c1", "search_collection", """{"query":"${q(1)}"}""")), LlmEvent.Completed),
                listOf(LlmEvent.ToolCallReady(infoscry.llm.ToolCall("c2", "search_collection", """{"query":"${q(2)}"}""")), LlmEvent.Completed),
                listOf(LlmEvent.ToolCallReady(infoscry.llm.ToolCall("c3", "search_collection", """{"query":"${q(3)}"}""")), LlmEvent.Completed),
                listOf(LlmEvent.ToolCallReady(infoscry.llm.ToolCall("c4", "search_collection", """{"query":"${q(4)}"}""")), LlmEvent.Completed),
                listOf(LlmEvent.TextDelta("done"), LlmEvent.Usage(TokenUsage(1L, 1L)), LlmEvent.Completed),
            ),
        )
        val sv = service(tools(CollectionId("collection")), provider)

        sv.investigate(request(profile = profile(contextWindow = 6200))).toList()

        val answerRequest = provider.streamRequests.last()
        val presentCallIds = answerRequest.messages.flatMap { it.toolCalls }.map { it.id }.toSet()
        assertTrue("c1" !in presentCallIds, "the oldest round must be pruned from the answer request, got $presentCallIds")
        assertTrue("c2" !in presentCallIds, "more than one group must be pruned from the answer request, got $presentCallIds")
        assertTrue("c3" in presentCallIds, "the third-oldest round must survive, got $presentCallIds")
        assertTrue("c4" in presentCallIds, "the newest round must survive, got $presentCallIds")
        val presentResultIds = answerRequest.messages.filter { it.role == "tool" }.map { it.toolCallId }.toSet()
        assertEquals(presentCallIds, presentResultIds, "every surviving group must keep its tool results: ${answerRequest.messages}")
        assertTrue(provider.streamRequests.size >= 5, "expected the four-round scenario, got ${provider.streamRequests.size} stream calls")
    }

    @Test
    fun `a citation introduced by a pruned group is invalid while a surviving group stays valid`() = runBlocking {
        val collection = CollectionStore(database).create("Investigate collection").id
        val documentId = insertDocument(collection)
        val unitA = addUnit(documentId, ordinal = 0, "unit-a", "A".repeat(800))
        val unitB = addUnit(documentId, ordinal = 1, "unit-b", "B".repeat(800))
        val provider = PerRoundProvider(
            listOf(
                listOf(LlmEvent.ToolCallReady(infoscry.llm.ToolCall("cA", "read_content_unit", """{"contentUnitId":"${unitA.value}"}""")), LlmEvent.Completed),
                listOf(LlmEvent.ToolCallReady(infoscry.llm.ToolCall("cB", "read_content_unit", """{"contentUnitId":"${unitB.value}"}""")), LlmEvent.Completed),
                listOf(LlmEvent.TextDelta("found [S1] and [S2]"), LlmEvent.Usage(TokenUsage(1L, 1L)), LlmEvent.Completed),
            ),
            completion = LlmCompletion("still [S1] and [S2]", TokenUsage(0, 0)),
        )
        val sv = service(tools(collection), provider)

        val events = sv.investigate(InvestigateRequest(collection, "question", profile(contextWindow = 5600))).toList()

        val citations = events.filterIsInstance<InvestigateEvent.Citation>()
        assertTrue(
            citations.any { it.evidenceId == "S1" && !it.valid },
            "a citation to evidence pruned from its own request must be invalid, got $citations",
        )
        assertTrue(
            citations.any { it.evidenceId == "S2" && it.valid },
            "a citation to a surviving group's evidence must stay valid, got $citations",
        )
        assertEquals(1, provider.completionRequests.size, "the pruned-out citation must trigger exactly one correction")
    }

    @Test
    fun `pruning never splits a tool-call group from its results`() = runBlocking {
        val query = "q" + "x".repeat(500)
        val provider = PerRoundProvider(
            (1..11).map { i ->
                listOf(LlmEvent.ToolCallReady(infoscry.llm.ToolCall("c$i", "search_collection", """{"query":"${query}$i"}""")), LlmEvent.Completed)
            },
        )
        val sv = service(tools(CollectionId("collection")), provider)

        sv.investigate(request(profile = profile(contextWindow = 5500))).toList()

        assertTrue(provider.streamRequests.size >= 3, "expected enough rounds to exercise pruning, got ${provider.streamRequests.size}")
        for (req in provider.streamRequests) {
            val callIds = req.messages.flatMap { it.toolCalls }.map { it.id }.toSet()
            val resultIds = req.messages.filter { it.role == "tool" }.map { it.toolCallId }.toSet()
            assertEquals(callIds, resultIds, "a request must keep each tool-call group together with its results: ${req.messages}")
        }
        val firstRequest = provider.streamRequests[1]
        val lastRequest = provider.streamRequests.last()
        assertTrue(
            firstRequest.messages.any { dropped -> lastRequest.messages.none { it === dropped } },
            "expected an earlier tool exchange to be pruned from later requests",
        )
    }

    @Test
    fun `a turn that exceeds the turn timeout persists a TURN_TIMEOUT limit event`() = runBlocking {
        val persistence = FakePersistence()
        val provider = SuspendingProvider()
        // A non-positive deadline means the turn has already exceeded its time, so the first
        // boundary check must fire before any provider call, deterministically on any machine.
        val sv = service(tools(CollectionId("collection")), provider, persistence = persistence, turnTimeoutMs = -1)

        val events = sv.investigate(request()).toList()

        val snapshot = persistence.appended.single().second
        assertTrue(
            snapshot.limitEvents.any { it.eventType == "TURN_TIMEOUT" },
            "the TURN_TIMEOUT limit event must be durable, got ${snapshot.limitEvents}",
        )
        assertTrue(events.first() is InvestigateEvent.Started, "the timed-out conversation must still surface its id first")
        assertTrue(
            events.filterIsInstance<InvestigateEvent.Error>().any { it.code == "TURN_TIMEOUT" },
            "expected a TURN_TIMEOUT error event",
        )
        assertTrue(events.none { it is InvestigateEvent.Done }, "a timed-out turn must not produce a final answer")
        assertTrue(provider.streamCalls == 0, "a turn whose deadline has already passed must make zero provider calls")
    }

    @Test
    fun `a deadline that passes mid-round stops before the remaining tool calls execute`() = runBlocking {
        // The provider streams two tool calls immediately, but the first search takes long enough
        // that the turn deadline passes while it runs: the per-call deadline check must stop the
        // second call instead of letting the whole batch burn past the bound.
        val provider = ScriptedProvider(
            LlmEvent.ToolCallReady(infoscry.llm.ToolCall("c1", "search_collection", """{"query":"q1"}""")),
            LlmEvent.ToolCallReady(infoscry.llm.ToolCall("c2", "search_collection", """{"query":"q2"}""")),
            LlmEvent.Completed,
        )
        val sv = service(
            tools = InvestigationTools(CollectionId("collection"), SlowSearch(delayMs = 1_000), contentStore, documentStore),
            provider = provider,
            turnTimeoutMs = 300,
        )

        val events = sv.investigate(request()).toList()

        val executed = events.filterIsInstance<InvestigateEvent.ToolResult>().map { it.callId }
        assertEquals(listOf("c1"), executed, "only the in-flight tool call may finish once the deadline passes, got $executed")
        assertTrue(
            events.filterIsInstance<InvestigateEvent.Error>().any { it.code == "TURN_TIMEOUT" },
            "expected a TURN_TIMEOUT error, got ${events.filterIsInstance<InvestigateEvent.Error>()}",
        )
        assertTrue(provider.streamRequests.size <= 1, "no provider call may start after the deadline, got ${provider.streamRequests.size}")
        assertTrue(events.none { it is InvestigateEvent.Done }, "a timed-out turn must not produce a final answer")
    }

    private interface InvestigateProvider : LlmStreamingClient, LlmCompletionClient

    /** Serves one distinct event script per stream call, so each round can make different calls. */
    private class PerRoundProvider(
        private val rounds: List<List<LlmEvent>>,
        private val completion: LlmCompletion = LlmCompletion("", TokenUsage(0, 0)),
    ) : InvestigateProvider {
        val streamRequests = mutableListOf<LlmRequest>()
        val completionRequests = mutableListOf<LlmRequest>()
        private var nextRound = 0

        override fun stream(request: LlmRequest): Flow<LlmEvent> {
            streamRequests += request
            val script = rounds.getOrElse(nextRound) { error("no scripted round ${nextRound + 1}") }
            nextRound++
            return flowOf(*script.toTypedArray())
        }

        override suspend fun complete(request: LlmRequest): LlmCompletion {
            completionRequests += request
            return completion
        }
    }

    private class ScriptedProvider(vararg val streamed: LlmEvent, private val completion: LlmCompletion = LlmCompletion("", TokenUsage(0, 0))) : InvestigateProvider {
        val streamRequests = mutableListOf<LlmRequest>()
        val completionRequests = mutableListOf<LlmRequest>()

        override fun stream(request: LlmRequest): Flow<LlmEvent> {
            streamRequests += request
            return flowOf(*streamed)
        }

        override suspend fun complete(request: LlmRequest): LlmCompletion {
            completionRequests += request
            return completion
        }
    }

    private object FailingProvider : InvestigateProvider {
        override fun stream(request: LlmRequest): Flow<LlmEvent> =
            kotlinx.coroutines.flow.flow { throw infoscry.llm.LlmError.ProviderUnavailableError("down") }

        override suspend fun complete(request: LlmRequest): LlmCompletion = error("must not correct")
    }

    /** Never emits and never completes, so the turn deadline fires while the stream is suspended. */
    private class SuspendingProvider : InvestigateProvider {
        var streamCalls = 0

        override fun stream(request: LlmRequest): Flow<LlmEvent> {
            streamCalls++
            return kotlinx.coroutines.flow.flow { kotlinx.coroutines.delay(60_000) }
        }

        override suspend fun complete(request: LlmRequest): LlmCompletion = error("must not correct")
    }

    /** A search that blocks for [delayMs], so a turn deadline can pass while a tool call runs. */
    private class SlowSearch(private val delayMs: Long) : InvestigationSearch {
        override fun search(queryText: String, mode: SearchMode, filters: SearchFilters): SearchOutcome {
            Thread.sleep(delayMs)
            return SearchOutcome(emptyList(), 0)
        }
    }

    private fun insertDocument(collectionId: CollectionId): DocumentId {
        val document = Document(
            id = DocumentId.new(),
            collectionId = collectionId,
            sha256 = "f".repeat(64),
            mediaType = "application/pdf",
            originalFilename = "minutes.pdf",
            sourcePath = "/secret/original/path/minutes.pdf",
            sizeBytes = 1_000,
            status = DocumentStatus.COMPLETE,
            createdAt = Instants.now(),
            updatedAt = Instants.now(),
            title = null,
            author = "author",
            language = "en",
        )
        documentStore.insert(document)
        return document.id
    }

    private fun addUnit(documentId: DocumentId, ordinal: Int, key: String, text: String): ContentUnitId {
        val fingerprint = ExtractionFingerprint.of(
            sha256 = "f".repeat(64),
            settings = ExtractionSettings(ocrLanguages = "eng"),
        )
        val commit = contentStore.commitExtractedUnit(
            documentId = documentId,
            fingerprint = fingerprint,
            key = key,
            ordinal = ordinal,
            draft = ContentUnitDraft(locator = SourceLocation.TextLines(1, 2), extractedText = text, searchText = text),
            artifactRoot = directory.resolve("artifacts"),
        )
        return commit.unit.id
    }

    private class FakeSearch(private val collectionId: CollectionId) : InvestigationSearch {
        override fun search(queryText: String, mode: SearchMode, filters: SearchFilters): SearchOutcome {
            return SearchOutcome(emptyList(), 0)
        }
    }

    private class FakePersistence(
        private val historyByConversationId: Map<String, InvestigateHistory> = emptyMap(),
    ) : InvestigatePersistence {
        data class CreatedConversation(
            val collectionId: CollectionId,
            val profile: LlmProfile,
            val promptVersion: Int,
            val retrievalSnapshot: String,
        )

        val created = mutableListOf<CreatedConversation>()
        val appended = mutableListOf<Pair<String, InvestigateTurnSnapshot>>()
        val loaded = mutableListOf<String>()

        override fun createConversation(
            collectionId: CollectionId,
            profile: LlmProfile,
            promptVersion: Int,
            retrievalSnapshot: String,
        ): String {
            created += CreatedConversation(collectionId, profile, promptVersion, retrievalSnapshot)
            return "conv-id"
        }

        override fun appendTurn(conversationId: String, snapshot: InvestigateTurnSnapshot) {
            appended += conversationId to snapshot
        }

        override fun loadHistory(conversationId: String): InvestigateHistory? {
            loaded += conversationId
            return historyByConversationId[conversationId]
        }
    }
}
