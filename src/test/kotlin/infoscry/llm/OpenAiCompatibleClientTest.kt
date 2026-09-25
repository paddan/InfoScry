package infoscry.llm

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.toList
import kotlin.time.Duration.Companion.seconds

/**
 * The OpenAI-compatible adapter against a real local socket: fragmentation, multiple tool calls,
 * provider errors that must never leak, malformed streams, cancellation, and the retry budget.
 */
class OpenAiCompatibleClientTest {

    @Test
    fun `a required tool name must be one of the supplied tools`() {
        assertFailsWith<IllegalArgumentException> {
            LlmRequest(
                messages = listOf(LlmMessage("user", "Call ping.")),
                tools = listOf(ToolDefinition("ping", "Nothing but a reply.")),
                requiredToolName = "other",
            )
        }
    }

    @Test
    fun `optional tools do not emit a tool choice`() = runBlocking {
        withServer(
            listOf(FakeOpenAiResponse(stream = true, body = sse(listOf("""{"choices":[{"delta":{"content":"ok"},"finish_reason":"stop"}]}""")))),
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
    fun `a required tool choice names the requested function on the wire`() = runBlocking {
        withServer(
            listOf(
                FakeOpenAiResponse(
                    stream = true,
                    body = sse(listOf("""{"choices":[{"delta":{"content":"ok"},"finish_reason":"stop"}]}""")),
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
            assertEquals("function", choice.getValue("type").jsonPrimitive.content)
            assertEquals("ping", choice.getValue("function").jsonObject.getValue("name").jsonPrimitive.content)
        }
    }

    @Test
    fun `a text answer reassembles from fragments and reports usage`() = runBlocking {
        withServer(
            listOf(
                FakeOpenAiResponse(
                    stream = true,
                    fragmentBytes = 3,
                    gapMillis = 1,
                    body = sse(
                        listOf(
                            """{"choices":[{"delta":{"content":"Hel"},"finish_reason":null}]}""",
                            """{"choices":[{"delta":{"content":"lo"},"finish_reason":null}]}""",
                            """{"choices":[{"delta":{"finish_reason":"stop"}}],"usage":{"prompt_tokens":7,"completion_tokens":2,"cache_read_input_tokens":3}}""",
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
    fun `two fragmented tool calls assemble their argument json in index order`() = runBlocking {
        withServer(
            listOf(
                FakeOpenAiResponse(
                    stream = true,
                    fragmentBytes = 2,
                    gapMillis = 1,
                    body = sse(
                        listOf(
                            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"find","arguments":"{\"q"}}]},"finish_reason":null}]}""",
                            """{"choices":[{"delta":{"tool_calls":[{"index":1,"id":"call_2","type":"function","function":{"name":"count","arguments":"{\"y"}}]},"finish_reason":null}]}""",
                            """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"uery\":\"nightfall\"}"}}]},"finish_reason":null}]}""",
                            """{"choices":[{"delta":{"tool_calls":[{"index":1,"function":{"arguments":"ear\":2020}"}}]},"finish_reason":null}]}""",
                            """{"choices":[{"delta":{},"finish_reason":"tool_calls"}]}""",
                        ),
                    ),
                ),
            ),
        ) { _, _, llm ->
            assertEquals(
                listOf(
                    LlmEvent.ToolCallReady(
                        ToolCall(id = "call_1", name = "find", arguments = """{"query":"nightfall"}"""),
                    ),
                    LlmEvent.ToolCallReady(
                        ToolCall(id = "call_2", name = "count", arguments = """{"year":2020}"""),
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
                    body = """{"error":{"message":"invalid bearer $secret"}}""",
                ),
            ),
            secret = secret,
        ) { server, _, llm ->
            val failure = assertFailsWith<LlmError.AuthenticationError> {
                llm.stream(request()).toList()
            }
            assertFalse((failure.message ?: "").contains(secret), "the error message must not echo the key")
            assertFalse(failure.toString().contains(secret), "the full error text must not echo the key")
            assertTrue(server.authorization == "Bearer $secret", "the key must have reached the header")
            assertEquals(1, server.handledRequests, "authentication errors are not retried")
        }
    }

    @Test
    fun `a stream chunk that is not json is a malformed response, never a partial success`() = runBlocking {
        withServer(
            listOf(FakeOpenAiResponse(stream = true, body = "data: not-json\n\n")),
        ) { server, _, llm ->
            assertFailsWith<LlmError.MalformedResponseError> {
                llm.stream(request()).toList()
            }
            assertEquals(1, server.handledRequests, "a malformed stream after the first delta is not retried")
        }
    }

    @Test
    fun `a malformed payload is a typed error that leaks neither the body nor the key, and prints nothing`() = runBlocking {
        val secret = "super-secret-key-abc"
        val captured = java.io.ByteArrayOutputStream()
        val original = System.err
        System.setErr(java.io.PrintStream(captured))
        try {
            withServer(
                listOf(
                    // A broken-JSON payload that *carries the key*: the old decode printed it to stderr
                    // and interpolated it into the error message. New code must keep it out of both.
                    FakeOpenAiResponse(stream = true, body = "data: echoed-$secret\n\n"),
                ),
                secret = secret,
            ) { server, _, llm ->
                val failure = assertFailsWith<LlmError.MalformedResponseError> {
                    llm.stream(request()).toList()
                }
                assertFalse((failure.message ?: "").contains(secret), "the message must not echo the key")
                assertFalse(failure.toString().contains(secret), "the full error text must not echo the key")
                assertTrue(server.authorization == "Bearer $secret", "the key must have reached the header")
                assertEquals(1, server.handledRequests, "a malformed stream is not retried")
            }
        } finally {
            System.setErr(original)
        }
        val stderr = captured.toString()
        assertFalse(stderr.contains(secret), "no payload on stderr")
    }

    @Test
    fun `a stream that emitted a delta and then fails is not retried`() = runBlocking {
        withServer(
            listOf(
                FakeOpenAiResponse(
                    stream = true,
                    body =
                        """data: {"choices":[{"delta":{"content":"partial"},"finish_reason":null}]}\n\n""" +
                        "data: not-json\n\n",
                ),
            ),
        ) { server, _, llm ->
            assertFailsWith<LlmError.MalformedResponseError> {
                llm.stream(request()).toList()
            }
            assertEquals(
                1,
                server.handledRequests,
                "a stream that already emitted a delta is never replayed",
            )
        }
    }

    @Test
    fun `a stream that ends before a started tool call is complete is a malformed response`() = runBlocking {
        withServer(
            listOf(
                // A call starts without an id and the stream ends with no finish_reason nor [DONE], so
                // the idle call can never be presented as complete.
                FakeOpenAiResponse(
                    stream = true,
                    body = """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"name":"find","arguments":"x"}}]},"finish_reason":null}]}\n\n""",
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
                // The server advertises far more body than it sends and holds the connection open, so
                // the consumer is mid-read awaiting bytes when the test cancels it. `started` latches as
                // soon as the first fragment is written, so the cancel is deterministic: the request has
                // reached the server and the body is being produced.
                FakeOpenAiResponse(
                    stream = true,
                    body = """data: {"choices":[{"delta":{"content":"partial"},"finish_reason":null}]}\n\n""",
                    declaredLength = 10_000_000,
                    holdMillis = 60_000,
                    onFirstFragment = { started.complete(Unit) },
                ),
            ),
        ) { server, _, llm ->
            val collector = launch { llm.stream(request()).toList() }
            withTimeout(30.seconds) { started.await() } // the request reached the server, deterministically
            collector.cancel()
            // kotlinx.coroutines 1.10 treats `cancel()` as *normal* cancellation, so `join()` returns
            // normally rather than rethrowing (only a *failed* job makes join throw). The contract this
            // test pins is that the cancelled read unwinds without hanging and without a retry.
            collector.join()
            assertTrue(collector.isCancelled, "the cancelled consumer ends cancelled, not completed or failed")
            assertEquals(1, server.handledRequests, "a cancelled stream is not retried")
        }
    }

    @Test
    fun `a rate limit is retried with the injected policy and then the stream succeeds`() = runBlocking {
        withServer(
            listOf(
                FakeOpenAiResponse(statusCode = 429, body = """{"error":{"message":"slow down"}}"""),
                FakeOpenAiResponse(
                    stream = true,
                    body = sse(listOf("""{"choices":[{"delta":{"content":"ok"},"finish_reason":"stop"}]}""")),
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
    fun `an assistant tool-call message encodes tool_calls and its results encode tool_call_id`() = runBlocking {
        withServer(
            listOf(
                FakeOpenAiResponse(
                    stream = true,
                    body = sse(listOf("""{"choices":[{"delta":{"content":"ok"},"finish_reason":"stop"}]}""")),
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
            val calls = assistant.getValue("tool_calls").jsonArray
            assertEquals(2, calls.size)
            val first = calls[0].jsonObject
            assertEquals("call_1", first.getValue("id").jsonPrimitive.content)
            assertEquals("function", first.getValue("type").jsonPrimitive.content)
            assertEquals("find", first.getValue("function").jsonObject.getValue("name").jsonPrimitive.content)
            assertEquals("""{"query":"nightfall"}""", first.getValue("function").jsonObject.getValue("arguments").jsonPrimitive.content)
            val second = calls[1].jsonObject
            assertEquals("call_2", second.getValue("id").jsonPrimitive.content)
            assertEquals("count", second.getValue("function").jsonObject.getValue("name").jsonPrimitive.content)
            assertEquals("""{"year":2020}""", second.getValue("function").jsonObject.getValue("arguments").jsonPrimitive.content)
            assertEquals("call_1", messages[2].jsonObject.getValue("tool_call_id").jsonPrimitive.content)
            assertEquals("call_2", messages[3].jsonObject.getValue("tool_call_id").jsonPrimitive.content)
        }
    }

    @Test
    fun `a plain text request body is unchanged`() = runBlocking {
        withServer(
            listOf(
                FakeOpenAiResponse(
                    stream = true,
                    body = sse(listOf("""{"choices":[{"delta":{"content":"ok"},"finish_reason":"stop"}]}""")),
                ),
            ),
        ) { server, _, llm ->
            llm.stream(request()).toList()
            val message = LlmJson.parseToJsonElement(server.requestBody!!).jsonObject.getValue("messages").jsonArray[0].jsonObject
            assertEquals("user", message.getValue("role").jsonPrimitive.content)
            assertEquals("q", message.getValue("content").jsonPrimitive.content)
            assertFalse(message.containsKey("tool_calls"))
            assertFalse(message.containsKey("tool_call_id"))
        }
    }

    // ---- helpers ----

    private fun request(): LlmRequest = LlmRequest(messages = listOf(LlmMessage("user", "q")))

    /** Runs [block] against a scripted fake endpoint, closing the client and the server afterwards. */
    private suspend fun withServer(
        script: List<FakeOpenAiResponse>,
        secret: String? = null,
        block: suspend (FakeOpenAiServer, HttpClient, OpenAiCompatibleClient) -> Unit,
    ) {
        val server = FakeOpenAiServer(script)
        val client = HttpClient(CIO)
        try {
            val llm = OpenAiCompatibleClient(
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
        provider = LlmProvider.OPENAI_COMPATIBLE,
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

    private fun sse(dataLines: List<String>): String =
        dataLines.map { "data: $it\n\n" }.joinToString("") + "data: [DONE]\n\n"
}
