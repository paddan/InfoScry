package infoscry.correction

import infoscry.AppContext
import infoscry.document.RestoreFixtures
import infoscry.domain.AppliedCorrection
import infoscry.domain.CollectionId
import infoscry.domain.ContentUnitId
import infoscry.domain.ExtractionMethod
import infoscry.domain.ReadingText
import infoscry.domain.SourceLocation
import infoscry.extract.ContentUnitDraft
import infoscry.extract.ExtractionEvent
import infoscry.extract.ExtractionFingerprint
import infoscry.jobs.StoredUnitsSink
import infoscry.jobs.seedCollectionWithId
import infoscry.storage.PageApproval
import infoscry.storage.RevisionPageDraft
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class CandidateCorrectorTest {

    private lateinit var dataDir: Path
    private val collection = CollectionId("default")

    @BeforeTest
    fun createTemporaryDataDirectory() {
        dataDir = Files.createTempDirectory("infoscry-candidate-corrector")
        AppContext.open(dataDir).use { context -> context.seedCollectionWithId("default", "Default") }
    }

    @AfterTest
    fun removeTemporaryDataDirectory() {
        dataDir.toFile().deleteRecursively()
    }

    private fun AppContext.addRule(find: String = "spinners", replace: String = "Spinnars") =
        corrections.put(collection, "r-$find", CorrectionRuleInput(find, replace, exactCase = false))

    private fun AppContext.chunkOrdinals(revisionId: String): Set<Int> = revisions.chunks(revisionId).map { it.unitOrdinal }.toSet()

    @Test
    fun `corrected pages get the new text and lose their chunks`() {
        AppContext.open(dataDir).use { context ->
            val documentId = RestoreFixtures.importDocument(context, dataDir, collection, "x")
            val candidate = RestoreFixtures.stage(context, documentId, null, listOf("hos spinners", "annat"), listOf(ContentUnitId.new(), ContentUnitId.new()))
            context.addRule()

            val applied = context.candidateCorrector.correct(collection, documentId, candidate)

            val pages = context.revisions.pages(candidate)
            assertEquals("hos Spinnars", pages[0].extractedText)
            assertEquals(ReadingText("hos spinners", "hos spinners"), pages[0].reading)
            assertEquals("annat", pages[1].extractedText)
            assertNull(pages[1].reading)
            assertEquals(setOf(1), context.chunkOrdinals(candidate))
            assertEquals(listOf(AppliedCorrection("spinners", "Spinnars", 1)), applied)
            assertEquals(applied, context.revisions.revision(candidate)?.correctionsApplied)
        }
    }

    @Test
    fun `correcting twice changes nothing more`() {
        AppContext.open(dataDir).use { context ->
            val documentId = RestoreFixtures.importDocument(context, dataDir, collection, "x")
            val candidate = RestoreFixtures.stage(context, documentId, null, listOf("hos spinners", "annat"), listOf(ContentUnitId.new(), ContentUnitId.new()))
            context.addRule()
            context.candidateCorrector.correct(collection, documentId, candidate)
            val pagesBefore = context.revisions.pages(candidate)

            val applied = context.candidateCorrector.correct(collection, documentId, candidate)

            assertEquals(pagesBefore, context.revisions.pages(candidate))
            assertEquals(setOf(1), context.chunkOrdinals(candidate))
            assertEquals(listOf(AppliedCorrection("spinners", "Spinnars", 1)), applied)
        }
    }

    @Test
    fun `a page is corrected from its reading, so a removed rule brings the reading back`() {
        AppContext.open(dataDir).use { context ->
            val documentId = RestoreFixtures.importDocument(context, dataDir, collection, "x")
            val candidate = context.revisions.openCandidate(documentId, null, "TEST_REPLACEMENT")
            context.revisions.appendPage(
                candidate,
                RevisionPageDraft(
                    ordinal = 0,
                    unitId = ContentUnitId.new(),
                    locator = SourceLocation.TextLines(1, 1),
                    extractedText = "hos Spinnars",
                    searchText = "hos Spinnars",
                    approval = PageApproval.APPROVED,
                    reading = ReadingText("hos spinners", "hos spinners"),
                ),
            )

            context.candidateCorrector.correct(collection, documentId, candidate)

            val page = context.revisions.pages(candidate).single()
            assertEquals("hos spinners", page.extractedText)
            assertNull(page.reading)
        }
    }

    @Test
    fun `wouldChange says whether the list rewrites a revision`() {
        AppContext.open(dataDir).use { context ->
            val documentId = RestoreFixtures.importDocument(context, dataDir, collection, "x")
            val candidate = RestoreFixtures.stage(context, documentId, null, listOf("hos spinners"), listOf(ContentUnitId.new()))

            assertFalse(context.candidateCorrector.wouldChange(collection, candidate))
            context.addRule()
            assertTrue(context.candidateCorrector.wouldChange(collection, candidate))
            context.candidateCorrector.correct(collection, documentId, candidate)
            assertFalse(context.candidateCorrector.wouldChange(collection, candidate))
        }
    }

    @Test
    fun `a unit delivered twice is corrected once`() {
        AppContext.open(dataDir).use { context ->
            val documentId = RestoreFixtures.importDocument(context, dataDir, collection, "x")
            context.addRule()
            val sink = StoredUnitsSink(context.paths, context.documents, context.content, context.corrections)
            val event = ExtractionEvent.UnitReady(
                "page-0",
                0,
                ContentUnitDraft(SourceLocation.TextLines(1, 1), "hos spinners", "hos spinners", ExtractionMethod.DIRECT_TEXT),
            )
            val fingerprint = ExtractionFingerprint("f".repeat(64))

            runBlocking {
                sink.deliver(documentId, fingerprint, event)
                sink.deliver(documentId, fingerprint, event)
            }

            val unit = context.content.listUnits(documentId, -1, 10).single()
            assertEquals("hos Spinnars", unit.extractedText)
            assertEquals(ReadingText("hos spinners", "hos spinners"), unit.reading)
        }
    }

    @Test
    fun `a revision published under an older list starts the correction job`() {
        AppContext.open(dataDir).use { context ->
            val documentId = RestoreFixtures.importDocument(context, dataDir, collection, "x")
            context.corrections.put(collection, "r1", CorrectionRuleInput("spinners", "Spinnars", false))
            check(context.jobs.activeJob(infoscry.domain.JobType.CORRECTIONS, collection) == null)
            val candidate = RestoreFixtures.stage(context, documentId, null, listOf("hos spinners"), listOf(ContentUnitId.new()))

            runBlocking { context.revisionPublication.publish(documentId, null, candidate) }

            assertTrue(context.jobs.activeJob(infoscry.domain.JobType.CORRECTIONS, collection) != null)
        }
    }

    @Test
    fun `a revision that follows the list starts no job`() {
        AppContext.open(dataDir).use { context ->
            val documentId = RestoreFixtures.importDocument(context, dataDir, collection, "x")
            context.corrections.put(collection, "r1", CorrectionRuleInput("spinners", "Spinnars", false))
            val candidate = RestoreFixtures.stage(context, documentId, null, listOf("annat"), listOf(ContentUnitId.new()))

            runBlocking { context.revisionPublication.publish(documentId, null, candidate) }

            assertNull(context.jobs.activeJob(infoscry.domain.JobType.CORRECTIONS, collection))
        }
    }

    @Test
    fun `recordApplied says whether a published import still needs correcting`() {
        AppContext.open(dataDir).use { context ->
            val documentId = RestoreFixtures.importDocument(context, dataDir, collection, "x")
            val revision = RestoreFixtures.stage(context, documentId, null, listOf("hos spinners"), listOf(ContentUnitId.new()))
            context.corrections.put(collection, "r1", CorrectionRuleInput("spinners", "Spinnars", false))

            assertTrue(context.candidateCorrector.recordApplied(collection, revision))
            context.candidateCorrector.correct(collection, documentId, revision)
            assertFalse(context.candidateCorrector.recordApplied(collection, revision))
        }
    }

    @Test
    fun `a rule that writes the text back as it was changes nothing`() {
        AppContext.open(dataDir).use { context ->
            val documentId = RestoreFixtures.importDocument(context, dataDir, collection, "x")
            val candidate = RestoreFixtures.stage(context, documentId, null, listOf("hos spinners"), listOf(ContentUnitId.new()))
            context.corrections.put(collection, "r1", CorrectionRuleInput("SPINNERS", "spinners", false))

            assertFalse(context.candidateCorrector.wouldChange(collection, candidate))
            context.candidateCorrector.correct(collection, documentId, candidate)
            assertNull(context.revisions.pages(candidate).single().reading)
        }
    }

    @Test
    fun `a failing correction follow-up never fails the publication it follows`() {
        AppContext.open(dataDir).use { context ->
            val documentId = RestoreFixtures.importDocument(context, dataDir, collection, "x")
            // A rule row no matcher can compile, as damaged data would leave it.
            context.database.transaction { connection ->
                connection.createStatement().use {
                    it.execute(
                        "INSERT INTO collection_corrections (id, collection_id, find, find_key, replace, exact_case, created_at, updated_at) " +
                            "VALUES ('bad', 'default', ' ', 'fold: ', 'x', 0, 't', 't')",
                    )
                }
            }
            val candidate = RestoreFixtures.stage(context, documentId, null, listOf("hos spinners"), listOf(ContentUnitId.new()))

            val steps = mutableListOf<infoscry.document.PublicationStep>()
            runBlocking { context.revisionPublication.publish(documentId, null, candidate) { steps += it } }

            assertEquals(candidate, context.revisions.activeRevisionId(documentId))
            assertTrue(infoscry.document.PublicationStep.CLEANED in steps, "the publication finished its own cleanup: $steps")
            assertEquals(emptyList(), context.revisions.intentsAwaitingCleanup())
        }
    }
}
