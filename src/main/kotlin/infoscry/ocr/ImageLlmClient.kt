package infoscry.ocr

import infoscry.llm.LlmJson
import infoscry.llm.LlmProvider
import infoscry.llm.RetryPolicy
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64
import java.util.HexFormat
import java.util.Locale
import javax.imageio.ImageIO
import javax.net.ssl.SSLException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * One image request failed, in this project's own vocabulary.
 *
 * The codes are the whole answer a caller gets: a page's failure travels into queues, logs and the CLI,
 * and a provider's own message may quote the request, the image or a key. Nothing here is interpolated
 * into a message either — no endpoint (which may carry credentials), no key value, no page text, and no
 * response body — so what crosses the logging boundary is a code and a fixed sentence about it.
 *
 * [dispatched] says whether a provider call was made at all, which is the difference between "nothing left
 * this client" and "a paid call may have been made": a caller that refuses a request before dispatch can act
 * on the first, and only the second can have been billed.
 */
class ImageLlmException(
    val code: String,
    message: String,
    val statusCode: Int? = null,
    val dispatched: Boolean = false,
) : IOException(message) {

    companion object {

        /** The page's artifact could not be read back, so the image the reading would be made from is gone. */
        const val IMAGE_UNREADABLE: String = "OCR_IMAGE_UNREADABLE"

        /** The artifact is no longer the image the page record names, so a reading could not be attributed to it. */
        const val IMAGE_CHANGED: String = "OCR_IMAGE_CHANGED"

        /** The artifact's own header cannot state the raster's size, so the page's image share cannot be budgeted. */
        const val IMAGE_DIMENSIONS_UNVERIFIED: String = "OCR_IMAGE_DIMENSIONS_UNVERIFIED"

        /** The artifact is in a form neither image protocol accepts, so no provider could read it. */
        const val IMAGE_FORMAT_UNSUPPORTED: String = "OCR_IMAGE_FORMAT_UNSUPPORTED"

        /** The artifact is larger than either provider's documented per-image limit. */
        const val IMAGE_TOO_LARGE: String = "OCR_IMAGE_TOO_LARGE"

        /** The image, the prompt and the reserved answer do not fit the profile's context window. */
        const val REQUEST_OVERSIZED: String = "OCR_REQUEST_OVERSIZED"

        /** A page image may only leave this machine through ticket 07's dispatch permit, and there is none. */
        const val EXTERNAL_DISPATCH_NOT_PERMITTED: String = "OCR_EXTERNAL_DISPATCH_NOT_PERMITTED"

        /** The profile names no endpoint, so there is nowhere to dispatch a page to. */
        const val PROFILE_ENDPOINT_REQUIRED: String = "OCR_PROFILE_ENDPOINT_REQUIRED"

        /** The profile's key variable is not set, so no page payload is sent without a credential. */
        const val MISSING_CREDENTIAL: String = "OCR_MISSING_CREDENTIAL"

        /** The provider answered a redirect; a page is only ever sent to the endpoint the profile names. */
        const val REDIRECT_REFUSED: String = "OCR_REDIRECT_REFUSED"

        /** The provider refused the request: the image form, the model or the endpoint is not usable. */
        const val IMAGE_NOT_SUPPORTED: String = "OCR_IMAGE_NOT_SUPPORTED"

        /** The provider answered a status that is neither a refusal of the image nor a temporary failure. */
        const val PROVIDER_REFUSED: String = "OCR_PROVIDER_REFUSED"

        /** The provider rejected the credential. */
        const val AUTHENTICATION: String = "OCR_AUTHENTICATION_FAILED"

        /** The provider asked us to slow down, and the bounded retries are spent. */
        const val RATE_LIMITED: String = "OCR_RATE_LIMITED"

        /** The provider could not be reached, or failed while answering. */
        const val PROVIDER_UNAVAILABLE: String = "OCR_PROVIDER_UNAVAILABLE"

        /** The provider did not answer inside the bound, so this page's reading is unknown. */
        const val TIMEOUT: String = "OCR_TIMEOUT"

        /** The response was larger than the bound this client holds for one page's answer. */
        const val RESPONSE_TOO_LARGE: String = "OCR_RESPONSE_TOO_LARGE"

        /** The response is not the protocol's own document, or not this answer's schema. */
        const val MALFORMED_RESPONSE: String = "OCR_MALFORMED_RESPONSE"

        /** The answer stopped because the output limit was reached, so it is a prefix of a reading. */
        const val TRUNCATED: String = "OCR_TRUNCATED_RESPONSE"

        /** The answer stopped for a reason that is not a completed reading. */
        const val INCOMPLETE: String = "OCR_INCOMPLETE_RESPONSE"

        /** The answer asked for a tool call, which no reading may come from. */
        const val TOOL_CALL_REFUSED: String = "OCR_TOOL_CALL_REFUSED"

        /** The answer did not name the page it was asked about. */
        const val WRONG_PAGE: String = "OCR_WRONG_PAGE"

        /** The attempt names a profile revision this build no longer knows. */
        const val PROFILE_REVISION_UNKNOWN: String = "OCR_PROFILE_REVISION_UNKNOWN"

        /** The attempt was snapshotted under another transcription prompt version than this build ships. */
        const val PROMPT_VERSION_MISMATCH: String = "OCR_PROMPT_VERSION_MISMATCH"
    }
}

/**
 * The page a dispatch is about: what a permit is asked about, and what the answer has to name.
 *
 * [documentId] is absent for the synthetic capability check, which is about no document at all — which is
 * also why a probe cannot stand in for a page: the identity it dispatches under names no document.
 */
data class PageDispatchIdentity(val unitId: String, val ordinal: Int, val documentId: String? = null)

/**
 * One dispatch an external-processing permit is asked about.
 *
 * The revision and the page are both named because the permit ticket 07 mints is bound to an operation, a
 * profile revision and a document/page identity: a validator has to be able to answer "was *this* page
 * approved, through *this* revision" rather than "is anything approved".
 */
data class ExternalDispatchPermitRequest(
    val profileRevisionId: String,
    val page: PageDispatchIdentity,
)

/**
 * Whether one dispatch may leave this machine.
 *
 * Ticket 07 owns the persisted admission service that implements this; this build injects **nothing**, so
 * an external destination cannot be dispatched to at all — and because the validator is an injected
 * capability with no permissive default, a fake one in a test cannot become a production bypass.
 *
 * One exception exists and is not a permit: the explicit capability probe of a client built with
 * `allowSyntheticProbeWithoutPermit` may dispatch its constant synthetic image to an external endpoint with no
 * validator at all. That image names no document and the check runs only on a user's request, so there is
 * nothing here for a permit to protect. Transcription and review never get this exception.
 */
fun interface ExternalDispatchPermitValidator {
    fun isPermitted(request: ExternalDispatchPermitRequest): Boolean
}

/** One page's reading, as the transcription schema carries it. */
data class ImageReading(
    val text: String,
    val unreadable: List<UnreadableSpan> = emptyList(),
    /** The model version the provider reported, when it reported one. */
    val modelVersion: String? = null,
)

/**
 * Which side of the review request the image supports, in the request's own labels.
 *
 * The request labels its two readings A and B and says nothing else about them, so this is the whole of what
 * a reviewer can answer: a side, or `UNCERTAIN` when the image cannot settle the difference. Which side is
 * the published reading and which is the candidate is deliberately not in this vocabulary — the request must
 * not tell the reviewer which side to prefer, and the caller that sent the readings maps this to its own
 * [ReviewerRecommendation] where it knows which side each reading went out as.
 */
@Serializable
enum class ReviewerSide { A_BETTER, B_BETTER, UNCERTAIN }

/** One validated reviewer answer, as the review schema carries it. */
data class ImageReview(
    val recommendation: ReviewerSide,
    /** The reviewer's own confidence, when it gave one between 0 and 1. It decides nothing. */
    val confidence: Double?,
    val reasons: List<ImageReviewReason>,
    /** The model version the provider reported, when it reported one. */
    val modelVersion: String?,
)

/**
 * One reason a reviewer gave, with the spans it pointed at validated inside the two readings it judged.
 *
 * The explanation is the reviewer's own bounded words: untrusted text, shown to a person beside the page and
 * never used to change what the text is. A span is present only when the reviewer named that side, which is
 * how a region only one reading holds is described.
 */
data class ImageReviewReason(
    val explanation: String,
    val spanA: TextSpan?,
    val spanB: TextSpan?,
)

/** One validated answer, with what the provider said about the model that produced it. */
data class ImageAnswer<T>(val value: T, val modelVersion: String?)

/**
 * The shipped transcription instructions, as one versioned body.
 *
 * The body is a resource rather than a string constant, following the Ask and Investigate prompts: it is
 * read from the classpath so it can be reviewed as prose. The *number* is [OCR_TRANSCRIPTION_PROMPT_VERSION]
 * in the seam, because it travels in the attempt's identity and its fingerprint: two readings of one page
 * under different instructions are not the same evidence, so a body change is a new version and an attempt
 * snapshotted under the old one is refused rather than read with the new words.
 *
 * Two placeholders are substituted — the page's stable unit id and its ordinal — because the answer has to
 * name the page it belongs to. Nothing else about the request varies: the same instructions are sent for
 * every page of every document, and no page's text, name or path reaches the prompt.
 */
object OcrTranscriptionPrompt {

    /** The classpath resource the body lives in. */
    const val RESOURCE_PATH: String = "prompts/ocr-transcription.txt"

    /** The version this build's body is, which an attempt's snapshot has to agree with. */
    val version: Int get() = OCR_TRANSCRIPTION_PROMPT_VERSION

    private val body: String by lazy {
        OcrTranscriptionPrompt::class.java.classLoader.getResourceAsStream(RESOURCE_PATH)?.use { stream ->
            stream.readBytes().decodeToString()
        } ?: error("prompt resource $RESOURCE_PATH is missing from the classpath")
    }

    /** The shipped body with its placeholders unsubstituted. */
    fun body(): String = body

    /** The body for one page, with the identity the answer has to echo substituted into it. */
    fun forPage(identity: PageDispatchIdentity): String = renderPrompt(
        body,
        mapOf("unitId" to identity.unitId, "ordinal" to identity.ordinal.toString()),
    )
}

/**
 * The shipped review instructions, as one versioned body.
 *
 * The body is a resource rather than a string constant for the same reason the transcription body is: it is
 * read from the classpath so it can be reviewed as prose. The *number* is [OCR_REVIEW_PROMPT_VERSION] in the
 * seam, because it travels in the review's fingerprint: two comparisons of one page under different
 * instructions are not the same comparison, so a body change is a new version and an attempt snapshotted
 * under the old one is refused rather than judged with the new words.
 *
 * Four placeholders are substituted: the page's stable unit id and ordinal, which the answer has to echo, and
 * the two readings the reviewer is asked about. The readings are inserted inside fixed markers and are
 * treated as data — the reviewer has no tools, and a reading that contains instructions of its own cannot
 * change what this asks for, nor can a reading that happens to spell a placeholder: substitution happens in
 * one pass over the body, so text this prompt inserts is never read as this prompt's own template. Nothing
 * in the body says which reading is preferred, or which of the two is the published one and which the
 * candidate: the readings are labelled A and B and nothing else, and the answer the body asks for names a
 * side (`A_BETTER`, `B_BETTER` or `UNCERTAIN`) rather than this application's own
 * [ReviewerRecommendation], so the request itself cannot introduce a preference for either side.
 */
object OcrReviewPrompt {

    /** The classpath resource the body lives in. */
    const val RESOURCE_PATH: String = "prompts/ocr-review.txt"

    /** The version this build's body is, which an attempt's snapshot has to agree with. */
    val version: Int get() = OCR_REVIEW_PROMPT_VERSION

    private val body: String by lazy {
        OcrReviewPrompt::class.java.classLoader.getResourceAsStream(RESOURCE_PATH)?.use { stream ->
            stream.readBytes().decodeToString()
        } ?: error("prompt resource $RESOURCE_PATH is missing from the classpath")
    }

    /** The shipped body with its placeholders unsubstituted. */
    fun body(): String = body

    /** The body for one comparison, with the page identity and both readings substituted into it. */
    fun forPage(identity: PageDispatchIdentity, readingA: String, readingB: String): String = renderPrompt(
        body,
        mapOf(
            "unitId" to identity.unitId,
            "ordinal" to identity.ordinal.toString(),
            "readingA" to readingA,
            "readingB" to readingB,
        ),
    )
}

/** The placeholders the shipped prompt bodies are written in. */
private val PROMPT_PLACEHOLDER = Regex("\\{(unitId|ordinal|readingA|readingB)\\}")

/**
 * One prompt body with its placeholders substituted, in a single pass over the *body*.
 *
 * The pass is single because what it substitutes includes page text, which is untrusted: substituting one
 * value and then looking for the next placeholder in the result would let a reading that contains another
 * placeholder's spelling be rewritten — a text holding the literal `{readingB}` would have a different
 * reading put into the middle of it — and the reviewer would be asked about a reading nobody supplied. This
 * replaces every placeholder in the template exactly once and never looks at what it inserted.
 *
 * A name the body does not use is simply not in [values], and a placeholder with no value left in the body
 * is a prompt this build cannot render, which is a build defect rather than something to send half-filled.
 */
private fun renderPrompt(body: String, values: Map<String, String>): String =
    PROMPT_PLACEHOLDER.replace(body) { match ->
        values[match.groupValues[1]] ?: error("prompt placeholder ${match.value} has no value to render")
    }

/**
 * The bounded image request: the page's own bytes, one fixed prompt, and a validated answer.
 *
 * This is the only place a page image becomes a provider payload, for both protocols this build speaks:
 *
 * - **The image is the page's own bytes.** The artifact is read back, hashed against the page record's
 *   hash, and sent base64-encoded in the protocol's own image form. A page whose file has changed, is
 *   missing, is larger than either provider accepts, or is in a form no image block takes is refused
 *   *before* anything is sent, so no reading can be attributed to pixels that were never sent. What the
 *   image *is* — its media type and its pixel count — is read from those bytes, and the record has to
 *   agree with them, because the record is what a caller supplied and an image share of zero is not a
 *   budget.
 * - **No tools, ever.** Neither request type has a field for a tool definition, so no page image — including
 *   one that carries the words "ignore your instructions" or "call this tool" — can add one, request one or
 *   change the schema its answer is validated against. An answer that *did* ask for a tool is refused.
 * - **The whole request is budgeted before dispatch.** The instructions, the image's own measured tokens
 *   and the reserved output are measured against the profile's context window; a request that does not fit
 *   is refused rather than sent with its text truncated. No tile plan is used: a page is sent whole or not
 *   at all, and a plan that split one would need a version of its own and a rule for preserving the page's
 *   layout when it is put back together.
 * - **Every call is bounded.** One request is one coroutine timeout, one response-size bound and a bounded
 *   number of retries, and only a status that proves the provider did not answer (429, 5xx) is retried. A
 *   timeout is *not* retried: the provider may already have produced and billed an answer nobody can commit,
 *   and so is a crash between a response and its commit — a repeat request is possible and this build does
 *   not claim exactly-once external billing.
 * - **A redirect is never followed.** The transport is configured not to, and a 3xx is refused as itself:
 *   following one would carry a page image to a destination the profile never named.
 * - **A non-local destination needs ticket 07's permit.** Constructing a client for one without a validator
 *   is refused, and every dispatch asks the validator about the revision and the page first. The one
 *   exception is the synthetic capability probe, and only with `allowSyntheticProbeWithoutPermit`; see
 *   [probeCapability]. Local loopback endpoints need neither, which is what makes a local model usable today.
 * - **The answer has to be a complete reading of *this* page.** The provider's own document is parsed,
 *   the finish reason must mean "complete output", and the answer must name the unit id and ordinal it was
 *   asked about; a truncated, throttled, timed-out, malformed or misattributed answer is a failure rather
 *   than a reading. The resolved model version is carried back, because an alias the provider resolved is
 *   what the reading was really made by.
 *
 * Nothing here publishes or commits: a reading that comes back is the caller's to verify, fingerprint and
 * stage, and an empty reading is left as an empty reading for the seam's own rule.
 */
class ImageLlmClient(
    private val profile: OcrProfileRevision,
    private val lookup: (String) -> String? = System::getenv,
    private val permits: ExternalDispatchPermitValidator? = null,
    /**
     * Told immediately before each network attempt, retries included.
     *
     * A caller that has to account for what it paid for cannot count calls from its own side: this client
     * retries a throttled or failed request by itself, and only here is each attempt a separate event. It is
     * told nothing at all when the dispatch is local, because an external page allowance is about pages that
     * leave this machine.
     */
    private val calls: ((ExternalDispatchPermitRequest) -> Unit)? = null,
    private val timeout: Duration = DEFAULT_TIMEOUT,
    private val maxImageBytes: Int = MAX_IMAGE_BYTES,
    private val maxResponseBytes: Int = MAX_RESPONSE_BYTES,
    private val retryPolicy: RetryPolicy = RetryPolicy(),
    /**
     * The transport's engine, or null for this client's own CIO engine.
     *
     * This is the seam a test uses to inject a recording engine so an external dispatch can be observed
     * without a socket opening. What a test injects is an *engine* rather than a whole [HttpClient]
     * precisely so it cannot weaken the two rules below: the client still builds the [HttpClient] around
     * it and still sets `followRedirects = false` and `expectSuccess = false` itself. Null is production:
     * the client then builds its own CIO engine and owns closing it, exactly as before this seam existed.
     */
    private val engine: HttpClientEngine? = null,
    /**
     * Whether the explicit capability probe may reach an external destination without a permit validator.
     *
     * The product owner's exception, and only that: the probe's synthetic image is this build's constant
     * page of project words, so no user document can be in it, and the check runs only because a user asked
     * for it. With this flag set, [probeCapability] is the one call that may dispatch to an external endpoint
     * with no validator; it still asks a validator when one is present. Transcription and review never gain
     * this exception, whatever the flag says. False is the default and keeps the strict rule.
     */
    private val allowSyntheticProbeWithoutPermit: Boolean = false,
) : AutoCloseable {

    private val endpoint: String = profile.endpoint.trimEnd('/')

    /**
     * The transport, with redirects off.
     *
     * This is not a preference: with Ktor's default, a loopback endpoint that answers `302` would make this
     * client re-send the page image to whatever host the response named, before any code here could refuse
     * it. The client therefore builds its own transport — around an injected [engine] when a test supplies
     * one, around its own CIO engine otherwise — and applies these two settings in either case, so what
     * the transport is cannot turn redirects back on.
     */
    private val transport: HttpClient = if (engine != null) {
        // A test's injected engine still gets this client's own rules: the HttpClient is built here, so
        // redirects and expectSuccess are set on it no matter what the transport is, and what an injected
        // engine can script (a 3xx, a 5xx) is still judged by this client rather than by the transport.
        // The client does not manage an injected engine's lifetime: one recording engine serves every
        // per-page client a run builds, and closing one page's client must not close it.
        HttpClient(engine) {
            followRedirects = false
            expectSuccess = false
        }
    } else {
        // Production: the client's own CIO engine, which the client owns and closes with it — the
        // behaviour this code had before any seam existed.
        HttpClient(CIO) {
            followRedirects = false
            expectSuccess = false
        }
    }

    init {
        require(timeout.isPositive()) { "an image request needs a positive timeout, was $timeout" }
        require(maxImageBytes > 0) { "an image request needs a positive image bound, was $maxImageBytes" }
        require(maxResponseBytes > 0) { "an image request needs a positive response bound, was $maxResponseBytes" }
        if (endpoint.isBlank()) {
            throw ImageLlmException(
                ImageLlmException.PROFILE_ENDPOINT_REQUIRED,
                "this image-model profile names no endpoint, so there is nowhere a page image could be " +
                    "sent and no reading could be made from it",
            )
        }
        if (profile.scope == OcrEndpointScope.EXTERNAL && permits == null && !allowSyntheticProbeWithoutPermit) {
            throw ImageLlmException(
                ImageLlmException.EXTERNAL_DISPATCH_NOT_PERMITTED,
                "sending a page image off this machine needs the external dispatch permit of an admitted " +
                    "rescan, and this build has no permit validator: the image was not sent",
            )
        }
    }

    /**
     * Reads one page's transcription through the shipped prompt.
     *
     * The answer is validated against the transcription schema, must name this page, and must be complete;
     * a page the model could not read comes back as an empty reading rather than as a success, which the
     * seam's own rule turns into [OcrPageResult.EMPTY_READING_CODE]. Blankness is *not* decided here: only
     * the caller that holds the raster can ask whether the paper is blank.
     */
    suspend fun transcribe(page: PageImage): ImageReading {
        val answer = requestOn(page, OcrTranscriptionPrompt.forPage(identityOf(page))) { content ->
            transcriptionOf(content)
        }
        return ImageReading(
            text = answer.value.text,
            unreadable = answer.value.unreadable,
            modelVersion = answer.modelVersion,
        )
    }

    /**
     * Checks that this profile can carry an image at all, using the explicit synthetic image.
     *
     * The image is this build's own constant — a page of this project's words, drawn and encoded here — so
     * a capability check can never carry a user's document, and the identity it is dispatched under names no
     * document. What a passing check proves is *transport*: the provider accepted an image block and
     * answered a complete, schema-valid reading of the synthetic page. It says nothing about transcription
     * quality.
     *
     * This is the one exception to ticket 07's permit rule. An external destination is dispatched to without
     * a permit validator only when this client was built with [allowSyntheticProbeWithoutPermit]: the synthetic
     * image is a constant that names no document, and the check runs only because a user asked for it. A
     * validator that is present is still asked, and a refusal stops the probe before anything is sent.
     * Transcription and review have no such exception.
     */
    suspend fun probeCapability(): ImageReading {
        val identity = PageDispatchIdentity(unitId = CAPABILITY_PROBE_UNIT_ID, ordinal = 0)
        val image = syntheticProbeImage()
        val answer = exchange(
            identity = identity,
            image = image,
            form = imageFormOf(image),
            instructions = OcrTranscriptionPrompt.forPage(identity),
            probe = true,
        ) { content -> transcriptionOf(content) }
        return ImageReading(
            text = answer.value.text,
            unreadable = answer.value.unreadable,
            modelVersion = answer.modelVersion,
        )
    }

    /**
     * Asks the reviewer which of two readings of [page] the page image supports.
     *
     * This is the comparison slice's own call, and it goes through the same envelope as a transcription:
     * the page's own bytes, hashed and format-checked, budgeted against the profile's window, one bounded
     * timeout, no redirects, a permit for any destination off this machine, and an answer that has to be
     * complete and to name this page. What is validated *extra* here is this answer's own schema: the
     * recommendation is one of the three the prompt promises, the confidence is a number between 0 and 1, the
     * reasons are bounded in number and in length, and every span a reason names is a stretch of the reading
     * it claims to be about. An answer that fails any of that is refused as this schema's failure rather than
     * stored as an opinion about a page.
     *
     * Which of the two readings is which is the caller's business, not this client's: the labels are fixed so
     * the same comparison always asks the same question, nothing in the request says which reading is the
     * published one or the one being proposed, and the answer this returns names a side rather than a side's
     * standing, so the client is never told which side is which either.
     */
    suspend fun review(page: PageImage, readingA: String, readingB: String): ImageReview {
        val answer = requestOn(page, OcrReviewPrompt.forPage(identityOf(page), readingA, readingB)) { content ->
            reviewOf(content, readingA = readingA, readingB = readingB)
        }
        return answer.value.copy(modelVersion = answer.modelVersion)
    }

    /**
     * One bounded image request with a caller's own instructions and answer schema.
     *
     * The comparison slice's review call is the next user of this: it shares the serialization, budgeting,
     * bounded I/O, redirect refusal, permit and identity checks, and supplies its own prompt and its own
     * decoder for its own answer document. Everything the envelope has to satisfy — a complete response,
     * an answer naming this page, the size and timeout bounds — is enforced here rather than by the caller.
     */
    suspend fun <T> requestOn(page: PageImage, instructions: String, decode: (String) -> T): ImageAnswer<T> {
        val image = imageOf(page)
        val form = imageFormOf(image, page)
        return exchange(
            identity = identityOf(page),
            image = image,
            form = form,
            instructions = instructions,
            decode = decode,
        )
    }

    override fun close() {
        transport.close()
    }

    // ---- the exchange ----

    private suspend fun <T> exchange(
        identity: PageDispatchIdentity,
        image: ByteArray,
        form: ImageForm,
        instructions: String,
        probe: Boolean = false,
        decode: (String) -> T,
    ): ImageAnswer<T> {
        // Nothing is sent before the permit: a page image that leaves this machine without one is the
        // failure this whole path exists to prevent, and the permit is asked about this revision and this
        // page rather than about the profile in general. The one exception is the synthetic capability probe
        // of a client built with [allowSyntheticProbeWithoutPermit]; every other caller still needs a validator.
        if (profile.scope == OcrEndpointScope.EXTERNAL) {
            val validator = permits
            if (validator == null) {
                // No validator is only allowed for the synthetic probe of a client built to permit it.
                if (!(probe && allowSyntheticProbeWithoutPermit)) {
                    throw ImageLlmException(
                        ImageLlmException.EXTERNAL_DISPATCH_NOT_PERMITTED,
                        "sending a page image off this machine needs the external dispatch permit of an admitted " +
                            "rescan, and this build has no permit validator: the image was not sent",
                    )
                }
            } else if (!validator.isPermitted(ExternalDispatchPermitRequest(profile.revisionId, identity))) {
                throw ImageLlmException(
                    ImageLlmException.EXTERNAL_DISPATCH_NOT_PERMITTED,
                    "this page is not covered by an external dispatch permit for this profile revision, so " +
                        "its image was not sent",
                )
            }
        }
        val credential = credential()
        if (image.size > maxImageBytes) {
            throw ImageLlmException(
                ImageLlmException.IMAGE_TOO_LARGE,
                "this page's image is larger than the ${maxImageBytes} bytes an image block may carry, so it " +
                    "was not sent",
            )
        }
        requireFits(instructions, form.pixels)
        return dispatch(identity, form.mediaType, image, instructions, credential, decode)
    }

    /**
     * The credential to send, read from the environment at call time.
     *
     * A loopback endpoint may have none, and a profile without a variable name sends none. A destination
     * that leaves this machine is different: its key has to be present, because there is no point sending a
     * page image to a paid endpoint to be told the credential was missing — and the value is never stored,
     * logged or put in a message, only in the one header the provider reads it from.
     */
    private fun credential(): String? {
        val variable = profile.apiKeyEnvironmentVariable
        if (variable == null) {
            // A loopback endpoint may have no credential at all; a destination off this machine may not,
            // because there is nothing to gain from sending a page image to be told the key was missing.
            if (profile.scope == OcrEndpointScope.LOCAL) return null
            throw ImageLlmException(
                ImageLlmException.MISSING_CREDENTIAL,
                "this profile names no environment variable for its key, so a page image was not sent to an " +
                    "external destination without one",
            )
        }
        return lookup(variable)?.takeIf { it.isNotBlank() } ?: throw ImageLlmException(
            ImageLlmException.MISSING_CREDENTIAL,
            "the environment variable this profile names for its key is not set, so no page image was sent",
        )
    }

    /** One bounded provider call: bounded retries outside, one timeout, one response-size bound inside. */
    /**
     * One bounded provider call, retried while the provider's own answer says it did not produce one.
     *
     * Each attempt is bounded as a whole — the send, the status, the body read and the validation — because
     * a provider that accepts a request and then trickles or stalls its answer is exactly the case one
     * timeout has to cover. Only a status that proves no answer was produced (429, 5xx) is retried; a
     * timeout, a redirect and every other failure ends this page's reading. A timeout in particular is never
     * retried: the provider may have produced and billed an answer nobody saw, and a repeat would be a second
     * paid call for the same page.
     */
    private suspend fun <T> dispatch(
        identity: PageDispatchIdentity,
        mediaType: String,
        image: ByteArray,
        instructions: String,
        credential: String?,
        decode: (String) -> T,
    ): ImageAnswer<T> {
        var attempt = 1
        while (true) {
            // Counted here, not at the permit: a retry is another paid request, and this is the only place
            // that knows one is happening. A dispatch that never reaches the transport is not counted.
            if (profile.scope == OcrEndpointScope.EXTERNAL) {
                calls?.invoke(ExternalDispatchPermitRequest(profile.revisionId, identity))
            }
            val answer = try {
                withTimeout(timeout) {
                    oneAttempt(identity, mediaType, image, instructions, credential, decode)
                }
            } catch (retryable: RetryableStatus) {
                if (attempt > retryPolicy.maxRetries) throw failureFor(retryable.status)
                retryPolicy.waitBeforeRetry(attempt)
                attempt += 1
                continue
            } catch (timedOut: TimeoutCancellationException) {
                throw ImageLlmException(
                    ImageLlmException.TIMEOUT,
                    "the provider did not answer within $timeout, so this page's reading is unknown; the call " +
                        "was not repeated, because an answer this client never saw may have been billed",
                    dispatched = true,
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (refused: ImageLlmException) {
                // A refusal this client made about the answer is this page's outcome, not a provider failure:
                // it is rethrown as itself so the codes say what actually happened.
                throw refused
            } catch (unreachable: IOException) {
                throw ImageLlmException(
                    ImageLlmException.PROVIDER_UNAVAILABLE,
                    "the provider could not be reached (${safeCauseOf(unreachable)}), so this page's reading is unknown",
                    dispatched = true,
                )
            } catch (failure: Throwable) {
                throw ImageLlmException(
                    ImageLlmException.PROVIDER_UNAVAILABLE,
                    "the provider call failed before an answer arrived (${safeCauseOf(failure)}), so this page's " +
                        "reading is unknown",
                    dispatched = true,
                )
            }
            return answer
        }
    }

    /**
     * One request, one response, one reading — or the status that says this one should be tried again.
     *
     * The response body is read inside the same bound as the request, so a provider that stalls after its
     * headers is this page's timeout rather than a hung process.
     */
    private suspend fun <T> oneAttempt(
        identity: PageDispatchIdentity,
        mediaType: String,
        image: ByteArray,
        instructions: String,
        credential: String?,
        decode: (String) -> T,
    ): ImageAnswer<T> {
        val response = post(mediaType, image, instructions, credential)
        if (response.status.isSuccess()) return readAnswer(response, identity, decode)
        if (response.status.value in REDIRECT_STATUSES) {
            throw ImageLlmException(
                ImageLlmException.REDIRECT_REFUSED,
                "the provider answered a redirect, and a page image is only ever sent to the endpoint " +
                    "the profile names; the redirect was not followed",
                statusCode = response.status.value,
                dispatched = true,
            )
        }
        if (retryPolicy.isRetryableStatus(response.status.value)) throw RetryableStatus(response.status.value)
        throw failureFor(response.status.value)
    }

    /** Performs one POST whose body is the protocol's own image request. */
    private suspend fun post(
        mediaType: String,
        image: ByteArray,
        instructions: String,
        credential: String?,
    ): HttpResponse = transport.post(url()) {
        when (profile.provider) {
            LlmProvider.OPENAI_COMPATIBLE ->
                credential?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            LlmProvider.ANTHROPIC -> {
                credential?.let { header(ANTHROPIC_KEY_HEADER, it) }
                header(ANTHROPIC_VERSION_HEADER, ANTHROPIC_VERSION)
            }
        }
        contentType(ContentType.Application.Json)
        setBody(bodyFor(mediaType, image, instructions))
    }

    private fun url(): String = when (profile.provider) {
        LlmProvider.OPENAI_COMPATIBLE -> "$endpoint/chat/completions"
        LlmProvider.ANTHROPIC -> "$endpoint/v1/messages"
    }

    /**
     * One request body, in the protocol's own shape.
     *
     * The two encodings differ in exactly two places — where the image goes and how the key travels — and
     * neither carries a tool, a page text, a path or a name: the only fields are the model, the output
     * limit, one user message and the page's own image bytes.
     */
    private fun bodyFor(mediaType: String, image: ByteArray, instructions: String): String {
        val encoded = Base64.getEncoder().encodeToString(image)
        return when (profile.provider) {
            LlmProvider.OPENAI_COMPATIBLE -> buildJsonObject {
                put("model", profile.model)
                put("stream", false)
                put("max_tokens", profile.maxOutputTokens)
                putJsonArray("messages") {
                    addJsonObject {
                        put("role", "user")
                        putJsonArray("content") {
                            addJsonObject {
                                put("type", "text")
                                put("text", instructions)
                            }
                            addJsonObject {
                                put("type", "image_url")
                                putJsonObject("image_url") { put("url", "data:$mediaType;base64,$encoded") }
                            }
                        }
                    }
                }
            }.toString()

            LlmProvider.ANTHROPIC -> buildJsonObject {
                put("model", profile.model)
                put("stream", false)
                put("max_tokens", profile.maxOutputTokens)
                putJsonArray("messages") {
                    addJsonObject {
                        put("role", "user")
                        putJsonArray("content") {
                            addJsonObject {
                                put("type", "text")
                                put("text", instructions)
                            }
                            addJsonObject {
                                put("type", "image")
                                putJsonObject("source") {
                                    put("type", "base64")
                                    put("media_type", mediaType)
                                    put("data", encoded)
                                }
                            }
                        }
                    }
                }
            }.toString()
        }
    }

    // ---- the answer ----

    /** Reads the response body inside its bound, then validates the envelope and the answer. */
    private suspend fun <T> readAnswer(
        response: HttpResponse,
        identity: PageDispatchIdentity,
        decode: (String) -> T,
    ): ImageAnswer<T> {
        val body = boundedBody(response)
        val content: String
        val model: String?
        when (profile.provider) {
            LlmProvider.OPENAI_COMPATIBLE -> {
                val answer = decodeEnvelope<OpenAiImageAnswer>(body)
                val choice = answer.choices.firstOrNull() ?: throw ImageLlmException(
                    ImageLlmException.MALFORMED_RESPONSE,
                    "the provider answered with no choice at all",
                    dispatched = true,
                )
                val message = choice.message ?: throw ImageLlmException(
                    ImageLlmException.MALFORMED_RESPONSE,
                    "the provider's choice carried no message",
                    dispatched = true,
                )
                if (message.tool_calls.isNotEmpty()) throw toolCallRefused()
                requireComplete(choice.finish_reason)
                content = message.content ?: throw ImageLlmException(
                    ImageLlmException.MALFORMED_RESPONSE,
                    "the provider's message carried no content",
                    dispatched = true,
                )
                model = answer.model
            }

            LlmProvider.ANTHROPIC -> {
                val answer = decodeEnvelope<AnthropicImageAnswer>(body)
                if (answer.content.any { block -> block.type == ANTHROPIC_TOOL_USE }) throw toolCallRefused()
                requireComplete(answer.stop_reason)
                content = answer.content.mapNotNull { block -> block.text }.joinToString("")
                model = answer.model
            }
        }
        val document = parseAnswer(content)
        requireAnsweredFor(document, identity)
        val value = try {
            decode(content)
        } catch (refused: ImageLlmException) {
            throw refused
        } catch (failure: Throwable) {
            throw ImageLlmException(
                ImageLlmException.MALFORMED_RESPONSE,
                "the provider's answer is not this schema, so it is not a reading of any page",
                dispatched = true,
            )
        }
        return ImageAnswer(value, model?.takeIf { it.isNotBlank() })
    }

    /**
     * Reads the body until it ends or passes [maxResponseBytes].
     *
     * Reading it whole first and measuring afterwards is how one chatty provider exhausts a process: the
     * bound is applied while the bytes arrive, so a response past it is dropped rather than held.
     */
    private suspend fun boundedBody(response: HttpResponse): String {
        val channel = response.bodyAsChannel()
        val sink = ByteArrayOutputStream()
        val buffer = ByteArray(READ_CHUNK_BYTES)
        while (true) {
            val read = channel.readAvailable(buffer, 0, buffer.size)
            if (read < 0) break
            if (read == 0) continue
            if (sink.size() > maxResponseBytes - read) {
                throw ImageLlmException(
                    ImageLlmException.RESPONSE_TOO_LARGE,
                    "the provider's answer is larger than the $maxResponseBytes bytes this client holds for " +
                        "one page's reading, so it was refused rather than read",
                    dispatched = true,
                )
            }
            sink.write(buffer, 0, read)
        }
        return sink.toByteArray().decodeToString()
    }

    /** The provider's envelope, or a refusal that keeps whatever it printed out of every message. */
    private inline fun <reified T> decodeEnvelope(body: String): T = try {
        LlmJson.decodeFromString<T>(body)
    } catch (failure: Throwable) {
        throw ImageLlmException(
            ImageLlmException.MALFORMED_RESPONSE,
            "the provider's response is not its own protocol, so no page was read from it",
            dispatched = true,
        )
    }

    /** The answer as a JSON object, or a refusal: a reading is a document, not prose around one. */
    private fun parseAnswer(content: String): JsonObject = try {
        LlmJson.parseToJsonElement(content) as? JsonObject
            ?: throw ImageLlmException(
                ImageLlmException.MALFORMED_RESPONSE,
                "the provider's answer is not one JSON object, so it is not a reading",
                dispatched = true,
            )
    } catch (refused: ImageLlmException) {
        throw refused
    } catch (failure: Throwable) {
        throw ImageLlmException(
            ImageLlmException.MALFORMED_RESPONSE,
            "the provider's answer is not one JSON object, so it is not a reading",
            dispatched = true,
        )
    }

    /**
     * Refuses an answer that is not this page's.
     *
     * Which page an answer belongs to is decided by the identity *in* the answer rather than by which call
     * it arrived on: a model that answers about another page, or about none, has not read this one, and
     * committing such an answer would attribute one page's text to another page's image.
     */
    private fun requireAnsweredFor(document: JsonObject, identity: PageDispatchIdentity) {
        val answeredUnit = document[UNIT_ID_FIELD]?.jsonPrimitive?.contentOrNull
        val answeredOrdinal = document[ORDINAL_FIELD]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
        if (answeredUnit != identity.unitId || answeredOrdinal != identity.ordinal) {
            throw ImageLlmException(
                ImageLlmException.WRONG_PAGE,
                "the provider's answer does not name the page it was asked about, so it is not this page's " +
                    "reading",
                dispatched = true,
            )
        }
    }

    /**
     * Refuses an answer that is not a completed reading.
     *
     * The two protocols say why they stopped in their own words, and only a normal stop is a reading: a
     * length limit means the answer is the beginning of a page, a tool call means the model tried to do
     * something the request never allowed, and anything else — a content filter, a refusal, no reason at
     * all — means nobody can say this answer is the whole page.
     */
    private fun requireComplete(finishReason: String?) {
        if (finishReason in COMPLETE_REASONS) return
        if (finishReason in TRUNCATED_REASONS) {
            throw ImageLlmException(
                ImageLlmException.TRUNCATED,
                "the provider stopped at its output limit, so the answer is a prefix of a reading rather " +
                    "than one",
                dispatched = true,
            )
        }
        if (finishReason in TOOL_REASONS) throw toolCallRefused()
        throw ImageLlmException(
            ImageLlmException.INCOMPLETE,
            "the provider did not report a completed answer (no complete finish reason), so it is not a " +
                "reading of this page",
            dispatched = true,
        )
    }

    private fun toolCallRefused(): ImageLlmException = ImageLlmException(
        ImageLlmException.TOOL_CALL_REFUSED,
        "the provider's answer asked for a tool call, and no request from this client offers one, so no " +
            "reading is taken from it",
        dispatched = true,
    )

    /**
     * The transcription schema, as this build validates it.
     *
     * The shipped prompt promises one exact shape and names every field in it, so all four are required and
     * nothing the shape does not have is accepted: a model that omits a field has not answered in the shape
     * it was given, and one that adds a field has written something the schema does not describe — neither is
     * the reading that was asked for. The envelope around this document keeps [LlmJson]'s tolerance, because
     * there a provider legitimately adds metadata of its own (`usage`, `id`, its own kind markers).
     * `text` is required — an answer without one is not a reading — and the unreadable spans have to be
     * stretches of the text that came with them: a span outside the reading it belongs to would mark a part
     * of a page that the answer does not describe.
     */
    private fun transcriptionOf(content: String): Transcription {
        val answer = try {
            StrictAnswerJson.decodeFromString<OcrTranscriptionAnswer>(content)
        } catch (failure: Throwable) {
            throw ImageLlmException(
                ImageLlmException.MALFORMED_RESPONSE,
                "the provider's answer is not the transcription schema, so it is not a reading",
                dispatched = true,
            )
        }
        val spans = answer.unreadable.map { span ->
            if (span.start < 0 || span.end <= span.start || span.end > answer.text.length) {
                throw ImageLlmException(
                    ImageLlmException.MALFORMED_RESPONSE,
                    "the provider's answer marked a stretch that is not part of the reading it came with",
                    dispatched = true,
                )
            }
            UnreadableSpan(span.start, span.end)
        }
        return Transcription(answer.text, spans.sortedBy { span -> span.startOffset })
    }

    /**
     * The review schema, as this build validates it.
     *
     * The shipped prompt promises one shape and names every field in it, so the recommendation and each
     * reason's explanation are required and nothing the shape does not have is accepted — the codec is the
     * same strict one the transcription answer uses. The recommendation is therefore also checked to be one
     * of the sides the request asked about, because that is the only vocabulary the reviewer was given. What is checked beyond the shape is what makes an answer
     * a judgement of *these two readings*: the confidence has to be a number a confidence can be, the reasons
     * are bounded in number and in length, and every span has to be a stretch of the reading it claims. A
     * reason that names a place in neither reading is not a reason, and an answer that fails any of this is
     * refused rather than stored as an opinion about a page: the caller turns the refusal into `UNCERTAIN`
     * with the baseline retained.
     */
    private fun reviewOf(content: String, readingA: String, readingB: String): ImageReview {
        val answer = try {
            StrictAnswerJson.decodeFromString<OcrReviewAnswer>(content)
        } catch (failure: Throwable) {
            throw ImageLlmException(
                ImageLlmException.MALFORMED_RESPONSE,
                "the provider's answer is not the review schema, so it is not a judgement of this page",
                dispatched = true,
            )
        }
        val confidence = answer.confidence
        if (confidence != null && (confidence < 0.0 || confidence > 1.0)) {
            throw ImageLlmException(
                ImageLlmException.MALFORMED_RESPONSE,
                "the provider's answer gave a confidence outside 0 to 1, which is not a confidence",
                dispatched = true,
            )
        }
        if (answer.reasons.size > MAX_REVIEW_REASONS) {
            throw ImageLlmException(
                ImageLlmException.MALFORMED_RESPONSE,
                "the provider's answer gave more reasons than a review holds, so it was refused rather than " +
                    "stored unbounded",
                dispatched = true,
            )
        }
        val reasons = answer.reasons.map { reason ->
            if (reason.explanation.isBlank() || reason.explanation.length > MAX_REVIEW_EXPLANATION_CHARACTERS) {
                throw ImageLlmException(
                    ImageLlmException.MALFORMED_RESPONSE,
                    "one of the provider's reasons is empty or longer than a bounded explanation, so the " +
                        "answer is not the shape the review asks for",
                    dispatched = true,
                )
            }
            val spanA = spanOf(reason.aStart, reason.aEnd, readingA.length)
            val spanB = spanOf(reason.bStart, reason.bEnd, readingB.length)
            if (spanA == null && spanB == null) {
                throw ImageLlmException(
                    ImageLlmException.MALFORMED_RESPONSE,
                    "one of the provider's reasons points at neither reading, so it is not a reason about " +
                        "this comparison",
                    dispatched = true,
                )
            }
            ImageReviewReason(explanation = reason.explanation, spanA = spanA, spanB = spanB)
        }
        return ImageReview(
            recommendation = answer.recommendation,
            confidence = confidence,
            reasons = reasons,
            modelVersion = null,
        )
    }

    /**
     * One span a reviewer named, refused unless it is a stretch of the reading it claims to be about.
     *
     * A reason says which side it is about by naming that side's offsets and leaving the other side's absent,
     * so half a span is not a side. An offset outside its reading, or an empty or reversed stretch, marks a
     * place the answer cannot have read, which is a refusal rather than a reason pointing at nothing.
     */
    private fun spanOf(start: Int?, end: Int?, length: Int): TextSpan? {
        if (start == null || end == null) {
            if (start != end) {
                throw ImageLlmException(
                    ImageLlmException.MALFORMED_RESPONSE,
                    "one of the provider's reasons gave half a span, so it does not name a stretch of a " +
                        "reading",
                    dispatched = true,
                )
            }
            return null
        }
        if (start < 0 || end <= start || end > length) {
            throw ImageLlmException(
                ImageLlmException.MALFORMED_RESPONSE,
                "one of the provider's reasons pointed outside the reading it is about, so it marks a place " +
                    "that answer did not read",
                dispatched = true,
            )
        }
        return TextSpan(start, end)
    }

    private fun failureFor(status: Int): ImageLlmException = when {
        status == 401 || status == 403 -> ImageLlmException(
            ImageLlmException.AUTHENTICATION,
            "the provider rejected the credential (HTTP $status), so this page was not read",
            statusCode = status,
            dispatched = true,
        )
        status == 400 || status == 422 -> ImageLlmException(
            ImageLlmException.IMAGE_NOT_SUPPORTED,
            "the provider refused the request (HTTP $status): the model, the endpoint or the image form is " +
                "not supported there, so this page was not read",
            statusCode = status,
            dispatched = true,
        )
        status == 429 -> ImageLlmException(
            ImageLlmException.RATE_LIMITED,
            "the provider asked us to slow down (HTTP 429) and the bounded retries are spent, so this page " +
                "was not read",
            statusCode = status,
            dispatched = true,
        )
        status in 500..599 -> ImageLlmException(
            ImageLlmException.PROVIDER_UNAVAILABLE,
            "the provider failed (HTTP $status), so this page's reading is unknown",
            statusCode = status,
            dispatched = true,
        )
        else -> ImageLlmException(
            ImageLlmException.PROVIDER_REFUSED,
            "the provider refused the request (HTTP $status), so this page was not read",
            statusCode = status,
            dispatched = true,
        )
    }

    // ---- the image ----

    private fun identityOf(page: PageImage): PageDispatchIdentity = PageDispatchIdentity(
        unitId = page.unitId,
        ordinal = page.ordinal,
        documentId = page.documentId.value,
    )

    /**
     * The page's own bytes, read from the artifact the record names.
     *
     * The hash is checked against the page image's own, because a reading names the image it was made from:
     * sending whatever the file holds now and committing the reading under the recorded hash would attribute
     * one image's text to another.
     */
    private fun imageOf(page: PageImage): ByteArray {
        val path = page.imagePath
        val size = try {
            Files.size(path)
        } catch (missing: IOException) {
            throw ImageLlmException(
                ImageLlmException.IMAGE_UNREADABLE,
                "this page's image artifact cannot be read back, so no page image was sent",
            )
        }
        if (size > maxImageBytes) {
            throw ImageLlmException(
                ImageLlmException.IMAGE_TOO_LARGE,
                "this page's image artifact is larger than the ${maxImageBytes} bytes an image block may " +
                    "carry, so it was not sent",
            )
        }
        val bytes = try {
            Files.readAllBytes(path)
        } catch (missing: IOException) {
            throw ImageLlmException(
                ImageLlmException.IMAGE_UNREADABLE,
                "this page's image artifact cannot be read back, so no page image was sent",
            )
        }
        if (bytes.size > maxImageBytes) {
            throw ImageLlmException(
                ImageLlmException.IMAGE_TOO_LARGE,
                "this page's image artifact is larger than the ${maxImageBytes} bytes an image block may " +
                    "carry, so it was not sent",
            )
        }
        if (sha256Of(bytes) != page.sha256) {
            throw ImageLlmException(
                ImageLlmException.IMAGE_CHANGED,
                "this page's image artifact is no longer the image its record names, so no reading may be " +
                    "attributed to it",
            )
        }
        return bytes
    }

    /**
     * What the image's own bytes say it is: the media type a provider block takes and its pixel count.
     *
     * Both are read from the artifact rather than from the record, because the record is what a caller
     * supplied: a page whose record understated its size would otherwise be budgeted as if its image were
     * nearly free, and a request sent with no room for its answer comes back truncated.
     */
    private data class ImageForm(val mediaType: String, val pixels: Long)

    /**
     * The form of the artifact's own bytes, from the reader's declared raster size.
     *
     * Both protocols accept the same four forms, and neither accepts a TIFF or a form no reader claims: a
     * page image in one of those is refused here rather than sent to be refused there, so the failure names
     * what is wrong with this page's artifact instead of what a provider said about it. The header is enough
     * to measure the raster, the same way a producer measures its own artifact: decoding a page to count its
     * pixels is what a bound exists to prevent.
     */
    private fun imageFormOf(image: ByteArray): ImageForm {
        val stream = ImageIO.createImageInputStream(ByteArrayInputStream(image))
            ?: throw unsupportedFormat(null)
        try {
            val readers = ImageIO.getImageReaders(stream)
            if (!readers.hasNext()) throw unsupportedFormat(null)
            val reader = readers.next()
            try {
                val format = reader.formatName.lowercase(Locale.ROOT)
                val mediaType = MEDIA_TYPES[format] ?: throw unsupportedFormat(format)
                reader.input = stream
                val pixels = try {
                    reader.getWidth(0).toLong() * reader.getHeight(0)
                } catch (unreadable: IOException) {
                    throw dimensionsUnverified(
                        "this page's image is in a form whose header cannot be read, so the room its image " +
                            "needs in the profile's context window cannot be measured",
                    )
                }
                return ImageForm(mediaType, pixels)
            } finally {
                reader.dispose()
            }
        } finally {
            stream.close()
        }
    }

    /**
     * The artifact's form against the record that describes it, or a refusal.
     *
     * The record's own dimensions are what the request would otherwise be budgeted from, and an absent pair
     * is not a claim of zero by zero: it is no measurement at all, so the request would be dispatched as if
     * the image — the largest part of it — were not there. A pair that disagrees with the artifact is the
     * record describing an image this is not, which is the same failure the hash check exists to catch.
     */
    private fun imageFormOf(image: ByteArray, page: PageImage): ImageForm {
        val form = imageFormOf(image)
        val recorded = page.pixels()
        if (recorded == null) {
            throw dimensionsUnverified(
                "this page's record states no dimensions, so the room its image needs in the profile's " +
                    "context window cannot be measured and the request was refused rather than budgeted as " +
                    "if there were no image in it",
            )
        }
        if (recorded != form.pixels) {
            throw dimensionsUnverified(
                "this page's artifact is ${form.pixels} pixels and the record that describes it says " +
                    "$recorded, so the artifact is not the image that record names",
            )
        }
        return form
    }

    private fun dimensionsUnverified(reason: String): ImageLlmException = ImageLlmException(
        ImageLlmException.IMAGE_DIMENSIONS_UNVERIFIED,
        "$reason, so no page image was sent",
    )

    private fun unsupportedFormat(format: String?): ImageLlmException = ImageLlmException(
        ImageLlmException.IMAGE_FORMAT_UNSUPPORTED,
        "this page's image is ${if (format == null) "in a form no reader claims" else "a '$format' image"}, " +
            "which no image block of either protocol accepts, so it was not sent",
    )

    /**
     * Refuses a request that cannot fit, before anything is sent.
     *
     * The three parts of a request are measured together — the instructions, the image and the output the
     * answer is allowed to take — because a page sent with too little room for its answer comes back
     * truncated, and a provider that truncates says so only afterwards. No tokenizer is available for an
     * arbitrary model, so the estimate is deliberately conservative: a byte cannot under-count a token of
     * text, and the image's own share follows the providers' own published approximation of one token per
     * 750 pixels. A page past the window is refused; its text is never cut down to fit.
     */
    private fun requireFits(instructions: String, imagePixels: Long) {
        val instructionTokens = instructions.toByteArray(Charsets.UTF_8).size.toLong()
        val imageTokens = (imagePixels + PIXELS_PER_TOKEN - 1) / PIXELS_PER_TOKEN
        val reserved = profile.maxOutputTokens.toLong() + BUDGET_SAFETY_MARGIN
        val total = instructionTokens + imageTokens + reserved
        if (total > profile.contextWindow) {
            throw ImageLlmException(
                ImageLlmException.REQUEST_OVERSIZED,
                "this page's image, its instructions and the reserved answer need about $total tokens and " +
                    "the profile's context window holds ${profile.contextWindow}, so the request was " +
                    "refused rather than sent with anything cut down",
            )
        }
    }

    private fun PageImage.pixels(): Long? =
        if (width == null || height == null) null else width.toLong() * height

    private fun sha256Of(bytes: ByteArray): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    /** One transcription answer, as the schema requires it. */
    private data class Transcription(val text: String, val unreadable: List<UnreadableSpan>)

    companion object {

        /**
         * The unit id a capability check dispatches under.
         *
         * It names no document and no page, which is what keeps a probe from ever standing in for one
         * wherever an identity is recorded.
         */
        const val CAPABILITY_PROBE_UNIT_ID: String = "capability-probe"

        /** How long one provider call may take before its answer is treated as unknown. */
        val DEFAULT_TIMEOUT: Duration = 120.seconds

        /** How long a capability check waits for a provider: shorter than a page, because it is one small image. */
        val PROBE_TIMEOUT: Duration = 60.seconds

        /**
         * The most bytes one page image may be.
         *
         * It is Anthropic's documented per-image limit, which is the stricter of the two protocols' bounds,
         * so one number keeps a page acceptable to both rather than to whichever endpoint happens to be
         * configured.
         */
        const val MAX_IMAGE_BYTES: Int = 5 * 1024 * 1024

        /** The most bytes one page's answer may be before it is refused unread. */
        const val MAX_RESPONSE_BYTES: Int = 1024 * 1024

        /** The most reasons one review answer may carry, matching what the shipped prompt asks for. */
        const val MAX_REVIEW_REASONS: Int = 5

        /** The longest explanation one reason may carry, matching what the shipped prompt asks for. */
        const val MAX_REVIEW_EXPLANATION_CHARACTERS: Int = 200

        /**
         * The synthetic page a capability check sends.
         *
         * Drawn and encoded here, from this build's own constant words, so it is redistributable and can
         * never carry a document: the picture exists to prove that an image block travels, not to measure
         * transcription quality.
         */
        internal fun syntheticProbeImage(): ByteArray {
            val raster = BufferedImage(PROBE_WIDTH, PROBE_HEIGHT, BufferedImage.TYPE_INT_RGB)
            val graphics = raster.createGraphics()
            try {
                graphics.color = Color.WHITE
                graphics.fillRect(0, 0, PROBE_WIDTH, PROBE_HEIGHT)
                graphics.color = Color.BLACK
                graphics.drawString(PROBE_TEXT, 24, 56)
                graphics.drawString(PROBE_NUMBERS, 24, 104)
            } finally {
                graphics.dispose()
            }
            val encoded = ByteArrayOutputStream()
            check(ImageIO.write(raster, "png", encoded)) { "no PNG writer is available" }
            return encoded.toByteArray()
        }

        internal const val PROBE_WIDTH: Int = 640
        internal const val PROBE_HEIGHT: Int = 160
    }
}

/** The status of a response that says the provider did not produce an answer, so the call may be tried again. */
private class RetryableStatus(val status: Int) : Exception(null, null, false, false)

/** The identity a probe image carries, so a test and the route can name the same page. */
private const val PROBE_TEXT: String = "InfoScry image capability probe"
private const val PROBE_NUMBERS: String = "1234567890"

/** The provider's own field names, in one place so the schema and its check cannot drift apart. */
private const val UNIT_ID_FIELD: String = "unitId"
private const val ORDINAL_FIELD: String = "ordinal"

/** The four image forms both protocols accept, by the reader's own format name. */
private val MEDIA_TYPES: Map<String, String> = mapOf(
    "png" to "image/png",
    "jpeg" to "image/jpeg",
    "jpg" to "image/jpeg",
    "gif" to "image/gif",
    "webp" to "image/webp",
)

/** Ktor's transport never follows a redirect here; any 3xx is a destination the profile did not name. */
private val REDIRECT_STATUSES: IntRange = 300..399

/** The finish reasons both protocols use for "the output was cut off at the limit". */
private val TRUNCATED_REASONS: Set<String> = setOf("length", "max_tokens")

/** The finish reasons both protocols use for "the model asked for a tool". */
private val TOOL_REASONS: Set<String> = setOf("tool_calls", "tool_use")

/** The finish reasons that mean the whole answer was produced. */
private val COMPLETE_REASONS: Set<String> = setOf("stop", "end_turn", "stop_sequence")

private const val ANTHROPIC_TOOL_USE: String = "tool_use"
private const val ANTHROPIC_KEY_HEADER: String = "x-api-key"
private const val ANTHROPIC_VERSION_HEADER: String = "anthropic-version"
private const val ANTHROPIC_VERSION: String = "2023-06-01"

/** How much of a response body is read at a time while its bound is enforced. */
private const val READ_CHUNK_BYTES: Int = 8 * 1024

/** The providers' own published approximation: one image token per 750 pixels. */
private const val PIXELS_PER_TOKEN: Long = 750

/** Room left for the provider's own framing beyond the reserved output. */
private const val BUDGET_SAFETY_MARGIN: Int = 512

// ---- provider answers, decode-only: only the fields a reading is taken from ----

@Serializable
internal data class OpenAiImageAnswer(
    val model: String? = null,
    val choices: List<OpenAiImageChoice> = emptyList(),
)

@Serializable
internal data class OpenAiImageChoice(
    val finish_reason: String? = null,
    val message: OpenAiImageMessage? = null,
)

@Serializable
internal data class OpenAiImageMessage(
    val content: String? = null,
    /** Present only when the model tried to call a tool, which no reading may come from. */
    val tool_calls: List<JsonObject> = emptyList(),
)

@Serializable
internal data class AnthropicImageAnswer(
    val model: String? = null,
    val stop_reason: String? = null,
    val content: List<AnthropicImageBlock> = emptyList(),
)

@Serializable
internal data class AnthropicImageBlock(
    val type: String? = null,
    val text: String? = null,
)

/** The transcription answer's own schema, as the prompt asks for it. Every field is required and none is defaulted. */
@Serializable
internal data class OcrTranscriptionAnswer(
    val unitId: String,
    val ordinal: Int,
    val text: String,
    val unreadable: List<OcrTranscriptionSpan>,
)

@Serializable
internal data class OcrTranscriptionSpan(val start: Int, val end: Int)

/**
 * The review answer's own schema, as the shipped prompt asks for it.
 *
 * The identity, the recommendation and the reasons are required and none is defaulted, so an answer that
 * leaves one out is refused rather than completed here. The recommendation is the request's own vocabulary,
 * a side ([ReviewerSide]) rather than this application's [ReviewerRecommendation]: an answer in the latter
 * is not this schema and is refused. A reason names the stretches it is about by giving the offsets of the
 * side or sides it points at, which is why a half-named span is refused rather than read as "no span".
 */
@Serializable
internal data class OcrReviewAnswer(
    val unitId: String,
    val ordinal: Int,
    val recommendation: ReviewerSide,
    val confidence: Double? = null,
    val reasons: List<OcrReviewReason> = emptyList(),
)

@Serializable
internal data class OcrReviewReason(
    val explanation: String,
    val aStart: Int? = null,
    val aEnd: Int? = null,
    val bStart: Int? = null,
    val bEnd: Int? = null,
)

/**
 * The codec for the answer *document*, which is this build's schema rather than a provider's protocol.
 *
 * [LlmJson] is tolerant on purpose because a provider's envelope carries metadata nobody here enumerated;
 * the document inside it is the opposite case. The prompt promises one shape and lists its members, so a
 * missing member and a member the shape does not have are both refusals, and this codec says so where the
 * tolerance of [LlmJson] would silently accept either.
 */
private val StrictAnswerJson: Json = Json {
    ignoreUnknownKeys = false
}

/**
 * A short, fixed description of why a provider call failed, derived from the exception's type alone.
 *
 * The exception message is never used: a transport's message may carry the endpoint's host, a URL or a
 * credential, and none of those may reach a log or an error. The causes are walked so a type wrapped by the
 * HTTP stack is still named; anything unrecognised is named by its simple class name, which says nothing
 * about the endpoint.
 */
internal fun safeCauseOf(failure: Throwable): String {
    var current: Throwable? = failure
    var depth = 0
    while (current != null && depth < MAX_CAUSE_DEPTH) {
        when (current) {
            is UnknownHostException -> return "the host name could not be resolved"
            is ConnectException, is ConnectTimeoutException, is SocketTimeoutException ->
                return "the connection was refused or timed out"
            is SSLException -> return "the TLS handshake failed"
        }
        current = current.cause
        depth += 1
    }
    return if (failure is IOException) {
        "the connection was closed before the answer arrived"
    } else {
        failure::class.simpleName ?: "unknown failure"
    }
}

private const val MAX_CAUSE_DEPTH: Int = 8
