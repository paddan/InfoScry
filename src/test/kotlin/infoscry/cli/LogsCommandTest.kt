package infoscry.cli

import infoscry.logging.LogFilter
import infoscry.logging.LogLevel
import infoscry.logging.LogReader
import infoscry.logging.LogRecord
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Duration
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `infoscry logs` is the same event stream the web panel reads, filtered and rendered for a terminal.
 * These tests pin the reader's behaviour: one-shot reads across rotated files, filtering by level,
 * job, component and age, and a follow mode that survives rotation without losing or repeating lines.
 */
class LogsCommandTest {

    private lateinit var logsDir: Path

    @BeforeTest
    fun createTemporaryLogsDirectory() {
        logsDir = Files.createTempDirectory("infoscry-logs")
    }

    @AfterTest
    fun removeTemporaryLogsDirectory() {
        logsDir.toFile().deleteRecursively()
    }

    @Test
    fun `a json line becomes a record with the documented fields`() {
        val record = LogRecord.parse(
            """{"timestamp":"2026-09-21T10:15:30.250Z","level":"WARN","logger":"infoscry.extract",""" +
                """"component":"ingest","job":"job-1","document":"doc-1","message":"page failed",""" +
                """"fields":{"page":"42"},"exception":"java.lang.IllegalStateException: pdf is encrypted"}""",
        )

        assertEquals(Instant.parse("2026-09-21T10:15:30.250Z"), record?.timestamp)
        assertEquals(LogLevel.WARN, record?.level)
        assertEquals("infoscry.extract", record?.logger)
        assertEquals("ingest", record?.component)
        assertEquals("job-1", record?.job)
        assertEquals("doc-1", record?.document)
        assertEquals("page failed", record?.message)
        assertEquals(mapOf("page" to "42"), record?.fields)
        assertContains(record?.exception.orEmpty(), "pdf is encrypted")
    }

    @Test
    fun `malformed or partial lines are skipped instead of breaking the reader`() {
        writeActiveLog(
            "{\"timestamp\":\"2026-09-21T10:00:00Z\",\"level\":\"INFO\",\"logger\":\"l\",\"message\":\"first\"}",
            "{\"timestamp\":\"2026-09-21T10:00:01Z\",\"level\":\"INFO\",\"logg",
            "not json at all",
            "{\"timestamp\":\"2026-09-21T10:00:02Z\",\"level\":\"NOSUCH\",\"logger\":\"l\",\"message\":\"third\"}",
            "",
        )

        val records = LogReader(logsDir).read(LogFilter())

        assertEquals(listOf("first"), records.map { it.message })
    }

    @Test
    fun `reading finds records in rotated files as well as the active one`() {
        val rotated = logsDir.resolve("infoscry.1.log")
        Files.writeString(rotated, line("older", Instant.parse("2026-09-21T09:00:00Z")))
        Files.setLastModifiedTime(rotated, FileTime.from(Instant.parse("2026-09-21T09:00:00Z")))
        writeActiveLog(line("newer", Instant.parse("2026-09-21T10:00:00Z")))

        val records = LogReader(logsDir).read(LogFilter())

        assertEquals(listOf("older", "newer"), records.map { it.message }, "oldest file first")
    }

    @Test
    fun `filtering by level keeps that level and above`() {
        writeActiveLog(
            line("trace", level = "TRACE"),
            line("debug", level = "DEBUG"),
            line("info", level = "INFO"),
            line("warn", level = "WARN"),
            line("error", level = "ERROR"),
        )

        val warnings = LogReader(logsDir).read(LogFilter(minimumLevel = LogLevel.WARN))

        assertEquals(listOf("warn", "error"), warnings.map { it.message })
    }

    @Test
    fun `filtering by job and component selects only that work`() {
        writeActiveLog(
            """{"timestamp":"2026-09-21T10:00:00Z","level":"INFO","logger":"l","component":"ingest","job":"job-1","message":"mine"}""",
            """{"timestamp":"2026-09-21T10:00:01Z","level":"INFO","logger":"l","component":"ingest","job":"job-2","message":"other job"}""",
            """{"timestamp":"2026-09-21T10:00:02Z","level":"INFO","logger":"l","component":"search","job":"job-1","message":"other component"}""",
            """{"timestamp":"2026-09-21T10:00:03Z","level":"INFO","logger":"l","message":"no context"}""",
        )

        val reader = LogReader(logsDir)

        assertEquals(listOf("mine"), reader.read(LogFilter(job = "job-1", component = "ingest")).map { it.message })
        assertEquals(
            listOf("mine", "other job"),
            reader.read(LogFilter(component = "ingest")).map { it.message },
        )
    }

    @Test
    fun `filtering by age drops records older than the window`() {
        val now = Instant.parse("2026-09-21T12:00:00Z")
        writeActiveLog(
            line("recent", timestamp = now.minus(Duration.ofMinutes(5))),
            line("exactly on the edge", timestamp = now.minus(Duration.ofMinutes(30))),
            line("stale", timestamp = now.minus(Duration.ofMinutes(31))),
        )

        val records = LogReader(logsDir, now = { now }).read(LogFilter(since = Duration.ofMinutes(30)))

        // Oldest first, as the whole reader reports: the newest record is last.
        assertEquals(listOf("exactly on the edge", "recent"), records.map { it.message })
    }

    @Test
    fun `follow prints existing lines, then only new ones, and survives rotation`() {
        writeActiveLog(line("existing"))
        val printed = mutableListOf<String>()
        var polls = 0
        val reader = LogReader(logsDir)

        reader.follow(
            filter = LogFilter(),
            sink = { printed.add(it.message) },
            pause = {
                when (polls++) {
                    0 -> append(line("appended"))
                    // Rotation: the active file is renamed aside and a fresh one takes its place.
                    1 -> {
                        Files.move(logsDir.resolve(LogReader.ACTIVE_FILE), logsDir.resolve("infoscry.1.log"))
                        writeActiveLog(line("after rotation"))
                    }
                    else -> append(line("after rotation again"))
                }
            },
            keepGoing = { polls < 4 },
        )

        assertEquals(
            listOf("existing", "appended", "after rotation", "after rotation again"),
            printed,
            "no duplicate and no missing line across rotation",
        )
    }

    @Test
    fun `follow tolerates a log directory that does not exist yet`() {
        val printed = mutableListOf<String>()
        val missing = logsDir.resolve("not-created")

        LogReader(missing).follow(
            filter = LogFilter(),
            sink = { printed.add(it.message) },
            pause = {},
            keepGoing = { false },
        )

        assertTrue(printed.isEmpty())
    }

    @Test
    fun `the human rendering names level, component and message`() {
        val rendered = LogRecord.parse(
            """{"timestamp":"2026-09-21T10:15:30Z","level":"ERROR","logger":"infoscry.extract",""" +
                """"component":"ingest","job":"job-1","message":"page failed","exception":"java.lang.IllegalStateException"}""",
        )!!.render()

        assertContains(rendered, "ERROR")
        assertContains(rendered, "ingest")
        assertContains(rendered, "job-1")
        assertContains(rendered, "page failed")
        assertContains(rendered, "IllegalStateException")
    }

    @Test
    fun `since accepts the documented units and rejects anything else`() {
        assertEquals(Duration.ofSeconds(45), LogsCommand.parseSince("45s"))
        assertEquals(Duration.ofMinutes(30), LogsCommand.parseSince("30m"))
        assertEquals(Duration.ofHours(12), LogsCommand.parseSince("12h"))
        assertEquals(Duration.ofDays(7), LogsCommand.parseSince("7d"))
        assertNull(LogsCommand.parseSince(null))

        val failure = assertFailsWith<IllegalArgumentException> { LogsCommand.parseSince("half an hour") }
        assertContains(failure.message.orEmpty(), "--since")
    }

    @Test
    fun `level parsing is case insensitive and rejects unknown levels`() {
        assertEquals(LogLevel.WARN, LogLevel.parse("warn"))
        assertEquals(LogLevel.WARN, LogLevel.parse("WARN"))
        assertEquals(LogLevel.INFO, LogLevel.parse("Info"))
        assertNull(LogLevel.parse("VERBOSE"), "an unknown level must be reported, not silently ignored")
    }

    private fun writeActiveLog(vararg lines: String) {
        Files.writeString(logsDir.resolve(LogReader.ACTIVE_FILE), lines.joinToString("\n", postfix = "\n"))
    }

    private fun append(line: String) {
        Files.writeString(
            logsDir.resolve(LogReader.ACTIVE_FILE),
            line + "\n",
            java.nio.file.StandardOpenOption.APPEND,
        )
    }

    private fun line(
        message: String,
        timestamp: Instant = Instant.parse("2026-09-21T10:00:00Z"),
        level: String = "INFO",
    ): String = """{"timestamp":"$timestamp","level":"$level","logger":"infoscry.test","message":"$message"}"""
}
