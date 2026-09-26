package infoscry.investigate

import infoscry.llm.LlmEvent
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withTimeout

object InvestigationTimeouts {
    const val TOOL_CALL_TIMEOUT_MS = 30_000L
    const val PROVIDER_INACTIVITY_MS = 120_000L

    /**
     * The default total turn budget in milliseconds, aliasing the shared per-turn default. Production
     * turn length comes from the request's [InvestigationLimits.maxTurnSeconds]; this names the default.
     */
    const val TURN_TIMEOUT_MS = InvestigationLimits.DEFAULT_MAX_TURN_SECONDS * 1_000L

    /** The cap on the final-answer window held back from research: at most two minutes. */
    const val RESEARCH_RESERVE_MS = 120_000L

    /**
     * The final-answer reserve for a [turnTimeoutMs] budget: `min(120 s, 20% of the budget)`.
     */
    fun researchReserveMs(turnTimeoutMs: Long): Long =
        minOf(RESEARCH_RESERVE_MS, turnTimeoutMs / 5)

    /**
     * Collects [flow] for at most [timeoutMs] ms. On expiry it throws [TimeoutCancellationException],
     * which the service catches as a typed limit event. Callers pass the smallest of the inactivity
     * cap and the time left on the applicable deadline, so a call can never outlive its phase; a
     * non-positive timeout is clamped to 1 ms rather than throwing.
     */
    suspend fun collectWithProviderDeadline(
        flow: Flow<LlmEvent>,
        timeoutMs: Long = PROVIDER_INACTIVITY_MS,
        onEvent: suspend (LlmEvent) -> Unit,
    ) = withTimeout(timeoutMs.coerceAtLeast(1L)) {
        flow.collect { onEvent(it) }
    }
}