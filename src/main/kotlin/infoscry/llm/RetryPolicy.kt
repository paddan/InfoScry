package infoscry.llm

import kotlin.math.max
import kotlinx.coroutines.delay

/**
 * The bounded retry rule for provider calls.
 *
 * A call may be retried at most [maxRetries] times, and only on HTTP 429 or a 5xx response. Base delays
 * are 500 / 1000 / 2000 ms, jittered within ±20 % so a fleet of clients does not stampede in lockstep.
 *
 * The policy is a pure decision maker: [delayBeforeRetry] computes how long to wait before the
 * [retryAttempt]-th retry (or returns null when there must be no retry), and [waitBeforeRetry] applies
 * that delay through the injected [retryDelay] function. Tests inject a no-op delay function so they
 * never sleep, and a deterministic random source so jitter is pinned at its bounds.
 */
class RetryPolicy(
    val maxRetries: Int = 3,
    private val randomSource: () -> Double = { Math.random() },
    private val retryDelay: suspend (Long) -> Unit = { millis -> delay(millis) },
) {

    companion object {
        /** The maximum number of retries after the first attempt. */
        const val MAX_ATTEMPTS = 3

        /** Base delay in milliseconds before retry attempt 1, 2 and 3. */
        val BASE_DELAY_MILLIS: List<Long> = listOf(500L, 1000L, 2000L)
    }

    init {
        require(maxRetries >= 1) { "RetryPolicy.maxRetries must be at least one, was $maxRetries" }
    }

    /** Whether the response status is retryable: HTTP 429 or any 5xx. */
    fun isRetryableStatus(statusCode: Int): Boolean =
        statusCode == 429 || (statusCode in 500..599)

    /**
     * The milliseconds to wait before the [retryAttempt]-th retry (1-based), jittered within ±20 % of the
     * base delay. Returns null when [retryAttempt] is outside `1..maxRetries` — the caller must not retry.
     */
    fun delayBeforeRetry(retryAttempt: Int): Long? {
        if (retryAttempt < 1 || retryAttempt > maxRetries) return null
        val base = BASE_DELAY_MILLIS[(retryAttempt - 1) % BASE_DELAY_MILLIS.size]
        val jitterRatio = (randomSource() * 2.0 - 1.0) * 0.2
        return max(0L, (base * (1.0 + jitterRatio)).toLong())
    }

    /** Sleeps for the delay before the [retryAttempt]-th retry, if a retry is allowed at all. */
    suspend fun waitBeforeRetry(retryAttempt: Int) {
        delayBeforeRetry(retryAttempt)?.let { millis -> retryDelay(millis) }
    }
}