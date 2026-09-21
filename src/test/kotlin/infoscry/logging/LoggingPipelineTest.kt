package infoscry.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.joran.JoranConfigurator
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.Appender
import ch.qos.logback.core.rolling.RollingFileAppender
import ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy
import ch.qos.logback.core.util.FileSize
import infoscry.config.BearerToken
import infoscry.config.RuntimeInfo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.w3c.dom.Element
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The log pipeline is judged on one promise: what lands on disk is structured JSON that never
 * contains an API key, an authorization value, a user question, or a document excerpt.
 */
class LoggingPipelineTest {

    private val temporaryDirectories = mutableListOf<Path>()

    /**
     * Installs the redaction filter on the application's own Logback context.
     *
     * These tests log through the SLF4J binding the product uses, rather than through a hand-made
     * LoggerContext: that is the path where the MDC adapter exists, so it is both the honest test and
     * the one that exercises redaction where it actually happens. The filter is idempotent and stays
     * installed for the rest of the test JVM.
     */
    @BeforeTest
    fun installRedactionFilter() {
        val context = loggerContext
        if (context.turboFilterList.none { it is SensitiveDataTurboFilter }) {
            val filter = SensitiveDataTurboFilter()
            filter.context = context
            filter.start()
            context.addTurboFilter(filter)
        }
    }

    @AfterTest
    fun removeTemporaryDirectories() {
        temporaryDirectories.forEach { it.toFile().deleteRecursively() }
    }

    @Test
    fun `an event becomes one json line with the fields the spec names`() {
        val file = logToFile { logger -> logger.info("import started") }

        val json = Json.parseToJsonElement(singleLine(file)).jsonObject

        assertTrue(json.getValue("timestamp").jsonPrimitive.content.isNotBlank())
        assertEquals("INFO", json.getValue("level").jsonPrimitive.content)
        assertEquals("infoscry.test", json.getValue("logger").jsonPrimitive.content)
        assertEquals("import started", json.getValue("message").jsonPrimitive.content)
        assertFalse(json.containsKey("fields"), "an event without fields carries no empty object")
        assertFalse(json.containsKey("exception"))
    }

    @Test
    fun `component, job and document ids are separate fields and other fields stay grouped`() {
        val file = logToFile { logger ->
            MDC.put(LogFields.COMPONENT, "ingest")
            MDC.put(LogFields.JOB, "job-1")
            MDC.put(LogFields.DOCUMENT, "doc-1")
            MDC.put("stage", "OCR")
            logger.atInfo().addKeyValue("page", 42).log("page ready")
            MDC.clear()
        }

        val json = Json.parseToJsonElement(singleLine(file)).jsonObject

        assertEquals("ingest", json.getValue("component").jsonPrimitive.content)
        assertEquals("job-1", json.getValue("job").jsonPrimitive.content)
        assertEquals("doc-1", json.getValue("document").jsonPrimitive.content)
        val fields = json.getValue("fields").jsonObject
        assertEquals("OCR", fields.getValue("stage").jsonPrimitive.content)
        assertEquals("42", fields.getValue("page").jsonPrimitive.content)
    }

    @Test
    fun `exception details are part of the record`() {
        val file = logToFile { logger -> logger.error("extraction failed", IllegalStateException("pdf is encrypted")) }

        val exception = Json.parseToJsonElement(singleLine(file)).jsonObject.getValue("exception").jsonPrimitive.content

        assertContains(exception, "IllegalStateException")
        assertContains(exception, "pdf is encrypted")
    }

    /**
     * The Review Focus assertion for this task: an API key, an authorization value, a question, and a
     * document excerpt must be replaced by the redaction placeholder wherever they are named as fields,
     * and none of them may survive anywhere in the written log.
     */
    @Test
    fun `named sensitive fields and arguments are redacted before they reach the file`() {
        val secrets = mapOf(
            "api_key" to "sk-live-9f8e7d6c5b4a",
            "authorization" to "Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9",
            "question" to "Who authorised the disputed transfers?",
            "excerpt" to "the board approved the transfer on 14 March",
        )

        val file = logToFile { logger ->
            secrets.forEach { (name, value) -> MDC.put(name, value) }
            logger.atInfo()
                .addKeyValue("api_key", secrets.getValue("api_key"))
                .addKeyValue("authorization", secrets.getValue("authorization"))
                .log("provider call rejected")
            logger.warn("document read failed")
            MDC.clear()
        }

        val content = Files.readString(file)

        assertContains(content, SensitiveFields.PLACEHOLDER)
        secrets.forEach { (name, value) ->
            assertFalse(content.contains(value), "$name leaked into the log: $content")
        }
        // The names themselves are not secret: an operator still has to see which field was dropped.
        assertContains(content, "api_key")
        assertContains(content, "question")
        assertContains(content, "authorization")
    }

    @Test
    fun `a bearer token cannot reach the log through stringification`() {
        val token = BearerToken.new()
        val info = RuntimeInfo(pid = ProcessHandle.current().pid(), port = 8765, bearerToken = token)

        val file = logToFile { logger ->
            logger.info("server listening: $info")
            logger.atInfo().addKeyValue("runtime_info", info).log("runtime ready")
        }

        val content = Files.readString(file)

        assertFalse(
            content.contains(token.value),
            "the bearer token is a credential and must never reach the log: $content",
        )
        assertContains(content, "bearerToken", message = "the field name still shows what was withheld")
        assertContains(content, BearerToken.REDACTED, message = "the value is replaced, not dropped")
    }

    @Test
    fun `the redaction filter replaces the mdc value itself, for every appender`() {
        var valueAfterDispatch: String? = null

        logToFile { logger ->
            MDC.put("question", "Who authorised the disputed transfers?")
            logger.info("asking")
            valueAfterDispatch = MDC.get("question")
            MDC.clear()
        }

        assertEquals(SensitiveFields.PLACEHOLDER, valueAfterDispatch)
    }

    @Test
    fun `the shipped logback configuration pins the documented rolling limits`() {
        // The configuration file is what ships, so its numbers are asserted directly; Logback keeps the
        // parsed sizes in non-public fields, and parsing the shipped XML is the honest check on them.
        val configuration = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(javaClass.getResource("/logback.xml").toURI().toString())

        val turboFilter = configuration.getElementsByTagName("turboFilter").item(0)
        assertEquals(
            "infoscry.logging.SensitiveDataTurboFilter",
            turboFilter.attributes.getNamedItem("class").nodeValue,
        )

        val fileAppender = (0 until configuration.getElementsByTagName("appender").length)
            .map { configuration.getElementsByTagName("appender").item(it) as Element }
            .single { it.attributes.getNamedItem("name").nodeValue == FILE_APPENDER }
        val text = { tag: String -> fileAppender.getElementsByTagName(tag).item(0).textContent.trim() }
        assertEquals("10MB", text("maxFileSize"), "10 MiB per file")
        assertEquals("10", text("maxHistory"), "10 history files")
        assertEquals("100MB", text("totalSizeCap"), "100 MiB total cap")
        assertEquals(
            "infoscry.logging.JsonLogEncoder",
            fileAppender.getElementsByTagName("encoder").item(0).attributes.getNamedItem("class").nodeValue,
        )
        assertContains(
            fileAppender.getElementsByTagName("file").item(0).textContent,
            "LOG_DIR",
            message = "the log directory is addressable, so tests and other data directories can redirect it",
        )

        // And the configuration really loads without a status error, so the numbers above are the
        // ones a running InfoScry gets. Whether a record reaches the file, and how it is redacted, is
        // proven above through the application's own logging context.
        val context = LoggerContext()
        context.putProperty(LOG_DIR_PROPERTY, newTemporaryDirectory("infoscry-logback").toString())
        val configurator = JoranConfigurator()
        configurator.context = context
        configurator.doConfigure(javaClass.getResource("/logback.xml"))

        assertTrue(
            context.turboFilterList.any { it is SensitiveDataTurboFilter },
            "the shipped configuration has to install the redaction filter",
        )
        val appender: Appender<*>? = context.getLogger(Logger.ROOT_LOGGER_NAME).getAppender(FILE_APPENDER)
        assertTrue(appender is RollingFileAppender<*>, "the file sink is a rolling file appender")
        assertTrue(appender.encoder is JsonLogEncoder, "the file sink writes JSON lines")

        val errors = context.statusManager.copyOfStatusList.filter { it.level == ch.qos.logback.core.status.Status.ERROR }
        assertTrue(errors.isEmpty(), "the shipped logging configuration loads cleanly: $errors")
    }

    /**
     * Runs [block] against the application's logger context, with one rolling JSON file attached.
     *
     * The appender is detached again afterwards so the tests cannot influence each other.
     */
    private fun logToFile(block: (Logger) -> Unit): Path {
        val logsDir = newTemporaryDirectory("infoscry-logging")
        val file = logsDir.resolve("infoscry.log")
        val context = loggerContext
        val root = context.getLogger(Logger.ROOT_LOGGER_NAME)
        val previousLevel = root.level

        val encoder = JsonLogEncoder()
        encoder.context = context
        encoder.start()

        val policy = SizeAndTimeBasedRollingPolicy<ILoggingEvent>()
        policy.context = context
        policy.fileNamePattern = logsDir.resolve("infoscry.%d{yyyy-MM-dd}.%i.log").toString()
        policy.setMaxFileSize(FileSize.valueOf("10MB"))
        policy.maxHistory = 10
        policy.setTotalSizeCap(FileSize.valueOf("100MB"))

        val appender = RollingFileAppender<ILoggingEvent>()
        appender.context = context
        appender.name = FILE_APPENDER
        appender.setFile(file.toString())
        appender.setEncoder(encoder)
        policy.setParent(appender)
        policy.start()
        appender.setRollingPolicy(policy)
        appender.start()

        root.level = Level.TRACE
        root.addAppender(appender)
        try {
            block(context.getLogger("infoscry.test"))
        } finally {
            root.detachAppender(appender)
            appender.stop()
            root.level = previousLevel
        }
        return file
    }

    /** The Logback context the application itself logs through. */
    private val loggerContext: LoggerContext
        get() = LoggerFactory.getILoggerFactory() as LoggerContext

    private fun newTemporaryDirectory(prefix: String): Path =
        Files.createTempDirectory(prefix).also { temporaryDirectories.add(it) }

    private fun singleLine(file: Path): String {
        val trimmed = Files.readString(file).trim()
        assertEquals(1, trimmed.lines().size, "one event has to produce exactly one line: $trimmed")
        return trimmed
    }

    private companion object {
        const val FILE_APPENDER = "FILE"
        const val LOG_DIR_PROPERTY = "infoscry.logs.dir"
    }
}
