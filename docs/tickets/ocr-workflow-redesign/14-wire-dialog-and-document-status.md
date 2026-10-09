# 14: Use the dialog for import and Scan again; per-document status

**Status:** Implemented; automated gates passed (2026-10-09). See [verification record](STATUS.md#verification-record).
**Blocked by:** 12 (`documentStatus`, client changes) and 13 (the dialog component). It can be started against a stub component with the CONTRACTS section 11 props and a mocked `documentStatus` signature, then switched to the real ones.
**Plan:** [OCR workflow redesign, Task 14](../../plans/2026-10-08-ocr-workflow-redesign.md). Read first: this ticket, [CONTRACTS.md](CONTRACTS.md) (sections 11, 12, 13), `AGENTS.md`, the [spec](../../specs/2026-10-08-ocr-workflow-redesign.md) (decisions 1-2, 7).

## Problem

Import and Scan again use separate engine/mode/approval controls, and a document does not
show one status and one next action.

## Deliverable

Import and Scan again open `StartReadingDialog`. Each document row shows its
`documentStatus` and its one next action. A failed or cancelled document offers
Scan again/Retry immediately. An in-progress run shows `Reading n of N` and
"Cancel and start over" in the dialog. Approval and review UI is deleted.

## Can be built against

`StartReadingDialog` (props in CONTRACTS section 11) and `documentStatus` (same section);
panel tests mock the dialog and the api module.

## Files and interfaces

- Modify `web/src/lib/ImportPanel.svelte`, `web/src/lib/CollectionsPanel.svelte`, `web/src/lib/DocumentRescan.svelte` (these have uncommitted edits: read and keep what still applies).
- Delete `web/src/lib/JobApproval.svelte` (+ its test), `web/src/lib/OcrReviewPanel.svelte` (+ its test), and the discard and approval UI in `DocumentRescan.svelte`. `DocumentRescan` no longer renders `OcrChoiceFields` (the dialog replaces it); deleting `OcrChoiceFields.svelte` itself is ticket 15 or 17.
- Tests `ImportPanel.test.ts`, `CollectionsPanel.test.ts`, `DocumentRescan.test.ts`.

## Original test-first implementation plan

- Write failing tests: starting an import goes through the dialog and sends the method and the preview hash; Scan again after a failed run needs no prior action; no text anywhere mentions approval or review; cancel updates the row at once.
- Run `cd web && npm test -- --run`; expect FAIL.
- Idempotence: clicking Retry/Scan again twice quickly opens one dialog and starts one run; cancelling an already cancelled document leaves the row in `cancelled` with no error shown.
- Implement and delete.
- Run tests and `npm run check`; expect PASS.

## Focused verification

```sh
cd /Users/patrik/projects/infoscry/web && npm test -- --run && npm run check
```

## Out of scope

Collection settings (ticket 15); CLI; deleting backend review code (ticket 17); browser acceptance (ticket 18).
