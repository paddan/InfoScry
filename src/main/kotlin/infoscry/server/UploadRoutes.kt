package infoscry.server

import infoscry.AppContext
import infoscry.account.CollectionRole
import infoscry.domain.CollectionId
import infoscry.domain.ApiFaultException
import infoscry.domain.ApiStatus
import infoscry.jobs.ImportRootView
import io.ktor.http.HttpStatusCode
import io.ktor.http.decodeURLPart
import io.ktor.server.application.call
import io.ktor.server.request.receiveChannel
import io.ktor.server.routing.Routing
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.utils.io.readAvailable
import java.nio.file.Files
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/** The header an upload names its file in, percent-encoded. */
const val UPLOAD_FILENAME_HEADER = "X-InfoScry-Filename"

/** An upload, as it can be imported: [uploadId] goes into an import's `uploads`. */
@Serializable
data class UploadResponse(
    val uploadId: String? = null,
    val name: String,
    val sizeBytes: Long,
    val sha256: String,
    /** The document these bytes already are in this collection; nothing was stored when it is set. */
    val existingDocumentId: String? = null,
)

/** An upload larger than the server accepts. */
class UploadTooLargeException(limit: Long) : ApiFaultException(
    ApiStatus.BAD_REQUEST,
    "UPLOAD_TOO_LARGE",
    "a file may be at most ${limit / (1024 * 1024)} MiB",
)

/** The largest file an upload may be: `INFOSCRY_UPLOAD_MAX_BYTES`, by default 4 GiB. */
internal fun uploadLimit(environment: Map<String, String> = System.getenv()): Long =
    environment["INFOSCRY_UPLOAD_MAX_BYTES"]?.trim()?.toLongOrNull()?.takeIf { it > 0 } ?: 4L * 1024 * 1024 * 1024

/** The stored files of [uploads] in [collectionId]'s upload area; an unknown upload id is a 404. */
internal fun AppContext.uploadPaths(collectionId: CollectionId, uploads: List<String>): List<String> = uploads.map { id ->
    require(id.matches(UPLOAD_ID)) { "not an upload id" }
    val directory = importRoots.uploadArea(collectionId).resolve(id)
    val file = if (Files.isDirectory(directory)) Files.list(directory).use { it.findFirst().orElse(null) } else null
    file?.toString() ?: throw NoSuchElementException("no upload with id $id in this collection")
}

private val UPLOAD_ID = Regex("[0-9a-f-]{36}")

/**
 * Uploads and import roots: the two places an import reads from.
 *
 * An upload is one file per request, the raw bytes as the body, streamed into the collection's upload area and
 * hashed on the way. Bytes that are already one of the collection's documents are not kept: the answer names
 * that document instead.
 */
/** The upload size limit in force; tests lower it. */
object UploadLimit {
    @Volatile
    var bytes: Long = uploadLimit()
}

fun Routing.configureUploadRoutes(context: AppContext) {
    post("/api/collections/{id}/uploads") {
        call.handle {
            val collection = context.collectionService.requireActiveByNameOrId(call.collectionId().value)
            val name = call.request.headers[UPLOAD_FILENAME_HEADER]?.let { runCatching { it.decodeURLPart() }.getOrNull() }
                ?.substringAfterLast('/')?.substringAfterLast('\\')?.trim()
                ?.takeIf { it.isNotEmpty() && it != "." && it != ".." && it.none(Char::isISOControl) }
                ?: throw BadRequestException("an upload names its file in $UPLOAD_FILENAME_HEADER")
            val uploadId = UUID.randomUUID().toString()
            val directory = context.importRoots.uploadArea(collection.id).resolve(uploadId)
            val target = directory.resolve(name)
            val maxBytes = UploadLimit.bytes
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            try {
                withContext(Dispatchers.IO) { Files.createDirectories(directory) }
                val channel = call.receiveChannel()
                val buffer = ByteArray(BUFFER_BYTES)
                withContext(Dispatchers.IO) { Files.newOutputStream(target) }.use { output ->
                    while (true) {
                        val read = channel.readAvailable(buffer, 0, buffer.size)
                        if (read < 0) break
                        if (read == 0) continue
                        size += read
                        if (size > maxBytes) throw UploadTooLargeException(maxBytes)
                        digest.update(buffer, 0, read)
                        withContext(Dispatchers.IO) { output.write(buffer, 0, read) }
                    }
                }
            } catch (failure: Throwable) {
                directory.toFile().deleteRecursively()
                if (failure is UploadTooLargeException) {
                    call.respondJson(
                        HttpStatusCode.PayloadTooLarge,
                        ApiErrorResponse(ApiError(code = failure.code, message = failure.clientMessage())),
                    )
                    return@handle
                }
                throw failure
            }
            val sha256 = HexFormat.of().formatHex(digest.digest())
            // The same bytes are already a document here: that document is the answer, and nothing is kept.
            context.documents.findBySha256(collection.id, sha256)?.let { existing ->
                directory.toFile().deleteRecursively()
                call.respondJson(HttpStatusCode.OK, UploadResponse(null, name, size, sha256, existing.id.value))
                return@handle
            }
            call.respondJson(HttpStatusCode.Created, UploadResponse(uploadId, name, size, sha256))
        }
    }

    get("/api/import-roots") {
        call.handle {
            call.requireImportBrowsing(context)
            call.respondJson(HttpStatusCode.OK, ImportRootsResponse(context.importRoots.views()))
        }
    }

    post("/api/import-roots") {
        call.handle {
            call.requireAdministrator()
            val request = call.receiveJson<AddImportRootRequest>()
            val (root, created) = context.importRoots.add(request.path)
            call.respondJson(if (created) HttpStatusCode.Created else HttpStatusCode.OK, ImportRootResponse(root, created))
        }
    }

    delete("/api/import-roots/{rootId}") {
        call.handle {
            call.requireAdministrator()
            context.importRoots.remove(call.parameters["rootId"].orEmpty())
            call.respond(HttpStatusCode.NoContent)
        }
    }

    get("/api/import-roots/browse") {
        call.handle {
            call.requireImportBrowsing(context)
            val root = call.request.queryParameters["root"]?.takeIf { it.isNotBlank() } ?: throw BadRequestException("root names an import folder")
            val path = call.request.queryParameters["path"].orEmpty()
            call.respondJson(HttpStatusCode.OK, context.importRoots.browse(root, path))
        }
    }
}

@Serializable
data class ImportRootsResponse(val roots: List<ImportRootView>)

@Serializable
data class AddImportRootRequest(val path: String)

@Serializable
data class ImportRootResponse(val root: ImportRootView, val created: Boolean)

/** Browsing the server's import roots is for people who may import somewhere: an editor of any collection. */
private fun io.ktor.server.application.ApplicationCall.requireImportBrowsing(context: AppContext) {
    if (principal.isAdmin) return
    val roles = context.memberships.rolesOf(principal.user.id).values
    if (roles.none { it.allows(CollectionRole.EDITOR) }) throw CollectionRoleRequiredException(CollectionRole.EDITOR)
}

private const val BUFFER_BYTES = 64 * 1024
