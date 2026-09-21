package infoscry.logging

import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.joran.JoranConfigurator
import infoscry.config.AppPaths
import org.slf4j.LoggerFactory
import java.net.URL

/**
 * Binds the log directory to the data directory the process was actually started with.
 *
 * `logback.xml` resolves its rolling file sink from the `infoscry.logs.dir` system property and falls
 * back to the default data directory. The layout of a data directory is [AppPaths]'s knowledge, so the
 * property has to be set from [AppPaths] rather than re-derived in the logging configuration: two
 * derivations is how a server started with `--data-dir /elsewhere` ends up writing its logs into
 * `~/.infoscry/logs` while `infoscry logs --data-dir /elsewhere` reads an empty directory.
 *
 * [useLogsDirectory] must therefore run **before** anything logs. Every entry point that resolves a
 * data directory calls it first; [infoscry.AppContext.open] calls it again as a safety net. When
 * Logback has already configured itself against a different directory — which happens only if a
 * caller logged earlier than it should have — the configuration is re-applied so the promise still
 * holds.
 */
object LoggingBootstrap {

    /** The property `logback.xml` reads for its sink. One spelling, in one place. */
    const val LOGS_DIR_PROPERTY = "infoscry.logs.dir"

    fun useLogsDirectory(paths: AppPaths) {
        val directory = paths.logsDir.toString()
        System.setProperty(LOGS_DIR_PROPERTY, directory)

        val context = LoggerFactory.getILoggerFactory() as? LoggerContext ?: return
        if (!context.isStarted) return
        if (context.getProperty(LOGS_DIR_PROPERTY) == directory) return

        val configuration = configurationResource() ?: return
        context.reset()
        JoranConfigurator().apply { this.context = context }.doConfigure(configuration)
    }

    /**
     * The configuration Logback would have used itself: the test configuration when it is present on
     * the classpath, the product configuration otherwise. Re-applying the same file (rather than a
     * programmatic equivalent of it) keeps one source of truth for appenders and limits.
     */
    private fun configurationResource(): URL? {
        val loader = LoggingBootstrap::class.java.classLoader
        return loader.getResource(TEST_CONFIGURATION) ?: loader.getResource(CONFIGURATION)
    }

    private const val CONFIGURATION = "logback.xml"
    private const val TEST_CONFIGURATION = "logback-test.xml"
}
