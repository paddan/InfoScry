package infoscry.storage

import infoscry.domain.DocumentId
import infoscry.ocr.OcrReviewFingerprint
import infoscry.ocr.PageReview
import infoscry.ocr.PublicationDisposition
import infoscry.ocr.ReviewReason
import infoscry.ocr.ReviewerRecommendation
import infoscry.ocr.ReviewerScope
import java.sql.ResultSet
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * The durable reviews of pages: what one comparison of a page's published text with a candidate concluded.
 *
 * Four questions this store exists to answer, and the reason each one is a store question rather than a
 * service one:
 *
 * - **"Have we already asked this?"** A review is keyed by the comparison's fingerprints — the baseline
 *   revision and the hash of its text, the candidate's text hash, the page image's hash, the reviewer
 *   revision, the review prompt version and the policy version. The same comparison is reused, so an
 *   unchanged page is not sent to a paid reviewer twice; a changed reviewer or policy makes it a different
 *   comparison, which recomputes *without* reading the page again. The image hash is part of the key because
 *   a reviewer judges pixels: a review of one rendering of a page is not a review of another.
 * - **"What is still waiting for a person?"** Pilot mode replaces nothing without a manual decision, so the
 *   pending proposals are read back per document. Resolving one is ticket 08's manual decision, not this
 *   store's write.
 * - **"May a retrieval path use this page's text?"** [PageReview.searchable] is stored, so a page that is
 *   pending review — especially one with no baseline at all — is visibly not searchable instead of being
 *   served as if its reading had been accepted.
 * - **"Was this reviewer and policy combination accepted?"** An automatic replacement needs a persisted,
 *   accepted record behind it, and [acceptedValidation] is the only thing that answers with one. Reading it is
 *   the whole of how such a record enters a process: nothing a caller supplies to a comparison can stand in
 *   for it, and no production path in this build writes one — [acceptValidation] is the measurement and
 *   acceptance flow's write.
 *
 * What a review deliberately does *not* hold is page text. The reasons are a bounded code, the spans they are
 * about and, for a reviewer's own reason, its bounded explanation, so a stored proposal cannot become a
 * second copy of the page and no accepted text can be a merged third reading.
 */
class OcrReviewStore(private val database: Database) {

    /**
     * The review of exactly this comparison of exactly this image, or null when there is none.
     *
     * A miss is what makes a comparison recompute: another reviewer, another policy, another candidate text
     * or another rendering of the page is simply not this row, and the caller performs the comparison again
     * rather than reusing an answer about something else.
     */
    fun find(fingerprint: OcrReviewFingerprint, imageSha256: String): PageReview? = database.read { connection ->
        connection.prepareStatement("$SELECT_REVIEW WHERE review_fingerprint = ? AND image_sha256 = ?").use { statement ->
            statement.setString(1, fingerprint.value)
            statement.setString(2, imageSha256)
            statement.executeQuery().use { rows -> if (rows.next()) rows.toPageReview() else null }
        }
    }

    /**
     * Writes one review, and returns it as it is stored.
     *
     * Only durable reviews reach here: a `KEEP` leaves nothing for anybody to act on and is not stored, so a
     * caller cannot turn a no-op into a row. A review is written once — its key is the comparison it is about —
     * and a repeat of the same key is the same review, which the primary key refuses rather than overwriting.
     */
    fun record(review: PageReview): PageReview {
        database.transaction { connection ->
            connection.prepareStatement(
                "INSERT OR IGNORE INTO page_reviews (review_fingerprint, image_sha256, document_id, unit_id, " +
                    "ordinal, baseline_revision_id, baseline_text_hash, candidate_hash, recommendation, " +
                    "disposition, confidence, reviewer_revision_id, reviewer_model_version, review_prompt_version, " +
                    "policy_version, outcome_code, searchable, reasons, created_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            ).use { statement ->
                statement.setString(1, review.fingerprint.value)
                statement.setString(2, review.imageSha256)
                statement.setString(3, review.documentId.value)
                statement.setString(4, review.unitId)
                statement.setInt(5, review.ordinal)
                statement.setString(6, review.baselineRevisionId)
                statement.setString(7, review.baselineTextHash)
                statement.setString(8, review.candidateHash)
                statement.setString(9, review.recommendation.name)
                statement.setString(10, review.disposition.name)
                if (review.confidence == null) {
                    statement.setNull(11, java.sql.Types.REAL)
                } else {
                    statement.setDouble(11, review.confidence)
                }
                statement.setString(12, review.reviewerRevisionId)
                statement.setString(13, review.reviewerModelVersion)
                statement.setInt(14, review.reviewPromptVersion)
                statement.setInt(15, review.policyVersion)
                statement.setString(16, review.outcomeCode)
                statement.setInt(17, if (review.searchable) 1 else 0)
                statement.setString(18, REASONS_JSON.encodeToString(REASONS_SERIALIZER, review.reasons))
                statement.setString(19, Instants.now())
                statement.executeUpdate()
            }
        }
        return checkNotNull(find(review.fingerprint, review.imageSha256)) {
            "a recorded review is readable back under the fingerprints it was written with"
        }
    }

    /**
     * One accepted validation record: the measured evidence that was accepted for one combination of policy
     * version, reviewer revision and prompt versions, as the archive read it back.
     *
     * This is a value rather than a predicate on purpose. "An accepted validation allows this replacement"
     * used to be a function a caller implemented, which made it a claim the caller of a comparison made
     * about itself; this record instead exists only while a row exists. Its constructor is private to this
     * class — a nested declaration is where it belongs, because the store is the only thing that reads the
     * row — and the one entry point is [readBack], which *reads* the row rather than accepting values. So
     * nothing a caller passes to [infoscry.ocr.PageComparisonInput] or to [infoscry.ocr.OcrComparisonService],
     * nothing a caller believes about their own work, and no other file in this module can bring one into
     * being: there is nowhere left to state an acceptance, only a row to read.
     *
     * Its keys are the ones ticket 11 measures acceptance by: the policy version the thresholds belong to,
     * the immutable reviewer revision and the review prompt version the accepted evidence was produced under.
     * A record accepted for another combination is not this comparison's acceptance, so a changed model,
     * prompt or policy falls back to pilot approval instead of carrying an old acceptance forward.
     */
    class OcrValidationRecord private constructor(
        /** The record's own identity, as the acceptance flow named it. */
        val validationId: String,
        /** The policy version these thresholds were measured and accepted for. */
        val policyVersion: Int,
        /** The immutable reviewer revision the accepted evidence was measured with. */
        val reviewProfileRevisionId: String,
        /** The review prompt version the accepted evidence was measured under. */
        val reviewPromptVersion: Int,
        /** When the measured result was accepted, as the store wrote it. */
        val acceptedAt: String,
    ) {

        init {
            require(validationId.isNotBlank()) { "an accepted validation record names itself" }
            require(policyVersion > 0) {
                "an accepted validation record names a positive policy version, was $policyVersion"
            }
            require(reviewProfileRevisionId.isNotBlank()) {
                "an accepted validation record names the reviewer revision it was measured with"
            }
            require(reviewPromptVersion > 0) {
                "an accepted validation record names a positive review prompt version, was $reviewPromptVersion"
            }
            require(acceptedAt.isNotBlank()) { "an accepted validation record says when it was accepted" }
        }

        companion object {

            /**
             * One combination's accepted record, or null when the archive holds no such row.
             *
             * This is the only construction site there is: the values the record holds are the ones this
             * query returned, and the private constructor above is reachable from nowhere else — a nested
             * declaration's private constructor is not accessible to the class that nests it, to another file
             * of this module, or to a test. A record therefore cannot name a combination the archive did not
             * accept, and the combination asked about is the table's primary key exactly.
             */
            internal fun readBack(
                database: Database,
                scope: ReviewerScope,
                policyVersion: Int,
            ): OcrValidationRecord? = database.read { connection ->
                connection.prepareStatement(
                    "$SELECT_ACCEPTED_VALIDATION WHERE policy_version = ? AND review_profile_revision_id = ? " +
                        "AND review_prompt_version = ?",
                ).use { statement ->
                    statement.setInt(1, policyVersion)
                    statement.setString(2, scope.reviewerRevisionId)
                    statement.setInt(3, scope.reviewPromptVersion)
                    statement.executeQuery().use { rows ->
                        if (!rows.next()) {
                            null
                        } else {
                            OcrValidationRecord(
                                validationId = rows.getString("validation_id"),
                                policyVersion = rows.getInt("policy_version"),
                                reviewProfileRevisionId = rows.getString("review_profile_revision_id"),
                                reviewPromptVersion = rows.getInt("review_prompt_version"),
                                acceptedAt = rows.getString("accepted_at"),
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * The accepted validation record covering one comparison, or null when no accepted record covers it.
     *
     * This is the only way an accepted validation can enter the process, and it is a *read*: a comparison
     * path can ask whether a measured combination was accepted and cannot answer that question for itself.
     * A row whose policy version, reviewer revision or review prompt version differs is not this scope's
     * record, so a changed model, prompt or policy falls back to manual approval rather than carrying an old
     * acceptance forward.
     */
    fun acceptedValidation(scope: ReviewerScope, policyVersion: Int): OcrValidationRecord? =
        OcrValidationRecord.readBack(database, scope, policyVersion)

    /**
     * Writes one accepted validation record, and returns it as it is stored.
     *
     * This is the write the measurement and acceptance flow performs once a measured policy was accepted for
     * an exact combination of policy version, reviewer revision and review prompt version. No production path
     * in this build calls it, so no row exists here and no policy can carry a record until that flow does.
     * Acceptance is re-stated by accepting the combination again, which replaces that combination's record
     * rather than accumulating several answers to the same question.
     */
    internal fun acceptValidation(
        validationId: String,
        policyVersion: Int,
        reviewerRevisionId: String,
        reviewPromptVersion: Int,
    ): OcrValidationRecord {
        database.transaction { connection ->
            connection.prepareStatement(
                "INSERT INTO ocr_validation_records (policy_version, review_profile_revision_id, " +
                    "review_prompt_version, validation_id, accepted_at) VALUES (?, ?, ?, ?, ?) " +
                    "ON CONFLICT (policy_version, review_profile_revision_id, review_prompt_version) " +
                    "DO UPDATE SET validation_id = excluded.validation_id, accepted_at = excluded.accepted_at",
            ).use { statement ->
                statement.setInt(1, policyVersion)
                statement.setString(2, reviewerRevisionId)
                statement.setInt(3, reviewPromptVersion)
                statement.setString(4, validationId)
                statement.setString(5, Instants.now())
                statement.executeUpdate()
            }
        }
        return checkNotNull(acceptedValidation(ReviewerScope(reviewerRevisionId, reviewPromptVersion), policyVersion)) {
            "an accepted validation record is readable back under the combination it was written with"
        }
    }

    /**
     * One document's pending proposals, in reading order.
     *
     * These are the pages pilot mode must not replace on its own: the difference is established, a person has
     * not decided about it yet, and the page keeps its baseline text meanwhile. A `KEEP` is not here because
     * nothing has to be decided, and neither is an approved replacement.
     */
    fun pending(documentId: DocumentId): List<PageReview> = database.read { connection ->
        connection.prepareStatement("$SELECT_REVIEW WHERE document_id = ? AND disposition = ? ORDER BY ordinal, unit_id").use { statement ->
            statement.setString(1, documentId.value)
            statement.setString(2, PublicationDisposition.PROPOSE.name)
            statement.executeQuery().use { rows ->
                buildList { while (rows.next()) add(rows.toPageReview()) }
            }
        }
    }

    /**
     * One page's reviews, oldest first, whichever reviewer or policy made them.
     *
     * A page keeps every comparison it has been through, so a later reviewer's answer does not erase the one
     * before it and a person can see what was judged by whom.
     */
    fun forPage(documentId: DocumentId, unitId: String, ordinal: Int): List<PageReview> = database.read { connection ->
        connection.prepareStatement(
            "$SELECT_REVIEW WHERE document_id = ? AND unit_id = ? AND ordinal = ? ORDER BY created_at, review_fingerprint",
        ).use { statement ->
            statement.setString(1, documentId.value)
            statement.setString(2, unitId)
            statement.setInt(3, ordinal)
            statement.executeQuery().use { rows ->
                buildList { while (rows.next()) add(rows.toPageReview()) }
            }
        }
    }

    private fun ResultSet.toPageReview(): PageReview {
        val confidence = getDouble("confidence").takeUnless { wasNull() }
        return PageReview(
            fingerprint = OcrReviewFingerprint(getString("review_fingerprint")),
            documentId = DocumentId(getString("document_id")),
            unitId = getString("unit_id"),
            ordinal = getInt("ordinal"),
            imageSha256 = getString("image_sha256"),
            baselineRevisionId = getString("baseline_revision_id"),
            baselineTextHash = getString("baseline_text_hash"),
            candidateHash = getString("candidate_hash"),
            recommendation = ReviewerRecommendation.valueOf(getString("recommendation")),
            disposition = PublicationDisposition.valueOf(getString("disposition")),
            confidence = confidence,
            reasons = REASONS_JSON.decodeFromString(REASONS_SERIALIZER, getString("reasons")),
            reviewerRevisionId = getString("reviewer_revision_id"),
            reviewerModelVersion = getString("reviewer_model_version"),
            reviewPromptVersion = getInt("review_prompt_version"),
            policyVersion = getInt("policy_version"),
            outcomeCode = getString("outcome_code"),
            searchable = getInt("searchable") != 0,
        )
    }

    private companion object {

        const val COLUMNS =
            "review_fingerprint, image_sha256, document_id, unit_id, ordinal, baseline_revision_id, " +
                "baseline_text_hash, candidate_hash, recommendation, disposition, confidence, " +
                "reviewer_revision_id, reviewer_model_version, review_prompt_version, policy_version, " +
                "outcome_code, searchable, reasons"

        const val SELECT_REVIEW = "SELECT $COLUMNS FROM page_reviews"

        const val SELECT_ACCEPTED_VALIDATION =
            "SELECT validation_id, policy_version, review_profile_revision_id, review_prompt_version, " +
                "accepted_at FROM ocr_validation_records"

        /** The bounded reasons as JSON, with the field names the record itself uses. */
        val REASONS_JSON: Json = Json { encodeDefaults = true }

        val REASONS_SERIALIZER = ListSerializer(ReviewReason.serializer())
    }
}
