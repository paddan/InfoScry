@file:OptIn(InternalAPI::class)

package infoscry.ocr

import io.ktor.client.engine.HttpClientEngineBase
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.callContext
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpProtocolVersion
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.util.date.GMTDate
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.InternalAPI
import io.ktor.utils.io.toByteArray
import java.util.Collections
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

/**
 * One scripted answer the recording engine replies with: the response half of what `FakeOpenAiServer`
 * scripts for a loopback endpoint, in the same shape — a status, a body, and headers a test may add.
 *
 * The last entry repeats for every request past the end of the script, so a script of two answers serves
 * a transcription and a review without the test having to know how many clients the pipeline builds.
 */
internal class RecordedImageResponse(
    val statusCode: Int = 200,
    val body: String = "",
    val headers: Map<String, String> = emptyMap(),
    /** A transport failure to throw instead of answering, as a socket or DNS error would surface. */
    val failure: Throwable? = null,
)

/**
 * One request the recording engine received: its method, full URL, headers, and the body as sent.
 *
 * The body is the protocol's own JSON — the prompt, the model and the page's base64 bytes — because what
 * this ticket proves is that a *permitted* external dispatch really formed that payload and aimed it at
 * the endpoint production classified, without a socket ever opening.
 */
internal data class RecordedImageRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    val body: String,
)

/**
 * A scripted [io.ktor.client.engine.HttpClientEngine] that records every request instead of opening a
 * connection — the transport a test injects through `ImageLlmClient`'s `engine` parameter so an external
 * dispatch can be observed without leaving the machine.
 *
 * It is an *engine*, not a whole `HttpClient`, on purpose: the client builds the `HttpClient` itself and
 * keeps applying `followRedirects = false` and `expectSuccess = false` to it, so what a test injects here
 * cannot silently turn redirects back on or let a status pass as a success. Each request consumes the next
 * script entry (the last repeats), mirroring `FakeOpenAiServer`, so a throttled attempt and its retry
 * appear as two recorded requests answered in sequence.
 *
 * One instance may serve every client a run builds — the per-page clients `LlmOcr` constructs do not
 * manage an injected engine's lifetime — and a test closes it when the assertions are done.
 */
internal class RecordingImageLlmEngine(
    private val script: List<RecordedImageResponse> = listOf(RecordedImageResponse()),
) : HttpClientEngineBase("recording-image-llm") {

    override val config: HttpClientEngineConfig = HttpClientEngineConfig()

    /** How many requests have been answered, which is the script's position. */
    private val handled = AtomicInteger(0)

    private val recorded = Collections.synchronizedList(mutableListOf<RecordedImageRequest>())

    /** Every request this engine received, in order. */
    val requests: List<RecordedImageRequest>
        get() = synchronized(recorded) { recorded.toList() }

    /** How many requests this engine answered, retries included. */
    val requestCount: Int get() = handled.get()

    init {
        require(script.isNotEmpty()) { "a scripted engine needs at least one response to answer with" }
    }

    override suspend fun execute(data: HttpRequestData): HttpResponseData {
        val scripted = script[min(handled.getAndIncrement(), script.size - 1)]
        recorded += RecordedImageRequest(
            method = data.method.value,
            url = data.url.toString(),
            headers = data.headers.entries().associate { (name, values) ->
                name.lowercase(Locale.ROOT) to values.joinToString(", ")
            },
            body = bodyTextOf(data.body),
        )
        scripted.failure?.let { throw it }
        val responseHeaders = buildList {
            add(ContentType.Application.Json.toString() to emptyList<String>())
            scripted.headers.forEach { (name, value) -> add(name to listOf(value)) }
        }
        return HttpResponseData(
            statusCode = HttpStatusCode.fromValue(scripted.statusCode),
            requestTime = GMTDate(),
            headers = headersOf(*responseHeaders.toTypedArray()),
            version = HttpProtocolVersion.HTTP_1_1,
            body = ByteReadChannel(scripted.body),
            callContext = callContext(),
        )
    }

    /**
     * The request body as the client's own pipeline delivered it to the engine.
     *
     * A `String` body with a content type arrives here as `TextContent` (the client's `HttpPlainText`
     * plugin encodes it), and both protocol bodies this project sends are UTF-8 JSON, so recording the
     * text is recording the bytes that would have gone on the wire.
     */
    private suspend fun bodyTextOf(body: OutgoingContent): String = when (body) {
        is OutgoingContent.ByteArrayContent -> body.bytes().decodeToString()
        is OutgoingContent.ReadChannelContent -> body.readFrom().toByteArray().decodeToString()
        else -> ""
    }
}
