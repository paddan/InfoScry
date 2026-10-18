# Structured Page Reading Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** OCR pages keep headings, lists, tables, formulas and page headers/footers as a typed
`PageStructure` artifact that the reader, chunker, Ask/Investigate evidence and selectable PDF use.

**Architecture:** Each engine (Surya, LLM OCR, Tesseract) turns its answer into a
`PageStructureDraft`. `StructureDerivation` derives `extractedText`, `searchText` and the ranges
from it. The structure is stored as a gzipped JSON sidecar referenced exactly like the existing
`layoutReference` (draft → revision page → content unit). Consumers fall back to today's behaviour
when a unit has no structure.

**Tech Stack:** Kotlin/JVM 25, kotlinx.serialization, jsoup 1.21.1 (already pinned; parses Surya
HTML), SQLite (`001_baseline.sql` edited in place), Python worker, SvelteKit + Vitest.

**Spec:** `docs/specs/2026-10-18-structured-page-reading-design.md`

## Global Constraints

- `searchText` stays the only offset space for chunks and citations. `PageTextLayout` offsets keep addressing `extractedText`.
- `STRUCTURE_VERSION = 1`; parser ids `surya-html/1`, `markdown/1`, `tesseract-tsv/1`.
- Artifact name `page-%06d.structure.json.gz`, written atomically and content-addressed, referenced by relative path + SHA-256.
- No new dependency. The Markdown parser is a hand-written subset parser: the prompt fixes the grammar, and CommonMark would be the only new library for one bounded input.
- Schema changes go into `001_baseline.sql` in place; no incremental migrations.
- A structure that cannot be built never fails a page with text: paragraph-only structure plus warning `STRUCTURE_DEGRADED`.
- Units without structure behave exactly as today in every consumer.
- No document text, HTML or Markdown in logs or error messages.
- Product copy in English. Reader uses normal HTML elements, never `innerHTML`.
- Every operation is idempotent: run twice, interrupt between artifact write and commit, run again.
- JVMs headless (`-Djava.awt.headless=true`). Backend iteration: `./gradlew test -PskipFrontend --tests <class>`. Frontend: `cd web && npm test -- --run && npm run check`. `./gradlew check` before done.
- Embedding inputs ≤ 512 tokens with the exact tokenizer; never truncate passages.
- Real-tool gate: `SuryaRealToolTest` on the local Mac before claiming Surya tables work. No real LLM provider test.

## Review Focus

1. Ragged Surya tables (rows with different cell counts, `colspan` overshooting) render every cell, in order, without an exception. Pinned in Task 2.
2. LLM Markdown with `\|` inside a cell, a table missing its `---` separator, or an unterminated fence becomes cells or paragraphs. Text is never lost. Pinned in Task 4.
3. A page whose only text is a page header/footer gets zero chunks, yet it still opens in the reader and its structure renders. Pinned in Task 7.
4. A legacy revision page without structure is preserved across a rescan, then restored, and renders and chunks as before. Pinned in Task 6.
5. A table whose header row alone exceeds the chunk budget is chunked without the repeated header, and no passage is truncated. Pinned in Task 7.

---

### Task 1: Structure model, derivation and artifact store

**Files:**
- Create: `src/main/kotlin/infoscry/ocr/PageStructure.kt`, `src/main/kotlin/infoscry/ocr/StructureDerivation.kt`, `src/main/kotlin/infoscry/ocr/PageStructureStore.kt`
- Test: `src/test/kotlin/infoscry/ocr/PageStructureTest.kt`, `StructureDerivationTest.kt`, `PageStructureStoreTest.kt`

**Interfaces:**
- Produces (all in `infoscry.ocr`):
  - `const val STRUCTURE_VERSION = 1`
  - `@Serializable data class TextRange(val start: Int, val endExclusive: Int)`
  - `@Serializable data class PixelBox(val left: Int, val top: Int, val right: Int, val bottom: Int)`
  - `enum class BlockKind { HEADING, PARAGRAPH, LIST_ITEM, TABLE, FORMULA, CODE, CAPTION, FOOTNOTE, PAGE_HEADER, PAGE_FOOTER, FIGURE }`
  - `PageStructure`, `StructureBlock`, `TableRow`, `TableCell`, with fields exactly as in the spec's Data model.
  - Drafts with text but no ranges: `data class BlockDraft(val kind: BlockKind, val text: String, val level: Int? = null, val bbox: PixelBox? = null, val rows: List<List<CellDraft>>? = null, val latex: String? = null)`, `data class CellDraft(val text: String, val header: Boolean, val rowSpan: Int = 1, val colSpan: Int = 1)`, `data class PageStructureDraft(val parser: String, val blocks: List<BlockDraft>, val degraded: Boolean = false)`
  - `object StructureDerivation { fun derive(draft: PageStructureDraft, imageSha256: String): DerivedPage }`
  - `data class DerivedPage(val extractedText: String, val searchText: String, val structure: PageStructure, val degraded: Boolean)`
  - `const val STRUCTURE_DEGRADED = "STRUCTURE_DEGRADED"`
  - `data class StructureReference(val relativePath: String, val sha256: String)`
  - `object PageStructureStore { fun write(artifactRoot: Path, ordinal: Int, structure: PageStructure): StructureReference; fun read(artifactRoot: Path, reference: StructureReference, extractedText: String): PageStructure? }`

`PageStructureStore` mirrors `infoscry.pdf.PageTextLayoutStore`: same path validation, gzip and
atomic-move write. `read` returns `null` on a hash mismatch, invalid JSON, a `textSha256` that does
not match `extractedText`, or an invariant violation. Callers treat `null` as "no structure".

- [ ] **Step 1: Write failing tests**
  - `PageStructureTest`: the constructor rejects overlapping ranges, `level` outside 1..6 or on a non-heading, `rows` on a non-table, a range splitting a surrogate pair, and non-unique `order`.
  - `StructureDerivationTest`:
    - `derive` joins blocks with `\n`, table cells with `\t` and rows with `\n`.
    - For every block and cell, `extractedText.substring(extracted)` and `searchText.substring(search)` equal their normalized text.
    - CRLF in a cell and decomposed `"é"` input keep the invariant (search form is NFC).
    - A blank `BlockDraft` is dropped.
    - A draft with `degraded = true` gives `DerivedPage.degraded == true`.
    - An empty draft gives `""` and zero blocks.
  - `PageStructureStoreTest`:
    - write then read round-trips.
    - Writing twice gives the same path and sha.
    - A tampered byte, a different `extractedText`, or a `../` path all read as `null`.
- [ ] **Step 2: Run** `./gradlew test -PskipFrontend --tests 'infoscry.ocr.PageStructure*' --tests 'infoscry.ocr.StructureDerivationTest'`. Expected: FAIL (unresolved references).
- [ ] **Step 3: Implement the three files.** `derive` normalizes each block and cell with `TextNormalizer.normalize(...).search` and accumulates offsets. It then asserts that the concatenation equals `TextNormalizer.normalize(extractedText).search`. If not, it falls back to one `PARAGRAPH` per non-blank line with `degraded = true`.
- [ ] **Step 4: Run the same command.** Expected: PASS.
- [ ] **Step 5: Commit** `Add the page structure model, derivation and store`.

### Task 2: Surya HTML structure parser

**Files:**
- Create: `src/main/kotlin/infoscry/ocr/SuryaStructureParser.kt`
- Test: `src/test/kotlin/infoscry/ocr/SuryaStructureParserTest.kt`

**Interfaces:**
- Consumes: Task 1 drafts.
- Produces: `data class SuryaBlockInput(val html: String, val label: String?, val readingOrder: Int?, val bbox: PixelBox)` and `object SuryaStructureParser { const val PARSER = "surya-html/1"; fun parse(blocks: List<SuryaBlockInput>): PageStructureDraft }`

The label mapping table is the spec's, verbatim. Parse with jsoup `Jsoup.parseBodyFragment`. Only
the allowlisted elements carry meaning; any other element contributes its text. The heading level
comes from the first `h1`–`h6`, default 2. `ListGroup` gives one `LIST_ITEM` per `li`. `Table`,
`Form` and `TableOfContents` give `rows` from `tr`/`th`/`td`, with `rowspan`/`colspan` clamped to
1..1000. A `math` element's text becomes `latex`, and the block text is the LaTeX source. Blocks are
sorted by `readingOrder` (null last, stable).

- [ ] **Step 1: Write failing tests:**
  - `mapsEveryLabel`: one case per table row of the spec.
  - `headingLevelFromTag` (`<h3>` → 3; no tag → 2).
  - `tableWithSpans`: `rowspan=2`, `colspan=3` and a `th` header.
  - `raggedTableKeepsEveryCell` (Review Focus 1): a 3-cell row next to a 1-cell row with `colspan=9`. Asserts every cell text, in document order.
  - `brokenTableBecomesParagraph`: `<table><tr>` text without cells → `PARAGRAPH`, `degraded == true`.
  - `scriptAndStyleDropped`.
  - `sortsByReadingOrderNullLast`.
  - `pictureWithoutTextDropped`.
  - `formulaKeepsLatex`.
- [ ] **Step 2: Run** `--tests 'infoscry.ocr.SuryaStructureParserTest'`. Expected: FAIL.
- [ ] **Step 3: Implement `SuryaStructureParser.parse`.**
- [ ] **Step 4: Run.** Expected: PASS.
- [ ] **Step 5: Commit** `Parse Surya block HTML into a page structure`.

### Task 3: Surya worker protocol 2 and structured readings

**Files:**
- Modify: `scripts/ocr/surya_worker.py` (`PROTOCOL_VERSION = 2`, `block_result`, `collect_blocks`)
- Modify: `src/main/kotlin/infoscry/ocr/SuryaOcr.kt` (`PROTOCOL_VERSION = 2`, `SuryaBlock`, reply → result at ~line 379)
- Modify: `src/main/kotlin/infoscry/ocr/OcrEngine.kt` (`OcrPageResult`)
- Test: `scripts/ocr/test_surya_worker.py`, `src/test/kotlin/infoscry/ocr/SuryaOcrTest.kt`, `OcrEngineContractTest.kt`

**Interfaces:**
- Consumes: `SuryaStructureParser.parse`, `StructureDerivation.derive`.
- Produces: `OcrPageResult.structure: PageStructure? = null` and `OcrPageResult.warnings: List<String> = emptyList()`. When `structure != null`, `OcrPageResult.text == structure-derived extractedText` (contract test).

The worker keeps `text` and adds `html` per block (raw `block.html`). The per-page character bound
counts `html`, not just text. A block is dropped only when both its text and its HTML are blank.
`SuryaOcr` builds `SuryaBlockInput` from each block (bbox from the existing validated box). It then
calls `parse` and `derive`, and sets `text = derived.extractedText`. It also sets `structure`, adds
`STRUCTURE_DEGRADED` to `warnings` when degraded, and keeps the empty/`OCR_EMPTY` rule on the
derived text.

- [ ] **Step 1: Write failing tests:**
  - Python `test_block_carries_html` and `test_html_counts_toward_bound`.
  - Kotlin `SuryaOcrTest.readingCarriesStructure`, using the fake worker line with a table block: asserts `structure.blocks[0].kind == TABLE` and `text` contains `"a\tb"`.
  - `SuryaOcrTest.protocolOneIsRefused`.
  - `OcrEngineContractTest.structureTextMatchesResultText`.
- [ ] **Step 2: Run** `python3 -I -m pytest scripts/ocr/test_surya_worker.py` and `--tests 'infoscry.ocr.SuryaOcrTest' --tests 'infoscry.ocr.OcrEngineContractTest'`. Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run both.** Expected: PASS.
- [ ] **Step 5: Commit** `Send Surya block HTML and return a structured reading`.

### Task 4: LLM OCR Markdown (prompt version 2)

**Files:**
- Modify: `src/main/resources/prompts/ocr-transcription.txt`, `src/main/kotlin/infoscry/ocr/OcrModels.kt` (`OCR_TRANSCRIPTION_PROMPT_VERSION = 2`), `src/main/kotlin/infoscry/ocr/LlmOcr.kt` (~line 97)
- Create: `src/main/kotlin/infoscry/ocr/MarkdownStructureParser.kt`
- Test: `src/test/kotlin/infoscry/ocr/MarkdownStructureParserTest.kt`, `LlmOcrTest.kt` (existing or new)

**Interfaces:**
- Produces: `object MarkdownStructureParser { const val PARSER = "markdown/1"; fun parse(markdown: String): PageStructureDraft }`

The prompt's rule 2 becomes:

> 2. Write the page as GitHub-flavored Markdown that keeps its layout: `#` to `######` for headings,
> `-` or `1.` for list items, pipe tables with a header row and a `---` separator row, `$…$` or
> `$$…$$` for formulas, fenced code blocks for code. Wrap the page's running header in
> `<!-- page-header -->` … `<!-- /page-header -->` and its footer in `<!-- page-footer -->` …
> `<!-- /page-footer -->`. Keep paragraph breaks as blank lines.

The JSON envelope stays as it is. The parser is line based:
- ATX headings and `-`/`*`/`1.` list items are recognised.
- A table is a run of `|` lines whose second line is a separator. `\|` is a literal pipe and the first row is the header.
- `$$` blocks and lines that consist of one `$…$` become `FORMULA`, with `latex` set to the content.
- Fenced code becomes `CODE`.
- Comment markers produce `PAGE_HEADER`/`PAGE_FOOTER`.
- Everything else is paragraphs split at blank lines; inline `**`/`_`/`` ` `` markers are stripped.

Unreadable spans are remapped: each `[unreadable]` marker in the derived `extractedText` is matched
in order to the reported spans. A count mismatch drops all spans and adds `STRUCTURE_DEGRADED`.

- [ ] **Step 1: Write failing tests:**
  - `headingsListsTable`.
  - `escapedPipeInCell` (Review Focus 2).
  - `tableWithoutSeparatorIsParagraph` (Review Focus 2).
  - `unterminatedFenceKeepsText` (Review Focus 2).
  - `headerFooterMarkers`.
  - `displayFormula`.
  - `LlmOcrTest.unreadableSpansAddressDerivedText`: input Markdown `"# T\n\n[unreadable] x"` gives a span that slices to `[unreadable]` in `result.text`.
  - `LlmOcrTest.promptVersionTwo`.
- [ ] **Step 2: Run** `--tests 'infoscry.ocr.MarkdownStructureParserTest' --tests 'infoscry.ocr.LlmOcrTest'`. Expected: FAIL.
- [ ] **Step 3: Implement the parser, the prompt change and the `LlmOcr` wiring** (parse, derive, set `text`, `structure`, `warnings`, and the remapped spans).
- [ ] **Step 4: Run.** Expected: PASS.
- [ ] **Step 5: Commit** `Read LLM OCR pages as Markdown into a page structure`.

### Task 5: Tesseract paragraph structure

**Files:**
- Modify: `src/main/kotlin/infoscry/extract/TesseractOcr.kt` (`TesseractTsv`, `TesseractReading`, result at ~line 115)
- Test: `src/test/kotlin/infoscry/extract/TesseractTsvTest.kt` (existing or new)

**Interfaces:**
- Produces: `TesseractReading.paragraphs: List<BlockDraft>`, grouped by (`block_num`, `par_num`), with lines joined by `\n` and `bbox` the union of the word boxes. The parser id is `tesseract-tsv/1`.

- [ ] **Step 1: Write failing tests:**
  - `TesseractTsvTest.groupsParagraphs`: a TSV fixture with two blocks and three paragraphs gives three `PARAGRAPH` drafts with union boxes.
  - `TesseractOcrTest.resultTextIsDerived`.
- [ ] **Step 2: Run** `--tests 'infoscry.extract.Tesseract*'`. Expected: FAIL.
- [ ] **Step 3: Implement.** The result's `text` becomes `derived.extractedText`. Existing word boxes are unchanged.
- [ ] **Step 4: Run.** Expected: PASS, including existing Tesseract tests.
- [ ] **Step 5: Commit** `Group Tesseract lines into paragraph structure`.

### Task 6: Persist the structure reference and fingerprint it

**Files:**
- Modify: `src/main/resources/db/migration/001_baseline.sql` (`content_units` and the revision-pages table next to `layout_relative_path`: `structure_relative_path`, `structure_sha256` with the same path CHECKs)
- Modify, following every place `layoutReference` travels:
  - `extract/DocumentExtractor.kt` (`ContentUnitDraft.structureReference`)
  - `extract/PdfExtractor.kt` and `extract/ImageExtractor.kt` (write the artifact via `PageStructureStore.write` before the draft is returned)
  - `storage/DocumentRevisionStore.kt` (`RevisionPageDraft`/`RevisionPageText.structureReference`)
  - `ocr/CandidateRevisionPhases.kt` and `ocr/CandidateRevisionSink.kt`
  - `document/RevisionPublicationService.kt`
  - `jobs/RescanJobHandler.kt` (preserved pages inherit the reference exactly, including absence)
  - `storage/ContentStore.kt` and `domain/Models.kt` (`ContentUnit.structureRelativePath`, `structureSha256`, complete or both absent)
  - `ocr/StaleOcrStateCleanup.kt` (orphaned `*.structure.json.gz`)
  - `ocr/OcrModels.kt` (`OcrAttemptIdentity`: add `STRUCTURE_VERSION` and the engine's parser id to the fingerprint lines)
- Test: `storage/ContentStoreTest.kt`, `jobs/RescanJobHandlerTest.kt`, `ocr/OcrSettingsTest.kt`, `pdf/SelectablePdfLifecycleTest.kt`

**Interfaces:**
- Consumes: `PageStructureStore`, `OcrPageResult.structure`, `OcrPageResult.warnings`.
- Produces: `ContentUnit.structureRelativePath: String?`, `ContentUnit.structureSha256: String?`, and `fun ContentStore.structureOf(unit: ContentUnit): PageStructure?`, which reads via the store and returns `null` when absent or invalid. All consumers in Tasks 7–10 use `structureOf`.

`warnings` reach page review the way existing OCR warning codes do. The executor finds that path from
`OcrPageResult.errorCode` and records it in the commit.

- [ ] **Step 1: Write failing tests:**
  - `ContentStoreTest.structureReferenceRoundTrips`.
  - `ContentStoreTest.tamperedStructureReadsAsNull`.
  - `RescanJobHandlerTest.preservedLegacyPageKeepsNoStructure` (Review Focus 4: rescan, then restore the old revision; `structureOf` is `null` and the page chunks exactly as before).
  - `RescanJobHandlerTest.interruptAfterStructureWriteResumes` (write the artifact, throw before commit, run again: one artifact, same sha, unit committed).
  - `OcrSettingsTest.structureVersionChangesFingerprint`.
- [ ] **Step 2: Run** `--tests 'infoscry.storage.ContentStoreTest' --tests 'infoscry.jobs.RescanJobHandlerTest' --tests 'infoscry.ocr.OcrSettingsTest'`. Expected: FAIL.
- [ ] **Step 3: Implement the plumbing.**
- [ ] **Step 4: Run, then the phase gate** `./gradlew test -PskipFrontend`. Expected: PASS.
- [ ] **Step 5: Commit** `Persist page structure references and fingerprint the structure version`.

### Task 7: Structure-aware chunking

**Files:**
- Modify: `src/main/kotlin/infoscry/chunk/Chunker.kt` (`chunk`, `repeatedHeaderOf`, `CHUNKER_VERSION` bump)
- Modify callers: `ocr/CandidateRevisionPhases.kt:229`, `jobs/DocumentIngest.kt:522`, `search/ReindexService.kt:394`
- Test: `src/test/kotlin/infoscry/chunk/ChunkerTest.kt`

**Interfaces:**
- Consumes: `ContentStore.structureOf`, `PageStructure`.
- Produces: `fun chunk(unit: ContentUnit, maxSequenceTokens: Int = …, overlapTokens: Int = …, structure: PageStructure? = null): ChunkPlan`

With a structure:
- `searchText` is split into segments, which are maximal runs of blocks that are not `PAGE_HEADER` or `PAGE_FOOTER`. No chunk crosses a segment or covers an excluded block.
- Inside a segment, a split prefers block ends, then table-row ends. It never falls inside a table row unless the row alone exceeds the budget; then the existing token split applies.
- A chunk that starts inside a table gets that table's header row (cells with `header = true`, else the first row) as its repeated header. If the header would leave no room for body text, it is not repeated and `headerDropped` is set.
- The `slice(startOffset, endOffset) == text minus header` invariant still holds.
- Without a structure the behaviour is unchanged.

The `CHUNKER_VERSION` bump re-chunks and re-embeds; extraction checkpoints are untouched.

- [ ] **Step 1: Write failing tests:**
  - `pageHeaderAndFooterAreNotChunked`.
  - `onlyHeaderPageHasNoChunks` (Review Focus 3).
  - `neverSplitsInsideTableRow`.
  - `tableContinuationRepeatsHeaderRow`.
  - `oversizedTableHeaderIsNotRepeated` (Review Focus 5: no draft is truncated, `headerDropped == true`, every body token is covered).
  - `noStructureIsUnchanged`: the same drafts as today for a fixture.
- [ ] **Step 2: Run** `--tests 'infoscry.chunk.ChunkerTest'`. Expected: FAIL.
- [ ] **Step 3: Implement and pass `structure` from the three callers.**
- [ ] **Step 4: Run.** Expected: PASS.
- [ ] **Step 5: Commit** `Chunk OCR pages at structure boundaries`.

### Task 8: Markdown evidence for Ask and Investigate

**Files:**
- Create: `src/main/kotlin/infoscry/ask/StructuredEvidence.kt`
- Modify: `ask/ContextPacker.kt` (where `Evidence` is built, ~line 72), `investigate/InvestigationTools.kt` (`searchCollection`, `readUnit`, `readAdjacent`), `investigate/InvestigationService.kt:586`
- Test: `src/test/kotlin/infoscry/ask/StructuredEvidenceTest.kt`, `ContextPackerTest.kt`

**Interfaces:**
- Produces: `object StructuredEvidence { fun render(structure: PageStructure, searchText: String, start: Int, end: Int): String }`, which returns Markdown for `[start, end)` of `searchText`.

Rendering:
- Headings use `#` × level.
- `LIST_ITEM` becomes `- `.
- A table becomes a pipe table with its header row and `---`. When the span starts mid-table the header row is repeated. A `|` in a cell is escaped as `\|`.
- A formula becomes `$latex$`, and code is fenced.
- Page header/footer text is omitted.
- Text outside any block is copied verbatim.

Evidence IDs, citations and validation are unchanged. Budget counting (`RequestBudget`) runs on the
rendered string, because that is what is sent.

- [ ] **Step 1: Write failing tests:**
  - `rendersTableWithHeader`.
  - `midTableSpanRepeatsHeader`.
  - `escapesPipe`.
  - `omitsPageFooter`.
  - `ContextPackerTest.structuredUnitEvidenceIsMarkdown`.
  - `ContextPackerTest.budgetCountsRenderedText`.
- [ ] **Step 2: Run** `--tests 'infoscry.ask.*'`. Expected: FAIL.
- [ ] **Step 3: Implement and wire the call sites through `ContentStore.structureOf`.**
- [ ] **Step 4: Run** `--tests 'infoscry.ask.*' --tests 'infoscry.investigate.*'`. Expected: PASS.
- [ ] **Step 5: Commit** `Send structured OCR evidence to the LLM as Markdown`.

### Task 9: Selectable PDF text layer in structure order

**Files:**
- Modify: `src/main/kotlin/infoscry/pdf/OcrGeometryAligner.kt` (or the span builder it feeds), `src/main/kotlin/infoscry/pdf/SelectablePdfInputs.kt`
- Modify: `docs/selectable-pdf-contracts.md` (reason code)
- Test: `src/test/kotlin/infoscry/pdf/OcrGeometryAlignerTest.kt`, `SelectablePdfLifecycleTest.kt`

**Interfaces:**
- Consumes: `ContentStore.structureOf`.
- Produces: the reason code `ESTIMATED_CELL_GEOMETRY`.

With a structure, spans follow block `order`, then table cells row by row. A table cell's rectangle
is its table block's `bbox` divided evenly by row count and by column count (respecting spans);
such a layout is `complete = false` with reason `ESTIMATED_CELL_GEOMETRY`. A block without `bbox`
(LLM OCR) produces the layout today's code produces.

- [ ] **Step 1: Write failing tests:**
  - `tablePageSpansFollowCellOrder`.
  - `estimatedCellsAreIncomplete`.
  - `noStructureLayoutUnchanged`.
- [ ] **Step 2: Run** `--tests 'infoscry.pdf.*'`. Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run.** Expected: PASS.
- [ ] **Step 5: Commit** `Write the selectable PDF text layer in structure order`.

### Task 10: Reader renders the structure

**Files:**
- Modify: `src/main/kotlin/infoscry/server/SourceRoutes.kt` (`SourceContentResponse.structure`)
- Modify: `web/src/lib/api.ts` (`SourceContentResponse.structure?: StructureView`)
- Create: `web/src/lib/StructuredPage.svelte`, `web/src/lib/StructuredPage.test.ts`
- Modify: `web/src/lib/SourceWorkspace.svelte`
- Test: `src/test/kotlin/infoscry/server/SourceRoutesTest.kt` (existing or new)

**Interfaces:**
- Produces: JSON `structure: { blocks: [{ kind, level?, order, start, end, rows?: [{ cells: [{ header, rowSpan, colSpan, start, end }] }], latex? }] }`. The offsets are in the same text space and window that `text`/`offset` already use. Only blocks overlapping the served window are sent. The field is absent without a structure.
- TS types: `type StructureBlockView = {...}` and `type StructureView = { blocks: StructureBlockView[] }`, mirroring the JSON.

`StructuredPage.svelte` renders the elements listed in the spec's Reader section, with headings
offset by one (`level 1 → <h2>`, max `<h6>`). Page header/footer text is wrapped in an element
labelled "Page header"/"Page footer" and muted with CSS. Hit highlighting reuses the existing
highlight ranges, intersected with each block and cell range. `SourceWorkspace` uses
`StructuredPage` when `structure` is present and today's renderer otherwise.

- [ ] **Step 1: Write failing tests:**
  - Kotlin `structureIsServedForWindow`.
  - Kotlin `noStructureFieldAbsent`.
  - Vitest `rendersEveryBlockKind`.
  - Vitest `tableUsesThScope`.
  - Vitest `highlightInsideCell`.
  - Vitest `pageFooterIsLabelled`.
  - Vitest `withoutStructureUsesPlainRenderer`.
- [ ] **Step 2: Run** `--tests 'infoscry.server.SourceRoutesTest'` and `cd web && npm test -- --run`. Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run both, plus** `cd web && npm run check`. Expected: PASS.
- [ ] **Step 5: Commit** `Render structured OCR pages in the reader`.

### Task 11: Acceptance, real Surya gate and documentation

**Files:**
- Modify: `src/test/kotlin/.../DocumentBrowserAcceptanceTest.kt` (a scanned page with a table from the fake engine)
- Modify: `src/test/kotlin/infoscry/ocr/SuryaRealToolTest.kt` (a redistributable table page fixture: asserts at least one `TABLE` block and a header cell)
- Modify: `docs/ocr-workflow.md`, `docs/technical-reference.md` (Surya section, protocol 2, structure artifact), `docs/usage.md`, `docs/implementation-status.md`, `README.md`

- [ ] **Step 1: Add the acceptance scenario.** Import a fake-OCR page with a table, open it, assert a `<table>` with a header cell, then ask a question and assert that the evidence sent to the fake provider contains a pipe table. Run with `externalTest`. Expected: PASS.
- [ ] **Step 2: Run the real gate on the local Mac:** `./gradlew test -PskipFrontend --tests 'infoscry.ocr.SuryaRealToolTest'` with `INFOSCRY_SURYA_PYTHON` set. Record the result honestly in `docs/implementation-status.md`. If it fails, report the failure and do not mark the gate complete.
- [ ] **Step 3: Update the docs.** Then check links, check consistency with the spec, and review the diff.
- [ ] **Step 4: Run** `./gradlew check`. Expected: PASS.
- [ ] **Step 5: Commit** `Cover structured page reading end to end and document it`.
