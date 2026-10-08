# 08: Rescan preview and admission take a method

**Status:** Not started
**Blocked by:** None for logic. It uses `ReadingMethod.kt` (CONTRACTS section 1) and `ReadingMethodCatalog.require` (build against a fake until ticket 03 lands; the real reason strings come from 03).
**Plan:** [OCR workflow redesign, Task 8](../../plans/2026-10-08-ocr-workflow-redesign.md). Read first: this ticket, [CONTRACTS.md](CONTRACTS.md) (sections 1, 7, 12, 13), `AGENTS.md`, the [spec](../../specs/2026-10-08-ocr-workflow-redesign.md) ("Preview").

## Problem

Rescan preview merges field-by-field overrides (engine, profile, mode, reviewer) with the
collection's settings, which causes the engine/profile mismatch bug, and the preview
returns an approval requirement that is resolved mid-run.

## Deliverable

`RescanService.preview(collectionId, documentId, method)` and
`RescanPreviewRequest(method: String)`. The preview's `externalPageUpperBound` is the page
total; `approvalRequired` is removed; the admitted snapshot's `externalPageLimit` = page
total. `withOverrides` and `RescanOverrides` are deleted.

## Can be built against

CONTRACTS section 7. A fake `ReadingMethodCatalog` covers availability. Retry (ticket 11)
currently calls `resolveChosenReading`; if that function still exists when this ticket
lands, keep it compiling by routing it through the method (do not delete what the retry
path still uses; ticket 11 finishes that).

## Files and interfaces

- Modify `src/main/kotlin/infoscry/document/RescanService.kt` (`preview` :436, `admitRescan`, `withOverrides` ~:1700, `requirePreviewStillCurrent`; uncommitted edits exist: read and keep them), `src/main/kotlin/infoscry/server/OcrRoutes.kt` (`RescanPreviewRequest` :35).
- Test `src/test/kotlin/infoscry/jobs/RescanJobHandlerTest.kt`.

## Test-first implementation

- [ ] Write failing tests: previewing with each method yields a consistent snapshot; an LLM method without an available profile is refused with the reason from the catalog; admission is idempotent per request id; a changed profile revision between preview and admit raises `StaleRescanPreviewException`.
- [ ] Run; expect FAIL.
- [ ] Idempotence/restart: admit twice with the same id gives one operation; admission interrupted before commit leaves no operation and the repeat succeeds; preview twice gives equal results and writes no operation.
- [ ] Implement; delete the override merging and its helpers.
- [ ] Run `infoscry.jobs.Rescan*`, `infoscry.document.*`, `infoscry.server.Ocr*`; expect PASS.

## Focused verification

```sh
cd /Users/patrik/projects/infoscry
./gradlew test -PskipFrontend --tests 'infoscry.jobs.Rescan*' --tests 'infoscry.document.*' --tests 'infoscry.server.Ocr*'
./gradlew check
```

## Out of scope

Replacing a stopped operation (ticket 02); removing approval routes and the mid-run pause
(ticket 09); web client (ticket 12); retry (ticket 11).
