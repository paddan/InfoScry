package infoscry.server

import infoscry.EXTERNAL_TAG
import infoscry.domain.Chunk
import infoscry.domain.ChunkId
import infoscry.domain.ContentUnitId
import infoscry.domain.Document
import infoscry.domain.DocumentId
import infoscry.domain.DocumentStatus
import infoscry.domain.SourceLocation
import infoscry.embedding.QueryEmbedder
import infoscry.search.DocumentRow
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import org.junit.jupiter.api.Tag

/** Real Chromium and HTTP against a disposable archive, with a local deterministic query vector. */
@Tag(EXTERNAL_TAG)
class SearchBrowserAcceptanceTest {
    @Test
    fun `live filters retire old replies and highlighted results remain inert`() {
        val dataDir = Files.createTempDirectory("infoscry-search-browser-")
        try {
            ApiTestServer(dataDir, queryEmbedder = { QueryEmbedder { VECTOR.copyOf() } }).use { harness ->
                val collection = harness.context.collections.create("Search fixtures")
                runBlocking {
                    for ((filename, path, text, createdAt) in listOf(
                        listOf("report.txt", "reports/report.txt", "mark budget Ångström <img src=x onerror=\"window.__searchXss=true\">", "2026-03-01T00:00:00.000Z"),
                        listOf("receipt.txt", "receipts/receipt.txt", "mark budget receipt", "2026-03-01T23:59:59.999Z"),
                        listOf("number.txt", "numbers/number.txt", "7", "2026-03-02T00:00:00.000Z"),
                    )) {
                        val id = DocumentId.new()
                        val unit = ContentUnitId.new()
                        harness.context.documents.insert(Document(
                            id = id,
                            collectionId = collection.id,
                            sha256 = "fixture-$filename",
                            mediaType = "text/plain",
                            originalFilename = filename,
                            sourcePath = path,
                            sizeBytes = text.length.toLong(),
                            status = DocumentStatus.COMPLETE,
                            title = filename,
                            author = null,
                            language = null,
                            createdAt = createdAt,
                            updatedAt = createdAt,
                        ))
                        harness.context.index().replaceDocument(listOf(DocumentRow(
                            collectionId = collection.id,
                            documentId = id,
                            unitId = unit,
                            locator = SourceLocation.TextLines(1, 1),
                            locatorLabel = "line 1",
                            chunk = Chunk(
                                id = ChunkId.new(), contentUnitId = unit, ordinal = 0, text = text,
                                startOffset = 0, endOffset = text.length, tokenCount = text.length,
                                tokenStart = 0, tokenEnd = text.length - 1,
                            ),
                            vector = VECTOR.copyOf(),
                        )))
                    }
                }
                val script = Paths.get(System.getProperty("user.dir"), "web", "e2e", "search-browser-acceptance.mjs")
                val outputFile = Files.createTempFile("infoscry-search-browser-", ".log")
                try {
                    val process = ProcessBuilder("node", script.toString())
                        .directory(script.parent.toFile())
                        .redirectErrorStream(true)
                        .redirectOutput(outputFile.toFile())
                        .apply { environment()["BASE_URL"] = harness.url }
                        .start()
                    if (!process.waitFor(90, TimeUnit.SECONDS)) {
                        process.destroyForcibly().waitFor(5, TimeUnit.SECONDS)
                        error("Search browser acceptance timed out: ${Files.readString(outputFile)}")
                    }
                    check(process.exitValue() == 0) { "Search browser acceptance failed: ${Files.readString(outputFile)}" }
                } finally {
                    Files.deleteIfExists(outputFile)
                }
            }
        } finally {
            dataDir.toFile().deleteRecursively()
        }
    }

    companion object {
        private val VECTOR = FloatArray(768) { if (it == 0) 1f else 0f }
    }
}
