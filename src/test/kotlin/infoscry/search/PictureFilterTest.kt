package infoscry.search

import infoscry.domain.Chunk
import infoscry.domain.ChunkId
import infoscry.domain.CollectionId
import infoscry.domain.ContentUnitId
import infoscry.domain.Document
import infoscry.domain.DocumentId
import infoscry.domain.DocumentStatus
import infoscry.domain.SourceLocation
import infoscry.embedding.QueryEmbedder
import infoscry.image.ImageSourceKind
import infoscry.image.analysis.AnalysisOutcome
import infoscry.image.analysis.ImageAnalysis
import infoscry.image.analysis.ImageAnalysisKey
import infoscry.image.analysis.ImageCategories
import infoscry.image.analysis.ImageTag
import infoscry.image.analysis.ImageTagEdit
import infoscry.image.analysis.TagCategory
import infoscry.image.analysis.TagEditAction
import infoscry.image.analysis.TagSource
import infoscry.storage.CollectionStore
import infoscry.storage.Database
import infoscry.storage.DocumentStore
import infoscry.storage.ImageAnalysisStore
import infoscry.storage.ImageStore
import infoscry.storage.Instants
import infoscry.storage.SchemaMigrator
import infoscry.storage.StoredImage
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * The picture category filter: it keeps only picture units tagged with the category, never a text unit, and it
 * follows the edits people made rather than the model's first answer.
 */
class PictureFilterTest {

    private lateinit var directory: Path
    private lateinit var database: Database
    private lateinit var collections: CollectionStore
    private lateinit var documents: DocumentStore
    private lateinit var analyses: ImageAnalysisStore
    private lateinit var images: ImageStore
    private lateinit var index: SearchIndex
    private lateinit var service: SearchService
    private var collectionId: CollectionId = CollectionId("unset")

    @BeforeTest
    fun openStack() {
        directory = Files.createTempDirectory("infoscry-picture-filter")
        database = Database(directory)
        SchemaMigrator(database).migrate()
        collections = CollectionStore(database)
        documents = DocumentStore(database)
        analyses = ImageAnalysisStore(database)
        images = ImageStore(database)
        collectionId = collections.create("Pictures").id
        index = SearchIndex(database, IDENTITY).open()
        service = SearchService(
            collections = collections,
            documents = documents,
            index = { index },
            queryEmbedder = { QueryEmbedder { query -> vectorFor(query) } },
        )
    }

    @AfterTest
    fun closeStack() {
        database.close()
        directory.toFile().deleteRecursively()
    }

    private fun document(filename: String): Document {
        val now = Instants.now()
        return documents.insert(
            Document(
                id = DocumentId.new(),
                collectionId = collectionId,
                sha256 = "sha-$filename",
                mediaType = if (filename.endsWith(".png")) "image/png" else "text/plain",
                originalFilename = filename,
                sourcePath = "tmp/original/$filename",
                sizeBytes = 1,
                status = DocumentStatus.COMPLETE,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    private fun row(
        documentId: DocumentId,
        unitId: ContentUnitId,
        text: String,
        categories: Set<String> = emptySet(),
    ): DocumentRow = DocumentRow(
        collectionId = collectionId,
        documentId = documentId,
        unitId = unitId,
        locator = SourceLocation.TextLines(1, 1),
        locatorLabel = "line 1",
        chunk = Chunk(
            id = ChunkId.new(),
            contentUnitId = unitId,
            ordinal = 0,
            text = text,
            startOffset = 0,
            endOffset = text.length,
            tokenCount = text.length.coerceAtLeast(1),
            tokenStart = 0,
            tokenEnd = (text.length - 1).coerceAtLeast(0),
        ),
        vector = vectorFor(text),
        imageCategories = categories,
    )

    private fun search(mode: SearchMode): SearchOutcome = service.search(
        queryText = "man vid en bil",
        mode = mode,
        filters = SearchFilters(collectionId, pictureCategory = TagCategory.PERSON),
    )

    @Test
    fun `keyword semantic and hybrid with a picture category return only the tagged picture`() = runBlocking {
        val manifest = document("manifest.txt")
        val picture = document("picture.png")
        val textUnit = ContentUnitId.new()
        val pictureUnit = ContentUnitId.new()
        index.replaceDocument(listOf(row(manifest.id, textUnit, "man vid en bil")))
        index.replaceDocument(listOf(row(picture.id, pictureUnit, "man vid en bil", setOf("PERSON"))))

        listOf(SearchMode.KEYWORD, SearchMode.SEMANTIC, SearchMode.HYBRID).forEach { mode ->
            val outcome = search(mode)

            assertEquals(
                listOf(pictureUnit),
                outcome.hits.map { it.unitId },
                "$mode must keep the tagged picture and drop the text unit",
            )
        }
    }

    @Test
    fun `a picture with another category does not match the people filter`() = runBlocking {
        val picture = document("car.png")
        val car = ContentUnitId.new()
        index.replaceDocument(listOf(row(picture.id, car, "man vid en bil", setOf("OBJECT"))))

        assertEquals(emptyList(), search(SearchMode.KEYWORD).hits.map { it.unitId })
    }

    @Test
    fun `a removed person tag stops matching once the picture is published again`() = runBlocking {
        val picture = document("picture.png")
        val pictureUnit = ContentUnitId.new()
        val parent = ContentUnitId("unit:page-1")
        val sha256 = "a".repeat(64)
        images.record(
            StoredImage(
                documentId = picture.id,
                unitId = pictureUnit,
                sha256 = sha256,
                parentUnitId = parent,
                kind = ImageSourceKind.EMBEDDED,
                relativePath = "images/picture.png",
                thumbnailRelativePath = "images/thumbs/picture.jpg",
                mediaType = "image/png",
                widthPx = 400,
                heightPx = 300,
                box = null,
                caption = null,
                captionSource = null,
            ),
        )
        val key = ImageAnalysisKey(picture.id, pictureUnit, sha256, "revision-1", 1, "swe", false)
        analyses.record(
            ImageAnalysis(
                key = key,
                outcome = AnalysisOutcome.DONE,
                description = "En man vid en bil.",
                tags = listOf(ImageTag(TagCategory.PERSON, "man", "Jeffrey Epstein", TagSource.APPEARANCE)),
                errorCode = null,
            ),
        )
        val categories = ImageCategories(analyses, images)
        listOf(categories.of(picture.id, pictureUnit, sha256), categories.ofUnit(picture.id, pictureUnit)).forEach { found ->
            assertEquals(setOf("PERSON"), found, "the analysis the row builder reads has the person tag")
        }
        index.replaceDocument(listOf(row(picture.id, pictureUnit, "man vid en bil", categories.of(picture.id, pictureUnit, sha256))))
        assertEquals(listOf(pictureUnit), search(SearchMode.KEYWORD).hits.map { it.unitId })

        analyses.addEdit(
            ImageTagEdit(
                editId = "e1",
                documentId = picture.id,
                unitId = pictureUnit,
                action = TagEditAction.REMOVE,
                tag = ImageTag(TagCategory.PERSON, "man", "Jeffrey Epstein", TagSource.MANUAL),
                createdBy = null,
                createdAt = "2026-11-07T10:00:00Z",
            ),
        )
        val afterEdit = categories.of(picture.id, pictureUnit, sha256)
        index.replaceDocument(listOf(row(picture.id, pictureUnit, "man vid en bil", afterEdit)))

        assertEquals(emptySet(), afterEdit, "a removed tag is no category")
        assertTrue(search(SearchMode.KEYWORD).hits.isEmpty(), "a picture whose only person tag was removed no longer matches")
    }

    private companion object {
        val IDENTITY = IndexIdentity(
            schemaVersion = SearchIndex.SCHEMA_VERSION,
            model = "intfloat/multilingual-e5-base",
            modelRevision = "d128750597153bb5987e10b1c3493a34e5a4502a",
            modelFingerprint = "picture-filter-test",
            dimension = 768,
        )
    }
}
