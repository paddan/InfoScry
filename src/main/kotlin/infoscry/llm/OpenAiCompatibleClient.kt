package infoscry.llm

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * A client for any endpoint that speaks the OpenAI chat-completions protocol.
 *
 * One implementation covers OpenAI, OpenRouter, Ollama, LM Studio and most local gateways: they all
 * accept a POST /chat/completions with `stream: true` and standard function tools. The endpoint, model
 * and key-variable come from the profile; the key value is read through the injected [lookup] at call
 * time and appears only in the `Authorization` header.
 *
 * ## Streaming and failure discipline
 *
 * - Retries happen **before the first delta, on the HTTP status only** (429 and 5xx). Once a 200
 *   response starts emitting text or tool calls, nothing is retried: replaying would duplicate content
 *   a consumer has already seen, so a failure after streaming began surfaces as a typed [LlmError] on
 *   that same attempt.
 * - Tool calls arrive fragmented across many SSE deltas and are accumulated **by index**; a complete
 *   call is emitted when the provider signals `finish_reason: "tool_calls"`, or when the stream ends.
 *   A stream that ends before a started call was completed is a [LlmError.MalformedResponseError]:
 *   presenting a truncated call as complete would be exactly the kind of broken citation this product
 *   refuses to produce.
 * - A [LlmError] carries typed fields only. No request body, response body, prompt, or key value ever
 *   reaches an error message or a log line: redaction in this project is by named field, so an
 *   interpolated secret would be invisible to the redactor.
 */
class OpenAiCompatibleClient(
    private val profile: LlmProfile,
    private val lookup: (String) -> String?,
    private val client: HttpClient = HttpClient(CIO),
    private val retryPolicy: RetryPolicy = RetryPolicy(),
) : LlmStreamingClient, LlmCompletionClient {

    init {
        require(profile.endpoint.isNotBlank()) {
            "an OpenAI-compatible profile must configure an endpoint, was '${profile.endpoint}'"
        }
        require(profile.provider == LlmProvider.OPENAI_COMPATIBLE) {
            "OpenAiCompatibleClient requires an OPENAI_COMPATIBLE profile, was ${profile.provider}"
        }
    }

    private val endpoint: String = profile.endpoint.trimEnd('/')
    private val url: String get() = "$endpoint/chat/completions"

    private class ToolCallAccumulator(
        val index: Int,
        var id: String? = null,
        var name: String? = null,
        val arguments: StringBuilder = StringBuilder(),
    )

    override fun stream(request: LlmRequest): Flow<LlmEvent> = flow {
        var attempt = 1
        while (true) {
            val response = awaitRequest(request)
            if (response.status.isSuccess()) {
                consume(response)
                emit(LlmEvent.Completed)
                return@flow
            }
            if (retryPolicy.isRetryableStatus(response.status.value) && attempt <= retryPolicy.maxRetries) {
                retryPolicy.waitBeforeRetry(attempt)
                attempt += 1
                continue
            }
            throw mapFailure(response.status.value)
        }
    }

    override suspend fun complete(request: LlmRequest): LlmCompletion {
        val response = client.post(url) {
            profile.apiKeyEnvironmentVariable?.let { variable -> lookup(variable)?.let { key -> header(HttpHeaders.Authorization, "Bearer $key") } }
            contentType(ContentType.Application.Json)
            setBody(LlmJson.encodeToString(buildChatCompletionRequest(profile.model, request, stream = false)))
        }
        if (!response.status.isSuccess()) throw mapFailure(response.status.value)
        return try { val result = LlmJson.decodeFromString<ChatCompletionResponse>(response.bodyAsText()); LlmCompletion(result.choices.firstOrNull()?.message?.content.orEmpty(), result.usage?.let { TokenUsage(it.prompt_tokens ?: 0, it.completion_tokens ?: 0, it.cache_read_input_tokens ?: 0) } ?: TokenUsage(0, 0)) }
        catch (failure: Throwable) { throw LlmError.MalformedResponseError("the provider returned an invalid correction response", failure) }
    }

    /** Performs one POST. Connection-level failures become [LlmError]; cancellation is rethrown. */
    private suspend fun awaitRequest(request: LlmRequest): HttpResponse {
        return try {
            client.post(url) {
                profile.apiKeyEnvironmentVariable?.let { variable ->
                    lookup(variable)?.let { key -> header(HttpHeaders.Authorization, "Bearer $key") }
                }
                contentType(ContentType.Application.Json)
                setBody(LlmJson.encodeToString(buildChatCompletionRequest(profile.model, request)))
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            throw LlmError.ProviderUnavailableError(
                "the provider could not be reached: ${profile.name}",
                cause = failure,
            )
        }
    }

    // ---- Response consumption (a FlowCollector extension so `emit` binds to the stream) ----

    /**
     * Reads the decoded response body line by line, feeding an [SseScanner] so a single event split
     * across many chunks reassembles, and emitting deltas, completed tool calls and usage as they
     * arrive. The body is closed in a `finally` so a cancelled consumer is never leaked.
     */
    private suspend fun FlowCollector<LlmEvent>.consume(response: HttpResponse) {
        val scanner = SseScanner()
        val toolCalls = ArrayList<ToolCallAccumulator>()
        val channel = response.bodyAsChannel()
        var streaming = true
        while (streaming) {
            val line = LlmSseLineReader.readLine(channel) ?: break
            for (event in scanner.feed(line + "\n")) {
                if (!applyEvent(event, toolCalls)) {
                    streaming = false
                    break
                }
            }
        }
        if (streaming) {
            for (event in scanner.finish()) {
                applyEvent(event, toolCalls)
            }
            flushToolCalls(toolCalls)
        }
    }

    /** Returns false once the provider's end marker ([DONE]) is seen; throws on an unparseable event. */
    private suspend fun FlowCollector<LlmEvent>.applyEvent(
        event: SseEvent,
        toolCalls: MutableList<ToolCallAccumulator>,
    ): Boolean {
        val payload = event.data.trim()
        if (payload == "[DONE]") return false
        val chunk = decode(payload)
        for (choice in chunk.choices) {
            choice.delta.content?.let { emit(LlmEvent.TextDelta(it)) }
            for (call in choice.delta.tool_calls) {
                var accumulated = toolCalls.firstOrNull { it.index == call.index }
                if (accumulated == null) {
                    accumulated = ToolCallAccumulator(index = call.index)
                    toolCalls.add(accumulated)
                }
                call.id?.let { accumulated.id = it }
                call.function.name?.let { accumulated.name = it }
                call.function.arguments?.let { accumulated.arguments.append(it) }
            }
            if (choice.finish_reason == "tool_calls") {
                flushToolCalls(toolCalls)
                toolCalls.clear()
            }
        }
        chunk.usage?.let { emit(LlmEvent.Usage(usageOf(it))) }
        return true
    }

    /** Emits each completed tool call; a call whose id or name never arrived is a truncated stream. */
    private suspend fun FlowCollector<LlmEvent>.flushToolCalls(calls: List<ToolCallAccumulator>) {
        for (call in calls) {
            val id = call.id
            val name = call.name
            if (id == null || name == null) {
                throw LlmError.MalformedResponseError(
                    "the provider ended the stream before completing a tool call",
                )
            }
            emit(
                LlmEvent.ToolCallReady(
                    ToolCall(id = id, name = name, arguments = call.arguments.toString()),
                ),
            )
        }
    }

    // ---- Decoding ----

    private fun decode(payload: String): ChatChunk = try {
        LlmJson.decodeFromString<ChatChunk>(payload)
    } catch (failure: Throwable) {
        // The payload is never printed or interpolated into the error: a response body crossing the
        // logging/API boundary unredacted violates Review Focus 5 (a body can echo a key, a prompt or
        // document text). The typed error carries a fixed reason and the cause only.
        throw LlmError.MalformedResponseError(
            "the provider sent an event that is not its protocol",
            cause = failure,
        )
    }

    private fun usageOf(usage: ChatUsage): TokenUsage = TokenUsage(
        inputTokens = nonNegative(usage.prompt_tokens),
        outputTokens = nonNegative(usage.completion_tokens),
        cacheReadTokens = nonNegative(usage.cache_read_input_tokens),
    )

    private fun mapFailure(statusCode: Int): LlmError {
        if (statusCode == 400) {
            return LlmError.UnsupportedToolsError(
                "the provider refused the request (HTTP 400): the model, the tools or a requested " +
                    "option is not supported on this endpoint",
            )
        }
        if (statusCode == 429) {
            return LlmError.RateLimitedError(
                "the provider asked us to slow down (HTTP 429)",
                statusCode = statusCode,
            )
        }
        if (statusCode == 401 || statusCode == 403 || statusCode == 422) {
            return LlmError.AuthenticationError(
                "the provider rejected the credential or the request (HTTP $statusCode)",
                statusCode = statusCode,
            )
        }
        return LlmError.ProviderUnavailableError(
            if (statusCode in 500..599) "the provider failed (HTTP $statusCode)"
            else "the provider responded unexpectedly (HTTP $statusCode)",
            statusCode = statusCode,
        )
    }

    private fun nonNegative(value: Long?): Long = (value ?: 0L).let { if (it < 0) 0L else it }
}

// ---- OpenAI chat-completions wire types (decode-only for chunks; encode-only for the request) ----

@Serializable
data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val stream: Boolean = true,
    val max_tokens: Int,
    val tools: List<ChatTool>? = null,
    val tool_choice: OpenAiToolChoice? = null,
    val stream_options: StreamOptions = StreamOptions(),
)

@Serializable
data class OpenAiToolChoice(
    val type: String = "function",
    val function: OpenAiToolChoiceFunction,
)

@Serializable
data class OpenAiToolChoiceFunction(val name: String)

/**
 * Asks the provider to include usage in the final chunk so cost accounting can use real numbers.
 * Compatible endpoints that do not know the option simply ignore the member.
 */
@Serializable
data class StreamOptions(val include_usage: Boolean = true)

@Serializable
data class ChatMessage(
    val role: String,
    val content: String,
    /** Present only on an assistant message that calls tools; null is omitted from the wire. */
    val tool_calls: List<ChatToolCall>? = null,
    /** Present only on a tool result message; null is omitted from the wire. */
    val tool_call_id: String? = null,
)

/** One completed tool call on a request message; [ChatMessage.tool_calls] entries. */
@Serializable
data class ChatToolCall(
    val id: String,
    val type: String = "function",
    val function: ChatToolFunction,
)

/** The request-shape function of a tool call: unlike [ChatFunctionDelta], name and arguments are complete. */
@Serializable
data class ChatToolFunction(
    val name: String,
    val arguments: String,
)

@Serializable
data class ChatTool(val type: String = "function", val function: ChatFunction)

@Serializable
data class ChatFunction(
    val name: String,
    val description: String = "",
    /** The parameters schema as a JSON object; [LlmRequest] tools carry it as its own document. */
    val parameters: JsonElement? = null,
)

/** One SSE `data:` payload in a chat-completions stream. */
@Serializable
data class ChatChunk(
    val choices: List<ChatChoice> = emptyList(),
    val usage: ChatUsage? = null,
)

@Serializable data class ChatCompletionResponse(val choices: List<ChatCompletionChoice> = emptyList(), val usage: ChatUsage? = null)
@Serializable data class ChatCompletionChoice(val message: ChatMessage = ChatMessage("assistant", ""))

@Serializable
data class ChatChoice(
    val delta: ChatDelta = ChatDelta(),
    val finish_reason: String? = null,
)

@Serializable
data class ChatDelta(
    val content: String? = null,
    val tool_calls: List<ChatToolCallDelta> = emptyList(),
)

@Serializable
data class ChatToolCallDelta(
    val index: Int = 0,
    val id: String? = null,
    val type: String? = null,
    val function: ChatFunctionDelta = ChatFunctionDelta(),
)

@Serializable
data class ChatFunctionDelta(
    val name: String? = null,
    val arguments: String? = null,
)

@Serializable
data class ChatUsage(
    val prompt_tokens: Long? = null,
    val completion_tokens: Long? = null,
    val cache_read_input_tokens: Long? = null,
)

/**
 * The one place a request becomes an OpenAI chat-completions envelope. The adapter sends exactly what
 * [RequestBudget.measure] measures, so tool encoding exists in one copy only.
 */
internal fun buildChatCompletionRequest(
    model: String,
    request: LlmRequest,
    stream: Boolean = true,
): ChatCompletionRequest = ChatCompletionRequest(
    model = model,
    messages = request.messages.map { message ->
        ChatMessage(
            role = message.role,
            content = message.content,
            tool_calls = message.toolCalls.takeIf { it.isNotEmpty() }?.map { call ->
                ChatToolCall(
                    id = call.id,
                    function = ChatToolFunction(name = call.name, arguments = call.arguments),
                )
            },
            tool_call_id = message.toolCallId,
        )
    },
    max_tokens = request.maxOutputTokens,
    tools = request.tools.takeIf { it.isNotEmpty() }?.map { tool ->
        ChatTool(
            function = ChatFunction(
                name = tool.name,
                description = tool.description,
                parameters = tool.parametersJson?.let { raw -> toolParametersJson(raw) },
            ),
        )
    },
    tool_choice = request.requiredToolName?.let { required ->
        OpenAiToolChoice(function = OpenAiToolChoiceFunction(name = required))
    },
    stream = stream,
)
