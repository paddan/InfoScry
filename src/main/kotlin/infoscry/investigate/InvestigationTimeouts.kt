package infoscry.investigate

import infoscry.llm.LlmEvent
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.withTimeoutOrNull

object InvestigationTimeouts {
    const val TOOL_CALL_TIMEOUT_MS = 30_000L

    /**
     * The provider inactivity cap: a streaming call that produces no valid provider event for this
     * long is treated as a stalled provider. The timer resets on every event the adapter emits —
     * text, usage, and tool-call progress — and is independent of the turn's absolute deadline.
     */
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
     * Why collecting a provider stream ended without a provider failure: the stream finished
     * normally, the provider went silent past the inactivity cap, or the absolute phase deadline
     * passed. The service maps these to a nonfatal research cutoff or a typed fatal outcome.
     */
    sealed interface ProviderCollectOutcome {
        data object Completed : ProviderCollectOutcome
        data object InactivityTimeout : ProviderCollectOutcome
        data object DeadlineExceeded : ProviderCollectOutcome
    }

    /**
     * Collects [flow] until it completes, throws, or hits one of two independent bounds:
     *
     * - the **inactivity** bound: [inactivityMs] with no event; it is reset by every event handed to
     *   [onEvent], so a stream that keeps producing progress may outlive one interval;
     * - the **absolute deadline** [deadlineNanos] (on [nanoTime]'s clock), which is never reset.
     *
     * Whichever expires first wins; a timeout returns the corresponding outcome instead of throwing,
     * so the caller can distinguish a nonfatal research cutoff from a fatal total-deadline failure.
     * A provider failure or an explicit cancellation still propagates as an exception.
     *
     * The remaining time is recomputed after every event and inactivity is measured from the
     * last event's arrival (not from the end of its handling), and each wait is the smaller of the
     * inactivity remainder and the deadline's remaining time, so an active stream can never extend
     * the budget. The only possible overshoot is one in-flight synchronous native tool call and the
     * adapter's own read, neither of which is interruptible from here.
     */
    suspend fun collectWithInactivityDeadline(
        flow: Flow<LlmEvent>,
        deadlineNanos: Long,
        inactivityMs: Long = PROVIDER_INACTIVITY_MS,
        nanoTime: () -> Long,
        onEvent: suspend (LlmEvent) -> Unit,
    ): ProviderCollectOutcome = coroutineScope {
        val events = flow.produceIn(this)
        try {
            // Inactivity is measured from the arrival of the last provider event, not from when its
            // handling finished, so backpressure in onEvent cannot silently extend the interval.
            var lastEventNanos = nanoTime()
            while (true) {
                val now = nanoTime()
                val deadlineRemainingMs = (deadlineNanos - now) / 1_000_000L
                if (deadlineRemainingMs <= 0L) return@coroutineScope ProviderCollectOutcome.DeadlineExceeded
                val inactivityRemainingMs = inactivityMs - (now - lastEventNanos) / 1_000_000L
                if (inactivityRemainingMs <= 0L) return@coroutineScope ProviderCollectOutcome.InactivityTimeout
                val deadlineBoundsWait = deadlineRemainingMs <= inactivityRemainingMs
                val waitMs = minOf(deadlineRemainingMs, inactivityRemainingMs).coerceAtLeast(1L)
                val received = withTimeoutOrNull(waitMs) { events.receiveCatching() }
                if (received == null) {
                    return@coroutineScope if (deadlineBoundsWait || nanoTime() >= deadlineNanos) {
                        ProviderCollectOutcome.DeadlineExceeded
                    } else {
                        ProviderCollectOutcome.InactivityTimeout
                    }
                }
                val event = received.getOrNull()
                if (event == null) {
                    received.exceptionOrNull()?.let { throw it }
                    return@coroutineScope ProviderCollectOutcome.Completed
                }
                lastEventNanos = nanoTime()
                onEvent(event)
            }
            @Suppress("UNREACHABLE_CODE")
            ProviderCollectOutcome.Completed
        } finally {
            events.cancel()
        }
    }
}
