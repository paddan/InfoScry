# OCR workflow reference

Current reading-method, start, recovery and page-selection contracts.
See [implementation status](implementation-status.md) for recorded verification
and [remaining product choices](tickets/follow-ups/STATUS.md). Patrik confirmed the
remaining OCR/PDF checks complete on 2026-10-11.

## Reading workflow

1. The **document** is the unit of work. Each document shows one status and one
   next action. During import, file progress is accompanied by a separate count
   and progress bar for saved pages of the current document (including direct-text
   pages). Unknown totals use an indeterminate bar. Progress comes from durable
   results so reloads and resumed attempts retain the saved count.
2. Import of a file or a directory, and **Scan again**, share one **start
   dialog**. The reading method is chosen each time. The collection's default
   method pre-fills the dialog; the dialog is always shown and must be
   confirmed. Nothing starts silently on a default.
3. **Every page that can need reading is read** with the chosen method. There
   is no "fill missing" versus "check and improve" mode, and no page limit to
   enter. A PDF page is read when it has no usable text layer, or when an
   embedded image covers at least 25% of the page and the page's own text layer
   scores below 75 on the text-quality heuristic; every other page keeps its own
   text and is neither rendered for reading nor sent anywhere. The same
   rule applies to every method and is used for the page count, the cost and
   the approval shown in the dialog (amended 2026-10-09; see "Page selection").
   **No OCR** is a reading method that reads no page: every page keeps the
   document's own text, a page without usable text is kept without text and the
   document is **Done** with a warning, and Scan again with No OCR replaces each
   page with the managed copy's own text layer while a page whose layer is
   unusable keeps its published text (amended 2026-10-21; see
   [the design](specs/2026-10-21-no-ocr-reading-method-design.md)).
4. External sending is approved **once, in the dialog**, for all pages shown.
5. There is **no reviewer** and no review step. A finished run replaces the
   document's text. The previous version stays in history and can be restored.
6. OCR language belongs to the collection.
7. **A stopped run never blocks the next one.** If an import or rescan is
   cancelled, fails, or is cut off in any other way (process killed, provider
   gone, machine restarted), the person can always start again. No message about
   an operation that "waits for approval", "has pages to review" or "cannot
   start yet" may be the answer to starting again. See "Always restartable".
8. The application is single-user. Protection for other readers of a document
   while it is being rescanned is not required.
9. For an external import with an unknown page count, approval covers every page
   in the exact unchanged files shown in the dialog. The confirmed source
   manifest (path, size and SHA-256) is the authority; no exact page allowance or
   exact cost is claimed. A retry may retain that scope only when it resumes the
   original reading against the same immutable managed document and unchanged
   method snapshot.

## Reading methods

A **reading method** is one entry in one list, never a pair of fields:

| Method | Where pages go | Needs |
|---|---|---|
| Tesseract | this machine | installed tool |
| Surya | this machine | installed model |
| LLM: *profile name* | the profile's endpoint (local or external) | an enabled OCR profile |

Engine and profile are stored together as one value, so a method cannot name an
engine without its profile or the reverse. A method that cannot run now (tool
missing, profile disabled, key variable unset, image check not passed) is
listed with the reason and cannot be chosen.

## Profile metadata and local credentials (2026-10-10)

Unknown catalog metadata may be omitted on the wire. Selecting such a model
keeps existing form values, including token limits and prices; missing values
are normalized to unknown rather than converted to empty inputs or `1`.
Creating or updating an LLM profile requires positive integer token limits and
an output limit strictly below the context window, leaving room for input.
Existing stored profiles remain readable so an invalid setting can be repaired.

Local loopback endpoints without a configured API-key variable are available
without credentials once their image check passes. A configured variable must
have a nonblank value. External endpoints always require a configured,
nonblank key variable. The method list follows the image client's dispatch
rules and gives an actionable reason when credentials are missing.

## Start dialog

Opened by *Import* (file or directory) and by *Scan again* on a document.

- **Method list** with the collection's default pre-selected.
- **Summary** for the selection: number of pages that will be read, destination
  (`this machine` or the endpoint host for an external profile), and, for an
  external profile with known prices, an estimated cost with its basis. An
  unknown price says so.
- **Confirm button** names the consequence: `Read 48 pages with Surya` or
  `Send 48 pages to <host> with <profile>`. Pressing it is the approval of
  exactly those pages and that method.
- For an import of a directory the page total is computed before the dialog
  opens (see "Preview"). A file whose page count cannot be determined is listed
  as such; the total then reads `at least N` and the approval covers the stated
  files only. When the method is external, an unknown count means the exact
  unchanged files shown are approved for all their pages; the dialog says that
  no exact cost estimate is available.

## Preview

`POST /api/imports/preview` and the existing rescan preview return, for a
proposed method: the files or document, page counts, destination, cost estimate,
and a snapshot hash binding them. Starting the run submits that hash. A run
whose inputs changed since the preview (file added or removed, method changed,
profile revision changed) is refused with a message to preview again; it does
not read a different set of pages than the person confirmed.

## Run and document status

States shown per document: **Imported**, **Reading n of N**, **Done**,
**Failed**, **Cancelled**. There is no *waiting for approval*, *needs review* or
*complete with pending pages* state.

- Pages are committed and checkpointed one at a time as today. A failure (for
  example the provider cannot be reached) ends the run as **Failed** with a
  plain cause label; **Retry** resumes from the committed pages and never pays
  for a page twice.
- One page does not fail a document (amended 2026-10-21). On import, a page
  whose reading fails is left out, the other pages are published, and the
  document is **Done** with a warning; only a reading with no page at all fails.
  A page the engine reads and finds no text on (a photograph, a drawing) is not
  a failure: it keeps its own text layer, or no text, and the document is
  **Done** with a warning. A reading that would replace published text still
  fails on a missing page, so that page's current text is kept.
- When every page is read, the new text replaces the document's text and is
  indexed. This is one publication, using the existing publication machinery,
  without a candidate that waits for a person.
- Cancel stops the run; nothing is published; the existing text is unchanged.
- The previous version is kept in history. **Restore** is unchanged.
- A run never sends more pages than were confirmed. If it would, it ends as
  Failed with that reason rather than pausing.

## LLM request deadlines (2026-10-10)

A page-reading request may take up to **10 minutes**, covering the send, response
headers, body and answer validation. This is an operational default allowing
slow non-streamed image generation; it is not a measured provider latency SLA.
The synthetic image-capability probe keeps its separate **60-second** deadline.
CIO's independent request timeout is disabled so its 15-second default cannot
cut either operation short. Cancellation still interrupts an in-flight request.

A deadline ends the page with `OCR_TIMEOUT`. It is not retried automatically:
the provider may already have generated and billed an answer. Previously
committed pages remain resumable; existing published text stays unchanged on
failure. HTTP request timeouts are distinguished from other transport failures.

## Structured page reading (2026-10-18)

An OCR page keeps the layout its engine recognised (headings, lists, tables,
formulas, captions, footnotes, page headers and footers) as a structure stored
beside its text. The [design](specs/2026-10-18-structured-page-reading-design.md)
is implemented; the technical contracts are in
[technical reference](technical-reference.md#page-structure).

- **Which readings have structure.** Surya and the LLM reader (Markdown,
  transcription prompt version 2) carry structure. Tesseract gives paragraphs
  grouped by its own blocks, and recognises no tables or headings. Embedded-image
  units of e-book and Office documents get no structure in this project.
- **Degraded structure.** Only a block the engine labelled `Table` falls back to
  a paragraph when its markup does not parse as a table; `Form` and
  `TableOfContents` blocks fall back to a paragraph without the flag. A page
  whose structure could not be kept is marked degraded (`STRUCTURE_DEGRADED`),
  and page review says so. Its text and citations are unaffected.
- **Existing readings are not upgraded.** A page gets structure on its next
  import or rescan with an OCR engine. A rescan whose text is unchanged adopts
  the new reading's structure.
- **Reader.** The source viewer draws headings, lists, real tables with header
  cells, formulas as their LaTeX source, and page headers and footers muted.
- **Ask and Investigate.** A chunk's tables and headings reach the model as
  Markdown (pipe tables with their header row); citations still name the chunk.
- **Chunking.** Chunks split at block boundaries and never inside a table row. A
  table that spans chunks repeats its header row. Page headers and footers stay
  out of the search index and embeddings.
- **Selectable PDF.** The text layer follows reading order, and a table cell by
  cell, row by row. Cell rectangles are not estimated: exact cell geometry waits
  for Surya line detection, a later sub-project.

## Always restartable

The rule: **starting a run never depends on the state a previous run left
behind.**

- A new run for a document that has an unfinished or stopped run of its own
  (running, queued, failed, cancelled, interrupted) replaces that run. If a
  stopped run holds committed pages for the same method, the new run may reuse
  them; otherwise they are ignored. The person is not asked to cancel, resume,
  discard or approve first.
- A run that is genuinely still executing in this process is the only thing a
  new start waits for, and the dialog says so ("Reading 12 of 48; start over?")
  and offers to cancel it and start again in one step.
- On startup, runs left in a non-terminal state by a previous process are
  marked **Failed (interrupted)**. Nothing stays "running" or "waiting" across a
  restart.
- A cancelled or failed run publishes nothing and leaves the document's text
  exactly as it was. A finished import with some unread pages is not a failed
  run; see "Run and document status".
- Data written by the shipped flow is cleaned up once, by migration or on first
  start: jobs stored as complete with an `awaiting-approval` stage become
  Cancelled; operations that hold a candidate revision with pending pages have
  that candidate withdrawn. After that no stored state can produce a
  "waits for approval" or "pending review" refusal.
- Start, Retry and Cancel are idempotent: repeating one returns the current
  state instead of an error. Idempotence is the governing principle for every
  step of this redesign, not only these three: preview, start, page commit,
  publication, migration, startup recovery and cleanup must each be safe to run
  twice and must converge from any partial state a crash leaves. A start carries
  a client request id; the same id and body returns the run that exists, as the
  shipped rescan admission does.

## Collection settings

Kept: OCR language, default reading method (engine and profile as one value).
Removed: import mode, reviewer profile, external page allowance. The removed columns remain for database compatibility and are no longer read or written;
idempotent startup cleanup withdraws stale work. a stored engine/profile pair that disagrees is
resolved to the engine's local method.

## Single-user behavior

The application has one user:

- A rescan no longer refuses to start, or holds the document, because another
  reader or operation might see intermediate state. Two writers to the same
  document are still serialized by the shared mutation admission.
- Publication no longer waits for readers to drain from the previous revision.

Unchanged, because they protect data rather than other users and `AGENTS.md`
requires them: per-page durable checkpoints and resume, immutable originals and
managed copies, stable content-unit ids, atomic index publication that preserves
all collections, recovery of unfinished publications and collection deletion,
and keeping keys, questions and document text out of logs.

## Page selection (2026-10-09)

Automatic selection is used for import and ordinary rescans. An explicit
page set selected in page review overrides the heuristic for manual rescans.
No OCR selects no page.

Reading every page sent text-only pages to the model for nothing. A PDF page is
selected for reading when either holds:

- it has no usable text layer (`PdfPageCandidate.needsOcr`), or
- one embedded image, placed on the page, covers at least 25% of the page area
  (`PdfPageSelector.MIN_IMAGE_COVERAGE`) **and** the page's own text layer scores
  below 75 (`PdfPageSelector.MIN_TEXT_LAYER_QUALITY`, the "good" band of
  `OcrQualityScorer`). A searchable scan whose hidden text layer is clean, and a
  digital page with a photo, therefore keep their text; a scan with a poor
  hidden layer is read again.

Image placement is measured from the page's content stream, including images
inside form XObjects, as the area of the unit square under the current
transformation matrix. Inline images are not measured.

There is deliberately **no** test of whether an image contains text. A local
OCR pre-check was rejected: it fails on handwriting, which is where an image
model helps most, and a skipped page is silent. The rule only skips pages that
cannot be scans of text, so a wrong guess costs a page read in vain rather than
a page lost.

- The preview counts the selected pages and says how many the document has.
- Import: an unselected page becomes a direct-text unit, as before.
- Rescan: an unselected page is staged from the published text without a
  reading; a page with no published text is read anyway.
- Pictures (png, jpeg, tiff) are one page and are always read.
- The rule version is part of the extraction fingerprint, so pages committed
  under all-pages reading are not reused as if they followed this rule.
