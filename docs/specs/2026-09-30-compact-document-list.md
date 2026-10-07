# Compact document listing in Admin Collections

Status: design agreed and documentation authorized on 2026-09-30.
**Planned, not implemented.** See the [four-ticket plan](../tickets/compact-document-list/STATUS.md).
This change can ship independently of the [OCR plan](2026-09-30-ocr-rescanning.md).

## Problem and existing behavior

Collections is primarily for overview, settings, import and status. The current
always-expanded, 50-row document table pushes details and import history far down
the page. `web/src/lib/CollectionsPanel.svelte` already has server-backed filename
search, status filtering, four sort orders and pagination; its table wrapper only
constrains horizontal overflow. Details currently appear below the whole table.
`web/src/routes/+page.svelte` unmounts CollectionsPanel when leaving Admin, so
component-local state alone cannot preserve a session across that navigation.

The existing Collections spec remains the
contract for import, Retry, deletion, selection and source opening. This spec
changes presentation, search triggering and view-state lifetime only. Search
remains literal case-insensitive filename matching, not document-content search.

## Agreed behavior

### Collapsed overview

Show a compact Documents row, initially collapsed. Display the total count and
nonzero attention counts for FAILED, NEEDS_TOOL, CANCELLED and
COMPLETE_WITH_WARNINGS, labelled `failed`, `needs tool`, `cancelled`, `with warnings`.
These exact status mappings are implementation defaults; do not silently group
statuses into a filter the existing API cannot express. Pending OCR review is
not implemented and must not appear as a zero count or active control yet.

The disclosure button toggles visibility without changing filters. The total
count is a separate action that opens all documents, clearing filename and status
filters and resetting to page one while preserving the chosen sort. Each status
count opens that exact status, clears the filename filter and resets page one.
This ensures clicking `2 failed` actually shows those two documents. Zero-count
statuses are omitted; an empty collection still shows `Documents · 0 total`.
Use separate buttons, not interactive elements nested in a disclosure button.

All counts describe the entire selected collection and come from an aggregate
read, never the current 50 rows or filtered result total. Count loading/failure
is shown as unavailable/loading, never fabricated zero. Row and summary reads
can observe different instants during imports; refresh both after mutations and
use separate latest-request guards. Preserve safe curated errors and Retry of a
failed summary read. Collapsing does not hide collection settings, Add documents,
import progress or import history. List-scoped controls, Retry all explanation,
bulk actions and document details are inside the collapsed section.

### Expanded list

Keep filename search, status and sorting above a bounded table scroll region.
Keep pagination below the scroll region; preserve 50 rows per page and server-side
filtering/sorting before pagination. Use `max-height: min(60vh, 36rem)` as a starting
CSS value, not a fixed-height blank panel. Include inline details inside this same
scroll area. Preserve horizontal scrolling on narrow screens without causing page
width overflow. Use sticky table headers where they remain readable and do not
cover focused controls. The controls remain outside the table's vertical scroll.

One Details control expands a full-width row immediately after its document;
opening another closes the first. Preserve loading/errors, status, Retry/Delete
and Open document behavior. Open document still uses the parent-owned source
reader. Keep the expanded detail visible by minimally scrolling within the table,
not jumping the whole page. Closing details restores focus to their trigger when
needed; collapsing the section puts focus on its disclosure button if focus was
inside. Do not retain invisible selected rows or hidden destructive actions.

### Live filename search

Use a 300 ms trailing debounce. Enter flushes it immediately without a Search
button. During IME composition, defer requests until composition ends. Empty input
clears the filter with the same debounce. Status and sort changes apply immediately
using the current trimmed filename input and cancel pending timers. Effective
query/filter/sort changes reset offset, selected rows and details. Pagination
preserves current applied criteria and clears selection/details as today.

Immediately invalidate old requests when input intent changes, before debounce
expires, so a response for an old query cannot replace the new intent. On collapse,
collection switch or unmount, cancel timers and invalidate in-flight responses.
Persist the latest input for the originating collection even if its timer did not
fire. Reopening applies that text once, with offset zero. No late work may target
a newly selected collection. Keep actual request errors visible and recoverable.

### Session state

Keep a parent-owned in-memory map keyed by managed collection ID:

```ts
type DocumentListViewState = {
  expanded: boolean;
  filename: string;
  status: string; // empty or an existing DocumentStatus value
  sort: 'newest' | 'oldest' | 'name-asc' | 'name-desc';
};
```

Persist expansion, filename, status and sort when leaving/re-entering Admin or
switching collections. First visit: collapsed, empty filename/status, newest.
Browser reload resets the map; do not use localStorage/sessionStorage or a backend
preference. Do not persist pages, scroll position, checked IDs, open details,
loaded data or errors. On return/open, fetch page one fresh. On collapse, clear
selection/details but retain query/filter/sort. Remove a deleted collection's
entry; rename retains it. Workspace collection choice is independent.

When collapsed, do not fetch/poll document pages or details. Keep the selected
collection summary current through the existing bounded refresh lifecycle while
Admin Collections is visible, including after import/Retry/deletion updates.
Pause hidden/unmounted polling; refresh on return. Reuse the existing timer/event
mechanism rather than adding competing refresh loops.

## Backend and frontend contract

Add `GET /api/collections/{id}/documents/summary`, under existing authentication
and collection lifecycle guards. Return `{ total, byStatus }`, where byStatus
contains integer counts for every current DocumentStatus (including zero).
Compute a grouped SQL read over the same managed-document eligibility as the
existing document list and derive total from that same snapshot. Do not count
file-only import items or documents in other collections. Unknown/deleting
collections follow existing not-found behavior; summary contains no paths/text.
Place/resolve the static summary route safely alongside the document-ID route.

Frontend `getCollectionDocumentSummary(collectionId)` owns that typed call.
Keep existing listing API/filters/sort/pagination unchanged. Parent page owns
`Map<string, DocumentListViewState>` and passes selected state and a callback
`onDocumentViewStateChange(collectionId, state)` to CollectionsPanel. Define the
type in `web/src/lib/documentListViewState.ts`. Validate cached status/sort against
current allowed values before applying. Update immutably so Svelte reactivity
observes changes; no shared SSR module singleton.

## Constraints and non-goals

Kotlin/JVM 25 and TypeScript/SvelteKit; SQLite authority, same single process,
loopback/bearer/CSRF boundaries, existing deletion and mutation safeguards.
English UI, restrained HTML/CSS, keyboard operation, labelled controls and
accessible disclosure state. Preserve pinned dependencies and unrelated changes.
No separate route/page, sidebar reorganization, document-content search, row
virtualization, new OCR backend, bulk rescanning or changed Retry eligibility.
No persistent user-preference database is required. This design does not close
existing Ask, source-viewer or manual accessibility acceptance gates.

## Acceptance

A collection with more than 50 documents occupies one compact row while collapsed;
settings/import/history remain available. Status counts include hidden pages and
clicks reveal the complete corresponding result set. Expanded rows and details
stay within the bounded scroll region on desktop and narrow screens. Re-entering
Admin restores the selected collection's view choices but never destructive
selection. Reload resets to collapsed. Out-of-order responses, debounce timers,
collection deletion and polling cannot restore stale rows or old collection state.

Use meaningful failing tests, focused passing tests, accumulated `./gradlew check`,
web `npm run check`/`npm run build`, and Collections browser acceptance against local
fixtures. No real OCR/GPU run is required for this presentation/aggregate feature;
do not claim that it verifies those capabilities. Human keyboard/screen-reader
checks and viewport measurements are recorded separately from unit tests.
