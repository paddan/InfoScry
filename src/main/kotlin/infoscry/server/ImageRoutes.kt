package infoscry.server

import infoscry.AppContext
import infoscry.domain.ContentUnitId
import infoscry.domain.Document
import infoscry.domain.DocumentId
import infoscry.domain.SourceLocation
import infoscry.image.ImageArtifacts
import infoscry.image.ImageSourceKind
import infoscry.image.analysis.ImageTag
import infoscry.image.analysis.ImageTagEditView
import infoscry.image.analysis.ImageTagsView
import infoscry.image.analysis.TagEditAction
import infoscry.storage.ImageStore
import infoscry.storage.RevisionState
import infoscry.storage.StoredImage
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.respondOutputStream
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Writes an image locator with its `type` discriminator, like every other locator the API sends, while the
 * property keeps the narrower type: an image's place is always an image region.
 */
internal object ImageRegionLocatorSerializer : KSerializer<SourceLocation.ImageRegion> {
    override val descriptor: SerialDescriptor = SourceLocation.serializer().descriptor

    override fun serialize(encoder: Encoder, value: SourceLocation.ImageRegion) =
        encoder.encodeSerializableValue(SourceLocation.serializer(), value)

    override fun deserialize(decoder: Decoder): SourceLocation.ImageRegion =
        decoder.decodeSerializableValue(SourceLocation.serializer()) as? SourceLocation.ImageRegion
            ?: throw IllegalArgumentException("an image's locator is an image region")
}

/** One image of a document's reading: where it sits, what it shows and how big it is. No path is ever part of it. */
@Serializable
data class ImageView(
    val unitId: String,
    @Serializable(with = ImageRegionLocatorSerializer::class)
    val locator: SourceLocation.ImageRegion,
    val label: String,
    val parentUnitId: String,
    val kind: String,
    val mediaType: String,
    val widthPx: Int,
    val heightPx: Int,
    val caption: String?,
    val captionSource: String?,
    /** The description the last completed analysis made, when the picture has been analysed. */
    val description: String? = null,
    /** The effective tags: the analysis with every person's edit applied. */
    val tags: List<ImageTag> = emptyList(),
    /** What an edit of this picture's tags is made from. */
    val tagsVersion: Long = 0,
    /** The code of the latest failed analysis, when the last attempt to describe the picture failed. */
    val analysisErrorCode: String? = null,
)

/** One edit of a picture's tags; the request id makes a retried POST change nothing. */
@Serializable
data class EditImageTagRequest(
    val editId: String,
    val action: TagEditAction,
    val tag: ImageTag,
)

/**
 * Reader-only routes for the images of a document's published (or given) revision. A unit id names an image
 * of that revision only; the byte routes resolve the stored file inside the document's own folders and answer
 * 404 for anything that resolves elsewhere, and SVG is never served.
 */
fun Routing.configureImageRoutes(context: AppContext) {
    route("/api/collections/{id}/documents/{documentId}/images") {
        get {
            call.handle {
                val (document, revisionId) = call.imageScope(context)
                val views = revisionId?.let { revision -> imageViews(context, document, revision) }.orEmpty()
                call.respondJson(HttpStatusCode.OK, views.map { it.first })
            }
        }
        get("/{unitId}") {
            call.handle {
                val (_, revisionId) = call.imageScope(context)
                val found = call.findImage(context, revisionId)
                call.respondJson(HttpStatusCode.OK, found.first)
            }
        }
        get("/{unitId}/file") {
            call.handle {
                val (document, revisionId) = call.imageScope(context)
                val image = call.findImage(context, revisionId).second
                if (image.usesDisplayCopy()) {
                    // A TIFF cannot be shown by a browser: its PNG display copy is served, the managed copy is not.
                    val copy = regularFileIn(
                        context.paths.artifactsDir(document.collectionId, document.id),
                        ImageArtifacts.displayCopyPath(image.sha256),
                    ) ?: throw missingImage()
                    return@handle call.serveImage(copy, ContentType.Image.PNG)
                }
                val mediaType = servableMediaType(image.mediaType) ?: throw missingImage()
                call.serveImage(imageFile(context, document, image), mediaType)
            }
        }
        get("/{unitId}/thumbnail") {
            call.handle {
                val (document, revisionId) = call.imageScope(context)
                val image = call.findImage(context, revisionId).second
                call.serveImage(thumbnailFile(context, document, image), THUMBNAIL_MEDIA_TYPE)
            }
        }
        get("/{unitId}/tags") {
            call.handle {
                val (document, _) = call.imageScope(context)
                val unitId = call.imageUnitId()
                call.respondJson(
                    HttpStatusCode.OK,
                    context.imageTagService.tags(document.collectionId, document.id, unitId),
                )
            }
        }
        post("/{unitId}/tags") {
            call.handle {
                val (document, _) = call.imageScope(context)
                val unitId = call.imageUnitId()
                // Changing a picture's tags changes what its document publishes, so it is an editor's act.
                call.requireCollectionRole(document.collectionId.value, infoscry.account.CollectionRole.EDITOR)
                val request = call.receiveJson<EditImageTagRequest>()
                if (request.editId.isBlank()) {
                    throw BadRequestException("a tag edit needs an id, so a retry cannot apply it twice")
                }
                // A tag edit is a shared mutation like any other: it waits for maintenance, refuses while
                // mutations are paused, and publishes through the revision protocol inside this permit.
                val view = context.mutations.withMutation {
                    context.collectionService.requireMutationsAllowed()
                    context.imageTagService.edit(
                        collectionId = document.collectionId,
                        documentId = document.id,
                        unitId = unitId,
                        editId = request.editId,
                        action = request.action,
                        tag = request.tag,
                        expectedVersion = call.ifMatchVersion(),
                    )
                }
                call.respondJson(HttpStatusCode.OK, view)
            }
        }
    }
}

private val THUMBNAIL_MEDIA_TYPE = ContentType.Image.JPEG

/** Whether the stored picture is served from a PNG display copy: a standalone picture a browser cannot show. */
private fun StoredImage.usesDisplayCopy(): Boolean =
    kind == ImageSourceKind.STANDALONE && ImageArtifacts.needsDisplayCopy(mediaType)

private fun missingImage() = NoSuchElementException("no such image exists in this document")

/** The document in the caller's collection and the revision whose images are asked for (null: it has none yet). */
private fun ApplicationCall.imageScope(context: AppContext): Pair<Document, String?> {
    val collection = context.collectionService.requireActiveByNameOrId(collectionId().value)
    val rawDocumentId = parameters["documentId"]?.takeIf(String::isNotBlank)
        ?: throw BadRequestException("a document needs an id")
    val document = context.documents.get(DocumentId(rawDocumentId))
        ?.takeIf { it.collectionId == collection.id }
        ?: throw NoSuchElementException("no document with id $rawDocumentId exists in this collection")
    val requested = request.queryParameters["revision"]
    if (requested == null) return document to context.revisions.activeRevisionId(document.id)
    // Only a published reading of this very document: a candidate, a withdrawn one or another document's
    // revision is answered like one that does not exist.
    val revision = context.revisions.revision(requested)
        ?.takeIf { it.documentId == document.id }
        ?.takeIf { it.state == RevisionState.PUBLISHED || it.state == RevisionState.SUPERSEDED }
        ?: throw NoSuchElementException("no such revision exists for this document")
    return document to revision.id
}

/** The revision's images with their metadata, in reading order: one query, however many images it holds. */
private fun imageViews(
    context: AppContext,
    document: Document,
    revisionId: String,
): List<Pair<ImageView, StoredImage>> =
    context.imageStore.locatedImagesOf(revisionId).map { it.toView(context, document) }

private fun ImageStore.LocatedImage.toView(context: AppContext, document: Document): Pair<ImageView, StoredImage> {
    val tags = context.imageTagService.tags(document.collectionId, document.id, image.unitId)
    return ImageView(
    unitId = image.unitId.value,
    locator = locator,
    label = locator.describe(),
    parentUnitId = image.parentUnitId.value,
    kind = image.kind.name,
    mediaType = if (image.usesDisplayCopy()) ContentType.Image.PNG.toString() else image.mediaType,
    widthPx = image.widthPx,
    heightPx = image.heightPx,
    caption = image.caption,
    captionSource = image.captionSource?.name,
    description = tags.description,
    tags = tags.tags,
    tagsVersion = tags.tagsVersion,
    analysisErrorCode = tags.analysisErrorCode,
) to image
}

/** The one image a request names, looked up directly rather than by walking the revision's images. */
private fun ApplicationCall.findImage(context: AppContext, revisionId: String?): Pair<ImageView, StoredImage> {
    val unitId = parameters["unitId"]?.takeIf(String::isNotBlank) ?: throw BadRequestException("an image needs an id")
    return revisionId?.let { revision ->
        context.imageStore.locatedImagesOf(revision, ContentUnitId(unitId)).firstOrNull()
            ?.let { located -> located.toView(context, context.documents.get(located.image.documentId) ?: return@let null) }
    } ?: throw missingImage()
}

/** The image id in the path, refused when absent. */
private fun ApplicationCall.imageUnitId(): ContentUnitId =
    parameters["unitId"]?.takeIf(String::isNotBlank)?.let(::ContentUnitId)
        ?: throw BadRequestException("an image needs an id")

/** The media type to send for stored [raw], or null when it is not a raster image that is safe to show inline. */
private fun servableMediaType(raw: String): ContentType? {
    val parsed = runCatching { ContentType.parse(raw) }.getOrNull() ?: return null
    if (!parsed.contentType.equals("image", ignoreCase = true)) return null
    if (parsed.contentSubtype.lowercase().startsWith("svg")) return null
    return ContentType(parsed.contentType, parsed.contentSubtype)
}

/**
 * The stored picture. A standalone picture's artifact is the document's managed copy, named by its file name;
 * every other image lives under the document's artifact folder.
 */
private fun imageFile(context: AppContext, document: Document, image: StoredImage): Path {
    if (image.kind == ImageSourceKind.STANDALONE) {
        val managed = context.library.managedPathOf(document)
        if (image.relativePath != managed.fileName.toString()) throw missingImage()
        return regularFileIn(managed.parent, image.relativePath) ?: throw missingImage()
    }
    return regularFileIn(context.paths.artifactsDir(document.collectionId, document.id), image.relativePath)
        ?: throw missingImage()
}

private fun thumbnailFile(context: AppContext, document: Document, image: StoredImage): Path =
    regularFileIn(context.paths.artifactsDir(document.collectionId, document.id), image.thumbnailRelativePath)
        ?: throw missingImage()

/** [relativePath] under [root] when it stays inside it, through links too, and names a regular file; else null. */
private fun regularFileIn(root: Path, relativePath: String): Path? {
    if (relativePath.isEmpty() || relativePath.any { it.isISOControl() }) return null
    return try {
        val resolved = root.resolve(relativePath).normalize()
        if (resolved == root || !resolved.startsWith(root)) return null
        if (!Files.isRegularFile(resolved, LinkOption.NOFOLLOW_LINKS)) return null
        if (!resolved.toRealPath().startsWith(root.toRealPath())) return null
        resolved
    } catch (_: InvalidPathException) {
        null
    } catch (_: java.io.IOException) {
        null
    }
}

private suspend fun ApplicationCall.serveImage(file: Path, mediaType: ContentType) {
    response.headers.append("X-Content-Type-Options", "nosniff")
    response.headers.append(HttpHeaders.ContentDisposition, "inline")
    response.headers.append(HttpHeaders.ContentLength, Files.size(file).toString())
    respondOutputStream(contentType = mediaType) {
        Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS).use { it.copyTo(this) }
    }
}
