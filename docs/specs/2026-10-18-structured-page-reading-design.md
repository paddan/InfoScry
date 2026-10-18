# Structured page reading

Status: implemented 2026-10-18. Browser acceptance and the local real-Surya table gate passed; see
[implementation status](../implementation-status.md#structured-page-reading-2026-10-18). Contracts as
built are in [technical reference](../technical-reference.md#page-structure) and
[OCR workflow](../ocr-workflow.md#structured-page-reading-2026-10-18).

## Goal

An OCR page keeps the layout its engine recognised (headings, paragraphs, lists, tables, formulas,
captions, footnotes, page headers and footers) instead of being flattened to plain text. The
structure is used in four places:

1. the reader renders headings, lists and real tables;
2. Ask and Investigate send tables and headings to the LLM as Markdown;
3. chunking splits at block boundaries, never inside a table row, and leaves page headers and
   footers out of the index;
4. the selectable PDF writes its text layer in reading order, table cell by cell.

All three engines (Surya, LLM OCR, Tesseract) fill the same engine-neutral structure in this
project.

This is sub-project 1 of using more of Surya. Later sub-projects, each with its own spec:

2. line boxes from Surya `detection` for exact hit highlighting and PDF placement;
3. page quality flagging with Surya `ocr_error`;
4. batching several pages per Surya call.

## What exists today, and what changes

- `scripts/ocr/surya_worker.py` calls `RecognitionPredictor(...)([image], full_page=True)` and
  flattens each block's HTML to text with `html_to_text`. It already sends `label` and
  `readingOrder`, which `SuryaOcr.kt` parses into `SuryaBlock` and then ignores. Blocks are used in
  the order received, not in `readingOrder`.
- LLM OCR (`LlmOcr.kt`, transcription prompt version 1) returns plain text.
- Tesseract (`TesseractOcr.kt`) returns TSV with `block_num`/`par_num`/`line_num`; the TSV is the
  page artifact.
- `OcrPageResult` carries `text`, word or block boxes and confidence. `ContentUnit` carries
  `extractedText` (canonical; selectable-PDF offsets address it) and `searchText` (from
  `TextNormalizer.normalize`; chunk offsets address it). `searchText` is the only offset space for
  chunks and citations, and this design keeps it that way.

This design:

- adds `PageStructure`, a per-page artifact derived from the engine's answer;
- derives `extractedText`, and from it `searchText`, from the structure, so offsets stay consistent;
- changes the Surya worker protocol to send block HTML (bounded) instead of flattened text;
- bumps the LLM transcription prompt to version 2 (Markdown output);
- teaches the reader, the chunker, the evidence renderer and the PDF writer to use the structure
  when it exists, and to behave exactly as today when it does not.

Existing readings are not upgraded. A page gets structure on its next import or rescan with an OCR
engine. Nothing runs automatically.

## Data model

Package `infoscry.ocr`, serializable immutable records, validated at construction and when read.

```kotlin
@Serializable data class PageStructure(
    val version: Int,              // STRUCTURE_VERSION, starts at 1
    val textSha256: String,        // of the unit's extractedText
    val imageSha256: String,       // of the page image read
    val parser: String,            // e.g. "surya-html/1", "markdown/1", "tesseract-tsv/1"
    val blocks: List<StructureBlock>,
)

@Serializable data class StructureBlock(
    val kind: BlockKind,
    val level: Int? = null,        // HEADING only, 1..6
    val order: Int,                // unique, follows the canonical reading
    val bbox: PixelBox? = null,    // image pixels; null when the engine has no boxes (LLM OCR)
    val extracted: TextRange,      // [start, endExclusive) into extractedText (UTF-16)
    val search: TextRange,         // [start, endExclusive) into searchText (UTF-16)
    val rows: List<TableRow>? = null,   // TABLE only
    val latex: String? = null,     // FORMULA only, source as the engine wrote it; not searchable
)

@Serializable data class TableRow(val cells: List<TableCell>)
@Serializable data class TableCell(
    val header: Boolean,
    val rowSpan: Int = 1,
    val colSpan: Int = 1,
    val extracted: TextRange,
    val search: TextRange,
)

enum class BlockKind {
    HEADING, PARAGRAPH, LIST_ITEM, TABLE, FORMULA, CODE, CAPTION, FOOTNOTE,
    PAGE_HEADER, PAGE_FOOTER, FIGURE,
}
```

### Derivation (one direction only)

engine answer → `PageStructure` (minus ranges) → `extractedText` → `searchText` → ranges filled in.

- `extractedText` is the blocks' text in `order`, joined by `\n`. A table is its rows joined by
  `\n`, each row's cells joined by `\t`, the same convention spreadsheet units use. A block with
  no text is dropped (a `FIGURE` without a caption, for example).
- `searchText` is `TextNormalizer.normalize(extractedText).search`. The normalizer works per
  character (CR/LF unification, NFC, control filtering) and blocks are separated by `\n`, so block
  boundaries map one-to-one. Each block's and cell's `search` range is computed by normalizing that
  block or cell on its own and accumulating.
- Invariant, asserted at commit: slicing `extractedText` with a block's `extracted` range, or
  `searchText` with its `search` range, gives that block's text in that space. The same holds for
  every cell. A structure that breaks the invariant is not committed. The page gets a
  paragraph-only structure and `STRUCTURE_DEGRADED` (see Errors).
- Ranges never overlap, never split a surrogate pair, and every non-whitespace character of
  `extractedText` belongs to exactly one block.

### Storage

- One artifact per page next to the engine's existing artifact, under the document's artifact
  root: `structure/page-%06d.<sha256>.structure.json.gz`, where `<sha256>` is the hash of the
  structure's bytes. It is written atomically and content-addressed.
- `ContentUnit` gets an optional `structureRelativePath` and `structureSha256`, complete or both
  absent like the existing artifact reference. They go into `001_baseline.sql` in place, as is
  the project's practice; there is no new table.
- A hash mismatch on read is corrupt provenance. The unit is shown and cited without structure and
  is read again on the next rescan, like a mismatched box artifact today.
- `STRUCTURE_DEGRADED` is stored as the `degraded` flag on the structure artifact and shown in page
  review. It has no table of its own.
- A rescan whose text is unchanged from the current reading adopts the new reading's structure.
- Ebook and Office embedded-image OCR units get no structure in this project. Only OCR readings
  (Surya, LLM, Tesseract) carry it.

### Fingerprint

`STRUCTURE_VERSION` and the engine's parser version join the reading's identity and the extraction
fingerprint. A different version means a different key, so the page is read again on rescan, and
the previous version stays in history as today.

## Engines

Every engine has its own parser with a version string. The parsers all produce the same
`PageStructure` and go through the same validation.

### Surya (`surya-html/1`)

- Worker protocol version 2: each block sends `html` (the model's markup, bounded by the
  existing per-page text bound counted on the HTML), `label`, `readingOrder` and `polygon`→`bbox`.
  The worker does no structure work; parsing is in Kotlin, so it is tested with the rest of the
  JVM code. `text` per page stays for the existing empty/read decision.
- Blocks are sorted by `readingOrder`. A tie or a missing order keeps the received order.
- Label mapping:

  | Surya label | BlockKind |
  |---|---|
  | `SectionHeader` | `HEADING` (level from `<h1>`–`<h6>`, default 2) |
  | `Text`, `Bibliography` | `PARAGRAPH` |
  | `ListGroup` | one `LIST_ITEM` per `<li>` |
  | `Table`, `Form`, `TableOfContents` | `TABLE` (from `<table>`) |
  | `Equation` | `FORMULA` |
  | `Code` | `CODE` |
  | `Caption` | `CAPTION` |
  | `Footnote` | `FOOTNOTE` |
  | `PageHeader` | `PAGE_HEADER` |
  | `PageFooter` | `PAGE_FOOTER` |
  | `Picture`, `Figure`, `Diagram`, `ChemicalBlock` | `FIGURE` (only when it has text) |
  | anything else | `PARAGRAPH` |

- HTML is parsed with an allowlist: `table`, `thead`, `tbody`, `tr`, `th`, `td` (`rowspan`,
  `colspan`), `h1`–`h6`, `p`, `ul`, `ol`, `li`, `br`, `math`, `code`, `pre`, inline formatting.
  Everything else becomes its text content. No markup is stored. `<math>` content becomes `latex`,
  and the block's text is the LaTeX source, so it stays searchable as written.
- Only a `Table` block whose HTML does not parse as a table degrades: it becomes a `PARAGRAPH` with
  its text, and the page gets `STRUCTURE_DEGRADED`. A `Form` or `TableOfContents` block that does not
  parse as a table becomes a `PARAGRAPH` with its text, without the flag.

### LLM OCR (`markdown/1`)

- `OCR_TRANSCRIPTION_PROMPT_VERSION` becomes 2. The prompt asks for GitHub-flavored Markdown:
  `#` headings, `-`/`1.` lists, pipe tables with a header row, `$…$`/`$$…$$` for formulas, fenced
  code, and page headers and footers wrapped in `<!-- page-header -->` / `<!-- page-footer -->`
  comment markers. The existing rule that imported instructions are untrusted data still applies.
- A hand-written, line-based Kotlin parser builds the structure. It reads ATX headings, list items,
  pipe tables with a separator row, `$…$` and `$$…$$` formulas, fenced code, and the page header and
  footer markers. Anything else stays text, in order. No Markdown library is used, and no dependency
  was added. `bbox` is null, as LLM readings have no boxes today.
- Markdown that does not parse becomes paragraphs split at blank lines, plus `STRUCTURE_DEGRADED`.
  It never fails the page.

### Tesseract (`tesseract-tsv/1`)

- Lines are grouped into `PARAGRAPH` blocks by Tesseract's own `block_num`/`par_num` in the TSV,
  with the union of the word boxes as `bbox`. Tesseract recognises no tables or headings, and the
  structure does not pretend otherwise.

## Consumers

Every consumer behaves exactly as today for a unit without structure: legacy readings, non-OCR
formats, and degraded artifacts.

### Reader

- The page API returns `structure` (blocks with kind, level, order, `extracted` ranges, rows and
  cells, and latex) next to the text. The ranges are in the served text's space, shifted by the
  window offset. No `search` ranges are sent. Boxes are not needed by the reader in this project.
- Svelte builds normal elements from the typed data: `<h2>`–`<h6>` (offset so the page title keeps
  `<h1>`), `<p>`, `<ul>`/`<li>`, `<table>` with `<th scope="col|row">`, `<pre><code>`, and
  `<figure>`/`<figcaption>`. No `innerHTML`.
- A formula is shown as its LaTeX source in `<code>`. No math rendering library.
- Page headers and footers are shown muted and labelled ("Page header", "Page footer").
- Hit highlighting is not wired. The reader does not mark search hits inside structured blocks, and
  `StructuredPage` has a highlight prop that nothing sets yet.

### Chunking and search

- `Chunker` receives block boundaries (in `searchText`) as preferred split points. It never splits
  inside a table row. When it must split inside a block, the existing rules apply.
- A table that needs several chunks repeats its header row through the existing repeated-header
  mechanism, inside the 512-token budget measured with the exact tokenizer. A header row too long
  to repeat is not repeated, and the plan records that rule; passages are never truncated.
- `PAGE_HEADER` and `PAGE_FOOTER` spans are excluded from every chunk. They stay in
  `extractedText`/`searchText` for the reader and the PDF, but neither keyword search nor
  embeddings see them.
- Changing chunking changes the chunk fingerprint, so affected units are re-chunked and
  re-embedded. Re-embedding does not invalidate extraction checkpoints.

### Ask and Investigate evidence

- A chunk's evidence text is rendered as Markdown from the blocks it overlaps: headings as `#`,
  tables as pipe tables with their header row (repeated when the chunk starts mid-table), and
  formulas as `$…$`. Text outside blocks, and units without structure, are sent as today.
- Evidence IDs and citations still name the chunk. The request budget counts the Markdown actually
  sent.

### Selectable PDF

- The text layer is written block by block in `order`, and a table cell by cell, row by row, so
  selecting and copying follows reading order.
- No cell geometry is estimated: no rectangles are derived from a table block's box, and no
  `ESTIMATED_CELL_GEOMETRY` reason code exists. Exact geometry is sub-project 2 (Surya detection).
- `PageTextLayout` offsets still address `extractedText`, which is now the structure's derived text,
  so the existing contract in `docs/selectable-pdf-contracts.md` is unchanged.

## Errors

- A structure that cannot be built never fails a page that has text. The page gets a
  paragraph-only structure and the warning code `STRUCTURE_DEGRADED`, which page review shows.
- A worker result with HTML past its bound is the existing `OUTPUT_TOO_LARGE`.
- No document text, HTML or Markdown appears in logs or error messages.

## Idempotence and recovery

- The structure artifact is written atomically and content-addressed before the page's checkpoint
  commits. A crash after the write and before the commit means the next run writes the same bytes
  and commits. An orphaned artifact is removed by the existing stale-artifact cleanup.
- A rescan with an unchanged fingerprint reuses the checkpoint, including the structure. A changed
  `STRUCTURE_VERSION` or parser version means a new key and a new reading.
- Every new path is tested by running it twice, and by interrupting it between artifact write and
  commit and running it again.

## Testing

- Parser unit tests with fixtures, one suite per engine:
  - Surya HTML: tables with `rowspan`/`colspan`, empty cells, malformed tables, nested lists,
    `<math>`, unknown labels, missing `readingOrder`.
  - Markdown: pipe tables, headings, lists, formulas, header/footer markers, malformed input.
  - Tesseract TSV: block and paragraph grouping.
- Derivation invariants: ranges in both text spaces, no overlap, full coverage, surrogate pairs,
  CRLF and NFC input.
- Chunker: no page header or footer in any chunk, no split inside a table row, repeated table
  header, token budget with the exact tokenizer.
- Evidence: Markdown rendering for a chunk starting mid-table, and budget counting.
- Selectable PDF: text-layer order for a table page, with no cell geometry claimed.
- Reader (Vitest, component-level only): rendering of every block kind, including table cells, and
  a unit without structure rendering as today. Highlighting is not tested, as it is not wired.
- Python worker test (`scripts/ocr/test_surya_worker.py`) for the new block fields and bounds.
- Idempotence: repeat, and interrupt between artifact write and commit.
- `DocumentBrowserAcceptanceTest` gains a scanned page with a table, rendered from a fake engine.
- **Real-tool gate:** `SuryaRealToolTest` on the local Mac, with a table page the test renders itself
  (redistributable by construction), before any claim that Surya tables work. It asserts a `TABLE`
  block with header cells. It runs under `externalTest`, since the `external` tag keeps it out of
  `test`. A real LLM provider test needs separate authorization and is not
  part of this project.

## Documentation to update

`docs/ocr-workflow.md`, `docs/technical-reference.md` (Surya section, worker protocol),
`docs/usage.md` (reader), `README.md` status. `docs/selectable-pdf-contracts.md` needs no change:
there is no reason-code change, since no cell geometry is estimated.

## Out of scope

- Surya `detection` line boxes, `ocr_error`, batching (sub-projects 2–4).
- Surya `table_rec`. Revisit only if real pages show full-page table HTML is not good enough.
- Structure for non-OCR formats (DOCX, HTML, PDF text layer, EPUB).
- Math rendering in the reader.
- Upgrading existing readings without a rescan.
