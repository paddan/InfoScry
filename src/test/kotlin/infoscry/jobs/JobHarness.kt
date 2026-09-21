package infoscry.jobs

import infoscry.AppContext
import infoscry.domain.JobId
import infoscry.domain.JobType
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.system.exitProcess

/**
 * A stand-in for a process that dies in the middle of a job attempt.
 *
 * "The process was killed between two durable stages" cannot be produced from inside the process being
 * killed, so the recovery test starts this harness as a real child JVM, waits for it to report the
 * instant it reached, and then terminates it forcibly. The harness runs the *real* [JobRunner] with a
 * handler that records every unit it executes, so the state it leaves behind is the state production
 * would leave if it were killed at that instant.
 *
 * One unit of work is one durable commit: the handler appends a line to the execution log inside the
 * stage (the unit's own artifact write) and then reports progress (its checkpoint). The log is what lets
 * the test prove that a resumed attempt does not execute a committed unit twice.
 *
 * It prints `HARNESS JOB <id>` once the job exists and `HARNESS READY <stop-after>` when it reaches the
 * instant, and then blocks until it is killed. The data directory lock stays held while it blocks, just
 * as it would in a running server, and the operating system releases it when the process dies.
 */
object JobHarness {

    /** The instants an attempt can be interrupted at, in the order they happen. */
    enum class StopAfter {
        /** One unit is committed: its line is in the log and its progress is durable. */
        AFTER_FIRST_UNIT,

        /** Two units are committed, so a resume has more than one stage behind it. */
        AFTER_SECOND_UNIT,

        /**
         * A cancellation request is recorded while the attempt is still running.
         *
         * The request is written through the store's own primitive, which is exactly what a collection
         * tombstone writes and exactly what the runner's cancel does first — without the in-memory
         * interrupt, because the instant under test is the one a kill leaves behind: request durable,
         * worker not yet aware of it.
         */
        CANCEL_REQUESTED,
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val options = args.toList()
        val dataDir = Path.of(value(options, "--data-dir"))
        val units = value(options, "--units").toInt()
        val log = Path.of(value(options, "--log"))
        val stopAfter = runCatching { StopAfter.valueOf(value(options, "--stop-after")) }.getOrElse {
            System.err.println("--stop-after must be one of ${StopAfter.entries.joinToString()}")
            exitProcess(2)
        }

        val existing = options.indexOf("--job-id").takeIf { it >= 0 }?.let { options[it + 1] }

        AppContext.open(dataDir).use { context ->
            val jobId = if (existing != null) {
                JobId(existing)
            } else {
                context.jobs.enqueue(
                    type = JobType.IMPORT,
                    collectionId = context.collectionService.list().first().id,
                    total = units,
                ).id
            }
            println("HARNESS JOB " + jobId.value)
            System.out.flush()

            val runner = JobRunner(
                store = context.jobs,
                collections = context.collections,
                mutations = context.mutations,
                handler = unitHandler(log, units, stopAfter) { id -> context.jobs.cancel(id) },
                pollIntervalMillis = HARNESS_POLL_MILLIS,
            )
            context.attachJobRunner(runner)
            runner.start()

            // The runner holds this process open until it is killed; the test always kills it.
            while (true) {
                Thread.sleep(HOLD_MILLIS)
            }
        }
    }

    /**
     * The unit loop every attempt in this test runs: the same loop the child process uses and the same
     * one the test uses to finish the job in-process, so "resumes at its checkpoint" means the same
     * thing on both sides.
     *
     * Each unit is one durable commit: the artifact write (the log line) inside the stage, then the
     * progress report that makes the checkpoint durable.
     */
    internal fun unitHandler(
        log: Path,
        units: Int,
        stopAfter: StopAfter? = null,
        requestCancellation: suspend (JobId) -> Unit = {},
    ): JobHandler = JobHandler { job, stage ->
        for (unit in (job.completed + 1)..units) {
            stage.run("unit $unit") {
                Files.writeString(log, "unit $unit\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
            }
            stage.reportProgress(unit, units)

            if (stopAfter != null && stopAfter.stopsAfter(unit)) {
                if (stopAfter == StopAfter.CANCEL_REQUESTED) requestCancellation(job.id)
                ready(stopAfter)
                waitForKill()
            }
        }
    }

    private fun StopAfter.stopsAfter(unit: Int): Boolean = when (this) {
        StopAfter.AFTER_FIRST_UNIT, StopAfter.CANCEL_REQUESTED -> unit == 1
        StopAfter.AFTER_SECOND_UNIT -> unit == 2
    }

    /**
     * Reports the instant and then holds this JVM open. A bounded sleep would race the test's kill
     * signal; an unbounded one is safe because the test always terminates the process.
     */
    private fun ready(stopAfter: StopAfter) {
        println("HARNESS READY $stopAfter")
        System.out.flush()
    }

    private fun waitForKill() {
        while (true) {
            Thread.sleep(HOLD_MILLIS)
        }
    }

    private fun value(options: List<String>, name: String): String {
        val index = options.indexOf(name)
        require(index >= 0 && index + 1 < options.size) { "$name is required" }
        return options[index + 1]
    }

    private const val HOLD_MILLIS = 100L
    private const val HARNESS_POLL_MILLIS = 5L
}
