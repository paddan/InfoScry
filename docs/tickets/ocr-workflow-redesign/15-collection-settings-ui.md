# 15: Collection settings: language and default method

**Status:** Implemented; automated gates passed (2026-10-09). See [verification record](STATUS.md#verification-record).
**Blocked by:** 12 for `listReadingMethods` and `ReadingMethodOption` (declare exactly CONTRACTS section 11 types if 12 has not landed). The backend PATCH shape is CONTRACTS section 3; tests mock the api.
**Plan:** [OCR workflow redesign, Task 15](../../plans/2026-10-08-ocr-workflow-redesign.md). Read first: this ticket, [CONTRACTS.md](CONTRACTS.md) (sections 2, 3, 11, 12, 13), `AGENTS.md`, the [spec](../../specs/2026-10-08-ocr-workflow-redesign.md) ("Collection settings").

## Problem

The collection settings expose engine, profiles, mode, reviewer and an allowance.

## Deliverable

Settings show only the OCR language and the default-method list; saving sends
`{ language, defaultMethod }`; unavailable methods are disabled with their reasons; the
allowance hint added earlier is dropped.

## Can be built against

CONTRACTS section 3 (PATCH body) and section 2 (list), with the api mocked.

## Files and interfaces

- Modify `web/src/lib/CollectionOcrSettings.svelte`, `web/src/lib/OcrChoiceFields.svelte` (delete or reduce it to the method list; delete it only if no other component still imports it, otherwise leave the rest for ticket 17), `web/src/lib/ocrRescan.ts` (uncommitted edits exist: read first).
- Test `web/src/lib/CollectionOcrSettings.test.ts`.

## Original test-first implementation plan

- Write failing tests: only language and the default-method list are shown; saving sends `{ language, defaultMethod }`; unavailable methods are disabled with reasons.
- Run `cd web && npm test -- --run`; expect FAIL.
- Idempotence: saving unchanged values twice sends the same body and shows no error; a collection with `default: null` shows no selection and does not block saving the language.
- Implement; drop the allowance hint.
- Run tests and `npm run check`; expect PASS.

## Focused verification

```sh
cd /Users/patrik/projects/infoscry/web && npm test -- --run && npm run check
```

## Out of scope

The dialog (13, 14); backend storage (04).
