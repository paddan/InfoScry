package infoscry.server

import infoscry.AppContext
import infoscry.domain.CollectionId
import infoscry.domain.DocumentId
import infoscry.pdf.CreateSelectablePdfRequest
import infoscry.pdf.PdfArtifactState
import infoscry.pdf.PdfArtifactStatus
import infoscry.pdf.SelectablePdfService
import infoscry.storage.RevisionState
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.utils.io.writeFully
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The selectable-PDF surface of one document's revision (ticket 07 of the selectable-PDF plan).
 *
 * Three thin routes over [SelectablePdfService], which owns every decision: what a status is, whether an
 * artifact may be built, and which file is verified. This file only parses the request, checks that the named
 * revision is one a reader may ever see, and renders the answer with the status code and headers the contract
 * names. A GET never generates or enqueues anything; only the POST admits work, through the shared mutation gate
 * and the same CSRF/bearer guard every other mutation uses.
 */
fun Routing.configureSelectablePdfRoutes(context: AppContext) {
    route("/api/collections/{id}/documents/{documentId}/selectable-pdf") {
        get {
            call.selectablePdfHandle {
                val scope = call.selectablePdfScope(context)
                val revisionId = call.selectablePdfRevision()
                val status = withContext(Dispatchers.IO) {
                    requirePublishedRevision(context, scope, revisionId)
                    context.selectablePdfService.status(scope.collectionId, scope.documentId, revisionId)
                }
                call.respondStatus(status)
            }
        }

        // Explicit creation or retry. Admission goes through the same mutation gate as every other write, and the
        // service answers with the current identity, so a repeated POST returns what the first one started.
        post {
            call.selectablePdfHandle {
                val scope = call.selectablePdfScope(context)
                val body = call.receiveSelectablePdfCreateBody()
                val request = CreateSelectablePdfRequest(body.revisionId, body.retry)
                val status = context.mutations.withMutation {
                    context.collectionService.requireMutationsAllowed()
                    withContext(Dispatchers.IO) {
                        requirePublishedRevision(context, scope, request.revisionId)
                        context.selectablePdfService.ensure(scope.collectionId, scope.documentId, request.revisionId, request.retry)
                    }
                }
                call.respondStatus(status)
            }
        }

        get("/file") {
            call.selectablePdfHandle {
                val scope = call.selectablePdfScope(context)
                val revisionId = call.selectablePdfRevision()
                val key = call.selectablePdfKey()
                val inline = call.selectablePdfInline()
                val served = withContext(Dispatchers.IO) {
                    requirePublishedRevision(context, scope, revisionId)
                    servableFile(context, scope, revisionId, key)
                }
                call.respondPdf(served, inline)
            }
        }
    }
}

/** The collection and document a request is scoped to, both resolved inside the active collection. */
private class SelectablePdfScope(val collectionId: CollectionId, val documentId: DocumentId)

/** The wire body of a create or retry, already checked by [receiveSelectablePdfCreateBody]. */
private class SelectablePdfCreateBody(val revisionId: String, val retry: Boolean)

/**
 * Reads the create body with the strict types the contract names: `revisionId` a JSON string, `retry` a JSON
 * boolean. Both are required, a missing field is refused rather than defaulted, and a quoted `"true"` is a
 * type error, not a boolean. The shared decoder would accept the quoted form, so this reads the JSON itself.
 */
private suspend fun ApplicationCall.receiveSelectablePdfCreateBody(): SelectablePdfCreateBody {
    val text = receiveText()
    val root = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject
        ?: throw BadRequestException("the request body must be a JSON object with revisionId and retry")
    val revision = root["revisionId"] as? JsonPrimitive
    if (revision == null || !revision.isString) throw BadRequestException("revisionId must be a string")
    val retry = root["retry"] as? JsonPrimitive
    if (retry == null || retry.isString || (retry.content != "true" && retry.content != "false")) {
        throw BadRequestException("retry must be true or false")
    }
    return SelectablePdfCreateBody(revision.content, retry.content == "true")
}

/** A named artifact whose current state cannot give its file: answered with a 409 carrying only [code]. */
private class SelectablePdfConflict(val code: String, message: String) : IllegalStateException(message)

/** One request's status codec: every documented field is present, with an explicit null where a field has none. */
private val SelectablePdfJson = Json(ApiJson) { explicitNulls = true }

private const val SELECTABLE_PDF_NOT_READY_CODE = "SELECTABLE_PDF_NOT_READY"

private val REVISION_PATTERN = Regex("[A-Za-z0-9_-]{1,128}")
private val KEY_PATTERN = Regex("[0-9a-f]{64}")

private const val MAX_DOWNLOAD_STEM_CHARS = 120

private suspend fun ApplicationCall.selectablePdfScope(context: AppContext): SelectablePdfScope {
    val collection = context.collectionService.requireActiveByNameOrId(collectionId().value)
    val documentId = parameters["documentId"]?.takeIf(String::isNotBlank)
        ?: throw BadRequestException("a document id is required in the path")
    return SelectablePdfScope(collection.id, DocumentId(documentId))
}

private fun ApplicationCall.selectablePdfRevision(): String {
    val raw = request.queryParameters["revision"] ?: throw BadRequestException("a revision is required")
    if (!REVISION_PATTERN.matches(raw)) throw BadRequestException("revision must be a revision identifier")
    return raw
}

private fun ApplicationCall.selectablePdfKey(): String {
    val raw = request.queryParameters["key"] ?: throw BadRequestException("a key is required")
    if (!KEY_PATTERN.matches(raw)) throw BadRequestException("key must be a lowercase SHA-256 hex digest")
    return raw
}

private fun ApplicationCall.selectablePdfInline(): Boolean = when (val raw = request.queryParameters["inline"]) {
    null, "false" -> false
    "true" -> true
    else -> throw BadRequestException("inline must be true or false, was '$raw'")
}

/**
 * A revision a reader may ever see: published now, or published and since replaced. A candidate that was never
 * published, a withdrawn one, a revision of another document, and an unknown id are all the same not-found, so
 * the answer confirms nothing about what else exists. The service repeats the document's collection scope.
 */
private fun requirePublishedRevision(context: AppContext, scope: SelectablePdfScope, revisionId: String) {
    val revision = context.revisions.revision(revisionId)?.takeIf { it.documentId == scope.documentId }
    val published = revision != null &&
        (revision.state == RevisionState.PUBLISHED || revision.state == RevisionState.SUPERSEDED)
    if (!published) throw NoSuchElementException("no published revision with id $revisionId exists for this document")
}

/** A file ready to answer: its path, its hash as verified, its size and the document name it downloads as. */
private class ServableFile(val path: Path, val sha256: String, val size: Long, val documentName: String)

/**
 * The verified file for exactly the artifact the request names, or the safe conflict that says why not.
 *
 * The status is read first so that a different key, a revision with no artifact and a foreign identity are
 * all not-found, while a named artifact that is not ready, failed or corrupt is a conflict. The service then
 * hashes the file again, and this route refuses anything reached through a symbolic link.
 */
private fun servableFile(context: AppContext, scope: SelectablePdfScope, revisionId: String, key: String): ServableFile {
    val status = context.selectablePdfService.status(scope.collectionId, scope.documentId, revisionId)
    // The key is compared with the stored artifact's own key, not with the status: a verification failure
    // reports FAILED without its key (ticket 06's status), but the stored key still names the artifact that
    // failed, so its own key must still get the conflict rather than not-found.
    val storedKey = context.selectablePdfStore.get(scope.documentId, revisionId)?.key
    if (storedKey == null || storedKey != key) {
        throw NoSuchElementException("no selectable PDF with that key exists for this revision")
    }
    when (status.state) {
        PdfArtifactState.READY, PdfArtifactState.PARTIAL -> Unit
        PdfArtifactState.QUEUED, PdfArtifactState.BUILDING -> throw SelectablePdfConflict(
            SELECTABLE_PDF_NOT_READY_CODE,
            "the selectable PDF for this revision is not ready yet; check its status again",
        )
        PdfArtifactState.FAILED -> throw SelectablePdfConflict(
            status.reasonCode ?: SelectablePdfService.VERIFICATION_FAILED_CODE,
            "the selectable PDF for this revision failed to build; retry its generation",
        )
        PdfArtifactState.NOT_CREATED -> throw NoSuchElementException("no selectable PDF exists for this revision")
    }
    val path = try {
        context.selectablePdfService.file(scope.collectionId, scope.documentId, revisionId, key)
    } catch (missing: NoSuchElementException) {
        throw SelectablePdfConflict(
            SelectablePdfService.VERIFICATION_FAILED_CODE,
            "the selectable PDF file failed verification; retry its generation",
        )
    } catch (changed: IllegalArgumentException) {
        // The service refuses a state or key that changed between the status read and this call.
        throw SelectablePdfConflict(SELECTABLE_PDF_NOT_READY_CODE, "the selectable PDF for this revision changed; check its status again")
    }
    if (!isConfined(path, context.paths.root)) {
        throw SelectablePdfConflict(
            SelectablePdfService.VERIFICATION_FAILED_CODE,
            "the selectable PDF file failed verification; retry its generation",
        )
    }
    val document = context.documents.get(scope.documentId)
        ?.takeIf { it.collectionId == scope.collectionId }
        ?: throw NoSuchElementException("no document exists in this collection")
    return ServableFile(path, sha256Hex(path), Files.size(path), document.originalFilename)
}

/**
 * Whether [file] is a regular file reached without any symbolic link between it and the data root.
 *
 * The data root itself may sit behind a link on this machine, so the walk stops there rather than checking it.
 * Anything under the root that is a link, or any path that climbs out of it, is refused.
 */
private fun isConfined(file: Path, root: Path): Boolean {
    val base = root.toAbsolutePath().normalize()
    var current: Path? = file.toAbsolutePath().normalize()
    while (current != null && current != base) {
        if (!current.startsWith(base) || Files.isSymbolicLink(current)) return false
        current = current.parent
    }
    return current == base && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
}

private suspend fun ApplicationCall.respondStatus(status: PdfArtifactStatus) {
    respondText(SelectablePdfJson.encodeToString(PdfArtifactStatus.serializer(), status), ContentType.Application.Json, HttpStatusCode.OK)
}

private suspend fun ApplicationCall.respondPdf(served: ServableFile, inline: Boolean) {
    response.headers.append(HttpHeaders.ETag, "\"${served.sha256}\"")
    response.headers.append(HttpHeaders.CacheControl, "private, no-cache")
    response.headers.append(HttpHeaders.ContentDisposition, selectablePdfContentDisposition(inline, served.documentName))
    response.headers.append("X-Content-Type-Options", "nosniff")
    // The length is passed to the writer rather than set as a header: an output stream of unknown length is sent
    // chunked, and the exact Content-Length a client can rely on is only written this way.
    respondBytesWriter(ContentType.Application.Pdf, HttpStatusCode.OK, served.size) {
        Files.newInputStream(served.path, LinkOption.NOFOLLOW_LINKS).use { input ->
            val buffer = ByteArray(SHA_BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                writeFully(buffer, 0, read)
            }
        }
    }
}

/**
 * The `Content-Disposition` for a downloaded artifact, built only from characters that cannot end a header.
 *
 * The name is the document's own name, cut to its stem with control characters and quotes removed, plus
 * `-selectable.pdf`. An ASCII `filename` carries a safe fallback, and `filename*` carries the exact UTF-8 name
 * percent-encoded per RFC 5987, so a crafted name can never add a header or a quoted parameter.
 */
internal fun selectablePdfContentDisposition(inline: Boolean, originalFilename: String): String {
    val disposition = if (inline) "inline" else "attachment"
    val stem = originalFilename.substringBeforeLast('.', originalFilename)
        .filterNot { it.isISOControl() || it == '/' || it == '\\' || it == '"' }
        .trim()
        .take(MAX_DOWNLOAD_STEM_CHARS)
        .ifEmpty { "document" }
    val name = "$stem-selectable.pdf"
    val ascii = name.map { c -> if (c in ' '..'~' && c != '"' && c != '\\' && c != ';') c else '_' }.joinToString("")
    return "$disposition; filename=\"$ascii\"; filename*=UTF-8''${rfc5987Encode(name)}"
}

private fun rfc5987Encode(value: String): String = buildString {
    value.toByteArray(Charsets.UTF_8).forEach { byte ->
        val code = byte.toInt() and 0xFF
        val c = code.toChar()
        if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in "!#\$&+-.^_`|~") {
            append(c)
        } else {
            append('%').append("%02X".format(code))
        }
    }
}

private fun sha256Hex(file: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS).use { input ->
        val buffer = ByteArray(SHA_BUFFER_BYTES)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private const val SHA_BUFFER_BYTES = 64 * 1024

/**
 * Renders a handler's outcome, with the one conflict this surface names that [handle] does not already know.
 *
 * Everything else (a missing identity, a malformed field, a maintenance refusal, a deleting document) reaches
 * [handle], which owns the status codes the rest of the API uses.
 */
private suspend fun ApplicationCall.selectablePdfHandle(block: suspend () -> Unit) = handle {
    try {
        block()
    } catch (conflict: SelectablePdfConflict) {
        respondJson(
            HttpStatusCode.Conflict,
            ApiErrorResponse(ApiError(code = conflict.code, message = conflict.message.orEmpty())),
        )
    }
}
