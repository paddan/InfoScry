# 12: Web API client and document status model

**Status:** Implemented; automated gates passed (2026-10-09). See [verification record](STATUS.md#verification-record).
**Blocked by:** None. The backend routes are not required: tests mock `fetch` per CONTRACTS section 11.
**Plan:** [OCR workflow redesign, Task 12](../../plans/2026-10-08-ocr-workflow-redesign.md). Read first: this ticket, [CONTRACTS.md](CONTRACTS.md) (sections 2, 4, 5, 7, 11, 12, 13), `AGENTS.md`, the [spec](../../specs/2026-10-08-ocr-workflow-redesign.md) ("Run and document status").

## Problem

The web client has approval, review and discard calls and no client for the new routes;
nothing computes one status and one next action per document.

## Deliverable

New client functions and types exactly as CONTRACTS section 11; the old approval, review
and discard clients removed; and `documentStatus(doc, job?)`.

## Can be built against

CONTRACTS sections 2, 4, 7 and 11 (request/response shapes) with `fetch` mocked in tests.

## Files and interfaces

- Modify `web/src/lib/api.ts`: add `listReadingMethods`, `previewImport`, `startImport`, `previewRescan(method)`, `startRescan`, types `ReadingMethodOption`, `ReadingMethodList`, `ImportPreview`, `ImportPreviewRequest`; a 409 `PREVIEW_STALE` becomes an `ApiError` with that code; remove approval, review and discard clients. Components still importing removed functions are fixed by ticket 14; to keep `npm run check` green in this ticket, delete only the functions whose callers are already gone and otherwise report the remaining callers in the handover.
- Create `web/src/lib/documentStatus.ts` (CONTRACTS section 11).
- Tests `web/src/lib/api.test.ts`, `web/src/lib/documentStatus.test.ts`.

## Original test-first implementation plan

- Write failing tests for the URL, method and body of each client function, and for every status mapping in CONTRACTS section 11 including a failed run with a cause label; a `PREVIEW_STALE` response surfaces `code === 'PREVIEW_STALE'`.
- Run `cd web && npm test -- --run`; expect FAIL.
- Idempotence: `documentStatus` is a pure function (same input, same output); `startImport` sends the caller's `requestId` unchanged on every call.
- Implement.
- Run tests and `npm run check`; expect PASS.

## Focused verification

```sh
cd /Users/patrik/projects/infoscry/web && npm test -- --run && npm run check
```

## Out of scope

The dialog (ticket 13); panels (ticket 14); settings (ticket 15); backend.
