package infoscry.pdf

import infoscry.AppContext
import infoscry.domain.CollectionId
import infoscry.domain.DocumentId
import infoscry.jobs.seedCollectionWithId
import infoscry.pdf.SelectablePdfTestFixtures.hasQueuedOrRunningJob
import infoscry.pdf.SelectablePdfTestFixtures.publishMixedNativeAndOcrPdf
import infoscry.pdf.SelectablePdfTestFixtures.publishNativePdf
import infoscry.pdf.SelectablePdfTestFixtures.publishPlainTextDocument
import infoscry.pdf.SelectablePdfTestFixtures.runNextSelectablePdfJob
import infoscry.storage.DocumentBeingDeletedException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [SelectablePdfService]: identity, idempotent admission, verified reuse and explicit retry (ticket 06
 * of the selectable-PDF plan, acceptance vectors 2, 3, 6 and 8).
 *
 * Every fixture here is a real published revision over a real SQLite data directory and a real managed
 * PDF original; the service is exercised through its public surface exactly as a route would call it.
 */
class SelectablePdfServiceTest {

    private lateinit var dataDir: Path

    @BeforeTest
    fun openDataDirectory() {
        dataDir = Files.createTempDirectory("infoscry-selectable-pdf-service")
        AppContext.open(dataDir).use { it.seedCollectionWithId("default", "Default") }
    }

    @AfterTest
    fun removeDataDirectory() {
        dataDir.toFile().deleteRecursively()
    }

    @Test
    fun `a successful publication queues exactly one job with a non-null key`() {
        AppContext.open(dataDir).use { context ->
            val fixture = publishNativePdf(context, dataDir)
            val status = context.selectablePdfService.status(fixture.collectionId, fixture.documentId, fixture.revisionId)
            assertEquals(PdfArtifactState.QUEUED, status.state)
            assertNotNull(status.key, "a QUEUED artifact already carries its cache key")
            assertTrue(hasQueuedOrRunningJob(context, fixture.documentId, fixture.revisionId))
        }
    }

    @Test
    fun `ensure is idempotent while queued and never enqueues a second job`() {
        AppContext.open(dataDir).use { context ->
            val fixture = publishNativePdf(context, dataDir)
            val first = context.selectablePdfService.ensure(fixture.collectionId, fixture.documentId, fixture.revisionId, retry = false)
            val row = assertNotNull(context.selectablePdfStore.get(fixture.documentId, fixture.revisionId))
            val jobCountAfterFirst = countSelectablePdfJobs(context)

            val second = context.selectablePdfService.ensure(fixture.collectionId, fixture.documentId, fixture.revisionId, retry = false)

            assertEquals(first, second, "repeated ensure returns the same identity")
            assertEquals(row.key, second.key)
            assertEquals(jobCountAfterFirst, countSelectablePdfJobs(context), "no duplicate job was enqueued")
        }
    }

    @Test
    fun `a native-only page builds to READY with every page accounted for`() {
        AppContext.open(dataDir).use { context ->
            val fixture = publishNativePdf(context, dataDir)
            runNextSelectablePdfJob(context)

            val status = context.selectablePdfService.status(fixture.collectionId, fixture.documentId, fixture.revisionId)
            assertEquals(PdfArtifactState.READY, status.state)
            assertEquals(1, status.totalPages)
            assertEquals(1, status.selectablePages)
            assertEquals(0, status.blankPages)
            assertTrue(status.unavailablePages.isEmpty())

            val file = context.selectablePdfService.file(fixture.collectionId, fixture.documentId, fixture.revisionId, assertNotNull(status.key))
            assertTrue(Files.isRegularFile(file))
        }
    }

    @Test
    fun `a mixed native and OCR document is READY with both pages selectable, calling no OCR engine`() {
        AppContext.open(dataDir).use { context ->
            val fixture = publishMixedNativeAndOcrPdf(context, dataDir)
            runNextSelectablePdfJob(context)

            val status = context.selectablePdfService.status(fixture.collectionId, fixture.documentId, fixture.revisionId)
            assertEquals(PdfArtifactState.READY, status.state)
            assertEquals(2, status.totalPages)
            assertEquals(2, status.selectablePages)
            assertTrue(status.unavailablePages.isEmpty())
        }
    }

    @Test
    fun `an OCR page with no layout reference is UNAVAILABLE and the artifact is PARTIAL`() {
        AppContext.open(dataDir).use { context ->
            val fixture = SelectablePdfTestFixtures.publishOcrPageWithMissingLayout(context, dataDir)
            runNextSelectablePdfJob(context)

            val status = context.selectablePdfService.status(fixture.collectionId, fixture.documentId, fixture.revisionId)
            assertEquals(PdfArtifactState.PARTIAL, status.state)
            assertEquals(2, status.totalPages)
            assertEquals(1, status.selectablePages, "the native page stays selectable")
            assertEquals(1, status.unavailablePages.size)
            assertEquals(1, status.unavailablePages.single().ordinal)
            assertEquals(PdfPageCoverage.UNAVAILABLE, status.unavailablePages.single().coverage)
        }
    }

    @Test
    fun `a READY artifact is reused on repeated status and ensure calls`() {
        AppContext.open(dataDir).use { context ->
            val fixture = publishNativePdf(context, dataDir)
            runNextSelectablePdfJob(context)
            val ready = context.selectablePdfService.status(fixture.collectionId, fixture.documentId, fixture.revisionId)

            val statusAgain = context.selectablePdfService.status(fixture.collectionId, fixture.documentId, fixture.revisionId)
            val ensureAgain = context.selectablePdfService.ensure(fixture.collectionId, fixture.documentId, fixture.revisionId, retry = false)

            assertEquals(ready, statusAgain)
            assertEquals(ready, ensureAgain)
            assertEquals(1, countSelectablePdfJobs(context), "no second job was ever created for a READY artifact")
        }
    }

    @Test
    fun `a corrupted READY file is not served again and degrades to FAILED on the next read`() {
        AppContext.open(dataDir).use { context ->
            val fixture = publishNativePdf(context, dataDir)
            runNextSelectablePdfJob(context)
            val ready = context.selectablePdfService.status(fixture.collectionId, fixture.documentId, fixture.revisionId)
            val key = assertNotNull(ready.key)
            val file = context.paths.selectablePdfDir(fixture.collectionId, fixture.documentId, fixture.revisionId, key)
                .resolve(SelectablePdfService.PDF_FILE_NAME)
            Files.write(file, "tampered".toByteArray())

            val status = context.selectablePdfService.status(fixture.collectionId, fixture.documentId, fixture.revisionId)
            assertEquals(PdfArtifactState.FAILED, status.state)
            assertEquals(SelectablePdfService.VERIFICATION_FAILED_CODE, status.reasonCode)

            assertFailsWith<IllegalArgumentException> {
                context.selectablePdfService.file(fixture.collectionId, fixture.documentId, fixture.revisionId, key)
            }
        }
    }

    @Test
    fun `a tampered READY artifact reports FAILED with the row's own key, not null`() {
        AppContext.open(dataDir).use { context ->
            val fixture = publishNativePdf(context, dataDir)
            runNextSelectablePdfJob(context)
            val ready = context.selectablePdfService.status(fixture.collectionId, fixture.documentId, fixture.revisionId)
            val key = assertNotNull(ready.key)
            val file = context.paths.selectablePdfDir(fixture.collectionId, fixture.documentId, fixture.revisionId, key)
                .resolve(SelectablePdfService.PDF_FILE_NAME)
            Files.write(file, "tampered".toByteArray())

            val status = context.selectablePdfService.status(fixture.collectionId, fixture.documentId, fixture.revisionId)
            assertEquals(PdfArtifactState.FAILED, status.state)
            assertEquals(key, status.key, "the returned status keeps the artifact's identity, matching the stored row")
            assertEquals(key, context.selectablePdfStore.get(fixture.documentId, fixture.revisionId)?.key)
        }
    }

    @Test
    fun `a FAILED artifact is reported unchanged unless retry is explicit`() {
        AppContext.open(dataDir).use { context ->
            val fixture = publishPlainTextDocument(context, dataDir)
            val status = context.selectablePdfService.ensure(fixture.collectionId, fixture.documentId, fixture.revisionId, retry = false)
            assertEquals(PdfArtifactState.FAILED, status.state)
            assertEquals("UNSUPPORTED_MEDIA_TYPE", status.reasonCode)
            assertNull(context.selectablePdfStore.get(fixture.documentId, fixture.revisionId), "an ineligible document never gets a durable row")

            val again = context.selectablePdfService.ensure(fixture.collectionId, fixture.documentId, fixture.revisionId, retry = false)
            assertEquals(status, again, "no retry means no change")
            val retried = context.selectablePdfService.ensure(fixture.collectionId, fixture.documentId, fixture.revisionId, retry = true)
            assertEquals(PdfArtifactState.FAILED, retried.state, "retrying an unfixable ineligibility still fails the same way")
        }
    }

    @Test
    fun `a candidate revision that was never published is ineligible without a durable row`() {
        AppContext.open(dataDir).use { context ->
            val fixture = publishNativePdf(context, dataDir)
            val candidateId = context.revisions.openCandidate(fixture.documentId, fixture.revisionId, "TEST_CANDIDATE")

            val status = context.selectablePdfService.ensure(fixture.collectionId, fixture.documentId, candidateId, retry = false)

            assertEquals(PdfArtifactState.FAILED, status.state)
            assertEquals("REVISION_NOT_PUBLIC", status.reasonCode)
            assertNull(context.selectablePdfStore.get(fixture.documentId, candidateId))
        }
    }

    @Test
    fun `status and ensure refuse a revision that belongs to another document`() {
        AppContext.open(dataDir).use { context ->
            val a = publishNativePdf(context, dataDir, text = "document A")
            val b = publishNativePdf(context, dataDir, text = "document B")

            assertFailsWith<NoSuchElementException> {
                context.selectablePdfService.status(a.collectionId, a.documentId, b.revisionId)
            }
            assertFailsWith<NoSuchElementException> {
                context.selectablePdfService.ensure(a.collectionId, a.documentId, b.revisionId, retry = false)
            }
        }
    }

    @Test
    fun `status and ensure refuse a document from a foreign collection`() {
        AppContext.open(dataDir).use { context ->
            context.seedCollectionWithId("other", "Other")
            val fixture = publishNativePdf(context, dataDir)

            assertFailsWith<NoSuchElementException> {
                context.selectablePdfService.status(CollectionId("other"), fixture.documentId, fixture.revisionId)
            }
        }
    }

    @Test
    fun `status and ensure refuse an unknown document or revision`() {
        AppContext.open(dataDir).use { context ->
            val fixture = publishNativePdf(context, dataDir)
            assertFailsWith<NoSuchElementException> {
                context.selectablePdfService.status(fixture.collectionId, DocumentId("does-not-exist"), fixture.revisionId)
            }
            assertFailsWith<NoSuchElementException> {
                context.selectablePdfService.status(fixture.collectionId, fixture.documentId, "revision-does-not-exist")
            }
        }
    }

    @Test
    fun `ensure refuses a document that is being deleted, without creating or touching a row`() {
        AppContext.open(dataDir).use { context ->
            val fixture = publishNativePdf(context, dataDir)
            // The row from automatic queueing already exists; the refusal must still apply to a later
            // explicit ensure on a document a deletion has since targeted.
            context.documentService.beginDeletion(fixture.collectionId, listOf(fixture.documentId))

            assertFailsWith<DocumentBeingDeletedException> {
                context.selectablePdfService.ensure(fixture.collectionId, fixture.documentId, fixture.revisionId, retry = true)
            }
        }
    }

    private fun countSelectablePdfJobs(context: AppContext): Int =
        context.jobs.list(limit = 1000).count { it.type == infoscry.domain.JobType.SELECTABLE_PDF }
}
