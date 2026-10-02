package infoscry.ocr

import infoscry.domain.CollectionId
import infoscry.domain.ContentUnitId
import infoscry.domain.Document
import infoscry.domain.DocumentId
import infoscry.domain.DocumentStatus
import infoscry.domain.ExtractionMethod
import infoscry.domain.SourceLocation
import infoscry.extract.ContentUnitDraft
import infoscry.extract.ExtractionFingerprint
import infoscry.extract.ExtractionSettings
import infoscry.llm.FakeOpenAiResponse
import infoscry.llm.FakeOpenAiServer
import infoscry.llm.LlmProvider
import infoscry.llm.RetryPolicy
import infoscry.storage.CollectionStore
import infoscry.storage.ContentStore
import infoscry.storage.Database
import infoscry.storage.DocumentRevisionStore
import infoscry.storage.DocumentStore
import infoscry.storage.Instants
import infoscry.storage.OcrReviewStore
import infoscry.storage.PageApproval
import infoscry.storage.RevisionPageDraft
import infoscry.storage.SchemaMigrator
import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Comparison and pilot decisions over a real archive and scripted local endpoints.
 *
 * The properties under test are the ones the ticket is about: a differing candidate is a pending proposal
 * rather than a replacement, a reviewer that fails or that cannot point at a difference leaves the baseline
 * alone, an identically empty pair is not a no-op, a changed reviewer or policy recomputes the comparison
 * without reading any page again, and a decision taken against a baseline the document has moved past is
 * neither recomputed nor applied. No page is sent anywhere: the reviewer is a fake loopback endpoint.
 */
class OcrComparisonTest {

    private lateinit var directory: Path
    private lateinit var archive: ComparisonArchive
    private val pages = AtomicInteger(0)

    @BeforeTest
    fun createArchive() {
        directory = Files.createTempDirectory("infoscry-ocr-comparison")
        archive = ComparisonArchive(directory)
    }

    @AfterTest
    fun removeArchive() {
        archive.close()
        directory.toFile().deleteRecursively()
    }

    // ---- the pilot's decision ----

    @Test
    fun `a new better recommendation stays pending manual review in pilot mode`() = runBlocking {
        val document = archive.document()
        val baselineRevision = archive.publish(document.id, FIRST_TEXT)
        val candidateRevision = archive.stageCandidate(document.id, baselineRevision, "name 128")
        val page = pageImage(document.id)
        withReviewer(reviewAnswer(page.unitId, "B_BETTER", confidence = 0.9, reasons = listOf(NAME_REASON))) { server ->
            val review = service(server).compare(input(page, "name 128", baselineRevision, FIRST_TEXT))

            assertEquals(ReviewerRecommendation.NEW_BETTER, review.recommendation)
            assertEquals(
                PublicationDisposition.PROPOSE,
                review.disposition,
                "pilot mode replaces nothing without a human decision, whatever the reviewer recommends",
            )
            assertTrue(review.searchable, "the page keeps the text its published revision already holds")
            assertNull(review.outcomeCode, "the reviewer answered, so no outcome code stands in for it")
            val deterministic = review.reasons
                .filter { it.origin == ReasonOrigin.DETERMINISTIC }
                .mapNotNull { reason -> reason.code.asDiagnosticCode() }
            assertTrue(DiagnosticCode.NUMBER_CHANGED in deterministic, "the changed number is called out by name")
            assertTrue(DiagnosticCode.ALIGNED_DIFFERENCE in deterministic, "the place it differs in is reported")
            assertTrue(review.reasons.isNotEmpty())
            assertTrue(review.reasons.any { reason -> reason.origin == ReasonOrigin.REVIEWER && reason.candidateSpan != null })

            // Nothing about the comparison reaches published text or the staged candidate.
            assertEquals(FIRST_TEXT, assertNotNull(archive.revisions.page(baselineRevision, 0)).extractedText)
            assertEquals("name 128", assertNotNull(archive.revisions.page(candidateRevision, 0)).extractedText)
            assertEquals(PageApproval.PENDING, assertNotNull(archive.revisions.page(candidateRevision, 0)).approval)
            assertEquals(
                setOf(baselineRevision, candidateRevision),
                archive.revisions.revisions(document.id).map { it.id }.toSet(),
                "a comparison creates no revision and publishes none",
            )
            assertEquals(
                listOf(review),
                archive.reviews.pending(document.id),
                "the proposal is durable and pending until somebody decides",
            )
        }
    }

    @Test
    fun `a plausible invented name is pending rather than accepted from fluency or confidence`() = runBlocking {
        val document = archive.document()
        val baselineRevision = archive.publish(document.id, "Anna Andersson betalade")
        val page = pageImage(document.id)
        withReviewer(
            reviewAnswer(
                page.unitId,
                "B_BETTER",
                confidence = 1.0,
                reasons = listOf(ReviewReasonWire("the name on the page is longer", aSpan = 0 to 14, bSpan = 0 to 15)),
            ),
        ) { server ->
            val review = service(server).compare(input(page, "Anna Andersson-Lindqvist betalade", baselineRevision, "Anna Andersson betalade"))

            assertTrue(DiagnosticCode.NAME_CHANGED in review.reasons.mapNotNull { it.code.asDiagnosticCode() })
            assertEquals(PublicationDisposition.PROPOSE, review.disposition)
            assertEquals(1.0, review.confidence, "the reviewer's own number is recorded, and decides nothing")
        }
    }

    @Test
    fun `a changed date and a changed negation are pending too`() = runBlocking {
        val document = archive.document()
        val baselineRevision = archive.publish(document.id, "betalad 2026-09-30 och inte klar")
        val page = pageImage(document.id)
        withReviewer(reviewAnswer(page.unitId, "B_BETTER", reasons = listOf(ReviewReasonWire("the date differs")))) { server ->
            val review = service(server).compare(input(page, "betalad 2026-09-03 och klar", baselineRevision, "betalad 2026-09-30 och inte klar"))

            val codes = review.reasons.mapNotNull { it.code.asDiagnosticCode() }
            assertTrue(DiagnosticCode.DATE_CHANGED in codes)
            assertTrue(DiagnosticCode.NEGATION_CHANGED in codes)
            assertEquals(PublicationDisposition.PROPOSE, review.disposition)
        }
    }

    @Test
    fun `a repeated paragraph is pending rather than accepted`() = runBlocking {
        val document = archive.document()
        val baselineRevision = archive.publish(document.id, "Faktura 4711\nSumma 100")
        val page = pageImage(document.id)
        withReviewer(reviewAnswer(page.unitId, "B_BETTER")) { server ->
            val review = service(server).compare(
                input(page, "Faktura 4711\nSumma 100\nSumma 100", baselineRevision, "Faktura 4711\nSumma 100"),
            )

            val codes = review.reasons.mapNotNull { it.code.asDiagnosticCode() }
            assertTrue(DiagnosticCode.REPEATED_TEXT in codes, "the paragraph the candidate repeats is named")
            assertEquals(PublicationDisposition.PROPOSE, review.disposition)
        }
    }

    @Test
    fun `an omitted marginal line is a missing region that is pending`() = runBlocking {
        val document = archive.document()
        val baselineRevision = archive.publish(document.id, "Faktura 4711\nSumma 100\nmarginalanteckning")
        val page = pageImage(document.id)
        withReviewer(reviewAnswer(page.unitId, "B_BETTER")) { server ->
            val review = service(server).compare(
                input(page, "Faktura 4711\nSumma 100", baselineRevision, "Faktura 4711\nSumma 100\nmarginalanteckning"),
            )

            val missing = review.reasons.single { it.code.asDiagnosticCode() == DiagnosticCode.MISSING_REGION }
            assertEquals("marginalanteckning", "Faktura 4711\nSumma 100\nmarginalanteckning"
                .substring(missing.baselineSpan!!.startOffset, missing.baselineSpan.endOffset).trim())
            assertEquals(PublicationDisposition.PROPOSE, review.disposition)
        }
    }

    @Test
    fun `a high confidence reviewer cannot unlock automatic replacement`() = runBlocking {
        val document = archive.document()
        val baselineRevision = archive.publish(document.id, FIRST_TEXT)
        val page = pageImage(document.id)
        withReviewer(reviewAnswer(page.unitId, "B_BETTER", confidence = 1.0, reasons = listOf(NAME_REASON))) { server ->
            val review = service(server).compare(input(page, "name 128", baselineRevision, FIRST_TEXT))

            assertEquals(
                PublicationDisposition.PROPOSE,
                review.disposition,
                "no confidence and no policy version a caller passes can enable automatic acceptance, and a " +
                    "changed number may never be replaced automatically either",
            )
        }

        // And the same, on a difference the checks do *not* call out: here nothing but an accepted validation
        // record this build cannot produce stands between a confident recommendation and a replacement.
        val plainRevision = archive.publish(document.id, PLAIN_BEFORE)
        val plainPage = pageImage(document.id)
        withReviewer(
            reviewAnswer(
                plainPage.unitId,
                "B_BETTER",
                confidence = 1.0,
                reasons = listOf(PLAIN_REASON),
            ),
        ) { server ->
            val review = service(server).compare(input(plainPage, PLAIN_AFTER, plainRevision, PLAIN_BEFORE))

            assertEquals(ReviewerRecommendation.NEW_BETTER, review.recommendation)
            assertEquals(1.0, review.confidence)
            assertEquals(
                PublicationDisposition.PROPOSE,
                review.disposition,
                "only an accepted validation record could open automatic replacement, and this build has none",
            )
            assertFalse(
                OcrDecisionPolicy().automaticReplacementEnabled,
                "the pilot policy this service is built with cannot approve anything on its own",
            )
        }
    }

    @Test
    fun `only an accepted record the store holds opens automatic replacement`() = runBlocking {
        val document = archive.document()
        val baselineRevision = archive.publish(document.id, PLAIN_BEFORE)
        val page = pageImage(document.id)
        val measured = MEASURED_POLICY_VERSION
        withReviewer(
            reviewAnswer(page.unitId, "B_BETTER", confidence = 1.0, reasons = listOf(PLAIN_REASON)),
        ) { server ->
            val unaccepted = OcrDecisionPolicy(policyVersion = measured)
            assertFalse(unaccepted.automaticReplacementEnabled)
            assertNull(
                archive.reviews.acceptedValidation(ReviewerScope(REVIEWER_ONE, OCR_REVIEW_PROMPT_VERSION), measured),
                "this build writes no acceptance, so no record covers this comparison",
            )
            assertEquals(
                PublicationDisposition.PROPOSE,
                service(server, policy = unaccepted)
                    .compare(input(page, PLAIN_AFTER, baselineRevision, PLAIN_BEFORE, policyVersion = measured))
                    .disposition,
                "a measured policy version with nothing accepted behind it is still the pilot gate",
            )

            // Ticket 11's measurement and acceptance flow is the only writer of a record; the read below is
            // the only way a policy can be handed one, and it is keyed by what was actually accepted.
            archive.reviews.acceptValidation(
                validationId = "validation-1",
                policyVersion = measured,
                reviewerRevisionId = REVIEWER_TWO,
                reviewPromptVersion = OCR_REVIEW_PROMPT_VERSION,
            )
            val accepted = assertNotNull(
                archive.reviews.acceptedValidation(ReviewerScope(REVIEWER_TWO, OCR_REVIEW_PROMPT_VERSION), measured),
                "the record is readable under the combination it was accepted for",
            )
            assertEquals("validation-1", accepted.validationId)
            assertEquals(measured, accepted.policyVersion)
            assertEquals(REVIEWER_TWO, accepted.reviewProfileRevisionId)
            assertEquals(OCR_REVIEW_PROMPT_VERSION, accepted.reviewPromptVersion)
            assertTrue(accepted.acceptedAt.isNotBlank(), "a stored record says when it was accepted")
            assertNull(
                archive.reviews.acceptedValidation(ReviewerScope(REVIEWER_ONE, OCR_REVIEW_PROMPT_VERSION), measured),
                "and not under another reviewer, which is the scope ticket 11 accepts thresholds for",
            )

            val activated = service(
                server,
                policy = OcrDecisionPolicy(
                    policyVersion = measured,
                    reviews = archive.reviews,
                ),
                reviewerRevisionIds = listOf(REVIEWER_ONE, REVIEWER_TWO),
            )
            val approved = activated.compare(
                input(page, PLAIN_AFTER, baselineRevision, PLAIN_BEFORE, reviewer = REVIEWER_TWO, policyVersion = measured),
            )

            assertEquals(ReviewerRecommendation.NEW_BETTER, approved.recommendation)
            assertEquals(
                PublicationDisposition.APPROVE,
                approved.disposition,
                "an accepted record read back from the store is what opens automatic replacement",
            )
            assertEquals(
                approved,
                archive.reviews.find(approved.fingerprint, page.sha256),
                "the approved replacement is durable, and the pending set is where a person's work is",
            )
        }
    }

    // ---- failure and absence ----

    @Test
    fun `a reviewer that does not answer leaves the baseline, says why, and read no page again`() = runBlocking {
        val document = archive.document()
        val baselineRevision = archive.publish(document.id, "existing text")
        val page = pageImage(document.id)
        // A response that promises a body and then never finishes it: one bounded timeout, no retry, and the
        // answer that never arrived is this page's outcome rather than an exception.
        val server = FakeOpenAiServer(
            listOf(
                FakeOpenAiResponse(
                    body = "",
                    stream = true,
                    declaredLength = UNFINISHED_BODY_BYTES,
                    holdMillis = HOLD_MILLIS,
                ),
            ),
        )
        try {
            val review = service(server, timeout = 100.milliseconds)
                .compare(input(page, "new text", baselineRevision, "existing text"))

            assertEquals(ImageLlmException.TIMEOUT, review.outcomeCode)
            assertEquals(ReviewerRecommendation.UNCERTAIN, review.recommendation)
            assertEquals(PublicationDisposition.PROPOSE, review.disposition, "never an implicit acceptance")
            assertTrue(review.searchable, "the baseline is still what the document publishes")
            assertEquals("existing text", assertNotNull(archive.revisions.page(baselineRevision, 0)).extractedText)
            assertEquals(
                listOf(baselineRevision),
                archive.revisions.revisions(document.id).map { it.id },
                "a failed review replays no transcription and stages no revision",
            )
            assertEquals(1, server.handledRequests, "one bounded call, not a replay of the page's reading")
            assertTrue(server.requestBodies.all { body -> body.contains(REVIEW_PROMPT_MARKER) })
            assertTrue(
                server.requestBodies.none { body -> body.contains(TRANSCRIPTION_PROMPT_MARKER) },
                "a review failure must never cause a transcription to be replayed",
            )
        } finally {
            server.close()
        }
    }

    @Test
    fun `no baseline keeps an uncertain proposal outside every retrieval path`() = runBlocking {
        val document = archive.document()
        val page = pageImage(document.id)
        withReviewer(reviewAnswer(page.unitId, "UNCERTAIN")) { server ->
            val review = service(server).compare(input(page, "name?"))

            assertEquals(ReviewerRecommendation.UNCERTAIN, review.recommendation)
            assertEquals(PublicationDisposition.PROPOSE, review.disposition)
            assertNull(review.baselineRevisionId)
            assertFalse(review.searchable, "with nothing published there is no text a retrieval path may use")
            assertEquals(
                listOf(review),
                archive.reviews.pending(document.id),
                "the proposal stays pending and unsearchable until a person decides",
            )
            assertFalse(archive.reviews.pending(document.id).single().searchable)
        }
    }

    @Test
    fun `a reading with nothing to compare against cannot be called better than the old one`() = runBlocking {
        val document = archive.document()
        val page = pageImage(document.id)
        withReviewer(reviewAnswer(page.unitId, "B_BETTER", confidence = 1.0)) { server ->
            val review = service(server).compare(input(page, "name?"))

            assertEquals(
                ReviewerRecommendation.UNCERTAIN,
                review.recommendation,
                "there is no existing text the candidate could be better than, so no preference for new text",
            )
            assertEquals(OcrComparisonService.NO_BASELINE_OUTCOME, review.outcomeCode)
            assertEquals(PublicationDisposition.PROPOSE, review.disposition)
        }
    }

    @Test
    fun `an invalid reviewer answer is uncertain rather than an acceptance`() = runBlocking {
        val document = archive.document()
        val baselineRevision = archive.publish(document.id, FIRST_TEXT)
        val page = pageImage(document.id)
        // The answer names a stretch of a reading that is not there, so it is not a judgement of this page.
        withReviewer(
            reviewAnswer(page.unitId, "B_BETTER", reasons = listOf(ReviewReasonWire("elsewhere", aSpan = 900 to 950, bSpan = 900 to 950))),
        ) { server ->
            val review = service(server).compare(input(page, "name 128", baselineRevision, FIRST_TEXT))

            assertEquals(ImageLlmException.MALFORMED_RESPONSE, review.outcomeCode)
            assertEquals(ReviewerRecommendation.UNCERTAIN, review.recommendation)
            assertEquals(PublicationDisposition.PROPOSE, review.disposition)
        }
    }

    @Test
    fun `a recommendation that points at no difference is uncertain`() = runBlocking {
        val document = archive.document()
        val baselineRevision = archive.publish(document.id, "Faktura 4711\nSumma 100")
        val page = pageImage(document.id)
        // A well-formed answer whose only reason points at a stretch both readings agree on supports nothing.
        withReviewer(
            reviewAnswer(page.unitId, "B_BETTER", reasons = listOf(ReviewReasonWire("nothing here", aSpan = 0 to 7, bSpan = 0 to 7))),
        ) { server ->
            val review = service(server).compare(input(page, "Faktura 4711\nSumma 100 betald", baselineRevision, "Faktura 4711\nSumma 100"))

            assertEquals(ReviewerRecommendation.UNCERTAIN, review.recommendation)
            assertEquals(OcrComparisonService.UNSUPPORTED_REVIEW_OUTCOME, review.outcomeCode)
            assertEquals(PublicationDisposition.PROPOSE, review.disposition)
        }
    }

    // ---- identical and empty ----

    @Test
    fun `identical non-empty text is a no-op that asks nobody`() = runBlocking {
        val document = archive.document()
        val baselineRevision = archive.publish(document.id, "same text")
        val page = pageImage(document.id)
        withReviewer(reviewAnswer(page.unitId, "B_BETTER")) { server ->
            val review = service(server).compare(input(page, "same text", baselineRevision, "same text"))

            assertEquals(PublicationDisposition.KEEP, review.disposition)
            assertEquals(OcrComparisonService.IDENTICAL_TEXT_OUTCOME, review.outcomeCode)
            assertTrue(review.searchable)
            assertEquals(0, server.handledRequests, "an identical pair needs no review call")
            assertEquals(emptyList(), archive.reviews.pending(document.id))
            assertTrue(review.appliesTo(baselineRevision, readingTextHash("same text")))
        }
    }

    @Test
    fun `an identically empty pair is not an identical pair and not a blank page`() = runBlocking {
        val document = archive.document()
        val page = pageImage(document.id)
        withReviewer(reviewAnswer(page.unitId, "UNCERTAIN")) { server ->
            val review = service(server).compare(input(page, ""))

            assertNotEquals(
                OcrComparisonService.IDENTICAL_TEXT_OUTCOME,
                review.outcomeCode,
                "two empty readings are not the no-op an identical pair is",
            )
            assertEquals(OcrComparisonService.EMPTY_PAIR_OUTCOME, review.outcomeCode)
            assertEquals(ReviewerRecommendation.UNCERTAIN, review.recommendation)
            assertEquals(PublicationDisposition.PROPOSE, review.disposition)
            assertFalse(review.searchable, "an empty page nobody verified is not searchable text")
            assertEquals(0, server.handledRequests, "nothing a comparison of two empty readings could add")
            assertTrue(DiagnosticCode.EMPTY_PAIR in review.reasons.mapNotNull { it.code.asDiagnosticCode() })
        }
    }

    // ---- reuse ----

    @Test
    fun `the same comparison is reused and a changed reviewer only recomputes the comparison`() = runBlocking {
        val document = archive.document()
        val baselineRevision = archive.publish(document.id, "name 123")
        val page = pageImage(document.id)
        withReviewer(reviewAnswer(page.unitId, "B_BETTER", reasons = listOf(NAME_REASON))) { server ->
            val comparison = service(server, reviewerRevisionIds = listOf(REVIEWER_ONE, REVIEWER_TWO))
            val first = comparison.compare(input(page, "name 128", baselineRevision, "name 123"))

            assertEquals(1, server.handledRequests)
            assertEquals(first, comparison.compare(input(page, "name 128", baselineRevision, "name 123")))
            assertEquals(1, server.handledRequests, "the stored review is reused rather than paid for again")

            val reReviewed = comparison.compare(
                input(page, "name 128", baselineRevision, "name 123", reviewer = REVIEWER_TWO),
            )
            assertEquals(2, server.handledRequests, "a changed reviewer recomputes the comparison")
            assertEquals(REVIEWER_TWO, reReviewed.reviewerRevisionId)
            assertEquals(2, archive.reviews.pending(document.id).size)

            assertTrue(
                server.requestBodies.none { body -> body.contains(TRANSCRIPTION_PROMPT_MARKER) },
                "no comparison reads a page again: a reviewer change must not redo transcription",
            )
            assertTrue(
                archive.revisions.revisions(document.id).map { it.id } == listOf(baselineRevision),
                "the comparison staged nothing at all",
            )
        }
    }

    @Test
    fun `a cached review is served with the disposition this policy decides rather than the one it stored`() =
        runBlocking {
            val document = archive.document()
            val baselineRevision = archive.publish(document.id, PLAIN_BEFORE)
            val page = pageImage(document.id)
            val measured = MEASURED_POLICY_VERSION
            withReviewer(
                reviewAnswer(page.unitId, "B_BETTER", confidence = 1.0, reasons = listOf(PLAIN_REASON)),
            ) { server ->
                archive.reviews.acceptValidation(
                    validationId = "validation-1",
                    policyVersion = measured,
                    reviewerRevisionId = REVIEWER_ONE,
                    reviewPromptVersion = OCR_REVIEW_PROMPT_VERSION,
                )
                val activated = service(
                    server,
                    policy = OcrDecisionPolicy(
                        policyVersion = measured,
                        reviews = archive.reviews,
                    ),
                )
                val comparison = input(page, PLAIN_AFTER, baselineRevision, PLAIN_BEFORE, policyVersion = measured)
                val approved = activated.compare(comparison)

                assertEquals(PublicationDisposition.APPROVE, approved.disposition)
                assertEquals(1, server.handledRequests)

                // The acceptance goes. The store deliberately has no delete — acceptance is re-stated by
                // accepting the combination again — so the row is removed here the one way a record can
                // vanish for a reader: directly, which is what a policy resolving its record from the table
                // then sees as no record at all.
                archive.database.transaction { connection ->
                    connection.createStatement().use { statement ->
                        statement.execute("DELETE FROM ocr_validation_records")
                    }
                }
                assertNull(
                    archive.reviews.acceptedValidation(ReviewerScope(REVIEWER_ONE, OCR_REVIEW_PROMPT_VERSION), measured),
                    "the acceptance this approval was decided under is gone",
                )

                // And it is *this* service — the one built while the acceptance existed, whose policy is the
                // one that approved — that is asked again, because a decision from a policy held across the
                // acceptance's life is the case an approval could have outlived its row in.
                val served = activated.compare(comparison)

                assertEquals(
                    1,
                    server.handledRequests,
                    "the stored review is what was served, so the reviewer was not asked again",
                )
                assertEquals(approved.fingerprint, served.fingerprint)
                assertEquals(
                    PublicationDisposition.PROPOSE,
                    served.disposition,
                    "an approval cannot be served from cache after the accepted record that produced it is gone",
                )
                assertEquals(
                    PublicationDisposition.APPROVE,
                    assertNotNull(archive.reviews.find(approved.fingerprint, page.sha256)).disposition,
                    "the stored review stays what was decided then; what a caller is given is what this policy decides now",
                )
            }
        }

    @Test
    fun `another policy version recomputes the comparison and reads no page`() = runBlocking {
        val document = archive.document()
        val baselineRevision = archive.publish(document.id, "name 123")
        val page = pageImage(document.id)
        withReviewer(reviewAnswer(page.unitId, "B_BETTER", reasons = listOf(NAME_REASON))) { server ->
            val pilot = service(server)
            val measured = service(server, policy = OcrDecisionPolicy(policyVersion = OCR_POLICY_VERSION + 1))

            val underPilot = pilot.compare(input(page, "name 128", baselineRevision, "name 123"))
            val underMeasured = measured.compare(
                input(page, "name 128", baselineRevision, "name 123", policyVersion = OCR_POLICY_VERSION + 1),
            )

            assertEquals(2, server.handledRequests, "a policy change is a different comparison, so it recomputes")
            assertNotEquals(underPilot.fingerprint, underMeasured.fingerprint)
            assertEquals(PublicationDisposition.PROPOSE, underMeasured.disposition)
            assertTrue(server.requestBodies.none { body -> body.contains(TRANSCRIPTION_PROMPT_MARKER) })
        }
    }

    @Test
    fun `a decision against a baseline the document has moved past is refused and does not apply`() = runBlocking {
        val document = archive.document()
        val firstRevision = archive.publish(document.id, "name 123")
        val page = pageImage(document.id)
        withReviewer(reviewAnswer(page.unitId, "B_BETTER", reasons = listOf(NAME_REASON))) { server ->
            val comparison = service(server)
            val decision = comparison.compare(input(page, "name 128", firstRevision, "name 123"))
            assertEquals(PublicationDisposition.PROPOSE, decision.disposition)

            val secondRevision = archive.publish(document.id, "name 123\nen till rad")
            assertNotEquals(firstRevision, secondRevision)
            assertEquals(secondRevision, archive.revisions.activeRevisionId(document.id))

            val refused = assertFailsWith<OcrComparisonException> {
                comparison.compare(input(page, "name 128", firstRevision, "name 123"))
            }
            assertEquals(OcrComparisonException.STALE_BASELINE, refused.code)
            assertFalse(
                decision.appliesTo(secondRevision, readingTextHash("name 123\nen till rad")),
                "a decision taken against the old text must not be applied to the new one",
            )
            assertTrue(decision.appliesTo(firstRevision, readingTextHash("name 123")))
        }
    }

    @Test
    fun `an attempt admitted under another prompt or policy version is refused`() = runBlocking {
        val document = archive.document()
        val baselineRevision = archive.publish(document.id, FIRST_TEXT)
        val page = pageImage(document.id)
        withReviewer(reviewAnswer(page.unitId, "B_BETTER")) { server ->
            val comparison = service(server)

            assertEquals(
                OcrComparisonException.REVIEW_PROMPT_MISMATCH,
                assertFailsWith<OcrComparisonException> {
                    comparison.compare(
                        input(page, "name 128", baselineRevision, FIRST_TEXT).copy(
                            reviewPromptVersion = OCR_REVIEW_PROMPT_VERSION + 1,
                        ),
                    )
                }.code,
            )
            assertEquals(
                OcrComparisonException.POLICY_VERSION_MISMATCH,
                assertFailsWith<OcrComparisonException> {
                    comparison.compare(
                        input(page, "name 128", baselineRevision, FIRST_TEXT).copy(
                            policyVersion = OCR_POLICY_VERSION + 1,
                        ),
                    )
                }.code,
            )
            assertEquals(0, server.handledRequests, "nothing was sent for an attempt this build cannot honour")
        }
    }

    @Test
    fun `a fabricated baseline naming a real active revision is refused rather than reviewed`() = runBlocking {
        val document = archive.document()
        val baselineRevision = archive.publish(document.id, "name 123")
        val page = pageImage(document.id)
        withReviewer(reviewAnswer(page.unitId, "B_BETTER", reasons = listOf(NAME_REASON))) { server ->
            val comparison = service(server)

            // The revision is real, active and holds a page — but not the text this input carries, and a
            // review of it would have been persisted as that revision's page.
            val invented = assertFailsWith<OcrComparisonException> {
                comparison.compare(input(page, "name 128", baselineRevision, "an invented reading of this page"))
            }
            assertEquals(OcrComparisonException.BASELINE_MISMATCH, invented.code)

            // The same for the no-op path: text the revision does not hold is not identical to anything.
            val identical = assertFailsWith<OcrComparisonException> {
                comparison.compare(input(page, "invented", baselineRevision, "invented"))
            }
            assertEquals(
                OcrComparisonException.BASELINE_MISMATCH,
                identical.code,
                "the revision's page is checked before the identical-text no-op, not after it",
            )

            // And a page the revision does not hold at all is no page of that revision to compare against.
            val unknown = assertFailsWith<OcrComparisonException> {
                comparison.compare(
                    input(page.copy(unitId = "page-99"), "name 128", baselineRevision, "name 123"),
                )
            }
            assertEquals(OcrComparisonException.BASELINE_PAGE_UNKNOWN, unknown.code)

            // The text is the revision's own here and the page is one it holds; only the ordinal says the
            // reading belongs to another page, which is enough for this not to be the page it names.
            val wrongOrdinal = assertFailsWith<OcrComparisonException> {
                comparison.compare(input(page.copy(ordinal = 1), "name 128", baselineRevision, "name 123"))
            }
            assertEquals(OcrComparisonException.BASELINE_MISMATCH, wrongOrdinal.code)

            assertEquals(0, server.handledRequests, "a baseline the revision does not hold is never reviewed")
            assertEquals(
                emptyList(),
                archive.reviews.pending(document.id),
                "and nothing was persisted as that revision's page",
            )
        }
    }

    @Test
    fun `a stored review is not reused once the page image is no longer the image it judged`() = runBlocking {
        val document = archive.document()
        val baselineRevision = archive.publish(document.id, "name 123")
        val page = pageImage(document.id)
        withReviewer(reviewAnswer(page.unitId, "B_BETTER", reasons = listOf(NAME_REASON))) { server ->
            val comparison = service(server)
            val first = comparison.compare(input(page, "name 128", baselineRevision, "name 123"))
            assertEquals(first, comparison.compare(input(page, "name 128", baselineRevision, "name 123")))
            assertEquals(1, server.handledRequests, "while the artifact is the one it judged, the review is reused")

            // The path holds another image now: the pixels the stored verdict was made about are gone.
            rewriteArtifact(page.imagePath)
            assertFalse(page.isIntact(), "the artifact is no longer the page image this record names")

            val second = comparison.compare(input(page, "name 128", baselineRevision, "name 123"))

            assertNotEquals(first, second, "a verdict about other pixels is not a review of this page")
            assertEquals(ImageLlmException.IMAGE_CHANGED, second.outcomeCode)
            assertEquals(1, server.handledRequests, "nothing was sent about pixels that are no longer there")
        }
    }

    // ---- the request itself ----

    @Test
    fun `the review request carries the page image and both readings without saying which side is which`() = runBlocking {
        val document = archive.document()
        val baselineRevision = archive.publish(document.id, "name 123")
        val page = pageImage(document.id)
        withReviewer(reviewAnswer(page.unitId, "B_BETTER", reasons = listOf(NAME_REASON))) { server ->
            service(server).compare(input(page, "name 128", baselineRevision, "name 123"))

            val request = Json.parseToJsonElement(server.requestBody!!).jsonObject
            assertFalse(request.containsKey("tools"), "the reviewer has no tools")
            val content = request.getValue("messages").jsonArray.single().jsonObject.getValue("content").jsonArray
            assertEquals(2, content.size, "the request carries the instructions and the page image, nothing else")
            val instructions = content[0].jsonObject.getValue("text").jsonPrimitive.content
            assertContains(instructions, READINGS_FIRST_OPEN)
            assertContains(instructions, READINGS_SECOND_OPEN)
            assertContains(instructions, "name 123")
            assertContains(instructions, "name 128")
            assertContains(
                instructions,
                "A_BETTER",
                message = "the answer the request asks for is one of its own sides",
            )
            assertContains(instructions, "B_BETTER")
            assertFalse(
                server.requestBody!!.contains("EXISTING_BETTER"),
                "the request cannot answer in the application's vocabulary, which names a side as the old one",
            )
            assertFalse(server.requestBody!!.contains("NEW_BETTER"))
            listOf(
                "TESSERACT",
                "SURYA",
                "candidate",
                "baseline",
                "engine",
                "existing",
                "new",
                "older",
                "newer",
            ).forEach { forbidden ->
                assertFalse(
                    instructions.contains(forbidden, ignoreCase = true),
                    "the request must not tell the reviewer which reading is which: '$forbidden' was sent",
                )
            }
            assertEquals(
                "name 123",
                instructions.substringAfter(READINGS_FIRST_OPEN).substringBefore(READINGS_FIRST_CLOSE).trim(),
                "reading A is the published reading and reading B the candidate, and nothing says so",
            )
            assertEquals(
                "name 128",
                instructions.substringAfter(READINGS_SECOND_OPEN).substringBefore(READINGS_SECOND_CLOSE).trim(),
            )
            val url = content[1].jsonObject.getValue("image_url").jsonObject.getValue("url").jsonPrimitive.content
            assertTrue(url.startsWith("data:image/png;base64,"), "the page's own image is what the reviewer sees")
            assertTrue(
                java.util.Base64.getDecoder().decode(url.substringAfter("base64,"))
                    .contentEquals(Files.readAllBytes(page.imagePath)),
            )
        }
    }

    @Test
    fun `a side answer is mapped to the application's vocabulary in both directions`() {
        assertEquals(
            ReviewerRecommendation.EXISTING_BETTER,
            recommendationOf(ReviewerSide.A_BETTER, baselineIsSideA = true),
        )
        assertEquals(
            ReviewerRecommendation.NEW_BETTER,
            recommendationOf(ReviewerSide.B_BETTER, baselineIsSideA = true),
        )
        assertEquals(
            ReviewerRecommendation.NEW_BETTER,
            recommendationOf(ReviewerSide.A_BETTER, baselineIsSideA = false),
            "the same side answer means the other thing when the baseline went out as the other side",
        )
        assertEquals(
            ReviewerRecommendation.EXISTING_BETTER,
            recommendationOf(ReviewerSide.B_BETTER, baselineIsSideA = false),
        )
        listOf(true, false).forEach { baselineIsSideA ->
            assertEquals(
                ReviewerRecommendation.UNCERTAIN,
                recommendationOf(ReviewerSide.UNCERTAIN, baselineIsSideA = baselineIsSideA),
                "an answer about neither side is uncertain whichever side the baseline went out as",
            )
        }
    }

    @Test
    fun `an answer about the baseline's side is the published text being better`() = runBlocking {
        val document = archive.document()
        val baselineRevision = archive.publish(document.id, FIRST_TEXT)
        val page = pageImage(document.id)
        // The baseline goes out as side A, so this answer says the reading that went out as A is the better
        // one — and the service, which knows A is the baseline, is what turns that into EXISTING_BETTER.
        withReviewer(reviewAnswer(page.unitId, "A_BETTER", confidence = 0.9, reasons = listOf(NAME_REASON))) { server ->
            val review = service(server).compare(input(page, "name 128", baselineRevision, FIRST_TEXT))

            assertEquals(ReviewerRecommendation.EXISTING_BETTER, review.recommendation)
            assertEquals(PublicationDisposition.PROPOSE, review.disposition, "the difference still needs a person")
            assertNull(review.outcomeCode, "the reviewer answered, so no outcome code stands in for it")
        }
    }

    @Test
    fun `an answer in the application's own vocabulary is not the review schema`() = runBlocking {
        val document = archive.document()
        val baselineRevision = archive.publish(document.id, FIRST_TEXT)
        val page = pageImage(document.id)
        withReviewer(reviewAnswer(page.unitId, "EXISTING_BETTER", reasons = listOf(NAME_REASON))) { server ->
            val review = service(server).compare(input(page, "name 128", baselineRevision, FIRST_TEXT))

            assertEquals(
                ImageLlmException.MALFORMED_RESPONSE,
                review.outcomeCode,
                "the request asks about sides, so an answer naming a side as the published one is not its schema",
            )
            assertEquals(ReviewerRecommendation.UNCERTAIN, review.recommendation)
            assertEquals(PublicationDisposition.PROPOSE, review.disposition)
        }
    }

    @Test
    fun `a reading that spells a prompt placeholder is inserted as itself`() = runBlocking {
        val document = archive.document()
        val baselineText = "{readingB} name 123"
        val baselineRevision = archive.publish(document.id, baselineText)
        val page = pageImage(document.id)
        withReviewer(reviewAnswer(page.unitId, "B_BETTER", reasons = listOf(NAME_REASON))) { server ->
            service(server).compare(input(page, "name 128", baselineRevision, baselineText))

            val instructions = Json.parseToJsonElement(server.requestBody!!).jsonObject
                .getValue("messages").jsonArray.single().jsonObject
                .getValue("content").jsonArray.first().jsonObject.getValue("text").jsonPrimitive.content
            assertEquals(
                baselineText,
                instructions.substringAfter(READINGS_FIRST_OPEN).substringBefore(READINGS_FIRST_CLOSE).trim(),
                "a reading that spells a placeholder is page text, and no other reading may be put inside it",
            )
            assertEquals(
                "name 128",
                instructions.substringAfter(READINGS_SECOND_OPEN).substringBefore(READINGS_SECOND_CLOSE).trim(),
            )
        }
    }

    // ---- helpers ----

    private fun service(
        server: FakeOpenAiServer,
        policy: OcrDecisionPolicy = OcrDecisionPolicy(),
        reviewerRevisionIds: List<String> = listOf(REVIEWER_ONE),
        timeout: Duration = DEFAULT_TIMEOUT,
    ): OcrComparisonService {
        val revisions = reviewerRevisionIds.associateWith { id -> reviewerRevision(server.url, id) }
        return OcrComparisonService(
            revisions = archive.revisions,
            reviews = archive.reviews,
            revisionOf = { id -> revisions[id] },
            policy = policy,
            lookup = { null },
            permits = null,
            timeout = timeout,
            retryPolicy = RetryPolicy(maxRetries = 1, retryDelay = {}),
        )
    }

    private fun reviewerRevision(endpoint: String, revisionId: String): OcrProfileRevision = OcrProfileRevision(
        revisionId = revisionId,
        profileId = "profile-reviewer",
        sequence = 1,
        provider = LlmProvider.OPENAI_COMPATIBLE,
        model = "vision-reviewer",
        contextWindow = 32_000,
        maxOutputTokens = 1_024,
        inputPricePerMillion = 1.0,
        outputPricePerMillion = 1.0,
        createdAt = "2026-09-30T00:00:00.000Z",
        endpoint = endpoint,
    )

    private fun input(
        page: PageImage,
        candidateText: String,
        baselineRevisionId: String? = null,
        baselineText: String? = null,
        reviewer: String = REVIEWER_ONE,
        policyVersion: Int = OCR_POLICY_VERSION,
    ): PageComparisonInput = PageComparisonInput(
        page = page,
        candidateText = candidateText,
        reviewProfileRevisionId = reviewer,
        baselineRevisionId = baselineRevisionId,
        baselineText = baselineText,
        baselineTextHash = baselineText?.let { text -> readingTextHash(text) },
        policyVersion = policyVersion,
    )

    /** Runs [block] against a scripted loopback reviewer, closing the server afterwards. */
    private suspend fun withReviewer(
        answer: String,
        block: suspend (FakeOpenAiServer) -> Unit,
    ) {
        val server = FakeOpenAiServer(listOf(FakeOpenAiResponse(body = openAiReviewEnvelope(answer))))
        try {
            block(server)
        } finally {
            server.close()
        }
    }

    /**
     * One page image over a written PNG, measured the way a producer measures it.
     *
     * It is named the way the document's published revision names that page, because a comparison's baseline
     * is verified against the page its revision actually holds: a page image named anything else is a page
     * the revision does not have.
     */
    private fun pageImage(documentId: DocumentId): PageImage {
        val reference = "page-%06d.png".format(pages.incrementAndGet())
        val root = Files.createDirectories(directory.resolve(reference))
        val raster = BufferedImage(400, 200, BufferedImage.TYPE_INT_RGB).also { image ->
            val graphics = image.createGraphics()
            try {
                graphics.color = Color.WHITE
                graphics.fillRect(0, 0, image.width, image.height)
                graphics.color = Color.BLACK
                graphics.drawString("Faktura 4711", 8, 24)
            } finally {
                graphics.dispose()
            }
        }
        check(ImageIO.write(raster, "png", root.resolve(reference).toFile())) { "no PNG writer is available" }
        return PageImage.ofFile(
            documentId = documentId,
            unitId = archive.publishedUnitId(documentId) ?: PAGE_UNIT_ID,
            ordinal = 0,
            imageRoot = root,
            imageReference = reference,
            artifactRoot = archive.artifacts,
            renderDpi = 300,
            rotationDegrees = 0,
        )
    }

    /** Rewrites the artifact at [path] with other pixels, so the record's hash no longer describes it. */
    private fun rewriteArtifact(path: Path) {
        val raster = BufferedImage(320, 160, BufferedImage.TYPE_INT_RGB).also { image ->
            val graphics = image.createGraphics()
            try {
                graphics.color = Color.BLACK
                graphics.fillRect(0, 0, image.width, image.height)
            } finally {
                graphics.dispose()
            }
        }
        check(ImageIO.write(raster, "png", path.toFile())) { "no PNG writer is available" }
    }

    /** One OpenAI-compatible envelope whose single answer is [content]. */
    private fun openAiReviewEnvelope(content: String): String = buildJsonObject {
        put("model", "vision-reviewer-2026-02-01")
        putJsonArray("choices") {
            addJsonObject {
                put("finish_reason", "stop")
                putJsonObject("message") {
                    put("role", "assistant")
                    put("content", content)
                }
            }
        }
    }.toString()

    private fun reviewAnswer(
        unitId: String,
        recommendation: String,
        confidence: Double? = null,
        reasons: List<ReviewReasonWire> = emptyList(),
    ): String = buildJsonObject {
        put("unitId", unitId)
        put("ordinal", 0)
        put("recommendation", recommendation)
        confidence?.let { put("confidence", it) }
        putJsonArray("reasons") {
            reasons.forEach { reason ->
                addJsonObject {
                    put("explanation", reason.explanation)
                    reason.aSpan?.let { (start, end) ->
                        put("aStart", start)
                        put("aEnd", end)
                    }
                    reason.bSpan?.let { (start, end) ->
                        put("bStart", start)
                        put("bEnd", end)
                    }
                }
            }
        }
    }.toString()

    private fun String?.asDiagnosticCode(): DiagnosticCode? =
        this?.let { code -> DiagnosticCode.entries.firstOrNull { it.name == code } }

    private data class ReviewReasonWire(
        val explanation: String,
        val aSpan: Pair<Int, Int>? = null,
        val bSpan: Pair<Int, Int>? = null,
    )

    private companion object {
        const val REVIEWER_ONE = "reviewer-1"
        const val REVIEWER_TWO = "reviewer-2"
        const val PAGE_UNIT_ID = "page-1"
        const val FIRST_TEXT = "name 123"
        const val PLAIN_BEFORE = "kvittot ar betald"
        const val PLAIN_AFTER = "kvittot ar betalad"
        val NAME_REASON = ReviewReasonWire("the number in the name differs", aSpan = 5 to 8, bSpan = 5 to 8)

        /** A reason about the one word the plain pair differs in: no name, number, date or negation. */
        val PLAIN_REASON = ReviewReasonWire("the last word differs", aSpan = 8 to 17, bSpan = 8 to 17)

        /** A policy version past the pilot's: a caller can pass one, and this build accepts nothing under it. */
        const val MEASURED_POLICY_VERSION: Int = OCR_POLICY_VERSION + 1
        val DEFAULT_TIMEOUT: Duration = 10.seconds
        const val UNFINISHED_BODY_BYTES = 4_096L
        const val HOLD_MILLIS = 2_000L

        /** A phrase only the transcription prompt has, so a request body can be told apart from a review's. */
        const val TRANSCRIPTION_PROMPT_MARKER = "Transcribe exactly what is on the page"

        /** A phrase only the review prompt has. */
        const val REVIEW_PROMPT_MARKER = "You compare two readings of one page image"

        const val READINGS_FIRST_OPEN = "----- BEGIN READING A -----"
        const val READINGS_FIRST_CLOSE = "----- END READING A -----"
        const val READINGS_SECOND_OPEN = "----- BEGIN READING B -----"
        const val READINGS_SECOND_CLOSE = "----- END READING B -----"
    }
}

/**
 * A real archive, because the rules under test are about what a store reads back and what a comparison
 * leaves behind.
 */
private class ComparisonArchive(directory: Path) : AutoCloseable {

    val database: Database = Database(directory.resolve("infoscry.db"))

    private val collections: CollectionStore = CollectionStore(database)

    private val documents: DocumentStore = DocumentStore(database)

    private val content: ContentStore = ContentStore(database)

    val revisions: DocumentRevisionStore = DocumentRevisionStore(database, content)

    val reviews: OcrReviewStore = OcrReviewStore(database)

    val artifacts: Path = Files.createDirectories(directory.resolve("artifacts"))

    private var documentsCreated = 0

    init {
        SchemaMigrator(database).migrate()
    }

    /** A document of this archive that a test can publish text for. */
    fun document(): Document {
        val collection = collections.create("Rescan ${documentsCreated++}")
        return documents.insert(
            Document(
                id = DocumentId("doc-$documentsCreated"),
                collectionId = CollectionId(collection.id.value),
                sha256 = "b".repeat(64),
                mediaType = "application/pdf",
                originalFilename = "rescan.json",
                sourcePath = "/tmp/rescan.pdf",
                sizeBytes = 42,
                status = DocumentStatus.COMPLETE,
                createdAt = Instants.now(),
                updatedAt = Instants.now(),
            ),
        )
    }

    /**
     * Publishes [text] as the document's only page the way the import path leaves its text: the unit is
     * committed and the reading it holds becomes the document's published revision.
     */
    fun publish(documentId: DocumentId, text: String): String {
        content.commitExtractedUnit(
            documentId = documentId,
            fingerprint = ExtractionFingerprint.of(HASH, ExtractionSettings(ocrLanguages = "eng")),
            key = "page-1",
            ordinal = 0,
            draft = ContentUnitDraft(
                locator = SourceLocation.TextLines(1, 1),
                extractedText = text,
                searchText = text,
                method = ExtractionMethod.OCR,
            ),
            artifactRoot = artifacts,
        )
        return checkNotNull(revisions.recordPublishedContent(documentId, "TEST_PUBLICATION")) {
            "a document with committed text becomes a published revision"
        }
    }

    /**
     * The unit id the document's published revision gives its first page, or null when it publishes nothing.
     *
     * A comparison's baseline is the revision's own page, so a page image of a document that has published
     * text is named by the unit id that revision holds for it.
     */
    fun publishedUnitId(documentId: DocumentId): String? = revisions.activeRevisionId(documentId)
        ?.let { revisionId -> revisions.page(revisionId, 0)?.unitId?.value }

    /** Stages [text] as an unpublished candidate of [documentId], the way a rescan leaves one. */
    fun stageCandidate(documentId: DocumentId, parentRevisionId: String?, text: String): String {
        val revisionId = revisions.openCandidate(documentId, parentRevisionId, "TEST_RESCAN")
        revisions.appendPage(
            revisionId,
            RevisionPageDraft(
                ordinal = 0,
                unitId = ContentUnitId.new(),
                locator = SourceLocation.TextLines(1, 1),
                extractedText = text,
                searchText = text,
                extractionMethod = ExtractionMethod.OCR,
            ),
        )
        return revisionId
    }

    override fun close() {
        database.close()
    }

    private companion object {
        const val HASH = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    }
}
