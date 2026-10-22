# No-OCR reading method

Status: implemented 2026-10-21.

## Goal

When a person imports or rescans documents, they can choose **No OCR**. With No OCR, the document's own
text is used as it is, for example a PDF's text layer, and no page is rendered, read by an engine or sent
anywhere. It sits beside Tesseract, Surya and the LLM profiles wherever a reading method is chosen.

## What changes in the OCR workflow

[OCR workflow](../ocr-workflow.md) currently says:

- every page that can need reading is read, with the chosen method;
- the start dialog always chooses an engine.

No OCR is a reading method that reads no page. Everything else in the workflow is unchanged:

- the start dialog is shown and confirmed on every import and rescan;
- the collection default pre-fills the dialog;
- a run is always restartable;
- a finished rescan replaces the text, and the previous version stays in history.

## The method

- API id `none`, label **No OCR (use the document's own text)**, destination "this machine", never
  external.
- It is always available, because it needs no tool, model or profile.
- Internally it is a new `OcrEngine.NONE`, reached through `ReadingMethod.None`. The engine is part of
  the snapshot and the extraction fingerprint, so checkpoints from a No-OCR run and from an OCR run are
  never mixed.
- There is no `PageOcrEngine` for `NONE`. Asking the engine registry to transcribe with `NONE` is a
  programming error, not a page failure.
- CLI: `infoscry import --method none`. The `--method` help text lists it.

## Import

- **PDF.** Nothing is rendered and no page selector runs. Every page becomes a `DIRECT_TEXT` unit with
  its own text layer.
- **PDF page with no usable text layer** (`PdfPageCandidate.needsOcr`, or a text layer that cannot be
  read). The page is kept with whatever text it has, or with no text. It is counted in the extraction
  metadata as `pages_without_text`. A document with any such page ends as **Done with warning**
  (`COMPLETE_WITH_WARNINGS`). This is the same outcome as a page whose reading finds no text
  (`empty_reading_pages`).
- **Picture files** (png, jpeg, tiff). The image is not read. The document gets one empty unit, is counted
  in `pages_without_text`, and ends as Done with warning.
- **Office, ebook and text formats.** Unchanged; they never use OCR.
- **Staged and plain paths.** Both follow the same rule. A No-OCR import is never `READ_ALL` or
  `CHECK_AND_IMPROVE`. It is snapshotted with mode `FILL_MISSING`, and the engine `NONE` decides that no
  page is read.

## Preview and approval

- The import preview reports 0 pages to read, no cost and no external sending, so there is nothing to
  approve.
- The preview hash covers the method id as it does for every method, so a confirmed No-OCR preview
  cannot start an OCR run.

## Scan again

- **PDF.** Each page is staged from the managed copy's own text layer as a `DIRECT_TEXT` page.
- **Page whose layer is unusable.** It keeps the document's currently published text for that page, the
  way an unread page already does in a rescan. A page that was read by OCR earlier is therefore not
  wiped. When there is no published text for that page, the page is staged with whatever text its layer
  has, or none.
- **Picture document.** Its published text is kept.
- **Outcome.** The finished candidate is published as one revision, and the previous version stays in
  history. Pages that kept their published text make the document Done with warning.
- **Repeating.** A rescan with No OCR is idempotent in the same way as other rescans. A repeated or
  resumed run stages the same pages and reuses pages it has already staged.

## Collection default

- A collection's default method can be `none`. It is stored as `collections.ocr_engine = 'NONE'` with no
  profile.
- `Collection.ocrSettings()` maps `NONE` to `ReadingMethod.None`, and `CollectionStore.updateOcrSettings`
  maps it back.

## Schema version 2

`collections.ocr_engine` has `CHECK (ocr_engine IN ('TESSERACT', 'SURYA', 'LLM'))`. SQLite cannot change
a CHECK constraint in place, and `SchemaMigrator` has so far had only a baseline and no upgrade path.

- **Baseline.** `001_baseline.sql` gets `'NONE'` in the CHECK. A new database is created at version 2.
- **Upgrade.** `SUPPORTED_VERSION` becomes 2, and the migrator gains one upgrade step from 1 to 2. In a
  single transaction the step:
  1. builds `collections_v2` with the version-2 definition;
  2. copies every row;
  3. drops `collections`, then renames `collections_v2` to `collections`;
  4. recreates the indexes on `collections`;
  5. runs `PRAGMA foreign_key_check`;
  6. sets `user_version = 2` and records version 2 in `schema_version`.
- **Foreign keys during the upgrade.** Foreign-key enforcement is switched off around the transaction and
  switched back on afterwards. A failed `foreign_key_check` rolls the transaction back and stops startup
  with a clear error.
- **Idempotence.**
  - A database at version 2 is left alone.
  - A crash before the commit leaves version 1 intact, and the next start runs the step again.
  - A database newer than 2 is refused, as today.
- **Documentation.** The class documentation of `SchemaMigrator` and `docs/development.md` describe the
  single upgrade step.

## Frontend

- `ReadingMethod` and `OcrEngine` in `web/src/lib/api.ts` gain `none` and `NONE`.
- **Start dialog** (`StartReadingDialog`). No OCR is shown as an option. With it selected, the dialog
  states that no page is read and the document's own text is used, and that pages without text are kept
  without text.
- **Collection OCR settings.** No OCR can be chosen as the default.
- **History and labels.** These show "No OCR" for an engine `NONE`.

## Testing

Tests are written first, at each layer:

- **`ReadingMethod`.** `none` parses, serializes and round-trips. The catalog lists it as available and
  local.
- **`PdfExtractor` with `NONE`.**
  - No page is rendered or read.
  - Clean pages are direct text.
  - A page without a usable layer keeps its own text or none and is counted.
- **`ImageExtractor` with `NONE`.** One empty unit and a count, and OCR is never called.
- **Import preview.** 0 pages to read, no cost, no approval.
- **Import end to end.** Published, searchable, and Done with warning when a page has no text.
- **Rescan with `NONE`.**
  - Pages are replaced by the text layer.
  - An unusable page keeps its published text.
  - History holds the previous version.
  - Running twice, and interrupting then running again, converges.
- **Collection default.** `none` can be set and read back, through the API and through the store.
- **Schema upgrade.**
  - A version-1 database with collections, documents, conversations and references upgrades to
    version 2 with every row and every foreign key intact.
  - `NONE` can then be stored.
  - A second run does nothing.
  - A failure injected inside the step leaves version 1 intact.
- **Frontend.** The start dialog and collection settings offer No OCR, and the preview shows 0 pages.

Manual verification:

- the upgrade, on a copy of the owner's `~/.infoscry/infoscry.db`, never on the live database;
- a No-OCR import of a real OCRmyPDF document into a temporary data directory.

## Out of scope

- Changing the page selector's thresholds or the collection's OCR language.
- Treating Office or text formats differently.
