# PDF copies with selectable text implementation plan

> **For agentic workers:** Use superpowers:executing-plans for inline work or superpowers:subagent-driven-development if the owner chooses delegation. Implement task by task; checkboxes describe future work and stay unchecked until verified.

**Goal:** Make the published OCR text selectable and copyable over its page image in a separate PDF copy.

**Architecture:** Persist revision-bound text geometry, then generate a derived PDF with the existing PDFBox writer. Durable artifact jobs follow successful publication without making reading/search depend on PDF generation. Untouched native-text pages are copied; OCR pages use their reading image and a replacement invisible layer.

**Tech Stack:** Kotlin/JVM 25, PDFBox 3.0.7, SQLite, existing local Tesseract/Surya adapters, TypeScript/SvelteKit, Vitest and Playwright.

**Spec:** [PDF copies with selectable text](../specs/2026-10-10-selectable-pdf.md).

**Status:** Implemented (2026-10-11) through [ticket 09](tickets/selectable-pdf/09-integrated-acceptance.md)'s automated scope; see [STATUS.md](tickets/selectable-pdf/STATUS.md) for the full ticket-by-ticket record. Milestones 1–6 (feasibility through acceptance/docs, tickets 01–09) are complete for fake/local-provider coverage: durable generation, HTTP/UI and integrated acceptance all pass against the real local job pipeline with independent PDFBox byte inspection. Native Preview/second-reader selection, real Tesseract/Surya/LLM positioning and CoreML remain open manual/real-provider gates, not established by this work. A document's plain first import does not yet auto-queue generation (ticket 09 finding); explicit **Create PDF with selectable text** works around it. No Git publication (commit/push) has occurred as part of this plan.

## Global constraints

- Kotlin/JVM 25; existing PDFBox 3.0.7 and other pinned dependencies.
- Immutable originals and managed copies; derived PDFs never feed extraction or deduplication.
- SQLite authority, one application process, stable page IDs and revision-bound artifacts.
- Complete published text is authoritative; no hidden truncation, silent character substitution or replacement by positioning OCR.
- No additional external model calls, automatic engine fallback or archive-wide re-OCR.
- English product copy, compact accessible controls, existing loopback/bearer/CSRF boundaries.
- Existing schema policy: baseline additions only; no existing-data conversion/reset without owner authorization.
- Repeat, cancel and recover every durable operation; PDF failure cannot block the next import/rescan.
- Normal tests use generated redistributable fixtures and local fakes; native selection and real OCR are separate gates.
- No commit/push or deployment until requested; checkpoint reviewable diffs per task.

Archive search continues through Lucene. PDF generation neither adds nor
replaces a search path. Acceptance measures selection geometry and copied text,
not PDF-reader search.

## Review focus

1. Surya block boxes look like word boxes in Kotlin: Task 1 proves granularity and Task 2 forbids treating blocks as complete geometry.
2. LLM corrects, inserts or reorders text: Task 2 preserves canonical text and reports ambiguous mapping, never fabricates spatial coverage.
3. Rotation/crop/reduced DPI moves the hidden layer: Task 3 asserts both glyph positions and rendered appearance for each transform.
4. A historical export finishes after Restore/rescan: Task 4 pins identity and Task 5 never presents the old file as current.
5. A crash/deletion leaves files but no finished row: Task 4 exercises both orders and proves retry convergence without repeated OCR.

## Sequence and ownership

The six milestones below are split into [nine agent tickets](../tickets/selectable-pdf/STATUS.md).
Use the tickets for exact file ownership, dependencies, dispatch prompts and
acceptance vectors; [shared contracts](../tickets/selectable-pdf/CONTRACTS.md)
refine and replace the preliminary interface sketches in this plan.

| Plan milestone | Agent tickets |
|---|---|
| 1 — Feasibility | 01 |
| 2 — Geometry and persistence | 02, 03, 04 |
| 3 — PDF writer | 05 |
| 4 — Durable generation | 06 |
| 5 — HTTP/UI | 07, 08 |
| 6 — Acceptance/docs | 09 |

01 → 02 is the feasibility/contract gate. Then 03, 05 and the fake-backed part of
08 can run in parallel with disjoint ownership. 04 follows 03; 06 follows 04/05;
07 follows 06; real 08 integration and 09 follow 07. Full checks are coordinated,
not run concurrently against shared build output. Ticket preparation does not
start implementation or dispatch agents.

### Task 1: Prove positioned text for all reading methods

**Files:**
- Create: `src/test/kotlin/infoscry/pdf/PdfTextPlacementProbeTest.kt`.
- Create: `src/test/kotlin/infoscry/pdf/SelectablePdfFixtures.kt`.
- Inspect: `ocr/OcrEngine.kt`, `extract/TesseractOcr.kt`, `ocr/SuryaOcr.kt`, `ocr/LlmOcr.kt`, `ocr/PageImageRenderer.kt` under `src/main/kotlin/infoscry/`, and `scripts/ocr/surya_worker.py`.
- Update: the feasibility evidence section at the end of this plan.

**Deliverable:** A kept regression fixture/test, plus measured design decisions;
no full feature or owner-data experiment. The test helper draws images with known
word rectangles and a PDF with a deliberately wrong invisible text layer.

- [ ] Create a two-column Swedish/English fixture containing ÅÄÖ/åäö, repeated names, a corrected number, a split/merged word, hyphenation and a missing line. Add known Tesseract word TSV, Surya block output and LLM text fixtures independently.
- [ ] Write failing spatial expectations before writer/alignment implementation. Assert word text and rectangle, not merely PDFTextStripper output.

```kotlin
// Probe fixture contract: canonical text and expected visual words are independent.
assertEquals("Åsa betalade 120 kronor.", fixture.canonicalText)
assertEquals(listOf("Åsa", "betalade", "120", "kronor."), fixture.visualWords.map { it.text })
assertTrue(fixture.visualWords.all { it.rectangle.width > 0 })
```

- [ ] Run `./gradlew test -PskipFrontend --tests infoscry.pdf.PdfTextPlacementProbeTest`; record the actual failure.
- [ ] Prototype local anchor alignment against these fixtures. Verify line/word refinement is possible for Surya blocks; inspect actual worker readingOrder and HTML-to-text behavior rather than relying on `OcrWordBox` naming.
- [ ] Prove PDFBox hidden text can round-trip Swedish Unicode and give correct TextPosition rectangles. Record a redistributable font asset, exact version/hash/license and whether line selection meets the desired precision. Do not silently use a machine-installed font.
- [ ] Record complete/partial outcomes for each engine. Missing/reordered lines must be ambiguous. If real Surya/LLM geometry cannot meet the contract, report that limitation and revise the proposed support statement before building later tasks; do not call plain page text spatial selection.

### Task 2: Persist canonical text geometry per page revision

Detailed ownership and steps: [02 — contracts](../tickets/selectable-pdf/02-layout-and-wire-contracts.md),
[03 — alignment](../tickets/selectable-pdf/03-local-text-positioning.md),
[04 — persistence](../tickets/selectable-pdf/04-layout-persistence-and-provenance.md).

**Interfaces:** Use exact records and signatures in [CONTRACTS.md](../tickets/selectable-pdf/CONTRACTS.md).
These include full PDF geometry/transforms, canonical UTF-16 offsets and a separate
normalized-layout artifact reference. Do not overload the engine's TSV reference.

- [ ] Add failing model/serialization tests for complete/incomplete layouts, finite
  rectangles/transforms, hashes, unsupported block granularity and omitted wire fields.
- [ ] Add independent alignment cases for corrected spelling/numbers, split/merge,
  ligatures/hyphenation, repeated anchors, missing lines and reordered columns.
  Preserve canonical text; ambiguous placement returns an incomplete result.
- [ ] Implement bounded local alignment with reversible canonical offsets. No provider
  call and no replacement of published text by locally recognized anchor text.
- [ ] Write layout bytes atomically before committing page references; hash-bind them
  to final normalized text and exact image/geometry. Record rendering transforms
  while the original PDF page is available, not from DPI alone.
- [ ] Preserve layout through extraction, checkpoints, candidate staging, failed and
  unselected-page inheritance and Restore. Missing positioning affects export only.
- [ ] Test both artifact/page-commit interruption orders, then recovery twice; successful
  transcription is not repeated. Older records remain readable without backfill.
- [ ] Run the focused commands in 02–04, then coordinator integration review/full gate.

### Task 3: Write and validate a replacement PDF layer

Detailed ownership and steps: [05 — PDF writer](../tickets/selectable-pdf/05-pdf-layer-writer.md).
Consumes the frozen physical-page modes, geometry and output/manifest types in
[CONTRACTS.md](../tickets/selectable-pdf/CONTRACTS.md). Writer code, font resources
and private writer tests belong to 05; it does not edit the pipeline or stores.

- [ ] Write failing mixed-PDF tests: keep native text and all physical pages, replace
  stale OCR exactly once, retain original hashes and page dimensions/order.
- [ ] Assert independent TextPosition rectangles within 1 point for each quarter turn,
  CropBox/MediaBox origins, UserUnit and reduced DPI; compare visible raster output.
- [ ] Test Swedish Unicode copying, canonical reading order, long text, unsupported
  glyphs, ambiguous/missing geometry and verified blank/unread pages.
- [ ] Implement native page copying and lossless image-backed OCR pages with one
  invisible layer and the pinned licensed embedded font. Never read an earlier export.
- [ ] Report unavailable layers explicitly; retain visual content without stale OCR or
  fail if impossible. Reopen/validate temporary PDF, manifest/hash and actual byte size.
- [ ] Run `./gradlew test -PskipFrontend --tests infoscry.pdf.SelectablePdfWriterTest`.
  Record memory/file-size measurements and flattened annotation/bookmark behavior;
  native Preview selection remains a separate gate.

### Task 4: Durable, repeatable artifact generation

**Files:**
- Create: `src/main/kotlin/infoscry/pdf/SelectablePdfService.kt`.
- Create: `src/main/kotlin/infoscry/storage/SelectablePdfStore.kt`.
- Create: `src/main/kotlin/infoscry/jobs/SelectablePdfJobHandler.kt`.
- Modify: `src/main/kotlin/infoscry/domain/Models.kt`, `config/AppPaths.kt`, `document/RevisionPublicationService.kt`, `server/Server.kt`, existing job-handler wiring in `jobs/ImportJobHandler.kt`, and CLI runner composition as found by `rg 'JobType.RESCAN' src/main/kotlin`.
- Modify: baseline schema and existing document/collection artifact cleanup owners.
- Create: `src/test/kotlin/infoscry/pdf/SelectablePdfRecoveryTest.kt` and `SelectablePdfServiceTest.kt`.

**Interfaces produced:** Use the service and wire DTO declarations in
[CONTRACTS.md](../tickets/selectable-pdf/CONTRACTS.md), including NOT_CREATED,
nullable key/physical totals, blank counts and exact published revision identity.

- [ ] Write failing tests for duplicate ensure, queued/building retry, PARTIAL/FAILED retry, deleted documents and mismatched collection/revision. Unique identity is the complete input/version key; one result cannot mean a different text revision.
- [ ] Write crash-boundary tests after desired row, enqueue, page checkpoint, temporary PDF close, file rename and final SQLite update. Invoke recovery twice and assert convergence; count transcription-provider calls and require zero during export/recovery.
- [ ] Add JobType and handler wiring in every server/CLI composition. Add desired-artifact intent with successful publication or a startup reconciliation covering the publication→enqueue gap. Include initial import, rescan and Restore paths; do not delay text publication for PDF generation.
- [ ] Implement manifest/PDF atomic publication and hash validation on reuse. Restart stale BUILDING rows; remove partial/orphan files idempotently; retain safe failure codes and explicit Retry.
- [ ] Pin snapshot inputs once. Late completion of revision A after B must leave B current; historical A remains downloadable only as A. Deletion cancels/checks ownership before final rename and cannot recreate a removed directory.
- [ ] Test missing/corrupt layout/image/font, exhausted disk, cancellation, interrupted job and retry without losing the current reading or preventing another rescan. No automatic archive-wide backfill.
- [ ] Run recovery/service tests plus existing publication, Restore and deletion recovery suites. Run accumulated `./gradlew check` after integration.

### Task 5: Scoped routes and compact open/download controls

**Files:**
- Create: `src/main/kotlin/infoscry/server/SelectablePdfRoutes.kt` and `src/test/kotlin/infoscry/server/SelectablePdfRoutesTest.kt`.
- Modify server route registration using the existing SourceRoutes pattern.
- Create: `web/src/lib/SelectablePdfActions.svelte` and `SelectablePdfActions.test.ts`.
- Modify: `web/src/lib/api.ts`, `api.test.ts`, `PageReview.svelte`, `PageReview.test.ts`; amend `web/src/routes/+page.svelte` only for cited source views that bypass PageReview.

**HTTP contract:**

```text
GET  /api/collections/{id}/documents/{documentId}/selectable-pdf?revision={revisionId}
POST /api/collections/{id}/documents/{documentId}/selectable-pdf
     { revisionId: string, retry: boolean }
GET  /api/collections/{id}/documents/{documentId}/selectable-pdf/file?revision={revisionId}&key={key}&inline=true
```

GET status returns revision ID and NOT_CREATED if no row; POST returns the same
artifact status on repeat. The file route returns only a verified READY/PARTIAL
artifact for that exact revision/key. Wrong collection or unpublished revision
returns 404; not-yet-generated/corrupt artifacts return a safe actionable status.
POST uses existing CSRF/admission. Generation never occurs in either GET route.

- [ ] Write failing route tests for read-only GET, duplicate POST, auth/CSRF, foreign collection IDs, candidate revisions, filename/header injection, historical exact revision, corrupt files and completed export after collection deletion.
- [ ] Write failing UI tests for NOT_CREATED/QUEUED/BUILDING/READY/PARTIAL/FAILED. New generation is POST; open/download is GET. Preserve Open original in every state.
- [ ] Implement shared actions beside the existing original link. READY exposes open/download; PARTIAL explicitly shows X of Y selectable pages and missing-page reasons before download. FAILED offers Retry and safe reason. No full history/workspace column.
- [ ] Poll only while an artifact is queued/building, cancel stale requests on page/revision change and ignore late status from another revision. Historical citations use their own revision; a new current revision must not reuse the older file URL.
- [ ] Run routes tests and `cd web && npm test -- --run && npm run check`; assert keyboard labels and focus after Retry. Run `./gradlew check` for the integrated task.

### Task 6: Complete acceptance and documentation

**Files:**
- Extend: `web/e2e/document-browser-acceptance.mjs` and `src/test/kotlin/infoscry/server/DocumentBrowserAcceptanceTest.kt`.
- Add a fixture-only end-to-end test for selected rescan, Restore and export revision identity.
- Update: `README.md`, `docs/usage.md`, `docs/implementation-status.md`, this plan and its spec.

- [ ] Write a failing browser scenario: import generated PDF → reading finishes → derived PDF becomes ready → download → independently inspect actual bytes/text/positions; repeat the request and assert no duplicate job/provider call.
- [ ] Rescan one selected page using a fake LLM plus local alignment fixture. Assert only that page's layer changes, untouched native pages remain, and A/B download URLs retain their own text. Restore A and verify the new active revision/export follows restored text with inherited geometry.
- [ ] Cover partial coverage, unavailable local positioning, export failure/retry, reload during generation and narrow/wide UI placement. Assert no impact on reading success and no hidden provider call.
- [ ] Run `./gradlew externalTest --tests infoscry.server.DocumentBrowserAcceptanceTest`; also run InvestigateBrowserAcceptanceTest if its cited-source integration changed.
- [ ] Run `./gradlew check`, frontend diagnostics/build as applicable, document link/consistency checks and `git diff --check`. Review all tasks centrally and resolve findings within approved scope.
- [ ] Perform the spec's manual Preview/second-reader selection, appearance, annotation and size/memory checks with redistributable sample files. Real provider tests require a separate explicit authorized fixture; keep those gates open if not run.
- [ ] Document selectable PDF versus immutable original, flattening, partial pages, local positioning cost/tool requirements, historical revisions, Retry and supported scripts. Record exact finished checks and outstanding gates, not checkbox-based claims. Journal the final result according to AGENTS.md.

## Feasibility evidence and release gate

Ticket 01 (synthetic fixtures, real Tesseract 5.5.3, simulated Surya/LLM; no
native-reader or real-Surya/LLM validation) ran 2026-10-10 and found:

- **Native PDFBox round-trip** (invisible text written and read back by this
  codebase alone): PASS. Worst span deviation 0.0001pt against the 1pt bound;
  stale prior text fully replaced; raster pixel-identical before/after.
- **Tesseract word boxes** (real tool, swe+eng, psm 3): positioned but not to
  1pt — left edges sit 1.0–2.7pt right of an independent fixture box, since
  Tesseract boxes are ink-based, not glyph-geometric. 101 of 144 non-space
  glyphs positioned; real misreads (`Åsa`→`Aca`, `ÅÄÖ`→`AAG`) correctly came
  back unpositioned; a merged OCR word (`ochså`) is ambiguous at the word
  boundary; a hyphenated box overshoots by ~5pt including the hyphen.
- **Surya**: block-level only. The real worker's `html_to_text` joins
  multi-line blocks into one box; `readingOrder` is parsed but unused. A block
  box does not pass as a word or line. Not installed locally; simulated only.
- **Coordinate-free LLM text**: unsupported standalone — 9 of 31 words
  unplaced even transcribed in order, 18 of 31 when columns are reordered.
- Font: Caveat Regular 2.000 (Impallari Type), SHA-256
  `0bdb6b660482d31531b3945849fba5916b3ef8695da7024a9e6b9ee3c4157988`, SIL OFL
  1.1 (per its name table; the OFL license text itself is not yet vendored
  locally), already committed under `src/test/resources/fixtures/ocr/`.

This raised three contract decisions, resolved 2026-10-10 for ticket 02 to
freeze:

1. **Tolerance splits in two.** The native PDFBox round-trip keeps the 1pt
   bound — it is pure geometry with no OCR noise. An OCR-sourced anchor
   (Tesseract, or whatever 03's aligner produces) is never compared against an
   independent "true" box, because none exists for a real scanned page.
   Acceptance there is self-consistency: after 05's horizontal-scale (Tz) fit,
   the written glyph run must land inside its reported box plus a fixed 1.5pt
   inset, with no visible overflow. No calibration/correction subsystem is
   added; a fixed inset is the entire fix.
2. **Spans are token-level, never per-character.** A corrected or merged token
   is one span over its full canonical range with one rectangle (the
   recognizer's box, or the union of boxes for a merge), Tz-fit by the writer.
   A split token (one recognized box, two canonical words) divides that one
   box into two rectangles by estimated character-width proportion, each
   still Tz-fit to its half.
3. **Surya/LLM text is never a source of WORD/LINE geometry by itself.** A
   Surya block is only a search window: 03's local aligner tries to place
   canonical words/lines inside each block's own text range (never Surya's
   coordinates directly), and LLM text is positioned only through the same
   local anchors, never from the LLM's own output. If the aligner cannot
   confidently place everything in a page within its block windows, that
   page's `PageTextLayout.complete = false` with a reason code, and 06/05 map
   that to `PdfPageMode.UNAVAILABLE` for the selectable-PDF export only — the
   reading image still shows, nothing is selectable for that page, and it is
   reported in the manifest's `unavailablePages`. This reuses the existing
   `UNAVAILABLE` coverage state; no new state is introduced.

The feature is not fully accepted until Tesseract, Surya refinement and LLM
positioning meet their declared coverage/selection contracts under these
rules. Reporting PARTIAL honestly is required behavior, not evidence of full
reading-method support. Real-Surya, real-LLM and native-reader validation
remain open gates.

## Execution handoff

Review the proposed automatic generation, image-backed replacement pages and
explicit partial-coverage behavior before implementation. The plan can then be
executed in this session, task by task, with bounded Luna assistance if selected.
No implementation or Git publication is performed as part of writing this plan.
