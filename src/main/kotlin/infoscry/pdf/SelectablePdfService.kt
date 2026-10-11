package infoscry.pdf

import infoscry.config.AppPaths
import infoscry.domain.CollectionId
import infoscry.domain.DocumentId
import infoscry.domain.Job
import infoscry.domain.JobId
import infoscry.domain.JobState
import infoscry.domain.JobType
import infoscry.library.ManagedLibrary
import infoscry.storage.DocumentBeingDeletedException
import infoscry.storage.DocumentRevisionStore
import infoscry.storage.DocumentStore
import infoscry.storage.JobStore
import infoscry.storage.SelectablePdfArtifactRow
import infoscry.storage.SelectablePdfArtifactState
import infoscry.storage.SelectablePdfStore
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * Durably generates, publishes, recovers and serves one revision's selectable-PDF artifact (ticket 06
 * of the selectable-PDF plan).
 *
 * The service owns no OCR or embedding dependency: everything it reads is already-committed revision
 * text, images and layouts ([SelectablePdfInputResolver]), and the row it writes
 * ([infoscry.storage.SelectablePdfStore]) is also the durable "desired" marker a crash between a
 * publication committing and its job being enqueued recovers from — see [queueForPublishedRevision] and
 * [reconcileQueuedJobs].
 */
class SelectablePdfService(
    private val paths: AppPaths,
    private val documents: DocumentStore,
    private val revisions: DocumentRevisionStore,
    private val library: ManagedLibrary,
    private val store: SelectablePdfStore,
    private val jobs: JobStore,
) {

    private val json = Json { ignoreUnknownKeys = true }

    /** The current status of [revisionId]'s selectable-PDF artifact. Never creates a row or a job. */
    fun status(collectionId: CollectionId, documentId: DocumentId, revisionId: String): PdfArtifactStatus {
        resolveIdentity(collectionId, documentId, revisionId)
        val row = store.get(documentId, revisionId) ?: return notCreated(revisionId)
        return verified(row)
    }

    /**
     * Ensures one durable generation exists for [revisionId] and returns its current status.
     *
     * Idempotent: a QUEUED/BUILDING row is returned as-is (its job is repaired first if it is no longer
     * live); a verified READY/PARTIAL row is returned as-is; a FAILED/PARTIAL row is only redone with
     * [retry] = true. [retry] never has an effect on a QUEUED/BUILDING/READY row.
     */
    fun ensure(collectionId: CollectionId, documentId: DocumentId, revisionId: String, retry: Boolean): PdfArtifactStatus {
        val identity = resolveIdentity(collectionId, documentId, revisionId)
        if (documents.isDeletionTarget(documentId)) throw DocumentBeingDeletedException(documentId)

        val existing = store.get(documentId, revisionId)
        if (existing == null) {
            return admitNew(identity, collectionId, documentId, revisionId)
        }
        return when (existing.state) {
            SelectablePdfArtifactState.QUEUED, SelectablePdfArtifactState.BUILDING -> {
                ensureLiveJob(existing)
                toStatus(existing)
            }
            SelectablePdfArtifactState.READY -> verified(existing)
            SelectablePdfArtifactState.PARTIAL -> {
                if (!retry) verified(existing) else admitRetry(identity, existing)
            }
            SelectablePdfArtifactState.FAILED -> {
                if (!retry) toStatus(existing) else admitRetry(identity, existing)
            }
        }
    }

    /** The verified file for a READY or PARTIAL artifact. Internal server use only; never a raw path to a client. */
    fun file(collectionId: CollectionId, documentId: DocumentId, revisionId: String, key: String): Path {
        resolveIdentity(collectionId, documentId, revisionId)
        val row = store.get(documentId, revisionId)
            ?: throw NoSuchElementException("no selectable-PDF artifact for revision $revisionId")
        require(row.key == key) { "key does not name this revision's current artifact" }
        require(row.state == SelectablePdfArtifactState.READY || row.state == SelectablePdfArtifactState.PARTIAL) {
            "revision $revisionId has no ready selectable-PDF file"
        }
        val relativePath = row.pdfRelativePath
            ?: throw NoSuchElementException("no selectable-PDF artifact for revision $revisionId")
        val file = paths.selectablePdfDir(collectionId, documentId, revisionId, key).resolve(relativePath)
        if (!Files.isRegularFile(file) || sha256(file) != row.pdfSha256) {
            throw NoSuchElementException("the selectable-PDF file for revision $revisionId is missing or corrupt")
        }
        return file
    }

    // ---- The publication hook and startup reconciliation ---------------------------------------------------------

    /**
     * Ensures a QUEUED row (with its job) exists for a revision that just became published.
     *
     * Called by [infoscry.document.RevisionPublicationService] right after a publication is durably
     * recorded, for import, rescan and restore alike. Never throws: a failure here must never roll back
     * the publication it follows, so every error is logged and swallowed. A document whose media type or
     * revision state make a public artifact impossible is silently skipped — not even a row is written,
     * so this never becomes a queue of permanently-FAILED rows for ineligible documents.
     */
    fun queueForPublishedRevision(documentId: DocumentId, collectionId: CollectionId, revisionId: String) {
        try {
            val document = documents.get(documentId) ?: return
            val revision = revisions.revision(revisionId) ?: return
            if (store.get(documentId, revisionId) != null) return
            if (document.mediaType != SelectablePdfInputResolver.PDF_MEDIA_TYPE) return
            admitNew(Identity(document, revision), collectionId, documentId, revisionId)
        } catch (failure: Exception) {
            LOGGER.atWarn()
                .addKeyValue("document", documentId.value)
                .addKeyValue("revision", revisionId)
                .setCause(failure)
                .log("could not queue a selectable-PDF artifact for a just-published revision; a later ensure repairs it")
        }
    }

    /**
     * Repairs every QUEUED row whose job is not live: the startup half of the crash gap between a row
     * being recorded and its job being durably enqueued.
     *
     * Bounded by construction: only rows [queueForPublishedRevision] or an explicit [ensure] already
     * created are ever QUEUED, so this never re-processes a document's whole publication history.
     *
     * @return how many rows were repaired.
     */
    fun reconcileQueuedJobs(): Int {
        var repaired = 0
        for (row in store.allPending()) {
            if (hasLiveJob(row)) continue
            try {
                enqueueJob(row.documentId, row.collectionId, row.revisionId)
                repaired++
            } catch (failure: Exception) {
                LOGGER.atWarn()
                    .addKeyValue("document", row.documentId.value)
                    .addKeyValue("revision", row.revisionId)
                    .setCause(failure)
                    .log("could not repair a queued selectable-PDF artifact with no live job")
            }
        }
        return repaired
    }

    // ---- Admission ---------------------------------------------------------------------------------------------

    private data class Identity(val document: infoscry.domain.Document, val revision: infoscry.storage.DocumentRevision)

    private fun resolveIdentity(collectionId: CollectionId, documentId: DocumentId, revisionId: String): Identity {
        val document = documents.get(documentId)?.takeIf { it.collectionId == collectionId }
            ?: throw NoSuchElementException("no document with id ${documentId.value} exists in this collection")
        val revision = revisions.revision(revisionId)?.takeIf { it.documentId == documentId }
            ?: throw NoSuchElementException("no revision with id $revisionId exists for this document")
        return Identity(document, revision)
    }

    /** The first-admission path: no row exists yet. Resolve, then create the row already in its right shape. */
    private fun admitNew(identity: Identity, collectionId: CollectionId, documentId: DocumentId, revisionId: String): PdfArtifactStatus {
        val resolution = resolve(identity, collectionId, documentId, revisionId)
        return when (resolution) {
            is SelectablePdfResolution.Ineligible -> failedWithoutRow(revisionId, resolution.reasonCode)
            is SelectablePdfResolution.Failed -> {
                store.recordFailed(documentId, collectionId, revisionId, resolution.reasonCode)
                toStatus(checkNotNull(store.get(documentId, revisionId)))
            }
            is SelectablePdfResolution.Ready -> {
                val key = selectablePdfArtifactKey(resolution.keyInput)
                store.recordDesired(documentId, collectionId, revisionId, key)
                enqueueJob(documentId, collectionId, revisionId)
                toStatus(checkNotNull(store.get(documentId, revisionId)))
            }
        }
    }

    /** The retry path: a PARTIAL/FAILED row already exists. Resolve again and re-admit atomically. */
    private fun admitRetry(identity: Identity, row: SelectablePdfArtifactRow): PdfArtifactStatus {
        val resolution = resolve(identity, row.collectionId, row.documentId, row.revisionId)
        return when (resolution) {
            is SelectablePdfResolution.Ineligible -> {
                store.reFailForRetry(row.documentId, row.revisionId, resolution.reasonCode)
                toStatus(checkNotNull(store.get(row.documentId, row.revisionId)))
            }
            is SelectablePdfResolution.Failed -> {
                store.reFailForRetry(row.documentId, row.revisionId, resolution.reasonCode)
                toStatus(checkNotNull(store.get(row.documentId, row.revisionId)))
            }
            is SelectablePdfResolution.Ready -> {
                val key = selectablePdfArtifactKey(resolution.keyInput)
                store.reQueueForRetry(row.documentId, row.revisionId, key)
                enqueueJob(row.documentId, row.collectionId, row.revisionId)
                toStatus(checkNotNull(store.get(row.documentId, row.revisionId)))
            }
        }
    }

    private fun resolve(identity: Identity, collectionId: CollectionId, documentId: DocumentId, revisionId: String): SelectablePdfResolution {
        val output = paths.selectablePdfDir(collectionId, documentId, revisionId, PENDING_KEY_PLACEHOLDER).resolve(PDF_FILE_NAME)
        return SelectablePdfInputResolver.resolve(
            paths = paths,
            library = library,
            document = identity.document,
            revision = identity.revision,
            revisionPages = revisions.pages(revisionId),
            output = output,
        )
    }

    private fun ensureLiveJob(row: SelectablePdfArtifactRow) {
        if (hasLiveJob(row)) return
        enqueueJob(row.documentId, row.collectionId, row.revisionId)
    }

    private fun hasLiveJob(row: SelectablePdfArtifactRow): Boolean {
        val id = row.jobId ?: return false
        val job = jobs.get(JobId(id)) ?: return false
        return job.state == JobState.QUEUED || job.state == JobState.RUNNING
    }

    private fun enqueueJob(documentId: DocumentId, collectionId: CollectionId, revisionId: String) {
        val job: Job = jobs.enqueue(
            type = JobType.SELECTABLE_PDF,
            collectionId = collectionId,
            payload = json.encodeToString(
                SelectablePdfJobPayload.serializer(),
                SelectablePdfJobPayload(documentId.value, revisionId),
            ),
        )
        store.attachJob(documentId, revisionId, job.id.value)
    }

    // ---- Building (called by SelectablePdfJobHandler) -----------------------------------------------------------
    //
    // The whole attempt — prepareBuild, the writer, publishBuildResult — is idempotent and safe to call
    // twice, including after a crash at any point: prepareBuild reads nothing but already-committed
    // revision data, SelectablePdfWriter.write is itself a content-addressed atomic write (ticket 05),
    // and the manifest is written the same way in publishBuildResult. Each of the three re-checks
    // whether the document is a deletion target before it does its own work, so a deletion that starts
    // mid-attempt is noticed at the next checkpoint rather than only at the start, and a result is never
    // published for a document whose rows and files are already gone. A row that is no longer
    // QUEUED/BUILDING (already finished, or gone because the document was deleted) makes prepareBuild a
    // no-op: a stale or duplicate job attempt has nothing to do.

    /** What [prepareBuild] found: nothing more for the caller to do, or a request ready to write. */
    internal sealed interface PreparedBuild {
        /** The row is no longer QUEUED/BUILDING, the document is gone, or resolution already settled a
         * terminal state (Ineligible/Failed) by itself. The caller (the job handler) has nothing to write. */
        object NothingToDo : PreparedBuild

        /** [request] is ready for [SelectablePdfWriter.write], called outside any stage/permit. */
        data class Ready(val request: PdfWriteRequest, val key: String, val collectionId: CollectionId) : PreparedBuild
    }

    /**
     * The fast, SQLite-only half of one build attempt: resolves fresh inputs and either settles a
     * terminal FAILED result itself (nothing further to do) or marks the row BUILDING under the key
     * this attempt resolved and hands back the request to write. Called from inside a job stage.
     */
    internal fun prepareBuild(documentId: DocumentId, revisionId: String): PreparedBuild {
        val row = store.get(documentId, revisionId) ?: return PreparedBuild.NothingToDo
        if (row.state != SelectablePdfArtifactState.QUEUED && row.state != SelectablePdfArtifactState.BUILDING) {
            return PreparedBuild.NothingToDo
        }
        if (documents.isDeletionTarget(documentId)) return PreparedBuild.NothingToDo
        val document = documents.get(documentId) ?: return PreparedBuild.NothingToDo
        val revision = revisions.revision(revisionId) ?: return PreparedBuild.NothingToDo
        val placeholderOutput = paths.selectablePdfDir(row.collectionId, documentId, revisionId, PENDING_KEY_PLACEHOLDER)
            .resolve(PDF_FILE_NAME)
        val resolution = SelectablePdfInputResolver.resolve(
            paths, library, document, revision, revisions.pages(revisionId), placeholderOutput,
        )
        return when (resolution) {
            is SelectablePdfResolution.Ineligible -> {
                store.markFailed(documentId, revisionId, resolution.reasonCode)
                PreparedBuild.NothingToDo
            }
            is SelectablePdfResolution.Failed -> {
                store.markFailed(documentId, revisionId, resolution.reasonCode, row.key)
                PreparedBuild.NothingToDo
            }
            is SelectablePdfResolution.Ready -> {
                val key = selectablePdfArtifactKey(resolution.keyInput)
                if (documents.isDeletionTarget(documentId)) return PreparedBuild.NothingToDo
                store.markBuilding(documentId, revisionId, key)
                val directory = paths.selectablePdfDir(row.collectionId, documentId, revisionId, key)
                PreparedBuild.Ready(resolution.request.copy(output = directory.resolve(PDF_FILE_NAME)), key, row.collectionId)
            }
        }
    }

    /** Records a safe failure from [SelectablePdfWriter.write] itself. Called from inside a job stage. */
    internal fun recordBuildFailure(documentId: DocumentId, revisionId: String, reasonCode: String, key: String) {
        store.markFailed(documentId, revisionId, reasonCode, key)
    }

    /**
     * The fast, SQLite-only half that follows a successful [SelectablePdfWriter.write]: writes the
     * manifest atomically, reopens and hashes both files independently of what the writer returned
     * in-process, and publishes a verified READY/PARTIAL result — or FAILED if either file does not
     * verify. Called from inside a job stage, after the write itself ran outside one.
     */
    internal fun publishBuildResult(
        documentId: DocumentId,
        collectionId: CollectionId,
        revisionId: String,
        key: String,
        result: PdfWriteResult,
    ) {
        val directory = paths.selectablePdfDir(collectionId, documentId, revisionId, key)
        val manifestFile = directory.resolve(MANIFEST_FILE_NAME)
        val manifestBytes = json.encodeToString(SelectablePdfManifest.serializer(), result.manifest).toByteArray()
        writeAtomically(manifestFile, manifestBytes)
        val pdfFile = directory.resolve(PDF_FILE_NAME)
        val reopenedPdfSha256 = sha256(pdfFile)
        val reopenedManifestSha256 = sha256(manifestFile)
        if (reopenedPdfSha256 != result.sha256 || reopenedManifestSha256 != sha256Of(manifestBytes)) {
            store.markFailed(documentId, revisionId, VERIFICATION_FAILED_CODE, key)
            return
        }
        if (documents.isDeletionTarget(documentId)) return
        val manifest = result.manifest
        val totalPages = manifest.pages.size
        val selectablePages = manifest.selectablePageCount
        val blankPages = manifest.blankPageCount
        val unavailable = manifest.unavailablePageResults
        if (unavailable.isEmpty()) {
            store.markReady(
                documentId, revisionId, key, MANIFEST_FILE_NAME, reopenedManifestSha256,
                PDF_FILE_NAME, reopenedPdfSha256, result.bytes, totalPages, selectablePages, blankPages,
            )
        } else {
            store.markPartial(
                documentId, revisionId, key, MANIFEST_FILE_NAME, reopenedManifestSha256,
                PDF_FILE_NAME, reopenedPdfSha256, result.bytes, totalPages, selectablePages, blankPages, unavailable,
            )
        }
    }

    /** Writes [bytes] to [target] through a temporary file and an atomic rename, like every other artifact here. */
    private fun writeAtomically(target: Path, bytes: ByteArray) {
        Files.createDirectories(checkNotNull(target.parent))
        val temporary = target.resolveSibling("${target.fileName}.${java.util.UUID.randomUUID()}.tmp")
        try {
            Files.write(temporary, bytes)
            try {
                Files.move(temporary, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            } catch (unsupported: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun sha256Of(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(bytes)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    // ---- Verified reuse ------------------------------------------------------------------------------------------

    /** A READY/PARTIAL row, re-checked against the actual files; anything else is returned as-is. */
    private fun verified(row: SelectablePdfArtifactRow): PdfArtifactStatus {
        if (row.state != SelectablePdfArtifactState.READY && row.state != SelectablePdfArtifactState.PARTIAL) {
            return toStatus(row)
        }
        val key = row.key
        val manifestRelative = row.manifestRelativePath
        val pdfRelative = row.pdfRelativePath
        if (key == null || manifestRelative == null || pdfRelative == null) {
            store.markFailed(row.documentId, row.revisionId, VERIFICATION_FAILED_CODE)
            return failedWithoutRow(row.revisionId, VERIFICATION_FAILED_CODE)
        }
        val directory = paths.selectablePdfDir(row.collectionId, row.documentId, row.revisionId, key)
        val manifestFile = directory.resolve(MANIFEST_FILE_NAME)
        val pdfFile = directory.resolve(PDF_FILE_NAME)
        val intact = runCatching {
            Files.isRegularFile(manifestFile) && Files.isRegularFile(pdfFile) &&
                sha256(manifestFile) == row.manifestSha256 && sha256(pdfFile) == row.pdfSha256
        }.getOrDefault(false)
        if (!intact) {
            store.markFailed(row.documentId, row.revisionId, VERIFICATION_FAILED_CODE, key)
            return failedWithoutRow(row.revisionId, VERIFICATION_FAILED_CODE, key)
        }
        return toStatus(row)
    }

    private fun toStatus(row: SelectablePdfArtifactRow): PdfArtifactStatus = when (row.state) {
        SelectablePdfArtifactState.QUEUED -> PdfArtifactStatus(
            row.revisionId, requireNotNull(row.key), PdfArtifactState.QUEUED, 0, null, 0, emptyList(), null,
        )
        SelectablePdfArtifactState.BUILDING -> PdfArtifactStatus(
            row.revisionId, requireNotNull(row.key), PdfArtifactState.BUILDING, 0, null, 0, emptyList(), null,
        )
        SelectablePdfArtifactState.READY -> PdfArtifactStatus(
            row.revisionId, row.key, PdfArtifactState.READY, row.selectablePages,
            requireNotNull(row.totalPages), row.blankPages, emptyList(), null,
        )
        SelectablePdfArtifactState.PARTIAL -> PdfArtifactStatus(
            row.revisionId, row.key, PdfArtifactState.PARTIAL, row.selectablePages,
            requireNotNull(row.totalPages), row.blankPages, row.unavailablePages, null,
        )
        SelectablePdfArtifactState.FAILED -> PdfArtifactStatus(
            row.revisionId, row.key, PdfArtifactState.FAILED, 0, null, 0, emptyList(),
            requireNotNull(row.reasonCode),
        )
    }

    private fun notCreated(revisionId: String): PdfArtifactStatus =
        PdfArtifactStatus(revisionId, null, PdfArtifactState.NOT_CREATED, 0, null, 0, emptyList(), null)

    private fun failedWithoutRow(revisionId: String, reasonCode: String, key: String? = null): PdfArtifactStatus =
        PdfArtifactStatus(revisionId, key, PdfArtifactState.FAILED, 0, null, 0, emptyList(), reasonCode)

    private fun sha256(file: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val MANIFEST_FILE_NAME: String = "manifest.json"
        const val PDF_FILE_NAME: String = "document.pdf"
        const val VERIFICATION_FAILED_CODE: String = "ARTIFACT_VERIFICATION_FAILED"

        /** Never published: the output path's directory name while a key is still being resolved. */
        private const val PENDING_KEY_PLACEHOLDER: String = "pending"
    }
}

/** The durable payload of a [JobType.SELECTABLE_PDF] job: the exact revision it was enqueued for. */
@kotlinx.serialization.Serializable
data class SelectablePdfJobPayload(val documentId: String, val revisionId: String)

private val LOGGER = LoggerFactory.getLogger("infoscry.pdf")
