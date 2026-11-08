package infoscry.jobs

import infoscry.AppContext
import infoscry.document.ImageRescanModeRequiredException
import infoscry.document.ImageRescanOutcome
import infoscry.document.ImageRescanStep
import infoscry.document.RescanRefusalException
import infoscry.domain.CollectionId
import infoscry.domain.DocumentId
import infoscry.domain.JobState
import infoscry.domain.JobType
import infoscry.image.ImageExtractionMode
import infoscry.ocr.ReadingMethod
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlinx.coroutines.runBlocking

/**
 * A collection-wide image rescan: one Keep current text rescan per document that can be read again from page images,
 * started at most once per job and document, never replacing a reading someone has running.
 */
class CollectionImageRescanTest {

    private val collection = CollectionId("default")

    @Test
    fun `a picture gets a keep current text rescan and a text document is skipped as an unsupported format`() {
        withHarness { harness ->
            val picture = harness.importPicture(text = "first reading")
            val notes = harness.importTextDocument()
            AppContext.open(harness.archiveDir).use { context ->
                val service = harness.rescanServiceOf(context, FakePageEngine())
                val started = runBlocking {
                    service.startImageRescan(collection, picture.documentId, ImageExtractionMode.EMBEDDED, "step-1").outcome
                }
                val skipped = runBlocking { service.startImageRescan(collection, notes, ImageExtractionMode.EMBEDDED, "step-2").outcome }

                assertEquals(ImageRescanOutcome.STARTED, started)
                assertEquals(ImageRescanOutcome.UNSUPPORTED_FORMAT, skipped)
                val operation = assertNotNull(context.ocrOperations.activeOperation(picture.documentId))
                assertEquals("step-1", operation.requestId)
                assertEquals(true, operation.snapshot.keepsText)
                assertEquals(ImageExtractionMode.EMBEDDED, operation.snapshot.imageExtraction)
                assertEquals(null, context.ocrOperations.activeOperation(notes))
            }
        }
    }

    @Test
    fun `repeating a step answers with the rescan it already started`() {
        withHarness { harness ->
            val picture = harness.importPicture(text = "first reading")
            AppContext.open(harness.archiveDir).use { context ->
                val service = harness.rescanServiceOf(context, FakePageEngine())
                val first = runBlocking { service.startImageRescan(collection, picture.documentId, ImageExtractionMode.ALL, "step").outcome }
                val operation = assertNotNull(context.ocrOperations.activeOperation(picture.documentId))
                val again = runBlocking { service.startImageRescan(collection, picture.documentId, ImageExtractionMode.ALL, "step").outcome }

                assertEquals(ImageRescanOutcome.STARTED, first)
                assertEquals(ImageRescanOutcome.ALREADY_STARTED, again)
                assertEquals(operation.operationId, context.ocrOperations.activeOperation(picture.documentId)?.operationId)
            }
        }
    }

    @Test
    fun `a document another reading is running on is left alone`() {
        withHarness { harness ->
            val picture = harness.importPicture(text = "first reading")
            AppContext.open(harness.archiveDir).use { context ->
                val running = harness.admitOnly(context, picture)
                val service = harness.rescanServiceOf(context, FakePageEngine())
                val outcome = runBlocking {
                    service.startImageRescan(collection, picture.documentId, ImageExtractionMode.EMBEDDED, "image-step").outcome
                }

                assertEquals(ImageRescanOutcome.BUSY, outcome)
                val active = assertNotNull(context.ocrOperations.activeOperation(picture.documentId))
                assertEquals(running.operationId, active.operationId)
            }
        }
    }

    @Test
    fun `the census counts eligible documents and why the others are left out`() {
        withHarness { harness ->
            harness.importPicture(text = "first reading")
            harness.importTextDocument()
            AppContext.open(harness.archiveDir).use { context ->
                val census = context.collectionImageRescanService.census(collection)

                assertEquals(1, census.eligible)
                assertEquals(mapOf("UNSUPPORTED_FORMAT" to 1), census.skipped)
                assertEquals(null, census.job)
            }
        }
    }

    @Test
    fun `starting twice answers with the running job and images off is refused`() {
        withHarness { harness ->
            harness.importPicture(text = "first reading")
            AppContext.open(harness.archiveDir).use { context ->
                val service = context.collectionImageRescanService
                val first = runBlocking { service.start(collection, ImageExtractionMode.EMBEDDED) }
                val second = runBlocking { service.start(collection, ImageExtractionMode.ALL) }

                assertEquals(first.id, second.id)
                assertEquals(JobType.IMAGE_RESCAN, first.type)
                assertEquals(ImageExtractionMode.EMBEDDED, ImageRescanJobPayload.decode(context.jobs.get(first.id)?.payload).mode)
                assertFailsWith<ImageRescanModeRequiredException> {
                    runBlocking { service.start(collection, ImageExtractionMode.OFF) }
                }
            }
        }
    }

    @Test
    fun `the job starts one step per document with stable request ids and a repeat starts nothing new`() {
        withHarness { harness ->
            val picture = harness.importPicture(text = "first reading")
            val notes = harness.importTextDocument()
            AppContext.open(harness.archiveDir).use { context ->
                val job = runBlocking { context.collectionImageRescanService.start(collection, ImageExtractionMode.EMBEDDED) }
                val claimed = assertNotNull(context.jobs.claimNextQueued(setOf(JobType.IMAGE_RESCAN)))
                val service = harness.rescanServiceOf(context, FakePageEngine())
                val calls = mutableListOf<Pair<DocumentId, String>>()
                val outcomes = mutableListOf<ImageRescanOutcome>()
                val handler = ImageRescanJobHandler(context.documents) { collectionId, documentId, mode, requestId, _ ->
                    calls += documentId to requestId
                    service.startImageRescan(collectionId, documentId, mode, requestId).also { outcomes += it.outcome }
                }
                val stage = JobStage(claimed.id, context.jobs, context.collections, context.mutations)

                runBlocking { handler.handle(claimed, stage) }
                runBlocking { handler.handle(claimed, stage) }

                assertEquals(job.id, claimed.id)
                assertEquals(setOf(picture.documentId, notes), calls.map { it.first }.toSet())
                assertEquals(
                    setOf("image-rescan:${job.id.value}:${picture.documentId.value}", "image-rescan:${job.id.value}:${notes.value}"),
                    calls.map { it.second }.toSet(),
                )
                assertEquals(
                    listOf(ImageRescanOutcome.STARTED, ImageRescanOutcome.ALREADY_STARTED),
                    outcomes.filterNot { it == ImageRescanOutcome.UNSUPPORTED_FORMAT },
                )
                val progressed = assertNotNull(context.jobs.get(job.id))
                assertEquals(2, progressed.completed)
                assertEquals(2, progressed.total)
                assertEquals(JobState.RUNNING, progressed.state)
                assertEquals(ReadingMethod.KeepText.id, "keep")
            }
        }
    }

    @Test
    fun `a document whose managed copy is gone is refused with the reason, not counted as an unsupported format`() {
        withHarness { harness ->
            val picture = harness.importPicture(text = "first reading")
            AppContext.open(harness.archiveDir).use { context ->
                context.paths.documentDir(collection, picture.documentId).toFile().deleteRecursively()
                val step = runBlocking {
                    harness.rescanServiceOf(context, FakePageEngine())
                        .startImageRescan(collection, picture.documentId, ImageExtractionMode.EMBEDDED, "step")
                }

                assertEquals(ImageRescanOutcome.REFUSED, step.outcome)
                assertEquals(RescanRefusalException.MANAGED_COPY_MISSING, step.refusal?.code)
            }
        }
    }

    @Test
    fun `a job in which every step is refused fails with the refusal, and one that started some names what it did`() {
        withHarness { harness ->
            harness.importPicture(text = "first reading")
            harness.importTextDocument()
            AppContext.open(harness.archiveDir).use { context ->
                val refusal = RescanRefusalException(RescanRefusalException.MANAGED_COPY_MISSING, "the managed copy is gone")
                val refusing = ImageRescanJobHandler(context.documents) { _, _, _, _, _ ->
                    ImageRescanStep(ImageRescanOutcome.REFUSED, refusal)
                }
                runBlocking { context.collectionImageRescanService.start(collection, ImageExtractionMode.EMBEDDED) }
                val claimed = assertNotNull(context.jobs.claimNextQueued(setOf(JobType.IMAGE_RESCAN)))
                val stage = JobStage(claimed.id, context.jobs, context.collections, context.mutations)

                val failure = assertFailsWith<ImageRescanFailedException> { runBlocking { refusing.handle(claimed, stage) } }
                assertEquals(RescanRefusalException.MANAGED_COPY_MISSING, failure.code)
                assertEquals("No document could be scanned for images. 2 documents refused: the managed copy is gone", failure.message)

                var first = true
                val mixed = ImageRescanJobHandler(context.documents) { _, _, _, _, _ ->
                    if (first) { first = false; ImageRescanStep(ImageRescanOutcome.STARTED) }
                    else ImageRescanStep(ImageRescanOutcome.UNSUPPORTED_FORMAT)
                }
                runBlocking { mixed.handle(claimed, stage) }
                assertEquals("1 document started, 1 document left out", context.jobs.get(claimed.id)?.currentItem)
            }
        }
    }

    @Test
    fun `the run counts the rescans it started, and cancelling stops them all once`() {
        withHarness { harness ->
            val first = harness.importPicture(text = "first reading")
            harness.importTextDocument()
            AppContext.open(harness.archiveDir).use { context ->
                val job = runBlocking { context.collectionImageRescanService.start(collection, ImageExtractionMode.EMBEDDED) }
                val claimed = assertNotNull(context.jobs.claimNextQueued(setOf(JobType.IMAGE_RESCAN)))
                val service = harness.rescanServiceOf(context, FakePageEngine())
                val handler = ImageRescanJobHandler(context.documents) { collectionId, documentId, mode, requestId, _ ->
                    service.startImageRescan(collectionId, documentId, mode, requestId)
                }
                runBlocking { handler.handle(claimed, JobStage(claimed.id, context.jobs, context.collections, context.mutations)) }

                val running = assertNotNull(context.collectionImageRescanService.run(collection))
                assertEquals(job.id.value, running.jobId)
                assertEquals(2, running.documents)
                assertEquals(2, running.examined)
                assertEquals(1, running.started)
                assertEquals(1, running.scanning + running.waiting)
                assertEquals(true, running.active)

                val stopped = assertNotNull(runBlocking { context.collectionImageRescanService.cancel(collection) })
                assertEquals(1, stopped.cancelled)
                assertEquals(0, stopped.scanning + stopped.waiting)
                assertEquals(null, context.ocrOperations.activeOperation(first.documentId))
                val again = assertNotNull(runBlocking { context.collectionImageRescanService.cancel(collection) })
                assertEquals(stopped.cancelled, again.cancelled)
            }
        }
    }

    private fun withHarness(block: (RescanHarness) -> Unit) {
        val directory = Files.createTempDirectory("infoscry-collection-image-rescan")
        try {
            RescanHarness(directory).use(block)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
