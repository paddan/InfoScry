package infoscry.server

import infoscry.EXTERNAL_TAG
import infoscry.domain.CollectionId
import infoscry.embedding.QueryEmbedder
import infoscry.jobs.Harness
import infoscry.jobs.SingleLargeUnit
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.jupiter.api.Tag

/** Real Chromium and HTTP against a disposable archive: a glossary group widens search and is removed again. */
@Tag(EXTERNAL_TAG)
class GlossaryBrowserAcceptanceTest {

    @Test
    fun `a glossary group makes one name find the other and stops when it is removed`() {
        val tempDir = Files.createTempDirectory("infoscry-glossary-browser-")
        try {
            val texts = listOf("letter.txt" to "Brev från Stockholm i maj", "minutes.txt" to "Möte i Sthlm i dag")
            Harness(tempDir).use { importer ->
                texts.forEach { (name, text) -> importer.importDurably(listOf(importer.writeText(name, text)), SingleLargeUnit(text)) }
            }
            ApiTestServer(tempDir.resolve("data"), queryEmbedder = { QueryEmbedder { VECTOR.copyOf() } }).use { harness ->
                val script = Paths.get(System.getProperty("user.dir"), "web", "e2e", "glossary-browser-acceptance.mjs")
                val outputFile = Files.createTempFile("infoscry-glossary-browser-", ".log")
                try {
                    val process = ProcessBuilder("node", script.toString())
                        .directory(script.parent.toFile())
                        .redirectErrorStream(true)
                        .redirectOutput(outputFile.toFile())
                        .apply { environment()["BASE_URL"] = harness.url }
                        .start()
                    if (!process.waitFor(180, TimeUnit.SECONDS)) {
                        process.destroyForcibly().waitFor(5, TimeUnit.SECONDS)
                        error("Glossary browser acceptance timed out: ${Files.readString(outputFile)}")
                    }
                    check(process.exitValue() == 0) { "Glossary browser acceptance failed: ${Files.readString(outputFile)}" }
                } finally {
                    Files.deleteIfExists(outputFile)
                }
                // The text itself was never touched: the glossary only changes how it is searched.
                assertTrue(harness.context.glossaryService.snapshot(CollectionId("default")).groups.isEmpty())
            }
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    companion object {
        private val VECTOR = FloatArray(768) { if (it == 0) 1f else 0f }
    }
}
