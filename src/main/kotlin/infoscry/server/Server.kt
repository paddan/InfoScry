package infoscry.server

import infoscry.AppContext
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The port InfoScry listens on unless another is asked for. */
const val DEFAULT_PORT = 8080

/** The interface InfoScry binds unless `INFOSCRY_BIND` names another. */
const val LOOPBACK_HOST = "127.0.0.1"

/** A running server. Closing it stops the engine. */
class RunningServer internal constructor(
    private val engine: EmbeddedServer<*, *>,
    val port: Int,
    private val settings: ServerSettings,
) : AutoCloseable {

    /** Where a browser reaches the server: the public origin when one is configured, else loopback. */
    val url: String
        get() = settings.publicOrigin ?: "http://${if (isWildcard(settings.host)) LOOPBACK_HOST else displayHost(settings.host)}:$port"

    override fun close() {
        engine.stop(GRACE_PERIOD_MILLIS, TIMEOUT_MILLIS)
    }

    private fun isWildcard(host: String): Boolean = host == "0.0.0.0" || host == "::"

    private fun displayHost(host: String): String = if (host.contains(':')) "[$host]" else host

    private companion object {
        const val GRACE_PERIOD_MILLIS = 1_000L
        const val TIMEOUT_MILLIS = 3_000L
    }
}

/**
 * Starts the API and the web interface on [settings]' address.
 *
 * Port `0` binds any free port and is reported through [RunningServer.port]; tests use it, where a fixed port
 * could collide with another test or another InfoScry.
 */
suspend fun startServer(
    context: AppContext,
    settings: ServerSettings = ServerSettings(),
    jobEventIdleDeadlineMillis: Long = DEFAULT_JOB_EVENT_IDLE_DEADLINE_MILLIS,
    picker: suspend (Boolean) -> List<String> = ::pickWithOsascript,
): RunningServer {
    val engine = embeddedServer(Netty, port = settings.port, host = settings.host) {
        configureRoutes(context, settings, jobEventIdleDeadlineMillis, picker)
    }
    val boundPort = try {
        engine.start(wait = false)
        withContext(Dispatchers.IO) { engine.engine.resolvedConnectors().first().port }
    } catch (failure: Exception) {
        engine.stop(GRACE_PERIOD_MILLIS, TIMEOUT_MILLIS)
        throw IllegalStateException("InfoScry could not bind ${settings.host}:${settings.port}: ${failure.message}", failure)
    }
    return RunningServer(engine, boundPort, settings)
}

private const val GRACE_PERIOD_MILLIS = 1_000L
private const val TIMEOUT_MILLIS = 3_000L
