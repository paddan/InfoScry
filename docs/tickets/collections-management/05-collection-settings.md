# 05: Edit collection settings

**What to build:** The user can rename a selected collection and change its OCR
languages in Collections. They understand that languages affect future work and
new attempts, without silently reprocessing successful documents.

**Blocked by:** 01 — Collections replaces Import.

**Status:** done (2026-09-27) — verified by `./gradlew test --tests
'infoscry.server.CollectionRoutesTest'` (21 tests, 0 failed; the four new route tests
cover a duplicate rename, a blank rename, a future import snapshotting the saved OCR
languages, and a settings save queueing no work and leaving a completed document alone),
`./gradlew test --tests 'infoscry.server.*' --tests 'infoscry.storage.*' --tests
'infoscry.jobs.*'` (265 tests, 0 failed), web `npx vitest run` (160, 0 failed, 7 new) and
`svelte-check` (0 errors, 0 warnings). No browser acceptance: that is ticket 12's gate.

- [x] Collection settings expose the current name and OCR languages with save,
      pending, success, and readable validation/error states.
- [x] Rename retains existing collection identity, uniqueness and validation;
      successful changes refresh both Admin and the workspace collection selector.
- [x] OCR-language validation uses existing service rules. Future imports and
      explicit retries snapshot the saved settings; previously queued payloads
      retain their snapshot.
- [x] The UI states that saved languages do not automatically reprocess completed
      documents and may require repeated extraction in a later explicit retry.
- [x] Changes preserve document IDs, import history, source references, and
      current conversation selection.
- [x] Mutations enforce existing credentials and shared mutation admission;
      maintenance conflicts and invalid/deleting collections surface typed errors.
- [x] Route and component tests cover rename, duplicate/invalid names, invalid
      language settings, failed saves, settings snapshots, and no automatic
      reprocessing. Focused tests and the accumulated offline gate pass.
