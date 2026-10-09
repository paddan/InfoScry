package infoscry.jobs

import infoscry.AppContext
import infoscry.domain.CollectionId
import infoscry.domain.DocumentStatus
import infoscry.ocr.OcrEngine
import infoscry.ocr.OcrPageResult
import infoscry.ocr.OcrSettingsSnapshot
import infoscry.ocr.PageImage
import infoscry.ocr.PageOcrEngine
import infoscry.ocr.ReadingMethod
import infoscry.document.RescanService
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

class RescanAdmissionStatusTest {

    @Test
    fun `admission queues a completed document and an identical replay does not change its status`() {
        val directory = Files.createTempDirectory("infoscry-rescan-admission-status")
        RescanHarness(directory).use { harness ->
            val picture = harness.importPicture("published text")
            AppContext.open(harness.archiveDir).use { context ->
                val collectionId = CollectionId("default")
                val service = service(context)
                val preview = runBlocking {
                    service.preview(collectionId, picture.documentId, ReadingMethod.Surya)
                }

                val admitted = runBlocking {
                    service.admitRescan(
                        collectionId = CollectionId("default"),
                        documentId = picture.documentId,
                        previewId = preview.previewId,
                        requestId = "admission-status",
                    )
                }

                assertEquals(DocumentStatus.QUEUED, context.documents.get(picture.documentId)?.status)
                context.documents.updateStatus(picture.documentId, DocumentStatus.COMPLETE)
                val replay = runBlocking {
                    service.admitRescan(
                        collectionId = CollectionId("default"),
                        documentId = picture.documentId,
                        previewId = preview.previewId,
                        requestId = "admission-status",
                    )
                }

                assertEquals(admitted.operationId, replay.operationId)
                assertEquals(admitted.jobId, replay.jobId)
                assertEquals(DocumentStatus.COMPLETE, context.documents.get(picture.documentId)?.status)
            }
        }
        directory.toFile().deleteRecursively()
    }

    private fun service(context: AppContext) = RescanService(
        paths = context.paths,
        collections = context.collections,
        documents = context.documents,
        revisions = context.revisions,
        jobs = context.jobs,
        operations = context.ocrOperations,
        mutations = context.mutations,
        blockers = context.blockers,
        publication = context.revisionPublication,
        profileOf = context.ocrProfiles::findById,
        profileRevisionOf = context.ocrProfiles::findRevision,
        engines = { engine, _, _ -> if (engine == OcrEngine.SURYA) previewOnlyEngine else null },
        embedderAvailable = { true },
    )

    private companion object {
        val previewOnlyEngine = object : PageOcrEngine {
            override val engine = OcrEngine.SURYA
            override suspend fun transcribe(page: PageImage, settings: OcrSettingsSnapshot): OcrPageResult =
                error("admission status tests do not read a page")
        }
    }
}
