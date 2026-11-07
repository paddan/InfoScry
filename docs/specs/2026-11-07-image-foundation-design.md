# Image foundation

Status: implemented 2026-11-07 (see "Amendments during implementation"). Region extraction from scanned
pages is not verified against real Surya; see [implementation status](../implementation-status.md#image-foundation-2026-11-07).

## Goal

An archive holds pictures as well as text: photographs imported on their own, figures and photographs
embedded in PDFs, Office files and e-books, and photographs printed on scanned pages. A person wants to find
them, see them beside the page they came from and cite them. This spec makes every such picture a citable,
stable unit of its document. It is the first of three sub-projects:

1. **Image foundation (this spec).** Find, store, show and cite images, at import and rescan. A picture's
   only searchable text is its caption (alt text, figcaption, a layout caption, or the page text next to it) and a
   bounded excerpt of the page text around it.
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
| Searchable text in v1 | The caption (alt text, figcaption, layout caption, else the page text next to the image) and, for PDF images and scanned-page regions, a bounded excerpt of the surrounding page text; an image with neither is shown and citable but not indexed |
| Re-reading | Image extraction has its own checkpoint, outside the text fingerprint. A rescan with the rescan-only method *Keep current text* runs only the image step: turning images on for an existing document never repeats OCR |
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

- `document_images` (immutable image metadata): `document_id`, `unit_id`, `artifact_sha256`,
  `parent_unit_id`, `source_kind` (`EMBEDDED`, `PAGE_REGION`, `STANDALONE`), `artifact_relative_path`,
  `artifact_sha256`, `thumbnail_relative_path`, `media_type`, `width_px`, `height_px`, the normalized box
  (four nullable columns, all or none), `caption` (the caption only, never the context), `caption_source`
  (`ALT_TEXT`, `FIGCAPTION`, `LAYOUT_CAPTION`, `PAGE_TEXT`, null; `PAGE_TEXT` was added by schema version 5),
  `created_at`. Primary key `(document_id, unit_id, artifact_sha256)`; deleted with
  its document. Revision binding comes from the revision's page rows: each image unit has a
  `page_text_revisions` row (text = the unit text below, approval `APPROVED`) whose `source_image_*` names the image
  file, so a revision's images are its `image_region` rows joined on `(document_id, unit_id,
  source_image_sha256)`, and history and Restore work unchanged.
- An image unit's ordinal is `1_000_000_000 + parentOrdinal * 1_000 + (index - 1)`, after every text unit
  and stable for its parent and index. A parent ordinal above `1_147_482` gets no images, with a warning.
- `image_extraction_checkpoints`: `document_id`, `target`, `parent_unit_id`, `extractor_version`, `mode`,
  `outcome` (`DONE`, `FAILED`), `image_count`, `warning`, `committed_at`. `target` is the text fingerprint for
  an import and the candidate revision for a rescan. One row per target, parent unit and mode.
- `collections.default_image_extraction` (`OFF`, `EMBEDDED`, `ALL`, default `OFF`), changed with the
  collection's version and answering 409 `EDIT_CONFLICT` when stale.
- The image unit's text is its caption, a blank line and the page-text context; either part may be absent, and a
  unit with neither is empty. An empty unit is not chunked or indexed, as blank pages are not today. The chunker
  splits a unit that does not fit one passage; nothing is truncated.

### Extraction by format

| Source | Rule |
|---|---|
| PDF page with a text layer | PDFBox walks the page's image XObjects, including nested forms and inline images; the box comes from the current transformation matrix. The caption and context come from the page's text layer (see *Captions and page context*) |
| Scanned PDF page | The full-page image is the scan and is not an image unit. In `ALL` mode, Surya layout `FIGURE` blocks (`Picture`, `Figure`, `Diagram`, `ChemicalBlock`) are cropped from the page raster; an adjacent `CAPTION` block is the caption, else the page text next to the region (the page's structure blocks) |
| docx, pptx, xlsx | POI picture relations in document order, with the shape's `descr` as alt text; parent is the section, slide or sheet range |
| EPUB | `<img>` in spine order; `alt`, else the enclosing `<figcaption>`, is the caption |
| Imported picture | One `STANDALONE` image unit for the whole picture; no layout pass |

A page counts as scanned when one image covers at least 90 % of the page area; such a page's other embedded
images are still extracted.

The layout pass reuses the page's structure when Surya read the page in this attempt. Otherwise, in `ALL`
mode, Surya is run on the page raster for layout only; its text output is discarded and the page's reading
is unchanged. Tesseract and No OCR pages therefore get regions too.

### Captions and page context

Added 2026-11-07 at the owner's request; it supersedes the earlier rule that a PDF's embedded images carry no
caption. An embedded PDF image and a scanned-page region get a caption derived from the page text nearest the
image, and a bounded excerpt of the surrounding page text; both are indexed with the image unit, so a search on
either finds the image.

- **Caption source order.** A caption that exists wins: alt text, a figcaption, or a layout `CAPTION` block. The page
  text is the fallback, recorded as `PAGE_TEXT`. `document_images.caption` is the caption only.
- **The unit's text** is the caption, a blank line, then the context; either may be absent. A caption from alt
  text with no context is unchanged.
- **Text source.** For a PDF page, the text layer with positions (PDFBox `PDFTextStripper` text positions grouped
  into lines), with each line's box in the same normalized coordinates as the image's box: the crop box, y flipped to
  a top-left origin, and the page rotation applied. Text that is not upright in the displayed page is not used.
  For a scanned-page region, the page's structure blocks (heading, paragraph, list item, caption and footnote
  blocks, each with a pixel box over the page raster); a stored structure keeps block boxes and no word boxes, so
  a block is the finest unit there. Page headers, footers, tables, formulas and code are not used.
- **Caption rule** (`PageTextContext.captionFor`). The contiguous lines directly below the image, else directly
  above, at most 3, each within 3 % of the page height of the image or of the previous line and sharing at least
  half of the narrower width with the image. A block starting with `Fig`, `Figur`, `Figure`, `Bild`, `Foto`,
  `Photo` or `Tab` followed by a number or punctuation wins over a plain adjacent block; with no such label the
  block below is used, else the block above. A line longer than 400 characters is body text, not a caption.
- **Context rule** (`PageTextContext.contextFor`). The page's other lines, nearest to the image first (vertical
  distance, then horizontal), without the caption, joined with spaces until 600 characters and cut at a word
  boundary.
- **Refreshing.** The text of an image unit changes only when the image is delivered again. The image
  extractor version is now `2`, so an import that resumes or is retried looks at its pictures again, and any
  rescan with the automatic page selection or the rescan-only *Keep current text* (which reads no page) delivers every
  picture into its new revision with the caption and context. A rescan of an explicitly selected set of pages carries
  the unselected pages' pictures forward with their old text; only the selected pages are delivered again. A document
  already complete keeps its old unit text until it is rescanned.
- No document text is logged.

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
- Rescan supports PDFs and pictures, as today; images in Office files and e-books come from their import.
- The rescan dialog offers the reading method **Keep current text** (id `keep`, rescan only). It carries
  every page's published text forward unchanged and runs only the image step on the selected pages; no OCR
  and no text layer is read. A rescan with images off produces a revision without image units; earlier revisions
  keep theirs. A selected-page rescan extracts images only for the selected pages and carries the others'
  image units forward.
- Publication adds the image units and their caption chunks in the same transaction as the rest of the revision.
  The `document_images` rows are recorded by `ImageStore.record` in their own transaction, when the image is
  written; a revision is bound to its images through its page rows (`image_region` pages joined to
  `document_images` on document, unit and source image), not through the publication transaction.
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
`NEEDS_SURYA_LAYOUT`, `IMAGE_DECODE_FAILED`, `IMAGE_TOO_LARGE`, `IMAGE_LIMIT_REACHED`, `TOO_MANY_IMAGES`,
`IMAGE_PARENT_UNREADABLE`, `IMAGE_EXTRACTION_FAILED`, `IMAGE_PARENT_ORDINAL_TOO_LATE`,
`IMAGE_SOURCE_UNREADABLE` (amended 2026-11-07). The most important image code of a run is recorded on the document
(error code and message) when it completes with a warning, and the reader names it; `NEEDS_SURYA_LAYOUT` comes
first and carries its install remedy.

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

## Amendments during implementation (2026-11-07)

Rulings made while implementing; they take precedence over the sections above.

- Codes `TOO_MANY_IMAGES` (a parent holds more images than one parent may deliver), `IMAGE_PARENT_UNREADABLE`
  (the finder could not read the parent; its checkpoint is `FAILED` so the next run tries again) and
  `IMAGE_EXTRACTION_FAILED` (the stage failed for a reason of its own; the text is kept, no checkpoint is
  written) join the code list.
- `IMAGE_PARENT_ORDINAL_TOO_LATE` (a parent too late in the reading order for its images to have ordinals; a `DONE`
  checkpoint with no images) and `IMAGE_SOURCE_UNREADABLE` (the document could not be opened to look for images)
  join the code list.
- Vector drawings (EMF, WMF, SVG, PICT) in Office and EPUB documents are the skip reason `VECTOR`: counted in the
  checkpoint and the run's skips, never a warning, because the spec puts vector drawings out of scope.
- Image checkpoints exist only for `EMBEDDED` and `ALL`; `OFF` writes none.
- Each parent reconciles what it holds: after a delivery, the image units in the parent's ordinal band (its
  `ImageIdentity.ordinal(p, 1)` to `ordinal(p, 1000)`) that the delivery did not produce are removed, with their
  passages, in both targets (published content and a candidate revision). So a re-run in another mode, a retry
  that finds fewer images, or the cap, leaves no stale unit. A parent that could not be read at all keeps what it
  holds. With images off an import or a rescan removes every image unit of the attempt. A retry carries the image
  extraction of the document's last attempt, else the collection default; it never falls back to off.
- A page that cannot be rendered or read for its regions fails alone: it warns `IMAGE_PARENT_UNREADABLE`, commits a
  `FAILED` checkpoint and the stage goes on. The repetition pass and the delivery pass each open their own source,
  and an EPUB chapter is parsed once per spine item, so a long book is not charged its archive budget per section
  and per pass. A PDF stencil mask is counted as `MASK` from its XObject flag and never decoded.
- When images are extracted, a picture whose OCR reading is empty (a photograph with no words) keeps a unit with no
  text, counted like an empty-reading PDF page, so its `STANDALONE` image unit has a parent and the document
  completes with a warning. With images off the empty reading fails its unit as before. A document that is only
  pictures has no passage to index: its publication clears its search rows rather than being refused.
- A standalone picture whose type browsers cannot show (TIFF) gets a PNG display copy of its bounded decoded
  picture, written once at `artifacts/images/<sha>.png` beside its thumbnail; `/file` serves that copy and the
  managed copy is never sent or changed.
- The collection default and the OCR settings are saved in one store transaction under the caller's version, so an
  image-default change sent beside an unchanged language still answers `409 EDIT_CONFLICT` when stale.
- An xlsx picture anchored outside every emitted range is not found. Legacy `.doc`, `.xls` and `.ppt` files and
  e-books other than EPUB have no image finder in v1.
- `STANDALONE` images are exempt from the size and one-colour filters.
- The 500-image cap is an invariant: a parent may deliver at most 500 minus the images in every committed
  parent minus the images delivered earlier in the run. For a document over the cap, which images survive near
  the cap can depend on which parent was retried.
- A parent holds at most 512 MiB of retained image bytes (`IMAGE_TOO_LARGE` for the rest), and each PDF XObject
  is decoded once per parent however often the page draws it.
- A page raster over `maxRenderedPixels` gets no regions (`IMAGE_TOO_LARGE`); the raster is never subsampled,
  because the layout boxes are in its own coordinates.
- Migration `004_images.sql` also adds the `source_image_*` columns to `content_units`, written only for image
  units, so an import's published revision finds its images the same way a rescan's does.
- A real Surya `Picture` block is markup with no words, and the parser and structure derivation used to drop
  every word-less block, so no `FIGURE` block could reach `LayoutRegions`. They now keep a word-less `FIGURE`
  block that has a box, as a block with an empty text range and its box; it adds no text and no separator, and
  chunking, the reader's structure view and Ask evidence ignore empty blocks. Other word-less blocks are still
  dropped. `STRUCTURE_VERSION` is unchanged, but the Surya parser identity is now `surya-html/2`. The identity is part
  of an attempt's fingerprint, so this changes the fingerprint of new Surya import attempts (an in-flight Surya
  import from before the upgrade starts its reading over). A structure stored by `surya-html/1` has no figures, so
  the region pass treats it as absent and runs the layout pass; so it does for a stored structure whose image hash
  is not the page raster being cropped, whose boxes would not fit. Two real
  Surya runs on synthetic pictures returned no `Picture` block even after the fix, so region extraction from
  scanned pages remains not verified against real Surya; it needs a run on a real photograph.
- ~~A PDF's embedded images have no caption source.~~ Superseded 2026-11-07 (Task 12): a PDF image and a scanned-page
  region take a `PAGE_TEXT` caption and a context from the page text (see *Captions and page context*). Schema
  version 5 (`005_caption_source_page_text.sql`) allows the new `caption_source`.

## Out of scope

Tagging and descriptions (sub-project 2), faces and names (sub-project 3), tag filters in search, a
collection-level image gallery, SVG and vector drawings, video, and extracting images from archives other
than the formats listed.
