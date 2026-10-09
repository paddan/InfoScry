# 02: A new rescan replaces a stopped one

**Status:** Implemented; automated gates passed (2026-10-09). See [verification record](STATUS.md#verification-record).
**Blocked by:** None.
**Plan:** [OCR workflow redesign, Task 2](../../plans/2026-10-08-ocr-workflow-redesign.md). Read first: this ticket, [CONTRACTS.md](CONTRACTS.md) (sections 6, 12, 13), `AGENTS.md`, the [spec](../../specs/2026-10-08-ocr-workflow-redesign.md) ("Always restartable").

## Problem

`RescanService.admitRescan` throws `OcrOperationConflictException` when the document
has a stopped, failed, interrupted or waiting operation, so Scan again can be blocked
by a previous run.

## Deliverable

`admitRescan` never refuses because of a previous operation of the same document. It
ends that operation as `CANCELLED` and admits the new one in one transaction. A live
attempt is cancelled through `cancel()` first. The published text is unchanged by the
replacement. The document moves to `QUEUED` atomically with creation of its job, so
a completed document cannot be shown as done while the admitted reading is waiting
for the worker. Replaying the same start returns the same operation without
rewriting a later document status.

## Can be built against

Nothing else. The cleanup of old rows is ticket 01 and is not needed here; tests build
the stopped operations directly with the store.

## Files and interfaces

- Modify `src/main/kotlin/infoscry/document/RescanService.kt` (`admitRescan` ~:566, `resume` ~:685) and `src/main/kotlin/infoscry/storage/OcrOperationStore.kt` (`admit`, `activeOperation`).
- Test `src/test/kotlin/infoscry/jobs/RescanJobHandlerTest.kt` (this file has uncommitted edits: read and keep them).
- Implement the replacement inside `OcrOperationStore.admit`: cancel the active row, then insert. Keep the partial unique index.

## Original test-first implementation plan

- Write failing tests: `a new rescan is admitted over a failed operation`, `a new rescan is admitted over an operation left in OCR by a killed process`, `a new rescan is admitted over a running operation, which is cancelled`, `repeating admit with the same request id returns the operation that exists`, `the document's published text is unchanged by the replaced operation`.
- Run them; expect FAIL with the conflict exception.
- Idempotence/restart tests: admission interrupted after the old row is cancelled but before the new row is inserted (single transaction, so assert no partial state remains: the old operation is still active and the retry succeeds); two concurrent admits with different request ids leave exactly one active operation; two with the same id return the same operation.
- Implement in `OcrOperationStore.admit`.
- Run `infoscry.jobs.*`, `infoscry.document.*`, `infoscry.server.Ocr*`; expect PASS.

## Focused verification

```sh
cd /Users/patrik/projects/infoscry
./gradlew test -PskipFrontend --tests 'infoscry.jobs.*' --tests 'infoscry.document.*' --tests 'infoscry.server.Ocr*'
./gradlew check
```

## Out of scope

Import replacement of a stopped import; choosing a method (ticket 08); retry (ticket 11);
startup cleanup (ticket 01).
