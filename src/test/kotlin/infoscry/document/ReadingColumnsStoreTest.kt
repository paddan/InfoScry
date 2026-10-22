package infoscry.document

import infoscry.AppContext
import infoscry.domain.AppliedCorrection
import infoscry.domain.CollectionId
import infoscry.domain.ContentUnitId
import infoscry.domain.ReadingText
import infoscry.domain.SourceLocation
import infoscry.embedding.TestDocumentEmbedder
import infoscry.jobs.seedCollectionWithId
import infoscry.ocr.StructureReference
import infoscry.pdf.LayoutReference
import infoscry.storage.PageApproval
import infoscry.storage.RevisionChunkDraft
import infoscry.storage.RevisionPageDraft
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking

/** A corrected page keeps the reading it was corrected from, through staging, publication and restore. */
class ReadingColumnsStoreTest {

    private lateinit var dataDir: Path
    private val collection = CollectionId("default")
    private val reading = ReadingText(
        "Brev från spinners",
        "Brev från spinners",
        structure = StructureReference("structure/page-000000.${"c".repeat(64)}.structure.json.gz", "c".repeat(64)),
        layout = LayoutReference("layout/${"d".repeat(64)}.json", "d".repeat(64)),
    )

    @BeforeTest
    fun createTemporaryDataDirectory() {
        dataDir = Files.createTempDirectory("infoscry-reading-columns")
        AppContext.open(dataDir).use { context -> context.seedCollectionWithId("default", "Default") }
    }

    @AfterTest
    fun removeTemporaryDataDirectory() {
        dataDir.toFile().deleteRecursively()
    }

    private fun AppContext.publishPage(text: String, reading: ReadingText?, unitId: ContentUnitId, documentId: infoscry.domain.DocumentId): String {
        val active = revisions.activeRevisionId(documentId)
        val candidate = revisions.openCandidate(documentId, active, "TEST_REPLACEMENT")
        revisions.appendPage(
            candidate,
            RevisionPageDraft(
                ordinal = 0,
                unitId = unitId,
                locator = SourceLocation.TextLines(1, 1),
                extractedText = text,
                searchText = text,
                approval = PageApproval.APPROVED,
                reading = reading,
                chunks = listOf(
                    RevisionChunkDraft(
                        ordinal = 0,
                        text = text,
                        startOffset = 0,
                        endOffset = text.length,
                        tokenCount = 5,
                        tokenStart = 0,
                        tokenEnd = 4,
                        embedding = TestDocumentEmbedder().embedDocuments(listOf(text)).single(),
                    ),
                ),
            ),
        )
        runBlocking { revisionPublication.publish(documentId, active, candidate) }
        return candidate
    }

    @Test
    fun `a staged reading is read back and published onto the content unit`() {
        AppContext.open(dataDir).use { context ->
            val documentId = RestoreFixtures.importDocument(context, dataDir, collection, "Brev från spinners")
            val unitId = ContentUnitId.new()

            val revision = context.publishPage("Brev från Spinnars", reading, unitId, documentId)

            assertEquals(reading, context.revisions.pages(revision).single().reading)
            assertEquals(reading, context.content.readUnit(unitId)?.reading)
        }
    }

    @Test
    fun `a page without a reading publishes none`() {
        AppContext.open(dataDir).use { context ->
            val documentId = RestoreFixtures.importDocument(context, dataDir, collection, "plain")
            val unitId = ContentUnitId.new()

            val revision = context.publishPage("plain", null, unitId, documentId)

            assertNull(context.revisions.pages(revision).single().reading)
            assertNull(context.content.readUnit(unitId)?.reading)
        }
    }

    @Test
    fun `a restore copies each page's reading`() {
        AppContext.open(dataDir).use { context ->
            val documentId = RestoreFixtures.importDocument(context, dataDir, collection, "Brev från spinners")
            val unitId = ContentUnitId.new()
            val corrected = context.publishPage("Brev från Spinnars", reading, unitId, documentId)
            val later = context.publishPage("something else", null, unitId, documentId)

            val restore = context.revisions.stageRestore(collection, documentId, "r1", "a".repeat(64), later, corrected)

            assertEquals(reading, context.revisions.pages(restore.newRevisionId).single().reading)
        }
    }

    @Test
    fun `a revision records the corrections it was made with`() {
        AppContext.open(dataDir).use { context ->
            val documentId = RestoreFixtures.importDocument(context, dataDir, collection, "Brev från spinners")
            val candidate = context.revisions.openCandidate(documentId, context.revisions.activeRevisionId(documentId), "CORRECTIONS")
            val applied = listOf(AppliedCorrection("spinners", "Spinnars", 14))

            context.revisions.recordCorrections(candidate, "f".repeat(64), applied)

            val revision = checkNotNull(context.revisions.revision(candidate))
            assertEquals("f".repeat(64), revision.correctionsFingerprint)
            assertEquals(applied, revision.correctionsApplied)
        }
    }

    @Test
    fun `a revision without corrections records none`() {
        AppContext.open(dataDir).use { context ->
            val documentId = RestoreFixtures.importDocument(context, dataDir, collection, "plain")
            val candidate = context.revisions.openCandidate(documentId, null, "TEST_REPLACEMENT")
            val revision = checkNotNull(context.revisions.revision(candidate))

            assertNull(revision.correctionsFingerprint)
            assertEquals(emptyList(), revision.correctionsApplied)
        }
    }
}
