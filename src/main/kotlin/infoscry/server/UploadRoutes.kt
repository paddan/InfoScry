package infoscry.server

import infoscry.AppContext
import infoscry.domain.CollectionId
import infoscry.domain.ApiFaultException
import infoscry.domain.ApiStatus
import io.ktor.http.HttpStatusCode
import io.ktor.http.decodeURLPart
import io.ktor.server.application.call
import io.ktor.server.request.receiveChannel
import io.ktor.server.routing.Routing
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
    val directory = uploadAreas.uploadArea(collectionId).resolve(id)
    val file = if (Files.isDirectory(directory)) Files.list(directory).use { it.findFirst().orElse(null) } else null
    file?.toString() ?: throw NoSuchElementException("no upload with id $id in this collection")
}

private val UPLOAD_ID = Regex("[0-9a-f-]{36}")

/**
 * Uploads: the only place an import reads from. A folder is uploaded from the client's computer file by file.
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
            // Written under a scratch id first; its bytes decide the id it is kept under.
            val directory = context.uploadAreas.uploadArea(collection.id).resolve(UUID.randomUUID().toString())
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
            // The upload's id follows from its bytes, so sending the same file again, or a second person sending it
            // before it is imported, answers with the upload that is already there and one import of it.
            val stableId = UUID.nameUUIDFromBytes("${collection.id.value}:$sha256".toByteArray()).toString()
            val stable = context.uploadAreas.uploadArea(collection.id).resolve(stableId)
            val existing = withContext(Dispatchers.IO) {
                if (!Files.isDirectory(stable)) {
                    try {
                        Files.move(directory, stable, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
                        return@withContext null
                    } catch (_: java.nio.file.FileAlreadyExistsException) {
                        // Someone else's identical upload got there first; theirs is the answer.
                    } catch (_: java.nio.file.DirectoryNotEmptyException) {
                    }
                }
                directory.toFile().deleteRecursively()
                // Kept a week from now: a repeated upload renews the area the sweep would otherwise remove.
                Files.setLastModifiedTime(stable, java.nio.file.attribute.FileTime.from(java.time.Instant.now()))
                Files.list(stable).use { it.findFirst().orElse(null) }?.fileName?.toString() ?: name
            }
            if (existing != null) {
                call.respondJson(HttpStatusCode.OK, UploadResponse(stableId, existing, size, sha256))
                return@handle
            }
            call.respondJson(HttpStatusCode.Created, UploadResponse(stableId, name, size, sha256))
        }
    }
}

private const val BUFFER_BYTES = 64 * 1024
