package infoscry.llm

import kotlinx.serialization.Serializable
import java.net.URI
import java.util.Locale

/**
 * The LLM provider families the adapters speak.
 *
 * OPENAI_COMPATIBLE covers any endpoint that implements the OpenAI chat-completions protocol —
 * OpenAI, OpenRouter, Ollama, LM Studio, and most local servers. ANTHROPIC is the native Anthropic
 * Messages protocol. Earlier rulings: gemini/anything-else is reached through an OpenAI-compatible
 * gateway rather than a third adapter.
 */
@Serializable
enum class LlmProvider {
    OPENAI_COMPATIBLE,
    ANTHROPIC,
}

/** Which prompt body a composition is built for. Each role's default is independently editable. */
@Serializable
enum class LlmPromptRole {
    ASK,
    INVESTIGATE,
}

/**
 * A named LLM profile.
 *
 * A profile never holds an API-key value: it holds the *name* of the environment variable that will
 * be read at call time. The frontend and the CLI therefore see at most "the variable is present or
 * absent", never the credential itself. `toString()` of a Kotlin data class prints field names and
 * values, and the only secret-adjacent field here is the variable *name*, which is not a secret.
 */
@Serializable
data class LlmProfile(
    val id: String,
    val name: String,
    val provider: LlmProvider,
    val model: String,
    val contextWindow: Int,
    val maxOutputTokens: Int,
    val inputPricePerMillion: Double,
    val outputPricePerMillion: Double,
    val cacheReadPricePerMillion: Double,
    val enabled: Boolean,
    val endpoint: String = "",
    val apiKeyEnvironmentVariable: String? = null,
    val toolCallingMeasured: Boolean? = null,
    val capabilityCheckedAt: String? = null,
) {
    init {
        require(name.isNotBlank()) { "LlmProfile.name must not be blank" }
        require(model.isNotBlank()) { "LlmProfile.model must not be blank" }
        require(contextWindow > 0) { "LlmProfile.contextWindow must be positive, was $contextWindow" }
        require(maxOutputTokens > 0) { "LlmProfile.maxOutputTokens must be positive, was $maxOutputTokens" }
        require(inputPricePerMillion >= 0.0) { "LlmProfile.inputPricePerMillion must not be negative" }
        require(outputPricePerMillion >= 0.0) { "LlmProfile.outputPricePerMillion must not be negative" }
        require(cacheReadPricePerMillion >= 0.0) {
            "LlmProfile.cacheReadPricePerMillion must not be negative"
        }
        endpoint.takeIf { it.isNotBlank() }?.let { candidate ->
            val uri = try {
                URI(candidate)
            } catch (failure: java.net.URISyntaxException) {
                throw IllegalArgumentException(
                    "LlmProfile.endpoint must be an absolute URL when provided, was '$candidate'",
                    failure,
                )
            }
            require(uri.isAbsolute) { "LlmProfile.endpoint must be an absolute URL, was '$candidate'" }
        }
        apiKeyEnvironmentVariable?.let { require(ValidEnvironmentVariableName.matches(it)) {
            "apiKeyEnvironmentVariable must be a valid environment variable name, was '$it'"
        } }
    }

    /**
     * Whether the caller can expect tool calling on this profile: the declared capability is only a
     * stub until Task 20 measures it, so a profile is only `toolCallingSupported` when a measurement
     * actually succeeded.
     */
    val toolCallingSupported: Boolean get() = toolCallingMeasured == true

    /** The wired environment-variable presence, resolved by the injected lookup. */
    fun keyAvailable(lookup: (String) -> String?): Boolean = apiKeyEnvironmentVariable?.let { lookup(it) != null } == true
}

/** One capability measurement, so quotes never masquerade as measurements. */
@Serializable
data class LlmCapabilityProbe(
    val toolCallingSupported: Boolean,
    val checkedAt: String,
) {
    init {
        require(checkedAt.isNotBlank()) { "probe.checkedAt must not be blank" }
    }
}

object ValidEnvironmentVariableName {
    private val pattern = Regex("^[A-Za-z_][A-Za-z0-9_]*$")

    fun matches(name: String): Boolean = pattern.matches(name)

    /** The same rule the SQL CHECK column uses, so nothing can be written around the store. */
    fun requireValid(name: String?) {
        if (name != null) require(matches(name)) {
            "environment variable name must match ^[A-Za-z_][A-Za-z0-9_]*$, was '$name'"
        }
    }
}