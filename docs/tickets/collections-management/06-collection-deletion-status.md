# 06: Delete collections with recoverable visible status

**What to build:** The user confirms a collection's exact name to delete it,
including during import, and sees durable deletion status until removal is done.
The same operation can be inspected after navigation or restart, even after the
collection row is gone. This slice exposes the operation-status mechanism used
by document deletion next.

**Blocked by:** 01 — Collections replaces Import.

- [x] The accessible confirmation dialog identifies the collection and permanent
      removal of InfoScry data, explains cancellation of ongoing work, and
      requires the exact collection name. Declining removes nothing.
- [x] Existing deletion phases remain authoritative; admission is durable before
      asynchronous work. The response includes operation ID, collection ID and
      phase, and existing consumers are updated together.
- [x] A safe operation read reports kind, target IDs, phase, terminal flag and
      error code without managed/trash paths; it works after collection removal.
- [x] The initiating UI shows `Deleting…` until `DONE`, prevents duplicate
      submission, and restores unfinished operations when Admin is reopened.
- [x] Requests disconnected after admission and process restarts cannot lose the
      operation. Repeated admission returns the existing unfinished operation.
- [x] Collection jobs receive cancellation requests and active mutation stages
      drain at bounded checkpoints. Deleting collections accept no new imports.
- [x] Unsafe recovery preserves parked files, blocks mutations and reports an
      actionable state without incorrectly displaying success.
- [x] Completion refreshes collection lists, clears selection/results bound to
      the removed collection and closes its open source viewer. Other collection
      content, external originals, and LLM profiles remain unaffected.
- [x] Tests exercise credential/name rejection, deletion during import, durable
      status after row removal, disconnection, repeated admission, each phase's
      interruption/recovery and unsafe recovery. Component tests cover
      confirmation, reopen, errors and selection cleanup; accumulated checks pass.

**Status:** Done. `JAVA_HOME=/Users/patrik/.asdf/installs/java/temurin-25.0.4+101.0.LTS
./gradlew test --tests 'infoscry.server.*' --tests 'infoscry.storage.*' --tests
'infoscry.jobs.*' --tests 'infoscry.collection.*' --tests 'infoscry.search.*'`
passed (0 failed); the new `infoscry.server.DeletionRoutesTest` (9 tests) and the
updated `CollectionRoutesTest` (22), `SearchRoutesTest` (17) and
`CollectionDeletionRecoveryTest` pass, and all six of the new deletion-route
assertions that pin asynchronous admission fail against the old synchronous route
(checked by temporarily restoring `deleteConfirmed`/200 and re-running the class).
Web: `npx vitest run` passed (166 tests, 8 files) and `npm run check` reported
0 errors and 0 warnings. The accumulated gate and browser acceptance stay
ticket 12's.

Notes: the collection route answers `202` with `{operationId, collectionId,
phase}`; `GET /api/deletions/{operationId}` answers kinds, target IDs, phase,
terminal flag and a curated error code (never the stored message, which names
data-directory paths); `GET /api/deletions` lists the unfinished operations so a
reopened Admin can restore them. Migration 011 adds the durable `error_code`
beside the message. Deletions admitted after this change are finished by a
coroutine the collection service owns, closed with the context; the process-owned
work and a real restart are covered by the existing interrupted-process harness
tests. No CLI `collection delete` command exists in this tree, so no CLI consumer
needed updating.
