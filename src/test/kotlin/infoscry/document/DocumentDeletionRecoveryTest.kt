package infoscry.document

import infoscry.AppContext
import infoscry.collection.DeletionStep
import infoscry.config.AppPaths
import infoscry.domain.Chunk
import infoscry.domain.ChunkId
import infoscry.domain.CollectionId
import infoscry.domain.ContentUnitId
import infoscry.domain.DocumentId
import infoscry.domain.DocumentStatus
import infoscry.domain.JobState
import infoscry.domain.JobType
import infoscry.domain.ExtractionMethod
import infoscry.domain.SourceLocation
import infoscry.embedding.DocumentEmbedder
import infoscry.embedding.TestDocumentEmbedder
import infoscry.extract.ContentUnitDraft
import infoscry.extract.ExtractionEvent
import infoscry.extract.ExtractionFingerprint
import infoscry.extract.ExtractionSettings
import infoscry.jobs.Harness
import infoscry.jobs.HeldUnits
import infoscry.jobs.ImportJobHandler
import infoscry.jobs.seedCollectionWithId
import infoscry.jobs.ParkedUnits
import infoscry.jobs.RecordingUnits
import infoscry.search.DocumentRow
import infoscry.storage.Database
import infoscry.storage.DeletionKind
import infoscry.storage.DeletionPhase
import infoscry.storage.DeletionStore
import infoscry.storage.DocumentBeingDeletedException
import infoscry.storage.ImportItemOutcome
import infoscry.jobs.StoredUnitsSink
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.sql.SQLException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Document deletion across real filesystem, database and index boundaries.
 *
 * The point of these tests is that a deletion is not a row delete: it is a durable operation that spans
 * three places that cannot be changed together, and every interruption of it must be recoverable — and,
 * when recovery cannot be safe, must stop the whole archive rather than discard a file. The kill cases
 * run a real child process through the service's own observation seam, so the state asserted after a
 * "crash" is exactly the state production would leave.
 */
class DocumentDeletionRecoveryTest {

    private lateinit var dataDir: Path

    @BeforeTest
    fun createTemporaryDataDirectory() {
        dataDir = Files.createTempDirectory("infoscry-document-deletion")
        // A new archive has no automatic Default collection, and this class names `default` explicitly.
        AppContext.open(dataDir).use { context -> context.seedCollectionWithId("default", "Default") }
    }

    @AfterTest
    fun removeTemporaryDataDirectory() {
        dataDir.toFile().deleteRecursively()
    }

    // ---- Interruption at every boundary ----

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
    fun `a deletion interrupted after the index removal but before the phase is recorded recovers`() =
        assertRecovered(StopAfter.INDEX_REMOVED)

    @Test
    fun `a deletion interrupted at INDEX_DELETED recovers`() = assertRecovered(StopAfter.INDEX_DELETED)

    @Test
    fun `a deletion interrupted after the parked files were purged recovers`() = assertRecovered(StopAfter.PURGED)

    @Test
    fun `a three-target deletion interrupted at DB_DELETED recovers every target and only them`() =
        assertRecovered(StopAfter.DB_DELETED, targets = 3)

    // ---- Unsafe recovery ----

    @Test
    fun `a managed directory that reappears beside its parked copy is blocked, not resolved`() {
        var operationId: String? = null

        AppContext.open(dataDir).use { context ->
            val staged = stage(context)
            val operation = context.documentService.beginDeletion(staged.collectionId, listOf(staged.target))
            operationId = operation.id
            context.documentService.parkDocumentDirectories(operation)
            // The state an operator has to resolve: the record says the files are parked, and a managed
            // directory for the same document exists too.
            Files.createDirectories(context.paths.documentDir(staged.collectionId, staged.target))
        }

        AppContext.open(dataDir).use { reopened ->
            val blocked = reopened.deletionRecovery.blocked
            assertEquals(1, blocked.size, "a document present in two places must stop recovery")
            assertEquals(operationId, blocked.single().id)
            assertEquals(DeletionPhase.PREPARED, reopened.deletions.get(operationId!!)?.phase)
            assertTrue(
                Files.exists(reopened.paths.trashDirectory(blocked.single().trashBasename)),
                "nothing may be discarded to make the deletion look finished",
            )
            // Every mutating command is refused while the state is unresolved.
            assertFailsWith<infoscry.collection.DeletionRecoveryBlockedException> {
                reopened.collectionService.requireMutationsAllowed()
            }
        }
    }

    @Test
    fun `managed files that vanished are blocked, not finished as if nothing was there`() {
        var operationId: String? = null

        AppContext.open(dataDir).use { context ->
            val staged = stage(context)
            val operation = context.documentService.beginDeletion(staged.collectionId, listOf(staged.target))
            operationId = operation.id
            // The record says the document had a managed directory; removing it behind the deletion's back
            // is the state that must not be resolved by declaring the deletion done.
            deleteRecursively(context.paths.documentDir(staged.collectionId, staged.target))
        }

        AppContext.open(dataDir).use { reopened ->
            val blocked = reopened.deletionRecovery.blocked
            assertEquals(1, blocked.size, "a vanished managed directory must stop recovery")
            assertEquals(operationId, blocked.single().id)
            assertFalse(blocked.single().lastError.isNullOrBlank(), "an operator needs the recorded reason")
        }
    }

    // ---- Injected phase failures ----

    @Test
    fun `an index failure leaves the deletion pending and a later recovery finishes it`() {
        var operationId: String? = null

        AppContext.open(dataDir, documentIndex = DocumentIndexRemover { throw IOException("the index is unavailable") })
            .use { context ->
                val staged = stage(context)

                assertFailsWith<IOException> {
                    runBlocking { context.documentService.deleteConfirmed(staged.collectionId, listOf(staged.target)) }
                }

                val pending = context.deletions.unfinishedFor(staged.collectionId, DeletionKind.DOCUMENT).single()
                operationId = pending.id
                assertEquals(DeletionPhase.DB_DELETED, pending.phase)
                assertNull(context.documents.get(staged.target), "the rows are gone, so the phase is durable")
                assertEquals(1, parkedDirectories(context.paths).size, "the files must stay parked")
            }

        AppContext.open(dataDir).use { reopened ->
            assertTrue(reopened.deletionRecovery.blocked.isEmpty(), "a working index remover finishes the deletion")
            assertEquals(DeletionPhase.DONE, reopened.deletions.get(operationId!!)?.phase)
            assertTrue(parkedDirectories(reopened.paths).isEmpty(), "the parked files are purged")
        }
    }

    @Test
    fun `a database failure leaves the deletion at its last durable phase`() {
        var operationId: String? = null

        AppContext.open(dataDir).use { context ->
            val staged = stage(context)
            val operation = context.documentService.beginDeletion(staged.collectionId, listOf(staged.target))
            context.documentService.parkDocumentDirectories(operation)
            val moved = context.deletions.advance(operation.id, DeletionPhase.FILES_MOVED)
            operationId = moved.id
            val parked = context.paths.trashDirectory(operation.trashBasename)

            // A dead connection is the cheapest honest way to make every statement fail.
            context.database.close()

            assertFailsWith<SQLException> { context.documentService.deleteDocumentRows(moved) }
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
    fun `a parked-files cleanup failure leaves the deletion pending and a later recovery finishes it`() {
        assertTrue(
            Files.getFileStore(dataDir).supportsFileAttributeView("posix"),
            "this test makes the parked directory undeletable with POSIX modes",
        )

        AppContext.open(dataDir).use { context ->
            val staged = stage(context)
            val operation = context.documentService.beginDeletion(staged.collectionId, listOf(staged.target))
            context.documentService.parkDocumentDirectories(operation)
            val moved = context.deletions.advance(operation.id, DeletionPhase.FILES_MOVED)
            context.documentService.deleteDocumentRows(moved)
            val rowsGone = context.deletions.get(operation.id)!!
            runBlocking { context.documentService.removeSearchEntries(rowsGone) }
            context.deletions.advance(operation.id, DeletionPhase.INDEX_DELETED)

            val trash = context.paths.trashDirectory(operation.trashBasename)
            Files.setPosixFilePermissions(trash, PosixFilePermissions.fromString("r-x------"))
            try {
                val report = runBlocking { context.documentService.recoverDeletions() }

                assertEquals(1, report.blocked.size, "a parked directory that cannot be purged stays pending")
                assertEquals(DeletionPhase.INDEX_DELETED, report.blocked.single().phase)
                assertNotNull(report.blocked.single().lastError)
                assertTrue(Files.exists(trash), "nothing may be discarded to make the deletion look done")
            } finally {
                Files.setPosixFilePermissions(trash, PosixFilePermissions.fromString("rwx------"))
            }

            val finished = runBlocking { context.documentService.recoverDeletions() }

            assertTrue(finished.blocked.isEmpty(), "restoring access lets recovery finish the operation")
            assertEquals(DeletionPhase.DONE, context.deletions.get(operation.id)?.phase)
            assertFalse(Files.exists(trash))
        }
    }

    // ---- Scope: only the targets, however many were selected ----

    @Test
    fun `one operation removes every selected document and leaves the rest`() {
        AppContext.open(dataDir).use { context ->
            val staged = stage(context, targets = 3)
            val operation = runBlocking {
                context.documentService.requestDeletion(staged.collectionId, staged.targets)
            }
            assertEquals(
                staged.targets.toSet(),
                context.deletions.get(operation.id)?.targets?.map { it.documentId }?.toSet(),
                "one operation records every selected document exactly once",
            )
            assertEquals(DeletionPhase.DONE, runBlocking { awaitDeletion(context, operation.id) }.phase)

            for (target in staged.targets) {
                assertNull(context.documents.get(target), "the selected document's row is gone")
                assertEquals(0, context.index().rowCount(target), "and so are its index entries")
                assertFalse(Files.exists(context.paths.documentDir(staged.collectionId, target)))
            }
            assertEquals(staged.survivor, context.documents.get(staged.survivor)?.id)
            assertEquals(1, context.index().rowCount(staged.survivor), "the unselected document is untouched")
            assertTrue(parkedDirectories(context.paths).isEmpty(), "the parked directory is purged once")
            assertEquals(
                "the target material-2",
                Files.readString(staged.targetSources[1]),
                "the user's own source files are never InfoScry's to remove",
            )
        }
    }

    @Test
    fun `only the targeted document is removed, and the others stay readable and searchable`() {
        val staged = stage()

        AppContext.open(dataDir).use { context ->
            val operation = runBlocking { context.documentService.requestDeletion(staged.collectionId, listOf(staged.target)) }
            assertTrue(runBlocking { awaitDeletion(context, operation.id) }.phase == DeletionPhase.DONE)

            assertNull(context.documents.get(staged.target), "the target's row is gone")
            assertEquals(staged.survivor, context.documents.get(staged.survivor)?.id)
            assertEquals(1, context.index().rowCount(staged.survivor), "the survivor keeps its index entries")
            assertEquals(
                "the survivor material",
                Files.readString(staged.survivorSource),
                "the user's own source file is never InfoScry's to remove",
            )

            val survivorHits = context.index().searchKeyword(staged.collectionId, "survivor", limit = 10)
            assertEquals(listOf(staged.survivor.value), survivorHits.map { it.documentId })
            assertTrue(
                context.index().searchKeyword(staged.collectionId, "target-only", limit = 10).isEmpty(),
                "the removed document's text must not answer a search any more",
            )
        }
    }

    // ---- The deleting guard ----

    @Test
    fun `a status write for a deleting document is refused as a cancellation, not a missing row`() {
        AppContext.open(dataDir).use { context ->
            val staged = stage(context)
            context.documentService.beginDeletion(staged.collectionId, listOf(staged.target))

            assertFailsWith<DocumentBeingDeletedException> {
                context.documents.updateStatus(staged.target, DocumentStatus.COMPLETE)
            }
            // The survivor is untouched by the guard.
            context.documents.updateStatus(staged.survivor, DocumentStatus.COMPLETE)
        }
    }

    @Test
    fun `an extraction commit for a deleting document is refused before it writes anything`() {
        AppContext.open(dataDir).use { context ->
            val staged = stage(context)
            context.documentService.beginDeletion(staged.collectionId, listOf(staged.target))
            val sink = StoredUnitsSink(context.paths, context.documents, context.content)
            val fingerprint = ExtractionFingerprint.of(
                context.documents.get(staged.survivor)!!.sha256,
                ExtractionSettings(ocrLanguages = "eng"),
            )

            assertFailsWith<DocumentBeingDeletedException> {
                runBlocking {
                    sink.deliver(
                        documentId = staged.target,
                        fingerprint = fingerprint,
                        event = ExtractionEvent.Finished(metadata = emptyMap(), totalUnits = 1),
                    )
                }
            }
            // The OCR status write lives inside the sink too, so it is the same boundary: a page read by a
            // tool must not move a document that is being deleted into its OCR phase.
            assertFailsWith<DocumentBeingDeletedException> {
                runBlocking {
                    sink.deliver(
                        documentId = staged.target,
                        fingerprint = fingerprint,
                        event = ExtractionEvent.UnitReady(
                            key = "unit-ocr",
                            ordinal = 0,
                            unit = ContentUnitDraft(
                                locator = SourceLocation.TextLines(1, 1),
                                extractedText = "a scanned page",
                                searchText = "a scanned page",
                                method = ExtractionMethod.OCR,
                            ),
                        ),
                    )
                }
            }
        }
    }

    @Test
    fun `a document targeted while it is being embedded is never published to the index`() {
        val harness = Harness(Files.createTempDirectory("infoscry-document-deletion-index-race"))
        try {
            AppContext.open(harness.dataDir).use { context ->
                val source = harness.writeText("raced.txt", "the raced file")
                val job = harness.enqueueForTest(context, listOf(source))
                // The deletion is admitted while the attempt is between extraction and publication, which is
                // the window a concurrent confirmation opens. Persisting the target is enough: every write
                // boundary after it must refuse.
                val deleting = DocumentEmbedder { texts ->
                    context.importItems.listForJob(job.id).firstNotNullOfOrNull { it.documentId }?.let { target ->
                        context.documentService.beginDeletion(CollectionId("default"), listOf(target))
                    }
                    TestDocumentEmbedder().embedDocuments(texts)
                }
                harness.attach(
                    context,
                    harness.storedPipeline(context, RecordingUnits(units = 1)),
                    embedder = deleting,
                )
                harness.awaitJob(context, job.id)

                val item = context.importItems.listForJob(job.id).single()
                val target = item.documentId ?: error("the attempt never attached a document")
                assertEquals(
                    ImportItemOutcome.CANCELLED,
                    item.outcome,
                    "the file is cancelled rather than published",
                )
                assertEquals(0, context.index().rowCount(target), "the deletion guard kept it out of the index")
                assertTrue(
                    context.documents.get(target)?.status != DocumentStatus.COMPLETE,
                    "a document being deleted must not be reported as finished",
                )
            }
        } finally {
            harness.close()
            harness.directory.toFile().deleteRecursively()
        }
    }

    // ---- A multi-file import keeps running, and the deleted target is not resurrected ----

    @Test
    fun `a deleted target is not copied again by a resumed multi-file import, and the rest still completes`() {
        val harness = Harness(Files.createTempDirectory("infoscry-document-deletion-import"))
        try {
            val first = harness.writeText("first.txt", "the first file")
            val second = harness.writeText("second.txt", "the second file")
            val parked = CompletableDeferred<Unit>()
            // The attempt dies while reading the first file, with its first unit committed.
            val jobId = harness.interruptDurably(listOf(first, second), ParkedUnits(parked), parked)

            val targetId = AppContext.open(harness.dataDir).use { context ->
                val item = context.importItems.listForJob(jobId).first { it.sourcePath.endsWith("first.txt") }
                val target = item.documentId ?: error("the parked attempt had not attached a document")
                val operation = runBlocking { context.documentService.requestDeletion(CollectionId("default"), listOf(target)) }
                assertTrue(runBlocking { awaitDeletion(context, operation.id) }.phase == DeletionPhase.DONE)
                // The durable disposition is written at admission, so the resume has it even though the
                // document row (and the item's reference to it) is already gone.
                assertEquals(ImportItemOutcome.CANCELLED, context.importItems.find(jobId, item.itemKey)?.outcome)
                target
            }

            val resumed = harness.resumeDurably(jobId, RecordingUnits(units = 1))

            assertNull(resumed.documents[targetId], "the deleted target must not come back")
            assertEquals(1, resumed.documents.size, "the other file of the import still became a document")
            assertEquals(
                DocumentStatus.COMPLETE,
                resumed.documents.values.single().status,
                "the file that was not deleted completes normally",
            )
            assertEquals(
                ImportItemOutcome.CANCELLED,
                resumed.items.first { it.sourcePath.endsWith("first.txt") }.outcome,
                "the deleted file's item keeps its disposition across the restart",
            )
            assertEquals(
                ImportItemOutcome.IMPORTED,
                resumed.items.first { it.sourcePath.endsWith("second.txt") }.outcome,
            )
            assertEquals("the first file", Files.readString(first), "the source file is untouched")
            assertTrue(
                parkedDirectories(AppPaths.from(harness.dataDir)).isEmpty(),
                "the deleted document's parked files are purged",
            )
        } finally {
            harness.close()
            harness.directory.toFile().deleteRecursively()
        }
    }

    /**
     * The same boundary without a restart: the deletion finishes while the import that owns that document
     * is still reading it, and the file is cancelled while the rest of the import carries on.
     *
     * The extractor holds itself *between* two units, so the shared mutation permit is free and the
     * deletion can be admitted while the import is running — which is the promise the confirmation makes
     * ("a running import for this document is interrupted after its current step; other documents,
     * including others in the same import, are not affected").
     */
    @Test
    fun `a document removed while its own file is being read cancels that file and the import continues`() {
        val harness = Harness(Files.createTempDirectory("infoscry-document-deletion-live-import"))
        try {
            val first = harness.writeText("first.txt", "the first file")
            val second = harness.writeText("second.txt", "the second file")
            val parked = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()

            AppContext.open(harness.dataDir).use { context ->
                val job = harness.enqueueForTest(context, listOf(first, second))
                harness.attach(context, harness.storedPipeline(context, HeldUnits(parked, release)))
                runBlocking { withTimeout(30_000) { parked.await() } }

                val target = context.documents.listByCollection(CollectionId("default"), limit = 10)
                    .single { it.originalFilename == "first.txt" }
                val operation = runBlocking {
                    context.documentService.requestDeletion(CollectionId("default"), listOf(target.id))
                }
                assertEquals(DeletionPhase.DONE, runBlocking { awaitDeletion(context, operation.id) }.phase)

                // The import resumes here, with the document it was reading already gone.
                release.complete(Unit)
                val finished = harness.awaitJob(context, job.id)

                assertEquals(
                    JobState.COMPLETE,
                    finished.state,
                    "one removed file is that file's outcome, not the whole import's",
                )
                val items = context.importItems.listForJob(job.id)
                assertEquals(
                    ImportItemOutcome.CANCELLED,
                    items.single { it.sourcePath.endsWith("first.txt") }.outcome,
                    "the removed file is cancelled rather than reported as a failure",
                )
                assertEquals(
                    ImportItemOutcome.IMPORTED,
                    items.single { it.sourcePath.endsWith("second.txt") }.outcome,
                    "the other file of the same import is read normally",
                )
                assertNull(context.documents.get(target.id), "the removed document must not come back")
                val remaining = context.documents.listByCollection(CollectionId("default"), limit = 10)
                assertEquals(1, remaining.size, "only the file that was not removed became a document")
                assertEquals(DocumentStatus.COMPLETE, remaining.single().status)
                assertEquals("the first file", Files.readString(first), "the source file is never InfoScry's to remove")
                assertEquals("the second file", Files.readString(second))
            }
        } finally {
            harness.close()
            harness.directory.toFile().deleteRecursively()
        }
    }

    /**
     * The file a queued attempt had not reached yet.
     *
     * A file that never reached a copy has an item with no document, so the deletion has nothing but the
     * source path to recognise it by. A queued attempt that resumed after the target was removed would
     * otherwise copy the deleted target back into the collection as a genuinely new document, which is the
     * resurrection the disposition exists to prevent.
     */
    @Test
    fun `a queued item for a deleted document's path is cancelled instead of copied again`() {
        val harness = Harness(Files.createTempDirectory("infoscry-document-deletion-pending-item"))
        try {
            val source = harness.writeText("queued.txt", "the queued bytes")
            // The document the deletion removes was created from this very path, by an import of its own.
            val imported = harness.importDurably(listOf(source), RecordingUnits(units = 1))
            val target = imported.documents.values.single().id

            val jobId = AppContext.open(harness.dataDir).use { context ->
                // The durable state a killed attempt leaves between queueing a file and copying it: the item
                // exists and names the path it will read, and no document exists yet.
                val job = harness.enqueueForTest(context, listOf(source))
                val itemKey = source.toRealPath().toString()
                context.importItems.queue(job.id, itemKey, itemKey)
                assertEquals(ImportItemOutcome.PENDING, context.importItems.find(job.id, itemKey)?.outcome)

                val operation = runBlocking {
                    context.documentService.deleteConfirmed(CollectionId("default"), listOf(target))
                }
                assertEquals(DeletionPhase.DONE, operation.phase)
                assertEquals(
                    ImportItemOutcome.CANCELLED,
                    context.importItems.find(job.id, itemKey)?.outcome,
                    "the queued file target is recorded as cancelled work when the deletion is admitted",
                )
                job.id
            }

            val resumed = harness.resumeDurably(jobId, RecordingUnits(units = 1))

            assertTrue(
                resumed.documents.isEmpty(),
                "the deleted document's path must not be copied again; it would be a new document",
            )
            assertEquals(
                ImportItemOutcome.CANCELLED,
                resumed.items.single().outcome,
                "the resume obeys the disposition instead of copying the file",
            )
            assertEquals(JobState.COMPLETE, resumed.job.state)
            assertEquals("the queued bytes", Files.readString(source), "the source file is untouched")
        } finally {
            harness.close()
            harness.directory.toFile().deleteRecursively()
        }
    }

    /**
     * The same disposition when the item already names a document.
     *
     * The copy and the admission race: a file can finish copying and get its document attached while the
     * deletion is being admitted, and the item's snapshot then names a document rather than nothing. The
     * deleted target's own row is gone, so nothing about the target is what the path still identifies — the
     * path is, and a file whose path the deletion owns is cancelled work either way. The document the item
     * names is not the deletion's target and is left exactly as it is.
     */
    @Test
    fun `a pending item for a deleted path is cancelled even when it already names a document`() {
        val harness = Harness(Files.createTempDirectory("infoscry-document-deletion-path-rule"))
        try {
            AppContext.open(harness.dataDir).use { context ->
                val collectionId = CollectionId("default")
                val source = harness.writeText("shared.txt", "the first bytes")
                val path = source.toRealPath()
                // Two documents from one path, because the file changed between the two imports: the earlier
                // version is what the deletion removes, and the item for the path already names the later one.
                val target = context.library.importFile(collectionId, path).document
                Files.writeString(path, "the second bytes")
                val later = context.library.importFile(collectionId, path).document

                val job = context.jobs.enqueue(
                    type = JobType.IMPORT,
                    collectionId = collectionId,
                    payload = "{}",
                    total = 1,
                )
                val itemKey = path.toString()
                context.importItems.queue(job.id, itemKey, itemKey)
                context.importItems.attachDocument(job.id, itemKey, later.id)
                assertEquals(ImportItemOutcome.PENDING, context.importItems.find(job.id, itemKey)?.outcome)

                val operation = runBlocking {
                    context.documentService.deleteConfirmed(collectionId, listOf(target.id))
                }

                assertEquals(DeletionPhase.DONE, operation.phase)
                assertEquals(
                    ImportItemOutcome.CANCELLED,
                    context.importItems.find(job.id, itemKey)?.outcome,
                    "the path the deletion owns is cancelled work, whatever the item already names",
                )
                assertNull(context.documents.get(target.id), "the deletion's own target is removed")
                assertNotNull(
                    context.documents.get(later.id),
                    "a document that is not the deletion's target is left alone",
                )
            }
        } finally {
            harness.close()
            harness.directory.toFile().deleteRecursively()
        }
    }

    /**
     * The window between a file's last document write and its per-item result.
     *
     * A deletion admitted exactly there marks the item cancelled and removes the document's row, so the item
     * write that follows would name a document that no longer exists. The write has to obey the disposition
     * instead: one file's race may not take the files after it down with it.
     */
    @Test
    fun `a deletion that lands after a file's last write cancels that file and the import finishes`() {
        val harness = Harness(Files.createTempDirectory("infoscry-document-deletion-item-race"))
        try {
            val first = harness.writeText("first.txt", "the first file")
            val second = harness.writeText("second.txt", "the second file")

            AppContext.open(harness.dataDir).use { context ->
                val job = harness.enqueueForTest(context, listOf(first, second))
                var deleted: DocumentId? = null
                var deletionPhase: DeletionPhase? = null
                ImportJobHandler.attachTo(
                    context,
                    harness.storedPipeline(context, RecordingUnits(units = 1)),
                    documentEmbedder = { TestDocumentEmbedder() },
                    afterIngestion = {
                        // The hook runs once per file; the window under test is the one after the file whose
                        // document is about to be deleted, and the file is identified by its own item.
                        if (deleted == null) {
                            val document = context.importItems.listForJob(job.id)
                                .first { it.sourcePath.endsWith("first.txt") }
                                .documentId ?: error("the first file was not attached to a document")
                            deleted = document
                            deletionPhase = context.documentService
                                .deleteConfirmed(CollectionId("default"), listOf(document)).phase
                        }
                    },
                )

                val finished = harness.awaitJob(context, job.id)

                assertEquals(DeletionPhase.DONE, deletionPhase, "the deletion admitted in the window finishes")
                val removed = deleted ?: error("the race never ran")
                assertEquals(
                    JobState.COMPLETE,
                    finished.state,
                    "one file's deletion is that file's outcome, not the whole import's failure",
                )
                val items = context.importItems.listForJob(job.id)
                val raced = items.single { it.sourcePath.endsWith("first.txt") }
                assertEquals(
                    ImportItemOutcome.CANCELLED,
                    raced.outcome,
                    "the item keeps the deletion's disposition instead of naming the removed document",
                )
                assertNull(raced.documentId, "a document id that no longer exists is not written back")
                assertEquals(
                    ImportItemOutcome.IMPORTED,
                    items.single { it.sourcePath.endsWith("second.txt") }.outcome,
                    "the file after the raced one is read normally",
                )
                assertNull(context.documents.get(removed), "the removed document must not come back")
                val remaining = context.documents.listByCollection(CollectionId("default"), limit = 10)
                assertEquals(1, remaining.size, "only the file that was not removed became a document")
                assertEquals("second.txt", remaining.single().originalFilename)
                assertEquals(DocumentStatus.COMPLETE, remaining.single().status)
            }
        } finally {
            harness.close()
            harness.directory.toFile().deleteRecursively()
        }
    }

    /**
     * The window between a file's item being read and its bytes being copied.
     *
     * The item was read as pending, and admission cancels it by source path while the copy is still ahead. A
     * handler that trusted that snapshot would copy the file, create a document for the deleted path and read
     * it into the index — the same resurrection as copying the deleted target, by another route. What has to
     * happen instead is that the cancelled file leaves nothing behind and the rest of the import finishes.
     */
    @Test
    fun `a deletion between the item read and the copy leaves no document and the import finishes`() {
        val harness = Harness(Files.createTempDirectory("infoscry-document-deletion-copy-race"))
        try {
            val first = harness.writeText("first.txt", "the first file")
            val second = harness.writeText("second.txt", "the second file")
            // The document the deletion removes was created from this very path, by an import of its own.
            val target = harness.importDurably(listOf(first), RecordingUnits(units = 1)).documents.values.single().id

            AppContext.open(harness.dataDir).use { context ->
                val job = harness.enqueueForTest(context, listOf(first, second))
                var deletionPhase: DeletionPhase? = null
                ImportJobHandler.attachTo(
                    context,
                    harness.storedPipeline(context, RecordingUnits(units = 1)),
                    documentEmbedder = { TestDocumentEmbedder() },
                    beforeCopy = {
                        // The hook runs once per file, and the window under test is the one before the copy of
                        // the file whose path a deletion is about to own.
                        if (deletionPhase == null) {
                            deletionPhase = context.documentService
                                .deleteConfirmed(CollectionId("default"), listOf(target)).phase
                        }
                    },
                )

                val finished = harness.awaitJob(context, job.id)

                assertEquals(DeletionPhase.DONE, deletionPhase, "the deletion admitted in the window finishes")
                assertEquals(
                    JobState.COMPLETE,
                    finished.state,
                    "one file the deletion cancelled is that file's outcome, not the import's failure",
                )
                val items = context.importItems.listForJob(job.id)
                val cancelled = items.single { it.sourcePath.endsWith("first.txt") }
                assertEquals(ImportItemOutcome.CANCELLED, cancelled.outcome)
                assertNull(cancelled.documentId, "a file the deletion cancelled is never attached")
                assertEquals(
                    ImportItemOutcome.IMPORTED,
                    items.single { it.sourcePath.endsWith("second.txt") }.outcome,
                    "the file after the cancelled one is read normally",
                )
                assertNull(context.documents.get(target), "the deleted document must not come back")
                val remaining = context.documents.listByCollection(CollectionId("default"), limit = 10)
                assertEquals(1, remaining.size, "the cancelled file created no document")
                assertEquals("second.txt", remaining.single().originalFilename)
                assertEquals(DocumentStatus.COMPLETE, remaining.single().status)
                assertEquals("the first file", Files.readString(first), "the source file is untouched")
            }
        } finally {
            harness.close()
            harness.directory.toFile().deleteRecursively()
        }
    }

    /**
     * The window the per-file checks cannot close: the file's document exists (copied and attached), and the
     * deletion of the document that path was imported from is admitted before the reading starts.
     *
     * The handler's own checks are best effort by construction — this file's attempt can be interrupted,
     * killed, or simply be between two stages when admission lands — so the *operation* owns the path: it
     * finds the document its path led to in its own last phase and removes it like a target. The assertions
     * below hold with the handler's cleanup neutralized, which is what makes this the operation's guarantee
     * rather than the attempt's luck. A later explicit import of the same path is an ordinary new import:
     * the finished operation owns nothing.
     */
    @Test
    fun `a document copied for a deleted path before the reading starts is removed by the operation`() {
        val harness = Harness(Files.createTempDirectory("infoscry-document-deletion-owned-path"))
        try {
            val first = harness.writeText("first.txt", "the first file")
            val second = harness.writeText("second.txt", "the second file")
            val collectionId = CollectionId("default")
            // The document the deletion removes was created from this very path, by an import of its own.
            val target = harness.importDurably(listOf(first), RecordingUnits(units = 1)).documents.values.single().id
            // The file changes after that import, so the attempt below copies a *new* document for this path
            // instead of finding the deleted document's bytes already stored as a duplicate of it.
            Files.writeString(first, "the first file, edited")

            var copied: DocumentId? = null
            var deletionPhase: DeletionPhase? = null
            AppContext.open(harness.dataDir).use { context ->
                val job = harness.enqueueForTest(context, listOf(first, second))
                ImportJobHandler.attachTo(
                    context,
                    harness.storedPipeline(context, RecordingUnits(units = 1)),
                    documentEmbedder = { TestDocumentEmbedder() },
                    afterAttach = {
                        // Once: the window is the one before the reading of the file whose path the deletion
                        // is about to own, and the file's own document is what its item already names.
                        if (copied == null) {
                            val item = context.importItems.listForJob(job.id)
                                .single { it.sourcePath.endsWith("first.txt") }
                            copied = item.documentId ?: error("the first file was not attached to a document")
                            deletionPhase = context.documentService
                                .deleteConfirmed(collectionId, listOf(target)).phase
                        }
                    },
                )

                val finished = harness.awaitJob(context, job.id)

                assertEquals(DeletionPhase.DONE, deletionPhase, "the deletion admitted in the window finishes")
                val copiedHere = copied ?: error("the window never ran")
                assertTrue(copiedHere != target, "the window is about a document copied for the deleted path")
                assertEquals(
                    JobState.COMPLETE,
                    finished.state,
                    "one file the deletion removed is that file's outcome, not the import's failure",
                )
                val items = context.importItems.listForJob(job.id)
                val cancelled = items.single { it.sourcePath.endsWith("first.txt") }
                assertEquals(
                    ImportItemOutcome.CANCELLED,
                    cancelled.outcome,
                    "the file whose document the deletion removed is cancelled work, never an import",
                )
                assertNull(cancelled.documentId)
                assertEquals(
                    ImportItemOutcome.IMPORTED,
                    items.single { it.sourcePath.endsWith("second.txt") }.outcome,
                    "the rest of the import finishes",
                )

                assertNull(context.documents.get(target), "the document the caller deleted is gone")
                assertNull(
                    context.documents.get(copiedHere),
                    "the document copied for its path while the deletion ran is removed by the operation",
                )
                assertFalse(
                    Files.exists(context.paths.documentDir(collectionId, copiedHere)),
                    "its managed bytes are gone, not just its row",
                )
                assertEquals(0, context.index().rowCount(copiedHere), "and it is not in the index")
                assertTrue(
                    context.documents.isDeletionTarget(copiedHere),
                    "it is a durable target, which is what refuses any attempt that still holds it",
                )
                val remaining = context.documents.listByCollection(collectionId, limit = 10)
                assertEquals(1, remaining.size, "only the file that was not removed became a document")
                assertEquals("second.txt", remaining.single().originalFilename)
                assertEquals(
                    "the first file, edited",
                    Files.readString(first),
                    "the source file is untouched, whatever the deletion did to its document",
                )
            }

            // A later explicit import of the same path is an ordinary new import: the operation owned the
            // path while it ran, and owns nothing once it is DONE.
            val created = copied ?: error("the window never ran")
            val reimported = harness.importDurably(listOf(first), RecordingUnits(units = 1))
            val replacement = reimported.documents.values.single { it.originalFilename == "first.txt" }
            assertEquals(DocumentStatus.COMPLETE, replacement.status)
            assertEquals(
                first.toRealPath().toString(),
                replacement.sourcePath,
                "the new document is imported from the same path that used to be owned",
            )
            assertTrue(replacement.id != created && replacement.id != target)

            AppContext.open(harness.dataDir).use { reopened ->
                val kept = reopened.documents.listByCollection(collectionId, limit = 10)
                assertEquals(2, kept.size, "the re-import keeps its document beside the other file's")
                assertTrue(kept.any { it.id == replacement.id }, "the new document survives reopening")
                assertFalse(
                    reopened.documents.isDeletionTarget(replacement.id),
                    "a finished deletion refuses no path",
                )
                assertTrue(
                    reopened.index().rowCount(replacement.id) > 0,
                    "and the re-imported document is searchable",
                )
            }
        } finally {
            harness.close()
            harness.directory.toFile().deleteRecursively()
        }
    }

    /**
     * The crash window: the copy has committed a document for this path and the item has not been attached to
     * it yet.
     *
     * A deletion admitted exactly here cancels the path's pending item, and the attach that follows is then
     * refused — so no per-file write ever names the copied document, and a process that died at this instant
     * would leave nothing behind for a repair to work from. The copy's own transaction wrote the link between
     * the item and the document it made, which is what lets the operation find and remove it by itself. This
     * test asserts that with the handler's per-file cleanup neutralized, so the operation is the only thing
     * that can pass it.
     */
    @Test
    fun `a document copied for a deleted path whose attach never happens is removed by the operation`() {
        val harness = Harness(Files.createTempDirectory("infoscry-document-deletion-copy-commit-race"))
        try {
            val first = harness.writeText("first.txt", "the first file")
            val second = harness.writeText("second.txt", "the second file")
            val collectionId = CollectionId("default")
            // The document the deletion removes was created from this very path, by an import of its own.
            val target = harness.importDurably(listOf(first), RecordingUnits(units = 1)).documents.values.single().id
            // The file changes, so the attempt below copies a *new* document for this path.
            Files.writeString(first, "the first file, edited")

            var copied: DocumentId? = null
            var linkedByTheCopy: Boolean? = null
            var deletionPhase: DeletionPhase? = null
            AppContext.open(harness.dataDir).use { context ->
                val job = harness.enqueueForTest(context, listOf(first, second))
                ImportJobHandler.attachTo(
                    context,
                    harness.storedPipeline(context, RecordingUnits(units = 1)),
                    documentEmbedder = { TestDocumentEmbedder() },
                    afterCopy = {
                        // Once: the window is the one between the copy of the file whose path the deletion is
                        // about to own and the attach that would claim it.
                        if (deletionPhase == null) {
                            val item = context.importItems.listForJob(job.id)
                                .single { it.sourcePath.endsWith("first.txt") }
                            // Both are read as they stand: the link is the copy's business, and a build that
                            // records it only at the attach has nothing here — which is the window itself.
                            copied = item.documentId
                            linkedByTheCopy = item.createdDocument
                            deletionPhase = context.documentService
                                .deleteConfirmed(collectionId, listOf(target)).phase
                        }
                    },
                )

                val finished = harness.awaitJob(context, job.id)

                assertEquals(DeletionPhase.DONE, deletionPhase, "the deletion admitted in the window finishes")
                assertEquals(
                    JobState.COMPLETE,
                    finished.state,
                    "one file the deletion removed is that file's outcome, not the import's failure",
                )
                val items = context.importItems.listForJob(job.id)
                val cancelled = items.single { it.sourcePath.endsWith("first.txt") }
                assertEquals(ImportItemOutcome.CANCELLED, cancelled.outcome)
                assertNull(cancelled.documentId, "the refused attach never named the document")
                assertEquals(
                    ImportItemOutcome.IMPORTED,
                    items.single { it.sourcePath.endsWith("second.txt") }.outcome,
                    "the rest of the import finishes",
                )

                assertNull(context.documents.get(target), "the document the caller deleted is gone")
                // The invariant, stated without depending on whether the item managed to claim the document:
                // nothing may be left for the deleted path.
                val atThePath = context.documents.listByCollection(collectionId, limit = 10)
                    .filter { it.originalFilename == "first.txt" }
                assertTrue(
                    atThePath.isEmpty(),
                    "no document may survive at the deleted path, but these did: ${atThePath.map { it.id.value }}",
                )
                copied?.let { made ->
                    assertEquals(0, context.index().rowCount(made), "its search entries are gone")
                    assertFalse(
                        Files.exists(context.paths.documentDir(collectionId, made)),
                        "its managed bytes are gone, not just its row",
                    )
                    assertTrue(
                        context.documents.isDeletionTarget(made),
                        "it is a durable target, which is what refuses any attempt that still holds it",
                    )
                }
                val remaining = context.documents.listByCollection(collectionId, limit = 10)
                assertEquals(1, remaining.size, "the other file's document is the only one left")
                assertEquals("second.txt", remaining.single().originalFilename)
                assertEquals(
                    "the first file, edited",
                    Files.readString(first),
                    "the source file is untouched, whatever the deletion did to its document",
                )
                // Last, because it is the fix's own claim rather than the invariant: the copy recorded what
                // it made in its own commit, so nothing had to happen after it for the deletion to see it.
                assertEquals(true, linkedByTheCopy, "the copy itself recorded what it made, before any attach")
            }

            // A later explicit import of the same path is an ordinary new import: the operation owned the
            // path while it ran, and owns nothing once it is DONE.
            val removed = copied
            val reimported = harness.importDurably(listOf(first), RecordingUnits(units = 1))
            val replacement = reimported.documents.values.single { it.originalFilename == "first.txt" }
            assertEquals(DocumentStatus.COMPLETE, replacement.status)
            assertEquals(
                first.toRealPath().toString(),
                replacement.sourcePath,
                "the new document is imported from the same path that used to be owned",
            )
            assertTrue(replacement.id != target)
            removed?.let { assertTrue(replacement.id != it, "the re-import is a new document, not the swept one") }

            AppContext.open(harness.dataDir).use { reopened ->
                val kept = reopened.documents.listByCollection(collectionId, limit = 10)
                assertEquals(2, kept.size, "the re-import keeps its document beside the other file's")
                assertTrue(kept.any { it.id == replacement.id }, "the new document survives reopening")
                assertFalse(
                    reopened.documents.isDeletionTarget(replacement.id),
                    "a finished deletion refuses no path",
                )
                assertTrue(
                    reopened.index().rowCount(replacement.id) > 0,
                    "and the re-imported document stays searchable",
                )
            }
        } finally {
            harness.close()
            harness.directory.toFile().deleteRecursively()
        }
    }

    /**
     * A document that appears at a target's path *while the operation runs* is swept as well.
     *
     * Nothing else in the process may write while a deletion holds exclusive maintenance, which is exactly
     * why its last phase is the place to look: the operation sees the document before it is done, and the
     * test creates it there — the row a raced copy publishes after admission, with search entries of its
     * own, so the sweep's index step is the one that has to remove them.
     */
    @Test
    fun `a document created at a deleted path while the operation runs is swept`() {
        AppContext.open(dataDir).use { context ->
            val collectionId = CollectionId("default")
            val source = writeSource("owned", "the owned bytes")
            val target = context.library.importFile(collectionId, source).document
            val survivorSource = writeSource("survivor", "the survivor bytes")
            val survivor = context.library.importFile(collectionId, survivorSource).document
            // A document at another path, searchable, so the sweep's steps are observable as *not* touching it.
            runBlocking { context.index().replaceDocument(rowsFor(collectionId, survivor.id, "the survivor document")) }
            assertTrue(context.index().rowCount(survivor.id) > 0)
            var created: DocumentId? = null

            val operation = runBlocking {
                context.documentService.deleteConfirmed(collectionId, listOf(target.id)) { instant ->
                    if (instant == DeletionStep(DeletionPhase.DB_DELETED, recorded = true)) {
                        // The same path, a new document, created after the operation was admitted.
                        val document = context.library.importFile(collectionId, source).document
                        context.index().replaceDocument(rowsFor(collectionId, document.id, "the raced document"))
                        assertTrue(context.index().rowCount(document.id) > 0, "the raced document is searchable first")
                        created = document.id
                    }
                }
            }

            val swept = created ?: error("the window never ran")
            assertEquals(DeletionPhase.DONE, operation.phase)
            assertNull(context.documents.get(target.id), "the document the caller deleted is gone")
            assertNull(context.documents.get(swept), "a document created at its path while it ran is swept")
            assertFalse(Files.exists(context.paths.documentDir(collectionId, swept)), "its bytes are gone")
            assertEquals(0, context.index().rowCount(swept), "its search entries are gone")
            assertNotNull(context.documents.get(survivor.id), "a document at another path is not touched")
            assertEquals(1, context.index().rowCount(survivor.id), "nor its search entries")
            assertTrue(parkedDirectories(context.paths).isEmpty(), "nothing is left parked")
        }
    }

    /**
     * The sweep is idempotent across a resume: an operation that already removed the document its path led
     * to leaves nothing for the next attempt to sweep, and the next attempt still finishes the deletion.
     *
     * The interruption is the parked-directory cleanup, which is the one step after the sweep: the record
     * keeps the phase it describes, a new process over the same archive resumes it, and the resumed sweep
     * finds no document to adopt because the record and the rows already agree.
     */
    @Test
    fun `a resumed operation sweeps once and finishes without a second pass`() {
        assertTrue(
            Files.getFileStore(dataDir).supportsFileAttributeView("posix"),
            "this test makes the parked directory undeletable with POSIX modes",
        )

        val collectionId = CollectionId("default")
        var operationId: String? = null
        var trash: Path? = null
        var target: DocumentId? = null
        var swept: DocumentId? = null

        AppContext.open(dataDir).use { context ->
            val source = writeSource("owned", "the owned bytes")
            val deleted = context.library.importFile(collectionId, source).document
            target = deleted.id

            assertFailsWith<IOException> {
                runBlocking {
                    context.documentService.deleteConfirmed(collectionId, listOf(deleted.id)) { instant ->
                        // The operation is durable from admission on, so its own record names the directory the
                        // phases park into.
                        val running = context.deletions.listUnfinished(DeletionKind.DOCUMENT).single()
                        operationId = running.id
                        trash = context.paths.trashDirectory(running.trashBasename)
                        if (instant == DeletionStep(DeletionPhase.INDEX_DELETED, recorded = true)) {
                            // The document the path led to, without a managed directory: a row whose bytes
                            // never landed is the documented crash window, and it is what lets the sweep
                            // finish its own work before the one step that cannot run this time.
                            val raced = context.library.importFile(collectionId, source).document
                            deleteRecursively(context.paths.documentDir(collectionId, raced.id))
                            swept = raced.id
                            // The parked-directory cleanup comes after the sweep, and it cannot run now.
                            Files.setPosixFilePermissions(trash, PosixFilePermissions.fromString("r-x------"))
                        }
                    }
                }
            }

            val interrupted = context.deletions.get(operationId!!)
            assertEquals(DeletionPhase.INDEX_DELETED, interrupted?.phase, "the sweep ran; the cleanup did not")
            assertEquals(
                2,
                interrupted?.targets?.size,
                "the sweep adopted the document the path led to, exactly once",
            )
            assertNull(context.documents.get(swept!!), "the swept document is already removed")
        }

        // Make the parked directory deletable again, as an operator resolving the state would, and let the
        // next process over this archive finish the deletion.
        Files.setPosixFilePermissions(trash!!, PosixFilePermissions.fromString("rwx------"))

        val resumed = operationId ?: error("the interrupted operation was not recorded")
        val racy = swept ?: error("the window never ran")
        val removed = target ?: error("the target was not recorded")

        AppContext.open(dataDir).use { reopened ->
            val finished = reopened.deletions.get(resumed)
            assertEquals(DeletionPhase.DONE, finished?.phase, "the restarted process finishes the deletion")
            assertEquals(
                setOf(removed.value, racy.value),
                finished?.targets?.map { it.documentId.value }?.toSet(),
                "the resumed sweep finds nothing to adopt: the record is not written twice",
            )
            assertNull(reopened.documents.get(removed), "the document the caller deleted is gone")
            assertNull(reopened.documents.get(racy), "the swept document stays removed across the resume")
            assertFalse(Files.exists(reopened.paths.documentDir(collectionId, racy)))
            assertEquals(0, reopened.index().rowCount(racy))
            assertTrue(reopened.documents.listByCollection(collectionId, limit = 10).isEmpty())
            assertTrue(parkedDirectories(reopened.paths).isEmpty(), "the resumed deletion purges what it parked")
        }
    }

    // ---- Helpers ----

    /**
     * One interruption-and-recovery cycle: stage two documents, kill a real child process at [stopAfter],
     * assert the state that kill left, then reopen and assert recovery finished the deletion without
     * touching anything else.
     */
    private fun assertRecovered(stopAfter: StopAfter, targets: Int = 1) {
        val staged = AppContext.open(dataDir).use { context -> stage(context, targets = targets) }

        terminateHarnessAt(stopAfter, staged)
        assertInterruptedState(stopAfter, staged)

        AppContext.open(dataDir).use { reopened ->
            assertTrue(
                reopened.deletionRecovery.blocked.isEmpty(),
                "recovery after a kill at $stopAfter must not be blocked: ${reopened.deletionRecovery.blocked}",
            )
            for (target in staged.targets) {
                assertNull(reopened.documents.get(target), "no document row may survive")
                assertFalse(Files.exists(reopened.paths.documentDir(staged.collectionId, target)))
                assertEquals(0, reopened.index().rowCount(target), "its index entries are gone")
            }
            assertEquals(staged.survivor, reopened.documents.get(staged.survivor)?.id)
            assertTrue(reopened.deletions.listUnfinished().isEmpty(), "the operation must reach DONE")
            assertTrue(parkedDirectories(reopened.paths).isEmpty(), "the parked directory must be purged")
            assertEquals(1, reopened.index().rowCount(staged.survivor), "the other document is still indexed")
            staged.targetSources.forEachIndexed { index, source ->
                assertEquals(
                    staged.targetTexts[index],
                    Files.readString(source),
                    "the user's own source file is never InfoScry's to remove",
                )
            }

            val again = runBlocking { reopened.documentService.recoverDeletions() }
            assertTrue(again.recovered.isEmpty(), "a recovered deletion is not pending work")
            assertTrue(again.blocked.isEmpty())
            assertTrue(parkedDirectories(reopened.paths).isEmpty())
        }
    }

    private data class Staged(
        val collectionId: CollectionId,
        val targets: List<DocumentId>,
        val survivor: DocumentId,
        val targetSources: List<Path>,
        val targetTexts: List<String>,
        val survivorSource: Path,
    ) {
        /** The one document a single-target case names. */
        val target: DocumentId get() = targets.first()

        /** The one external original a single-target case keeps by. */
        val targetSource: Path get() = targetSources.first()
    }

    /**
     * Managed documents with one indexed passage each — [targets] of them to delete together, plus one
     * survivor — so a deletion has something to remove and something beside it to leave alone.
     */
    private fun stage(context: AppContext, targets: Int = 1): Staged {
        val collectionId = CollectionId("default")
        val targetTexts = (1..targets).map { index -> "the target material" + if (targets == 1) "" else "-$index" }
        val targetSources = targetTexts.mapIndexed { index, text ->
            writeSource("target-${index + 1}-", text)
        }
        val survivorSource = writeSource("survivor", "the survivor material")
        val targetDocuments = targetSources.mapIndexed { index, source ->
            val imported = context.library.importFile(collectionId, source)
            val suffix = if (targets == 1) "" else "-${index + 1}"
            runBlocking {
                context.index().replaceDocument(rowsFor(collectionId, imported.document.id, "target-only$suffix"))
            }
            imported.document.id
        }
        val survivor = context.library.importFile(collectionId, survivorSource)
        runBlocking {
            context.index().replaceDocument(rowsFor(collectionId, survivor.document.id, "survivor"))
        }
        return Staged(
            collectionId,
            targetDocuments,
            survivor.document.id,
            targetSources,
            targetTexts,
            survivorSource,
        )
    }

    private fun stage(): Staged = AppContext.open(dataDir).use { context -> stage(context) }

    private fun rowsFor(collectionId: CollectionId, documentId: DocumentId, text: String): List<DocumentRow> {
        val unitId = ContentUnitId.new()
        return listOf(
            DocumentRow(
                collectionId = collectionId,
                documentId = documentId,
                unitId = unitId,
                chunk = Chunk(
                    id = ChunkId.new(),
                    contentUnitId = unitId,
                    ordinal = 0,
                    text = text,
                    startOffset = 0,
                    endOffset = text.length,
                    tokenCount = text.length,
                    tokenStart = 0,
                    tokenEnd = (text.length - 1).coerceAtLeast(0),
                ),
                locator = SourceLocation.TextLines(1, 1),
                locatorLabel = "line 1",
                vector = FloatArray(8) { 0.1f },
            ),
        )
    }

    private fun writeSource(name: String, content: String): Path {
        val directory = Files.createDirectories(dataDir.resolve("sources"))
        val source = directory.resolve("$name-${SOURCE_SEQUENCE.incrementAndGet()}.txt")
        Files.writeString(source, content)
        return source
    }

    /** Polls the durable record, because a deletion's phases run after the request that admitted it. */
    private suspend fun awaitDeletion(context: AppContext, operationId: String) = withTimeout(20_000) {
        while (true) {
            val operation = context.deletions.get(operationId) ?: error("the deletion record disappeared")
            if (operation.phase == DeletionPhase.DONE) return@withTimeout operation
            delay(10)
        }
        error("unreachable")
    }

    private fun deleteRecursively(directory: Path) {
        if (!Files.exists(directory)) return
        Files.walk(directory).use { entries ->
            entries.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    private fun terminateHarnessAt(stopAfter: StopAfter, staged: Staged) {
        val process = ProcessBuilder(
            javaExecutable(),
            "--enable-native-access=ALL-UNNAMED",
            "-cp",
            System.getProperty("java.class.path"),
            DocumentDeletionHarness::class.java.name,
            "--data-dir",
            dataDir.toString(),
            "--collection-id",
            staged.collectionId.value,
            "--document-ids",
            staged.targets.joinToString(",") { it.value },
            "--stop-after",
            stopAfter.name,
        ).redirectErrorStream(true).start()

        val output = java.util.Collections.synchronizedList(mutableListOf<String>())
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
            throw AssertionError("the harness never reached $stopAfter; output was:\n${output.joinToString("\n")}")
        }

        process.destroyForcibly()
        process.waitFor()
    }

    private fun javaExecutable(): String =
        Path.of(System.getProperty("java.home"), "bin", "java").toString()

    private fun openDeletionPhase(dataDir: Path, operationId: String): DeletionPhase =
        Database(dataDir.resolve("infoscry.db")).use { database ->
            DeletionStore(database).get(operationId)?.phase ?: error("the deletion record disappeared")
        }

    /**
     * The state a kill at [stopAfter] left on disk, read without opening the application so recovery has
     * not run yet: the phase the record still shows, and whether the target's managed directory is in
     * place, parked, or already purged.
     */
    private fun assertInterruptedState(stopAfter: StopAfter, staged: Staged) {
        val paths = AppPaths.from(dataDir)
        val operation = Database(paths.databaseFile).use { database ->
            DeletionStore(database).unfinishedFor(staged.collectionId, DeletionKind.DOCUMENT).singleOrNull()
                ?: error("no unfinished document deletion for collection ${staged.collectionId.value}")
        }

        assertEquals(
            stopAfter.recordedPhase,
            operation.phase,
            "a kill at $stopAfter must leave the deletion record at the phase the disk can still explain",
        )
        for (target in staged.targets) {
            assertEquals(
                stopAfter.managedDirectoryPresent,
                Files.exists(paths.documentDir(staged.collectionId, target)),
                "whether the target's managed directory was still in place after a kill at $stopAfter",
            )
        }
        assertEquals(
            stopAfter.parkedDirectoryPresent,
            Files.exists(paths.trashDirectory(operation.trashBasename)),
            "whether the target's managed directory was parked after a kill at $stopAfter",
        )
        assertEquals(
            staged.targets.map { it.value }.toSet(),
            operation.targets.map { it.documentId.value }.toSet(),
            "every selected target must be durable before any destructive step",
        )
    }

    /** The parked directories, which are the ones with the trash prefix inside `library/`. */
    private fun parkedDirectories(paths: AppPaths): List<Path> {
        if (!Files.isDirectory(paths.libraryDir)) return emptyList()
        return Files.list(paths.libraryDir).use { entries ->
            entries.filter { it.fileName.toString().startsWith(".deleted-") }.toList()
        }
    }

    private companion object {
        const val HARNESS_TIMEOUT_NANOS = 30_000_000_000L
        const val POLL_MILLIS = 10L

        /** Keeps each test's source file distinct so a shared directory cannot hide a mistake. */
        val SOURCE_SEQUENCE = java.util.concurrent.atomic.AtomicInteger()
    }
}

private typealias StopAfter = DocumentDeletionHarness.StopAfter
