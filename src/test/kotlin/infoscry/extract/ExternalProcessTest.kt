package infoscry.extract

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/**
 * How a child process is started, waited for, and stopped.
 *
 * Every property here is one the pipeline depends on and none of them is visible from a successful run:
 * that arguments are never handed to a shell, that a tool which never finishes is killed rather than
 * waited for, that a cancelled attempt does not leave a process behind writing into the data directory,
 * and that a tool which writes without stopping cannot exhaust this process's memory.
 *
 * The tools are shell scripts the test writes itself, so a failure is never the environment's: a slow
 * tool is slow because the script sleeps, and a missing tool is missing because the path does not exist.
 */
class ExternalProcessTest {

    private lateinit var directory: Path

    @BeforeTest
    fun createTemporaryDirectory() {
        directory = Files.createTempDirectory("infoscry-external-process")
    }

    @AfterTest
    fun removeTemporaryDirectory() {
        directory.toFile().deleteRecursively()
    }

    // ---- The command is a list, never a shell string -------------------------------------------------

    @Test
    fun `an argument keeps its spaces quotes and expansions`() = runBlocking {
        val tool = writeFakeExecutable(directory, "echo-args", "printf '%s\\n' \"\$@\"")
        val arguments = listOf("a b", "c'd", "\$(echo pwned)", "e;f", "*", "back\\slash")

        val outcome = ExternalProcess.run(
            command = listOf(tool.toString()) + arguments,
            timeout = SHORT,
        )

        assertEquals(0, outcome.exitCode)
        assertEquals(arguments, outcome.stdout.trimEnd('\n').split("\n"))
    }

    @Test
    fun `an executable whose own name contains a space is still one executable`() = runBlocking {
        // A shell string would split this path into "fake" and "tool" and run the first one; a list
        // cannot, which is the whole reason the API takes a list.
        val tool = writeFakeExecutable(directory, "fake tool", "echo ran")

        val outcome = ExternalProcess.run(command = listOf(tool.toString()), timeout = SHORT)

        assertEquals("ran", outcome.stdout.trim())
    }

    @Test
    fun `the exit code is reported instead of being turned into an exception`() = runBlocking {
        val tool = writeFakeExecutable(directory, "fails", "echo out\nexit 3")

        val outcome = ExternalProcess.run(command = listOf(tool.toString()), timeout = SHORT)

        assertEquals(3, outcome.exitCode)
        assertFalse(outcome.succeeded)
        assertEquals("out", outcome.stdout.trim())
    }

    @Test
    fun `both streams are captured`() = runBlocking {
        val tool = writeFakeExecutable(directory, "noisy", "echo to-stdout\necho to-stderr 1>&2")

        val outcome = ExternalProcess.run(command = listOf(tool.toString()), timeout = SHORT)

        assertEquals("to-stdout", outcome.stdout.trim())
        assertEquals("to-stderr", outcome.stderr.trim())
    }

    @Test
    fun `the working directory is the one the caller asked for`() = runBlocking {
        val tool = writeFakeExecutable(directory, "where", "pwd")

        val outcome = ExternalProcess.run(
            command = listOf(tool.toString()),
            timeout = SHORT,
            cwd = directory,
        )

        assertEquals(directory.toRealPath().toString(), outcome.stdout.trim())
    }

    // ---- A tool that writes without stopping cannot exhaust this process ------------------------------

    @Test
    fun `stderr is capped and a full pipe does not deadlock the child`() = runBlocking {
        val tool = writeFakeExecutable(
            directory,
            "shouts",
            "head -c 2097152 /dev/zero | tr '\\0' 'e' 1>&2; echo done",
        )

        val outcome = ExternalProcess.run(command = listOf(tool.toString()), timeout = LONG)

        assertEquals(0, outcome.exitCode, "the child was blocked by its own output")
        assertEquals("done", outcome.stdout.trim())
        assertEquals(
            ExternalProcess.MAX_CAPTURED_STDERR_BYTES,
            outcome.stderr.toByteArray(Charsets.UTF_8).size,
            "stderr was not capped at the documented bound",
        )
        assertTrue(outcome.stderrTruncated)
    }

    @Test
    fun `stdout past the caller's bound is reported as truncated rather than kept`() = runBlocking {
        val tool = writeFakeExecutable(
            directory,
            "talks",
            "head -c 4096 /dev/zero | tr '\\0' 'x'; echo done 1>&2",
        )

        val outcome = ExternalProcess.run(
            command = listOf(tool.toString()),
            timeout = LONG,
            maxCapturedStdoutBytes = 64,
        )

        assertEquals(0, outcome.exitCode)
        assertEquals(64, outcome.stdout.length)
        assertTrue(outcome.stdoutTruncated)
    }

    // ---- A tool that never finishes does not hold the pipeline ---------------------------------------

    @Test
    fun `a tool that outlives its timeout is killed along with what it started`() = runBlocking {
        val pidFile = directory.resolve("timeout.pid")
        val tool = writeFakeExecutable(
            directory,
            "slow",
            "sleep 60 &\necho \"\$! \$\$\" > '$pidFile'\nwait",
        )

        val started = System.nanoTime()
        val failure = try {
            ExternalProcess.run(command = listOf(tool.toString()), timeout = Duration.ofMillis(800))
            error("the call returned although the tool never finished")
        } catch (timedOut: ExternalProcessTimeoutException) {
            timedOut
        }
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000

        assertEquals(tool.toString(), failure.executable)
        assertEquals(Duration.ofMillis(800), failure.timeout)
        assertContains(failure.message.orEmpty(), "slow")
        // The call has to come back when the tool is stopped, not when the tool decides to finish: a page
        // that hung would otherwise hold the pipeline for as long as the tool wanted. The script sleeps for
        // a minute, so a call that returned promptly is a call that stopped it.
        assertTrue(
            elapsedMillis < PROMPT_RETURN_MILLIS,
            "the call waited ${elapsedMillis}ms, which is waiting for the tool rather than stopping it",
        )
        val processIds = recordedProcessIds(pidFile)
        assertEquals(
            EXPECTED_PROCESSES,
            processIds.size,
            "the script recorded $processIds, so the kill was not exercised on both processes",
        )
        assertEquals(
            emptyList(),
            aliveProcesses(pidFile),
            "the stopped tool left processes behind: ${recordedProcessIds(pidFile)}",
        )
    }

    @Test
    fun `the instrument that checks for left-behind processes can see one`() = runBlocking {
        // The assertion above is only worth making if it can fail: this starts the same shape of script,
        // stops the shell the way the process handling does, and checks that the orphaned helper is
        // visible to the instrument. Without this, "nothing is running" would be indistinguishable from
        // "this test cannot see anything running".
        val pidFile = directory.resolve("instrument.pid")
        val tool = writeFakeExecutable(
            directory,
            "orphan-maker",
            "sleep 60 &\necho \"\$! \$\$\" > '$pidFile'\nwait",
        )
        val process = ProcessBuilder(tool.toString()).start()
        awaitCondition { recordedProcessIds(pidFile).size == EXPECTED_PROCESSES }
        process.toHandle().destroy()
        awaitCondition { !isRunning(recordedProcessIds(pidFile).last()) }
        val orphaned = recordedProcessIds(pidFile).first()
        // The shell is gone and its helper is not: that is what the kill has to prevent, and it is what
        // the instrument has to be able to see.
        assertTrue(
            isRunning(orphaned),
            "the instrument cannot see an orphaned helper, so the kill assertions prove nothing",
        )
        awaitCondition { ProcessBuilder("kill", "-9", orphaned).start().waitFor() == 0 }
    }

    @Test
    fun `a cancelled call kills its child and its child's own children`() = runBlocking {
        val pidFile = directory.resolve("cancelled.pid")
        val tool = writeFakeExecutable(
            directory,
            "patient",
            "sleep 60 &\necho \"\$! \$\$\" > '$pidFile'\nwait",
        )

        val started = System.nanoTime()
        val failure = runCatching {
            coroutineScope {
                val call = async(Dispatchers.Default) {
                    ExternalProcess.run(command = listOf(tool.toString()), timeout = Duration.ofMinutes(5))
                }
                awaitCondition { recordedProcessIds(pidFile).size == EXPECTED_PROCESSES }
                delay(50)
                call.cancel()
                call.await()
            }
        }.exceptionOrNull()
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000

        assertTrue(failure is CancellationException, "expected a cancellation, got $failure")
        assertTrue(
            elapsedMillis < PROMPT_RETURN_MILLIS,
            "the cancelled call took ${elapsedMillis}ms to come back, so it waited for the tool",
        )
        assertEquals(emptyList(), aliveProcesses(pidFile), "the cancelled tool left processes behind")
    }

    // ---- A tool that is not installed is not a document that is broken -------------------------------

    @Test
    fun `a missing executable names the tool that is missing`() = runBlocking {
        val missing = directory.resolve("no-such-tool")

        val failure = try {
            ExternalProcess.run(command = listOf(missing.toString(), "--version"), timeout = SHORT)
            error("the call returned although the tool does not exist")
        } catch (notInstalled: ExternalToolMissingException) {
            notInstalled
        }

        assertEquals(missing.toString(), failure.executable)
        assertContains(failure.message.orEmpty(), missing.toString())
    }

    // ---- Helpers ------------------------------------------------------------------------------------

    private fun aliveProcesses(pidFile: Path): List<String> {
        if (!Files.exists(pidFile)) return emptyList()
        return recordedProcessIds(pidFile).filter { pid -> isRunning(pid) }
    }

    /**
     * The pids the script wrote down, in the order it wrote them.
     *
     * The file's *content* is what matters, not its existence: the script's redirect creates the file
     * before it writes to it, so waiting for the file to appear can observe it empty.
     */
    private fun recordedProcessIds(pidFile: Path): List<String> {
        if (!Files.exists(pidFile)) return emptyList()
        return Files.readString(pidFile).trim().split(" ").filter { pid -> pid.isNotBlank() }
    }

    /**
     * Whether a process is still running, asked of the system rather than of this JVM.
     *
     * `ProcessHandle.of` is not usable here: it reports nothing for a process this JVM did not start,
     * which is exactly what an orphaned helper is — and an instrument that can never see the thing under
     * test would make every assertion below pass for the wrong reason. `kill -0` asks the kernel whether
     * the pid exists and whether this user may signal it, which is the same question.
     */
    private fun isRunning(pid: String): Boolean {
        val probe = ProcessBuilder("kill", "-0", pid).redirectErrorStream(true).start()
        probe.inputStream.use { stream -> stream.readBytes() }
        return probe.waitFor() == 0
    }

    private companion object {

        val SHORT: Duration = Duration.ofSeconds(20)
        val LONG: Duration = Duration.ofSeconds(60)

        /** The shell and the helper it started: both are recorded, because both have to be gone. */
        const val EXPECTED_PROCESSES: Int = 2

        /**
         * How long a stopped or cancelled call may take to come back.
         *
         * The script sleeps for a minute, so anything well under that says the call ended because the tool
         * was stopped rather than because it finished; the margin covers the kill grace period.
         */
        const val PROMPT_RETURN_MILLIS: Long = 20_000
    }
}
