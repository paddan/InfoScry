# Task 2 report: reindex maintenance and reader leases

## Scope

Implemented only the reindex maintenance/reader-lease proofs and the explicitly approved
`MutationCoordinator` admission change. Controller-owned `README.md`, `docs/**`, and the journal were
not changed by this task.

## RED evidence

- Added a direct coordinator regression proving a competing exclusive maintenance owner must fail fast
  and that a retry succeeds after release.
- Before the production change,
  `JAVA_HOME="$(asdf where java)" ./gradlew test --tests infoscry.storage.MutationCoordinatorTest`
  failed in the two new competing-owner tests with `TimeoutCancellationException`: the old
  `maintenanceOrder` implementation waited instead of refusing.
- Added the real reader-lease assertion against `LuceneIndex.withReaderLease`; before that seam,
  `JAVA_HOME="$(asdf where java)" ./gradlew test --tests infoscry.search.ReindexRecoveryTest`
  failed compilation with `Unresolved reference 'withReaderLease'`.

## GREEN evidence

- `JAVA_HOME="$(asdf where java)" ./gradlew test --tests infoscry.storage.MutationCoordinatorTest`
  passed after fail-fast admission was implemented.
- `JAVA_HOME="$(asdf where java)" ./gradlew test --tests infoscry.search.ReindexRecoveryTest`
  passed with both maintenance tests active.
- The maintenance test now runs rebuild work on `Dispatchers.Default`, marks the seeded document's
  chunking marker stale so the blocking embedder is reached, asserts ordinary mutation and collection
  deletion refusal during rebuild, then verifies both are admitted after release.
- The reader test holds a real old-generation lease across publication, verifies the old generation
  remains on disk after publication, and verifies retirement only after lease release.

## Final gate

Passed:

```text
JAVA_HOME="$(asdf where java)" ./gradlew test --tests infoscry.search.ReindexRecoveryTest --tests 'infoscry.jobs.*'
JAVA_HOME="$(asdf where java)" ./gradlew check
git diff --check
```

The recovery tests, jobs tests, frontend tests (5/5), full Kotlin/JVM test suite, `check`, and diff
validation all passed. `check` completed successfully in 5m 50s.

## Changed files

- `src/main/kotlin/infoscry/storage/MutationCoordinator.kt`: competing external exclusive owners now
  fail fast with `MaintenanceInProgressException`; `awaitMutation` retains waiting behavior.
- `src/main/kotlin/infoscry/search/LuceneIndex.kt`: minimal internal `withReaderLease` test seam over
  the existing lease accounting.
- `src/test/kotlin/infoscry/storage/MutationCoordinatorTest.kt`: direct winner/refusal/retry and
  background-stage regression coverage.
- `src/test/kotlin/infoscry/search/ReindexRecoveryTest.kt`: re-enabled concurrent maintenance tests,
  controllable dispatcher fixture, post-release mutation assertions, and old-generation lease proof.

## Self-review and concerns

- `withReaderLease` is `internal` and does not expose `IndexSearcher`; it only exists to exercise the
  existing lease boundary without duplicating production protocol logic.
- `maintenanceInProgress` is claimed atomically under `gate` before waiting for active stages, so a
  pending maintenance owner is also visible/refusable. Existing background stages still use
  `awaitMutation` and wait as required.
- No production behavior beyond the explicitly ruled-in fail-fast external maintenance admission was
  changed.

## Fix round 1

Addressed the review findings without touching the controller-owned README, status/plans, or journal.

### RED/GREEN

- Added a real `POST /api/imports` regression through `ApiTestServer`: before the fix it returned 202
  and created a queued job during held maintenance; after the fix it returns HTTP 423 with
  `MAINTENANCE_IN_PROGRESS`, creates no job row, and accepts after release.
- Added a real server-owned CLI regression through `ImportCommandProcessTest`: before the fix the child
  exited 0 despite the remote refusal and did not expose the typed code; after the fix it exits nonzero,
  includes `MAINTENANCE_IN_PROGRESS`, and creates no job row.
- Added admission around the local CLI enqueue path as well; `RemoteApiFailure` now becomes a typed
  `CliFailure` preserving its error code.
- Extended the old-generation lease test to signal after publication and run an actual keyword search
  through the held old `IndexSearcher`. The result remains searchable after the successor is current;
  retirement is asserted only after releasing the lease.
- The lease RED compile run failed with the expected missing `searchKeywordWithReader` seam; focused
  GREEN tests passed after adding the minimal internal reader-query seam.

### Fix-round verification

Passed:

```text
JAVA_HOME="$(asdf where java)" ./gradlew test \
  --tests infoscry.search.ReindexRecoveryTest \
  --tests infoscry.server.CollectionRoutesTest \
  --tests infoscry.cli.ImportCommandProcessTest
```

The combined focused run passed all selected tests. The final recovery gate and full `check` are run
after this report append before the follow-up commit.

### Fix-round self-review

- HTTP admission encloses the enqueue side effect in `MutationCoordinator.withMutation`; existing
  claimed job stages remain on `awaitMutation` and continue waiting at checkpoints.
- Local CLI admission is also before `jobs.enqueue`; remote CLI receives the server's typed refusal and
  preserves its code in the process error.
- The lease seam exposes the held `IndexSearcher` only internally and reuses the index's normal parser,
  filtering, and hit conversion for the post-publication proof.
