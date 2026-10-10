package infoscry.jobs

import infoscry.AppContext
import infoscry.domain.CollectionId
import infoscry.domain.DocumentStatus
import infoscry.document.RescanRefusalException
import infoscry.document.RescanService
import infoscry.llm.LlmProvider
import infoscry.ocr.OcrEngine
import infoscry.ocr.OcrPageResult
import infoscry.ocr.OcrProfileRevisionDraft
import infoscry.ocr.OcrSettingsSnapshot
import infoscry.ocr.PageImage
import infoscry.ocr.PageOcrEngine
import infoscry.ocr.ReadingMethod
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
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

    @Test
    fun `a local keyless OCR profile can be previewed and admitted`() {
        val directory = Files.createTempDirectory("infoscry-rescan-local-keyless")
        RescanHarness(directory).use { harness ->
            val picture = harness.importPicture("published text")
            AppContext.open(harness.archiveDir).use { context ->
                val profile = context.ocrProfiles.create(
                    "Local keyless",
                    OcrProfileRevisionDraft(
                        provider = LlmProvider.OPENAI_COMPATIBLE,
                        endpoint = "http://127.0.0.1:11434",
                        model = "local-vision",
                        contextWindow = 32_000,
                        maxOutputTokens = 4_096,
                        apiKeyEnvironmentVariable = null,
                    ),
                    enabled = true,
                )
                context.ocrProfiles.recordImageCapability(profile.revision.revisionId, true, "2026-10-10T10:00:00Z")
                val service = service(context, keyAvailable = { it != null })
                val preview = runBlocking {
                    service.preview(CollectionId("default"), picture.documentId, ReadingMethod.Llm(profile.id))
                }

                runBlocking {
                    service.admitRescan(CollectionId("default"), picture.documentId, preview.previewId, "local-keyless")
                }
                assertEquals(DocumentStatus.QUEUED, context.documents.get(picture.documentId)?.status)
            }
        }
        directory.toFile().deleteRecursively()
    }

    @Test
    fun `an external OCR profile with no available key is refused at admission`() {
        val directory = Files.createTempDirectory("infoscry-rescan-external-keyless")
        RescanHarness(directory).use { harness ->
            val picture = harness.importPicture("published text")
            AppContext.open(harness.archiveDir).use { context ->
                val profile = context.ocrProfiles.create(
                    "External keyless",
                    OcrProfileRevisionDraft(
                        provider = LlmProvider.OPENAI_COMPATIBLE,
                        endpoint = "https://api.example/v1",
                        model = "external-vision",
                        contextWindow = 32_000,
                        maxOutputTokens = 4_096,
                        apiKeyEnvironmentVariable = null,
                    ),
                    enabled = true,
                )
                context.ocrProfiles.recordImageCapability(profile.revision.revisionId, true, "2026-10-10T10:00:00Z")
                var credentialAvailable = true
                val service = service(context, keyAvailable = { credentialAvailable })
                val preview = runBlocking {
                    service.preview(CollectionId("default"), picture.documentId, ReadingMethod.Llm(profile.id))
                }
                credentialAvailable = false

                val failure = assertFailsWith<RescanRefusalException> {
                    runBlocking {
                        service.admitRescan(
                            CollectionId("default"), picture.documentId, preview.previewId, "external-keyless",
                        )
                    }
                }
                assertEquals(RescanRefusalException.PROFILE_UNAVAILABLE, failure.code)
                assertContains(failure.message.orEmpty(), "environment variable")
                assertEquals(DocumentStatus.COMPLETE, context.documents.get(picture.documentId)?.status)
            }
        }
        directory.toFile().deleteRecursively()
    }

    private fun service(
        context: AppContext,
        keyAvailable: (String?) -> Boolean = { variable -> variable != null && System.getenv(variable)?.isNotBlank() == true },
    ) = RescanService(
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
        engines = { engine, _, _ -> previewOnlyEngine(engine) },
        embedderAvailable = { true },
        keyAvailable = keyAvailable,
    )

    private companion object {
        fun previewOnlyEngine(engine: OcrEngine) = object : PageOcrEngine {
            override val engine = engine
            override suspend fun transcribe(page: PageImage, settings: OcrSettingsSnapshot): OcrPageResult =
                error("admission status tests do not read a page")
        }
    }
}
