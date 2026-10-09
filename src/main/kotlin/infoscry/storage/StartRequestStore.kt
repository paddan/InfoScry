package infoscry.storage

import infoscry.domain.JobId
import infoscry.domain.JobState
import java.sql.Connection

/** A client request id was already used for a different start or retry body. */
class StartRequestConflictException(requestId: String) : IllegalStateException(
    "request id '$requestId' was already used with a different request body",
)

/** Durable idempotency records shared by import starts and retries. */
class StartRequestStore(private val database: Database) {
    init {
        database.transaction { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    "CREATE TABLE IF NOT EXISTS start_requests (" +
                        "request_id TEXT PRIMARY KEY, kind TEXT NOT NULL, body_hash TEXT NOT NULL, " +
                        "job_id TEXT NOT NULL, created_at TEXT NOT NULL)",
                )
            }
        }
    }

    /** Returns the job currently mapped to this request without inspecting its state. */
    fun lookup(requestId: String, kind: String, bodyHash: String): JobId? {
        require(requestId.isNotBlank()) { "a start request needs a request id" }
        require(kind.isNotBlank()) { "a start request needs a kind" }
        require(bodyHash.isNotBlank()) { "a start request needs a body hash" }
        return database.read { connection -> find(connection, requestId, kind, bodyHash) }
    }

    /**
     * Atomically creates the job and remembers it, or returns the job a concurrent call already created.
     * When opted in by CLI import, a failed or cancelled prior job is replaced under the same request id.
     */
    fun claim(
        requestId: String,
        kind: String,
        bodyHash: String,
        create: () -> JobId,
    ): JobId = claim(requestId, kind, bodyHash, restartStopped = false, create = create)

    /** Variant that can replace a stopped import when the caller explicitly opts in. */
    fun claim(
        requestId: String,
        kind: String,
        bodyHash: String,
        restartStopped: Boolean,
        create: () -> JobId,
    ): JobId {
        require(requestId.isNotBlank()) { "a start request needs a request id" }
        require(kind.isNotBlank()) { "a start request needs a kind" }
        require(bodyHash.isNotBlank()) { "a start request needs a body hash" }
        require(!restartStopped || kind == "import") { "only imports can restart a stopped request" }
        return database.transaction { connection ->
            val previous = findRequest(connection, requestId, kind, bodyHash)
            if (previous != null) {
                if (!restartStopped || !isStoppedImport(connection, previous)) {
                    return@transaction previous
                }
                val replacement = create()
                connection.prepareStatement(
                    "UPDATE start_requests SET job_id = ?, created_at = ? WHERE request_id = ?",
                ).use { statement ->
                    statement.setString(1, replacement.value)
                    statement.setString(2, Instants.now())
                    statement.setString(3, requestId)
                    check(statement.executeUpdate() == 1) { "the import start request disappeared during replacement" }
                }
                return@transaction replacement
            }
            val jobId = create()
            connection.prepareStatement(
                "INSERT INTO start_requests (request_id, kind, body_hash, job_id, created_at) VALUES (?, ?, ?, ?, ?)",
            ).use { statement ->
                statement.setString(1, requestId)
                statement.setString(2, kind)
                statement.setString(3, bodyHash)
                statement.setString(4, jobId.value)
                statement.setString(5, Instants.now())
                statement.executeUpdate()
            }
            jobId
        }
    }

    private fun find(connection: Connection, requestId: String, kind: String, bodyHash: String): JobId? =
        findRequest(connection, requestId, kind, bodyHash)

    private fun findRequest(connection: Connection, requestId: String, kind: String, bodyHash: String): JobId? =
        connection.prepareStatement("SELECT kind, body_hash, job_id FROM start_requests WHERE request_id = ?").use { statement ->
            statement.setString(1, requestId)
            statement.executeQuery().use { rows ->
                if (!rows.next()) return null
                if (rows.getString("kind") != kind || rows.getString("body_hash") != bodyHash) {
                    throw StartRequestConflictException(requestId)
                }
                JobId(rows.getString("job_id"))
            }
        }

    private fun isStoppedImport(connection: Connection, jobId: JobId): Boolean =
        connection.prepareStatement("SELECT state FROM jobs WHERE id = ? AND type = 'IMPORT'").use { statement ->
            statement.setString(1, jobId.value)
            statement.executeQuery().use { rows ->
                if (!rows.next()) return false
                JobState.valueOf(rows.getString("state")) in setOf(JobState.FAILED, JobState.CANCELLED)
            }
        }
}
