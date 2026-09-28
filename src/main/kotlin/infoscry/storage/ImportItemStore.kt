package infoscry.storage

import infoscry.domain.DocumentId
import infoscry.domain.JobId
import kotlinx.serialization.Serializable
import java.sql.Connection
import java.sql.ResultSet
import java.util.UUID

/** How one selected source file fared. */
enum class ImportItemOutcome {
    PENDING,
    IMPORTED,
    DUPLICATE,
    FAILED,

    /**
     * The document this file became is being deleted, so the file is neither pending nor imported.
     *
     * This is a disposition the handler obeys, not a guess: it is written when the deletion is admitted
     * and it survives cancellation, restart and resume, which is what stops a later attempt from copying
     * the deleted document's bytes into the collection again.
     */
    CANCELLED,
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
    val sourceName: String? = null,
    val documentId: DocumentId?,
    val outcome: ImportItemOutcome,
    val errorCode: String?,
    val errorMessage: String?,
    val createdAt: String,
    val updatedAt: String,
    /**
     * Whether this file's own copy made the document it is attached to, rather than finding its bytes
     * already stored as a duplicate.
     *
     * It is the durable distinction a document deletion needs: only a document a cancelled file *created* at
     * one of the deletion's paths is the deletion's to remove, and a duplicate it merely found belongs to
     * whatever imported it first. Nothing else can tell the two apart — both leave an item naming a document
     * at the same path, and both writes can share a millisecond.
     */
    val createdDocument: Boolean = false,
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
     *
     * [createdByThisCopy] is the copy's own answer about the document it produced: `true` when this attempt's
     * copy laid the bytes down as a new document, `false` when it found them already stored. It can only ever
     * *add* [ImportItem.createdDocument] here, never clear it: the durable answer is written by
     * [noteCreatedDocument] inside the copy's own transaction, and this write follows it — often after a
     * deletion has already dispositioned the item, when it will not match at all.
     *
     * The write obeys the same disposition [record] does: a file whose disposition is already
     * `CANCELLED` is left as it is, and an identifier whose document row is gone is not written. A copy
     * and a document deletion can finish either side of the other, and naming a document that is gone
     * would fail the whole import through the item's foreign key instead of cancelling one file.
     */
    fun attachDocument(
        jobId: JobId,
        itemKey: String,
        documentId: DocumentId,
        createdByThisCopy: Boolean = false,
    ): ImportItem =
        update(jobId, itemKey) { connection ->
            connection.prepareStatement(
                "UPDATE import_items SET document_id = (SELECT id FROM documents WHERE id = ?), " +
                    "created_document = CASE WHEN ? = 1 THEN 1 ELSE created_document END, updated_at = ? " +
                    "WHERE job_id = ? AND item_key = ? AND outcome <> ?",
            ).use { statement ->
                statement.setString(1, documentId.value)
                statement.setInt(2, if (createdByThisCopy) 1 else 0)
                statement.setString(3, Instants.now())
                statement.setString(4, jobId.value)
                statement.setString(5, itemKey)
                statement.setString(6, ImportItemOutcome.CANCELLED.name)
                statement.executeUpdate()
            }
        }

    /**
     * Records, in the caller's own transaction, that this file's copy created [documentId].
     *
     * This is the durable half of a copy: it is written by the same commit as the document row, so a crash,
     * a refused attach or an admitted deletion can never separate "a document exists for this path" from
     * "this file made it". A document deletion of the path finds the document through this row — that is why
     * the outcome is deliberately *not* part of the condition: the case this exists for is precisely a
     * deletion that has already dispositioned the item, and what it records is a link, not a disposition.
     * Nothing here changes the item's outcome, so a cancelled file stays cancelled work.
     */
    fun noteCreatedDocument(
        connection: Connection,
        jobId: JobId,
        itemKey: String,
        documentId: DocumentId,
    ) {
        connection.prepareStatement(
            "UPDATE import_items SET document_id = ?, created_document = 1, updated_at = ? " +
                "WHERE job_id = ? AND item_key = ?",
        ).use { statement ->
            statement.setString(1, documentId.value)
            statement.setString(2, Instants.now())
            statement.setString(3, jobId.value)
            statement.setString(4, itemKey)
            statement.executeUpdate()
        }
    }

    /**
     * Marks every item of this job that holds [documentId] as `CANCELLED`.
     *
     * Used when a stage discovers mid-flight that its document is being deleted: the item's own
     * disposition is the durable record the next attempt obeys, and the other items of the job are left
     * exactly as they are so the remaining files keep running.
     */
    fun cancelTargeting(jobId: JobId, documentId: DocumentId): Int =
        database.transaction { connection ->
            connection.prepareStatement(
                "UPDATE import_items SET outcome = ?, updated_at = ? WHERE job_id = ? AND document_id = ?",
            ).use { statement ->
                statement.setString(1, ImportItemOutcome.CANCELLED.name)
                statement.setString(2, Instants.now())
                statement.setString(3, jobId.value)
                statement.setString(4, documentId.value)
                statement.executeUpdate()
            }
        }

    /**
     * Records how the item ended. A failure keeps its code and an actionable message.
     *
     * Two things this write refuses, because a document deletion can land between an attempt's last
     * document write and this one — the window a foreign key turns into a whole job's failure:
     *
     * - an item already dispositioned `CANCELLED` is left exactly as it is, because the cancellation is
     *   the durable disposition and an `IMPORTED` written over it would resurrect work the deletion
     *   already decided against;
     * - a document id whose row is gone is not written, so the file ends as the cancelled file the
     *   deletion made it rather than failing the import through the item's foreign key.
     */
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
                "UPDATE import_items SET outcome = ?, " +
                    "document_id = CASE WHEN ? IS NULL THEN document_id ELSE " +
                    "(SELECT id FROM documents WHERE id = ?) END, " +
                    "error_code = ?, error_message = ?, updated_at = ? " +
                    "WHERE job_id = ? AND item_key = ? AND outcome <> ?",
            ).use { statement ->
                statement.setString(1, outcome.name)
                statement.setString(2, documentId?.value)
                statement.setString(3, documentId?.value)
                statement.setString(4, errorCode)
                statement.setString(5, errorMessage)
                statement.setString(6, Instants.now())
                statement.setString(7, jobId.value)
                statement.setString(8, itemKey)
                statement.setString(9, ImportItemOutcome.CANCELLED.name)
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
        createdDocument = getInt("created_document") == 1,
    )

    private companion object {

        const val SELECT_ITEMS =
            "SELECT id, job_id, item_key, source_path, document_id, outcome, error_code, error_message, " +
                "created_at, updated_at, created_document FROM import_items"
    }
}
