package infoscry.jobs

import infoscry.domain.Document
import infoscry.domain.Job
import infoscry.ocr.OcrDispatchAuthority
import infoscry.ocr.OcrDispatchStage
import infoscry.ocr.OcrEndpointScope
import infoscry.ocr.OcrExternalOwner
import infoscry.ocr.OcrProfileRevision
import infoscry.ocr.OcrSettingsSnapshot
import infoscry.storage.OcrOperationStore

/**
 * The attempt-level wiring an import and a retry share: how one job-owned external page scope becomes the
 * dispatch authority a page is sent through.
 *
 * Both attempts read the same pages by the same rules, so this is one place rather than two: an import's
 * confirmed files share one allowance per job, and no page leaves without its configured allowance.
 */
internal class AttemptDispatch(
    private val operations: OcrOperationStore,
    private val profileRevisionOf: (String) -> OcrProfileRevision?,
) {

    fun authorityFor(
        job: Job,
        document: Document,
        snapshot: OcrSettingsSnapshot?,
        dispatchStage: OcrDispatchStage,
        perDocumentScope: Boolean = false,
        confirmedManifestScope: Boolean = false,
    ): OcrDispatchAuthority? {
        val scope = snapshot ?: return null
        val revisionId = when (dispatchStage) {
            OcrDispatchStage.TRANSCRIPTION -> scope.transcriptionProfileRevisionId
            OcrDispatchStage.REVIEW -> scope.reviewProfileRevisionId
        } ?: return null
        val revision = profileRevisionOf(revisionId) ?: return null
        if (revision.scope != OcrEndpointScope.EXTERNAL) return null
        return OcrDispatchAuthority(
            operations = operations,
            owner = OcrExternalOwner.job(
                if (perDocumentScope) "${job.id.value}:${document.id.value}" else job.id.value,
            ),
            documentId = document.id,
            stage = dispatchStage,
            profileRevisionId = revisionId,
            configuredAllowance = if (confirmedManifestScope && scope.externalConfirmedSourceScope) {
                Int.MAX_VALUE
            } else {
                scope.externalPageLimit
            },
        )
    }
}
