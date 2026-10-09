# 11: Retry uses a method and resumes committed pages

**Status:** Implemented; automated gates passed (2026-10-09). See [verification record](STATUS.md#verification-record).
**Blocked by:** 02 (retry after a failure that left a stale operation relies on a new run replacing it), 08 (the method-to-snapshot resolution replaces `resolveChosenReading`/overrides that retry uses today), 09 (same `RetryJobHandler` pause paths removed there). It reuses `PageCounter` from ticket 05 to set `externalPageLimit` when a new method is chosen (**chosen**: the plan does not say where a retry's page total comes from); if 05 has not landed, create `PageCounter` per CONTRACTS section 4.
**Plan:** [OCR workflow redesign, Task 11](../../plans/2026-10-08-ocr-workflow-redesign.md). Read first: this ticket, [CONTRACTS.md](CONTRACTS.md) (sections 1, 5, 7, 8, 12, 13), `AGENTS.md`, the [spec](../../specs/2026-10-08-ocr-workflow-redesign.md) ("Run and document status", "Always restartable").

## Problem

Retry is the way to start again after a failure, but it takes engine/mode/profile
overrides, and a repeated request can create a second job.

## Deliverable

The retry request takes `method` (optional; omitted = the failed run's method) and
`requestId`. Retrying a document with pages committed for the same fingerprint reads only
the remaining pages. Repeating the request returns the same job.

## Can be built against

CONTRACTS sections 1, 5, 8. `StartRequestStore` (section 5) is created by whichever of
tickets 06 and 11 lands first.

## Files and interfaces

- Modify `src/main/kotlin/infoscry/jobs/RetryJobHandler.kt`, `src/main/kotlin/infoscry/document/RetryService.kt`, `src/main/kotlin/infoscry/server/DocumentRoutes.kt` (:309).
- With a new method: the snapshot comes from the same resolution as rescan (ticket 08), `externalPageLimit` = the document's page count via `PageCounter` (0 for local). With `method` omitted: the failed run's frozen snapshot is reused unchanged.
- Tests `RetryJobHandlerTest`, `DocumentRetryRoutesTest`.

## Original test-first implementation plan

- Write failing tests: resume without re-reading committed pages (fake provider call count equals the number of uncommitted pages); a repeated request with the same `requestId` returns the same job; retry after a failure that left a stale operation succeeds with no prior action.
- Run; expect FAIL.
- Idempotence/restart: kill after page k of a retry, restart, repeat the request: same job, only pages after k are read; a retry with a different method ignores committed pages of the other fingerprint.
- Implement.
- Run; expect PASS.

## Focused verification

```sh
cd /Users/patrik/projects/infoscry
./gradlew test -PskipFrontend --tests 'infoscry.jobs.RetryJobHandlerTest' --tests 'infoscry.server.DocumentRetryRoutesTest'
./gradlew check
```

## Out of scope

Web retry UI (ticket 14); removing the old `ocr` override object from other consumers
beyond what the retry route needs.
