package infoscry.jobs

import infoscry.config.AppPaths
import infoscry.domain.DocumentId
import infoscry.domain.DocumentStatus
import infoscry.domain.ExtractionMethod
import infoscry.extract.ExtractionEvent
import infoscry.extract.ExtractionFingerprint
import infoscry.extract.ExtractionSink
import infoscry.storage.ContentStore
import infoscry.storage.DocumentBeingDeletedException
import infoscry.storage.DocumentStore
import java.nio.file.Files
import java.nio.file.Path
import org.slf4j.LoggerFactory

/**
 * Where an extractor's units become durable rows.
 *
 * This is the other half of the extraction contract: an extractor reads a document and reports what it read
 * one unit at a time, and this commits each event as it arrives. Because a flow is collected inline, the
 * commit happens inside the unit boundary's permit — the same permit that covers the artifact the unit names
 * — so a unit's text, its artifact reference and its checkpoint cannot be separated by a collection deletion
 * or an index rebuild.
 *
 * Three responsibilities that belong together, and are here because otherwise they would be spread over the
 * pipeline:
 *
 * - **Committing one unit at a time.** Each event is its own transaction. The alternative — collecting a
 *   document's units and writing them at the end — would hold a permit for a whole PDF and lose everything
 *   read before a kill.
 * - **Deciding what a later attempt may skip.** A resumed attempt is handed the keys an earlier one
 *   committed *after* their artifacts are verified, so a citation can never rest on word boxes that are no
 *   longer there.
 * - **Cleaning up after a killed conversion.** A converter that was terminated leaves its private working
 *   directory inside the document's artifacts; it is swept when the document is next touched, because
 *   nothing else will ever look at it again.
 *
 * It also publishes the two facts a reader watches while a document is being read: the total an extractor
 * announced, and the OCR phase itself. Both are written only after the unit they describe is committed, so
 * what the collection shows can never be ahead of what the archive holds.
 */
class StoredUnitsSink(
    private val paths: AppPaths,
    private val documents: DocumentStore,
    private val content: ContentStore,
) : ExtractionSink {

    override val storesUnits: Boolean = true

    /**
     * Where each document's artifacts live, kept only for the documents this process is working on.
     *
     * The lookup costs a document read and happens once per extraction event, so resolving it every time adds
     * a query per page; caching it for the sink's life would instead keep one entry for every document a
     * long-lived server ever imported. A small access-ordered map is the middle: the document being read
     * stays resident and documents nobody is delivering to fall out.
     */
    private val artifactRoots = object : LinkedHashMap<DocumentId, Path>(CACHE_CAPACITY, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<DocumentId, Path>): Boolean =
            size > CACHE_CAPACITY
    }

    override suspend fun committedKeys(
        documentId: DocumentId,
        fingerprint: ExtractionFingerprint,
    ): Set<String> = reuseKeys(documentId, fingerprint, revisitFailedUnits = false)

    /**
     * The units an explicit retry may skip: only what an earlier attempt committed successfully.
     *
     * A failed unit is not skipped here, because revisiting it is what the retry asked for; a crash
     * resume through [committedKeys] takes the other answer on purpose.
     */
    override suspend fun retryKeys(
        documentId: DocumentId,
        fingerprint: ExtractionFingerprint,
    ): Set<String> = reuseKeys(documentId, fingerprint, revisitFailedUnits = true)

    /** Which keys this attempt may skip, after verifying that their artifacts are still there. */
    private fun reuseKeys(
        documentId: DocumentId,
        fingerprint: ExtractionFingerprint,
        revisitFailedUnits: Boolean,
    ): Set<String> {
        val root = artifactRoot(documentId)
        sweepKilledConversions(root, documentId)
        val reuse = if (revisitFailedUnits) {
            content.reusableSucceededCheckpoints(documentId, fingerprint, root)
        } else {
            content.reusableCheckpoints(documentId, fingerprint, root)
        }
        if (reuse.repaired.isNotEmpty()) {
            LOGGER.atWarn()
                .addKeyValue(COMPONENT_FIELD, EXTRACTION_COMPONENT)
                .addKeyValue(DOCUMENT_FIELD, documentId.value)
                .addKeyValue(REPAIRED_FIELD, reuse.repaired.size)
                .log("committed units whose artifacts no longer verify will be read again")
        }
        return reuse.skipKeys
    }

    override suspend fun deliver(
        documentId: DocumentId,
        fingerprint: ExtractionFingerprint,
        event: ExtractionEvent,
    ) {
        // The commit boundary is where an extraction unit becomes durable, so it is where the deletion
        // guard belongs: a unit committed for a document that is being deleted would outlive the document
        // it cites, and its row's foreign key would fail the whole attempt instead of skipping one file.
        if (documents.isDeletionTarget(documentId)) throw DocumentBeingDeletedException(documentId)
        val root = artifactRoot(documentId)
        when (event) {
            is ExtractionEvent.UnitReady -> {
                val commit = content.commitExtractedUnit(
                    documentId = documentId,
                    fingerprint = fingerprint,
                    key = event.key,
                    ordinal = event.ordinal,
                    draft = event.unit,
                    artifactRoot = root,
                )
                commit.artifactIssue?.let { issue ->
                    LOGGER.atWarn()
                        .addKeyValue(COMPONENT_FIELD, EXTRACTION_COMPONENT)
                        .addKeyValue(DOCUMENT_FIELD, documentId.value)
                        .addKeyValue(CODE_FIELD, issue.code)
                        .addKeyValue(REASON_FIELD, issue.reason)
                        .addKeyValue(ORDINAL_FIELD, event.ordinal)
                        .log("a unit's artifact could not be verified; its text was kept without the reference")
                }
                // A unit read by a tool means this document is in its OCR phase, and the phase is written
                // only after the unit is durable: a document must not read as "reading with OCR" while
                // nothing has been recorded by it yet.
                if (event.unit.method == ExtractionMethod.OCR) {
                    documents.updateStatus(documentId, DocumentStatus.OCR)
                }
            }

            is ExtractionEvent.UnitFailed -> content.commitFailedUnit(
                documentId = documentId,
                fingerprint = fingerprint,
                key = event.key,
                ordinal = event.ordinal,
                code = event.code,
            )

            is ExtractionEvent.Progress -> content.recordProgress(
                documentId = documentId,
                fingerprint = fingerprint,
                unitKind = event.unitKind,
                totalUnits = event.totalUnits,
            )

            is ExtractionEvent.Finished -> content.finishExtraction(
                documentId = documentId,
                fingerprint = fingerprint,
                metadata = event.metadata,
                totalUnits = event.totalUnits,
            )
        }
    }

    /** Resolves one document's artifact root, serving it from [artifactRoots] when it is already known. */
    private fun artifactRoot(documentId: DocumentId): Path {
        synchronized(artifactRoots) { artifactRoots[documentId]?.let { return it } }
        val document = documents.get(documentId)
            ?: error("a document's units were delivered after the document was deleted")
        val root = paths.artifactsDir(document.collectionId, document.id)
        synchronized(artifactRoots) { artifactRoots[documentId] = root }
        return root
    }

    /**
     * Removes the working directories a killed conversion left behind.
     *
     * A converter builds its output in a private directory inside the artifact root and renames the result
     * into place, so a run that was terminated — a timeout, a cancelled job, a lost process — leaves the
     * directory with nothing referring to it. The name is the converter's own prefix and cannot collide with
     * a fingerprint directory, which is a hash, so sweeping it here is exact rather than a guess.
     */
    private fun sweepKilledConversions(root: Path, documentId: DocumentId) {
        if (!Files.isDirectory(root)) return
        val leftovers = runCatching {
            Files.list(root).use { entries ->
                entries.filter { Files.isDirectory(it) && it.fileName.toString().startsWith(WORK_DIRECTORY_PREFIX) }
                    .toList()
            }
        }.getOrElse { failure ->
            // A directory that cannot be listed is the document's problem to report, not a reason to fail
            // the attempt that is about to read it.
            LOGGER.atWarn()
                .addKeyValue(COMPONENT_FIELD, EXTRACTION_COMPONENT)
                .addKeyValue(DOCUMENT_FIELD, documentId.value)
                .setCause(failure)
                .log("a document's artifact directory could not be listed for leftover conversions")
            return
        }
        leftovers.forEach { leftover ->
            runCatching { deleteRecursively(leftover) }
                .onFailure { failure ->
                    LOGGER.atWarn()
                        .addKeyValue(COMPONENT_FIELD, EXTRACTION_COMPONENT)
                        .addKeyValue(DOCUMENT_FIELD, documentId.value)
                        .setCause(failure)
                        .log("a killed conversion's working directory could not be removed")
                }
        }
    }

    private fun deleteRecursively(directory: Path) {
        Files.walk(directory).use { entries ->
            entries.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    private companion object {
        const val WORK_DIRECTORY_PREFIX = infoscry.extract.CalibreConverter.WORK_DIRECTORY_PREFIX

        /** How many documents' artifact roots stay resolved at once. */
        const val CACHE_CAPACITY = 64
        const val COMPONENT_FIELD = "component"
        const val DOCUMENT_FIELD = "document_id"
        const val CODE_FIELD = "code"
        const val REASON_FIELD = "reason"
        const val ORDINAL_FIELD = "unit_ordinal"
        const val REPAIRED_FIELD = "repaired_units"
        const val EXTRACTION_COMPONENT = "extraction"
    }
}

private val LOGGER = LoggerFactory.getLogger("infoscry.extraction")
