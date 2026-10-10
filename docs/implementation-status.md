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
| LLM profiles | CLI/Admin management, role defaults, provider presets and model catalog; new Admin profiles suggest a provider/model name until manually edited; tool calling measured from Admin or the CLI |
| Ask | Streaming answers with validated citations and saved answer history |
| Investigate | Bounded research, retained-evidence follow-ups, one adopted answer per turn and saved conversations; an unmeasured profile is marked and refused with the remedy |
| Sources | Bounded extracted-source viewer and managed-original link |

## OCR engines and document rescanning

The [OCR workflow redesign](specs/2026-10-08-ocr-workflow-redesign.md) replaces
collection engine/profile/mode controls with one reading method confirmed at
start. Selected pages are read and successful runs publish the complete document
automatically, preserving text history and Restore. Stopped and legacy paused work is cleaned on startup.
Only pages that can need reading are read (see the spec's "Page selection"). The
redesign's tickets are complete and were removed from `docs/` (see git history
before the commit that adds [the follow-up plan](plans/2026-10-09-follow-ups.md));
open work is in [the follow-up tickets](tickets/follow-ups/STATUS.md). Real
providers and CoreML remain separate manual gates; fake tests do not establish
OCR quality.

## Fixes from local testing

The owner's first local test of the OCR work (2026-10-07) produced an
eight-ticket plan (since removed; see git history). All eight are
implemented and merged to `main` (2026-10-08), each with a fake-provider
browser scenario: measuring tool calling from Admin, a finished import's stage,
the OCR settings in text history, OCR profiles with the LLM providers and
image-capable models, skipping unsupported files, include/exclude extensions,
per-collection ignore patterns, and retrying a document with a chosen OCR
method. The owner decisions are recorded in ticket
[06](tickets/follow-ups/06-owner-decisions.md); existing behavior is retained.

## Compact document list and page review work

The [compact Collections document list](specs/2026-09-30-compact-document-list.md)
has its collection-wide summary and collapsed/session UI implemented (tickets
01–02). Ticket 02 passed independent review and a final gate of 1,585 JVM and 358
frontend tests, with type/build checks green. Ticket 03 is implemented and reviewed:
live filename search and inline details passed 1,585 JVM / 382 frontend tests,
with type/build checks green. Browser ticket 04 is implemented and reviewed:
16 scenarios passed, including geometry and visible focus at four widths. Its
final gate passed 1,585 JVM / 383 frontend tests. Human accessibility stays open.
Its [plan](tickets/compact-document-list/STATUS.md) adds a
collapsed summary, bounded full-width table, session view memory, live filename
search and inline details.

The [wide workspace and page review plan](plans/2026-10-09-workspace-and-page-review.md)
was refined and authorized for implementation with agents on 2026-10-09.
The common wide workspace is implemented and independently reviewed: its gate
passed 1,557 JVM and 335 frontend tests, with frontend type/build checks green.
Rendered viewport geometry is verified by ticket 07. The exact-revision
page-review API is implemented and reviewed, including durable per-page method
provenance, independent text/image access and immutable rescan raster namespaces.
Its final accumulated gate passed 1,585 JVM and 337 frontend tests with no
failures, errors or skips. The adaptive nonmodal source/history workspace is
implemented and independently reviewed, including narrow Back and opener/fallback
focus. Its final gate passed 1,585 JVM / 389 frontend tests with type/build checks
green; viewport geometry is verified in ticket 07.
Side-by-side PDF/image review is implemented and independently reviewed, including
TIFF display conversion, historical page citations and independent resource states.
Its final frontend gate passed 402 tests with type/build checks green; actual browser
geometry is verified in ticket 07.
Durable explicit-page rescan backend is implemented and reviewed: 35 focused
backend tests, a supplementary 11-test cancel/restart gate, nine API wire tests and
five real child-process crash boundaries passed. The frozen canonical selection
controls rendering/OCR/progress while unselected provenance is preserved.
Manual page selection and confirmation are implemented and independently reviewed,
including visible progress, Back/hidden lifecycle, uncertain replay and active-head
publication refresh after Restore.
Integrated [ticket](tickets/workspace-and-page-review/STATUS.md) has a green
42-test browser gate (Collections 16, Document 7, Investigate 8, OCR 10 and Search
1), including geometry at 390/1024/1440/1920px, exact historical PDF/image
citations, selected-only reading, partial explicit-run resume and independent
resource failures. Resume retains the admitted operation and committed pages;
repeated admission returns the current operation without another job. Independent
Luna task review and scoped crash-marker harness rereview passed. The replacement
final accumulated gate after whole-plan and approved cap fixes passed in 4m29s:
131 JVM suites / 1,598 tests and 425 frontend tests, zero failures/errors/skips. Whole-plan review
and its one scoped fix review passed; nested dialog Escape now retains the reader,
selected page and marks. Resume checkpoints also require the candidate producer,
so preserved failed reads are retried even with identical image hashes. Historical
Investigate evidence resolves document identity from preserved revision/unit
provenance when publication removes its current unit.
Current automatic PDF selection remains implemented.

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

The 2026-10-09 follow-up implementation passed `./gradlew check frontendCheck`:
1,556 JVM tests and 328 frontend tests, without failures or skipped tests;
Svelte/TypeScript reported zero errors or warnings. Rescans with no selected
pages create no operation; progress counts selected pages, persisted previews
retain the document page count, and preserved pages are not rendered. Generated
PDF tests cover mixed page selection, stable IDs and provenance, and recovery.
The owner retained existing filter/ignore/selection behavior and declined all
existing-data migrations and profile conversion. The fresh baseline changed;
the owner must recreate the database before using this version. No archive data
was deleted or converted. The real-PDF corpus and real-model/provider checks in
[ticket 05](tickets/follow-ups/05-real-pdf-and-model-check.md) remain open.

The follow-up five-class browser gate also passed all 36 tests: Collections 14,
OCR 10, Investigate 8, Search 1 and Document 3. This browser-only run did not
include the separate real Tesseract/Surya tests. JavaScript syntax, documentation
links and diff whitespace checks passed.

The subsequent image-check diagnostics fix carries the curated failure message
and HTTP status from `ImageLlmException` through the probe API to Admin. Failed
checks display the reason, status when available, and error code without claiming
that the model lacks image support. Provider response bodies are not exposed.
Failure details remain specific to the latest check response; no database change
or existing-data conversion is involved.
`./gradlew check frontendCheck` passed with 1,557 JVM tests. The final UI
status-display regression then passed the complete 330-test frontend suite and
typecheck with zero errors or warnings. The focused route suite passed 34 tests,
including HTTP 402 and provider-body redaction; UI coverage also checks HTTP 302.
No real-provider request was made to verify the code change.

New-profile name suggestions were then verified by `./gradlew check frontendCheck`
(1,557 JVM tests and 332 frontend tests). A final form-mode reset correction
passed the full 332-test frontend suite, typecheck with zero errors or warnings,
and the production build. Tests preserve manually edited and existing names and
check that Cancel → New resets the preset before another model is selected.

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

**OCR:** the redesigned scenarios exercise the image-reading check on LLM profiles,
language and default-method settings, explicit method selection, automatic
publication, cancellation, restart after interruption, text history and guarded
restoration. Eleven scenarios pass with fake engines and a local fake provider;
the current run is recorded in the redesign ticket status.

**Search:** a Chromium scenario exercises live advanced filters, a delayed
older response, inclusive date bounds, a single-digit query, mode changes and
safe highlighted source text. It uses the built reader, local server, temporary
archive and a deterministic local query embedder.

**Document and Ask:** three Chromium scenarios cover Admin page navigation and
focus wrapping, one selected page in a three-page PDF rescan, and opening an
Ask citation after its recorded revision becomes historical, without a live
pager. They verify selected-page progress, one fake-engine call and all three
stable page IDs after publication. Generated PDFs, fake reading/answer providers
and deterministic test embeddings keep this separate from OCR-quality gates.

These tests drive the real built reader and local server. They establish
workflow and persistence behavior within their test setup. The original
follow-up symptom was not reproduced against the pre-fix browser code; current
acceptance demonstrates that follow-ups complete.

## Open verification and product gates

- The format-specific source views and source-viewer finish line.
- Real-provider browser acceptance, including the model-catalog workflow.
- A human-operated native macOS file/folder picker check; automated route tests
  replace the dialog with a fake.
- A human keyboard and screen-reader pass.
- Real OCR and CoreML processing progress inside the complete Collections
  workflow. Separate real-tool/GPU tests do not close this workflow gate.

Ambiguous legacy Investigate answer pairs are preserved by design rather than
reconciled automatically. The project remains implementation in progress.
