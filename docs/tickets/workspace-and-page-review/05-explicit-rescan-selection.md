# 05 — Durable explicit-page rescan backend

**Status:** Implemented and independently reviewed 2026-10-10.
**Blocked by:** 03.
**Spec:** [Wide workspace and page review](../../specs/2026-10-09-workspace-and-page-review.md).
Read the [plan and shared gates](../../plans/2026-10-09-workspace-and-page-review.md).

## Deliverable and files

Modify `src/main/kotlin/infoscry/server/OcrRoutes.kt`,
`src/main/kotlin/infoscry/document/RescanService.kt`,
`src/main/kotlin/infoscry/ocr/OcrOperation.kt`,
`src/main/kotlin/infoscry/storage/OcrOperationStore.kt`,
`src/main/kotlin/infoscry/jobs/RescanJobHandler.kt`,
`src/main/resources/db/migration/001_baseline.sql`, `web/src/lib/api.ts`.
Test `src/test/kotlin/infoscry/server/OcrRoutesTest.kt`,
`src/test/kotlin/infoscry/jobs/RescanJobHandlerTest.kt`,
`src/test/kotlin/infoscry/storage/OcrOperationStorePreviewTest.kt`;
create `src/test/kotlin/infoscry/jobs/ExplicitPageRescanRecoveryTest.kt`.

## Interfaces

Consumes RescanSelection from the spec. Preview and operation persist its
canonical value; `previewRescan(collectionId, documentId, method, selection?)`
adds the optional fourth argument. Start remains previewId/requestId. The frozen
selection controls page generation, dispatch, costs, progress and recovery.

## Behavioral acceptance vectors

1. Three clean-text PDF pages; explicit [1] reads page two despite automatic
   selection reading none. Unselected pages are not rendered or sent.
2. Explicit [2,1,1] normalizes to [1,2]; []/-1/3/fractional values are invalid
   for three pages. Omitted selection retains current automatic behavior.
3. Reopen SQLite after preview and admission: exact selection/settings survive.
   Same start ID/body returns the same operation, with no duplicate dispatch.
4. Interrupt after admission, after each committed page, before index publication
   and after durable publication authority; restart converges with stable IDs,
   preserved provenance and no repeated committed OCR. Use child-process crash
   harness patterns, not exceptions alone, for durable boundaries.
5. Cancel/fail then start again works; changed base/hash invalidates an old preview.
   Partial OCR/embed failure preserves active text. Restore twice is idempotent.
6. Selected-page progress/allowance/cost equal the canonical set size, not the
   whole document; fake provider sees only selected images.

## Concrete request vectors

For a three-page clean-text PDF, use this request against the existing OCR
preview route (page numbers in the dialog are one-based):

```json
{"method":"surya","selection":{"mode":"explicit","ordinals":[1]}}
```

Assert response selection is `{ "mode": "explicit", "ordinals": [1] }`,
`pageTotal == 1`, `documentPages == 3`. Start twice with the same
`{ "previewId": "<returned previewId>", "requestId": "explicit-page-2" }`;
assert the operation ID is identical and fake-engine call ordinals equal `[1]`.
With `[2,1,1]` assert normalized ordinals `[1,2]` and exactly two calls. With
`[]`, `[-1]`, `[3]` or `[1.5]` assert HTTP 400 and no admitted operation.
With no selection and a clean text layer, assert automatic zero-read behavior
is unchanged. Use fixture-generated IDs, not literal placeholder IDs, in tests.

## Implementation steps

- [ ] Add failing behavioral tests for each numbered vector above. Record actual
  failure output before implementation; compilation failure alone is not evidence.
- [ ] Run the focused side's command below and confirm the intended failures.
- [ ] Implement the deliverable: Persist the mode/set with preview and operation in SQLite before jobs can run.
Do not derive it later from UI state or automatic heuristics. Preserve all pages
in the staged complete revision, copying text/unit IDs/page provenance for those
not selected. Extend fresh baseline only; do not convert/reset owner data. Review
payload/store/service consumers together and retain the zero-automatic-read case.
- [ ] Run focused tests green, then the accumulated gate; record exact commands,
  counts and any unperformed hardware/manual checks.
- [ ] Review contracts, repeat/recovery behavior and spec coverage; update ticket
  status only from evidence. Preserve unrelated edits. Do not commit/push without
  the owner's Git instruction.

## Commands

```sh
./gradlew test -PskipFrontend --tests 'infoscry.server.OcrRoutesTest' --tests 'infoscry.jobs.RescanJobHandlerTest' --tests 'infoscry.storage.OcrOperationStorePreviewTest' --tests 'infoscry.jobs.ExplicitPageRescanRecoveryTest'
./gradlew check
```

## Verification record — 2026-10-10

Canonical automatic/explicit selection is persisted with preview and operation,
bound to hash/base/settings, and used for selected-only rendering, OCR dispatch,
allowance/cost and progress. Explicit pages override the PDF heuristic. Complete
candidate publication preserves unselected stable IDs, text, image and producer.

Route RED reproduced ignored/invalid selection before implementation. Focused
backend tests passed 35 tests; the supplementary cancel → new preview → start
route gate passed 11 tests. API wire tests passed 9 tests and Svelte checks found
zero errors/warnings. Distinct sets/modes have isolated candidates/checkpoints;
stale hash/base previews are rejected before admission.

Real child JVMs were forcibly stopped after admission, after each of two selected
page commits, immediately before publication and after publication authority.
Normal startup retires interrupted pre-publication operations; supported resume
admission reuses the frozen operation/candidate and skips committed OCR.
Post-publication startup repair converges to COMPLETE. Independent Luna spec and
quality review found no issues.

The first accumulated gate exposed nine stale-fixture failures; fixture-only
corrections retained all assertions and passed 32 focused tests plus scoped review.
Replacement final `./gradlew check` passed in 4m34s: 131 JVM suites / 1,595 tests
and 402 frontend tests, zero failures, errors or skips.

Fresh baseline schema only; no owner archive conversion/reset or external model
calls occurred. Integrated browser flows, real OCR providers and CoreML remain
separate gates.
