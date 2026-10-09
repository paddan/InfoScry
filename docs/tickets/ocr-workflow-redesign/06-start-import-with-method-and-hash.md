# 06: Start an import with a method and a confirmed preview

**Status:** Implemented; automated gates passed (2026-10-09). See [verification record](STATUS.md#verification-record).
**Blocked by:** 05, because `POST /api/imports` calls `ImportPreviewService.hashOf` (it can be built first against a fake implementing `hashOf(request): String` from CONTRACTS section 4, but the real hash is only meaningful once 05 lands). `ReadingMethod.kt` per CONTRACTS section 1.
**Plan:** [OCR workflow redesign, Task 6](../../plans/2026-10-08-ocr-workflow-redesign.md). Read first: this ticket, [CONTRACTS.md](CONTRACTS.md) (sections 1, 4, 5, 6, 12, 13), `AGENTS.md`, the [spec](../../specs/2026-10-08-ocr-workflow-redesign.md) ("Preview", "Always restartable").

## Problem

An import starts from collection defaults, can be started twice by a double click, and
can read a different set of files than the person confirmed.

## Deliverable

`POST /api/imports` accepts `method`, `previewHash`, `requestId`; builds the snapshot
from the method; refuses a stale hash with 409 `PREVIEW_STALE`; and returns the same job
for a repeated or concurrent request.

## Can be built against

`ImportPreviewService.hashOf` as a fake until 05 lands; `ReadingMethodCatalog` as a fake
until 03 lands. The idempotency store is specified in CONTRACTS section 5 (create
`StartRequestStore` if it does not exist yet; ticket 11 reuses it).

## Files and interfaces

- Modify `src/main/kotlin/infoscry/server/Routes.kt` (`ImportRequest` :188, `POST /api/imports` :357-402), `src/main/kotlin/infoscry/jobs/ImportJobPayload.kt`.
- Modify `src/main/kotlin/infoscry/cli/ImportCommand.kt` only as far as needed to compile and keep its current behavior (it constructs the new request fields from the collection's current engine/profile via the mapping in ticket 03; the full `--method`/`--yes` behavior is ticket 16).
- Create `src/main/kotlin/infoscry/storage/StartRequestStore.kt` (CONTRACTS section 5) if absent.
- Bind the queued payload to the exact confirmed source manifest (path, byte size and SHA-256). The worker
  validates that manifest before copying and validates the managed bytes before reading.
- For an external method with known page counts, `externalPageLimit` is the previewed `totalPages`. If any
  file's count is unknown, the snapshot uses numeric limit `0` and `externalConfirmedSourceScope = true`;
  this authorizes all pages only from the unchanged manifest the dialog showed. The worker must ignore that
  flag unless the manifest is attached and verified.
- Unknown counts cannot produce an exact external cost estimate. The preview returns `estimatedCostUsd: null`
  with a reason instead of pricing only the known-page subtotal.
- Test `src/test/kotlin/infoscry/server/ImportRoutesTest.kt` (create if absent, else extend the existing import route test).

## Original test-first implementation plan

- Write failing tests: start with a matching hash enqueues one job whose payload snapshot has the method and the page total; a changed file between preview and start gives 409 `PREVIEW_STALE` and queues nothing; the same request id twice gives one job and the same response; same request id with a different body is 409 `REQUEST_ID_CONFLICT`; double-submit race (two concurrent requests, same id) gives one job.
- Review focus 5: changed files, changed method, or a changed profile revision between preview and start are each refused, never read as a different page set.
- Run; expect FAIL.
- Restart test: after the job row is created, restarting the process and repeating the same request returns that job.
- Implement.
- Run; expect PASS; run `infoscry.cli.*` to prove the CLI still works.

## Focused verification

```sh
cd /Users/patrik/projects/infoscry
./gradlew test -PskipFrontend --tests 'infoscry.server.ImportRoutesTest' --tests 'infoscry.jobs.Import*' --tests 'infoscry.cli.*'
./gradlew check
```

## Out of scope

READ_ALL (ticket 07); removing the approval pause (ticket 09); CLI flags and summary
(ticket 16); the web dialog (tickets 13, 14).
