package infoscry

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import infoscry.jobs.JobHandler
import infoscry.jobs.JobRunner
import infoscry.storage.Database
import infoscry.storage.JobStore
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.slf4j.LoggerFactory

/**
 * Shutting down is where a failure is easiest to lose: the process is already going away, so a worker
 * that could not hand its attempts back would leave the next process repairing the queue with no
 * record of why. Close stays best-effort, but it leaves evidence and it still finishes.
 */
class AppContextCloseTest {

    private val temporaryDirectories = mutableListOf<Path>()

    @AfterTest
    fun removeTemporaryDirectories() {
        temporaryDirectories.forEach { it.toFile().deleteRecursively() }
    }

    @Test
    fun `a runner that cannot hand its attempts back is logged and shutdown still finishes`() {
        val dataDir = Files.createTempDirectory("infoscry-close").also(temporaryDirectories::add)
        val context = AppContext.open(dataDir)

        // A store whose database is already closed is the cheapest faithful way to make the runner's
        // final sweep fail: JobRunner has no fault-injection seam, and this is the same code path
        // AppContext.close() takes.
        val closedDatabase = Database(dataDir.resolve("infoscry.db")).apply { close() }
        context.attachJobRunner(
            JobRunner(
                JobStore(closedDatabase),
                context.collections,
                context.mutations,
                JobHandler { _, _ -> },
            ),
        )

        val logger = LoggerFactory.getLogger("infoscry.startup") as Logger
        val records = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(records)
        try {
            context.close()
        } finally {
            logger.detachAppender(records)
        }

        val warning = records.list.singleOrNull { it.level == Level.WARN }
        assertTrue(
            warning != null,
            "a failed hand-back has to leave evidence: ${records.list.map { it.formattedMessage }}",
        )
        assertEquals(
            "jobs",
            warning.keyValuePairs.firstOrNull { it.key == "component" }?.value,
            "the record names the component so it can be filtered",
        )
        assertTrue(warning.throwableProxy != null, "the reason is part of the record")

        // Best-effort means the rest of the shutdown still happened, and the lock is what proves it:
        // a second open of the same data directory would fail if close() had given up half way.
        AppContext.open(dataDir).use { reopened ->
            assertEquals(dataDir.toRealPath(), reopened.paths.root.toRealPath())
        }
    }
}
