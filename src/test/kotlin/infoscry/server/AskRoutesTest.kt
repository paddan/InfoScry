package infoscry.server

import infoscry.ask.AskEvent
import infoscry.ask.CitationValidation
import infoscry.ask.Evidence
import infoscry.domain.Document
import infoscry.domain.DocumentId
import infoscry.domain.DocumentStatus
import infoscry.domain.SourceLocation
import infoscry.embedding.QueryEmbedder
import infoscry.extract.ContentUnitDraft
import infoscry.extract.ExtractionFingerprint
import infoscry.extract.ExtractionSettings
import infoscry.llm.FakeOpenAiResponse
import infoscry.llm.FakeOpenAiServer
import infoscry.llm.LlmProfile
import infoscry.llm.LlmProvider
import infoscry.llm.TokenUsage
import infoscry.search.vectorFor
import infoscry.storage.Instants
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import java.nio.file.Files
import java.util.UUID
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
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

    // ---- Titles ----

    @Test
    fun `a new Ask answer ends with a title written from its opening question by its own profile`() = runBlocking {
        val dataDir = Files.createTempDirectory("infoscry-ask-title")
        try {
            ApiTestServer(dataDir, queryEmbedder = { QueryEmbedder { vectorFor(it) } }).use { harness ->
                FakeOpenAiServer(
                    listOf(
                        FakeOpenAiResponse(stream = true, body = answerSse()),
                        FakeOpenAiResponse(statusCode = 200, body = titleCompletion("The signing")),
                    ),
                ).use { fake ->
                    createLlmProfile(harness, name = "title-asker", model = "title-model", endpoint = fake.url)

                    val response = harness.request(
                        HttpMethod.Post,
                        "/api/ask",
                        body = """{"collection":"Default","question":"Who signed it?","profile":"title-asker"}""",
                        credential = Credential.BEARER,
                    )

                    assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
                    val responseBody = response.bodyAsText()
                    assertContains(responseBody, "\"type\":\"done\"", message = "the answer must complete")
                    assertEquals(2, fake.handledRequests, "the answer stream plus one title completion")
                    assertContains(fake.requestBodies[1], "\"model\":\"title-model\"", message = "the title call must use the conversation's locked model")
                    assertContains(fake.requestBodies[1], "\"stream\":false", message = "the title call must be the non-streaming completion boundary")

                    val defaultCollectionId = harness.context.collectionService.requireActiveByNameOrId("Default").id.value
                    val conversationId = harness.context.database.read { connection ->
                        connection.createStatement().use { statement ->
                            statement.executeQuery("SELECT id FROM conversations WHERE mode = 'ASK' ORDER BY created_at DESC LIMIT 1").use { rows ->
                                rows.next(); rows.getString(1)
                            }
                        }
                    }
                    val storedTitle = harness.context.database.read { connection ->
                        connection.prepareStatement("SELECT title FROM conversations WHERE id = ?").use { statement ->
                            statement.setString(1, conversationId)
                            statement.executeQuery().use { rows -> rows.next(); rows.getString("title") }
                        }
                    }
                    assertEquals("The signing", storedTitle, "the title must be written to the conversation row")

                    val done = responseBody.lineSequence()
                        .filter { it.startsWith("data: ") }
                        .map { ApiJson.decodeFromString<AskWire>(it.removePrefix("data: ")) }
                        .first { it.type == "done" }
                    assertEquals(
                        conversationId,
                        done.conversationId,
                        "the completion event must carry the id of the conversation persistence stored",
                    )

                    val list = harness.request(
                        HttpMethod.Get,
                        "/api/collections/$defaultCollectionId/asks",
                        credential = Credential.BEARER,
                    )
                    assertEquals(HttpStatusCode.OK, list.status, list.bodyAsText())
                    assertContains(list.bodyAsText(), "\"title\":\"The signing\"", message = "the list must return the stored title")
                }
            }
        } finally {
            dataDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a title request goes to the conversation's own profile`() = runBlocking {
        val dataDir = Files.createTempDirectory("infoscry-ask-title-profile")
        try {
            ApiTestServer(dataDir, queryEmbedder = { QueryEmbedder { vectorFor(it) } }).use { harness ->
                FakeOpenAiServer(
                    listOf(
                        FakeOpenAiResponse(stream = true, body = answerSse()),
                        FakeOpenAiResponse(statusCode = 200, body = titleCompletion("One")),
                        FakeOpenAiResponse(stream = true, body = answerSse()),
                        FakeOpenAiResponse(statusCode = 200, body = titleCompletion("Two")),
                    ),
                ).use { fake ->
                    createLlmProfile(harness, name = "asker-a", model = "model-a", endpoint = fake.url)
                    createLlmProfile(harness, name = "asker-b", model = "model-b", endpoint = fake.url)

                    val first = harness.request(
                        HttpMethod.Post,
                        "/api/ask",
                        body = """{"collection":"Default","question":"Who signed it?","profile":"asker-a"}""",
                        credential = Credential.BEARER,
                    )
                    assertEquals(HttpStatusCode.OK, first.status, first.bodyAsText())
                    val second = harness.request(
                        HttpMethod.Post,
                        "/api/ask",
                        body = """{"collection":"Default","question":"Who signed it too?","profile":"asker-b"}""",
                        credential = Credential.BEARER,
                    )
                    assertEquals(HttpStatusCode.OK, second.status, second.bodyAsText())

                    assertEquals(4, fake.handledRequests, "two answer streams and two title completions")
                    assertContains(fake.requestBodies[1], "\"model\":\"model-a\"", message = "the first title call must use asker-a's model")
                    assertContains(fake.requestBodies[3], "\"model\":\"model-b\"", message = "the second title call must use asker-b's model")
                }
            }
        } finally {
            dataDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a failing title call leaves the conversation without a title and never fails the answer`() = runBlocking {
        val failures = listOf(
            "a provider error" to FakeOpenAiResponse(statusCode = 500, body = "{}"),
            "a malformed completion" to FakeOpenAiResponse(statusCode = 200, body = "not-a-response"),
            "an empty completion" to FakeOpenAiResponse(statusCode = 200, body = """{"choices":[{"message":{"role":"assistant","content":""}}]}"""),
        )
        failures.forEachIndexed { index, (label, titleFailure) ->
            val dataDir = Files.createTempDirectory("infoscry-ask-title-failure")
            try {
                ApiTestServer(dataDir, queryEmbedder = { QueryEmbedder { vectorFor(it) } }).use { harness ->
                    FakeOpenAiServer(
                        listOf(FakeOpenAiResponse(stream = true, body = answerSse()), titleFailure),
                    ).use { fake ->
                        createLlmProfile(harness, name = "failure-asker-$index", model = "fail-model", endpoint = fake.url)

                        val response = harness.request(
                            HttpMethod.Post,
                            "/api/ask",
                            body = """{"collection":"Default","question":"Who signed it?","profile":"failure-asker-$index"}""",
                            credential = Credential.BEARER,
                        )

                        assertEquals(HttpStatusCode.OK, response.status, "$label: ${response.bodyAsText()}")
                        assertContains(response.bodyAsText(), "\"type\":\"done\"", message = "$label: the answer must still complete")
                        assertEquals(2, fake.handledRequests, "$label: the title call must still happen exactly once")

                        val profileId = harness.context.llm.findByName("failure-asker-$index")!!.id
                        harness.context.database.read { connection ->
                            val conversation = connection.createStatement().use { statement ->
                                statement.executeQuery("SELECT id, title FROM conversations WHERE mode = 'ASK' ORDER BY created_at DESC LIMIT 1").use { rows ->
                                    rows.next()
                                    rows.getString("id") to rows.getString("title")
                                }
                            }
                            assertEquals(null, conversation.second, "$label: no title may be stored")
                            val modelCalls = connection.prepareStatement("SELECT COUNT(*) FROM model_calls WHERE conversation_id = ?").use { statement ->
                                statement.setString(1, conversation.first)
                                statement.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
                            }
                            assertEquals(1, modelCalls, "$label: the title call must not be recorded as a conversation model call")
                            val totals = connection.prepareStatement("SELECT calls FROM usage_totals WHERE profile_id = ?").use { statement ->
                                statement.setString(1, profileId)
                                statement.executeQuery().use { rows -> rows.next(); rows.getInt("calls") }
                            }
                            assertEquals(1, totals, "$label: the title tokens must not touch usage_totals")
                        }
                    }
                }
            } finally {
                dataDir.toFile().deleteRecursively()
            }
        }
    }

    @Test
    fun `a stalled title call times out and leaves the conversation without a title`() = runBlocking {
        val dataDir = Files.createTempDirectory("infoscry-ask-title-timeout")
        try {
            ApiTestServer(dataDir, queryEmbedder = { QueryEmbedder { vectorFor(it) } }).use { harness ->
                // The title completion accepts the request, advertises a body it never sends and keeps
                // the connection open far past the titler's own bound, so a title that never answers
                // must not hold the finished answer's route open. The fake is closed on a daemon
                // thread: the JDK HttpServer stops only once the held exchange finishes, and the test
                // must not wait for that.
                val fake = FakeOpenAiServer(
                    listOf(
                        FakeOpenAiResponse(stream = true, body = answerSse()),
                        FakeOpenAiResponse(statusCode = 200, body = "", stream = true, declaredLength = 4_096, holdMillis = 30_000),
                    ),
                )
                try {
                    createLlmProfile(harness, name = "timeout-asker", model = "timeout-model", endpoint = fake.url)

                    val startedAt = System.nanoTime()
                    val response = harness.request(
                        HttpMethod.Post,
                        "/api/ask",
                        body = """{"collection":"Default","question":"Who signed it?","profile":"timeout-asker"}""",
                        credential = Credential.BEARER,
                    )
                    val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000

                    assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
                    assertContains(response.bodyAsText(), "\"type\":\"done\"", message = "the answer must complete")
                    assertEquals(2, fake.handledRequests, "the answer stream plus one timed-out title call")
                    assertTrue(
                        elapsedMillis < 12_000,
                        "the title bound must release the route long before the fake's 30s hold, took ${elapsedMillis}ms",
                    )
                    val storedTitle = harness.context.database.read { connection ->
                        connection.prepareStatement("SELECT title FROM conversations WHERE mode = 'ASK' ORDER BY created_at DESC LIMIT 1").use { statement ->
                            statement.executeQuery().use { rows -> rows.next(); rows.getString("title") }
                        }
                    }
                    assertEquals(null, storedTitle, "a timed-out title call must leave the title unset")
                } finally {
                    thread(isDaemon = true, name = "close-holding-fake") { fake.close() }
                }
            }
        } finally {
            dataDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `the Ask list returns the title and falls back to the truncated opening question`() = runBlocking {
        val dataDir = Files.createTempDirectory("infoscry-ask-title-list")
        try {
            ApiTestServer(dataDir).use { harness ->
                val collection = harness.context.collectionService.requireActiveByNameOrId("Default")
                val profile = createLlmProfile(harness, name = "list-asker", model = "list-model", endpoint = "https://provider.invalid/v1")
                harness.context.llm.persistAsk(
                    collectionId = collection.id,
                    profile = profile,
                    question = "Who signed it?",
                    answer = "Mira signed it.",
                    evidence = emptyList(),
                    initialUsage = TokenUsage(inputTokens = 1, outputTokens = 1, cacheReadTokens = 0),
                    initialCitations = CitationValidation(valid = emptyList(), invalid = emptyList()),
                )
                val titled = harness.context.llm.persistAsk(
                    collectionId = collection.id,
                    profile = profile,
                    question = "What day was it?",
                    answer = "Sunday.",
                    evidence = emptyList(),
                    initialUsage = TokenUsage(inputTokens = 1, outputTokens = 1, cacheReadTokens = 0),
                    initialCitations = CitationValidation(valid = emptyList(), invalid = emptyList()),
                )
                harness.context.llm.setConversationTitle(titled, "The signing")

                val list = harness.request(
                    HttpMethod.Get,
                    "/api/collections/${collection.id.value}/asks",
                    credential = Credential.BEARER,
                )

                assertEquals(HttpStatusCode.OK, list.status, list.bodyAsText())
                val body = list.bodyAsText()
                assertContains(body, "\"title\":\"The signing\"", message = "a titled row must show its title")
                assertContains(body, "\"title\":\"Who signed it?\"", message = "a row without a title must fall back to its question")
            }
        } finally {
            dataDir.toFile().deleteRecursively()
        }
    }

    private fun answerSse(): String = sse(
        listOf(
            """{"choices":[{"delta":{"content":"Mira signed it."},"finish_reason":null}]}""",
            """{"choices":[{"delta":{"finish_reason":"stop"}}],"usage":{"prompt_tokens":7,"completion_tokens":2}}""",
        ),
    )

    private fun sse(dataLines: List<String>): String =
        dataLines.map { "data: $it\n\n" }.joinToString("") + "data: [DONE]\n\n"

    private fun titleCompletion(title: String): String =
        """{"choices":[{"message":{"role":"assistant","content":"$title"}}],"usage":{"prompt_tokens":9,"completion_tokens":3}}"""

    private fun createLlmProfile(
        harness: ApiTestServer,
        name: String,
        model: String,
        endpoint: String,
    ): LlmProfile = LlmProfile(
        id = UUID.randomUUID().toString(),
        name = name,
        provider = LlmProvider.OPENAI_COMPATIBLE,
        model = model,
        contextWindow = 10_000,
        maxOutputTokens = 64,
        inputPricePerMillion = 1.0,
        outputPricePerMillion = 2.0,
        cacheReadPricePerMillion = 0.0,
        enabled = true,
        endpoint = endpoint,
    ).also { harness.context.llm.create(it) }
}
