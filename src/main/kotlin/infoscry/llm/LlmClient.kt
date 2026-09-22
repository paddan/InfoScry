package infoscry.llm

import kotlinx.coroutines.flow.Flow

/**
 * Provider-neutral request and stream types for LLM calls.
 *
 * The two adapters translate this contract to the OpenAI chat-completions protocol and the Anthropic
 * Messages protocol respectively, and emit the same [LlmEvent] stream so Ask and Investigate never
 * branch on the provider.
 */

/** A chat message in provider-neutral form. The role is a provider string ("system", "user", ...). */
data class LlmMessage(val role: String, val content: String) {
    init {
        require(role.isNotBlank()) { "LlmMessage.role must not be blank" }
    }
}

/** A function the model may call. [parametersJson] is the JSON schema of its arguments. */
data class ToolDefinition(val name: String, val description: String, val parametersJson: String? = null) {
    init {
        require(name.isNotBlank()) { "ToolDefinition.name must not be blank" }
    }
}

/** A call the model requested: provider-neutral [id], [name] and [arguments] JSON. */
data class ToolCall(val id: String, val name: String, val arguments: String) {
    init {
        require(id.isNotBlank()) { "ToolCall.id must not be blank" }
        require(name.isNotBlank()) { "ToolCall.name must not be blank" }
    }
}

/** Provider-reported token usage for one call. Cache reads count separately for cost accounting. */
data class TokenUsage(val inputTokens: Long, val outputTokens: Long, val cacheReadTokens: Long = 0) {
    init {
        require(inputTokens >= 0) { "TokenUsage.inputTokens must not be negative" }
        require(outputTokens >= 0) { "TokenUsage.outputTokens must not be negative" }
        require(cacheReadTokens >= 0) { "TokenUsage.cacheReadTokens must not be negative" }
    }
}

/** The request the adapters send; every field is provider-neutral. */
data class LlmRequest(
    val messages: List<LlmMessage>,
    val tools: List<ToolDefinition> = emptyList(),
    val requiredToolName: String? = null,
    val maxOutputTokens: Int = 4096,
) {
    init {
        require(messages.isNotEmpty()) { "LlmRequest.messages must not be empty" }
        require(maxOutputTokens > 0) { "LlmRequest.maxOutputTokens must be positive, was $maxOutputTokens" }
        requiredToolName?.let { required ->
            require(tools.any { it.name == required }) {
                "LlmRequest.requiredToolName must name one of the supplied tools"
            }
        }
    }
}

/** One step of a streaming provider call, in provider-neutral terms. */
sealed interface LlmEvent {
    /** A piece of the model's answer text, in order. */
    data class TextDelta(val text: String) : LlmEvent

    /** One completed tool call the model requested; arguments are JSON text, already accumulated. */
    data class ToolCallReady(val call: ToolCall) : LlmEvent

    /** Provider-reported usage, available once the call reports it. */
    data class Usage(val usage: TokenUsage) : LlmEvent

    /** The stream ended normally. Consumers can rely on this being the last event. */
    data object Completed : LlmEvent
}

/**
 * One streaming provider call.
 *
 * Implementations yield [LlmEvent] values in order and end with [LlmEvent.Completed]. The flow is cold
 * until a consumer advances it, so a failed request never runs twice and a cancelled consumer stops the
 * HTTP work.
 */
interface LlmStreamingClient {
    fun stream(request: LlmRequest): Flow<LlmEvent>
}

/** Optional non-streaming completion boundary used for one-shot citation correction. */
interface LlmCompletionClient {
    suspend fun complete(request: LlmRequest): String
}

/**
 * A provider call failed.
 *
 * Subclasses carry **typed fields only** — never the request body, a response body, an authorization
 * value, a question or a document excerpt — because errors cross the logging and API boundary, and the
 * project's redaction is by named field only (Review Focus 5). [statusCode] is the provider's HTTP
 * status when one exists; it is not a secret.
 */
sealed class LlmError(
    message: String,
    cause: Throwable? = null,
    val statusCode: Int? = null,
) : Exception(message, cause) {

    /** The provider rejected the credential or the payload (HTTP 401/403/422). */
    final class AuthenticationError(
        message: String,
        cause: Throwable? = null,
        statusCode: Int? = null,
    ) : LlmError(message, cause, statusCode)

    /** The provider asked us to slow down (HTTP 429). Retryable by [RetryPolicy]. */
    final class RateLimitedError(
        message: String,
        cause: Throwable? = null,
        statusCode: Int? = null,
    ) : LlmError(message, cause, statusCode)

    /** The provider or the network failed (HTTP 5xx, connect or read timeout). Possibly retryable. */
    final class ProviderUnavailableError(
        message: String,
        cause: Throwable? = null,
        statusCode: Int? = null,
    ) : LlmError(message, cause, statusCode)

    /** The response did not parse as the provider's protocol (bad JSON, broken SSE). Never retried. */
    final class MalformedResponseError(
        message: String,
        cause: Throwable? = null,
    ) : LlmError(message, cause)

    /** The provider refused the requested tools, or the request exceeds its window. Not retried. */
    final class UnsupportedToolsError(
        message: String,
        cause: Throwable? = null,
    ) : LlmError(message, cause)
}
