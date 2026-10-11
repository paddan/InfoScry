# 09 — Integrated mark/copy acceptance and documentation

**Status:** Done for the automated fake/local-provider scope, 2026-10-11 (sonnet,
dispatched by the coordinator with vector 5 and the real-provider/CoreML half of
vector 6 explicitly deferred to the owner). Coordinator's independent final
review and `./gradlew check` have not yet run.
**Blocked by:** 03–08 reviewed and integrated; 01 limits recorded.
**Read first:** [Spec](../../specs/2026-10-10-selectable-pdf.md), [plan](../../plans/2026-10-10-selectable-pdf.md), [shared contracts](CONTRACTS.md), [dispatch and gates](STATUS.md).

## Deliverable and ownership

Prove the integrated user flow and state honest manual/provider limits. Maps to
plan Task 6 and the final release gate.

**Own:** `web/e2e/document-browser-acceptance.mjs`,
`src/test/kotlin/infoscry/server/DocumentBrowserAcceptanceTest.kt`,
`src/test/kotlin/infoscry/pdf/SelectablePdfLifecycleTest.kt`, integration fixtures,
`README.md`, `docs/usage.md`, `docs/implementation-status.md`, plan/spec/ticket
verification records. Investigate harness changes require coordinator assignment.
No production fixes outside this ownership; return findings to their task owners.

## Acceptance

1. Generated mixed PDF import finishes, copy generation runs, real bytes download.
   Independently inspect canonical text and spatial positions against fixture truth;
   no double text, changed original or lost physical page. GET/repeat POST adds no
   duplicate artifact or transcription. PDF-reader find/search is not a criterion.
2. Selected-page fake-LLM rescan changes only its layer; previous/native pages remain.
   Historical A/B files retain their own text/image/geometry. Restore creates the
   correct current export with inherited layout, not the latest unrelated one.
3. Reload mid-generation, explicit Retry after failure/PARTIAL, missing positioning,
   late old completion and deletion are exercised with the real local job pipeline.
   Export failure leaves reading, Lucene search and new rescan usable.
4. Geometry/copy controls fit 390/1024/1440/1920px, with keyboard focus and partial
   page reasons. Preserve existing historical citation/source selection behavior.
5. Manually select and copy words/lines/columns in macOS Preview and a second PDF
   reader; compare clipboard text and selection location on rotated/cropped mixed
   PDFs. Check annotations, file size and memory with redistributable fixtures.
6. Separate real Tesseract/Surya/authorized LLM and CoreML/manual-accessibility
   evidence from fake-provider tests. Unperformed gates remain open and support
   claims cannot exceed measured coverage. Never send private owner PDFs for tests.

## Steps and checks

- [ ] Add failing end-to-end/lifecycle scenarios before integration fixes.
- [ ] Download and inspect actual PDFs, not just button existence or route 200.
- [ ] Run `./gradlew test -PskipFrontend --tests infoscry.pdf.SelectablePdfLifecycleTest`.
- [ ] Run `./gradlew externalTest --tests infoscry.server.DocumentBrowserAcceptanceTest`; add
  `--tests infoscry.server.InvestigateBrowserAcceptanceTest` if its source path changed.
- [ ] Coordinator runs `./gradlew check`; frontend diagnostics/build where applicable.
  Check documentation links, all named tests/interfaces, `git diff --check` and
  plan/spec/ticket consistency; complete an independent final review.
- [ ] Record finished commands/counts, native-reader evidence and open gates; update
  docs and the same Obsidian journal entry under AGENTS.md. No commit/push/deploy.

Browser downloads plus PDFBox text positions do not replace the native clipboard
check. A feature can be implemented with manual gates open; do not label it fully
accepted or all-engine supported until those specific gates pass.

## Completion evidence

**Owned files touched:** `src/test/kotlin/infoscry/pdf/SelectablePdfLifecycleTest.kt`
(new), `src/test/kotlin/infoscry/server/DocumentBrowserAcceptanceTest.kt` (one
new `@Test`), `web/e2e/document-browser-acceptance.mjs` (one new scenario,
`selectable-pdf`, plus its dispatch line), `README.md`, `docs/usage.md`,
`docs/implementation-status.md`, this ticket, `docs/tickets/selectable-pdf/STATUS.md`,
`docs/plans/2026-10-10-selectable-pdf.md` and `docs/specs/2026-10-10-selectable-pdf.md`
(status lines only, no design content changed). No file outside this ownership
was edited; no commit, push or `git` write ran.

**RED then GREEN:** `SelectablePdfLifecycleTest`'s first run failed twice before
passing — an `OcrSettingsSnapshot.runtimeIdentity` that did not match the fake
engine's own answer threw `RescanAttemptFailedException` (real production
runtime-identity-reuse guard, not a bug), and the `Restore` call needed
`runBlocking`. Both were test-fixture mistakes, fixed, then
`./gradlew test -PskipFrontend --tests infoscry.pdf.SelectablePdfLifecycleTest`
passed 2/2. The browser scenario failed three times before passing, each one a
real finding: (1) `getByText`/`waitForFunction` timed out because a document's
first import never queues selectable-PDF generation at all (see the finding
below) — fixed by using the real **Create PDF with selectable text** control
instead of assuming automatic queueing; (2) the same timeout persisted because
`startServer()` alone starts no background job runner — fixed by attaching the
real `ImportJobHandler.attachTo(...)` composition (the same one `ServeCommand`
uses) with a fake embedder/chunker; (3) after both fixes, the scenario passed
end to end. `./gradlew externalTest --tests infoscry.server.DocumentBrowserAcceptanceTest`
then passed 8/8 (all pre-existing scenarios plus the new one, run together).

**Acceptance vectors (automated scope):**

1. *Generated-PDF byte inspection.* `SelectablePdfLifecycleTest`'s first test
   imports and publishes a real two-page mixed native/OCR PDF, runs the real
   job, and independently re-opens the result with PDFBox (not the writer's or
   service's own report): exact text on both pages, no duplicate text layer, the
   managed original's hash unchanged, both physical pages present, and every
   written OCR glyph inside its span's rectangle plus the writer's fixed 1.5pt
   self-consistency inset (there is no independent ground-truth OCR box — see
   `CONTRACTS.md`'s frozen tolerance rule). Repeated GET status and POST
   create/retry (4 extra calls) all answer the identical verified identity, with
   exactly one job ever created and byte-identical served files.
2. *Selected-page rescan and Restore identity.* The second lifecycle test
   rescans physical page 1 of a published mixed document through the real
   `RescanJobHandler`, using a counted fake Tesseract-shaped engine that supplies
   its own word boxes (no separate alignment pass, exactly as a real Tesseract
   reading would). Only that page's export layer changes; the untouched native
   page and the historical revision's own export file are proven byte-for-byte
   unchanged. Repeating status/ensure/retry six more times never calls the
   engine again (call count stays 1). Restoring the original revision through
   the real `RevisionRestoreService` produces a new, distinctly-keyed current
   export whose content is the restored revision's own OCR text, not the
   superseded rescan's — and the superseded revision's own export is in turn
   unaffected by the restore.
3. *Reload/retry/deletion/late-completion.* Already covered through the same
   real local job pipeline by the pre-existing `SelectablePdfRecoveryTest` and
   `SelectablePdfServiceTest` (crash at the publish→enqueue gap, BUILDING with a
   dead job, missing/corrupt layout, a historical revision's export never
   becoming current, deletion during generation, retry never repeating
   transcription). Re-run unchanged as part of this ticket's gate rather than
   duplicated: `./gradlew test -PskipFrontend --tests 'infoscry.pdf.*'` — 174
   tests (172 existing + the 2 new lifecycle tests), 0 failures.
4. *Viewport/keyboard/partial reasons.* The new browser scenario fits
   390/1024/1440/1920px (no page-wide overflow, the controls' own group does not
   overflow itself) and proves keyboard focus reaches the visible "Open PDF with
   selectable text" link. PARTIAL's "X of Y pages" and its listed unavailable-page
   reasons are proven at the real backend level (`SelectablePdfServiceTest`'s
   PARTIAL case) and at the frontend component level (ticket 08's
   `SelectablePdfActions.test.ts`, which already renders a PARTIAL status with
   `unavailablePages` and asserts the reason text); a live-browser-rendered
   PARTIAL screenshot was not added in this dispatch — see open gates below.
   Historical citation/source-selection behavior is unaffected: the full
   `DocumentBrowserAcceptanceTest` class (8 tests, all pre-existing scenarios
   plus the new one) passed together in one run.
5. *Manual native-reader selection.* Out of this dispatch's scope entirely, per
   the coordinator's explicit narrowing — see "Open gates" below.
6. *Honest separation (fake-provider half).* See "Open gates" below and the
   dated journal entry in `docs/implementation-status.md`.

**A real production gap found and reported, not silently fixed:** a document's
plain first import commits through `StoredUnitsSink` directly and never calls
`RevisionPublicationService.publish()` — confirmed by reading
`ImportPipeline.production()` (`src/main/kotlin/infoscry/jobs/ImportPipeline.kt`)
and `ImportJobHandler.kt`. Since `queueForPublishedRevision` is only ever called
from `publish()`'s `onPublished` hook, **no selectable-PDF row is ever queued
automatically for a plain first import**, in production, not only in this test.
Only a later rescan or Restore (which do call `publish()`) queues it. This
sharpens ticket 04's already-recorded "first import drops the layout reference"
finding: the consequence is not merely a missing layout reference but that
generation never starts at all until someone clicks **Create PDF with
selectable text** explicitly or a rescan/Restore happens first. This matches
the spec's own documented fallback, so it is not a correctness bug the writer
or service must fix — but it does mean the plan's "automatically prepare a
derived PDF after successful PDF import" default is not actually wired for the
one case ("successful PDF import") the sentence names first. Reported here for
the coordinator to route (wire the hook into the first-import commit path, or
explicitly accept "first import never auto-queues" as current behavior) rather
than decided unilaterally in this ticket.

**Open gates — explicitly out of this dispatch's scope, for the owner to run
manually:**

- Acceptance vector 5 in full: selecting and copying words/lines/columns in
  macOS Preview and a second PDF reader, on rotated/cropped mixed PDFs,
  comparing clipboard text and selection location; checking annotations, file
  size and memory with redistributable fixtures.
- The real-Tesseract/Surya/authorized-real-LLM and CoreML/manual-accessibility
  half of vector 6.
- A live-browser-rendered PARTIAL screenshot of the selectable-PDF controls
  (PARTIAL is proven at the backend and frontend-component levels, not as a
  rendered browser scenario in this dispatch).
- Closing, or explicitly accepting, the first-import auto-queueing gap above.

No real Tesseract/Surya/LLM/CoreML process was named, configured or imported
anywhere in this ticket's own new code. Coordinator review, the coordinator's
own `./gradlew check` and final whole-feature sign-off have not run as part of
this ticket.
