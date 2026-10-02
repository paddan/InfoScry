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

- [ ] Add meaningful failing tests for the following observable scenarios before changing production behavior:
  - An import admitted with `CHECK_AND_IMPROVE` and a differing page goes through comparison and review, and a document whose pages await decisions is review-pending rather than `COMPLETE` or searchable.
  - An import's job-owned allowance covers two files in one job, counts one distinct page once across transcription and review while counting two calls, and pauses in `AWAITING_APPROVAL` before the first page beyond it; the approval resumes the waiting job, and nothing is dispatched before it.
  - A settings change between preview and admission is refused, for engine, mode, language, reviewer selection and allowance — not only for a profile revision.
  - Two admissions with different request ids do not both own one document, and a resume or approval while an attempt is running does not enqueue a second one.
  - The remote CLI import reports a waiting-approval requirement instead of enqueueing and returning success.
- [ ] Run the focused command below and record the expected behavioral failure. For an external test, first prove missing/invalid-runtime diagnostics using the fake transport; do not download private fixtures.
- [ ] Carry the admitted snapshot (engine, mode, profiles, allowance, and the probed runtime identity) into execution instead of re-deriving it, so a restart resumes the runtime it was admitted with rather than discovering a new one.
- [ ] Build or inject an image-capable engine for an LLM attempt in the production registry, and compare/review differing pages on the import path with the same rules the rescan path already implements (identical non-blank no-op, empty pair pending, review failure keeps the baseline, pilot `PROPOSE`).
- [ ] Wire a store-backed `OcrDecisionPolicy(policyVersion, reviews)` wherever a comparison happens, so an accepted validation is consulted rather than defaulted away.
- [ ] Make resume and approval claim the attempt through `OcrOperationStore.startAttempt` (or an equivalent idempotent guard), so two workers cannot run against one candidate and one set of counters.
- [ ] Revalidate the whole effective settings snapshot at admission and refuse a stale preview with the existing conflict shape.
- [ ] Decide with the owner where the review-decision/publication and revision endpoints in `OcrRoutes` belong: review decisions and publication are ticket 08's surface, and revision history is ticket 09's.
- [ ] Run focused tests green, then the accumulated gates in CONTRACTS.md. Record failures and environment blockers separately.
- [ ] Review the diff against the spec and update STATUS.md. Commit/push only under the user's separately authorized Git workflow.

## Focused verification

```sh
./gradlew test --tests 'infoscry.jobs.*' --tests 'infoscry.server.OcrRoutesTest' --tests 'infoscry.document.*' --tests 'infoscry.storage.*'
```

Normal tests use local fakes and temporary archives. A successful fake-provider run does not satisfy a
real-tool or hardware gate.
