# 04: Show durable processing and OCR progress

**What to build:** Document rows and details show truthful processing stages,
committed extraction progress, direct-text versus OCR counts, warnings, and
actionable failures. Progress survives interrupted work and resume without
counting previously committed units twice.

**Blocked by:** 02 — Find and open documents.

- [x] Extractors report a known total when available before completion, and
      persist the current attempt/fingerprint, unit kind, extraction method,
      committed successes, and failures through the authoritative state.
- [x] Progress advances only with a durable unit/checkpoint commit. Resume
      combines only compatible work and does not double count units.
- [x] Unit methods distinguish direct extraction from OCR explicitly rather than
      treating mean confidence as a definitive method indicator.
- [x] Rows and details show readable queued/copying/extraction/OCR/chunking/
      embedding/indexing stages and terminal statuses, including missing tools.
- [x] Known page progress can read `OCR · 12/40 pages processed`; the denominator
      describes document pages, not an invented total of OCR-required pages.
      Separate counters distinguish OCR and direct-text pages.
- [x] Non-page formats use appropriate unit labels. Unknown totals show counts
      without a denominator/percentage; unavailable legacy method information is
      unknown rather than zero or a fabricated historical value.
- [x] Details show failed-unit counts and warnings, with curated messages derived
      from safe error codes. Missing tools/model/GPU prerequisites show a remedy.
- [x] GPU failure preserves existing diagnostic, keyword-search and source-view
      access; no CPU embedding fallback or dependency upgrade is introduced.
- [x] Tests cover mixed direct/OCR input, unknown totals, non-page units, legacy
      summaries, commit boundaries, interruption/resume, and safe error responses.
- [x] Component tests verify row/detail counts, labels, warning/error states and
      stage updates. Focused tests and the accumulated offline gate pass; actual
      local OCR/GPU acceptance is recorded separately from fake-pipeline results.

**Status:** Done, verified with the focused suites (the accumulated gate and
browser acceptance belong to tickets 12 and to the parent's gate).

- `JAVA_HOME=/Users/patrik/.asdf/installs/java/temurin-25.0.4+101.0.LTS ./gradlew test --tests 'infoscry.storage.SchemaMigratorTest'`
  — passes after the schema version moved to 10, including the new
  `document_extraction_progress` table.
- `... ./gradlew test --tests 'infoscry.storage.ExtractionProgressTest'` —
  11 tests pass: announced totals, unknown totals, direct/OCR counters from the
  stored method rather than a confidence, commit boundaries, a resumed attempt
  committing the same keys twice, a new fingerprint starting over, legacy
  summaries, legacy units with no method, and a document with no attempt at all.
- `... ./gradlew test --tests 'infoscry.extract.PdfExtractorTest'` — passes with
  the announcement asserted before the first page and both methods asserted on
  their own pages.
- `... ./gradlew test --tests 'infoscry.jobs.ImportJobHandlerTest'` — passes with
  the OCR phase and its committed page asserted mid-attempt through the real
  `StoredUnitsSink`.
- `... ./gradlew test --tests 'infoscry.server.*' --tests 'infoscry.storage.*' --tests 'infoscry.jobs.*' --tests 'infoscry.extract.*'`
  — 494 tests completed, 0 failed (`BUILD SUCCESSFUL`).
- `cd web && npx vitest run` — 153 tests pass, including the new row/detail
  progress, stage-label, unknown-total and legacy-method component tests;
  `npm run check` — `svelte-check found 0 errors and 0 warnings`.

Nothing here is real OCR or GPU acceptance: the extraction and embedding paths
in these tests use fakes and fixtures, and the local Tesseract/CoreML acceptance
remains ticket 12's and the parent's separate gate.
