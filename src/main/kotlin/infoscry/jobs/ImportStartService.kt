package infoscry.jobs

import infoscry.diagnostics.ToolProbe
import infoscry.collection.CollectionService
import infoscry.domain.CollectionId
import infoscry.domain.Job
import infoscry.domain.JobId
import infoscry.domain.JobType
import infoscry.extract.ExtractionSettings
import infoscry.ocr.CollectionOcrSettings
import infoscry.ocr.OcrProfileService
import infoscry.ocr.OcrSettingsSnapshot
import infoscry.ocr.ReadingMethod
import infoscry.ocr.ReadingMethodCatalog
import infoscry.storage.JobStore
import infoscry.storage.StartRequestStore
import infoscry.storage.MutationCoordinator
import java.security.MessageDigest
import java.util.HexFormat

data class ImportStartRequest(
    val collectionId: CollectionId,
    val paths: List<String>,
    val recursive: Boolean = false,
    val include: List<String> = emptyList(),
    val exclude: List<String> = emptyList(),
    val method: ReadingMethod,
    val previewHash: String,
    val requestId: String,
    /** CLI opts in so a later identical command can replace a stopped import; HTTP defaults to strict replay. */
    val restartStopped: Boolean = false,
)

class StaleImportPreviewException : IllegalStateException("The files changed; review the summary again.")

/** Shared HTTP and CLI admission for an import whose reading method and file set were confirmed. */
class ImportStartService internal constructor(
    private val previews: ImportPreviewService,
    private val catalog: ReadingMethodCatalog,
    private val ocr: OcrProfileService,
    private val jobs: JobStore,
    private val requests: StartRequestStore,
    private val mutations: MutationCoordinator,
    private val collectionService: CollectionService,
    private val ignorePatterns: (infoscry.domain.CollectionId) -> IgnorePatterns,
    private val extractionSettings: suspend (String) -> ExtractionSettings = { ToolProbe.extractionSettings(it) },
) {
    suspend fun start(request: ImportStartRequest): Job {
        require(request.requestId.isNotBlank()) { "an import start needs a request id" }
        require(request.previewHash.matches(Regex("[0-9a-f]{64}"))) { "an import start needs a preview hash" }
        val extensions = ExtensionFilter.of(request.include, request.exclude)
        val bodyHash = bodyHash(request, extensions)
        existingUnlessRestartable(request, bodyHash)?.let { return it }
        try {
            return mutations.withMutation {
                collectionService.requireMutationsAllowed()
                existingUnlessRestartable(request, bodyHash)?.let { return@withMutation it }
                val collection = collectionService.requireActiveByNameOrId(request.collectionId.value)
                val previewRequest = ImportPreviewRequest(
                    collection = collection.id,
                    paths = request.paths,
                    recursive = request.recursive,
                    include = request.include,
                    exclude = request.exclude,
                    method = request.method,
                )
                val availability = catalog.require(collection.id, request.method)
                val preview = previews.preview(previewRequest)
                if (preview.previewHash != request.previewHash) throw StaleImportPreviewException()
                val confirmed = previews.confirmedSourceSet(previewRequest)
                if (confirmed.previewHash != request.previewHash) throw StaleImportPreviewException()
                val baseSnapshot = ocr.snapshotFor(
                    settings = CollectionOcrSettings(collection.ocrLanguages, request.method),
                    extractorVersion = infoscry.extract.EXTRACTOR_SCHEMA_VERSION,
                    renderDpi = infoscry.extract.PdfExtractor.DEFAULT_RENDER_DPI,
                )
                val manifestScopeConfirmed = availability.external && preview.atLeast
                val allowance = if (availability.external && !manifestScopeConfirmed) preview.totalPages else 0
                val snapshot = ocr.withProbedRuntime(
                    baseSnapshot.copy(
                        externalPageLimit = allowance,
                        externalConfirmedSourceScope = manifestScopeConfirmed,
                    ),
                )
                val settings = extractionSettings(collection.ocrLanguages).forOcrSettings(snapshot)
                val payload = ImportJobPayload.of(
                    collectionId = collection.id,
                    sources = confirmed.sources.map { it.path },
                    settings = settings,
                    recursive = false,
                    ocr = snapshot,
                    extensions = ExtensionFilter.NONE,
                    ignore = IgnorePatterns.NONE,
                    confirmedSources = confirmed.sources,
                )
                val jobId = requests.claim(
                    requestId = request.requestId,
                    kind = KIND,
                    bodyHash = bodyHash,
                    restartStopped = request.restartStopped,
                    create = {
                        jobs.enqueue(
                            type = JobType.IMPORT,
                            collectionId = collection.id,
                            payload = payload.encode(),
                            total = 0,
                        ).id
                    },
                )
                requireJob(jobId)
            }
        } catch (failure: Throwable) {
            // A concurrent request can win while this call is checking files and probing the runtime. If that
            // happened, the durable request is authoritative and the retry returns the same job.
            existingUnlessRestartable(request, bodyHash)?.let { return it }
            throw failure
        }
    }

    /** Replays ordinary requests, and CLI opt-in requests whose prior job is still active or completed. */
    private fun existingUnlessRestartable(request: ImportStartRequest, bodyHash: String): Job? {
        val id = requests.lookup(request.requestId, KIND, bodyHash) ?: return null
        val existing = requireJob(id)
        if (request.restartStopped && existing.state in RESTARTABLE_STATES) return null
        return existing
    }

    private fun requireJob(id: JobId): Job = jobs.get(id)
        ?: throw NoSuchElementException("no job with id ${id.value}")

    private fun bodyHash(request: ImportStartRequest, extensions: ExtensionFilter): String {
        val fields = buildList {
            add("collection" to request.collectionId.value)
            add("method" to request.method.id)
            add("previewHash" to request.previewHash)
            add("restartStopped" to request.restartStopped.toString())
            add("recursive" to request.recursive.toString())
            extensions.include.forEach { add("include" to it) }
            extensions.exclude.forEach { add("exclude" to it) }
            request.paths.forEach { add("path" to it) }
        }
        val canonical = fields.joinToString("") { (key, value) -> "$key:${value.length}:$value\n" }
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8)))
    }

    companion object {
        const val KIND = "import"
        private val RESTARTABLE_STATES = setOf(infoscry.domain.JobState.FAILED, infoscry.domain.JobState.CANCELLED)
    }
}
