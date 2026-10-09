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
| Import | Managed copies, per-collection deduplication, extraction/OCR checkpoints and persistent jobs; unsupported files are skipped and not shown; include/exclude extensions and per-collection ignore patterns |
| Search | Keyword, semantic and hybrid retrieval, live as the reader types; advanced filters rerun the current query; validated retrieval refills stale or artifact-heavy results; inclusive import dates and safe excerpt highlighting; recoverable reindexing |
| Collections | Creation, settings (including OCR defaults and ignore patterns), paged document browsing, progress, durable import history, retries (optionally with a chosen OCR method) and recoverable deletion |
| LLM profiles | CLI/Admin management, role defaults, provider presets and model catalog; tool calling measured from Admin or the CLI |
| Ask | Streaming answers with validated citations and saved answer history |
| Investigate | Bounded research, retained-evidence follow-ups, one adopted answer per turn and saved conversations; an unmeasured profile is marked and refused with the remedy |
| Sources | Bounded extracted-source viewer and managed-original link |

## OCR engines and document rescanning

The [OCR workflow redesign](specs/2026-10-08-ocr-workflow-redesign.md) replaces
collection engine/profile/mode controls with one reading method confirmed at
start. Every page is read and successful runs publish automatically, preserving
text history and Restore. Stopped and legacy paused work is cleaned on startup.
See the [redesign ticket record](tickets/ocr-workflow-redesign/STATUS.md) for
current implementation and verification. Real providers and CoreML remain
separate manual gates; fake tests do not establish OCR quality.

## Fixes from local testing

The owner's first local test of the OCR work (2026-10-07) produced an
[eight-ticket plan](plans/2026-10-07-local-testing-feedback.md). All eight are
implemented and merged to `main` (2026-10-08), each with a fake-provider
browser scenario: measuring tool calling from Admin, a finished import's stage,
the OCR settings in text history, OCR profiles with the LLM providers and
image-capable models, skipping unsupported files, include/exclude extensions,
per-collection ignore patterns, and retrying a document with a chosen OCR
method. Owner decisions still open are listed in the
[ticket status](tickets/local-testing-feedback/STATUS.md).

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
including 192 frontend tests and 16 external tests. The Collections report and
acceptance ticket held the final review evidence and the exact gate criteria
(removed from `docs/` once complete; see git history before commit `c9edfab`). Counts above are historical snapshots, not fixed expected totals.

On 2026-09-29 the Search repair and wider source preview passed `./gradlew check`
with 1,059 JVM tests and 210 frontend Vitest tests, `npm run check` with zero
errors or warnings, and all 17 `externalTest` tests, including the Search
Chromium flow. The full normal suite took 12 minutes 55 seconds; its cancelled
provider-stream fixtures deliberately hold a local HTTP response for 60 seconds.
This run did not repeat real CoreML testing.

On 2026-10-08 the merged local-testing fixes were run in a Linux cloud
container as root (not the Mac): 1,589 JVM tests with 10 failures, all
environmental (permission checks bypassed by root, process reaping without an
init process, locale-dependent fixtures); 423 frontend Vitest tests and
`npm run check` with no errors; `externalTest` 37 tests with all four browser
classes passing (Collections 13, OCR 12, Investigate 8, Search 1) and 3
failures because Tesseract and Surya are not installed there. `./gradlew check`,
real OCR tools and CoreML were not run.

On 2026-10-09 the OCR workflow redesign passed `./gradlew check externalTest`
on the local Mac: 1,534 JVM tests, 336 frontend tests and 36 external tests,
without failures or skipped tests. The browser classes cover Collections 14,
OCR 11, Investigate 8 and Search 1; two independent real-tool tests cover
Tesseract and Surya. Svelte/TypeScript checks reported zero errors or warnings.
The final Retry help-text correction passed all 79 Collections frontend tests
and typecheck afterwards. Real external-provider OCR and the complete workflow
with real CoreML were not run.

## What browser acceptance proves

**Collections:** fourteen scenarios exercise empty start, creation, folder import
and duplicates, filtered/paged browsing, details and source reading, settings,
single/bulk retry, single/bulk deletion, collection deletion during an import,
restart recovery, the legacy `Default` collection, a finished import's stage,
skipped unsupported files, extension filters, ignore patterns and retry with a
chosen OCR method. They use a fake extraction
pipeline and deterministic fake embedder with redistributable fixtures.

**Investigate:** eight scenarios exercise an unmeasured profile measured from
Admin, the first cited question,
retained-evidence follow-ups, source opening, reload/reopen, citation correction,
HTTP rejection, empty evidence, research limits, repeated-call limits and
cancellation recovery. They use a local fake provider. The Investigate report
(removed from `docs/` once complete; see git history before commit `c9edfab`) records the details.

**OCR:** the redesigned scenarios exercise OCR profiles and their provider catalog,
language and default-method settings, explicit method selection, automatic
publication, cancellation, restart after interruption, text history and guarded
restoration. Eleven scenarios pass with fake engines and a local fake provider;
the current run is recorded in the redesign ticket status.

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
