package infoscry.storage

import infoscry.domain.CollectionId
import infoscry.domain.Job
import infoscry.domain.JobId
import infoscry.domain.JobState
import infoscry.domain.JobType
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Nothing was ever enqueued under this job id. */
class NoSuchJobException(val jobId: JobId) : NoSuchElementException("no job with id ${jobId.value}")

/**
 * A job was asked to do something its state does not allow — reporting progress from a job that is not
 * running, or finishing one that already ended.
 *
 * [requirement] says what the caller would have needed, so the failure names the fix rather than only
 * the symptom.
 */
class JobStateException(val jobId: JobId, val state: JobState, val requirement: String) :
    IllegalStateException("job ${jobId.value} is $state but $requirement")

/**
 * Persistence for the durable work queue.
 *
 * Every state change is a compare-and-set inside a transaction: the `WHERE` clause names the state the
 * job must be in, and a statement that updates no row is a refusal rather than a silent no-op. That is
 * what makes claiming safe with more than one worker and what makes a crash between two steps
 * recoverable — the recorded state is always a state the process actually reached.
 *
 * The read methods are not a safety mechanism. A caller that needs to decide and then write has to do
 * both in the store, because anything read outside a transaction can be stale by the time it is used.
 *
 * The class is open only so [claimNextQueued] can be overridden by a test that needs one claim to fail.
 */
open class JobStore(private val database: Database) {

    fun enqueue(
        type: JobType,
        collectionId: CollectionId? = null,
        payload: String? = null,
        total: Int = 0,
    ): Job {
        require(total >= 0) { "a job's total must not be negative, was $total" }
        val now = Instants.now()
        val job = Job(
            id = JobId.new(),
            type = type,
            state = JobState.QUEUED,
            collectionId = collectionId,
            payload = payload,
            total = total,
            createdAt = now,
            updatedAt = now,
        )
        database.transaction { connection ->
            // Checked inside the transaction rather than read beforehand, so a collection deleted in
            // between cannot turn this refusal into a foreign-key error, and checked by query instead of
            // by matching the driver's message text, which a driver upgrade would reword into an
            // internal error the caller cannot act on.
            if (collectionId != null && !collectionExists(connection, collectionId)) {
                throw NoSuchElementException("no collection with id ${collectionId.value}")
            }
            connection.prepareStatement(INSERT_JOB).use { statement ->
                bind(statement, job)
                statement.executeUpdate()
            }
        }
        return job
    }

    fun get(id: JobId): Job? = database.read { connection -> selectById(connection, id) }

    /**
     * Jobs newest first, one page at a time. [limit] has no default on purpose: a caller that silently
     * saw the first hundred jobs of a long queue would report the queue as shorter than it is.
     *
     * Ordering ties are broken by insertion order (`rowid`), because two jobs enqueued inside the same
     * millisecond must still page deterministically.
     */
    fun list(limit: Int, offset: Int = 0): List<Job> {
        require(limit > 0) { "limit must be positive, was $limit" }
        require(offset >= 0) { "offset must not be negative, was $offset" }
        return database.read { connection ->
            connection.prepareStatement(
                "$SELECT_JOBS ORDER BY created_at DESC, rowid DESC LIMIT ? OFFSET ?",
            ).use { statement ->
                statement.setInt(1, limit)
                statement.setInt(2, offset)
                statement.executeQuery().use { rows -> rows.readAll() }
            }
        }
    }

    /** Legacy jobs that ended only because an external-page approval was requested. */
    fun legacyAwaitingApprovalJobs(): List<Job> = database.read { connection ->
        connection.prepareStatement(
            "$SELECT_JOBS WHERE state = ? AND stage = ? ORDER BY created_at, rowid",
        ).use { statement ->
            statement.setString(1, JobState.COMPLETE.name)
            statement.setString(2, "awaiting-approval")
            statement.executeQuery().use { rows -> rows.readAll() }
        }
    }

    /** Cancels a legacy paused job only while its persisted state still matches. */
    fun cancelLegacyAwaitingApproval(id: JobId): Boolean = database.transaction { connection ->
        connection.prepareStatement(
            "UPDATE jobs SET state = ?, stage = NULL, current_item = NULL, cancel_requested = 1, " +
                "updated_at = ? WHERE id = ? AND state = ? AND stage = ?",
        ).use { statement ->
            statement.setString(1, JobState.CANCELLED.name)
            statement.setString(2, Instants.now())
            statement.setString(3, id.value)
            statement.setString(4, JobState.COMPLETE.name)
            statement.setString(5, "awaiting-approval")
            statement.executeUpdate() == 1
        }
    }

    /** Fails a rescan job whose operation cleanup already determined that its process was interrupted. */
    fun failInterruptedRescanJob(id: JobId): Boolean = database.transaction { connection ->
        connection.prepareStatement(
            "UPDATE jobs SET state = ?, stage = NULL, current_item = NULL, cancel_requested = 1, " +
                "error_code = 'INTERRUPTED', " +
                "error_message = 'the previous reading was interrupted; start it again', updated_at = ? " +
                "WHERE id = ? AND type = ? AND state IN (?, ?)",
        ).use { statement ->
            statement.setString(1, JobState.FAILED.name)
            statement.setString(2, Instants.now())
            statement.setString(3, id.value)
            statement.setString(4, JobType.RESCAN.name)
            statement.setString(5, JobState.QUEUED.name)
            statement.setString(6, JobState.RUNNING.name)
            statement.executeUpdate() == 1
        }
    }

    /** Completes a queued/running rescan whose candidate is already the document's authoritative revision. */
    fun completePublishedRescanJob(id: JobId): Boolean = database.transaction { connection ->
        connection.prepareStatement(
            "UPDATE jobs SET state = ?, stage = NULL, current_item = NULL, cancel_requested = 0, " +
                "error_code = NULL, error_message = NULL, updated_at = ? " +
                "WHERE id = ? AND type = ? AND state IN (?, ?)",
        ).use { statement ->
            statement.setString(1, JobState.COMPLETE.name)
            statement.setString(2, Instants.now())
            statement.setString(3, id.value)
            statement.setString(4, JobType.RESCAN.name)
            statement.setString(5, JobState.QUEUED.name)
            statement.setString(6, JobState.RUNNING.name)
            statement.executeUpdate() == 1
        }
    }

    /** Repairs a rescan job after an older process cleared its operation pointer before ending the job. */
    fun finishRescanJobsForOperation(operationId: String, published: Boolean): Int = database.transaction { connection ->
        val candidates = connection.prepareStatement(
            "$SELECT_JOBS WHERE type = ? AND state IN (?, ?)",
        ).use { statement ->
            statement.setString(1, JobType.RESCAN.name)
            statement.setString(2, JobState.QUEUED.name)
            statement.setString(3, JobState.RUNNING.name)
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        val job = rows.toJob()
                        val payloadOperationId = runCatching {
                            Json.parseToJsonElement(job.payload.orEmpty()).jsonObject
                                .getValue("operationId").jsonPrimitive.content
                        }.getOrNull()
                        if (payloadOperationId == operationId) add(job.id)
                    }
                }
            }
        }
        candidates.count { id ->
            if (published) completePublishedRescanJob(id) else failInterruptedRescanJob(id)
        }
    }

    /**
     * One page of a collection's import jobs, newest first.
     *
     * Import history is a collection-scoped read: another collection's import is never selected, so a page
     * can only describe the collection the caller named. Ordering ties are broken by insertion order
     * (`rowid`) for the same reason [list] does it: two imports enqueued in the same millisecond must still
     * page deterministically. Reindex jobs are not imports and are not part of this history.
     */
    fun listImports(collectionId: CollectionId, limit: Int, offset: Int = 0): List<Job> {
        require(limit > 0) { "limit must be positive, was $limit" }
        require(offset >= 0) { "offset must not be negative, was $offset" }
        return database.read { connection ->
            connection.prepareStatement(
                "$SELECT_JOBS WHERE collection_id = ? AND type = ? ORDER BY created_at DESC, rowid DESC " +
                    "LIMIT ? OFFSET ?",
            ).use { statement ->
                statement.setString(1, collectionId.value)
                statement.setString(2, JobType.IMPORT.name)
                statement.setInt(3, limit)
                statement.setInt(4, offset)
                statement.executeQuery().use { rows -> rows.readAll() }
            }
        }
    }

    /** How many imports a collection holds, under the same criteria [listImports] pages through. */
    fun countImports(collectionId: CollectionId): Int = database.read { connection ->
        connection.prepareStatement("SELECT COUNT(*) FROM jobs WHERE collection_id = ? AND type = ?").use { statement ->
            statement.setString(1, collectionId.value)
            statement.setString(2, JobType.IMPORT.name)
            statement.executeQuery().use { rows ->
                rows.next()
                rows.getInt(1)
            }
        }
    }

    /**
     * Takes one job for a worker, or returns `null` when it is no longer queued — an unknown id and an
     * already-claimed job are the same answer, because both mean there is nothing to take.
     */
    fun claim(id: JobId): Job? = database.transaction { connection -> claimIn(connection, id) }

    /**
     * Takes the oldest queued job in one transaction, so two workers cannot be handed the same one.
     *
     * Oldest first keeps the queue fair: an archive of thousands of documents is imported in the order
     * it was handed over, not in whatever order a poll happens to see.
     *
     * Open so a test can make one claim fail and prove the dispatcher keeps claiming afterwards — a
     * failure here used to end the claim loop silently, which left a live runner with a full queue and no
     * worker.
     */
    open fun claimNextQueued(): Job? = database.transaction { connection ->
        val next = connection.prepareStatement(
            "$SELECT_JOBS WHERE state = ? ORDER BY created_at, rowid LIMIT 1",
        ).use { statement ->
            statement.setString(1, JobState.QUEUED.name)
            statement.executeQuery().use { rows -> if (rows.next()) rows.toJob() else null }
        } ?: return@transaction null
        claimIn(connection, next.id)
    }

    /**
     * Records the stage an attempt is in, how far it has come, and which file it is holding.
     *
     * A `null` argument keeps what the row already holds, so a caller that only reports the file it is
     * reading does not have to repeat the counters it reported a moment ago.
     */
    fun progress(
        id: JobId,
        stage: String? = null,
        completed: Int? = null,
        total: Int? = null,
        currentItem: String? = null,
    ): Job = database.transaction { connection ->
        val job = requireRunning(connection, id, "progress is only reported while a job is RUNNING")
        val nextCompleted = completed ?: job.completed
        val nextTotal = total ?: job.total
        require(nextCompleted >= 0) { "job ${id.value} reported $nextCompleted completed items" }
        require(nextTotal >= 0) { "job ${id.value} reported a total of $nextTotal items" }
        require(nextCompleted <= nextTotal) {
            "job ${id.value} reported $nextCompleted of $nextTotal items; completed cannot pass total"
        }
        updateIn(
            connection,
            "UPDATE jobs SET stage = ?, completed = ?, total = ?, current_item = ?, updated_at = ? " +
                "WHERE id = ? AND state = ?",
            stage ?: job.stage,
            nextCompleted,
            nextTotal,
            currentItem ?: job.currentItem,
            Instants.now(),
            id.value,
            JobState.RUNNING.name,
        )
        readIn(connection, id)
    }

    /**
     * Ends a running attempt as complete.
     *
     * The stage the attempt last entered is cleared, because it described work that has stopped: a finished
     * import must not read as still queued or copying.
     */
    fun complete(id: JobId): Job = database.transaction { connection ->
        val job = requireRunning(connection, id, "only a RUNNING attempt can complete")
        check(isLegalTransition(JobState.RUNNING, JobState.COMPLETE)) {
            "the transition table must allow RUNNING to COMPLETE"
        }
        updateIn(
            connection,
            "UPDATE jobs SET state = ?, stage = ?, updated_at = ? WHERE id = ? AND state = ?",
            JobState.COMPLETE.name,
            null,
            Instants.now(),
            id.value,
            JobState.RUNNING.name,
        )
        readIn(connection, id)
    }

    /**
     * Ends a failed attempt with a code a caller can branch on and a message an operator can act on.
     *
     * The message is written by the worker, so it names the tool or the file, never the document's own
     * content: the queue is displayed in the CLI and the web UI.
     */
    fun fail(id: JobId, code: String, message: String): Job = database.transaction { connection ->
        require(code.isNotBlank()) { "a job failure needs an error code" }
        require(message.isNotBlank()) { "a job failure needs an actionable message" }
        requireRunning(connection, id, "only a RUNNING attempt can fail")
        updateIn(
            connection,
            "UPDATE jobs SET state = ?, stage = NULL, error_code = ?, error_message = ?, " +
                "updated_at = ? WHERE id = ? AND state = ?",
            JobState.FAILED.name,
            code,
            message,
            Instants.now(),
            id.value,
            JobState.RUNNING.name,
        )
        readIn(connection, id)
    }

    /**
     * Records the durable cancellation request.
     *
     * A queued job is cancelled outright: nothing has started, so there is no stage to interrupt. A
     * running job is only flagged — its worker may still be inside work that has to finish and record
     * what it did — and [finishCancelled] is the honest end of it. A job that already ended is returned
     * unchanged: cancelling finished work is not an error, it is simply too late.
     */
    fun cancel(id: JobId): Job = database.transaction { connection ->
        val job = selectById(connection, id) ?: throw NoSuchJobException(id)
        when (job.state) {
            JobState.QUEUED -> {
                check(isLegalTransition(JobState.QUEUED, JobState.CANCELLED)) {
                    "the transition table must allow cancelling a queued job"
                }
                updateIn(
                    connection,
                    "UPDATE jobs SET state = ?, stage = NULL, cancel_requested = 1, " +
                        "updated_at = ? WHERE id = ? AND state = ?",
                    JobState.CANCELLED.name,
                    Instants.now(),
                    id.value,
                    JobState.QUEUED.name,
                )
            }

            JobState.RUNNING -> updateIn(
                connection,
                "UPDATE jobs SET cancel_requested = 1, updated_at = ? WHERE id = ? AND state = ?",
                Instants.now(),
                id.value,
                JobState.RUNNING.name,
            )

            else -> Unit
        }
        readIn(connection, id)
    }

    /** Ends a running attempt after its worker observed the cancellation request. */
    fun finishCancelled(id: JobId): Job = database.transaction { connection ->
        val job = requireRunning(connection, id, "only a RUNNING attempt can be ended as cancelled")
        check(job.cancelRequested || isLegalTransition(JobState.RUNNING, JobState.CANCELLED)) {
            "the transition table must allow cancelling a running job"
        }
        updateIn(
            connection,
            "UPDATE jobs SET state = ?, stage = NULL, cancel_requested = 1, " +
                "updated_at = ? WHERE id = ? AND state = ?",
            JobState.CANCELLED.name,
            Instants.now(),
            id.value,
            JobState.RUNNING.name,
        )
        readIn(connection, id)
    }

    /**
     * Puts one running attempt back in the queue, keeping what it already committed.
     *
     * This is what a clean shutdown does: the process is going away, so the attempt is not a failure and
     * not a cancellation — it is work that will be picked up again. The counters are the checkpoint that
     * stops the next attempt redoing what this one finished, so they are kept; the stage, the current file
     * and the error described the attempt that ended and are cleared.
     */
    fun requeue(id: JobId): Job = database.transaction { connection ->
        requireRunning(connection, id, "only a RUNNING attempt can be queued again")
        requeueIn(connection, id)
    }

    /**
     * Returns interrupted work to the queue after a restart, and reports a cancellation request as a
     * cancellation.
     *
     * Idempotent by construction: only `RUNNING` jobs are examined, so a second sweep during the same
     * startup — or a later one — changes nothing and returns zero. Terminal jobs are never resurrected.
     */
    fun resetInterrupted(): Int = database.transaction { connection ->
        val interrupted = connection.prepareStatement("$SELECT_JOBS WHERE state = ?").use { statement ->
            statement.setString(1, JobState.RUNNING.name)
            statement.executeQuery().use { rows -> rows.readAll() }
        }
        interrupted.forEach { job ->
            if (job.cancelRequested) finishCancelledIn(connection, job.id) else requeueIn(connection, job.id)
        }
        interrupted.size
    }

    private fun claimIn(connection: Connection, id: JobId): Job? {
        val claimed = updateIn(
            connection,
            "UPDATE jobs SET state = ?, updated_at = ? WHERE id = ? AND state = ?",
            JobState.RUNNING.name,
            Instants.now(),
            id.value,
            JobState.QUEUED.name,
        )
        return if (claimed == 0) null else readIn(connection, id)
    }

    private fun requeueIn(connection: Connection, id: JobId): Job {
        updateIn(
            connection,
            "UPDATE jobs SET state = ?, stage = NULL, current_item = NULL, error_code = NULL, " +
                "error_message = NULL, updated_at = ? WHERE id = ? AND state = ?",
            JobState.QUEUED.name,
            Instants.now(),
            id.value,
            JobState.RUNNING.name,
        )
        return readIn(connection, id)
    }

    private fun finishCancelledIn(connection: Connection, id: JobId): Job {
        updateIn(
            connection,
            "UPDATE jobs SET state = ?, stage = NULL, cancel_requested = 1, " +
                "updated_at = ? WHERE id = ? AND state = ?",
            JobState.CANCELLED.name,
            Instants.now(),
            id.value,
            JobState.RUNNING.name,
        )
        return readIn(connection, id)
    }

    private fun requireRunning(connection: Connection, id: JobId, requirement: String): Job {
        val job = selectById(connection, id) ?: throw NoSuchJobException(id)
        if (job.state != JobState.RUNNING) throw JobStateException(id, job.state, requirement)
        return job
    }

    private fun updateIn(connection: Connection, sql: String, vararg parameters: Any?): Int =
        connection.prepareStatement(sql).use { statement ->
            parameters.forEachIndexed { index, value ->
                statement.setObject(index + 1, value)
            }
            statement.executeUpdate()
        }

    private fun readIn(connection: Connection, id: JobId): Job =
        selectById(connection, id) ?: throw NoSuchJobException(id)

    private fun selectById(connection: Connection, id: JobId): Job? =
        connection.prepareStatement("$SELECT_JOBS WHERE id = ?").use { statement ->
            statement.setString(1, id.value)
            statement.executeQuery().use { rows -> if (rows.next()) rows.toJob() else null }
        }

    private fun bind(statement: PreparedStatement, job: Job) {
        statement.setString(1, job.id.value)
        statement.setString(2, job.collectionId?.value)
        statement.setString(3, job.type.name)
        statement.setString(4, job.state.name)
        statement.setString(5, job.stage)
        statement.setString(6, job.currentItem)
        statement.setInt(7, job.completed)
        statement.setInt(8, job.total)
        statement.setString(9, job.payload)
        statement.setString(10, job.errorCode)
        statement.setString(11, job.errorMessage)
        statement.setInt(12, if (job.cancelRequested) 1 else 0)
        statement.setString(13, job.createdAt)
        statement.setString(14, job.updatedAt)
    }

    private fun ResultSet.readAll(): List<Job> = buildList { while (next()) add(toJob()) }

    private fun ResultSet.toJob(): Job {
        val state = JobState.valueOf(getString("state"))
        return Job(
            id = JobId(getString("id")),
            type = JobType.valueOf(getString("type")),
            state = state,
            collectionId = getString("collection_id")?.let(::CollectionId),
            stage = visibleStage(state, getString("stage")),
            currentItem = getString("current_item"),
            completed = getInt("completed"),
            total = getInt("total"),
            payload = getString("payload"),
            errorCode = getString("error_code"),
            errorMessage = getString("error_message"),
            cancelRequested = getInt("cancel_requested") == 1,
            createdAt = getString("created_at"),
            updatedAt = getString("updated_at"),
        )
    }

    private fun collectionExists(connection: Connection, id: CollectionId): Boolean =
        connection.prepareStatement("SELECT 1 FROM collections WHERE id = ?").use { statement ->
            statement.setString(1, id.value)
            statement.executeQuery().use { rows -> rows.next() }
        }

    companion object {

        /**
         * The states a job may move between: queued may run or be cancelled outright, a running attempt
         * may complete, fail, be cancelled, or be queued again after a shutdown, and nothing may leave a
         * terminal state. Every write path checks this before its `WHERE` clause, so an illegal
         * transition is refused in Kotlin and in SQL rather than only in one of them.
         */
        private val LEGAL_TRANSITIONS: Map<JobState, Set<JobState>> = mapOf(
            JobState.QUEUED to setOf(JobState.RUNNING, JobState.CANCELLED),
            JobState.RUNNING to setOf(JobState.COMPLETE, JobState.FAILED, JobState.CANCELLED, JobState.QUEUED),
            JobState.COMPLETE to emptySet(),
            JobState.FAILED to emptySet(),
            JobState.CANCELLED to emptySet(),
        )

        fun isLegalTransition(from: JobState, to: JobState): Boolean = to in LEGAL_TRANSITIONS.getValue(from)

        /**
         * The stage a job reports to a reader, given the stage its row holds. A finished job has no stage:
         * it described work that has stopped, and the old approval stage is retired by startup cleanup.
         */
        fun visibleStage(state: JobState, storedStage: String?): String? =
            if (state in TERMINAL_STATES) null else storedStage

        private val TERMINAL_STATES: Set<JobState> = setOf(JobState.COMPLETE, JobState.FAILED, JobState.CANCELLED)

        private const val SELECT_JOBS =
            "SELECT id, collection_id, type, state, stage, current_item, completed, total, payload, " +
                "error_code, error_message, cancel_requested, created_at, updated_at FROM jobs"

        private const val INSERT_JOB =
            "INSERT INTO jobs (id, collection_id, type, state, stage, current_item, completed, total, " +
                "payload, error_code, error_message, cancel_requested, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
    }
}
