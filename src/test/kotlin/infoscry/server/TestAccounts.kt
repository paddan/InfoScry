package infoscry.server

import infoscry.AppContext
import infoscry.account.Passwords
import infoscry.account.User
import infoscry.account.UserRole
import infoscry.storage.AccountStore
import infoscry.storage.Instants
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType

/** Accounts for tests: users with a known password, signed in the way a browser signs in. */
internal object TestAccounts {

    /** The password every test user has. */
    const val PASSWORD = "test password, long enough"

    /** [username] with [role] and [PASSWORD], created if it does not exist, ready to sign in. */
    fun ensureUser(context: AppContext, username: String, role: UserRole = UserRole.USER): User {
        val created = context.accounts.createUser(username, null, role, actor = null)
        AccountStore(context.database).updatePassword(
            created.user.id,
            Passwords().hash(PASSWORD),
            mustChange = false,
            updatedAt = Instants.now(),
            updatedBy = null,
        )
        return context.accounts.user(created.user.id)
    }

    /** Signs [username] in over HTTP and answers the session cookie's value and the CSRF token. */
    suspend fun signIn(client: HttpClient, url: String, username: String, password: String = PASSWORD): Pair<String, String> {
        val response = client.post("$url/api/auth/sign-in") {
            contentType(ContentType.Application.Json)
            setBody(ApiJson.encodeToString(SignInRequest(username, password)))
        }
        check(response.status == HttpStatusCode.OK) { "signing in $username failed: ${response.status} ${response.bodyAsText()}" }
        val cookie = response.headers.getAll(HttpHeaders.SetCookie).orEmpty()
            .first { it.startsWith("$SESSION_COOKIE=") }
            .substringAfter("$SESSION_COOKIE=").substringBefore(';')
        val csrf = ApiJson.decodeFromString<SignedInResponse>(response.bodyAsText()).csrfToken
        return cookie to csrf
    }
}
