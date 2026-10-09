# 10: Publish on completion; no review stage

**Status:** Implemented; automated gates passed (2026-10-09). See [verification record](STATUS.md#verification-record).
**Blocked by:** 01, because runs that previously ended with pending pages are converted by the startup cleanup and `PageApproval.PENDING` / `REVIEW` stop being produced here.
**Plan:** [OCR workflow redesign, Task 10](../../plans/2026-10-08-ocr-workflow-redesign.md). Read first: this ticket, [CONTRACTS.md](CONTRACTS.md) (sections 10, 12, 13), `AGENTS.md`, the [spec](../../specs/2026-10-08-ocr-workflow-redesign.md) (decision 5, "Run and document status").

## Problem

A finished rescan or import can leave pages pending review and a candidate revision that
waits for a person, which makes a document unusable.

## Deliverable

Every staged page is `APPROVED`. The run publishes once, when all pages are read, with
`RevisionPublicationService.publish(documentId, baseRevisionId, candidateRevisionId)`;
publishing again is a no-op; a cancelled or failed run publishes nothing and leaves the
text unchanged. `OcrOperationStage.REVIEW` and `PageApproval.PENDING` are no longer
produced (the enum values stay readable for old rows; deleting code is ticket 17).

## Can be built against

The fake engine and the existing publication service. Rescan admission by method
(ticket 08) is not needed; tests admit with the existing helper.

## Files and interfaces

- Modify `src/main/kotlin/infoscry/ocr/CandidateRevisionPhases.kt` (`acceptedPageOf` :86, `publishCandidate` :323), `src/main/kotlin/infoscry/jobs/RescanJobHandler.kt` (review phase), `src/main/kotlin/infoscry/jobs/AttemptDispatch.kt` (`stagedPageReviewOf` :62), `src/main/kotlin/infoscry/document/RevisionPublicationService.kt` (`refusalFor` :443), `src/main/kotlin/infoscry/jobs/DocumentIngest.kt`.
- Tests `RescanJobHandlerTest`, `ImportJobHandlerTest`, `RevisionPublicationTest`.

## Original test-first implementation plan

- Write failing tests: a completed rescan replaces the text and the previous revision stays restorable; a run killed after the last page but before publication, then restarted, publishes exactly once; a cancelled run leaves the text unchanged; publishing twice is a no-op.
- Run; expect FAIL.
- Review focus 2: process killed mid-run at each stage (reading, chunking, embedding, publishing): restart succeeds with no prior action, and the old text is intact until publication.
- Idempotence: interrupt publication after each durable step (candidate pages final, chunks written, embeddings written, index swap) and repeat; the end state equals one uninterrupted run and the index still contains all collections.
- Implement.
- Run `infoscry.jobs.*`, `infoscry.document.*`; expect PASS.

## Focused verification

```sh
cd /Users/patrik/projects/infoscry
./gradlew test -PskipFrontend --tests 'infoscry.jobs.*' --tests 'infoscry.document.*'
./gradlew check
```

## Out of scope

Deleting review stores, routes and tests (ticket 17); removing the pause (ticket 09); web changes.
