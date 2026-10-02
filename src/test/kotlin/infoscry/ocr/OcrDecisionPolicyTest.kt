package infoscry.ocr

import infoscry.storage.Database
import infoscry.storage.OcrReviewStore
import infoscry.storage.OcrReviewStore.OcrValidationRecord
import infoscry.storage.SchemaMigrator
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The deterministic checks and the disposition policy, without a model or a page.
 *
 * What this file defends is the pair of rules the pilot rests on: the checks establish *where* two
 * readings differ, and the policy decides what may happen to that difference. Nothing here can be
 * weakened by a fluent or confident reading, because neither fluency nor the model's own confidence is an
 * input to the policy at all.
 *
 * An accepted validation record is the one thing here that needs an archive: a record is a capability that
 * exists only as a persisted row, so the tests below take one the way the measurement flow leaves one —
 * accept the combination, then read the record back — rather than stating one.
 */
class OcrDecisionPolicyTest {

    // ---- the deterministic checks ----

    @Test
    fun `an empty reading is named as an empty reading, not as blank paper`() {
        val diagnostics = PageDiagnostics.of("Faktura 4711", "")

        assertTrue(DiagnosticCode.EMPTY_OUTPUT in diagnostics.codes)
        assertTrue(
            diagnostics.codes.none { code -> code == DiagnosticCode.EMPTY_PAIR },
            "one empty side is an empty output, not two identically empty readings",
        )
        assertTrue(diagnostics.needsDecision)
    }

    @Test
    fun `two identically empty readings are an unverified pair rather than a no-op`() {
        val diagnostics = PageDiagnostics.of("", "")

        assertTrue(DiagnosticCode.EMPTY_PAIR in diagnostics.codes)
        assertEquals(
            2,
            diagnostics.findings.count { finding -> finding.code == DiagnosticCode.EMPTY_OUTPUT },
            "each empty side is reported as its own empty output as well",
        )
        assertTrue(diagnostics.needsDecision)
        assertFalse(
            diagnostics.allowsAutomaticReplacement,
            "an empty pair may never be replaced automatically: blankness is the raster's question",
        )
    }

    @Test
    fun `a reading that stops mid-token is reported as truncated`() {
        assertEquals(
            DiagnosticCode.TRUNCATED_OUTPUT,
            PageDiagnostics.of("Faktura 4711\nAnna Andersson", "Faktura 4711\nAnna Ander-").codes
                .first { code -> code == DiagnosticCode.TRUNCATED_OUTPUT },
        )
        assertTrue(DiagnosticCode.TRUNCATED_OUTPUT in PageDiagnostics.of("a (b", "a (b").codes)
    }

    @Test
    fun `a repeated paragraph is reported as repeated text`() {
        val diagnostics = PageDiagnostics.of(
            "Faktura 4711\nSumma 100",
            "Faktura 4711\nSumma 100\nSumma 100",
        )

        assertTrue(DiagnosticCode.REPEATED_TEXT in diagnostics.codes)
        assertFalse(diagnostics.allowsAutomaticReplacement)
    }

    @Test
    fun `a suspicious character sequence is reported`() {
        assertTrue(
            DiagnosticCode.SUSPICIOUS_SEQUENCE in
                PageDiagnostics.of("Faktura 4711", "Faktura #### 4711").codes,
        )
        assertTrue(
            DiagnosticCode.SUSPICIOUS_SEQUENCE in
                PageDiagnostics.of("ren text", "ren\u0000text").codes,
        )
    }

    @Test
    fun `an omitted marginal line is a missing region of the baseline`() {
        val diagnostics = PageDiagnostics.of(
            "Faktura 4711\nAnna Andersson\nmarginalanteckning",
            "Faktura 4711\nAnna Andersson",
        )

        val missing = diagnostics.findings.single { finding -> finding.code == DiagnosticCode.MISSING_REGION }
        assertEquals(
            "marginalanteckning",
            "Faktura 4711\nAnna Andersson\nmarginalanteckning"
                .substring(missing.baselineSpan!!.startOffset, missing.baselineSpan.endOffset)
                .trim(),
            "the finding points at the text the candidate dropped",
        )
        assertFalse(diagnostics.allowsAutomaticReplacement)
    }

    @Test
    fun `a line only the candidate reads is an added region`() {
        val diagnostics = PageDiagnostics.of("Faktura 4711", "Faktura 4711\nBetalad 2026-09-30")

        val added = diagnostics.findings.single { finding -> finding.code == DiagnosticCode.ADDED_REGION }
        assertTrue(added.candidateSpan != null && added.baselineSpan == null)
    }

    @Test
    fun `a changed number, name, date and negation are each called out`() {
        assertTrue(DiagnosticCode.NUMBER_CHANGED in codesOf("name 123", "name 128"))
        assertTrue(DiagnosticCode.NAME_CHANGED in codesOf("Anna Andersson", "Anna Lindqvist"))
        assertTrue(DiagnosticCode.DATE_CHANGED in codesOf("betalad 2026-09-30", "betalad 2026-09-03"))
        assertTrue(DiagnosticCode.NEGATION_CHANGED in codesOf("the door is not locked", "the door is locked"))

        assertTrue(
            DiagnosticCode.DATE_CHANGED in codesOf("2026-09-30", "2026-09-03") &&
                DiagnosticCode.NUMBER_CHANGED !in codesOf("2026-09-30", "2026-09-03"),
            "a date is a date rather than a bare number",
        )
        assertTrue(DiagnosticCode.NUMBER_CHANGED in codesOf("Summa 100", "Summa 9999"))
        assertFalse(DiagnosticCode.DATE_CHANGED in codesOf("Summa 100", "Summa 9999"))
    }

    @Test
    fun `every sensitive change is reported with the region it was found in`() {
        val baseline = "Faktura 4711\nSumma 100"
        val candidate = "Faktura 4712\nSumma 100"
        val diagnostics = PageDiagnostics.of(baseline, candidate)

        val finding = diagnostics.findings.single { it.code == DiagnosticCode.NUMBER_CHANGED }
        val baselineSpan = finding.baselineSpan!!
        val candidateSpan = finding.candidateSpan!!
        assertEquals("4711", baseline.substring(baselineSpan.startOffset, baselineSpan.endOffset))
        assertEquals("4712", candidate.substring(candidateSpan.startOffset, candidateSpan.endOffset))
        assertTrue(diagnostics.validates(baselineSpan, candidateSpan), "a reported region is a validated one")
    }

    @Test
    fun `a length change is a metric of the comparison and not a region a reason may point at`() {
        val diagnostics = PageDiagnostics.of("a".repeat(100), "b".repeat(10))

        val finding = diagnostics.findings.single { it.code == DiagnosticCode.LENGTH_CHANGE }
        assertNull(finding.baselineSpan, "a length change is about the whole comparison, not a stretch of it")
        assertNull(finding.candidateSpan)
        assertFalse(
            DiagnosticCode.LENGTH_CHANGE in DiagnosticCode.REGION_CODES,
            "a reason pointing at two different lengths would validate a claim anywhere on the page",
        )
    }

    @Test
    fun `a difference the checks did not establish is not a validated span`() {
        val diagnostics = PageDiagnostics.of("Faktura 4711\nSumma 100", "Faktura 4712\nSumma 100")

        assertTrue(diagnostics.validates(TextSpan(8, 12), TextSpan(8, 12)))
        assertFalse(
            diagnostics.validates(TextSpan(0, 7), TextSpan(0, 7)),
            "a stretch the two readings agree on is not a difference",
        )
        assertFalse(diagnostics.validates(null, null), "a reason that points nowhere supports nothing")
    }

    @Test
    fun `a claimed span is absent exactly when that side has no finding span`() {
        val diagnostics = PageDiagnostics.of("Faktura 4711", "Faktura 4711\nBetalad 2026-09-30")
        val added = diagnostics.findings.single { finding -> finding.code == DiagnosticCode.ADDED_REGION }
        assertNull(added.baselineSpan, "a line only the candidate reads has no baseline place")
        val candidateSpan = added.candidateSpan!!

        assertTrue(
            diagnostics.validates(baselineSpan = null, candidateSpan = candidateSpan),
            "a reason about a region only the candidate has names the candidate's side and nothing else",
        )
        assertFalse(
            diagnostics.validates(TextSpan(0, 7), candidateSpan),
            "a reason cannot attach a baseline stretch to a region the baseline has nothing in",
        )
        assertFalse(
            diagnostics.validates(baselineSpan = null, candidateSpan = TextSpan(0, 7)),
            "nor drop the side the finding is about and point anywhere at all",
        )
    }

    @Test
    fun `identical readings produce no findings at all`() {
        val diagnostics = PageDiagnostics.of("Faktura 4711", "Faktura 4711")

        assertEquals(emptyList(), diagnostics.findings)
        assertFalse(diagnostics.needsDecision)
        assertTrue(diagnostics.allowsAutomaticReplacement, "nothing differs, so there is nothing to block")
    }

    // ---- the pilot's disposition policy ----

    @Test
    fun `a differing candidate is pending manual review in pilot mode whoever recommends it`() {
        val policy = OcrDecisionPolicy()
        val diagnostics = PageDiagnostics.of("name 123", "name 128")

        assertEquals(
            PublicationDisposition.PROPOSE,
            policy.disposition(diagnostics, ReviewerRecommendation.NEW_BETTER, scope()),
            "the reviewer's recommendation never replaces text in pilot mode",
        )
        assertEquals(
            PublicationDisposition.PROPOSE,
            policy.disposition(diagnostics, ReviewerRecommendation.UNCERTAIN, scope()),
        )
        assertEquals(
            PublicationDisposition.PROPOSE,
            policy.disposition(diagnostics, ReviewerRecommendation.EXISTING_BETTER, scope()),
        )
        assertFalse(policy.automaticReplacementEnabled)
    }

    @Test
    fun `nothing that differs is kept and nothing that is the same is proposed`() {
        val policy = OcrDecisionPolicy()

        assertEquals(
            PublicationDisposition.KEEP,
            policy.disposition(PageDiagnostics.of("same", "same"), ReviewerRecommendation.NEW_BETTER, scope()),
        )
        assertEquals(
            PublicationDisposition.PROPOSE,
            policy.disposition(PageDiagnostics.of("", ""), ReviewerRecommendation.UNCERTAIN, scope()),
            "an identically empty pair is a page nobody has resolved, not a no-op",
        )
    }

    @Test
    fun `an accepted validation record is the only way to automatic replacement`() {
        ValidationArchive().use { archive ->
            val diagnostics = PageDiagnostics.of(PLAIN_BEFORE, PLAIN_AFTER)
            archive.accept()
            val accepting = OcrDecisionPolicy(policyVersion = MEASURED, reviews = archive.reviews)

            assertEquals(
                PublicationDisposition.APPROVE,
                accepting.disposition(diagnostics, ReviewerRecommendation.NEW_BETTER, scope()),
                "a policy resolving an accepted record for this reviewer, prompt and version may approve a plain change",
            )
            assertEquals(
                PublicationDisposition.PROPOSE,
                accepting.disposition(diagnostics, ReviewerRecommendation.EXISTING_BETTER, scope()),
                "the reviewer has to have recommended the candidate for an automatic replacement",
            )
            assertFalse(
                OcrDecisionPolicy(policyVersion = MEASURED)
                    .disposition(diagnostics, ReviewerRecommendation.NEW_BETTER, scope()) ==
                    PublicationDisposition.APPROVE,
                "a later policy version that resolves no record approves nothing, which is what the pilot constant rests on",
            )
            assertFalse(OcrDecisionPolicy(policyVersion = MEASURED).automaticReplacementEnabled)
            archive.accept(policyVersion = OCR_POLICY_VERSION)
            assertEquals(
                PublicationDisposition.PROPOSE,
                OcrDecisionPolicy(
                    policyVersion = OCR_POLICY_VERSION,
                    reviews = archive.reviews,
                ).disposition(diagnostics, ReviewerRecommendation.NEW_BETTER, scope()),
                "the pilot's own version replaces nothing without a person, whatever record is resolved for it",
            )
            // Checked through the store rather than through a supplied record: the store's read is keyed by
            // the scope and the version, so a record accepted for another reviewer, prompt or policy version
            // cannot even be handed to this decision. The scope is a property of the row the policy reads.
            assertEquals(
                PublicationDisposition.PROPOSE,
                accepting.disposition(
                    diagnostics,
                    ReviewerRecommendation.NEW_BETTER,
                    ReviewerScope("reviewer-2", OCR_REVIEW_PROMPT_VERSION),
                ),
                "a record accepted for another reviewer is not this comparison's acceptance",
            )
            assertEquals(
                PublicationDisposition.PROPOSE,
                accepting.disposition(
                    diagnostics,
                    ReviewerRecommendation.NEW_BETTER,
                    ReviewerScope(REVIEWER_REVISION, OCR_REVIEW_PROMPT_VERSION + 1),
                ),
                "and one accepted under another prompt is not either",
            )
            assertEquals(
                PublicationDisposition.PROPOSE,
                OcrDecisionPolicy(
                    policyVersion = MEASURED + 1,
                    reviews = archive.reviews,
                ).disposition(diagnostics, ReviewerRecommendation.NEW_BETTER, scope()),
                "a record accepted for another policy version is not evidence about this one",
            )
        }
    }

    @Test
    fun `an approval does not outlive the row it was decided from`() {
        ValidationArchive().use { archive ->
            val diagnostics = PageDiagnostics.of(PLAIN_BEFORE, PLAIN_AFTER)
            archive.accept()
            val accepting = OcrDecisionPolicy(policyVersion = MEASURED, reviews = archive.reviews)

            assertEquals(
                PublicationDisposition.APPROVE,
                accepting.disposition(diagnostics, ReviewerRecommendation.NEW_BETTER, scope()),
                "the acceptance is there, so this policy approves",
            )

            archive.discardAcceptances()

            assertEquals(
                PublicationDisposition.PROPOSE,
                accepting.disposition(diagnostics, ReviewerRecommendation.NEW_BETTER, scope()),
                "the same policy asks the archive as it stands now, so the approval ends with its row",
            )
        }
    }

    @Test
    fun `named and sensitive differences block automatic replacement even when it is enabled`() {
        ValidationArchive().use { archive ->
            archive.accept()
            val accepting = OcrDecisionPolicy(policyVersion = MEASURED, reviews = archive.reviews)
            val changedDate = PageDiagnostics.of("betalad 2026-09-30", "betalad 2026-09-03")
            val inventedName = PageDiagnostics.of("Anna Andersson", "Anna Lindqvist")
            val plainChange = PageDiagnostics.of(PLAIN_BEFORE, PLAIN_AFTER)

            assertEquals(
                PublicationDisposition.PROPOSE,
                accepting.disposition(changedDate, ReviewerRecommendation.NEW_BETTER, scope()),
                "a changed date needs a person even when a validation record was accepted",
            )
            assertEquals(
                PublicationDisposition.PROPOSE,
                accepting.disposition(inventedName, ReviewerRecommendation.NEW_BETTER, scope()),
                "a changed or invented name needs a person too",
            )
            assertFalse(changedDate.allowsAutomaticReplacement)
            assertTrue(plainChange.allowsAutomaticReplacement)
            assertEquals(
                PublicationDisposition.APPROVE,
                accepting.disposition(plainChange, ReviewerRecommendation.NEW_BETTER, scope()),
                "what stays approvable is a plain change the checks did not flag",
            )
        }
    }

    /** A change that is a plain difference and nothing the checks call out: no name, number, date or negation. */
    private val PLAIN_BEFORE = "kvittot ar betald"
    private val PLAIN_AFTER = "kvittot ar betalad"

    private fun scope(reviewer: String = REVIEWER_REVISION, prompt: Int = OCR_REVIEW_PROMPT_VERSION) =
        ReviewerScope(reviewerRevisionId = reviewer, reviewPromptVersion = prompt)

    @Test
    fun `a span that does not belong to a reading is refused by the record itself`() {
        assertFalse(TextSpan(0, 1).overlaps(TextSpan(5, 6)))
        assertTrue(TextSpan(0, 5).overlaps(TextSpan(4, 6)))
        kotlin.test.assertFailsWith<IllegalArgumentException> { TextSpan(4, 4) }
        kotlin.test.assertFailsWith<IllegalArgumentException> { TextSpan(-1, 4) }
    }

    private fun codesOf(baseline: String, candidate: String): Set<DiagnosticCode> =
        PageDiagnostics.of(baseline, candidate).codes
}

/** The measured policy version these tests accept a record under, past the pilot's own. */
private const val MEASURED: Int = OCR_POLICY_VERSION + 1

/** The reviewer revision these tests accept a record for. */
private const val REVIEWER_REVISION: String = "reviewer-1"

/**
 * A real store over a throwaway archive, because an accepted validation record exists only as a persisted
 * row.
 *
 * The policy's rule is that the record is the capability, and the capability is a row: there is no
 * constructor to call and no value to state. A test that needs a record therefore accepts a combination the
 * way the measurement flow does, through the store's own write path, and wires the policy to the store that
 * holds it — the only thing a policy can be wired to.
 */
private class ValidationArchive : AutoCloseable {

    private val directory: Path = Files.createTempDirectory("infoscry-validation")

    private val database = Database(directory.resolve("infoscry.db"))

    /** The store a policy is wired to, and the only thing that produces an accepted record. */
    val reviews: OcrReviewStore

    init {
        SchemaMigrator(database).migrate()
        reviews = OcrReviewStore(database)
    }

    /** Accepts one combination, and returns the record the archive now holds for it. */
    fun accept(
        policyVersion: Int = MEASURED,
        reviewerRevisionId: String = REVIEWER_REVISION,
        reviewPromptVersion: Int = OCR_REVIEW_PROMPT_VERSION,
    ): OcrValidationRecord = reviews.acceptValidation(
        validationId = "validation-$policyVersion-$reviewerRevisionId-$reviewPromptVersion",
        policyVersion = policyVersion,
        reviewerRevisionId = reviewerRevisionId,
        reviewPromptVersion = reviewPromptVersion,
    )

    /**
     * Removes every acceptance, the way the review of this behaviour does.
     *
     * The store deliberately has no delete — acceptance is re-stated by accepting the combination again — so
     * the one way a record can vanish for a reader is a statement against the table like this one.
     */
    fun discardAcceptances() {
        database.transaction { connection ->
            connection.createStatement().use { statement ->
                statement.execute("DELETE FROM ocr_validation_records")
            }
        }
    }

    override fun close() {
        database.close()
        directory.toFile().deleteRecursively()
    }
}
