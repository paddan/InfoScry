# 13: `StartReadingDialog.svelte`

**Status:** Implemented; automated gates passed (2026-10-09). See [verification record](STATUS.md#verification-record).
**Blocked by:** 12 for the types and client function names only (`ReadingMethodOption`, `ImportPreview`, `listReadingMethods`, `previewImport`, `startImport`, `previewRescan`, `startRescan`). If 12 has not landed, add exactly those declarations from CONTRACTS section 11 to `web/src/lib/api.ts` and let 12 keep them.
**Plan:** [OCR workflow redesign, Task 13](../../plans/2026-10-08-ocr-workflow-redesign.md). Read first: this ticket, [CONTRACTS.md](CONTRACTS.md) (sections 11, 12, 13), `AGENTS.md`, the [spec](../../specs/2026-10-08-ocr-workflow-redesign.md) ("Start dialog", "Preview").

## Problem

Import and Scan again decide the method in different places, without a summary and
without one confirmation.

## Deliverable

One dialog that loads methods and the preview, pre-selects the collection default, shows
pages, destination, cost and a confirm label naming the consequence, disables unavailable
methods with their reasons, re-previews on method change, and submits exactly once.

## Can be built against

Mock `$lib/api` with `vi.mock` per CONTRACTS section 11; no backend, no panel ticket. Props
are fixed in CONTRACTS section 11.

## Files and interfaces

- Create `web/src/lib/StartReadingDialog.svelte`, `web/src/lib/StartReadingDialog.test.ts`.
- Props `{ collectionId, request, onstarted(run), oncancel() }` (CONTRACTS section 11).
- Confirm labels: `Read 48 pages with Surya`, `Send 48 pages to <host> with <profile>`, `Read at least 48 pages ...` when `atLeast` (CONTRACTS section 11).
- A new `requestId` per dialog open (generated once at open, reused for every submit from that open); the confirm button is disabled while submitting.
- For rescan: `previewRescan` then `startRescan({previewId, requestId})`; for import: `previewImport` then `startImport({...request, method, previewHash, requestId})`.
- Plain HTML/CSS, English copy, accessible (labelled radio group or `<select>`, `role="dialog"`, focus management, disabled options carry their reason as visible text).

## Original test-first implementation plan

- Write failing tests: default pre-selected; unavailable method shows its reason and cannot be chosen; changing the method re-previews; confirm label for local, external and `atLeast`; double click submits once; a `PREVIEW_STALE` response triggers a re-preview with the message "The files changed; review the summary again."
- Run `cd web && npm test -- --run`; expect FAIL.
- Idempotence: reopening the dialog creates a new `requestId`; submitting twice from one open sends the same `requestId` (a retry after a network error does not start a second run); an unknown cost shows the cost basis text instead of a number.
- Implement.
- Run tests and `npm run check`; expect PASS.

## Focused verification

```sh
cd /Users/patrik/projects/infoscry/web && npm test -- --run && npm run check
```

## Out of scope

Placing the dialog in panels and per-document status (ticket 14); settings (ticket 15).
