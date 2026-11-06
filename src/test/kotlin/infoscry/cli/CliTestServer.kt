package infoscry.cli

import infoscry.chunk.Chunker
import infoscry.chunk.WhitespaceTokenCounter
import infoscry.embedding.QueryEmbedder
import infoscry.jobs.ImportJobHandler
import infoscry.server.ApiTestServer
import java.nio.file.Path

/**
 * A real InfoScry server for CLI tests, in this JVM, with a job worker that extracts with the process
 * harness's test extractor and embeds with the fake embedder.
 *
 * The CLI under test runs as a child process and reaches this server the way a person's CLI does: over HTTP
 * at `INFOSCRY_URL`, with a personal access token in `INFOSCRY_TOKEN`.
 */
internal class CliTestServer(
    val dataDir: Path,
    queryEmbedder: (() -> QueryEmbedder?)? = null,
    /** Whether a job worker claims queued jobs; a test that inspects the queue itself leaves it off. */
    worker: Boolean = true,
) : AutoCloseable {

    val api: ApiTestServer = ApiTestServer(dataDir, queryEmbedder = queryEmbedder)

    init {
        if (worker) ImportJobHandler.attachTo(
            api.context,
            ImportProcessHarness.harnessPipeline(api.context),
            chunker = Chunker(WhitespaceTokenCounter(prefixTokens = 2, specialTokens = 1)),
            documentEmbedder = { FakeDocumentEmbedder },
        )
    }

    val context get() = api.context

    /** What a CLI child needs to reach this server as the test administrator. */
    val environment: Map<String, String> = mapOf("INFOSCRY_URL" to api.url, "INFOSCRY_TOKEN" to api.bearer)

    fun run(vararg args: String, stdin: ByteArray? = null, timeout: Long = 60_000L): CliResult =
        CliProcess.run(*args, timeout = timeout, stdin = stdin, environment = environment)

    override fun close() {
        api.close()
    }
}
