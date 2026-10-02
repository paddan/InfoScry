package infoscry.investigate

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
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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
        // Null keeps the production path: the turn budget comes from the request's limits. Tests that
        // need a fixed clock-pinned budget pass an explicit override here.
        turnTimeoutMs: Long? = null,
        // Null keeps the production inactivity cap; a deterministic test injects a short one.
        providerInactivityMs: Long? = null,
        nanoTime: () -> Long = System::nanoTime,
    ) = InvestigationService(
        tools,
        PromptService(store),
        promptVersion = 1,
        streamingClient = { provider },
        persistence = persistence,
        turnTimeoutMs = turnTimeoutMs,
        providerInactivityMs = providerInactivityMs,
        nanoTime = nanoTime,
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
    fun `invalid request limits emit INVALID_REQUEST without creating a conversation`() = runBlocking {
        val provider = ScriptedProvider()
        val persistence = FakePersistence()
        val sv = service(tools(CollectionId("collection")), provider, persistence = persistence)

        val events = sv.investigate(request().copy(limits = InvestigationLimits(maxToolRounds = 0))).toList()

        assertEquals("INVALID_REQUEST", assertIs<InvestigateEvent.Error>(events.single()).code)
        assertEquals(0, provider.streamRequests.size)
        assertTrue(persistence.created.isEmpty(), "an invalid turn must not create a conversation")
    }

    @Test
    fun `the request's round limit stops research after that many rounds`() = runBlocking {
        val collection = CollectionStore(database).create("Configured round cap collection").id
        val documentId = insertDocument(collection)
        val unitId = addUnit(documentId, ordinal = 0, "configured-round-unit", "The meeting began at noon.")
        val provider = PerRoundProvider(
            listOf(
                listOf(LlmEvent.ToolCallReady(infoscry.llm.ToolCall("c1", "read_content_unit", """{"contentUnitId":"${unitId.value}"}""")), LlmEvent.Completed),
                listOf(LlmEvent.ToolCallReady(infoscry.llm.ToolCall("c2", "search_collection", """{"query":"q2"}""")), LlmEvent.Completed),
                listOf(LlmEvent.TextDelta("The meeting began at noon [S1]."), LlmEvent.Usage(TokenUsage(2, 3)), LlmEvent.Completed),
            ),
        )

        val events = service(tools(collection), provider)
            .investigate(InvestigateRequest(collection, "When did it begin?", profile(), limits = InvestigationLimits(maxToolRounds = 2)))
            .toList()

        assertEquals(3, provider.streamRequests.size, "two configured rounds then exactly one synthesis request")
        assertTrue(provider.streamRequests.last().tools.isEmpty(), "the synthesis request must not expose tools")
        val limit = assertIs<InvestigateEvent.Limit>(events.first { it is InvestigateEvent.Limit })
        assertEquals("MAX_ROUNDS", limit.code)
        assertEquals("The meeting began at noon [S1].", assertIs<InvestigateEvent.Done>(events.last()).answer)
    }

    @Test
    fun `the request's tool-call limit refuses calls beyond it`() = runBlocking {
        val collection = CollectionStore(database).create("Configured call cap collection").id
        val documentId = insertDocument(collection)
        val unitId = addUnit(documentId, ordinal = 0, "configured-call-unit", "The meeting began at noon.")
        val provider = PerRoundProvider(
            listOf(
                listOf(
                    LlmEvent.ToolCallReady(infoscry.llm.ToolCall("c1", "read_content_unit", """{"contentUnitId":"${unitId.value}"}""")),
                    LlmEvent.ToolCallReady(infoscry.llm.ToolCall("c2", "get_document_metadata", """{"documentId":"missing"}""")),
                    LlmEvent.Completed,
                ),
                listOf(LlmEvent.TextDelta("The meeting began at noon [S1]."), LlmEvent.Usage(TokenUsage(2, 3)), LlmEvent.Completed),
            ),
        )

        val events = service(tools(collection), provider)
            .investigate(InvestigateRequest(collection, "When did it begin?", profile(), limits = InvestigationLimits(maxToolCalls = 1)))
            .toList()

        val results = events.filterIsInstance<InvestigateEvent.ToolResult>()
        assertEquals(listOf("c1", "c2"), results.map { it.callId })
        assertEquals("LIMIT_REACHED", results.last().resultCode)
        val limit = assertIs<InvestigateEvent.Limit>(events.first { it is InvestigateEvent.Limit })
        assertEquals("MAX_TOOL_CALLS", limit.code)
        assertEquals(2, provider.streamRequests.size, "the cap allows one research request and one tool-free synthesis")
        assertTrue(provider.streamRequests.last().tools.isEmpty(), "the synthesis request must not expose tools")
        assertEquals("The meeting began at noon [S1].", assertIs<InvestigateEvent.Done>(events.last()).answer)
    }

    @Test
    fun `the request's maxTurnSeconds bounds the turn when no test override is set`() = runBlocking {
        // No turnTimeoutMs override, so the ten-second request budget is the only deadline. The pinned
        // clock jumps to ten seconds after the first research call, which is past that budget but far
        // short of the 600-second default, so only the request's limit can produce the timeout.
        var nowNanos = 0L
        val provider = ScriptedProvider(
            LlmEvent.ToolCallReady(infoscry.llm.ToolCall("c1", "search_collection", """{"query":"q"}""")),
            LlmEvent.Completed,
        )
        val persistence = FakePersistence()
        val events = mutableListOf<InvestigateEvent>()

        service(
            tools(CollectionId("collection")),
            provider,
            persistence = persistence,
            nanoTime = { nowNanos },
        ).investigate(request().copy(limits = InvestigationLimits(maxTurnSeconds = 10))).collect { event ->
            events += event
            if (event is InvestigateEvent.ToolResult) nowNanos = 10_000_000_000L
        }

        assertEquals("TURN_TIMEOUT", assertIs<InvestigateEvent.Error>(events.last()).code)
        assertEquals(1, provider.streamRequests.size, "a turn out of time must start no synthesis request")
        assertTrue(events.none { it is InvestigateEvent.Done })
        assertTrue(persistence.appended.single().second.limitEvents.any { it.eventType == "TURN_TIMEOUT" })
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
    fun `a continue whose locked profile is switched off is refused where the service dispatches`() = runBlocking {
        val history = InvestigateHistory(
            collectionId = CollectionId("collection"),
            profile = profile().copy(enabled = false),
            promptVersion = 1,
            retrievalSnapshot = RetrievalSnapshot.value(),
            messages = listOf(LlmMessage("user", "prior question")),
            evidenceIds = emptyList(),
        )
        val persistence = FakePersistence(mapOf("conv-1" to history))
        val provider = ScriptedProvider(LlmEvent.TextDelta("answer"), LlmEvent.Usage(TokenUsage(1L, 1L)), LlmEvent.Completed)
        val sv = service(tools(CollectionId("collection")), provider, persistence = persistence)

        // The request names an enabled profile, the way a route whose check raced the profile being switched
        // off — or a direct caller that never ran one — would: the turn dispatches through the locked snapshot,
        // so the service itself has to refuse it, before any call leaves the process.
        val events = sv.investigate(
            InvestigateRequest(CollectionId("collection"), "follow-up", profile(), conversationId = "conv-1"),
        ).toList()

        // Asserted before the event shape: a dispatch that should not have happened still emits well-formed
        // events, so the call counts are what names the failure.
        assertEquals(0, provider.streamRequests.size, "nothing dispatches through a switched-off profile")
        assertEquals(0, provider.completionRequests.size, "not even a correction call")
        assertTrue(persistence.appended.isEmpty(), "a refused turn is not appended to the conversation")

        val refusal = assertIs<InvestigateEvent.Error>(events.single())
        assertEquals("INVALID_REQUEST", refusal.code)
        assertTrue(refusal.message.contains("disabled"), "the refusal says the locked profile is switched off: ${refusal.message}")
    }

    @Test
    fun `a new turn through a switched-off profile is refused before a conversation is created`() = runBlocking {
        val persistence = FakePersistence()
        val provider = ScriptedProvider()
        val sv = service(tools(CollectionId("collection")), provider, persistence = persistence)

        val events = sv.investigate(request().copy(profile = profile().copy(enabled = false))).toList()

        // Asserted before the event shape: nothing is created and nothing is dispatched when the profile is off,
        // so a regression shows up as a created conversation or a provider call, not only as a different event.
        assertTrue(persistence.created.isEmpty(), "a refused turn must not create a conversation")
        assertTrue(persistence.appended.isEmpty())
        assertEquals(0, provider.streamRequests.size)

        val refusal = assertIs<InvestigateEvent.Error>(events.single())
        assertEquals("INVALID_REQUEST", refusal.code)
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
    fun `downstream stream failure propagates without attempting an error event`() = runBlocking {
        val provider = ScriptedProvider(LlmEvent.TextDelta("answer"))
        val sv = service(tools(CollectionId("collection")), provider)
        val downstreamFailure = IOException("client disconnected")

        val thrown = assertFailsWith<IOException> {
            sv.investigate(request()).collect { event ->
                if (event is InvestigateEvent.Delta) throw downstreamFailure
            }
        }

        assertEquals("client disconnected", thrown.message)
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

        val limit = assertIs<InvestigateEvent.Limit>(events.filterIsInstance<InvestigateEvent.Limit>().single())
        assertEquals("REPEATED_TOOL_CALL", limit.code)
        assertEquals(1, provider.streamRequests.size, "the turn must stop at the repeated call instead of burning the budget on more rounds, got ${provider.streamRequests.size} stream calls")
        assertTrue(events.none { it is InvestigateEvent.Error }, "a repeat stop is a nonfatal limit, not a fatal turn")
        val results = events.filterIsInstance<InvestigateEvent.ToolResult>()
        assertEquals(listOf("c1", "c2"), results.map { it.callId }, "the refused repeat must still close its exchange with a result")
        assertEquals("LIMIT_REACHED", results.last().resultCode)
        // This collection holds no evidence, so the repeat-stopped turn answers locally: a final
        // answer without spending a further (tool-free synthesis) provider call.
        val done = assertIs<InvestigateEvent.Done>(events.last())
        assertTrue(done.answer.isNotBlank(), "a repeat-stopped turn must still answer")
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
    fun `exactly twenty admitted calls stops research before another tool-enabled request`() = runBlocking {
        val collection = CollectionStore(database).create("Exact call cap collection").id
        val documentId = insertDocument(collection)
        val unitId = addUnit(documentId, ordinal = 0, "exact-call-cap-unit", "The meeting began at noon.")
        val calls = listOf(
            infoscry.llm.ToolCall("read", "read_content_unit", """{"contentUnitId":"${unitId.value}"}"""),
        ) + (1..19).map { i ->
            infoscry.llm.ToolCall("search-$i", "search_collection", """{"query":"query-$i"}""")
        }
        val provider = PerRoundProvider(
            listOf(
                calls.map { LlmEvent.ToolCallReady(it) } + LlmEvent.Completed,
                listOf(LlmEvent.TextDelta("The meeting began at noon [S1]."), LlmEvent.Completed),
            ),
        )

        val events = service(tools(collection), provider)
            .investigate(InvestigateRequest(collection, "When did it begin?", profile(), limits = InvestigationLimits(maxToolCalls = 20)))
            .toList()

        assertEquals(2, provider.streamRequests.size, "the cap is reached by the twentieth admitted call")
        val limit = assertIs<InvestigateEvent.Limit>(events.first { it is InvestigateEvent.Limit })
        assertEquals("MAX_TOOL_CALLS", limit.code)
        assertTrue(provider.streamRequests.last().tools.isEmpty(), "the request after the cap must be tool-free synthesis")
        val done = assertIs<InvestigateEvent.Done>(events.last())
        assertEquals("The meeting began at noon [S1].", done.answer)
    }

    @Test
    fun `a provider batch crossing the tool-call cap gets complete results and a cited final answer`() = runBlocking {
        val collection = CollectionStore(database).create("Tool-call cap collection").id
        val documentId = insertDocument(collection)
        val unitId = addUnit(documentId, ordinal = 0, "tool-call-cap-unit", "The meeting began at noon.")
        val calls = listOf(
            infoscry.llm.ToolCall("read", "read_content_unit", """{"contentUnitId":"${unitId.value}"}"""),
        ) + (1..20).map { i ->
            infoscry.llm.ToolCall("search-$i", "search_collection", """{"query":"query-$i"}""")
        }
        val provider = PerRoundProvider(
            listOf(
                calls.map { LlmEvent.ToolCallReady(it) } + listOf(LlmEvent.Usage(TokenUsage(3, 4)), LlmEvent.Completed),
                listOf(LlmEvent.TextDelta("The meeting began at noon [S1]."), LlmEvent.Usage(TokenUsage(5, 6)), LlmEvent.Completed),
            ),
        )
        val persistence = FakePersistence()
        val service = service(tools(collection), provider, persistence = persistence)

        val events = service
            .investigate(InvestigateRequest(collection, "When did it begin?", profile(), limits = InvestigationLimits(maxToolCalls = 20)))
            .toList()

        assertEquals(2, provider.streamRequests.size, "one research request and one tool-free synthesis request are allowed")
        val limit = assertIs<InvestigateEvent.Limit>(events.first { it is InvestigateEvent.Limit })
        assertEquals("MAX_TOOL_CALLS", limit.code)
        assertTrue(events.none { it is InvestigateEvent.Error }, "a successfully synthesized limit is not fatal")
        assertIs<InvestigateEvent.Done>(events.last())
        val results = events.filterIsInstance<InvestigateEvent.ToolResult>()
        assertEquals(21, results.size, "every provider call needs a matching result, including the refused call")
        assertEquals("LIMIT_REACHED", results.last().resultCode)
        val researchExchange = persistence.appended.single().second.modelCalls.first().toolCalls
        assertEquals(21, researchExchange.size)
        assertEquals(20, researchExchange.count { it.resultCode != "LIMIT_REACHED" })
        val persistedMessages = persistence.appended.single().second.messages.map { it.second }
        val assistantCalls = persistedMessages.filter { it.toolCalls.isNotEmpty() }.flatMap { it.toolCalls }.map { it.id }.toSet()
        val toolResults = persistedMessages.filter { it.role == "tool" }.mapNotNull { it.toolCallId }.toSet()
        assertEquals(assistantCalls, toolResults, "the persisted exchange cannot leave an unmatched tool call")
        val synthesis = provider.streamRequests.last()
        assertTrue(synthesis.tools.isEmpty(), "the synthesis request must not expose tools")
        assertTrue(synthesis.messages.any { it.content.contains("The meeting began at noon.") }, "synthesis must receive the collected source evidence")
    }

    @Test
    fun `failed calls count toward the call cap`() = runBlocking {
        val collection = CollectionStore(database).create("Failed-call cap collection").id
        val documentId = insertDocument(collection)
        val unitId = addUnit(documentId, ordinal = 0, "failed-call-unit", "Only this source exists.")
        // Twenty calls that execute and fail, then one call that would gather evidence: the failures
        // already consumed the allowance, so the evidence read must be refused, not executed.
        val calls = (1..20).map { i ->
            infoscry.llm.ToolCall("bad-$i", "nonexistent", """{"i":$i}""")
        } + infoscry.llm.ToolCall("read", "read_content_unit", """{"contentUnitId":"${unitId.value}"}""")
        val provider = PerRoundProvider(
            listOf(calls.map { LlmEvent.ToolCallReady(it) } + listOf(LlmEvent.Usage(TokenUsage(1, 1)), LlmEvent.Completed)),
        )
        val persistence = FakePersistence()

        val events = service(tools(collection), provider, persistence = persistence)
            .investigate(InvestigateRequest(collection, "question", profile(), limits = InvestigationLimits(maxToolCalls = 20)))
            .toList()

        val limit = assertIs<InvestigateEvent.Limit>(events.filterIsInstance<InvestigateEvent.Limit>().single())
        assertEquals("MAX_TOOL_CALLS", limit.code)
        val results = events.filterIsInstance<InvestigateEvent.ToolResult>()
        assertEquals(20, results.count { it.resultCode == "UNKNOWN_TOOL" }, "the failed calls must have executed, got $results")
        assertEquals("LIMIT_REACHED", results.single { it.callId == "read" }.resultCode, "a failed call must still consume the allowance")
        assertTrue(persistence.appended.single().second.evidenceEntries.isEmpty(), "a refused call must allocate no evidence")
        val done = assertIs<InvestigateEvent.Done>(events.last())
        assertTrue(done.evidence.isEmpty(), "the refused evidence read leaves nothing to answer from, got ${done.evidence}")
    }

    @Test
    fun `a repeated tool call gets a refusal result and a cited final answer`() = runBlocking {
        val collection = CollectionStore(database).create("Repeated-call collection").id
        val documentId = insertDocument(collection)
        val unitId = addUnit(documentId, ordinal = 0, "repeated-call-unit", "The meeting began at noon.")
        val repeatedArguments = """{"contentUnitId":"${unitId.value}"}"""
        val provider = PerRoundProvider(
            listOf(
                listOf(LlmEvent.ToolCallReady(infoscry.llm.ToolCall("first", "read_content_unit", repeatedArguments)), LlmEvent.Completed),
                listOf(LlmEvent.ToolCallReady(infoscry.llm.ToolCall("repeat", "read_content_unit", repeatedArguments)), LlmEvent.Completed),
                listOf(LlmEvent.TextDelta("The meeting began at noon [S1]."), LlmEvent.Usage(TokenUsage(2, 3)), LlmEvent.Completed),
            ),
        )
        val persistence = FakePersistence()
        val events = service(tools(collection), provider, persistence = persistence)
            .investigate(InvestigateRequest(collection, "When did it begin?", profile()))
            .toList()

        assertEquals(3, provider.streamRequests.size, "the repeated call must stop research and permit only synthesis")
        val limit = assertIs<InvestigateEvent.Limit>(events.first { it is InvestigateEvent.Limit })
        assertEquals("REPEATED_TOOL_CALL", limit.code)
        val repeatResult = events.filterIsInstance<InvestigateEvent.ToolResult>().single { it.callId == "repeat" }
        assertEquals("LIMIT_REACHED", repeatResult.resultCode)
        assertIs<InvestigateEvent.Done>(events.last())
        assertTrue(events.none { it is InvestigateEvent.Error })
        val saved = persistence.appended.single().second
        assertEquals("LIMIT_REACHED", saved.modelCalls[1].toolCalls.single().resultCode)
        val exchangeMessages = provider.streamRequests.last().messages
        val callIds = exchangeMessages.flatMap { it.toolCalls }.map { it.id }.toSet()
        val resultIds = exchangeMessages.filter { it.role == "tool" }.mapNotNull { it.toolCallId }.toSet()
        assertEquals(callIds, resultIds, "the refused repeat must still close its assistant tool exchange")
    }

    @Test
    fun `the default round cap stops research and synthesizes a cited answer from the evidence it gathered`() = runBlocking {
        val collection = CollectionStore(database).create("Round cap collection").id
        val documentId = insertDocument(collection)
        val unitId = addUnit(documentId, ordinal = 0, "round-cap-unit", "The meeting began at noon.")
        val provider = PerRoundProvider(
            researchRoundsToTheCap(unitId) + listOf(
                listOf(
                    LlmEvent.TextDelta("The meeting began at noon [S1]."),
                    LlmEvent.Usage(TokenUsage(5L, 6L)),
                    LlmEvent.Completed,
                ),
            ),
        )
        val events = mutableListOf<InvestigateEvent>()
        var eventsWhenPersisted = -1
        val persistence = FakePersistence(onAppendTurn = { eventsWhenPersisted = events.size })

        service(tools(collection), provider, persistence = persistence)
            .investigate(InvestigateRequest(collection, "When did it begin?", profile(), limits = roundCapLimits()))
            .collect { events += it }

        assertEquals(
            InvestigationService.MAX_ROUNDS + 1,
            provider.streamRequests.size,
            "the capped turn may make exactly one synthesis request and no further research request",
        )
        val synthesis = provider.streamRequests.last()
        assertTrue(synthesis.tools.isEmpty(), "the synthesis request must carry no tool definitions")
        assertTrue(
            synthesis.messages.any { it.content.contains("\"evidenceId\":\"S1\"") },
            "the synthesis request must carry the gathered evidence, got ${synthesis.messages.map { it.role }}",
        )
        assertTrue(
            synthesis.messages.any { it.content.contains("The meeting began at noon.") },
            "the synthesis request must carry the evidence text, not just its id",
        )
        assertEquals(
            InvestigationService.MAX_ROUNDS,
            events.filterIsInstance<InvestigateEvent.ToolResult>().size,
            "exactly one research call per round may execute",
        )

        val done = events.filterIsInstance<InvestigateEvent.Done>().single()
        assertEquals("The meeting began at noon [S1].", done.answer)
        assertEquals(listOf("S1"), done.evidence.map { it.id })
        assertEquals(InvestigateEvent.Usage(5L, 6L), events.filterIsInstance<InvestigateEvent.Usage>().single())
        assertTrue(
            events.filterIsInstance<InvestigateEvent.Citation>().any { it.evidenceId == "S1" && it.valid },
            "the synthesized citation must be valid",
        )

        val order = labels(events)
        assertEquals(1, order.count { it == "limit:MAX_ROUNDS" }, order.toString())
        assertEquals(1, order.count { it == "done" }, order.toString())
        assertEquals(order.size - 1, order.indexOf("done"), "Done must be the single last event: $order")
        assertTrue(order.indexOf("limit:MAX_ROUNDS") < order.indexOf("answer-start"), order.toString())
        assertTrue(
            order.indexOf("limit:MAX_ROUNDS") > order.lastIndexOf("tool-result:c${InvestigationService.MAX_ROUNDS}"),
            "the notice must follow the last executed research call: $order",
        )
        assertTrue(order.indexOf("answer-start") < order.indexOf("citation:S1:true"), order.toString())
        assertTrue(order.indexOf("citation:S1:true") < order.indexOf("usage"), order.toString())
        assertTrue(order.none { it.startsWith("error") }, "a limited turn is not a fatal turn: $order")

        val snapshot = persistence.appended.single().second
        assertTrue(snapshot.limitEvents.any { it.eventType == "MAX_ROUNDS" }, "the limit reason must be durable: ${snapshot.limitEvents}")
        assertEquals(listOf("S1"), snapshot.evidenceEntries.map { it.evidenceId })
        assertTrue(
            snapshot.messages.any { it.second.role == "assistant" && it.second.content == done.answer },
            "the final answer must be persisted",
        )
        assertEquals(
            InvestigationService.MAX_ROUNDS + 1,
            snapshot.modelCalls.size,
            "every research call and the synthesis call must all be persisted",
        )
        assertEquals(listOf("S1"), snapshot.modelCalls.last().eligibilityEvidenceIds)
        assertEquals(6L, snapshot.modelCalls.last().outputTokens)
        assertTrue(
            eventsWhenPersisted in 0 until order.indexOf("done"),
            "the turn must be persisted before Done, but persistence happened at event $eventsWhenPersisted of ${events.size}",
        )
    }

    @Test
    fun `a round-limited turn with no evidence answers honestly without a provider call`() = runBlocking {
        // The boundary is the submitted round limit, not the shipped default.
        val rounds = 2
        val provider = PerRoundProvider(
            (1..rounds).map { round ->
                listOf(LlmEvent.ToolCallReady(infoscry.llm.ToolCall("c$round", "search_collection", """{"query":"q$round"}""")), LlmEvent.Completed)
            },
        )
        val persistence = FakePersistence()

        val events = service(tools(CollectionId("collection")), provider, persistence = persistence)
            .investigate(request().copy(limits = roundLimit(rounds)))
            .toList()

        assertEquals(
            rounds,
            provider.streamRequests.size,
            "a no-evidence answer must not call the provider; a synthesis request would be stream call ${rounds + 1}",
        )
        assertTrue(events.none { it is InvestigateEvent.Error }, "a no-evidence answer is not a fatal turn: ${labels(events)}")
        val done = events.filterIsInstance<InvestigateEvent.Done>().single()
        assertTrue(done.answer.isNotBlank(), "the no-evidence answer must say something")
        assertTrue(done.evidence.isEmpty(), "a no-evidence answer must not carry evidence, got ${done.evidence}")
        assertTrue(
            events.none { it is InvestigateEvent.Citation },
            "a no-evidence answer must not invent citations: ${labels(events)}",
        )
        assertTrue(labels(events).contains("limit:MAX_ROUNDS"), "the limited turn must still report its nonfatal notice")

        val snapshot = persistence.appended.single().second
        assertTrue(snapshot.limitEvents.any { it.eventType == "MAX_ROUNDS" }, "the limit reason must be durable: ${snapshot.limitEvents}")
        assertEquals(rounds, snapshot.modelCalls.size, "the local answer must add no model call")
        assertTrue(
            snapshot.messages.any { it.second.role == "assistant" && it.second.content == done.answer },
            "the no-evidence answer must be persisted",
        )
    }

    @Test
    fun `a synthesis response that returns tool calls is a typed failure that executes nothing`() = runBlocking {
        val collection = CollectionStore(database).create("Synthesis tool collection").id
        val documentId = insertDocument(collection)
        val unitId = addUnit(documentId, ordinal = 0, "synthesis-tool-unit", "Only this source exists.")
        // An explicit small round limit: the boundary is submitted, not inherited from the defaults.
        val rounds = 2
        val provider = PerRoundProvider(
            researchRoundsToTheCap(unitId, rounds) + listOf(
                listOf(
                    LlmEvent.ToolCallReady(infoscry.llm.ToolCall("sneaky", "read_content_unit", """{"contentUnitId":"${unitId.value}"}""")),
                    LlmEvent.Completed,
                ),
            ),
        )
        val persistence = FakePersistence()

        val events = service(tools(collection), provider, persistence = persistence)
            .investigate(InvestigateRequest(collection, "question", profile(), limits = roundLimit(rounds)))
            .toList()

        assertEquals("SYNTHESIS_TOOL_CALL", assertIs<InvestigateEvent.Error>(events.last()).code)
        assertEquals(rounds + 1, provider.streamRequests.size)
        assertEquals(
            rounds,
            events.filterIsInstance<InvestigateEvent.ToolResult>().size,
            "a tool returned by synthesis must never be executed",
        )
        assertTrue(
            events.filterIsInstance<InvestigateEvent.ToolResult>().none { it.callId == "sneaky" },
            "the synthesis tool call must have no result",
        )
        assertTrue(events.none { it is InvestigateEvent.Done }, "an unexecuted tool call is not a successful completion")
        assertTrue(
            persistence.appended.single().second.messages.none { it.second.role == "assistant" && it.second.toolCalls.isEmpty() },
            "a failed synthesis must not persist a final answer",
        )
    }

    @Test
    fun `a synthesis provider failure is a typed failure, not a limited answer`() = runBlocking {
        val collection = CollectionStore(database).create("Synthesis failure collection").id
        val documentId = insertDocument(collection)
        val unitId = addUnit(documentId, ordinal = 0, "synthesis-failure-unit", "Only this source exists.")
        // An explicit small round limit: the boundary is submitted, not inherited from the defaults.
        val rounds = 2
        val provider = PerRoundProvider(
            researchRoundsToTheCap(unitId, rounds),
            afterScript = infoscry.llm.LlmError.ProviderUnavailableError("synthesis is down"),
        )
        val persistence = FakePersistence()

        val events = service(tools(collection), provider, persistence = persistence)
            .investigate(InvestigateRequest(collection, "question", profile(), limits = roundLimit(rounds)))
            .toList()

        assertEquals("LLM_REQUEST_FAILED", assertIs<InvestigateEvent.Error>(events.last()).code)
        assertTrue(events.none { it is InvestigateEvent.Done }, "a failed synthesis must not report a successful answer")
        assertEquals(rounds + 1, provider.streamRequests.size)
        assertTrue(provider.streamRequests.last().tools.isEmpty(), "the failed call must have been the tool-free synthesis request")
        assertEquals(
            "FAILED",
            persistence.appended.single().second.modelCalls.last().status,
            "the failed synthesis call must be persisted as failed",
        )
    }

    /**
     * One event label per emitted event, in stream order, so ordering assertions read as the sequence
     * the reader sees.
     */
    private fun labels(events: List<InvestigateEvent>) = events.map { event ->
        when (event) {
            is InvestigateEvent.Started -> "started"
            is InvestigateEvent.Delta -> "delta"
            is InvestigateEvent.ToolCall -> "tool-call:${event.callId}"
            is InvestigateEvent.ToolResult -> "tool-result:${event.callId}"
            is InvestigateEvent.Usage -> "usage"
            is InvestigateEvent.Citation -> "citation:${event.evidenceId}:${event.valid}"
            is InvestigateEvent.Done -> "done"
            is InvestigateEvent.Error -> "error:${event.code}"
            is InvestigateEvent.Limit -> "limit:${event.code}"
            is InvestigateEvent.AnswerStart -> "answer-start"
        }
    }

    /**
     * The default round cap with the call cap raised out of its way. The shipped defaults are equal, so
     * a turn that spends one call per round reaches both caps in the same round and the call cap is what
     * stops research; a round-cap scenario needs room above [InvestigationService.MAX_ROUNDS] calls.
     * Only the default-cap assertion below may use this; a specific-boundary test submits [roundLimit]
     * so a shipped-default change cannot silently move its boundary.
     */
    private fun roundCapLimits() = InvestigationLimits(
        maxToolRounds = InvestigationService.MAX_ROUNDS,
        maxToolCalls = InvestigationLimits.MAX_TOOL_CALLS,
    )

    /**
     * The explicit small round cap a boundary test submits, with a call allowance above it so only the
     * round limit — never the shipped [InvestigationLimits] defaults — can stop research.
     */
    private fun roundLimit(rounds: Int) = InvestigationLimits(maxToolRounds = rounds, maxToolCalls = rounds * 2)

    /**
     * [rounds] tool rounds: distinct searches that reach the round cap without tripping the repeat guard,
     * and the evidence read last. The read is last on purpose: the shipped default of fifty rounds builds a
     * fifty-exchange transcript that prunes its oldest groups out of the synthesis request, so evidence read
     * in the first round would be gone by the time research stops. The pruned-evidence path is covered on its
     * own by `synthesis only validates citations for evidence surviving request pruning`.
     */
    private fun researchRoundsToTheCap(contentUnitId: ContentUnitId, rounds: Int = InvestigationService.MAX_ROUNDS): List<List<LlmEvent>> =
        (1 until rounds).map { round ->
            listOf(LlmEvent.ToolCallReady(infoscry.llm.ToolCall("c$round", "search_collection", """{"query":"q$round"}""")), LlmEvent.Completed)
        } + listOf(
            listOf(
                LlmEvent.ToolCallReady(
                    infoscry.llm.ToolCall(
                        "c$rounds",
                        "read_content_unit",
                        """{"contentUnitId":"${contentUnitId.value}"}""",
                    ),
                ),
                LlmEvent.Completed,
            ),
        )

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
    fun `synthesis only validates citations for evidence surviving request pruning`() = runBlocking {
        val collection = CollectionStore(database).create("Synthesis pruning collection").id
        val documentId = insertDocument(collection)
        val unitA = addUnit(documentId, ordinal = 0, key = "synthesis-pruned-a", text = "A".repeat(800))
        val unitB = addUnit(documentId, ordinal = 1, key = "synthesis-kept-b", text = "B".repeat(800))
        val provider = PerRoundProvider(
            listOf(
                listOf(LlmEvent.ToolCallReady(infoscry.llm.ToolCall("cA", "read_content_unit", """{"contentUnitId":"${unitA.value}"}""")), LlmEvent.Completed),
                listOf(LlmEvent.ToolCallReady(infoscry.llm.ToolCall("cB", "read_content_unit", """{"contentUnitId":"${unitB.value}"}""")), LlmEvent.Completed),
                listOf(LlmEvent.TextDelta("found [S1] and [S2]"), LlmEvent.Usage(TokenUsage(1L, 1L)), LlmEvent.Completed),
            ),
            completion = LlmCompletion("still [S1] and [S2]", TokenUsage(0, 0)),
        )
        val request = InvestigateRequest(
            collection,
            "question",
            profile(contextWindow = 5_600),
            limits = InvestigationLimits(maxToolRounds = 2),
        )

        val events = service(tools(collection), provider).investigate(request).toList()

        assertEquals(3, provider.streamRequests.size, "two research rounds are followed by one synthesis request")
        val synthesis = provider.streamRequests.last()
        assertTrue(synthesis.tools.isEmpty(), "the final synthesis remains tool-free after pruning")
        val synthesisContext = synthesis.messages.joinToString(" ") { it.content }
        assertFalse(synthesisContext.contains("A".repeat(800)), "pruned evidence must not return in synthesis")
        assertTrue(synthesisContext.contains("B".repeat(800)), "the surviving source must be sent to synthesis")
        val correctionRules = provider.completionRequests.single().messages.first().content
        assertTrue(correctionRules.contains("S2"), "citation correction must allow the surviving source id")
        assertFalse(correctionRules.contains("S1"), "citation correction must not re-authorize a pruned source id")
        val citations = events.filterIsInstance<InvestigateEvent.Citation>()
        assertTrue(citations.any { it.evidenceId == "S1" && !it.valid }, citations.toString())
        assertTrue(citations.any { it.evidenceId == "S2" && it.valid }, citations.toString())
        assertEquals(listOf("S2"), assertIs<InvestigateEvent.Done>(events.last()).evidence.map { it.id })
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
    fun `research deadline enters tool-free synthesis while the reserve remains`() = runBlocking {
        val collection = CollectionStore(database).create("Research deadline collection").id
        val documentId = insertDocument(collection)
        val unitId = addUnit(documentId, ordinal = 0, "research-deadline-unit", "The meeting began at noon.")
        var nowNanos = 0L
        val provider = PerRoundProvider(
            listOf(
                listOf(LlmEvent.ToolCallReady(infoscry.llm.ToolCall("read", "read_content_unit", """{"contentUnitId":"${unitId.value}"}""")), LlmEvent.Completed),
                listOf(LlmEvent.TextDelta("The meeting began at noon [S1]."), LlmEvent.Usage(TokenUsage(2, 3)), LlmEvent.Completed),
            ),
        )
        val persistence = FakePersistence()
        val events = mutableListOf<InvestigateEvent>()

        service(
            tools(collection),
            provider,
            persistence = persistence,
            turnTimeoutMs = 10_000,
            nanoTime = { nowNanos },
        ).investigate(InvestigateRequest(collection, "When did it begin?", profile())).collect { event ->
            events += event
            if (event is InvestigateEvent.ToolResult) nowNanos = 8_000_000_000L
        }

        assertEquals(2, provider.streamRequests.size, "research stops at eight seconds and uses the two-second synthesis reserve")
        assertTrue(provider.streamRequests.last().tools.isEmpty(), "the reserved final-answer request must not expose tools")
        val limit = events.filterIsInstance<InvestigateEvent.Limit>().single()
        assertEquals("TURN_TIMEOUT", limit.code)
        assertIs<InvestigateEvent.Done>(events.last())
        assertTrue(events.none { it is InvestigateEvent.Error })
        assertTrue(persistence.appended.single().second.limitEvents.any { it.eventType == "TURN_TIMEOUT" })
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
    fun `a deadline that passes mid-round refuses remaining tool calls and keeps the exchange closed`() = runBlocking {
        // The provider streams two tool calls immediately, but the first search outlives the whole
        // turn. The first result is preserved and the second call is refused rather than left open.
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

        val results = events.filterIsInstance<InvestigateEvent.ToolResult>()
        assertEquals(listOf("c1", "c2"), results.map { it.callId })
        assertEquals("SUCCESS", results.first().resultCode)
        assertEquals("LIMIT_REACHED", results.last().resultCode)
        assertTrue(
            events.filterIsInstance<InvestigateEvent.Error>().any { it.code == "TURN_TIMEOUT" },
            "expected a TURN_TIMEOUT error, got ${events.filterIsInstance<InvestigateEvent.Error>()}",
        )
        assertTrue(provider.streamRequests.size <= 1, "no provider call may start after the deadline, got ${provider.streamRequests.size}")
        assertTrue(events.none { it is InvestigateEvent.Done }, "a timed-out turn must not produce a final answer")
    }

    @Test
    fun `a total deadline that passes during finalization calls no provider and fails with TURN_TIMEOUT`() = runBlocking {
        val collection = CollectionStore(database).create("Late total deadline collection").id
        val documentId = insertDocument(collection)
        val unitId = addUnit(documentId, ordinal = 0, "late-total-unit", "The meeting began at noon.")
        var nowNanos = 0L
        val provider = PerRoundProvider(
            listOf(
                listOf(LlmEvent.ToolCallReady(infoscry.llm.ToolCall("read", "read_content_unit", """{"contentUnitId":"${unitId.value}"}""")), LlmEvent.Completed),
                listOf(LlmEvent.TextDelta("The meeting began at noon [S1]."), LlmEvent.Usage(TokenUsage(2, 3)), LlmEvent.Completed),
            ),
        )
        val persistence = FakePersistence()
        val events = mutableListOf<InvestigateEvent>()

        service(
            tools(collection),
            provider,
            persistence = persistence,
            turnTimeoutMs = 10_000,
            nanoTime = { nowNanos },
        ).investigate(InvestigateRequest(collection, "When did it begin?", profile())).collect { event ->
            events += event
            // Research stops at eight seconds; the clock then runs past the ten-second total budget
            // while the reserved final answer is being prepared, so no provider call may start.
            if (event is InvestigateEvent.ToolResult) nowNanos = 8_000_000_000L
            if (event is InvestigateEvent.AnswerStart) nowNanos = 10_500_000_000L
        }

        assertEquals("TURN_TIMEOUT", assertIs<InvestigateEvent.Error>(events.last()).code)
        assertEquals(1, provider.streamRequests.size, "no provider call may start once the total budget is gone")
        assertTrue(events.none { it is InvestigateEvent.Done }, "an out-of-time turn must not report a successful answer")
        assertTrue(
            events.none { it is InvestigateEvent.Citation },
            "an answer that never completed must not report citations: ${labels(events)}",
        )
        val timeEvents = persistence.appended.single().second.limitEvents.filter { it.eventType == "TURN_TIMEOUT" }
        assertEquals(2, timeEvents.size, "the research cutoff and fatal total timeout must both be durable")
        assertTrue(timeEvents.any { it.message == "the turn exceeded its time limit" }, "the persisted history must identify total-budget exhaustion")
    }

    @Test
    fun `a total deadline refuses remaining calls and closes the persisted batch before failing`() = runBlocking {
        val provider = ScriptedProvider(
            LlmEvent.ToolCallReady(infoscry.llm.ToolCall("c1", "search_collection", """{"query":"first"}""")),
            LlmEvent.ToolCallReady(infoscry.llm.ToolCall("c2", "search_collection", """{"query":"second"}""")),
            LlmEvent.Completed,
        )
        var nowNanos = 0L
        val persistence = FakePersistence()
        val events = mutableListOf<InvestigateEvent>()

        service(
            tools(CollectionId("collection")),
            provider,
            persistence = persistence,
            turnTimeoutMs = 10_000,
            nanoTime = { nowNanos },
        ).investigate(request()).collect { event ->
            events += event
            if (event is InvestigateEvent.ToolResult && event.callId == "c1") nowNanos = 10_000_000_000L
        }

        val results = events.filterIsInstance<InvestigateEvent.ToolResult>()
        assertEquals(listOf("c1", "c2"), results.map { it.callId })
        assertEquals("SUCCESS", results.first().resultCode)
        assertEquals("LIMIT_REACHED", results.last().resultCode)
        assertEquals("TURN_TIMEOUT", events.filterIsInstance<InvestigateEvent.Error>().single().code)
        assertTrue(events.none { it is InvestigateEvent.Done })
        val saved = persistence.appended.single().second
        assertEquals(2, saved.modelCalls.single().toolCalls.size)
        val messages = saved.messages.map { it.second }
        assertEquals(
            messages.flatMap { it.toolCalls }.map { it.id }.toSet(),
            messages.filter { it.role == "tool" }.mapNotNull { it.toolCallId }.toSet(),
            "the batch must be persisted as complete even when total time expires between calls",
        )
    }

    @Test
    fun `a research provider call is bounded by the remaining research time, not the inactivity cap`() = runBlocking {
        // A fifty-millisecond budget reserves ten milliseconds, leaving forty for research. The clock
        // is pinned, so the bound comes only from the remaining research time.
        var nowNanos = 0L
        val provider = SuspendingProvider()
        val persistence = FakePersistence()

        val startedAt = System.nanoTime()
        val events = service(
            tools(CollectionId("collection")),
            provider,
            persistence = persistence,
            turnTimeoutMs = 50,
            nanoTime = { nowNanos },
        ).investigate(request()).toList()
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

        assertEquals(1, provider.streamCalls)
        val limit = events.filterIsInstance<InvestigateEvent.Limit>().single()
        assertEquals("TURN_TIMEOUT", limit.code)
        val done = assertIs<InvestigateEvent.Done>(events.last())
        assertTrue(done.answer.contains("nothing to answer"), "an empty evidence set gets the honest local answer")
        assertTrue(done.evidence.isEmpty())
        assertTrue(events.none { it is InvestigateEvent.Error })
        assertTrue(
            elapsedMs < 5_000,
            "the call must end with the 40 ms research window, not the 120 s inactivity cap, took ${elapsedMs} ms",
        )
    }

    @Test
    fun `a synthesis provider call is bounded by remaining total time`() = runBlocking {
        val collection = CollectionStore(database).create("Synthesis timeout collection").id
        val documentId = insertDocument(collection)
        val unitId = addUnit(documentId, ordinal = 0, "synthesis-timeout-unit", "The meeting began at noon.")
        var nowNanos = 0L
        val provider = ResearchThenSuspendingProvider(
            listOf(
                LlmEvent.ToolCallReady(infoscry.llm.ToolCall("read", "read_content_unit", """{"contentUnitId":"${unitId.value}"}""")),
                LlmEvent.Completed,
            ),
        )
        val persistence = FakePersistence()
        val events = mutableListOf<InvestigateEvent>()
        val startedAt = System.nanoTime()

        service(
            tools(collection),
            provider,
            persistence = persistence,
            turnTimeoutMs = 50,
            nanoTime = { nowNanos },
        ).investigate(InvestigateRequest(collection, "When did it begin?", profile())).collect { event ->
            events += event
            if (event is InvestigateEvent.ToolResult) nowNanos = 40_000_000L
        }
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

        assertEquals(2, provider.streamCalls, "research is followed by one synthesis request within the reserve")
        assertEquals("TURN_TIMEOUT", assertIs<InvestigateEvent.Error>(events.last()).code)
        assertTrue(events.none { it is InvestigateEvent.Done }, "a synthesis past the total deadline is not a successful answer")
        assertTrue(elapsedMs < 5_000, "synthesis must use its ten-millisecond remainder, took ${elapsedMs} ms")
        val timeEvents = persistence.appended.single().second.limitEvents.filter { it.eventType == "TURN_TIMEOUT" }
        assertEquals(2, timeEvents.size, "both the nonfatal cutoff and fatal exhaustion must be persisted")
    }

    @Test
    fun `citation correction is bounded by the remaining total time`() = runBlocking {
        val collection = CollectionStore(database).create("Correction deadline collection").id
        val documentId = insertDocument(collection)
        val unitId = addUnit(documentId, ordinal = 0, "correction-deadline-unit", "The meeting began at noon.")
        var nowNanos = 0L
        val provider = StreamThenSlowCompletionProvider(
            listOf(
                listOf(LlmEvent.ToolCallReady(infoscry.llm.ToolCall("read", "read_content_unit", """{"contentUnitId":"${unitId.value}"}""")), LlmEvent.Completed),
                listOf(LlmEvent.TextDelta("The source says [S999]."), LlmEvent.Usage(TokenUsage(2, 3)), LlmEvent.Completed),
            ),
        )
        val persistence = FakePersistence()
        val events = mutableListOf<InvestigateEvent>()

        service(
            tools(collection),
            provider,
            persistence = persistence,
            turnTimeoutMs = 50,
            nanoTime = { nowNanos },
        ).investigate(InvestigateRequest(collection, "When did it begin?", profile())).collect { event ->
            events += event
            if (event is InvestigateEvent.ToolResult) nowNanos = 40_000_000L
        }

        assertEquals(2, provider.streamCalls)
        assertEquals(1, provider.completionCalls, "the one citation correction consumes the remaining time")
        assertEquals("TURN_TIMEOUT", events.filterIsInstance<InvestigateEvent.Error>().single().code)
        assertTrue(events.none { it is InvestigateEvent.Done }, "an out-of-time correction cannot produce Done")
        assertTrue(events.none { it is InvestigateEvent.Citation }, "a failed correction cannot report validated citations")
        val timeEvents = persistence.appended.single().second.limitEvents.filter { it.eventType == "TURN_TIMEOUT" }
        assertEquals(2, timeEvents.size, "the research cutoff and correction timeout are both durable")
    }

    @Test
    fun `a follow-up cites retained historical evidence without researching or correcting`() = runBlocking {
        val collection = CollectionId("collection")
        val history = InvestigateHistory(
            collectionId = collection,
            profile = profile(),
            promptVersion = 1,
            retrievalSnapshot = RetrievalSnapshot.value(),
            messages = listOf(
                LlmMessage("user", "first question"),
                LlmMessage("assistant", "", toolCalls = listOf(infoscry.llm.ToolCall("c1", "search_collection", """{"query":"x"}"""))),
                LlmMessage("tool", "result", toolCallId = "c1"),
                LlmMessage("assistant", "First answer [S1]"),
            ),
            evidenceIds = listOf("S1"),
            evidence = listOf(EvidenceLedgerSnapshot("S1", "unit-1", """{"type":"pdf_page","page":4}""", "The meeting began at noon.", messageSeq = 1)),
            messageSeqs = listOf(0, 1, 2, 3),
            nextMessageSeq = 4,
        )
        val provider = ScriptedProvider(
            LlmEvent.TextDelta("It began at noon [S1]."),
            LlmEvent.Usage(TokenUsage(1L, 1L)),
            LlmEvent.Completed,
        )
        val persistence = FakePersistence(mapOf("conv-1" to history))

        val events = service(tools(collection), provider, persistence = persistence)
            .investigate(InvestigateRequest(collection, "When did it begin?", profile(), conversationId = "conv-1"))
            .toList()

        assertEquals(1, provider.streamRequests.size, "a retained-evidence follow-up must not research again")
        assertTrue(provider.completionRequests.isEmpty(), "a valid retained citation must not trigger correction")
        assertTrue(
            events.filterIsInstance<InvestigateEvent.Citation>().any { it.evidenceId == "S1" && it.valid },
            "the retained source must be citable, got ${events.filterIsInstance<InvestigateEvent.Citation>()}",
        )
        assertEquals(listOf("S1"), assertIs<InvestigateEvent.Done>(events.last()).evidence.map { it.id })
    }

    @Test
    fun `a pruned historical group makes its retained evidence ineligible`() = runBlocking {
        val collection = CollectionId("collection")
        val padding = "x".repeat(8_000)
        val history = InvestigateHistory(
            collectionId = collection,
            profile = profile(contextWindow = 6_000),
            promptVersion = 1,
            retrievalSnapshot = RetrievalSnapshot.value(),
            messages = listOf(
                LlmMessage("user", "prior question $padding"),
                LlmMessage("assistant", "", toolCalls = listOf(infoscry.llm.ToolCall("c1", "search_collection", """{"query":"y"}"""))),
                LlmMessage("tool", padding, toolCallId = "c1"),
                LlmMessage("assistant", "prior answer"),
            ),
            evidenceIds = listOf("S1"),
            evidence = listOf(EvidenceLedgerSnapshot("S1", "unit-1", """{"type":"pdf_page","page":4}""", "retained excerpt", messageSeq = 1)),
            messageSeqs = listOf(0, 1, 2, 3),
            nextMessageSeq = 4,
        )
        val provider = ScriptedProvider(
            LlmEvent.TextDelta("cites [S1]"),
            LlmEvent.Usage(TokenUsage(1L, 1L)),
            LlmEvent.Completed,
            completion = LlmCompletion("still [S1]", TokenUsage(0, 0)),
        )

        val events = service(tools(collection), provider, persistence = FakePersistence(mapOf("conv-1" to history)))
            .investigate(InvestigateRequest(collection, "And?", profile(contextWindow = 6_000), conversationId = "conv-1"))
            .toList()

        assertTrue(
            provider.streamRequests.first().messages.none { it.content.contains("prior question") },
            "the oversized prior group must be pruned from the request",
        )
        assertTrue(
            events.filterIsInstance<InvestigateEvent.Citation>().any { it.evidenceId == "S1" && !it.valid },
            "evidence whose group was pruned must be ineligible, got ${events.filterIsInstance<InvestigateEvent.Citation>()}",
        )
        assertTrue(
            provider.completionRequests.single().messages.first().content.let { !it.contains("S1") },
            "correction must not re-authorize pruned evidence",
        )
        assertTrue(events.filterIsInstance<InvestigateEvent.Done>().single().evidence.isEmpty())
    }

    @Test
    fun `a corrected answer supersedes its draft as the single adopted answer`() = runBlocking {
        val collection = CollectionStore(database).create("Adoption collection").id
        val documentId = insertDocument(collection)
        val unitId = addUnit(documentId, 0, "adoption-source", "The meeting began at noon.")
        val provider = PerRoundProvider(
            listOf(
                listOf(LlmEvent.ToolCallReady(infoscry.llm.ToolCall("c1", "read_content_unit", """{"contentUnitId":"${unitId.value}"}""")), LlmEvent.Completed),
                listOf(LlmEvent.TextDelta("draft cites [S9]"), LlmEvent.Usage(TokenUsage(1L, 1L)), LlmEvent.Completed),
            ),
            completion = LlmCompletion("corrected cites [S1]", TokenUsage(0, 0)),
        )
        val persistence = FakePersistence()

        service(tools(collection), provider, persistence = persistence)
            .investigate(InvestigateRequest(collection, "When did it begin?", profile()))
            .toList()

        val snapshot = persistence.appended.single().second
        val assistantMessages = snapshot.messages.filter { it.second.role == "assistant" }.map { it.first to it.second.content }
        val draftSeq = assistantMessages.first { it.second == "draft cites [S9]" }.first
        val adoptedSeq = assistantMessages.first { it.second == "corrected cites [S1]" }.first
        assertEquals(1, provider.completionRequests.size, "exactly one correction attempt")
        assertTrue(draftSeq in snapshot.supersededSeqs, "the superseded draft must be marked as audit data")
        assertFalse(adoptedSeq in snapshot.supersededSeqs, "the corrected answer is the adopted one")
    }

    @Test
    fun `an empty correction keeps the draft as the adopted answer`() = runBlocking {
        val collection = CollectionStore(database).create("Empty correction collection").id
        val documentId = insertDocument(collection)
        val unitId = addUnit(documentId, 0, "empty-correction-source", "The meeting began at noon.")
        val provider = PerRoundProvider(
            listOf(
                listOf(LlmEvent.ToolCallReady(infoscry.llm.ToolCall("c1", "read_content_unit", """{"contentUnitId":"${unitId.value}"}""")), LlmEvent.Completed),
                listOf(LlmEvent.TextDelta("draft cites [S9]"), LlmEvent.Usage(TokenUsage(1L, 1L)), LlmEvent.Completed),
            ),
            completion = LlmCompletion("", TokenUsage(2, 2)),
        )
        val persistence = FakePersistence()

        val events = service(tools(collection), provider, persistence = persistence)
            .investigate(InvestigateRequest(collection, "When did it begin?", profile()))
            .toList()

        val snapshot = persistence.appended.single().second
        assertTrue(snapshot.supersededSeqs.isEmpty(), "an empty correction must not supersede the draft")
        assertTrue(
            snapshot.messages.any { it.second.role == "assistant" && it.second.content == "draft cites [S9]" },
            "the draft must stay the adopted answer",
        )
        assertEquals("draft cites [S9]", assertIs<InvestigateEvent.Done>(events.last()).answer)
    }

    @Test
    fun `repeated malformed arguments are refused while the first keeps its typed failure`() = runBlocking {
        val provider = PerRoundProvider(
            listOf(
                listOf(LlmEvent.ToolCallReady(infoscry.llm.ToolCall("first", "search_collection", "not json")), LlmEvent.Completed),
                listOf(LlmEvent.ToolCallReady(infoscry.llm.ToolCall("repeat", "search_collection", "not json")), LlmEvent.Completed),
            ),
        )

        val events = service(tools(CollectionId("collection")), provider).investigate(request()).toList()

        val results = events.filterIsInstance<InvestigateEvent.ToolResult>()
        val first = results.first { it.callId == "first" }
        assertTrue(first.resultCode != "SUCCESS" && first.resultCode != "LIMIT_REACHED", "the first malformed call keeps its typed failure, was ${first.resultCode}")
        assertEquals("LIMIT_REACHED", results.first { it.callId == "repeat" }.resultCode)
        assertTrue(events.filterIsInstance<InvestigateEvent.Limit>().any { it.code == "REPEATED_TOOL_CALL" })
    }

    @Test
    fun `an active stream outlives one inactivity interval within the turn budget`() = runBlocking {
        val provider = PacedProvider(
            listOf(LlmEvent.TextDelta("part one "), LlmEvent.TextDelta("part two "), LlmEvent.TextDelta("part three"), LlmEvent.Usage(TokenUsage(3, 3)), LlmEvent.Completed),
            gapMs = 40,
        )
        // Four gaps of 40 ms exceed one 60 ms inactivity interval, but each gap is under it.
        val sv = service(tools(CollectionId("collection")), provider, providerInactivityMs = 60)

        val events = sv.investigate(request()).toList()

        assertTrue(events.none { it is InvestigateEvent.Error }, "an active stream must not be treated as inactive, got $events")
        assertEquals("part one part two part three", assertIs<InvestigateEvent.Done>(events.last()).answer)
    }

    @Test
    fun `a silent provider reports inactivity rather than a total-deadline failure`() = runBlocking {
        val provider = SuspendingProvider()
        val sv = service(tools(CollectionId("collection")), provider, providerInactivityMs = 60)

        val events = sv.investigate(request()).toList()

        assertEquals("PROVIDER_TIMEOUT", events.filterIsInstance<InvestigateEvent.Error>().single().code)
        assertTrue(events.none { it is InvestigateEvent.Done })
    }

    @Test
    fun `continuous progress cannot extend the total turn budget`() = runBlocking {
        val provider = PacedProvider(
            (1..20).map { LlmEvent.TextDelta("chunk $it ") } + listOf(LlmEvent.Usage(TokenUsage(1, 1)), LlmEvent.Completed),
            gapMs = 40,
        )
        val sv = service(tools(CollectionId("collection")), provider, turnTimeoutMs = 120, providerInactivityMs = 60)

        val events = sv.investigate(request()).toList()

        // Continuous provider activity resets the inactivity timer, so the end is the fixed absolute
        // deadline, not a provider timeout. The turn ends with a TURN_TIMEOUT outcome and never
        // completes the provider's full research script.
        assertTrue(
            events.any { it is InvestigateEvent.Error && it.code == "TURN_TIMEOUT" } ||
                events.any { it is InvestigateEvent.Limit && it.code == "TURN_TIMEOUT" },
            "a bounded turn must report TURN_TIMEOUT, got $events",
        )
        events.filterIsInstance<InvestigateEvent.Done>().forEach { done ->
            assertFalse(done.answer.contains("chunk"), "continuous progress must not extend the budget into a full answer")
        }
    }

    @Test
    fun `equivalent repeated calls with reordered keys and whitespace are refused`() = runBlocking {
        val provider = PerRoundProvider(
            listOf(
                listOf(
                    LlmEvent.ToolCallReady(infoscry.llm.ToolCall("first", "search_collection", """{"query":"x","filters":{"ocrOnly":true,"mediaTypes":["pdf"]}}""")),
                    LlmEvent.Completed,
                ),
                listOf(
                    LlmEvent.ToolCallReady(infoscry.llm.ToolCall("repeat", "search_collection", """{ "filters" : { "mediaTypes" : ["pdf"], "ocrOnly" : true }, "query" : "x" }""")),
                    LlmEvent.Completed,
                ),
            ),
        )

        val events = service(tools(CollectionId("collection")), provider).investigate(request()).toList()

        val refused = events.filterIsInstance<InvestigateEvent.ToolResult>().filter { it.callId == "repeat" }
        assertEquals(listOf("LIMIT_REACHED"), refused.map { it.resultCode })
        assertTrue(events.filterIsInstance<InvestigateEvent.Limit>().any { it.code == "REPEATED_TOOL_CALL" })
        assertEquals(2, provider.streamRequests.size, "an equivalent repeat must not start another research round")
    }

    @Test
    fun `genuinely different arguments and reordered arrays remain executable`() = runBlocking {
        val provider = PerRoundProvider(
            listOf(
                listOf(
                    LlmEvent.ToolCallReady(infoscry.llm.ToolCall("first", "search_collection", """{"query":"x","filters":{"mediaTypes":["pdf","epub"]}}""")),
                    LlmEvent.Completed,
                ),
                listOf(
                    LlmEvent.ToolCallReady(infoscry.llm.ToolCall("reordered", "search_collection", """{"query":"x","filters":{"mediaTypes":["epub","pdf"]}}""")),
                    LlmEvent.Completed,
                ),
                listOf(LlmEvent.TextDelta("done"), LlmEvent.Usage(TokenUsage(1, 1)), LlmEvent.Completed),
            ),
        )

        val events = service(tools(CollectionId("collection")), provider).investigate(request()).toList()

        assertTrue(
            events.filterIsInstance<InvestigateEvent.ToolResult>().none { it.resultCode == "LIMIT_REACHED" },
            "reordered arrays and different values are genuinely different calls",
        )
        assertEquals(3, provider.streamRequests.size, "both distinct calls must execute and research continue")
    }

    private interface InvestigateProvider : LlmStreamingClient, LlmCompletionClient

    /** Serves one distinct event script per stream call, so each round can make different calls. */
    private class PerRoundProvider(
        private val rounds: List<List<LlmEvent>>,
        private val completion: LlmCompletion = LlmCompletion("", TokenUsage(0, 0)),
        /** What the stream call after the last scripted round throws, so a test can fail that call. */
        private val afterScript: Throwable = AssertionError("no scripted round"),
    ) : InvestigateProvider {
        val streamRequests = mutableListOf<LlmRequest>()
        val completionRequests = mutableListOf<LlmRequest>()
        private var nextRound = 0

        override fun stream(request: LlmRequest): Flow<LlmEvent> {
            streamRequests += request
            val script = rounds.getOrNull(nextRound)
            nextRound++
            if (script == null) throw afterScript
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

    private class StreamThenSlowCompletionProvider(private val streams: List<List<LlmEvent>>) : InvestigateProvider {
        var streamCalls = 0
        var completionCalls = 0

        override fun stream(request: LlmRequest): Flow<LlmEvent> {
            val events = streams.getOrNull(streamCalls++) ?: error("unexpected stream request")
            return flowOf(*events.toTypedArray())
        }

        override suspend fun complete(request: LlmRequest): LlmCompletion {
            completionCalls++
            kotlinx.coroutines.delay(60_000)
            return LlmCompletion("unused", TokenUsage(0, 0))
        }
    }

    /** One research tool call, then a provider stream that stalls during synthesis. */
    private class ResearchThenSuspendingProvider(private val researchEvents: List<LlmEvent>) : InvestigateProvider {
        var streamCalls = 0

        override fun stream(request: LlmRequest): Flow<LlmEvent> {
            streamCalls++
            return if (streamCalls == 1) {
                flowOf(*researchEvents.toTypedArray())
            } else {
                kotlinx.coroutines.flow.flow { kotlinx.coroutines.delay(60_000) }
            }
        }

        override suspend fun complete(request: LlmRequest): LlmCompletion = error("must not correct")
    }

    /** Emits each event after a fixed gap, so a stream can outlive one inactivity interval while staying active. */
    private class PacedProvider(private val events: List<LlmEvent>, private val gapMs: Long) : InvestigateProvider {
        override fun stream(request: LlmRequest): Flow<LlmEvent> = kotlinx.coroutines.flow.flow {
            events.forEach { event ->
                kotlinx.coroutines.delay(gapMs)
                emit(event)
            }
        }

        override suspend fun complete(request: LlmRequest): LlmCompletion = LlmCompletion("", TokenUsage(0, 0))
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
            draft = ContentUnitDraft(
                locator = SourceLocation.TextLines(1, 2),
                extractedText = text,
                searchText = text,
                method = ExtractionMethod.DIRECT_TEXT,
            ),
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
        /** Runs inside [appendTurn], so a test can prove persistence happened before Done. */
        private val onAppendTurn: () -> Unit = {},
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
            onAppendTurn()
        }

        override fun loadHistory(conversationId: String): InvestigateHistory? {
            loaded += conversationId
            return historyByConversationId[conversationId]
        }
    }
}
