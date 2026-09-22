package infoscry.cli

import infoscry.config.AppPaths
import infoscry.config.BearerToken
import infoscry.config.RuntimeInfo
import infoscry.domain.JobState
import infoscry.server.ApiJson
import infoscry.server.ApiTestServer
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
 * `infoscry import` as a real process, on both sides of the ownership question.
 *
 * Who runs an import is the whole design of this command: with a server running, the command must hand
 * the request over and return; with nobody owning the data directory, the command must take the lock and
 * stay alive until the work is done, because a job that outlives its only owner is a job nobody reports on
 * and nobody can stop. Both halves are invisible to a test that calls the command as a function, so both
 * are tested here by starting the real thing and, where it matters, holding its work open with a gate so
 * the states in between can be observed.
 */
class ImportCommandProcessTest {

    private lateinit var directory: Path
    private lateinit var dataDir: Path
    private lateinit var gate: Path

    @BeforeTest
    fun createTemporaryDirectories() {
        directory = Files.createTempDirectory("infoscry-import-cli")
        dataDir = Files.createDirectories(directory.resolve("data"))
        gate = directory.resolve("gate")
    }

    @AfterTest
    fun removeTemporaryDirectories() {
        directory.toFile().deleteRecursively()
    }

    @Test
    fun `a foreground import stays alive while the work is blocked and releases the directory when it ends`() {
        val source = writeSource("minutes.txt", "Ett protokoll.\n")
        val process = startHarness(gated = true, "import", "--data-dir", dataDir.toString(), "--collection", "Default", "--json", source.toString())

        awaitManagedCopy(process)
        assertTrue(process.isAlive, "the import command exited while its work was still running")

        Files.writeString(gate, "go")
        val exit = process.waitFor(WAIT_SECONDS)

        assertTrue(exit, "the import command did not finish after its work was released")
        assertEquals(0, process.exitCode(), "stderr=${process.stderr()}")
        val result = ApiJson.decodeFromString<ImportResult>(lastJsonLine(process))
        assertEquals("COMPLETE", result.state)
        assertEquals(1, result.imported)
        assertEquals(0, result.failed)

        // The lock is released, which is what a later command needs: importing and then not being able to
        // read the archive would be a strange kind of success.
        val list = CliProcess.run("--data-dir", dataDir.toString(), "collection", "list", "--json")
        assertEquals(0, list.exitCode, "a later command could not open the data directory: ${list.stderr}")
    }

    @Test
    fun `a foreground import reports every document and exits nonzero when one could not be read`() {
        val good = writeSource("good.txt", "Readable.\n")
        val blob = writeSource("blob.bin", ByteArray(256) { (it and 0xFF).toByte() })

        val result = runHarness(
            "import", "--data-dir", dataDir.toString(), "--collection", "Default", "--json",
            good.toString(), blob.toString(),
        )

        assertNotEquals(0, result.exitCode, "an import with a failed document reported success")
        val report = ApiJson.decodeFromString<ImportResult>(result.stdout.lines().last { it.isNotBlank() })
        assertEquals(1, report.imported)
        assertEquals(1, report.failed)
        assertTrue(report.items.any { it.sourcePath.endsWith("blob.bin") && it.errorCode == "UNSUPPORTED_MEDIA_TYPE" })
        assertContains(result.stderr, "1 of 2 document(s) could not be imported")
    }

    @Test
    fun `a server accepts an import and keeps working after the command has returned`() {
        val source = writeSource("report.txt", "En rapport.\n")
        val server = startHarness(gated = true, "serve", "--data-dir", dataDir.toString(), "--port", "0", "--json")
        try {
            server.awaitStdoutLine("{")

            val accepted = CliProcess.run(
                "import", "--data-dir", dataDir.toString(), "--collection", "Default", "--json", source.toString(),
            )

            assertEquals(0, accepted.exitCode, accepted.stderr)
            val report = ApiJson.decodeFromString<ImportAccepted>(accepted.stdout.lines().last { it.isNotBlank() })
            assertTrue(report.accepted)
            assertFalse(report.executedHere, "the import ran locally while a server owned the directory")
            // Accepted is not finished: the server still has the work, which is what the answer has to say.
            assertTrue(
                report.state == JobState.QUEUED.name || report.state == JobState.RUNNING.name,
                "the command reported ${report.state} for an import it only handed over",
            )
            assertTrue(server.isAlive, "the server exited when the import command returned")

            Files.writeString(gate, "go")
            val finished = awaitJobState(JobState.COMPLETE)
            assertEquals(JobState.COMPLETE, finished)
            assertTrue(managedOriginals(dataDir).size == 1)
        } finally {
            server.terminate()
        }
    }

    @Test
    fun `--wait waits for the server's work instead of returning when it is accepted`() {
        val source = writeSource("waited.txt", "Väntar.\n")
        val server = startHarness(gated = true, "serve", "--data-dir", dataDir.toString(), "--port", "0", "--json")
        try {
            server.awaitStdoutLine("{")
            val waiting = startHarness(
                gated = true,
                "import", "--data-dir", dataDir.toString(), "--collection", "Default", "--json", "--wait", source.toString(),
            )

            // The work is blocked, so a command that waited has to still be running.
            awaitManagedCopy(waiting)
            assertTrue(waiting.isAlive, "--wait returned before the import finished")

            Files.writeString(gate, "go")
            assertTrue(waiting.waitFor(WAIT_SECONDS), "--wait never finished")
            assertEquals(0, waiting.exitCode())
            val report = ApiJson.decodeFromString<ImportResult>(lastJsonLine(waiting))
            assertEquals(1, report.imported)
            assertEquals("COMPLETE", report.state)
        } finally {
            server.terminate()
        }
    }

    @Test
    fun `a runtime file from a dead process does not send the command to a dead server`() {
        val source = writeSource("orphan.txt", "Ingen server.\n")
        // A killed process leaves this file behind; being sent to its port would be a confusing way to
        // learn that nobody is listening.
        RuntimeInfo(pid = 999_999L, port = 1, bearerToken = BearerToken.new())
            .writeTo(AppPaths.of(dataDir).runtimeFile)

        val result = CliProcess.run(
            "import", "--data-dir", dataDir.toString(), "--collection", "Default", source.toString(),
        )

        // The dead runtime file must not send the command to a dead server: the import runs in this
        // process. Without an installed embedding model the document's outcome is the model remedy rather
        // than a finished import, so the assertion here is about where the import went, not that it
        // completed — the bytes land locally, never on the dead process's port.
        assertEquals(1, managedOriginals(dataDir).size)
        assertContains(result.stderr, "importing in this process")
        assertContains(result.stdout + result.stderr, "MODEL_NOT_INSTALLED")
    }

    @Test
    fun `a second foreground import refuses the directory the first one holds`() {
        val first = writeSource("first.txt", "Först.\n")
        val second = writeSource("second.txt", "Sedan.\n")
        val holder = startHarness(gated = true, "import", "--data-dir", dataDir.toString(), "--collection", "Default", first.toString())
        try {
            awaitManagedCopy(holder)

            val refused = CliProcess.run(
                "import", "--data-dir", dataDir.toString(), "--collection", "Default", second.toString(),
            )

            assertNotEquals(0, refused.exitCode, "a second import ran while another process owned the directory")
            assertTrue(
                refused.stderr.contains(dataDir.toString()) || refused.stderr.contains("already"),
                "the refusal did not say what it was about: ${refused.stderr}",
            )
        } finally {
            Files.writeString(gate, "go")
            holder.waitFor(WAIT_SECONDS)
        }
    }

    @Test
    fun `a server-owned import refuses maintenance before the CLI creates a job`() = runBlocking {
        val source = writeSource("maintenance.txt", "Under maintenance.\n")
        ApiTestServer(dataDir).use { server ->
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val maintenance = async(Dispatchers.Default) {
                server.context.mutations.withExclusiveMaintenance("reindex") {
                    started.complete(Unit)
                    release.await()
                }
            }
            withTimeout(WAIT_SECONDS) { started.await() }

            val refused = CliProcess.run(
                "import", "--data-dir", dataDir.toString(), "--collection", "Default", "--json", source.toString(),
            )

            assertNotEquals(0, refused.exitCode, "stdout=${refused.stdout} stderr=${refused.stderr}")
            assertContains(refused.stderr, "MAINTENANCE_IN_PROGRESS")
            assertEquals(0, server.context.jobs.list(100, 0).size, "refused CLI import must not create a job row")

            release.complete(Unit)
            withTimeout(WAIT_SECONDS) { maintenance.await() }
        }
    }

    private fun awaitJobState(expected: JobState): JobState {
        var latest = JobState.QUEUED
        awaitUntil("the job to reach $expected") {
            val jobs = CliProcess.run("--data-dir", dataDir.toString(), "jobs", "--json")
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

    private fun writeSource(name: String, content: ByteArray): Path {
        val file = directory.resolve(name)
        Files.write(file, content)
        return file
    }

    private fun lastJsonLine(process: HarnessCli): String =
        process.stdout().last { it.trim().startsWith("{") }

    /** Starts the CLI harness, optionally with the extractor held at its gate. */
    private fun startHarness(gated: Boolean, vararg args: String): HarnessCli {
        val builder = ProcessBuilder(harnessArgs(*args).toList())
        if (gated) {
            builder.environment()["INFOSCRY_TEST_GATE"] = gate.toString()
        } else {
            builder.environment().remove("INFOSCRY_TEST_GATE")
        }
        return HarnessCli(builder.start())
    }

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

    /** The command line a script would use: this JDK, the test classpath, and the CLI's main class. */
    private fun harnessArgs(vararg args: String): Array<String> = arrayOf(
        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
        "--enable-native-access=ALL-UNNAMED",
        "-cp",
        System.getProperty("java.class.path"),
        "infoscry.cli.ImportProcessHarness",
        *args,
    )

    /** Runs the harness CLI to completion and returns what it produced, the way a script reads it. */
    private fun runHarness(vararg args: String): CliResult {
        val harness = HarnessCli(ProcessBuilder(harnessArgs(*args).toList()).start())
        if (!harness.waitFor(WAIT_SECONDS)) {
            harness.terminate()
            throw AssertionError("the harness did not finish: stdout=${harness.stdout()} stderr=${harness.stderr()}")
        }
        return CliResult(harness.exitCode(), harness.stdout().joinToString("\n"), harness.stderr())
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
