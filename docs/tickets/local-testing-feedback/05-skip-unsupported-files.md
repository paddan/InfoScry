# 05: Files that cannot be imported are skipped and not shown

**Status:** Not started. Unchecked criteria are requirements, not evidence.
**Blocked by:** None.
**Plan:** [Local testing feedback](../../plans/2026-10-07-local-testing-feedback.md).

## Problem

A file the pipeline has no extractor for becomes a document with status `Failed` (`UNSUPPORTED_MEDIA_TYPE`).

## Deliverable

A file that cannot be imported is skipped before anything durable is created, and is not shown anywhere: no
managed copy, no document row, no failed import item, no per-file result, and it is not counted among the import's
files (owner decision). This is the shared file-selection seam that tickets 06 and 07 extend.

## Files and interfaces

- `src/main/kotlin/infoscry/jobs/ImportJobHandler.kt` (today's `UNSUPPORTED_MEDIA_TYPE` failure),
  `src/main/kotlin/infoscry/jobs/ImportPipeline.kt`, `MediaTypeDetector`, `ExtractorRegistry`; the folder
  enumeration in the import path (grep `recursive`). Introduce one file-selection step that decides "import or
  skip" before copying.
- Counters and history: `ImportHistoryRoutes`, the CLI import summary (`--wait`).

## Test-first implementation

- [ ] Failing tests first: a folder with supported and unsupported files imports only the supported ones; nothing
  durable exists for the skipped ones (no copy, no document, no item); the history and CLI counts include only
  imported files; an import whose every file is skipped finishes with zero files rather than failing; a file that
  is supported but fails extraction still fails as today.
- [ ] Decide detection by content (the existing detector) rather than by extension alone, and record the decision.
- [ ] Extend the Collections browser scenario with an unsupported file in the imported folder.

## Focused verification

```sh
./gradlew test -PskipFrontend --tests 'infoscry.jobs.ImportJobHandlerTest' --tests 'infoscry.server.ImportHistoryRoutesTest'
```
