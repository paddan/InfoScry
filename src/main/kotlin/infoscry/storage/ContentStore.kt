package infoscry.storage

import infoscry.chunk.ChunkDraft
import infoscry.domain.Chunk
import infoscry.domain.ChunkId
import infoscry.domain.ContentUnit
import infoscry.domain.ContentUnitId
import infoscry.domain.DocumentId
import infoscry.domain.SourceLocation
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
        UnitCommit(unit = unit, artifactIssue = issue)
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
     */
    fun reusableCheckpoints(
        documentId: DocumentId,
        fingerprint: ExtractionFingerprint,
        artifactRoot: Path,
    ): CheckpointReuse {
        val skip = mutableSetOf<String>()
        val repaired = mutableListOf<String>()
        loadCheckpoints(documentId, fingerprint).forEach { checkpoint ->
            val relative = checkpoint.artifactRelativePath
            if (!checkpoint.succeeded || relative == null) {
                // A failed unit is a known result, and a unit with no artifact has nothing to verify.
                skip += checkpoint.key
                return@forEach
            }
            if (artifactStillMatches(artifactRoot, relative, checkpoint.artifactSha256)) {
                skip += checkpoint.key
            } else {
                repaired += checkpoint.key
            }
        }

        if (repaired.isNotEmpty()) {
            database.transaction { connection ->
                loadCheckpoints(documentId, fingerprint)
                    .filter { it.key in repaired }
                    .forEach { checkpoint ->
                        connection.deleteCheckpoint(documentId, fingerprint, checkpoint.key)
                        connection.clearUnitArtifact(documentId, checkpoint.ordinal)
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
        now: String,
    ): ContentUnit {
        val id = existing?.id ?: ContentUnitId.new()
        connection.prepareStatement(
            "INSERT INTO content_units (id, document_id, ordinal, locator_type, locator, extracted_text, " +
                "search_text, artifact_relative_path, artifact_sha256, mean_confidence, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                "ON CONFLICT (document_id, ordinal) DO UPDATE SET locator_type = excluded.locator_type, " +
                "locator = excluded.locator, extracted_text = excluded.extracted_text, " +
                "search_text = excluded.search_text, " +
                "artifact_relative_path = excluded.artifact_relative_path, " +
                "artifact_sha256 = excluded.artifact_sha256, mean_confidence = excluded.mean_confidence, " +
                "updated_at = excluded.updated_at",
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
            statement.setString(11, now)
            statement.setString(12, now)
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
                "artifact_relative_path, artifact_sha256, mean_confidence"

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
