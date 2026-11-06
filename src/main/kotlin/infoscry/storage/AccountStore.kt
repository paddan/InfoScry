package infoscry.storage

import infoscry.account.AccessToken
import infoscry.account.User
import infoscry.account.UserId
import infoscry.account.UserRole
import java.sql.ResultSet

/** A user row with its password hash, which only the account service reads. */
class StoredUser(val user: User, val passwordHash: String)

/** A session row joined with its user. */
class StoredSession(val user: StoredUser, val lastSeenAt: String, val expiresAt: String)

/** An access-token row joined with its user. */
class StoredToken(val user: StoredUser, val token: AccessToken)

/**
 * Persistence for users, browser sessions and personal access tokens. Instants are written by the caller
 * (the account service owns the clock), so expiry can be tested without waiting.
 */
class AccountStore(private val database: Database) {

    fun <T> transaction(block: () -> T): T = database.transaction { block() }

    fun insertUser(user: User, passwordHash: String, createdBy: UserId?) {
        database.transaction { connection ->
            connection.prepareStatement(
                "INSERT INTO users (id, username, display_name, password_hash, role, active, must_change_password, " +
                    "created_at, created_by, updated_at, updated_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            ).use { statement ->
                statement.setString(1, user.id.value)
                statement.setString(2, user.username)
                statement.setString(3, user.displayName)
                statement.setString(4, passwordHash)
                statement.setString(5, user.role.name)
                statement.setInt(6, if (user.active) 1 else 0)
                statement.setInt(7, if (user.mustChangePassword) 1 else 0)
                statement.setString(8, user.createdAt)
                statement.setString(9, createdBy?.value)
                statement.setString(10, user.updatedAt)
                statement.setString(11, createdBy?.value)
                statement.executeUpdate()
            }
        }
    }

    fun findByUsername(username: String): StoredUser? = database.read { connection ->
        connection.prepareStatement("$SELECT_USERS WHERE username = ? COLLATE infoscry_nocase").use { statement ->
            statement.setString(1, username)
            statement.executeQuery().use { rows -> if (rows.next()) rows.toStoredUser() else null }
        }
    }

    fun get(id: UserId, forUpdate: Boolean = false): StoredUser? = database.read { connection ->
        connection.prepareStatement("$SELECT_USERS WHERE id = ?${if (forUpdate) " FOR UPDATE" else ""}").use { statement ->
            statement.setString(1, id.value)
            statement.executeQuery().use { rows -> if (rows.next()) rows.toStoredUser() else null }
        }
    }

    fun list(): List<User> = database.read { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("$SELECT_USERS ORDER BY username COLLATE infoscry_nocase").use { rows ->
                buildList { while (rows.next()) add(rows.toStoredUser().user) }
            }
        }
    }

    fun countUsers(): Int = database.read { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT count(*) FROM users").use { rows -> rows.next(); rows.getInt(1) }
        }
    }

    /** The IDs of every active administrator, locked until the surrounding transaction ends. */
    fun lockActiveAdministrators(): List<UserId> = database.read { connection ->
        connection.prepareStatement("SELECT id FROM users WHERE role = 'ADMIN' AND active = 1 ORDER BY id FOR UPDATE").use { statement ->
            statement.executeQuery().use { rows -> buildList { while (rows.next()) add(UserId(rows.getString(1))) } }
        }
    }

    fun updateProfile(id: UserId, displayName: String, role: UserRole, active: Boolean, updatedAt: String, updatedBy: UserId?) {
        database.transaction { connection ->
            connection.prepareStatement(
                "UPDATE users SET display_name = ?, role = ?, active = ?, updated_at = ?, updated_by = ? WHERE id = ?",
            ).use { statement ->
                statement.setString(1, displayName)
                statement.setString(2, role.name)
                statement.setInt(3, if (active) 1 else 0)
                statement.setString(4, updatedAt)
                statement.setString(5, updatedBy?.value)
                statement.setString(6, id.value)
                statement.executeUpdate()
            }
        }
    }

    fun updatePassword(id: UserId, passwordHash: String, mustChange: Boolean, updatedAt: String, updatedBy: UserId?) {
        database.transaction { connection ->
            connection.prepareStatement(
                "UPDATE users SET password_hash = ?, must_change_password = ?, updated_at = ?, updated_by = ? WHERE id = ?",
            ).use { statement ->
                statement.setString(1, passwordHash)
                statement.setInt(2, if (mustChange) 1 else 0)
                statement.setString(3, updatedAt)
                statement.setString(4, updatedBy?.value)
                statement.setString(5, id.value)
                statement.executeUpdate()
            }
        }
    }

    fun recordSignIn(id: UserId, at: String) {
        database.read { connection ->
            connection.prepareStatement("UPDATE users SET last_sign_in_at = ? WHERE id = ?").use { statement ->
                statement.setString(1, at)
                statement.setString(2, id.value)
                statement.executeUpdate()
            }
        }
    }

    // ---- sessions ----

    fun insertSession(idHash: String, userId: UserId, createdAt: String, expiresAt: String, userAgent: String?) {
        database.read { connection ->
            connection.prepareStatement(
                "INSERT INTO sessions (id_hash, user_id, created_at, last_seen_at, expires_at, user_agent) VALUES (?, ?, ?, ?, ?, ?)",
            ).use { statement ->
                statement.setString(1, idHash)
                statement.setString(2, userId.value)
                statement.setString(3, createdAt)
                statement.setString(4, createdAt)
                statement.setString(5, expiresAt)
                statement.setString(6, userAgent?.take(USER_AGENT_LIMIT))
                statement.executeUpdate()
            }
        }
    }

    fun findSession(idHash: String): StoredSession? = database.read { connection ->
        connection.prepareStatement(
            "SELECT ${USER_COLUMNS.joinToString { "u.$it" }}, s.last_seen_at, s.expires_at " +
                "FROM sessions s JOIN users u ON u.id = s.user_id WHERE s.id_hash = ?",
        ).use { statement ->
            statement.setString(1, idHash)
            statement.executeQuery().use { rows ->
                if (rows.next()) StoredSession(rows.toStoredUser(), rows.getString("last_seen_at"), rows.getString("expires_at")) else null
            }
        }
    }

    fun touchSession(idHash: String, at: String) {
        database.read { connection ->
            connection.prepareStatement("UPDATE sessions SET last_seen_at = ? WHERE id_hash = ?").use { statement ->
                statement.setString(1, at)
                statement.setString(2, idHash)
                statement.executeUpdate()
            }
        }
    }

    fun deleteSession(idHash: String) {
        database.read { connection ->
            connection.prepareStatement("DELETE FROM sessions WHERE id_hash = ?").use { statement ->
                statement.setString(1, idHash)
                statement.executeUpdate()
            }
        }
    }

    /** Ends every session of [userId] except [keepIdHash], if one is named. */
    fun deleteSessionsOf(userId: UserId, keepIdHash: String? = null) {
        database.transaction { connection ->
            connection.prepareStatement("DELETE FROM sessions WHERE user_id = ? AND id_hash IS DISTINCT FROM ?").use { statement ->
                statement.setString(1, userId.value)
                statement.setString(2, keepIdHash)
                statement.executeUpdate()
            }
        }
    }

    /** Removes sessions past their absolute expiry or idle since before [idleBefore]. */
    fun deleteExpiredSessions(now: String, idleBefore: String): Int = database.read { connection ->
        connection.prepareStatement("DELETE FROM sessions WHERE expires_at <= ? OR last_seen_at <= ?").use { statement ->
            statement.setString(1, now)
            statement.setString(2, idleBefore)
            statement.executeUpdate()
        }
    }

    // ---- access tokens ----

    fun insertToken(userId: UserId, token: AccessToken, tokenHash: String) {
        database.transaction { connection ->
            connection.prepareStatement(
                "INSERT INTO access_tokens (id, user_id, name, token_hash, created_at, expires_at) VALUES (?, ?, ?, ?, ?, ?)",
            ).use { statement ->
                statement.setString(1, token.id)
                statement.setString(2, userId.value)
                statement.setString(3, token.name)
                statement.setString(4, tokenHash)
                statement.setString(5, token.createdAt)
                statement.setString(6, token.expiresAt)
                statement.executeUpdate()
            }
        }
    }

    fun findToken(tokenHash: String): StoredToken? = database.read { connection ->
        connection.prepareStatement(
            "SELECT ${USER_COLUMNS.joinToString { "u.$it" }}, ${TOKEN_COLUMNS.joinToString { "t.$it AS token_$it" }} " +
                "FROM access_tokens t JOIN users u ON u.id = t.user_id WHERE t.token_hash = ?",
        ).use { statement ->
            statement.setString(1, tokenHash)
            statement.executeQuery().use { rows ->
                if (rows.next()) StoredToken(rows.toStoredUser(), rows.toToken(prefix = "token_")) else null
            }
        }
    }

    fun listTokens(userId: UserId): List<AccessToken> = database.read { connection ->
        connection.prepareStatement(
            "SELECT ${TOKEN_COLUMNS.joinToString()} FROM access_tokens WHERE user_id = ? ORDER BY created_at DESC, id",
        ).use { statement ->
            statement.setString(1, userId.value)
            statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.toToken(prefix = "")) } }
        }
    }

    fun touchToken(id: String, at: String) {
        database.read { connection ->
            connection.prepareStatement("UPDATE access_tokens SET last_used_at = ? WHERE id = ?").use { statement ->
                statement.setString(1, at)
                statement.setString(2, id)
                statement.executeUpdate()
            }
        }
    }

    /** Revokes token [id] of [userId] if it is not revoked yet; whether such a token exists at all. */
    fun revokeToken(userId: UserId, id: String, at: String): Boolean = database.transaction { connection ->
        connection.prepareStatement(
            "UPDATE access_tokens SET revoked_at = coalesce(revoked_at, ?) WHERE id = ? AND user_id = ?",
        ).use { statement ->
            statement.setString(1, at)
            statement.setString(2, id)
            statement.setString(3, userId.value)
            statement.executeUpdate() > 0
        }
    }

    fun revokeTokensOf(userId: UserId, at: String) {
        database.transaction { connection ->
            connection.prepareStatement(
                "UPDATE access_tokens SET revoked_at = ? WHERE user_id = ? AND revoked_at IS NULL",
            ).use { statement ->
                statement.setString(1, at)
                statement.setString(2, userId.value)
                statement.executeUpdate()
            }
        }
    }

    private fun ResultSet.toStoredUser(): StoredUser = StoredUser(
        User(
            id = UserId(getString("id")),
            username = getString("username"),
            displayName = getString("display_name"),
            role = UserRole.valueOf(getString("role")),
            active = getInt("active") == 1,
            mustChangePassword = getInt("must_change_password") == 1,
            createdAt = getString("created_at"),
            updatedAt = getString("updated_at"),
            lastSignInAt = getString("last_sign_in_at"),
        ),
        getString("password_hash"),
    )

    private fun ResultSet.toToken(prefix: String): AccessToken = AccessToken(
        id = getString("${prefix}id"),
        name = getString("${prefix}name"),
        createdAt = getString("${prefix}created_at"),
        expiresAt = getString("${prefix}expires_at"),
        lastUsedAt = getString("${prefix}last_used_at"),
        revokedAt = getString("${prefix}revoked_at"),
    )

    private companion object {
        val USER_COLUMNS = listOf(
            "id", "username", "display_name", "password_hash", "role", "active", "must_change_password",
            "created_at", "updated_at", "last_sign_in_at",
        )
        val TOKEN_COLUMNS = listOf("id", "name", "created_at", "expires_at", "last_used_at", "revoked_at")
        val SELECT_USERS = "SELECT ${USER_COLUMNS.joinToString()} FROM users"
        const val USER_AGENT_LIMIT = 512
    }
}
