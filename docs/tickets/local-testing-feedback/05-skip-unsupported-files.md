# 05: Files that cannot be imported are skipped and not shown

**Status:** Implemented on the worktree branch; the browser scenario is not done. Unchecked criteria are requirements, not evidence.
**Blocked by:** None.
**Plan:** [Local testing feedback](../../plans/2026-10-07-local-testing-feedback.md).

## Problem

A file the pipeline has no extractor for becomes a document with status `Failed` (`UNSUPPORTED_MEDIA_TYPE`).

## Deliverable

A file that cannot be imported is skipped before anything durable is created, and is not shown anywhere: no
managed copy, no document row, no failed import item, no per-file result, and it is not counted among the import's
files (owner decision). This is the shared file-selection seam that tickets 06 and 07 extend.

## Decision

- **Detection is by content.** The selection step asks the same `MediaTypeDetector` the import reads a managed copy
  with, and admits a file when `ExtractorRegistry.select` has an extractor for the detected type. The name is not
  consulted except where the detector already does so for text containers (`.csv`, `.md`), which is unchanged.
- **One seam.** `ImportSelection` (`src/main/kotlin/infoscry/jobs/ImportSelection.kt`) decides import-or-skip. It is
  called from exactly one place: `ImportJobHandler.handle`, on the enumerated files, before the first item is queued
  and before any byte is copied. The progress total is the admitted count, so a skipped file is not in `filesTotal`.
- **Missing sources are admitted.** A selected path that no longer exists has no content to inspect; it still
  becomes a `SOURCE_MISSING` item, as before.
- **Unreadable files are admitted.** A file the detector cannot inspect is imported as before, so the copy reports its
  failure against its own item rather than the file vanishing silently.
- **Supported files that fail still fail.** Extraction failures are unchanged (`EXTRACTION_FAILED`,
  `ENCRYPTED_DOCUMENT`, and the rest).
- **Not migrated.** Rows written before this change (`UNSUPPORTED_MEDIA_TYPE` items and documents) are left as they
  are. The retry path (`DocumentIngest`) still reports `UNSUPPORTED_MEDIA_TYPE` for a stored document that has no
  extractor; this ticket changes only the import's selection.

## Files and interfaces

- `src/main/kotlin/infoscry/jobs/ImportSelection.kt` (new): the import-or-skip decision.
- `src/main/kotlin/infoscry/jobs/ImportJobHandler.kt`: the selection call in `handle`, and the handler takes the
  selection from `attachTo`, which builds it from the pipeline's own detector and registry.
- Counters and history need no change: `filesTotal` is the job total the handler reports, and the CLI summary counts
  the items, so both now exclude skipped files.
- `src/test/kotlin/infoscry/cli/ImportProcessHarness.kt`: the harness extractor can now be asked to fail a text file
  (`FAILING_CONTENT`), so the CLI tests keep a supported file that fails.

## Test-first implementation

- [x] Failing tests first: a folder with supported and unsupported files imports only the supported ones; nothing
  durable exists for the skipped ones (no copy, no document, no item); the history and CLI counts include only
  imported files; an import whose every file is skipped finishes with zero files rather than failing; a file that
  is supported but fails extraction still fails as today. Red evidence is recorded below.
- [x] Decide detection by content (the existing detector) rather than by extension alone, and record the decision.
- [ ] Extend the Collections browser scenario with an unsupported file in the imported folder. **Not done**: the
  browser (Playwright) item was excluded from this run.

## Focused verification

```sh
./gradlew test -PskipFrontend --tests 'infoscry.jobs.ImportJobHandlerTest' --tests 'infoscry.server.ImportHistoryRoutesTest'
```

Run with the same test class list plus `infoscry.cli.ImportCommandProcessTest`.

Results (`--no-build-cache`, Java 25):

- Red, with the selection filter disabled: `ImportJobHandlerTest` 3 new tests failed (the folder with supported and
  unsupported files, the all-unsupported folder, and content-not-name); `ImportCommandProcessTest` 2 failed (the
  foreground import and the all-unsupported exit code). The guard test for a supported file that fails extraction
  passes both before and after, as intended. `a file that cannot be copied...` also fails and is the root
  environmental failure, not counted.
- Green, filter enabled: `infoscry.jobs.JobRunnerTest` (11), `JobRunnerRecoveryTest` (3), `RetryJobHandlerTest` (18),
  `RescanJobHandlerTest` (16), `ImportHistoryRoutesTest` (9), `ImportCommandProcessTest` (14) and
  `ImportJobHandlerTest` (54) pass, except `a file that cannot be copied...`. `DocumentDeletionRecoveryTest` has 2
  failures (`a resumed operation sweeps once...` and `a parked-files cleanup failure...`) that expect a permission
  error; they are the same root environmental failure and were not run on the base commit.
