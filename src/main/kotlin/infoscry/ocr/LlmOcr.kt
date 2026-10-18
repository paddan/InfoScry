package infoscry.ocr

import infoscry.llm.RetryPolicy
import io.ktor.client.engine.HttpClientEngine
import kotlin.time.Duration

/** The marker the transcription prompt writes in place of text that cannot be read. */
private const val UNREADABLE_MARKER: String = "[unreadable]"

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
        // The reading is Markdown (prompt version 2). Its structure is parsed and derived into the page's text, and
        // that derived text is the reading's text, so the structure's ranges point into what the reading carries.
        // Whitespace is not a page's text: a blank reading derives to nothing and is reported as an empty reading
        // rather than as a success. Text that says something keeps the words the model wrote.
        val derived = StructureDerivation.derive(MarkdownStructureParser.parse(reading.text), page.sha256)
        val text = derived.extractedText
        // The model's own offsets name its raw text, not the derived text, so only the count of unreadable markers is
        // compared with what the model reported. A model that reported no spans is not a mismatch: the markers' own
        // positions are the spans. A non-zero report that differs in count cannot say which marker it meant, so the
        // spans are dropped and the page says so.
        val markers = unreadableSpansOf(text)
        val spansAgree = reading.unreadable.isEmpty() || markers.size == reading.unreadable.size
        // The page's warning and the structure's flag are one fact, so the structure is marked degraded too. The text
        // is the same either way; only the flag changes what page review shows.
        val degraded = derived.degraded || !spansAgree
        return OcrPageResult(
            text = text,
            engine = engine,
            imageSha256 = page.sha256,
            unreadableSpans = if (spansAgree) markers else emptyList(),
            modelVersion = reading.modelVersion,
            errorCode = if (text.isEmpty()) OcrPageResult.EMPTY_READING_CODE else null,
            structure = if (degraded) derived.structure.copy(degraded = true) else derived.structure,
            warnings = if (degraded) listOf(STRUCTURE_DEGRADED) else emptyList(),
        )
    }

    /** Every [UNREADABLE_MARKER] in [text], as the span it occupies. */
    private fun unreadableSpansOf(text: String): List<UnreadableSpan> {
        val spans = ArrayList<UnreadableSpan>()
        var from = 0
        while (true) {
            val at = text.indexOf(UNREADABLE_MARKER, from)
            if (at < 0) return spans
            spans += UnreadableSpan(at, at + UNREADABLE_MARKER.length)
            from = at + UNREADABLE_MARKER.length
        }
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
