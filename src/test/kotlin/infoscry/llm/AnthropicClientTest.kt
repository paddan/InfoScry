package infoscry.llm

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.toList
import kotlin.time.Duration.Companion.seconds

/**
 * The Anthropic adapter, reusing the same provider-agnostic `FakeOpenAiServer` script fixture (it serves
 * whatever body it is given; only the SSE body construction differs between the providers). Covers
 * fragmentation, tool-use block assembly, provider errors that must never leak, malformed streams,
 * cancellation, the "never retry after a delta" rule, and the retry budget.
 */
class AnthropicClientTest {

    @Test
    fun `optional tools do not emit a tool choice`() = runBlocking {
        withServer(
            listOf(
                FakeOpenAiResponse(
                    stream = true,
                    body = anthropicStream(
                        listOf(
                            AnthropicWireEvent(
                                "content_block_delta",
                                """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"ok"}}""",
                            ),
                        ),
                    ),
                ),
            ),
        ) { server, _, llm ->
            llm.stream(
                LlmRequest(
                    messages = listOf(LlmMessage("user", "You may call ping.")),
                    tools = listOf(ToolDefinition("ping", "Nothing but a reply.")),
                ),
            ).toList()
            assertFalse(LlmJson.parseToJsonElement(server.requestBody!!).jsonObject.containsKey("tool_choice"))
        }
    }

    @Test
    fun `a required tool choice names the requested tool on the wire`() = runBlocking {
        withServer(
            listOf(
                FakeOpenAiResponse(
                    stream = true,
                    body = anthropicStream(
                        listOf(
                            AnthropicWireEvent(
                                "content_block_delta",
                                """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"ok"}}""",
                            ),
                        ),
                    ),
                ),
            ),
        ) { server, _, llm ->
            llm.stream(
                LlmRequest(
                    messages = listOf(LlmMessage("user", "Call ping.")),
                    tools = listOf(ToolDefinition("ping", "Nothing but a reply.")),
                    requiredToolName = "ping",
                ),
            ).toList()

            val request = LlmJson.parseToJsonElement(server.requestBody!!).jsonObject
            val choice = request.getValue("tool_choice").jsonObject
            assertEquals("tool", choice.getValue("type").jsonPrimitive.content)
            assertEquals("ping", choice.getValue("name").jsonPrimitive.content)
        }
    }

    @Test
    fun `a text answer reassembles from fragments and reports usage including cache reads`() = runBlocking {
        withServer(
            listOf(
                FakeOpenAiResponse(
                    stream = true,
                    fragmentBytes = 3,
                    gapMillis = 1,
                    body = anthropicStream(
                        listOf(
                            AnthropicWireEvent(
                                "content_block_start",
                                """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":"Hel"}}""",
                            ),
                            AnthropicWireEvent(
                                "content_block_delta",
                                """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"lo"}}""",
                            ),
                            AnthropicWireEvent(
                                "message_delta",
                                """{"type":"message_delta","usage":{"input_tokens":7,"output_tokens":2,"cache_read_input_tokens":3}}""",
                            ),
                        ),
                    ),
                ),
            ),
        ) { _, _, llm ->
            assertEquals(
                listOf(
                    LlmEvent.TextDelta("Hel"),
                    LlmEvent.TextDelta("lo"),
                    LlmEvent.Usage(TokenUsage(inputTokens = 7, outputTokens = 2, cacheReadTokens = 3)),
                    LlmEvent.Completed,
                ),
                llm.stream(request()).toList(),
            )
        }
    }

    @Test
    fun `two fragmented tool calls assemble their argument json in block index order`() = runBlocking {
        withServer(
            listOf(
                FakeOpenAiResponse(
                    stream = true,
                    fragmentBytes = 2,
                    gapMillis = 1,
                    body = anthropicStream(
                        listOf(
                            AnthropicWireEvent(
                                "content_block_start",
                                """{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"toolu_1","name":"find","input":{}}}""",
                            ),
                            AnthropicWireEvent(
                                "content_block_start",
                                """{"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_2","name":"count","input":{}}}""",
                            ),
                            AnthropicWireEvent(
                                "content_block_delta",
                                """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"q"}}""",
                            ),
                            AnthropicWireEvent(
                                "content_block_delta",
                                """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"y"}}""",
                            ),
                            AnthropicWireEvent(
                                "content_block_delta",
                                """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"uery\":\"nightfall\"}"}}""",
                            ),
                            AnthropicWireEvent(
                                "content_block_delta",
                                """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"ear\":2020}"}}""",
                            ),
                            AnthropicWireEvent(
                                "content_block_stop",
                                """{"type":"content_block_stop","index":0}""",
                            ),
                            AnthropicWireEvent(
                                "content_block_stop",
                                """{"type":"content_block_stop","index":1}""",
                            ),
                        ),
                    ),
                ),
            ),
        ) { _, _, llm ->
            assertEquals(
                listOf(
                    LlmEvent.ToolCallReady(
                        ToolCall(id = "toolu_1", name = "find", arguments = """{"query":"nightfall"}"""),
                    ),
                    LlmEvent.ToolCallReady(
                        ToolCall(id = "toolu_2", name = "count", arguments = """{"year":2020}"""),
                    ),
                    LlmEvent.Completed,
                ),
                llm.stream(request()).toList(),
            )
        }
    }

    @Test
    fun `an error response carries no response body and no key value anywhere`() = runBlocking {
        val secret = "super-secret-key-abc"
        withServer(
            listOf(
                FakeOpenAiResponse(
                    statusCode = 401,
                    body = """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key $secret"}}""",
                ),
            ),
            secret = secret,
        ) { server, _, llm ->
            val failure = assertFailsWith<LlmError.AuthenticationError> {
                llm.stream(request()).toList()
            }
            assertFalse((failure.message ?: "").contains(secret), "the error message must not echo the key")
            assertFalse(failure.toString().contains(secret), "the full error text must not echo the key")
            assertTrue(server.xApiKey == secret, "the key must have reached the x-api-key header")
            assertEquals(1, server.handledRequests, "authentication errors are not retried")
        }
    }

    @Test
    fun `a stream chunk that is not json is a malformed response, never a partial success`() = runBlocking {
        withServer(
            listOf(
                FakeOpenAiResponse(
                    stream = true,
                    body = anthropicSse(
                        "content_block_delta",
                        """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"ok"}}""",
                    ) + "data: not-json\n\n",
                ),
            ),
        ) { server, _, llm ->
            assertFailsWith<LlmError.MalformedResponseError> {
                llm.stream(request()).toList()
            }
            assertEquals(1, server.handledRequests, "a malformed stream after the first delta is not retried")
        }
    }

    @Test
    fun `a stream that ends before a started tool call is complete is a malformed response`() = runBlocking {
        withServer(
            listOf(
                // A tool-use block starts with an id but the stream ends without content_block_stop, and
                // there is no message_stop — the open block can never be presented as complete.
                FakeOpenAiResponse(
                    stream = true,
                    body = anthropicSse(
                        "content_block_start",
                        """{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"toolu_1","name":"find","input":{}}}""",
                    ),
                ),
            ),
        ) { server, _, llm ->
            assertFailsWith<LlmError.MalformedResponseError> {
                llm.stream(request()).toList()
            }
            assertEquals(1, server.handledRequests, "a truncated stream after the first delta is not retried")
        }
    }

    @Test
    fun `a stream that emitted a delta and then failed is not retried`() = runBlocking {
        withServer(
            listOf(
                // A 200 stream starts emitting text, then the connection delivers a broken event that
                // cannot be parsed. The retry window is long gone: replaying would duplicate content a
                // consumer has already seen, so exactly one request must have been made.
                FakeOpenAiResponse(
                    stream = true,
                    body = anthropicSse(
                        "content_block_delta",
                        """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"partial"}}""",
                    ) + "data: {{{{not json\n\n",
                ),
            ),
        ) { server, _, llm ->
            assertFailsWith<LlmError.MalformedResponseError> {
                llm.stream(request()).toList()
            }
            assertEquals(1, server.handledRequests, "no retry happens after a delta was emitted")
        }
    }

    @Test
    fun `cancelling the consumer mid-stream unwinds a blocked read and is not retried`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        withServer(
            listOf(
                FakeOpenAiResponse(
                    stream = true,
                    body = anthropicSse(
                        "content_block_delta",
                        """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"partial"}}""",
                    ),
                    declaredLength = 10_000_000,
                    holdMillis = 60_000,
                    onFirstFragment = { started.complete(Unit) },
                ),
            ),
        ) { server, _, llm ->
            val collector = launch { llm.stream(request()).toList() }
            withTimeout(30.seconds) { started.await() } // the request reached the server, deterministically
            collector.cancel()
            collector.join() // kotlinx.coroutines 1.10 treats cancel() as normal cancellation
            assertTrue(collector.isCancelled, "the cancelled consumer ends cancelled, not completed or failed")
            assertEquals(1, server.handledRequests, "a cancelled stream is not retried")
        }
    }

    @Test
    fun `a rate limit is retried with the injected policy and then the stream succeeds`() = runBlocking {
        withServer(
            listOf(
                FakeOpenAiResponse(statusCode = 429, body = """{"type":"error","error":{"message":"slow down"}}"""),
                FakeOpenAiResponse(
                    stream = true,
                    body = anthropicStream(
                        listOf(
                            AnthropicWireEvent(
                                "content_block_delta",
                                """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"ok"}}""",
                            ),
                        ),
                    ),
                ),
            ),
        ) { server, _, llm ->
            assertEquals(
                listOf(LlmEvent.TextDelta("ok"), LlmEvent.Completed),
                llm.stream(request()).toList(),
            )
            assertEquals(2, server.handledRequests, "one 429 then one 200")
        }
    }

    @Test
    fun `an assistant tool-call message encodes tool_use blocks and its results encode tool_result blocks`() = runBlocking {
        withServer(
            listOf(
                FakeOpenAiResponse(
                    stream = true,
                    body = anthropicStream(
                        listOf(
                            AnthropicWireEvent(
                                "content_block_delta",
                                """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"ok"}}""",
                            ),
                        ),
                    ),
                ),
            ),
        ) { server, _, llm ->
            llm.stream(
                LlmRequest(
                    messages = listOf(
                        LlmMessage("user", "Run both."),
                        LlmMessage(
                            role = "assistant",
                            content = "",
                            toolCalls = listOf(
                                ToolCall("call_1", "find", """{"query":"nightfall"}"""),
                                ToolCall("call_2", "count", """{"year":2020}"""),
                            ),
                        ),
                        LlmMessage("tool", """[{"title":"x"}]""", toolCallId = "call_1"),
                        LlmMessage("tool", "42", toolCallId = "call_2"),
                    ),
                ),
            ).toList()

            val messages = LlmJson.parseToJsonElement(server.requestBody!!).jsonObject.getValue("messages").jsonArray
            val assistant = messages[1].jsonObject
            assertEquals("assistant", assistant.getValue("role").jsonPrimitive.content)
            val blocks = assistant.getValue("content").jsonArray
            assertEquals(2, blocks.size)
            val first = blocks[0].jsonObject
            assertEquals("tool_use", first.getValue("type").jsonPrimitive.content)
            assertEquals("call_1", first.getValue("id").jsonPrimitive.content)
            assertEquals("find", first.getValue("name").jsonPrimitive.content)
            assertEquals(LlmJson.parseToJsonElement("""{"query":"nightfall"}"""), first.getValue("input"))
            assertTrue(first.getValue("input") is JsonObject, "the tool input must be the parsed object, not a string")
            val second = blocks[1].jsonObject
            assertEquals("tool_use", second.getValue("type").jsonPrimitive.content)
            assertEquals("call_2", second.getValue("id").jsonPrimitive.content)
            assertEquals("count", second.getValue("name").jsonPrimitive.content)
            assertEquals(LlmJson.parseToJsonElement("""{"year":2020}"""), second.getValue("input"))

            val firstResult = messages[2].jsonObject
            assertEquals("user", firstResult.getValue("role").jsonPrimitive.content, "a tool result is sent as the user role")
            val firstResultBlocks = firstResult.getValue("content").jsonArray
            assertEquals(1, firstResultBlocks.size)
            assertEquals("tool_result", firstResultBlocks[0].jsonObject.getValue("type").jsonPrimitive.content)
            assertEquals("call_1", firstResultBlocks[0].jsonObject.getValue("tool_use_id").jsonPrimitive.content)
            assertEquals("""[{"title":"x"}]""", firstResultBlocks[0].jsonObject.getValue("content").jsonPrimitive.content)
            val secondResult = messages[3].jsonObject
            assertEquals("user", secondResult.getValue("role").jsonPrimitive.content)
            assertEquals("call_2", secondResult.getValue("content").jsonArray[0].jsonObject.getValue("tool_use_id").jsonPrimitive.content)
            assertEquals("42", secondResult.getValue("content").jsonArray[0].jsonObject.getValue("content").jsonPrimitive.content)
        }
    }

    @Test
    fun `a plain text request body is unchanged`() = runBlocking {
        withServer(
            listOf(
                FakeOpenAiResponse(
                    stream = true,
                    body = anthropicStream(
                        listOf(
                            AnthropicWireEvent(
                                "content_block_delta",
                                """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"ok"}}""",
                            ),
                        ),
                    ),
                ),
            ),
        ) { server, _, llm ->
            llm.stream(request()).toList()
            val message = LlmJson.parseToJsonElement(server.requestBody!!).jsonObject.getValue("messages").jsonArray[0].jsonObject
            assertEquals("user", message.getValue("role").jsonPrimitive.content)
            assertEquals("q", message.getValue("content").jsonPrimitive.content)
        }
    }

    // ---- helpers ----

    private fun request(): LlmRequest = LlmRequest(messages = listOf(LlmMessage("user", "q")))

    /** Runs [block] against a scripted fake endpoint, closing the client and the server afterwards. */
    private suspend fun withServer(
        script: List<FakeOpenAiResponse>,
        secret: String? = null,
        block: suspend (FakeOpenAiServer, HttpClient, AnthropicClient) -> Unit,
    ) {
        val server = FakeOpenAiServer(script)
        val client = HttpClient(CIO)
        try {
            val llm = AnthropicClient(
                profile(server.url, secret),
                lookupOf(secret),
                client,
                // No real sleeps in tests; the policy itself is covered by RetryPolicyTest.
                RetryPolicy(retryDelay = { }),
            )
            block(server, client, llm)
        } finally {
            client.close()
            server.close()
        }
    }

    private fun profile(endpoint: String, secret: String?): LlmProfile = LlmProfile(
        id = "test-profile",
        name = "test",
        provider = LlmProvider.ANTHROPIC,
        model = "test-model",
        contextWindow = 8192,
        maxOutputTokens = 512,
        inputPricePerMillion = 1.0,
        outputPricePerMillion = 1.0,
        cacheReadPricePerMillion = 0.0,
        enabled = true,
        endpoint = endpoint,
        apiKeyEnvironmentVariable = if (secret != null) "FAKE_LLM_KEY" else null,
    )

    private fun lookupOf(secret: String?): (String) -> String? =
        { variable -> if (variable == "FAKE_LLM_KEY") secret else null }

    /** One Anthropic SSE event: an `event:` type line plus a `data:` JSON line. */
    private fun anthropicSse(type: String, dataJson: String): String = "event: $type\n" +
        "data: $dataJson\n\n"

    /** One scripted wire event: the real `event:` type and its JSON payload. */
    private data class AnthropicWireEvent(val type: String, val json: String)

    /** A complete stream: the given events, then `message_stop`. */
    private fun anthropicStream(events: List<AnthropicWireEvent>): String =
        events.map { anthropicSse(it.type, it.json) }.joinToString("") +
            anthropicSse(
                "message_stop",
                """{"type":"message_stop"}""",
            )
}
