package infoscry.storage

import infoscry.domain.DocumentId

/** Minimal fields retained to explain historical revisions written by the retired review workflow. */
data class HistoricalPageReview(
    val baselineRevisionId: String?,
    val candidateHash: String,
    val disposition: HistoricalReviewDisposition,
)

/** Stored enum values are parsed only for revision history; no current workflow creates review rows. */
enum class HistoricalReviewDisposition { KEEP, PROPOSE, APPROVE }

/** Read-only access to old review rows, retained only for revision-history attribution. */
class OcrReviewStore(private val database: Database) {

    fun forPage(documentId: DocumentId, unitId: String, ordinal: Int): List<HistoricalPageReview> =
        database.read { connection ->
            connection.prepareStatement(
                "SELECT baseline_revision_id, candidate_hash, disposition FROM page_reviews " +
                    "WHERE document_id = ? AND unit_id = ? AND ordinal = ? ORDER BY created_at, rowid",
            ).use { statement ->
                statement.setString(1, documentId.value)
                statement.setString(2, unitId)
                statement.setInt(3, ordinal)
                statement.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) {
                            add(
                                HistoricalPageReview(
                                    baselineRevisionId = rows.getString("baseline_revision_id"),
                                    candidateHash = rows.getString("candidate_hash"),
                                    disposition = HistoricalReviewDisposition.valueOf(rows.getString("disposition")),
                                ),
                            )
                        }
                    }
                }
            }
        }
}
