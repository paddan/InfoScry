package infoscry.server

import infoscry.ask.Evidence
import infoscry.ask.RetrievalSnapshot
import infoscry.domain.Document
import infoscry.domain.DocumentId
import infoscry.domain.DocumentStatus
import infoscry.domain.SourceLocation
import infoscry.extract.ContentUnitDraft
import infoscry.extract.ExtractionFingerprint
import infoscry.extract.ExtractionSettings
import infoscry.investigate.InvestigateEvent
import infoscry.llm.FakeOpenAiResponse
import infoscry.llm.FakeOpenAiServer
import infoscry.llm.LlmCapabilityProbe
import infoscry.llm.LlmProfile
import infoscry.llm.LlmPromptRole
import infoscry.llm.LlmProvider
import infoscry.storage.Instants
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.atomic.AtomicInteger

/**
 * The Investigate wire: the 422 capability gate, the started/delta/tool/usage/citation/done/error SSE
 * contract, and the cancel registry, all over a real socket through [ApiTestServer].
 *
 * Provider calls in the create/continue tests fail fast (the test profile has no endpoint), so the
 * stream the tests read is deterministic: `started` then a typed error event. The one test that needs
 * a genuinely running turn points the profile at a socket that accepts connections and never answers,
 * so the turn stays in flight until `cancel` stops it.
 */
class InvestigationRoutesTest {

    private lateinit var dataDir: Path
    private lateinit var harness: ApiTestServer

    @BeforeTest
    fun startServer() {
        dataDir = Files.createTempDirectory("infoscry-investigation-routes")
        harness = ApiTestServer(dataDir)
    }

    @AfterTest
    fun stopServer() {
        harness.close()
        dataDir.toFile().deleteRecursively()
    }

    private fun createProfile(
        name: String = "investigator",
        toolCallingSupported: Boolean = true,
        endpoint: String = "",
        model: String = "model",
        inputPricePerMillion: Double = 0.0,
        outputPricePerMillion: Double = 0.0,
    ) {
        harness.context.llm.create(
            LlmProfile(
                id = UUID.randomUUID().toString(),
                name = name,
                provider = LlmProvider.OPENAI_COMPATIBLE,
                model = model,
                contextWindow = 10_000,
                maxOutputTokens = 64,
                inputPricePerMillion = inputPricePerMillion,
                outputPricePerMillion = outputPricePerMillion,
                cacheReadPricePerMillion = 0.0,
                enabled = true,
                endpoint = endpoint,
            ),
        )
        if (toolCallingSupported) {
            harness.context.llm.recordCapability(name, LlmCapabilityProbe(toolCallingSupported = true, checkedAt = "2026-09-21T10:00:00Z"))
        }
    }

    private fun createBody(
        question: String = "what happened",
        profile: String = "investigator",
        collection: String = "Default",
        limits: String? = null,
    ) =
        """{"collection":"$collection","question":"$question","profile":"$profile"${limits?.let { ",\"limits\":$it" } ?: ""}}"""

    // ---- Wire unit contract ----

    @Test
    fun `the SSE sequence keeps the stable started delta tool usage citation done error contract`() {
        val events = listOf(
            InvestigateEvent.Started("conv-1"),
            InvestigateEvent.Delta("partial"),
            InvestigateEvent.ToolCall("c1", "search_collection", """{"query":"q"}"""),
            InvestigateEvent.ToolResult("c1", "search_collection", "SUCCESS", 42L),
            InvestigateEvent.Usage(11, 13),
            InvestigateEvent.Citation("S1", true),
            InvestigateEvent.Done("final [S1]", emptyList()),
            InvestigateEvent.Error("CONVERSATION_NOT_FOUND", "the conversation could not be found"),
        )

        assertEquals(
            listOf("started", "delta", "tool", "tool", "usage", "citation", "done", "error"),
            events.map { ApiJson.encodeToString(it.toWire()).substringAfter("\"type\":\"").substringBefore('"') },
        )
    }

    @Test
    fun `the tool wire event carries call fields for a call and result fields for a result`() {
        val call = ApiJson.encodeToString(InvestigateEvent.ToolCall("c1", "read_content_unit", "{}").toWire())
        assertEquals("""{"type":"tool","callId":"c1","name":"read_content_unit","arguments":"{}"}""", call)

        val result = ApiJson.encodeToString(InvestigateEvent.ToolResult("c1", "read_content_unit", "SUCCESS", 42L).toWire())
        assertEquals("""{"type":"tool","callId":"c1","name":"read_content_unit","resultCode":"SUCCESS","durationMs":42}""", result)
    }

    @Test
    fun `the limit and answer-start wire events carry the new nonfatal shapes`() {
        assertEquals(
            """{"type":"limit","code":"MAX_ROUNDS","message":"Tool round limit reached."}""",
            ApiJson.encodeToString(InvestigateEvent.Limit("MAX_ROUNDS", "Tool round limit reached.").toWire()),
        )
        assertEquals(
            """{"type":"answer-start"}""",
            ApiJson.encodeToString(InvestigateEvent.AnswerStart.toWire()),
        )
    }

    @Test
    fun `ten research rounds stream a limit and cited synthesis over SSE`() = runBlocking {
        val sourceText = "Mira signed the note."
        val contentUnitId = addEvidenceUnit(sourceText)
        val researchResponses = (1..10).map { round ->
            val toolName = if (round == 1) "read_content_unit" else "get_document_metadata"
            val arguments = if (round == 1) {
                """{"contentUnitId":"$contentUnitId"}"""
            } else {
                """{"documentId":"missing-$round"}"""
            }.replace("\"", "\\\"")
            val call = """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call-$round","type":"function","function":{"name":"$toolName","arguments":"$arguments"}}]},"finish_reason":null}]}"""
            val finish = """{"choices":[{"delta":{},"finish_reason":"tool_calls"}]}"""
            val usage = """{"choices":[],"usage":{"prompt_tokens":1,"completion_tokens":1}}"""
            FakeOpenAiResponse(stream = true, body = sse(listOf(call, finish, usage)))
        }
        val synthesis = sse(listOf(
            """{"choices":[{"delta":{"content":"Mira signed the note [S1]."},"finish_reason":"stop"}],"usage":{"prompt_tokens":20,"completion_tokens":8}}""",
        ))

        FakeOpenAiServer(
            researchResponses + listOf(
                FakeOpenAiResponse(stream = true, body = synthesis),
                FakeOpenAiResponse(body = titleCompletion("The signed note")),
            ),
        ).use { fake ->
            createProfile(endpoint = fake.url)
            val response = harness.request(
                HttpMethod.Post,
                "/api/investigations",
                // Explicit limits: the shipped default round cap is larger than the ten scripted
                // research responses this test serves.
                body = createBody(question = "Who signed the note?", limits = """{"maxToolRounds":10}"""),
                credential = Credential.BEARER,
            )

            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            val events = response.bodyAsText().lineSequence()
                .filter { it.startsWith("data: ") }
                .map { Json.parseToJsonElement(it.removePrefix("data: ")).jsonObject }
                .toList()
            val types = events.map { it.getValue("type").jsonPrimitive.content }
            val limitIndex = types.indexOf("limit")
            assertTrue(limitIndex > types.lastIndexOf("tool"), types.toString())
            assertEquals("MAX_ROUNDS", events[limitIndex].getValue("code").jsonPrimitive.content)
            assertEquals("answer-start", types[limitIndex + 1])
            assertEquals("done", types.last())
            assertEquals(1, types.count { it == "done" })
            assertFalse("error" in types, types.toString())
            val citation = events.single { it.getValue("type").jsonPrimitive.content == "citation" }
            assertEquals("S1", citation.getValue("id").jsonPrimitive.content)
            assertEquals("true", citation.getValue("valid").jsonPrimitive.content)
            val usage = events.single { it.getValue("type").jsonPrimitive.content == "usage" }
            assertEquals(30, usage.getValue("inputTokens").jsonPrimitive.content.toInt())
            assertEquals(18, usage.getValue("outputTokens").jsonPrimitive.content.toInt())

            assertEquals(12, fake.requestBodies.size, "ten research rounds, one synthesis request, and one title request")
            val synthesisRequest = Json.parseToJsonElement(fake.requestBodies[10]).jsonObject
            assertFalse("tools" in synthesisRequest, "the final-answer request must not expose tools")
            val synthesisMessages = synthesisRequest.getValue("messages").jsonArray
            val synthesisContext = synthesisMessages.joinToString(" ") { it.jsonObject.getValue("content").jsonPrimitive.content }
            assertContains(synthesisContext, sourceText)
            assertContains(synthesisContext, "evidenceId")
            assertContains(synthesisContext, "S1")

            val conversationId = events.first().getValue("id").jsonPrimitive.content
            val history = harness.request(
                HttpMethod.Get,
                "/api/collections/Default/investigations/$conversationId",
                credential = Credential.BEARER,
            )
            assertEquals(HttpStatusCode.OK, history.status, history.bodyAsText())
            assertContains(history.bodyAsText(), "Mira signed the note [S1].")
            val persistedLimit = harness.context.database.read { connection ->
                connection.prepareStatement("SELECT event_type FROM limit_events WHERE conversation_id = ?").use { statement ->
                    statement.setString(1, conversationId)
                    statement.executeQuery().use { rows ->
                        assertTrue(rows.next(), "the MAX_ROUNDS event must be durable")
                        rows.getString("event_type")
                    }
                }
            }
            assertEquals("MAX_ROUNDS", persistedLimit)
        }
    }

    @Test
    fun `the tool-call cap closes a provider batch and streams cited synthesis over SSE`() = runBlocking {
        val sourceText = "Mira signed the note."
        val contentUnitId = addEvidenceUnit(sourceText)
        val researchFrames = (1..21).map { callNumber ->
            val toolName = if (callNumber == 1) "read_content_unit" else "get_document_metadata"
            val rawArguments = if (callNumber == 1) {
                """{"contentUnitId":"$contentUnitId"}"""
            } else {
                """{"documentId":"missing-$callNumber"}"""
            }
            val arguments = rawArguments.replace("\"", "\\\"")
            """{"choices":[{"delta":{"tool_calls":[{"index":${callNumber - 1},"id":"call-$callNumber","type":"function","function":{"name":"$toolName","arguments":"$arguments"}}]},"finish_reason":null}]}"""
        } + listOf(
            """{"choices":[{"delta":{},"finish_reason":"tool_calls"}]}""",
            """{"choices":[],"usage":{"prompt_tokens":1,"completion_tokens":1}}""",
        )
        val research = FakeOpenAiResponse(stream = true, body = sse(researchFrames))
        val synthesis = sse(listOf(
            """{"choices":[{"delta":{"content":"Mira signed the note [S1]."},"finish_reason":"stop"}],"usage":{"prompt_tokens":20,"completion_tokens":8}}""",
        ))

        FakeOpenAiServer(
            listOf(
                research,
                FakeOpenAiResponse(stream = true, body = synthesis),
                FakeOpenAiResponse(body = titleCompletion("The signed note")),
            ),
        ).use { fake ->
            createProfile(endpoint = fake.url)
            val response = harness.request(
                HttpMethod.Post,
                "/api/investigations",
                body = createBody(question = "Who signed the note?", limits = """{"maxToolCalls":20}"""),
                credential = Credential.BEARER,
            )

            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            val events = response.bodyAsText().lineSequence()
                .filter { it.startsWith("data: ") }
                .map { Json.parseToJsonElement(it.removePrefix("data: ")).jsonObject }
                .toList()
            val types = events.map { it.getValue("type").jsonPrimitive.content }
            val limitIndex = types.indexOf("limit")
            assertTrue(limitIndex > types.lastIndexOf("tool"), types.toString())
            assertEquals("MAX_TOOL_CALLS", events[limitIndex].getValue("code").jsonPrimitive.content)
            assertEquals("answer-start", types[limitIndex + 1])
            assertEquals("done", types.last())
            assertEquals(1, types.count { it == "done" })
            assertFalse("error" in types, types.toString())
            val calls = events.filter { it.getValue("type").jsonPrimitive.content == "tool" && "arguments" in it }
            val results = events.filter { it.getValue("type").jsonPrimitive.content == "tool" && "resultCode" in it }
            assertEquals(21, calls.size)
            assertEquals(21, results.size, "the refused provider call must still receive a tool result")
            assertEquals("LIMIT_REACHED", results.last().getValue("resultCode").jsonPrimitive.content)
            val citation = events.single { it.getValue("type").jsonPrimitive.content == "citation" }
            assertEquals("S1", citation.getValue("id").jsonPrimitive.content)
            assertEquals("true", citation.getValue("valid").jsonPrimitive.content)

            assertEquals(3, fake.requestBodies.size, "one research batch, one synthesis request, and one title request")
            val synthesisRequest = Json.parseToJsonElement(fake.requestBodies[1]).jsonObject
            assertFalse("tools" in synthesisRequest, "synthesis must not expose tools")
            val synthesisContext = synthesisRequest.getValue("messages").jsonArray
                .joinToString(" ") { it.jsonObject.getValue("content").jsonPrimitive.content }
            assertContains(synthesisContext, sourceText)
            assertContains(synthesisContext, "S1")

            val conversationId = events.first().getValue("id").jsonPrimitive.content
            val history = harness.request(
                HttpMethod.Get,
                "/api/collections/Default/investigations/$conversationId",
                credential = Credential.BEARER,
            )
            assertEquals(HttpStatusCode.OK, history.status, history.bodyAsText())
            assertContains(history.bodyAsText(), "Mira signed the note [S1].")
            assertContains(history.bodyAsText(), "LIMIT_REACHED", message = "the refused call must be visible in history activity")
        }
    }

    // ---- The 422 capability gate ----

    @Test
    fun `an unmeasured profile is refused with a 422 before any provider call or conversation`() = runBlocking {
        createProfile("unmeasured", toolCallingSupported = false)

        val response = harness.request(
            HttpMethod.Post,
            "/api/investigations",
            body = createBody(profile = "unmeasured"),
            credential = Credential.BEARER,
        )

        assertEquals(HttpStatusCode.UnprocessableEntity, response.status, response.bodyAsText())
        assertContains(response.bodyAsText(), "TOOL_CALLING_UNSUPPORTED")
        assertFalse(response.bodyAsText().contains("data: "), "a refused turn must not stream SSE events")
        val conversationCount = harness.context.database.read { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM conversations").use { rows ->
                    rows.next(); rows.getInt(1)
                }
            }
        }
        assertEquals(0, conversationCount, "the 422 gate must run before any conversation is created")
    }

    @Test
    fun `invalid per-turn limits are rejected before a conversation is created`() = runBlocking {
        createProfile()
        val response = harness.request(
            HttpMethod.Post,
            "/api/investigations",
            body = """{"collection":"Default","question":"question","profile":"investigator","limits":{"maxToolRounds":0}}""",
            credential = Credential.BEARER,
        )

        assertEquals(HttpStatusCode.BadRequest, response.status, response.bodyAsText())
        assertContains(response.bodyAsText(), "INVALID_REQUEST")
        assertFalse(response.bodyAsText().contains("data: "), "invalid limits must be rejected before the SSE stream")
        val conversationCount = harness.context.database.read { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM conversations").use { rows ->
                    rows.next(); rows.getInt(1)
                }
            }
        }
        assertEquals(0, conversationCount, "invalid limits must not create a conversation")
    }

    @Test
    fun `out-of-range and malformed per-turn limits are rejected before any side effect`() = runBlocking {
        createProfile()
        val bodies = listOf(
            """{"collection":"Default","question":"q","profile":"investigator","limits":{"maxToolRounds":51}}""",
            """{"collection":"Default","question":"q","profile":"investigator","limits":{"maxToolCalls":0}}""",
            """{"collection":"Default","question":"q","profile":"investigator","limits":{"maxToolCalls":101}}""",
            """{"collection":"Default","question":"q","profile":"investigator","limits":{"maxTurnSeconds":9}}""",
            """{"collection":"Default","question":"q","profile":"investigator","limits":{"maxTurnSeconds":1801}}""",
            """{"collection":"Default","question":"q","profile":"investigator","limits":{"maxToolRounds":1.5}}""",
            """{"collection":"Default","question":"q","profile":"investigator","limits":{"maxToolRounds":"ten"}}""",
        )

        for (body in bodies) {
            val response = harness.request(HttpMethod.Post, "/api/investigations", body = body, credential = Credential.BEARER)
            assertEquals(HttpStatusCode.BadRequest, response.status, "$body -> ${response.bodyAsText()}")
            assertContains(response.bodyAsText(), "INVALID_REQUEST")
            assertFalse(response.bodyAsText().contains("data: "), "invalid limits must be rejected before the SSE stream: $body")
        }
        // The continue endpoint validates before it reserves the conversation for a running turn.
        val continued = harness.request(
            HttpMethod.Post,
            "/api/investigations/unknown/continue",
            body = """{"collection":"Default","question":"q","profile":"investigator","limits":{"maxToolRounds":51}}""",
            credential = Credential.BEARER,
        )
        assertEquals(HttpStatusCode.BadRequest, continued.status, continued.bodyAsText())
        assertFalse(continued.bodyAsText().contains("data: "), "invalid continue limits must not open an SSE stream")

        val conversationCount = harness.context.database.read { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM conversations").use { rows ->
                    rows.next(); rows.getInt(1)
                }
            }
        }
        assertEquals(0, conversationCount, "invalid limits must create no conversation")
    }

    @Test
    fun `a partial limits object defaults the remaining fields and caps rounds on create`() = runBlocking {
        val sourceText = "Mira signed the note."
        val contentUnitId = addEvidenceUnit(sourceText)
        val arguments = """{"contentUnitId":"$contentUnitId"}""".replace("\"", "\\\"")
        val research = sse(
            listOf(
                """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call-1","type":"function","function":{"name":"read_content_unit","arguments":"$arguments"}}]},"finish_reason":null}]}""",
                """{"choices":[{"delta":{},"finish_reason":"tool_calls"}]}""",
            ),
        )
        val synthesis = sse(
            listOf(
                """{"choices":[{"delta":{"content":"Mira signed the note [S1]."},"finish_reason":"stop"}]}""",
            ),
        )

        FakeOpenAiServer(
            listOf(
                FakeOpenAiResponse(stream = true, body = research),
                FakeOpenAiResponse(stream = true, body = synthesis),
                FakeOpenAiResponse(body = titleCompletion("The signed note")),
            ),
        ).use { fake ->
            createProfile(endpoint = fake.url)
            val response = harness.request(
                HttpMethod.Post,
                "/api/investigations",
                body = """{"collection":"Default","question":"Who signed the note?","profile":"investigator","limits":{"maxToolRounds":1}}""",
                credential = Credential.BEARER,
            )

            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            assertContains(response.bodyAsText(), "\"code\":\"MAX_ROUNDS\"")
            assertContains(response.bodyAsText(), "\"type\":\"done\"")
            assertEquals(3, fake.requestBodies.size, "one configured round, one synthesis request, and one title request")
            val synthesisRequest = Json.parseToJsonElement(fake.requestBodies[1]).jsonObject
            assertFalse("tools" in synthesisRequest, "the final-answer request must not expose tools")
        }
    }

    @Test
    fun `a continue uses the submitted limits while keeping the locked conversation`() = runBlocking {
        val sourceText = "Mira signed the note."
        val contentUnitId = addEvidenceUnit(sourceText)
        val arguments = """{"contentUnitId":"$contentUnitId"}""".replace("\"", "\\\"")
        val research = sse(
            listOf(
                """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call-1","type":"function","function":{"name":"read_content_unit","arguments":"$arguments"}}]},"finish_reason":null}]}""",
                """{"choices":[{"delta":{},"finish_reason":"tool_calls"}]}""",
            ),
        )
        val synthesis = sse(
            listOf(
                """{"choices":[{"delta":{"content":"Mira signed the note [S1]."},"finish_reason":"stop"}]}""",
            ),
        )

        FakeOpenAiServer(
            listOf(
                FakeOpenAiResponse(stream = true, body = turnSse()),
                FakeOpenAiResponse(body = titleCompletion("The signed note")),
                FakeOpenAiResponse(stream = true, body = research),
                FakeOpenAiResponse(stream = true, body = synthesis),
            ),
        ).use { fake ->
            createProfile(endpoint = fake.url)
            // A second collection exists, so a request naming it would be accepted if the continue did
            // not keep the conversation's locked collection; the tool must still resolve Default's unit.
            harness.context.collectionService.create("Other")
            val created = harness.request(HttpMethod.Post, "/api/investigations", body = createBody(), credential = Credential.BEARER)
            assertContains(created.bodyAsText(), "\"type\":\"done\"")
            val conversationId = created.bodyAsText().substringAfter("\"type\":\"started\"").substringAfter("\"id\":\"").substringBefore('"')

            val continued = harness.request(
                HttpMethod.Post,
                "/api/investigations/$conversationId/continue",
                body = """{"collection":"Other","question":"and then?","profile":"investigator","limits":{"maxToolRounds":1,"maxToolCalls":5,"maxTurnSeconds":120}}""",
                credential = Credential.BEARER,
            )

            assertEquals(HttpStatusCode.OK, continued.status, continued.bodyAsText())
            assertContains(continued.bodyAsText(), "\"code\":\"MAX_ROUNDS\"")
            assertContains(continued.bodyAsText(), "\"id\":\"S1\"")
            assertContains(continued.bodyAsText(), "\"type\":\"done\"")
            assertEquals(4, fake.requestBodies.size, "opening turn, its title, one configured round, and synthesis")
            assertTrue("tools" in Json.parseToJsonElement(fake.requestBodies[2]).jsonObject, "the continue's research round must expose tools")
            assertFalse("tools" in Json.parseToJsonElement(fake.requestBodies[3]).jsonObject, "the continue's synthesis must not expose tools")
        }
    }

    // ---- Create / continue ----

    @Test
    fun `a continued investigation sends its persisted tool exchange to the provider`() = runBlocking {
        val toolCall = """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"document_metadata","arguments":"{\"documentId\":\"missing\"}"}}]},"finish_reason":null}]}"""
        val toolFinish = """{"choices":[{"delta":{},"finish_reason":"tool_calls"}]}"""
        val answer = sse(listOf(
            """{"choices":[{"delta":{"content":"No matching document."},"finish_reason":null}]}""",
            """{"choices":[{"delta":{},"finish_reason":"stop"}]}""",
        ))
        FakeOpenAiServer(listOf(
            FakeOpenAiResponse(stream = true, body = sse(listOf(toolCall, toolFinish))),
            FakeOpenAiResponse(stream = true, body = answer),
            // The first turn's titling completion lands between the two turns.
            FakeOpenAiResponse(statusCode = 200, body = titleCompletion("The missing document")),
            FakeOpenAiResponse(stream = true, body = answer),
        )).use { fake ->
            createProfile(endpoint = fake.url)
            val created = harness.request(HttpMethod.Post, "/api/investigations", body = createBody(), credential = Credential.BEARER)
            assertContains(created.bodyAsText(), "\"type\":\"done\"")
            val conversationId = created.bodyAsText().substringAfter("\"type\":\"started\"").substringAfter("\"id\":\"").substringBefore('"')

            val continued = harness.request(
                HttpMethod.Post, "/api/investigations/$conversationId/continue",
                body = createBody(question = "and then?"), credential = Credential.BEARER,
            )
            assertContains(continued.bodyAsText(), "\"type\":\"done\"")
            val messages = Json.parseToJsonElement(fake.requestBodies[3]).jsonObject.getValue("messages").jsonArray
            val assistant = messages.first { it.jsonObject["role"]?.jsonPrimitive?.content == "assistant" && "tool_calls" in it.jsonObject }
            val tool = messages.first { it.jsonObject["role"]?.jsonPrimitive?.content == "tool" }
            assertEquals("call_1", assistant.jsonObject.getValue("tool_calls").jsonArray.single().jsonObject.getValue("id").jsonPrimitive.content)
            assertEquals("call_1", tool.jsonObject.getValue("tool_call_id").jsonPrimitive.content)
        }
    }

    @Test
    fun `a create request streams a started event whose id continues the conversation`() = runBlocking {
        createProfile()

        val created = harness.request(
            HttpMethod.Post,
            "/api/investigations",
            body = createBody(),
            credential = Credential.BEARER,
        )

        assertEquals(HttpStatusCode.OK, created.status, created.bodyAsText())
        val createdBody = created.bodyAsText()
        val conversationId = createdBody.substringAfter("\"type\":\"started\"").substringAfter("\"id\":\"").substringBefore('"')
        assertTrue(conversationId.isNotBlank(), "expected a started event carrying the conversation id, got $createdBody")

        val continued = harness.request(
            HttpMethod.Post,
            "/api/investigations/$conversationId/continue",
            body = createBody(question = "and then?"),
            credential = Credential.BEARER,
        )

        assertEquals(HttpStatusCode.OK, continued.status, continued.bodyAsText())
        val continuedBody = continued.bodyAsText()
        assertContains(continuedBody, "\"type\":\"started\"", message = "a continue must surface its id as the first event")
        assertContains(continuedBody, conversationId)
    }

    @Test
    fun `continue with an unknown id streams a typed error and no provider call`() = runBlocking {
        createProfile()

        val response = harness.request(
            HttpMethod.Post,
            "/api/investigations/missing/continue",
            body = createBody(),
            credential = Credential.BEARER,
        )

        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val body = response.bodyAsText()
        val dataLines = body.lineSequence().filter { it.startsWith("data: ") }.toList()
        assertEquals(1, dataLines.size, "an unknown conversation must stream exactly the error event, got $body")
        assertContains(body, "\"code\":\"CONVERSATION_NOT_FOUND\"")
        assertFalse(body.contains("\"type\":\"started\""), "a missing conversation must not stream a started event")
        assertFalse(body.contains("\"type\":\"delta\""), "a missing conversation must never reach the provider")
    }

    @Test
    fun `a running conversation rejects a duplicate continue before another provider call`() = runBlocking {
        HangingEndpoint().use { hanging ->
            createProfile(endpoint = hanging.url)
            val collection = harness.context.collectionService.requireActiveByNameOrId("Default")
            val profile = harness.context.llm.findByName("investigator")!!
            val conversationId = harness.context.llm.persistInvestigateConversation(
                collectionId = collection.id,
                profile = profile,
                promptVersion = 1,
                retrievalSnapshot = RetrievalSnapshot.value(),
            )
            val body = createBody(question = "continue me")
            val first = launch {
                harness.request(
                    HttpMethod.Post,
                    "/api/investigations/$conversationId/continue",
                    body = body,
                    credential = Credential.BEARER,
                ).bodyAsText()
            }
            var duplicateStatus: HttpStatusCode? = null
            var duplicateBody = ""
            hanging.awaitConnections(1)
            val duplicate = launch {
                val response = harness.request(
                    HttpMethod.Post,
                    "/api/investigations/$conversationId/continue",
                    body = createBody(question = "duplicate"),
                    credential = Credential.BEARER,
                )
                duplicateStatus = response.status
                duplicateBody = response.bodyAsText()
            }
            try {
                val duplicateFinished = withTimeoutOrNull(2_000) {
                    duplicate.join()
                    true
                } ?: false
                assertTrue(duplicateFinished, "the duplicate request must be rejected without waiting for a second provider response")
                assertEquals(HttpStatusCode.Conflict, duplicateStatus, duplicateBody)
                assertContains(duplicateBody, "INVESTIGATION_ALREADY_RUNNING")
                assertEquals(1, hanging.connectionCount, "only the admitted turn may call the provider")
            } finally {
                harness.request(
                    HttpMethod.Post,
                    "/api/investigations/$conversationId/cancel",
                    body = null,
                    credential = Credential.BEARER,
                )
                first.cancel()
                duplicate.cancel()
                withTimeoutOrNull(2_000) { first.join(); duplicate.join() }
            }
        }
    }

    @Test
    fun `saved history is readable only through its collection and omits provider details`() = runBlocking {
        createProfile(endpoint = "https://provider.invalid/v1")
        val defaultCollection = harness.context.collectionService.requireActiveByNameOrId("Default")
        val otherCollection = harness.context.collectionService.create("Other")
        val profile = harness.context.llm.findByName("investigator")!!
        val conversationId = harness.context.llm.persistInvestigateConversation(
            collectionId = defaultCollection.id,
            profile = profile,
            promptVersion = 3,
            retrievalSnapshot = RetrievalSnapshot.value(),
        )
        harness.context.llm.persistInvestigateMessage(conversationId, 0, "user", "Who signed it?")
        harness.context.llm.persistInvestigateMessage(conversationId, 1, "assistant", "Mira signed it.")

        val loaded = harness.request(
            HttpMethod.Get,
            "/api/collections/${defaultCollection.id.value}/investigations/$conversationId",
            credential = Credential.BEARER,
        )
        assertEquals(HttpStatusCode.OK, loaded.status, loaded.bodyAsText())
        assertContains(loaded.bodyAsText(), "Who signed it?")
        assertContains(loaded.bodyAsText(), "Mira signed it.")
        assertFalse(loaded.bodyAsText().contains(profile.endpoint), "history must not expose provider endpoints")
        assertFalse(loaded.bodyAsText().contains("artifact_relative_path"), "history must not expose artifact paths")

        val crossCollection = harness.request(
            HttpMethod.Get,
            "/api/collections/${otherCollection.id.value}/investigations/$conversationId",
            credential = Credential.BEARER,
        )
        assertEquals(HttpStatusCode.NotFound, crossCollection.status, crossCollection.bodyAsText())
        val list = harness.request(
            HttpMethod.Get,
            "/api/collections/${otherCollection.id.value}/investigations",
            credential = Credential.BEARER,
        )
        assertEquals(HttpStatusCode.OK, list.status, list.bodyAsText())
        assertFalse(list.bodyAsText().contains(conversationId), "collection history must not list another collection's conversation")
    }

    // ---- Titles ----

    @Test
    fun `a new investigation is titled from its opening question by its own profile`() = runBlocking {
        FakeOpenAiServer(
            listOf(
                FakeOpenAiResponse(stream = true, body = turnSse()),
                FakeOpenAiResponse(statusCode = 200, body = titleCompletion("The fire")),
            ),
        ).use { fake ->
            createProfile(name = "title-investigator", endpoint = fake.url, model = "title-model")

            val created = harness.request(
                HttpMethod.Post,
                "/api/investigations",
                body = createBody(question = "What happened in the fire?", profile = "title-investigator"),
                credential = Credential.BEARER,
            )

            assertEquals(HttpStatusCode.OK, created.status, created.bodyAsText())
            val createdBody = created.bodyAsText()
            val conversationId = createdBody.substringAfter("\"type\":\"started\"").substringAfter("\"id\":\"").substringBefore('"')
            assertContains(createdBody, "\"type\":\"done\"", message = "the turn must complete, got $createdBody")
            assertEquals(2, fake.handledRequests, "the turn stream plus one title completion")
            assertContains(fake.requestBodies[1], "\"model\":\"title-model\"", message = "the title call must use the conversation's locked model")
            assertContains(fake.requestBodies[1], "\"stream\":false", message = "the title call must be the non-streaming completion boundary")

            val defaultCollectionId = harness.context.collectionService.requireActiveByNameOrId("Default").id.value
            val list = harness.request(
                HttpMethod.Get,
                "/api/collections/$defaultCollectionId/investigations",
                credential = Credential.BEARER,
            )
            assertEquals(HttpStatusCode.OK, list.status, list.bodyAsText())
            assertContains(list.bodyAsText(), "\"title\":\"The fire\"", message = "the list must return the stored title")

            val profileId = harness.context.llm.findByName("title-investigator")!!.id
            harness.context.database.read { connection ->
                val storedTitle = connection.prepareStatement("SELECT title FROM conversations WHERE id = ?").use { statement ->
                    statement.setString(1, conversationId)
                    statement.executeQuery().use { rows -> rows.next(); rows.getString("title") }
                }
                assertEquals("The fire", storedTitle, "the title must be written to the conversation row")
                val modelCalls = connection.prepareStatement("SELECT COUNT(*) FROM model_calls WHERE conversation_id = ?").use { statement ->
                    statement.setString(1, conversationId)
                    statement.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
                }
                assertEquals(1, modelCalls, "the title call must not be recorded as a conversation model call")
                val totals = connection.prepareStatement("SELECT calls FROM usage_totals WHERE profile_id = ?").use { statement ->
                    statement.setString(1, profileId)
                    statement.executeQuery().use { rows -> rows.next(); rows.getInt("calls") }
                }
                assertEquals(1, totals, "the title tokens must not touch usage_totals")
            }
        }
    }

    @Test
    fun `continuing an investigation never rewrites its title`() = runBlocking {
        FakeOpenAiServer(
            listOf(
                FakeOpenAiResponse(stream = true, body = turnSse()),
                FakeOpenAiResponse(statusCode = 200, body = titleCompletion("Original title")),
                FakeOpenAiResponse(stream = true, body = turnSse()),
            ),
        ).use { fake ->
            createProfile(endpoint = fake.url)

            val created = harness.request(HttpMethod.Post, "/api/investigations", body = createBody(), credential = Credential.BEARER)
            val conversationId = created.bodyAsText().substringAfter("\"type\":\"started\"").substringAfter("\"id\":\"").substringBefore('"')
            assertContains(created.bodyAsText(), "\"type\":\"done\"")

            val continued = harness.request(
                HttpMethod.Post,
                "/api/investigations/$conversationId/continue",
                body = createBody(question = "and then?"),
                credential = Credential.BEARER,
            )
            assertEquals(HttpStatusCode.OK, continued.status, continued.bodyAsText())
            assertContains(continued.bodyAsText(), "\"type\":\"done\"")

            assertEquals(3, fake.handledRequests, "a continued turn must never consume a title completion")
            val storedTitle = harness.context.database.read { connection ->
                connection.prepareStatement("SELECT title FROM conversations WHERE id = ?").use { statement ->
                    statement.setString(1, conversationId)
                    statement.executeQuery().use { rows -> rows.next(); rows.getString("title") }
                }
            }
            assertEquals("Original title", storedTitle, "a continued conversation must keep its first title")
        }
    }

    @Test
    fun `a failing title call leaves the investigation without a title and does not fail the turn`() = runBlocking {
        val failures = listOf(
            "a provider error" to FakeOpenAiResponse(statusCode = 500, body = "{}"),
            "a malformed completion" to FakeOpenAiResponse(statusCode = 200, body = "not-a-response"),
            "an empty completion" to FakeOpenAiResponse(statusCode = 200, body = """{"choices":[{"message":{"role":"assistant","content":""}}]}"""),
        )
        failures.forEachIndexed { index, (label, titleFailure) ->
            FakeOpenAiServer(
                listOf(FakeOpenAiResponse(stream = true, body = turnSse()), titleFailure),
            ).use { fake ->
                createProfile(name = "failure-investigator-$index", endpoint = fake.url)

                val created = harness.request(
                    HttpMethod.Post,
                    "/api/investigations",
                    body = createBody(profile = "failure-investigator-$index"),
                    credential = Credential.BEARER,
                )
                assertEquals(HttpStatusCode.OK, created.status, "$label: ${created.bodyAsText()}")
                val createdBody = created.bodyAsText()
                val conversationId = createdBody.substringAfter("\"type\":\"started\"").substringAfter("\"id\":\"").substringBefore('"')
                assertContains(createdBody, "\"type\":\"done\"", message = "$label: the turn must still complete")
                assertEquals(2, fake.handledRequests, "$label: the title call must still happen exactly once")

                harness.context.database.read { connection ->
                    val storedTitle = connection.prepareStatement("SELECT title FROM conversations WHERE id = ?").use { statement ->
                        statement.setString(1, conversationId)
                        statement.executeQuery().use { rows -> rows.next(); rows.getString("title") }
                    }
                    assertEquals(null, storedTitle, "$label: no title may be stored")
                    val modelCalls = connection.prepareStatement("SELECT COUNT(*) FROM model_calls WHERE conversation_id = ?").use { statement ->
                        statement.setString(1, conversationId)
                        statement.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
                    }
                    assertEquals(1, modelCalls, "$label: the title call must not be recorded as a conversation model call")
                }
            }
        }
    }

    @Test
    fun `the investigation list returns the title and falls back to the opening question`() = runBlocking {
        createProfile(endpoint = "https://provider.invalid/v1")
        val defaultCollection = harness.context.collectionService.requireActiveByNameOrId("Default")
        val profile = harness.context.llm.findByName("investigator")!!
        val conversationId = harness.context.llm.persistInvestigateConversation(
            collectionId = defaultCollection.id,
            profile = profile,
            promptVersion = 1,
            retrievalSnapshot = RetrievalSnapshot.value(),
        )
        harness.context.llm.persistInvestigateMessage(conversationId, 0, "user", "Who signed it?")
        harness.context.llm.persistInvestigateMessage(conversationId, 1, "assistant", "Mira signed it.")
        val titledId = harness.context.llm.persistInvestigateConversation(
            collectionId = defaultCollection.id,
            profile = profile,
            promptVersion = 1,
            retrievalSnapshot = RetrievalSnapshot.value(),
        )
        harness.context.llm.persistInvestigateMessage(titledId, 0, "user", "What day was it?")
        harness.context.llm.persistInvestigateMessage(titledId, 1, "assistant", "Sunday.")
        harness.context.llm.setConversationTitle(titledId, "The signing")

        val list = harness.request(
            HttpMethod.Get,
            "/api/collections/${defaultCollection.id.value}/investigations",
            credential = Credential.BEARER,
        )

        assertEquals(HttpStatusCode.OK, list.status, list.bodyAsText())
        val body = list.bodyAsText()
        assertContains(body, "\"title\":\"The signing\"", message = "a titled row must show its title")
        assertContains(body, "\"title\":\"Who signed it?\"", message = "a row without a title must fall back to its question")
    }

    // ---- Cancel ----

    @Test
    fun `cancel of an unknown id is a 404`() = runBlocking {
        val response = harness.request(
            HttpMethod.Post,
            "/api/investigations/nope/cancel",
            body = null,
            credential = Credential.BEARER,
        )

        assertEquals(HttpStatusCode.NotFound, response.status, response.bodyAsText())
    }

    @Test
    fun `cancel stops a running turn and unregisters it once the stream ends`() = runBlocking {
        HangingEndpoint().use { hanging ->
            createProfile(endpoint = hanging.url)

            val turn = launch {
                harness.request(
                    HttpMethod.Post,
                    "/api/investigations",
                    body = createBody(),
                    credential = Credential.BEARER,
                ).bodyAsText()
            }

            try {
                // The conversation row is created before the provider call hangs; the running-turn
                // registry fills a moment after Started is emitted, so poll for the 200 instead of
                // racing the registration.
                val conversationId = awaitConversationId()
                assertTrue(
                    awaitStatus(conversationId, expected = HttpStatusCode.OK),
                    "cancel must answer 200 while the turn is running",
                )
                assertTrue(
                    awaitStatus(conversationId, expected = HttpStatusCode.NotFound),
                    "a cancelled turn must be unregistered once its stream ends",
                )
                withTimeout(30_000) { turn.join() }
            } finally {
                turn.cancel()
            }
        }
    }

    @Test
    fun `a completed create turn persists messages model calls and usage totals to the real database`() = runBlocking {
        FakeOpenAiServer(
            listOf(
                FakeOpenAiResponse(
                    stream = true,
                    body = sse(
                        listOf(
                            """{"choices":[{"delta":{"content":"no sources"},"finish_reason":null}]}""",
                            """{"choices":[{"delta":{"finish_reason":"stop"}}],"usage":{"prompt_tokens":7,"completion_tokens":2,"cache_read_input_tokens":3}}""",
                        ),
                    ),
                ),
            ),
        ).use { fake ->
            createProfile(endpoint = fake.url, inputPricePerMillion = 1.0, outputPricePerMillion = 1.0)

            val response = harness.request(
                HttpMethod.Post,
                "/api/investigations",
                body = createBody(),
                credential = Credential.BEARER,
            )

            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            val body = response.bodyAsText()
            assertContains(body, "\"type\":\"done\"", message = "the turn must complete against the fake provider, got $body")
            val conversationId = body.substringAfter("\"type\":\"started\"").substringAfter("\"id\":\"").substringBefore('"')
            assertTrue(conversationId.isNotBlank(), "expected a started event carrying the conversation id, got $body")

            val profileId = harness.context.llm.list().first { it.name == "investigator" }.id
            val defaultCollectionId = harness.context.collectionService.list().first { it.name == "Default" }.id.value

            harness.context.database.read { connection ->
                val conversation = connection.prepareStatement(
                    "SELECT collection_id, mode, profile_provider, profile_endpoint, profile_model, profile_name, prompt_version, retrieval_snapshot FROM conversations WHERE id = ?",
                ).use { statement ->
                    statement.setString(1, conversationId)
                    statement.executeQuery().use { rows ->
                        assertTrue(rows.next(), "the route must create a conversations row for the completed turn")
                        ConversationRow(
                            collectionId = rows.getString("collection_id"),
                            mode = rows.getString("mode"),
                            provider = rows.getString("profile_provider"),
                            endpoint = rows.getString("profile_endpoint"),
                            model = rows.getString("profile_model"),
                            profileName = rows.getString("profile_name"),
                            promptVersion = rows.getInt("prompt_version"),
                            retrievalSnapshot = rows.getString("retrieval_snapshot"),
                        )
                    }
                }
                assertEquals("INVESTIGATE", conversation.mode)
                assertEquals(defaultCollectionId, conversation.collectionId, "the conversation locks the requested collection")
                assertEquals("OPENAI_COMPATIBLE", conversation.provider)
                assertEquals(fake.url, conversation.endpoint, "the conversation locks the profile's endpoint")
                assertEquals("model", conversation.model)
                assertEquals("investigator", conversation.profileName)
                assertEquals(
                    harness.context.llm.effectivePromptVersion(LlmPromptRole.INVESTIGATE),
                    conversation.promptVersion,
                    "the conversation persists the real prompt version",
                )
                assertEquals(RetrievalSnapshot.value(), conversation.retrievalSnapshot)
                assertNotEquals("{}", conversation.retrievalSnapshot, "the retrieval snapshot must be the real one")

                val messageCount = connection.prepareStatement(
                    "SELECT COUNT(*) FROM messages WHERE conversation_id = ?",
                ).use { statement ->
                    statement.setString(1, conversationId)
                    statement.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
                }
                assertTrue(messageCount >= 3, "expected the system, user and assistant messages, got $messageCount")

                val modelCallCount = connection.prepareStatement(
                    "SELECT COUNT(*) FROM model_calls WHERE conversation_id = ?",
                ).use { statement ->
                    statement.setString(1, conversationId)
                    statement.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
                }
                assertEquals(1, modelCallCount, "a plain-text turn must persist exactly one model call")
                val usage = connection.prepareStatement(
                    "SELECT input_tokens, output_tokens, cost_usd FROM model_calls WHERE conversation_id = ?",
                ).use { statement ->
                    statement.setString(1, conversationId)
                    statement.executeQuery().use { rows ->
                        assertTrue(rows.next(), "the route must persist the completed model call")
                        Triple(rows.getLong("input_tokens"), rows.getLong("output_tokens"), rows.getDouble("cost_usd"))
                    }
                }
                assertTrue(
                    usage.first > 0L && usage.second > 0L && usage.third > 0.0,
                    "the model call must carry non-zero usage and price-derived cost, got $usage",
                )

                val totals = connection.prepareStatement(
                    "SELECT calls, input_tokens, output_tokens, cost_usd FROM usage_totals WHERE profile_id = ?",
                ).use { statement ->
                    statement.setString(1, profileId)
                    statement.executeQuery().use { rows ->
                        assertTrue(rows.next(), "the completed turn must move the profile's usage_totals")
                        UsageTotalsRow(
                            calls = rows.getInt("calls"),
                            inputTokens = rows.getLong("input_tokens"),
                            outputTokens = rows.getLong("output_tokens"),
                            costUsd = rows.getDouble("cost_usd"),
                        )
                    }
                }
                assertTrue(totals.calls >= 1, "usage_totals must count the completed call, got ${totals.calls}")
                assertTrue(
                    totals.inputTokens > 0L && totals.outputTokens > 0L && totals.costUsd > 0.0,
                    "usage_totals must carry the turn's usage and cost, got $totals",
                )
            }
        }
    }

    @Test
    fun `the done wire carries evidence with its locator and omits the excerpt`() {
        val json = ApiJson.encodeToString(
            InvestigateEvent.Done(
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

    // ---- The credential boundary ----

    @Test
    fun `the investigation endpoints require the bearer credential`() = runBlocking {
        val create = harness.request(HttpMethod.Post, "/api/investigations", body = createBody(), credential = Credential.NONE)
        assertEquals(HttpStatusCode.Unauthorized, create.status, create.bodyAsText())

        val continueTry = harness.request(HttpMethod.Post, "/api/investigations/anything/continue", body = createBody(), credential = Credential.NONE)
        assertEquals(HttpStatusCode.Unauthorized, continueTry.status, continueTry.bodyAsText())

        val cancelTry = harness.request(HttpMethod.Post, "/api/investigations/anything/cancel", body = null, credential = Credential.NONE)
        assertEquals(HttpStatusCode.Unauthorized, cancelTry.status, cancelTry.bodyAsText())
    }

    // ---- Helpers ----

    private fun sse(dataLines: List<String>): String =
        dataLines.map { "data: $it\n\n" }.joinToString("") + "data: [DONE]\n\n"

    private fun turnSse(): String = sse(
        listOf(
            """{"choices":[{"delta":{"content":"no sources"},"finish_reason":null}]}""",
            """{"choices":[{"delta":{"finish_reason":"stop"}}],"usage":{"prompt_tokens":7,"completion_tokens":2}}""",
        ),
    )

    private fun titleCompletion(title: String): String =
        """{"choices":[{"message":{"role":"assistant","content":"$title"}}],"usage":{"prompt_tokens":9,"completion_tokens":3}}"""

    private fun addEvidenceUnit(text: String): String {
        val collection = harness.context.collectionService.requireActiveByNameOrId("Default")
        val documentId = DocumentId.new()
        val now = Instants.now()
        harness.context.documents.insert(
            Document(
                id = documentId,
                collectionId = collection.id,
                sha256 = "f".repeat(64),
                mediaType = "text/plain",
                originalFilename = "round-limit.txt",
                sourcePath = "tests/round-limit.txt",
                sizeBytes = text.length.toLong(),
                status = DocumentStatus.COMPLETE,
                createdAt = now,
                updatedAt = now,
                title = "round limit source",
                author = null,
                language = "en",
            ),
        )
        return harness.context.content.commitExtractedUnit(
            documentId = documentId,
            fingerprint = ExtractionFingerprint.of(
                "f".repeat(64),
                ExtractionSettings(ocrLanguages = "eng", extractorSchemaVersion = "round-limit-test"),
            ),
            key = "round-limit-unit",
            ordinal = 0,
            draft = ContentUnitDraft(
                locator = SourceLocation.TextLines(1, 1),
                extractedText = text,
                searchText = text,
            ),
            artifactRoot = harness.context.paths.libraryDir,
        ).unit.id.value
    }

    private data class ConversationRow(
        val collectionId: String,
        val mode: String,
        val provider: String,
        val endpoint: String?,
        val model: String,
        val profileName: String,
        val promptVersion: Int,
        val retrievalSnapshot: String,
    )

    private data class UsageTotalsRow(
        val calls: Int,
        val inputTokens: Long,
        val outputTokens: Long,
        val costUsd: Double,
    )

    private suspend fun awaitConversationId(): String {
        repeat(200) {
            val id = harness.context.database.read { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT id FROM conversations WHERE mode = 'INVESTIGATE' ORDER BY created_at DESC LIMIT 1").use { rows ->
                        if (rows.next()) rows.getString(1) else null
                    }
                }
            }
            if (id != null) return id
            delay(20)
        }
        error("no investigation conversation was created")
    }

    private suspend fun awaitStatus(conversationId: String, expected: HttpStatusCode): Boolean {
        repeat(200) {
            val response = harness.request(
                HttpMethod.Post,
                "/api/investigations/$conversationId/cancel",
                body = null,
                credential = Credential.BEARER,
            )
            if (response.status == expected) return true
            delay(20)
        }
        return false
    }

    /**
     * Accepts TCP connections and holds them open without ever answering, so a provider call against
     * it stays in flight until the turn is cancelled rather than failing fast.
     */
    private class HangingEndpoint : AutoCloseable {
        private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        private val acceptedConnections = AtomicInteger()
        val connectionCount: Int get() = acceptedConnections.get()
        private val acceptor = thread(name = "hanging-endpoint") {
            try {
                while (true) {
                    val socket = server.accept()
                    acceptedConnections.incrementAndGet()
                    thread(name = "hanging-connection") {
                        try {
                            socket.inputStream.use { stream -> while (stream.read() != -1) Unit }
                        } catch (_: Exception) {
                            // the client cancelled or the test closed
                        } finally {
                            runCatching { socket.close() }
                        }
                    }
                }
            } catch (_: Exception) {
                // the test closed the server
            }
        }

        private val started = runCatching { server.localPort }.getOrThrow()

        val url: String = "http://127.0.0.1:$started"

        suspend fun awaitConnections(expected: Int) {
            repeat(200) {
                if (connectionCount >= expected) return
                delay(20)
            }
            error("expected $expected provider connections, saw $connectionCount")
        }

        override fun close() {
            runCatching { server.close() }
            acceptor.join(2_000)
        }
    }
}
