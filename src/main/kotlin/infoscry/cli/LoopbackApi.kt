package infoscry.cli

import infoscry.config.RuntimeInfo
import infoscry.domain.Collection
import infoscry.server.ApiErrorResponse
import infoscry.server.ApiJson
import infoscry.server.CollectionResponse
import infoscry.server.CollectionsResponse
import infoscry.server.CreateCollectionRequest
import infoscry.server.LOOPBACK_HOST
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess

/**
 * A failure the server reported, in the words it used.
 *
 * The CLI does not invent its own error text for a server-side failure: the status the API returned is
 * the contract (423 means retry later, 409 means the name is taken), and its message already names what
 * the caller should do.
 */
class RemoteApiFailure(val code: String, override val message: String) : IllegalStateException(message)

/**
 * The CLI's path to a server that owns the data directory.
 *
 * A CLI command cannot open the database while a server has it — one writer is the whole point of the
 * process lock — so when `runtime.json` names a live server, the CLI asks that server instead of
 * touching the files. The bearer token comes from the same private file the server published it in.
 */
class LoopbackApi(
    private val runtime: RuntimeInfo,
    private val client: HttpClient = HttpClient(CIO),
) : AutoCloseable {

    private val base: String = "http://$LOOPBACK_HOST:${runtime.port}"

    suspend fun listCollections(): List<Collection> =
        ApiJson.decodeFromString<CollectionsResponse>(expect(client.get("$base/api/collections"))).collections

    suspend fun createCollection(
        name: String,
        description: String? = null,
        ocrLanguages: String? = null,
    ): Collection {
        val request = CreateCollectionRequest(name = name, description = description, ocrLanguages = ocrLanguages)
        val response = client.post("$base/api/collections") {
            header()
            contentType(ContentType.Application.Json)
            setBody(ApiJson.encodeToString(request))
        }
        return ApiJson.decodeFromString<CollectionResponse>(expect(response)).collection
    }

    override fun close() {
        client.close()
    }

    private fun io.ktor.client.request.HttpRequestBuilder.header() {
        headers.append(HttpHeaders.Authorization, "Bearer ${runtime.bearerToken.value}")
    }

    private suspend fun expect(response: HttpResponse): String {
        val body = response.bodyAsText()
        if (response.status.isSuccess()) return body
        val error = runCatching { ApiJson.decodeFromString<ApiErrorResponse>(body).error }.getOrNull()
        throw RemoteApiFailure(
            code = error?.code ?: "HTTP_${response.status.value}",
            message = error?.message ?: "the server answered ${response.status} without a message",
        )
    }
}
