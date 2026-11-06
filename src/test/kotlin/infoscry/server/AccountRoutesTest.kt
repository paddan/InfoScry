package infoscry.server

import infoscry.account.UserRole
import io.ktor.client.request.request
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** Sign-in, the caller's own account and user administration, over the wire. */
class AccountRoutesTest {

    private lateinit var dataDir: Path
    private lateinit var harness: ApiTestServer

    @BeforeTest
    fun start() {
        dataDir = Files.createTempDirectory("infoscry-account-routes")
        harness = ApiTestServer(dataDir)
    }

    @AfterTest
    fun stop() {
        harness.close()
        dataDir.toFile().deleteRecursively()
    }

    /** A browser signed in as one user: its cookie and CSRF token. */
    inner class Browser(val cookie: String, val csrf: String) {
        suspend fun send(method: HttpMethod, path: String, body: String? = null): HttpResponse =
            harness.client.request(harness.url + path) {
                this.method = method
                header(HttpHeaders.Cookie, "$SESSION_COOKIE=$cookie")
                header(CSRF_HEADER, csrf)
                if (body != null) {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            }
    }

    private suspend fun signIn(username: String, password: String = TestAccounts.PASSWORD): Browser {
        val (cookie, csrf) = TestAccounts.signIn(harness.client, harness.url, username, password)
        return Browser(cookie, csrf)
    }

    private suspend fun HttpResponse.text(): String = bodyAsText()

    @Test
    fun `a new user signs in with the one-time password and must change it before anything else`() = runBlocking {
        val created = harness.request(HttpMethod.Post, "/api/users", """{"username":"anna","displayName":"Anna"}""", Credential.CSRF)
        assertEquals(HttpStatusCode.Created, created.status, created.text())
        val password = assertNotNull(ApiJson.decodeFromString<CreatedUserResponse>(created.text()).oneTimePassword)

        val anna = signIn("anna", password)
        val blocked = anna.send(HttpMethod.Get, "/api/collections")
        assertEquals(HttpStatusCode.Forbidden, blocked.status)
        assertContains(blocked.text(), "PASSWORD_CHANGE_REQUIRED")
        assertEquals(HttpStatusCode.OK, anna.send(HttpMethod.Get, "/api/me").status)

        val changed = anna.send(
            HttpMethod.Post,
            "/api/me/password",
            ApiJson.encodeToString(ChangePasswordRequest(password, "anna's own long password")),
        )
        assertEquals(HttpStatusCode.NoContent, changed.status, changed.text())
        assertEquals(HttpStatusCode.OK, anna.send(HttpMethod.Get, "/api/collections").status)
    }

    @Test
    fun `creating a user twice answers the existing user without a password`() = runBlocking {
        harness.request(HttpMethod.Post, "/api/users", """{"username":"anna"}""", Credential.CSRF)
        val again = harness.request(HttpMethod.Post, "/api/users", """{"username":"ANNA","role":"ADMIN"}""", Credential.CSRF)

        assertEquals(HttpStatusCode.OK, again.status)
        val body = ApiJson.decodeFromString<CreatedUserResponse>(again.text())
        assertFalse(body.created)
        assertNull(body.oneTimePassword)
        assertEquals(UserRole.USER, body.user.role)
    }

    @Test
    fun `a wrong password and an unknown username answer the same`() = runBlocking {
        val wrong = harness.client.post("${harness.url}/api/auth/sign-in") {
            contentType(ContentType.Application.Json)
            setBody(ApiJson.encodeToString(SignInRequest(harness.admin.username, "not the password at all")))
        }
        val unknown = harness.client.post("${harness.url}/api/auth/sign-in") {
            contentType(ContentType.Application.Json)
            setBody(ApiJson.encodeToString(SignInRequest("nobody", "not the password at all")))
        }

        assertEquals(HttpStatusCode.Unauthorized, wrong.status)
        assertEquals(wrong.text(), unknown.text())
        assertTrue(wrong.headers.getAll(HttpHeaders.SetCookie).orEmpty().none { it.startsWith("$SESSION_COOKIE=") && !it.startsWith("$SESSION_COOKIE=;") })
    }

    @Test
    fun `too many failed sign-ins answer 429 with Retry-After`() = runBlocking {
        repeat(10) {
            harness.client.post("${harness.url}/api/auth/sign-in") {
                contentType(ContentType.Application.Json)
                setBody(ApiJson.encodeToString(SignInRequest(harness.admin.username, "wrong password $it")))
            }
        }
        val limited = harness.client.post("${harness.url}/api/auth/sign-in") {
            contentType(ContentType.Application.Json)
            setBody(ApiJson.encodeToString(SignInRequest(harness.admin.username, TestAccounts.PASSWORD)))
        }

        assertEquals(HttpStatusCode.TooManyRequests, limited.status)
        assertContains(limited.text(), "SIGN_IN_RATE_LIMITED")
        assertNotNull(limited.headers[HttpHeaders.RetryAfter])
    }

    @Test
    fun `signing out ends the session and signing out twice answers 204`() = runBlocking {
        val browser = signIn(harness.admin.username)

        assertEquals(HttpStatusCode.NoContent, browser.send(HttpMethod.Post, "/api/auth/sign-out").status)
        assertEquals(HttpStatusCode.NoContent, browser.send(HttpMethod.Post, "/api/auth/sign-out").status)
        assertEquals(HttpStatusCode.Unauthorized, browser.send(HttpMethod.Get, "/api/collections").status)
        assertEquals(HttpStatusCode.OK, harness.get("/api/collections").status, "the harness's own session is untouched")
    }

    @Test
    fun `a regular user cannot manage users`() = runBlocking {
        TestAccounts.ensureUser(harness.context, "erik")
        val erik = signIn("erik")

        val list = erik.send(HttpMethod.Get, "/api/users")
        val create = erik.send(HttpMethod.Post, "/api/users", """{"username":"mallory","role":"ADMIN"}""")

        assertEquals(HttpStatusCode.Forbidden, list.status)
        assertContains(list.text(), "ADMINISTRATOR_REQUIRED")
        assertEquals(HttpStatusCode.Forbidden, create.status)
        assertNull(harness.context.accounts.findByUsername("mallory"))
    }

    @Test
    fun `deactivating a user ends their session and token at once`() = runBlocking {
        val user = TestAccounts.ensureUser(harness.context, "erik")
        val erik = signIn("erik")
        val token = harness.context.accounts.createToken(user.id, "cli", null).value

        val deactivated = harness.request(HttpMethod.Patch, "/api/users/${user.id.value}", """{"active":false}""", Credential.CSRF)
        assertEquals(HttpStatusCode.OK, deactivated.status, deactivated.text())

        assertEquals(HttpStatusCode.Unauthorized, erik.send(HttpMethod.Get, "/api/collections").status)
        val withToken = harness.client.get("${harness.url}/api/collections") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.Unauthorized, withToken.status)
    }

    @Test
    fun `the last administrator cannot demote themself`() = runBlocking {
        val response = harness.request(HttpMethod.Patch, "/api/users/${harness.admin.id.value}", """{"role":"USER"}""", Credential.CSRF)

        assertEquals(HttpStatusCode.Conflict, response.status)
        assertContains(response.text(), "LAST_ADMINISTRATOR")
    }

    @Test
    fun `a password reset hands out a one-time password once and ends the user's sessions`() = runBlocking {
        val user = TestAccounts.ensureUser(harness.context, "erik")
        val erik = signIn("erik")

        val reset = harness.request(HttpMethod.Post, "/api/users/${user.id.value}/password-reset", credential = Credential.CSRF)
        assertEquals(HttpStatusCode.OK, reset.status)
        val password = ApiJson.decodeFromString<PasswordResetResponse>(reset.text()).oneTimePassword

        assertEquals(HttpStatusCode.Unauthorized, erik.send(HttpMethod.Get, "/api/collections").status)
        signIn("erik", password)
    }

    @Test
    fun `a token is shown once, works for the CLI, and is managed only from a browser`() = runBlocking {
        val created = harness.request(HttpMethod.Post, "/api/me/tokens", """{"name":"laptop","expiresInDays":30}""", Credential.CSRF)
        assertEquals(HttpStatusCode.Created, created.status, created.text())
        val token = ApiJson.decodeFromString<CreatedTokenResponse>(created.text())
        assertTrue(token.value.startsWith("isc_"))

        val listed = harness.get("/api/me/tokens").text()
        assertContains(listed, "laptop")
        assertFalse(listed.contains(token.value))

        val withToken = harness.client.get("${harness.url}/api/collections") { header(HttpHeaders.Authorization, "Bearer ${token.value}") }
        assertEquals(HttpStatusCode.OK, withToken.status)
        val minted = harness.client.post("${harness.url}/api/me/tokens") {
            header(HttpHeaders.Authorization, "Bearer ${token.value}")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"another"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, minted.status)
        assertContains(minted.text(), "BROWSER_SESSION_REQUIRED")

        val revoke = harness.request(HttpMethod.Delete, "/api/me/tokens/${token.token.id}", credential = Credential.CSRF)
        val again = harness.request(HttpMethod.Delete, "/api/me/tokens/${token.token.id}", credential = Credential.CSRF)
        assertEquals(HttpStatusCode.NoContent, revoke.status)
        assertEquals(HttpStatusCode.NoContent, again.status)
        val revoked = harness.client.get("${harness.url}/api/collections") { header(HttpHeaders.Authorization, "Bearer ${token.value}") }
        assertEquals(HttpStatusCode.Unauthorized, revoked.status)
    }

    @Test
    fun `a user changes their own display name`() = runBlocking {
        val response = harness.request(HttpMethod.Patch, "/api/me", """{"displayName":"The Administrator"}""", Credential.CSRF)

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("The Administrator", harness.context.accounts.user(harness.admin.id).displayName)
    }
}
