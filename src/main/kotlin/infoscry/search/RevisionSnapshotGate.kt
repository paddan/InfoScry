package infoscry.search

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The snapshot that says which revisions a reader may see, and the leases that keep it from changing
 * under a reader that already holds it.
 *
 * A reader takes a lease and keeps the scope it was handed for the whole of its work; a publication
 * swaps the scope while new acquisitions are blocked, so an acquisition either sees the state before the
 * handoff or the state after it and never a mixture.
 *
 * The handoff also waits for the readers that are already inside, and that wait is what makes the two
 * halves of a publication agree. A reader's rows are the searcher it leased; a publication that wrote and
 * committed the new rows while a reader was still inside would be lending that reader both readings at
 * once. Waiting for the count to reach zero costs one bounded search, which is the price of the promise.
 *
 * There is no reentrant read/write lock here because a handoff is a suspending step (it commits a Lucene
 * writer) and a thread-affine lock cannot be held across a suspension point. The flag and the counter
 * express the same exclusion, and the flag is what a non-suspending reader spins on for the few
 * milliseconds a handoff takes.
 */
class RevisionSnapshotGate {

    private val readers = AtomicInteger()

    /**
     * Guards [handingOff] and [snapshot] together.
     *
     * A reader has to learn both in one indivisible step. If it could read "no handoff in progress" and only
     * afterwards be counted, a handoff could raise its flag and decide no reader was inside in between — and
     * then write under a reader, which is the one thing a handoff exists to prevent. Raising the flag,
     * counting a reader and swapping the snapshot all happen under this monitor, so a lease never names a
     * scope the flag was not raised for.
     */
    private val lock = Any()

    /**
     * Serializes handoffs.
     *
     * Two publications can hold the shared mutation permit at once, and two handoffs interleaving their
     * drain-and-swap would each decide "no reader is inside" while the other's readers are. Serializing them
     * is what makes that sentence true for every handoff rather than only for an uncontended one.
     */
    private val handoffs = Mutex()

    @Volatile
    private var snapshot: RevisionScope = RevisionScope.EVERYTHING

    @Volatile
    private var handingOff: Boolean = false

    /**
     * Why reads are refused, or null while they are served.
     *
     * It is set when an authoritative publication cannot be finished in this process, and every read fails
     * until recovery has completed that publication: the authority has already moved, so there is no reading
     * that is right for every consumer.
     */
    @Volatile
    private var sealedReason: String? = null

    /**
     * Refuses new reads until [unseal], for an authoritative publication that could not be made coherent.
     *
     * Sealing is the last resort and it is deliberately blunt: once the database serves a new reading while
     * the index still holds the old one, any search answer would be a mixture of the two. A refusal a reader
     * can retry is honest; a wrong passage is not.
     */
    fun seal(reason: String) {
        synchronized(lock) {
            if (sealedReason == null) sealedReason = reason
        }
    }

    /** Allows reads again, once the publication the seal was raised for is coherent. */
    fun unseal() {
        synchronized(lock) { sealedReason = null }
    }

    /**
     * Refuses a read that would serve the live reading without taking a lease, while the seal is raised.
     *
     * The source route reads SQLite rather than the index, so a lease protects nothing there — but the
     * same refusal is owed: while an authoritative publication has not switched its snapshot, SQLite
     * already serves the target's text and the index still holds the text it replaced, so a served live
     * read is the exact mixture [acquire] refuses on the search side. A request that names a revision is
     * not checked here: its text is the immutable text of the revision the caller named, which is
     * coherent whatever the index is doing — the spec lets requests already using a revision finish.
     */
    fun requireUnsealed() {
        sealedReason?.let { reason -> throw RevisionSnapshotUnavailableException(reason) }
    }

    /** The scope a reader that starts now must search under. */
    fun current(): RevisionScope = snapshot

    /**
     * Takes a lease on the current scope; the caller releases it when its read is finished.
     *
     * A reader that arrives while a handoff is in progress waits for it, which is the contract's "block
     * new reader acquisition across the handoff": it either reads the reading that was published before
     * the handoff or the one published by it, and it is counted before it reads the scope so a handoff
     * that starts afterwards cannot miss it.
     */
    fun acquire(): RevisionLease {
        val deadline = System.nanoTime() + ACQUIRE_TIMEOUT_NANOS
        while (true) {
            // The flag, the count and the scope are one decision taken under the handoff's own monitor: the
            // count rises before the lease exists, so a handoff that later sees a zero count has excluded this
            // reader by construction rather than by timing.
            synchronized(lock) {
                sealedReason?.let { reason -> throw RevisionSnapshotUnavailableException(reason) }
                if (!handingOff) {
                    readers.incrementAndGet()
                    return RevisionLease(this, snapshot)
                }
            }
            if (System.nanoTime() >= deadline) {
                error(
                    "a publication has been holding the revision handoff for more than " +
                        "${ACQUIRE_TIMEOUT_NANOS / NANOS_PER_SECOND} s; refusing to read under an unknown snapshot",
                )
            }
            try {
                Thread.sleep(ACQUIRE_POLL_MILLIS)
            } catch (interrupted: InterruptedException) {
                // A reader that is interrupted while it waits must not continue with the flag still set for
                // someone else: hand the interrupt back to the caller with its status intact.
                Thread.currentThread().interrupt()
                throw interrupted
            }
        }
    }

    /**
     * Blocks new acquisitions, waits for the readers already inside, and makes the scope [block] returns
     * the visible one.
     *
     * The block is where a publication writes what the new snapshot names; running it while no reader is
     * inside is what keeps the swap from being observable half-done. The wait is bounded, and running out
     * is an error rather than a shrug: the caller's fallback is to have published nothing, which is a
     * coherent state, whereas proceeding would not be.
     */
    suspend fun handoff(block: suspend () -> RevisionScope): RevisionScope = handoffs.withLock {
        synchronized(lock) { handingOff = true }
        try {
            check(awaitReaders(HANDOFF_DRAIN_TIMEOUT_MILLIS)) {
                "readers were still inside the previous snapshot after $HANDOFF_DRAIN_TIMEOUT_MILLIS ms; " +
                    "no publication was attempted"
            }
            val next = block()
            synchronized(lock) { snapshot = next }
            next
        } finally {
            synchronized(lock) { handingOff = false }
        }
    }

    internal fun release() {
        readers.decrementAndGet()
    }

    /**
     * Waits, bounded, for every reader that is inside a lease to finish.
     *
     * `false` means the wait ran out with readers still inside, and the caller must leave the rows it was
     * about to remove alone rather than removing them under a reader — they are hidden by the scope
     * either way, and a rebuild or the next startup removes them.
     */
    suspend fun drainReaders(timeoutMillis: Long): Boolean = awaitReaders(timeoutMillis)

    private suspend fun awaitReaders(timeoutMillis: Long): Boolean {
        val deadline = System.nanoTime() + timeoutMillis * NANOS_PER_MILLIS
        while (readers.get() > 0) {
            if (System.nanoTime() >= deadline) return false
            delay(DRAIN_POLL_MILLIS)
        }
        return true
    }

    private companion object {
        const val DRAIN_POLL_MILLIS: Long = 10L
        const val ACQUIRE_POLL_MILLIS: Long = 2L
        const val NANOS_PER_MILLIS: Long = 1_000_000
        const val NANOS_PER_SECOND: Long = 1_000_000_000

        /**
         * How long a handoff waits for the readers that are already inside.
         *
         * A reader is one search over one index, so this is generous; a search that outlives it is a search
         * that is not finishing, and publishing under it would be worse than refusing.
         */
        const val HANDOFF_DRAIN_TIMEOUT_MILLIS: Long = 30_000

        /** How long a reader waits for a handoff before it refuses to read under an unknown snapshot. */
        const val ACQUIRE_TIMEOUT_NANOS: Long = 60 * NANOS_PER_SECOND
    }
}

/**
 * The revision snapshot is unavailable, because an authoritative publication could not be made coherent in
 * this process.
 *
 * A reader gets this instead of an answer: the database already serves the new reading while the index still
 * holds the old one, so any answer would mix them. Recovery completes that publication at the next start,
 * which is why the message names restarting rather than retrying forever.
 */
class RevisionSnapshotUnavailableException(reason: String) :
    IllegalStateException("the revision snapshot is unavailable: $reason")

/** One reader's lease on a revision snapshot. Closing it twice releases once. */
class RevisionLease internal constructor(
    private val gate: RevisionSnapshotGate,
    val scope: RevisionScope,
) : AutoCloseable {

    private var released = false

    override fun close() {
        if (!released) {
            released = true
            gate.release()
        }
    }
}