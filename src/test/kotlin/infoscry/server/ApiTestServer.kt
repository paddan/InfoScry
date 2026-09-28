package infoscry.server

import infoscry.AppContext
import infoscry.collection.CollectionIndexRemover
import infoscry.config.RuntimeInfo
import infoscry.document.DocumentIndexRemover
import infoscry.document.RetryPrerequisites
import infoscry.domain.Collection
import infoscry.embedding.QueryEmbedder
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
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import java.nio.file.Path
import infoscry.server.DEFAULT_JOB_EVENT_IDLE_DEADLINE_MILLIS
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

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
    picker: suspend (Boolean) -> List<String> = ::pickWithOsascript,
    /** A deterministic retrieval vector for tests that drive a whole search-backed route without a model. */
    queryEmbedder: (() -> QueryEmbedder?)? = null,
    /** The document index phase, for tests that need to hold or fail a document deletion's index step. */
    documentIndex: DocumentIndexRemover = DocumentIndexRemover.NONE,
    /**
     * What a retry may assume about this machine. Tests inject it so an admission test asserts on the
     * archive's behaviour rather than on whether Tesseract, Calibre or the pinned model happen to be
     * installed where the suite runs.
     */
    retryPrerequisites: (suspend (Collection) -> RetryPrerequisites)? = null,
) : AutoCloseable {

    val context: AppContext = AppContext.open(dataDir, index, queryEmbedder, documentIndex, retryPrerequisites)

    // Port 0 lets the operating system choose, so a test never collides with another server — including
    // a second server started inside the same test to observe how maintenance excludes it.
    val server: RunningServer = runBlocking {
        startLoopbackServer(
            context,
            port = 0,
            jobEventIdleDeadlineMillis = jobEventIdleDeadlineMillis,
            picker = picker,
        )
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

    /**
     * Confirms one collection's deletion and returns the operation the server admitted.
     *
     * The answer is checked to be the 202 the route promises, so a test that then follows the operation
     * is following one that was really admitted rather than one it invented.
     */
    suspend fun admitCollectionDeletion(collectionId: String, confirmName: String): DeleteCollectionResponse {
        val response = request(
            HttpMethod.Delete,
            "/api/collections/$collectionId",
            body = """{"confirmName":"$confirmName"}""",
            credential = Credential.BEARER,
        )
        check(response.status == HttpStatusCode.Accepted) {
            "admitting a deletion should be accepted, was ${response.status}: ${response.bodyAsText()}"
        }
        return ApiJson.decodeFromString(response.bodyAsText())
    }

    /**
     * Reads one deletion operation until it is terminal.
     *
     * A deletion's phases run after the response that admitted it, so every assertion about the end
     * state follows the operation instead of assuming it finished with the request.
     */
    suspend fun awaitDeletion(operationId: String, timeoutMillis: Long = DELETION_TIMEOUT_MILLIS): DeletionOperationApiView =
        withTimeout(timeoutMillis) {
            while (true) {
                val operation = readDeletion(operationId)
                if (operation.terminal) return@withTimeout operation
                delay(DELETION_POLL_MILLIS)
            }
            error("unreachable")
        }

    /** One deletion operation, terminal or not, as the read route reports it. */
    suspend fun readDeletion(operationId: String): DeletionOperationApiView {
        val response = get("/api/deletions/$operationId")
        check(response.status == HttpStatusCode.OK) {
            "reading deletion $operationId should work, was ${response.status}: ${response.bodyAsText()}"
        }
        return ApiJson.decodeFromString<DeletionOperationResponse>(response.bodyAsText()).operation
    }

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

        /** Generous: the poll is what a real deletion's own phases take, not a fixed wait. */
        const val DELETION_TIMEOUT_MILLIS = 30_000L

        const val DELETION_POLL_MILLIS = 25L

        /** A credential-shaped value that is deliberately not the server's. */
        const val WRONG_CREDENTIAL_VALUE = "not-the-credential-this-server-issued"
    }
}
