package infoscry.storage

import infoscry.account.CollectionRole
import infoscry.account.UserId
import infoscry.domain.CollectionId
import java.sql.Connection

/** One membership row with the member's names, as a collection's members list shows it. */
data class MembershipRow(
    val userId: UserId,
    val username: String,
    val displayName: String,
    val active: Boolean,
    val role: CollectionRole,
    val addedAt: String,
    val addedBy: UserId,
)

/** Persistence for `collection_members`. Rules (the last owner, idempotence) live in the membership service. */
class MembershipStore(private val database: Database) {

    fun <T> transaction(block: () -> T): T = database.transaction { block() }

    fun roleOf(collectionId: CollectionId, userId: UserId): CollectionRole? = database.read { connection ->
        connection.prepareStatement("SELECT role FROM collection_members WHERE collection_id = ? AND user_id = ?").use { statement ->
            statement.setString(1, collectionId.value)
            statement.setString(2, userId.value)
            statement.executeQuery().use { rows -> if (rows.next()) CollectionRole.valueOf(rows.getString(1)) else null }
        }
    }

    /** Every collection [userId] is a member of, with their role there. */
    fun rolesOf(userId: UserId): Map<CollectionId, CollectionRole> = database.read { connection ->
        connection.prepareStatement("SELECT collection_id, role FROM collection_members WHERE user_id = ?").use { statement ->
            statement.setString(1, userId.value)
            statement.executeQuery().use { rows ->
                buildMap { while (rows.next()) put(CollectionId(rows.getString(1)), CollectionRole.valueOf(rows.getString(2))) }
            }
        }
    }

    fun list(collectionId: CollectionId): List<MembershipRow> = database.read { connection ->
        connection.prepareStatement(
            "SELECT m.user_id, u.username, u.display_name, u.active, m.role, m.added_at, m.added_by " +
                "FROM collection_members m JOIN users u ON u.id = m.user_id " +
                "WHERE m.collection_id = ? ORDER BY u.display_name COLLATE infoscry_nocase, u.username",
        ).use { statement ->
            statement.setString(1, collectionId.value)
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        add(
                            MembershipRow(
                                userId = UserId(rows.getString(1)),
                                username = rows.getString(2),
                                displayName = rows.getString(3),
                                active = rows.getInt(4) == 1,
                                role = CollectionRole.valueOf(rows.getString(5)),
                                addedAt = rows.getString(6),
                                addedBy = UserId(rows.getString(7)),
                            ),
                        )
                    }
                }
            }
        }
    }

    /** The owners of [collectionId], locked until the surrounding transaction ends. */
    fun lockOwners(collectionId: CollectionId): List<UserId> = database.read { connection ->
        connection.prepareStatement(
            "SELECT user_id FROM collection_members WHERE collection_id = ? AND role = 'OWNER' ORDER BY user_id FOR UPDATE",
        ).use { statement ->
            statement.setString(1, collectionId.value)
            statement.executeQuery().use { rows -> buildList { while (rows.next()) add(UserId(rows.getString(1))) } }
        }
    }

    /** Adds [userId] or changes their role; `added_at` and `added_by` keep the first addition's values. */
    fun put(collectionId: CollectionId, userId: UserId, role: CollectionRole, at: String, by: UserId) {
        database.transaction { connection -> insert(connection, collectionId, userId, role, at, by) }
    }

    internal fun insert(connection: Connection, collectionId: CollectionId, userId: UserId, role: CollectionRole, at: String, by: UserId) {
        connection.prepareStatement(
            "INSERT INTO collection_members (collection_id, user_id, role, added_at, added_by) VALUES (?, ?, ?, ?, ?) " +
                "ON CONFLICT (collection_id, user_id) DO UPDATE SET role = excluded.role",
        ).use { statement ->
            statement.setString(1, collectionId.value)
            statement.setString(2, userId.value)
            statement.setString(3, role.name)
            statement.setString(4, at)
            statement.setString(5, by.value)
            statement.executeUpdate()
        }
    }

    fun remove(collectionId: CollectionId, userId: UserId): Boolean = database.transaction { connection ->
        connection.prepareStatement("DELETE FROM collection_members WHERE collection_id = ? AND user_id = ?").use { statement ->
            statement.setString(1, collectionId.value)
            statement.setString(2, userId.value)
            statement.executeUpdate() > 0
        }
    }
}
