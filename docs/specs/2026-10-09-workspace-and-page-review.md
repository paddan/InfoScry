# Wide workspace and page reading review

Status: Implemented 2026-10-10; automated gates passed with fake providers. Real
Surya/LLM and CoreML/GPU workflow, human keyboard/screen-reader review, and the
format-specific source-viewer finish remain open. [Plan](../plans/2026-10-09-workspace-and-page-review.md)
· [Tickets](../tickets/workspace-and-page-review/STATUS.md).

## Purpose and agreed scope

The owner primarily uses Collections to check import/reading results and to read
individual poorly extracted pages again with Surya or an image-capable LLM.
Show extracted text beside the image it came from. Use available window width
across Search, Ask, Investigate and Admin rather than centering narrow panels.
The owner accepted an adaptive split view: preserve the search result or answer
beside its source; document review from Collections uses the whole workspace.

This extends the [compact document list](2026-09-30-compact-document-list.md).
It also extends the [OCR workflow](2026-10-08-ocr-workflow-redesign.md) with explicit
page selection; automatic PDF selection remains the default for ordinary runs.
This spec governs the new manual mode. Existing import, Retry and automatic
rescan behavior is unchanged. This document records the implemented manual-mode
contract; [ticket status](../tickets/workspace-and-page-review/STATUS.md) records
verification and the remaining manual gates.

## Common workspace

Keep the existing navigation and English product copy. Remove the shared 78rem
cap and the Collections 52rem / LLM Admin 44rem caps on work areas. Use modest
responsive outer gutters (16–24px as the starting range). Do not stretch prose or
short form fields simply because their parent is wide: answer paragraphs should
have a readable measure around 65–80 characters, while tables and source panes
use the available width. No new UI dependency or persistent preference store.

Search places the selected source beside the results when both panes fit. Ask
and Investigate place it beside the answer; history is independently collapsible
and must yield before answer/source become unusably narrow. Without an open
source, use the space for the current task. Opening/closing a source preserves
query, conversation and meaningful focus. On narrow screens show one pane at a
time with an explicit Back action. Layout decisions use the actual workspace
width after navigation, not an assumed screen size. At a minimum, verify 390,
1024, 1440 and 1920px windows. No page-wide horizontal overflow.

LLM Admin uses width to arrange related settings and probe results; preserve
existing validation, model selection, name suggestions and image-check feedback.
Collections puts import/reading status and the compact document section before
secondary settings/history. Settings and import history become disclosures;
active import progress and failures remain visible independently. Delete
collection remains a clearly labelled secondary action inside settings.

## Compact document list

Keep the existing compact-list contract: collapsed on first visit/reload,
collection-wide counts, 50-row paging, session view memory, 300ms filename search,
one inline details row and no hidden destructive selection. Counts for ongoing
work use the existing status vocabulary; do not invent a persisted status or
pretend heuristic quality measures OCR accuracy. Keep active import progress
outside the collapsed document section. Total and status shortcuts are separate
buttons; status buttons filter exactly one existing status.

Rows emphasize filename, reading status, live progress and access to details or
Open document. Move media type, size and import date into details. Keep deletion
and Retry accessible without crowding every row with duplicate actions. Preserve
whole-collection Retry semantics. The bounded table may use available width;
controls and pager remain outside its vertical scroll area. Details stay brief:
metadata, warnings, actions and History; the page comparison belongs in the
workspace reader, not inside a table row. No new mandatory review/approval stage.

## Page review

Opening a PDF/image document from Collections uses the workspace for review.
Show image left and extracted text right, initially equal widths, with a keyboard
operable draggable divider. Keep page navigation, page number/total and Scan this
page again above both panes. Preserve the list's view state when returning.
Support zoom and fit-to-pane for images and independent image/text scrolling.
Do not claim line-aligned scrolling without OCR coordinates. On narrow screens,
Image/Text tabs preserve the same page, revision and zoom while switching.

Use zero-based ordinals in contracts and one-based page numbers in UI. Navigate
the document's physical PDF/image pages, including pages with empty/failed text;
content-unit-only navigation must not silently omit them. A missing image or text
has its own loading/error/empty state. Retry fetches only that resource. Page
changes invalidate both old requests immediately, and must never pair one page's
image with another's text. Text remains bounded and supports Load more.

Resolve a live read to one exact published revision before fetching its text and
image; a later publication must not silently mix versions. Offer refresh to the
new reading after publication. Historical citations stay pinned to their recorded
revision and page. Unknown-revision citations keep the saved-excerpt behavior;
do not manufacture an image pairing or offer a rescan of historical evidence.

Prefer the exact stored source-image artifact for OCR text. Where text came from
a PDF text layer, render the corresponding page of the verified immutable managed
copy and label it Original page, not the image used by OCR. If a stored OCR image
is missing, show an explicit unavailable state; an optional original-page fallback
must be labelled as such. Show recorded page-level method/profile when available;
for preserved pages do not attribute the latest document operation's method to
all pages. Unknown provenance is shown as unavailable.

Persist a nullable page-level `producerRevisionId` with each revision page.
A successful extraction or reread records its producing candidate revision even
when text and image match the previous version. Preserving a baseline page after
a skipped or failed read, and restoring a revision, copies its recorded producer
and source image unchanged, including unknown values. Do not infer producers from
equal text/images or backfill unknown historical origins. Resolve the method from
the same document's recorded producer and its frozen reading snapshot. Repeating
page append/checkpoint or recovery preserves this attribution. This additive
contract uses the fresh baseline only; it does not migrate the owner's archive.
Generated OCR page artifacts use a persisted operation/candidate-specific namespace,
stable when the same run resumes and distinct for a new run. A later render, even
at another DPI or before a failed read, must never overwrite images referenced by
current or historical revisions.

PDF and supported image formats get this page review in this phase. Other formats
retain their extracted-source view and managed-original link; never show a false
page image. This does not close all format-specific source-viewer work.

## Explicit page rescanning

Scan this page again preselects the current live page. Users may mark additional
pages while navigating; show the selected page numbers and a Clear selection
control. Changing document, leaving review or switching to history clears those
marks. No typed page-range parser or automatic quality-based selection is needed.

The start dialog confirms the exact page set and one reading method, including
Surya and available confirmed LLM profiles. Selecting a page explicitly always
reads it, even if its text layer passes the automatic selector. A method change
refreshes the preview. Only selected pages are rendered/read/sent externally;
other pages retain text, stable IDs and source-image/method provenance. Progress,
external allowance and costs describe selected pages, and unknown cost is never
zero. Confirmation remains the single external-sending approval.

Persist selection in the preview and admitted operation before dispatch. Bind it
to managed hash, base revision and settings. Normalize duplicates/order into a
sorted unique set; empty explicit selection and out-of-range pages are invalid,
never interpreted as automatic/all. Start still uses previewId + requestId; repeat
an uncertain start with the identical body. Restart resumes the frozen selection
and committed checkpoints, without re-running the automatic selector. Starting
a new run after cancellation/failure must work. Publish a complete new revision
atomically with unselected pages preserved; failure leaves current text intact.
History/Restore uses the existing protocol and stays idempotent.

## Planned contracts

Existing public APIs remain compatible when selection is omitted. Extend the OCR
preview request and response with the following selection union:

```ts
type RescanSelection =
  | { mode: 'automatic' }
  | { mode: 'explicit'; ordinals: number[] };
// previewRescan(collectionId, documentId, method, selection?)
// POST existing /ocr/preview: { method, selection? }
// response: existing RescanPreview + selection (normalized)
```

Selection belongs in durable preview/operation storage, not in a mutable browser
object or just a job payload. Use the fresh schema baseline, consistently with
the owner's existing decision against data migration/conversion. Do not reset
or convert the owner's database; report any required reset before runtime use.

Add collection-scoped, document-scoped page-review routes in a dedicated route
file, backed by a shared service. Proposed paths under
`/api/collections/{id}/documents/{documentId}`:

```text
GET /page-review?ordinal=0&revision=<optional>&offset=0&limit=<bounded>
GET /page-review/image?ordinal=0&revision=<exact-published-revision>&kind=ocr|original
```

The shared client contract is:

```ts
type PageReviewOptions = {
  ordinal: number; revision?: string; offset?: number; limit?: number;
};
type PageReviewResponse = {
  documentId: string; unitId: string | null; revisionId: string | null;
  ordinal: number; documentPages: number | null;
  previousOrdinal: number | null; nextOrdinal: number | null;
  text: string; offset: number; totalChars: number; truncated: boolean;
  qualityScore: number | null; readingMethod: string | null;
  imageKind: 'ocr' | 'original' | 'none';
};
// getDocumentPageReview(collectionId: string, documentId: string,
//   options: PageReviewOptions): Promise<PageReviewResponse>
```

`documentPages` is the verified physical count when available; null means that
the original is unavailable and no verified count is cached. Do not fabricate a
physical total from stored text pages. Metadata still returns intact revision
text, and stored OCR images remain readable without the original. When the
physical count is known, neighbor ordinals traverse every physical page; otherwise
they traverse available pages of the exact revision. The UI labels the total
unavailable and does not claim gaps are the complete original. Requests for an
unknown physical page with no recorded text and no original return not found.
Original-image failure is independent of text and stored OCR-image access.
A previously verified count may be reused by immutable managed hash. Verify the
original before generating a missing render; reuse verified generated artifacts
without rescanning the original on every image/text slice. Use indexed page and
snapshot lookups, not all page texts or hydrated operation histories per request.

A null unitId denotes missing extracted text. A null revisionId is allowed only
when the document has no published revision; original-image requests for that
case omit revision and validate the immutable recorded managed hash in the service. Otherwise image
requests must name the exact revision returned by metadata.
A document without a published revision may be inspected from its managed copy,
but must not pretend it has a published text or enable explicit rescan until the
existing service supports admission for it. Image requests resolve opaque IDs,
never client-supplied filesystem paths. Enforce revision/document/collection
ownership, published-history visibility, artifact confinement, rendering limits
and existing loopback/authentication rules. Serving originals/generated images
sends nothing externally, requires no GPU and never changes source bytes.

## Gates and non-goals

Normal tests use generated redistributable PDFs, fake OCR/providers and deterministic
embeddings. Every implementation ticket records red evidence, focused green tests
and applicable accumulated checks. Browser acceptance proves layout, page pairing,
selection and recovery within that setup. It cannot establish OCR quality or real
CoreML behavior. Real Surya/LLM and complete GPU workflow, native picker and human
keyboard/screen-reader passes remain explicit manual gates.

No application code, database changes, installs, commits, pushes or real provider
requests are authorized by this documentation task. No automatic reviewer, mid-run
approval, source editing, new OCR engine, mandatory quality threshold, persistent
UI preferences or unrelated answer/retrieval changes.
