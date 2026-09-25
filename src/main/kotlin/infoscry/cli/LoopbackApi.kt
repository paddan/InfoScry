package infoscry.cli

import infoscry.config.RuntimeInfo
import infoscry.domain.Collection
import infoscry.domain.Job
import infoscry.domain.JobId
import infoscry.server.ApiErrorResponse
import infoscry.server.ApiJson
import infoscry.server.CollectionResponse
import infoscry.server.CollectionsResponse
import infoscry.server.CreateCollectionRequest
import infoscry.server.ImportAcceptedResponse
import infoscry.server.ImportRequest
import infoscry.server.JobResponse
import infoscry.server.LOOPBACK_HOST
import infoscry.server.ReindexAcceptedResponse
import infoscry.server.SearchResponse
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
import io.ktor.client.request.url

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

    suspend fun listJobs(limit: Int): List<Job> =
        ApiJson.decodeFromString<infoscry.server.JobsResponse>(
            expect(client.get("$base/api/jobs?limit=$limit")),
        ).jobs.map { it.toDomain() }

    /** Hands one import to the server that owns the data directory. */
    suspend fun enqueueImport(collection: String, paths: List<String>): ImportAcceptedResponse {
        val request = ImportRequest(collection = collection, paths = paths)
        val response = client.post("$base/api/imports") {
            header()
            contentType(ContentType.Application.Json)
            setBody(ApiJson.encodeToString(request))
        }
        return ApiJson.decodeFromString<ImportAcceptedResponse>(expect(response))
    }

    /**
     * Searches the collection the server serves.
     *
     * This is a read, so a CLI asks the server rather than contending for the data directory's lock;
     * the filters travel as query parameters because that is what `GET /api/search` takes, and the
     * server resolves the collection the same way the collection commands do, by name or id.
     */
    suspend fun search(
        collection: String,
        query: String,
        mode: String?,
        filters: infoscry.search.SearchFilters,
        limit: Int,
    ): infoscry.search.SearchOutcome {
        val response = client.get("$base/api/search") {
            header()
            url {
                parameters.append("collection", collection)
                parameters.append("q", query)
                mode?.let { parameters.append("mode", it) }
                filters.mediaTypes.forEach { parameters.append("mediaType", it) }
                filters.filenameOrPathContains?.let { parameters.append("path", it) }
                filters.titleAuthorOrLanguageContains?.let { parameters.append("text", it) }
                filters.importedFrom?.let { parameters.append("from", it) }
                filters.importedUntil?.let { parameters.append("until", it) }
                filters.statuses.forEach { parameters.append("status", it.name) }
                if (filters.ocrOnly) parameters.append("ocrOnly", "true")
                parameters.append("limit", limit.toString())
            }
        }
        val body = ApiJson.decodeFromString<SearchResponse>(expect(response))
        return infoscry.search.SearchOutcome(
            hits = body.hits.map { hit ->
                infoscry.search.SearchHit(
                    collectionId = infoscry.domain.CollectionId(hit.collectionId),
                    documentId = infoscry.domain.DocumentId(hit.documentId),
                    unitId = infoscry.domain.ContentUnitId(hit.unitId),
                    chunkOrdinal = hit.chunkOrdinal,
                    text = hit.text,
                    highlighted = hit.highlighted,
                    locator = hit.locator,
                    locatorLabel = hit.locatorLabel,
                    matchedBy = hit.matchedBy.map { infoscry.search.SearchMode.valueOf(it) }.toSet(),
                )
            },
            staleFiltered = body.staleFiltered,
        )
    }

    /** Hands a rebuild to the server that owns the data directory. */
    suspend fun enqueueReindex(collection: String?): ReindexAcceptedResponse {
        val request = infoscry.server.ReindexApiRequest(collection = collection)
        val response = client.post("$base/api/reindex") {
            header()
            contentType(ContentType.Application.Json)
            setBody(ApiJson.encodeToString(request))
        }
        return ApiJson.decodeFromString<ReindexAcceptedResponse>(expect(response))
    }

    /** One job's current state, which is what `import --wait` polls. */
    suspend fun getJob(id: JobId): Job =
        ApiJson.decodeFromString<JobResponse>(expect(client.get("$base/api/jobs/${id.value}"))).job.toDomain()

    /** One job's per-document results. */
    suspend fun importItems(id: JobId): List<infoscry.storage.ImportItem> =
        ApiJson.decodeFromString<infoscry.server.ImportItemsResponse>(
            expect(client.get("$base/api/jobs/${id.value}/items")),
        ).items.map { it.toDomain() }

    suspend fun cancelJob(id: JobId): Job {
        val response = client.post("$base/api/jobs/${id.value}/cancel") {
            header()
        }
        return ApiJson.decodeFromString<JobResponse>(expect(response)).job.toDomain()
    }

    override fun close() {
        client.close()
    }

    private fun io.ktor.client.request.HttpRequestBuilder.header() {
        headers.append(HttpHeaders.Authorization, "Bearer ${runtime.bearerToken.value}")
    }

    private fun infoscry.server.JobApiView.toDomain() = Job(
        id = id,
        type = type,
        state = state,
        createdAt = createdAt,
        updatedAt = updatedAt,
        collectionId = collectionId,
        stage = stage,
        completed = completed,
        total = total,
        errorCode = errorCode,
        cancelRequested = cancelRequested,
    )

    private fun infoscry.server.ImportItemApiView.toDomain() = infoscry.storage.ImportItem(
        id = id,
        jobId = jobId,
        itemKey = "",
        documentId = documentId,
        sourcePath = "",
        sourceName = sourceName,
        outcome = outcome,
        errorCode = errorCode,
        errorMessage = null,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

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
