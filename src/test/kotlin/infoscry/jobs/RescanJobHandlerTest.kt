package infoscry.jobs

import infoscry.AppContext
import infoscry.chunk.Chunker
import infoscry.chunk.WhitespaceTokenCounter
import infoscry.document.PageReviewer
import infoscry.document.RescanOverrides
import infoscry.document.RescanRefusalException
import infoscry.document.RescanService
import infoscry.domain.CollectionId
import infoscry.domain.ContentUnitId
import infoscry.domain.DocumentId
import infoscry.domain.DocumentStatus
import infoscry.domain.ExtractionMethod
import infoscry.domain.JobId
import infoscry.domain.JobState
import infoscry.domain.JobType
import infoscry.domain.SourceLocation
import infoscry.embedding.DocumentEmbedder
import infoscry.embedding.TestDocumentEmbedder
import infoscry.extract.ContentUnitDraft
import infoscry.extract.DocumentExtractor
import infoscry.extract.ExtractionEvent
import infoscry.extract.ExtractionInput
import infoscry.extract.ExtractionSettings
import infoscry.extract.OCR_FAILED_CODE
import infoscry.llm.LlmProvider
import infoscry.ocr.CollectionOcrSettings
import infoscry.ocr.ExternalDispatchPermitRequest
import infoscry.ocr.ImageLlmException
import infoscry.ocr.OcrDispatchAuthority
import infoscry.ocr.OcrDispatchStage
import infoscry.ocr.OcrEndpointScope
import infoscry.ocr.OcrEngine
import infoscry.ocr.OcrExternalOwner
import infoscry.ocr.OcrImportMode
import infoscry.ocr.OcrOperation
import infoscry.ocr.OcrOperationStage
import infoscry.ocr.OcrPageResult
import infoscry.ocr.OcrProfileRevision
import infoscry.ocr.OcrSettingsSnapshot
import infoscry.ocr.PageComparisonInput
import infoscry.ocr.PageDispatchIdentity
import infoscry.ocr.PageImage
import infoscry.ocr.PageOcrEngine
import infoscry.ocr.PageReview
import infoscry.ocr.PublicationDisposition
import infoscry.ocr.ReviewReason
import infoscry.ocr.ReasonOrigin
import infoscry.ocr.ReviewerRecommendation
import infoscry.storage.OcrAttemptInProgressException
import infoscry.storage.OcrOperationStore
import infoscry.storage.PageApproval
import infoscry.storage.RevisionState
import infoscry.storage.StaleRescanPreviewException
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * What one rescan attempt does to a document that is already published.
 *
 * The tests drive the real stores, the real candidate revision, the real publication protocol and the real
 * crash-resume rules on a temporary archive; what is stood in for is the page-reading engine and the
 * reviewer, because those are the two things this feature sends pages to. Everything these tests assert is
 * therefore about the archive's own promises: what is committed per page, what may be published, what a
 * failed or cancelled attempt leaves behind, and exactly which pages were sent where.
 */
class RescanJobHandlerTest {

    @Test
    fun `a rescan proposes a differing reading without replacing the published text`() {
        withHarness { harness ->
            val picture = harness.importPicture(text = "first reading")
            val engine = FakePageEngine(readings = listOf("second reading"))

            // A review profile is configured and its external scope approved, so the reviewer really runs.
            val result = harness.rescan(
                picture = picture,
                engine = engine,
                reviewer = RecordingReviewer(NEW_BETTER),
                reviewRevisionId = harness.reviewRevisionId,
                externalPageLimit = 1,
            )

            // The reading is staged as a proposal: pilot mode replaces nothing without a person, even when the
            // reviewer recommends the new text.
            val operation = result.operation
            assertEquals(OcrOperationStage.COMPLETE, operation.stage)
            assertEquals(REVISION_AWAITING_REVIEW_CODE, operation.errorCode)
            assertEquals(1, operation.pendingReviewCount)
            assertNotNull(operation.candidateRevisionId)
            assertEquals(listOf(PageApproval.PENDING), result.candidateApprovals)
            // A page image is named with the baseline revision's *own* unit id, not with an extraction key: the
            // comparison refuses a page that names another identity than the revision holds, and the staged page
            // therefore keeps the identity the document already cites the page by.
            assertEquals(picture.unitId, result.candidatePage?.unitId?.value)

            // Nothing published moved: the document still serves its own text and its own index rows.
            assertEquals("first reading", result.publishedText)
            assertEquals(1, result.indexedChunks)
            assertEquals(picture.baselineRevisionId, result.activeRevisionId)
            assertEquals(1, engine.calls)
        }
    }

    @Test
    fun `a rescan without a review profile approves a differing reading so the replacement publishes`() {
        withHarness { harness ->
            val picture = harness.importPicture(text = "first reading")
            val engine = FakePageEngine(readings = listOf("second reading"))

            // No review profile is configured, so no reviewer can ever approve the page. The new reading is
            // approved automatically: nothing is left pending, and the replacement can be published.
            val result = harness.rescan(picture, engine = engine, reviewer = null)

            // Nothing is pending, so the attempt publishes the whole approved revision itself.
            assertEquals(OcrOperationStage.COMPLETE, result.operation.stage)
            assertNull(result.operation.errorCode, "the approved replacement was refused: ${result.operation.errorCode}")
            assertEquals(0, result.operation.pendingReviewCount)
            assertEquals(listOf(PageApproval.APPROVED), result.candidateApprovals)
            assertEquals("second reading", result.publishedText)
            assertNotEqualsId(picture.baselineRevisionId, assertNotNull(result.activeRevisionId))
        }
    }

    @Test
    fun `an explicit rescan of a format without page images is refused with the clear reason`() {
        withHarness { harness ->
            val documentId = harness.importTextDocument()

            val refusal = harness.previewRefusal(documentId)

            val exception = assertIs<RescanRefusalException>(
                refusal,
                "a rescan of a format without page images was not refused with the project's own refusal: $refusal",
            )
            assertEquals(RescanRefusalException.PAGE_IMAGES_UNSUPPORTED, exception.code)
            assertContains(
                exception.message.orEmpty(),
                "text/plain has no page images to read, so it cannot be read again from them",
                message = "the refusal must keep the clear reason a person acts on",
            )
            // The refusal is about the format, not about this document's reading: the ordinary extraction the
            // import published is untouched by the rescan that could not be asked for.
            AppContext.open(harness.archiveDir).use { context ->
                val document = assertNotNull(context.documents.get(documentId))
                assertEquals("text/plain", document.mediaType)
                assertEquals(DocumentStatus.COMPLETE, document.status)
            }
        }
    }

    @Test
    fun `a resume or an approval while an attempt owns the operation starts no second attempt`() {
        withHarness { harness ->
            val picture = harness.importPicture(text = "first reading")
            // Admission alone: the operation names the attempt its admission created, and nothing has run it.
            val operation = harness.admitOnly(picture, reviewRevisionId = harness.reviewRevisionId)
            val jobs = harness.rescanJobCount()

            val resumed = harness.resumeRefusal(picture, operation.operationId)
            // An approval of a scope an attempt is already working in is that attempt's approval: the running
            // reading resolves its allowance again on every page. What it may not do is start a second attempt.
            harness.approveThroughService(picture, operation.operationId, maxDistinctPages = 2)

            assertTrue(resumed is OcrAttemptInProgressException, "a resume started a second attempt: $resumed")
            assertEquals(2, harness.approvedScopeOf(picture, operation.operationId), "the approval was not recorded")
            assertEquals(jobs, harness.rescanJobCount(), "one reading was given a second attempt")
        }
    }

    @Test
    fun `an approval of an operation that waits starts exactly one attempt`() {
        withHarness { harness ->
            val picture = harness.importPicture(text = "first reading")
            // No external page is allowed, so the attempt pauses before it reviews anything.
            val paused = harness.rescan(
                picture = picture,
                engine = FakePageEngine(readings = listOf("second reading")),
                reviewer = RecordingReviewer(),
                reviewRevisionId = harness.reviewRevisionId,
                externalPageLimit = 0,
            )
            assertEquals(OcrOperationStage.AWAITING_APPROVAL, paused.operation.stage)
            val jobs = harness.rescanJobCount()

            val approved = harness.approveThroughService(picture, paused.operation.operationId, maxDistinctPages = 2)

            assertEquals(OcrOperationStage.PREFLIGHT, approved.stage)
            assertNotNull(approved.jobId, "the approval has to name the attempt it started")
            assertEquals(jobs + 1, harness.rescanJobCount(), "an approval owes exactly one new attempt")
        }
    }

    @Test
    fun `a settings change between preview and admission is refused for every field the person was shown`() {
        withHarness { harness ->
            val picture = harness.importPicture(text = "first reading")
            val changes = listOf<Pair<String, (CollectionOcrSettings) -> CollectionOcrSettings>>(
                "engine" to { settings -> settings.copy(engine = OcrEngine.TESSERACT) },
                "mode" to { settings -> settings.copy(importMode = OcrImportMode.FILL_MISSING) },
                "language" to { settings -> settings.copy(language = "swe") },
                "reviewer" to { settings -> settings.copy(reviewProfileId = null) },
                "allowance" to { settings -> settings.copy(externalPageLimit = 4) },
            )
            changes.forEach { (field, change) ->
                val previewId = harness.previewFor(picture, reviewRevisionId = harness.reviewRevisionId)
                harness.updateSelection(change)

                val refusal = harness.admitRefusal(picture, previewId, requestId = "stale-$field")

                assertTrue(
                    refusal is StaleRescanPreviewException,
                    "a change of the $field was admitted against the preview it invalidated: $refusal",
                )
            }
            // The same preview, admitted against the settings it was taken with, is still admitted.
            val previewId = harness.previewFor(picture, reviewRevisionId = harness.reviewRevisionId)
            assertNull(harness.admitRefusal(picture, previewId, requestId = "unchanged"))
        }
    }

    @Test
    fun `a preview taken with a chosen import mode is admitted while the collection default is unchanged`() {
        withHarness { harness ->
            val picture = harness.importPicture(text = "first reading")
            val previewId = harness.previewFor(
                picture,
                overrides = RescanOverrides(importMode = OcrImportMode.FILL_MISSING),
            )

            assertNull(
                harness.admitRefusal(picture, previewId, requestId = "chosen-mode"),
                "a choice made for this scan was treated as the collection having changed",
            )
        }
    }

    @Test
    fun `a preview that chooses the LLM engine for one scan is admitted while the collection engine is unchanged`() {
        withHarness { harness ->
            val picture = harness.importPicture(text = "first reading")
            val previewId = harness.previewFor(
                picture,
                overrides = RescanOverrides(
                    engine = OcrEngine.LLM,
                    transcriptionProfileId = harness.reviewProfileId,
                ),
            )

            assertNull(
                harness.admitRefusal(picture, previewId, requestId = "chosen-llm"),
                "a per-scan engine choice was treated as the collection's engine having changed",
            )
        }
    }

    @Test
    fun `a preview with a chosen engine is still refused when a setting it did not choose has changed`() {
        withHarness { harness ->
            val picture = harness.importPicture(text = "first reading")
            val previewId = harness.previewFor(
                picture,
                overrides = RescanOverrides(
                    engine = OcrEngine.LLM,
                    transcriptionProfileId = harness.reviewProfileId,
                ),
            )
            harness.updateSelection { settings -> settings.copy(importMode = OcrImportMode.FILL_MISSING) }

            val refusal = harness.admitRefusal(picture, previewId, requestId = "drift-mode")

            assertTrue(
                refusal is StaleRescanPreviewException,
                "a change of the import mode the preview did not choose was admitted: $refusal",
            )
        }
    }

    @Test
    fun `two admissions of one document with different request ids do not both own it`() {
        withHarness { harness ->
            val picture = harness.importPicture(text = "first reading")
            harness.admitOnly(picture)

            val second = harness.admitRefusal(picture, requestId = "another-request")

            assertTrue(
                second is infoscry.storage.OcrOperationConflictException,
                "a second reading of one document was admitted: $second",
            )
        }
    }

    @Test
    fun `a second attempt reuses the page it already read instead of paying for it again`() {
        withHarness { harness ->
            val picture = harness.importPicture(text = "first reading")
            val engine = FakePageEngine(readings = listOf("second reading"))

            val first = harness.rescan(picture, engine = engine, reviewer = fakeReviewer(NEW_BETTER))
            assertEquals(1, engine.calls)

            // A resumed attempt continues the same operation: the page's pixels and its review are already
            // durable, so the engine is not asked again.
            val second = harness.resumeRescan(first.operation.operationId, engine, picture)

            assertEquals(1, engine.calls, "a committed page was read a second time")
            assertEquals(
                first.operation.candidateRevisionId,
                second.operation.candidateRevisionId,
                "a resume stages into the candidate the operation already records",
            )
        }
    }

    @Test
    fun `an approved proposal is published as a whole revision and replaces the searchable text`() {
        withHarness { harness ->
            val picture = harness.importPicture(text = "first reading")
            val engine = FakePageEngine(readings = listOf("second reading"))
            val staged = harness.rescan(
                picture = picture,
                engine = engine,
                reviewer = RecordingReviewer(NEW_BETTER),
                reviewRevisionId = harness.reviewRevisionId,
                externalPageLimit = 1,
            )
            val page = assertNotNull(staged.candidatePage)

            val decided = harness.decide(
                picture = picture,
                operation = staged.operation,
                page = page,
                choice = infoscry.document.ReviewChoice.USE_NEW,
            )
            assertEquals(1, decided.applied.size)

            val published = harness.publish(picture, decided.operation)
            assertEquals(OcrOperationStage.COMPLETE, published.operation.stage)
            assertNull(published.errorCode)

            assertEquals("second reading", harness.publishedTextOf(picture))
            assertEquals(1, harness.indexedChunksOf(picture))
            assertNotEqualsId(picture.baselineRevisionId, harness.activeRevisionOf(picture))
        }
    }

    @Test
    fun `a reading that keeps the page approved publishes unchanged text`() {
        withHarness { harness ->
            val picture = harness.importPicture(text = "first reading")
            // The engine reads the same text the page already carries: nothing differs, so nothing has to be
            // decided and the revision is complete on its own.
            val engine = FakePageEngine(readings = listOf("first reading"))

            val result = harness.rescan(picture, engine = engine, reviewer = fakeReviewer(NEW_BETTER))

            assertEquals(OcrOperationStage.COMPLETE, result.operation.stage)
            assertEquals(0, result.operation.pendingReviewCount)
            assertEquals("first reading", harness.publishedTextOf(picture))
        }
    }

    @Test
    fun `a review refusal pauses the operation before a page is sent and leaves the baseline alone`() {
        withHarness { harness ->
            val picture = harness.importPicture(text = "first reading")
            val engine = FakePageEngine(readings = listOf("second reading"))
            val reviewer = RecordingReviewer(NEW_BETTER)

            // The collection allows no external page, and the reviewer is external: the review of local Surya
            // output would leave this machine, so it may not happen at all.
            val result = harness.rescan(
                picture = picture,
                engine = engine,
                reviewer = reviewer,
                reviewRevisionId = harness.reviewRevisionId,
                externalPageLimit = 0,
            )

            assertEquals(0, reviewer.dispatches, "a page was sent before its scope was approved")
            assertEquals(OcrOperationStage.AWAITING_APPROVAL, result.operation.stage)
            assertEquals(0, result.operation.external.distinctPages)
            assertNull(result.candidatePage, "no page may be staged from a scope nobody approved")
            assertEquals("first reading", result.publishedText)
        }
    }

    @Test
    fun `an approved scope lets the review happen and counts calls apart from pages`() {
        withHarness { harness ->
            val picture = harness.importPicture(text = "first reading")
            val engine = FakePageEngine(readings = listOf("second reading"))
            val reviewer = RecordingReviewer(NEW_BETTER)

            val result = harness.rescan(
                picture = picture,
                engine = engine,
                reviewer = reviewer,
                reviewRevisionId = harness.reviewRevisionId,
                externalPageLimit = 1,
            )

            assertEquals(1, reviewer.dispatches, "the approved page was not reviewed")
            // The allowed page left once and was reviewed once: one distinct page, and the call counters show
            // what was paid for.
            assertEquals(1, result.operation.external.distinctPages)
            assertEquals(1, result.operation.external.calls)
        }
    }

    @Test
    fun `an unreachable provider fails the operation and keeps the published reading`() {
        withHarness { harness ->
            val picture = harness.importPicture(text = "first reading")
            val engine = FakePageEngine(failWith = ImageLlmException.PROVIDER_UNAVAILABLE)

            val result = harness.rescan(picture, engine = engine, reviewer = null)

            assertEquals(OcrOperationStage.FAILED, result.operation.stage)
            assertEquals(ImageLlmException.PROVIDER_UNAVAILABLE, result.operation.errorCode)
            assertEquals("first reading", harness.publishedTextOf(picture))
            assertEquals(picture.baselineRevisionId, harness.activeRevisionOf(picture))
        }
    }

    @Test
    fun `an absent embedding model fails before a page is read`() {
        withHarness { harness ->
            val picture = harness.importPicture(text = "first reading")
            val engine = FakePageEngine(readings = listOf("second reading"))

            val result = harness.rescan(picture, engine = engine, reviewer = null, embedder = null)

            assertEquals(OcrOperationStage.FAILED, result.operation.stage)
            assertEquals("EMBEDDING_UNAVAILABLE", result.operation.errorCode)
            assertEquals(0, engine.calls, "a rescan that cannot publish may not read pages first")
            assertEquals("first reading", harness.publishedTextOf(picture))
        }
    }

    @Test
    fun `a moved original changes nothing and a managed copy that changed fails without being read`() {
        withHarness { harness ->
            val picture = harness.importPicture(text = "first reading")
            // The reading matches what is published, so this attempt is a no-op that leaves the document
            // released: the subject here is the managed copy, not the review backlog the other tests cover.
            val engine = FakePageEngine(readings = listOf("first reading"))
            // The user's own file is moved away after the import. A rescan reads the archive's managed copy, so
            // nothing about it depends on where the original is — which is the whole point of a managed copy.
            Files.move(harness.sourceFileOf(picture), harness.sourceFileOf(picture).resolveSibling("moved.png"))

            val moved = harness.rescan(picture, engine = engine, reviewer = fakeReviewer(NEW_BETTER))
            assertEquals(1, engine.calls)
            assertEquals("first reading", moved.publishedText)

            // The managed copy is then replaced by other bytes. Every reading would be attributed to bytes the
            // archive never recorded, so this is a safe failure and not a reading of the new file.
            val resumed = harness.resumeAfterCorrupting(picture, engine)
            assertEquals(OcrOperationStage.FAILED, resumed.operation.stage)
            assertEquals("RESCAN_MANAGED_COPY_CHANGED", resumed.operation.errorCode)
            assertEquals("first reading", resumed.publishedText)
        }
    }

    @Test
    fun `a cancellation during the first page leaves no candidate page and the published text intact`() {
        withHarness { harness ->
            val picture = harness.importPicture(text = "first reading")
            val result = harness.rescan(
                picture = picture,
                // The engine cancels the job while it is reading, which is the window a cancellation request
                // has to be honoured in.
                engine = FakePageEngine(readings = listOf("second reading")),
                reviewer = null,
                beforePageCommit = { context, jobId -> context.jobs.cancel(jobId) },
            )

            assertEquals(OcrOperationStage.CANCELLED, result.operation.stage)
            assertNull(result.candidatePage, "a cancelled attempt may not commit a page it was stopped in")
            assertEquals("first reading", result.publishedText)
            assertEquals("first reading", result.activeRevisionId?.let { revisionId -> result.publishedText })
            assertEquals(1, result.indexedChunks)
        }
    }

    @Test
    fun `a collection deleted while a page is being read commits nothing`() {
        withHarness { harness ->
            val picture = harness.importPicture(text = "first reading")
            val result = harness.rescan(
                picture = picture,
                engine = FakePageEngine(readings = listOf("second reading")),
                reviewer = null,
                beforePageCommit = { context, _ ->
                    // The deletion is admitted while the page is being read: the commit that follows must not
                    // stage anything, and nothing may resurrect the document.
                    context.collectionService.requestDeletion(CollectionId("default"), "Default")
                },
            )

            // Admitting a deletion cancels the collection's jobs durably, so the attempt stops at its next
            // stage — the page it had just read is never committed, nothing is published, and the deletion
            // machine is what owns the document from here on.
            assertTrue(
                result.failure is kotlinx.coroutines.CancellationException,
                "expected the attempt to stop on the deletion, was ${result.failure}",
            )
            assertEquals(OcrOperationStage.CANCELLED, result.operation.stage)
            assertEquals(1, result.engine?.calls ?: 0)
            // The attempt committed nothing, and the document is the deletion's from here on: no revision of it
            // is published and nothing it read survives as a candidate.
            assertNull(result.candidatePage)
            assertNull(result.activeRevisionId, "the deleted document has no active reading")
        }
    }

    private fun assertNotEqualsId(first: String, second: String) {
        assertTrue(first != second, "the active revision did not move: $first")
    }

    private fun withHarness(block: (RescanHarness) -> Unit) {
        val directory = Files.createTempDirectory("infoscry-rescan")
        try {
            RescanHarness(directory).use(block)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    internal companion object {
        /** The code the publication refuses a revision whose pages are not all approved with. */
        const val REVISION_AWAITING_REVIEW_CODE = "REVISION_AWAITING_REVIEW"

        /** The immutable reviewer revision the tests dispatch through: an external destination. */
        const val REVIEW_REVISION = "review-revision-1"
    }
}

/** The interface a test's reviewer is handed, so it can be told what to answer and records dispatches. */
private val NEW_BETTER = ReviewerRecommendation.NEW_BETTER

private fun fakeReviewer(recommendation: ReviewerRecommendation): PageReviewer = RecordingReviewer(recommendation)

/** A reviewer that answers with a fixed recommendation and records what it was asked. */
internal class RecordingReviewer(
    private val recommendation: ReviewerRecommendation = ReviewerRecommendation.NEW_BETTER,
) : PageReviewer {

    /** How many review calls were actually dispatched, which is what an allowance bounds. */
    var dispatches: Int = 0
        private set

    private var authority: OcrDispatchAuthority? = null

    /** The authority this reviewer dispatches through, exactly as an image client would ask it. */
    fun dispatchingThrough(authority: OcrDispatchAuthority?) {
        this.authority = authority
    }

    override suspend fun compare(input: PageComparisonInput): PageReview {
        val identical = input.baselineText != null &&
            input.baselineText == input.candidateText &&
            input.candidateText.isNotBlank()
        val request = ExternalDispatchPermitRequest(
            profileRevisionId = input.reviewProfileRevisionId,
            page = PageDispatchIdentity(input.unitId, input.ordinal, input.documentId.value),
        )
        val authority = authority
        if (authority != null && !authority.isPermitted(request)) {
            // Exactly what [ImageLlmClient] does when its permit is refused: nothing is sent.
            throw ImageLlmException(
                ImageLlmException.EXTERNAL_DISPATCH_NOT_PERMITTED,
                "this page is not covered by an external dispatch permit for this profile revision",
            )
        }
        authority?.attemptAboutToBeSent(request)
        dispatches++
        return PageReview(
            fingerprint = infoscry.ocr.OcrReviewFingerprint.of(
                documentId = input.documentId.value,
                unitId = input.unitId,
                ordinal = input.ordinal,
                baselineRevisionId = input.baselineRevisionId,
                baselineTextHash = input.baselineTextHash,
                candidateHash = infoscry.ocr.readingTextHash(input.candidateText),
                reviewProfileRevisionId = input.reviewProfileRevisionId,
                reviewPromptVersion = input.reviewPromptVersion,
                policyVersion = input.policyVersion,
            ),
            documentId = input.documentId,
            unitId = input.unitId,
            ordinal = input.ordinal,
            imageSha256 = input.page.sha256,
            baselineRevisionId = input.baselineRevisionId,
            baselineTextHash = input.baselineTextHash,
            candidateHash = infoscry.ocr.readingTextHash(input.candidateText),
            recommendation = recommendation,
            // Pilot mode: the disposition a differing candidate gets is a proposal, which is what the policy
            // decides. A reviewer's recommendation never approves anything by itself.
            disposition = if (identical || recommendation == ReviewerRecommendation.EXISTING_BETTER) {
                // Identical text needs no replacement, and a page the reviewer prefers the existing reading
                // for keeps it: neither is a decision a person owes.
                PublicationDisposition.KEEP
            } else {
                PublicationDisposition.PROPOSE
            },
            confidence = 0.5,
            reasons = listOf(
                ReviewReason(
                    code = "ALIGNED_DIFFERENCE",
                    origin = ReasonOrigin.REVIEWER,
                    baselineSpan = input.baselineText?.let { infoscry.ocr.TextSpan(0, it.length) },
                    candidateSpan = infoscry.ocr.TextSpan(0, input.candidateText.length),
                    explanation = "the image supports this reading",
                ),
            ),
            reviewerRevisionId = input.reviewProfileRevisionId,
            reviewerModelVersion = "fake-vision-1",
            reviewPromptVersion = input.reviewPromptVersion,
            policyVersion = input.policyVersion,
            outcomeCode = null,
            searchable = false,
        )
    }
}

/** A page-reading engine a test can dictate: what it reads, or how it fails. */
internal class FakePageEngine(
    private val readings: List<String> = emptyList(),
    private val failWith: String? = null,
    private val onRead: suspend () -> Unit = {},
) : PageOcrEngine {

    override val engine: OcrEngine = OcrEngine.SURYA

    /** How many pages the engine was asked to read, so a test can prove a page was not read twice. */
    var calls: Int = 0
        private set

    /** What the engine was handed, in order, so a test can assert on the pages it was asked about. */
    val pages: MutableList<PageImage> = mutableListOf()

    override suspend fun transcribe(page: PageImage, settings: OcrSettingsSnapshot): OcrPageResult {
        calls++
        pages += page
        onRead()
        if (failWith != null) {
            throw ImageLlmException(failWith, "the fake engine refused this page")
        }
        val text = readings.getOrElse((calls - 1).coerceAtMost(readings.size - 1).coerceAtLeast(0)) { "" }
        return OcrPageResult(
            text = text,
            engine = engine,
            imageSha256 = page.sha256,
            modelVersion = "fake-surya-1",
        )
    }

    override suspend fun runtimeIdentity(): String? = "fake-surya-1"
}

/**
 * A temporary archive with one published picture document, and the rescan services pointed at it.
 *
 * The document is imported through the real import path with a stand-in extractor, so its managed copy, its
 * committed unit, its chunks and its first published revision are the archive's own records rather than rows
 * this harness wrote itself. Everything a rescan then does is compared against those.
 */
internal class RescanHarness(private val directory: Path) : AutoCloseable {

    private val harness: Harness = Harness(directory)

    private val admissions = java.util.concurrent.atomic.AtomicInteger()

    /**
     * The external reviewer profile, created through the real store so its revision id is a row that exists:
     * a collection's review slot references a profile, and a rescan resolves the revision that profile names.
     * Its capability is measured, because an external destination nobody measured is refused on purpose.
     */
    private val reviewProfile: infoscry.ocr.OcrProfile = AppContext.open(harness.dataDir).use { context ->
        val created = context.ocrProfiles.create(
            name = "Vision reviewer",
            draft = infoscry.ocr.OcrProfileRevisionDraft(
                provider = LlmProvider.OPENAI_COMPATIBLE,
                model = "vision-model",
                contextWindow = 32_000,
                maxOutputTokens = 2_048,
                endpoint = "https://example.invalid/v1",
                inputPricePerMillion = 1.0,
                outputPricePerMillion = 2.0,
            ),
            enabled = true,
        )
        context.ocrProfiles.recordImageCapability(created.revision.revisionId, true, "2026-10-02T00:00:00Z")
        assertNotNull(context.ocrProfiles.findById(created.id))
    }

    private val reviewRevision: OcrProfileRevision get() = reviewProfile.revision

    /** The immutable reviewer revision this archive's review profile points at. */
    val reviewRevisionId: String get() = reviewRevision.revisionId

    /** The profile a collection's review slot selects, as its own id. */
    val reviewProfileId: String get() = reviewProfile.id

    /** The archive itself, which is the directory a second process opens. */
    val archiveDir: Path get() = harness.dataDir

    /** One imported picture: the document, its published revision, its page's unit id and its source file. */
    data class Picture(
        val documentId: DocumentId,
        val baselineRevisionId: String,
        val unitId: String,
        val sourceFile: Path,
    )

    /**
     * What one attempt left behind, read back from the archive before its process is closed.
     *
     * The values are read rather than the connection kept open, because the point of every assertion here is
     * what is *durable*: a value read back after the attempt's process is gone is exactly that.
     */
    data class Attempt(
        val operation: OcrOperation,
        val candidatePage: infoscry.storage.RevisionPageText?,
        val candidateApprovals: List<PageApproval>,
        val activeRevisionId: String?,
        val publishedText: String,
        val indexedChunks: Int,
        val engine: FakePageEngine?,
        /** What the attempt threw, if anything, so a test can assert on how it stopped. */
        val failure: Throwable?,
    )

    fun importPicture(text: String): Picture {        val file = harness.sourcesDir.resolve("page.png")
        writePicture(file)
        val run = harness.importDurably(listOf(file), PictureUnits(text))
        val documentId = run.items.single().documentId!!
        AppContext.open(harness.dataDir).use { context ->
            val revisionId = context.revisions.activeRevisionId(documentId)!!
            val page = context.revisions.pages(revisionId).single()
            return Picture(documentId, revisionId, page.unitId.value, file)
        }
    }

    /** A second, different picture document in the same collection, for the tests that need a neighbour. */
    fun importAnotherPicture(text: String): Picture {
        val file = harness.sourcesDir.resolve("another-page.png")
        val image = BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB)
        for (x in 0 until 8) {
            for (y in 0 until 8) image.setRGB(x, y, 0xEEEEEE)
        }
        ImageIO.write(image, "png", file.toFile())
        val run = harness.importDurably(listOf(file), PictureUnits(text))
        val documentId = run.items.single().documentId!!
        AppContext.open(harness.dataDir).use { context ->
            val revisionId = context.revisions.activeRevisionId(documentId)!!
            val page = context.revisions.pages(revisionId).single()
            return Picture(documentId, revisionId, page.unitId.value, file)
        }
    }

    /** One imported plain-text document: a format without page images, so a rescan has nothing to read again. */
    fun importTextDocument(): DocumentId {
        val file = harness.sourcesDir.resolve("notes.txt")
        Files.writeString(file, "Ordinary notes about the meeting\n")
        val run = harness.importDurably(listOf(file), RecordingUnits(units = 1))
        return assertNotNull(run.items.single().documentId)
    }

    /** What one rescan preview answered for [documentId], or the refusal it was refused with. */
    fun previewRefusal(documentId: DocumentId): Throwable? =
        AppContext.open(harness.dataDir).use { context ->
            runBlocking {
                runCatching {
                    rescanServiceOf(context, FakePageEngine()).preview(CollectionId("default"), documentId)
                }.exceptionOrNull()
            }
        }

    /** The text the document publishes now, as its active revision holds it. */
    fun publishedTextOf(picture: Picture): String = AppContext.open(harness.dataDir).use { context ->
        val revisionId = context.revisions.activeRevisionId(picture.documentId)!!
        context.revisions.pages(revisionId).single().extractedText
    }

    fun activeRevisionOf(picture: Picture): String = AppContext.open(harness.dataDir).use { context ->
        context.revisions.activeRevisionId(picture.documentId)!!
    }

    /** How many of the document's chunks the search index holds, which is what a reader would find. */
    fun indexedChunksOf(picture: Picture): Int = AppContext.open(harness.dataDir).use { context ->
        context.index().chunkCount(CollectionId("default"), picture.documentId)
    }

    fun managedCopyOf(picture: Picture): Path = harness.managedOriginal(picture.documentId)

    /** The file the user imported, so a test can move it away and prove a rescan does not need it. */
    fun sourceFileOf(picture: Picture): Path = picture.sourceFile

    /**
     * An attempt admitted while the managed copy was intact and run after it changed.
     *
     * The order is deliberate: the preview looks at the copy's hash, so the corruption has to land between
     * admission and the reading to test what the *attempt* does about it — which is refuse, rather than
     * attribute a reading to bytes the archive never recorded.
     */
    fun resumeAfterCorrupting(picture: Picture, engine: FakePageEngine): Attempt =
        AppContext.open(harness.dataDir).use { context ->
            val operationId = admitFor(context, picture, reviewRevisionId = null, externalPageLimit = 0)
            Files.write(managedCopyOf(picture), byteArrayOf(1, 2, 3, 4))
            runAttempt(context, operationId, engine, null, TestDocumentEmbedder(), null, picture)
        }

    /** One whole rescan: preview, admit, and the attempt the admission queued. */
    fun rescan(
        picture: Picture,
        engine: FakePageEngine,
        reviewer: PageReviewer?,
        reviewRevisionId: String? = null,
        externalPageLimit: Int = 0,
        embedder: DocumentEmbedder? = TestDocumentEmbedder(),
        beforePageCommit: (suspend (AppContext, JobId) -> Unit)? = null,
    ): Attempt = AppContext.open(harness.dataDir).use { context ->
        val operationId = admitFor(context, picture, reviewRevisionId, externalPageLimit)
        runAttempt(context, operationId, engine, reviewer, embedder, beforePageCommit, picture)
    }

    /** Another attempt of an operation the archive already holds. */
    fun resumeRescan(operationId: String, engine: FakePageEngine, picture: Picture): Attempt =
        AppContext.open(harness.dataDir).use { context ->
            runAttempt(context, operationId, engine, null, TestDocumentEmbedder(), null, picture)
        }

    fun decide(
        picture: Picture,
        operation: OcrOperation,
        page: infoscry.storage.RevisionPageText,
        choice: infoscry.document.ReviewChoice,
        text: String? = null,
    ): infoscry.document.ReviewDecisionResult = AppContext.open(harness.dataDir).use { context ->
        val service = rescanServiceOf(context, null)
        val active = context.revisions.activeRevisionId(picture.documentId)!!
        service.decideReviews(
            collectionId = CollectionId("default"),
            documentId = picture.documentId,
            operationId = operation.operationId,
            requestId = "decision-1",
            expectedRevisionId = active,
            decisions = listOf(
                infoscry.document.ReviewDecision(
                    unitId = page.unitId.value,
                    ordinal = page.ordinal,
                    candidateHash = assertNotNull(page.textSha256),
                    choice = choice,
                    text = text,
                ),
            ),
        )
    }

    fun publish(
        picture: Picture,
        operation: OcrOperation,
    ): infoscry.document.PublicationDecisionResult = AppContext.open(harness.dataDir).use { context ->
        val service = rescanServiceOf(context, null)
        val active = context.revisions.activeRevisionId(picture.documentId)!!
        runBlocking {
            service.publishDecisions(
                collectionId = CollectionId("default"),
                documentId = picture.documentId,
                operationId = operation.operationId,
                expectedRevisionId = active,
            )
        }
    }

    /**
     * Writes the collection's OCR selection, as a person would have configured it: the local engine reads the
     * page images, and a review profile may send the comparison off the machine.
     */
    private fun writeSelection(
        context: AppContext,
        reviewRevisionId: String?,
        externalPageLimit: Int,
    ) {
        context.collections.updateOcrSettings(
            CollectionId("default"),
            CollectionOcrSettings(
                language = "eng",
                engine = OcrEngine.SURYA,
                importMode = OcrImportMode.CHECK_AND_IMPROVE,
                transcriptionProfileId = null,
                reviewProfileId = if (reviewRevisionId != null) reviewProfileIdOf(reviewRevisionId) else null,
                externalPageLimit = externalPageLimit,
            ),
        )
    }

    /** Rewrites the collection's OCR selection from what it holds now, for the stale-preview tests. */
    fun updateSelection(change: (CollectionOcrSettings) -> CollectionOcrSettings) {
        AppContext.open(harness.dataDir).use { context ->
            val current = assertNotNull(context.collections.get(CollectionId("default"))).ocrSettings()
            context.collections.updateOcrSettings(CollectionId("default"), change(current))
        }
    }

    /** One preview of [picture], against the selection [reviewRevisionId] implies. */
    fun previewFor(
        picture: Picture,
        reviewRevisionId: String? = null,
        externalPageLimit: Int = 0,
        overrides: RescanOverrides = RescanOverrides(),
    ): String =
        AppContext.open(harness.dataDir).use { context ->
            writeSelection(context, reviewRevisionId, externalPageLimit)
            runBlocking {
                rescanServiceOf(context, FakePageEngine())
                    .preview(CollectionId("default"), picture.documentId, overrides)
            }.previewId
        }

    /** One admission against a preview taken against the selection the tests name. */
    fun admitOnly(
        picture: Picture,
        reviewRevisionId: String? = null,
        externalPageLimit: Int = 0,
    ): OcrOperation = AppContext.open(harness.dataDir).use { context ->
        writeSelection(context, reviewRevisionId, externalPageLimit)
        val service = rescanServiceOf(context, FakePageEngine())
        runBlocking {
            val preview = service.preview(CollectionId("default"), picture.documentId)
            service.admitRescan(
                collectionId = CollectionId("default"),
                documentId = picture.documentId,
                previewId = preview.previewId,
                // A distinct request id per admission: the same id is the same request, which is exactly what
                // the routes' idempotency rests on and not what these tests are about.
                requestId = "rescan-request-${admissions.incrementAndGet()}",
            )
        }
    }

    /** What one admission of a fresh preview answered, or the refusal it was refused with. */
    fun admitRefusal(picture: Picture, requestId: String): Throwable? =
        AppContext.open(harness.dataDir).use { context ->
            val service = rescanServiceOf(context, FakePageEngine())
            runBlocking {
                runCatching {
                    val preview = service.preview(CollectionId("default"), picture.documentId)
                    service.admitRescan(
                        collectionId = CollectionId("default"),
                        documentId = picture.documentId,
                        previewId = preview.previewId,
                        requestId = requestId,
                    )
                }.exceptionOrNull()
            }
        }

    /** What one admission of a named preview answered, or the refusal it was refused with. */
    fun admitRefusal(picture: Picture, previewId: String, requestId: String): Throwable? =
        AppContext.open(harness.dataDir).use { context ->
            runBlocking {
                runCatching {
                    rescanServiceOf(context, FakePageEngine()).admitRescan(
                        collectionId = CollectionId("default"),
                        documentId = picture.documentId,
                        previewId = previewId,
                        requestId = requestId,
                    )
                }.exceptionOrNull()
            }
        }

    /** What the service answered to a resume of an operation, or the refusal it was refused with. */
    fun resumeRefusal(picture: Picture, operationId: String): Throwable? =
        AppContext.open(harness.dataDir).use { context ->
            runBlocking {
                runCatching {
                    rescanServiceOf(context, FakePageEngine()).resume(
                        collectionId = CollectionId("default"),
                        documentId = picture.documentId,
                        operationId = operationId,
                    )
                }.exceptionOrNull()
            }
        }

    /** What the service answered to an approval of an operation, or the refusal it was refused with. */
    fun approveRefusal(picture: Picture, operationId: String, maxDistinctPages: Int): Throwable? =
        AppContext.open(harness.dataDir).use { context ->
            runBlocking {
                runCatching {
                    rescanServiceOf(context, FakePageEngine()).approveExternal(
                        collectionId = CollectionId("default"),
                        documentId = picture.documentId,
                        operationId = operationId,
                        expectedSnapshotHash = OcrOperationStore.snapshotHashOf(
                            assertNotNull(context.ocrOperations.operation(operationId)).snapshot,
                        ),
                        maxDistinctPages = maxDistinctPages,
                    )
                }.exceptionOrNull()
            }
        }

    /** The operation one approval answered with, which is the attempt it started. */
    fun approveThroughService(picture: Picture, operationId: String, maxDistinctPages: Int): OcrOperation =
        AppContext.open(harness.dataDir).use { context ->
            runBlocking {
                rescanServiceOf(context, FakePageEngine()).approveExternal(
                    collectionId = CollectionId("default"),
                    documentId = picture.documentId,
                    operationId = operationId,
                    expectedSnapshotHash = OcrOperationStore.snapshotHashOf(
                        assertNotNull(context.ocrOperations.operation(operationId)).snapshot,
                    ),
                    maxDistinctPages = maxDistinctPages,
                )
            }
        }

    /** How many rescan attempts this archive holds, which is what "a second attempt" would add to. */
    fun rescanJobCount(): Int = AppContext.open(harness.dataDir).use { context ->
        context.jobs.list(limit = 100).count { job -> job.type == JobType.RESCAN }
    }

    /** How many distinct pages one operation's scope authorizes now, or zero when nobody approved anything. */
    fun approvedScopeOf(picture: Picture, operationId: String): Int =
        AppContext.open(harness.dataDir).use { context ->
            context.ocrOperations
                .latestApproval(OcrExternalOwner.operation(operationId))
                ?.authorizedDistinctPages ?: 0
        }

    // ---- internals ----

    private fun admitFor(
        context: AppContext,
        picture: Picture,
        reviewRevisionId: String?,
        externalPageLimit: Int,
    ): String {
        writeSelection(context, reviewRevisionId, externalPageLimit)
        val engine = FakePageEngine()
        val service = rescanServiceOf(context, engine)
        return runBlocking {
            val preview = service.preview(CollectionId("default"), picture.documentId)
            service.admitRescan(
                collectionId = CollectionId("default"),
                documentId = picture.documentId,
                previewId = preview.previewId,
                // A distinct request id per admission: the same id is the same request, which is exactly what
                // the routes' idempotency rests on and not what these tests are about.
                requestId = "rescan-request-${admissions.incrementAndGet()}",
            ).operationId
        }
    }

    private fun runAttempt(
        context: AppContext,
        operationId: String,
        engine: FakePageEngine,
        reviewer: PageReviewer?,
        embedder: DocumentEmbedder?,
        beforePageCommit: (suspend (AppContext, JobId) -> Unit)?,
        picture: Picture,
    ): Attempt {
        val job = runBlocking {
            context.jobs.enqueue(
                type = JobType.RESCAN,
                collectionId = CollectionId("default"),
                payload = RescanJobPayload(
                    collectionId = "default",
                    documentId = picture.documentId.value,
                    operationId = operationId,
                ).encode(),
                total = 1,
            )
        }
        val claimed = assertNotNull(context.jobs.claim(job.id))
        val before = assertNotNull(context.ocrOperations.operation(operationId))
        // The page is read first and the hook runs before the page is committed, which is the window a
        // cancellation request or a collection deletion has to be honoured in.
        val effective = if (beforePageCommit == null) {
            engine
        } else {
            HookedEngine(engine, context, claimed.id, beforePageCommit)
        }
        val handler = RescanJobHandler(
            paths = context.paths,
            collections = context.collections,
            documents = context.documents,
            library = context.library,
            mutations = context.mutations,
            jobs = context.jobs,
            revisions = context.revisions,
            operations = context.ocrOperations,
            reviews = context.ocrReviews,
            publication = context.revisionPublication,
            chunker = Chunker(WhitespaceTokenCounter(prefixTokens = 2, specialTokens = 1)),
            documentEmbedder = { embedder },
            engineFor = { _, _, _ -> effective },
            reviewerFor = { _, dispatch ->
                if (reviewer is RecordingReviewer) reviewer.dispatchingThrough(dispatch)
                reviewer ?: PageReviewer { input -> unreachableReviewer(input) }
            },
            profileRevisionOf = { revisionId -> context.ocrProfiles.findRevision(revisionId) },
        )
        val failure = runBlocking {
            runCatching {
                handler.handle(
                    assertNotNull(context.jobs.get(claimed.id)),
                    JobStage(claimed.id, context.jobs, context.collections, context.mutations),
                )
            }.exceptionOrNull()
        }
        runBlocking {
            // The runner's own endings, so the archive is left as a stopped attempt leaves it: a cancelled job
            // is cancelled, and an attempt that paused or finished completes.
            val running = context.jobs.get(claimed.id)
            if (running != null && running.state == JobState.RUNNING) {
                if (running.cancelRequested) {
                    context.jobs.finishCancelled(claimed.id)
                } else {
                    context.jobs.complete(claimed.id)
                }
            }
        }
        // A collection deletion cascades the document and its operations away, so a row that is gone is an
        // answer rather than a missing one: it is what "this document is not one this attempt owns" means.
        // A deleted collection cascades the document and its operations away, so a row that is gone is an
        // answer rather than a missing one; the last state this attempt recorded stands in for it.
        val operation = context.ocrOperations.operation(operationId) ?: before.copy(
            stage = OcrOperationStage.CANCELLED,
        )
        val candidate = if (context.ocrOperations.operation(operationId) == null) {
            null
        } else {
            operation.candidateRevisionId
        }
        val pages = candidate?.let { revisionId -> context.revisions.pages(revisionId) }.orEmpty()
        val active = context.revisions.activeRevisionId(picture.documentId)
        return Attempt(
            operation = operation,
            candidatePage = pages.singleOrNull(),
            candidateApprovals = pages.map { page -> page.approval },
            activeRevisionId = active,
            publishedText = active
                ?.let { revisionId -> context.revisions.pages(revisionId).singleOrNull() }
                ?.extractedText
                .orEmpty(),
            indexedChunks = context.index().chunkCount(CollectionId("default"), picture.documentId),
            engine = engine,
            failure = failure,
        )
    }

    private fun rescanServiceOf(context: AppContext, engine: PageOcrEngine?): RescanService = RescanService(
        paths = context.paths,
        collections = context.collections,
        documents = context.documents,
        revisions = context.revisions,
        jobs = context.jobs,
        operations = context.ocrOperations,
        reviews = context.ocrReviews,
        mutations = context.mutations,
        blockers = context.blockers,
        publication = context.revisionPublication,
        index = { context.index() },
        profileOf = { profileId -> context.ocrProfiles.findById(profileId) },
        profileRevisionOf = { revisionId -> context.ocrProfiles.findRevision(revisionId) },
        engines = { _, _, _ -> engine },
        embedderAvailable = { true },
        keyAvailable = { true },
    )

    /** The profile id a review revision belongs to, as the collection's slot names it. */
    private fun reviewProfileIdOf(revisionId: String): String = reviewProfile.id

    private fun writePicture(file: Path) {
        val image = BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB)
        for (x in 0 until 8) {
            for (y in 0 until 8) image.setRGB(x, y, 0xFFFFFF)
        }
        ImageIO.write(image, "png", file.toFile())
    }

    override fun close() {
        harness.close()
    }
}

/** The reviewer an attempt reaches for a page it was never asked to review. */
private fun unreachableReviewer(input: PageComparisonInput): PageReview = throw IllegalStateException(
    "this attempt was not given a reviewer, and page ${input.ordinal} asked for one",
)

/**
 * A page engine that calls back between the reading and its commit.
 *
 * That instant is where a cancellation request or a collection deletion lands in a real attempt: the page's
 * reading has come back and the commit is still ahead. Only a test can hold an attempt exactly there.
 */
private class HookedEngine(
    private val delegate: FakePageEngine,
    private val context: AppContext,
    private val jobId: JobId,
    private val afterRead: suspend (AppContext, JobId) -> Unit,
) : PageOcrEngine {

    override val engine: OcrEngine get() = delegate.engine

    /** The delegate's own identity: an attempt compares it with the one its operation was admitted with. */
    override suspend fun runtimeIdentity(): String? = delegate.runtimeIdentity()

    override suspend fun transcribe(page: PageImage, settings: OcrSettingsSnapshot): OcrPageResult {
        val reading = delegate.transcribe(page, settings)
        afterRead(context, jobId)
        return reading
    }
}

/** An extractor for a picture document: one page, whose text the test dictates. */
internal class PictureUnits(private val text: String) : DocumentExtractor {

    override val supportedMediaTypes: Set<String> = setOf("image/png")

    override fun extract(input: ExtractionInput): kotlinx.coroutines.flow.Flow<ExtractionEvent> =
        kotlinx.coroutines.flow.flow {
            if (!input.isCommitted(KEY)) {
                input.boundary.unit {
                    emit(
                        ExtractionEvent.UnitReady(
                            key = KEY,
                            ordinal = 0,
                            unit = ContentUnitDraft(
                                locator = SourceLocation.Image(input.originalFilename),
                                extractedText = text,
                                searchText = text,
                                method = ExtractionMethod.OCR,
                            ),
                        ),
                    )
                }
            }
            input.boundary.unit {
                emit(ExtractionEvent.Finished(metadata = emptyMap(), totalUnits = 1))
            }
        }

    private companion object {
        const val KEY = "image"
    }
}
