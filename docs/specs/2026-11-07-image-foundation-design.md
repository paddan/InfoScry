# Image foundation

Status: design approved 2026-11-07; not implemented.

## Goal

An archive holds pictures as well as text: photographs imported on their own, figures and photographs
embedded in PDFs, Office files and e-books, and photographs printed on scanned pages. A person wants to find
them, see them beside the page they came from and cite them. This spec makes every such picture a citable,
stable unit of its document. It is the first of three sub-projects:

1. **Image foundation (this spec).** Find, store, show and cite images, at import and rescan. A picture's
   only searchable text is its caption or alt text.
2. **Image content tagging (later spec).** An image-analysis reading method, chosen in the start dialog like
   the OCR method (a local vision model or a configured image-LLM profile), writes a description and tags
   in three categories (person, object, animal). The text is indexed in `search_chunks`; Ask and
   Investigate cite images; search can filter on tag category.
3. **Faces (later spec).** Local face detection and face vectors (never sent to an external service, never
   logged), grouping within a collection, and an editor who names, merges and splits groups. A name applies
   to every face in its group and becomes searchable. Nothing is shared between collections. The licence of
   the face model must be chosen deliberately; common pretrained weights are non-commercial only.

The foundation is designed so that 2 and 3 can attach to an image by its stable unit ID without changing it.

## Decisions

| Question | Decision |
|---|---|
| What is an image | A content unit of its document with a new locator `ImageRegion`, published in the same revision as the page text |
| Which images | Embedded image objects (PDF, docx/pptx/xlsx, EPUB), picture regions cut out of scanned PDF pages, and imported pictures |
| Scanned regions | Surya layout `FIGURE` blocks; reused when Surya reads the page, otherwise a layout-only Surya pass |
| Control | *Extract images* in the import and rescan start dialog: Off, Embedded images, Embedded + regions from scanned pages; the collection default pre-fills it |
| Searchable text in v1 | Caption and alt text only; an image without either is shown and citable but not indexed |
| Re-reading | Image extraction has its own checkpoint, outside the text fingerprint: turning images on never repeats OCR |
| Filtering | Fixed rules in v1 (size, repetition, masks, uniform colour); no settings |

### Approaches considered

1. **Images as content units with an `ImageRegion` locator (chosen).** Reuses revisions, history, Restore,
   checkpoints, citations, deletion and the one search store. Captions are searchable through the ordinary
   publication transaction.
2. **A separate `images` table with its own index.** Cleaner isolation, but it adds a second search store,
   which the architecture forbids, and duplicates publication and history.
3. **Each image as a child document.** Easy reuse of the document pipeline, but it confuses deduplication,
   import history and attribution, and a citation loses the page the image came from.

## Rules

### Locator and identity

- `SourceLocation.ImageRegion(parent: SourceLocation, index: Int, box: NormalizedBox?)`, serial name
  `image_region`, `unitKind = IMAGE`. `parent` is the unit the image belongs to (`PdfPage`, `Slide`,
  `WordSection`, `SpreadsheetRange`, `EbookSection`, or `Image` for an imported picture). `index` is
  one-based in the parent's reading order. `box` is the image's rectangle on the parent page as fractions
  `0..1` (`left`, `top`, `right`, `bottom`, positive size), null when the format has no page geometry.
- `describe()` is `"image <index> on <parent.describe()>"`, for example *image 2 on page 14* and
  *image 1 on slide 3*. An imported picture's image unit describes as *image <name>*.
- The unit ID is a UUID v5 over the document ID, the parent unit ID, the source kind and, for an embedded or
  standalone image, its SHA-256, or, for a page region, its box rounded to three decimals. The same image on
  a repeated run or a rescan gets the same ID; tags and faces in sub-projects 2 and 3 attach to it. Two equal
  embedded images in one parent are told apart by appending their occurrence number to the hashed input.
- An imported picture keeps its existing `Image(name)` OCR unit unchanged; the image unit is an additional
  unit with parent `Image(name)`.

### Artifacts

- An embedded image is written with its original bytes when the stream is a format the reader can show
  (a `DCTDecode` stream as JPEG; PNG, JPEG and GIF media in Office and EPUB as stored); anything else,
  including JPEG 2000, CCITT and raw PDF samples, is decoded and written as PNG. A page region is cropped from the page raster and written as
  PNG. A standalone image's artifact is the managed copy itself; no second copy is written.
- Path: `artifacts/images/<sha256>.<ext>` under the document's directory, and
  `artifacts/images/thumbs/<sha256>.jpg` (longest side 320 px). Writing goes to a temporary file in the
  same directory and is moved atomically to its hash name; an existing file with the right hash is reused.
- A region and a decoded image record `source_image_*` provenance as page readings do today, so a reviewer
  knows which pixels the image came from.

### Storage

- `document_images` (revision-bound, like `page_text_revisions`): `revision_id`, `unit_id`,
  `parent_unit_id`, `source_kind` (`EMBEDDED`, `PAGE_REGION`, `STANDALONE`), `artifact_relative_path`,
  `artifact_sha256`, `thumbnail_relative_path`, `media_type`, `width_px`, `height_px`, the normalized box
  (four nullable columns, all or none), `caption`, `caption_source` (`ALT_TEXT`, `FIGCAPTION`,
  `LAYOUT_CAPTION`, null), `created_at`. Primary key `(revision_id, unit_id)`; deleted with its revision.
- `image_extraction_checkpoints`: `document_id`, `parent_unit_id`, `extractor_version`, `mode`, `outcome`
  (`DONE`, `FAILED`), `image_count`, `warning`, `committed_at`. One row per parent unit and mode.
- `collections.default_image_extraction` (`OFF`, `EMBEDDED`, `ALL`, default `OFF`), changed with the
  collection's version and answering 409 `EDIT_CONFLICT` when stale.
- The image unit's text is its caption, or empty. An empty unit is not chunked or indexed, as blank pages
  are not today.

### Extraction by format

| Source | Rule |
|---|---|
| PDF page with a text layer | PDFBox walks the page's image XObjects, including nested forms and inline images; the box comes from the current transformation matrix |
| Scanned PDF page | The full-page image is the scan and is not an image unit. In `ALL` mode, Surya layout `FIGURE` blocks (`Picture`, `Figure`, `Diagram`, `ChemicalBlock`) are cropped from the page raster; an adjacent `CAPTION` block is the caption |
| docx, pptx, xlsx | POI picture relations in document order, with the shape's `descr` as alt text; parent is the section, slide or sheet range |
| EPUB | `<img>` in spine order; `alt`, else the enclosing `<figcaption>`, is the caption |
| Imported picture | One `STANDALONE` image unit for the whole picture; no layout pass |

A page counts as scanned when one image covers at least 90 % of the page area; such a page's other embedded
images are still extracted.

The layout pass reuses the page's structure when Surya read the page in this attempt. Otherwise, in `ALL`
mode, Surya is run on the page raster for layout only; its text output is discarded and the page's reading
is unchanged. Tesseract and No OCR pages therefore get regions too.

### Filtering

Skipped, and counted in the extraction's warning summary by reason:

- shortest side under 96 px, or an area under 1 % of the parent page (when the page has geometry);
- an image whose SHA-256 occurs on more than three pages of the document (logos, rules, ornaments);
- soft masks, stencil masks, and images of one uniform colour;
- images beyond 500 per document (the document completes with a warning).

An image whose declared raster is beyond `maxRenderedPixels` is reduced to a bounded copy before decoding,
as `ImageExtractor` does today; an encoded size beyond `maxImageBytes` is skipped with a warning.

### Job flow and idempotence

- The start dialog's choice is frozen in the attempt's `OcrSettingsSnapshot` as `imageExtraction`. The
  value is not part of the text extraction fingerprint.
- Per parent unit: the text is read and committed as today, then images are extracted and committed with
  their checkpoint. A resumed attempt skips committed text and committed image checkpoints separately.
- A rescan that turns images on runs only the image step on pages whose text is carried forward unchanged;
  no OCR is repeated. A rescan with images off produces a revision without image units; earlier revisions
  keep theirs. A selected-page rescan extracts images only for the selected pages and carries the others'
  image units forward.
- Publication adds the image units, their `document_images` rows and their caption chunks in the same
  transaction as the rest of the revision.
- A stopped import or rescan never blocks starting again. Temporary image files left by a killed process
  are removed by the existing stale-state cleanup; committed artifacts are reused by hash.
- A missing Surya runtime in `ALL` mode refuses only the region step, once per document, with
  `NEEDS_SURYA_LAYOUT`; text and embedded images are imported and the document completes with a warning.
  An image that cannot be decoded fails only its own image, as a warning.

### Reading and citation

- The source view shows a strip of thumbnails under each unit that has images. Opening one shows the image
  at full size with its caption and *from page 14*; for a page region the box is outlined on the page image.
- A search hit or citation on an `ImageRegion` opens the image with its parent unit behind it. A historical
  citation opens the exact revision's artifact.
- The document details show the number of images. Every `<img>` has an `alt`: the caption, else the
  locator's description.

## API

| Route | Role | Answer |
|---|---|---|
| `GET /api/collections/{c}/documents/{d}/images?revision=` | reader | Images of the published (or given) revision with metadata |
| `GET /api/collections/{c}/documents/{d}/images/{unitId}` | reader | One image's metadata |
| `GET /api/collections/{c}/documents/{d}/images/{unitId}/file` | reader | The artifact bytes |
| `GET /api/collections/{c}/documents/{d}/images/{unitId}/thumbnail` | reader | The thumbnail bytes |

Every route has an entry in `Authorization.kt`; a collection the caller is not a member of answers 404.
Byte routes send the stored media type with `X-Content-Type-Options: nosniff` and
`Content-Disposition: inline`; SVG is never served. No route takes or returns a path. The import and rescan
start routes accept `imageExtraction`; the collection settings route accepts `defaultImageExtraction`.

## CLI

`infoscry import … --images off|embedded|all`. Without the flag the collection default applies, and a
repeated command with a different value is a different request, as a different `--method` is today. The CLI
has no rescan or collection-settings command; rescans and the collection default are set in the web UI.

## Errors and logging

Logs carry counts, codes and IDs, never image bytes, captions or document text. New codes:
`NEEDS_SURYA_LAYOUT`, `IMAGE_DECODE_FAILED`, `IMAGE_TOO_LARGE`, `IMAGE_LIMIT_REACHED`.

## Schema

`004_images.sql` (version 4) adds `document_images`, `image_extraction_checkpoints` and
`collections.default_image_extraction`, and keeps every existing row. Existing documents gain images on
their next rescan with images on.

## Testing

- Redistributable fixtures generated in tests: a PDF with an embedded JPEG, a logo repeated on every page and
  a tiny icon; a scanned PDF page with a figure; a docx and a pptx with alt text; an EPUB with `<figure>`;
  a standalone PNG.
- A fake Surya layout engine returning `FIGURE` and `CAPTION` blocks.
- Identity: the same input twice gives the same unit IDs and no duplicate files; a rescan keeps IDs.
- Idempotence: interrupt after each step (text commit, image file write, checkpoint, publication) and run
  again; the result converges.
- A rescan that turns images on makes zero OCR calls (counted on the fake engine).
- Filters, limits, decode failures and `NEEDS_SURYA_LAYOUT` warnings.
- Authorization: reader access, 404 for non-members, no SVG, `nosniff`.
- `ImagesBrowserAcceptanceTest` (`externalTest`): import with images, see thumbnails, open an image, find
  it by its caption, open a citation.
- Before region extraction is reported as working: one real Surya layout run on this Mac on a rendered
  page with a photograph.

## Out of scope

Tagging and descriptions (sub-project 2), faces and names (sub-project 3), tag filters in search, a
collection-level image gallery, SVG and vector drawings, video, and extracting images from archives other
than the formats listed.
