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
 * reached, and then terminates it forcibly. The harness drives the same internal steps the real
 * deletion drives — not a re-implementation of them — so the state it leaves behind is exactly the
 * state production would leave if it were killed at that instant.
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

        /** The search entries are gone. */
        INDEX_DELETED,

        /** The parked directory is gone but the phase has not been advanced. */
        PURGED,
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

        val context = AppContext.open(dataDir)
        runBlocking {
            val operation = context.collectionService.beginDeletion(collectionId, confirmName)
            if (stopAfter == StopAfter.PREPARED) {
                waitForKill(stopAfter)
                return@runBlocking
            }

            context.collectionService.parkManagedDirectory(operation)
            if (stopAfter == StopAfter.RENAMED) {
                waitForKill(stopAfter)
                return@runBlocking
            }

            var current = context.deletions.advance(operation.id, DeletionPhase.FILES_MOVED)
            if (stopAfter == StopAfter.FILES_MOVED) {
                waitForKill(stopAfter)
                return@runBlocking
            }

            context.collectionService.deleteCollectionRows(current)
            current = context.deletions.get(current.id) ?: error("the deletion record disappeared")
            if (stopAfter == StopAfter.DB_DELETED) {
                waitForKill(stopAfter)
                return@runBlocking
            }

            context.collectionService.removeSearchEntries(current)
            current = context.deletions.advance(current.id, DeletionPhase.INDEX_DELETED)
            if (stopAfter == StopAfter.INDEX_DELETED) {
                waitForKill(stopAfter)
                return@runBlocking
            }

            context.collectionService.purgeTrash(current)
            waitForKill(stopAfter)
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
