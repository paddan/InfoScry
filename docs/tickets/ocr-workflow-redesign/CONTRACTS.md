# OCR workflow redesign: shared contracts

Read the [spec](../../specs/2026-10-08-ocr-workflow-redesign.md), the
[plan](../../plans/2026-10-08-ocr-workflow-redesign.md), [STATUS.md](STATUS.md) and
`AGENTS.md`. These are the interfaces every ticket in this directory builds against.
A ticket must match them exactly, so that tickets can be built in parallel and against
fakes. Where a ticket needs to change a contract, stop and report; do not edit it
locally. Items marked **(chosen)** were not fixed by the plan; this document fixes
them. Items marked **(open)** are owner decisions with the default stated.

Paths are relative to the repository root `/Users/patrik/projects/infoscry`.

## 1. Kotlin: `ReadingMethod`

File `src/main/kotlin/infoscry/ocr/ReadingMethod.kt`. **Shared file rule:** the first
ticket among 03, 04, 05, 06, 08, 11 to land creates this file exactly as below; the
others reuse it. Only ticket 03 owns its tests (`ReadingMethodTest`).

```kotlin
@Serializable(with = ReadingMethodSerializer::class)   // serialized as its id string
sealed interface ReadingMethod {
    val id: String                                      // "tesseract" | "surya" | "llm:<profileId>"
    data object Tesseract : ReadingMethod { override val id = "tesseract" }
    data object Surya : ReadingMethod { override val id = "surya" }
    data class Llm(val profileId: String) : ReadingMethod { override val id get() = "llm:$profileId" }
    companion object {
        /** Throws IllegalArgumentException for anything else (HTTP adapters map it to 400). */
        fun parse(id: String): ReadingMethod
    }
}
```

`parse(m.id) == m` for all three kinds. `llm:` with an empty profile id, an unknown
word and the empty string are invalid. A method is one value; engine and profile never
travel as a pair.

```kotlin
data class MethodAvailability(
    val method: ReadingMethod, val label: String, val destination: String,
    val available: Boolean, val unavailableReason: String?, val external: Boolean)
```

`destination` is `this machine` for local methods, the endpoint host for an LLM
profile. `unavailableReason` is non-null exactly when `available` is false.
`external` is true for an LLM profile whose endpoint is not local.

`ReadingMethodCatalog` **(chosen)**, in `src/main/kotlin/infoscry/ocr/ReadingMethodCatalog.kt`,
created by ticket 03:
`fun availability(collectionId: CollectionId): List<MethodAvailability>` and
`fun require(collectionId: CollectionId, method: ReadingMethod): MethodAvailability`
(throws `ReadingMethodUnavailableException(reason: String)` when not available). Tickets 05
and 08 call `require`; until 03 lands they build against a fake implementing this
interface (`interface ReadingMethodCatalog` with the two functions; 03 provides the
production class).

## 2. `GET /api/collections/{id}/reading-methods`

```json
{ "methods": [
    { "method": "tesseract", "label": "Tesseract", "destination": "this machine",
      "available": true, "unavailableReason": null, "external": false },
    { "method": "llm:p1", "label": "LLM: My profile", "destination": "api.example.com",
      "available": false, "unavailableReason": "Key variable OPENAI_API_KEY is not set", "external": true } ],
  "default": "tesseract" }
```

`method` is the id string. `default` is the collection default method id, or null when
none is stored. Order: Tesseract, Surya, then LLM profiles by name. Unknown collection
404. Unavailable reasons cover: tool missing, model missing, profile disabled, key
variable unset, image check not passed.

## 3. Collection settings (ticket 04)

```kotlin
data class CollectionOcrSettings(val language: String, val defaultMethod: ReadingMethod)
```

The old `ocr_engine` / `ocr_transcription_profile_id` columns are derived from
`defaultMethod` (written together, read together). `ocr_import_mode`,
`ocr_review_profile_id`, `ocr_external_page_limit` are no longer read or written; the
columns stay in `001_baseline.sql` **(chosen: no migration script; see STATUS open decisions)**.
A stored engine/profile pair that disagrees loads as the engine's local method (engine
LLM with no usable profile loads as Tesseract; local engines ignore a profile).
PATCH body for the existing route: `{ "language": "eng", "defaultMethod": "surya" }`;
unknown method id is 400. Saving the same value twice is a no-op.

## 4. `POST /api/imports/preview`

Request:

```json
{ "collection": "<collectionId>", "paths": ["/abs/a.pdf"], "recursive": true,
  "include": [], "exclude": [], "method": "surya" }
```

Response 200:

```json
{ "files": [ { "path": "/abs/a.pdf", "pages": 12, "reason": null },
             { "path": "/abs/b.pdf", "pages": null, "reason": "page count could not be read" } ],
  "totalPages": 12, "atLeast": true, "destination": "this machine", "external": false,
  "estimatedCostUsd": null, "costBasis": "price unknown for this model",
  "previewHash": "<64 lowercase hex chars>" }
```

- `pages` is null when the count cannot be read; then `reason` is set and `atLeast` is
  true. `totalPages` sums the known counts. An image counts 1.
- Directory expansion, filters and unsupported-file skipping are the same function the
  import job uses (`enumerate` in `ImportJobHandler`, exposed for sharing).
- `estimatedCostUsd` is null with a `costBasis` string when no price is known; both are
  null for local methods.
- If any external file has an unknown page count, `estimatedCostUsd` is null and
  `costBasis` says the page count is unknown, even when the profile has prices. A
  known-page subtotal is not an estimate of the approved full file set.
- Errors: unknown collection 404; bad method id 400; method not available 409 with
  code `METHOD_UNAVAILABLE` **(chosen)** and the `unavailableReason` as message.
- `PageCounter` **(chosen name)**: `interface PageCounter { fun pageCount(path: Path): Int? }`
  in `src/main/kotlin/infoscry/extract/PageCounter.kt`, extracted from
  `RescanPageSource.pageCount`. Created by ticket 05; ticket 11 uses it.

**`previewHash` definition (chosen encoding).** Lowercase hex SHA-256 of the UTF-8 bytes of:

```
method=<method id>\n
profileRevision=<profile revision id, or "-" for local methods>\n
language=<collection language>\n
file\t<path>\t<size in bytes>\t<file sha256 hex>\n     (one line per file, sorted by path in UTF-8 byte order)
```

Files that are listed but unreadable (`pages = null`) are included like any other file.
`ImportPreviewService.hashOf(request): String` computes it without page counting; it
returns the same value as `preview(request).previewHash` for the same inputs. The hash
changes when a file's bytes (size or sha256), the method id, the profile revision, the
language, or the file set changes; it does not depend on `paths` ordering.

## 5. `POST /api/imports` (ticket 06)

`ImportRequest` gains `method: String`, `previewHash: String`, `requestId: String`
(all required for new clients), and `restartStopped: Boolean` (optional, defaults to
false). Behavior:

1. Parse `method` (400 on a bad id), require it available (409 `METHOD_UNAVAILABLE`).
2. Recompute `hashOf`; mismatch with `previewHash` is 409 with code `PREVIEW_STALE` and
   message `The files changed; review the summary again.` Nothing is queued.
3. Bind the job payload to the exact confirmed source manifest (path, byte size and
   SHA-256); the handler validates it before queuing or copying and checks the
   managed copy again before reading. For an external method with known page counts,
   set `externalPageLimit` to the previewed `totalPages`. If any count is unknown,
   set the numeric limit to `0` and snapshot `externalConfirmedSourceScope = true`.
   That flag grants all pages only for the unchanged manifest attached to this
   import; the dispatcher ignores it without that verified manifest. This is the
   owner's explicit approval of every page in the named unchanged files, not an
   unbounded approval of files added later.
4. Idempotence: same `requestId` and same body returns the job that exists (same
   response shape as a fresh start); same `requestId` with a different body is 409
   with code `REQUEST_ID_CONFLICT` **(chosen)**. A concurrent double submit creates one job.
   `restartStopped` is false for HTTP/browser callers, preserving strict replay. The
   CLI opts in with true: it returns an existing QUEUED, RUNNING or COMPLETE job, but
   when the mapped import job is FAILED or CANCELLED it atomically creates one new
   import job and updates the request mapping to that job. Concurrent/repeated submits
   converge on the replacement; a later submit after that replacement itself fails or
   is cancelled may start its next attempt. Interrupted RUNNING jobs are reset to
   QUEUED at process startup and therefore resume the existing job.

Storage **(open, default adopted):** table `start_requests(request_id TEXT PRIMARY KEY,
kind TEXT NOT NULL, body_hash TEXT NOT NULL, job_id TEXT NOT NULL, created_at TEXT NOT NULL)`
created with `CREATE TABLE IF NOT EXISTS` by `StartRequestStore` in
`src/main/kotlin/infoscry/storage/StartRequestStore.kt`, with
`fun claim(requestId, kind, bodyHash, create: () -> JobId): JobId` retains strict
replay; the import-only overload also accepts `restartStopped: Boolean` and performs
terminal replacement inside the same transaction. Created by the first of tickets 06
and 11 to land; the other reuses it.

## 6. Run failure codes

| Code | Where | Meaning |
|---|---|---|
| `PREVIEW_STALE` | HTTP 409 on start | Inputs differ from the confirmed preview. |
| `MORE_PAGES_THAN_CONFIRMED` | run failure code (ticket 09) | The run would send more pages than `externalPageLimit`; it ends FAILED and sends no extra page. |
| `INTERRUPTED` | operation/job failure code (ticket 01) | Left in a working state by a previous process. |
| `METHOD_UNAVAILABLE` | HTTP 409 (chosen) | The chosen method cannot run now. |
| `REQUEST_ID_CONFLICT` | HTTP 409 (chosen) | Same request id, different body. |

No start response may ever say a run "waits for approval", "has pages to review" or
"cannot start yet".

## 7. Rescan (ticket 08)

- Preview: `POST /api/collections/{id}/documents/{documentId}/ocr/preview` body
  `{ "method": "<id>" }` (`RescanPreviewRequest(method: String)`). Service:
  `RescanService.preview(collectionId, documentId, method: ReadingMethod)`.
- Response is the existing `RescanPreview` with: `externalPageUpperBound` = the page
  total, `approvalRequired` removed. No field-by-field overrides (`RescanOverrides`,
  `withOverrides`) remain.
- Admit: `POST .../ocr/rescan` body `{ "previewId": "...", "requestId": "..." }`
  (unchanged). The snapshot's `externalPageLimit` = the previewed page total (0 for
  local methods). Same request id and body returns the existing operation. A changed
  profile revision, managed hash or method between preview and admit raises the
  existing `StaleRescanPreviewException` (existing HTTP code unchanged).
- Method not available: refused with the `ReadingMethodCatalog.require` reason (409
  `METHOD_UNAVAILABLE`).

## 8. Retry (ticket 11)

`POST /api/collections/{id}/documents/retry`: the existing body plus optional
`method: String` (omitted = the failed run's method) and `requestId: String`.
Retrying a document with pages committed under the same fingerprint reads only the
remaining pages. Repeating the same `requestId` and body returns the same job. A
retry without a new method may preserve `externalConfirmedSourceScope` only from
the original snapshot and only while the immutable managed document still matches
its recorded SHA-256. An explicit new external method does not inherit that scope;
it needs a known page count for a numeric allowance.

## 9. Snapshot, READ_ALL and fingerprint (ticket 07)

`OcrImportMode` gains `READ_ALL`: every page is rendered and read with the snapshot's
method even when a text layer exists. New runs always use `READ_ALL`. The old values
(`FILL_MISSING`, `CHECK_AND_IMPROVE`) stay readable for stored snapshots. The snapshot
fingerprint includes the mode and the method, so checkpoints are never reused across
methods.

## 10. Stored values that the cleanup (ticket 01) and the removals (09, 10, 17) share

- Job stage string `awaiting-approval` (constant `JobStore.AWAITING_APPROVAL_STAGE`,
  state `COMPLETE`).
- Operation stage `AWAITING_APPROVAL` and operation `pendingReviewCount > 0` with stage `COMPLETE`.
- Ticket 01 uses the literal string `"awaiting-approval"` (a private constant inside
  `StaleOcrStateCleanup`) rather than the `JobStore` constant, so that ticket 09 can
  delete the constant without touching ticket 01's code. After 09, old operation stage
  values must still be readable as plain strings or mapped to FAILED/CANCELLED on read.

## 11. TypeScript (web)

All in `web/src/lib/api.ts` unless noted (ticket 12 owns them; tickets 13-15 mock them).

```ts
export type ReadingMethodOption = {
  method: string;                 // "tesseract" | "surya" | "llm:<profileId>"
  label: string; destination: string;
  available: boolean; unavailableReason: string | null; external: boolean;
};
export type ReadingMethodList = { methods: ReadingMethodOption[]; default: string | null };

export type ImportPreviewRequest = {
  collection: string; paths: string[]; recursive: boolean;
  include: string[]; exclude: string[]; method: string;
};
export type ImportPreview = {
  files: { path: string; pages: number | null; reason: string | null }[];
  totalPages: number; atLeast: boolean; destination: string; external: boolean;
  estimatedCostUsd: number | null; costBasis: string | null; previewHash: string;
};

export function listReadingMethods(collectionId: string): Promise<ReadingMethodList>;
// GET /api/collections/{id}/reading-methods
export function previewImport(req: ImportPreviewRequest): Promise<ImportPreview>;
// POST /api/imports/preview, body = req
export function startImport(req: ImportPreviewRequest & { previewHash: string; requestId: string }): Promise<ImportStarted>;
// POST /api/imports, body = req
export function previewRescan(collectionId: string, documentId: string, method: string): Promise<RescanPreview>;
// POST /api/collections/{c}/documents/{d}/ocr/preview, body {method}
export function startRescan(collectionId: string, documentId: string,
                            body: { previewId: string; requestId: string }): Promise<RescanStarted>;
// POST /api/collections/{c}/documents/{d}/ocr/rescan, body
```

`ImportStarted`, `RescanStarted` and `RescanPreview` are the types `api.ts` already uses
for today's import start, rescan admission and rescan preview responses (reuse the
existing names; ticket 12 adjusts `RescanPreview` to the section 7 shape). A 409 whose
body has code `PREVIEW_STALE` is surfaced as `ApiError` with `code === 'PREVIEW_STALE'`
(use the existing error class; the same mechanism existing codes use).
Removed from `api.ts` by ticket 12: external approval (`approve-external`), review
decision/list/publish and discard clients.

`web/src/lib/documentStatus.ts`:

```ts
export type DocumentStatus = {
  kind: 'imported' | 'reading' | 'done' | 'failed' | 'cancelled';
  label: string;                                   // e.g. "Reading 12 of 48", "Failed: provider could not be reached"
  progress?: { done: number; total: number };      // present when kind === 'reading'
  nextAction: 'scan' | 'retry' | 'cancel' | null;
};
export function documentStatus(doc: DocumentSummary, job?: JobSummary): DocumentStatus;
```

`DocumentSummary` and `JobSummary` are the existing document-row and job types in
`api.ts` (use the existing names). Mapping: no run and no published text -> `imported`,
action `scan`; run in progress -> `reading`, action `cancel`; published text and no
failing run -> `done`, action `scan`; failed run -> `failed` with a cause label, action
`retry`; cancelled run -> `cancelled`, action `scan`. There is never a status for
waiting for approval or for review.

`StartReadingDialog.svelte` props (ticket 13):

```ts
{ collectionId: string;
  request: { kind: 'import'; paths: string[]; recursive: boolean; include: string[]; exclude: string[] }
         | { kind: 'rescan'; documentId: string };
  onstarted: (run: ImportStarted | RescanStarted) => void;
  oncancel: () => void; }
```

Messages: stale preview `The files changed; review the summary again.`; confirm labels
`Read 48 pages with Surya`, `Send 48 pages to <host> with <profile>`, and with
`atLeast`: `Read at least 48 pages with Surya` / `Send at least 48 pages to <host> with <profile>`.
In-progress run: `Reading 12 of 48; start over?` with a button `Cancel and start over`.

## 12. Idempotence rules (every ticket)

Run each new route, job step, cleanup and recovery step twice, and interrupt it after
each durable step then run it again; the end state and response equal one uninterrupted
run. Starting never returns "waits for approval", "pending review" or "cannot start yet".
Repeating Start/Retry/Cancel returns the current state, not an error.

## 13. Test conventions

Fake providers, temp data dirs, redistributable fixtures only. Backend:
`./gradlew test -PskipFrontend --tests '<class>'`. Frontend: `(cd web && npm test -- --run && npm run check)`.
Run the failing tests first and see them fail for the stated reason before implementing.
Run `./gradlew check` before reporting done. Workers do not commit; the primary agent
reviews and commits the complete change under the explicitly invoked implementation
workflow. Preserve unrelated working-tree changes; implementation began on a clean checkout.
