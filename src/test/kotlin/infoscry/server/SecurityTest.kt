package infoscry.server

import infoscry.AppContext
import infoscry.config.ProcessLockUnavailable
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType

/**
 * The request boundary.
 *
 * Who may talk to the server and what they must present: a `Host` header naming this server, no
 * cross-origin access, a signed-in session or a personal access token for every API route, the session's
 * CSRF token beside its cookie for every mutation, and credentials that never come back out.
 */
class SecurityTest {

    private lateinit var dataDir: Path
    private lateinit var harness: ApiTestServer

    @BeforeTest
    fun startServer() {
        dataDir = Files.createTempDirectory("infoscry-security")
        harness = ApiTestServer(dataDir)
    }

    @AfterTest
    fun stopServer() {
        harness.close()
        dataDir.toFile().deleteRecursively()
    }

    @Test
    fun `a caller that does not address the server by a known host is refused`() {
        val response = rawRequest("GET /api/session HTTP/1.1\r\nHost: evil.example\r\nConnection: close\r\n\r\n")

        assertContains(response, "403")
        assertContains(response, "UNKNOWN_HOST")
        assertFalse(response.contains("\"product\""), "no endpoint payload may be served to an unknown host")
    }

    @Test
    fun `a loopback host header is accepted`() {
        val response = rawRequest(
            "GET /api/session HTTP/1.1\r\nHost: 127.0.0.1:${harness.server.port}\r\nConnection: close\r\n\r\n",
        )

        assertContains(response, "200")
        assertContains(response, PRODUCT_NAME)
    }

    @Test
    fun `the session endpoint names the signed-in user and their CSRF token, and nothing for a stranger`() = runBlocking {
        val signedIn = ApiJson.decodeFromString<SessionResponse>(harness.get("/api/session").bodyAsText())
        val anonymous = harness.request(io.ktor.http.HttpMethod.Get, "/api/session", credential = Credential.ANONYMOUS)
        val stranger = ApiJson.decodeFromString<SessionResponse>(anonymous.bodyAsText())

        assertEquals(harness.admin.id, signedIn.user?.id)
        assertEquals(harness.csrfToken, signedIn.csrfToken)
        assertEquals(HttpStatusCode.OK, anonymous.status)
        assertNull(stranger.user)
        assertNull(stranger.csrfToken)
    }

    @Test
    fun `every API route needs a signed-in caller`() = runBlocking {
        val read = harness.request(io.ktor.http.HttpMethod.Get, "/api/collections", credential = Credential.ANONYMOUS)
        val write = harness.createCollection("Uninvited", Credential.ANONYMOUS)

        assertEquals(HttpStatusCode.Unauthorized, read.status)
        assertContains(read.bodyAsText(), "AUTHENTICATION_REQUIRED")
        assertEquals(HttpStatusCode.Unauthorized, write.status)
    }

    @Test
    fun `an encoded or doubled API path is still behind the sign-in`() {
        listOf("/%61pi/collections", "//api/collections", "/api//collections", "/API/collections").forEach { path ->
            val response = rawRequest("GET $path HTTP/1.1\r\nHost: 127.0.0.1:${harness.server.port}\r\nConnection: close\r\n\r\n")
            val status = response.lineSequence().first()
            assertTrue(
                status.contains("401") || status.contains("404"),
                "$path answered '$status' without a session: $response",
            )
            assertFalse(response.contains("\"collections\""), "$path served data without a session")
        }
    }

    @Test
    fun `the web interface itself is served without signing in`() = runBlocking {
        val response = harness.request(io.ktor.http.HttpMethod.Get, "/", credential = Credential.ANONYMOUS)

        assertTrue(response.status != HttpStatusCode.Unauthorized, "the sign-in form has to reach the browser")
    }

    @Test
    fun `no response offers cross-origin access`() = runBlocking {
        val responses = listOf(harness.get("/api/session"), harness.get("/api/collections"))

        responses.forEach { response ->
            assertFalse(
                response.headers.contains(HttpHeaders.AccessControlAllowOrigin),
                "InfoScry must not offer cross-origin access",
            )
        }
    }

    @Test
    fun `a mutation with only the session cookie is refused`() = runBlocking {
        val response = harness.createCollection("Uninvited", Credential.NONE)

        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertContains(response.bodyAsText(), "CSRF_TOKEN_INVALID")
    }

    @Test
    fun `a mutation with a wrong access token is refused`() = runBlocking {
        val response = harness.createCollection("Uninvited", Credential.WRONG_BEARER)

        assertEquals(HttpStatusCode.Unauthorized, response.status, response.bodyAsText())
        assertContains(response.bodyAsText(), "INVALID_ACCESS_TOKEN")
    }

    @Test
    fun `a mutation with a wrong CSRF token is refused`() = runBlocking {
        val response = harness.createCollection("Uninvited", Credential.WRONG_CSRF)

        assertEquals(HttpStatusCode.Forbidden, response.status, response.bodyAsText())
        assertContains(response.bodyAsText(), "CSRF_TOKEN_INVALID")
    }

    @Test
    fun `the CSRF token of another session does not authorize a mutation`() = runBlocking {
        val (_, otherCsrf) = TestAccounts.signIn(harness.client, harness.url, harness.admin.username)

        val response = harness.client.post("${harness.url}/api/collections") {
            header(HttpHeaders.Cookie, "$SESSION_COOKIE=${harness.sessionCookie}")
            header(CSRF_HEADER, otherCsrf)
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Wrong session"}""")
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `a mutation from another origin is refused even with valid credentials`() = runBlocking {
        val response = harness.client.post("${harness.url}/api/collections") {
            header(HttpHeaders.Cookie, "$SESSION_COOKIE=${harness.sessionCookie}")
            header(CSRF_HEADER, harness.csrfToken)
            header(HttpHeaders.Origin, "https://evil.example")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Cross origin"}""")
        }
        val sameOrigin = harness.client.post("${harness.url}/api/collections") {
            header(HttpHeaders.Cookie, "$SESSION_COOKIE=${harness.sessionCookie}")
            header(CSRF_HEADER, harness.csrfToken)
            header(HttpHeaders.Origin, harness.url)
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Same origin"}""")
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertContains(response.bodyAsText(), "CROSS_ORIGIN_REQUEST")
        assertEquals(HttpStatusCode.Created, sameOrigin.status, sameOrigin.bodyAsText())
    }

    @Test
    fun `an internal failure does not hand its message, or a path, to the caller`() = runBlocking {
        // Signing in again for a fresh session is not possible once the database is closed, so the token
        // the harness already holds is checked first; closing the database then makes one handler fail
        // inside the server without a test-only fault injection hook in the product.
        val token = harness.bearer
        harness.context.database.close()

        val response = harness.client.post("${harness.url}/api/collections") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Uninvited"}""")
        }
        val body = response.bodyAsText()

        assertEquals(HttpStatusCode.InternalServerError, response.status, body)
        assertContains(body, "INTERNAL_ERROR")
        assertFalse(
            body.contains(dataDir.toString()),
            "an internal message can name paths inside the data directory and is not the caller's to read",
        )
        assertFalse(body.contains("SQLException"), "the caller gets a code, not an internal class name")
    }

    @Test
    fun `a personal access token authorizes a mutation without a CSRF token`() = runBlocking {
        val response = harness.createCollection("Nightfall", Credential.BEARER)

        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
    }

    @Test
    fun `the session cookie with its CSRF token authorizes a mutation`() = runBlocking {
        val response = harness.createCollection("Nightfall", Credential.CSRF)

        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
    }

    @Test
    fun `one credential cannot stand in for the other`() = runBlocking {
        val sessionAsBearer = harness.client.post("${harness.url}/api/collections") {
            header(HttpHeaders.Authorization, "Bearer ${harness.sessionCookie}")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Wrong credential"}""")
        }
        val tokenAsCookie = harness.client.post("${harness.url}/api/collections") {
            header(HttpHeaders.Cookie, "$SESSION_COOKIE=${harness.bearer}")
            header(CSRF_HEADER, harness.csrfToken)
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Wrong credential"}""")
        }

        assertEquals(HttpStatusCode.Unauthorized, sessionAsBearer.status)
        assertEquals(HttpStatusCode.Unauthorized, tokenAsCookie.status)
    }

    @Test
    fun `reads need a session but no CSRF token`() = runBlocking {
        harness.createCollection("Nightfall", Credential.BEARER)

        val response = harness.get("/api/collections")

        assertEquals(HttpStatusCode.OK, response.status)
        assertContains(response.bodyAsText(), "Nightfall")
    }

    @Test
    fun `credentials never appear in a response`() = runBlocking {
        val responses = listOf(
            harness.get("/api/session"),
            harness.get("/api/collections"),
            harness.get("/api/me/tokens"),
            harness.createCollection("Nightfall", Credential.BEARER),
            harness.createCollection("Nightfall", Credential.NONE),
        )

        responses.forEach { response ->
            val body = response.bodyAsText()
            assertFalse(body.contains(harness.bearer), "an access token must never be returned after its creation")
            assertFalse(body.contains(harness.sessionCookie), "the session ID must never be in a body")
            response.headers.entries().forEach { (_, values) ->
                assertFalse(values.any { it.contains(harness.bearer) }, "no header may carry the token")
            }
        }
    }

    @Test
    fun `the session cookie is HttpOnly and SameSite Lax`() = runBlocking {
        val response = harness.client.post("${harness.url}/api/auth/sign-in") {
            contentType(ContentType.Application.Json)
            setBody(ApiJson.encodeToString(SignInRequest(harness.admin.username, TestAccounts.PASSWORD)))
        }
        val cookie = response.headers.getAll(HttpHeaders.SetCookie).orEmpty().single { it.startsWith("$SESSION_COOKIE=") }

        assertContains(cookie, "HttpOnly", ignoreCase = true)
        assertContains(cookie, "SameSite=Lax", ignoreCase = true)
        assertFalse(cookie.contains("Secure", ignoreCase = true), "a loopback HTTP origin cannot use Secure cookies")
    }

    @Test
    fun `a second server on the same data directory fails with an actionable lock error`() {
        val failure = assertFailsWith<ProcessLockUnavailable> { AppContext.open(dataDir) }

        assertContains(failure.message.orEmpty(), "already running")
        assertContains(
            failure.message.orEmpty(),
            "the CLI talks to the running server",
            message = "the error has to tell the caller what to do instead",
        )
    }

    private fun rawRequest(request: String): String =
        Socket(LOOPBACK_HOST, harness.server.port).use { socket ->
            socket.getOutputStream().write(request.toByteArray())
            socket.getOutputStream().flush()
            socket.getInputStream().readBytes().decodeToString()
        }
}
