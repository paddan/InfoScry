package infoscry.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.findObject
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import infoscry.AppContext
import infoscry.config.AppPaths
import infoscry.embedding.DocumentEmbedder
import infoscry.embedding.WarmableEmbedder
import infoscry.embedding.startEmbedderWarmUp
import infoscry.config.ProcessLockUnavailable
import infoscry.jobs.ImportJobHandler
import infoscry.jobs.ImportPipeline
import infoscry.logging.LoggingBootstrap
import infoscry.server.ApiJson
import infoscry.server.PRODUCT_NAME
import infoscry.server.ServerSettings
import infoscry.server.startServer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable

/** What `serve --json` reports, so a script can find the URL it published. */
@Serializable
data class ServeResponse(val url: String, val port: Int, val pid: Long)

/**
 * `infoscry serve` — the local API and the web interface, in the foreground.
 *
 * The process stays in the foreground on purpose: it owns the database and the data directory, so the
 * operator's terminal (or a launchd agent) is the thing that ends it. `INFOSCRY_BIND` and
 * `INFOSCRY_PUBLIC_ORIGIN` decide where it listens and which origin browsers use.
 */
class ServeCommand(
    private val pipeline: (AppContext) -> ImportPipeline = { context -> ImportPipeline.production(context) },
    private val importEmbedder: (AppContext) -> () -> DocumentEmbedder? = ::productionImportEmbedder,
) : CliktCommand(name = "serve") {

    private val port by option(
        "--port",
        help = "Port to listen on, overriding INFOSCRY_BIND's; 0 asks the operating system for a free one",
    ).int()

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
            // The worker is attached before anything is served, so an enqueued import is always owned by a
            // live process: a request that was accepted while no runner existed would be a job nobody runs.
            // Attaching first makes that an ordering invariant rather than a comment about one.
            val embedder = importEmbedder(opened)
            ImportJobHandler.attachTo(opened, pipeline(opened), documentEmbedder = embedder)
            // Search and imports share this embedder, so building its accelerator session now spares the
            // first semantic search the model load. A test's fake embedder is not warmable and is left alone.
            startEmbedderWarmUp { embedder() as? WarmableEmbedder }
            opened to startServer(opened, ServerSettings.fromEnvironment(portOverride = port))
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
        if (runCatching { context.accounts.listUsers().isEmpty() }.getOrDefault(false)) {
            echo("No users exist yet; create the first administrator with `infoscry admin create-user --admin <username>`.", err = true)
        }
        echo("Press Ctrl-C to stop.", err = true)

        if (!options.json) {
            val opened = try {
                val opener = ProcessBuilder("open", server.url)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
                if (opener.waitFor(5, TimeUnit.SECONDS)) {
                    opener.exitValue() == 0
                } else {
                    opener.destroy()
                    false
                }
            } catch (_: Exception) {
                false
            }
            if (!opened) echo("Could not open the browser; use ${server.url} instead.", err = true)
        }

        // The shutdown hook above closes everything; this thread only keeps the process alive.
        CountDownLatch(1).await()
    }
}
