package infoscry.llm

import java.nio.charset.StandardCharsets
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Raised before a provider is touched when the complete request cannot fit. */
class ContextBudgetExceeded(message: String = "the request exceeds the model context window") :
    IllegalStateException(message)

/** A deliberately conservative measurement when no provider tokenizer is available. */
@Serializable
data class RequestMeasurement(
    val serializedBytes: Int,
    val reservedTokens: Int,
    val contextWindow: Int,
    val estimated: Boolean = true,
) {
    val total: Int get() = serializedBytes + reservedTokens
    val fits: Boolean get() = total <= contextWindow
}

/**
 * Measures the provider-shaped request, not just message text. UTF-8 bytes are used as a conservative
 * estimate: a byte cannot under-count a tokenized request and, importantly, this never reuses E5's tokenizer.
 */
class RequestBudget(
    private val contextWindow: Int,
    private val safetyMargin: Int = 1_024,
    private val tokenizer: ((String) -> Int)? = null,
) {
    init {
        require(contextWindow > 0)
        require(safetyMargin >= 0)
    }

    fun measure(request: LlmRequest): RequestMeasurement {
        val serialized = ProviderFrame.encode(request)
        val inputUnits = tokenizer?.invoke(serialized) ?: serialized.toByteArray(StandardCharsets.UTF_8).size
        return RequestMeasurement(inputUnits, request.maxOutputTokens + safetyMargin, contextWindow, tokenizer == null)
    }

    fun fit(request: LlmRequest): Boolean = measure(request).fits

    fun requireFits(request: LlmRequest): RequestMeasurement = measure(request).also {
        if (!it.fits) throw ContextBudgetExceeded()
    }

    private object ProviderFrame {
        private val json = Json { encodeDefaults = true; explicitNulls = false }
        @Serializable data class Frame(val messages: List<Message>, val tools: List<Tool>, val requiredTool: String? = null, val maxTokens: Int, val stream: Boolean)
        @Serializable data class Message(val role: String, val content: String)
        @Serializable data class Tool(val name: String, val description: String, val parameters: String? = null)
        fun encode(request: LlmRequest): String = json.encodeToString(Frame(
            messages = request.messages.map { Message(it.role, it.content) },
            tools = request.tools.map { Tool(it.name, it.description, it.parametersJson) },
            requiredTool = request.requiredToolName,
            maxTokens = request.maxOutputTokens,
            stream = true,
        ))
    }
}
