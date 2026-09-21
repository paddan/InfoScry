package infoscry.collection

import infoscry.AppContext
import infoscry.domain.CollectionId
import infoscry.storage.DeletionPhase
import java.nio.file.Path
import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking

/**
 * A stand-in for a process that dies in the middle of a collection deletion.
 *
 * "The process was killed between two phases" cannot be produced from inside the process being killed,
 * so the recovery test starts this harness as a real child JVM, waits for it to report the instant it
 * reached, and then terminates it forcibly. The harness runs the *real* deletion and stops it through
 * the service's own observation seam, so the state it leaves behind is exactly the state production
 * would leave if it were killed at that instant — and the phase order it follows is production's, not a
 * second copy of it that could drift.
 *
 * It prints `HARNESS READY <stop-after>` on stdout and then blocks until it is killed. The data
 * directory lock stays held while it blocks, just as it would in a running server, and the operating
 * system releases it when the process dies.
 */
object DeletionHarness {

    /** The instants a deletion can be interrupted at, in the order they happen. */
    enum class StopAfter {
        /** The tombstone and the deletion record exist; nothing on disk has been touched. */
        PREPARED,

        /** The managed directory has been renamed into the trash but the phase has not been recorded. */
        RENAMED,

        /** The rename is durable. */
        FILES_MOVED,

        /** The collection's rows are gone. */
        DB_DELETED,

        /** The search entries have been removed but the phase that records it has not been written. */
        INDEX_REMOVED,

        /** The search entries are gone. */
        INDEX_DELETED,

        /** The parked directory is gone but the phase has not been advanced. */
        PURGED,

        ;

        /**
         * The instant this stop point corresponds to. [DeletionStep] is the service's, so a phase that
         * changes shape there fails here rather than passing silently.
         */
        internal fun instant(): DeletionStep = when (this) {
            PREPARED -> DeletionStep(DeletionPhase.PREPARED, recorded = true)
            RENAMED -> DeletionStep(DeletionPhase.FILES_MOVED, recorded = false)
            FILES_MOVED -> DeletionStep(DeletionPhase.FILES_MOVED, recorded = true)
            DB_DELETED -> DeletionStep(DeletionPhase.DB_DELETED, recorded = true)
            INDEX_REMOVED -> DeletionStep(DeletionPhase.INDEX_DELETED, recorded = false)
            INDEX_DELETED -> DeletionStep(DeletionPhase.INDEX_DELETED, recorded = true)
            PURGED -> DeletionStep(DeletionPhase.DONE, recorded = false)
        }

        /** The phase the deletion record must still show when the process was killed here. */
        internal val recordedPhase: DeletionPhase
            get() = when (this) {
                PREPARED, RENAMED -> DeletionPhase.PREPARED
                FILES_MOVED -> DeletionPhase.FILES_MOVED
                DB_DELETED, INDEX_REMOVED -> DeletionPhase.DB_DELETED
                INDEX_DELETED, PURGED -> DeletionPhase.INDEX_DELETED
            }

        /** Whether the managed directory is still in place when the process was killed here. */
        internal val managedDirectoryPresent: Boolean
            get() = this == PREPARED

        /** Whether the managed directory has been parked when the process was killed here. */
        internal val parkedDirectoryPresent: Boolean
            get() = this != PREPARED && this != PURGED
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val options = args.toList()
        val dataDir = Path.of(value(options, "--data-dir"))
        val collectionId = CollectionId(value(options, "--collection-id"))
        val confirmName = value(options, "--confirm-name")
        val stopAfter = runCatching { StopAfter.valueOf(value(options, "--stop-after")) }
            .getOrElse {
                System.err.println("--stop-after must be one of ${StopAfter.entries.joinToString()}")
                exitProcess(2)
            }

        val wanted = stopAfter.instant()
        AppContext.open(dataDir).use { context ->
            runBlocking {
                context.collectionService.deleteConfirmed(collectionId, confirmName) { instant ->
                    if (instant == wanted) waitForKill(stopAfter)
                }
            }
        }
    }

    /**
     * Reports the instant and then holds this JVM open. A bounded sleep would race the test's kill
     * signal; an unbounded one is safe because the test always terminates the process.
     */
    private fun waitForKill(stopAfter: StopAfter) {
        println("HARNESS READY $stopAfter")
        System.out.flush()
        while (true) {
            Thread.sleep(HOLD_MILLIS)
        }
    }

    private fun value(options: List<String>, name: String): String {
        val index = options.indexOf(name)
        require(index >= 0 && index + 1 < options.size) { "$name is required" }
        return options[index + 1]
    }

    private const val HOLD_MILLIS = 50L
}
