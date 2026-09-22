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
