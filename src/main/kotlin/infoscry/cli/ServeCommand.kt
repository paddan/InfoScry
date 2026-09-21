package infoscry.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.findObject
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import infoscry.AppContext
import infoscry.config.AppPaths
import infoscry.config.ProcessLockUnavailable
import infoscry.logging.LoggingBootstrap
import infoscry.server.ApiJson
import infoscry.server.DEFAULT_PORT
import infoscry.server.PRODUCT_NAME
import infoscry.server.startLoopbackServer
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable

/** What `serve --json` reports, so a script can find the URL it published. */
@Serializable
data class ServeResponse(val url: String, val port: Int, val pid: Long)

/**
 * `infoscry serve` — the local API and the web interface, in the foreground.
 *
 * The process stays in the foreground on purpose: it owns the data directory, so the operator's terminal
 * is the thing that ends it. Interrupting it stops the server and removes `runtime.json`, which is what
 * tells a later CLI that nobody is listening.
 */
class ServeCommand : CliktCommand(name = "serve") {

    private val port by option(
        "--port",
        help = "Loopback port to listen on; 0 asks the operating system for a free one",
    ).int().default(DEFAULT_PORT)

    private val jsonFlag by option("--json", help = JSON_HELP).flag()

    private val dataDirOption by option("--data-dir", help = DATA_DIR_HELP).path()

    private val parentOptions by findObject<CliOptions>()

    override fun run() {
        val options = resolveOptions(parentOptions, jsonFlag, dataDirOption)
        try {
            serve(options)
        } catch (unavailable: ProcessLockUnavailable) {
            throw CliFailure(unavailable.message.orEmpty(), unavailable)
        } catch (failure: IllegalStateException) {
            throw CliFailure(failure.message ?: "the server could not start", failure)
        }
    }

    private fun serve(options: CliOptions) {
        LoggingBootstrap.useLogsDirectory(AppPaths.of(options.dataDir))

        val (context, server) = runBlocking {
            val opened = AppContext.open(options.dataDir)
            val started = startLoopbackServer(opened, port)
            opened to started
        }
        Runtime.getRuntime().addShutdownHook(
            Thread {
                server.close()
                context.close()
            },
        )

        if (options.json) {
            echo(
                infoscry.server.ApiJson.encodeToString(
                    ServeResponse(
                        url = server.url,
                        port = server.port,
                        pid = ProcessHandle.current().pid(),
                    ),
                ),
            )
        } else {
            echo("$PRODUCT_NAME is listening on ${server.url}")
        }
        echo("Press Ctrl-C to stop.", err = true)

        // The shutdown hook above closes everything; this thread only keeps the process alive.
        CountDownLatch(1).await()
    }
}
