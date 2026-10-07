# Implementation status

[Project overview](../README.md) · [Development and verification commands](development.md)

This is the overview of implemented behavior and verification boundaries.
Detailed feature results live in the linked ticket reports. Recorded test runs
below describe earlier work; reorganizing the documentation did not rerun the
application suites.

## Implemented behavior

| Area | Current behavior |
|---|---|
| Local runtime | Backend, CLI and static web reader; foreground loopback server |
| Import | Managed copies, per-collection deduplication, extraction/OCR checkpoints and persistent jobs |
| Search | Keyword, semantic and hybrid retrieval, live as the reader types; advanced filters rerun the current query; validated retrieval refills stale or artifact-heavy results; inclusive import dates and safe excerpt highlighting; recoverable reindexing |
| Collections | Creation, settings, paged document browsing, progress, durable import history, retries and recoverable deletion |
| LLM profiles | CLI/Admin management, role defaults, provider presets and model catalog |
| Ask | Streaming answers with validated citations and saved answer history |
| Investigate | Bounded research, retained-evidence follow-ups, one adopted answer per turn and saved conversations |
| Sources | Bounded extracted-source viewer and managed-original link |

## OCR engines and document rescanning

[Selectable OCR and document rescanning](specs/2026-09-30-ocr-rescanning.md) is
implemented through the plan's rescan core. Tesseract, a local Surya engine and
image-capable LLM profiles can transcribe page images; documents carry immutable
revisions with a recoverable publication boundary; a rescan compares its reading
with the published text, a reviewer recommends and a person decides. Everything
stays in pilot mode: no replacement happens without a manual decision, and
"Scan again" is driven by the operations API rather than a UI panel. The manual UI and history/restore are unfinished. Retry wiring, non-image import compatibility, saved evidence and image provenance/lifetime still have open correctness gates. The [ticket plan](tickets/ocr-rescanning/STATUS.md) splits these repairs and the remaining UI/acceptance work into independently testable slices.

## Planned compact document list

The [compact Collections document list](specs/2026-09-30-compact-document-list.md)
is designed but not implemented. Its [four-ticket plan](tickets/compact-document-list/STATUS.md)
adds a collapsed summary, bounded table, session view memory, live filename search
and inline details independently of the OCR extension.

## Recorded verification

The previous README recorded a local 2026-09-24 run of `check`, `externalTest`
and `gpuIntegrationTest`, plus a manual browser check that found a CLI-imported
sample through keyword, semantic and hybrid search and opened its source.

The 2026-09-28 Collections gate recorded:

- `./gradlew check`: 1,023 JVM tests and 187 Vitest tests, no failures.
- `./gradlew externalTest`: eight Collections browser scenarios, seven
  Investigate browser scenarios and the real `TesseractRealToolTest`, no failures.
- `./gradlew gpuIntegrationTest`: successful real pinned-model/CoreML run.
- Frontend Vitest, Svelte/TypeScript diagnostics and production build passed.

The later independent review recorded additional repairs and focused reruns,
including 192 frontend tests and 16 external tests. See the
[Collections report](tickets/collections-management/STATUS.md) for the final
review evidence and [acceptance ticket](tickets/collections-management/12-integrated-acceptance.md)
for the exact gate criteria. Counts above are historical snapshots, not fixed
expected totals.

On 2026-09-29 the Search repair and wider source preview passed `./gradlew check`
with 1,059 JVM tests and 210 frontend Vitest tests, `npm run check` with zero
errors or warnings, and all 17 `externalTest` tests, including the Search
Chromium flow. The full normal suite took 12 minutes 55 seconds; its cancelled
provider-stream fixtures deliberately hold a local HTTP response for 60 seconds.
This run did not repeat real CoreML testing.

## What browser acceptance proves

**Collections:** eight scenarios exercise empty start, creation, folder import
and duplicates, filtered/paged browsing, details and source reading, settings,
single/bulk retry, single/bulk deletion, collection deletion during an import,
restart recovery and the legacy `Default` collection. They use a fake extraction
pipeline and deterministic fake embedder with redistributable fixtures.

**Investigate:** seven scenarios exercise the first cited question,
retained-evidence follow-ups, source opening, reload/reopen, citation correction,
HTTP rejection, empty evidence, research limits, repeated-call limits and
cancellation recovery. They use a local fake provider. See the
[Investigate report](tickets/investigate-follow-up-and-efficiency/STATUS.md).

**Search:** a Chromium scenario exercises live advanced filters, a delayed
older response, inclusive date bounds, a single-digit query, mode changes and
safe highlighted source text. It uses the built reader, local server, temporary
archive and a deterministic local query embedder.

These tests drive the real built reader and local server. They establish
workflow and persistence behavior within their test setup. The original
follow-up symptom was not reproduced against the pre-fix browser code; current
acceptance demonstrates that follow-ups complete.

## Open verification and product gates

- Ask's end-to-end browser acceptance.
- The format-specific source views and source-viewer finish line.
- Real-provider browser acceptance, including the model-catalog workflow.
- A human-operated native macOS file/folder picker check; automated route tests
  replace the dialog with a fake.
- A human keyboard and screen-reader pass.
- Real OCR and CoreML processing progress inside the complete Collections
  workflow. Separate real-tool/GPU tests do not close this workflow gate.

Ambiguous legacy Investigate answer pairs are preserved by design rather than
reconciled automatically. The project remains implementation in progress.
