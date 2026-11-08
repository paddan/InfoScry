package infoscry.storage

import infoscry.account.testOwner
import infoscry.domain.CollectionId
import infoscry.domain.EditConflictException
import infoscry.domain.ContentUnitId
import infoscry.domain.DocumentId
import infoscry.image.analysis.AnalysisOutcome
import infoscry.image.analysis.ImageAnalysis
import infoscry.image.analysis.ImageAnalysisKey
import infoscry.image.analysis.ImageTag
import infoscry.image.analysis.ImageTagEdit
import infoscry.image.analysis.TagCategory
import infoscry.image.analysis.TagEditAction
import infoscry.image.analysis.TagSource
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ImageAnalysisStoreTest {

    private lateinit var directory: Path
    private lateinit var database: Database
    private lateinit var store: ImageAnalysisStore
    private var documentId: DocumentId = DocumentId("unset")

    @BeforeTest
    fun openMigratedDatabase() {
        directory = Files.createTempDirectory("infoscry-image-analysis")
        database = Database(directory.resolve("state.db"))
        SchemaMigrator(database).migrate()
        store = ImageAnalysisStore(database)
        documentId = insertDocument()
    }

    @AfterTest
    fun closeAndRemoveDatabase() {
        database.close()
        directory.toFile().deleteRecursively()
    }

    private fun insertDocument(): DocumentId {
        val collectionId = CollectionId.new()
        val id = DocumentId.new()
        database.transaction { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    "INSERT INTO collections (id, name, created_at, updated_at) VALUES " +
                        "('${collectionId.value}', 'Pictures', '2026-11-07T00:00:00Z', '2026-11-07T00:00:00Z')",
                )
                statement.executeUpdate(
                    "INSERT INTO documents (id, collection_id, sha256, media_type, original_filename, original_path, " +
                        "size_bytes, status, created_at, updated_at) VALUES ('${id.value}', '${collectionId.value}', " +
                        "'h', 'image/png', 'picture.png', '/picture.png', 1, 'COMPLETE', " +
                        "'2026-11-07T00:00:00Z', '2026-11-07T00:00:00Z')",
                )
            }
        }
        return id
    }

    private fun key(
        imageSha256: String = "a".repeat(64),
        profileRevisionId: String = "rev-1",
        identifyAppearance: Boolean = false,
    ) = ImageAnalysisKey(
        documentId = documentId,
        unitId = ContentUnitId("unit-1"),
        imageSha256 = imageSha256,
        profileRevisionId = profileRevisionId,
        promptVersion = 1,
        language = "swe",
        identifyAppearance = identifyAppearance,
    )

    private fun analysis(
        key: ImageAnalysisKey = key(),
        outcome: AnalysisOutcome = AnalysisOutcome.DONE,
        description: String? = "Två män vid en bil.",
        tags: List<ImageTag> = listOf(
            ImageTag(TagCategory.PERSON, "man", "Jeffrey Epstein", TagSource.APPEARANCE),
            ImageTag(TagCategory.OBJECT, "bil", "Volvo PV444", TagSource.TEXT),
        ),
        errorCode: String? = null,
    ) = ImageAnalysis(key, outcome, description, tags, errorCode)

    @Test
    fun `record twice leaves one row and get returns it`() {
        val expected = analysis()

        store.record(expected)
        store.record(expected)

        assertEquals(expected, store.get(expected.key))
        val rows = database.read { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT count(*) FROM image_analyses").use { rows ->
                    rows.next()
                    rows.getInt(1)
                }
            }
        }
        assertEquals(1, rows)
    }

    @Test
    fun `a failed row is replaced by a later done analysis for the same key`() {
        val failed = analysis(outcome = AnalysisOutcome.FAILED, description = null, tags = emptyList(), errorCode = "IMAGE_ANALYSIS_TIMEOUT")
        val done = analysis()

        store.record(failed)
        assertEquals(failed, store.get(failed.key))

        store.record(done)

        assertEquals(done, store.get(done.key))
    }

    @Test
    fun `latest done ignores other image hashes and failed analyses`() {
        store.record(analysis(key = key(imageSha256 = "b".repeat(64)), description = "another image"))
        store.record(analysis(key = key(imageSha256 = "a".repeat(64), identifyAppearance = true), description = "identify"))
        store.record(
            analysis(
                key = key(imageSha256 = "a".repeat(64), profileRevisionId = "rev-2"),
                outcome = AnalysisOutcome.FAILED,
                description = null,
                tags = emptyList(),
                errorCode = "IMAGE_ANALYSIS_REFUSED",
            ),
        )

        val latest = store.latestDone(documentId, ContentUnitId("unit-1"), "a".repeat(64))

        assertEquals("identify", latest?.description)
        assertNull(store.latestDone(documentId, ContentUnitId("unit-1"), "c".repeat(64)))
    }

    @Test
    fun `addEdit with the same id twice leaves one edit and returns the stored one`() {
        val owner = testOwner(database)
        val edit = ImageTagEdit(
            editId = "edit-1",
            documentId = documentId,
            unitId = ContentUnitId("unit-1"),
            action = TagEditAction.CONFIRM,
            tag = ImageTag(TagCategory.PERSON, "man", "Jeffrey Epstein", TagSource.MANUAL),
            createdBy = owner.id,
            createdAt = "2026-11-07T10:00:00Z",
        )

        store.addEdit(edit)
        val repeated = store.addEdit(edit.copy(createdAt = "2026-11-07T11:00:00Z"))

        assertEquals(edit.createdAt, repeated.createdAt, "the stored edit is what an equal id returns")
        assertEquals(listOf(edit), store.edits(documentId, ContentUnitId("unit-1")))
    }

    @Test
    fun `an edit made from a stale version is refused and a repeated id is the first answer`() {
        val unitId = ContentUnitId("unit-1")
        val tag = ImageTag(TagCategory.PERSON, "man", null, TagSource.MANUAL)
        val first = ImageTagEdit("e1", documentId, unitId, TagEditAction.ADD, tag, null, "2026-11-07T10:00:00Z")
        val second = ImageTagEdit("e2", documentId, unitId, TagEditAction.ADD, tag, null, "2026-11-07T10:00:01Z")

        assertEquals(first, store.addEditIfVersion(first, 0))
        assertFailsWith<EditConflictException> { store.addEditIfVersion(second, 0) }
        assertEquals(listOf(first), store.edits(documentId, unitId))

        assertEquals(first, store.addEditIfVersion(first.copy(createdAt = "2026-11-07T11:00:00Z"), 0))
        assertEquals(1L, store.editsVersion(documentId, unitId))
    }

    @Test
    fun `edits keep their creation order and the version moves with each edit`() {
        val unitId = ContentUnitId("unit-1")
        assertEquals(0L, store.editsVersion(documentId, unitId))
        val person = ImageTag(TagCategory.PERSON, "man", null, TagSource.MANUAL)
        val first = ImageTagEdit("e1", documentId, unitId, TagEditAction.ADD, person, null, "2026-11-07T10:00:00Z")
        val second = ImageTagEdit("e2", documentId, unitId, TagEditAction.REMOVE, person, null, "2026-11-07T10:00:01Z")

        store.addEdit(first)
        store.addEdit(second)

        assertEquals(listOf(first, second), store.edits(documentId, unitId), "the order people made them")
        assertEquals(2L, store.editsVersion(documentId, unitId))
    }

    @Test
    fun `a second unit's edits do not move the first unit's version`() {
        val first = ContentUnitId("unit-1")
        val second = ContentUnitId("unit-2")
        store.addEdit(
            ImageTagEdit(
                "e1", documentId, second, TagEditAction.ADD,
                ImageTag(TagCategory.OBJECT, "bil", null, TagSource.MANUAL), null, "2026-11-07T10:00:00Z",
            ),
        )

        assertEquals(0L, store.editsVersion(documentId, first))
        assertEquals(1L, store.editsVersion(documentId, second))
    }
}
