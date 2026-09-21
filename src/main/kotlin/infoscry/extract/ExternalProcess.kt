package infoscry.extract

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withTimeout

/**
 * What one child process produced.
 *
 * [stdout] and [stderr] are decoded as UTF-8 and are capped: a tool that writes without stopping cannot
 * be allowed to decide how much memory this process holds, so what is kept is bounded and [truncated]
 * says whether anything was dropped. A non-zero [exitCode] is reported rather than thrown, because a tool
 * that failed is a result the caller has to translate into its own vocabulary, and only the caller knows
 * which failure it is.
 */
data class ProcessOutcome(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val stdoutTruncated: Boolean = false,
    val stderrTruncated: Boolean = false,
) {

    /** Whether the tool reported success. */
    val succeeded: Boolean get() = exitCode == 0
}

/**
 * The tool this command names is not installed, or cannot be run by this user.
 *
 * The distinction matters downstream: a missing tool is not a broken document, and the pipeline reports
 * it against the job with a code that names what has to be installed rather than against the file with a
 * parse error. [executable] is the name as it was given, so the message can repeat exactly what was
 * looked for.
 */
class ExternalToolMissingException(
    val executable: String,
    cause: Throwable?,
) : IOException("the tool '$executable' is not installed or cannot be executed", cause)

/**
 * The tool did not finish inside the time it was given, and has been stopped.
 *
 * [timeout] is carried so the message can say how long it waited: a page that takes longer than the bound
 * is either a pathological document or a tool that has hung, and the caller needs the number to tell
 * which.
 */
class ExternalProcessTimeoutException(
    val executable: String,
    val timeout: Duration,
) : IOException("the tool '$executable' did not finish within $timeout and was stopped")

/**
 * Runs an external tool as a child process, with a bound on its time and on how much of its output is
 * kept.
 *
 * The command is a **list**, never a string: a path with a space, a quote, or a `$(...)` is one argument
 * and cannot become two arguments or a command of its own. Nothing about this is a shell — there is no
 * `sh -c` anywhere in this file — which is what makes an imported file's name, or a path derived from it,
 * safe to pass to a tool.
 *
 * The process is stopped, not abandoned. On a timeout or a cancellation this kills the tool and everything
 * it started, because a tool left running would keep writing into the document's working directories after
 * the attempt that owned them has moved on. The kill is graceful first — `destroy`, then a grace period —
 * so a tool that finishes its current page has the chance to, and only then forcible.
 *
 * Both pipes are drained on their own threads while the process runs. A child whose stderr fills up and is
 * never read blocks forever, which is the failure this avoids by reading always and keeping only the first
 * [MAX_CAPTURED_STDERR_BYTES] of it.
 */
object ExternalProcess {

    /** How much of a tool's stderr is kept. Diagnostics, not evidence, so the bound is small. */
    const val MAX_CAPTURED_STDERR_BYTES: Int = 1024 * 1024

    /** How much of a tool's stdout is kept by default: the page's reading, boxed words and all. */
    const val MAX_CAPTURED_STDOUT_BYTES: Int = 64 * 1024 * 1024

    /** How long a tool has to exit after being asked politely, before it is killed. */
    val KILL_GRACE: Duration = Duration.ofSeconds(5)

    private const val CAPTURE_BUFFER_BYTES: Int = 64 * 1024

    /**
     * Runs [command] to completion, or stops it.
     *
     * [cwd] is the directory the tool runs in — the caller's private temporary directory, so a tool that
     * writes beside itself cannot write beside the user's documents. [maxCapturedStdoutBytes] is the
     * caller's bound rather than a constant because the caller is what knows how much output is a reading
     * and how much is a runaway.
     */
    suspend fun run(
        command: List<String>,
        timeout: Duration,
        cwd: Path? = null,
        maxCapturedStdoutBytes: Int = MAX_CAPTURED_STDOUT_BYTES,
    ): ProcessOutcome {
        require(command.isNotEmpty()) { "a child process needs a command to run" }
        require(command.first().isNotBlank()) { "a child process needs a program name" }
        require(maxCapturedStdoutBytes >= 1) {
            "a stdout bound must keep at least one byte, was $maxCapturedStdoutBytes"
        }
        require(!timeout.isZero && !timeout.isNegative) {
            "a child process needs a positive timeout, was $timeout"
        }
        val process = start(command, cwd)
        return coroutineScope {
            val stdout = async(Dispatchers.IO) {
                capture(process.inputStream, maxCapturedStdoutBytes)
            }
            val stderr = async(Dispatchers.IO) {
                capture(process.errorStream, MAX_CAPTURED_STDERR_BYTES)
            }
            val exitCode = try {
                withTimeout(timeout.toMillis()) { awaitExit(process) }
            } catch (timedOut: TimeoutCancellationException) {
                throw ExternalProcessTimeoutException(command.first(), timeout)
            } finally {
                // This runs on every path: a no-op after a normal exit, and the kill on a timeout or on a
                // cancellation of this call. It has to happen here rather than after the scope, because the
                // two readers above are blocked on this process's pipes: a process that is never stopped
                // keeps them — and this scope, which joins them — waiting forever.
                terminate(process)
            }
            val kept = stdout.await()
            val errors = stderr.await()
            ProcessOutcome(
                exitCode = exitCode,
                stdout = kept.text(),
                stderr = errors.text(),
                stdoutTruncated = kept.truncated,
                stderrTruncated = errors.truncated,
            )
        }
    }

    private fun start(command: List<String>, cwd: Path?): Process {
        val builder = ProcessBuilder(command)
        if (cwd != null) builder.directory(cwd.toFile())
        return try {
            builder.start()
        } catch (missing: IOException) {
            // A program that is not there, or is there and cannot be executed: the caller has to install
            // something either way, so both are one condition with one name.
            throw ExternalToolMissingException(command.first(), missing)
        }
    }

    /**
     * Suspends until the process exits.
     *
     * The wait is the process handle's own completion notification rather than a loop over `isAlive`,
     * because the notification is both precise and cancellable: a cancelled attempt stops waiting at once
     * and the `finally` above stops the process, instead of this call holding a thread until the tool
     * decides to finish.
     */
    private suspend fun awaitExit(process: Process): Int {
        process.toHandle().onExit().await()
        return process.exitValue()
    }

    /**
     * Stops the process and everything it started.
     *
     * A tool is free to start helpers of its own, and killing only the program this process knows about
     * would leave those helpers running with the working directory of an attempt that is over. The tree is
     * asked to stop first — children before their parent, so a child cannot outlive the parent that owns
     * it — and the whole tree is killed after the grace period.
     */
    private fun terminate(process: Process) {
        val handle = process.toHandle()
        val descendants = handle.descendants().toList()
        if (!handle.isAlive && descendants.isEmpty()) return
        descendants.reversed().forEach { it.destroy() }
        handle.destroy()
        if (awaitStopped(process)) return
        // The tree is read again rather than reused: a tool that was still running may have started
        // another helper while the grace period passed, and that one has to stop too.
        handle.descendants().toList().reversed().forEach { it.destroyForcibly() }
        handle.destroyForcibly()
        awaitStopped(process)
    }

    /** Waits out the kill grace period. Returns whether the process has stopped. */
    private fun awaitStopped(process: Process): Boolean = try {
        process.waitFor(KILL_GRACE.toMillis(), TimeUnit.MILLISECONDS)
    } catch (interrupted: InterruptedException) {
        Thread.currentThread().interrupt()
        true
    }

    /**
     * Reads a stream to its end, keeping at most [keep] bytes of it.
     *
     * Reading continues after the bound is reached and the rest is discarded, because a full pipe blocks
     * the child: refusing to read would turn "this tool talks too much" into "this tool never finishes".
     */
    private fun capture(stream: InputStream, keep: Int): Captured {
        val kept = ByteArrayOutputStream(minOf(keep, CAPTURE_BUFFER_BYTES))
        val buffer = ByteArray(CAPTURE_BUFFER_BYTES)
        var truncated = false
        stream.use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                val room = keep - kept.size()
                when {
                    room <= 0 -> truncated = true
                    read <= room -> kept.write(buffer, 0, read)
                    else -> {
                        kept.write(buffer, 0, room)
                        truncated = true
                    }
                }
            }
        }
        return Captured(kept.toByteArray(), truncated)
    }

    /** What one stream produced: the bytes that were kept, and whether any were dropped. */
    private class Captured(private val bytes: ByteArray, val truncated: Boolean) {

        fun text(): String = bytes.toString(Charsets.UTF_8)
    }
}
