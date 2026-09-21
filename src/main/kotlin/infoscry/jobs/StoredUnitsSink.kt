package infoscry.jobs

import infoscry.config.AppPaths
import infoscry.domain.DocumentId
import infoscry.extract.ExtractionEvent
import infoscry.extract.ExtractionFingerprint
import infoscry.extract.ExtractionSink
import infoscry.storage.ContentStore
import infoscry.storage.DocumentStore
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
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
 */
class StoredUnitsSink(
    private val paths: AppPaths,
    private val documents: DocumentStore,
    private val content: ContentStore,
) : ExtractionSink {

    override val storesUnits: Boolean = true

    /** Where each document's artifacts live, resolved once per document for the life of this sink. */
    private val artifactRoots = ConcurrentHashMap<DocumentId, Path>()

    override suspend fun committedKeys(
        documentId: DocumentId,
        fingerprint: ExtractionFingerprint,
    ): Set<String> {
        val root = artifactRoot(documentId)
        sweepKilledConversions(root, documentId)
        val reuse = content.reusableCheckpoints(documentId, fingerprint, root)
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
            }

            is ExtractionEvent.UnitFailed -> content.commitFailedUnit(
                documentId = documentId,
                fingerprint = fingerprint,
                key = event.key,
                ordinal = event.ordinal,
                code = event.code,
            )

            is ExtractionEvent.Finished -> content.finishExtraction(
                documentId = documentId,
                fingerprint = fingerprint,
                metadata = event.metadata,
                totalUnits = event.totalUnits,
            )
        }
    }

    private fun artifactRoot(documentId: DocumentId): Path = artifactRoots.computeIfAbsent(documentId) { id ->
        val document = documents.get(id)
            ?: error("a document's units were delivered after the document was deleted")
        paths.artifactsDir(document.collectionId, document.id)
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
