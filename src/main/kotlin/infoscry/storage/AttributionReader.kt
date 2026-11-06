package infoscry.storage

import infoscry.account.UserId
import infoscry.domain.DocumentId
import infoscry.domain.UserRef
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Names for attribution: who a user id is, as colleagues see them.
 *
 * A name is cached for a minute, because every job row and document detail asks and a display name changes
 * rarely; a rename shows within the minute.
 */
class AttributionReader(private val database: Database, private val clock: () -> Instant = Instant::now) {

    private val cache = ConcurrentHashMap<String, Pair<UserRef, Instant>>()

    fun userRef(id: UserId?): UserRef? {
        if (id == null) return null
        val now = clock()
        cache[id.value]?.let { (ref, at) -> if (Duration.between(at, now) < TTL) return ref }
        val name = database.read { connection ->
            connection.prepareStatement("SELECT display_name FROM users WHERE id = ?").use { statement ->
                statement.setString(1, id.value)
                statement.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
            }
        } ?: return null
        return UserRef(id.value, name).also { cache[id.value] = it to now }
    }

    /** Who imported [documentId]: whoever started the job that read it in. */
    fun importedBy(documentId: DocumentId): UserRef? = database.read { connection ->
        connection.prepareStatement("SELECT imported_by FROM documents WHERE id = ?").use { statement ->
            statement.setString(1, documentId.value)
            statement.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
        }
    }?.let { userRef(UserId(it)) }

    private companion object {
        val TTL: Duration = Duration.ofMinutes(1)
    }
}
