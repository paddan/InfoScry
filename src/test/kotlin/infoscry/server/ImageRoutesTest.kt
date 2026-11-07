package infoscry.server

import infoscry.domain.ContentUnitId
import infoscry.domain.Document
import infoscry.domain.NormalizedBox
import infoscry.domain.SourceImageProvenance
import infoscry.domain.SourceImageRoot
import infoscry.domain.SourceLocation
import infoscry.image.ImageExtractionMode
import infoscry.image.ImageIdentity
import infoscry.image.ImageSourceKind
import infoscry.jobs.ImportJobPayload
import infoscry.storage.CaptionSource
import infoscry.storage.PageApproval
import infoscry.storage.StoredImage
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ImageRoutesTest {
    private lateinit var root: Path
    private lateinit var server: ApiTestServer
    private lateinit var fixture: PageReviewFixture
    private lateinit var document: Document
    private lateinit var outsiderToken: String

    private val first = ImageIdentity.ordinal(0, 1)!!
    private val second = ImageIdentity.ordinal(1, 1)!!

    @BeforeTest fun setUp() {
        root = Files.createTempDirectory("infoscry-image-routes")
        server = ApiTestServer(root)
        fixture = PageReviewFixture(server.context, root)
        document = fixture.pdf(count = 2)
        val outsider = TestAccounts.ensureUser(server.context, "outsider")
        outsiderToken = server.context.accounts.createToken(outsider.id, "tests", null).value
    }

    @AfterTest fun tearDown() { server.close(); root.toFile().deleteRecursively() }

    private fun artifactRoot(doc: Document = document): Path =
        server.context.paths.artifactsDir(doc.collectionId, doc.id)

    /** Writes a PNG and its JPEG thumbnail under the artifact root; answers the sha256 and the PNG bytes. */
    private fun writeArtifact(name: String, color: Int): Pair<String, ByteArray> {
        val picture = BufferedImage(8, 6, BufferedImage.TYPE_INT_RGB).apply { setRGB(0, 0, color) }
        val png = artifactRoot().resolve("images/$name.png")
        Files.createDirectories(png.parent)
        ImageIO.write(picture, "png", png.toFile())
        ImageIO.write(picture, "jpg", artifactRoot().resolve("images/$name.jpg").toFile())
        val bytes = Files.readAllBytes(png)
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } to bytes
    }

    private fun imageUnitId(doc: Document, ordinal: Int) = ContentUnitId("${doc.id.value}-$ordinal")

    private fun imagePage(ordinal: Int, parentPage: Int, sha: String, caption: String) =
        fixture.page(0, caption).copy(
            ordinal = ordinal,
            locator = SourceLocation.ImageRegion(SourceLocation.PdfPage(parentPage), 1, null),
            sourceImage = SourceImageProvenance(SourceImageRoot.ARTIFACTS, "images/x.png", sha, 8, 6, 1),
            approval = PageApproval.APPROVED,
        )

    private fun record(
        doc: Document, ordinal: Int, parent: Int, sha: String, name: String,
        mediaType: String = "image/png", relativePath: String = "images/$name.png",
        kind: ImageSourceKind = ImageSourceKind.EMBEDDED, thumbnail: String = "images/$name.jpg",
    ) = server.context.imageStore.record(
        StoredImage(
            documentId = doc.id, unitId = imageUnitId(doc, ordinal), sha256 = sha,
            parentUnitId = ContentUnitId("${doc.id.value}-$parent"), kind = kind,
            relativePath = relativePath, thumbnailRelativePath = thumbnail, mediaType = mediaType,
            widthPx = 8, heightPx = 6, box = NormalizedBox(0.1, 0.1, 0.5, 0.5),
            caption = "caption $name", captionSource = CaptionSource.ALT_TEXT,
        ),
    )

    private fun base(doc: Document = document) =
        "/api/collections/${doc.collectionId.value}/documents/${doc.id.value}/images"

    private suspend fun json(path: String): JsonObject? {
        val response = server.get(path)
        return if (response.status == HttpStatusCode.OK) Json.parseToJsonElement(response.bodyAsText()).jsonObject else null
    }

    private suspend fun asOutsider(path: String): HttpResponse = server.client.request(server.url + path) {
        method = HttpMethod.Get
        header(HttpHeaders.Authorization, "Bearer $outsiderToken")
    }

    private fun seedTwo(): ByteArray {
        val (shaA, bytesA) = writeArtifact("a", 0xff0000)
        val (shaB, _) = writeArtifact("b", 0x00ff00)
        fixture.publish(
            document,
            listOf(
                fixture.page(0, "one"), fixture.page(1, "two"),
                imagePage(second, 2, shaB, "second picture"), imagePage(first, 1, shaA, "first picture"),
            ),
        )
        record(document, first, 0, shaA, "a")
        record(document, second, 1, shaB, "b")
        return bytesA
    }

    @Test fun `a reader lists the published revision's images in ordinal order`() = runBlocking {
        seedTwo()
        val response = server.get(base())
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val list = Json.parseToJsonElement(response.bodyAsText()).jsonArray.map { it.jsonObject }
        assertEquals(
            listOf(imageUnitId(document, first).value, imageUnitId(document, second).value),
            list.map { it["unitId"]!!.jsonPrimitive.content },
        )
        val head = list[0]
        assertEquals("image_region", head["locator"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("image 1 on page 1", head["label"]!!.jsonPrimitive.content)
        assertEquals("EMBEDDED", head["kind"]!!.jsonPrimitive.content)
        assertEquals("image/png", head["mediaType"]!!.jsonPrimitive.content)
        assertEquals("caption a", head["caption"]!!.jsonPrimitive.content)
        assertEquals("ALT_TEXT", head["captionSource"]!!.jsonPrimitive.content)
        assertFalse(response.bodyAsText().contains("images/a.png"), "no path is ever returned")

        val one = json("${base()}/${imageUnitId(document, second).value}")!!
        assertEquals("caption b", one["caption"]!!.jsonPrimitive.content)
    }

    @Test fun `a document with no published revision lists no images`() = runBlocking {
        assertEquals("[]", server.get(base()).bodyAsText())
    }

    @Test fun `revision names an older revision's images and must belong to the document`() = runBlocking {
        seedTwo()
        val older = server.context.revisions.activeRevisionId(document.id)!!
        // A later reading publishes the text only.
        fixture.publish(document, listOf(fixture.page(0, "one again"), fixture.page(1, "two again")))
        assertEquals(0, Json.parseToJsonElement(server.get(base()).bodyAsText()).jsonArray.size)
        val pinned = Json.parseToJsonElement(server.get("${base()}?revision=$older").bodyAsText()).jsonArray
        assertEquals(2, pinned.size)

        val other = fixture.pdf(name = "other.pdf", count = 1)
        fixture.publish(other, listOf(fixture.page(0, "elsewhere")))
        val foreign = server.context.revisions.activeRevisionId(other.id)!!
        assertEquals(HttpStatusCode.NotFound, server.get("${base()}?revision=$foreign").status)
        assertEquals(HttpStatusCode.NotFound, server.get("${base()}?revision=nope").status)
    }

    @Test fun `a non-member gets 404 on all four routes`() = runBlocking {
        seedTwo()
        val unit = imageUnitId(document, first).value
        for (path in listOf(base(), "${base()}/$unit", "${base()}/$unit/file", "${base()}/$unit/thumbnail")) {
            assertEquals(HttpStatusCode.OK, server.get(path).status, path)
            assertEquals(HttpStatusCode.NotFound, asOutsider(path).status, path)
        }
    }

    @Test fun `an unknown unit is 404 on every unit route`() = runBlocking {
        seedTwo()
        for (suffix in listOf("", "/file", "/thumbnail")) {
            assertEquals(HttpStatusCode.NotFound, server.get("${base()}/${document.id.value}-nope$suffix").status, suffix)
        }
        // A unit of the document that is a text page is not an image.
        assertEquals(HttpStatusCode.NotFound, server.get("${base()}/${document.id.value}-0").status)
    }

    @Test fun `file answers the stored bytes inline with nosniff and the thumbnail is a JPEG`() = runBlocking {
        val bytes = seedTwo()
        val unit = imageUnitId(document, first).value
        val file = server.get("${base()}/$unit/file")
        assertEquals(HttpStatusCode.OK, file.status)
        assertEquals("image/png", file.headers[HttpHeaders.ContentType])
        assertEquals("nosniff", file.headers["X-Content-Type-Options"])
        assertEquals("inline", file.headers[HttpHeaders.ContentDisposition])
        assertContentEquals(bytes, file.bodyAsBytes())

        val thumbnail = server.get("${base()}/$unit/thumbnail")
        assertEquals(HttpStatusCode.OK, thumbnail.status)
        assertEquals("image/jpeg", thumbnail.headers[HttpHeaders.ContentType])
        assertEquals("nosniff", thumbnail.headers["X-Content-Type-Options"])
        assertEquals("inline", thumbnail.headers[HttpHeaders.ContentDisposition])
    }

    @Test fun `an artifact path escaping the document root answers 404 and never the file`() = runBlocking {
        val secret = root.resolve("secret.png")
        ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", secret.toFile())
        val (sha, _) = writeArtifact("a", 0xff0000)
        fixture.publish(document, listOf(fixture.page(0, "one"), imagePage(first, 1, sha, "forged")))
        val escape = "../".repeat(12) + secret.toString().removePrefix("/")
        record(document, first, 0, sha, "a", relativePath = escape, thumbnail = escape)
        val unit = imageUnitId(document, first).value
        assertEquals(HttpStatusCode.OK, server.get("${base()}/$unit").status, "the metadata row itself is served")
        assertEquals(HttpStatusCode.NotFound, server.get("${base()}/$unit/file").status)
        assertEquals(HttpStatusCode.NotFound, server.get("${base()}/$unit/thumbnail").status)

        // An absolute path and a link out of the root are refused the same way.
        record(document, first, 0, sha, "a", relativePath = secret.toString(), thumbnail = secret.toString())
        assertEquals(HttpStatusCode.NotFound, server.get("${base()}/$unit/file").status)
        val link = artifactRoot().resolve("images/link.png")
        Files.createSymbolicLink(link, secret)
        record(document, first, 0, sha, "a", relativePath = "images/link.png", thumbnail = "images/link.png")
        assertEquals(HttpStatusCode.NotFound, server.get("${base()}/$unit/file").status)
        assertEquals(HttpStatusCode.NotFound, server.get("${base()}/$unit/thumbnail").status)
    }

    @Test fun `svg is never served`() = runBlocking {
        val (sha, _) = writeArtifact("a", 0xff0000)
        fixture.publish(document, listOf(fixture.page(0, "one"), imagePage(first, 1, sha, "svg")))
        record(document, first, 0, sha, "a", mediaType = "image/svg+xml")
        val unit = imageUnitId(document, first).value
        assertEquals(HttpStatusCode.NotFound, server.get("${base()}/$unit/file").status)
    }

    @Test fun `a standalone picture is served from the managed copy and nothing else`() = runBlocking {
        val tiff = fixture.tiff("scan.tiff")
        val managed = server.context.library.managedPathOf(tiff)
        val sha = tiff.sha256
        val unit = imageUnitId(tiff, first)
        val parent = SourceLocation.Image("scan.tiff")
        fixture.publish(
            tiff,
            listOf(
                fixture.page(0, "scan").copy(locator = parent),
                imagePage(first, 1, sha, "the picture").copy(locator = SourceLocation.ImageRegion(parent, 1, null)),
            ),
        )
        record(tiff, first, 0, sha, "none", mediaType = "image/tiff", relativePath = managed.fileName.toString(),
            kind = ImageSourceKind.STANDALONE, thumbnail = "images/none.jpg")
        val path = "${base(tiff)}/${unit.value}/file"
        val ok = server.get(path)
        assertEquals(HttpStatusCode.OK, ok.status, ok.bodyAsText())
        assertContentEquals(Files.readAllBytes(managed), ok.bodyAsBytes())

        // A standalone row naming any other file than the managed copy is refused.
        record(tiff, first, 0, sha, "none", mediaType = "image/tiff", relativePath = "../scan.tiff",
            kind = ImageSourceKind.STANDALONE, thumbnail = "images/none.jpg")
        assertEquals(HttpStatusCode.NotFound, server.get(path).status)
    }

    // ---- import start and collection settings ----

    private suspend fun startImport(requestId: String, images: String?, upload: String, previewHash: String): HttpResponse {
        val body = ApiJson.encodeToString(
            ImportRequest(
                collection = document.collectionId.value, uploads = listOf(upload), method = "none",
                previewHash = previewHash, requestId = requestId, imageExtraction = images,
            ),
        )
        return server.request(HttpMethod.Post, "/api/imports", body, Credential.BEARER)
    }

    @Test fun `the import start route accepts imageExtraction and a different value is a different request`() = runBlocking {
        val file = Files.writeString(root.resolve("note.txt"), "Some words.\n")
        val collection = document.collectionId.value
        val upload = server.upload(collection, file)
        val preview = ApiJson.decodeFromString<infoscry.jobs.ImportPreview>(
            server.request(
                HttpMethod.Post, "/api/imports/preview",
                ApiJson.encodeToString(ImportPreviewRouteRequest(collection = collection, uploads = listOf(upload), method = "none")),
                Credential.BEARER,
            ).bodyAsText(),
        )

        val bogus = startImport("req-bogus", "bogus", upload, preview.previewHash)
        assertEquals(HttpStatusCode.BadRequest, bogus.status, bogus.bodyAsText())

        val all = startImport("req-1", "all", upload, preview.previewHash)
        assertEquals(HttpStatusCode.Accepted, all.status, all.bodyAsText())
        val job = ApiJson.decodeFromString<ImportAcceptedResponse>(all.bodyAsText()).job
        val payload = ImportJobPayload.decode(server.context.jobs.get(job.id)!!.payload!!)
        assertEquals(ImageExtractionMode.ALL, payload.ocr!!.imageExtraction)

        val again = startImport("req-1", "all", upload, preview.previewHash)
        assertEquals(job.id, ApiJson.decodeFromString<ImportAcceptedResponse>(again.bodyAsText()).job.id, "the same request replays")
        val other = startImport("req-1", "embedded", upload, preview.previewHash)
        assertEquals(HttpStatusCode.Conflict, other.status, "another value is not the same request: ${other.bodyAsText()}")
    }

    @Test fun `the collection default is saved with If-Match and a stale version conflicts`() = runBlocking {
        val path = "/api/collections/${document.collectionId.value}/ocr-languages"
        suspend fun patch(body: String, ifMatch: String?) = server.client.request(server.url + path) {
            method = HttpMethod.Patch
            header(HttpHeaders.Authorization, "Bearer ${server.bearer}")
            if (ifMatch != null) header(HttpHeaders.IfMatch, ifMatch)
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        val before = server.context.collectionService.requireActiveByNameOrId(document.collectionId.value).version
        val saved = patch("""{"defaultImageExtraction":"embedded"}""", "\"$before\"")
        assertEquals(HttpStatusCode.OK, saved.status, saved.bodyAsText())
        val collection = ApiJson.decodeFromString<CollectionResponse>(saved.bodyAsText()).collection
        assertEquals(ImageExtractionMode.EMBEDDED, collection.defaultImageExtraction)

        val stale = patch("""{"defaultImageExtraction":"all"}""", "\"$before\"")
        assertEquals(HttpStatusCode.Conflict, stale.status)
        assert(stale.bodyAsText().contains("EDIT_CONFLICT")) { stale.bodyAsText() }
        assertEquals(HttpStatusCode.BadRequest, patch("""{"defaultImageExtraction":"bogus"}""", null).status)

        // Repeating the saved value is a no-op even with the old version, and language rides in the same edit.
        assertEquals(HttpStatusCode.OK, patch("""{"defaultImageExtraction":"embedded"}""", "\"$before\"").status)
        val both = patch("""{"language":"eng","defaultImageExtraction":"all"}""", "\"${collection.version}\"")
        assertEquals(HttpStatusCode.OK, both.status, both.bodyAsText())
        assertEquals(ImageExtractionMode.ALL, ApiJson.decodeFromString<CollectionResponse>(both.bodyAsText()).collection.defaultImageExtraction)
    }
}
