package infoscry.server

import infoscry.AppContext
import infoscry.config.RuntimeInfo
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The loopback port InfoScry uses unless another is asked for. */
const val DEFAULT_PORT = 8765

/** The only interface the server binds. There is deliberately no option to change it. */
const val LOOPBACK_HOST = "127.0.0.1"

/**
 * A running server, together with the runtime file that lets a CLI find it.
 *
 * Closing stops the engine and removes `runtime.json`. The order matters: the file must go away before
 * the process is regarded as finished, otherwise a CLI would discover a port nobody listens on.
 */
class RunningServer internal constructor(
    private val engine: EmbeddedServer<*, *>,
    val port: Int,
    private val runtimeFile: Path,
) : AutoCloseable {

    val url: String get() = "http://$LOOPBACK_HOST:$port"

    override fun close() {
        engine.stop(GRACE_PERIOD_MILLIS, TIMEOUT_MILLIS)
        RuntimeInfo.delete(runtimeFile)
    }

    private companion object {
        const val GRACE_PERIOD_MILLIS = 1_000L
        const val TIMEOUT_MILLIS = 3_000L
    }
}

/**
 * Starts the local API on the loopback interface and publishes how to reach it.
 *
 * The port is bound before `runtime.json` is written, so the file only ever describes a server that is
 * actually listening. If the file cannot be written the server is stopped again: a running server that a
 * CLI cannot discover, and that holds the process lock, would leave the data directory unusable until
 * the process was killed by hand. A failure there raises, with the port already released.
 *
 * Port `0` binds any free port and is reported through [RunningServer.port]; the CLI uses it in tests,
 * where a fixed port could collide with another test or another InfoScry.
 */
suspend fun startLoopbackServer(
    context: AppContext,
    port: Int = DEFAULT_PORT,
    jobEventIdleDeadlineMillis: Long = DEFAULT_JOB_EVENT_IDLE_DEADLINE_MILLIS,
): RunningServer {
    require(port in 0..MAX_PORT) { "port must be between 0 and $MAX_PORT, was $port" }

    val credentials = ApiCredentials.new()
    val engine = embeddedServer(Netty, port = port, host = LOOPBACK_HOST) {
        configureRoutes(context, credentials, jobEventIdleDeadlineMillis)
    }
    engine.start(wait = false)

    val boundPort = try {
        withContext(Dispatchers.IO) { engine.engine.resolvedConnectors().first().port }
    } catch (failure: Exception) {
        engine.stop(GRACE_PERIOD_MILLIS, TIMEOUT_MILLIS)
        throw IllegalStateException("InfoScry could not bind $LOOPBACK_HOST:$port: ${failure.message}", failure)
    }

    val runtime = RuntimeInfo(
        pid = ProcessHandle.current().pid(),
        port = boundPort,
        bearerToken = credentials.bearer,
    )
    try {
        runtime.writeTo(context.paths.runtimeFile)
    } catch (failure: Throwable) {
        engine.stop(GRACE_PERIOD_MILLIS, TIMEOUT_MILLIS)
        RuntimeInfo.delete(context.paths.runtimeFile)
        throw IllegalStateException(
            "the server was stopped because its runtime file could not be written to " +
                "${context.paths.runtimeFile}; a running server the CLI cannot discover would hold " +
                "the data directory lock without being usable",
            failure,
        )
    }

    return RunningServer(engine, boundPort, context.paths.runtimeFile)
}

private const val MAX_PORT = 65_535
private const val GRACE_PERIOD_MILLIS = 1_000L
private const val TIMEOUT_MILLIS = 3_000L
