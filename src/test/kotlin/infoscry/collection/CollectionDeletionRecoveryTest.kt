package infoscry.collection

import infoscry.AppContext
import infoscry.config.AppPaths
import infoscry.domain.CollectionId
import infoscry.storage.CollectionConfirmationMismatchException
import infoscry.storage.Database
import infoscry.storage.DeletionPhase
import infoscry.storage.DeletionStore
import infoscry.storage.MaintenanceInProgressException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.sql.SQLException
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Deleting a collection is the operation that spans everything InfoScry holds — files, rows and index
 * entries — so the tests below are about the two ways it can go wrong: the process dies between two
 * phases (a real child JVM, terminated forcibly, started by [DeletionHarness]), or a phase fails and
 * the operation has to stay pending and retryable.
 *
 * The invariant they all check is the same one: a deletion never discards files it cannot account for,
 * never finishes while work is missing, and never leaves a live row pointing at a file that moved.
 */
class CollectionDeletionRecoveryTest {

    private lateinit var dataDir: Path

    @BeforeTest
    fun createTemporaryDataDirectory() {
        dataDir = Files.createTempDirectory("infoscry-deletion")
    }

    @AfterTest
    fun removeTemporaryDataDirectory() {
        dataDir.toFile().deleteRecursively()
    }

    // ---- Terminated mid-deletion ----

    @Test
    fun `a deletion interrupted at PREPARED is finished by the next startup`() = assertRecovered(StopAfter.PREPARED)

    @Test
    fun `a deletion interrupted after the rename but before the phase is recorded recovers`() =
        assertRecovered(StopAfter.RENAMED)

    @Test
    fun `a deletion interrupted at FILES_MOVED recovers`() = assertRecovered(StopAfter.FILES_MOVED)

    @Test
    fun `a deletion interrupted at DB_DELETED recovers`() = assertRecovered(StopAfter.DB_DELETED)

    @Test
    fun `a deletion interrupted at INDEX_DELETED recovers`() = assertRecovered(StopAfter.INDEX_DELETED)

    @Test
    fun `a deletion interrupted after the trash was purged recovers`() = assertRecovered(StopAfter.PURGED)

    // ---- States that must not be finished silently ----

    @Test
    fun `both the managed directory and its parked copy existing is blocked, not resolved`() {
        AppContext.open(dataDir).use { context ->
            val collection = runBlocking { context.collectionService.create(COLLECTION_NAME) }
            val imported = context.library.importFile(collection.id, writeSource())
            val operation = context.collectionService.beginDeletion(collection.id, COLLECTION_NAME)
            val trash = context.paths.trashDirectory(operation.trashBasename)
            Files.createDirectories(trash)

            val report = runBlocking { context.collectionService.recoverDeletions() }

            assertEquals(1, report.blocked.size, "recovery must stop instead of choosing a directory")
            assertContains(report.blocked.single().lastError.orEmpty(), "both")
            assertTrue(Files.exists(imported.managedPath), "the managed original must not be deleted")
            assertTrue(Files.exists(trash), "the parked copy must not be deleted either")
            assertFailsWith<DeletionRecoveryBlockedException> {
                runBlocking { context.collectionService.create("Anything else") }
            }
        }
    }

    @Test
    fun `managed originals that vanished are blocked, not finished as if nothing was there`() {
        AppContext.open(dataDir).use { context ->
            val collection = runBlocking { context.collectionService.create(COLLECTION_NAME) }
            context.library.importFile(collection.id, writeSource())
            val operation = context.collectionService.beginDeletion(collection.id, COLLECTION_NAME)
            assertTrue(Files.isDirectory(context.paths.collectionDir(collection.id)))

            context.paths.collectionDir(collection.id).toFile().deleteRecursively()

            val report = runBlocking { context.collectionService.recoverDeletions() }

            assertEquals(1, report.blocked.size)
            assertContains(report.blocked.single().lastError.orEmpty(), "missing")
            assertEquals(
                DeletionPhase.PREPARED,
                report.blocked.single().phase,
                "a blocked operation must stay at the phase that describes the disk",
            )
        }
    }

    // ---- Injected phase failures ----

    @Test
    fun `an index failure leaves the deletion pending and a later recovery finishes it`() {
        val unavailable = CollectionIndexRemover { throw IOException("the index is unavailable") }
        var operationId: String? = null

        AppContext.open(dataDir, unavailable).use { context ->
            val collection = runBlocking { context.collectionService.create(COLLECTION_NAME) }
            context.library.importFile(collection.id, writeSource())

            assertFailsWith<IOException> {
                runBlocking { context.collectionService.deleteConfirmed(collection.id, COLLECTION_NAME) }
            }

            val pending = context.deletions.unfinishedFor(collection.id).single()
            operationId = pending.id
            assertEquals(DeletionPhase.DB_DELETED, pending.phase)
            assertNull(context.collections.get(collection.id), "the rows are gone, so the phase is durable")
            assertEquals(1, parkedDirectories(context.paths).size, "the files must stay parked")
        }

        AppContext.open(dataDir).use { reopened ->
            val report = reopened.deletionRecovery
            assertTrue(report.blocked.isEmpty(), "a working index removers finishes the deletion")
            assertEquals(DeletionPhase.DONE, reopened.deletions.get(operationId!!)?.phase)
            assertTrue(parkedDirectories(reopened.paths).isEmpty(), "the parked files are purged")
        }
    }

    @Test
    fun `a database failure leaves the deletion at its last durable phase`() {
        var operationId: String? = null

        AppContext.open(dataDir).use { context ->
            val collection = runBlocking { context.collectionService.create(COLLECTION_NAME) }
            context.library.importFile(collection.id, writeSource())
            val operation = context.collectionService.beginDeletion(collection.id, COLLECTION_NAME)
            context.collectionService.parkManagedDirectory(operation)
            val moved = context.deletions.advance(operation.id, DeletionPhase.FILES_MOVED)
            operationId = moved.id
            val parked = context.paths.trashDirectory(operation.trashBasename)

            // A dead connection is the cheapest honest way to make every statement fail: the phase
            // cannot be advanced, so the operation has to stay where it is.
            context.database.close()

            assertFailsWith<SQLException> { runBlocking { context.collectionService.deleteCollectionRows(moved) } }
            assertEquals(
                DeletionPhase.FILES_MOVED,
                openDeletionPhase(dataDir, moved.id),
                "the phase must not move when the transaction failed",
            )
            assertTrue(Files.exists(parked), "the files must stay parked")
        }

        AppContext.open(dataDir).use { reopened ->
            assertTrue(reopened.deletionRecovery.blocked.isEmpty())
            assertEquals(DeletionPhase.DONE, reopened.deletions.get(operationId!!)?.phase)
            assertTrue(parkedDirectories(reopened.paths).isEmpty(), "recovery purges what it parked")
        }
    }

    @Test
    fun `a trash cleanup failure leaves the deletion pending and a later recovery finishes it`() {
        val permissions = Files.getFileStore(dataDir).supportsFileAttributeView("posix")
        assertTrue(permissions, "this test makes the parked directory undeletable with POSIX modes")

        AppContext.open(dataDir).use { context ->
            val collection = runBlocking { context.collectionService.create(COLLECTION_NAME) }
            context.library.importFile(collection.id, writeSource())
            val operation = context.collectionService.beginDeletion(collection.id, COLLECTION_NAME)
            context.collectionService.parkManagedDirectory(operation)
            val moved = context.deletions.advance(operation.id, DeletionPhase.FILES_MOVED)
            context.collectionService.deleteCollectionRows(moved)
            val rowsGone = context.deletions.get(operation.id)!!
            runBlocking { context.collectionService.removeSearchEntries(rowsGone) }
            context.deletions.advance(operation.id, DeletionPhase.INDEX_DELETED)

            val trash = context.paths.trashDirectory(operation.trashBasename)
            val readable = PosixFilePermissions.fromString("r-x------")
            Files.setPosixFilePermissions(trash, readable)
            try {
                val report = runBlocking { context.collectionService.recoverDeletions() }

                assertEquals(1, report.blocked.size, "a parked directory that cannot be purged stays pending")
                assertEquals(DeletionPhase.INDEX_DELETED, report.blocked.single().phase)
                assertNotNull(report.blocked.single().lastError)
                assertTrue(Files.exists(trash), "nothing may be discarded to make the deletion look done")
            } finally {
                Files.setPosixFilePermissions(trash, PosixFilePermissions.fromString("rwx------"))
            }

            val finished = runBlocking { context.collectionService.recoverDeletions() }

            assertTrue(finished.blocked.isEmpty(), "restoring access lets recovery finish the operation")
            assertEquals(DeletionPhase.DONE, context.deletions.get(operation.id)?.phase)
            assertFalse(Files.exists(trash))
        }
    }

    // ---- Exclusion and confirmation ----

    @Test
    fun `mutating commands are refused while a deletion holds exclusive maintenance`() {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val gated = CollectionIndexRemover {
            started.complete(Unit)
            release.await()
        }

        AppContext.open(dataDir, gated).use { context ->
            val collection = runBlocking { context.collectionService.create(COLLECTION_NAME) }

            runBlocking {
                val deletion = launch(Dispatchers.Default) {
                    context.collectionService.deleteConfirmed(collection.id, COLLECTION_NAME)
                }
                withTimeout(TIMEOUT_MILLIS) { started.await() }

                val refusal = assertFailsWith<MaintenanceInProgressException> {
                    context.collectionService.create("An import in the middle of a deletion")
                }
                assertContains(refusal.operation, "delete collection")
                assertNotNull(context.mutations.maintenanceInProgress)

                release.complete(Unit)
                withTimeout(TIMEOUT_MILLIS) { deletion.join() }
            }

            assertNull(context.collections.get(collection.id))
            assertNotNull(runBlocking { context.collectionService.create("Afterwards") })
        }
    }

    @Test
    fun `a tombstoned collection is not listed, not readable and refuses new work`() {
        AppContext.open(dataDir).use { context ->
            val collection = runBlocking { context.collectionService.create(COLLECTION_NAME) }

            val operation = context.collectionService.beginDeletion(collection.id, COLLECTION_NAME)

            assertEquals(DeletionPhase.PREPARED, operation.phase)
            assertNull(context.collectionService.get(collection.id))
            assertTrue(context.collectionService.list().none { it.id == collection.id })
            assertFailsWith<NoSuchElementException> { context.collectionService.requireActive(collection.id) }
        }
    }

    @Test
    fun `a deletion with the wrong confirmation name is refused before anything is touched`() {
        AppContext.open(dataDir).use { context ->
            val collection = runBlocking { context.collectionService.create(COLLECTION_NAME) }
            val imported = context.library.importFile(collection.id, writeSource())

            assertFailsWith<CollectionConfirmationMismatchException> {
                runBlocking { context.collectionService.deleteConfirmed(collection.id, "Another collection") }
            }

            assertTrue(Files.exists(imported.managedPath))
            assertNotNull(context.collections.get(collection.id))
            assertEquals(
                infoscry.domain.CollectionLifecycle.ACTIVE,
                context.collections.get(collection.id)?.lifecycle,
                "the collection must not be tombstoned by a refused deletion",
            )
            assertTrue(context.deletions.listUnfinished().isEmpty(), "nothing may be recorded either")
        }
    }

    @Test
    fun `confirmation ignores surrounding whitespace, as collection names do`() {
        AppContext.open(dataDir).use { context ->
            val collection = runBlocking { context.collectionService.create(COLLECTION_NAME) }

            val operation = runBlocking {
                context.collectionService.deleteConfirmed(collection.id, "  $COLLECTION_NAME  ")
            }

            assertEquals(DeletionPhase.DONE, operation.phase)
            assertNull(context.collections.get(collection.id))
        }
    }

    // ---- Helpers ----

    /**
     * Kills a real child process at [stopAfter] and asserts that the next startup finishes the
     * deletion: the rows are gone, nothing is left parked, nothing points at a moved file, and the
     * user's own source file was never touched. Running recovery twice must change nothing, because a
     * recovered deletion is not work that is still pending.
     */
    private fun assertRecovered(stopAfter: StopAfter) {
        val source = writeSource()

        val collectionId = AppContext.open(dataDir).use { context ->
            val collection = runBlocking { context.collectionService.create(COLLECTION_NAME) }
            context.library.importFile(collection.id, source)
            collection.id
        }

        terminateHarnessAt(stopAfter, collectionId)

        AppContext.open(dataDir).use { reopened ->
            val report = reopened.deletionRecovery
            assertTrue(
                report.blocked.isEmpty(),
                "recovery after a kill at $stopAfter must not be blocked: ${report.blocked}",
            )
            assertNull(reopened.collections.get(collectionId), "no collection row may survive")
            assertNull(reopened.collectionService.get(collectionId))
            assertTrue(reopened.documents.listByCollection(collectionId, 10).isEmpty())
            assertTrue(reopened.deletions.listUnfinished().isEmpty(), "the operation must reach DONE")
            assertFalse(Files.exists(reopened.paths.collectionDir(collectionId)))
            assertTrue(parkedDirectories(reopened.paths).isEmpty(), "the parked directory must be purged")
            assertEquals(
                SOURCE_CONTENT,
                Files.readString(source),
                "the user's own source file is never InfoScry's to remove",
            )

            val again = runBlocking { reopened.collectionService.recoverDeletions() }
            assertTrue(again.recovered.isEmpty(), "a recovered deletion is not pending work")
            assertTrue(again.blocked.isEmpty())
            assertTrue(parkedDirectories(reopened.paths).isEmpty())
        }
    }

    private fun terminateHarnessAt(stopAfter: StopAfter, collectionId: CollectionId) {
        val process = ProcessBuilder(
            javaExecutable(),
            "--enable-native-access=ALL-UNNAMED",
            "-cp",
            System.getProperty("java.class.path"),
            DeletionHarness::class.java.name,
            "--data-dir",
            dataDir.toString(),
            "--collection-id",
            collectionId.value,
            "--confirm-name",
            COLLECTION_NAME,
            "--stop-after",
            stopAfter.name,
        ).redirectErrorStream(true).start()

        val output = Collections.synchronizedList(mutableListOf<String>())
        val pump = Thread {
            process.inputReader().useLines { lines -> lines.forEach { output += it } }
        }
        pump.isDaemon = true
        pump.start()

        val ready = "HARNESS READY ${stopAfter.name}"
        val deadline = System.nanoTime() + HARNESS_TIMEOUT_NANOS
        var reached = output.contains(ready)
        while (!reached && System.nanoTime() < deadline && process.isAlive) {
            Thread.sleep(POLL_MILLIS)
            reached = output.contains(ready)
        }
        if (!reached) {
            process.destroyForcibly()
            process.waitFor()
            throw AssertionError(
                "the harness never reached $stopAfter; output was:\n${output.joinToString("\n")}",
            )
        }

        process.destroyForcibly()
        process.waitFor()
    }

    private fun javaExecutable(): String =
        Path.of(System.getProperty("java.home"), "bin", "java").toString()

    private fun writeSource(): Path {
        val directory = Files.createDirectories(dataDir.resolve("sources"))
        val source = directory.resolve("nightfall-${SOURCE_SEQUENCE.incrementAndGet()}.txt")
        Files.writeString(source, SOURCE_CONTENT)
        return source
    }

    private fun openDeletionPhase(dataDir: Path, operationId: String): DeletionPhase =
        Database(dataDir.resolve("infoscry.db")).use { database ->
            DeletionStore(database).get(operationId)?.phase
                ?: error("the deletion record disappeared")
        }

    /** The parked directories, which are the ones with the trash prefix inside `library/`. */
    private fun parkedDirectories(paths: AppPaths): List<Path> {
        if (!Files.isDirectory(paths.libraryDir)) return emptyList()
        return Files.list(paths.libraryDir).use { entries ->
            entries.filter { it.fileName.toString().startsWith(".deleted-") }.toList()
        }
    }

    private companion object {
        const val COLLECTION_NAME = "Nightfall"
        const val SOURCE_CONTENT = "the source material"
        const val TIMEOUT_MILLIS = 15_000L
        const val HARNESS_TIMEOUT_NANOS = 30_000_000_000L
        const val POLL_MILLIS = 10L

        /** Keeps each test's source file distinct so a shared directory cannot hide a mistake. */
        val SOURCE_SEQUENCE = java.util.concurrent.atomic.AtomicInteger()
    }
}

private typealias StopAfter = DeletionHarness.StopAfter
