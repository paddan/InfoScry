package infoscry.cli

import infoscry.domain.JobState
import infoscry.server.ApiJson
import infoscry.server.JobsResponse
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * `infoscry import` as a real process asking a running server.
 *
 * The server owns every job: the command previews, confirms what the reading method sends, and answers with
 * the accepted job, or with `--wait` follows it and reports every document. The server here runs in the
 * test's JVM with the process harness's extractor, which can be held at a gate so the states in between can
 * be observed.
 */
class ImportCommandProcessTest {

    private lateinit var directory: Path
    private lateinit var dataDir: Path
    private lateinit var gate: Path
    private lateinit var cli: CliTestServer

    @BeforeTest
    fun createTemporaryDirectories() {
        directory = Files.createTempDirectory("infoscry-import-cli")
        dataDir = Files.createDirectories(directory.resolve("data"))
        gate = directory.resolve("gate")
        ImportProcessHarness.gate = null
        cli = CliTestServer(dataDir)
        // Every import in this class targets Default, and a new archive no longer holds one.
        cli.run("collection", "create", "Default")
    }

    @AfterTest
    fun removeTemporaryDirectories() {
        // Released before the server stops, so a held attempt does not keep the shutdown waiting.
        runCatching { Files.writeString(gate, "go") }
        cli.close()
        ImportProcessHarness.gate = null
        directory.toFile().deleteRecursively()
    }

    @Test
    fun `method selection prints the preview and an identical command returns the same job`() {
        val source = writeSource("repeat.txt", "Read once.\n")
        val args = arrayOf("import", "--collection", "Default",
            "--method", "tesseract", "--yes", "--json", "--wait", source.toString())
        val first = cli.run(*args)
        assertEquals(0, first.exitCode, first.stderr)
        assertContains(first.stderr, "this machine")
        val again = cli.run(*args)
        assertEquals(0, again.exitCode, again.stderr)
        val one = ApiJson.decodeFromString<ImportResult>(first.stdout.lines().last { it.isNotBlank() })
        val two = ApiJson.decodeFromString<ImportResult>(again.stdout.lines().last { it.isNotBlank() })
        assertEquals(one.jobId, two.jobId)
        assertEquals(1, two.imported)
    }

    @Test
    fun `No OCR says that each page keeps the document's own text`() {
        val source = writeSource("own-text.txt", "Kept as it is.\n")

        val result = cli.run(
            "import", "--collection", "Default",
            "--method", "none", "--json", source.toString(),
        )

        assertEquals(0, result.exitCode, result.stderr)
        assertContains(result.stderr, "No OCR: no page is read; each page keeps the document's own text.")
    }

    @Test
    fun `--wait reports every document and exits nonzero when one could not be read`() {
        val good = writeSource("good.txt", "Readable.\n")
        // A supported file whose extraction fails: it is an item and a failure, as it always was.
        val broken = writeSource("broken.txt", "${HarnessExtractor.FAILING_CONTENT}\n")
        // Unsupported content is skipped: it is not a document, not a failure and not one of the import's files.
        val blob = writeSource("blob.bin", ByteArray(256) { (it and 0xFF).toByte() })

        val result = cli.run(
            "import", "--collection", "Default", "--json", "--wait",
            good.toString(), broken.toString(), blob.toString(),
        )

        assertNotEquals(0, result.exitCode, "an import with a failed document reported success")
        val report = ApiJson.decodeFromString<ImportResult>(result.stdout.lines().last { it.isNotBlank() })
        assertEquals(1, report.imported)
        assertEquals(1, report.failed)
        assertEquals(listOf("broken.txt", "good.txt"), report.items.map { it.sourceName!! }.sorted())
        assertTrue(report.items.any { it.sourceName == "broken.txt" && it.errorCode == "EXTRACTION_FAILED" })
        assertContains(result.stderr, "1 of 2 document(s) could not be imported")
    }

    @Test
    fun `an import whose every file is unsupported exits zero with no documents`() {
        val blob = writeSource("blob.bin", ByteArray(256) { (it and 0xFF).toByte() })

        val result = cli.run(
            "import", "--collection", "Default", "--json", "--wait", blob.toString(),
        )

        assertEquals(0, result.exitCode, result.stderr)
        val report = ApiJson.decodeFromString<ImportResult>(result.stdout.lines().last { it.isNotBlank() })
        assertEquals(0, report.imported)
        assertEquals(0, report.failed)
        assertTrue(report.items.isEmpty(), "a skipped file must not appear in the import's items")
        assertFalse((result.stdout + result.stderr).contains("UNSUPPORTED_MEDIA_TYPE"))
    }

    @Test
    fun `--recursive descends into a directory while the default reads only its top level`() {
        val inbox = Files.createDirectories(directory.resolve("inbox"))
        Files.writeString(inbox.resolve("top.txt"), "Top.\n")
        Files.writeString(Files.createDirectories(inbox.resolve("nested")).resolve("deep.txt"), "Deep.\n")

        val plain = cli.run(
            "import", "--collection", "Default", "--json", "--wait",
            inbox.toString(),
        )
        assertEquals(0, plain.exitCode, plain.stderr)
        val plainReport = ApiJson.decodeFromString<ImportResult>(plain.stdout.lines().last { it.isNotBlank() })
        assertEquals(
            listOf("top.txt"),
            plainReport.items.map { it.sourceName!! }.sorted(),
        )

        val recursive = cli.run(
            "import", "--collection", "Default", "--json", "--wait", "--recursive",
            inbox.toString(),
        )
        assertEquals(0, recursive.exitCode, recursive.stderr)
        val recursiveReport = ApiJson.decodeFromString<ImportResult>(recursive.stdout.lines().last { it.isNotBlank() })
        assertEquals(
            listOf("deep.txt", "top.txt"),
            recursiveReport.items.map { it.sourceName!! }.sorted(),
        )
    }

    @Test
    fun `--include imports only the listed types and --exclude everything else`() {
        val notes = writeSource("notes.txt", "Readable.\n")
        val log = writeSource("run.log", "Logged.\n")

        val included = cli.run(
            "import", "--collection", "Default", "--json", "--wait",
            "--include=.TXT", notes.toString(), log.toString(),
        )
        assertEquals(0, included.exitCode, included.stderr)
        val includedReport = ApiJson.decodeFromString<ImportResult>(included.stdout.lines().last { it.isNotBlank() })
        assertEquals(
            listOf("notes.txt"),
            includedReport.items.map { it.sourceName!! },
        )

        // Fresh files for the exclude run: the include run above already holds notes.txt.
        val other = writeSource("other.txt", "Other.\n")
        val trace = writeSource("trace.log", "Traced.\n")
        val excluded = cli.run(
            "import", "--collection", "Default", "--json", "--wait",
            "--exclude=log", other.toString(), trace.toString(),
        )
        assertEquals(0, excluded.exitCode, excluded.stderr)
        val excludedReport = ApiJson.decodeFromString<ImportResult>(excluded.stdout.lines().last { it.isNotBlank() })
        assertEquals(
            listOf("other.txt"),
            excludedReport.items.map { it.sourceName!! },
        )
    }

    @Test
    fun `--include and --exclude together exit nonzero and create no job`() {
        val notes = writeSource("notes.txt", "Readable.\n")

        val result = cli.run(
            "import", "--collection", "Default",
            "--include=txt", "--exclude=log", notes.toString(),
        )

        assertNotEquals(0, result.exitCode, "both lists were accepted: ${result.stdout}")
        assertContains(result.stderr, "--include and --exclude cannot be used together")
        assertEquals(0, cli.context.jobs.list(100, 0).size, "a refused import must not create a job")
    }

    @Test
    fun `the server accepts an import and keeps working after the command has returned`() {
        val source = writeSource("report.txt", "En rapport.\n")
        ImportProcessHarness.gate = gate

        val accepted = cli.run("import", "--collection", "Default", "--json", source.toString())

        assertEquals(0, accepted.exitCode, accepted.stderr)
        val report = ApiJson.decodeFromString<ImportAccepted>(accepted.stdout.lines().last { it.isNotBlank() })
        assertTrue(report.accepted)
        // Accepted is not finished: the server still has the work, which is what the answer has to say.
        assertTrue(
            report.state == JobState.QUEUED.name || report.state == JobState.RUNNING.name,
            "the command reported ${report.state} for an import it only handed over",
        )

        Files.writeString(gate, "go")
        assertEquals(JobState.COMPLETE, awaitJobState(JobState.COMPLETE))
        assertTrue(managedOriginals(dataDir).size == 1)
    }

    @Test
    fun `--wait waits for the server's work instead of returning when it is accepted`() {
        val source = writeSource("waited.txt", "Väntar.\n")
        ImportProcessHarness.gate = gate
        val waiting = startCli("import", "--collection", "Default", "--json", "--wait", source.toString())

        // The work is blocked, so a command that waited has to still be running.
        awaitManagedCopy(waiting)
        assertTrue(waiting.isAlive, "--wait returned before the import finished")

        Files.writeString(gate, "go")
        assertTrue(waiting.waitFor(WAIT_SECONDS), "--wait never finished")
        assertEquals(0, waiting.exitCode())
        val report = ApiJson.decodeFromString<ImportResult>(lastJsonLine(waiting))
        assertEquals(1, report.imported)
        assertEquals("COMPLETE", report.state)
    }

    @Test
    fun `--wait reports the failure sentence the API derived, not the stored one`() {
        val broken = writeSource("broken.txt", "${HarnessExtractor.FAILING_CONTENT}\n")
            val result = cli.run(
                "import", "--collection", "Default", "--json", "--wait", broken.toString(),
            )

            assertNotEquals(0, result.exitCode, "a failed server-owned item must still make --wait fail")
            assertContains(result.stdout, "EXTRACTION_FAILED")
            assertContains(result.stdout, "broken.txt")
            assertContains(result.stdout, "the extractor stopped before it delivered every unit of this document")
            // The message stored beside the item names the media type it detected, and that text is the
            // server's diagnostic rather than the API's answer: only the derived sentence crosses.
            assertTrue(!result.stdout.contains("application/octet-stream"), result.stdout)
            assertTrue(!result.stdout.contains(dataDir.toString()), result.stdout)
    }

    @Test
    fun `--wait in text reports the per-file failure the API derived`() {
        val broken = writeSource("broken.txt", "${HarnessExtractor.FAILING_CONTENT}\n")
            val result = cli.run(
                "import", "--collection", "Default", "--wait", broken.toString(),
            )
            val output = result.stdout + result.stderr

            assertNotEquals(0, result.exitCode)
            assertContains(output, "EXTRACTION_FAILED")
            assertContains(output, "the extractor stopped before it delivered every unit of this document")
            assertFalse(output.contains("application/octet-stream"), output)
            assertFalse(output.contains(dataDir.toString()), output)
    }

    @Test
    fun `an import refused by maintenance creates no job`() = runBlocking {
        val source = writeSource("maintenance.txt", "Under maintenance.\n")
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val maintenance = async(Dispatchers.Default) {
            cli.context.mutations.withExclusiveMaintenance("reindex") {
                started.complete(Unit)
                release.await()
            }
        }
        withTimeout(WAIT_SECONDS * 1000) { started.await() }

        val refused = cli.run("import", "--collection", "Default", "--json", source.toString())

        assertNotEquals(0, refused.exitCode, "stdout=${refused.stdout} stderr=${refused.stderr}")
        assertContains(refused.stderr, "MAINTENANCE_IN_PROGRESS")
        assertEquals(0, cli.context.jobs.list(100, 0).size, "refused CLI import must not create a job row")

        release.complete(Unit)
        withTimeout(WAIT_SECONDS * 1000) { maintenance.await() }
    }

    @Test
    fun `an external default prompts before enqueue and no answer aborts`() {
        val source = writeSource("scan.txt", "Scanned text.\n")
        externalSelection()
        val result = cli.run("import", "--collection", "Default",
            "--json", source.toString())
        assertNotEquals(0, result.exitCode)
        assertContains(result.stderr, "example.invalid")
        assertContains(result.stderr, "Send")
        assertContains(result.stderr, "All pages in these files")
        assertContains(result.stderr, source.toRealPath().toString())
        assertFalse(result.stderr.contains("Estimated cost: USD"), result.stderr)
        assertTrue(cli.context.jobs.list(100).isEmpty(), "declining confirmation must create no job")
    }

    @Test
    fun `an unavailable method is a readable CLI refusal without a stack trace or job`() {
        val source = writeSource("unavailable.txt", "Text.\n")
        externalSelection(apiKeyEnvironmentVariable = null)
        val result = cli.run("import", "--collection", "Default", source.toString())
        assertNotEquals(0, result.exitCode)
        assertContains(result.stderr, "METHOD_UNAVAILABLE:")
        assertFalse(result.stderr.contains("Exception in thread"), result.stderr)
        assertTrue(cli.context.jobs.list(100).isEmpty())
    }

    private fun awaitJobState(expected: JobState): JobState {
        var latest = JobState.QUEUED
        awaitUntil("the job to reach $expected") {
            val jobs = cli.run("jobs", "--json")
            if (jobs.exitCode != 0) return@awaitUntil false
            val parsed = ApiJson.decodeFromString<JobsResponse>(jobs.stdout.lines().last { it.isNotBlank() })
            latest = parsed.jobs.firstOrNull()?.state ?: return@awaitUntil false
            latest == expected
        }
        return latest
    }

    private fun managedOriginals(dataDir: Path): List<Path> {
        val library = dataDir.resolve("library")
        if (!Files.isDirectory(library)) return emptyList()
        return Files.walk(library).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.fileName.toString().startsWith("original.") }.toList()
        }
    }

    private fun writeSource(name: String, content: String): Path {
        val file = directory.resolve(name)
        Files.writeString(file, content)
        return file
    }

    /**
     * The collection's OCR selection as an import admitted with an external page scope sees it: the image model
     * reads the pages, and the job may send [allowance] distinct pages before a person has to approve more.
     *
     * It is written before any process opens the archive, because the collection's settings are what the
     * enqueueing side resolves into the job's snapshot.
     */
    private fun externalSelection(apiKeyEnvironmentVariable: String? = "PATH") {
        cli.context.let { context ->
            val profile = context.ocrProfiles.create(
                name = "Vision transcriber",
                draft = infoscry.ocr.OcrProfileRevisionDraft(
                    provider = infoscry.llm.LlmProvider.OPENAI_COMPATIBLE,
                    model = "vision-model",
                    contextWindow = 32_000,
                    maxOutputTokens = 2_048,
                    endpoint = "https://example.invalid/v1",
                    apiKeyEnvironmentVariable = apiKeyEnvironmentVariable,
                    inputPricePerMillion = 1.0,
                    outputPricePerMillion = 2.0,
                ),
                enabled = true,
            )
            val collection = context.collectionService.requireActiveByNameOrId("Default")
            context.ocrProfiles.recordImageCapability(profile.revision.revisionId, true, infoscry.storage.Instants.now())
            context.collections.updateOcrSettings(collection.id,
                infoscry.ocr.CollectionOcrSettings(language = "eng", defaultMethod = infoscry.ocr.ReadingMethod.Llm(profile.id)))
        }
    }

    private fun writeSource(name: String, content: ByteArray): Path {
        val file = directory.resolve(name)
        Files.write(file, content)
        return file
    }

    private fun lastJsonLine(process: HarnessCli): String =
        process.stdout().last { it.trim().startsWith("{") }

    /** Starts the CLI against the test server without waiting for it. */
    private fun startCli(vararg args: String): HarnessCli = HarnessCli(CliProcess.start(*args, environment = cli.environment))

    /**
     * One harness process, with its output pumped as it arrives.
     *
     * The pumping matters: a child that blocks on its work also writes, and reading only after it exits
     * would hide the moment this whole test is about — a command that is still alive mid-import.
     */
    private class HarnessCli(private val process: Process) {

        private val stdout = java.util.Collections.synchronizedList(mutableListOf<String>())
        private val stderr = java.util.Collections.synchronizedList(mutableListOf<String>())

        init {
            pump(process.inputStream, stdout)
            pump(process.errorStream, stderr)
        }

        val isAlive: Boolean get() = process.isAlive

        fun pid(): Long = process.pid()

        fun stdout(): List<String> = stdout.toList()

        fun stderr(): String = stderr.joinToString("\n")

        fun exitCode(): Int = process.exitValue()

        fun waitFor(seconds: Long): Boolean = process.waitFor(seconds, TimeUnit.SECONDS)

        fun awaitStdoutLine(prefix: String): String {
            val deadline = System.nanoTime() + WAIT_SECONDS * 1_000_000_000
            while (System.nanoTime() < deadline) {
                stdout.firstOrNull { it.startsWith(prefix) }?.let { return it }
                if (!process.isAlive) break
                Thread.sleep(POLL_MILLIS)
            }
            throw AssertionError("the harness never printed a line starting with '$prefix'; stdout=${stdout()} stderr=${stderr()}")
        }

        fun terminate() {
            process.destroy()
            if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly()
        }

        private fun pump(stream: java.io.InputStream, into: MutableList<String>) {
            val thread = Thread { stream.bufferedReader().useLines { lines -> lines.forEach { into += it } } }
            thread.isDaemon = true
            thread.start()
        }
    }

    /** Waits for the managed copy, and reports what the child said when it never comes. */
    private fun awaitManagedCopy(process: HarnessCli) {
        try {
            awaitUntil("the managed copy to be written") { managedOriginals(dataDir).size == 1 }
        } catch (failure: AssertionError) {
            throw AssertionError(
                "${failure.message}; alive=${process.isAlive} stdout=${process.stdout()} stderr=${process.stderr()}",
            )
        }
    }

    private fun awaitUntil(what: String, timeoutMillis: Long = WAIT_SECONDS * 1000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(POLL_MILLIS)
        }
        throw AssertionError("timed out waiting for $what")
    }

    private companion object {
        const val WAIT_SECONDS = 60L
        const val POLL_MILLIS = 25L
    }
}
