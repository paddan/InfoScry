package infoscry.storage

import infoscry.domain.DocumentId
import infoscry.domain.UserRef

/** A person's decision on one published reading. */
data class PageDecision(
    val documentId: DocumentId,
    val revisionId: String,
    val decision: String,
    val decidedBy: UserRef?,
    val decidedAt: String,
    val restoreId: String?,
)

/**
 * Persistence for `page_decisions`, and the attribution of document revisions that page review shows.
 *
 * The first decision for a revision decides: [decide] inserts only when there is none, and always answers the
 * decision on record, so a second person (or a repeated request) learns what was decided and by whom.
 */
class PageDecisionStore(private val database: Database) {

    /** Records [decision] unless one exists; answers the recorded one and whether this call made it. */
    fun decide(documentId: DocumentId, revisionId: String, decision: String): Pair<PageDecision, Boolean> =
        database.transaction { connection ->
            val inserted = connection.prepareStatement(
                "INSERT INTO page_decisions (document_id, revision_id, decision, decided_by, decided_at) " +
                    "VALUES (?, ?, ?, ?, ?) ON CONFLICT (document_id, revision_id) DO NOTHING",
            ).use { statement ->
                statement.setString(1, documentId.value)
                statement.setString(2, revisionId)
                statement.setString(3, decision)
                statement.setString(4, Actor.current()?.value)
                statement.setString(5, Instants.now())
                statement.executeUpdate() == 1
            }
            checkNotNull(get(documentId, revisionId)) to inserted
        }

    fun recordRestore(documentId: DocumentId, revisionId: String, restoreId: String) {
        database.transaction { connection ->
            connection.prepareStatement(
                "UPDATE page_decisions SET restore_id = ? WHERE document_id = ? AND revision_id = ?",
            ).use { statement ->
                statement.setString(1, restoreId)
                statement.setString(2, documentId.value)
                statement.setString(3, revisionId)
                statement.executeUpdate()
            }
        }
    }

    /** Withdraws a decision whose restore could not even be admitted, so deciding again is possible. */
    fun withdraw(documentId: DocumentId, revisionId: String) {
        database.transaction { connection ->
            connection.prepareStatement(
                "DELETE FROM page_decisions WHERE document_id = ? AND revision_id = ? AND restore_id IS NULL",
            ).use { statement ->
                statement.setString(1, documentId.value)
                statement.setString(2, revisionId)
                statement.executeUpdate()
            }
        }
    }

    fun get(documentId: DocumentId, revisionId: String): PageDecision? = forDocument(documentId)[revisionId]

    /** The decisions on [documentId]'s revisions, by revision. */
    fun forDocument(documentId: DocumentId): Map<String, PageDecision> = database.read { connection ->
        connection.prepareStatement(
            "SELECT p.revision_id, p.decision, p.decided_by, u.display_name, p.decided_at, p.restore_id " +
                "FROM page_decisions p LEFT JOIN users u ON u.id = p.decided_by WHERE p.document_id = ?",
        ).use { statement ->
            statement.setString(1, documentId.value)
            statement.executeQuery().use { rows ->
                buildMap {
                    while (rows.next()) {
                        val revisionId = rows.getString(1)
                        put(
                            revisionId,
                            PageDecision(
                                documentId = documentId,
                                revisionId = revisionId,
                                decision = rows.getString(2),
                                decidedBy = rows.getString(3)?.let { UserRef(it, rows.getString(4) ?: it) },
                                decidedAt = rows.getString(5),
                                restoreId = rows.getString(6),
                            ),
                        )
                    }
                }
            }
        }
    }

    /** Who created each of [documentId]'s revisions (the person whose run or edit produced it). */
    fun revisionAuthors(documentId: DocumentId): Map<String, UserRef> = database.read { connection ->
        connection.prepareStatement(
            "SELECT r.id, r.created_by, u.display_name FROM document_revisions r " +
                "JOIN users u ON u.id = r.created_by WHERE r.document_id = ?",
        ).use { statement ->
            statement.setString(1, documentId.value)
            statement.executeQuery().use { rows ->
                buildMap { while (rows.next()) put(rows.getString(1), UserRef(rows.getString(2), rows.getString(3))) }
            }
        }
    }
}
