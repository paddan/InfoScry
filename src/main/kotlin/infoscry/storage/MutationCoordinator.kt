package infoscry.storage

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A mutating command could not be admitted because InfoScry is running exclusive maintenance.
 *
 * The refusal is deliberate and immediate rather than a wait: deleting a collection or rebuilding the
 * index can take minutes, and an interactive command that silently hangs for that long is worse than
 * one that says why it refused. A background job stage is different: it waits through
 * [MutationCoordinator.awaitMutation], because it resumes at its next durable checkpoint anyway.
 */
class MaintenanceInProgressException(val operation: String) : IllegalStateException(
    "InfoScry is running exclusive maintenance ($operation); mutating commands are refused until it " +
        "finishes. Retry once it reports no maintenance in progress.",
)

/**
 * The admission gate every write path goes through.
 *
 * Two kinds of writer exist. Ordinary stages — an import copying a file, a job committing a page, a
 * request creating a collection — hold a **shared mutation permit**; several may run at once, because
 * they mutate different rows and the gate is not a global lock. Exclusive maintenance — collection
 * deletion and index rebuild — holds the **only** permit: it must not overlap any other writer, since
 * it moves files and swaps whole index generations while the authoritative data set changes.
 *
 * The gate is writer-preferring. As soon as maintenance asks for the exclusive permit, no new shared
 * permit is admitted, and maintenance waits for the stages already inside to reach their durable
 * checkpoint and leave. Readers are not part of this gate at all: they do not touch the writer or the
 * files, so they continue against the current state while maintenance runs.
 *
 * The maintenance owner must use the ungated internal steps of the services it drives instead of
 * calling back into [withMutation]. Re-entering is refused by design rather than deadlocked, so the
 * mistake is loud and immediate; the maintenance scope is already exclusive, so re-entering would add
 * nothing.
 */
class MutationCoordinator {

    private val gate = Mutex()

    /** Serializes exclusive maintenance, so two deletions or a deletion and a rebuild never overlap. */
    private val maintenanceOrder = Mutex()

    private var activeStages = 0

    /** Completed when [activeStages] returns to zero, which is what maintenance waits for. */
    private var drainSignal: CompletableDeferred<Unit>? = null

    /** Completed when the current maintenance scope ends, which is what waiting job stages await. */
    private var maintenanceSignal: CompletableDeferred<Unit>? = null

    /**
     * The exclusive operation that is requested or running, or `null` when the gate is open.
     *
     * Volatile because it is read by request handlers to decide between HTTP 423 and doing the work,
     * while it is written under [gate] by the maintenance owner.
     */
    @Volatile
    var maintenanceInProgress: String? = null
        private set

    /**
     * Runs one ordinary mutation under a shared permit.
     *
     * @throws MaintenanceInProgressException when exclusive maintenance is pending or running.
     */
    suspend fun <T> withMutation(block: suspend () -> T): T {
        val refused = gate.withLock {
            val operation = maintenanceInProgress
            if (operation == null) {
                activeStages++
                null
            } else {
                operation
            }
        }
        if (refused != null) throw MaintenanceInProgressException(refused)

        try {
            return block()
        } finally {
            releaseStage()
        }
    }

    /**
     * Runs one mutation from a background job stage: waits for exclusive maintenance to finish, then
     * runs under a shared permit. A job yields its permit at every durable checkpoint, so waiting here
     * costs a delay, never a lost unit of work.
     */
    suspend fun <T> awaitMutation(block: suspend () -> T): T {
        while (true) {
            val waitFor = gate.withLock {
                if (maintenanceInProgress == null) {
                    activeStages++
                    null
                } else {
                    maintenanceSignal ?: CompletableDeferred<Unit>().also { maintenanceSignal = it }
                }
            }
            if (waitFor == null) break
            waitFor.await()
        }

        try {
            return block()
        } finally {
            releaseStage()
        }
    }

    /**
     * Runs [block] as the only writer in the process.
     *
     * Requests the exclusive permit, waits for the stages already inside to drain, runs [block], and
     * releases. A second maintenance call waits for the first rather than failing, because the callers
     * are a deletion and a rebuild that both have to happen, in some order.
     */
    suspend fun <T> withExclusiveMaintenance(operation: String, block: suspend () -> T): T {
        maintenanceOrder.withLock {
            val drained = gate.withLock {
                maintenanceInProgress = operation
                maintenanceSignal = CompletableDeferred()
                prepareDrainLocked()
            }
            drained.await()

            try {
                return block()
            } finally {
                val waiting = gate.withLock {
                    maintenanceInProgress = null
                    drainSignal = null
                    maintenanceSignal.also { maintenanceSignal = null }
                }
                waiting?.complete(Unit)
            }
        }
    }

    /** Leaves the shared permit and wakes maintenance when this was the last stage inside. */
    private suspend fun releaseStage() {
        val drained = gate.withLock {
            activeStages--
            check(activeStages >= 0) { "mutation stages were released more often than admitted" }
            if (activeStages == 0) {
                drainSignal.also { drainSignal = null }
            } else {
                null
            }
        }
        drained?.complete(Unit)
    }

    private fun prepareDrainLocked(): CompletableDeferred<Unit> {
        val signal = CompletableDeferred<Unit>()
        drainSignal = signal
        if (activeStages == 0) signal.complete(Unit)
        return signal
    }
}
