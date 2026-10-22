package infoscry.server

import infoscry.EXTERNAL_TAG
import infoscry.chunk.Chunker
import infoscry.chunk.WhitespaceTokenCounter
import infoscry.domain.CollectionId
import infoscry.embedding.TestDocumentEmbedder
import infoscry.jobs.Harness
import infoscry.jobs.ImportJobHandler
import infoscry.jobs.SingleLargeUnit
import infoscry.embedding.QueryEmbedder
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.jupiter.api.Tag

/** Real Chromium and HTTP against a disposable archive: correcting a misread word across a collection. */
@Tag(EXTERNAL_TAG)
class CorrectionsBrowserAcceptanceTest {

    @Test
    fun `a misread word is corrected from the reader, shown with its reading and removed again`() {
        val tempDir = Files.createTempDirectory("infoscry-corrections-browser-")
        try {
            val texts = listOf("letter.txt" to "Brev från spinners i Malmö", "minutes.txt" to "Möte hos spinners i dag")
            Harness(tempDir).use { importer ->
                texts.forEach { (name, text) -> importer.importDurably(listOf(importer.writeText(name, text)), SingleLargeUnit(text)) }
            }
            ApiTestServer(tempDir.resolve("data"), queryEmbedder = { QueryEmbedder { VECTOR.copyOf() } }).use { harness ->
                ImportJobHandler.attachTo(
                    harness.context,
                    documentEmbedder = { TestDocumentEmbedder() },
                    chunker = Chunker(WhitespaceTokenCounter(prefixTokens = 2, specialTokens = 1)),
                )
                val script = Paths.get(System.getProperty("user.dir"), "web", "e2e", "corrections-browser-acceptance.mjs")
                val outputFile = Files.createTempFile("infoscry-corrections-browser-", ".log")
                try {
                    val process = ProcessBuilder("node", script.toString())
                        .directory(script.parent.toFile())
                        .redirectErrorStream(true)
                        .redirectOutput(outputFile.toFile())
                        .apply { environment()["BASE_URL"] = harness.url }
                        .start()
                    if (!process.waitFor(180, TimeUnit.SECONDS)) {
                        process.destroyForcibly().waitFor(5, TimeUnit.SECONDS)
                        error("Corrections browser acceptance timed out: ${Files.readString(outputFile)}")
                    }
                    check(process.exitValue() == 0) { "Corrections browser acceptance failed: ${Files.readString(outputFile)}" }
                } finally {
                    Files.deleteIfExists(outputFile)
                }
                // Each document was rewritten twice, once by the rule and once back, and reads as it was read.
                val context = harness.context
                val documents = context.documents.listByCollection(CollectionId("default"), limit = 10)
                assertEquals(2, documents.size)
                documents.forEach { document ->
                    val revisions = context.revisions.revisions(document.id)
                    assertEquals(2, revisions.count { it.provenance == "CORRECTIONS" }, "corrected and corrected back")
                    val active = checkNotNull(context.revisions.activeRevisionId(document.id))
                    val page = context.revisions.pages(active).single()
                    assertEquals(true, "spinners" in page.extractedText)
                    assertEquals(null, page.reading)
                }
            }
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    companion object {
        private val VECTOR = FloatArray(768) { if (it == 0) 1f else 0f }
    }
}
