package infoscry.server

import infoscry.account.AccessToken
import infoscry.account.CredentialKind
import infoscry.account.User
import infoscry.account.UserId
import infoscry.account.UserRole
import infoscry.domain.ApiFaultException
import infoscry.domain.ApiStatus
import io.ktor.http.Cookie
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

@Serializable
data class SessionResponse(val product: String, val user: User? = null, val csrfToken: String? = null)

@Serializable
data class SignInRequest(val username: String, val password: String) {
    override fun toString(): String = "SignInRequest(username=$username)"
}

@Serializable
data class SignedInResponse(val user: User, val csrfToken: String)

@Serializable
data class UserResponse(val user: User)

@Serializable
data class UsersResponse(val users: List<User>)

@Serializable
data class UpdateMeRequest(val displayName: String)

@Serializable
data class ChangePasswordRequest(val currentPassword: String, val newPassword: String) {
    override fun toString(): String = "ChangePasswordRequest"
}

@Serializable
data class CreateTokenRequest(val name: String, val expiresInDays: Int? = null)

/** The answer to creating a token: the only time its [value] is ever shown. */
@Serializable
data class CreatedTokenResponse(val token: AccessToken, val value: String) {
    override fun toString(): String = "CreatedTokenResponse(id=${token.id})"
}

@Serializable
data class TokensResponse(val tokens: List<AccessToken>)

@Serializable
data class CreateUserRequest(val username: String, val displayName: String? = null, val role: UserRole = UserRole.USER)

/** [oneTimePassword] is present only when the user was created by this request. */
@Serializable
data class CreatedUserResponse(val user: User, val created: Boolean, val oneTimePassword: String? = null) {
    override fun toString(): String = "CreatedUserResponse(user=${user.username}, created=$created)"
}

@Serializable
data class UpdateUserRequest(val displayName: String? = null, val role: UserRole? = null, val active: Boolean? = null)

@Serializable
data class PasswordResetResponse(val oneTimePassword: String) {
    override fun toString(): String = "PasswordResetResponse"
}

/** Only an administrator may do this. */
class AdministratorRequiredException : ApiFaultException(
    ApiStatus.FORBIDDEN,
    "ADMINISTRATOR_REQUIRED",
    "only an administrator can do this",
)

/** Tokens are managed from a signed-in browser, so a leaked token cannot mint more. */
class BrowserSessionRequiredException : ApiFaultException(
    ApiStatus.FORBIDDEN,
    "BROWSER_SESSION_REQUIRED",
    "access tokens are created and revoked from a signed-in browser",
)

/**
 * Sign-in, the signed-in user's own account, and user administration.
 *
 * The request guard has already authenticated every route here except the public three, so a handler reads
 * [principal] and decides only what its role allows.
 */
fun Routing.configureAccountRoutes(accounts: infoscry.account.AccountService, settings: ServerSettings) {

    get("/api/session") {
        val caller = call.principalOrNull
        call.respondJson(
            HttpStatusCode.OK,
            SessionResponse(
                product = PRODUCT_NAME,
                user = caller?.user,
                csrfToken = caller?.takeIf { it.kind == CredentialKind.SESSION }?.sessionId?.let(infoscry.account.AccountService::csrfTokenFor),
            ),
        )
    }

    post("/api/auth/sign-in") {
        call.handle {
            val request = call.receiveJson<SignInRequest>()
            val signedIn = try {
                accounts.signIn(
                    request.username,
                    request.password,
                    clientAddress = call.request.local.remoteAddress,
                    userAgent = call.request.headers[HttpHeaders.UserAgent],
                )
            } catch (limited: infoscry.account.SignInRateLimitedException) {
                call.response.headers.append(HttpHeaders.RetryAfter, limited.retryAfterSeconds.toString())
                throw limited
            }
            // A sign-in in a browser that already had a session replaces it.
            call.request.cookies[SESSION_COOKIE]?.takeIf { it.isNotBlank() }?.let(accounts::signOut)
            runCatching { accounts.deleteExpiredSessions() }
            call.response.cookies.append(sessionCookie(signedIn.sessionId, settings, accounts.sessionLifetime.seconds.toInt()))
            call.respondJson(HttpStatusCode.OK, SignedInResponse(signedIn.user, signedIn.csrfToken))
        }
    }

    post("/api/auth/sign-out") {
        call.handle {
            call.request.cookies[SESSION_COOKIE]?.takeIf { it.isNotBlank() }?.let(accounts::signOut)
            call.response.cookies.append(sessionCookie("", settings, maxAgeSeconds = 0))
            call.respond(HttpStatusCode.NoContent)
        }
    }

    route("/api/me") {
        get {
            call.handle { call.respondJson(HttpStatusCode.OK, UserResponse(accounts.user(call.principal.user.id))) }
        }
        patch {
            call.handle {
                val request = call.receiveJson<UpdateMeRequest>()
                call.respondJson(HttpStatusCode.OK, UserResponse(accounts.updateOwnDisplayName(call.principal.user.id, request.displayName)))
            }
        }
        post("/password") {
            call.handle {
                val request = call.receiveJson<ChangePasswordRequest>()
                accounts.changeOwnPassword(call.principal, request.currentPassword, request.newPassword)
                call.respond(HttpStatusCode.NoContent)
            }
        }
        route("/tokens") {
            get {
                call.handle {
                    call.requireBrowserSession()
                    call.respondJson(HttpStatusCode.OK, TokensResponse(accounts.listTokens(call.principal.user.id)))
                }
            }
            post {
                call.handle {
                    call.requireBrowserSession()
                    val request = call.receiveJson<CreateTokenRequest>()
                    val created = accounts.createToken(call.principal.user.id, request.name, request.expiresInDays)
                    call.respondJson(HttpStatusCode.Created, CreatedTokenResponse(created.token, created.value))
                }
            }
            delete("/{tokenId}") {
                call.handle {
                    call.requireBrowserSession()
                    accounts.revokeToken(call.principal.user.id, call.parameters["tokenId"].orEmpty())
                    call.respond(HttpStatusCode.NoContent)
                }
            }
        }
    }

    route("/api/users") {
        get {
            call.handle {
                call.requireAdministrator()
                call.respondJson(HttpStatusCode.OK, UsersResponse(accounts.listUsers()))
            }
        }
        post {
            call.handle {
                call.requireAdministrator()
                val request = call.receiveJson<CreateUserRequest>()
                val created = accounts.createUser(request.username, request.displayName, request.role, call.principal.user.id)
                call.respondJson(
                    if (created.created) HttpStatusCode.Created else HttpStatusCode.OK,
                    CreatedUserResponse(created.user, created.created, created.oneTimePassword),
                )
            }
        }
        patch("/{userId}") {
            call.handle {
                call.requireAdministrator()
                val request = call.receiveJson<UpdateUserRequest>()
                val updated = accounts.updateUser(call.userId(), request.displayName, request.role, request.active, call.principal.user.id)
                call.respondJson(HttpStatusCode.OK, UserResponse(updated))
            }
        }
        post("/{userId}/password-reset") {
            call.handle {
                call.requireAdministrator()
                val password = accounts.resetPassword(call.userId(), call.principal.user.id)
                call.respondJson(HttpStatusCode.OK, PasswordResetResponse(password))
            }
        }
    }
}

private fun ApplicationCall.userId(): UserId =
    parameters["userId"]?.takeIf { it.isNotBlank() }?.let(::UserId) ?: throw BadRequestException("a user id is required")

/** Refuses a caller who is not an administrator. */
fun ApplicationCall.requireAdministrator() {
    if (!principal.isAdmin) throw AdministratorRequiredException()
}

private fun ApplicationCall.requireBrowserSession() {
    if (principal.kind != CredentialKind.SESSION) throw BrowserSessionRequiredException()
}

private fun sessionCookie(value: String, settings: ServerSettings, maxAgeSeconds: Int): Cookie = Cookie(
    name = SESSION_COOKIE,
    value = value,
    maxAge = maxAgeSeconds,
    path = "/",
    secure = settings.secureCookies,
    httpOnly = true,
    extensions = mapOf("SameSite" to "Lax"),
)
