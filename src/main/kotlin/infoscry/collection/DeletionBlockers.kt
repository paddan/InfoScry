package infoscry.collection

import infoscry.storage.DeletionKind
import infoscry.storage.DeletionOperation
import infoscry.storage.DeletionPhase

/**
 * The deletions whose recovery state is unsafe, shared by every service that admits mutations.
 *
 * A failed recovery can mean the record and the disk disagree in a way that could lose files. When that
 * happens the whole archive stops accepting mutating commands until an operator resolves it — reads,
 * source viewing and diagnostics keep working, which is what an operator needs to inspect the state that
 * blocked the deletion.
 *
 * It is one object rather than a list per service because the refusal has to be the same answer whichever
 * service a route asks first: a collection deletion and a document deletion are two machines, but an
 * unsafe one of either stops both.
 */
class DeletionBlockers {

    @Volatile
    private var unsafe: List<DeletionOperation> = emptyList()

    /** The unresolved operations, in the order they were recorded. */
    val operations: List<DeletionOperation> get() = unsafe

    /** Records one operation whose recovery is unsafe, replacing any earlier record of it. */
    fun record(operation: DeletionOperation) {
        if (operation.phase == DeletionPhase.DONE) return
        unsafe = unsafe.filterNot { it.id == operation.id } + operation
    }

    /**
     * Replaces the unresolved operations of one kind, leaving the other kind's records alone.
     *
     * Recovery runs once per kind and each reports the whole truth about its own kind, so replacing
     * within a kind is what makes a second recovery call idempotent without erasing the other service's
     * state.
     */
    fun replace(kind: DeletionKind, operations: List<DeletionOperation>) {
        unsafe = unsafe.filterNot { it.kind == kind } + operations
    }

    /** Refuses any mutating command while an unsafe deletion state is unresolved. */
    fun requireMutationsAllowed() {
        if (unsafe.isNotEmpty()) throw DeletionRecoveryBlockedException(unsafe)
    }
}
