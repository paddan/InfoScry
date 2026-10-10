package infoscry.server

import infoscry.AppContext
import infoscry.domain.CollectionId
import infoscry.domain.ContentUnitId
import infoscry.domain.Document
import infoscry.domain.ExtractionMethod
import infoscry.domain.SourceImageProvenance
import infoscry.domain.SourceImageRoot
import infoscry.domain.SourceLocation
import infoscry.ocr.OcrEngine
import infoscry.ocr.OcrImportMode
import infoscry.ocr.OcrSettingsSnapshot
import infoscry.storage.PageApproval
import infoscry.storage.RevisionPageDraft
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpStatusCode
import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import javax.imageio.ImageIO
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle

class PageReviewRoutesTest {
    private lateinit var root: Path
    private lateinit var server: ApiTestServer
    private lateinit var fixture: PageReviewFixture

    @BeforeTest fun setUp() {
        root = Files.createTempDirectory("infoscry-page-review-routes")
        server = ApiTestServer(root)
        fixture = PageReviewFixture(server.context, root)
    }
    @AfterTest fun tearDown() { server.close(); root.toFile().deleteRecursively() }

    @Test fun `physical page two pairs its text with its own original pixels including empty page navigation`() = runBlocking {
        val document = fixture.pdf()
        val revision = fixture.publish(document, listOf(fixture.page(0, "page one"), fixture.page(1, "page two")))
        repeat(2) {
            val metadata = metadata(document, "ordinal=1")
            assertEquals("page two", metadata["text"]!!.jsonPrimitive.content)
            assertEquals(3, metadata["documentPages"]!!.jsonPrimitive.int)
            assertEquals(0, metadata["previousOrdinal"]!!.jsonPrimitive.int)
            assertEquals(2, metadata["nextOrdinal"]!!.jsonPrimitive.int)
            assertEquals(revision, metadata["revisionId"]!!.jsonPrimitive.content)
            assertEquals("original", metadata["imageKind"]!!.jsonPrimitive.content)
            val image = server.get(path(document) + "/image?ordinal=1&revision=$revision&kind=original")
            assertEquals(HttpStatusCode.OK, image.status)
            val pixels = ImageIO.read(image.readRawBytes().inputStream())
            assertEquals(Color.GREEN.rgb, pixels.getRGB(pixels.width / 2, pixels.height / 2))
        }
        val empty = metadata(document, "ordinal=2")
        assertEquals(JsonNull, empty["unitId"])
        assertEquals("", empty["text"]!!.jsonPrimitive.content)
        assertEquals(3, empty["documentPages"]!!.jsonPrimitive.int)
    }

    @Test fun `an exact published revision survives publication and refuses candidate and foreign revisions`() = runBlocking {
        val document = fixture.pdf()
        val old = fixture.publish(document, listOf(fixture.page(1, "old reading")))
        val pinned = metadata(document, "ordinal=1")["revisionId"]!!.jsonPrimitive.content
        fixture.publish(document, listOf(fixture.page(1, "new reading")))
        assertEquals("old reading", metadata(document, "ordinal=1&revision=$pinned")["text"]!!.jsonPrimitive.content)
        assertEquals(HttpStatusCode.OK, server.get(path(document) + "/image?ordinal=1&revision=$old&kind=original").status)
        val candidate = server.context.revisions.openCandidate(document.id, null, "test")
        assertEquals(HttpStatusCode.NotFound, server.get(path(document) + "?ordinal=1&revision=$candidate").status)
        val other = fixture.pdf("other.pdf", 2)
        val foreign = fixture.publish(other, listOf(fixture.page(0, "foreign")))
        assertEquals(HttpStatusCode.NotFound, server.get(path(document) + "?ordinal=0&revision=$foreign").status)
        assertEquals(HttpStatusCode.NotFound, server.get(path(document, "foreign") + "?ordinal=0").status)
        assertEquals(HttpStatusCode.BadRequest, server.get(path(document) + "/image?ordinal=1&kind=original").status)
    }

    @Test fun `preserved OCR provenance and its own method survive a differently configured publication`() = runBlocking {
        val document = fixture.pdf()
        val artifact = fixture.ocrImage(document)
        val page = fixture.page(1, "OCR page", ExtractionMethod.OCR, artifact)
        val origin = fixture.publish(document, listOf(page), OcrEngine.TESSERACT)
        val current = fixture.publish(document, listOf(page.copy(producerRevisionId = origin), fixture.page(0, "changed")), OcrEngine.SURYA)
        val metadata = metadata(document, "ordinal=1")
        assertEquals("Tesseract", metadata["readingMethod"]!!.jsonPrimitive.content)
        assertEquals("ocr", metadata["imageKind"]!!.jsonPrimitive.content)
        val imagePath = path(document) + "/image?ordinal=1&revision=$current&kind=ocr"
        repeat(2) { assertEquals(HttpStatusCode.OK, server.get(imagePath).status) }
        Files.delete(server.context.paths.artifactsDir(document.collectionId, document.id).resolve(artifact.relativePath))
        assertEquals("OCR page", metadata(document, "ordinal=1")["text"]!!.jsonPrimitive.content)
        assertEquals(HttpStatusCode.NotFound, server.get(imagePath).status)
    }

    @Test fun `bounded Unicode paging works without embedding and validates bounds`() = runBlocking {
        val document = fixture.pdf()
        fixture.publish(document, listOf(fixture.page(0, "A😀B")))
        val slice = metadata(document, "ordinal=0&offset=2&limit=1")
        assertEquals("😀", slice["text"]!!.jsonPrimitive.content)
        assertEquals(1, slice["offset"]!!.jsonPrimitive.int)
        assertTrue(slice["truncated"]!!.jsonPrimitive.boolean)
        for (query in listOf("ordinal=-1", "ordinal=3", "ordinal=no", "ordinal=0&limit=65537", "ordinal=0&offset=9")) {
            assertEquals(HttpStatusCode.BadRequest, server.get(path(document) + "?$query").status, query)
        }
    }

    @Test fun `a document without published text has nullable identity and original image access`() = runBlocking {
        val document = fixture.pdf()
        val metadata = metadata(document, "ordinal=2")
        assertEquals(JsonNull, metadata["revisionId"])
        assertEquals(JsonNull, metadata["unitId"])
        assertEquals("", metadata["text"]!!.jsonPrimitive.content)
        assertEquals("original", metadata["imageKind"]!!.jsonPrimitive.content)
        assertEquals(HttpStatusCode.OK, server.get(path(document) + "/image?ordinal=2&kind=original").status)
    }

    @Test fun `TIFF originals and recorded OCR images are returned as bounded decodable PNGs`() = runBlocking {
        val originalDocument = fixture.tiff("original.tiff")
        val original = server.get(path(originalDocument) + "/image?ordinal=0&kind=original")
        assertEquals(HttpStatusCode.OK, original.status)
        assertEquals("image/png", original.headers["Content-Type"]?.substringBefore(';'))
        val originalPixels = ImageIO.read(original.readRawBytes().inputStream())
        assertEquals(5, originalPixels.width)
        assertEquals(7, originalPixels.height)
        assertEquals(Color.MAGENTA.rgb, originalPixels.getRGB(2, 3))

        val document = fixture.pdf("ocr.tif")
        val artifact = fixture.tiffArtifact(document, "ocr/page-0.tiff")
        val revision = fixture.publish(document, listOf(fixture.page(0, "tiff OCR", ExtractionMethod.OCR, artifact)))
        val response = server.get(path(document) + "/image?ordinal=0&revision=$revision&kind=ocr")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("image/png", response.headers["Content-Type"]?.substringBefore(';'))
        val ocrPixels = ImageIO.read(response.readRawBytes().inputStream())
        assertEquals(5, ocrPixels.width)
        assertEquals(7, ocrPixels.height)
        assertEquals(Color.MAGENTA.rgb, ocrPixels.getRGB(2, 3))
    }

    @Test fun `missing changed and symlink originals return curated failures without source paths`() = runBlocking {
        for (failure in listOf("missing", "changed", "symlink")) {
            val document = fixture.pdf("$failure.pdf")
            val original = server.context.library.managedPathOf(document)
            when (failure) {
                "missing" -> Files.delete(original)
                "changed" -> Files.writeString(original, "private source content")
                else -> { Files.delete(original); Files.createSymbolicLink(original, root.resolve("sources/$failure.pdf")) }
            }
            val response = server.get(path(document) + "/image?ordinal=0&kind=original")
            assertTrue(response.status in listOf(HttpStatusCode.NotFound, HttpStatusCode.Conflict), response.status.toString())
            assertFalse(response.bodyAsText().contains("no such API route"), "an unavailable managed copy needs a curated resource failure")
            val body = response.bodyAsText()
            assertFalse(body.contains(root.toString()))
            assertFalse(body.contains("private source content"))
        }
    }

    @Test fun `OCR artifact parent symlink cannot escape document confinement`() = runBlocking {
        val document = fixture.pdf()
        val source = fixture.ocrImage(document, "nested/ocr.png")
        val revision = fixture.publish(document, listOf(fixture.page(0, "private text", ExtractionMethod.OCR, source)))
        val nested = server.context.paths.artifactsDir(document.collectionId, document.id).resolve("nested")
        val outside = root.resolve("outside")
        Files.move(nested, outside)
        Files.createSymbolicLink(nested, outside)
        val response = server.get(path(document) + "/image?ordinal=0&revision=$revision&kind=ocr")
        assertEquals(HttpStatusCode.NotFound, response.status)
        assertContains(response.bodyAsText(), "PAGE_REVIEW_IMAGE_UNAVAILABLE")
        assertFalse(response.bodyAsText().contains(root.toString()))
    }

    @Test fun `oversized original raster is refused before allocation`() = runBlocking {
        val document = fixture.pdf("huge.pdf", huge = true)
        val response = server.get(path(document) + "/image?ordinal=0&kind=original")
        assertEquals(HttpStatusCode.Conflict, response.status, response.bodyAsText())
        assertContains(response.bodyAsText(), "PAGE_RASTER_UNBOUNDED")
    }

    @Test fun `missing or changed originals leave exact revision text and saved OCR pixels available with honest unknown totals`() = runBlocking {
        for (failure in listOf("missing-independent", "changed-independent")) {
            val document = fixture.pdf("$failure.pdf")
            val artifact = fixture.ocrImage(document)
            val revision = fixture.publish(document, listOf(
                fixture.page(0, "first recorded", ExtractionMethod.OCR, artifact),
                fixture.page(2, "third recorded", ExtractionMethod.OCR, artifact),
            ))
            val original = server.context.library.managedPathOf(document)
            if (failure.startsWith("missing")) Files.delete(original) else Files.writeString(original, "changed bytes")
            val first = metadata(document, "ordinal=0&revision=$revision")
            assertEquals("first recorded", first["text"]!!.jsonPrimitive.content)
            assertEquals(JsonNull, first["documentPages"])
            assertEquals(JsonNull, first["previousOrdinal"])
            assertEquals(2, first["nextOrdinal"]!!.jsonPrimitive.int)
            val third = metadata(document, "ordinal=2&revision=$revision")
            assertEquals(0, third["previousOrdinal"]!!.jsonPrimitive.int)
            assertEquals(JsonNull, third["nextOrdinal"])
            assertEquals(HttpStatusCode.NotFound, server.get(path(document) + "?ordinal=1&revision=$revision").status)
            val saved = server.get(path(document) + "/image?ordinal=0&revision=$revision&kind=ocr")
            assertEquals(HttpStatusCode.OK, saved.status, saved.bodyAsText())
            val originalImage = server.get(path(document) + "/image?ordinal=0&revision=$revision&kind=original")
            assertTrue(originalImage.status in listOf(HttpStatusCode.NotFound, HttpStatusCode.Conflict))
        }
    }

    private fun path(document: Document, collection: String = document.collectionId.value) =
        "/api/collections/$collection/documents/${document.id.value}/page-review"
    private suspend fun metadata(document: Document, query: String): JsonObject {
        val response = server.get(path(document) + "?$query")
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return Json.parseToJsonElement(response.bodyAsText()).jsonObject
    }
}

/** Generated source bytes and real SQLite/publication fixtures, without providers or an embedder. */
internal class PageReviewFixture(val context: AppContext, private val root: Path) {
    fun pdf(name: String = "pages.pdf", count: Int = 3, huge: Boolean = false): Document {
        val path = Files.createDirectories(root.resolve("sources")).resolve(name)
        PDDocument().use { pdf ->
            repeat(count) { ordinal ->
                val page = PDPage(if (huge) PDRectangle(1_000_000f, 1_000_000f) else PDRectangle(72f, 72f))
                pdf.addPage(page)
                PDPageContentStream(pdf, page).use { content ->
                    content.setNonStrokingColor(listOf(Color.RED, Color.GREEN, Color.BLUE)[ordinal % 3])
                    content.addRect(0f, 0f, page.mediaBox.width, page.mediaBox.height)
                    content.fill()
                }
            }
            pdf.save(path.toFile())
        }
        return context.library.importFile((context.collections.list().firstOrNull() ?: context.collections.create("Default")).id, path).document
    }
    fun page(ordinal: Int, text: String, method: ExtractionMethod = ExtractionMethod.DIRECT_TEXT,
             image: SourceImageProvenance? = null) = RevisionPageDraft(
        ordinal = ordinal, unitId = ContentUnitId("unit-$ordinal"), locator = SourceLocation.PdfPage(ordinal + 1),
        extractedText = text, searchText = text, extractionMethod = method,
        sourceImage = image, approval = PageApproval.APPROVED,
        chunks = listOf(infoscry.storage.RevisionChunkDraft(ordinal = 0, text = text,
            startOffset = 0, endOffset = text.length, tokenCount = 1, tokenStart = 0, tokenEnd = 0,
            embedding = infoscry.embedding.TestDocumentEmbedder().embedDocuments(listOf(text)).single())),
    )
    fun publish(document: Document, pages: List<RevisionPageDraft>, engine: OcrEngine = OcrEngine.TESSERACT): String {
        val baseline = context.revisions.activeRevisionId(document.id)
        val candidate = context.revisions.openCandidate(document.id, baseline, "test", readingSnapshot = OcrSettingsSnapshot(
            engine = engine, mode = OcrImportMode.FILL_MISSING, language = "eng", extractorVersion = "test",
        ))
        pages.forEach { context.revisions.appendPage(candidate, it.copy(unitId = ContentUnitId("${document.id.value}-${it.ordinal}"), producerRevisionId = it.producerRevisionId ?: candidate)) }
        runBlocking { context.revisionPublication.publish(document.id, baseline, candidate) }
        assertEquals(candidate, context.revisions.activeRevisionId(document.id))
        return candidate
    }
    fun ocrImage(document: Document, reference: String = "ocr.png"): SourceImageProvenance {
        val path = context.paths.artifactsDir(document.collectionId, document.id).resolve(reference)
        Files.createDirectories(path.parent)
        ImageIO.write(BufferedImage(5, 7, BufferedImage.TYPE_INT_RGB), "png", path.toFile())
        return SourceImageProvenance(SourceImageRoot.ARTIFACTS, reference,
            MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)).joinToString("") { "%02x".format(it) }, 5, 7, 1)
    }
    fun tiff(name: String): Document {
        val path = Files.createDirectories(root.resolve("sources")).resolve(name)
        ImageIO.write(BufferedImage(5, 7, BufferedImage.TYPE_INT_RGB).apply {
            createGraphics().also { g -> g.color = Color.MAGENTA; g.fillRect(0, 0, width, height); g.dispose() }
        }, "tiff", path.toFile())
        return context.library.importFile((context.collections.list().firstOrNull() ?: context.collections.create("Default")).id, path).document
    }
    fun tiffArtifact(document: Document, reference: String): SourceImageProvenance {
        val path = context.paths.artifactsDir(document.collectionId, document.id).resolve(reference)
        Files.createDirectories(path.parent)
        ImageIO.write(BufferedImage(5, 7, BufferedImage.TYPE_INT_RGB).apply {
            createGraphics().also { g -> g.color = Color.MAGENTA; g.fillRect(0, 0, width, height); g.dispose() }
        }, "tiff", path.toFile())
        return SourceImageProvenance(SourceImageRoot.ARTIFACTS, reference,
            MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)).joinToString("") { "%02x".format(it) }, 5, 7, 1)
    }
}
