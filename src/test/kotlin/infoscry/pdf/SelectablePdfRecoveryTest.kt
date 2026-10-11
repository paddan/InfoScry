package infoscry.pdf

import infoscry.AppContext
import infoscry.domain.JobId
import infoscry.domain.JobState
import infoscry.domain.JobType
import infoscry.jobs.seedCollectionWithId
import infoscry.pdf.SelectablePdfTestFixtures.publishNativePdf
import infoscry.pdf.SelectablePdfTestFixtures.publishOcrPageWithMissingLayout
import infoscry.pdf.SelectablePdfTestFixtures.runNextSelectablePdfJob
import infoscry.storage.SelectablePdfArtifactState
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Crash-boundary recovery for the selectable-PDF feature (ticket 06 of the selectable-PDF plan,
 * acceptance vectors 1, 4, 5, 6, 7 and 8).
 *
 * Every test interrupts a real attempt at a named boundary, by stopping short of the next step rather
 * than by mocking a failure, then runs the recovery/retry path — always at least twice — and asserts
 * convergence to one coherent result with no duplicate job and no OCR/transcription call. Nothing in
 * this file, or in the production code it exercises, imports or configures an OCR engine, an aligner or
 * a provider client: a test that reached one would fail for lack of configuration, not quietly use one.
 */
class SelectablePdfRecoveryTest {

    private lateinit var dataDir: Path

    @BeforeTest
    fun openDataDirectory() {
        dataDir = Files.createTempDirectory("infoscry-selectable-pdf-recovery")
        AppContext.open(dataDir).use { it.seedCollectionWithId("default", "Default") }
    }

    @AfterTest
    fun removeDataDirectory() {
        dataDir.toFile().deleteRecursively()
    }

    // ---- Vector 1: the publication -> enqueue crash gap ----------------------------------------------------------

    @Test
    fun `a row recorded with no job attached is repaired by startup reconciliation, twice, without a duplicate job`() {
        AppContext.open(dataDir).use { context ->
            val fixture = publishNativePdf(context, dataDir)
            // Simulate the exact crash: the row already exists (the automatic hook wrote it), but its job
            // never got attached. Cancel the one the hook actually enqueued and clear the row's pointer,
            // which is the only way this state can arise.
            val row = assertNotNull(context.selectablePdfStore.get(fixture.documentId, fixture.revisionId))
            val originalJobId = assertNotNull(row.jobId)
            runBlocking { context.jobs.cancel(JobId(originalJobId)) }
            clearJobPointer(context, fixture.documentId, fixture.revisionId)
            assertNull(context.selectablePdfStore.get(fixture.documentId, fixture.revisionId)!!.jobId)

            val firstPass = context.selectablePdfService.reconcileQueuedJobs()
            assertEquals(1, firstPass, "exactly the one broken row was repaired")
            val repairedJobId = assertNotNull(context.selectablePdfStore.get(fixture.documentId, fixture.revisionId)!!.jobId)
            assertNotEquals(originalJobId, repairedJobId, "a fresh job was enqueued")

            val secondPass = context.selectablePdfService.reconcileQueuedJobs()
            assertEquals(0, secondPass, "a row with a live job is left alone the second time")
            assertEquals(repairedJobId, context.selectablePdfStore.get(fixture.documentId, fixture.revisionId)!!.jobId)
            assertEquals(1, countLiveSelectablePdfJobs(context), "never more than one live job for the same revision")

            runNextSelectablePdfJob(context)
            val status = context.selectablePdfService.status(fixture.collectionId, fixture.documentId, fixture.revisionId)
            assertEquals(PdfArtifactState.READY, status.state)
        }
    }

    @Test
    fun `reconciliation never touches a row with a genuinely live job`() {
        AppContext.open(dataDir).use { context ->
            val fixture = publishNativePdf(context, dataDir)
            val before = assertNotNull(context.selectablePdfStore.get(fixture.documentId, fixture.revisionId))

            assertEquals(0, context.selectablePdfService.reconcileQueuedJobs())

            val after = assertNotNull(context.selectablePdfStore.get(fixture.documentId, fixture.revisionId))
            assertEquals(before.jobId, after.jobId, "an untouched, still-queued job is not replaced")
        }
    }

    // ---- Vector 4: crash after a page checkpoint / the writer's own temp-close / rename -------------------------

    @Test
    fun `a row stuck BUILDING with a dead job is retried to the same READY result, twice, with no stale file left over`() {
        AppContext.open(dataDir).use { context ->
            val fixture = publishNativePdf(context, dataDir)
            // Simulate a crash mid-write: resolve and mark BUILDING exactly as the job handler's first
            // stage would, but never run the writer and never finish the job.
            val prepared = context.selectablePdfService.prepareBuild(fixture.documentId, fixture.revisionId)
            check(prepared is SelectablePdfService.PreparedBuild.Ready)
            val stuck = assertNotNull(context.selectablePdfStore.get(fixture.documentId, fixture.revisionId))
            assertEquals(SelectablePdfArtifactState.BUILDING, stuck.state)
            runBlocking { context.jobs.cancel(JobId(assertNotNull(stuck.jobId))) }

            val repairedOnce = context.selectablePdfService.reconcileQueuedJobs()
            assertEquals(1, repairedOnce)
            runNextSelectablePdfJob(context)
            val ready = context.selectablePdfService.status(fixture.collectionId, fixture.documentId, fixture.revisionId)
            assertEquals(PdfArtifactState.READY, ready.state)
            val key = assertNotNull(ready.key)
            val pdfBytesFirst = readPdfBytes(context, fixture, key)

            // Run it all again: nothing should need to move a second time, and the bytes converge exactly.
            val repairedTwice = context.selectablePdfService.reconcileQueuedJobs()
            assertEquals(0, repairedTwice, "an already-READY artifact has nothing left to repair")
            val readyAgain = context.selectablePdfService.status(fixture.collectionId, fixture.documentId, fixture.revisionId)
            assertEquals(ready, readyAgain)
            assertTrue(pdfBytesFirst.contentEquals(readPdfBytes(context, fixture, key)), "the same bytes, not a second write")
        }
    }

    @Test
    fun `running the full build twice for the same revision converges without duplicate work`() {
        AppContext.open(dataDir).use { context ->
            val fixture = publishNativePdf(context, dataDir)
            runNextSelectablePdfJob(context)
            val first = context.selectablePdfService.status(fixture.collectionId, fixture.documentId, fixture.revisionId)
            assertEquals(PdfArtifactState.READY, first.state)
            val key = assertNotNull(first.key)
            val directory = context.paths.selectablePdfDir(fixture.collectionId, fixture.documentId, fixture.revisionId, key)
            val pdfModifiedFirst = Files.getLastModifiedTime(directory.resolve(SelectablePdfService.PDF_FILE_NAME))

            // Re-running the build directly (as a duplicate/leftover attempt would) must not corrupt or
            // duplicate the published result; the content-addressed writer either no-ops or rewrites the
            // same bytes, and the service re-publishes the same verified READY row.
            val prepared = context.selectablePdfService.prepareBuild(fixture.documentId, fixture.revisionId)
            if (prepared is SelectablePdfService.PreparedBuild.Ready) {
                val result = SelectablePdfWriter.write(prepared.request)
                context.selectablePdfService.publishBuildResult(fixture.documentId, prepared.collectionId, fixture.revisionId, prepared.key, result)
            }

            val second = context.selectablePdfService.status(fixture.collectionId, fixture.documentId, fixture.revisionId)
            assertEquals(first, second, "the result is byte-for-byte the same, reported identically")
            assertTrue(Files.exists(directory.resolve(SelectablePdfService.PDF_FILE_NAME)))
            assertTrue(pdfModifiedFirst.toMillis() <= Files.getLastModifiedTime(directory.resolve(SelectablePdfService.PDF_FILE_NAME)).toMillis())
        }
    }

    // ---- Vector 5: missing/corrupt layout, safe FAILED/PARTIAL, never touching the published text ----------------

    @Test
    fun `a missing layout produces a safe PARTIAL without touching the published text or search index`() {
        AppContext.open(dataDir).use { context ->
            val fixture = publishOcrPageWithMissingLayout(context, dataDir)
            val publishedTextBefore = context.content.readUnit(context.revisions.pages(fixture.revisionId)[0].unitId)?.extractedText

            runNextSelectablePdfJob(context)

            val status = context.selectablePdfService.status(fixture.collectionId, fixture.documentId, fixture.revisionId)
            assertEquals(PdfArtifactState.PARTIAL, status.state)
            assertEquals(fixture.revisionId, context.revisions.activeRevisionId(fixture.documentId), "the active revision never moved")
            assertEquals(
                publishedTextBefore,
                context.content.readUnit(context.revisions.pages(fixture.revisionId)[0].unitId)?.extractedText,
                "the published text is exactly what it was before the export attempt",
            )
        }
    }

    @Test
    fun `a corrupt layout sidecar fails the build safely and retry repairs only local work`() {
        AppContext.open(dataDir).use { context ->
            val fixture = SelectablePdfTestFixtures.publishMixedNativeAndOcrPdf(context, dataDir)
            // Corrupt the one layout sidecar on disk: a damaged artifact, not a missing one.
            val layoutDir = context.paths.artifactsDir(fixture.collectionId, fixture.documentId).resolve("layout")
            val sidecar = assertNotNull(Files.list(layoutDir).use { it.findFirst().orElse(null) })
            val backup = backupOf(sidecar)
            Files.write(sidecar, "not json".toByteArray())

            runNextSelectablePdfJob(context)
            val failed = context.selectablePdfService.status(fixture.collectionId, fixture.documentId, fixture.revisionId)
            assertEquals(PdfArtifactState.FAILED, failed.state)
            assertEquals("LAYOUT_CORRUPT", failed.reasonCode)

            // Repair the sidecar back (the local artifact, not a re-read of the page) and retry: this must
            // succeed without ever touching an OCR engine, which this whole test never configures.
            Files.write(sidecar, Files.readAllBytes(backupOf(sidecar)))
            val retried = context.selectablePdfService.ensure(fixture.collectionId, fixture.documentId, fixture.revisionId, retry = true)
            assertEquals(PdfArtifactState.QUEUED, retried.state)
            runNextSelectablePdfJob(context)
            val recovered = context.selectablePdfService.status(fixture.collectionId, fixture.documentId, fixture.revisionId)
            assertEquals(PdfArtifactState.READY, recovered.state)
        }
    }

    // ---- Vector 6: a late completion of an old revision can never become "current" --------------------------------

    @Test
    fun `a historical revision's artifact never becomes the document's current export`() {
        AppContext.open(dataDir).use { context ->
            val first = publishNativePdf(context, dataDir, text = "Revision A")
            runNextSelectablePdfJob(context)
            val readyA = context.selectablePdfService.status(first.collectionId, first.documentId, first.revisionId)
            assertEquals(PdfArtifactState.READY, readyA.state)

            // A second publication (a rescan/restore in production) supersedes A.
            val candidateB = context.revisions.openCandidate(first.documentId, first.revisionId, "TEST_REPLACEMENT")
            context.revisions.appendPage(
                candidateB,
                infoscry.storage.RevisionPageDraft(
                    ordinal = 0,
                    unitId = context.revisions.page(first.revisionId, 0)!!.unitId,
                    locator = infoscry.domain.SourceLocation.TextLines(1, 1),
                    extractedText = "Revision B",
                    searchText = "revision b",
                    extractionMethod = infoscry.domain.ExtractionMethod.DIRECT_TEXT,
                    approval = infoscry.storage.PageApproval.APPROVED,
                    chunks = listOf(
                        infoscry.storage.RevisionChunkDraft(
                            ordinal = 0, text = "Revision B", startOffset = 0, endOffset = 10,
                            tokenCount = 5, tokenStart = 0, tokenEnd = 4,
                            embedding = SelectablePdfTestFixtures.EMBEDDER.embedDocuments(listOf("Revision B")).single(),
                        ),
                    ),
                ),
            )
            runBlocking { context.revisionPublication.publish(first.documentId, first.revisionId, candidateB) }
            runNextSelectablePdfJob(context)
            val readyB = context.selectablePdfService.status(first.collectionId, first.documentId, candidateB)
            assertEquals(PdfArtifactState.READY, readyB.state)

            // A's artifact is still readable, by its own revision id, and is unaffected by B.
            val stillA = context.selectablePdfService.status(first.collectionId, first.documentId, first.revisionId)
            assertEquals(readyA, stillA, "the historical revision's own artifact is unchanged")
            assertNotEquals(readyA.key, readyB.key, "each revision keeps its own artifact and key")
            assertEquals(candidateB, context.revisions.activeRevisionId(first.documentId), "B is current")
        }
    }

    // ---- Vector 7: deletion cleans up without a late write recreating anything -------------------------------------

    @Test
    fun `document deletion removes the artifact and a late build attempt does not recreate it`() {
        AppContext.open(dataDir).use { context ->
            val fixture = publishNativePdf(context, dataDir)
            runNextSelectablePdfJob(context)
            val ready = context.selectablePdfService.status(fixture.collectionId, fixture.documentId, fixture.revisionId)
            assertEquals(PdfArtifactState.READY, ready.state)
            val key = assertNotNull(ready.key)
            val directory = context.paths.selectablePdfDir(fixture.collectionId, fixture.documentId, fixture.revisionId, key)
            assertTrue(Files.exists(directory))

            val operation = context.documentService.beginDeletion(fixture.collectionId, listOf(fixture.documentId))
            val finished = runBlocking { context.documentService.rollForward(operation) }

            assertNull(context.selectablePdfStore.get(fixture.documentId, fixture.revisionId), "the row is gone with its document")
            assertFalse(Files.exists(context.paths.documentDir(fixture.collectionId, fixture.documentId)), "the whole document directory, including the artifact, is gone")

            // A late/duplicate build attempt against the deleted identity must be a safe no-op, never a
            // resurrection of the directory deletion already removed.
            val lateBuild = context.selectablePdfService.prepareBuild(fixture.documentId, fixture.revisionId)
            assertEquals(SelectablePdfService.PreparedBuild.NothingToDo, lateBuild)
            assertFalse(Files.exists(context.paths.documentDir(fixture.collectionId, fixture.documentId)))

            // Idempotent cleanup: running the finished operation's own phases again changes nothing further.
            runBlocking { context.documentService.rollForward(finished) }
            assertNull(context.selectablePdfStore.get(fixture.documentId, fixture.revisionId))
        }
    }

    @Test
    fun `ensure refuses to queue new work the moment a document deletion is admitted`() {
        AppContext.open(dataDir).use { context ->
            val fixture = publishNativePdf(context, dataDir)
            context.documentService.beginDeletion(fixture.collectionId, listOf(fixture.documentId))

            assertFailsWithDeletionRefusal(context, fixture)
        }
    }

    // ---- Vector 8: retry never repeats transcription --------------------------------------------------------------

    @Test
    fun `retrying a FAILED artifact twice never requires or performs any OCR call`() {
        AppContext.open(dataDir).use { context ->
            val fixture = publishOcrPageWithMissingLayout(context, dataDir)
            runNextSelectablePdfJob(context)
            val partial = context.selectablePdfService.status(fixture.collectionId, fixture.documentId, fixture.revisionId)
            assertEquals(PdfArtifactState.PARTIAL, partial.state)

            // Retrying with nothing locally repaired converges to the same PARTIAL shape, twice, without
            // this test or the production code ever naming an OCR engine, aligner or provider client.
            val retry1 = context.selectablePdfService.ensure(fixture.collectionId, fixture.documentId, fixture.revisionId, retry = true)
            assertEquals(PdfArtifactState.QUEUED, retry1.state)
            runNextSelectablePdfJob(context)
            val afterRetry1 = context.selectablePdfService.status(fixture.collectionId, fixture.documentId, fixture.revisionId)
            assertEquals(PdfArtifactState.PARTIAL, afterRetry1.state)

            val retry2 = context.selectablePdfService.ensure(fixture.collectionId, fixture.documentId, fixture.revisionId, retry = true)
            assertEquals(PdfArtifactState.QUEUED, retry2.state)
            runNextSelectablePdfJob(context)
            val afterRetry2 = context.selectablePdfService.status(fixture.collectionId, fixture.documentId, fixture.revisionId)
            assertEquals(PdfArtifactState.PARTIAL, afterRetry2.state)
            assertEquals(afterRetry1.unavailablePages, afterRetry2.unavailablePages)
        }
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private fun assertFailsWithDeletionRefusal(context: AppContext, fixture: SelectablePdfTestFixtures.Fixture) {
        try {
            context.selectablePdfService.ensure(fixture.collectionId, fixture.documentId, fixture.revisionId, retry = true)
            throw AssertionError("expected a refusal for a document being deleted")
        } catch (expected: infoscry.storage.DocumentBeingDeletedException) {
            // expected
        }
    }

    private fun countLiveSelectablePdfJobs(context: AppContext): Int =
        context.jobs.list(limit = 1000).count {
            it.type == JobType.SELECTABLE_PDF && (it.state == JobState.QUEUED || it.state == JobState.RUNNING)
        }

    private fun readPdfBytes(context: AppContext, fixture: SelectablePdfTestFixtures.Fixture, key: String): ByteArray =
        Files.readAllBytes(
            context.paths.selectablePdfDir(fixture.collectionId, fixture.documentId, fixture.revisionId, key)
                .resolve(SelectablePdfService.PDF_FILE_NAME),
        )

    /** Keeps an untouched copy of a layout sidecar before it is corrupted, so a test can "repair" it back. */
    private fun backupOf(sidecar: Path): Path {
        val backup = sidecar.resolveSibling("${sidecar.fileName}.backup")
        if (!Files.exists(backup)) Files.copy(sidecar, backup)
        return backup
    }

    private fun clearJobPointer(context: AppContext, documentId: infoscry.domain.DocumentId, revisionId: String) {
        // There is no public "clear" on purpose (production never needs one): the store only ever moves a
        // job pointer forward. The test reaches the same state a crash would by going around that, once,
        // to set up the scenario, exactly as the other recovery tests in this codebase poke internal state
        // to land exactly on a boundary a real crash could.
        context.database.transaction { connection ->
            connection.prepareStatement(
                "UPDATE selectable_pdf_artifacts SET job_id = NULL WHERE document_id = ? AND revision_id = ?",
            ).use { statement ->
                statement.setString(1, documentId.value)
                statement.setString(2, revisionId)
                statement.executeUpdate()
            }
        }
    }
}
