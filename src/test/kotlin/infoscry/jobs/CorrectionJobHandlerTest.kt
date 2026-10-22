package infoscry.jobs

import infoscry.AppContext
import infoscry.chunk.Chunker
import infoscry.chunk.WhitespaceTokenCounter
import infoscry.correction.CorrectionJobFailedException
import infoscry.correction.CorrectionRuleInput
import infoscry.document.PublicationStep
import infoscry.document.RestoreDocument
import infoscry.document.RestoreFixtures
import infoscry.domain.CollectionId
import infoscry.domain.DocumentId
import infoscry.domain.Job
import infoscry.domain.JobState
import infoscry.embedding.DocumentEmbedder
import infoscry.embedding.TestDocumentEmbedder
import infoscry.storage.RevisionState
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking

class CorrectionJobHandlerTest {

    private lateinit var dataDir: Path
    private val collection = CollectionId("default")

    @BeforeTest
    fun createTemporaryDataDirectory() {
        dataDir = Files.createTempDirectory("infoscry-correction-job")
        AppContext.open(dataDir).use { context -> context.seedCollectionWithId("default", "Default") }
    }

    @AfterTest
    fun removeTemporaryDataDirectory() {
        dataDir.toFile().deleteRecursively()
    }

    private class Killed : RuntimeException("the process died here")

    private var lastFailure: Throwable? = null

    /** One CORRECTIONS job, claimed and run through its handler the way the runner runs it. */
    private fun AppContext.runCorrections(
        embedder: () -> DocumentEmbedder? = { TestDocumentEmbedder() },
        beforePublish: suspend (DocumentId) -> Unit = {},
        afterDocument: suspend (DocumentId) -> Unit = {},
        observePublication: (PublicationStep) -> Unit = {},
    ): Job {
        val job = corrections().apply(collection)
        jobs.claim(job.id)
        val handler = CorrectionJobHandler(
            revisions = revisions,
            documents = documents,
            corrections = this.corrections,
            corrector = candidateCorrector,
            publication = revisionPublication,
            chunker = Chunker(WhitespaceTokenCounter(prefixTokens = 2, specialTokens = 1)),
            documentEmbedder = embedder,
            beforePublish = beforePublish,
            afterDocument = afterDocument,
            observePublication = observePublication,
        )
        runCatching { runBlocking { handler.handle(checkNotNull(jobs.get(job.id)), JobStage(job.id, jobs, collections, mutations)) } }
            .onSuccess { jobs.complete(job.id) }
            .onFailure { failure ->
                lastFailure = failure
                jobs.fail(job.id, (failure as? CorrectionJobFailedException)?.code ?: "JOB_FAILED", failure.message ?: "failed")
            }
        return checkNotNull(jobs.get(job.id))
    }

    private fun AppContext.corrections() = correctionService

    private fun AppContext.rule(find: String, replace: String, id: String = "r-$find") = runBlocking {
        correctionService.put(collection, id, CorrectionRuleInput(find, replace, exactCase = false))
    }

    private fun AppContext.document(reading: String): RestoreDocument =
        RestoreFixtures.publishGenerations(context = this, dataDir = dataDir, collectionId = collection, marketReadings = listOf(reading))

    private fun AppContext.pageText(document: RestoreDocument): String =
        revisions.pages(checkNotNull(revisions.activeRevisionId(document.documentId)))[1].extractedText

    private fun AppContext.correctionRevisions(document: RestoreDocument): Int =
        revisions.revisions(document.documentId).count { it.provenance == "CORRECTIONS" && it.state != RevisionState.CANDIDATE && it.state != RevisionState.WITHDRAWN }

    @Test
    fun `only documents the list changes get a new revision`() {
        AppContext.open(dataDir).use { context ->
            val matching = context.document("hos spinners")
            val others = listOf(context.document("annat"), context.document("inget"))
            context.rule("spinners", "Spinnars")

            val job = context.runCorrections()

            assertEquals(JobState.COMPLETE, job.state)
            assertEquals("hos Spinnars", context.pageText(matching))
            assertEquals(1, context.correctionRevisions(matching))
            others.forEach { other -> assertEquals(other.revisionIds.last(), context.revisions.activeRevisionId(other.documentId)) }
        }
    }

    @Test
    fun `running twice creates no second revision`() {
        AppContext.open(dataDir).use { context ->
            val matching = context.document("hos spinners")
            context.rule("spinners", "Spinnars")
            context.runCorrections()
            val active = context.revisions.activeRevisionId(matching.documentId)

            context.runCorrections()

            assertEquals(active, context.revisions.activeRevisionId(matching.documentId))
            assertEquals(1, context.correctionRevisions(matching))
        }
    }

    @Test
    fun `removing the rule brings the reading back`() {
        AppContext.open(dataDir).use { context ->
            val matching = context.document("hos spinners")
            context.rule("spinners", "Spinnars")
            context.runCorrections()

            runBlocking { context.correctionService.delete(collection, "r-spinners") }
            context.runCorrections()

            assertEquals("hos spinners", context.pageText(matching))
            val pages = context.revisions.pages(checkNotNull(context.revisions.activeRevisionId(matching.documentId)))
            pages.forEach { assertNull(it.reading) }
        }
    }

    @Test
    fun `changing the rule rewrites the text from the reading`() {
        AppContext.open(dataDir).use { context ->
            val matching = context.document("hos spinners")
            context.rule("spinners", "Spinnars")
            context.runCorrections()

            context.rule("spinners", "Spinnarö")
            context.runCorrections()

            assertEquals("hos Spinnarö", context.pageText(matching))
            val page = context.revisions.pages(checkNotNull(context.revisions.activeRevisionId(matching.documentId)))[1]
            assertEquals("hos spinners", page.reading?.extractedText)
        }
    }

    @Test
    fun `a list changed during a run takes another pass`() {
        AppContext.open(dataDir).use { context ->
            val first = context.document("hos spinners")
            val second = context.document("annat")
            context.rule("spinners", "Spinnars")
            var changed = false

            context.runCorrections(afterDocument = {
                if (!changed) {
                    changed = true
                    context.rule("annat", "Annat")
                }
            })

            assertEquals("hos Spinnars", context.pageText(first))
            assertEquals("Annat", context.pageText(second))
        }
    }

    @Test
    fun `a document published meanwhile is corrected from its new revision`() {
        AppContext.open(dataDir).use { context ->
            val matching = context.document("hos spinners")
            context.rule("spinners", "Spinnars")
            var rescanned: String? = null

            val job = context.runCorrections(beforePublish = { documentId ->
                if (rescanned == null) {
                    val active = context.revisions.activeRevisionId(documentId)
                    val candidate = RestoreFixtures.stage(context, documentId, active, listOf(RestoreFixtures.STABLE_PAGE, "ny spinners"), matching.unitIds)
                    context.revisionPublication.publish(documentId, active, candidate)
                    rescanned = candidate
                }
            })

            assertEquals(JobState.COMPLETE, job.state, lastFailure?.stackTraceToString())
            assertEquals("ny Spinnars", context.pageText(matching))
            val active = checkNotNull(context.revisions.activeRevision(matching.documentId))
            assertEquals(rescanned, active.parentRevisionId)
        }
    }

    @Test
    fun `an interruption at any publication step converges on the next run`() {
        PublicationStep.entries.forEach { step ->
            val dir = Files.createTempDirectory("infoscry-correction-kill")
            try {
                AppContext.open(dir).use { context -> context.seedCollectionWithId("default", "Default") }
                val fixture = AppContext.open(dir).use { context ->
                    val document = RestoreFixtures.publishGenerations(context, dir, collection, listOf("hos spinners"))
                    runBlocking { context.correctionService.put(collection, "r1", CorrectionRuleInput("spinners", "Spinnars", false)) }
                    val handler = CorrectionJobHandler(
                        revisions = context.revisions,
                        documents = context.documents,
                        corrections = context.corrections,
                        corrector = context.candidateCorrector,
                        publication = context.revisionPublication,
                        chunker = Chunker(WhitespaceTokenCounter(prefixTokens = 2, specialTokens = 1)),
                        documentEmbedder = { TestDocumentEmbedder() },
                        observePublication = { observed -> if (observed == step) throw Killed() },
                    )
                    val job = context.correctionService.apply(collection)
                    context.jobs.claim(job.id)
                    runCatching { runBlocking { handler.handle(checkNotNull(context.jobs.get(job.id)), JobStage(job.id, context.jobs, context.collections, context.mutations)) } }
                    document
                }
                // A new process: startup recovery finishes or discards the publication, and the job runs again.
                AppContext.open(dir).use { context ->
                    val job = context.correctionService.apply(collection)
                    context.jobs.claim(job.id)
                    val handler = CorrectionJobHandler(
                        revisions = context.revisions,
                        documents = context.documents,
                        corrections = context.corrections,
                        corrector = context.candidateCorrector,
                        publication = context.revisionPublication,
                        chunker = Chunker(WhitespaceTokenCounter(prefixTokens = 2, specialTokens = 1)),
                        documentEmbedder = { TestDocumentEmbedder() },
                    )
                    runBlocking { handler.handle(checkNotNull(context.jobs.get(job.id)), JobStage(job.id, context.jobs, context.collections, context.mutations)) }
                    val active = checkNotNull(context.revisions.activeRevisionId(fixture.documentId))
                    assertEquals("hos Spinnars", context.revisions.pages(active)[1].extractedText, "after a kill at $step")
                    val published = context.revisions.revisions(fixture.documentId)
                        .count { it.provenance == "CORRECTIONS" && (it.state == RevisionState.PUBLISHED || it.state == RevisionState.SUPERSEDED) }
                    assertEquals(1, published, "after a kill at $step")
                }
            } finally {
                dir.toFile().deleteRecursively()
            }
        }
    }

    @Test
    fun `no embedder fails the job and a later run continues`() {
        AppContext.open(dataDir).use { context ->
            val matching = context.document("hos spinners")
            context.rule("spinners", "Spinnars")

            val failed = context.runCorrections(embedder = { null })

            assertEquals(JobState.FAILED, failed.state)
            assertEquals("EMBEDDING_UNAVAILABLE", failed.errorCode)
            assertEquals(matching.revisionIds.last(), context.revisions.activeRevisionId(matching.documentId))

            val retried = context.runCorrections()

            assertEquals(JobState.COMPLETE, retried.state)
            assertNotEquals(matching.revisionIds.last(), context.revisions.activeRevisionId(matching.documentId))
            assertEquals("hos Spinnars", context.pageText(matching))
        }
    }
}
