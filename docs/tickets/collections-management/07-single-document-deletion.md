# 07: Delete a document safely

**What to build:** The user removes one document after confirmation and can
follow its deletion to completion. Removal works during import/OCR/indexing,
removes all managed data, and does not interrupt or resurrect other documents
in the same multi-file import.

**Blocked by:** 02 — Find and open documents; 06 — Delete collections with
recoverable visible status.

- [x] The confirmation identifies filename and collection, explains permanent
      removal and interruption of targeted work, and sends an explicit confirmed
      document ID. Declining performs no mutation.
- [x] Unknown or cross-collection IDs are rejected before side effects. Mutation
      credentials, active-collection checks and shared/exclusive admission apply.
- [x] Deletion persists targets and guards before destructive work, drains
      in-flight bounded stages, parks the managed directory, removes related
      database data, commits index removal, and purges parked files.
- [x] Phases are idempotent and recover before startup admits jobs. A disconnected
      request does not lose admitted work; repeat admission returns its unfinished
      operation. Unsafe recovery preserves files and blocks mutations.
- [x] Copy/attach, extraction commit, status updates and index publication obey
      the deleting guard. Persisted import-item disposition prevents a later
      resume from copying the deleted target again.
- [x] Only targeted work is cancelled/skipped. Other files in a multi-file import
      continue and remain readable/searchable; copies in other collections and
      external originals remain untouched.
- [x] Existing operation reads and the UI show pending/failed/done document
      deletion across reload and restart, including after the document row is gone.
- [x] Completion removes the row, adjusts paging/counts and closes its open
      viewer. Saved citations report unavailable source content rather than
      silently pointing at another document.
- [x] Tests cover interruption at every filesystem/database/index boundary,
      failed recovery, copy/OCR/index races, multi-file continuation, and
      resurrection prevention across restart. Route/component tests cover scoped
      IDs, credentials, confirmation and status; accumulated checks pass.

**Status:** Done. Migration 012 adds `deletion_operations.kind`, the durable
`document_deletion_targets` guard (with `managed_existed` per target) and the
`CANCELLED` per-file disposition; `POST
/api/collections/{id}/documents/delete` admits the operation and answers `202`
with `{operationId, collectionId, documentIds, phase}`, and
`GET /api/deletions/{operationId}` reports the `DOCUMENT` kind and its targets.
`JAVA_HOME=/Users/patrik/.asdf/installs/java/temurin-25.0.4+101.0.LTS ./gradlew
test --tests 'infoscry.server.*' --tests 'infoscry.storage.*' --tests
'infoscry.jobs.*' --tests 'infoscry.collection.*' --tests 'infoscry.search.*'
--tests 'infoscry.document.*'` passed: 360 tests across 32 classes, 0 failed,
including the new `DocumentDeletionRecoveryTest` (17) and
`DocumentDeletionRoutesTest` (7). The recovery class kills a real child JVM at
each of the seven filesystem/database/index instants, asserts the interrupted
phase and directory state, then recovers; it also covers both unsafe states, an
injected index failure, a database failure, a parked-files cleanup failure, the
status-write and extraction-commit guards, the embedding-to-index race, and a
resumed multi-file import whose item disposition keeps the deleted document from
being copied again. The route class covers the 202 admission and follow, the
absence of paths in the read, unconfirmed/uncredentialed/empty/oversized/
unknown/cross-collection refusals with no side effect, repeated admission, a
second deletion refused `423` under exclusive maintenance, a disconnected
caller, and a saved source link answering `404`. Removing the `NOT EXISTS` guard
from `DocumentStore.updateStatus` makes the status-write test and the
index-race test fail (checked by temporarily removing it and re-running the
class), so they pin the guard rather than the plumbing around it. Web: `npx
vitest run` passed (169 tests, 8 files; CollectionsPanel gained 3 tests for the
document confirmation, the followed deletion and its restore across reopen) and
`npm run check` reported 0 errors and 0 warnings. The accumulated gate and
browser acceptance stay ticket 12's.

Notes: the phases reuse ticket 06's `PREPARED/FILES_MOVED/DB_DELETED/
INDEX_DELETED/DONE` vocabulary because the work is the same shape; `kind` says
what the targets mean. Admission writes the operation, its target rows and the
`CANCELLED` item dispositions in one transaction before anything destructive, and
the destructive phases run in a coroutine the document service owns, so a lost
response or a killed process leaves a resumable operation. `AppContext.open`
finishes collection and document deletions before `resetInterrupted`, so no job
is admitted over a half-deleted document. The index-publication guard shares its
boundary with the status-write guard, which is the one that fires in every
reachable interleaving: the deletion cannot persist its target while a stage
holds a mutation permit, so the index write is always refused one step earlier
than the publication itself. Collection deletion's file-only pending targets are
ticket 06's scope and were left as they are.
