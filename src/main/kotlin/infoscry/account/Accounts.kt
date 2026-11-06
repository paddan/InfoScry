package infoscry.account

import infoscry.domain.ApiFaultException
import infoscry.domain.ApiStatus
import java.util.UUID
import kotlinx.serialization.Serializable

@JvmInline
@Serializable
value class UserId(val value: String) {
    init {
        require(value.isNotBlank()) { "UserId must not be blank" }
    }

    companion object {
        fun new(): UserId = UserId(UUID.randomUUID().toString())
    }
}

/** The global role. Per-collection roles come with memberships. */
enum class UserRole { ADMIN, USER }

/** A person who signs in, as every caller may see them; the password hash never leaves the store. */
@Serializable
data class User(
    val id: UserId,
    val username: String,
    val displayName: String,
    val role: UserRole,
    val active: Boolean,
    val mustChangePassword: Boolean,
    val createdAt: String,
    val updatedAt: String,
    val lastSignInAt: String? = null,
)

/** A personal access token as its owner sees it later: everything except the value. */
@Serializable
data class AccessToken(
    val id: String,
    val name: String,
    val createdAt: String,
    val expiresAt: String? = null,
    val lastUsedAt: String? = null,
    val revokedAt: String? = null,
)

/** How a request proved who it is. */
enum class CredentialKind { SESSION, ACCESS_TOKEN }

/**
 * The authenticated caller of one request.
 *
 * [sessionId] is the cookie's value when the caller is a browser session; the CSRF token is derived from it.
 */
class Principal(val user: User, val kind: CredentialKind, internal val sessionId: String? = null) {
    val isAdmin: Boolean get() = user.role == UserRole.ADMIN
    override fun toString(): String = "Principal(user=${user.username}, kind=$kind)"
}

/** The username or password is wrong, or the account cannot sign in; which one is never said. */
class InvalidCredentialsException : ApiFaultException(
    ApiStatus.UNAUTHORIZED,
    "INVALID_CREDENTIALS",
    "the username or password is wrong",
)

/** Too many failed sign-ins for this username or address; the caller waits [retryAfterSeconds]. */
class SignInRateLimitedException(val retryAfterSeconds: Long) : ApiFaultException(
    ApiStatus.TOO_MANY_REQUESTS,
    "SIGN_IN_RATE_LIMITED",
    "too many failed sign-ins; try again in $retryAfterSeconds seconds",
) {
    override fun details(): Map<String, String> = mapOf("retryAfterSeconds" to retryAfterSeconds.toString())
}

/** The change would leave no active administrator. */
class LastAdministratorException : ApiFaultException(
    ApiStatus.CONFLICT,
    "LAST_ADMINISTRATOR",
    "the last active administrator cannot be deactivated or made a regular user; make another user an administrator first",
)

/** A password change named the wrong current password. */
class WrongCurrentPasswordException : ApiFaultException(
    ApiStatus.BAD_REQUEST,
    "WRONG_CURRENT_PASSWORD",
    "the current password is wrong",
)
