package infoscry.server

import infoscry.AppContext
import infoscry.config.ProcessLockUnavailable
import infoscry.config.RuntimeInfo
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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
 * The loopback boundary.
 *
 * The server is reachable by anything running on this machine, including a page in a browser, so these
 * tests are about who may talk to it and what they must present: a loopback `Host` header, no
 * cross-origin access, a credential for every mutation, and a bearer token that never comes back out.
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
    fun `a caller that does not address the server as loopback is refused`() {
        val response = rawRequest("GET /api/session HTTP/1.1\r\nHost: evil.example\r\nConnection: close\r\n\r\n")

        assertContains(response, "403")
        assertContains(response, "NON_LOOPBACK_HOST")
        assertFalse(
            response.contains("csrfToken"),
            "no endpoint payload may be served to a non-loopback host",
        )
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
    fun `the session endpoint issues a CSRF token for same-origin callers`() = runBlocking {
        val body = harness.get("/api/session").bodyAsText()

        assertContains(body, "csrfToken")
        assertContains(body, harness.csrfToken)
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
    fun `a mutation without a credential is refused`() = runBlocking {
        val response = harness.createCollection("Uninvited", Credential.NONE)

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertContains(response.bodyAsText(), "MUTATION_REQUIRES_CREDENTIALS")
    }

    @Test
    fun `a mutation with a wrong bearer token is refused`() = runBlocking {
        val response = harness.request(
            io.ktor.http.HttpMethod.Post,
            "/api/collections",
            body = """{"name":"Uninvited"}""",
            credential = Credential.NONE,
        )

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `the CLI bearer token authorizes a mutation`() = runBlocking {
        val response = harness.createCollection("Nightfall", Credential.BEARER)

        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
    }

    @Test
    fun `the browser CSRF token authorizes a mutation`() = runBlocking {
        val response = harness.createCollection("Nightfall", Credential.CSRF)

        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
    }

    @Test
    fun `one credential cannot stand in for the other`() = runBlocking {
        val csrfAsBearer = harness.client.post("${harness.url}/api/collections") {
            header(HttpHeaders.Authorization, "Bearer ${harness.csrfToken}")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Wrong credential"}""")
        }
        val bearerAsCsrf = harness.client.post("${harness.url}/api/collections") {
            header(CSRF_HEADER, harness.bearer)
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Wrong credential"}""")
        }

        assertEquals(HttpStatusCode.Unauthorized, csrfAsBearer.status)
        assertEquals(HttpStatusCode.Unauthorized, bearerAsCsrf.status)
    }

    @Test
    fun `reads need no credential`() = runBlocking {
        val response = harness.get("/api/collections")

        assertEquals(HttpStatusCode.OK, response.status)
        assertContains(response.bodyAsText(), "Default")
    }

    @Test
    fun `the bearer token never appears in a response`() = runBlocking {
        val responses = listOf(
            harness.get("/api/session"),
            harness.get("/api/collections"),
            harness.createCollection("Nightfall", Credential.BEARER),
            harness.createCollection("Nightfall", Credential.NONE),
        )

        responses.forEach { response ->
            val body = response.bodyAsText()
            assertFalse(body.contains(harness.bearer), "the bearer token must never be returned")
            response.headers.entries().forEach { (name, values) ->
                assertFalse(values.any { it.contains(harness.bearer) }, "no header may carry the token")
            }
        }
    }

    @Test
    fun `the runtime file carries the token a CLI needs and is private`() {
        val runtimeFile = harness.context.paths.runtimeFile
        val runtime = RuntimeInfo.read(runtimeFile)

        assertNotNull(runtime)
        assertEquals(harness.bearer, runtime.bearerToken.value)
        if (Files.getFileStore(runtimeFile).supportsFileAttributeView("posix")) {
            assertEquals(
                PosixFilePermissions.fromString("rw-------"),
                Files.getPosixFilePermissions(runtimeFile),
            )
        }
    }

    @Test
    fun `stopping the server removes the runtime file`() {
        val runtimeFile = harness.context.paths.runtimeFile
        assertTrue(Files.exists(runtimeFile))

        harness.server.close()

        assertFalse(Files.exists(runtimeFile), "a stale runtime file would send a CLI to a dead port")
    }

    @Test
    fun `a second server on the same data directory fails with an actionable lock error`() {
        val failure = assertFailsWith<ProcessLockUnavailable> { AppContext.open(dataDir) }

        assertContains(failure.message.orEmpty(), "already running")
        assertContains(
            failure.message.orEmpty(),
            "loopback API",
            ignoreCase = true,
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
