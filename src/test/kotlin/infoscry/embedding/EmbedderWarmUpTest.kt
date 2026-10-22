package infoscry.embedding

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The background warm-up `serve` starts, so the first semantic search does not pay for building and
 * verifying the accelerator session. It must never be what stops the server: a machine whose GPU cannot be
 * initialised still serves keyword search and source reads, and the search that asks for a vector reports
 * the remedy itself.
 */
class EmbedderWarmUpTest {

    @Test
    fun `the warm-up prepares the shared embedder off the calling thread`() {
        val warmed = AtomicInteger()
        val caller = Thread.currentThread()
        var warmedOn: Thread? = null

        startEmbedderWarmUp {
            object : WarmableEmbedder {
                override fun warmUp() {
                    warmedOn = Thread.currentThread()
                    warmed.incrementAndGet()
                }
            }
        }.join()

        assertEquals(1, warmed.get())
        assertTrue(warmedOn !== caller, "the warm-up must not hold up the thread that starts the server")
        assertTrue(warmedOn!!.isDaemon, "a warm-up still running must not keep the process alive at shutdown")
    }

    @Test
    fun `no installed model means there is nothing to warm`() {
        val uncaught = mutableListOf<Throwable>()
        val thread = startEmbedderWarmUp { null }
        thread.setUncaughtExceptionHandler { _, failure -> uncaught += failure }
        thread.join()

        assertTrue(uncaught.isEmpty())
    }

    @Test
    fun `an accelerator that cannot start is logged and does not escape the warm-up`() {
        val logger = LoggerFactory.getLogger(EMBEDDER_WARM_UP_LOGGER) as Logger
        val records = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(records)
        var uncaught: Throwable? = null
        try {
            val thread = startEmbedderWarmUp {
                object : WarmableEmbedder {
                    override fun warmUp() {
                        throw GpuUnavailableException(GpuRuntime.GPU_UNAVAILABLE_CODE, "CoreML is not available")
                    }
                }
            }
            thread.setUncaughtExceptionHandler { _, failure -> uncaught = failure }
            thread.join()
        } finally {
            logger.detachAppender(records)
        }

        assertNull(uncaught)
        val warning = records.list.single { it.level == Level.WARN }
        assertTrue(warning.formattedMessage.contains("warm"), warning.formattedMessage)
    }
}
