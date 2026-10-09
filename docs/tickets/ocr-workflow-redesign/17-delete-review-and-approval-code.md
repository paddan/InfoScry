# 17: Delete review and approval code, update docs

**Status:** Implemented; automated gates passed (2026-10-09). See [verification record](STATUS.md#verification-record).
**Blocked by:** 08, 09, 10 (nothing in production may produce a review or approval), 14 and 15 (the web no longer calls the routes), 16 (the CLI no longer references approval types).
**Plan:** [OCR workflow redesign, Task 17](../../plans/2026-10-08-ocr-workflow-redesign.md). Read first: this ticket, [CONTRACTS.md](CONTRACTS.md) (sections 10, 12, 13), `AGENTS.md`, the [spec](../../specs/2026-10-08-ocr-workflow-redesign.md) ("Superseded contracts").

## Problem

Dead review, comparison and approval code and documents describe a flow that no longer exists.

## Deliverable

The code, routes, tests and documents below are deleted or marked retired, with no
behavior change.

## Can be built against

No fake; this ticket needs the others merged. Start by running `./gradlew check` for a baseline.

## Files and interfaces

- Delete or reduce: `src/main/kotlin/infoscry/storage/OcrReviewStore.kt`, `ocr/StagedPageReview.kt`, `ocr/OcrComparisonService.kt`, `ocr/OcrDecisionPolicy.kt`, review routes in `server/OcrRoutes.kt`, `RescanService.{pendingReviews,decideReviews,publishDecisions,discardPending}`, and their tests (`OcrReviewRoutesTest`, `OcrReviewDecisionPublicationTest`, `OcrDecisionPolicyTest`). Keep `StaleOcrStateCleanupTest` green; if the stores are gone, the cleanup handles old rows with plain SQL.
- Delete leftovers: `web/src/lib/OcrChoiceFields.svelte` and unused exports of `web/src/lib/ocrRescan.ts` if nothing imports them.
- Modify docs: `docs/tickets/ocr-rescanning/STATUS.md` and tickets 08d-08f, 11a, 11b (mark retired), `docs/specs/2026-09-30-ocr-rescanning.md` (note superseded sections), `README.md`, `docs/usage.md`, `docs/cli.md`.

## Original test-first implementation plan

- Run `./gradlew check` and note the baseline.
- Delete the code and tests in the order the compiler allows; no new tests besides keeping `StaleOcrStateCleanupTest`.
- Restart/idempotence check: `StaleOcrStateCleanupTest` and the recovery tests still pass, proving a database written by the shipped flow is still cleaned.
- Update docs and ticket status; verify relative links resolve and `git diff` contains only the intended deletions.
- Run `./gradlew check` and `(cd web && npm test -- --run && npm run check)`; expect PASS.

## Focused verification

```sh
cd /Users/patrik/projects/infoscry
./gradlew check
(cd web && npm test -- --run && npm run check)
grep -rniE "awaiting-approval|approve-external|review pages|discard pending" src web/src docs/usage.md docs/cli.md README.md
```

The grep may match only the cleanup's literal string and the superseded-spec notes.

## Out of scope

New behavior; the acceptance rewrite (ticket 18).
