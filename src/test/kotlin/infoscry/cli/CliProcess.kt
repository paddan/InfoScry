package infoscry.cli

import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.TimeUnit

/** What a finished CLI invocation did. */
internal data class CliResult(val exitCode: Int, val stdout: String, val stderr: String)

/**
 * Runs the real `infoscry` command line in a child JVM.
 *
 * The CLI's contract with a script is its exit code and the text it writes, and neither exists when the
 * command is called as a Java function: Clikt turns option errors and [CliFailure] into an exit code
 * *outside* the command. Running the real thing is also the only way to test what the CLI does when
 * another process already owns the data directory, which is the difference between "it works" and "it
 * tells the user what happened".
 */
internal object CliProcess {

    /** Runs the CLI to completion and returns everything it produced. */
    fun run(
        vararg args: String,
        timeout: Long = DEFAULT_TIMEOUT_MILLIS,
    ): CliResult {
        val process = start(*args)
        val stdout = Collections.synchronizedList(mutableListOf<String>())
        val stderr = Collections.synchronizedList(mutableListOf<String>())
        pump(process.inputStream, stdout)
        pump(process.errorStream, stderr)
        if (!process.waitFor(timeout, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            error("the CLI did not finish within ${timeout}ms: ${args.joinToString(" ")}")
        }
        return CliResult(process.exitValue(), stdout.joinToString("\n"), stderr.joinToString("\n"))
    }

    /** Starts the CLI and keeps a handle on its output, for commands that run until told to stop. */
    fun startRunning(vararg args: String, environment: Map<String, String> = emptyMap()): RunningCli =
        RunningCli(start(*args, environment = environment))

    /** Starts the CLI without waiting. */
    fun start(vararg args: String, environment: Map<String, String> = emptyMap()): Process {
        val builder = ProcessBuilder(
            javaExecutable(),
            // The SQLite driver loads a native library; without this the JDK warns on every run.
            "--enable-native-access=ALL-UNNAMED",
            "-cp",
            System.getProperty("java.class.path"),
            "infoscry.MainKt",
            *args,
        )
        builder.environment().putAll(environment)
        return builder.start()
    }

    private fun pump(stream: java.io.InputStream, into: MutableList<String>) {
        val thread = Thread {
            stream.bufferedReader().useLines { lines -> lines.forEach { into += it } }
        }
        thread.isDaemon = true
        thread.start()
    }

    private fun javaExecutable(): String =
        Path.of(System.getProperty("java.home"), "bin", "java").toString()

    private const val DEFAULT_TIMEOUT_MILLIS = 60_000L
}

/**
 * A CLI process that is still running, with the output it has produced so far.
 *
 * `serve` never exits on its own, so a test has to watch its output and then decide how to end it —
 * both to read the URL it published and to check what a clean stop and a killed process leave behind.
 */
internal class RunningCli(private val process: Process) {

    private val stdout = Collections.synchronizedList(mutableListOf<String>())
    private val stderr = Collections.synchronizedList(mutableListOf<String>())

    val pid: Long get() = process.pid()

    init {
        pump(process.inputStream, stdout)
        pump(process.errorStream, stderr)
    }

    /** Waits for a line starting with [prefix] and returns it. */
    fun awaitStdoutLine(prefix: String, timeout: Long = DEFAULT_WAIT_MILLIS): String {
        val deadline = System.nanoTime() + timeout * 1_000_000
        while (System.nanoTime() < deadline) {
            stdout.firstOrNull { it.startsWith(prefix) }?.let { return it }
            if (!process.isAlive) break
            Thread.sleep(POLL_MILLIS)
        }
        throw AssertionError(
            "the CLI never printed a line starting with '$prefix'; stdout=${stdout.joinToString("|")} " +
                "stderr=${stderr.joinToString("|")}",
        )
    }

    fun stdout(): List<String> = stdout.toList()

    fun stderr(): List<String> = stderr.toList()

    /** Asks the process to stop the way Ctrl-C does, and returns its exit code. */
    fun terminate(timeout: Long = DEFAULT_WAIT_MILLIS): Int {
        process.destroy()
        if (!process.waitFor(timeout, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            process.waitFor(timeout, TimeUnit.MILLISECONDS)
        }
        return process.exitValue()
    }

    /** Kills the process without giving it a chance to clean up, which is what a crash looks like. */
    fun kill() {
        process.destroyForcibly()
        process.waitFor(DEFAULT_WAIT_MILLIS, TimeUnit.MILLISECONDS)
    }

    private fun pump(stream: java.io.InputStream, into: MutableList<String>) {
        val thread = Thread {
            stream.bufferedReader().useLines { lines -> lines.forEach { into += it } }
        }
        thread.isDaemon = true
        thread.start()
    }

    private companion object {
        const val DEFAULT_WAIT_MILLIS = 30_000L
        const val POLL_MILLIS = 10L
    }
}
