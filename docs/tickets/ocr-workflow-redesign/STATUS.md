# OCR workflow redesign: status

[Plan](../../plans/2026-10-08-ocr-workflow-redesign.md) · [Spec](../../specs/2026-10-08-ocr-workflow-redesign.md) · [Shared contracts](CONTRACTS.md)

Each ticket is meant to be built by reading only its own file, CONTRACTS.md, `AGENTS.md`
and the spec. "Blocked by" lists true minimums; where a ticket can start earlier against
a fake or a pinned contract, its "Can be built against" section says so.

| Ticket | Blocked by | State |
|---|---|---|
| [01 - Idempotent startup cleanup of stale OCR state](01-stale-state-startup-cleanup.md) | None | Implemented; automated gates passed |
| [02 - A new rescan replaces a stopped one](02-new-rescan-replaces-stopped.md) | None | Implemented; automated gates passed |
| [03 - ReadingMethod and the list of available methods](03-reading-method-and-list-route.md) | None | Implemented; automated gates passed |
| [04 - Collection stores language and a default method only](04-collection-default-method.md) | None (shares `ReadingMethod.kt` with 03) | Implemented; automated gates passed |
| [05 - Page counts and the import preview](05-import-preview-and-page-counts.md) | None (fake `ReadingMethodCatalog` until 03) | Implemented; automated gates passed |
| [06 - Start an import with a method and a confirmed preview](06-start-import-with-method-and-hash.md) | 05 (fake `hashOf` possible) | Implemented; automated gates passed |
| [07 - Read every page; no import mode](07-read-every-page.md) | None | Implemented; automated gates passed |
| [08 - Rescan preview and admission take a method](08-rescan-takes-a-method.md) | None (fake `ReadingMethodCatalog` until 03) | Implemented; automated gates passed |
| [09 - No mid-run pause; stop at the confirmed pages](09-no-mid-run-pause.md) | 01 | Implemented; automated gates passed |
| [10 - Publish on completion; no review stage](10-publish-on-completion.md) | 01 | Implemented; automated gates passed |
| [11 - Retry uses a method and resumes committed pages](11-retry-with-method-and-resume.md) | 02, 08, 09 | Implemented; automated gates passed |
| [12 - Web API client and document status model](12-web-api-client-and-document-status.md) | None (fetch mocked) | Implemented; automated gates passed |
| [13 - StartReadingDialog.svelte](13-start-reading-dialog.md) | 12 (types only) | Implemented; automated gates passed |
| [14 - Use the dialog for import and Scan again; per-document status](14-wire-dialog-and-document-status.md) | 12, 13 | Implemented; automated gates passed |
| [15 - Collection settings: language and default method](15-collection-settings-ui.md) | 12 (types only) | Implemented; automated gates passed |
| [16 - CLI import with a method](16-cli-import-method-and-yes.md) | 04, 05, 06 | Implemented; automated gates passed |
| [17 - Delete review and approval code, update docs](17-delete-review-and-approval-code.md) | 08, 09, 10, 14, 15, 16 | Implemented; automated gates passed |
| [18 - Acceptance](18-acceptance-rewrite.md) | 11, 14, 15, 16, 17 | Implemented; automated gates passed |

## Dependency graph

```
Backend
  01 ──┬──► 09 ──┐
       └──► 10   ├──► 11 ◄── 02
  08 ───────────►┘     (11 also needs 08)
  03, 04, 07 (leaves)
  05 ──► 06 ──┐
  04, 05 ─────┴──► 16
Web
  12 ──► 13 ──► 14
  12 ──► 15
Closing
  08, 09, 10, 14, 15, 16 ──► 17 ──► 18   (18 also needs 11)
```

## What can run in parallel

- Wave 1 (no blockers, different files): 01, 02, 03, 04, 05, 07, 08, 12.
- Wave 2: 06 (after 05), 09 and 10 (after 01), 13 and 15 (after 12 or against the CONTRACTS types), 11 (after 02, 08, 09), 16 (after 04, 05, 06), 14 (after 12, 13).
- Wave 3: 17, then 18.
- Same-file hazards (not logical dependencies; serialize or rebase): `OcrModels.kt` in 04, 07, 08; `RescanService.kt` in 02, 05, 08; `RetryJobHandler.kt` in 09, 11; `RescanJobHandler.kt` in 09, 10; `Routes.kt` in 03, 04, 05, 06; `ImportJobHandler.kt` in 05, 09; `api.ts` in 12, 13, 15; `OcrChoiceFields.svelte` and `ocrRescan.ts` in 14, 15.
- Phase 1 (01, 02) is useful on its own and can ship before the rest.
- Implementation began on a clean `main` checkout. All current edits belong to this redesign.

## Implementation decisions and remaining product ideas

1. **Migration approach.** Default adopted from the plan: no schema migration script. Removed collection columns stay in `001_baseline.sql`, unread and unwritten; stale-state cleanup (ticket 01) is an idempotent startup routine. The spec says "by migration or on first start", so this is allowed. No migration script was added.
2. **Import-request idempotency storage.** The plan leaves it to ticket 06 ("reuse the `ocr_operations` request-hash pattern or a small `import_requests` table"). CONTRACTS section 5 adopts a generic `start_requests` table with `CREATE TABLE IF NOT EXISTS`, shared by tickets 06 and 11. This is the implemented choice.
3. **`previewHash` encoding.** The plan names the inputs; CONTRACTS section 4 pins the exact byte encoding.
4. **New error codes** `METHOD_UNAVAILABLE` and `REQUEST_ID_CONFLICT` (409) and the `ReadingMethodCatalog` name are additions made in CONTRACTS because the plan did not name them.
5. **Retry page total.** The plan does not say how a retry with a new method gets its confirmed page total; ticket 11 uses `PageCounter` (set `externalPageLimit` = the document's page count) and keeps the failed run's snapshot when no method is given.
6. Spec open items (not decided): a better cost estimate than the existing per-page token assumption; an Undo link after a run.

## Approved clarifications (2026-10-09)

- Unknown external import page totals: confirmation covers every page in the
  enumerated, unchanged files; no exact total cost is shown. Resume retains this
  scope only while the managed bytes match the recorded SHA-256.
- CLI stopped imports: repeating the command starts a fresh attempt after
  `FAILED` or `CANCELLED`; active and completed jobs still replay. Browser/HTTP
  requests retain exact request replay unless the caller opts into this CLI behavior.

## Verification record

Verified on Patrik's Mac on 2026-10-09:

- `./gradlew check externalTest`: PASS, 1,534 normal JVM tests and 36 external
  tests, no failures or skipped tests; 336 frontend tests also pass.
- `npm run check`: zero errors and warnings. After the final Retry help-text
  correction, the 79 Collections tests and typecheck passed again.
- Browser coverage: Collections 14, OCR 11, Investigate 8 and Search 1.
  The OCR and Collections scenarios use fake extraction/reading engines and
  deterministic embeddings; the two independent real-tool tests cover
  Tesseract and Surya on redistributable fixtures.
- Reviewed backend admission, immutable source scope, recovery/publication,
  retry checkpoint reuse and frontend composition. The findings were fixed
  and covered by regressions.
- Documentation links, browser-script syntax and `git diff --check` passed.

The original test-first procedures remain in the individual tickets as plans;
this record describes the executed verification. Real external-provider OCR,
real CoreML in the complete workflow, native picker, keyboard/screen-reader
checks, Ask browser acceptance and the source-viewer finish line remain open.
No private document was sent to an external provider in these tests.
