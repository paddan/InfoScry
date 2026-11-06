package infoscry.storage

import infoscry.account.UserId
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.asContextElement

/**
 * The person on whose behalf the current code runs: the caller of a request, or whoever started the job an
 * attempt belongs to.
 *
 * It travels as a coroutine context element backed by a thread-local, so it follows a request or an attempt
 * across suspensions and into the coroutines they launch, and plain blocking store code can read it. The
 * database stamps it into the attribution columns ([Database] hands it to each connection as
 * `infoscry.actor`, and the schema's triggers write it), so no store has to pass it along.
 */
object Actor {
    private val current = ThreadLocal<UserId?>()

    /** Who is acting now, or `null` when nobody is in person (startup recovery, tests writing rows directly). */
    fun current(): UserId? = current.get()

    /** A context element that makes [user] the actor of the coroutine it is added to. */
    fun element(user: UserId?): ThreadContextElement<UserId?> = current.asContextElement(user)

    /** Runs blocking [block] as [user], restoring whoever was acting before. */
    fun <T> runAs(user: UserId?, block: () -> T): T {
        val previous = current.get()
        current.set(user)
        try {
            return block()
        } finally {
            current.set(previous)
        }
    }
}
