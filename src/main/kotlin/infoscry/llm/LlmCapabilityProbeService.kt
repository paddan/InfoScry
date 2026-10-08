package infoscry.llm

import infoscry.storage.Instants
import infoscry.storage.LlmStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.toList

/** What one two-request probe measured, before it is recorded on the profile. */
data class LlmProbeMeasurement(
    val textRequestSupported: Boolean,
    val toolCallingSupported: Boolean,
)

/**
 * The probe was not run because the profile must change first. [code] is stable for API callers; the message
 * names the profile's label and never a credential, address or provider body.
 */
class LlmProbeRefusedException(val code: String, message: String) : RuntimeException(message)

/**
 * The provider could not give an answer the probe can measure (unreachable, rate limited, or a server failure).
 * Nothing is recorded, because a dropped connection is not evidence that a model lacks tool calling.
 */
class LlmProbeUnavailableException(message: String) : RuntimeException(message)

/**
 * The one implementation of `infoscry llm test` and `POST /api/llm/profiles/{id}/probe`: the same two requests
 * (a text request, then a request that must call a harmless `ping` tool) and the same recording of the
 * tool-calling result. The CLI and the route are thin callers, so they cannot measure differently.
 *
 * [probe] sends the requests and records nothing, so a caller can run the network part outside the mutation
 * admission and record under it with [record].
 */
class LlmCapabilityProbeService(
    private val store: LlmStore,
    private val lookup: (String) -> String? = System::getenv,
    private val newHttpClient: () -> HttpClient = { HttpClient(CIO) },
) {

    suspend fun probe(profile: LlmProfile): LlmProbeMeasurement {
        // A switched-off profile is never dispatched to, probes included, so the refusal comes before any client exists.
        if (!profile.enabled) {
            throw LlmProbeRefusedException(
                code = "LLM_PROFILE_DISABLED",
                message = "the LLM profile '${profile.name}' is disabled; enable it before checking tool calling",
            )
        }
        val client = newHttpClient()
        try {
            val adapter = try {
                when (profile.provider) {
                    LlmProvider.OPENAI_COMPATIBLE -> OpenAiCompatibleClient(profile, lookup, client, RetryPolicy())
                    LlmProvider.ANTHROPIC -> AnthropicClient(profile, lookup, client, RetryPolicy())
                }
            } catch (invalid: IllegalArgumentException) {
                // The constructor's message can carry the stored address, so it is replaced rather than passed on.
                throw LlmProbeRefusedException(
                    code = "LLM_PROFILE_INVALID",
                    message = "the LLM profile '${profile.name}' cannot be checked; review its endpoint and provider",
                )
            }
            val textOk = answers(adapter, LlmRequest(messages = listOf(LlmMessage("user", "Say ok.")))) { event ->
                event is LlmEvent.TextDelta || event == LlmEvent.Completed
            }
            val toolsOk = answers(
                adapter,
                LlmRequest(
                    messages = listOf(LlmMessage("user", "Call the ping tool.")),
                    tools = listOf(ToolDefinition(name = "ping", description = "Nothing but a reply.")),
                    requiredToolName = "ping",
                ),
            ) { event -> event is LlmEvent.ToolCallReady }
            return LlmProbeMeasurement(textRequestSupported = textOk, toolCallingSupported = toolsOk)
        } finally {
            client.close()
        }
    }

    /** Persists the tool-calling result on the profile, the same way for every caller. */
    fun record(profileName: String, measurement: LlmProbeMeasurement, checkedAt: String = Instants.now()): Boolean =
        store.recordCapability(
            profileName,
            LlmCapabilityProbe(toolCallingSupported = measurement.toolCallingSupported, checkedAt = checkedAt),
        )

    /**
     * True or false only when the provider answered: a refusal (authentication, unsupported tools, a body that is
     * not the protocol) is a measurement; a missing answer is not, and is reported as unavailable.
     */
    private suspend fun answers(
        adapter: LlmStreamingClient,
        request: LlmRequest,
        accepted: (LlmEvent) -> Boolean,
    ): Boolean = try {
        adapter.stream(request).toList().any(accepted)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (refusal: LlmError) {
        when (refusal) {
            is LlmError.AuthenticationError, is LlmError.UnsupportedToolsError, is LlmError.MalformedResponseError -> false
            else -> throw unavailable()
        }
    } catch (failure: Throwable) {
        throw unavailable()
    }

    // The message names no provider body, address or key; the cause stays out of the API and the CLI output.
    private fun unavailable() = LlmProbeUnavailableException(
        "the provider did not answer the capability check; nothing was recorded",
    )
}
