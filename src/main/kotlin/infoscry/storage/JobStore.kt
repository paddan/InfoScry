package infoscry.storage

import infoscry.domain.CollectionId
import infoscry.domain.Job
import infoscry.domain.JobId
import infoscry.domain.JobState
import infoscry.domain.JobType
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet

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

    /** Records the stage an attempt is in and how far it has come. */
    fun progress(
        id: JobId,
        stage: String? = null,
        completed: Int? = null,
        total: Int? = null,
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
            "UPDATE jobs SET stage = ?, completed = ?, total = ?, updated_at = ? " +
                "WHERE id = ? AND state = ?",
            stage ?: job.stage,
            nextCompleted,
            nextTotal,
            Instants.now(),
            id.value,
            JobState.RUNNING.name,
        )
        readIn(connection, id)
    }

    fun complete(id: JobId): Job = database.transaction { connection ->
        requireRunning(connection, id, "only a RUNNING attempt can complete")
        moveIn(connection, id, JobState.RUNNING, JobState.COMPLETE)
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
            "UPDATE jobs SET state = ?, error_code = ?, error_message = ?, updated_at = ? " +
                "WHERE id = ? AND state = ?",
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
                    "UPDATE jobs SET state = ?, cancel_requested = 1, updated_at = ? " +
                        "WHERE id = ? AND state = ?",
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
            "UPDATE jobs SET state = ?, cancel_requested = 1, updated_at = ? WHERE id = ? AND state = ?",
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
     * stops the next attempt redoing what this one finished, so they are kept; the stage and the error
     * described the attempt that ended and are cleared.
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
            "UPDATE jobs SET state = ?, stage = NULL, error_code = NULL, error_message = NULL, " +
                "updated_at = ? WHERE id = ? AND state = ?",
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
            "UPDATE jobs SET state = ?, cancel_requested = 1, updated_at = ? WHERE id = ? AND state = ?",
            JobState.CANCELLED.name,
            Instants.now(),
            id.value,
            JobState.RUNNING.name,
        )
        return readIn(connection, id)
    }

    /** The state change a job is allowed to make. A `WHERE` that matches no row is the SQL half. */
    private fun moveIn(connection: Connection, id: JobId, from: JobState, to: JobState) {
        check(isLegalTransition(from, to)) { "the transition table must allow $from to $to" }
        updateIn(
            connection,
            "UPDATE jobs SET state = ?, updated_at = ? WHERE id = ? AND state = ?",
            to.name,
            Instants.now(),
            id.value,
            from.name,
        )
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
        statement.setInt(6, job.completed)
        statement.setInt(7, job.total)
        statement.setString(8, job.payload)
        statement.setString(9, job.errorCode)
        statement.setString(10, job.errorMessage)
        statement.setInt(11, if (job.cancelRequested) 1 else 0)
        statement.setString(12, job.createdAt)
        statement.setString(13, job.updatedAt)
    }

    private fun ResultSet.readAll(): List<Job> = buildList { while (next()) add(toJob()) }

    private fun ResultSet.toJob(): Job = Job(
        id = JobId(getString("id")),
        type = JobType.valueOf(getString("type")),
        state = JobState.valueOf(getString("state")),
        collectionId = getString("collection_id")?.let(::CollectionId),
        stage = getString("stage"),
        completed = getInt("completed"),
        total = getInt("total"),
        payload = getString("payload"),
        errorCode = getString("error_code"),
        errorMessage = getString("error_message"),
        cancelRequested = getInt("cancel_requested") == 1,
        createdAt = getString("created_at"),
        updatedAt = getString("updated_at"),
    )

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

        private const val SELECT_JOBS =
            "SELECT id, collection_id, type, state, stage, completed, total, payload, error_code, " +
                "error_message, cancel_requested, created_at, updated_at FROM jobs"

        private const val INSERT_JOB =
            "INSERT INTO jobs (id, collection_id, type, state, stage, completed, total, payload, " +
                "error_code, error_message, cancel_requested, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
    }
}
