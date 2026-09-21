package infoscry.storage

import infoscry.domain.DocumentId
import infoscry.domain.JobId
import kotlinx.serialization.Serializable
import java.sql.ResultSet
import java.util.UUID

/** How one selected source file fared. */
enum class ImportItemOutcome {
    PENDING,
    IMPORTED,
    DUPLICATE,
    FAILED,
}

/**
 * One source file of an import, and what happened to it.
 *
 * The item is what makes a big import resumable and observable: [itemKey] identifies the file
 * independently of the attempt that first queued it, and [documentId] is written as soon as the bytes are
 * in the managed library, so a later attempt picks up that document instead of copying the file again —
 * even if the user has meanwhile moved the file it came from.
 */
@Serializable
data class ImportItem(
    val id: String,
    val jobId: JobId,
    val itemKey: String,
    val sourcePath: String,
    val documentId: DocumentId?,
    val outcome: ImportItemOutcome,
    val errorCode: String?,
    val errorMessage: String?,
    val createdAt: String,
    val updatedAt: String,
)

/**
 * Persistence for one import's per-file results.
 *
 * The table is written one item at a time, as the attempt reaches it, rather than as a batch at the end:
 * a job that imports ten thousand files and is killed on the last one must not lose the nine thousand
 * nine hundred that already succeeded, and the queue view has to show which file is being worked on now.
 *
 * Item keys are unique per job, so queueing the same file twice in one job is a no-op rather than a
 * second copy of the same work.
 */
class ImportItemStore(private val database: Database) {

    /** Queues one file for this job, or returns the row an earlier attempt already queued. */
    fun queue(jobId: JobId, itemKey: String, sourcePath: String): ImportItem {
        require(itemKey.isNotBlank()) { "an import item needs a key" }
        require(sourcePath.isNotBlank()) { "an import item needs the path it was selected from" }
        val now = Instants.now()
        database.transaction { connection ->
            connection.prepareStatement(
                "INSERT INTO import_items (id, job_id, item_key, source_path, outcome, created_at, updated_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT (job_id, item_key) DO NOTHING",
            ).use { statement ->
                statement.setString(1, UUID.randomUUID().toString())
                statement.setString(2, jobId.value)
                statement.setString(3, itemKey)
                statement.setString(4, sourcePath)
                statement.setString(5, ImportItemOutcome.PENDING.name)
                statement.setString(6, now)
                statement.setString(7, now)
                statement.executeUpdate()
            }
        }
        return require(jobId, itemKey)
    }

    fun find(jobId: JobId, itemKey: String): ImportItem? = database.read { connection ->
        connection.prepareStatement("$SELECT_ITEMS WHERE job_id = ? AND item_key = ?").use { statement ->
            statement.setString(1, jobId.value)
            statement.setString(2, itemKey)
            statement.executeQuery().use { rows -> if (rows.next()) rows.toItem() else null }
        }
    }

    /** One job's items in the order they were queued, which is the order the attempt worked through them. */
    fun listForJob(jobId: JobId): List<ImportItem> = database.read { connection ->
        connection.prepareStatement("$SELECT_ITEMS WHERE job_id = ? ORDER BY rowid").use { statement ->
            statement.setString(1, jobId.value)
            statement.executeQuery().use { rows ->
                buildList { while (rows.next()) add(rows.toItem()) }
            }
        }
    }

    /**
     * Records the managed document a file became, before the rest of its work has run.
     *
     * This is the row that makes a resume cheap: the next attempt finds the bytes already copied and
     * continues with extraction instead of reading the source file again — which it may no longer be able
     * to reach.
     */
    fun attachDocument(jobId: JobId, itemKey: String, documentId: DocumentId): ImportItem =
        update(jobId, itemKey) { connection ->
            connection.prepareStatement(
                "UPDATE import_items SET document_id = ?, updated_at = ? WHERE job_id = ? AND item_key = ?",
            ).use { statement ->
                statement.setString(1, documentId.value)
                statement.setString(2, Instants.now())
                statement.setString(3, jobId.value)
                statement.setString(4, itemKey)
                statement.executeUpdate()
            }
        }

    /** Records how the item ended. A failure keeps its code and an actionable message. */
    fun record(
        jobId: JobId,
        itemKey: String,
        outcome: ImportItemOutcome,
        documentId: DocumentId? = null,
        errorCode: String? = null,
        errorMessage: String? = null,
    ): ImportItem {
        require(outcome != ImportItemOutcome.PENDING) { "an outcome is what the item ended as" }
        return update(jobId, itemKey) { connection ->
            connection.prepareStatement(
                "UPDATE import_items SET outcome = ?, document_id = COALESCE(?, document_id), error_code = ?, " +
                    "error_message = ?, updated_at = ? WHERE job_id = ? AND item_key = ?",
            ).use { statement ->
                statement.setString(1, outcome.name)
                statement.setString(2, documentId?.value)
                statement.setString(3, errorCode)
                statement.setString(4, errorMessage)
                statement.setString(5, Instants.now())
                statement.setString(6, jobId.value)
                statement.setString(7, itemKey)
                statement.executeUpdate()
            }
        }
    }

    private fun update(jobId: JobId, itemKey: String, change: (java.sql.Connection) -> Unit): ImportItem {
        database.transaction { connection -> change(connection) }
        return require(jobId, itemKey)
    }

    private fun require(jobId: JobId, itemKey: String): ImportItem =
        find(jobId, itemKey)
            ?: throw NoSuchElementException("no import item '$itemKey' in job ${jobId.value}")

    private fun ResultSet.toItem(): ImportItem = ImportItem(
        id = getString("id"),
        jobId = JobId(getString("job_id")),
        itemKey = getString("item_key"),
        sourcePath = getString("source_path"),
        documentId = getString("document_id")?.let(::DocumentId),
        outcome = ImportItemOutcome.valueOf(getString("outcome")),
        errorCode = getString("error_code"),
        errorMessage = getString("error_message"),
        createdAt = getString("created_at"),
        updatedAt = getString("updated_at"),
    )

    private companion object {

        const val SELECT_ITEMS =
            "SELECT id, job_id, item_key, source_path, document_id, outcome, error_code, error_message, " +
                "created_at, updated_at FROM import_items"
    }
}
