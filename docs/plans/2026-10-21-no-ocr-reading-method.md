# No-OCR Reading Method Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a fourth reading method, **No OCR**, that imports and rescans a document using its own text
and never renders, reads or sends a page.

**Architecture:**
- **Value.** The method is a new `ReadingMethod.None` (id `none`) backed by a new `OcrEngine.NONE`. It
  flows through the existing snapshot, the fingerprint, the collection default, previews and the start
  dialog unchanged.
- **Extractors.** `PdfExtractor` and `ImageExtractor` short-circuit on `NONE`.
- **Rescan.** `RescanPageSource` marks no page for reading on `NONE` and carries each page's own text
  layer; `RescanJobHandler` stages that text.
- **Storage.** Schema version 2 rebuilds `collections` so its CHECK constraint accepts `'NONE'`.

**Tech Stack:** Kotlin/JVM 25, SQLite (xerial JDBC), PDFBox 3, Ktor, SvelteKit/TypeScript, Vitest.

**Spec:** `docs/specs/2026-10-21-no-ocr-reading-method-design.md` (read it first).

## Global Constraints

- API id `none`; label `No OCR (use the document's own text)`; destination `this machine`; `external = false`; always available.
- Engine value `NONE`; stored as `collections.ocr_engine = 'NONE'` with no profile id.
- A No-OCR snapshot uses mode `FILL_MISSING`, never `READ_ALL` or `CHECK_AND_IMPROVE`.
- A page or picture kept without text is counted under the extraction metadata key `pages_without_text`.
  Any such page makes the document `COMPLETE_WITH_WARNINGS`.
- `SchemaMigrator.SUPPORTED_VERSION = 2`. The upgrade from version 1 is idempotent and atomic. A database
  newer than 2 is refused.
- Product copy is in English. Every JVM runs headless (`-Djava.awt.headless=true`).
- Iterate with `./gradlew test -PskipFrontend --tests <class>` and
  `cd web && npm test -- --run && npm run check`. Run `./gradlew check` before reporting done.
- Commit only when the owner asks; the "Commit" steps below mark scope boundaries.

## Review Focus

1. **A collection that already selects an LLM profile, upgraded to version 2.** Its profile id and every
   foreign key to `ocr_profiles` and `collection_prompts` survive. Tested in Task 1.
2. **Collections and collection prompts reference each other.** Dropping and renaming `collections` under
   `PRAGMA foreign_keys=OFF` must not leave dangling or rewritten references. `foreign_key_check` must
   report nothing. Tested in Task 1.
3. **A No-OCR rescan of a document whose scanned page was OCR'd earlier.** That page keeps its OCR text
   rather than being wiped by an empty layer. Tested in Task 5.
4. **A resumed No-OCR import or rescan.** Pages already committed are not staged twice, and the run
   converges. Tested in Tasks 3 and 5.
5. **A preview confirmed with `none` cannot start a Tesseract run.** The preview hash differs by method.
   Tested in Task 4.

---

### Task 1: Schema version 2

**Files:**
- Modify: `src/main/resources/db/migration/001_baseline.sql:35-36`, adding `'NONE'` to the `ocr_engine` CHECK.
- Modify: `src/main/kotlin/infoscry/storage/SchemaMigrator.kt`
- Modify: `docs/development.md`, which documents the one upgrade step.
- Test: `src/test/kotlin/infoscry/storage/SchemaMigratorTest.kt`

**Interfaces:**
- Produces:
  - `SchemaMigrator.SUPPORTED_VERSION = 2`.
  - `internal fun upgradeToVersion2(connection: Connection)`.
  - A new database is created at version 2 directly from the baseline.

- [ ] **Step 1: Write the failing tests** in `SchemaMigratorTest`. Build the version-1 database from a
  frozen copy of today's `collections` DDL, written in the test as a string. Set `user_version = 1`, then
  insert:
  - one collection with `ocr_engine='LLM'` and a profile;
  - a collection prompt that it references;
  - a document;
  - a conversation.

  The tests:
  - `a version 1 database upgrades to version 2 with every row and reference intact`:
    - row counts are equal;
    - the collection's `ocr_transcription_profile_id` and `ask_prompt_id` are unchanged;
    - `PRAGMA foreign_key_check` returns no rows;
    - `user_version == 2`;
    - `schema_version` holds 2.
  - `an upgraded database stores NONE as a collection's engine`: an UPDATE to `'NONE'` succeeds.
  - `upgrading twice changes nothing`: the second `migrate()` leaves `schema_version` with one row for 2.
  - `a failure inside the upgrade leaves version 1 intact`:
    - inject a throwing hook after the copy;
    - `user_version` stays 1 and the original table still rejects `'NONE'`;
    - a following `migrate()` succeeds.
  - `a new database starts at version 2 and accepts NONE`.
- [ ] **Step 2: Run them and see them fail.** Run
  `./gradlew test -PskipFrontend --tests infoscry.storage.SchemaMigratorTest`. Expected: the version-2
  assertions fail.
- [ ] **Step 3: Implement.**
  - `migrate()` runs:
    - `0 → baseline`, which records version 2;
    - `1 → upgradeToVersion2`;
    - `2 → nothing`;
    - `> 2 → SchemaVersionTooNewException`.
  - `upgradeToVersion2`, with `PRAGMA foreign_keys=OFF` set **before** the transaction (SQLite ignores it
    inside one) and restored in `finally`:
    1. create `collections_v2` with the baseline's current `collections` definition;
    2. `INSERT INTO collections_v2 SELECT * FROM collections` with explicit column lists;
    3. `DROP TABLE collections`;
    4. `ALTER TABLE collections_v2 RENAME TO collections`;
    5. recreate `collections_name_unique`;
    6. `PRAGMA foreign_key_check`, where any row throws and rolls back;
    7. `PRAGMA user_version = 2`;
    8. insert into `schema_version`.
  - Before writing this, check the baseline for triggers or views that name `collections` and recreate them
    if any exist.
  - Expose a test-only `afterCopy: () -> Unit = {}` constructor hook.
- [ ] **Step 4: Run the tests and see them pass.** Same command. Expected: all pass.
- [ ] **Step 5: Update the documentation.** Update the `SchemaMigrator` KDoc and `docs/development.md`:
  there is one baseline plus one upgrade step, from 1 to 2. **Commit:** `Add schema version 2 so a collection can default to NONE`.

### Task 2: `ReadingMethod.None` and `OcrEngine.NONE`

**Files:**
- Modify: `src/main/kotlin/infoscry/ocr/ReadingMethod.kt`, adding `data object None`, id `none`, to `parse`.
- Modify: `src/main/kotlin/infoscry/ocr/OcrModels.kt:23-27` (`NONE` in `OcrEngine`) and `:427` (`None -> OcrEngine.NONE`, with mode `FILL_MISSING` when the engine is `NONE`).
- Modify: `src/main/kotlin/infoscry/ocr/ReadingMethodCatalog.kt`, listing `None` first.
- Modify:
  - `src/main/kotlin/infoscry/domain/Models.kt:300`, `NONE -> ReadingMethod.None`;
  - `src/main/kotlin/infoscry/storage/CollectionStore.kt:155`, `None -> OcrEngine.NONE to null`;
  - `src/main/kotlin/infoscry/document/RescanService.kt:1040`;
  - `src/main/kotlin/infoscry/jobs/ImportPreviewService.kt:136`.
- Modify: every other exhaustive `when` the compiler reports. `NONE` has no `PageOcrEngine`, so
  `PageOcrEngines.transcribe` with `NONE` throws `IllegalStateException("No OCR reads no page")`.
- Test:
  - `src/test/kotlin/infoscry/ocr/ReadingMethodTest.kt` (create if absent);
  - the existing catalog and collection-settings tests (`grep -rln "ReadingMethod.Surya" src/test`);
  - `src/test/kotlin/infoscry/ocr/OcrSettingsTest.kt`.

**Interfaces:**
- Produces:
  - `ReadingMethod.None` with `id == "none"`;
  - `OcrEngine.NONE`;
  - `OcrSettingsSnapshot.of(CollectionOcrSettings(lang, ReadingMethod.None), …)` with `engine == NONE` and `mode == FILL_MISSING`;
  - `ExtractionSettings.readsPageImages(): Boolean = readingEngine() != OcrEngine.NONE`, in `DocumentExtractor.kt`.

- [ ] **Step 1: Write the failing tests.**
  - `none parses and serializes as none`.
  - `the catalog always offers No OCR on this machine`: label
    `No OCR (use the document's own text)`, `available`, `!external`, destination `this machine`, with
    Tesseract made unavailable through the `localUnavailableReason` seam.
  - `a no-OCR snapshot reads nothing and fingerprints apart from Tesseract`: the engine is `NONE`, the
    mode is `FILL_MISSING`, and `ExtractionFingerprint.of(sha, settingsFor(None)) != …(Tesseract)`.
  - `a collection defaults to No OCR and reads it back`: run through `CollectionStore.updateOcrSettings`
    and through the collection settings route test.
- [ ] **Step 2: Run them and see them fail.** Compilation fails on `ReadingMethod.None`.
- [ ] **Step 3: Implement** the cases above. Let the compiler find every exhaustive `when`.
- [ ] **Step 4: Run the tests and see them pass.**
  `./gradlew test -PskipFrontend --tests 'infoscry.ocr.*' --tests 'infoscry.storage.CollectionStore*' --tests 'infoscry.collection.*'`
- [ ] **Step 5: Commit:** `Add the No OCR reading method value`.

### Task 3: Extraction under `NONE`

**Files:**
- Modify: `src/main/kotlin/infoscry/extract/DocumentExtractor.kt`, adding
  `const val PAGES_WITHOUT_TEXT_METADATA = "pages_without_text"` beside `PAGE_BLANK_CODE`.
- Modify: `src/main/kotlin/infoscry/extract/PdfExtractor.kt`, in `emitPages`:
  - when `!input.settings.readsPageImages()`, every page emits `emitTextUnit(..., text = text.orEmpty(), method = DIRECT_TEXT)`;
  - a page where `text == null || PdfPageCandidate.needsOcr(text)` increments `run.pagesWithoutText`;
  - put the count in `metadata()`;
  - render nothing.
- Modify: `src/main/kotlin/infoscry/extract/ImageExtractor.kt`. Under `NONE`, emit one unit with empty
  text, locator `SourceLocation.Image`, and `Finished` metadata `pages_without_text=1`. Do not call OCR.
- Modify: `src/main/kotlin/infoscry/jobs/DocumentIngest.kt`, where `keptWithoutReading` is true when
  `Finished.metadata` has `EMPTY_READING_PAGES_METADATA` **or** `PAGES_WITHOUT_TEXT_METADATA`.
- Test:
  - `src/test/kotlin/infoscry/extract/PdfExtractorTest.kt`;
  - `src/test/kotlin/infoscry/extract/ImageExtractorTest.kt`;
  - `src/test/kotlin/infoscry/jobs/ImportJobHandlerTest.kt`.

**Interfaces:**
- Consumes: `ExtractionSettings.readsPageImages()` from Task 2.
- Produces: `PAGES_WITHOUT_TEXT_METADATA`.

- [ ] **Step 1: Write the failing tests.** Settings come from `settingsFor(ReadingMethod.None)`.
  - `no OCR reads no page and keeps every page's own text`. On `MIXED_NAME`:
    - `spy.readPages` is empty;
    - every unit is `DIRECT_TEXT`;
    - the text pages contain their text;
    - the scanned pages are present;
    - `metadata["pages_without_text"] == MIXED_SCANNED_PAGES.size.toString()`;
    - the renderer is never called (use a renderer that throws).
  - `no OCR keeps a page's poor text layer`: a `MemoryPage(text = listOf("ab"), scan = true)` page's unit
    contains `ab`.
  - `no OCR does not read a picture`: `ImageExtractor` gives one unit with empty text, the spy is not
    called, and `pages_without_text == "1"`.
  - Ingest: `a no-OCR import of a page without text completes with a warning`. Use
    `StagedUnitsThenFinish(goodUnits = 2, failedUnits = 0, metadata = mapOf(PAGES_WITHOUT_TEXT_METADATA to "1"))`
    and expect `COMPLETE_WITH_WARNINGS`.
  - `a resumed no-OCR import does not stage a committed page twice`: commit `page:1`, then rerun with it
    in `committed`; expect no `UnitReady` for `page:1`.
- [ ] **Step 2: Run them and see them fail.**
  `./gradlew test -PskipFrontend --tests infoscry.extract.PdfExtractorTest --tests infoscry.extract.ImageExtractorTest --tests infoscry.jobs.ImportJobHandlerTest`
- [ ] **Step 3: Implement** as listed under Files.
- [ ] **Step 4: Run the tests and see them pass.** Same command.
- [ ] **Step 5: Commit:** `Import with No OCR uses each page's own text`.

### Task 4: Import preview and CLI

**Files:**
- Modify: `src/main/kotlin/infoscry/jobs/ImportPreviewService.kt`, `preview()`. When
  `request.method == ReadingMethod.None`, each file's `pages = 0` while `documentPages` keeps its count,
  `totalPages = 0`, `atLeast = false`, and there is no estimate.
- Modify: `src/main/kotlin/infoscry/cli/ImportCommand.kt:97`. The help text becomes
  `Reading method: none, tesseract, surya, or llm:<profileId>`.
- Modify: `docs/cli.md`, which documents `--method none`.
- Test: the existing import-preview test class (`grep -rln "ImportPreviewService" src/test`) and the
  import routes test.

- [ ] **Step 1: Write the failing tests.**
  - `a no-OCR preview reads no page and needs no approval`:
    - `totalPages == 0`;
    - each file has `pages == 0` and `documentPages == n`;
    - `external == false`;
    - `estimatedCostUsd == null`.
  - `a no-OCR preview hash differs from a Tesseract preview of the same files`.
  - An end-to-end import of the mixed PDF fixture through the route with `method = "none"`:
    - the document is `COMPLETE_WITH_WARNINGS`;
    - it has chunks in the index;
    - no page image exists under its artifacts.
- [ ] **Step 2: Run them and see them fail.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run the tests and see them pass.**
- [ ] **Step 5: Commit:** `Preview and start a No OCR import`.

### Task 5: Scan again with `NONE`

**Files:**
- Modify: `src/main/kotlin/infoscry/document/RescanService.kt`.
  - `RescanPage` gains `val ownText: String? = null`.
  - `RescanPageSource.census(..., readsImages: Boolean = true)` and
    `pages(..., readsImages: Boolean = true)`: when `false`:
    - `pagesToRead = 0`;
    - every page has `read = false`;
    - nothing is rendered;
    - each PDF page's `ownText` is `PdfBoxPageTextReader.read(container, page)`, or `null` on `IOException`.
  - `preview()` passes `readsImages = method != ReadingMethod.None`, so `pageTotal = 0`. Check that
    `requireRescanable` and `destinationsOf` accept `NONE`.
- Modify: `src/main/kotlin/infoscry/jobs/RescanJobHandler.kt`.
  - Pass `readsImages = snapshot.engine != OcrEngine.NONE` to `pages.pages`.
  - Do not build a `PageOcrEngine` for `NONE`; the `rescanEngineFactory` call is skipped.
  - In the `!page.read` branch, when the engine is `NONE`:
    - when `ownText` is usable (`!PdfPageCandidate.needsOcr`), stage it as a `DIRECT_TEXT`,
      `APPROVED` page with `producerRevisionId = candidate` and no artifacts, using a new
      `stageOwnText(page, text, document, candidate, documentArtifactRoot)`;
    - otherwise call `stageBaselineText`, and when that returns `false`, call `stageOwnText` with
      `ownText.orEmpty()`.
    - Count every page that was not staged from a usable layer in `keptWithoutText`.
  - The final status is `COMPLETE` only when `failed == 0 && keptWithoutText == 0`.
- Test: `src/test/kotlin/infoscry/jobs/RescanJobHandlerTest.kt` and `src/test/kotlin/infoscry/document/RescanServiceTest.kt`.

**Interfaces:**
- Consumes: `ReadingMethod.None` and `OcrEngine.NONE` (Task 2).
- Produces: `RescanPage.ownText`, and the `readsImages` parameters.

- [ ] **Step 1: Write the failing tests.**
  - `a no-OCR rescan preview reads no page`: `pageTotal == 0` and `external == false`.
  - `a no-OCR rescan replaces pages with the managed copy's text layer`. Publish a reading whose page 1
    says `OCR text`, then rescan with `none`:
    - page 1's published text is the PDF's own layer;
    - its method is `DIRECT_TEXT`;
    - history holds the earlier revision.
  - `a no-OCR rescan keeps the published text of a page whose layer is unusable`: the scanned page of
    `MIXED_NAME`, published earlier as `läst sida 2`, keeps `läst sida 2`, and the status is
    `COMPLETE_WITH_WARNINGS`.
  - `a no-OCR rescan run twice converges`: the second admitted rescan publishes the same page texts, and
    no page is staged twice.
  - `an interrupted no-OCR rescan resumes`: use the existing `afterPageCommitted` hook to throw after the
    first page, run again, and expect the same result as an uninterrupted run.
  - `a no-OCR rescan renders nothing`: there are no files under `rescan-pages/<candidate>`.
  - `a no-OCR rescan of a picture keeps its published text`: a png published as `läst bild` still reads
    `läst bild`, and the OCR spy is not called.
- [ ] **Step 2: Run them and see them fail.**
  `./gradlew test -PskipFrontend --tests infoscry.jobs.RescanJobHandlerTest --tests infoscry.document.RescanServiceTest`
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run the tests and see them pass.** Same command.
- [ ] **Step 5: Commit:** `Scan again with No OCR`.

### Task 6: Frontend

**Files:**
- Modify: `web/src/lib/api.ts:12`, giving `OcrEngine` the value `'NONE'`. The `derivedMethod` code at
  `:55` maps `'NONE'` to `'none'`.
- Modify: `web/src/lib/ocrRescan.ts:5-9`, with `ENGINE_LABELS.NONE = 'No OCR (document text)'`.
- Modify: `web/src/lib/StartReadingDialog.svelte`. When the selected method id is `none`, show:
  `No page is read. Each page keeps the document's own text; pages without text are kept without text.`
- Test:
  - `web/src/lib/StartReadingDialog.test.ts`;
  - `web/src/lib/CollectionOcrSettings.test.ts`;
  - `web/src/lib/api.test.ts`.

- [ ] **Step 1: Write the failing tests.**
  - The dialog, given an option `{ method: 'none', label: 'No OCR (use the document's own text)', external: false, available: true }`,
    renders it, shows the sentence above when it is selected, and shows `0 of 3 pages will be read` for a
    preview with `pages: 0, documentPages: 3`.
  - Collection settings offer and save `none`.
  - `api` maps `ocrEngine: 'NONE'` to `defaultMethod: 'none'`.
- [ ] **Step 2: Run them and see them fail.** `cd web && npm test -- --run`
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run the tests and see them pass.** `cd web && npm test -- --run && npm run check`
- [ ] **Step 5: Commit:** `Offer No OCR in the start dialog and collection settings`.

### Task 7: Documentation and the full gate

**Files:**
- Modify: `docs/ocr-workflow.md`, points 2 and 3 and "Page selection". No OCR reads no page; amended
  2026-10-21.
- Modify: `docs/technical-reference.md`, adding `NONE` and `pages_without_text`.
- Modify: `README.md`, if it lists reading methods.
- Modify: `docs/specs/2026-10-21-no-ocr-reading-method-design.md`, with Status `implemented <date>`.

- [ ] **Step 1: Update the documents.** Run `git diff --check` and check the relative links.
- [ ] **Step 2: Run the gate.** Run `./gradlew check` in the background. Expected: `BUILD SUCCESSFUL`.
- [ ] **Step 3: Manual verification.**
  - Copy `~/.infoscry/infoscry.db*` to the scratchpad, open the copy with the built `SchemaMigrator`, and
    confirm that `user_version` is 2, the row counts are unchanged and `foreign_key_check` is empty.
  - Import the owner's Christer A. PDF with `--method none` into a temporary data directory, using
    `-PdataDir` or `--data-dir` with the models directory available. Expected: 52 direct-text pages and
    Done with warning, because page 12 has an unusable layer.
- [ ] **Step 4: Report** what was verified and what remains unverified. **Commit:** `Document No OCR`.
