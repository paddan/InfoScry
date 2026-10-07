# Collections management

Status: product scope agreed in the design interview and the 12-ticket breakdown
approved on 2026-09-27. All twelve tickets are implemented; the verified evidence
and the still-open gates (native picker, human keyboard pass, real OCR/CoreML
progress inside the Collections workflow, Ask and source-viewer acceptance) are in
the [ticket status and dependency index](../tickets/collections-management/STATUS.md).
This document itself claims no acceptance.

## Intent and agreed scope

The user needs to inspect and maintain the documents in each collection and to
understand their import progress, including OCR. Admin gains a Collections tab
that replaces the Import tab. Collection management does not move into the
workspace sidebar. The LLM profiles tab stays available.

Agreed capabilities:

- Start new archives without an automatically created Default collection.
- Create a collection within the import flow and explicitly select a collection
  before submitting any import.
- List documents; search filenames, filter statuses, sort by filename or import
  date, and paginate results.
- Open document processing details and open available content in the reader.
- Add files or folders, optionally including subfolders; report duplicates.
- Retain import progress and per-file results across navigation and reload.
- Show document processing stages, page progress where applicable, direct-text
  versus OCR counts, warnings, and safe, actionable errors.
- Retry failed or cancelled documents individually or all eligible documents in
  a collection, reusing compatible completed work.
- Delete one or multiple documents after confirmation, including during import.
- Delete collections after exact-name confirmation, including during import.
- Rename collections and change their OCR languages for future work and retries.
- Remove an empty legacy Default automatically; preserve a populated Default
  until the user explicitly renames or deletes it.

## Current implementation and gaps

The implementation was inspected on 2026-09-27:

- [Routes.kt](../../src/main/kotlin/infoscry/server/Routes.kt) already lists,
  creates, renames, changes OCR languages, and deletes collections. Deletion
  requires `confirmName`.
- [CollectionService.kt](../../src/main/kotlin/infoscry/collection/CollectionService.kt)
  and [DeletionStore.kt](../../src/main/kotlin/infoscry/storage/DeletionStore.kt)
  provide exclusive maintenance and durable collection deletion phases.
- [DocumentRoutes.kt](../../src/main/kotlin/infoscry/server/DocumentRoutes.kt)
  returns a bounded document page and a total. It lacks listing filters,
  sort choices, processing details, and document mutation routes.
- [DocumentStore.kt](../../src/main/kotlin/infoscry/storage/DocumentStore.kt)
  has an internal row deletion, not a complete user-facing removal service.
- [ImportPanel.svelte](../../web/src/lib/ImportPanel.svelte) creates collections,
  imports selected paths, and shows one active import. The new management view
  must restore durable job state rather than relying on that component's memory.
- [ImportJobHandler.kt](../../src/main/kotlin/infoscry/jobs/ImportJobHandler.kt)
  resumes an attached document through import items and extraction fingerprints.
  A new ordinary import can classify existing bytes as a duplicate; Retry must
  therefore be a distinct operation that targets existing document IDs.
- [ContentStore.kt](../../src/main/kotlin/infoscry/storage/ContentStore.kt)
  stores checkpoints and finished extraction summaries. A known page total
  during extraction needs an explicit durable progress contract.
- [001_baseline.sql](../../src/main/resources/db/migration/001_baseline.sql) seeds
  no collection; a new archive is empty.
- The same bytes in two collections are separate managed documents. Deduplication
  is scoped to `(collection_id, sha256)`.

Existing uncommitted PDF-extraction and build changes are outside this task.
Implementation must preserve them and must not silently replace pinned
dependencies. Any overlapping extraction edits need a diff review against the
starting working tree.

## Product behavior

### Collections tab

The tab lists active collections with their names and document counts. Selecting
a collection reveals its settings, document list, and durable imports. Selection
for management is explicit and does not unexpectedly change an active Ask or
Investigate conversation. Changes to names and deletion refresh the workspace's
collection selector. Deleting the workspace's selected collection clears that
selection and collection-bound results rather than showing stale content.

An empty archive shows `No collections yet` and `Create collection`. An empty
collection shows `No documents yet` and `Add documents`. Creation uses existing
name validation and case-insensitive uniqueness. Successful creation selects
the created collection in Admin.

Use the existing restrained dark layout, English product copy, normal table and
form controls, accessible labels, visible loading/error states, and keyboard
focus management for confirmation dialogs.

### Documents

Rows show filename, media type, size, import date, status, and compact progress.
Default ordering is newest import first. Supported sort values are `newest`,
`oldest`, `name-asc`, and `name-desc`, with ID as a deterministic tie-breaker.
Filename search is a literal case-insensitive contains match; it must not search
or expose the external source path. Status filtering uses the domain statuses.
Filtering and sorting happen before pagination on the server; the matching total
uses identical criteria. The UI uses 50 rows per page; the existing API maximum
remains 200. Changing a filter resets the offset and selection.

Checkboxes select only explicitly displayed documents. A select-all checkbox
selects the current page and says so. There is no implicit selection of hidden
pages. Bulk deletion uses the IDs shown in the confirmation. The confirmation
names the collection and number of documents; a single deletion also shows the
filename. Controls disable duplicate submission while a mutation is pending.

Details show the current stage, extracted/failed unit counts, known total,
direct-text and OCR counts, warnings, safe errors, and linked import attempt.
`Open document` opens the existing source reader at the first available content
unit. A document without readable content shows why this action is unavailable;
deletion of an open document closes its viewer. Source links from saved answers
remain historical references and report unavailable evidence after removal.

### Adding documents and durable imports

`Add documents` uses the current native picker and manual-path fallback. Support
files, one folder, and an `Include subfolders` option. The collection is shown
and preselected, but the import cannot submit without an explicit valid
collection. The create-collection action is available within this flow.

Reuse the server-owned import queue; duplicate bytes in the same collection are
skipped and shown as `Duplicate`, including after a reload. Before a document
exists, queued files and failures remain visible as import items rather than
being fabricated as document rows. Failed copy/type-detection items with no
managed document show an actionable result and can be resubmitted through
`Add documents`; document Retry never substitutes a new document silently.

The collection's import history lists job state, stage, file completed/total,
and per-file outcomes. File counters are labelled as files, never OCR pages.
Polling or job SSE refreshes persisted server state only while the panel is
mounted and resumes from a fresh snapshot on reopening. An older delayed
response cannot overwrite a newly selected collection or newer filter results.

### Processing status and OCR

Preserve domain stages (`QUEUED`, `COPYING`, `EXTRACTING`, `OCR`, `CHUNKING`,
`EMBEDDING`, `INDEXING`, terminal statuses, and `NEEDS_TOOL`) and translate them
to readable labels. Page counts require actual extractor facts. A format with
sections, slides, sheets, or other units uses `units` with the correct label;
it must not pretend they are pages. Unknown totals remain null and display
processed counts without a denominator or percentage.

Persist the current extraction fingerprint, known total, completed unit counts,
failed counts, and extraction methods alongside the document's current attempt.
An extractor announces a total when known before completing extraction; unit
method is explicit (`DIRECT_TEXT` or `OCR`), not inferred solely from confidence.
Progress advances only after the unit and its checkpoint are committed. A
snapshot combines compatible checkpoints without double counting resumed units.
Finished summaries are available for older documents; unavailable historical
method/progress information is shown as unknown, not as zero.

An OCR phase may display `OCR · 12/40 pages processed`, where the denominator
is the document's pages and the wording explains that scope. Separate counters
show how many pages were read by OCR and by direct extraction. Do not label a
whole-document denominator as the total number of OCR-required pages unless
that count is actually known.

Errors use curated messages derived from codes; never expose raw exceptions,
document text, external paths, payloads, or provider authorization. Missing
tools and unavailable GPU/model resources give the existing local remedy.
Diagnostic, keyword-search, and source-view access remain available when GPU
initialization fails. This feature does not add a CPU embedding fallback.

### Retry

Retry targets existing managed document IDs in their collection and preserves
IDs and original bytes. Eligible statuses are `FAILED`, `CANCELLED`, and
`NEEDS_TOOL`. Cancellation must update the interrupted document's status
honestly; a cancelled job must not leave a document looking permanently active.
`Retry all` means all eligible documents in this collection, across pages.
Completed documents are not included and warnings alone do not trigger Retry.

The server probes required tools and snapshots current collection OCR settings
for the new attempt. Missing prerequisites produce a safe error before queueing
work. A document already attached to queued/running work cannot be queued twice;
eligibility and admission are checked under the shared mutation boundary in a
transaction. Retry resumes from the managed copy even if the external original
was moved or deleted. The handler must not route it through duplicate detection.

Reuse successfully committed units only when their extraction fingerprint and
artifacts are compatible. Explicit Retry revisits failed units rather than
skipping failed checkpoints as an ordinary crash-resume might do. Changed OCR
languages can invalidate reuse of affected extraction work; the UI states that
such a retry may need to repeat extraction. Re-embedding must not invalidate
compatible extraction checkpoints. Completed documents are never automatically
reprocessed by a settings update.

### Safe deletion

Collection deletion retains its existing durable recovery phases and exact-name
confirmation. Document deletion needs its own durable operation spanning SQLite,
managed files, and Lucene; a row delete alone is insufficient. Persist the target
IDs and lifecycle/admission guard before destructive steps. Then drain active
mutation stages, park document directories, remove document rows and related
data, remove and commit index entries, and purge the parked directories. Each
phase is idempotent and resumes on startup before new jobs are admitted.

Only cancel work that can process the targeted documents. An import job may hold
other documents: deleting one must neither delete those documents nor cause the
target to be copied again from an old source item. Persist an item disposition
that the handler obeys across cancellation/resume. The deleting guard must be
checked at copy/attach, extraction commit, status writes, and index publication.
Record file-only pending targets within collection deletion as cancelled work.

Use the shared mutation admission and exclusive maintenance. Pending maintenance
must wait for at most an in-flight bounded stage, not a whole import. Failure or
request disconnection after deletion admission leaves a durable operation to
finish, not an ambiguous half-delete. Unsafe recovery blocks mutations and
preserves parked files for diagnosis, as current collection recovery does.

The initiating UI displays `Deleting…` until the operation is `DONE`; reopened
Admin shows unfinished operations. The server exposes read-only deletion status
and collection/document management excludes deleting targets. A failure displays
a safe actionable state and is not reported as success. External originals and
copies in other collections are never modified. There is no undo or recycle bin.

### Default collection

The baseline schema seeds no `default` collection, so a new archive exposes no
Default. No archive is migrated: the application is not deployed, so there is no
legacy `default` row to retire or preserve.

Preserve populated, renamed, or otherwise used legacy collections. For the
populated legacy Default, Admin explains the change and offers rename or explicit
collection deletion. Do not infer automatic deletion from a collection's name
alone or remove a newly user-created collection named Default. CLI documentation
creates `Notes` before its example imports and uses that name consistently.

## Architecture and API contracts

Keep Kotlin/JVM 25, one process and one Gradle backend module, SQLite authority,
Lucene rebuildability, and TypeScript/SvelteKit static frontend. Keep HTTP routes
thin over services, bind only to `127.0.0.1`, and preserve bearer/CSRF checks.

Extend rather than replace the existing collection and import APIs:

| Boundary | Contract |
|---|---|
| `GET /api/collections/{id}/documents` | Existing envelope; add `q`, `status`, `sort` criteria before paging and optional safe progress summary per row. |
| `GET /api/collections/{id}/documents/{documentId}` | Collection-scoped metadata, progress, warnings/error codes with curated messages, retry eligibility, linked attempt, and first reader source ID if available. |
| `POST /api/collections/{id}/documents/delete` | Explicit nonempty `documentIds`, `confirmed: true`; validate every ID belongs to the active collection before admitting deletion; return `202` and operation ID. Maximum 200 selected documents per call. |
| `POST /api/collections/{id}/documents/retry` | Either nonempty explicit `documentIds` (maximum 200) or `allEligible: true`, never both; return accepted job IDs and rejected IDs with safe eligibility reasons. |
| `GET /api/collections/{id}/imports` | Paginated durable import summaries and existing per-job item links, default 50 and maximum 200. |
| `GET /api/deletions/{operationId}` | Safe operation kind, target IDs, phase, terminal flag, and error code. No managed/trash paths. Must work after the collection row is gone. |
| Existing collection `DELETE` | Preserve exact `confirmName`; adopt durable asynchronous admission and return operation ID while retaining collection ID and phase in its response. Update route tests and consumers together. |
| Existing collection `PATCH` and OCR `PATCH` | Keep validation and semantics; update settings only for future queued attempts. |

All new mutations use existing credentials. Unknown or cross-collection IDs
produce not-found without leaking another collection's metadata. Invalid query
values and ambiguous Retry requests return typed 400 errors. A bulk deletion
with any invalid target rejects the whole request before side effects. Repeated
calls for already-admitted deletion return the same unfinished operation rather
than starting conflicting work. Exclusive maintenance conflicts retain the
existing typed refusal and retry behavior.

Proposed responsibilities:

- A document management service coordinates scoped details, deletion and Retry.
- Stores own filtered paging, progress, operation persistence, and retry admission.
- Import handlers/extractors publish honest durable progress and obey deletion
  guards; managed library retains ownership of original copies.
- AppContext wires services and recovers both operation kinds before workers run.
- A Collections panel composes settings, document table/details, and reused import
  controls; `api.ts` owns typed calls and credential handling.
- The page owns workspace collection selection and the existing source viewer.

## Implementation slices and acceptance

1. **Empty archive.** Baseline schema without a seeded Default, explicit
   collection selection, updated CLI examples. Test a new archive and reopen.
2. **Durable documents and processing reads.** Filtered paging/details, job
   history, extractor progress facts and safe errors. Test matching totals,
   literal wildcard characters, stable paging ties, unknown totals, mixed OCR,
   restart/resume, legacy unknown metadata, and cross-collection/privacy guards.
3. **Document deletion and Retry.** Services, durable operation phases, cancellation
   guards, compatible resume, and scoped API mutations. Test crash after every
   filesystem/DB/index boundary, failed recovery, retry after moved original,
   changed language fingerprint, failed-unit revisiting, repeated admission, and
   deletion during copy/OCR/indexing within multi-file imports.
4. **Admin Collections UI.** Replace Import tab and wire selection, settings,
   table/details, import history, deletion, Retry, and reader actions. Test
   navigation/reload, stale responses, bulk selection scope, confirmations,
   interrupted operations, empty states, errors, keyboard use, and workspace
   refresh after rename/removal.
5. **Integrated acceptance and documentation.** Browser coverage against a local
   fake provider/pipeline with redistributable fixtures and a temporary archive;
   real native picker, Tesseract OCR progress, and CoreML verification where needed
   on the local Mac. Update README and task status with verified results.

For implementation, run meaningful failing tests first, focused passing suites,
then the accumulated `JAVA_HOME="$(asdf where java)" ./gradlew check`, frontend
`npm run check`, and relevant `externalTest` browser coverage. Run real local GPU
acceptance before claiming GPU-dependent functionality works. Mock counts or a
static screenshot do not satisfy OCR/GPU acceptance. Report infrastructure or
test-shutdown failures separately from application failures.

Success means the user can maintain documents and collections entirely through
Admin Collections, observe durable and truthful processing state, and recover
from interruptions without resurrecting deleted documents or repeating
compatible committed OCR. Ask and source-viewer finish-line gates remain open
unless independently verified; this feature does not close them by implication.

## Non-goals

No moving/copying documents between collections, soft deletion, automatic
reprocessing of successful documents, changes to LLM behavior, external storage,
new embedding platforms, CI, packaging, dependency upgrades, or release work.
