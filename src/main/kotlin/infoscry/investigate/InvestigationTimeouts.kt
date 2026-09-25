package infoscry.investigate

import infoscry.llm.LlmEvent
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withTimeout

object InvestigationTimeouts {
    const val TOOL_CALL_TIMEOUT_MS = 30_000L
    const val PROVIDER_INACTIVITY_MS = 120_000L
    const val TURN_TIMEOUT_MS = 600_000L

    /**
     * Collects [flow] with a total deadline of [PROVIDER_INACTIVITY_MS] ms per call.
     * On expiry, throws [TimeoutCancellationException] which the service catches as a typed limit event.
     */
    suspend fun collectWithProviderDeadline(
        flow: Flow<LlmEvent>,
        onEvent: suspend (LlmEvent) -> Unit,
    ) = withTimeout(PROVIDER_INACTIVITY_MS) {
        flow.collect { onEvent(it) }
    }
}