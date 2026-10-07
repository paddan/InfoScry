package infoscry.ocr

import infoscry.domain.ContentUnitId
import infoscry.domain.DocumentId
import infoscry.llm.RetryPolicy
import infoscry.storage.DocumentRevisionStore
import io.ktor.client.engine.HttpClientEngine
import infoscry.storage.OcrReviewStore
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.time.Duration

/**
 * The digest a comparison names one reading by: lowercase hex SHA-256 of its text as UTF-8.
 *
 * It is deliberately the same form the revision store writes into a page's `text_sha256`, so a caller that
 * read a page's hash out of the store and one that computed it from the text it is holding name the same
 * reading — the comparison input's own check is what makes the two agree rather than assume it.
 */
internal fun readingTextHash(text: String): String =
    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)))

/**
 * The side of the review request the published reading goes out as.
 *
 * The request labels its two readings A and B and says nothing about which is which, so the order is this
 * service's own choice: [OcrComparisonService] sends the baseline as side A and the candidate as side B, and
 * a reviewer's answer about a side is mapped back with this. A request that said which side was which would
 * bias the reviewer toward one of the readings, which is why the order lives here and not in the request.
 */
internal const val BASELINE_IS_SIDE_A: Boolean = true

/**
 * What a reviewer's answer about one side of the request means in this application's vocabulary.
 *
 * The request and its answer speak only of sides, because telling the reviewer which reading is the published
 * one would bias it toward the other. The mapping is therefore made here, from the side the answer names and
 * the side the baseline went out as ([baselineIsSideA]): a side the image supports that holds the baseline is
 * [ReviewerRecommendation.EXISTING_BETTER], the other side is [ReviewerRecommendation.NEW_BETTER], and an
 * answer about neither side is uncertain however the readings were ordered.
 */
internal fun recommendationOf(side: ReviewerSide, baselineIsSideA: Boolean): ReviewerRecommendation = when {
    side == ReviewerSide.UNCERTAIN -> ReviewerRecommendation.UNCERTAIN
    (side == ReviewerSide.A_BETTER) == baselineIsSideA -> ReviewerRecommendation.EXISTING_BETTER
    else -> ReviewerRecommendation.NEW_BETTER
}

/**
 * A comparison that cannot be made, in this project's own vocabulary.
 *
 * The codes travel into queues, logs and the CLI, so they name the state rather than quoting anything: no
 * page text, no revision text, no endpoint and no provider message is ever part of one.
 */
class OcrComparisonException(val code: String, message: String) : IllegalStateException(message) {

    companion object {

        /**
         * The baseline the input names is not the revision the document publishes any more.
         *
         * The comparison is refused rather than made, and the decision a caller already holds against the old
         * baseline is not applied: the candidate it prefers was compared with text the page no longer holds.
         */
        const val STALE_BASELINE: String = "OCR_STALE_BASELINE"

        /** The attempt was admitted under another review prompt version than this build ships. */
        const val REVIEW_PROMPT_MISMATCH: String = "OCR_REVIEW_PROMPT_MISMATCH"

        /** The attempt was admitted under another decision policy than this build runs. */
        const val POLICY_VERSION_MISMATCH: String = "OCR_POLICY_VERSION_MISMATCH"

        /** The baseline's revision publishes no page under the identity this comparison names. */
        const val BASELINE_PAGE_UNKNOWN: String = "OCR_BASELINE_PAGE_UNKNOWN"

        /** The named page of the named revision is not the page this comparison's baseline came from. */
        const val BASELINE_MISMATCH: String = "OCR_BASELINE_MISMATCH"
    }
}

/**
 * Everything one comparison is about: the page, the two readings, and who is to judge them.
 *
 * The two readings are supplied rather than read here, because a comparison is about exactly the texts the
 * caller compared — a rescan holds the baseline it read at admission, and re-reading the store inside a
 * comparison would compare against whatever the page says *now*. [baselineTextHash] is what makes that
 * checkable: the record refuses to hold a text that is not the text it names, and the service then checks
 * the text, the hash, the unit and the ordinal against the page the named revision actually holds, so a
 * baseline this comparison was not made against is a refusal rather than a review.
 *
 * What the input deliberately does *not* carry is any accepted validation identifier: whether an automatic
 * replacement may happen at all is a property of the accepted record the [OcrDecisionPolicy] resolves from
 * the store at decision time, which only the store can produce, and a per-page request cannot ask for one.
 *
 * The page it names is the baseline revision's own page: [PageComparisonInput.unitId] is the unit id that
 * revision holds for the page at [PageComparisonInput.ordinal], because the service verifies the supplied
 * text, hash, unit and ordinal against that revision before anything is compared. A page image of a document
 * with published text is therefore named as the revision names it, not by a name of the producer's choosing.
 *
 * A baseline that is the page's own text rather than a published reading is the third shape: an import in
 * check-and-improve mode compares what an engine read from a page's pixels with the text layer of the file it
 * is importing, and that text was never published, so the comparison names it and no revision — and no hash,
 * because a hash is how a caller names the text of a *stored* page for this service to verify, and there is no
 * stored page here. What such a comparison is about is therefore the text it holds, the reading it stages and
 * the image they were both about; the review it produces carries no baseline revision or hash, exactly as a
 * page with nothing published does.
 */
data class PageComparisonInput(
    val page: PageImage,
    val candidateText: String,
    val reviewProfileRevisionId: String,
    val baselineRevisionId: String? = null,
    val baselineText: String? = null,
    val baselineTextHash: String? = null,
    val reviewPromptVersion: Int = OCR_REVIEW_PROMPT_VERSION,
    val policyVersion: Int = OCR_POLICY_VERSION,
) {

    /** The document the page belongs to, taken from the page rather than supplied again. */
    val documentId: DocumentId get() = page.documentId

    /** The page's stable extraction identity, taken from the page rather than supplied again. */
    val unitId: String get() = page.unitId

    /** The page's ordinal, taken from the page rather than supplied again. */
    val ordinal: Int get() = page.ordinal

    init {
        require(reviewProfileRevisionId.isNotBlank()) {
            "a comparison names the reviewer revision that is to judge it"
        }
        // The three shapes a baseline comes in, and what each must carry: absent for a page with nothing to
        // compare against; revision, text and hash together for a reading the archive publishes, because that
        // is what the service verifies the supplied text against; and a bare text for a reading the caller
        // holds, which names neither revision nor hash because nothing stored holds it.
        require(baselineRevisionId == null || baselineText != null) {
            "a baseline that names a revision names the text of that revision's page: a published reading is " +
                "not compared against nothing"
        }
        require(baselineRevisionId == null || baselineTextHash != null) {
            "a baseline that names a revision names the hash of its text, because that is what a stored page " +
                "is verified against"
        }
        require(baselineRevisionId != null || baselineTextHash == null) {
            "only a baseline the archive publishes is named by a hash: a reading the caller holds has no stored " +
                "page to verify it against, and a hash that names nothing is not this comparison's baseline"
        }
        require(baselineTextHash == null || readingTextHash(checkNotNull(baselineText)) == baselineTextHash) {
            "the baseline text is not the text this comparison names by hash, so the reading it holds is not " +
                "the reading it says it is"
        }
        require(reviewPromptVersion > 0) {
            "a comparison names a positive review prompt version, was $reviewPromptVersion"
        }
        require(policyVersion > 0) { "a comparison names a positive policy version, was $policyVersion" }
    }
}

/**
 * One page's review, as it is durable and as a caller applies it.
 *
 * The review carries no text: the accepted text stays exactly one of the two readings or a person's edit, so
 * nothing here can be a merged third text. What a caller gets instead is the disposition — keep, propose or
 * approve — the reasons behind it, and the fingerprints that say which comparison and which images it is
 * about, so a decision that was taken against text a page no longer holds can be told apart from one that
 * still applies ([appliesTo]).
 *
 * [fingerprint] and [imageSha256] are together the review's durable identity: the same comparison of the same
 * pixels is the same review, another reviewer, another policy, another candidate or another image is not.
 *
 * The reviewer and the policy are carried by what the review was actually made with: [reviewerRevisionId] is
 * an immutable reviewer revision, [reviewPromptVersion] the instructions it judged under, and [policyVersion]
 * the policy that decided — the three fields the [fingerprint] composes over, written out so a consumer can
 * read them without recomputing the digest. [candidateHash] is the same digest the revision store writes into
 * a staged page's `text_sha256`, so the candidate revision holding the text a decision is about is findable
 * from the decision itself.
 */
data class PageReview(
    val fingerprint: OcrReviewFingerprint,
    val documentId: DocumentId,
    val unitId: String,
    val ordinal: Int,
    /** The page image this review judged. A review of other pixels is not this review. */
    val imageSha256: String,
    val baselineRevisionId: String?,
    val baselineTextHash: String?,
    val candidateHash: String,
    /** What the reviewer recommended, or `UNCERTAIN` when no reviewer answer stands behind this review. */
    val recommendation: ReviewerRecommendation,
    /** What this decision says should happen to the page. */
    val disposition: PublicationDisposition,
    /** The reviewer's own confidence, when it gave one. It decides nothing. */
    val confidence: Double?,
    /** The deterministic findings and the reviewer's validated reasons, bounded in number. */
    val reasons: List<ReviewReason>,
    val reviewerRevisionId: String,
    /** The model version the provider resolved the reviewer to, when it reported one. */
    val reviewerModelVersion: String?,
    val reviewPromptVersion: Int,
    val policyVersion: Int,
    /**
     * Why no reviewer answer stands behind [recommendation], or null when the reviewer's own answer does.
     *
     * A comparison whose reviewer could not be reached, whose answer was not this schema, or which had
     * nothing to compare against carries the safe code of that state here rather than pretending to be a
     * judgement.
     */
    val outcomeCode: String?,
    /**
     * Whether text of this page is available to retrieval after this decision.
     *
     * A pending candidate is not: the page keeps whatever its baseline already published, which for a page
     * with no baseline is nothing at all. A page nobody has approved is visibly pending review instead of
     * being served as if its reading had been accepted.
     */
    val searchable: Boolean,
) {

    /**
     * Whether this decision still applies to the baseline a page holds now.
     *
     * A review names the revision and the text hash it judged. A later publication moves the document's
     * active revision, and a decision taken against the old text must not be applied to the new one, so a
     * caller hands in the page's current pair and gets a truthful "no" here rather than a silent replacement.
     */
    fun appliesTo(baselineRevisionId: String?, baselineTextHash: String?): Boolean =
        this.baselineRevisionId == baselineRevisionId && this.baselineTextHash == baselineTextHash
}

/**
 * Compares one page's published text with a candidate reading and decides what may happen to it.
 *
 * This is the pilot's whole decision path, and it is deliberately narrow:
 *
 * - **It publishes nothing.** The comparison returns a review and the caller commits it; no active page text,
 *   no candidate page and no chunk is written here, and the accepted text stays exactly one of the two
 *   readings or a person's edit.
 * - **The deterministic checks run first and are reported**, whatever the reviewer says afterwards. They are
 *   what a person can re-derive, and they are what a reviewer's claimed spans are validated against.
 * - **The reviewer is asked about the image and two unlabelled readings.** It is sent the page's own image
 *   and both texts as A and B, with nothing saying which is older, newer or the candidate: the reviewer has
 *   no tools and no web access, and the texts and the image are untrusted data.
 * - **Pilot mode is enforced here, not by the caller.** Every differing candidate is a pending proposal even
 *   when the reviewer recommends the new text, so nothing is replaced without a human decision — and the only
 *   thing that can change that is an accepted validation record the policy resolves from the store, which
 *   only the store can produce: naming a validation id, passing a policy version, or handing in a predicate
 *   cannot approve anything.
 * - **The baseline is the revision's own page.** The page the input names is read back from the revision it
 *   names and the supplied text, hash, unit and ordinal have to be that page's, so a comparison is never
 *   attributed to a page whose text it did not see.
 * - **A failed review is not an acceptance.** An unreachable, timed-out, truncated or malformed answer leaves
 *   the baseline in place and says which one it was; it never causes a transcription to be replayed, because
 *   nothing on this path reads a page.
 * - **The same comparison is not paid for twice.** The review is stored under the fingerprints of the
 *   baseline, the candidate, the image, the reviewer and the policy, so repeating a comparison reuses it and a
 *   changed reviewer or policy recomputes it — without reading a page again. A page whose artifact is no
 *   longer the image a stored review judged reuses nothing: those pixels are gone.
 */
class OcrComparisonService(
    /** The revisions a baseline is read from, so a baseline the document has moved past can be refused. */
    private val revisions: DocumentRevisionStore,
    /** Where a review is durable, so the same comparison is reused and a proposal stays pending. */
    private val reviews: OcrReviewStore,
    /** How a snapshotted reviewer revision id becomes the revision itself, as [LlmOcr] resolves its own. */
    private val revisionOf: (String) -> OcrProfileRevision?,
    private val policy: OcrDecisionPolicy = OcrDecisionPolicy(),
    /** How "the named key variable is set" is answered, injected so a test needs no environment of its own. */
    private val lookup: (String) -> String? = System::getenv,
    /** Ticket 07's dispatch permit validator, or null while this build has none. */
    private val permits: ExternalDispatchPermitValidator? = null,
    /**
     * Told before each review request is sent, retries included.
     *
     * The review call is a paid external request like the transcription call is, and the three readings it
     * carries make it the more expensive of the two. Ticket 07's accounting counts it here, in the stage it
     * belongs to, rather than folding it into the same counter as a transcription.
     */
    private val calls: ((ExternalDispatchPermitRequest) -> Unit)? = null,
    private val timeout: Duration = ImageLlmClient.DEFAULT_TIMEOUT,
    private val maxResponseBytes: Int = ImageLlmClient.MAX_RESPONSE_BYTES,
    private val retryPolicy: RetryPolicy = RetryPolicy(),
    /**
     * The transport engine every review client this comparison builds is constructed on, or null for each
     * client's own CIO engine — the seam a test injects a recording transport through so an external review
     * dispatch can be observed without leaving the machine. The client's own rules (redirects off, permits,
     * key handling) still apply to whatever transport it is built on.
     */
    private val clientEngine: HttpClientEngine? = null,
) {

    /**
     * The review of one page's comparison.
     *
     * @throws OcrComparisonException when the attempt cannot be honoured — a baseline the document has moved
     *   past, a baseline that is not the page its revision holds, or an attempt admitted under another prompt
     *   or policy version than this build runs.
     */
    suspend fun compare(input: PageComparisonInput): PageReview {
        if (input.reviewPromptVersion != OCR_REVIEW_PROMPT_VERSION) {
            throw OcrComparisonException(
                OcrComparisonException.REVIEW_PROMPT_MISMATCH,
                "this attempt was admitted under review prompt version ${input.reviewPromptVersion} and this " +
                    "build ships version $OCR_REVIEW_PROMPT_VERSION, so no comparison was made with " +
                    "different instructions than the attempt chose",
            )
        }
        if (input.policyVersion != policy.policyVersion) {
            throw OcrComparisonException(
                OcrComparisonException.POLICY_VERSION_MISMATCH,
                "this attempt was admitted under decision policy ${input.policyVersion} and this build runs " +
                    "policy ${policy.policyVersion}, so text was not judged by a policy the attempt did not " +
                    "choose",
            )
        }
        val candidateHash = readingTextHash(input.candidateText)
        val fingerprint = fingerprintOf(input, candidateHash)

        // The baseline is checked against the store before anything else that could answer from it: a page
        // the document has moved past, a revision that holds no such page, and a text that is not the text
        // that page holds are all refusals, and none of them may reach the no-op below or a reviewer.
        requirePublishableBaseline(input)

        // An identical non-empty pair is a no-op: nothing differs, so there is nothing to review and nothing
        // to replace — and it is answered from the two texts alone, without a store lookup or a reviewer call.
        // An identically *empty* pair is deliberately not this case: an empty pair is not proof that the page
        // is blank, and blankness is the raster's question rather than a comparison's.
        if (input.baselineText != null && input.baselineText == input.candidateText && input.candidateText.isNotBlank()) {
            return reviewOf(
                input = input,
                fingerprint = fingerprint,
                candidateHash = candidateHash,
                recommendation = ReviewerRecommendation.UNCERTAIN,
                disposition = PublicationDisposition.KEEP,
                confidence = null,
                reasons = emptyList(),
                reviewerModelVersion = null,
                outcomeCode = IDENTICAL_TEXT_OUTCOME,
            )
        }

        // Two empty readings are their own state rather than a comparison: there is no text to differ, no
        // text to prefer and nothing a reviewer could judge, and an identically empty pair is *not* a no-op —
        // it is not proof that the page is blank, which only the raster can say. It is therefore a pending
        // proposal with no searchable text, and the reviewer is not asked about nothing.
        if ((input.baselineText == null || input.baselineText.isBlank()) && input.candidateText.isBlank()) {
            val diagnostics = PageDiagnostics.of(input.baselineText, input.candidateText)
            val disposition = policy.disposition(diagnostics, ReviewerRecommendation.UNCERTAIN, scopeOf(input))
            val review = reviewOf(
                input = input,
                fingerprint = fingerprint,
                candidateHash = candidateHash,
                recommendation = ReviewerRecommendation.UNCERTAIN,
                disposition = disposition,
                confidence = null,
                reasons = deterministicReasons(diagnostics),
                reviewerModelVersion = null,
                outcomeCode = EMPTY_PAIR_OUTCOME,
            )
            if (review.disposition != PublicationDisposition.KEEP) reviews.record(review)
            return review
        }

        // A stored review is reused only while the artifact is still the image it judged. A page whose file
        // was replaced, truncated or removed is not the page this verdict was made about, so the comparison
        // is made again — and the reviewer refuses the pixels that are no longer there rather than a decision
        // about a page being handed back for whatever now sits at that path.
        //
        // What is reused is the reviewer's answer and the checks, not the disposition: a stored `APPROVE` was
        // decided while an accepted validation record covered this combination, and that record can be gone by
        // the time the same comparison is served again. Authoritative here is therefore the recommendation the
        // row holds and this policy, which resolves the accepted record from the archive on every decision — so
        // a cached approval whose acceptance is gone is served as a proposal, and an approval can never outlive
        // the record that produced it.
        if (input.page.isIntact()) {
            reviews.find(fingerprint, input.page.sha256)?.let { stored ->
                val diagnostics = PageDiagnostics.of(input.baselineText, input.candidateText)
                val disposition = policy.disposition(diagnostics, stored.recommendation, scopeOf(input))
                return stored.copy(
                    disposition = disposition,
                    searchable = searchable(disposition, input.baselineText, input.candidateText),
                )
            }
        }

        val diagnostics = PageDiagnostics.of(input.baselineText, input.candidateText)
        val reviewer = askReviewer(input)
        val validated = reviewer.reasons
            .filter { reason -> diagnostics.validates(reason.spanA, reason.spanB) }
            .map { reason ->
                ReviewReason(
                    code = REVIEWER_REASON_CODE,
                    origin = ReasonOrigin.REVIEWER,
                    baselineSpan = reason.spanA,
                    candidateSpan = reason.spanB,
                    explanation = reason.explanation,
                )
            }
        var recommendation = reviewer.recommendation
        var outcomeCode = reviewer.outcomeCode
        if (input.baselineText == null) {
            // Nothing the candidate could be better than: a page with no published text has no comparison to
            // be won, so it is uncertain by construction and stays outside retrieval until a person decides.
            // This is the state that explains the review, whatever the reviewer answered about it.
            recommendation = ReviewerRecommendation.UNCERTAIN
            outcomeCode = NO_BASELINE_OUTCOME
        } else if (recommendation != ReviewerRecommendation.UNCERTAIN && validated.isEmpty()) {
            // A recommendation nobody can tie to a place the readings differ in is not a judgement of these
            // two readings, so it is recorded as uncertainty rather than passed on as an opinion.
            recommendation = ReviewerRecommendation.UNCERTAIN
            outcomeCode = UNSUPPORTED_REVIEW_OUTCOME
        }
        val disposition = policy.disposition(diagnostics, recommendation, scopeOf(input))
        val review = reviewOf(
            input = input,
            fingerprint = fingerprint,
            candidateHash = candidateHash,
            recommendation = recommendation,
            disposition = disposition,
            confidence = reviewer.confidence,
            reasons = (deterministicReasons(diagnostics) + validated).take(MAX_REASONS),
            reviewerModelVersion = reviewer.modelVersion,
            outcomeCode = outcomeCode,
        )
        // Only what somebody has to act on is durable: a KEEP leaves nothing to decide, so it is not stored
        // and a later comparison of the same page reaches the same answer without asking again. Everything a
        // person has to look at — a pending proposal, including one whose reviewer failed — is kept.
        if (review.disposition != PublicationDisposition.KEEP) reviews.record(review)
        return review
    }

    // ---- the reviewer ----

    /** One reviewer's answer, or the safe code saying why there is none. */
    private class ReviewerAnswer(
        val recommendation: ReviewerRecommendation = ReviewerRecommendation.UNCERTAIN,
        val confidence: Double? = null,
        val reasons: List<ImageReviewReason> = emptyList(),
        val modelVersion: String? = null,
        val outcomeCode: String? = null,
    )

    /**
     * Asks the reviewer about this page, or records why there is no answer.
     *
     * Every failure is this page's outcome rather than an exception: an unavailable reviewer, a timeout, a
     * truncated or malformed answer, and a reviewer revision this build no longer knows all mean the same
     * thing to the caller — the recommendation is uncertain and the baseline stays. Nothing here reads a page,
     * so no failure on this path can replay a transcription.
     */
    private suspend fun askReviewer(input: PageComparisonInput): ReviewerAnswer {
        val revision = revisionOf(input.reviewProfileRevisionId)
            ?: return ReviewerAnswer(outcomeCode = ImageLlmException.PROFILE_REVISION_UNKNOWN)
        val client = ImageLlmClient(
            profile = revision,
            lookup = lookup,
            permits = permits,
            calls = calls,
            timeout = timeout,
            maxResponseBytes = maxResponseBytes,
            retryPolicy = retryPolicy,
            engine = clientEngine,
        )
        return try {
            // One client per comparison: the endpoint, model and limits are the revision's, and nothing
            // outlives the review it was built for. The reviewer answers about a side, and this side of the
            // request is the one the baseline went out as, which is the only place that is known.
            val answer = client.use { it.review(input.page, readingAOf(input), input.candidateText) }
            ReviewerAnswer(
                recommendation = recommendationOf(answer.recommendation, baselineIsSideA = BASELINE_IS_SIDE_A),
                confidence = answer.confidence,
                reasons = answer.reasons,
                modelVersion = answer.modelVersion,
            )
        } catch (failure: ImageLlmException) {
            ReviewerAnswer(outcomeCode = failure.code)
        }
    }

    /**
     * The first reading of the review request: what the page publishes now, or nothing at all.
     *
     * The two readings are sent as side A and side B of one request, and nothing in the request says which is
     * which: a request that named one side the candidate's would invite a preference for it. The labels are
     * fixed rather than shuffled so the same comparison always asks the same question and is reproducible,
     * and so a stored review stays reusable ([BASELINE_IS_SIDE_A] is the side this reading goes out as).
     */
    private fun readingAOf(input: PageComparisonInput): String = input.baselineText ?: ""

    // ---- the decision ----

    /**
     * Refuses a baseline that is not the page the revision it names actually holds.
     *
     * The input names the revision its baseline text came from, and that revision is the one the page is
     * compared against only while it is what the document publishes: after another publication the text has
     * moved on, and a comparison against the old reading — and a decision taken from it — would replace newer
     * text with an answer about older text.
     *
     * Being active is not enough on its own, which is the second half of this check: the page the input names
     * is read back from that revision, and the text, the text's hash where the revision recorded one, the unit
     * id and the ordinal the input carries have to be *that page's*. Without it a caller could name a real
     * active revision while holding fabricated baseline text, and the review — and any decision taken from it —
     * would be persisted as that revision's page. It runs before the identical-text no-op and before any stored
     * review or reviewer is reached, so a mismatch is a refusal rather than a comparison of something else.
     */
    private fun requirePublishableBaseline(input: PageComparisonInput) {
        val revisionId = input.baselineRevisionId ?: return
        val active = revisions.activeRevisionId(input.documentId)
        if (active != revisionId) {
            throw OcrComparisonException(
                OcrComparisonException.STALE_BASELINE,
                "the baseline this comparison names is no longer the revision the document publishes, so it " +
                    "was refused rather than computed against text the page has moved past",
            )
        }
        val stored = revisions.pageForUnit(revisionId, ContentUnitId(input.unitId))
            ?: throw OcrComparisonException(
                OcrComparisonException.BASELINE_PAGE_UNKNOWN,
                "the revision this comparison names holds no page under the identity it names, so the " +
                    "baseline it carries is not a reading of any page of that revision and nothing was compared",
            )
        // The text and the page's identity have to be the stored ones. The hash is matched where the revision
        // recorded one: a published revision's page carries the hash only when its text was staged with it
        // ([DocumentRevisionStore.recordPublishedContent] copies published pages without one), and a hash the
        // store deliberately did not record is not a mismatch — the text comparison above is what holds there.
        if (stored.ordinal != input.ordinal ||
            stored.extractedText != input.baselineText ||
            (stored.textSha256 != null && stored.textSha256 != input.baselineTextHash)
        ) {
            throw OcrComparisonException(
                OcrComparisonException.BASELINE_MISMATCH,
                "the page this comparison names is not the page of that revision this baseline text, hash and " +
                    "ordinal belong to, so it was refused rather than persisted as that revision's page",
            )
        }
    }

    /** Which reviewer judged this comparison, which is what an accepted validation record's scope is read with. */
    private fun scopeOf(input: PageComparisonInput): ReviewerScope =
        ReviewerScope(
            reviewerRevisionId = input.reviewProfileRevisionId,
            reviewPromptVersion = input.reviewPromptVersion,
        )

    private fun fingerprintOf(input: PageComparisonInput, candidateHash: String): OcrReviewFingerprint =
        OcrReviewFingerprint.of(
            documentId = input.documentId.value,
            unitId = input.unitId,
            ordinal = input.ordinal,
            baselineRevisionId = input.baselineRevisionId,
            baselineTextHash = input.baselineTextHash,
            candidateHash = candidateHash,
            reviewProfileRevisionId = input.reviewProfileRevisionId,
            reviewPromptVersion = input.reviewPromptVersion,
            policyVersion = input.policyVersion,
        )

    private fun deterministicReasons(diagnostics: PageDiagnostics): List<ReviewReason> =
        diagnostics.findings.map { finding ->
            ReviewReason(
                code = finding.code.name,
                origin = ReasonOrigin.DETERMINISTIC,
                baselineSpan = finding.baselineSpan,
                candidateSpan = finding.candidateSpan,
            )
        }

    private fun reviewOf(
        input: PageComparisonInput,
        fingerprint: OcrReviewFingerprint,
        candidateHash: String,
        recommendation: ReviewerRecommendation,
        disposition: PublicationDisposition,
        confidence: Double?,
        reasons: List<ReviewReason>,
        reviewerModelVersion: String?,
        outcomeCode: String?,
    ): PageReview = PageReview(
        fingerprint = fingerprint,
        documentId = input.documentId,
        unitId = input.unitId,
        ordinal = input.ordinal,
        imageSha256 = input.page.sha256,
        baselineRevisionId = input.baselineRevisionId,
        baselineTextHash = input.baselineTextHash,
        candidateHash = candidateHash,
        recommendation = recommendation,
        disposition = disposition,
        confidence = confidence,
        reasons = reasons,
        reviewerRevisionId = input.reviewProfileRevisionId,
        reviewerModelVersion = reviewerModelVersion,
        reviewPromptVersion = input.reviewPromptVersion,
        policyVersion = input.policyVersion,
        outcomeCode = outcomeCode,
        searchable = searchable(disposition, input.baselineText, input.candidateText),
    )

    /**
     * Whether the page keeps text a retrieval path may use.
     *
     * A pending candidate is never that text: until a person decides, the page keeps what its published
     * revision holds, and a page with no baseline then has nothing to search at all. Only an approved
     * candidate, or a keep with a baseline, leaves text behind.
     */
    private fun searchable(
        disposition: PublicationDisposition,
        baselineText: String?,
        candidateText: String,
    ): Boolean = when (disposition) {
        PublicationDisposition.KEEP, PublicationDisposition.PROPOSE -> !(baselineText ?: "").isBlank()
        PublicationDisposition.APPROVE -> candidateText.isNotBlank()
    }

    companion object {

        /** The comparison's own code for an identical non-empty pair, which needs no reviewer. */
        const val IDENTICAL_TEXT_OUTCOME: String = "IDENTICAL_TEXT"

        /** The code for a page with no baseline: there is nothing the candidate could be better than. */
        val NO_BASELINE_OUTCOME: String = DiagnosticCode.NO_BASELINE.name

        /** The code for a reviewer recommendation nobody could tie to a place the readings differ in. */
        const val UNSUPPORTED_REVIEW_OUTCOME: String = "UNSUPPORTED_REVIEW"

        /** The code for two readings that are both empty. An empty pair is not a blank page. */
        val EMPTY_PAIR_OUTCOME: String = DiagnosticCode.EMPTY_PAIR.name

        /** The code a reviewer's own validated reason carries, so its origin is visible in a stored review. */
        const val REVIEWER_REASON_CODE: String = "REVIEWER"

        /**
         * The most reasons one review stores.
         *
         * A review is shown beside a page and read by a person, so a pathological page must not turn one
         * comparison into an unbounded row: the deterministic findings come first and are the ones a person
         * re-derives, and the reviewer's reasons follow.
         */
        const val MAX_REASONS: Int = 16
    }
}
