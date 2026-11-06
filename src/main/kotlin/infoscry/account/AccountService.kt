package infoscry.account

import infoscry.storage.AccountStore
import infoscry.storage.Instants
import java.time.Duration
import java.time.Instant
import java.util.UUID
import org.slf4j.LoggerFactory

/** How long a browser session lives. */
data class SessionSettings(
    val idle: Duration = Duration.ofHours(DEFAULT_IDLE_HOURS),
    val absolute: Duration = Duration.ofDays(DEFAULT_MAX_DAYS),
) {
    companion object {
        const val DEFAULT_IDLE_HOURS = 12L
        const val DEFAULT_MAX_DAYS = 14L

        /** `INFOSCRY_SESSION_IDLE_HOURS` and `INFOSCRY_SESSION_MAX_DAYS`, each a positive whole number. */
        fun fromEnvironment(environment: Map<String, String> = System.getenv()): SessionSettings {
            fun positive(name: String, default: Long): Long {
                val raw = environment[name]?.trim()?.takeIf { it.isNotEmpty() } ?: return default
                val value = raw.toLongOrNull()
                require(value != null && value > 0) { "$name must be a positive whole number, was '$raw'" }
                return value
            }
            return SessionSettings(
                idle = Duration.ofHours(positive("INFOSCRY_SESSION_IDLE_HOURS", DEFAULT_IDLE_HOURS)),
                absolute = Duration.ofDays(positive("INFOSCRY_SESSION_MAX_DAYS", DEFAULT_MAX_DAYS)),
            )
        }
    }
}

/** A user [AccountService.createUser] answered with; [oneTimePassword] only when it created the user. */
data class CreatedUser(val user: User, val created: Boolean, val oneTimePassword: String?) {
    override fun toString(): String = "CreatedUser(user=${user.username}, created=$created)"
}

/** A new browser session: the cookie value and the user it belongs to. */
class SignedIn(val user: User, val sessionId: String) {
    val csrfToken: String get() = AccountService.csrfTokenFor(sessionId)
    override fun toString(): String = "SignedIn(user=${user.username})"
}

/** A new personal access token; [value] is shown once and never again. */
class CreatedToken(val token: AccessToken, val value: String) {
    override fun toString(): String = "CreatedToken(id=${token.id}, name=${token.name})"
}

/**
 * Users, sign-in, browser sessions and personal access tokens.
 *
 * Every operation is safe to repeat: creating a user that exists answers the existing user, signing out a
 * session that is gone succeeds, revoking a revoked token succeeds, and deactivating an inactive user
 * changes nothing. Credentials (passwords, session IDs, tokens) never reach a log line or an exception
 * message.
 */
class AccountService(
    private val store: AccountStore,
    private val passwords: Passwords = Passwords(),
    private val sessions: SessionSettings = SessionSettings(),
    private val clock: () -> Instant = Instant::now,
    private val rateLimiter: SignInRateLimiter = SignInRateLimiter(clock = clock),
) {

    private fun now(): String = Instants.format(clock())

    /** How long a new session can live at most; the cookie lives as long. */
    val sessionLifetime: Duration get() = sessions.absolute

    // ---- administration ----

    /**
     * Creates [username] with a one-time password, or answers the existing user unchanged when the name is
     * taken (compared without case). [actor] is the administrator doing it, `null` for the admin command.
     */
    fun createUser(username: String, displayName: String?, role: UserRole, actor: UserId?): CreatedUser {
        val name = requireUsername(username)
        store.findByUsername(name)?.let { return CreatedUser(it.user, created = false, oneTimePassword = null) }
        val password = Secrets.readablePassword()
        val at = now()
        val user = User(
            id = UserId.new(),
            username = name,
            displayName = displayName?.trim()?.takeIf { it.isNotEmpty() } ?: name,
            role = role,
            active = true,
            mustChangePassword = true,
            createdAt = at,
            updatedAt = at,
        )
        try {
            store.insertUser(user, passwords.hash(password), actor)
        } catch (failure: Exception) {
            // Another request created the same name in between; answer as a repeat would.
            if (!infoscry.storage.Database.isUniqueViolation(failure)) throw failure
            return CreatedUser(checkNotNull(store.findByUsername(name)).user, created = false, oneTimePassword = null)
        }
        LOGGER.atInfo().addKeyValue("username", user.username).addKeyValue("role", role.name).log("user created")
        return CreatedUser(user, created = true, oneTimePassword = password)
    }

    fun listUsers(): List<User> = store.list()

    fun user(id: UserId): User = store.get(id)?.user ?: throw NoSuchElementException("no user with id ${id.value}")

    fun findByUsername(username: String): User? = store.findByUsername(username.trim())?.user

    /**
     * Changes a user's display name, role or active flag; a field left `null` keeps its value. Deactivating
     * ends the user's sessions and revokes their tokens in the same transaction. Leaving no active
     * administrator is refused.
     */
    fun updateUser(id: UserId, displayName: String?, role: UserRole?, active: Boolean?, actor: UserId?): User =
        store.transaction {
            val current = store.get(id, forUpdate = true)?.user ?: throw NoSuchElementException("no user with id ${id.value}")
            val newName = displayName?.let(::requireDisplayName) ?: current.displayName
            val newRole = role ?: current.role
            val newActive = active ?: current.active
            if (newName == current.displayName && newRole == current.role && newActive == current.active) return@transaction current
            val losesAdministrator = current.role == UserRole.ADMIN && current.active &&
                (newRole != UserRole.ADMIN || !newActive)
            if (losesAdministrator && store.lockActiveAdministrators().none { it != id }) throw LastAdministratorException()
            val at = now()
            store.updateProfile(id, newName, newRole, newActive, at, actor)
            if (current.active && !newActive) {
                store.deleteSessionsOf(id)
                store.revokeTokensOf(id, at)
            }
            LOGGER.atInfo().addKeyValue("username", current.username).addKeyValue("role", newRole.name)
                .addKeyValue("active", newActive).log("user updated")
            current.copy(displayName = newName, role = newRole, active = newActive, updatedAt = at)
        }

    /** Gives [id] a new one-time password and ends their sessions; the old password stops working at once. */
    fun resetPassword(id: UserId, actor: UserId?): String = store.transaction {
        val current = store.get(id, forUpdate = true)?.user ?: throw NoSuchElementException("no user with id ${id.value}")
        val password = Secrets.readablePassword()
        store.updatePassword(id, passwords.hash(password), mustChange = true, updatedAt = now(), updatedBy = actor)
        store.deleteSessionsOf(id)
        LOGGER.atInfo().addKeyValue("username", current.username).log("password reset")
        password
    }

    // ---- the signed-in user ----

    fun updateOwnDisplayName(id: UserId, displayName: String): User = updateUser(id, displayName, null, null, actor = id)

    /** Replaces the caller's password; their other sessions end, the one that asked stays signed in. */
    fun changeOwnPassword(principal: Principal, currentPassword: String, newPassword: String) {
        Passwords.requireAcceptable(newPassword)
        val stored = store.get(principal.user.id) ?: throw NoSuchElementException("no such user")
        if (!passwords.verify(currentPassword, stored.passwordHash)) throw WrongCurrentPasswordException()
        store.transaction {
            store.updatePassword(stored.user.id, passwords.hash(newPassword), mustChange = false, updatedAt = now(), updatedBy = stored.user.id)
            store.deleteSessionsOf(stored.user.id, keepIdHash = principal.sessionId?.let(Secrets::sha256))
        }
    }

    // ---- sign-in and sessions ----

    fun signIn(username: String, password: String, clientAddress: String, userAgent: String?): SignedIn {
        val name = username.trim()
        rateLimiter.check(name, clientAddress)
        val stored = store.findByUsername(name)
        val accepted = if (stored == null) {
            passwords.verifyNothing(password)
            false
        } else {
            passwords.verify(password, stored.passwordHash) && stored.user.active
        }
        if (!accepted || stored == null) {
            rateLimiter.recordFailure(name, clientAddress)
            LOGGER.atInfo().addKeyValue("username", name).log("sign-in refused")
            throw InvalidCredentialsException()
        }
        rateLimiter.recordSuccess(name)
        val sessionId = Secrets.randomToken()
        val instant = clock()
        val at = Instants.format(instant)
        store.insertSession(Secrets.sha256(sessionId), stored.user.id, at, Instants.format(instant.plus(sessions.absolute)), userAgent)
        store.recordSignIn(stored.user.id, at)
        LOGGER.atInfo().addKeyValue("username", stored.user.username).log("signed in")
        return SignedIn(stored.user.copy(lastSignInAt = at), sessionId)
    }

    /** The session [sessionId] names, or `null` when it is unknown, expired, idle too long or its user is inactive. */
    fun authenticateSession(sessionId: String): Principal? {
        val idHash = Secrets.sha256(sessionId)
        val session = store.findSession(idHash) ?: return null
        val instant = clock()
        val now = Instants.format(instant)
        val idleSince = Instants.format(instant.minus(sessions.idle))
        if (session.expiresAt <= now || session.lastSeenAt <= idleSince || !session.user.user.active) {
            store.deleteSession(idHash)
            return null
        }
        // Writing on every request would turn each read into a write; a minute's precision is enough for an
        // idle limit measured in hours.
        if (session.lastSeenAt <= Instants.format(instant.minus(TOUCH_INTERVAL))) store.touchSession(idHash, now)
        return Principal(session.user.user, CredentialKind.SESSION, sessionId)
    }

    /** Ends the session [sessionId] names; ending one that is gone succeeds. */
    fun signOut(sessionId: String) {
        store.deleteSession(Secrets.sha256(sessionId))
    }

    /** Removes sessions nobody can use any more, so the table does not grow without bound. */
    fun deleteExpiredSessions(): Int {
        val instant = clock()
        return store.deleteExpiredSessions(Instants.format(instant), Instants.format(instant.minus(sessions.idle)))
    }

    // ---- personal access tokens ----

    fun createToken(userId: UserId, name: String, expiresInDays: Int?): CreatedToken {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "a token needs a name" }
        require(trimmed.length <= NAME_LIMIT) { "a token name may have at most $NAME_LIMIT characters" }
        require(expiresInDays == null || expiresInDays in 1..MAX_TOKEN_DAYS) {
            "a token expires after 1 to $MAX_TOKEN_DAYS days, or never"
        }
        val instant = clock()
        val value = TOKEN_PREFIX + Secrets.randomToken()
        val token = AccessToken(
            id = UUID.randomUUID().toString(),
            name = trimmed,
            createdAt = Instants.format(instant),
            expiresAt = expiresInDays?.let { Instants.format(instant.plus(Duration.ofDays(it.toLong()))) },
        )
        store.insertToken(userId, token, Secrets.sha256(value))
        return CreatedToken(token, value)
    }

    fun listTokens(userId: UserId): List<AccessToken> = store.listTokens(userId)

    /** Revokes one of [userId]'s tokens; revoking a revoked one succeeds, someone else's is not found. */
    fun revokeToken(userId: UserId, tokenId: String) {
        if (!store.revokeToken(userId, tokenId, now())) throw NoSuchElementException("no access token with id $tokenId")
    }

    /** The user a bearer [value] belongs to, or `null` when it is unknown, revoked, expired or its user inactive. */
    fun authenticateToken(value: String): Principal? {
        if (!value.startsWith(TOKEN_PREFIX)) return null
        val stored = store.findToken(Secrets.sha256(value)) ?: return null
        val instant = clock()
        val now = Instants.format(instant)
        val token = stored.token
        if (token.revokedAt != null || (token.expiresAt != null && token.expiresAt <= now) || !stored.user.user.active) return null
        if (token.lastUsedAt == null || token.lastUsedAt <= Instants.format(instant.minus(TOUCH_INTERVAL))) store.touchToken(token.id, now)
        return Principal(stored.user.user, CredentialKind.ACCESS_TOKEN)
    }

    private fun requireUsername(username: String): String {
        val name = username.trim()
        require(name.isNotEmpty()) { "a username must not be empty" }
        require(name.length <= NAME_LIMIT) { "a username may have at most $NAME_LIMIT characters" }
        require(name.none { it.isWhitespace() || it.isISOControl() }) { "a username may not contain spaces" }
        return name
    }

    private fun requireDisplayName(displayName: String): String {
        val name = displayName.trim()
        require(name.isNotEmpty()) { "a display name must not be empty" }
        require(name.length <= NAME_LIMIT) { "a display name may have at most $NAME_LIMIT characters" }
        return name
    }

    companion object {
        const val TOKEN_PREFIX = "isc_"
        const val MAX_TOKEN_DAYS = 3_650
        private const val NAME_LIMIT = 200
        private val TOUCH_INTERVAL: Duration = Duration.ofMinutes(1)
        private const val CSRF_LABEL = "infoscry-csrf\u0000"

        /** The CSRF token of a session: derived from its ID, so it needs no storage and is useless without the cookie. */
        fun csrfTokenFor(sessionId: String): String = Secrets.sha256(CSRF_LABEL + sessionId)

        private val LOGGER = LoggerFactory.getLogger("infoscry.accounts")
    }
}
