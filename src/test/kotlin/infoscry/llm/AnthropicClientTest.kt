package infoscry.llm

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
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