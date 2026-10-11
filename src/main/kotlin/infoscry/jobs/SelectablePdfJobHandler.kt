package infoscry.jobs

import infoscry.domain.DocumentId
import infoscry.domain.Job
import infoscry.pdf.SelectablePdfJobPayload
import infoscry.pdf.SelectablePdfService
import infoscry.pdf.SelectablePdfWriteException
import infoscry.pdf.SelectablePdfWriter
import kotlinx.serialization.json.Json

/**
 * Runs one attempt of a [infoscry.domain.JobType.SELECTABLE_PDF] job (ticket 06 of the selectable-PDF
 * plan).
 *
 * The slow part — [SelectablePdfWriter.write] — runs outside any [infoscry.jobs.JobStage] permit, per
 * the handler contract: it can take a while for a large document, and nothing else in the process needs
 * to wait for it. Every durable write goes through [infoscry.jobs.JobStage.run], one call to
 * [infoscry.pdf.SelectablePdfService] at a time, which is also where each attempt notices a cancellation
 * request or a document that started being deleted while it was reading or writing.
 *
 * Nothing here calls an OCR engine, an aligner or any provider: every input
 * [infoscry.pdf.SelectablePdfService.prepareBuild] reads is already-committed revision text, images and
 * layouts, so a retry of a FAILED/PARTIAL artifact can only repeat this local work, never transcription.
 */
class SelectablePdfJobHandler(private val service: SelectablePdfService) : JobHandler {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun handle(job: Job, stage: JobStage) {
        val payload = job.payload?.let { json.decodeFromString(SelectablePdfJobPayload.serializer(), it) }
            ?: return
        val documentId = DocumentId(payload.documentId)
        val revisionId = payload.revisionId

        val prepared = stage.run("resolve-selectable-pdf-inputs") {
            service.prepareBuild(documentId, revisionId)
        }
        val ready = prepared as? SelectablePdfService.PreparedBuild.Ready ?: return

        val result = try {
            SelectablePdfWriter.write(ready.request)
        } catch (failure: SelectablePdfWriteException) {
            stage.run("fail-selectable-pdf") {
                service.recordBuildFailure(documentId, revisionId, failure.reasonCode, ready.key)
            }
            return
        }

        stage.run("publish-selectable-pdf") {
            service.publishBuildResult(documentId, ready.collectionId, revisionId, ready.key, result)
        }
    }
}
