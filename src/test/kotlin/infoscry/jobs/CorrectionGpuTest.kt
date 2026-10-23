package infoscry.jobs

import infoscry.AppContext
import infoscry.GPU_TAG
import infoscry.MODEL_TAG
import infoscry.chunk.Chunker
import infoscry.config.AppPaths
import infoscry.correction.CorrectionRuleInput
import infoscry.document.RestoreFixtures
import infoscry.domain.CollectionId
import infoscry.embedding.E5Embedder
import infoscry.embedding.GpuRuntime
import infoscry.embedding.ModelInstallation
import infoscry.embedding.ModelManager
import infoscry.embedding.ModelManifest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Tag

/**
 * The hardware gate for collection corrections: a CORRECTIONS job rewrites a page and embeds it with the pinned
 * model on CoreML, and the publication accepts those vectors. It runs only under `./gradlew gpuIntegrationTest`
 * and fails, never skips, when the model or the accelerator is missing.
 */
@Tag(GPU_TAG)
@Tag(MODEL_TAG)
class CorrectionGpuTest {

    private val manifest = ModelManifest.load()

    private val models = AppPaths.from(
        Path.of(System.getProperty("infoscry.dataDir") ?: "${System.getProperty("user.home")}/.infoscry"),
    ).modelsDir

    @Test
    fun `a correction job embeds the rewritten page on coreml and publishes it`() {
        val problems = ModelManager(manifest).verifyInstalled(models)
        assertTrue(problems.isEmpty(), "the pinned model is not installed in $models: $problems")
        val readiness = GpuRuntime(manifest).probe()
        assertTrue(readiness.ready, "the validated platform must be ready: ${readiness.reasons}")
        val profile = Path.of("build", "gpu-validation").also { Files.createDirectories(it) }
        val embedder = E5Embedder.production(manifest, ModelInstallation(manifest, models.resolve(manifest.revision)), profileDirectory = profile)
        val chunker = Chunker(E5Embedder.productionCounter(models, profile))
        val dataDir = Files.createTempDirectory("infoscry-correction-gpu")
        val collection = CollectionId("default")
        try {
            AppContext.open(dataDir).use { context -> context.seedCollectionWithId("default", "Default") }
            AppContext.open(dataDir).use { context ->
                val document = RestoreFixtures.publishGenerations(context, dataDir, collection, listOf("Brev från spinners i Malmö"))
                runBlocking { context.correctionService.put(collection, "r1", CorrectionRuleInput("spinners", "Spinnars", false)) }
                val job = context.correctionService.apply(collection)
                context.jobs.claim(job.id)

                runBlocking {
                    CorrectionJobHandler(
                        revisions = context.revisions,
                        documents = context.documents,
                        corrections = context.corrections,
                        corrector = context.candidateCorrector,
                        publication = context.revisionPublication,
                        chunker = chunker,
                        documentEmbedder = { embedder },
                    ).handle(checkNotNull(context.jobs.get(job.id)), JobStage(job.id, context.jobs, context.collections, context.mutations))
                }

                val active = checkNotNull(context.revisions.activeRevision(document.documentId))
                assertEquals("CORRECTIONS", active.provenance)
                assertEquals("Brev från Spinnars i Malmö", context.revisions.pages(active.id)[1].extractedText)
                val rewritten = context.revisions.chunks(active.id).filter { it.unitOrdinal == 1 }
                assertTrue(rewritten.isNotEmpty(), "the rewritten page was chunked again")
                rewritten.forEach { chunk ->
                    val vector = checkNotNull(chunk.embedding) { "every passage of the rewritten page has a vector" }
                    assertEquals(768, vector.size)
                    val norm = sqrt(vector.sumOf { (it * it).toDouble() })
                    assertTrue(vector.all(Float::isFinite) && abs(norm - 1.0) < 1e-3, "a unit-length vector from the pinned model")
                    assertTrue("Spinnars" in chunk.text)
                }
            }
        } finally {
            dataDir.toFile().deleteRecursively()
        }
    }
}
