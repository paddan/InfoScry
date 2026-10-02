# 07b: Import execution, admission revalidation and attempt claiming

**Status:** Not started. Unchecked criteria are requirements, not evidence.
**Blocked by:** 07 (whose rescan path is verified; this ticket carries what its reviews left open).
**Spec:** [Selectable OCR and rescanning](../../specs/2026-09-30-ocr-rescanning.md).
Read [shared contracts and gates](CONTRACTS.md) before implementation.

## Deliverable

A new import executes the OCR mode and the review it was admitted with, under job-owned external approval;
admission refuses a stale preview; and one operation owns its document until it is finished.

## Why this is separate from 07

Two implementing runs on ticket 07 timed out on its combined scope (a ~62 KB rescan service, a ~49 KB
handler, the routes, the import path and their tests). What is verified there is the rescan path:
a `RESCAN` job type, preview and admission with request-id idempotency, per-page checkpoints, bounded
cancellation, external-page accounting, `AWAITING_APPROVAL`, comparison and review wiring, decisions,
publication and operation ownership that holds a document while an operation is unfinished or awaiting
review. What is not: the import half executes none of what it admits, admission does not revalidate the
whole settings snapshot, and resume or approval can start a second attempt.

## Files and interfaces

- `src/main/kotlin/infoscry/jobs/{ImportJobHandler.kt, ImportJobPayload.kt, DocumentIngest.kt}`
- `src/main/kotlin/infoscry/document/RescanService.kt` (admission revalidation; the attempt claim)
- `src/main/kotlin/infoscry/storage/OcrOperationStore.kt` (`startAttempt` is the claim seam)
- `src/main/kotlin/infoscry/server/{OcrRoutes.kt, Routes.kt}` (import approval resumes the waiting job)
- `src/main/kotlin/infoscry/cli/ImportCommand.kt` (ownership and the approval requirement)
- `src/main/kotlin/infoscry/extract/ExtractorRegistry.kt` (the LLM engine is absent from the production
  registry; decide where an LLM-profile engine is built for an OCR attempt)
- Tests: `src/test/kotlin/infoscry/jobs/*`, `src/test/kotlin/infoscry/server/OcrRoutesTest.kt`

## Test-first implementation

**Status of this ticket:** its earlier items are done and verified — job-owned external approval with
`AWAITING_APPROVAL`, the two-file allowance, distinct-page versus call accounting, admission revalidation of
the whole settings snapshot, attempt claiming through `OcrOperationStore.startAttempt`, the store-backed
decision policy, the CLI's approval surfacing — and so is the *first* of the two items below: the admitted
runtime identity is carried into execution (probe only when an attempt records none, so a recorded identity is
never replaced) and `NEEDS_REVIEW` is reachable for an import whose pages still owe a person a decision. The
one **open** item is the second: an import in `CHECK_AND_IMPROVE` does not yet compare or review. That is a
slice of its own, and the map is recorded with that item so a later run does not have to re-derive it.

- [x] Add meaningful failing tests for the following observable scenarios before changing production behavior:
  - An import admitted with `CHECK_AND_IMPROVE` and a differing page goes through comparison and review, and a document whose pages await decisions is review-pending rather than `COMPLETE` or searchable. — **Half:** the review-pending half is done and tested (`ImportJobHandlerTest`); the comparison/review half is the open item below.
  - An import's job-owned allowance covers two files in one job, counts one distinct page once across transcription and review while counting two calls, and pauses in `AWAITING_APPROVAL` before the first page beyond it; the approval resumes the waiting job, and nothing is dispatched before it.
  - A settings change between preview and admission is refused, for engine, mode, language, reviewer selection and allowance — not only for a profile revision.
  - Two admissions with different request ids do not both own one document, and a resume or approval while an attempt is running does not enqueue a second one.
  - The remote CLI import reports a waiting-approval requirement instead of enqueueing and returning success.
- [x] Run the focused command below and record the expected behavioral failure. For an external test, first prove missing/invalid-runtime diagnostics using the fake transport; do not download private fixtures.
- [x] Carry the admitted snapshot (engine, mode, profiles, allowance, and the probed runtime identity) into execution instead of re-deriving it, so a restart resumes the runtime it was admitted with rather than discovering a new one.
**Status of this ticket:** done for the scope listed below. A page read under `CHECK_AND_IMPROVE` carries its own
text beside the engine's reading; an import that stages a candidate compares each page while the draft is in
hand, records the reviews a person owes, publishes the pages it approved (a reading that matches the page's own
text needs no decision, so it is approved by equivalence; a differing reading stays pending in pilot mode), and
leaves the pending pages with no content unit, no chunk and no index row. Retrieval, indexing and the rescan
rule are untouched: a rescan with any unapproved page still refuses exactly as before. My own `./gradlew`
focused runs are green (26 suites / 542 tests, then 71, then a forced 2m34s run over jobs/document/ocr/extract).

**Residuals recorded, not resolved:** a later decision on an import's pending pages has no surface yet (the
revision is `PUBLISHED`, so re-publishing it would need its own step — that is ticket 08's decision surface); a
check-and-improve import whose pages all lack a text layer publishes nothing by design (no baseline ⇒ uncertain
proposal), and such a file belongs in fill-missing mode; a check-and-improve import now needs the embedder, so
without a model it fails `MODEL_NOT_INSTALLED` rather than ending `NEEDS_REVIEW` (the same remedy as an ordinary
import); a check-and-improve **retry** still stages without reviews; and the external (non-loopback) review
dispatch through a job's allowance is not exercised by a test, because the reviewer in the tests is loopback.

**Two decisions this sequence made, for ticket 08's surface:** a review built from a held baseline has no
baseline revision and no hash (the schema ties them together), so such a review's identity is candidate +
image + reviewer + policy — within a document the direct text is a fact of immutable bytes, so it cannot
silently differ for the same comparison; and a review's page identity is the extraction key (`page:1`), not the
staged unit id, so the two stages count one page once against an external-page allowance — a decision surface
therefore resolves a review by (document, ordinal), which the review carries.

**Blocked by:** 07.

- [x] Build or inject an image-capable engine for an LLM attempt in the production registry, and compare/review differing pages on the import path with the same rules the rescan path already implements (identical non-blank no-op, empty pair pending, review failure keeps the baseline, pilot `PROPOSE`).
- [x] Wire a store-backed `OcrDecisionPolicy(policyVersion, reviews)` wherever a comparison happens, so an accepted validation is consulted rather than defaulted away.
- [x] Make resume and approval claim the attempt through `OcrOperationStore.startAttempt` (or an equivalent idempotent guard), so two workers cannot run against one candidate and one set of counters.
- [x] Revalidate the whole effective settings snapshot at admission and refuse a stale preview with the existing conflict shape.
- [x] Decide with the owner where the review-decision/publication and revision endpoints in `OcrRoutes` belong: review decisions and publication are ticket 08's surface, and revision history is ticket 09's. — Decided: they stay in `OcrRoutes`; 08 and 09 consume them.
- [x] Run focused tests green, then the accumulated gates in CONTRACTS.md. Record failures and environment blockers separately.
- [x] Review the diff against the spec and update STATUS.md. Commit/push only under the user's separately authorized Git workflow.

## Focused verification

```sh
./gradlew test --tests 'infoscry.jobs.*' --tests 'infoscry.server.OcrRoutesTest' --tests 'infoscry.document.*' --tests 'infoscry.storage.*'
```

Normal tests use local fakes and temporary archives. A successful fake-provider run does not satisfy a
real-tool or hardware gate.
