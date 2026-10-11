# PDF copies with selectable text

Implemented feature reference. Patrik confirmed the remaining OCR/PDF checks
complete on 2026-10-11; see [verification status](implementation-status.md#owner-confirmed-ocrpdf-checks-2026-10-11).
[Interface contracts](selectable-pdf-contracts.md) describe revision/layout and
HTTP boundaries. [First-import automatic generation](tickets/selectable-pdf/STATUS.md)
remains a separate integration decision.

## Intent and success

The owner wants to select and copy the scanned text directly over the page image
in the copied PDF. InfoScry currently stores the reading separately. A generated
PDF must expose the published reading, retain the document's page order and
appearance, and open in an ordinary PDF reader outside InfoScry.

Rescan and History → Restore queue a derived PDF. A plain first import currently
requires **Create PDF with selectable text**; automatic generation for that path
remains a separate integration decision. Open/download actions appear beside
Open original.

Archive search remains in Lucene, using the existing extracted text and index.
PDF generation adds no search feature, alternate index or search dependency. A
PDF reader may incidentally find text in its text layer; that is not an acceptance
criterion. Correct spatial selection and clipboard text are the acceptance criteria.

## Engine boundaries

Tesseract provides word rectangles. Surya can provide block-level rectangles;
LLM readings have no native positions. The implemented local positioning pass
aligns the published reading to local anchors without changing its text or
sending it externally again. See the shared contracts for layout, provenance,
revision identity and coverage rules. Real-engine checks were confirmed complete by Patrik on 2026-10-11.

## Chosen approach and alternatives

Recommended: a revision-bound derived PDF, using the existing PDFBox dependency.
Copy untouched native-text pages from the managed original. For a page whose
published reading is OCR, rebuild its visible page from its exact reading image
and add only the published text as invisible positioned text. Always build from
the original and revision artifacts, never from a previous generated PDF.

This prevents duplicate old/new OCR layers without trying to remove arbitrary
PDF drawing operators. The tradeoff is that rewritten OCR pages are rasterized:
their editable vectors, interactive forms, links and annotations are not retained
as interactive objects. The original remains available. Render their visible
annotation appearances consistently with the image used for reading; document
any discrepancy during the feasibility gate. Preserve page dimensions, orientation,
page count and page order; preserve/remap document bookmarks where possible.

Alternatives considered:

1. Append invisible OCR to every original page: smaller change, but old text can
   remain selectable and be copied twice. Rejected for replacement readings.
2. Strip existing PDF text operators, then graft new text: preserves more vector
   content but can damage visible text, clipping, forms and reused XObjects.
   Deferred beyond the first version.
3. Let Tesseract or OCRmyPDF independently reread/export the file: convenient,
   but the text could differ from the selected Surya/LLM reading. Rejected as
   the source of exported text.

## Text and geometry contract

The exact revision's `extractedText` is authoritative. Persist a versioned
sidecar mapping UTF-16 text ranges to image-pixel rectangles, their reading order
and granularity, plus text SHA-256, image SHA-256, image dimensions, PDF page
geometry and the full image-to-page transform. Preserve this reference through
import checkpoints, candidate staging, unselected-page inheritance and Restore.
Artifact bytes are written atomically before committing a referring page.

Tesseract TSV can supply positions for the same reading. Surya blocks require
line/word refinement before being advertised as spatially selectable text.
For Surya refinement and LLM text, use a **local positioning pass** on the exact
image: existing Tesseract TSV supplies spatial anchors; an alignment algorithm
maps the published text onto anchors in reading order. Its recognized text never
replaces the published text, and this is not an implicit reading-engine fallback.
No extra external LLM request, prompt change or image transmission is allowed.

Normalize only for matching, retaining a reversible map to canonical offsets.
Handle whitespace, ligatures, line-end hyphenation and split/merged words;
corrected spellings may use their matched word's rectangle. Repeated anchors,
missing lines, insertions without a visual match and reordered columns must be
reported as ambiguous, rather than assigned fabricated coordinates. A match
score alone is not proof of correct placement. Save alignment provenance/version.

A page is complete only when every non-whitespace character is covered exactly
once by supported line/word spans, with valid finite rectangles and a verified
transform. Surya block-level output alone is insufficient. Blank pages need no
text; pages not successfully read retain their published baseline reading.
A PDF may be generated with some pages lacking an added layer, but its UI and
manifest must explicitly report which pages and why. Such an artifact is PARTIAL,
never READY. Where stale original text disagrees with the published reading,
use the reading image without the stale layer even in a PARTIAL export.
If the required visual page cannot be reconstructed without retaining stale text,
fail PDF generation with a safe reason rather than omit the page or return a
misleading copy. The published reading and original remain available.

## Generation and lifecycle

Use an independent durable derived-artifact job in the existing application
process. A failed export never changes import/rescan success, the active reading,
embeddings or search. SQLite owns desired artifact identity and job status.

Cache identity includes collection/document, managed-original hash, revision ID,
ordered page text/image/layout hashes and renderer/font versions. Store under
`artifacts/<collection>/<document>/selectable-pdf/<revision>/<key>/` with a PDF
and manifest. The manifest lists page coverage, transformations and safe errors.
No source text belongs in status messages, filenames or logs.

Queue from successful publication, including Restore, with an idempotent unique
key. Recovery reconciles missing work from published revisions so a crash between
publication and enqueue cannot lose generation. Regeneration uses committed OCR
and positioning artifacts; it never repeats paid OCR. A missing positioning
artifact can be rebuilt locally on explicit retry; unavailable tooling produces
an actionable export status without blocking reading or rescanning.

States: QUEUED, BUILDING, READY, PARTIAL, FAILED. Retry FAILED or PARTIAL explicitly;
repeated admission while QUEUED/BUILDING or with a verified READY artifact returns
that result. Write PDF/manifest to temporary paths, reopen and validate, atomically
publish files, then mark the row ready. Recover each interruption boundary,
including files published before the database update. Ignore orphan temporary
files and clean them idempotently. Collection/document deletion removes derived
artifacts and prevents late worker writes from recreating deleted data.

Every request resolves the requested revision once. Current UI shows only the
active revision's status; an older export finishing later cannot become current.
Historical page review can open the export for its own published revision.
Unknown, candidate and withdrawn revisions never masquerade as current exports.

Existing archived PDFs are eligible through an explicit Create PDF with selectable text
action; no mass OCR rerun, data conversion, automatic archive-wide backfill or
owner database reset. Schema additions follow the repository's baseline-only
policy; deployment compatibility is reported before installation, not solved by
an unapproved migration or reset.

## PDF encoding and limits

Use PDFBox invisible text rendering (`RenderingMode.NEITHER`) and embedded fonts
with Unicode mappings. Pin a redistributable font asset and license; begin with
Noto Sans for the Swedish/English fixtures and report unsupported characters
without substitution or omission. Broader script coverage needs explicit font
assets and verified shaping/reading order before being claimed supported.

Position against the actual raster dimensions and stored transform, not an
assumed 300 DPI. Test rotations 0/90/180/270, nonzero CropBox origins, differing
MediaBox/CropBox, UserUnit and reduced-resolution rasters. Prefer the committed
reading image; rebuild it only from verified immutable input and the same render
contract. Lossless image embedding in the first version avoids another lossy
conversion. Process one page at a time with existing PDF/raster limits, temporary
file-backed storage and a cancellable job. Record duration and byte counts, not
text. File size and native Preview memory checks were included in the owner-confirmed completion.

This is a PDF copy with selectable text, not a tagged accessible PDF, PDF/A
conversion, signature-preserving rewrite or source-viewer replacement. Show a
single explanation that OCR pages are flattened copies; Open original remains.

## UI and HTTP

English copy: Open PDF with selectable text, Download PDF with selectable text,
Create PDF with selectable text, Retry PDF generation, and Selectable text on
X of Y pages. Show a compact
status next to the original link in PageReview, shared by Collections and cited
Ask/Investigate source views. No new column or start-dialog approval pause.

GET status and GET file are read-only. Creating/retrying is an idempotent POST
under existing bearer/CSRF and shared mutation admission. Use collection-scoped
opaque document/revision IDs, never file paths. Return revision ID and coverage
in status; file response has `application/pdf`, safe Content-Disposition, exact
length, private cache policy and an ETag from the verified artifact hash. Opening
or downloading performs no generation and no provider call.

## Acceptance and verification

Automated generated fixtures must establish exact copied text, no stale/duplicate
OCR, correct word/line positions, unchanged source hashes, revision coherence,
repeatability, cancellation/recovery and deletion. Rendered appearance must match
the input page image at the defined scale. Browser tests use fake providers and
download the real generated PDF; they do not alone prove native selection UX.

Native-reader checks cover selecting and copying Swedish text in macOS Preview and a second PDF
reader; inspect corrected words over the image, rotated pages, a mixed native/
scanned PDF, a real Surya page and an explicitly authorized real LLM reading.
Check text order, memory/file size and annotations. Patrik confirmed the remaining OCR/PDF checks complete on 2026-10-11. This is
owner confirmation, separate from the automated fake-provider evidence; no new
provider, CoreML or native-reader test was run by the agent for this update.

## Primary technical references

- [PDFBox 3.0.7 invisible rendering mode](https://github.com/apache/pdfbox/blob/3.0.7/pdfbox/src/main/java/org/apache/pdfbox/pdmodel/graphics/state/RenderingMode.java).
- [PDFBox 3.0.7 embedded Type 0 fonts](https://github.com/apache/pdfbox/blob/3.0.7/pdfbox/src/main/java/org/apache/pdfbox/pdmodel/font/PDType0Font.java).
- [Tesseract TSV and PDF output](https://tesseract-ocr.github.io/tessdoc/Command-Line-Usage.html).
