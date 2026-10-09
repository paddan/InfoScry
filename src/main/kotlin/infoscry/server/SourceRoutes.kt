package infoscry.server

import infoscry.ocr.OcrQualityScorer
import infoscry.AppContext
import infoscry.domain.ContentUnitId
import infoscry.domain.DocumentId
import infoscry.domain.SourceLocation
import infoscry.storage.RevisionState
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respondOutputStream
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import java.nio.file.Files
import java.nio.file.LinkOption
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable

/** One bounded page of the exact extracted source unit named by a search hit or citation. */
@Serializable
data class SourceContentResponse(
    val id: String,
    val documentId: String,
    val ordinal: Int,
    val locator: SourceLocation,
    val text: String,
    val offset: Int,
    val totalChars: Int,
    val truncated: Boolean,
    /**
     * The revision the returned text was read from, or absent when the live reading was served.
     *
     * Absent means the caller did not name a revision, which is not the same as a citation whose own
     * revision is unknown: the former is a source read, the latter is evidence nobody can place.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val revisionId: String? = null,
    /**
     * A 0–100 heuristic for how clean the whole unit's text looks, absent when the unit holds no text. It
     * is scored over the full unit, not the returned slice, so every slice of one page shows the same value.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val qualityScore: Double? = null,
    /**
     * The unit before and after this one in the document, its 1-based place, and the unit count. They
     * describe the live reading only; a read pinned to a revision names none, because the pages that
     * revision held are not the ones the live document is browsed by.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val previousId: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val nextId: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val position: Int? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val unitCount: Int? = null,
)

/** Reader-only endpoints. Both identifiers are opaque IDs resolved inside the selected collection. */
fun Routing.configureSourceRoutes(context: AppContext) {
    route("/api/collections/{id}/sources/{sourceId}") {
        get {
            call.handle {
                val collection = context.collectionService.requireActiveByNameOrId(call.collectionId().value)
                val sourceId = call.parameters["sourceId"]?.takeIf(String::isNotBlank)
                    ?: throw BadRequestException("a source needs an id")
                val revisionId = call.request.queryParameters["revision"]?.takeIf(String::isNotBlank)
                val unitId = ContentUnitId(sourceId)
                // A citation that names its revision is read from that revision, so a saved excerpt keeps
                // showing the text it was taken from after the document has published another reading.
                // Reading it costs nothing from the index: the revision's text is SQLite's, which is what
                // keeps source access working when the embedding runtime does not initialize. The named
                // revision is resolved before the live unit is required, because the replacement that
                // superseded it may have removed the unit it names — and a named revision that holds no
                // such page is refused rather than answered with the current text, which would attribute
                // that text to a citation that never held it.
                val revisionPage = revisionId?.let { id ->
                    // Only a reading that was published may be read here. A candidate's text is a proposal no
                    // reader may see before it is approved and published, and a withdrawn one is text the
                    // stager gave up on: knowing the identifier must not be enough to reach either through the
                    // same route a citation uses. The refusal is the same not-found a foreign id gets, so it
                    // says nothing about what other revisions exist.
                    val state = context.revisions.revision(id)?.state
                    if (state != RevisionState.PUBLISHED && state != RevisionState.SUPERSEDED) {
                        throw NoSuchElementException("no source with id $sourceId exists in this collection")
                    }
                    context.revisions.pageForUnit(id, unitId)
                        ?: throw NoSuchElementException(
                            "no source with id $sourceId exists in revision $id",
                        )
                }
                // While a publication whose authority is durable cannot switch its snapshot, the live
                // reading SQLite holds and the rows the index still serves disagree, so the live source
                // read is refused with the same 503 a search gets (the seal the publication service
                // raises for that state). A request that named a revision is already answered above from
                // that revision's own immutable text, which is coherent whatever the index is doing.
                if (revisionPage == null) {
                    context.revisionSnapshots.requireUnsealed()
                }
                val unit = context.content.readUnit(unitId)
                val documentId = revisionPage?.let { context.revisions.revision(it.revisionId)?.documentId }
                    ?: unit?.documentId
                    ?: throw NoSuchElementException("no source with id $sourceId exists in this collection")
                val document = context.documents.get(documentId)
                    ?: throw NoSuchElementException("no source with id $sourceId exists in this collection")
                if (document.collectionId != collection.id) {
                    throw NoSuchElementException("no source with id $sourceId exists in this collection")
                }
                // One of the two readings exists here, which is what [documentId] was chosen from.
                val sourceText = revisionPage?.extractedText ?: unit!!.extractedText
                val sourceLocator = revisionPage?.locator ?: unit!!.locator
                val ordinal = revisionPage?.ordinal ?: unit!!.ordinal
                val (requestedOffset, limit) = call.sourceRange(sourceText.length)
                val offset = if (
                    requestedOffset in 1 until sourceText.length &&
                    Character.isHighSurrogate(sourceText[requestedOffset - 1]) &&
                    Character.isLowSurrogate(sourceText[requestedOffset])
                ) requestedOffset - 1 else requestedOffset
                val requestedEnd = minOf(sourceText.length.toLong(), offset.toLong() + limit).toInt()
                val splitsSurrogatePair = requestedEnd in 1 until sourceText.length &&
                    Character.isHighSurrogate(sourceText[requestedEnd - 1]) &&
                    Character.isLowSurrogate(sourceText[requestedEnd])
                val end = if (splitsSurrogatePair) {
                    if (requestedEnd - offset <= 1) requestedEnd + 1 else requestedEnd - 1
                } else requestedEnd
                val page = sourceText.substring(offset, end)
                val neighbours = if (revisionPage == null) context.content.neighboursOf(documentId, ordinal) else null
                call.respondJson(
                    HttpStatusCode.OK,
                    SourceContentResponse(
                        id = unitId.value,
                        documentId = documentId.value,
                        ordinal = ordinal,
                        locator = sourceLocator,
                        text = page,
                        offset = offset,
                        totalChars = sourceText.length,
                        truncated = end < sourceText.length,
                        revisionId = revisionPage?.revisionId,
                        qualityScore = OcrQualityScorer.score(sourceText),
                        previousId = neighbours?.previous?.value,
                        nextId = neighbours?.next?.value,
                        position = neighbours?.position,
                        unitCount = neighbours?.total,
                    ),
                )
            }
        }
    }

    route("/api/collections/{id}/documents/{documentId}/original") {
        get {
            call.handle {
                val collection = context.collectionService.requireActiveByNameOrId(call.collectionId().value)
                val rawDocumentId = call.parameters["documentId"]?.takeIf(String::isNotBlank)
                    ?: throw BadRequestException("a document needs an id")
                val document = context.documents.get(DocumentId(rawDocumentId))
                    ?.takeIf { it.collectionId == collection.id }
                    ?: throw NoSuchElementException("no document with id $rawDocumentId exists in this collection")
                val original = context.library.managedPathOf(document)
                if (!Files.isRegularFile(original, LinkOption.NOFOLLOW_LINKS)) {
                    throw NoSuchElementException("the managed original for document ${document.id.value} is unavailable")
                }
                val mediaType = runCatching { ContentType.parse(document.mediaType) }
                    .getOrDefault(ContentType.Application.OctetStream)
                val inline = mediaType.toString().lowercase() in INLINE_SAFE_MEDIA_TYPES
                val responseMediaType = if (inline) mediaType else ContentType.Application.OctetStream
                val extension = document.originalFilename.substringAfterLast('.', "")
                    .filter { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }
                    .take(MAX_ORIGINAL_EXTENSION_LENGTH)
                    .ifEmpty { "bin" }
                call.response.headers.append(
                    HttpHeaders.ContentDisposition,
                    "${if (inline) "inline" else "attachment"}; filename=\"original.$extension\"",
                )
                call.response.headers.append("X-Content-Type-Options", "nosniff")
                val size = Files.size(original)
                call.response.headers.append(HttpHeaders.AcceptRanges, "bytes")
                val rangeHeader = call.request.headers[HttpHeaders.Range]
                val byteRange = rangeHeader?.let { parseByteRange(it, size) }
                if (rangeHeader != null && byteRange == null) {
                    call.response.headers.append(HttpHeaders.ContentRange, "bytes */$size")
                    call.response.status(HttpStatusCode.RequestedRangeNotSatisfiable)
                    call.respondOutputStream(contentType = responseMediaType) { }
                    return@handle
                }
                val start = byteRange?.first ?: 0L
                val endInclusive = byteRange?.last ?: (size - 1)
                val length = if (size == 0L) 0L else endInclusive - start + 1
                val status = if (byteRange == null) HttpStatusCode.OK else HttpStatusCode.PartialContent
                if (byteRange != null) {
                    call.response.headers.append(HttpHeaders.ContentRange, "bytes $start-$endInclusive/$size")
                }
                call.response.headers.append(HttpHeaders.ContentLength, length.toString())
                call.respondOutputStream(contentType = responseMediaType, status = status) {
                    Files.newInputStream(original, LinkOption.NOFOLLOW_LINKS).use { input ->
                        input.skipNBytes(start)
                        val buffer = ByteArray(BYTE_RANGE_BUFFER_SIZE)
                        var remaining = length
                        while (remaining > 0) {
                            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                            if (read < 0) break
                            write(buffer, 0, read)
                            remaining -= read
                        }
                    }
                }
            }
        }
    }
}

private fun io.ktor.server.application.ApplicationCall.sourceRange(totalChars: Int): Pair<Int, Int> {
    val parameters = request.queryParameters
    val offset = parameters["offset"]?.let { raw ->
        raw.toIntOrNull()?.takeIf { it >= 0 }
            ?: throw BadRequestException("offset must be a whole number, zero or greater, was '$raw'")
    } ?: 0
    val limit = parameters["limit"]?.let { raw ->
        raw.toIntOrNull()?.takeIf { it in 1..MAX_SOURCE_PAGE_CHARS }
            ?: throw BadRequestException("limit must be a whole number from 1 to $MAX_SOURCE_PAGE_CHARS, was '$raw'")
    } ?: DEFAULT_SOURCE_PAGE_CHARS
    if (offset > totalChars) {
        throw BadRequestException("offset cannot be greater than the source length of $totalChars characters")
    }
    return offset to limit
}

private const val DEFAULT_SOURCE_PAGE_CHARS = 16_384
private const val MAX_SOURCE_PAGE_CHARS = 65_536
private const val BYTE_RANGE_BUFFER_SIZE = 8_192
private const val MAX_ORIGINAL_EXTENSION_LENGTH = 16
private val INLINE_SAFE_MEDIA_TYPES = setOf(
    "application/pdf",
    "image/bmp",
    "image/gif",
    "image/jpeg",
    "image/png",
    "image/tiff",
    "image/webp",
)

/** Parses one RFC-style byte range; invalid, multiple, and unsatisfiable ranges return null. */
private fun parseByteRange(header: String, size: Long): LongRange? {
    val match = SINGLE_BYTE_RANGE.matchEntire(header.trim()) ?: return null
    val rawStart = match.groupValues[1]
    val rawEnd = match.groupValues[2]
    if (rawStart.isEmpty()) {
        val suffixLength = rawEnd.toLongOrNull()?.takeIf { it > 0 } ?: return null
        if (size == 0L) return null
        val start = (size - suffixLength).coerceAtLeast(0L)
        return start..(size - 1)
    }
    val start = rawStart.toLongOrNull() ?: return null
    if (size == 0L || start >= size) return null
    val requestedEnd = rawEnd.takeIf(String::isNotEmpty)?.toLongOrNull() ?: if (rawEnd.isEmpty()) size - 1 else return null
    if (requestedEnd < start) return null
    return start..minOf(requestedEnd, size - 1)
}

private val SINGLE_BYTE_RANGE = Regex("bytes=(\\d*)-(\\d*)")
