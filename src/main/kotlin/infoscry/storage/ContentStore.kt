package infoscry.storage

import infoscry.chunk.ChunkDraft
import infoscry.domain.Chunk
import infoscry.domain.ChunkId
import infoscry.domain.ContentUnit
import infoscry.domain.ContentUnitId
import infoscry.domain.DocumentId
import infoscry.domain.ExtractionMethod
import infoscry.domain.SourceLocation
import infoscry.domain.UnitKind
import infoscry.extract.ContentUnitDraft
import infoscry.extract.ExtractionFingerprint
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.Connection
import java.sql.ResultSet
import java.util.HexFormat
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * A unit's artifact was committed but no longer verifies.
 *
 * It is a state of the unit rather than a failure of the attempt: the text is still the text that was read,
 * so it is kept and cited, while the artifact reference is dropped because a reader must not be sent to
 * evidence that is not there. [reason] names the problem in words, never with a path — the reason travels
 * into logs, and a log that carries a filesystem path is a log that leaks one.
 */
data class ArtifactIssue(val code: String, val reason: String)

/** What committing one unit produced: the stored unit, and any artifact problem found while storing it. */
data class UnitCommit(val unit: ContentUnit, val artifactIssue: ArtifactIssue?)

/** One unit's checkpoint: whether an attempt got it, and the artifact it named if it did. */
data class UnitCheckpoint(
    val key: String,
    val ordinal: Int,
    val succeeded: Boolean,
    val errorCode: String?,
    val artifactRelativePath: String?,
    val artifactSha256: String?,
    val completedAt: String,
)

/**
 * What an attempt may skip, and what an earlier attempt's committed evidence no longer supports.
 *
 * [repaired] is the answer to a damaged artifact: the unit is read again instead of being trusted, and the
 * keys are named so the caller can report which units the archive had to repair.
 */
data class CheckpointReuse(val skipKeys: Set<String>, val repaired: List<String>)

/** The terminal marker of a complete extraction pass for one document. */
data class ExtractionMarker(
    val fingerprint: String,
    val totalUnits: Int,
    val failedUnits: Int,
    val metadata: Map<String, String>,
    val completedAt: String,
)

/** One unit of a document's structure: enough to list what a document contains without reading its text. */
data class ContentUnitSummary(val id: ContentUnitId, val ordinal: Int, val locator: SourceLocation)

/**
 * One page of a revision, as publishing that revision writes it into the published content.
 *
 * The page names its own unit id rather than letting the store mint one, because a revision owns the
 * identity its pages were read under and a published page has to keep it: a citation into page four of
 * the previous reading must still open page four after a replacement.
 */
data class RevisionPage(
    val unitId: ContentUnitId,
    val ordinal: Int,
    val locator: SourceLocation,
    val extractedText: String,
    val searchText: String,
    val extractionMethod: ExtractionMethod?,
    val meanConfidence: Double?,
    val artifactRelativePath: String?,
    val artifactSha256: String?,
    val chunks: List<ChunkDraft>,
) {
    init {
        require(ordinal >= 0) { "RevisionPage.ordinal must not be negative, was $ordinal" }
        require((artifactRelativePath == null) == (artifactSha256 == null)) {
            "a page's artifact reference is either complete or absent"
        }
    }
}

/**
 * How far one attempt of one document has got, read back from the rows the attempt committed.
 *
 * Nothing here is a stored counter. [processedUnits] and [failedUnits] are counts of the checkpoints the
 * attempt committed, so a resumed pass that skips what an earlier one already wrote cannot add to them: the
 * row it would have counted is the row it skipped. [totalUnits] is what the extractor announced, and stays
 * `null` when it announced nothing — a processed count without a denominator is a fact, a made-up
 * denominator is not.
 *
 * [directTextUnits] and [ocrUnits] are `null` when no committed unit of the attempt carries a stored method,
 * which is what a document committed before methods were recorded looks like. Zero would be a different
 * claim, and a false one.
 */
data class DocumentProgress(
    val unitKind: UnitKind?,
    val totalUnits: Int?,
    val processedUnits: Int,
    val failedUnits: Int,
    val directTextUnits: Int?,
    val ocrUnits: Int?,
    val failedCodes: List<String>,
)

/**
 * The durable content of documents: their units, the chunks built from them, and the checkpoints that make an
 * interrupted attempt resumable.
 *
 * Every write here happens inside the permit the caller already holds — the extraction sink commits inside
 * the unit boundary's permit, and the chunking pass runs one unit per stage. Nothing in this class asks the
 * mutation gate for admission, which is what keeps a collection deletion waiting for one bounded unit rather
 * than for a whole document.
 *
 * Two invariants are load-bearing:
 *
 * - **A unit commits with its artifact.** The text, the locator, the artifact reference and the checkpoint
 *   land in one transaction, so a crash cannot leave a citation pointing at evidence that was never written.
 * - **Re-chunking is not re-extraction.** Chunks live beside the extracted text and are replaced per unit, so
 *   another tokenizer or another budget rebuilds them without touching the text or the OCR checkpoints.
 */
class ContentStore(private val database: Database) {

    /**
     * Commits one extracted unit under the fingerprint the attempt is running with.
     *
     * The unit keeps its identity across attempts because its identity is `(document, ordinal)`: a citation
     * that points at the third page of a document does not stop pointing there because the file was read
     * again. When a commit replaces a unit's text, that unit's chunks are dropped and the document's chunking
     * marker is cleared in the same transaction — the old chunks measured text that is no longer there.
     */
    fun commitExtractedUnit(
        documentId: DocumentId,
        fingerprint: ExtractionFingerprint,
        key: String,
        ordinal: Int,
        draft: ContentUnitDraft,
        artifactRoot: Path,
    ): UnitCommit = database.transaction { connection ->
        require(key.isNotBlank()) { "a unit checkpoint needs a key" }
        require(ordinal >= 0) { "a unit ordinal must not be negative, was $ordinal" }

        val issue = artifactIssue(draft, artifactRoot)
        val storedPath = if (issue == null) draft.artifactRelativePath else null
        val storedSha = if (issue == null) draft.artifactSha256 else null
        val storedConfidence = if (issue == null) draft.meanConfidence else null
        val storedMethod = if (issue == null) draft.method else null
        val now = Instants.now()

        val existing = connection.selectUnit(documentId, ordinal)
        val changed = existing == null || existing.searchText != draft.searchText ||
            existing.extractedText != draft.extractedText
        val unit = upsertUnit(
            connection = connection,
            existing = existing,
            documentId = documentId,
            ordinal = ordinal,
            draft = draft,
            artifactRelativePath = storedPath,
            artifactSha256 = storedSha,
            meanConfidence = storedConfidence,
            extractionMethod = storedMethod,
            now = now,
        )

        if (changed && existing != null) {
            connection.deleteChunksOf(unit.id)
            connection.deleteChunkingMarker(documentId)
        }

        upsertCheckpoint(
            connection = connection,
            documentId = documentId,
            fingerprint = fingerprint,
            key = key,
            ordinal = ordinal,
            succeeded = issue == null,
            errorCode = issue?.code,
            artifactRelativePath = storedPath,
            artifactSha256 = storedSha,
            now = now,
        )
        // The attempt's own row, written in the same transaction as the unit it describes: what the UI
        // calls progress and what the store holds as committed text are then never two different states.
        connection.upsertProgress(
            documentId = documentId,
            fingerprint = fingerprint,
            unitKind = draft.locator.unitKind,
            totalUnits = null,
            now = now,
        )
        UnitCommit(unit = unit, artifactIssue = issue)
    }

    /**
     * Records what the extractor announced it has, before it has read all of it.
     *
     * The total is the extractor's statement, so an extractor that never announces one never gets a
     * denominator. Re-announcing inside the same attempt keeps the first row's `started_at`, because the
     * attempt has not restarted; announcing under a new fingerprint starts the row over, because it has.
     */
    fun recordProgress(
        documentId: DocumentId,
        fingerprint: ExtractionFingerprint,
        unitKind: UnitKind,
        totalUnits: Int,
    ) {
        require(totalUnits >= 0) { "totalUnits must not be negative, was $totalUnits" }
        database.transaction { connection ->
            connection.upsertProgress(
                documentId = documentId,
                fingerprint = fingerprint,
                unitKind = unitKind,
                totalUnits = totalUnits,
                now = Instants.now(),
            )
        }
    }

    /**
     * Records a unit an attempt could not read.
     *
     * The failure is a result like any other: it is durable so that a later attempt does not spend the same
     * time deriving it again, and it carries the code the extractor classified it with.
     */
    fun commitFailedUnit(
        documentId: DocumentId,
        fingerprint: ExtractionFingerprint,
        key: String,
        ordinal: Int,
        code: String,
    ) {
        require(key.isNotBlank()) { "a unit checkpoint needs a key" }
        require(code.isNotBlank()) { "a failed unit names a code" }
        require(ordinal >= 0) { "a unit ordinal must not be negative, was $ordinal" }
        database.transaction { connection ->
            upsertCheckpoint(
                connection = connection,
                documentId = documentId,
                fingerprint = fingerprint,
                key = key,
                ordinal = ordinal,
                succeeded = false,
                errorCode = code,
                artifactRelativePath = null,
                artifactSha256 = null,
                now = Instants.now(),
            )
        }
    }

    /** Every checkpoint one attempt committed under [fingerprint], successful or not. */
    fun loadCheckpoints(documentId: DocumentId, fingerprint: ExtractionFingerprint): List<UnitCheckpoint> =
        database.read { connection ->
            connection.prepareStatement(
                "SELECT unit_key, ordinal, outcome, error_code, artifact_relative_path, artifact_sha256, " +
                    "completed_at FROM extraction_checkpoints WHERE document_id = ? AND fingerprint = ? " +
                    "ORDER BY ordinal, unit_key",
            ).use { statement ->
                statement.setString(1, documentId.value)
                statement.setString(2, fingerprint.value)
                statement.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) {
                            add(
                                UnitCheckpoint(
                                    key = rows.getString("unit_key"),
                                    ordinal = rows.getInt("ordinal"),
                                    succeeded = rows.getString("outcome") == OUTCOME_EXTRACTED,
                                    errorCode = rows.getString("error_code"),
                                    artifactRelativePath = rows.getString("artifact_relative_path"),
                                    artifactSha256 = rows.getString("artifact_sha256"),
                                    completedAt = rows.getString("completed_at"),
                                ),
                            )
                        }
                    }
                }
            }
        }

    /**
     * The keys this attempt may skip, after checking that what they committed is still there.
     *
     * A committed artifact that is gone or changed invalidates its own unit and nothing else: the checkpoint
     * goes, the unit's unusable artifact reference goes with it, and the key is named in
     * [CheckpointReuse.repaired] so the caller can say which units had to be read again. The check is what
     * makes "skip what was already read" safe — a skip without it would reuse a hash of text whose word boxes
     * are no longer the ones the citation would open.
     *
     * A failed unit is a known result and is skipped too, which is what an interrupted attempt resuming from
     * its checkpoint wants. An explicit retry wants the opposite and uses [reusableSucceededCheckpoints].
     */
    fun reusableCheckpoints(
        documentId: DocumentId,
        fingerprint: ExtractionFingerprint,
        artifactRoot: Path,
    ): CheckpointReuse = reuse(documentId, fingerprint, artifactRoot, revisitFailedUnits = false)

    /**
     * The same answer for an explicit retry: only units that were committed *successfully* are reusable.
     *
     * A failed checkpoint is deliberately not reusable, because revisiting what failed is what an explicit
     * retry is for; its row is removed with the rest of the failures of this fingerprint, so the pass that
     * follows describes only the failures it actually found. A succeeded unit is still verified against its
     * artifact, so reuse never rests on evidence that is no longer there.
     */
    fun reusableSucceededCheckpoints(
        documentId: DocumentId,
        fingerprint: ExtractionFingerprint,
        artifactRoot: Path,
    ): CheckpointReuse = reuse(documentId, fingerprint, artifactRoot, revisitFailedUnits = true)

    /** The shared answer: which keys may be skipped, and which committed units have to be read again. */
    private fun reuse(
        documentId: DocumentId,
        fingerprint: ExtractionFingerprint,
        artifactRoot: Path,
        revisitFailedUnits: Boolean,
    ): CheckpointReuse {
        val skip = mutableSetOf<String>()
        val repaired = mutableListOf<String>()
        // The failures an explicit retry is about to derive again. Their rows go, because a checkpoint left
        // behind would outlive the failure it describes: the marker written at the end of the pass counts
        // every failed checkpoint of the fingerprint, so a refusal the new pass did not repeat would still
        // be reported as a warning about content that was read in full.
        val rederived = mutableListOf<String>()
        loadCheckpoints(documentId, fingerprint).forEach { checkpoint ->
            if (!checkpoint.succeeded) {
                if (revisitFailedUnits) rederived += checkpoint.key else skip += checkpoint.key
                return@forEach
            }
            val relative = checkpoint.artifactRelativePath
            if (relative == null) {
                // A unit with no artifact has nothing to verify, so its committed text is what stands.
                skip += checkpoint.key
                return@forEach
            }
            if (artifactStillMatches(artifactRoot, relative, checkpoint.artifactSha256)) {
                skip += checkpoint.key
            } else {
                repaired += checkpoint.key
            }
        }

        if (repaired.isNotEmpty() || rederived.isNotEmpty()) {
            database.transaction { connection ->
                loadCheckpoints(documentId, fingerprint)
                    .filter { it.key in repaired || it.key in rederived }
                    .forEach { checkpoint ->
                        connection.deleteCheckpoint(documentId, fingerprint, checkpoint.key)
                        if (checkpoint.key in repaired) connection.clearUnitArtifact(documentId, checkpoint.ordinal)
                    }
            }
        }
        return CheckpointReuse(skipKeys = skip, repaired = repaired)
    }

    /**
     * Records that the extractor reached the end of its document.
     *
     * Only a pass that says so writes this marker: a flow that stopped early must not be able to leave a
     * document looking extracted, which is why the marker is a row of its own rather than a count of pages.
     * [failedUnits] is read from the checkpoints instead of being supplied, because a resumed attempt never
     * saw the failures an earlier one committed.
     */
    fun finishExtraction(
        documentId: DocumentId,
        fingerprint: ExtractionFingerprint,
        metadata: Map<String, String>,
        totalUnits: Int,
    ): ExtractionMarker {
        require(totalUnits >= 0) { "totalUnits must not be negative, was $totalUnits" }
        val now = Instants.now()
        val failedUnits = database.transaction { connection ->
            // Read inside the same transaction that writes it, so the marker and its return value cannot
            // describe two different states of the document.
            val failed = connection.countFailedCheckpoints(documentId, fingerprint)
            connection.prepareStatement(
                "INSERT INTO document_extractions (document_id, fingerprint, total_units, failed_units, " +
                    "metadata, completed_at) VALUES (?, ?, ?, ?, ?, ?) " +
                    "ON CONFLICT (document_id) DO UPDATE SET fingerprint = excluded.fingerprint, " +
                    "total_units = excluded.total_units, failed_units = excluded.failed_units, " +
                    "metadata = excluded.metadata, completed_at = excluded.completed_at",
            ).use { statement ->
                statement.setString(1, documentId.value)
                statement.setString(2, fingerprint.value)
                statement.setInt(3, totalUnits)
                statement.setInt(4, failed)
                statement.setString(5, JSON.encodeToString(METADATA_SERIALIZER, metadata))
                statement.setString(6, now)
                statement.executeUpdate()
            }
            failed
        }
        return ExtractionMarker(
            fingerprint = fingerprint.value,
            totalUnits = totalUnits,
            failedUnits = failedUnits,
            metadata = metadata,
            completedAt = now,
        )
    }

    /** The terminal marker of a complete extraction pass, or `null` while the document is unfinished. */
    fun extractionMarker(documentId: DocumentId): ExtractionMarker? = database.read { connection ->
        connection.prepareStatement(
            "SELECT fingerprint, total_units, failed_units, metadata, completed_at FROM document_extractions " +
                "WHERE document_id = ?",
        ).use { statement ->
            statement.setString(1, documentId.value)
            statement.executeQuery().use { rows ->
                if (!rows.next()) {
                    null
                } else {
                    ExtractionMarker(
                        fingerprint = rows.getString("fingerprint"),
                        totalUnits = rows.getInt("total_units"),
                        failedUnits = rows.getInt("failed_units"),
                        metadata = JSON.decodeFromString(METADATA_SERIALIZER, rows.getString("metadata")),
                        completedAt = rows.getString("completed_at"),
                    )
                }
            }
        }
    }

    /**
     * Whether a document's chunks have to be built again.
     *
     * The chunking marker has to agree on the chunker, the tokenizer, the budget, the overlap **and** the
     * number of units: a pass that stopped halfway has chunks for a prefix of the document, and a marker that
     * only named the settings would let that prefix pass for the whole.
     */
    fun needsChunking(
        documentId: DocumentId,
        chunkerVersion: String,
        tokenizerId: String,
        maxSequenceTokens: Int,
        overlapTokens: Int,
    ): Boolean = database.read { connection ->
        val marker = connection.selectChunkingMarker(documentId)
        marker == null ||
            marker.chunkerVersion != chunkerVersion ||
            marker.tokenizerId != tokenizerId ||
            marker.maxSequenceTokens != maxSequenceTokens ||
            marker.overlapTokens != overlapTokens ||
            marker.unitCount != connection.countUnits(documentId)
    }

    /**
     * Replaces one unit's chunks.
     *
     * Deleted and re-inserted in one transaction so a reader never sees a unit half-chunked; the unit's own
     * identity is untouched, which is what keeps a citation valid across a re-chunk.
     */
    fun replaceUnitChunks(
        unitId: ContentUnitId,
        drafts: List<ChunkDraft>,
        chunkerVersion: String,
        tokenizerId: String,
        maxSequenceTokens: Int,
        overlapTokens: Int,
    ): Int {
        require(drafts.map { it.ordinal } == drafts.indices.toList()) {
            "chunk ordinals must count from zero without gaps, was ${drafts.map { it.ordinal }}"
        }
        val now = Instants.now()
        database.transaction { connection ->
            connection.deleteChunksOf(unitId)
            connection.prepareStatement(INSERT_CHUNK).use { statement ->
                drafts.forEach { draft ->
                    statement.setString(1, ChunkId.new().value)
                    statement.setString(2, unitId.value)
                    statement.setInt(3, draft.ordinal)
                    statement.setString(4, draft.text)
                    statement.setInt(5, draft.startOffset)
                    statement.setInt(6, draft.endOffset)
                    statement.setInt(7, draft.tokenCount)
                    statement.setInt(8, draft.tokenStart)
                    statement.setInt(9, draft.tokenEnd)
                    statement.setString(10, now)
                    statement.addBatch()
                }
                statement.executeBatch()
            }
        }
        return drafts.size
    }

    /**
     * Records that every unit of the document has been chunked under this configuration.
     *
     * Written at the end of the pass rather than per unit, because a pass that dies halfway left chunks for
     * some units: without this marker the next attempt would have no way to tell a finished document from a
     * half-chunked one.
     */
    fun finishChunking(
        documentId: DocumentId,
        chunkerVersion: String,
        tokenizerId: String,
        maxSequenceTokens: Int,
        overlapTokens: Int,
        unitCount: Int,
    ) {
        require(unitCount >= 0) { "unitCount must not be negative, was $unitCount" }
        database.transaction { connection ->
            connection.prepareStatement(
                "INSERT INTO document_chunking (document_id, chunker_version, tokenizer_id, " +
                    "max_sequence_tokens, overlap_tokens, chunk_count, unit_count, chunked_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?) " +
                    "ON CONFLICT (document_id) DO UPDATE SET chunker_version = excluded.chunker_version, " +
                    "tokenizer_id = excluded.tokenizer_id, " +
                    "max_sequence_tokens = excluded.max_sequence_tokens, " +
                    "overlap_tokens = excluded.overlap_tokens, chunk_count = excluded.chunk_count, " +
                    "unit_count = excluded.unit_count, chunked_at = excluded.chunked_at",
            ).use { statement ->
                statement.setString(1, documentId.value)
                statement.setString(2, chunkerVersion)
                statement.setString(3, tokenizerId)
                statement.setInt(4, maxSequenceTokens)
                statement.setInt(5, overlapTokens)
                statement.setInt(6, connection.countChunks(documentId))
                statement.setInt(7, unitCount)
                statement.setString(8, Instants.now())
                statement.executeUpdate()
            }
        }
    }

    /**
     * Replaces the published text and chunks of [documentId] with one revision's pages.
     *
     * This is the only write in this class that changes what a reader sees without reading the source
     * again, and it is deliberately one transaction: the published text, its chunks and the pages that
     * disappeared are one state, so a crash cannot leave a document whose text and chunks disagree.
     *
     * The revision is the whole document, so a unit whose ordinal the revision does not name is removed
     * — but only this document's units, and only from inside the caller's transaction, which is the
     * publication boundary that has already rechecked the document's lifecycle and deletion state.
     *
     * A page keeps its unit identity because the revision names it; a page whose identity changed at an
     * ordinal replaces the row that was there rather than leaving two units at one ordinal. The chunking
     * marker is left alone on purpose: the revision's chunks were measured by the same chunker that
     * wrote it, so the marker's tokenizer and version still describe them, and clearing it would make
     * the next rebuild re-chunk pages that are already correct.
     */
    fun publishRevision(documentId: DocumentId, pages: List<RevisionPage>): Int {
        require(pages.map { it.ordinal }.toSet().size == pages.size) {
            "a revision names one page per ordinal, was ${pages.map { it.ordinal }}"
        }
        val now = Instants.now()
        database.transaction { connection ->
            val published = pages.associateBy { it.ordinal }
            connection.listUnitIdentities(documentId).forEach { (ordinal, id) ->
                if (published[ordinal]?.unitId?.value != id) connection.deleteUnit(ContentUnitId(id))
            }
            pages.forEach { page ->
                connection.writeRevisionUnit(documentId, page, now)
                connection.deleteChunksOf(page.unitId)
                connection.prepareStatement(INSERT_CHUNK).use { statement ->
                    page.chunks.forEach { draft ->
                        statement.setString(1, ChunkId.new().value)
                        statement.setString(2, page.unitId.value)
                        statement.setInt(3, draft.ordinal)
                        statement.setString(4, draft.text)
                        statement.setInt(5, draft.startOffset)
                        statement.setInt(6, draft.endOffset)
                        statement.setInt(7, draft.tokenCount)
                        statement.setInt(8, draft.tokenStart)
                        statement.setInt(9, draft.tokenEnd)
                        statement.setString(10, now)
                        statement.addBatch()
                    }
                    statement.executeBatch()
                }
            }
        }
        return pages.size
    }

    /** One unit, or `null` when no unit has that identifier. */
    fun readUnit(unitId: ContentUnitId): ContentUnit? = database.read { connection ->
        connection.prepareStatement("$SELECT_UNITS WHERE id = ?").use { statement ->
            statement.setString(1, unitId.value)
            statement.executeQuery().use { rows -> if (rows.next()) rows.toContentUnit() else null }
        }
    }

    /**
     * A document's units in ordinal order, one batch at a time.
     *
     * The chunking pass walks a document unit by unit on purpose: a document can hold tens of thousands of
     * units, and one list of them would be both a memory cost and a permit held for the whole document.
     */
    fun listUnits(documentId: DocumentId, afterOrdinal: Int, limit: Int): List<ContentUnit> {
        require(limit > 0) { "limit must be positive, was $limit" }
        return database.read { connection ->
            connection.prepareStatement(
                "$SELECT_UNITS WHERE document_id = ? AND ordinal > ? ORDER BY ordinal LIMIT ?",
            ).use { statement ->
                statement.setString(1, documentId.value)
                statement.setInt(2, afterOrdinal)
                statement.setInt(3, limit)
                statement.executeQuery().use { rows ->
                    buildList { while (rows.next()) add(rows.toContentUnit()) }
                }
            }
        }
    }

    /** What a document contains, without the text: the ordinals and locators a structure listing needs. */
    fun listStructure(documentId: DocumentId): List<ContentUnitSummary> = database.read { connection ->
        connection.prepareStatement(
            "SELECT id, ordinal, locator_type, locator FROM content_units WHERE document_id = ? ORDER BY ordinal",
        ).use { statement ->
            statement.setString(1, documentId.value)
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        add(
                            ContentUnitSummary(
                                id = ContentUnitId(rows.getString("id")),
                                ordinal = rows.getInt("ordinal"),
                                locator = decodeLocator(rows.getString("locator_type"), rows.getString("locator")),
                            ),
                        )
                    }
                }
            }
        }
    }

    /** One unit's chunks in ordinal order. */
    fun chunksOf(unitId: ContentUnitId): List<Chunk> = database.read { connection ->
        connection.prepareStatement("$SELECT_CHUNKS WHERE content_unit_id = ? ORDER BY ordinal").use { statement ->
            statement.setString(1, unitId.value)
            statement.executeQuery().use { rows ->
                buildList { while (rows.next()) add(rows.toChunk()) }
            }
        }
    }

    /** How many chunks a document has, across its units. */
    fun chunkCount(documentId: DocumentId): Int = database.read { connection -> connection.countChunks(documentId) }

    /**
     * How far each of [documentIds] has got in its current attempt, for the ones that have one.
     *
     * Batched on purpose: the documents table shows a progress cell per row, and a page of fifty rows must
     * not become fifty round trips. A document with no attempt recorded at all — one that is still queued,
     * or was imported before any of this existed and has no summary either — is simply absent from the map
     * rather than being given a row of zeroes.
     */
    fun documentProgress(documentIds: Collection<DocumentId>): Map<DocumentId, DocumentProgress> {
        if (documentIds.isEmpty()) return emptyMap()
        val ids = documentIds.map { it.value }.distinct()
        return database.read { connection ->
            val attempts = connection.progressAttempts(ids)
            val counts = connection.progressCounts(ids)
            val committedKinds = connection.committedUnitKinds(ids)
            val failedCodes = connection.failedCheckpointCodes(ids)
            attempts.mapValues { (documentId, attempt) ->
                val counted = counts[documentId to attempt.fingerprint]
                val knownMethods = counted?.unitsWithMethod ?: 0
                DocumentProgress(
                    // What the attempt said its units are, or what the units it committed turned out to be.
                    unitKind = attempt.unitKind ?: committedKinds[documentId],
                    totalUnits = attempt.totalUnits,
                    processedUnits = counted?.processed ?: 0,
                    failedUnits = counted?.failed ?: 0,
                    directTextUnits = counted?.directText.takeIf { knownMethods > 0 },
                    ocrUnits = counted?.ocr.takeIf { knownMethods > 0 },
                    failedCodes = failedCodes[documentId to attempt.fingerprint].orEmpty(),
                )
            }
        }
    }

    private fun artifactIssue(draft: ContentUnitDraft, artifactRoot: Path): ArtifactIssue? {
        val relative = draft.artifactRelativePath ?: return null
        val sha = draft.artifactSha256 ?: return ArtifactIssue(
            code = ARTIFACT_UNVERIFIED_CODE,
            reason = "the unit names an artifact without a checksum, so nothing could be verified",
        )
        val resolved = resolveInside(artifactRoot, relative) ?: return ArtifactIssue(
            code = ARTIFACT_UNVERIFIED_CODE,
            reason = "the unit's artifact path does not stay inside the document's artifact root",
        )
        if (!Files.isRegularFile(resolved)) {
            return ArtifactIssue(
                code = ARTIFACT_UNVERIFIED_CODE,
                reason = "the unit's artifact was not written where the unit says it is",
            )
        }
        if (sha256Of(resolved) != sha) {
            return ArtifactIssue(
                code = ARTIFACT_UNVERIFIED_CODE,
                reason = "the unit's artifact does not match the checksum the unit was committed with",
            )
        }
        return null
    }

    private fun artifactStillMatches(artifactRoot: Path, relative: String, sha: String?): Boolean {
        if (sha == null) return false
        val resolved = resolveInside(artifactRoot, relative) ?: return false
        if (!Files.isRegularFile(resolved)) return false
        return sha256Of(resolved) == sha
    }

    /**
     * Resolves an artifact path inside its root, or nothing.
     *
     * An extractor writes a relative path, and a path that climbs out of the root is not an artifact of this
     * document whatever it points at, so it is refused rather than followed.
     */
    private fun resolveInside(root: Path, relative: String): Path? {
        val base = root.toAbsolutePath().normalize()
        val resolved = base.resolve(relative).normalize()
        return if (resolved.startsWith(base)) resolved else null
    }

    private fun sha256Of(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(DIGEST_BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return HexFormat.of().formatHex(digest.digest())
    }

    /**
     * The attempt each document is on: its own progress row when it has one, else its finished summary.
     *
     * The second half is what an older archive looks like. A document extracted before progress was
     * recorded has a summary and no progress row, and the summary already answers what the question is:
     * which attempt, and how many units it had. What it cannot answer — the method each unit was read by —
     * stays unknown rather than being filled in with a guess.
     */
    private fun Connection.progressAttempts(ids: List<String>): Map<DocumentId, Attempt> {
        val attempts = LinkedHashMap<DocumentId, Attempt>()
        readRows(SELECT_PROGRESS, ids) { rows ->
            while (rows.next()) {
                attempts[DocumentId(rows.getString("document_id"))] = Attempt(
                    fingerprint = rows.getString("fingerprint"),
                    unitKind = rows.getString("unit_kind")?.let(UnitKind::valueOf),
                    totalUnits = rows.getInt("total_units").takeUnless { rows.wasNull() },
                )
            }
        }
        readRows(SELECT_EXTRACTION_SUMMARIES, ids) { rows ->
            while (rows.next()) {
                attempts.putIfAbsent(
                    DocumentId(rows.getString("document_id")),
                    Attempt(
                        fingerprint = rows.getString("fingerprint"),
                        // An older document's unit kind is nowhere in its summary; the units below answer it.
                        unitKind = null,
                        totalUnits = rows.getInt("total_units"),
                    ),
                )
            }
        }
        return attempts
    }

    /** The attempt's committed and failed unit counts, and how many of its units carry a method. */
    private fun Connection.progressCounts(ids: List<String>): Map<Pair<DocumentId, String>, Counted> {
        val counts = mutableMapOf<Pair<DocumentId, String>, Counted>()
        readRows(COUNT_PROGRESS, ids) { rows ->
            while (rows.next()) {
                counts[DocumentId(rows.getString("document_id")) to rows.getString("fingerprint")] = Counted(
                    processed = rows.getInt("processed_units"),
                    failed = rows.getInt("failed_units"),
                    directText = rows.getInt("direct_text_units"),
                    ocr = rows.getInt("ocr_units"),
                    unitsWithMethod = rows.getInt("units_with_method"),
                )
            }
        }
        return counts
    }

    /** What each document's first committed unit is, for an attempt that announced no kind of its own. */
    private fun Connection.committedUnitKinds(ids: List<String>): Map<DocumentId, UnitKind> {
        val kinds = mutableMapOf<DocumentId, UnitKind>()
        readRows(SELECT_FIRST_UNIT_LOCATOR, ids) { rows ->
            while (rows.next()) {
                UNIT_KIND_BY_LOCATOR_TYPE[rows.getString("locator_type")]
                    ?.let { kind -> kinds[DocumentId(rows.getString("document_id"))] = kind }
            }
        }
        return kinds
    }

    /** The codes of the units each attempt could not read, distinct and ordered by code. */
    private fun Connection.failedCheckpointCodes(ids: List<String>): Map<Pair<DocumentId, String>, List<String>> {
        val codes = mutableMapOf<Pair<DocumentId, String>, MutableList<String>>()
        readRows(SELECT_FAILED_CODES, ids) { rows ->
            while (rows.next()) {
                val key = DocumentId(rows.getString("document_id")) to rows.getString("fingerprint")
                codes.getOrPut(key) { mutableListOf() } += rows.getString("error_code")
            }
        }
        return codes
    }

    /** Runs one of the batched progress reads, whose `%s` is the id list it binds in order. */
    private fun <T> Connection.readRows(template: String, ids: List<String>, block: (ResultSet) -> T): T =
        prepareStatement(template.format(ids.joinToString(",") { "?" })).use { statement ->
            ids.forEachIndexed { index, id -> statement.setString(index + 1, id) }
            statement.executeQuery().use(block)
        }

    /**
     * Writes the current attempt's row.
     *
     * A commit and an announcement both land here, which is why the update has to be explicit about what it
     * keeps: the same attempt keeps its start instant and a total it already announced, while a new
     * fingerprint starts the row over with no total at all, because the new attempt has announced nothing.
     * The unit kind survives either way — a document's format does not change because it is read again.
     */
    private fun Connection.upsertProgress(
        documentId: DocumentId,
        fingerprint: ExtractionFingerprint,
        unitKind: UnitKind?,
        totalUnits: Int?,
        now: String,
    ) {
        prepareStatement(UPSERT_PROGRESS).use { statement ->
            statement.setString(1, documentId.value)
            statement.setString(2, fingerprint.value)
            statement.setString(3, unitKind?.name)
            if (totalUnits == null) {
                statement.setNull(4, java.sql.Types.INTEGER)
            } else {
                statement.setInt(4, totalUnits)
            }
            statement.setString(5, now)
            statement.setString(6, now)
            statement.executeUpdate()
        }
    }

    private fun Connection.selectUnit(documentId: DocumentId, ordinal: Int): ContentUnit? =
        prepareStatement("$SELECT_UNITS WHERE document_id = ? AND ordinal = ?").use { statement ->
            statement.setString(1, documentId.value)
            statement.setInt(2, ordinal)
            statement.executeQuery().use { rows -> if (rows.next()) rows.toContentUnit() else null }
        }

    private fun upsertUnit(
        connection: Connection,
        existing: ContentUnit?,
        documentId: DocumentId,
        ordinal: Int,
        draft: ContentUnitDraft,
        artifactRelativePath: String?,
        artifactSha256: String?,
        meanConfidence: Double?,
        extractionMethod: ExtractionMethod?,
        now: String,
    ): ContentUnit {
        val id = existing?.id ?: ContentUnitId.new()
        connection.prepareStatement(
            "INSERT INTO content_units (id, document_id, ordinal, locator_type, locator, extracted_text, " +
                "search_text, artifact_relative_path, artifact_sha256, mean_confidence, extraction_method, " +
                "created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                "ON CONFLICT (document_id, ordinal) DO UPDATE SET locator_type = excluded.locator_type, " +
                "locator = excluded.locator, extracted_text = excluded.extracted_text, " +
                "search_text = excluded.search_text, " +
                "artifact_relative_path = excluded.artifact_relative_path, " +
                "artifact_sha256 = excluded.artifact_sha256, mean_confidence = excluded.mean_confidence, " +
                "extraction_method = excluded.extraction_method, updated_at = excluded.updated_at",
        ).use { statement ->
            statement.setString(1, id.value)
            statement.setString(2, documentId.value)
            statement.setInt(3, ordinal)
            statement.setString(4, locatorType(draft.locator))
            statement.setString(5, encodeLocator(draft.locator))
            statement.setString(6, draft.extractedText)
            statement.setString(7, draft.searchText)
            statement.setString(8, artifactRelativePath)
            statement.setString(9, artifactSha256)
            if (meanConfidence == null) {
                statement.setNull(10, java.sql.Types.REAL)
            } else {
                statement.setDouble(10, meanConfidence)
            }
            statement.setString(11, extractionMethod?.name)
            statement.setString(12, now)
            statement.setString(13, now)
            statement.executeUpdate()
        }
        return checkNotNull(connection.selectUnit(documentId, ordinal)) {
            "the unit that was just written is not readable back"
        }.copy(id = id)
    }

    private fun upsertCheckpoint(
        connection: Connection,
        documentId: DocumentId,
        fingerprint: ExtractionFingerprint,
        key: String,
        ordinal: Int,
        succeeded: Boolean,
        errorCode: String?,
        artifactRelativePath: String?,
        artifactSha256: String?,
        now: String,
    ) {
        connection.prepareStatement(
            "INSERT INTO extraction_checkpoints (id, document_id, fingerprint, unit_key, ordinal, outcome, " +
                "error_code, artifact_relative_path, artifact_sha256, completed_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                "ON CONFLICT (document_id, fingerprint, unit_key) DO UPDATE SET ordinal = excluded.ordinal, " +
                "outcome = excluded.outcome, error_code = excluded.error_code, " +
                "artifact_relative_path = excluded.artifact_relative_path, " +
                "artifact_sha256 = excluded.artifact_sha256, completed_at = excluded.completed_at",
        ).use { statement ->
            statement.setString(1, java.util.UUID.randomUUID().toString())
            statement.setString(2, documentId.value)
            statement.setString(3, fingerprint.value)
            statement.setString(4, key)
            statement.setInt(5, ordinal)
            statement.setString(6, if (succeeded) OUTCOME_EXTRACTED else OUTCOME_FAILED)
            statement.setString(7, errorCode)
            statement.setString(8, artifactRelativePath)
            statement.setString(9, artifactSha256)
            statement.setString(10, now)
            statement.executeUpdate()
        }
    }

    private fun Connection.deleteCheckpoint(documentId: DocumentId, fingerprint: ExtractionFingerprint, key: String) {
        prepareStatement(
            "DELETE FROM extraction_checkpoints WHERE document_id = ? AND fingerprint = ? AND unit_key = ?",
        ).use { statement ->
            statement.setString(1, documentId.value)
            statement.setString(2, fingerprint.value)
            statement.setString(3, key)
            statement.executeUpdate()
        }
    }

    private fun Connection.clearUnitArtifact(documentId: DocumentId, ordinal: Int) {
        prepareStatement(
            "UPDATE content_units SET artifact_relative_path = NULL, artifact_sha256 = NULL, updated_at = ? " +
                "WHERE document_id = ? AND ordinal = ?",
        ).use { statement ->
            statement.setString(1, Instants.now())
            statement.setString(2, documentId.value)
            statement.setInt(3, ordinal)
            statement.executeUpdate()
        }
    }

    /** The ordinal and identity of every unit [documentId] currently publishes, for a revision to replace. */
    private fun Connection.listUnitIdentities(documentId: DocumentId): List<Pair<Int, String>> =
        prepareStatement("SELECT ordinal, id FROM content_units WHERE document_id = ?").use { statement ->
            statement.setString(1, documentId.value)
            statement.executeQuery().use { rows ->
                buildList { while (rows.next()) add(rows.getInt("ordinal") to rows.getString("id")) }
            }
        }

    private fun Connection.deleteUnit(unitId: ContentUnitId) {
        prepareStatement("DELETE FROM content_units WHERE id = ?").use { statement ->
            statement.setString(1, unitId.value)
            statement.executeUpdate()
        }
    }

    /** Writes one page of a revision by its own identity, so publishing cannot rename a page. */
    private fun Connection.writeRevisionUnit(documentId: DocumentId, page: RevisionPage, now: String) {
        prepareStatement(
            "INSERT INTO content_units (id, document_id, ordinal, locator_type, locator, extracted_text, " +
                "search_text, artifact_relative_path, artifact_sha256, mean_confidence, extraction_method, " +
                "created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                "ON CONFLICT (id) DO UPDATE SET document_id = excluded.document_id, " +
                "ordinal = excluded.ordinal, locator_type = excluded.locator_type, " +
                "locator = excluded.locator, extracted_text = excluded.extracted_text, " +
                "search_text = excluded.search_text, " +
                "artifact_relative_path = excluded.artifact_relative_path, " +
                "artifact_sha256 = excluded.artifact_sha256, mean_confidence = excluded.mean_confidence, " +
                "extraction_method = excluded.extraction_method, updated_at = excluded.updated_at",
        ).use { statement ->
            statement.setString(1, page.unitId.value)
            statement.setString(2, documentId.value)
            statement.setInt(3, page.ordinal)
            statement.setString(4, locatorType(page.locator))
            statement.setString(5, encodeLocator(page.locator))
            statement.setString(6, page.extractedText)
            statement.setString(7, page.searchText)
            statement.setString(8, page.artifactRelativePath)
            statement.setString(9, page.artifactSha256)
            if (page.meanConfidence == null) {
                statement.setNull(10, java.sql.Types.REAL)
            } else {
                statement.setDouble(10, page.meanConfidence)
            }
            statement.setString(11, page.extractionMethod?.name)
            statement.setString(12, now)
            statement.setString(13, now)
            statement.executeUpdate()
        }
    }

    private fun Connection.deleteChunksOf(unitId: ContentUnitId) {
        prepareStatement("DELETE FROM chunks WHERE content_unit_id = ?").use { statement ->
            statement.setString(1, unitId.value)
            statement.executeUpdate()
        }
    }

    private fun Connection.deleteChunkingMarker(documentId: DocumentId) {
        prepareStatement("DELETE FROM document_chunking WHERE document_id = ?").use { statement ->
            statement.setString(1, documentId.value)
            statement.executeUpdate()
        }
    }

    private fun Connection.selectChunkingMarker(documentId: DocumentId): ChunkingMarker? =
        prepareStatement(
            "SELECT chunker_version, tokenizer_id, max_sequence_tokens, overlap_tokens, unit_count " +
                "FROM document_chunking WHERE document_id = ?",
        ).use { statement ->
            statement.setString(1, documentId.value)
            statement.executeQuery().use { rows ->
                if (!rows.next()) {
                    null
                } else {
                    ChunkingMarker(
                        chunkerVersion = rows.getString("chunker_version"),
                        tokenizerId = rows.getString("tokenizer_id"),
                        maxSequenceTokens = rows.getInt("max_sequence_tokens"),
                        overlapTokens = rows.getInt("overlap_tokens"),
                        unitCount = rows.getInt("unit_count"),
                    )
                }
            }
        }

    private fun Connection.countUnits(documentId: DocumentId): Int =
        count("SELECT COUNT(*) FROM content_units WHERE document_id = ?", documentId)

    private fun Connection.countChunks(documentId: DocumentId): Int =
        count(
            "SELECT COUNT(*) FROM chunks c JOIN content_units u ON u.id = c.content_unit_id " +
                "WHERE u.document_id = ?",
            documentId,
        )

    private fun Connection.countFailedCheckpoints(
        documentId: DocumentId,
        fingerprint: ExtractionFingerprint,
    ): Int = prepareStatement(
        "SELECT COUNT(*) FROM extraction_checkpoints WHERE document_id = ? AND fingerprint = ? AND outcome = ?",
    ).use { statement ->
        statement.setString(1, documentId.value)
        statement.setString(2, fingerprint.value)
        statement.setString(3, OUTCOME_FAILED)
        statement.executeQuery().use { rows ->
            rows.next()
            rows.getInt(1)
        }
    }

    private fun Connection.count(sql: String, documentId: DocumentId): Int =
        prepareStatement(sql).use { statement ->
            statement.setString(1, documentId.value)
            statement.executeQuery().use { rows ->
                rows.next()
                rows.getInt(1)
            }
        }

    private fun ResultSet.toContentUnit(): ContentUnit = ContentUnit(
        id = ContentUnitId(getString("id")),
        documentId = DocumentId(getString("document_id")),
        ordinal = getInt("ordinal"),
        locator = decodeLocator(getString("locator_type"), getString("locator")),
        extractedText = getString("extracted_text"),
        searchText = getString("search_text"),
        artifactRelativePath = getString("artifact_relative_path"),
        artifactSha256 = getString("artifact_sha256"),
        meanConfidence = getDouble("mean_confidence").takeUnless { wasNull() },
        extractionMethod = getString("extraction_method")?.let(ExtractionMethod::valueOf),
    )

    private fun ResultSet.toChunk(): Chunk = Chunk(
        id = ChunkId(getString("id")),
        contentUnitId = ContentUnitId(getString("content_unit_id")),
        ordinal = getInt("ordinal"),
        text = getString("text"),
        startOffset = getInt("start_offset"),
        endOffset = getInt("end_offset"),
        tokenCount = getInt("token_count"),
        tokenStart = getInt("token_start"),
        tokenEnd = getInt("token_end"),
    )

    /** One document's current attempt: which one it is, and what it said about its own units. */
    private data class Attempt(val fingerprint: String, val unitKind: UnitKind?, val totalUnits: Int?)

    /** What one attempt's checkpoints add up to. */
    private data class Counted(
        val processed: Int,
        val failed: Int,
        val directText: Int,
        val ocr: Int,
        val unitsWithMethod: Int,
    )

    private data class ChunkingMarker(
        val chunkerVersion: String,
        val tokenizerId: String,
        val maxSequenceTokens: Int,
        val overlapTokens: Int,
        val unitCount: Int,
    )

    companion object {

        /** The code a unit's artifact fails under when it was committed but cannot be verified. */
        const val ARTIFACT_UNVERIFIED_CODE: String = "ARTIFACT_UNVERIFIED"

        private const val OUTCOME_EXTRACTED = "EXTRACTED"
        private const val OUTCOME_FAILED = "FAILED"

        private const val UNIT_COLUMNS =
            "id, document_id, ordinal, locator_type, locator, extracted_text, search_text, " +
                "artifact_relative_path, artifact_sha256, mean_confidence, extraction_method"

        /** The current attempt's own row, written by the migration that made it possible to keep one. */
        private const val SELECT_PROGRESS =
            "SELECT document_id, fingerprint, unit_kind, total_units FROM document_extraction_progress " +
                "WHERE document_id IN (%s)"

        /** The finished summaries, which are all an archive written before progress existed has. */
        private const val SELECT_EXTRACTION_SUMMARIES =
            "SELECT document_id, fingerprint, total_units FROM document_extractions " +
                "WHERE document_id IN (%s)"

        /**
         * What one attempt's checkpoints add up to.
         *
         * Counted from the checkpoints rather than from stored counters, so a resumed attempt that skips a
         * committed unit cannot count it twice: the row it would have counted is the row it skipped. The
         * method counts are joined on the unit the checkpoint stands for, which is where the method lives.
         */
        private val COUNT_PROGRESS = """
            SELECT c.document_id AS document_id,
                   c.fingerprint AS fingerprint,
                   SUM(CASE WHEN c.outcome = 'EXTRACTED' THEN 1 ELSE 0 END) AS processed_units,
                   SUM(CASE WHEN c.outcome = 'FAILED' THEN 1 ELSE 0 END) AS failed_units,
                   SUM(CASE WHEN c.outcome = 'EXTRACTED' AND u.extraction_method = 'DIRECT_TEXT'
                            THEN 1 ELSE 0 END) AS direct_text_units,
                   SUM(CASE WHEN c.outcome = 'EXTRACTED' AND u.extraction_method = 'OCR'
                            THEN 1 ELSE 0 END) AS ocr_units,
                   SUM(CASE WHEN c.outcome = 'EXTRACTED' AND u.extraction_method IS NOT NULL
                            THEN 1 ELSE 0 END) AS units_with_method
            FROM extraction_checkpoints c
            LEFT JOIN content_units u ON u.document_id = c.document_id AND u.ordinal = c.ordinal
            WHERE c.document_id IN (%s)
            GROUP BY c.document_id, c.fingerprint
        """.trimIndent()

        /** The lowest-ordinal committed unit of each document, which says what its units are. */
        private const val SELECT_FIRST_UNIT_LOCATOR =
            "SELECT document_id, locator_type FROM content_units cu WHERE document_id IN (%s) " +
                "AND ordinal = (SELECT MIN(ordinal) FROM content_units m WHERE m.document_id = cu.document_id)"

        /** The distinct codes of the units the attempt could not read, for the sentences shown beside them. */
        private const val SELECT_FAILED_CODES =
            "SELECT DISTINCT document_id, fingerprint, error_code FROM extraction_checkpoints " +
                "WHERE outcome = 'FAILED' AND error_code IS NOT NULL AND document_id IN (%s) " +
                "ORDER BY document_id, error_code"

        /**
         * The current attempt's row, written by a commit or by an announcement.
         *
         * A commit carries no total (`NULL`), so the case below is what decides: inside the same attempt the
         * total already announced is kept, and under a new fingerprint the row starts over with nothing
         * announced yet. The kind is kept across attempts because a document's format does not change.
         */
        private val UPSERT_PROGRESS = """
            INSERT INTO document_extraction_progress
                (document_id, fingerprint, unit_kind, total_units, started_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (document_id) DO UPDATE SET
                fingerprint = excluded.fingerprint,
                unit_kind = COALESCE(document_extraction_progress.unit_kind, excluded.unit_kind),
                total_units = CASE WHEN document_extraction_progress.fingerprint = excluded.fingerprint
                                   THEN COALESCE(document_extraction_progress.total_units, excluded.total_units)
                                   ELSE excluded.total_units END,
                started_at = CASE WHEN document_extraction_progress.fingerprint = excluded.fingerprint
                                  THEN document_extraction_progress.started_at ELSE excluded.started_at END,
                updated_at = excluded.updated_at
        """.trimIndent()

        /**
         * The unit kind each stored locator type means.
         *
         * The locator type names are stable database spellings (see [locatorType]), and this is their other
         * direction: a document whose attempt announced no kind of its own still has units, and what they
         * are is a fact about the document rather than something to guess.
         */
        private val UNIT_KIND_BY_LOCATOR_TYPE = mapOf(
            "pdf_page" to UnitKind.PAGE,
            "image" to UnitKind.IMAGE,
            "slide" to UnitKind.SLIDE,
            "text_lines" to UnitKind.LINE,
            "spreadsheet_range" to UnitKind.SHEET,
            "html_section" to UnitKind.SECTION,
            "ebook_section" to UnitKind.SECTION,
            "word_section" to UnitKind.SECTION,
        )

        private const val SELECT_UNITS = "SELECT $UNIT_COLUMNS FROM content_units"

        private const val SELECT_CHUNKS =
            "SELECT id, content_unit_id, ordinal, text, start_offset, end_offset, token_count, token_start, " +
                "token_end FROM chunks"

        private const val INSERT_CHUNK =
            "INSERT INTO chunks (id, content_unit_id, ordinal, text, start_offset, end_offset, token_count, " +
                "token_start, token_end, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"

        private val JSON = Json { ignoreUnknownKeys = true }

        /**
         * How much of an artifact is read at a time when it is verified.
         *
         * The digest runs while a mutation permit is held, and an artifact can be a hundred megabytes of word
         * boxes, so the checksum streams rather than materialising the file the way a one-shot read would.
         */
        private const val DIGEST_BUFFER_BYTES = 64 * 1024

        private val METADATA_SERIALIZER = MapSerializer(String.serializer(), String.serializer())

        /** The stable names a locator is stored under, so a package rename does not orphan a citation. */
        private fun locatorType(locator: SourceLocation): String = when (locator) {
            is SourceLocation.PdfPage -> "pdf_page"
            is SourceLocation.Image -> "image"
            is SourceLocation.WordSection -> "word_section"
            is SourceLocation.SpreadsheetRange -> "spreadsheet_range"
            is SourceLocation.Slide -> "slide"
            is SourceLocation.TextLines -> "text_lines"
            is SourceLocation.HtmlSection -> "html_section"
            is SourceLocation.EbookSection -> "ebook_section"
        }

        private fun encodeLocator(locator: SourceLocation): String =
            JSON.encodeToString(SourceLocation.serializer(), locator)

        private fun decodeLocator(type: String, json: String): SourceLocation {
            val decoded = JSON.decodeFromString(SourceLocation.serializer(), json)
            check(locatorType(decoded) == type) {
                "the stored locator type '$type' does not match the locator it holds"
            }
            return decoded
        }
    }
}
