package infoscry.logging

import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.joran.JoranConfigurator
import infoscry.config.AppPaths
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.slf4j.MDC

/**
 * The log directory has exactly one derivation: the data directory the process was started with. These
 * tests configure a Logback context of their own from the product's `logback.xml`, so they prove the
 * whole chain — bootstrap property, configuration, file on disk — without touching the context the test
 * runner uses.
 */
class LoggingBootstrapTest {

    private lateinit var dataDir: Path

    @BeforeTest
    fun createTemporaryDataDirectory() {
        dataDir = Files.createTempDirectory("infoscry-log-bootstrap")
    }

    @AfterTest
    fun removeTemporaryDataDirectory() {
        System.clearProperty(LoggingBootstrap.LOGS_DIR_PROPERTY)
        dataDir.toFile().deleteRecursively()
    }

    @Test
    fun `binds the log directory to the data directory`() {
        val paths = AppPaths.of(dataDir)

        LoggingBootstrap.useLogsDirectory(paths)

        assertEquals(paths.logsDir.toString(), System.getProperty(LoggingBootstrap.LOGS_DIR_PROPERTY))
    }

    @Test
    fun `the product logging configuration writes into the bound directory`() {
        val paths = AppPaths.of(dataDir)
        LoggingBootstrap.useLogsDirectory(paths)

        val context = LoggerContext()
        // A context created by hand is not the one SLF4J bound, so it has no MDC adapter; the log
        // encoders read the MDC, so the adapter has to be supplied explicitly.
        context.mdcAdapter = MDC.getMDCAdapter()
        try {
            JoranConfigurator().apply { this.context = context }.doConfigure(productConfiguration())
            context.getLogger("infoscry.test").error("bound-directory-probe")
        } finally {
            context.stop()
        }

        val logFile = paths.logsDir.resolve("infoscry.log")
        assertTrue(Files.exists(logFile), "the rolling file sink must live in the bound directory")
        assertContains(Files.readString(logFile), "bound-directory-probe")
    }

    @Test
    fun `the logging configuration reads the property this bootstrap sets`() {
        assertContains(productConfiguration().readText(), "\${${LoggingBootstrap.LOGS_DIR_PROPERTY}:-")
    }

    private fun productConfiguration() =
        assertNotNull(javaClass.classLoader.getResource("logback.xml"), "logback.xml must be on the classpath")
}
