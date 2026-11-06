package infoscry.account

import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Counts failed sign-ins per username and per client address in a sliding window.
 *
 * It lives in memory: there is one server per database, and a restart forgetting the counts only gives an
 * attacker one more window. Usernames are compared without case, as the accounts are.
 */
class SignInRateLimiter(
    private val perUsername: Int = PER_USERNAME,
    private val perAddress: Int = PER_ADDRESS,
    private val window: Duration = WINDOW,
    private val clock: () -> Instant = Instant::now,
) {

    private val byUsername = ConcurrentHashMap<String, ArrayDeque<Instant>>()
    private val byAddress = ConcurrentHashMap<String, ArrayDeque<Instant>>()

    /** Refuses with [SignInRateLimitedException] while either count is at its limit. */
    fun check(username: String, address: String) {
        val now = clock()
        val waits = listOfNotNull(
            waitFor(byUsername, username.lowercase(), perUsername, now),
            waitFor(byAddress, address, perAddress, now),
        )
        waits.maxOrNull()?.let { throw SignInRateLimitedException(it) }
    }

    fun recordFailure(username: String, address: String) {
        val now = clock()
        record(byUsername, username.lowercase(), now)
        record(byAddress, address, now)
    }

    /** A successful sign-in clears the username's failures; the address keeps its count. */
    fun recordSuccess(username: String) {
        byUsername.remove(username.lowercase())
    }

    private fun record(map: ConcurrentHashMap<String, ArrayDeque<Instant>>, key: String, now: Instant) {
        map.compute(key) { _, existing ->
            (existing ?: ArrayDeque()).also { failures ->
                prune(failures, now)
                failures.addLast(now)
            }
        }
    }

    /** Seconds until the oldest failure in the window leaves it, or `null` when below [limit]. */
    private fun waitFor(map: ConcurrentHashMap<String, ArrayDeque<Instant>>, key: String, limit: Int, now: Instant): Long? {
        var wait: Long? = null
        map.computeIfPresent(key) { _, failures ->
            prune(failures, now)
            if (failures.size >= limit) {
                val millis = Duration.between(now, failures.first().plus(window)).toMillis()
                wait = maxOf(1L, (millis + 999) / 1000)
            }
            failures.takeIf { it.isNotEmpty() }
        }
        return wait
    }

    private fun prune(failures: ArrayDeque<Instant>, now: Instant) {
        val cutoff = now.minus(window)
        while (failures.isNotEmpty() && !failures.first().isAfter(cutoff)) failures.removeFirst()
    }

    companion object {
        const val PER_USERNAME = 10
        const val PER_ADDRESS = 50
        val WINDOW: Duration = Duration.ofMinutes(15)
    }
}
