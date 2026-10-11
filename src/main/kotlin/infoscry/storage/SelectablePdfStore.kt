package infoscry.storage

import infoscry.domain.CollectionId
import infoscry.domain.DocumentId
import infoscry.pdf.PdfPageResult
import java.sql.Connection
import java.sql.ResultSet
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Where one revision's selectable-PDF artifact is, mirroring [infoscry.pdf.PdfArtifactState] minus `NOT_CREATED`: an absent row is that state. */
enum class SelectablePdfArtifactState { QUEUED, BUILDING, READY, PARTIAL, FAILED }

/**
 * The durable row for one revision's selectable-PDF artifact, exactly as persisted.
 *
 * It is also the "desired" marker a crash between a publication committing and its job being enqueued
 * recovers from: the row exists from the moment [SelectablePdfStore.recordDesired] or
 * [SelectablePdfStore.recordFailed] is called, which are the only two places a row is created, so
 * recovery only ever scans rows this feature itself created — never every published revision in the
 * archive.
 */
data class SelectablePdfArtifactRow(
    val documentId: DocumentId,
    val revisionId: String,
    val collectionId: CollectionId,
    val state: SelectablePdfArtifactState,
    val key: String?,
    val jobId: String?,
    val manifestRelativePath: String?,
    val manifestSha256: String?,
    val pdfRelativePath: String?,
    val pdfSha256: String?,
    val pdfBytes: Long?,
    val totalPages: Int?,
    val selectablePages: Int,
    val blankPages: Int,
    val unavailablePages: List<PdfPageResult>,
    val reasonCode: String?,
    val attempt: Int,
    val createdAt: String,
    val updatedAt: String,
)

/**
 * Persistence for selectable-PDF artifacts (ticket 06 of the selectable-PDF plan).
 *
 * Every write below is a full replacement of the row's mutable columns by primary key, never a
 * state-guarded compare-and-set: the row has exactly one owner at a time (the job [job_id] names), so a
 * repeated write of the same step — the resume case — writes the same values again, which is a safe
 * no-op rather than a race to guard against. What ordering exists is the caller's: [SelectablePdfJobHandler]
 * never calls these out of its own sequence.
 */
class SelectablePdfStore(private val database: Database) {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Ensures a QUEUED row exists for (documentId, revisionId), already carrying [key], and returns it,
     * whatever state it is actually in.
     *
     * Idempotent by construction: a second call for the same identity finds the row this one already
     * inserted — whatever state it has reached since — and changes nothing. This is one of the two
     * places a row is created ([recordFailed] is the other), so it is the explicit, narrow signal
     * recovery scans for; it is never inferred from "every revision this document has published." The
     * key is part of the same INSERT as the row itself, never a follow-up write: a crash between the two
     * would otherwise leave a QUEUED row with no key, which [infoscry.pdf.PdfArtifactStatus] refuses to
     * represent.
     */
    fun recordDesired(documentId: DocumentId, collectionId: CollectionId, revisionId: String, key: String): SelectablePdfArtifactRow =
        database.transaction { connection ->
            selectRow(connection, documentId, revisionId) ?: run {
                val now = Instants.now()
                connection.prepareStatement(
                    "INSERT INTO selectable_pdf_artifacts " +
                        "(document_id, revision_id, collection_id, state, key, attempt, selectable_pages, " +
                        "blank_pages, created_at, updated_at) VALUES (?, ?, ?, 'QUEUED', ?, 0, 0, 0, ?, ?)",
                ).use { statement ->
                    statement.setString(1, documentId.value)
                    statement.setString(2, revisionId)
                    statement.setString(3, collectionId.value)
                    statement.setString(4, key)
                    statement.setString(5, now)
                    statement.setString(6, now)
                    statement.executeUpdate()
                }
                checkNotNull(selectRow(connection, documentId, revisionId))
            }
        }

    /**
     * Ensures a FAILED row exists for (documentId, revisionId), carrying [reasonCode], and returns it.
     *
     * The other row-creating entry point: a revision whose inputs cannot be resolved at all (a missing
     * or corrupt layout, a vanished original) never reaches QUEUED, because there is nothing to enqueue
     * a job for. Idempotent the same way [recordDesired] is.
     */
    fun recordFailed(documentId: DocumentId, collectionId: CollectionId, revisionId: String, reasonCode: String): SelectablePdfArtifactRow =
        database.transaction { connection ->
            selectRow(connection, documentId, revisionId) ?: run {
                val now = Instants.now()
                connection.prepareStatement(
                    "INSERT INTO selectable_pdf_artifacts " +
                        "(document_id, revision_id, collection_id, state, reason_code, attempt, " +
                        "selectable_pages, blank_pages, created_at, updated_at) " +
                        "VALUES (?, ?, ?, 'FAILED', ?, 0, 0, 0, ?, ?)",
                ).use { statement ->
                    statement.setString(1, documentId.value)
                    statement.setString(2, revisionId)
                    statement.setString(3, collectionId.value)
                    statement.setString(4, reasonCode)
                    statement.setString(5, now)
                    statement.setString(6, now)
                    statement.executeUpdate()
                }
                checkNotNull(selectRow(connection, documentId, revisionId))
            }
        }

    fun get(documentId: DocumentId, revisionId: String): SelectablePdfArtifactRow? =
        database.read { connection -> selectRow(connection, documentId, revisionId) }

    /**
     * Every row still QUEUED or BUILDING, oldest first: what a startup (or an `ensure`) checks for a
     * live job. QUEUED is the publication->enqueue gap; BUILDING is a crash mid-write whose attempt
     * never reached a durable result, so nothing committed that a fresh attempt could conflict with.
     */
    fun allPending(): List<SelectablePdfArtifactRow> = database.read { connection ->
        connection.prepareStatement(
            "$SELECT_ROW WHERE state IN ('QUEUED', 'BUILDING') ORDER BY created_at, document_id, revision_id",
        ).use { statement -> statement.executeQuery().use { rows -> rows.readAll() } }
    }

    /** Records which job owns this row's attempt now. Called right after the job is durably enqueued. */
    fun attachJob(documentId: DocumentId, revisionId: String, jobId: String) {
        database.transaction { connection ->
            connection.prepareStatement(
                "UPDATE selectable_pdf_artifacts SET job_id = ?, updated_at = ? WHERE document_id = ? AND revision_id = ?",
            ).use { statement ->
                statement.setString(1, jobId)
                statement.setString(2, Instants.now())
                statement.setString(3, documentId.value)
                statement.setString(4, revisionId)
                statement.executeUpdate()
            }
        }
    }

    /** Marks the row as the attempt now resolving inputs and writing, under [key]. */
    fun markBuilding(documentId: DocumentId, revisionId: String, key: String) {
        database.transaction { connection ->
            connection.prepareStatement(
                "UPDATE selectable_pdf_artifacts SET state = 'BUILDING', key = ?, reason_code = NULL, " +
                    "updated_at = ? WHERE document_id = ? AND revision_id = ?",
            ).use { statement ->
                statement.setString(1, key)
                statement.setString(2, Instants.now())
                statement.setString(3, documentId.value)
                statement.setString(4, revisionId)
                statement.executeUpdate()
            }
        }
    }

    /** Publishes a verified READY result: every page is selectable or verified blank. */
    fun markReady(
        documentId: DocumentId,
        revisionId: String,
        key: String,
        manifestRelativePath: String,
        manifestSha256: String,
        pdfRelativePath: String,
        pdfSha256: String,
        pdfBytes: Long,
        totalPages: Int,
        selectablePages: Int,
        blankPages: Int,
    ) = writeResult(
        documentId, revisionId, key, SelectablePdfArtifactState.READY, manifestRelativePath, manifestSha256,
        pdfRelativePath, pdfSha256, pdfBytes, totalPages, selectablePages, blankPages,
        unavailablePages = emptyList(), reasonCode = null,
    )

    /** Publishes a verified PARTIAL result: at least one page is UNAVAILABLE, each with its own reason. */
    fun markPartial(
        documentId: DocumentId,
        revisionId: String,
        key: String,
        manifestRelativePath: String,
        manifestSha256: String,
        pdfRelativePath: String,
        pdfSha256: String,
        pdfBytes: Long,
        totalPages: Int,
        selectablePages: Int,
        blankPages: Int,
        unavailablePages: List<PdfPageResult>,
    ) = writeResult(
        documentId, revisionId, key, SelectablePdfArtifactState.PARTIAL, manifestRelativePath, manifestSha256,
        pdfRelativePath, pdfSha256, pdfBytes, totalPages, selectablePages, blankPages,
        unavailablePages = unavailablePages, reasonCode = null,
    )

    /** Records a safe failure. No file is claimed: whatever the attempt wrote is not referenced by the row. */
    fun markFailed(documentId: DocumentId, revisionId: String, reasonCode: String, key: String? = null) {
        database.transaction { connection ->
            connection.prepareStatement(
                "UPDATE selectable_pdf_artifacts SET state = 'FAILED', key = ?, manifest_relative_path = NULL, " +
                    "manifest_sha256 = NULL, pdf_relative_path = NULL, pdf_sha256 = NULL, pdf_bytes = NULL, " +
                    "total_pages = NULL, selectable_pages = 0, blank_pages = 0, unavailable_pages = NULL, " +
                    "reason_code = ?, updated_at = ? WHERE document_id = ? AND revision_id = ?",
            ).use { statement ->
                statement.setString(1, key)
                statement.setString(2, reasonCode)
                statement.setString(3, Instants.now())
                statement.setString(4, documentId.value)
                statement.setString(5, revisionId)
                statement.executeUpdate()
            }
        }
    }

    /**
     * Re-admits a PARTIAL or FAILED row for an explicit retry, straight to QUEUED under [key]: job and
     * prior result cleared, [SelectablePdfArtifactRow.attempt] incremented, in the one UPDATE that also
     * guards the state.
     *
     * The key is part of this same UPDATE as the state change, for the same reason it is part of
     * [recordDesired]'s INSERT: a row must never be observably QUEUED with no key, not even between two
     * statements a crash could land between. A no-op (returns `null`) for a row that is not PARTIAL or
     * FAILED: `ensure`'s retry flag only ever repeats a finished, unsuccessful attempt, never a live one.
     */
    fun reQueueForRetry(documentId: DocumentId, revisionId: String, key: String): SelectablePdfArtifactRow? = database.transaction { connection ->
        val changed = connection.prepareStatement(
            "UPDATE selectable_pdf_artifacts SET state = 'QUEUED', key = ?, job_id = NULL, " +
                "manifest_relative_path = NULL, manifest_sha256 = NULL, pdf_relative_path = NULL, " +
                "pdf_sha256 = NULL, pdf_bytes = NULL, total_pages = NULL, selectable_pages = 0, " +
                "blank_pages = 0, unavailable_pages = NULL, reason_code = NULL, attempt = attempt + 1, " +
                "updated_at = ? WHERE document_id = ? AND revision_id = ? AND state IN ('PARTIAL', 'FAILED')",
        ).use { statement ->
            statement.setString(1, key)
            statement.setString(2, Instants.now())
            statement.setString(3, documentId.value)
            statement.setString(4, revisionId)
            statement.executeUpdate()
        }
        if (changed == 0) null else selectRow(connection, documentId, revisionId)
    }

    /**
     * Re-admits a PARTIAL or FAILED row for an explicit retry whose inputs still cannot be resolved:
     * stays FAILED under the new [reasonCode], attempt incremented. The retry/no-op guard mirrors
     * [reQueueForRetry].
     */
    fun reFailForRetry(documentId: DocumentId, revisionId: String, reasonCode: String): SelectablePdfArtifactRow? = database.transaction { connection ->
        val changed = connection.prepareStatement(
            "UPDATE selectable_pdf_artifacts SET state = 'FAILED', key = NULL, job_id = NULL, " +
                "manifest_relative_path = NULL, manifest_sha256 = NULL, pdf_relative_path = NULL, " +
                "pdf_sha256 = NULL, pdf_bytes = NULL, total_pages = NULL, selectable_pages = 0, " +
                "blank_pages = 0, unavailable_pages = NULL, reason_code = ?, attempt = attempt + 1, " +
                "updated_at = ? WHERE document_id = ? AND revision_id = ? AND state IN ('PARTIAL', 'FAILED')",
        ).use { statement ->
            statement.setString(1, reasonCode)
            statement.setString(2, Instants.now())
            statement.setString(3, documentId.value)
            statement.setString(4, revisionId)
            statement.executeUpdate()
        }
        if (changed == 0) null else selectRow(connection, documentId, revisionId)
    }

    private fun writeResult(
        documentId: DocumentId,
        revisionId: String,
        key: String,
        state: SelectablePdfArtifactState,
        manifestRelativePath: String,
        manifestSha256: String,
        pdfRelativePath: String,
        pdfSha256: String,
        pdfBytes: Long,
        totalPages: Int,
        selectablePages: Int,
        blankPages: Int,
        unavailablePages: List<PdfPageResult>,
        reasonCode: String?,
    ) {
        database.transaction { connection ->
            connection.prepareStatement(
                "UPDATE selectable_pdf_artifacts SET state = ?, key = ?, manifest_relative_path = ?, " +
                    "manifest_sha256 = ?, pdf_relative_path = ?, pdf_sha256 = ?, pdf_bytes = ?, " +
                    "total_pages = ?, selectable_pages = ?, blank_pages = ?, unavailable_pages = ?, " +
                    "reason_code = ?, updated_at = ? WHERE document_id = ? AND revision_id = ?",
            ).use { statement ->
                statement.setString(1, state.name)
                statement.setString(2, key)
                statement.setString(3, manifestRelativePath)
                statement.setString(4, manifestSha256)
                statement.setString(5, pdfRelativePath)
                statement.setString(6, pdfSha256)
                statement.setLong(7, pdfBytes)
                statement.setInt(8, totalPages)
                statement.setInt(9, selectablePages)
                statement.setInt(10, blankPages)
                statement.setString(
                    11,
                    if (unavailablePages.isEmpty()) null else json.encodeToString(unavailablePages),
                )
                statement.setString(12, reasonCode)
                statement.setString(13, Instants.now())
                statement.setString(14, documentId.value)
                statement.setString(15, revisionId)
                statement.executeUpdate()
            }
        }
    }

    private fun selectRow(connection: Connection, documentId: DocumentId, revisionId: String): SelectablePdfArtifactRow? =
        connection.prepareStatement("$SELECT_ROW WHERE document_id = ? AND revision_id = ?").use { statement ->
            statement.setString(1, documentId.value)
            statement.setString(2, revisionId)
            statement.executeQuery().use { rows -> if (rows.next()) rows.toRow() else null }
        }

    private fun ResultSet.readAll(): List<SelectablePdfArtifactRow> = buildList { while (next()) add(toRow()) }

    private fun ResultSet.toRow(): SelectablePdfArtifactRow = SelectablePdfArtifactRow(
        documentId = DocumentId(getString("document_id")),
        revisionId = getString("revision_id"),
        collectionId = CollectionId(getString("collection_id")),
        state = SelectablePdfArtifactState.valueOf(getString("state")),
        key = getString("key"),
        jobId = getString("job_id"),
        manifestRelativePath = getString("manifest_relative_path"),
        manifestSha256 = getString("manifest_sha256"),
        pdfRelativePath = getString("pdf_relative_path"),
        pdfSha256 = getString("pdf_sha256"),
        pdfBytes = getLong("pdf_bytes").takeUnless { wasNull() },
        totalPages = getInt("total_pages").takeUnless { wasNull() },
        selectablePages = getInt("selectable_pages"),
        blankPages = getInt("blank_pages"),
        unavailablePages = getString("unavailable_pages")
            ?.let { json.decodeFromString<List<PdfPageResult>>(it) }
            ?: emptyList(),
        reasonCode = getString("reason_code"),
        attempt = getInt("attempt"),
        createdAt = getString("created_at"),
        updatedAt = getString("updated_at"),
    )

    private companion object {
        const val SELECT_ROW =
            "SELECT document_id, revision_id, collection_id, state, key, job_id, manifest_relative_path, " +
                "manifest_sha256, pdf_relative_path, pdf_sha256, pdf_bytes, total_pages, selectable_pages, " +
                "blank_pages, unavailable_pages, reason_code, attempt, created_at, updated_at " +
                "FROM selectable_pdf_artifacts"
    }
}
