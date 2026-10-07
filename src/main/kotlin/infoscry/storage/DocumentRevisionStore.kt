package infoscry.storage

import infoscry.chunk.ChunkDraft
import infoscry.domain.CollectionId
import infoscry.domain.ContentUnitId
import infoscry.domain.DocumentId
import infoscry.domain.ExtractionMethod
import infoscry.domain.SourceImageProvenance
import infoscry.domain.SourceImageRoot
import infoscry.domain.SourceLocation
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Types
import java.util.HexFormat
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** What a revision is to the document it belongs to. */
enum class RevisionState {

    /** Staged and not published: no reader may see its text. */
    CANDIDATE,

    /** The revision `document_active_revisions` names for its document. */
    PUBLISHED,

    /** A revision that was published and then replaced. Its text stays readable, its rows do not. */
    SUPERSEDED,

    /** A candidate the stager gave up on. Kept so the identifier is never reused. */
    WITHDRAWN,
}

/** The review decision of one page in one revision. Only approved pages may be published. */
enum class PageApproval { APPROVED, PENDING, REJECTED }

/** Where one publication attempt got to. */
enum class PublicationPhase { PREPARED, PUBLISHED, ABANDONED, REFUSED }

/** Where one restore request got to. */
enum class RestorePhase { STAGED, PUBLISHED, FAILED }

/**
 * One request to restore a historical revision, exactly as it is persisted.
 *
 * [newRevisionId] is the revision the restore created: a candidate while the request is [RestorePhase.STAGED],
 * the document's published text once it is [RestorePhase.PUBLISHED], and a withdrawn one when it
 * [RestorePhase.FAILED]. [restoredFromRevisionId] is the historical revision whose page texts it copied.
 */
data class RevisionRestoreRecord(
    val restoreId: String,
    val collectionId: CollectionId,
    val documentId: DocumentId,
    val requestId: String,
    val requestHash: String,
    val expectedRevisionId: String,
    val restoredFromRevisionId: String,
    val newRevisionId: String,
    val phase: RestorePhase,
    val errorCode: String?,
    val errorMessage: String?,
    val createdAt: String,
    val updatedAt: String,
)

/** What a page's text is, without the text: enough to say whether two readings of it differ. */
data class PageDigest(
    val ordinal: Int,
    val unitId: ContentUnitId,
    /** The hash of the page's extracted text, computed when the revision predates recorded hashes. */
    val textSha256: String,
    val extractionMethod: ExtractionMethod?,
    val approval: PageApproval,
)

/** One immutable reading of a document's text. */
data class DocumentRevision(
    val id: String,
    val documentId: DocumentId,
    val parentRevisionId: String?,
    val state: RevisionState,
    val provenance: String,
    val createdAt: String,
)

/**
 * One page's text as one revision read it.
 *
 * [sourceImage] is the image that text was read from, absent when no image was read at all. It is the
 * reading's own evidence rather than a description of the document: a page read from a bounded copy of an
 * oversized picture names that copy, so a reader shown the page can open the pixels behind its text instead
 * of assuming they were the managed original's.
 */
data class RevisionPageText(
    val revisionId: String,
    val ordinal: Int,
    val unitId: ContentUnitId,
    val locator: SourceLocation,
    val extractedText: String,
    val searchText: String,
    val textSha256: String?,
    val extractionMethod: ExtractionMethod?,
    val meanConfidence: Double?,
    val artifactRelativePath: String?,
    val artifactSha256: String?,
    val approval: PageApproval,
    val sourceImage: SourceImageProvenance? = null,
)

/**
 * One page as a stager hands it over, before it belongs to a revision.
 *
 * [sourceImage] is the image the page's text was read from, and it stays absent for a page that was read
 * without one — a text layer a parser read, a unit of a format that has no raster. The provenance carries
 * its own completeness ([SourceImageProvenance]), so a stager either names the artifact it read or names
 * nothing; a partially named image is refused rather than stored.
 */
data class RevisionPageDraft(
    val ordinal: Int,
    val unitId: ContentUnitId,
    val locator: SourceLocation,
    val extractedText: String,
    val searchText: String,
    val extractionMethod: ExtractionMethod? = null,
    val meanConfidence: Double? = null,
    val artifactRelativePath: String? = null,
    val artifactSha256: String? = null,
    val sourceImage: SourceImageProvenance? = null,
    val approval: PageApproval = PageApproval.PENDING,
    val chunks: List<RevisionChunkDraft> = emptyList(),
) {
    init {
        require(ordinal >= 0) { "RevisionPageDraft.ordinal must not be negative, was $ordinal" }
        require((artifactRelativePath == null) == (artifactSha256 == null)) {
            "a page's artifact reference is either complete or absent"
        }
    }
}

/**
 * One chunk of one page as a stager hands it over, with the vector it was embedded to.
 *
 * The vector travels with the chunk because staging an embedding is the expensive half, and a
 * publication that has to be recovered must be completable without the accelerator.
 */
data class RevisionChunkDraft(
    val ordinal: Int,
    val text: String,
    val startOffset: Int,
    val endOffset: Int,
    val tokenCount: Int,
    val tokenStart: Int,
    val tokenEnd: Int,
    val embedding: FloatArray? = null,
) {
    init {
        require(ordinal >= 0) { "RevisionChunkDraft.ordinal must not be negative, was $ordinal" }
        require(text.isNotEmpty()) { "a chunk carries text" }
        require(startOffset >= 0) { "RevisionChunkDraft.startOffset must not be negative, was $startOffset" }
        require(endOffset > startOffset) {
            "RevisionChunkDraft.endOffset ($endOffset) must follow startOffset ($startOffset)"
        }
        require(tokenCount >= 0) { "RevisionChunkDraft.tokenCount must not be negative, was $tokenCount" }
        require(tokenStart in 0..<tokenCount) {
            "RevisionChunkDraft.tokenStart ($tokenStart) is outside the passage"
        }
        require(tokenEnd in tokenStart..<tokenCount) {
            "RevisionChunkDraft.tokenEnd ($tokenEnd) must follow tokenStart ($tokenStart) inside the passage"
        }
    }
}

/** One chunk of one page as a revision stored it. */
data class RevisionChunk(
    val unitOrdinal: Int,
    val ordinal: Int,
    val text: String,
    val startOffset: Int,
    val endOffset: Int,
    val tokenCount: Int,
    val tokenStart: Int,
    val tokenEnd: Int,
    val embedding: FloatArray?,
) {
    /** Whether this chunk is complete enough to stage: a chunk without a vector cannot be indexed. */
    val isStaged: Boolean get() = embedding != null
}

/**
 * One recoverable publication attempt, exactly as it is persisted.
 *
 * [authoritativeAt] is the one field recovery decides on: it is written by the same transaction that
 * makes the target revision authoritative, so an intent that carries it is a publication that has to be
 * finished rather than a failure — which is why this type exposes no "failed" phase at all.
 */
data class PublicationIntent(
    val id: String,
    val documentId: DocumentId,
    val collectionId: CollectionId,
    val baseRevisionId: String?,
    val targetRevisionId: String,
    val phase: PublicationPhase,
    val authoritativeAt: String?,
    val preparedAt: String,
    val publishedAt: String?,
    /** When the rows of the reading this attempt replaced were removed from the index. */
    val cleanedAt: String?,
    val errorCode: String?,
    val errorMessage: String?,
)

/**
 * The immutable revisions of documents: their page texts, their chunks and vectors, which revision is
 * published, and the publication attempts that are still in flight.
 *
 * Three things this class deliberately does *not* do, because they are the reason it exists:
 *
 * - **A candidate cannot touch published content.** [stageCandidate] and [appendPage] write only
 *   `page_text_revisions` and `revision_chunks`. `content_units`, `chunks` and the active-revision
 *   pointer are written by [makeAuthoritative] alone.
 * - **A revision is never edited.** A later reading is a new revision whose parent names the previous
 *   one, so a saved excerpt read back from its own revision still says what it said.
 * - **Nothing here decides when a publication is allowed.** The deletion, lifecycle and maintenance
 *   rechecks belong to the publication boundary; this class only records what the boundary decided.
 *
 * Every write composes with the caller's transaction (SQLite savepoints), so the boundary's authority
 * commit is one transaction covering the published text, its chunks and the marker that says which side
 * of the handoff a crash landed on.
 */
class DocumentRevisionStore(private val database: Database, private val content: ContentStore) {

    // ---- Reading revisions and their text ----

    /** One revision, or `null` when no revision has that identifier. */
    fun revision(id: String): DocumentRevision? = database.read { connection -> connection.selectRevision(id) }

    /** One document's revisions, oldest first, so a reader can walk the history of its text. */
    fun revisions(documentId: DocumentId): List<DocumentRevision> = database.read { connection ->
        connection.prepareStatement("$SELECT_REVISIONS WHERE document_id = ? ORDER BY created_at, rowid").use { statement ->
            statement.setString(1, documentId.value)
            statement.executeQuery().use { rows ->
                buildList { while (rows.next()) add(rows.toRevision()) }
            }
        }
    }

    /** The revision [documentId] currently publishes, or `null` when it has none. */
    fun activeRevisionId(documentId: DocumentId): String? = database.read { connection ->
        connection.selectActiveRevision(documentId)
    }

    /** The revision [documentId] currently publishes, or `null` when it has none. */
    fun activeRevision(documentId: DocumentId): DocumentRevision? =
        activeRevisionId(documentId)?.let(::revision)

    /**
     * Every revision some document publishes.
     *
     * This is the set a rebuild keeps and everything else it may shed: a revision tag in the index that
     * is not in here belongs to a reading no document publishes any more.
     */
    fun allActiveRevisionIds(): Set<String> = database.read { connection ->
        connection.prepareStatement("SELECT revision_id FROM document_active_revisions").use { statement ->
            statement.executeQuery().use { rows ->
                buildSet { while (rows.next()) add(rows.getString(1)) }
            }
        }
    }

    /** One revision's pages in ordinal order. */
    fun pages(revisionId: String): List<RevisionPageText> = database.read { connection ->
        connection.prepareStatement("$SELECT_PAGE_TEXTS WHERE revision_id = ? ORDER BY ordinal").use { statement ->
            statement.setString(1, revisionId)
            statement.executeQuery().use { rows ->
                buildList { while (rows.next()) add(rows.toPageText()) }
            }
        }
    }

    /** One revision's page at [ordinal], or `null` when the revision does not name that page. */
    fun page(revisionId: String, ordinal: Int): RevisionPageText? = database.read { connection ->
        connection.selectPageText(revisionId, ordinal)
    }

    /**
     * One revision's text for [unitId], or `null` when that revision does not hold that unit.
     *
     * This is how a saved excerpt is read back: the citation names the revision it was taken from, and
     * the page it opens is the page of *that* revision rather than whatever the document publishes now.
     */
    fun pageForUnit(revisionId: String, unitId: ContentUnitId): RevisionPageText? = database.read { connection ->
        connection.prepareStatement(
            "$SELECT_PAGE_TEXTS WHERE revision_id = ? AND unit_id = ?",
        ).use { statement ->
            statement.setString(1, revisionId)
            statement.setString(2, unitId.value)
            statement.executeQuery().use { rows -> if (rows.next()) rows.toPageText() else null }
        }
    }

    /** One revision's chunks in reading order. */
    fun chunks(revisionId: String): List<RevisionChunk> = database.read { connection ->
        connection.prepareStatement(
            "$SELECT_CHUNKS WHERE revision_id = ? ORDER BY unit_ordinal, ordinal",
        ).use { statement ->
            statement.setString(1, revisionId)
            statement.executeQuery().use { rows ->
                buildList { while (rows.next()) add(rows.toRevisionChunk()) }
            }
        }
    }

    /** How many chunks one revision holds, for the same agreement checks the published chunks answer. */
    fun chunkCount(revisionId: String): Int = database.read { connection ->
        connection.prepareStatement("SELECT COUNT(*) FROM revision_chunks WHERE revision_id = ?").use { statement ->
            statement.setString(1, revisionId)
            statement.executeQuery().use { rows ->
                rows.next()
                rows.getInt(1)
            }
        }
    }

    // ---- Staging a candidate ----

    /**
     * Opens a candidate revision for [documentId] and returns its identifier.
     *
     * The revision is empty and invisible: nothing about the document's published text changes, and
     * nothing about it changes when the candidate is filled in, published or withdrawn.
     */
    fun openCandidate(documentId: DocumentId, parentRevisionId: String?, provenance: String): String {
        require(provenance.isNotBlank()) { "a revision records why it exists" }
        val id = "revision-" + UUID.randomUUID()
        database.transaction { connection ->
            connection.prepareStatement(
                "INSERT INTO document_revisions (id, document_id, parent_revision_id, state, provenance, " +
                    "created_at) VALUES (?, ?, ?, ?, ?, ?)",
            ).use { statement ->
                statement.setString(1, id)
                statement.setString(2, documentId.value)
                statement.setString(3, parentRevisionId)
                statement.setString(4, RevisionState.CANDIDATE.name)
                statement.setString(5, provenance)
                statement.setString(6, Instants.now())
                statement.executeUpdate()
            }
        }
        return id
    }

    /**
     * Appends one page and its chunks to a candidate revision.
     *
     * The write touches nothing published. A page whose ordinal is already staged is replaced, so a
     * resumed staging pass writes the same state twice rather than failing on the second attempt.
     */
    fun appendPage(revisionId: String, page: RevisionPageDraft) {
        database.transaction { connection ->
            val revision = connection.selectRevision(revisionId)
                ?: throw NoSuchElementException("no revision with id $revisionId exists")
            check(revision.state == RevisionState.CANDIDATE) {
                "revision $revisionId is ${revision.state}, so it cannot be staged into"
            }
            connection.deletePageChunks(revisionId, page.ordinal)
            connection.writePageText(revisionId, page, Instants.now())
            connection.writeChunks(revisionId, page)
        }
    }

    /**
     * Writes one staged page's passages, without touching the page's text.
     *
     * A rescan commits in the order its stages happen: a page's text is committed when the page was read and
     * reviewed, and its passages are committed when they are chunked. Splitting the two is what makes a
     * resumed attempt re-chunk without re-reading (and re-paying for) a page — the same reason re-embedding
     * must not invalidate an extraction checkpoint. The passages carry no vector yet: the embedding pass
     * attaches those ([recordChunkVectors]), and the publication refuses a passage that has none.
     */
    fun recordPageChunks(revisionId: String, unitOrdinal: Int, drafts: List<ChunkDraft>) {
        database.transaction { connection ->
            val revision = connection.selectRevision(revisionId)
                ?: throw NoSuchElementException("no revision with id $revisionId exists")
            check(revision.state == RevisionState.CANDIDATE) {
                "revision $revisionId is ${revision.state}, so it cannot be staged into"
            }
            connection.deletePageChunks(revisionId, unitOrdinal)
            drafts.forEach { draft ->
                connection.prepareStatement(
                    "INSERT INTO revision_chunks (revision_id, unit_ordinal, ordinal, text, start_offset, " +
                        "end_offset, token_count, token_start, token_end, embedding, embedding_dimension, " +
                        "created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, ?)",
                ).use { statement ->
                    statement.setString(1, revisionId)
                    statement.setInt(2, unitOrdinal)
                    statement.setInt(3, draft.ordinal)
                    statement.setString(4, draft.text)
                    statement.setInt(5, draft.startOffset)
                    statement.setInt(6, draft.endOffset)
                    statement.setInt(7, draft.tokenCount)
                    statement.setInt(8, draft.tokenStart)
                    statement.setInt(9, draft.tokenEnd)
                    statement.setString(10, Instants.now())
                    statement.executeUpdate()
                }
            }
        }
    }

    /**
     * Attaches the vectors of one staged page's passages, in the passages' own order.
     *
     * The vectors of a candidate are written after its passages rather than with them, because embedding is
     * the stage that needs the accelerator: a page whose text was read and reviewed stays committed while the
     * embedding pass is still ahead of it, and a resumed pass embeds only the passages that have none.
     */
    fun recordChunkVectors(revisionId: String, unitOrdinal: Int, vectors: List<FloatArray>) {
        database.transaction { connection ->
            val revision = connection.selectRevision(revisionId)
                ?: throw NoSuchElementException("no revision with id $revisionId exists")
            check(revision.state == RevisionState.CANDIDATE) {
                "revision $revisionId is ${revision.state}, so no vector may be attached to it"
            }
            val chunks = chunks(revisionId).filter { chunk -> chunk.unitOrdinal == unitOrdinal }
            require(chunks.size == vectors.size) {
                "page $unitOrdinal of revision $revisionId holds ${chunks.size} passages, not " +
                    "${vectors.size}; a vector is a passage's own"
            }
            chunks.forEachIndexed { index, chunk ->
                vectors[index].forEach { value ->
                    require(value.isFinite()) { "an embedding is a finite number" }
                }
                connection.prepareStatement(
                    "UPDATE revision_chunks SET embedding = ?, embedding_dimension = ? WHERE revision_id = ? " +
                        "AND unit_ordinal = ? AND ordinal = ?",
                ).use { statement ->
                    statement.setBytes(1, encodeEmbedding(vectors[index]))
                    statement.setInt(2, vectors[index].size)
                    statement.setString(3, revisionId)
                    statement.setInt(4, unitOrdinal)
                    statement.setInt(5, chunk.ordinal)
                    statement.executeUpdate()
                }
            }
        }
    }

    /** Records one page's review decision. Only approved pages may be published. */
    fun recordPageApproval(revisionId: String, ordinal: Int, approval: PageApproval) {
        database.transaction { connection ->
            val updated = connection.prepareStatement(
                "UPDATE page_text_revisions SET approval = ? WHERE revision_id = ? AND ordinal = ?",
            ).use { statement ->
                statement.setString(1, approval.name)
                statement.setString(2, revisionId)
                statement.setInt(3, ordinal)
                statement.executeUpdate()
            }
            if (updated == 0) {
                throw NoSuchElementException("revision $revisionId has no page at ordinal $ordinal")
            }
        }
    }

    /**
     * Gives up on a candidate without publishing it.
     *
     * A withdrawn candidate keeps its identifier and its staged pages, because a stager that failed to
     * embed half a document may resume against the same revision rather than start over; what it cannot
     * do is become visible, which is what the state says.
     */
    fun withdrawCandidate(revisionId: String) {
        database.transaction { connection ->
            connection.prepareStatement(
                "UPDATE document_revisions SET state = ? WHERE id = ? AND state = ?",
            ).use { statement ->
                statement.setString(1, RevisionState.WITHDRAWN.name)
                statement.setString(2, revisionId)
                statement.setString(3, RevisionState.CANDIDATE.name)
                statement.executeUpdate()
            }
        }
    }

    /**
     * Records the text and chunks a document already publishes as a new published revision.
     *
     * A fresh import publishes its text directly, so this is how that reading becomes a revision like
     * any other: the document's next replacement then has a base revision to name, and the text it
     * replaced stays readable. The chunks are copied without their vectors — they are already in the
     * index, and re-embedding them here would make an import depend on the accelerator twice.
     *
     * @return the new revision's identifier, or `null` when the document publishes no content yet.
     */
    fun recordPublishedContent(documentId: DocumentId, provenance: String): String? {
        require(provenance.isNotBlank()) { "a revision records why it exists" }
        return database.transaction { connection ->
            if (connection.countUnits(documentId) == 0) return@transaction null
            val previous = connection.selectActiveRevision(documentId)
            val id = "revision-" + UUID.randomUUID()
            val now = Instants.now()
            connection.insertRevision(id, documentId, previous, RevisionState.PUBLISHED, provenance, now)
            connection.copyPublishedPages(id, documentId, now)
            connection.copyPublishedChunks(id, documentId, now)
            connection.upsertActiveRevision(documentId, id, now)
            connection.supersede(previous, documentId)
            id
        }
    }

    // ---- Restoring a historical revision ----

    /**
     * Stages a restore of [sourceRevisionId] as a new candidate revision, in one transaction.
     *
     * The candidate is a *copy of the historical revision's immutable page texts*: the same stable unit ids,
     * locators, texts, extraction provenance, artifact references and source-image references, so no page is
     * read again and no page image is copied — the new revision names the images the old one named. Only the
     * pages that revision published (its approved ones) are copied, because those are the reading that was
     * ever searchable; a page nobody approved was never part of it. The passages are copied without vectors:
     * a restore embeds with the embedder the process has now rather than trusting a vector some earlier model
     * produced, and the publication refuses a passage that has none.
     *
     * Nothing published changes. The candidate descends from [expectedRevisionId], the reading it will
     * replace, and the request record is written in the same transaction, so a crash leaves either nothing or
     * a candidate that a record accounts for.
     */
    fun stageRestore(
        collectionId: CollectionId,
        documentId: DocumentId,
        requestId: String,
        requestHash: String,
        expectedRevisionId: String,
        sourceRevisionId: String,
    ): RevisionRestoreRecord {
        val restoreId = "restore-" + UUID.randomUUID()
        val newRevisionId = "revision-" + UUID.randomUUID()
        database.transaction { connection ->
            val now = Instants.now()
            connection.insertRevision(
                newRevisionId, documentId, expectedRevisionId, RevisionState.CANDIDATE, PROVENANCE_RESTORE, now,
            )
            val copied = connection.copyApprovedPages(newRevisionId, sourceRevisionId, now)
            check(copied > 0) { "revision $sourceRevisionId published no page, so there is nothing to restore" }
            connection.copyApprovedChunks(newRevisionId, sourceRevisionId, now)
            connection.prepareStatement(
                "INSERT INTO revision_restores (restore_id, collection_id, document_id, request_id, request_hash, " +
                    "expected_revision_id, restored_from_revision_id, new_revision_id, phase, created_at, " +
                    "updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            ).use { statement ->
                statement.setString(1, restoreId)
                statement.setString(2, collectionId.value)
                statement.setString(3, documentId.value)
                statement.setString(4, requestId)
                statement.setString(5, requestHash)
                statement.setString(6, expectedRevisionId)
                statement.setString(7, sourceRevisionId)
                statement.setString(8, newRevisionId)
                statement.setString(9, RestorePhase.STAGED.name)
                statement.setString(10, now)
                statement.setString(11, now)
                statement.executeUpdate()
            }
        }
        return checkNotNull(restore(restoreId))
    }

    /**
     * Ends a restore request that is still in flight, as published or failed.
     *
     * Only a STAGED request can be finished, and only once: the first outcome recorded is the outcome, so a
     * recovery that races a late completion cannot turn a published restore back into a failed one.
     *
     * @return whether this call recorded the outcome.
     */
    fun finishRestore(restoreId: String, phase: RestorePhase, errorCode: String? = null, errorMessage: String? = null): Boolean {
        require(phase != RestorePhase.STAGED) { "a restore is finished as published or failed, not $phase" }
        return database.transaction { connection ->
            connection.prepareStatement(
                "UPDATE revision_restores SET phase = ?, error_code = ?, error_message = ?, updated_at = ? " +
                    "WHERE restore_id = ? AND phase = ?",
            ).use { statement ->
                statement.setString(1, phase.name)
                statement.setString(2, errorCode)
                statement.setString(3, errorMessage)
                statement.setString(4, Instants.now())
                statement.setString(5, restoreId)
                statement.setString(6, RestorePhase.STAGED.name)
                statement.executeUpdate() > 0
            }
        }
    }

    /** One restore request, or `null` when no request has that identifier. */
    fun restore(restoreId: String): RevisionRestoreRecord? = database.read { connection ->
        connection.selectRestore("$SELECT_RESTORES WHERE restore_id = ?", restoreId)
    }

    /** The restore request a caller's own request id names for one document, or `null`. */
    fun restoreForRequest(collectionId: CollectionId, documentId: DocumentId, requestId: String): RevisionRestoreRecord? =
        database.read { connection ->
            connection.prepareStatement(
                "$SELECT_RESTORES WHERE collection_id = ? AND document_id = ? AND request_id = ?",
            ).use { statement ->
                statement.setString(1, collectionId.value)
                statement.setString(2, documentId.value)
                statement.setString(3, requestId)
                statement.executeQuery().use { rows -> if (rows.next()) rows.toRestore() else null }
            }
        }

    /** The restore that created [newRevisionId], or `null` when that revision is not a restore. */
    fun restoreCreating(newRevisionId: String): RevisionRestoreRecord? = database.read { connection ->
        connection.selectRestore("$SELECT_RESTORES WHERE new_revision_id = ?", newRevisionId)
    }

    /** Every restore request of one document, oldest first. */
    fun restores(documentId: DocumentId): List<RevisionRestoreRecord> = database.read { connection ->
        connection.prepareStatement("$SELECT_RESTORES WHERE document_id = ? ORDER BY created_at, rowid").use { statement ->
            statement.setString(1, documentId.value)
            statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.toRestore()) } }
        }
    }

    /** The restore of [documentId] that has not finished, or `null`. */
    fun inFlightRestore(documentId: DocumentId): RevisionRestoreRecord? = database.read { connection ->
        connection.prepareStatement("$SELECT_RESTORES WHERE document_id = ? AND phase = ?").use { statement ->
            statement.setString(1, documentId.value)
            statement.setString(2, RestorePhase.STAGED.name)
            statement.executeQuery().use { rows -> if (rows.next()) rows.toRestore() else null }
        }
    }

    /** Every restore request that has not finished, oldest first: what a startup has to resolve. */
    fun unfinishedRestores(): List<RevisionRestoreRecord> = database.read { connection ->
        connection.prepareStatement("$SELECT_RESTORES WHERE phase = ? ORDER BY created_at, rowid").use { statement ->
            statement.setString(1, RestorePhase.STAGED.name)
            statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.toRestore()) } }
        }
    }

    // ---- Reading history ----

    /**
     * When each revision of [documentId] became its published text, by revision id.
     *
     * The instant is the one the SQLite authority moved, which is the only moment a revision *became* the
     * document's text. A revision that never went through a publication — an import's, or the backfill's —
     * has no entry, and is not given one.
     */
    fun publishedAt(documentId: DocumentId): Map<String, String> = database.read { connection ->
        connection.prepareStatement(
            "SELECT target_revision_id, authoritative_at FROM revision_publications " +
                "WHERE document_id = ? AND authoritative_at IS NOT NULL ORDER BY authoritative_at",
        ).use { statement ->
            statement.setString(1, documentId.value)
            statement.executeQuery().use { rows ->
                buildMap { while (rows.next()) put(rows.getString(1), rows.getString(2)) }
            }
        }
    }

    /** One revision's pages without their text, so a history can compare readings without loading them. */
    fun pageDigests(revisionId: String): List<PageDigest> = database.read { connection ->
        connection.prepareStatement(
            "SELECT ordinal, unit_id, text_sha256, CASE WHEN text_sha256 IS NULL THEN extracted_text END AS text, " +
                "extraction_method, approval FROM page_text_revisions WHERE revision_id = ? ORDER BY ordinal",
        ).use { statement ->
            statement.setString(1, revisionId)
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        add(
                            PageDigest(
                                ordinal = rows.getInt("ordinal"),
                                unitId = ContentUnitId(rows.getString("unit_id")),
                                textSha256 = rows.getString("text_sha256") ?: sha256Of(rows.getString("text")),
                                extractionMethod = rows.getString("extraction_method")?.let(ExtractionMethod::valueOf),
                                approval = PageApproval.valueOf(rows.getString("approval")),
                            ),
                        )
                    }
                }
            }
        }
    }

    // ---- Publication intents ----

    /**
     * Persists a PREPARED intent for a publication that is about to be staged.
     *
     * The row exists before any staged row does, so a crash between the two leaves an intent recovery
     * can read rather than an index nobody can explain.
     */
    fun prepare(
        documentId: DocumentId,
        collectionId: CollectionId,
        baseRevisionId: String?,
        targetRevisionId: String,
    ): PublicationIntent {
        val id = "publication-" + UUID.randomUUID()
        database.transaction { connection ->
            connection.prepareStatement(
                "INSERT INTO revision_publications (id, document_id, collection_id, base_revision_id, " +
                    "target_revision_id, phase, prepared_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
            ).use { statement ->
                statement.setString(1, id)
                statement.setString(2, documentId.value)
                statement.setString(3, collectionId.value)
                statement.setString(4, baseRevisionId)
                statement.setString(5, targetRevisionId)
                statement.setString(6, PublicationPhase.PREPARED.name)
                statement.setString(7, Instants.now())
                statement.executeUpdate()
            }
        }
        return checkNotNull(intent(id))
    }

    /** One publication attempt, or `null` when no attempt has that identifier. */
    fun intent(id: String): PublicationIntent? = database.read { connection -> connection.selectIntent(id) }

    /**
     * Every attempt that has not been resolved, oldest first: what a startup has to decide about.
     *
     * A PREPARED attempt is the only unresolved one. An abandoned or refused attempt has an answer
     * already (its authority never moved) and a published one has finished, so neither is work — the
     * predicate is the phase rather than the absence of `published_at`, which an abandoned attempt also
     * never gets.
     */
    fun unfinishedIntents(): List<PublicationIntent> = database.read { connection ->
        connection.prepareStatement(
            "$SELECT_INTENTS WHERE phase = ? ORDER BY prepared_at, id",
        ).use { statement ->
            statement.setString(1, PublicationPhase.PREPARED.name)
            statement.executeQuery().use { rows ->
                buildList { while (rows.next()) add(rows.toIntent()) }
            }
        }
    }

    /**
     * Every attempt whose reading was made authoritative but whose superseded rows may still be in the
     * index, oldest first.
     *
     * A restart has no in-process snapshot to keep those rows hidden, so this is the list the startup has
     * to clean before anything reads: not doing it would let one document answer a search with two
     * readings at once.
     */
    fun intentsAwaitingCleanup(): List<PublicationIntent> = database.read { connection ->
        connection.prepareStatement(
            "$SELECT_INTENTS WHERE authoritative_at IS NOT NULL AND cleaned_at IS NULL ORDER BY prepared_at, id",
        ).use { statement ->
            statement.executeQuery().use { rows ->
                buildList { while (rows.next()) add(rows.toIntent()) }
            }
        }
    }

    /** Records that the superseded rows of [intentId] are gone from the index. */
    fun markCleaned(intentId: String) {
        database.transaction { connection ->
            connection.prepareStatement(
                "UPDATE revision_publications SET cleaned_at = ? WHERE id = ?",
            ).use { statement ->
                statement.setString(1, Instants.now())
                statement.setString(2, intentId)
                statement.executeUpdate()
            }
        }
    }

    /**
     * Makes [intent]'s target revision the published text of its document, in one transaction.
     *
     * This is the handoff's durable half and the only place the published content changes: the pages'
     * text and chunks become the document's, the active-revision pointer moves, the previous revision is
     * marked superseded, and `authoritative_at` is written beside them. Because all of it commits or
     * none of it does, an intent either has not moved authority at all or has moved it completely, and
     * recovery never has to guess which.
     */
    fun makeAuthoritative(intent: PublicationIntent, pages: List<RevisionPage>) {
        database.transaction { connection ->
            content.publishRevision(intent.documentId, pages)
            val now = Instants.now()
            connection.upsertActiveRevision(intent.documentId, intent.targetRevisionId, now)
            connection.setRevisionState(intent.targetRevisionId, RevisionState.PUBLISHED)
            connection.supersede(intent.baseRevisionId, intent.documentId)
            connection.prepareStatement(
                "UPDATE revision_publications SET authoritative_at = ?, error_code = NULL, " +
                    "error_message = NULL WHERE id = ?",
            ).use { statement ->
                statement.setString(1, now)
                statement.setString(2, intent.id)
                statement.executeUpdate()
            }
        }
    }

    /** Records that the target revision is the one readers lease. Written after the snapshot is published. */
    fun markPublished(intentId: String) {
        val now = Instants.now()
        database.transaction { connection ->
            connection.prepareStatement(
                "UPDATE revision_publications SET phase = ?, published_at = ? WHERE id = ?",
            ).use { statement ->
                statement.setString(1, PublicationPhase.PUBLISHED.name)
                statement.setString(2, now)
                statement.setString(3, intentId)
                statement.executeUpdate()
            }
        }
    }

    /**
     * Records that a publication did not happen and why.
     *
     * Only ever called on an intent whose authority has *not* moved: an attempt that committed its
     * authority is completed by recovery, never abandoned, because the database already says its target
     * revision is the published one.
     */
    fun abandon(intentId: String, phase: PublicationPhase, code: String, message: String) {
        require(phase == PublicationPhase.ABANDONED || phase == PublicationPhase.REFUSED) {
            "an unfinished publication is abandoned or refused, not $phase"
        }
        database.transaction { connection ->
            connection.prepareStatement(
                "UPDATE revision_publications SET phase = ?, error_code = ?, error_message = ? " +
                    "WHERE id = ? AND authoritative_at IS NULL",
            ).use { statement ->
                statement.setString(1, phase.name)
                statement.setString(2, code)
                statement.setString(3, message)
                statement.setString(4, intentId)
                statement.executeUpdate()
            }
        }
    }

    // ---- SQL the rest of this class is written in ----

    private fun Connection.selectRevision(id: String): DocumentRevision? =
        prepareStatement("$SELECT_REVISIONS WHERE id = ?").use { statement ->
            statement.setString(1, id)
            statement.executeQuery().use { rows -> if (rows.next()) rows.toRevision() else null }
        }

    private fun Connection.selectActiveRevision(documentId: DocumentId): String? =
        prepareStatement("SELECT revision_id FROM document_active_revisions WHERE document_id = ?").use { statement ->
            statement.setString(1, documentId.value)
            statement.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
        }

    private fun Connection.upsertActiveRevision(documentId: DocumentId, revisionId: String, now: String) {
        prepareStatement(
            "INSERT INTO document_active_revisions (document_id, revision_id, updated_at) VALUES (?, ?, ?) " +
                "ON CONFLICT (document_id) DO UPDATE SET revision_id = excluded.revision_id, " +
                "updated_at = excluded.updated_at",
        ).use { statement ->
            statement.setString(1, documentId.value)
            statement.setString(2, revisionId)
            statement.setString(3, now)
            statement.executeUpdate()
        }
    }

    private fun Connection.setRevisionState(revisionId: String, state: RevisionState) {
        prepareStatement("UPDATE document_revisions SET state = ? WHERE id = ?").use { statement ->
            statement.setString(1, state.name)
            statement.setString(2, revisionId)
            statement.executeUpdate()
        }
    }

    /** Marks the revision a publication replaced, so the history reads in the order it happened. */
    private fun Connection.supersede(revisionId: String?, documentId: DocumentId) {
        if (revisionId == null) return
        prepareStatement(
            "UPDATE document_revisions SET state = ? WHERE id = ? AND document_id = ? AND state = ?",
        ).use { statement ->
            statement.setString(1, RevisionState.SUPERSEDED.name)
            statement.setString(2, revisionId)
            statement.setString(3, documentId.value)
            statement.setString(4, RevisionState.PUBLISHED.name)
            statement.executeUpdate()
        }
    }

    private fun Connection.insertRevision(
        id: String,
        documentId: DocumentId,
        parentRevisionId: String?,
        state: RevisionState,
        provenance: String,
        now: String,
    ) {
        prepareStatement(
            "INSERT INTO document_revisions (id, document_id, parent_revision_id, state, provenance, " +
                "created_at) VALUES (?, ?, ?, ?, ?, ?)",
        ).use { statement ->
            statement.setString(1, id)
            statement.setString(2, documentId.value)
            statement.setString(3, parentRevisionId)
            statement.setString(4, state.name)
            statement.setString(5, provenance)
            statement.setString(6, now)
            statement.executeUpdate()
        }
    }

    private fun Connection.copyPublishedPages(revisionId: String, documentId: DocumentId, now: String) {
        prepareStatement(
            "INSERT INTO page_text_revisions (revision_id, ordinal, unit_id, locator, extracted_text, " +
                "search_text, text_sha256, extraction_method, mean_confidence, artifact_relative_path, " +
                "artifact_sha256, approval, created_at) " +
                "SELECT ?, ordinal, id, locator, extracted_text, search_text, NULL, extraction_method, " +
                "mean_confidence, artifact_relative_path, artifact_sha256, ?, ? FROM content_units " +
                "WHERE document_id = ?",
        ).use { statement ->
            statement.setString(1, revisionId)
            statement.setString(2, PageApproval.APPROVED.name)
            statement.setString(3, now)
            statement.setString(4, documentId.value)
            statement.executeUpdate()
        }
    }

    private fun Connection.copyPublishedChunks(revisionId: String, documentId: DocumentId, now: String) {
        prepareStatement(
            "INSERT INTO revision_chunks (revision_id, unit_ordinal, ordinal, text, start_offset, end_offset, " +
                "token_count, token_start, token_end, embedding, embedding_dimension, created_at) " +
                "SELECT ?, u.ordinal, c.ordinal, c.text, c.start_offset, c.end_offset, c.token_count, " +
                "c.token_start, c.token_end, NULL, NULL, ? FROM chunks c " +
                "JOIN content_units u ON u.id = c.content_unit_id WHERE u.document_id = ?",
        ).use { statement ->
            statement.setString(1, revisionId)
            statement.setString(2, now)
            statement.setString(3, documentId.value)
            statement.executeUpdate()
        }
    }

    /**
     * Copies the approved pages of one revision into another, keeping every recorded fact about them.
     *
     * The source-image columns are copied too: a restored page names the image its text was read from, and
     * that image is the same immutable artifact, not a second copy of it.
     */
    private fun Connection.copyApprovedPages(revisionId: String, sourceRevisionId: String, now: String): Int =
        prepareStatement(
            "INSERT INTO page_text_revisions (revision_id, ordinal, unit_id, locator, extracted_text, " +
                "search_text, text_sha256, extraction_method, mean_confidence, artifact_relative_path, " +
                "artifact_sha256, source_image_root, source_image_relative_path, source_image_sha256, " +
                "source_image_width, source_image_height, source_image_render_version, approval, created_at) " +
                "SELECT ?, ordinal, unit_id, locator, extracted_text, search_text, text_sha256, " +
                "extraction_method, mean_confidence, artifact_relative_path, artifact_sha256, " +
                "source_image_root, source_image_relative_path, source_image_sha256, source_image_width, " +
                "source_image_height, source_image_render_version, ?, ? FROM page_text_revisions " +
                "WHERE revision_id = ? AND approval = ?",
        ).use { statement ->
            statement.setString(1, revisionId)
            statement.setString(2, PageApproval.APPROVED.name)
            statement.setString(3, now)
            statement.setString(4, sourceRevisionId)
            statement.setString(5, PageApproval.APPROVED.name)
            statement.executeUpdate()
        }

    /** Copies the passages of the approved pages of one revision, without their vectors. */
    private fun Connection.copyApprovedChunks(revisionId: String, sourceRevisionId: String, now: String) {
        prepareStatement(
            "INSERT INTO revision_chunks (revision_id, unit_ordinal, ordinal, text, start_offset, end_offset, " +
                "token_count, token_start, token_end, embedding, embedding_dimension, created_at) " +
                "SELECT ?, c.unit_ordinal, c.ordinal, c.text, c.start_offset, c.end_offset, c.token_count, " +
                "c.token_start, c.token_end, NULL, NULL, ? FROM revision_chunks c WHERE c.revision_id = ? " +
                "AND c.unit_ordinal IN (SELECT ordinal FROM page_text_revisions WHERE revision_id = ? " +
                "AND approval = ?)",
        ).use { statement ->
            statement.setString(1, revisionId)
            statement.setString(2, now)
            statement.setString(3, sourceRevisionId)
            statement.setString(4, sourceRevisionId)
            statement.setString(5, PageApproval.APPROVED.name)
            statement.executeUpdate()
        }
    }

    private fun Connection.selectRestore(sql: String, key: String): RevisionRestoreRecord? =
        prepareStatement(sql).use { statement ->
            statement.setString(1, key)
            statement.executeQuery().use { rows -> if (rows.next()) rows.toRestore() else null }
        }

    private fun ResultSet.toRestore(): RevisionRestoreRecord = RevisionRestoreRecord(
        restoreId = getString("restore_id"),
        collectionId = CollectionId(getString("collection_id")),
        documentId = DocumentId(getString("document_id")),
        requestId = getString("request_id"),
        requestHash = getString("request_hash"),
        expectedRevisionId = getString("expected_revision_id"),
        restoredFromRevisionId = getString("restored_from_revision_id"),
        newRevisionId = getString("new_revision_id"),
        phase = RestorePhase.valueOf(getString("phase")),
        errorCode = getString("error_code"),
        errorMessage = getString("error_message"),
        createdAt = getString("created_at"),
        updatedAt = getString("updated_at"),
    )

    private fun Connection.selectPageText(revisionId: String, ordinal: Int): RevisionPageText? =
        prepareStatement("$SELECT_PAGE_TEXTS WHERE revision_id = ? AND ordinal = ?").use { statement ->
            statement.setString(1, revisionId)
            statement.setInt(2, ordinal)
            statement.executeQuery().use { rows -> if (rows.next()) rows.toPageText() else null }
        }

    private fun Connection.deletePageChunks(revisionId: String, unitOrdinal: Int) {
        prepareStatement("DELETE FROM revision_chunks WHERE revision_id = ? AND unit_ordinal = ?").use { statement ->
            statement.setString(1, revisionId)
            statement.setInt(2, unitOrdinal)
            statement.executeUpdate()
        }
    }

    private fun Connection.writePageText(revisionId: String, page: RevisionPageDraft, now: String) {
        prepareStatement(
            "INSERT INTO page_text_revisions (revision_id, ordinal, unit_id, locator, extracted_text, " +
                "search_text, text_sha256, extraction_method, mean_confidence, artifact_relative_path, " +
                "artifact_sha256, source_image_root, source_image_relative_path, source_image_sha256, " +
                "source_image_width, source_image_height, source_image_render_version, approval, " +
                "created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                "ON CONFLICT (revision_id, ordinal) DO UPDATE SET unit_id = excluded.unit_id, " +
                "locator = excluded.locator, extracted_text = excluded.extracted_text, " +
                "search_text = excluded.search_text, text_sha256 = excluded.text_sha256, " +
                "extraction_method = excluded.extraction_method, " +
                "mean_confidence = excluded.mean_confidence, " +
                "artifact_relative_path = excluded.artifact_relative_path, " +
                "artifact_sha256 = excluded.artifact_sha256, " +
                "source_image_root = excluded.source_image_root, " +
                "source_image_relative_path = excluded.source_image_relative_path, " +
                "source_image_sha256 = excluded.source_image_sha256, " +
                "source_image_width = excluded.source_image_width, " +
                "source_image_height = excluded.source_image_height, " +
                "source_image_render_version = excluded.source_image_render_version, " +
                "approval = excluded.approval",
        ).use { statement ->
            statement.setString(1, revisionId)
            statement.setInt(2, page.ordinal)
            statement.setString(3, page.unitId.value)
            statement.setString(4, LOCATOR_JSON.encodeToString(SourceLocation.serializer(), page.locator))
            statement.setString(5, page.extractedText)
            statement.setString(6, page.searchText)
            statement.setString(7, sha256Of(page.extractedText))
            statement.setString(8, page.extractionMethod?.name)
            if (page.meanConfidence == null) {
                statement.setNull(9, Types.REAL)
            } else {
                statement.setDouble(9, page.meanConfidence)
            }
            statement.setString(10, page.artifactRelativePath)
            statement.setString(11, page.artifactSha256)
            val source = page.sourceImage
            statement.setString(12, source?.root?.name)
            statement.setString(13, source?.relativePath)
            statement.setString(14, source?.sha256)
            if (source?.width == null) {
                statement.setNull(15, Types.INTEGER)
            } else {
                statement.setInt(15, source.width)
            }
            if (source?.height == null) {
                statement.setNull(16, Types.INTEGER)
            } else {
                statement.setInt(16, source.height)
            }
            if (source == null) {
                statement.setNull(17, Types.INTEGER)
            } else {
                statement.setInt(17, source.renderVersion)
            }
            statement.setString(18, page.approval.name)
            statement.setString(19, now)
            statement.executeUpdate()
        }
    }

    private fun Connection.writeChunks(revisionId: String, page: RevisionPageDraft) {
        if (page.chunks.isEmpty()) return
        val now = Instants.now()
        prepareStatement(
            "INSERT INTO revision_chunks (revision_id, unit_ordinal, ordinal, text, start_offset, end_offset, " +
                "token_count, token_start, token_end, embedding, embedding_dimension, created_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        ).use { statement ->
            page.chunks.forEach { chunk ->
                statement.setString(1, revisionId)
                statement.setInt(2, page.ordinal)
                statement.setInt(3, chunk.ordinal)
                statement.setString(4, chunk.text)
                statement.setInt(5, chunk.startOffset)
                statement.setInt(6, chunk.endOffset)
                statement.setInt(7, chunk.tokenCount)
                statement.setInt(8, chunk.tokenStart)
                statement.setInt(9, chunk.tokenEnd)
                if (chunk.embedding == null) {
                    statement.setNull(10, Types.BLOB)
                    statement.setNull(11, Types.INTEGER)
                } else {
                    statement.setBytes(10, encodeEmbedding(chunk.embedding))
                    statement.setInt(11, chunk.embedding.size)
                }
                statement.setString(12, now)
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    private fun Connection.selectIntent(id: String): PublicationIntent? =
        prepareStatement("$SELECT_INTENTS WHERE id = ?").use { statement ->
            statement.setString(1, id)
            statement.executeQuery().use { rows -> if (rows.next()) rows.toIntent() else null }
        }

    private fun ResultSet.toRevision(): DocumentRevision = DocumentRevision(
        id = getString("id"),
        documentId = DocumentId(getString("document_id")),
        parentRevisionId = getString("parent_revision_id"),
        state = RevisionState.valueOf(getString("state")),
        provenance = getString("provenance"),
        createdAt = getString("created_at"),
    )

    private fun ResultSet.toPageText(): RevisionPageText = RevisionPageText(
        revisionId = getString("revision_id"),
        ordinal = getInt("ordinal"),
        unitId = ContentUnitId(getString("unit_id")),
        locator = LOCATOR_JSON.decodeFromString(SourceLocation.serializer(), getString("locator")),
        extractedText = getString("extracted_text"),
        searchText = getString("search_text"),
        textSha256 = getString("text_sha256"),
        extractionMethod = getString("extraction_method")?.let(ExtractionMethod::valueOf),
        meanConfidence = getDouble("mean_confidence").takeUnless { wasNull() },
        artifactRelativePath = getString("artifact_relative_path"),
        artifactSha256 = getString("artifact_sha256"),
        approval = PageApproval.valueOf(getString("approval")),
        // Absent columns are one answer rather than six: a row written before source images were recorded,
        // and a page that was never read from an image, both say the same thing — no image was observed.
        sourceImage = toSourceImage(),
    )

    /**
     * The source image the row names, or `null` when it names none.
     *
     * "None" is a claim that no image was read, so it is only the answer when *every* provenance column is
     * absent. The schema and the record both refuse a row that carries only some of them,
     * but a row can still reach this read through a damaged archive or a writer that went around the store,
     * and answering "no image" for it would hide an artifact nobody can reopen. Such a row is corrupt and
     * says so, naming the revision and page but never the reference.
     */
    private fun ResultSet.toSourceImage(): SourceImageProvenance? {
        val root = getString("source_image_root")
        val relativePath = getString("source_image_relative_path")
        val sha256 = getString("source_image_sha256")
        val width = getInt("source_image_width").takeUnless { wasNull() }
        val height = getInt("source_image_height").takeUnless { wasNull() }
        val renderVersion = getInt("source_image_render_version").takeUnless { wasNull() }
        if (root == null && relativePath == null && sha256 == null && width == null && height == null &&
            renderVersion == null
        ) {
            return null
        }
        val where = "revision ${getString("revision_id")} page ${getInt("ordinal")}"
        check(root != null && relativePath != null && sha256 != null && renderVersion != null) {
            "$where carries only part of a source image provenance, so it names no image it can be trusted to"
        }
        return try {
            SourceImageProvenance(
                root = SourceImageRoot.valueOf(root),
                relativePath = relativePath,
                sha256 = sha256,
                width = width,
                height = height,
                renderVersion = renderVersion,
            )
        } catch (malformed: IllegalArgumentException) {
            throw IllegalStateException("$where carries a source image provenance that is not well formed", malformed)
        }
    }

    private fun ResultSet.toRevisionChunk(): RevisionChunk = RevisionChunk(
        unitOrdinal = getInt("unit_ordinal"),
        ordinal = getInt("ordinal"),
        text = getString("text"),
        startOffset = getInt("start_offset"),
        endOffset = getInt("end_offset"),
        tokenCount = getInt("token_count"),
        tokenStart = getInt("token_start"),
        tokenEnd = getInt("token_end"),
        embedding = getBytes("embedding")?.let(::decodeEmbedding),
    )

    private fun ResultSet.toIntent(): PublicationIntent = PublicationIntent(
        id = getString("id"),
        documentId = DocumentId(getString("document_id")),
        collectionId = CollectionId(getString("collection_id")),
        baseRevisionId = getString("base_revision_id"),
        targetRevisionId = getString("target_revision_id"),
        phase = PublicationPhase.valueOf(getString("phase")),
        authoritativeAt = getString("authoritative_at"),
        preparedAt = getString("prepared_at"),
        publishedAt = getString("published_at"),
        cleanedAt = getString("cleaned_at"),
        errorCode = getString("error_code"),
        errorMessage = getString("error_message"),
    )

    private fun Connection.countUnits(documentId: DocumentId): Int =
        prepareStatement("SELECT COUNT(*) FROM content_units WHERE document_id = ?").use { statement ->
            statement.setString(1, documentId.value)
            statement.executeQuery().use { rows ->
                rows.next()
                rows.getInt(1)
            }
        }

    private companion object {
        const val SELECT_REVISIONS =
            "SELECT id, document_id, parent_revision_id, state, provenance, created_at FROM document_revisions"

        const val SELECT_PAGE_TEXTS =
            "SELECT revision_id, ordinal, unit_id, locator, extracted_text, search_text, text_sha256, " +
                "extraction_method, mean_confidence, artifact_relative_path, artifact_sha256, approval, " +
                "source_image_root, source_image_relative_path, source_image_sha256, source_image_width, " +
                "source_image_height, source_image_render_version FROM page_text_revisions"

        const val SELECT_CHUNKS =
            "SELECT unit_ordinal, ordinal, text, start_offset, end_offset, token_count, token_start, " +
                "token_end, embedding FROM revision_chunks"

        const val SELECT_RESTORES =
            "SELECT restore_id, collection_id, document_id, request_id, request_hash, expected_revision_id, " +
                "restored_from_revision_id, new_revision_id, phase, error_code, error_message, created_at, " +
                "updated_at FROM revision_restores"

        /** What a revision that is a restore of an earlier one records as its provenance. */
        const val PROVENANCE_RESTORE = "RESTORE"

        const val SELECT_INTENTS =
            "SELECT id, document_id, collection_id, base_revision_id, target_revision_id, phase, " +
                "authoritative_at, prepared_at, published_at, cleaned_at, error_code, error_message " +
                "FROM revision_publications"

        private val LOCATOR_JSON = Json { ignoreUnknownKeys = true }

        /** The revision text's own identity, so a stager can prove which text it staged. */
        internal fun sha256Of(text: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            return HexFormat.of().formatHex(digest.digest(text.toByteArray(Charsets.UTF_8)))
        }

        /** Vectors are stored little-endian float32, the same layout every platform reads back. */
        internal fun encodeEmbedding(vector: FloatArray): ByteArray {
            val buffer = ByteBuffer.allocate(vector.size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            vector.forEach(buffer::putFloat)
            return buffer.array()
        }

        internal fun decodeEmbedding(bytes: ByteArray): FloatArray {
            require(bytes.size % Float.SIZE_BYTES == 0) {
                "a stored embedding is a whole number of float32 values, was ${bytes.size} bytes"
            }
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            return FloatArray(bytes.size / Float.SIZE_BYTES) { buffer.getFloat() }
        }
    }
}
