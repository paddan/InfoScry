package infoscry.llm

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * A client for Anthropic's Messages API.
 *
 * One implementation covers every Anthropic endpoint that speaks `/v1/messages`: it sends the key in
 * the `x-api-key` header (their auth model), the required `anthropic-version` header, and the streamed
 * tool protocol, and reassembles the provider-neutral [LlmEvent] stream so Ask and Investigate cannot
 * tell it from the OpenAI adapter.
 *
 * ## Messages streaming protocol (Anthropic)
 *
 * The stream is SSE with `event:`/`data:` pairs. The JSON payload always carries a `type`; this adapter
 * dispatches on that type:
 *
 * - `message_start` — may carry the first `usage` block (emit `Usage`).
 * - `content_block_start` — a text block begins (its `text` may already be present), or a `tool_use`
 *   block begins with its `id`, `name` and an `input` object that may already hold a partial
 *   argument document.
 * - `content_block_delta` — `text_delta` appends answer text; `input_json_delta` appends to the
 *   argument JSON of the tool-use block at `index`.
 * - `content_block_stop` — the block at `index` is complete; a tool-use block is now a complete
 *   [LlmEvent.ToolCallReady].
 * - `message_delta` — may carry the final `usage` (including cache reads).
 * - `message_stop` — the stream's end. There is no `[DONE]` sentinel in this protocol.
 * - `ping` and unknown types — ignored, so a provider that adds a type never breaks the loop.
 *
 * ## Streaming and failure discipline
 *
 * Identical to the OpenAI adapter: retry on 429/5xx **before the first delta** via [RetryPolicy]; a
 * stream that has begun emitting is never replayed; a stream that ends while a tool-use block is still
 * open is a [LlmError.MalformedResponseError]. A [LlmError] carries typed fields only — no request
 * body, response body, prompt or key value ever reaches an error message or a log line.
 */
class AnthropicClient(
    private val profile: LlmProfile,
    private val lookup: (String) -> String?,
    private val client: HttpClient = HttpClient(CIO),
    private val retryPolicy: RetryPolicy = RetryPolicy(),
) : LlmStreamingClient {

    init {
        require(profile.endpoint.isNotBlank()) {
            "an Anthropic profile must configure an endpoint, was '${profile.endpoint}'"
        }
        require(profile.provider == LlmProvider.ANTHROPIC) {
            "AnthropicClient requires an ANTHROPIC profile, was ${profile.provider}"
        }
    }

    private val endpoint: String = profile.endpoint.trimEnd('/')
    private val url: String get() = "$endpoint/v1/messages"

    private class ToolUseAccumulator(
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

    /** Performs one POST. Connection-level failures become [LlmError]; cancellation is rethrown. */
    private suspend fun awaitRequest(request: LlmRequest): HttpResponse {
        return try {
            client.post(url) {
                profile.apiKeyEnvironmentVariable?.let { variable ->
                    lookup(variable)?.let { key -> header("x-api-key", key) }
                }
                header("anthropic-version", "2023-06-01")
                contentType(ContentType.Application.Json)
                setBody(LlmJson.encodeToString(messagesRequest(request)))
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
     * Reads the decoded response body line by line through an [SseScanner], dispatching each event by
     * its JSON `type`. The body is closed by Ktor when the channel is exhausted; a cancelled consumer
     * unwinds the read inside [LlmSseLineReader.readLine].
     */
    private suspend fun FlowCollector<LlmEvent>.consume(response: HttpResponse) {
        val scanner = SseScanner()
        val toolUses = ArrayList<ToolUseAccumulator>()
        val channel = response.bodyAsChannel()
        var streaming = true
        while (streaming) {
            val line = LlmSseLineReader.readLine(channel) ?: break
            for (event in scanner.feed(line + "\n")) {
                if (!applyEvent(event, toolUses)) {
                    streaming = false
                    break
                }
            }
        }
        if (streaming) {
            for (event in scanner.finish()) {
                applyEvent(event, toolUses)
            }
            // A stream that ended without message_stop but with an open tool-use block is truncated;
            // a completed block was already emitted at content_block_stop, so nothing is pending here.
            flushOnTruncatedStream(toolUses)
        }
    }

    /**
     * Returns false once `message_stop` is seen; throws on an unparseable event. On `content_block_stop`
     * a complete tool-use call is emitted immediately, so a later truncation cannot present a partial
     * call as complete.
     */
    private suspend fun FlowCollector<LlmEvent>.applyEvent(
        event: SseEvent,
        toolUses: MutableList<ToolUseAccumulator>,
    ): Boolean {
        val payload = event.data.trim()
        if (payload.isEmpty()) return true // a keep-alive or a bare ping
        val message = decode(payload)
        if (message.type == "message_stop") {
            return false
        }
        if (message.type == "message_start") {
            message.usage?.let { emitUsage(it) }
        } else if (message.type == "content_block_start") {
            val index = message.index
            if (index == null) {
                throw malformed("content_block_start carried no block index")
            }
            val block = message.content_block
            if (block == null) {
                throw malformed("content_block_start carried no content block")
            }
            if (block.type == "text") {
                block.text?.let { emit(LlmEvent.TextDelta(it)) }
            } else if (block.type == "tool_use") {
                var accumulated = toolUses.firstOrNull { it.index == index }
                if (accumulated == null) {
                    accumulated = ToolUseAccumulator(index = index)
                    toolUses.add(accumulated)
                }
                block.id?.let { accumulated.id = it }
                block.name?.let { accumulated.name = it }
                block.input?.let { input ->
                    val initial = inputJson(input)
                    // Anthropic's `input` at content_block_start is an *initial* argument object that the
                    // input_json_delta fragments continue. An empty object is a placeholder and must not
                    // be prepended — `{}{...}` is invalid JSON and would break every assembled tool call.
                    if (initial.isNotEmpty() && initial != "{}") {
                        accumulated.arguments.append(initial)
                    }
                }
            }
        } else if (message.type == "content_block_delta") {
            val index = message.index
            val delta = message.delta
            if (index == null || delta == null) {
                throw malformed("content_block_delta carried no index or delta")
            }
            if (delta.type == "text_delta") {
                delta.text?.let { emit(LlmEvent.TextDelta(it)) }
            } else if (delta.type == "input_json_delta") {
                val accumulated = toolUses.firstOrNull { it.index == index }
                if (accumulated == null) {
                    throw malformed("input_json_delta arrived for an unknown block index $index")
                }
                delta.partial_json?.let { accumulated.arguments.append(it) }
            }
        } else if (message.type == "content_block_stop") {
            val index = message.index
            if (index == null) {
                throw malformed("content_block_stop carried no block index")
            }
            val completed = toolUses.firstOrNull { it.index == index }
            if (completed != null) {
                flushToolUse(completed)
                toolUses.remove(completed)
            }
        } else if (message.type == "message_delta") {
            message.usage?.let { emitUsage(it) }
        } else {
            // ping and unknown types are ignored.
        }
        return true
    }

    /** A stream that ends without `message_stop` is truncated; an open tool-use block cannot be closed. */
    private suspend fun FlowCollector<LlmEvent>.flushOnTruncatedStream(toolUses: List<ToolUseAccumulator>) {
        if (toolUses.isNotEmpty()) {
            throw LlmError.MalformedResponseError(
                "the provider ended the stream before completing a tool call",
            )
        }
    }

    /** Emits one completed tool call; an id or name that never arrived is a truncated stream. */
    private suspend fun FlowCollector<LlmEvent>.flushToolUse(call: ToolUseAccumulator) {
        val id = call.id
        val name = call.name
        if (id == null || name == null) {
            throw LlmError.MalformedResponseError(
                "the provider ended the block before completing a tool call",
            )
        }
        emit(
            LlmEvent.ToolCallReady(
                ToolCall(id = id, name = name, arguments = call.arguments.toString()),
            ),
        )
    }

    private suspend fun FlowCollector<LlmEvent>.emitUsage(usage: AnthropicUsage) {
        emit(
            LlmEvent.Usage(
                TokenUsage(
                    inputTokens = nonNegative(usage.input_tokens),
                    outputTokens = nonNegative(usage.output_tokens),
                    cacheReadTokens = nonNegative(usage.cache_read_input_tokens),
                ),
            ),
        )
    }

    private fun malformed(reason: String): LlmError.MalformedResponseError =
        LlmError.MalformedResponseError(reason)

    // ---- Decoding ----

    /**
     * Decodes one SSE payload. Unlike the OpenAI adapter's first version, the payload is **never**
     * interpolated into the error message or printed: a response body in an error crosses the
     * logging/API boundary unredacted, which Review Focus 5 forbids.
     */
    private fun decode(payload: String): AnthropicEvent = try {
        LlmJson.decodeFromString<AnthropicEvent>(payload)
    } catch (failure: Throwable) {
        throw LlmError.MalformedResponseError(
            "the provider sent an event that is not its protocol",
            cause = failure,
        )
    }

    /** Serialises a tool-use `input` object (possibly partial) as the opening of the arguments JSON. */
    private fun inputJson(input: JsonElement): String = try {
        input.toString()
    } catch (failure: Throwable) {
        throw LlmError.MalformedResponseError(
            "a tool-use input could not be represented as JSON",
            cause = failure,
        )
    }

    // ---- Request encoding ----

    private fun messagesRequest(request: LlmRequest): AnthropicMessagesRequest = AnthropicMessagesRequest(
        model = profile.model,
        max_tokens = request.maxOutputTokens,
        messages = request.messages.map { AnthropicMessage(role = it.role, content = it.content) },
        tools = request.tools.takeIf { it.isNotEmpty() }?.map { tool ->
            AnthropicTool(
                name = tool.name,
                description = tool.description,
                input_schema = tool.parametersJson?.let { raw -> jsonParameters(raw) },
            )
        },
    )

    private fun jsonParameters(raw: String): JsonElement = try {
        LlmJson.parseToJsonElement(raw)
    } catch (failure: Throwable) {
        throw LlmError.MalformedResponseError(
            "a tool definition carried invalid parameters JSON",
            cause = failure,
        )
    }

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

// ---- Anthropic Messages wire types ----

@Serializable
data class AnthropicMessagesRequest(
    val model: String,
    val max_tokens: Int,
    val stream: Boolean = true,
    val messages: List<AnthropicMessage>,
    val tools: List<AnthropicTool>? = null,
)

@Serializable
data class AnthropicMessage(val role: String, val content: String)

@Serializable
data class AnthropicTool(
    val name: String,
    val description: String = "",
    /** The parameters schema as a JSON object; [LlmRequest] tools carry it as its own document. */
    val input_schema: JsonElement? = null,
)

/** One SSE `data:` payload in a Messages stream. */
@Serializable
data class AnthropicEvent(
    val type: String,
    val index: Int? = null,
    val content_block: AnthropicContentBlock? = null,
    val delta: AnthropicDelta? = null,
    val usage: AnthropicUsage? = null,
)

@Serializable
data class AnthropicContentBlock(
    val type: String? = null,
    val id: String? = null,
    val name: String? = null,
    val text: String? = null,
    val input: JsonElement? = null,
)

@Serializable
data class AnthropicDelta(
    val type: String? = null,
    val text: String? = null,
    val partial_json: String? = null,
)

@Serializable
data class AnthropicUsage(
    val input_tokens: Long? = null,
    val output_tokens: Long? = null,
    val cache_read_input_tokens: Long? = null,
)