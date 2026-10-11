package infoscry.llm

import infoscry.investigate.NativeReasoning
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

class NativeReasoningTransportTest {
    private fun request(reasoning: NativeReasoning = NativeReasoning.None) = LlmRequest(listOf(LlmMessage("user", "question")), reasoning = reasoning)
    private fun sse(vararg values: String) = values.joinToString("") { "data: $it\n\n" }

    @Test fun `native options are explicit and None leaves the existing wire shape unchanged`() {
        val none = LlmJson.encodeToString(AnthropicMessagesRequest.serializer(), buildAnthropicMessagesRequest("model", request()))
        assertFalse(none.contains("thinking"))
        val anthropic = buildAnthropicMessagesRequest("model", request(NativeReasoning.AnthropicThinkingBudget(2048)))
        assertEquals(2048, anthropic.thinking?.budget_tokens)
        assertEquals("enabled", anthropic.thinking?.type)
        val openai = buildChatCompletionRequest("model", request(NativeReasoning.OpenAiReasoningEffort("high")))
        assertEquals("high", openai.reasoning_effort)
        assertNull(buildChatCompletionRequest("model", request()).reasoning_effort)
    }

    @Test fun `signed and redacted blocks survive fragmented streaming and exact tool replay`() = runBlocking {
        val body = sse(
            """{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"private thought"}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"sig-"}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"value"}}""",
            """{"type":"content_block_stop","index":0}""",
            """{"type":"content_block_start","index":1,"content_block":{"type":"redacted_thinking","data":"opaque"}}""",
            """{"type":"content_block_stop","index":1}""",
            """{"type":"content_block_start","index":2,"content_block":{"type":"text","text":"visible"}}""",
            """{"type":"content_block_stop","index":2}""",
            """{"type":"content_block_start","index":3,"content_block":{"type":"tool_use","id":"call-1","name":"search","input":{}}}""",
            """{"type":"content_block_delta","index":3,"delta":{"type":"input_json_delta","partial_json":"{\"q\":\"archive\"}"}}""",
            """{"type":"content_block_stop","index":3}""",
            """{"type":"message_stop"}""",
        )
        FakeOpenAiServer(listOf(FakeOpenAiResponse(stream = true, body = body, fragmentBytes = 3), FakeOpenAiResponse(stream = true, body = body), FakeOpenAiResponse(stream = true, body = body))).use { server ->
            HttpClient(CIO).use { http ->
                val profile = LlmProfile("id", "native", LlmProvider.ANTHROPIC, "model", 20000, 4096, 0.0, 0.0, 0.0, true, endpoint = server.url)
                val client = AnthropicClient(profile, { null }, http)
                val events = client.stream(request(NativeReasoning.AnthropicThinkingBudget(2048))).toList()
                val envelope = events.filterIsInstance<LlmEvent.AssistantContentReady>().single().content
                assertEquals(listOf("visible"), events.filterIsInstance<LlmEvent.TextDelta>().map { it.text })
                assertEquals(LlmEvent.Completed, events.last())
                assertFalse(envelope.toString().contains("private thought"))
                val expected = listOf(
                    """{"type":"thinking","thinking":"private thought","signature":"sig-value"}""",
                    """{"type":"redacted_thinking","data":"opaque"}""",
                    """{"type":"text","text":"visible"}""",
                    """{"type":"tool_use","id":"call-1","name":"search","input":{"q":"archive"}}""",
                ).map { LlmJson.parseToJsonElement(it).jsonObject }
                assertEquals(expected, envelope.blocks)
                val tool = events.filterIsInstance<LlmEvent.ToolCallReady>().single().call
                val messages = listOf(LlmMessage("user", "question"), LlmMessage("assistant", "visible", toolCalls = listOf(tool), assistantContent = envelope), LlmMessage("tool", "result", toolCallId = tool.id))
                val replay = LlmRequest(messages, reasoning = NativeReasoning.AnthropicThinkingBudget(2048))
                val second = client.stream(replay).toList().filterIsInstance<LlmEvent.AssistantContentReady>().single().content
                client.stream(replay.copy(messages = messages + listOf(
                    LlmMessage("assistant", "visible", toolCalls = listOf(tool), assistantContent = second),
                    LlmMessage("tool", "second result", toolCallId = tool.id),
                ))).toList()
                val sent = LlmJson.parseToJsonElement(server.requestBodies.last()).jsonObject
                assertEquals(JsonArray(expected), sent.getValue("messages").jsonArray[1].jsonObject.getValue("content"))
                assertEquals(JsonArray(expected), sent.getValue("messages").jsonArray[3].jsonObject.getValue("content"))
                assertFalse(LlmJson.encodeToString(ChatCompletionRequest.serializer(), buildChatCompletionRequest("model", replay)).contains("private thought"))
            }
        }
    }

    @Test fun `malformed opaque blocks fail with sanitized protocol errors`() {
        for (payload in listOf(
            """{"type":{"private":"secret"}}""",
            """{"type":"content_block_start","index":0,"content_block":{"type":"thinking","signature":{"private":"secret"}}}""",
        )) {
            val accumulator = AnthropicContentAccumulator(true)
            val failure = assertFailsWith<LlmError.MalformedResponseError> {
                accumulator.accept(payload)
                accumulator.accept("""{"type":"content_block_stop","index":0}""")
                accumulator.accept("""{"type":"message_stop"}""")
                accumulator.finish()
            }
            assertFalse(failure.toString().contains("secret"))
        }
    }

    @Test fun `incomplete thinking streams fail without exposing their content`() = runBlocking {
        val body = sse("""{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":"secret"}}""")
        FakeOpenAiServer(listOf(FakeOpenAiResponse(stream = true, body = body))).use { server ->
            HttpClient(CIO).use { http ->
                val profile = LlmProfile("id", "native", LlmProvider.ANTHROPIC, "model", 20000, 4096, 0.0, 0.0, 0.0, true, endpoint = server.url)
                val failure = assertFailsWith<LlmError.MalformedResponseError> {
                    AnthropicClient(profile, { null }, http).stream(request(NativeReasoning.AnthropicThinkingBudget(2048))).toList()
                }
                assertFalse(failure.toString().contains("secret"))
            }
        }
    }
}
