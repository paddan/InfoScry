package infoscry.llm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** The retry decision table: the bound, the retryable set, and the jittered delays. */
class RetryPolicyTest {

    @Test
    fun `the retry budget and base delays are the documented ones`() {
        assertEquals(3, RetryPolicy.MAX_ATTEMPTS)
        assertEquals(listOf(500L, 1000L, 2000L), RetryPolicy.BASE_DELAY_MILLIS)
        assertEquals(3, RetryPolicy().maxRetries)
    }

    @Test
    fun `only http 429 and 5xx are retryable`() {
        val policy = RetryPolicy()
        assertTrue(policy.isRetryableStatus(429))
        assertTrue(policy.isRetryableStatus(500))
        assertTrue(policy.isRetryableStatus(503))
        assertTrue(policy.isRetryableStatus(599))
        assertFalse(policy.isRetryableStatus(200))
        assertFalse(policy.isRetryableStatus(400))
        assertFalse(policy.isRetryableStatus(401))
        assertFalse(policy.isRetryableStatus(403))
        assertFalse(policy.isRetryableStatus(404))
        assertFalse(policy.isRetryableStatus(499))
        assertFalse(policy.isRetryableStatus(600))
    }

    @Test
    fun `the first and third retry slots return the jittered base delays`() {
        // Constant random sources pin the jitter at its extremes: 0.0 -> -20%, 1.0 -> +20%.
        assertEquals(400L, RetryPolicy(randomSource = { 0.0 }).delayBeforeRetry(1))
        assertEquals(600L, RetryPolicy(randomSource = { 1.0 }).delayBeforeRetry(1))
        assertEquals(800L, RetryPolicy(randomSource = { 0.0 }).delayBeforeRetry(2))
        assertEquals(1200L, RetryPolicy(randomSource = { 1.0 }).delayBeforeRetry(2))
        assertEquals(1600L, RetryPolicy(randomSource = { 0.0 }).delayBeforeRetry(3))
        assertEquals(2400L, RetryPolicy(randomSource = { 1.0 }).delayBeforeRetry(3))
    }

    @Test
    fun `the jitter always stays within twenty percent of the base delay`() {
        val policy = RetryPolicy(randomSource = { 0.63 }) // arbitrary fixed point inside [0, 1)
        for (attempt in 1..3) {
            val base = RetryPolicy.BASE_DELAY_MILLIS[attempt - 1]
            val delay = policy.delayBeforeRetry(attempt)
            assertTrue(delay != null && delay >= base * 0.8)
            assertTrue(delay != null && delay <= base * 1.2)
        }
    }

    @Test
    fun `no delay is offered outside the retry budget`() {
        val policy = RetryPolicy()
        assertNull(policy.delayBeforeRetry(0))
        assertNull(policy.delayBeforeRetry(4))
    }

    @Test
    fun `a maximum of three retries is the documented ceiling`() {
        assertFailsWith<IllegalArgumentException> { RetryPolicy(maxRetries = 0) }
    }

    @Test
    fun `waitBeforeRetry sleeps through the injected delay function`() {
        val slept = mutableListOf<Long>()
        val policy = RetryPolicy(
            randomSource = { 0.5 },
            retryDelay = { millis -> slept.add(millis) },
        )
        runBlocking { policy.waitBeforeRetry(2) }
        assertEquals(listOf(1000L), slept, "the second retry waits the 1000 ms base delay, jittered to 1000")
    }

    @Test
    fun `waitBeforeRetry does not sleep when the retry budget is exhausted`() {
        val slept = mutableListOf<Long>()
        val policy = RetryPolicy(
            randomSource = { 0.5 },
            retryDelay = { millis -> slept.add(millis) },
        )
        runBlocking { policy.waitBeforeRetry(4) }
        assertEquals(emptyList<Long>(), slept)
    }
}