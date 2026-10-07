package infoscry.ocr

import infoscry.llm.RetryPolicy
import io.ktor.client.engine.HttpClientEngine
import kotlin.time.Duration

/**
 * Reads one page with an image-capable model, through the profile revision one attempt was admitted with.
 *
 * This is the `LLM` arm of [PageOcrEngine], and the difference that matters is where the dispatch
 * destination comes from. A local engine answers about the machine it runs on; this one sends a page image
 * to an endpoint, so:
 *
 * - **The revision is the one the snapshot names, never the profile's current one.** An attempt is admitted
 *   with an immutable [OcrProfileRevision] id, and this engine resolves *that* id: editing the profile
 *   afterwards changes future attempts and cannot move a running one to another endpoint, another model or
 *   another price. A revision the build no longer knows is a refusal, never a fallback to whatever the
 *   profile points at now.
 * - **The prompt version in the snapshot has to be the body this build ships.** The instructions are part
 *   of what a reading is, and their version travels in the attempt's fingerprint, so an attempt snapshotted
 *   under another version is refused rather than read with the words this build happens to have.
 * - **A destination off this machine needs ticket 07's dispatch permit.** This build has no validator to
 *   inject, and the client refuses to be constructed for an external destination without one, so no page
 *   image leaves the Mac through this engine until that admission service exists. Local loopback endpoints
 *   work without a permit, which is what makes a local image model usable today.
 * - **It publishes nothing.** A reading is returned to the caller that holds the attempt and the sink; the
 *   engine writes no candidate, no checkpoint and no progress.
 *
 * What an empty reading means is the seam's rule and not this engine's opinion: a page that came back with
 * no text carries [OcrPageResult.EMPTY_READING_CODE] rather than a bare success, and blankness is left to
 * the caller that can ask the raster ([OcrPageResult.verifiedAgainst]).
 *
 * Nothing here can say whether an earlier attempt's page may be reused. The decision belongs to the
 * attempt's fingerprint, which the profile revision id already names: a revision is immutable, so a page
 * committed under one is a page read with that model, that endpoint and those limits. A provider model
 * *alias* that later resolves to another version is exactly what the fingerprint cannot see — the resolved
 * version arrives with a reading, in [OcrPageResult.modelVersion], and is recorded rather than promised.
 */
class LlmOcr(
    /**
     * How a snapshotted revision id becomes the revision itself. One read, of an immutable row, so an
     * attempt that was asked for at admission is read with what it was admitted with.
     */
    private val revisionOf: (String) -> OcrProfileRevision?,
    /** How "the variable is set" is answered, injected so a test needs no environment of its own. */
    private val lookup: (String) -> String? = System::getenv,
    /**
     * The external dispatch permit validator of ticket 07, or null while this build has none.
     *
     * Null is not "unrestricted": the client refuses a non-local destination without a validator, so this
     * engine cannot dispatch a page image off this machine until that admission service is integrated.
     */
    private val permits: ExternalDispatchPermitValidator? = null,
    /**
     * Told before each transcription request is sent, retries included.
     *
     * Ticket 07's accounting counts provider calls where they happen: this engine retries a throttled or
     * failed request by itself, so only the client can say how many requests one page cost.
     */
    private val calls: ((ExternalDispatchPermitRequest) -> Unit)? = null,
    private val timeout: Duration = ImageLlmClient.DEFAULT_TIMEOUT,
    private val maxImageBytes: Int = ImageLlmClient.MAX_IMAGE_BYTES,
    private val maxResponseBytes: Int = ImageLlmClient.MAX_RESPONSE_BYTES,
    private val retryPolicy: RetryPolicy = RetryPolicy(),
    /**
     * The transport's engine for every client this engine builds, or null for each client's own CIO
     * engine — the seam a test injects a recording transport through. It is passed through unchanged:
     * what the client does with redirects, credentials and permits is the client's, not the engine's.
     * Named `clientEngine` rather than `engine` because [engine] is this attempt's reading engine.
     */
    private val clientEngine: HttpClientEngine? = null,
) : PageOcrEngine {

    override val engine: OcrEngine = OcrEngine.LLM

    override suspend fun transcribe(page: PageImage, settings: OcrSettingsSnapshot): OcrPageResult {
        val revision = revisionFor(settings)
        val client = ImageLlmClient(
            profile = revision,
            lookup = lookup,
            permits = permits,
            calls = calls,
            timeout = timeout,
            maxImageBytes = maxImageBytes,
            maxResponseBytes = maxResponseBytes,
            retryPolicy = retryPolicy,
            engine = clientEngine,
        )
        // One client per page: the endpoint, model and limits are the revision's, the transport is this
        // call's own, and nothing outlives the reading it was built for.
        val reading = client.use { it.transcribe(page) }
        // Whitespace is not a page's text: an empty reading is what the caller's blankness question is asked
        // about, and it is delivered under the seam's own code rather than as a success. Text that says
        // something is delivered exactly as the model wrote it — names, numbers and line structure are the
        // page's, not this engine's to normalise.
        val text = if (reading.text.isBlank()) "" else reading.text
        return OcrPageResult(
            text = text,
            engine = engine,
            imageSha256 = page.sha256,
            unreadableSpans = reading.unreadable,
            modelVersion = reading.modelVersion,
            errorCode = if (text.isEmpty()) OcrPageResult.EMPTY_READING_CODE else null,
        )
    }

    /**
     * The revision the attempt was admitted with, or a refusal that says which part of the snapshot cannot
     * be honoured.
     *
     * Both checks are about the same thing — that this page is read with what the attempt was admitted
     * with rather than with what this build or this profile happens to hold now — and both refuse *before*
     * any client exists, so no page image is sent under an identity nobody snapshotted.
     */
    private fun revisionFor(settings: OcrSettingsSnapshot): OcrProfileRevision {
        val revisionId = settings.transcriptionProfileRevisionId ?: throw ImageLlmException(
            ImageLlmException.PROFILE_REVISION_UNKNOWN,
            "this attempt names no OCR profile revision, and an image-model reading is only ever made " +
                "through the revision the attempt was admitted with",
        )
        if (settings.transcriptionPromptVersion != OcrTranscriptionPrompt.version) {
            throw ImageLlmException(
                ImageLlmException.PROMPT_VERSION_MISMATCH,
                "this attempt was admitted under transcription prompt version " +
                    "${settings.transcriptionPromptVersion} and this build ships version " +
                    "${OcrTranscriptionPrompt.version}, so no page was read with different instructions " +
                    "than the attempt chose",
            )
        }
        return revisionOf(revisionId) ?: throw ImageLlmException(
            ImageLlmException.PROFILE_REVISION_UNKNOWN,
            "the OCR profile revision this attempt was admitted with is no longer known, so no page can be " +
                "read through it and no other revision may be substituted for it",
        )
    }
}
