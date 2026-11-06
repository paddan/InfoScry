# Ticket 4: Job queue in Postgres

Spec: [Multi-user server](../../specs/2026-11-05-multi-user-server-design.md), section *Jobs*, ticket 4 of
the suggested order. Status: implemented on branch `job-queue` (2026-11-06), not yet merged to `main`.

## What the spec asks, and where it stands

| Requirement | State |
|---|---|
| Jobs are rows in Postgres | Done in [ticket 2](02-postgres-storage.md): `jobs` moved with the rest of the schema |
| Each lane claims its oldest queued job with `FOR UPDATE SKIP LOCKED` | Done here: `JobStore.claimNextQueued` locks the row it claims and skips rows another worker holds, so concurrent workers take different jobs and none waits for another's open claim. The claim's `UPDATE … WHERE state = 'QUEUED'` stays the compare-and-set |
| At start, holding the advisory lock, every `RUNNING` job returns to `QUEUED` | Already true: `AppContext.open` takes `ProcessOwnership` before anything else, and `JobStore.resetInterrupted` requeues interrupted attempts (or finishes those whose cancellation was recorded); work resumes from committed checkpoints |
| A stopped job never blocks starting again | Unchanged |
| Deletion keeps its persisted phases and recovery | Unchanged |

No lease columns are added: with one process per database (the advisory lock), a `RUNNING` job at start
always belongs to a process that stopped. Leases are needed only when a separate worker process exists,
which the spec leaves out of scope.

## Verification record (2026-11-06)

- New `JobStoreTest` case: with the oldest job's row locked by another database connection, a claim returns
  the next job within the timeout instead of waiting; once released, the older job is claimed. It failed
  (timeout) before the change and passes after it.
- Existing coverage of restart recovery: `JobStoreTest` (interrupted work returns to the queue exactly once)
  and `JobRunnerRecoveryTest` (a killed process's jobs resume).
- `./gradlew check`: 2,206 backend and 576 frontend tests, 0 failures.
