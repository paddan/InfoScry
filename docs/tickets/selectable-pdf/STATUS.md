# PDF text selection and copying: ticket status

[Spec](../../specs/2026-10-10-selectable-pdf.md) ·
[Plan](../../plans/2026-10-10-selectable-pdf.md) · [Shared contracts](CONTRACTS.md)

Nine agent-sized tickets written 2026-10-10, all implemented and reviewed by
2026-10-11 on branch `codex/selectable-pdf-2026-10-10` for their automated
scope. Selecting/copying text is the feature; archive search continues
through Lucene. Open, owner-run gates remain: native Preview/second-reader
selection and clipboard comparison, real Tesseract/Surya/authorized-LLM
positioning, CoreML/accessibility, and a product decision on whether a
document's plain first import should auto-queue generation (today it needs
an explicit **Create PDF with selectable text** click; see ticket 09's and
04's notes below).

| Ticket | Depends on | State |
|---|---|---|
| [01 — Positioning feasibility](01-positioning-feasibility.md) | None | Done — reviewed 2026-10-10 |
| [02 — Layout/wire contracts](02-layout-and-wire-contracts.md) | 01 reviewed | Done — frozen 2026-10-10 |
| [03 — Local text positioning](03-local-text-positioning.md) | 02 frozen | Done — reviewed 2026-10-10 |
| [04 — Layout persistence/provenance](04-layout-persistence-and-provenance.md) | 03 reviewed | Done — reviewed 2026-10-10 |
| [05 — PDF layer writer](05-pdf-layer-writer.md) | 02 frozen | Done — reviewed 2026-10-10 |
| [06 — Durable generation/jobs](06-durable-generation-jobs.md) | 04 and 05 reviewed | Done — reviewed 2026-10-11 |
| [07 — Scoped HTTP API](07-scoped-http-api.md) | 06 reviewed | Done — reviewed 2026-10-11 |
| [08 — PDF actions UI](08-pdf-actions-ui.md) | 02 frozen to develop; 07 to finish | Done — reviewed 2026-10-11 |
| [09 — Integrated acceptance/docs](09-integrated-acceptance.md) | 03–08 integrated | Done for automated scope — reviewed 2026-10-11 |

## Dispatch order

```text
01 → review/feasibility decision → 02 → contract freeze
                                     ├─ 03 → 04 ─┐
                                     ├─ 05 ──────┴─ 06 → 07 ─┐
                                     └─ 08 (contract fakes) ───┴─ 08 integration → 09
```

After 02, three disjoint workers may take 03, 05 and 08. With a coordinator plus
three Luna agents this fits four slots. 04 can start after 03; 06 waits for 04/05
and owns the next schema/composition changes. UI can develop against frozen fakes
but is not complete before real 07 integration. Do not run several full Gradle
builds against the same output directories simultaneously.

## Shared-file ownership

- Shared types/CONTRACTS.md: 02 freezes; later changes coordinated with all consumers.
- Shared generated fixtures: 01; later tickets own separate fixture directories.
- OCR/extraction/revision/layout schema plumbing: 04; no other active owner.
- Writer/font resources: 05; no store, service or web edits.
- Job enum/AppContext/publication/artifact schema/cleanup: 06, after 04 releases files.
- Routes.kt: 07, after 06 wiring is integrated.
- api.ts/PageReview/+page.svelte: 08 only; 07 does not write the client.
- Browser harness and final documentation: 09 after production owners release files.

Paths in ticket file lists are repository-relative; abbreviated subsequent names
share the directory stated in that list. Locate additional required consumers with
rg and request ownership before editing. Do not refactor unrelated consumers or
overwrite pre-existing uncommitted timeout/Ollama/history work.

## Integration and review gates

1. 01 evidence: independent spatial truth, exact copying and honest Surya/LLM
   limits. A string-only round-trip or provider-fake success does not establish
   usable native selection or general positioning.
2. 02 freeze: validate Kotlin/JSON/status shapes, page transforms, complete/partial
   definitions and cache identity. Reconcile plan sketches with CONTRACTS.md.
3. Every implementation ticket: meaningful behavioral failing tests before code,
   focused GREEN on the changed side, coordinator diff/contract review. Return
   counts and limits; never infer completion from checkboxes.
4. Coordinator runs `./gradlew check` after each integrated milestone. 08 runs
   frontend tests and diagnostics during iteration. No concurrent whole-build
   checks by workers in a shared checkout.
5. 09: real built reader + local fake jobs/providers + actual PDF bytes, mark/copy
   position and clipboard checks, exact historical revisions, crash/retry/deletion,
   final full gate and independent whole-feature review.

No real-provider/private-document test, database reset, deployment, commit or
push is included. Native Preview/second-reader selection, real Surya/LLM, CoreML
and human accessibility gates remain open until the actual specified checks run.
Schema compatibility must be reported before installation; no unapproved migration.

## Ready-to-use agent assignment

Copy this prompt and replace the ticket filename for a future authorized dispatch:

> Work only on `docs/tickets/selectable-pdf/03-local-text-positioning.md` in
> `/Users/patrik/projects/infoscry`. Read the linked spec, plan, CONTRACTS.md and
> STATUS.md first. Confirm dependencies are reviewed. Own only the ticket's files;
> preserve unrelated changes and do not dispatch agents, commit, push, deploy,
> reset the archive or call external providers. Write meaningful failing tests,
> implement the ticket and run its focused checks. Report owned files, actual
> RED/GREEN evidence, acceptance outcomes, exported interfaces and unverified
> limits. Ask the coordinator before changing shared contracts or another owner's
> files. Full integration checks and journal updates belong to the coordinator.

## Verification record

Documentation link, ownership/dependency and contract consistency checks only
at planning time. Ticket 01's actual probe (synthetic fixtures, real
Tesseract 5.5.3, simulated Surya/LLM) ran 2026-10-10: `./gradlew test
-PskipFrontend --tests infoscry.pdf.PdfTextPlacementProbeTest`, 8/8 passing,
independently re-run and confirmed by the coordinator. Findings and the three
resulting contract decisions (tolerance, span granularity, Surya/LLM honesty)
are recorded in the plan's feasibility-evidence section and frozen into
CONTRACTS.md. No production code was touched by 01; no native-reader,
real-Surya or real-LLM validation has run yet.

Ticket 02 implemented the frozen records/DTOs in `src/main/kotlin/infoscry/pdf/`
(`PageTextLayout.kt`, `PdfArtifactModels.kt`, `SelectablePdfManifest.kt`,
`PdfWriteModels.kt`, `GeometryAlignmentInput.kt`) with model tests in
`src/test/kotlin/infoscry/pdf/`. Two coordinator-reviewed follow-up passes
closed real gaps found during review: a crop-box-outside-media-box case was
unvalidated, provenance (hash-mismatch) corruption threw the same exception
type as ordinary bad input, and an `UNAVAILABLE` page could carry an
unverified `complete = true` layout. All three are now closed; a
`PageTextLayoutProvenanceException` is distinct from `IllegalArgumentException`
for structural validation. `imageSha256` verification is documented in
CONTRACTS.md as 04's responsibility (02's `verifiedAgainst` only proves text).
Final state: `./gradlew test -PskipFrontend --tests 'infoscry.pdf.*'` —
85 tests (77 for 02's models, 8 carried over from 01), 0 failures,
independently re-run and confirmed by the coordinator. No production file
outside `infoscry.pdf` touched; no git operations by any agent.

Ticket 03 implemented `OcrGeometryAligner` (not score-based: unique-anchor
"islands" plus bounded one-edit matching between them; corrected/split/merge
tokens stay token-level per 02's rule). Real Tesseract 5.5.3 output against
ticket 01's probe page places 21 of 31 words; the rest — misreads, an absent
line, a cross-line hyphen join — correctly report incomplete rather than
guessing geometry, so that page maps to UNAVAILABLE for selection. One review
round fixed a real gap: a line-end hyphen join ("stor-"/"kostnad") was being
refused outright (CONTRACTS.md's merge rule only works for a same-line union;
a cross-line union rectangle is nonsensical). It now splits into two WORD
spans at the line boundary, each keeping its own anchor's rectangle — written
into CONTRACTS.md's "Resolved by ticket 01" block. Two new safe reason codes
(`PDF_TEXT_ALIGNMENT_AMBIGUOUS`, `PDF_TEXT_ALIGNMENT_LIMIT_EXCEEDED`) and the
requirement that 04 pass anchors in genuine visual reading order are also
documented there for 04/05. Final state: `./gradlew test -PskipFrontend
--tests 'infoscry.pdf.*'` — 112 tests, 0 failures, independently re-run and
confirmed. Surya/LLM positioning is still simulated only; no real-engine or
native-reader validation has run.

Ticket 05 implemented `SelectablePdfWriter`/`PdfFontResolver`, built in an
isolated worktree in parallel with 03 to avoid concurrent Gradle builds, then
merged into this branch. The font is Noto Sans Regular 2.015 (OFL 1.1),
recovered from a LibreOffice bundle already on this machine (no network was
available) rather than downloaded; its SHA-256 and the OFL text are committed
under `src/main/resources/fonts/`. Merging surfaced one real integration
conflict the isolated worktree could not see: both ticket 01's probe and
ticket 05's writer test independently declared a private `ExtractedGlyph`
test-support class in the same package with different field shapes (top-based
vs baseline-based box). Resolved by renaming ticket 05's to
`WriterExtractedGlyph`; no production type was affected. Two design points
the writer raised are confirmed: an invisible 0.1pt separator glyph between
two touching spans that the canonical text already separates by a space is
correct, not fabricated content; reading images are assumed upright/
horizontal-text (no vertical-script support), consistent with this project's
Swedish/English v1 scope. A third note — PDFBox leaves `PDFBox*.tmp` scratch
files behind if a write is interrupted — is carried forward as a cleanup
requirement for ticket 06's job recovery. Final state after merge:
`./gradlew test -PskipFrontend --tests 'infoscry.pdf.*'` — 126 tests, 0
failures, independently re-run and confirmed by the coordinator. Native
Preview/second-reader selection and real multi-engine validation remain
gate 09.

Ticket 04 (sonnet, given the blast radius of touching the already-shipped
OCR/rescan/revision pipeline rather than new isolated files) threaded a
layout reference through every real copy path: `PdfExtractor` (kept OCR
renders only, mirroring the existing `sourceImage` rule), candidate staging
(unchanged-baseline pages inherit verbatim, never recompute; new text builds
fresh against the *normalized* text), `RescanJobHandler` (unselected/failed
pages inherit), and `DocumentRevisionStore` (Restore's `copyApprovedPages`
copies verbatim; `appendPage`'s preserved-page check now also compares
`layoutReference`). Surya/LLM positioning always resolves a separate local
Tesseract-slot pass with no dispatch authority, never the Surya/LLM engine
itself — proven by a test asserting the real reading engine is never asked
for anchors. `PageTextLayoutStore.verifyImageMatch` closes the imageSha256
gap noted after ticket 02 (hash mismatch throws the same
`PageTextLayoutProvenanceException`, not a generic error). Two crash
boundaries (interrupted after the layout is written but before the page
commits; interrupted right after commit) were exercised through the real
`RescanJobHandler.handle()` with a call-counting fake engine and re-run
twice each, proving convergence with zero repeated transcription calls.
Schema change is additive-only: two new nullable columns on
`page_text_revisions` plus a both-or-neither CHECK; a 793-test pre-existing
regression baseline (jobs/document/storage/ocr/extract) passed unchanged
both before and after, and the full accumulated gate —
`./gradlew check -PskipFrontend` plus a from-cold `./gradlew test
-PskipFrontend --rerun-tasks` — gave 1754 backend tests and 432 frontend
tests, 0 failures, independently reproduced by the coordinator from a cold
cache (not just the agent's own cached run).

Two real gaps were found and are carried forward, not silently fixed by
expanding this ticket's scope:

- **Migration mechanism.** `SchemaMigrator` only applies `001_baseline.sql`
  to a brand-new (`user_version == 0`) database; editing it in place does
  not retroactively add the two new columns to any InfoScry data directory
  that already completed migration 1. Ticket 06 (which already owns
  "schema/job wiring" after 04 per this table) must add a real incremental
  migration step and bump `SUPPORTED_VERSION`, or document that this
  feature requires a fresh data directory until it does. **If you already
  have local InfoScry data from before this branch, it will not pick up
  these columns without that step or a fresh data directory — say so before
  testing this feature against it.**
- **First import drops the layout reference.** A document's very first
  import (the plain `StoredUnitsSink` path, not routed through
  `CandidateRevisionSink`) has no field to carry a layout reference into
  `content_units`, so it is dropped there — exactly mirroring `sourceImage`,
  which already has this same gap today, unrelated to this feature. Net
  effect: only a document's first *rescan* (or a check-and-improve import)
  gets a selectable-PDF layout; a plain first import does not, until
  someone chooses to widen `ContentUnit`/`StoredUnitsSink` to match
  `sourceImage`'s eventual fix, if any. Not blocking; not expanded here.

Also corrected: CONTRACTS.md's `PageTextLayoutStore` sketch omitted the
`canonicalText` parameter `read()` needs to call `verifiedAgainst`, and
assumed an `AppPaths`+ids-only shape; the actual implementation is a
stateless `object` with a `root: Path`-primary API plus an `AppPaths`-based
convenience overload, matching how `TesseractOcr`/`PageImageRenderer`
already work. Reconciled in CONTRACTS.md.

Ticket 06 (sonnet, the riskiest ticket so far — wires a new JobType into the
single existing composition root, hooks revision publication, and touches
schema) integrated generation with publication, recovery and deletion using
only already-existing infrastructure: a `RevisionPublishedHook` on
`RevisionPublicationService` (called at all three places a publication
durably finishes, including its own crash-recovery path) plus a bounded
`reconcileQueuedJobs()` startup scan for the few-CPU-instruction gap between
a row being recorded and its job actually being enqueued. Document/collection
deletion needed **no new code at all** — the new `selectable_pdf_artifacts`
table cascades via the existing `document_id ... ON DELETE CASCADE`, and the
job handler separately checks the existing `isDeletionTarget` at each
checkpoint; proven directly through the real `DocumentService.beginDeletion`
phases, not mocked. Every crash boundary (intent, enqueue, page checkpoint,
temp-file close, rename, final status) was interrupted and recovery run
twice, converging to byte-identical output with zero duplicate jobs and zero
OCR/aligner calls — the production path is structurally incapable of calling
a transcription provider (no such import exists in it at all). Schema change
is additive-only (one CHECK-list entry, one new table), same baseline-edit
pattern as ticket 04.

Two design judgment calls, both confirmed sound:

- A theoretical edge case where `recoverUnfinished()`'s crash-recovery list
  could, after a future upgrade against an *already-crashed-and-never-
  recovered* old publication, retroactively fire the new hook for it: this
  is accepted as correct, not a bug — enqueuing a PDF for a publication that
  genuinely never got one is exactly the intended behavior, not an unwanted
  side effect, and the list converges to empty in steady state. No archive-
  wide backfill risk (there's no existing data directory this matters for
  yet, and the scan is bounded to this feature's own crash-recovery rows).
- `verified()`'s self-healing `markFailed` when a READY/PARTIAL row's files
  fail re-verification during `status()`/`ensure()`: confirmed consistent
  with CONTRACTS.md's "GETs never generate or enqueue" — it only corrects
  bookkeeping about an already-materialized, now-provably-corrupt result to
  FAILED; it never generates a new artifact or enqueues a job.

Final state, independently reproduced by the coordinator from a cold
`./gradlew test -PskipFrontend --rerun-tasks`: 1777 backend tests, 171 in
`infoscry.pdf.*` alone, 0 failures. `Server.kt`, `Routes.kt`, and every
ticket 01–05 owned file are confirmed untouched (consumed read-only).

A follow-up fix landed on 06's `SelectablePdfService.kt` after ticket 07's
review surfaced it: `verified()`'s corrupt-file branch correctly told the
store to keep the artifact's key (`store.markFailed(..., key)`) but then
unconditionally returned a DTO with `key = null` via `failedWithoutRow`,
losing it on the wire. `failedWithoutRow` now takes an optional `key`,
defaulted to `null` for the genuinely-no-key branch and passed through for
the corrupt-file branch. `infoscry.pdf.*` — 172 tests (171 + the new
regression test), 0 failures, independently re-run and confirmed.

Ticket 07 implemented `SelectablePdfRoutes.kt` (one new file, one
registration line in `Routes.kt`) as thin delegation over 06's service,
reusing `installRequestGuard`/`context.mutations.withMutation` for
auth/CSRF/mutation-admission and `requirePublishedRevision` (mirroring
SourceRoutes' revision-state check) for the 404-on-unpublished rule
CONTRACTS.md requires. No shared path-confinement helper existed in
SourceRoutes beyond `NOFOLLOW_LINKS`, so a small private `isConfined()` was
written locally; confirmed acceptable. One real gap was found and fixed (the
dropped-key bug above, discovered because the route needed the key from a
FAILED status and found it missing) — not a route bug, a service bug the
route's own careful testing surfaced. One suspected gap (an unpublished
candidate getting `QUEUED` from a POST) turned out NOT to be a bug on
inspection: `SelectablePdfInputResolver.resolve()` already rejects it with
`Ineligible("REVISION_NOT_PUBLIC")` before any row is ever created, so POST
against a candidate already correctly produced `FAILED`, never `QUEUED` —
the route's added 404 check is independently required by CONTRACTS.md for
GET, not a workaround for a missing service check. A background regression
run inside this ticket's own session briefly reported many unrelated
failures (ImportHistoryRoutesTest, OcrProfileRoutesTest, etc.); the
coordinator reproduced a clean `infoscry.server.*` run (342/342, 0 failures)
immediately afterward and attributes the false failures to two Gradle test
runs writing to the same `build/test-results` directory concurrently — not a
real regression from this ticket's one-line `Routes.kt` change. Final state,
all independently re-run and confirmed by the coordinator:
`SelectablePdfRoutesTest` 33/33, `infoscry.server.*` 342/342, 0 failures.
Frontend fakes (08) must carry explicit `null` for `key`, `totalPages` and
`reasonCode` per ApiJson's `explicitNulls = true`.

Ticket 08 built `SelectablePdfActions.svelte` (shared controls beside "Open
original" wherever a source is shown) plus typed `api.ts` functions, and
integrated into `PageReview.svelte` and `+page.svelte`'s cited-source bypass.
No live server existed to hit, so "integration after 07" meant cross-checking
the decoder/component line-by-line against 07's real route code and its real
test fixtures (paths, query names, DTO field/enum names, the
`explicitNulls = true` codec, the error envelope, revision-id/key regex
shapes) — all confirmed to match. Final state: 469 then 470 frontend tests
(22 files), 0 svelte-check errors/warnings, both independently re-run and
confirmed by the coordinator.

Two real gaps were found reading the integration carefully and fixed in a
follow-up, not silently decided either way:

- **Polling never paused for the Collections PageReview path.** `visible`
  (which pauses `SelectablePdfActions`' status polling) was wired through
  +page.svelte's cited-source bypass branch but never reached
  `PageReview.svelte`'s own internal usage of the component, nor was it
  threaded from the real `<PageReview>` call site. Fixed: `PageReview.svelte`
  now takes a `visible` prop (default `true`) and forwards it; the real call
  site now passes `visible={sourcePaneVisible}`, matching how the adjacent
  `DocumentRescan` already receives it.
- **Excerpt-only ("Revision unknown") citations hid the actions entirely**
  instead of showing "unavailable" — contradicting CONTRACTS.md's explicit
  "for null/unknown revision, show unavailable without inventing identity or
  dispatch." This was a deliberate but mistaken choice (documented in a code
  comment at the time), confirmed wrong and fixed: the excerpt-only case now
  renders `SelectablePdfActions` with `revisionId={null}`, matching what the
  bypass branch already did correctly.

Final state after both fixes, independently re-run and confirmed: 470
frontend tests (22 files), 0 svelte-check errors/warnings. Five other
questions (copy wording, all-blank READY still offering Open/Download,
Retry on both PARTIAL and FAILED, a markup-shape change to "Open original",
and `document.hidden` also pausing polling) were reviewed and confirmed
correct as implemented, no changes needed. Narrow/wide wrapping is asserted
via computed inline styles only, with no real-browser viewport render —
carried forward as an open item for ticket 09.

Ticket 09 (sonnet, dispatched 2026-10-11 with the manual Preview/second-reader
and real-provider/CoreML gates explicitly out of scope) added
`SelectablePdfLifecycleTest` (new) and one new browser scenario rather than
re-deriving what 02–08's own tests already proved. The lifecycle test's first
case runs the real import/publish/job pipeline for a mixed native+OCR PDF and
independently re-opens the output with PDFBox itself: exact copied text, every
OCR glyph inside its span's rectangle plus the 1.5pt self-consistency inset,
no duplicate text layer, an unchanged managed original, both physical pages
present, and no duplicate artifact or job across five repeated GET/POST calls.
Its second case rescans one page through the real `RescanJobHandler` with a
counted fake Tesseract-shaped engine (own word boxes, no separate alignment
pass): only that page's layer changes, the historical revision's own export
stays byte-for-byte unchanged, repeated status/ensure/retry never call the
engine again, and restoring the original revision produces a new, distinctly-
keyed export carrying the restored revision's own OCR text — not the
superseded rescan's. Crash/retry/deletion/late-completion (06's own
`SelectablePdfRecoveryTest`/`SelectablePdfServiceTest`) were re-run unchanged
rather than duplicated. Final state: `./gradlew test -PskipFrontend --tests
'infoscry.pdf.*'` — 174 tests (172 + 2 new), 0 failures.

`DocumentBrowserAcceptanceTest` gained one Chromium scenario
(`selectable-pdf`): the real **Create PDF with selectable text** control,
polled to a real verified READY status, a real file download independently
checked for a `%PDF-` signature and non-trivial size, a repeated download and
POST create proving no duplicate artifact/job, and 390/1024/1440/1920px with
keyboard focus reaching the visible "Open PDF with selectable text" link. The
full class (8 tests) passed together, which is this ticket's evidence that
existing historical-citation/source-selection scenarios are unaffected.
`InvestigateBrowserAcceptanceTest` was not touched or re-run: nothing in this
feature reaches Investigate's own conversation flow.

One real production gap, unrelated to any single prior ticket's own code, was
found while building the browser scenario and is reported rather than silently
patched: a document's plain first import commits through `StoredUnitsSink`
directly, which never calls `RevisionPublicationService.publish()`, so the
hook that auto-queues selectable-PDF generation never fires for a first
import — confirmed by reading `ImportPipeline.production()` and
`ImportJobHandler.kt`, not merely observed in the browser run. Only a later
rescan or Restore (which do call `publish()`) queues it automatically; the
browser scenario above works around this exactly as the spec's own documented
fallback describes, by clicking **Create PDF with selectable text** explicitly.
This sharpens ticket 04's already-recorded "a plain first import... does not"
get a layout-reference finding: the consequence is that generation never
starts at all, not only that one field is dropped. Left for the coordinator to
route — not decided unilaterally here.

PARTIAL's "X of Y pages" and its listed unavailable-page reasons are proven at
the real backend level (06's `SelectablePdfServiceTest`) and the frontend
component level (08's `SelectablePdfActions.test.ts`); a live-browser-rendered
PARTIAL screenshot was not added in this dispatch and remains open. Native
Preview/second-reader selection, real Tesseract/Surya/an authorized real LLM
reading and CoreML/accessibility remain open manual/real-provider gates, not
established by any test in this ticket. `README.md`, `docs/usage.md` and
`docs/implementation-status.md` were updated to this exact state; no commit,
push or other `git` write ran.

## Coordinator's final review (2026-10-11)

Independently, from cold: `./gradlew check -PskipFrontend --rerun-tasks` —
BUILD SUCCESSFUL in 5m12s, 1813 backend tests and 470 frontend tests, zero
failures. `./gradlew externalTest --tests
infoscry.server.DocumentBrowserAcceptanceTest --rerun-tasks` — BUILD
SUCCESSFUL, all 8 scenarios passing (7 pre-existing + the new
`selectable-pdf` scenario), proving the addition does not regress existing
historical-citation/source-selection behavior. `git diff --check` found one
trivial trailing-blank-line issue in `web/src/routes/page.test.ts` (introduced
incidentally, not a substantive change); fixed and re-verified clean, frontend
suite re-run unaffected at 470/470.

The first-import auto-queue gap ticket 09 found (a plain first import commits
through `StoredUnitsSink` directly and never calls
`RevisionPublicationService.publish()`, so the hook that auto-queues
generation never fires for that path) was reviewed and deliberately left
open: closing it means touching `ImportPipeline`/`StoredUnitsSink`/
`ContentUnit`, a cross-cutting change no ticket in this plan owns, and the
existing explicit **Create PDF with selectable text** control already covers
it as a workaround. This, along with native Preview/second-reader selection,
clipboard comparison on rotated/cropped mixed PDFs, real Tesseract/Surya/an
authorized real LLM's positioning, and CoreML/accessibility, remain open,
owner-run gates — not established by any test in this plan. Everything else
in tickets 01–09's automated scope is implemented, independently verified by
the coordinator at every step (not merely trusted from each ticket's own
report), and reviewed. No commit, push, or other `git` write has been made as
part of this plan; the working tree holds the complete, uncommitted diff.
