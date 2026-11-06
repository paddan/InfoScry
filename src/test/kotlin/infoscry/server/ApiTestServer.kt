package infoscry.server

import infoscry.AppContext
import infoscry.collection.CollectionIndexRemover
import infoscry.document.DocumentIndexRemover
import infoscry.document.RetryPrerequisites
import infoscry.domain.Collection
import infoscry.embedding.QueryEmbedder
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.HttpRequestBuilder
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
import io.ktor.http.isSuccess
import java.util.UUID
import java.nio.file.Path
import infoscry.server.DEFAULT_JOB_EVENT_IDLE_DEADLINE_MILLIS
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Which credential a request carries.
 *
 * Every credential except [ANONYMOUS] belongs to the signed-in test administrator. [NONE] is a signed-in
 * browser that sends only its session cookie, which is all a read needs and what a forged cross-site
 * request would carry; [CSRF] adds the session's CSRF token; [BEARER] is a personal access token, as the
 * CLI sends. `WRONG_BEARER` and `WRONG_CSRF` are well-formed credentials that are not this server's: the
 * point is that presenting *a* token is not enough, only the right one is.
 */
internal enum class Credential {
    ANONYMOUS,
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
    /** Whether a rescan could embed here, for the tests that drive rescan admission without the pinned model. */
    rescanEmbedder: (() -> Boolean)? = null,
    /** The embedder a restore re-embeds with, for the tests that restore a revision without the pinned model. */
    restoreEmbedder: (() -> infoscry.embedding.DocumentEmbedder?)? = null,
) : AutoCloseable {

    val context: AppContext = AppContext.open(
        dataDir,
        index,
        queryEmbedder,
        documentIndex,
        retryPrerequisites,
        rescanEmbedder,
        restoreEmbedder,
    )

    // Port 0 lets the operating system choose, so a test never collides with another server — including
    // a second server started inside the same test to observe how maintenance excludes it.
    val server: RunningServer = runBlocking {
        startServer(
            context,
            ServerSettings(port = 0),
            jobEventIdleDeadlineMillis = jobEventIdleDeadlineMillis,
        )
    }
    val client: HttpClient = HttpClient(CIO)

    val url: String get() = server.url

    /** The administrator every credential but [Credential.ANONYMOUS] belongs to. */
    val admin: infoscry.account.User = TestAccounts.ensureUser(context, ADMIN_USERNAME, infoscry.account.UserRole.ADMIN)

    /** The admin's browser session: its cookie value and its CSRF token, as a real sign-in returns them. */
    val sessionCookie: String
    val csrfToken: String

    init {
        val (cookie, csrf) = runBlocking { TestAccounts.signIn(client, url, ADMIN_USERNAME) }
        sessionCookie = cookie
        csrfToken = csrf
    }

    /** A personal access token of the admin, as the CLI presents it. */
    val bearer: String = context.accounts.createToken(admin.id, "tests", null).value

    suspend fun get(path: String): HttpResponse = request(HttpMethod.Get, path, body = null, Credential.NONE)

    suspend fun request(
        method: HttpMethod,
        path: String,
        body: String? = null,
        credential: Credential = Credential.NONE,
    ): HttpResponse = client.request(url + path) {
        this.method = method
        applyCredential(credential)
        if (body != null) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
    }

    /**
     * Sends the body as the bytes on the wire, for a request a route has to refuse by its bytes.
     *
     * A body is not the text it decodes to: a UTF-16 body holding only a byte order mark decodes to an
     * empty string while still being two bytes a caller sent, so a test about "the endpoint takes no body"
     * has to put the bytes there itself.
     */
    suspend fun requestBytes(
        method: HttpMethod,
        path: String,
        body: ByteArray,
        contentType: ContentType,
        credential: Credential = Credential.NONE,
    ): HttpResponse = client.request(url + path) {
        this.method = method
        applyCredential(credential)
        contentType(contentType)
        setBody(body)
    }

    /** Adds the signed-in admin's session cookie to a request a test builds itself. */
    fun signedIn(builder: HttpRequestBuilder) {
        builder.header(HttpHeaders.Cookie, "$SESSION_COOKIE=$sessionCookie")
    }

    internal fun HttpRequestBuilder.applyCredential(credential: Credential) {
        when (credential) {
            Credential.ANONYMOUS -> Unit
            Credential.NONE -> header(HttpHeaders.Cookie, "$SESSION_COOKIE=$sessionCookie")
            Credential.BEARER -> header(HttpHeaders.Authorization, "Bearer $bearer")
            Credential.CSRF -> {
                header(HttpHeaders.Cookie, "$SESSION_COOKIE=$sessionCookie")
                header(CSRF_HEADER, csrfToken)
            }
            Credential.WRONG_BEARER -> header(HttpHeaders.Authorization, "Bearer isc_$WRONG_CREDENTIAL_VALUE")
            Credential.WRONG_CSRF -> {
                header(HttpHeaders.Cookie, "$SESSION_COOKIE=$sessionCookie")
                header(CSRF_HEADER, WRONG_CREDENTIAL_VALUE)
            }
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

    /** Uploads [file] to [collection] (by name or id) as a browser or the CLI does. */
    suspend fun uploadResponse(collection: String, file: Path, credential: Credential = Credential.BEARER): HttpResponse {
        val collectionId = runCatching { collectionIdOf(collection) }.getOrDefault(collection)
        return client.request(url + "/api/collections/$collectionId/uploads") {
            this.method = HttpMethod.Post
            applyCredential(credential)
            header(UPLOAD_FILENAME_HEADER, java.net.URLEncoder.encode(file.fileName.toString(), Charsets.UTF_8).replace("+", "%20"))
            contentType(ContentType.Application.OctetStream)
            setBody(java.nio.file.Files.readAllBytes(file))
        }
    }

    /** Uploads [file] and answers its upload id, for a test that then names it in an import request. */
    suspend fun upload(collection: String, file: Path, credential: Credential = Credential.BEARER): String {
        val response = uploadResponse(collection, file, credential)
        check(response.status.isSuccess()) { "uploading $file should succeed, was ${response.status}: ${response.bodyAsText()}" }
        return ApiJson.decodeFromString<UploadResponse>(response.bodyAsText()).uploadId!!
    }

    /**
     * Imports files from this machine the way the web and the CLI do: each is uploaded to the collection, then
     * the uploads go through the preview and the confirmed start. An upload the server refuses is the answer.
     */
    suspend fun previewAndStartImport(
        collection: String,
        paths: List<String>,
        credential: Credential = Credential.BEARER,
        method: String = "tesseract",
    ): HttpResponse {
        val uploads = mutableListOf<String>()
        for (path in paths) {
            val uploaded = uploadResponse(collection, Path.of(path), credential)
            if (!uploaded.status.isSuccess()) return uploaded
            ApiJson.decodeFromString<UploadResponse>(uploaded.bodyAsText()).uploadId?.let(uploads::add)
        }
        val previewRequest = ImportPreviewRouteRequest(collection = collection, uploads = uploads, method = method)
        val previewResponse = request(
            HttpMethod.Post,
            "/api/imports/preview",
            ApiJson.encodeToString(previewRequest),
            credential,
        )
        check(previewResponse.status == HttpStatusCode.OK) {
            "previewing an import should succeed, was ${previewResponse.status}: ${previewResponse.bodyAsText()}"
        }
        val preview = ApiJson.decodeFromString<infoscry.jobs.ImportPreview>(previewResponse.bodyAsText())
        val startRequest = ImportRequest(
            collection = collection,
            uploads = uploads,
            method = method,
            previewHash = preview.previewHash,
            requestId = "test-import-${UUID.randomUUID()}",
        )
        return request(
            HttpMethod.Post,
            "/api/imports",
            ApiJson.encodeToString(startRequest),
            credential,
        )
    }

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
        const val ADMIN_USERNAME = "test-admin"
        const val COLLECTIONS_PATH = "/api/collections"

        /** Generous: the poll is what a real deletion's own phases take, not a fixed wait. */
        const val DELETION_TIMEOUT_MILLIS = 30_000L

        const val DELETION_POLL_MILLIS = 25L

        /** A credential-shaped value that is deliberately not the server's. */
        const val WRONG_CREDENTIAL_VALUE = "not-the-credential-this-server-issued"
    }
}
