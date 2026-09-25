package infoscry.server

import infoscry.AppContext
import infoscry.collection.CollectionIndexRemover
import infoscry.config.RuntimeInfo
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import java.nio.file.Path
import infoscry.server.DEFAULT_JOB_EVENT_IDLE_DEADLINE_MILLIS
import kotlinx.coroutines.runBlocking

/**
 * Which credential a request carries.
 *
 * `WRONG_BEARER` and `WRONG_CSRF` are well-formed credentials that are not this server's: the point is
 * that presenting *a* token is not enough, only the right one is.
 */
internal enum class Credential {
    NONE,
    BEARER,
    CSRF,
    WRONG_BEARER,
    WRONG_CSRF,
}

/**
 * A real InfoScry server on a temporary data directory, for tests that need the whole boundary.
 *
 * It deliberately discovers its credentials the way real callers do — the bearer from `runtime.json`,
 * the CSRF token from the session endpoint — so no test-only accessor has to exist in the product. The
 * client is a real HTTP client over a real socket, because the guarantee under test is about the wire,
 * not about a handler called directly.
 */
internal class ApiTestServer(
    val dataDir: Path,
    index: CollectionIndexRemover = CollectionIndexRemover.NONE,
    jobEventIdleDeadlineMillis: Long = DEFAULT_JOB_EVENT_IDLE_DEADLINE_MILLIS,
) : AutoCloseable {

    val context: AppContext = AppContext.open(dataDir, index)

    // Port 0 lets the operating system choose, so a test never collides with another server — including
    // a second server started inside the same test to observe how maintenance excludes it.
    val server: RunningServer = runBlocking {
        startLoopbackServer(context, port = 0, jobEventIdleDeadlineMillis = jobEventIdleDeadlineMillis)
    }
    val client: HttpClient = HttpClient(CIO)

    val url: String get() = server.url

    val bearer: String = RuntimeInfo.read(context.paths.runtimeFile)!!.bearerToken.value

    val csrfToken: String = runBlocking {
        val body = client.get(url + SESSION_PATH).bodyAsText()
        body.substringAfter("\"csrfToken\":\"").substringBefore('"')
    }

    suspend fun get(path: String): HttpResponse = request(HttpMethod.Get, path, body = null, Credential.NONE)

    suspend fun request(
        method: HttpMethod,
        path: String,
        body: String? = null,
        credential: Credential = Credential.NONE,
    ): HttpResponse = client.request(url + path) {
        this.method = method
        when (credential) {
            Credential.NONE -> Unit
            Credential.BEARER -> header(HttpHeaders.Authorization, "Bearer $bearer")
            Credential.CSRF -> header(CSRF_HEADER, csrfToken)
            Credential.WRONG_BEARER -> header(HttpHeaders.Authorization, "Bearer $WRONG_CREDENTIAL_VALUE")
            Credential.WRONG_CSRF -> header(CSRF_HEADER, WRONG_CREDENTIAL_VALUE)
        }
        if (body != null) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
    }

    suspend fun createCollection(
        name: String,
        credential: Credential = Credential.BEARER,
    ): HttpResponse = request(
        HttpMethod.Post,
        COLLECTIONS_PATH,
        body = """{"name":"$name"}""",
        credential = credential,
    )

    /** The collection id the server assigned to [name], read back through the API. */
    suspend fun collectionIdOf(name: String): String {
        val body = get(COLLECTIONS_PATH).bodyAsText()
        val collections = ApiJson.decodeFromString<CollectionsResponse>(body).collections
        return collections.firstOrNull { it.name == name }?.id?.value
            ?: error("no collection named $name in $body")
    }

    override fun close() {
        client.close()
        server.close()
        context.close()
    }

    private companion object {
        const val SESSION_PATH = "/api/session"
        const val COLLECTIONS_PATH = "/api/collections"

        /** A credential-shaped value that is deliberately not the server's. */
        const val WRONG_CREDENTIAL_VALUE = "not-the-credential-this-server-issued"
    }
}
