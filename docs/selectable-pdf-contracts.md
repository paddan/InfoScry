# Selectable PDF interface contracts

Current interface reference for the implemented PDF-copy feature. Read the
[design](selectable-pdf.md) and [remaining integration work](tickets/selectable-pdf/STATUS.md).
Update contracts and consumers together when a change is authorized.

## Product boundaries

The purpose is selecting and copying the published reading over the page image.
Archive search stays in Lucene. Do not add PDF search controls, another index,
embedding work or a new OCR fallback. Original and managed-original bytes remain
immutable. The generated file is a separate revision-bound copy.

Real-provider tests and archive reset require separate authorization. Preserve
unrelated edits and keep UI copy in English.

## Layout types

Package `infoscry.pdf`; serializable immutable records. Exact finite/range/hash
invariants are validated at construction/read boundaries. UTF-16 offsets address
canonical `extractedText`, never the string normalized for matching.

```kotlin
@Serializable data class TextRectangle(val left: Double, val top: Double, val width: Double, val height: Double)
@Serializable enum class SpanGranularity { WORD, LINE }
@Serializable data class PositionedTextSpan(val start: Int, val endExclusive: Int, val rectangle: TextRectangle, val granularity: SpanGranularity, val order: Int)
@Serializable data class ImageToPdf(val a: Double, val b: Double, val c: Double, val d: Double, val e: Double, val f: Double)
@Serializable data class PdfPageGeometry(val mediaX: Double, val mediaY: Double, val mediaWidth: Double, val mediaHeight: Double, val cropX: Double, val cropY: Double, val cropWidth: Double, val cropHeight: Double, val rotation: Int, val userUnit: Double)
@Serializable data class PageTextLayout(val version: Int, val textSha256: String, val imageSha256: String, val width: Int, val height: Int, val geometry: PdfPageGeometry, val transform: ImageToPdf, val spans: List<PositionedTextSpan>, val complete: Boolean, val reasonCode: String?, val positioningVersion: String)
@Serializable data class LayoutReference(val relativePath: String, val sha256: String)
```

`ImageToPdf` maps top-left image pixels into unrotated PDF user space:
`x' = a*x + c*y + e`, `y' = b*x + d*y + f`. PDF page rotation is applied once
by the viewer. Layout geometry belongs to the exact rendered crop/image; a writer
normalizing output page rotation must compose the corresponding transform.

Non-whitespace canonical characters must be covered exactly once for complete
layouts; whitespace gaps are permitted. Ranges cannot split surrogate pairs or
overlap; order is unique and follows the canonical reading. Rectangles must be
positive and within the image. A multi-line Surya block is not a LINE span.
Hash mismatch is corrupt provenance, not low OCR confidence. Missing/ambiguous
alignment is an explicit incomplete layout with a safe reason.

**Resolved by ticket 01, 2026-10-10 (binding on 02's freeze):**

- *Tolerance.* The native PDFBox round-trip (01's proven case: this writer's
  own invisible text written and read back) keeps the 1pt bound from the
  plan — pure geometry, no OCR noise. An OCR-sourced anchor (Tesseract boxes,
  or anything 03's aligner derives) is never checked against an independent
  ground-truth box — none exists for a real page. Its acceptance is
  self-consistency: after 05's horizontal-scale (Tz) fit, the written glyph
  run lands inside its span rectangle plus a fixed 1.5pt inset, with no
  visible overflow. No calibration/correction layer; the fixed inset is the
  whole mitigation.
- *Granularity.* A span is always token-level, never per-character. A
  corrected or merged token is one span over its full canonical range with
  one rectangle (the recognizer's box, or the union of two boxes for a
  merge). A split token (one recognized box, two canonical words) divides
  that box into two rectangles by estimated character-width proportion.
  **Same-line merges only.** A merge whose two anchors sit on different
  lines (a line-end hyphen join, e.g. "stor-" / "kostnad" → "storkostnad")
  is never a single cross-line union rectangle — that produces a nonsensical
  box spanning the gap between lines. Instead it becomes two independent
  WORD spans, each covering its own anchor's canonical sub-range (split at
  the line boundary) with that anchor's own rectangle, exactly like an
  ordinary split token. Resolved by ticket 03, 2026-10-10.
- *Surya/LLM.* A Surya block is never itself a WORD/LINE span; it is only a
  search window 03's local aligner places canonical text inside. LLM text is
  positioned only through the same local anchors, never from LLM output
  directly. Anything the aligner cannot confidently place inside its block
  makes that page's layout `complete = false` with a reason code; 06/05 map
  an incomplete layout to `PdfPageMode.UNAVAILABLE` for that page (reading
  image still shown, nothing selectable, listed in the manifest's
  `unavailablePages`) — reusing the existing coverage state, not a new one.

## Local alignment (implemented by 03)

```kotlin
data class GeometryAlignmentInput(val canonicalText: String, val image: PageImage, val geometry: PdfPageGeometry, val transform: ImageToPdf, val anchors: List<OcrWordBox>, val positioningVersion: String)
// OcrGeometryAligner.align(input: GeometryAlignmentInput): PageTextLayout
```

The anchors are word/line positions from a local positioning pass, not arbitrary
Surya blocks relabelled as words. No network/provider call in this class. The
caller (04) obtains anchors with existing local process limits. Canonical text is
never replaced by anchor text. Bound alignment work; on ambiguity or exhausted
work budget return incomplete geometry, never truncate the reading.

`PageTextLayout.verifiedAgainst(canonicalText)` (02) is the single checkpoint
that proves `complete = true` against actual content, not just internal
structural validity. `OcrGeometryAligner.align` must return only a layout that
has already been run through it — never hand back a structurally-valid-but-
unverified layout and leave verification to the caller.

`OcrGeometryAligner` (03) requires its `anchors` input in genuine visual
reading order (the engine's own block/line/word order) — it treats input
order as ground truth for monotonic matching, not something it re-derives.
04 must pass anchors in that order. On an incomplete result it sets
`reasonCode` to one of two strings: `PDF_TEXT_ALIGNMENT_AMBIGUOUS` (anchors
exist but cannot be confidently matched — repeats, reordering, no safe
one-edit candidate) or `PDF_TEXT_ALIGNMENT_LIMIT_EXCEEDED` (canonical text,
anchor count or comparison work exceeded the aligner's bounded limits). 04/05
must recognize both as safe, retryable-only-by-re-running-locally reasons,
never as corrupt provenance.

## Layout artifact storage (implemented by 04)

```kotlin
// As actually implemented (the sketch below omitted the canonicalText parameter
// read() needs to call verifiedAgainst, and assumed an AppPaths/ids-based store
// where call sites only ever hold a resolved artifact-root Path):
object PageTextLayoutStore {
  fun write(root: Path, layout: PageTextLayout): LayoutReference
  fun write(paths: AppPaths, collectionId: CollectionId, documentId: DocumentId, layout: PageTextLayout): LayoutReference
  fun read(root: Path, reference: LayoutReference, canonicalText: String): PageTextLayout
  fun read(paths: AppPaths, collectionId: CollectionId, documentId: DocumentId, reference: LayoutReference, canonicalText: String): PageTextLayout
  fun verifyImageMatch(layout: PageTextLayout, actualImageSha256: String): PageTextLayout  // throws PageTextLayoutProvenanceException on mismatch
}
```

Layout references are separate from existing engine/TSV references. Write sidecar
bytes atomically before storing a page reference. Every committed/reused reference
is hash-bound to its canonical text, reading image and page geometry. Preservation
and Restore inherit it with the producer; no inference from equal text/image bytes.

`read` must call `verifiedAgainst(canonicalText)` on the deserialized layout
before returning it — a hash mismatch there is corrupt provenance and must
surface as 02's dedicated provenance exception, never a generic validation
failure a caller could mistake for a different bug.

`verifiedAgainst` only proves `textSha256` against actual canonical text; it
has no image bytes to check `imageSha256` against. 04 owns that half: when it
loads the reading image for a page, it independently hashes the actual bytes
and compares them to `layout.imageSha256`, surfacing a mismatch the same way
(02's provenance exception, not generic validation). Neither side is complete
alone.

## Writer (implemented by 05)

```kotlin
enum class PdfPageMode { NATIVE, OCR, BLANK, UNAVAILABLE }
@Serializable enum class PdfPageCoverage { NATIVE, OCR, BLANK, UNAVAILABLE }
data class PdfPageInput(val ordinal: Int, val canonicalText: String?, val readingImage: Path?, val layout: PageTextLayout?, val geometry: PdfPageGeometry, val mode: PdfPageMode)
data class PdfWriteRequest(val original: Path, val revisionId: String, val pages: List<PdfPageInput>, val output: Path)
@Serializable data class PdfPageResult(val ordinal: Int, val coverage: PdfPageCoverage, val reasonCode: String?)
@Serializable data class SelectablePdfManifest(val revisionId: String, val rendererVersion: String, val fontHash: String, val pages: List<PdfPageResult>)
data class PdfWriteResult(val manifest: SelectablePdfManifest, val sha256: String, val bytes: Long)
// SelectablePdfWriter.write(request: PdfWriteRequest): PdfWriteResult
```

Input includes every physical page, even when no extracted unit exists. Mode is
resolved by 06 from revision provenance, never from text being empty. NATIVE copies
original page objects/text; OCR rebuilds from the reading image with one new layer.
UNAVAILABLE preserves visible content with no stale text on rewritten pages. If
that cannot be achieved without omitting a visible page, generation fails.
BLANK requires verified blankness. No result drops a page or hides a glyph failure.

For manifests, `totalPages` counts every physical page. `selectablePages` counts
NATIVE/OCR only. A verified BLANK is complete but has no selectable text; an all-
blank PDF may be READY with 0/N. Any UNAVAILABLE makes an artifact PARTIAL. Explain
blank and unavailable counts separately so X/Y is not mistaken for a failure count.

## Service and wire DTO (implemented by 06; DTO owned by 02)

```kotlin
@Serializable enum class PdfArtifactState { NOT_CREATED, QUEUED, BUILDING, READY, PARTIAL, FAILED }
@Serializable data class PdfArtifactStatus(val revisionId: String, val key: String?, val state: PdfArtifactState, val selectablePages: Int, val totalPages: Int?, val blankPages: Int, val unavailablePages: List<PdfPageResult>, val reasonCode: String?)
@Serializable data class CreateSelectablePdfRequest(val revisionId: String, val retry: Boolean = false)
// SelectablePdfService.status(collectionId: CollectionId, documentId: DocumentId,
//                            revisionId: String): PdfArtifactStatus
// SelectablePdfService.ensure(collectionId: CollectionId, documentId: DocumentId,
//                            revisionId: String, retry: Boolean): PdfArtifactStatus
// SelectablePdfService.file(collectionId: CollectionId, documentId: DocumentId,
//                          revisionId: String, key: String): Path
```

NOT_CREATED is a DTO state, not a queued job/DB row. It has null key, 0 selectable,
0 blank, no unavailable-page results, and nullable physical total if not measured.
QUEUED/BUILDING have a non-null version/input key and may have unknown total.
READY/PARTIAL have verified file/hash/manifest and known totals; file is internal
server use only. FAILED returns a safe reason and no usable file. No raw paths,
source text or provider bodies in DTOs/errors/logs.

`ensure` returns the existing QUEUED/BUILDING/verified READY identity; PARTIAL or
FAILED is retried only with retry=true. Retrying an old attempt converges on one
live generation for that input. Explicit retry may rebuild missing layouts locally,
never repeat transcription. Store intent transactionally with publication or
reconcile the publication/enqueue gap; no permanently lost automatic generation.

## HTTP and frontend (07 and 08)

```text
GET  /api/collections/{id}/documents/{documentId}/selectable-pdf?revision={revisionId}
POST /api/collections/{id}/documents/{documentId}/selectable-pdf
     { revisionId: string, retry: boolean }
GET  /api/collections/{id}/documents/{documentId}/selectable-pdf/file?revision={revisionId}&key={key}&inline=true
```

All operations require a named published revision and collection-scoped ownership.
GET status: 200 typed status. POST: 200 current typed status, existing CSRF/mutation
admission. File: 200 verified application/pdf; inline=true means inline, otherwise
attachment; ETag is content hash. Missing/foreign/unpublished identity: 404;
malformed fields: 400; valid identity but file not ready/corrupt: 409 with safe
reason. Preserve existing authentication statuses. GETs never generate or enqueue.
Serve collection-scoped verified files with existing source-route confinement.

Frontend functions owned by 08 in `api.ts`:
`getSelectablePdfStatus(collectionId, documentId, revisionId)`,
`createSelectablePdf(collectionId, documentId, request)`,
`selectablePdfFileHref(collectionId, documentId, revisionId, key, inline)`.
Status/POST return Promise<PdfArtifactStatus>; href returns an encoded relative URL.
Normalize omitted nullable DTO fields as existing API conventions require.

`SelectablePdfActions.svelte` consumes collectionId, documentId and revisionId.
It fetches status, polls only QUEUED/BUILDING while mounted/visible, and ignores
responses from earlier identity tuples. It owns no selected revision or OCR state.
For null/unknown revision, show unavailable without inventing identity or dispatch.

Copy: Open PDF with selectable text; Download PDF with selectable text; Create
PDF with selectable text; Retry PDF generation; Selectable text on X of Y pages.
Keep Open original outside these actions and always reachable. Explain flattened
OCR pages once. PARTIAL lists exact unavailable pages and reasons before download.

## Agent handoff and evidence

Each agent returns: owned files changed, numbered acceptance results, failing then
passing commands/output, exact public signatures, unresolved limits and unrelated
changes left untouched. No raw document contents or secrets. Report meaningful
behavioral RED results; a missing class compilation error alone is insufficient.

The coordinator reviews each deliverable before unlocking dependents. Contract
changes update this document, plan and every affected consumer in one coordinated
slice; do not modify another active agent's files. Run ./gradlew check after each
integrated milestone, not concurrently in several agents sharing build outputs.
