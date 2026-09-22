package infoscry.llm

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.math.min

/**
 * A scripted fake of an OpenAI-compatible endpoint on a random local port.
 *
 * Each request consumes the next script entry, so a retried request shows up as an extra handled request
 * and the tests can prove the retry budget. A streaming response is written back in [fragmentBytes]-sized
 * pieces with [gapMillis] between pieces, so the adapter must reassemble events that arrive fragmented
 * across many reads; [holdMillis] lets a test keep the connection open after the body, which is how the
 * cancellation test proves a cancelled consumer unwinds a blocked read. The server runs on its own
 * threads, never on the test's dispatcher.
 */
internal class FakeOpenAiResponse(
    val statusCode: Int = 200,
    val body: String = "",
    val stream: Boolean = false,
    val fragmentBytes: Int = 1024,
    val gapMillis: Long = 0,
    val holdMillis: Long = 0,
    /** Content-Length to advertise; 0 means the body length. A value beyond the body lets a test keep
     *  the client awaiting more bytes while the connection stays open (the cancellation test). */
    val declaredLength: Long = 0,
    val onFirstFragment: (() -> Unit)? = null,
)

internal class FakeOpenAiServer(
    private val script: List<FakeOpenAiResponse>,
) : AutoCloseable {

    private val server: HttpServer = HttpServer.create(InetSocketAddress(LOOPBACK_HOST, 0), 0)
    private val handled: AtomicInteger = AtomicInteger(0)
    private val lastAuthorization: AtomicReference<String?> = AtomicReference(null)
    private val lastApiKey: AtomicReference<String?> = AtomicReference(null)

    val url: String get() = "http://$LOOPBACK_HOST:${server.address.port}"
    val handledRequests: Int get() = handled.get()
    val authorization: String? get() = lastAuthorization.get()
    val xApiKey: String? get() = lastApiKey.get()

    init {
        server.createContext("/") { exchange -> serve(exchange) }
        try {
            server.start()
        } catch (failure: IOException) {
            throw IllegalArgumentException("the fake OpenAI server could not start", failure)
        }
    }

    private fun serve(exchange: HttpExchange) {
        try {
            val index = handled.getAndIncrement()
            lastAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"))
            lastApiKey.set(exchange.getRequestHeaders().getFirst("x-api-key"))
            val response = script[min(index, script.size - 1)]
            if (response.stream) {
                streamResponse(exchange, response)
            } else {
                respondOnce(exchange, response)
            }
        } catch (ignored: IOException) {
            // The client closed early (cancelled) or the socket broke; nothing to clean up.
        }
    }

    private fun respondOnce(exchange: HttpExchange, response: FakeOpenAiResponse) {
        val body = response.body.toByteArray(Charsets.UTF_8)
        exchange.sendResponseHeaders(response.statusCode, body.size.toLong())
        exchange.getResponseBody().write(body)
        exchange.close()
    }

    private fun streamResponse(exchange: HttpExchange, response: FakeOpenAiResponse) {
        val content = response.body.toByteArray(Charsets.UTF_8)
        val declared = if (response.declaredLength > 0) response.declaredLength else content.size.toLong()
        // A fixed Content-Length streams just as well as chunked for this client and the JDK server
        // delivers fixed-length bodies reliably, which chunked (-1) did not in the first attempt.
        exchange.sendResponseHeaders(response.statusCode, declared)
        val out = exchange.getResponseBody()
        var offset = 0
        var signalled = false
        try {
            while (offset < content.size) {
                val end = min(content.size, offset + max(1, response.fragmentBytes))
                out.write(content, offset, end - offset)
                out.flush()
                if (!signalled) {
                    response.onFirstFragment?.invoke()
                    signalled = true
                }
                offset = end
                if (response.gapMillis > 0) Thread.sleep(response.gapMillis)
            }
        } catch (ignored: IOException) {
            // the client cancelled the exchange
        }
        if (response.holdMillis > 0) {
            try {
                Thread.sleep(response.holdMillis)
            } catch (ignored: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        try {
            out.close()
            exchange.close()
        } catch (ignored: IOException) {
            // the exchange is already gone
        }
    }

    override fun close() {
        server.stop(0)
    }

    private companion object {
        const val LOOPBACK_HOST = "127.0.0.1"
    }
}