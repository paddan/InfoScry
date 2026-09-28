# 12: Verify the complete Collections workflow

**What to build:** Establish verified end-to-end evidence that the user can
maintain collections and documents entirely through Admin Collections, retain
truthful processing state across interruptions, and recover without losing other
documents or repeating compatible committed OCR. Publish accurate local
documentation and acceptance status.

**Blocked by:** 03 — Restore durable import history;
04 — Show durable processing and OCR progress;
08 — Delete selected documents together;
10 — Retry all eligible documents in a collection;
11 — Retire automatic Default safely.

- [x] Real-browser acceptance against a local server and fake pipeline/provider
      exercises empty start, collection creation, add documents, duplicates,
      filtered/paginated listing, details and reader opening.
- [x] Browser coverage verifies durable import/processing state after navigation
      and reload, settings changes, single/bulk Retry, single/bulk deletion,
      collection deletion during import and restored deletion status.
- [x] Legacy Default acceptance covers automatic unused cleanup and preservation
      with explicit rename/delete choices when user state exists.
- [x] A mixed direct-text/OCR fixture demonstrates honest page counters and
      unknown-total/unit labels. Interrupted/resumed work does not double count
      committed progress or repeat compatible successfully committed OCR.
- [x] Recovery acceptance covers deletion boundaries, restart and multi-file
      continuation without resurrecting targets. Other collections and external
      originals remain untouched; historical source links report unavailable
      content after deletion.
- [x] The automated keyboard and layout evidence that stands in for a manual pass
      is recorded: the `collection-creation` scenario asserts that the exact-name
      confirmation takes focus, that Escape declines it and returns focus to the
      opener, and that a 420px window keeps the mode tabs in one row with no
      sideways page scroll; `documents-and-history` asserts that the wide table
      scrolls inside the panel at that width; `settings-and-retry` asserts that a
      typed rename conflict is readable; `svelte-check` reports no errors or
      warnings. Progress updates and preserved LLM profiles are asserted by the
      same scenarios.
- [ ] A **person-driven keyboard/screen-reader pass** over that panel is still
      open. No person drove the reader by keyboard or with a screen reader, so
      this criterion is not verified; it is recorded as an open gate below.
- [x] Meaningful focused suites, the accumulated offline check, frontend
      diagnostics/build and relevant browser/external checks finish successfully;
      hangs or environmental failures are reported separately, not as passing.
- [x] Real Tesseract and required CoreML behavior are verified on the local Mac
      with redistributable fixtures and temporary data, by their own passing gates
      (`externalTest` for `TesseractRealToolTest`, `gpuIntegrationTest` for the
      real pinned model on CoreML). Fake-pipeline/browser results alone do not
      close hardware/tool gates, and no private archive is sent to an external
      provider.
- [ ] The **real native picker dialog** is not verified by any test: the
      acceptance server is handed a fake picker that returns the fixture paths,
      and no picker capability is claimed from that fake. A person at a graphical
      session still has to confirm the real dialog opens and returns the chosen
      paths. It stays a manual gate below.
- [x] README records final commands, Collections behavior and actual verification
      limits. Ticket status records completed evidence and any still-open gates;
      unrelated Ask/source-viewer acceptance is not closed by implication.
- [x] Local changes are scoped and existing unrelated PDF/build work remains
      intact. No CI, packaging, release, dependency upgrade or LLM behavior change
      is introduced as part of acceptance.

**Status:** Done for the implementation and the automated evidence — verified on
this Mac on 2026-09-28 with
`JAVA_HOME=/Users/patrik/.asdf/installs/java/temurin-25.0.4+101.0.LTS ./gradlew check`
(BUILD SUCCESSFUL, 12m51s; 1023 offline JVM tests, 0 failed; Vitest 187 passed;
`verifyTestTags` passed),
`./gradlew test --tests 'infoscry.server.*' --tests 'infoscry.storage.*' --tests 'infoscry.jobs.*' --tests 'infoscry.collection.*' --tests 'infoscry.search.*' --tests 'infoscry.document.*'`
(BUILD SUCCESSFUL, 8m32s; 402 tests, 0 failed),
`./gradlew externalTest` (BUILD SUCCESSFUL, 1m27s;
`CollectionsBrowserAcceptanceTest` 8, `InvestigateBrowserAcceptanceTest` 7, real
`TesseractRealToolTest` 1; 0 failed),
`./gradlew gpuIntegrationTest` (BUILD SUCCESSFUL, 48s; the real pinned model on
CoreML, unchanged by this ticket), and
`cd web && npx vitest run` (187 passed) / `npm run check` (0 errors, 0 warnings) /
`npm run build` (site written to `web/build`).

Two criteria remain unchecked, because both need a person rather than a test:
the real native pick file/folder dialog never was opened, and no human
keyboard/screen-reader pass was performed. The still-open gates below say what
that leaves unverified.

After this acceptance, an independent review of the finished epic found four UI
defects, each fixed with a focused test that fails without it: a settings save
resolving after another collection was selected, the job-items response carrying
the persisted source path, stale workspace results after a deleted collection's
selection moved, and Tab escaping the confirmation dialogs. The ticket's own
commands were re-run after those fixes: `./gradlew test --tests 'infoscry.cli.*'
--tests 'infoscry.server.*'` (BUILD SUCCESSFUL; 250 tests, 0 failed; `JobRoutesTest`
now asserts the items response carries no source path), `./gradlew externalTest`
(BUILD SUCCESSFUL, 1m28s; 16 tests, 0 failed), `cd web && npx vitest run` (192
passed), `npm run check` (0 errors, 0 warnings) and `npm run build`.

## What is verified, and how

`src/test/kotlin/infoscry/server/CollectionsBrowserAcceptanceTest` drives the real
built reader in Chromium through `web/e2e/collections-browser-acceptance.mjs`
against a real local server, a real SQLite archive, the real durable unit store
and the real import job path. Two things are substituted through seams production
already has: the extraction pipeline (`CollectionsAcceptancePipeline.kt`, a fake
extractor) and the document embedder (`TestDocumentEmbedder`). Every fixture is
generated in a temporary directory from text and eight PNG magic bytes.

Eight scenarios:

| Scenario | What the browser proves |
|---|---|
| `collection-creation` | Empty archive, creation inside Admin, workspace-selector refresh, confirmation focus and focus return on decline, 420px layout (one-row mode tabs, no sideways page scroll), LLM profiles preserved, collection durable across a reload |
| `documents-and-history` | 55-file folder import, duplicate reporting, 50-row paging, filename search, status filter that matches nothing, name-desc sort, per-file `Duplicate` outcomes, and both imports and the 56-document count restored after a reload, with the table scrolling inside the panel on a narrow window |
| `document-details-and-reader` | Mixed direct-text/OCR counters (`3/3 pages`, `Read directly 1 page`, `Read by OCR 2 pages`, the denominator explained), an unannounced total as a count without a denominator, a legacy summary reading `0/7 units` with no invented method, the reader opening the first unit, and progress surviving a reload |
| `settings-and-retry` | Rename, a readable typed rename conflict, OCR-language save surviving a reload, one `Retry` and `Retry all eligible documents`, and `Nothing to retry` when nothing is eligible |
| `document-deletion` | Single-row deletion, bulk deletion of the checked page, the viewer closing when its document is removed, a saved source URL answering typed 404 `NOT_FOUND`, and another collection untouched |
| `collection-deletion-during-import` | An import running while the exact-name-confirmed collection deletion is admitted, `Deleting Notes…` restored after a reload, the deletion reaching DONE through the read route, the other collection intact, and the deleted collection's import history gone with it |
| `multi-file-continuation` | A document deleted while its own file is being read, the other files of the same import completing, the removed file keeping `Cancelled`, then a real server restart showing the same durable state |
| `legacy-default` | A used legacy `Default` kept with its explanation and rename/delete choices, the notice following the renamed collection, its document listed, its explicit deletion, and a reader-created `Default` not mistaken for the legacy one |

The Kotlin side asserts what the archive holds: document statuses, committed
progress and unit methods, per-file dispositions, the retry's reuse of committed
units (the acceptance extractor records per-file what each attempt produced and
skipped, so an unchanged retry reuses `unit-0` while a retry after an OCR-language
change repeats it), the retired-versus-preserved `Default`, and that the external
originals still exist.

## Findings from this acceptance

1. **Fixed: a completed document deletion killed the rest of its own import.** The
   browser scenario for multi-file continuation found the whole job `Failed`
   (`0 of 3 files`) when one of its documents was removed while that file was
   between two units. `DocumentIngest` swallowed `DocumentBeingDeletedException`
   and its failure reporter then wrote an item and a status row for a document the
   deletion had already removed, so the exception the handler already handles for
   this case never reached it. It is now rethrown, which is what makes the import
   handler's existing `CANCELLED` disposition reachable; the remaining files run
   normally. `DocumentDeletionRecoveryTest.a document removed while its own file is
   being read cancels that file and the import continues` is the focused test, and
   it fails without the change (`expected: <COMPLETE> but was: <FAILED>`).
2. **Fixed: wide tables pushed a narrow window sideways.** The document and import
   tables now scroll inside the Collections panel (`.table-scroll`), which the
   acceptance asserts at 420px.
3. **Observation, unchanged:** the single-document `Retry queued.` sentence lives
   inside the eligible-document block, so the refresh that follows the submission
   replaces it with the attempt's own status; the queued attempt is what stays
   visible.
4. **Observation, unchanged:** after a reload Admin does not remember which
   collection it was managing (that is page state), so the reader selects it
   again; everything about the collection is durable and comes back.
5. **Observation, unchanged:** a completed document whose extractor never announced
   a total keeps showing a count without a denominator (`1 line processed`),
   because the attempt's own progress row is what the reads use; the finished
   summary's total is only used for archives that have no progress row at all.

## Still-open gates (not closed here)

- The **native macOS pick file/folder dialog** cannot be answered by an automated
  test. The acceptance server is handed a fake picker that returns the fixture
  paths, and the route is covered by its own tests; a person with a graphical
  session still has to confirm the real dialog opens and returns the chosen paths.
  This is a manual gate.
- No **human keyboard/screen-reader pass** was performed. The keyboard and layout
  parts of the manual criterion are covered by the `collection-creation` scenario
  (confirmation focus, focus return, Escape, narrow viewport, table scrolling) and
  by `svelte-check`, not by a person driving a screen reader.
- **Real OCR page progress and real CoreML embeddings** in this workflow were not
  produced by these fixtures: the acceptance extractor stands in for Tesseract and
  `TestDocumentEmbedder` for the pinned model. The machine's real gates are the
  separate `./gradlew externalTest` (`TesseractRealToolTest`) and
  `./gradlew gpuIntegrationTest` (real model on CoreML), both of which passed in
  the run recorded above and neither of which was changed by this ticket.
- **Ask's browser flow and the format-specific source views** still lack
  end-to-end browser acceptance. This ticket does not close them.
