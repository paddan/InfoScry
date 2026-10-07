package infoscry.document

import infoscry.collection.DeletionBlockers
import infoscry.diagnostics.ToolProbe
import infoscry.domain.Collection
import infoscry.domain.CollectionId
import infoscry.domain.CollectionLifecycle
import infoscry.domain.Document
import infoscry.domain.DocumentId
import infoscry.domain.DocumentStatus
import infoscry.domain.Job
import infoscry.domain.JobType
import infoscry.embedding.ModelManager
import infoscry.extract.CalibreConverter
import infoscry.extract.ExtractionSettings
import infoscry.extract.LEGACY_EBOOK_MEDIA_TYPE
import infoscry.extract.MOBIPOCKET_MEDIA_TYPE
import infoscry.extract.TesseractOcr
import infoscry.jobs.RetryJobPayload
import infoscry.ocr.OcrEngine
import infoscry.ocr.OcrImportMode
import infoscry.ocr.OcrSettingsSnapshot
import infoscry.ocr.requireOcrLanguages
import infoscry.storage.CollectionStore
import infoscry.storage.Database
import infoscry.storage.DocumentStore
import infoscry.storage.JobStore
import infoscry.storage.MutationCoordinator
import java.nio.file.Path
import java.sql.Connection
import kotlinx.serialization.Serializable

/**
 * What a retry needs from this machine before it may be queued.
 *
 * Both halves are decided once per admission. The settings are the ones the attempt will run with — they
 * and the document's content hash decide which committed units are reusable — and the flags say whether
 * the tools those settings name are actually usable. A tool that is absent is recorded as absent rather
 * than left unrecorded, so "this attempt had no tool" stays a different fingerprint from "this attempt
 * used version X", which is what makes installing the tool later a new extraction rather than a silent
 * reuse of an older reading.
 */
data class RetryPrerequisites(
    val settings: ExtractionSettings,
    /**
     * The snapshotted OCR selection the retry was admitted with, or null when none was resolved.
     *
     * It travels in the payload beside the settings for the same reason they do: the external page allowance
     * and the profile revisions a dispatch or an approval binds to are decided at admission, so a running
     * retry reads its pages through the engines that were chosen rather than whatever the collection says
     * later. Null means a legacy admission — Tesseract/fill-missing, no external pages.
     */
    val ocr: OcrSettingsSnapshot? = null,
    val ocrToolAvailable: Boolean,
    val ebookToolAvailable: Boolean,
    val embeddingModelAvailable: Boolean,
) {

    companion object {

        /**
         * What this machine has, asked once: the reading tool's version, the converter's version, and
         * whether the pinned embedding model's files are present.
         *
         * The model check is the cheap one that the embedder itself makes before it builds a session
         * ([ModelManager.isInstalled]), not a digest verification: the question here is only whether a
         * retry has any chance of embedding what it reads.
         */
        /**
         * What this machine has for one collection's reading.
         *
         * [ocrSnapshot] resolves the collection's OCR selection into the attempt snapshot the retry will run
         * with, or answers null for a caller that has no profiles wired: without it, a retry records the
         * legacy Tesseract/fill-missing reading, which is what an unchanged collection means anyway. It is
         * suspend because resolving the snapshot includes asking the reading engine what runtime it is —
         * the identity an attempt is identified by has to be recorded before the attempt is queued.
         */
        suspend fun probe(
            collection: Collection,
            modelsDir: Path,
            ocrSnapshot: suspend (infoscry.ocr.CollectionOcrSettings) -> OcrSettingsSnapshot? = { null },
        ): RetryPrerequisites {
            val snapshot = ocrSnapshot(collection.ocrSettings())
            val settings = ToolProbe.extractionSettings(collection.ocrLanguages).let { probed ->
                snapshot?.let(probed::forOcrSettings) ?: probed
            }
            return RetryPrerequisites(
                settings = settings,
                ocr = snapshot,
                ocrToolAvailable = settings.ocrTool != null && settings.ocrTool != ToolProbe.OCR_TOOL_ABSENT,
                ebookToolAvailable =
                settings.ebookTool != null && settings.ebookTool != ToolProbe.CALIBRE_TOOL_ABSENT,
                embeddingModelAvailable = ModelManager.production().isInstalled(modelsDir),
            )
        }
    }
}

/** One document an admission refused, with the sentence that says why and, where there is one, a safe code. */
data class RejectedRetry(val documentId: String, val reason: String, val code: String? = null)

/**
 * The OCR method a person chose for one retry, over the collection's settings.
 *
 * Every field is optional and an absent one means the collection's value, so a choice that names nothing is
 * the same request as no choice at all. The fields are the ones a rescan preview lets a person override, plus
 * the language list, and they are resolved by the same code ([RescanService.resolveChosenReading]) - this
 * type only carries the choice to it. It is never stored: what a retry runs with is the snapshot it resolves
 * to, frozen into the retry's payload.
 */
@Serializable
data class RetryOcrChoice(
    val engine: OcrEngine? = null,
    val importMode: OcrImportMode? = null,
    val transcriptionProfileId: String? = null,
    val reviewProfileId: String? = null,
    val language: String? = null,
) {

    /** Whether the choice names nothing, so the retry keeps the collection's settings exactly as before. */
    val isEmpty: Boolean
        get() = engine == null && importMode == null && transcriptionProfileId == null &&
            reviewProfileId == null && language.isNullOrBlank()

    internal fun toOverrides(): RescanOverrides = RescanOverrides(
        engine = engine,
        importMode = importMode,
        transcriptionProfileId = transcriptionProfileId,
        reviewProfileId = reviewProfileId,
        language = language?.takeIf { it.isNotBlank() }?.let(::requireOcrLanguages),
    )
}

/**
 * What a retry with a chosen OCR method asks of the rest of the archive, as one seam.
 *
 * The production implementation is the rescan service's own admission (so the rules are not copied); this
 * interface is the only place the two meet.
 */
interface RetryReadingChoices {

    /** The validated, frozen reading the choice makes for [collection]; refuses with a [RescanRefusalException]. */
    suspend fun resolve(collection: Collection, choice: RetryOcrChoice): OcrSettingsSnapshot

    /** Why this document's format cannot be read from page images at all, or null when it can. */
    fun pageImagesRefusal(document: Document): RescanRefusalException?

    /** Whether the document already publishes a text, which only Scan again may replace. */
    fun publishesText(documentId: DocumentId): Boolean
}

/**
 * What an admission did: the job it queued, or none when nothing was eligible, and the documents it
 * refused with a safe reason. Rejecting some documents does not reject the others — a retry of ten
 * documents where one is already running is still nine attempts the user asked for.
 */
data class RetryAdmission(val job: Job?, val rejected: List<RejectedRetry>) {

    /** The jobs this admission queued. One request is one attempt, so this is empty or one id. */
    val acceptedJobIds: List<String> get() = job?.let { listOf(it.id.value) }.orEmpty()
}

/**
 * Admitting a retry: which documents may be read again, and the durable attempt that does it.
 *
 * It is the retry half of what [DocumentService] is for deletion — same gate, same blockers, same rule
 * that the caller cannot learn about another collection's documents — and it keeps three promises that
 * only make sense together:
 *
 * - **Nothing is queued twice.** The decision is made *inside* the shared mutation permit and inside one
 *   transaction, so two clicks arriving together cannot both see an eligible document: the first one moves
 *   it to `QUEUED`, and the second sees a document that already has work.
 * - **Nothing is queued that cannot run.** The reading tool, the e-book converter and the embedding model
 *   are probed before anything is enqueued. A missing prerequisite is a refusal with a remedy the user can
 *   act on, never a job that fails a minute later for a reason admission already knew.
 * - **Nothing is queued for a document that is going away.** A document a deletion has targeted is refused,
 *   and so is one whose import still has it queued or running: the attempt that follows would race work
 *   that already owns the document.
 *
 * Eligibility is the status the user can see: `FAILED`, `CANCELLED` and `NEEDS_TOOL` have unfinished
 * business, while a pipeline status means an attempt is already underway and `COMPLETE` and
 * `COMPLETE_WITH_WARNINGS` mean the document was read.
 */
class RetryService(
    private val database: Database,
    private val collections: CollectionStore,
    private val documents: DocumentStore,
    private val jobs: JobStore,
    private val coordinator: MutationCoordinator,
    private val blockers: DeletionBlockers = DeletionBlockers(),
    /**
     * What this machine can offer an attempt. It is a seam because the answer is a property of the machine
     * — and because a test that would otherwise depend on Tesseract or Calibre being installed has to be
     * able to say what it wants the answer to be.
     */
    private val prerequisites: suspend (Collection) -> RetryPrerequisites = { collection ->
        RetryPrerequisites.probe(collection, Path.of("."))
    },
    /** How a chosen OCR method is validated and frozen; null for a build that offers no choice. */
    private val choices: RetryReadingChoices? = null,
) {

    /** Queues one attempt for exactly [documentIds] and answers which were accepted and which were not. */
    suspend fun admitRetry(
        collectionId: CollectionId,
        documentIds: List<DocumentId>,
        choice: RetryOcrChoice? = null,
    ): RetryAdmission = admit(collectionId, choice) { documentIds.distinct() }

    /** Queues one attempt for every eligible document of the collection, across pages. */
    suspend fun admitRetryOfEligible(collectionId: CollectionId, choice: RetryOcrChoice? = null): RetryAdmission =
        admit(collectionId, choice) { connection -> eligibleDocuments(connection, collectionId) }

    /** Whether one document would be accepted right now, for the details view's Retry action. */
    fun isEligible(document: Document): Boolean =
        document.status in ELIGIBLE_STATUSES && !documents.isDeletionTarget(document.id)

    private suspend fun admit(
        collectionId: CollectionId,
        choice: RetryOcrChoice?,
        select: (Connection) -> List<DocumentId>,
    ): RetryAdmission {
        blockers.requireMutationsAllowed()
        // An empty choice is no choice: the collection's settings are used exactly as they always were.
        val chosen = choice?.takeUnless { it.isEmpty }
        val collection = activeCollection(collectionId)
        // Probed once, before anything is queued: the settings travel in the payload and cannot be re-read
        // later, and an absent prerequisite is a refusal rather than a job that was falsely accepted.
        val probed = prerequisites(collection)
        // A chosen method is validated here, before the permit and before anything is queued, so a refusal
        // never leaves a document moved to QUEUED.
        val available = if (chosen == null) probed else withChosenReading(collection, probed, chosen)

        return coordinator.withMutation {
            blockers.requireMutationsAllowed()
            // The probe ran before this permit, so an OCR-language change admitted in between would be
            // enqueued stale: the payload would name the settings a reader no longer has. The collection is
            // read again *inside* the permit — no settings change can land from here on — and probed again
            // only when its languages actually moved, so the payload and the probe describe the settings
            // that were admitted rather than the ones the earlier read happened to see.
            val admitted = activeCollection(collectionId)
            val reconciled = when {
                // The collection's settings are what every unchosen field of the reading comes from, so any
                // change to them while the choice was being resolved resolves the choice again.
                chosen != null && admitted.ocrSettings() != collection.ocrSettings() ->
                    withChosenReading(admitted, prerequisites(admitted), chosen)
                admitted.ocrLanguages == collection.ocrLanguages -> available
                else -> prerequisites(admitted)
            }
            database.transaction { connection ->
                val accepted = mutableListOf<DocumentId>()
                val rejected = mutableListOf<RejectedRetry>()
                select(connection).distinct().forEach { documentId ->
                    val rejection = rejectionFor(collectionId, documentId, reconciled, chosen != null)
                    if (rejection == null) accepted += documentId else rejected += rejection
                }
                if (accepted.isEmpty()) {
                    // No job is queued for nothing: an archive with nothing to retry is answered, not acted on.
                    return@transaction RetryAdmission(job = null, rejected = rejected)
                }

                // The document moves to `QUEUED` in the same transaction that records the job, so the state a
                // concurrent admission reads is one where the work already exists — that is what makes two
                // clicks one attempt instead of two.
                accepted.forEach { documentId -> documents.updateStatus(documentId, DocumentStatus.QUEUED) }
                val payload = RetryJobPayload(
                    collectionId = collectionId.value,
                    documentIds = accepted.map { it.value },
                    settings = reconciled.settings,
                    ocr = reconciled.ocr,
                )
                val job = jobs.enqueue(
                    type = JobType.RETRY,
                    collectionId = collectionId,
                    payload = payload.encode(),
                    total = accepted.size,
                )
                RetryAdmission(job = job, rejected = rejected)
            }
        }
    }

    private fun rejectionFor(
        collectionId: CollectionId,
        documentId: DocumentId,
        available: RetryPrerequisites,
        readsWithChoice: Boolean,
    ): RejectedRetry? {
        // Scoped: another collection's document is answered exactly like one that does not exist, so a
        // response can never confirm that an id belongs to a collection the caller did not name.
        val document = documents.get(documentId)?.takeIf { it.collectionId == collectionId }
            ?: return RejectedRetry(documentId.value, NOT_IN_COLLECTION)
        if (documents.isDeletionTarget(documentId)) return RejectedRetry(documentId.value, DELETING)
        if (document.status !in ELIGIBLE_STATUSES) {
            val reason = if (document.status in PIPELINE_STATUSES) ALREADY_RUNNING else NOT_ELIGIBLE
            return RejectedRetry(documentId.value, reason)
        }
        // An import that still names this document owns it: a retry would race a resume that is already going
        // to read the same bytes, and the item's durable row is what tells us so.
        if (importInFlight(documentId)) return RejectedRetry(documentId.value, ALREADY_RUNNING)
        if (readsWithChoice) choiceRejection(document)?.let { return it }
        requiredToolRemedy(document, available)?.let { remedy ->
            return RejectedRetry(documentId.value, remedy)
        }
        if (!available.embeddingModelAvailable) return RejectedRetry(documentId.value, MODEL_UNAVAILABLE)
        return null
    }

    /**
     * Resolves a chosen OCR method into the prerequisites the retry will be queued with.
     *
     * The snapshot comes from the rescan admission, so an engine this build lacks, an unmeasured external
     * profile, a missing key or a missing embedder refuse the whole request here. The extraction settings are
     * the machine's probed ones with the chosen language and the chosen reading folded in, which is what makes
     * a unit read under another choice a different fingerprint rather than a silent reuse. A chosen Tesseract
     * needs the tool itself, which the rescan admission does not look for.
     */
    private suspend fun withChosenReading(
        collection: Collection,
        probed: RetryPrerequisites,
        chosen: RetryOcrChoice,
    ): RetryPrerequisites {
        val reading = checkNotNull(choices) { "this archive was built without a way to choose an OCR method" }
        val snapshot = reading.resolve(collection, chosen)
        if (snapshot.engine == OcrEngine.TESSERACT && !probed.ocrToolAvailable) {
            throw RescanRefusalException(
                RescanRefusalException.ENGINE_UNAVAILABLE,
                "the OCR tool (Tesseract) is not installed, so a retry cannot read with it: " +
                    TesseractOcr.installRemedy(),
            )
        }
        val settings = probed.settings
            .copy(ocrLanguages = snapshot.language, ocrAttempt = null, ocrMode = OcrImportMode.FILL_MISSING)
            .forOcrSettings(snapshot)
        return probed.copy(settings = settings, ocr = snapshot)
    }

    /**
     * Why a chosen OCR method cannot be applied to this document, if that is how it stands.
     *
     * A document that already publishes a text keeps Scan again, whose review-before-replacing rule a retry
     * must not sidestep; a format without page images has nothing for the chosen engine to read.
     */
    private fun choiceRejection(document: Document): RejectedRetry? {
        val reading = checkNotNull(choices)
        if (reading.publishesText(document.id)) {
            return RejectedRetry(document.id.value, USE_SCAN_AGAIN, USE_SCAN_AGAIN_CODE)
        }
        return reading.pageImagesRefusal(document)?.let { refusal ->
            RejectedRetry(document.id.value, refusal.message.orEmpty(), refusal.code)
        }
    }

    /**
     * What this document cannot be read without, if that is how it stands.
     *
     * Two requirements are knowable without reading the file. A format only the converter can open needs the
     * converter whatever failed last time; a document that failed because the OCR tool was missing needs that
     * tool, because the same pages will need it again.
     */
    private fun requiredToolRemedy(document: Document, available: RetryPrerequisites): String? {
        if (document.mediaType in CONVERTER_ONLY_MEDIA_TYPES && !available.ebookToolAvailable) {
            return "the e-book converter is not installed, so this format cannot be read again: " +
                CalibreConverter.installRemedy()
        }
        if (document.errorCode == TesseractOcr.NEEDS_TESSERACT_CODE && !available.ocrToolAvailable) {
            return "the OCR tool (Tesseract) is not installed, so this document cannot be read again: " +
                TesseractOcr.installRemedy()
        }
        return null
    }

    /** Whether a queued or running import still holds this document, whatever its per-file outcome was. */
    private fun importInFlight(documentId: DocumentId): Boolean = database.read { connection ->
        connection.prepareStatement(IMPORT_IN_FLIGHT).use { statement ->
            statement.setString(1, documentId.value)
            statement.setString(2, infoscry.storage.ImportItemOutcome.CANCELLED.name)
            statement.executeQuery().use { rows -> rows.next() }
        }
    }

    /** Every document of the collection whose status leaves something to retry, in a stable order. */
    private fun eligibleDocuments(connection: Connection, collectionId: CollectionId): List<DocumentId> =
        connection.prepareStatement(
            "SELECT id FROM documents WHERE collection_id = ? AND status IN (?, ?, ?) ORDER BY created_at, id",
        ).use { statement ->
            statement.setString(1, collectionId.value)
            ELIGIBLE_STATUSES.forEachIndexed { index, status -> statement.setString(index + 2, status.name) }
            statement.executeQuery().use { rows ->
                buildList { while (rows.next()) add(DocumentId(rows.getString(1))) }
            }
        }

    /** The collection the attempt is aimed at; a tombstoned one is the same not-found answer. */
    private fun activeCollection(collectionId: CollectionId): Collection =
        collections.get(collectionId)?.takeIf { it.lifecycle == CollectionLifecycle.ACTIVE }
            ?: throw NoSuchElementException(
                "no usable collection with id ${collectionId.value}; it does not exist or its deletion is in progress",
            )

    /** The mutating state the archive refuses: an unresolved unsafe deletion of either kind. */
    fun requireMutationsAllowed() {
        blockers.requireMutationsAllowed()
    }

    companion object {

        /** The statuses a retry is offered for: work that stopped without finishing the document. */
        val ELIGIBLE_STATUSES: Set<DocumentStatus> = setOf(
            DocumentStatus.FAILED,
            DocumentStatus.CANCELLED,
            DocumentStatus.NEEDS_TOOL,
        )

        /** The statuses that mean an attempt already owns the document. */
        private val PIPELINE_STATUSES: Set<DocumentStatus> = setOf(
            DocumentStatus.QUEUED,
            DocumentStatus.COPYING,
            DocumentStatus.EXTRACTING,
            DocumentStatus.OCR,
            DocumentStatus.CHUNKING,
            DocumentStatus.EMBEDDING,
            DocumentStatus.INDEXING,
        )

        /** The formats nothing in this build reads without the external converter. */
        private val CONVERTER_ONLY_MEDIA_TYPES: Set<String> = setOf(
            MOBIPOCKET_MEDIA_TYPE,
            LEGACY_EBOOK_MEDIA_TYPE,
        )

        const val NOT_IN_COLLECTION = "this document is not in this collection"

        const val NOT_ELIGIBLE =
            "this document is not failed, cancelled or waiting for a tool, so there is nothing to retry"

        const val ALREADY_RUNNING = "an import or retry for this document is already queued or running"

        const val DELETING = "this document is being deleted"

        /** A document with a published text is read again through Scan again, which asks before replacing it. */
        const val USE_SCAN_AGAIN_CODE = "RETRY_USE_SCAN_AGAIN"
        const val USE_SCAN_AGAIN = "this document already has a published text, so reading it with another OCR " +
            "method is done with Scan again, which shows the new reading before it replaces the old one"

        /** The pinned embedding model is missing, so nothing read here could be made searchable. */
        val MODEL_UNAVAILABLE: String = "the embedding model is not installed, " +
            "so a retry could not make this document searchable: " + ModelManager.installRemedy()

        private const val IMPORT_IN_FLIGHT =
            "SELECT 1 FROM import_items item JOIN jobs job ON job.id = item.job_id " +
                "WHERE item.document_id = ? AND item.outcome <> ? AND job.state IN ('QUEUED', 'RUNNING')"
    }
}
