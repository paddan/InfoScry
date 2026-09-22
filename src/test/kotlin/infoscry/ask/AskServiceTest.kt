package infoscry.ask

import infoscry.domain.CollectionId
import infoscry.domain.ContentUnitId
import infoscry.domain.DocumentId
import infoscry.domain.SourceLocation
import infoscry.llm.LlmCompletion
import infoscry.llm.LlmCompletionClient
import infoscry.llm.LlmEvent
import infoscry.llm.LlmProfile
import infoscry.llm.LlmProvider
import infoscry.llm.LlmRequest
import infoscry.llm.LlmStreamingClient
import infoscry.llm.PromptService
import infoscry.llm.TokenUsage
import infoscry.search.SearchFilters
import infoscry.search.SearchHit
import infoscry.search.SearchMode
import infoscry.search.SearchOutcome
import infoscry.storage.Database
import infoscry.storage.LlmStore
import infoscry.storage.SchemaMigrator
import java.nio.file.Files
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AskServiceTest {
    private val directory = Files.createTempDirectory("infoscry-ask-service")
    private val database = Database(directory.resolve("state.db"))
    private val store = LlmStore(database)

    init { SchemaMigrator(database).migrate() }

    @AfterTest
    fun close() {
        database.close()
        directory.toFile().deleteRecursively()
    }

    @Test
    fun `streams initial answer then makes one non-streaming correction and persists both call snapshots`() = runBlocking {
        val search = CapturedSearch(listOf(hit()))
        val provider = ScriptedProvider(
            streamed = listOf(LlmEvent.TextDelta("initial [S999]"), LlmEvent.Usage(TokenUsage(11, 13, 17))),
            completion = LlmCompletion("corrected [S1]", TokenUsage(19, 23, 29)),
        )
        val saved = mutableListOf<SavedAsk>()
        val service = service(search, provider) { request, answer, evidence, initialUsage, initialCitations, correction ->
            saved += SavedAsk(request, answer, evidence, initialUsage, initialCitations, correction)
        }

        val events = service.ask(request()).toList()

        assertEquals(listOf("question"), search.questions)
        assertEquals(1, provider.streamRequests.size)
        assertEquals(1, provider.completionRequests.size)
        assertEquals("initial [S999]", provider.completionRequests.single().messages.last().content)
        assertTrue(provider.completionRequests.single().messages.first().content.contains("S1"))
        assertEquals("corrected [S1]", assertIs<AskEvent.Done>(events.last()).answer)
        assertEquals(listOf(AskEvent.Citation("S1", true)), events.filterIsInstance<AskEvent.Citation>())
        assertEquals(1, saved.size)
        assertEquals(TokenUsage(11, 13, 17), saved.single().initialUsage)
        assertEquals(listOf("S999"), saved.single().initialCitations.invalid)
        assertEquals("corrected [S1]", saved.single().correction?.answer)
        assertEquals(TokenUsage(19, 23, 29), saved.single().correction?.usage)
        assertEquals(listOf("S1"), saved.single().correction?.citations?.valid)
    }

    @Test
    fun `initial budget overflow makes no streaming or correction calls`() = runBlocking {
        val provider = ScriptedProvider(listOf(LlmEvent.TextDelta("unused")), LlmCompletion("unused"))
        val service = service(CapturedSearch(listOf(hit())), provider) { _, _, _, _, _, _ -> error("must not persist") }

        val events = service.ask(request(profile = profile(contextWindow = 1))).toList()

        assertEquals(listOf(AskEvent.Error("CONTEXT_BUDGET_EXCEEDED", "the request is too large for the configured model")), events)
        assertTrue(provider.streamRequests.isEmpty())
        assertTrue(provider.completionRequests.isEmpty())
    }

    @Test
    fun `correction budget overflow makes no correction call and does not persist`() = runBlocking {
        val provider = ScriptedProvider(
            streamed = listOf(LlmEvent.TextDelta("x".repeat(8_000) + " [S999]"), LlmEvent.Usage(TokenUsage(1, 1))),
            completion = LlmCompletion("unused"),
        )
        val service = service(CapturedSearch(listOf(hit())), provider) { _, _, _, _, _, _ -> error("must not persist") }

        val events = service.ask(request(profile = profile(contextWindow = 3_000))).toList()

        assertEquals(1, provider.streamRequests.size)
        assertTrue(provider.completionRequests.isEmpty())
        assertEquals("CONTEXT_BUDGET_EXCEEDED", assertIs<AskEvent.Error>(events.last()).code)
    }

    private fun service(search: AskSearch, provider: ScriptedProvider, persistence: AskPersistence): AskService =
        AskService(search, PromptService(store), client = { provider }, persistence = persistence)

    private fun request(profile: LlmProfile = profile()) = AskRequest(CollectionId("collection"), "question", profile)

    private fun profile(contextWindow: Int = 10_000) = LlmProfile(
        id = "profile", name = "test", provider = LlmProvider.OPENAI_COMPATIBLE, model = "model",
        contextWindow = contextWindow, maxOutputTokens = 20,
        inputPricePerMillion = 2.0, outputPricePerMillion = 3.0, cacheReadPricePerMillion = 4.0,
        enabled = true,
    )

    private fun hit() = SearchHit(
        collectionId = CollectionId("collection"), documentId = DocumentId("document"), unitId = ContentUnitId("unit"),
        chunkOrdinal = 0, text = "evidence", highlighted = null, locator = SourceLocation.TextLines(1, 1),
        locatorLabel = "lines 1", matchedBy = setOf(SearchMode.HYBRID),
    )

    private data class SavedAsk(
        val request: AskRequest, val answer: String, val evidence: List<Evidence>, val initialUsage: TokenUsage,
        val initialCitations: CitationValidation, val correction: CorrectionSnapshot?,
    )

    private class CapturedSearch(private val hits: List<SearchHit>) : AskSearch {
        val questions = mutableListOf<String>()
        override fun search(question: String, mode: SearchMode, filters: SearchFilters): SearchOutcome {
            questions += question
            assertEquals(SearchMode.HYBRID, mode)
            assertEquals(CollectionId("collection"), filters.collectionId)
            return SearchOutcome(hits, 0)
        }
    }

    private class ScriptedProvider(
        private val streamed: List<LlmEvent>, private val completion: LlmCompletion,
    ) : LlmStreamingClient, LlmCompletionClient {
        val streamRequests = mutableListOf<LlmRequest>()
        val completionRequests = mutableListOf<LlmRequest>()

        override fun stream(request: LlmRequest): Flow<LlmEvent> {
            streamRequests += request
            return flowOf(*streamed.toTypedArray())
        }

        override suspend fun complete(request: LlmRequest): LlmCompletion {
            completionRequests += request
            return completion
        }
    }
}
