package infoscry.correction

import infoscry.AppContext
import infoscry.document.RestoreFixtures
import infoscry.domain.CollectionId
import infoscry.domain.JobType
import infoscry.jobs.seedCollectionWithId
import infoscry.jobs.CorrectionJobHandler
import infoscry.jobs.JobStage
import infoscry.chunk.Chunker
import infoscry.chunk.WhitespaceTokenCounter
import infoscry.embedding.TestDocumentEmbedder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class CorrectionServiceTest {

    private lateinit var dataDir: Path
    private val collection = CollectionId("default")

    @BeforeTest
    fun createTemporaryDataDirectory() {
        dataDir = Files.createTempDirectory("infoscry-correction-service")
        AppContext.open(dataDir).use { context -> context.seedCollectionWithId("default", "Default") }
    }

    @AfterTest
    fun removeTemporaryDataDirectory() {
        dataDir.toFile().deleteRecursively()
    }

    private val spinners = CorrectionRuleInput("spinners", "Spinnars", exactCase = false)

    @Test
    fun `saving a rule starts one job, and asking again returns it`() {
        AppContext.open(dataDir).use { context ->
            runBlocking { context.correctionService.put(collection, "r1", spinners) }
            val first = context.correctionService.apply(collection)
            val second = context.correctionService.apply(collection)

            assertEquals(first.id, second.id)
            assertEquals(JobType.CORRECTIONS, first.type)
            assertEquals(1, context.jobs.listActive(10).count { it.type == JobType.CORRECTIONS })
        }
    }

    @Test
    fun `a preview counts matches and documents and changes nothing`() {
        AppContext.open(dataDir).use { context ->
            val docs = (1..6).map { index ->
                RestoreFixtures.publishGenerations(
                    context, dataDir, collection,
                    marketReadings = listOf(if (index <= 4) "brev från spinners och spinners" else "brev från spinners till Spinners igen och spinners"),
                )
            }
            val revisionsBefore = docs.map { context.revisions.activeRevisionId(it.documentId) }
            val jobsBefore = context.jobs.list(100).size

            val preview = context.correctionService.preview(collection, spinners, replacingId = null)

            assertEquals(14, preview.matches)
            assertEquals(6, preview.documents)
            assertEquals(5, preview.examples.size)
            assertTrue(preview.examples.all { "spinners" in it.before.lowercase() && "Spinnars" in it.after })
            assertEquals(revisionsBefore, docs.map { context.revisions.activeRevisionId(it.documentId) })
            assertEquals(jobsBefore, context.jobs.list(100).size)
            assertEquals(emptyList(), context.corrections.list(collection))
        }
    }

    @Test
    fun `a removal preview counts the documents that would change back`() {
        AppContext.open(dataDir).use { context ->
            RestoreFixtures.publishGenerations(context, dataDir, collection, marketReadings = listOf("hos spinners"))
            RestoreFixtures.publishGenerations(context, dataDir, collection, marketReadings = listOf("annat"))
            runBlocking { context.correctionService.put(collection, "r1", spinners) }
            val job = checkNotNull(context.jobs.activeJob(JobType.CORRECTIONS, collection))
            context.jobs.claim(job.id)
            runBlocking {
                CorrectionJobHandler(
                    revisions = context.revisions,
                    documents = context.documents,
                    corrections = context.corrections,
                    corrector = context.candidateCorrector,
                    publication = context.revisionPublication,
                    chunker = Chunker(WhitespaceTokenCounter(prefixTokens = 2, specialTokens = 1)),
                    documentEmbedder = { TestDocumentEmbedder() },
                ).handle(job, JobStage(job.id, context.jobs, context.collections, context.mutations))
            }

            val preview = context.correctionService.previewRemoval(collection, "r1")

            assertEquals(1, preview.documents)
            assertEquals(1, preview.matches)
        }
    }

    @Test
    fun `an invalid rule is refused before anything is stored`() {
        AppContext.open(dataDir).use { context ->
            assertFailsWith<InvalidCorrectionException> {
                runBlocking { context.correctionService.put(collection, "r1", CorrectionRuleInput(" ", "x", false)) }
            }
            assertEquals(emptyList(), context.corrections.list(collection))
            assertEquals(0, context.jobs.listActive(10).size)
        }
    }

    @Test
    fun `the list reports each rule's matches in published text`() {
        AppContext.open(dataDir).use { context ->
            val document = RestoreFixtures.publishGenerations(context, dataDir, collection, marketReadings = listOf("hos spinners"))
            runBlocking { context.correctionService.put(collection, "r1", spinners) }
            val active = checkNotNull(context.revisions.activeRevisionId(document.documentId))
            context.revisions.recordCorrections(active, "f".repeat(64), listOf(infoscry.domain.AppliedCorrection("spinners", "Spinnars", 3)))

            val view = context.correctionService.list(collection)

            assertEquals(listOf(3), view.rules.map { it.matches })
            assertEquals(JobType.CORRECTIONS, view.job?.type)
        }
    }
}
