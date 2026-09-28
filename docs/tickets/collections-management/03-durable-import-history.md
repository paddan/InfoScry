# 03: Restore durable import history

**What to build:** The user sees current and previous imports for a collection,
including per-file results, after navigating away, reloading the browser, or
restarting the server. Queued files and pre-copy failures are visible even when
no document row exists.

**Blocked by:** 01 — Collections replaces Import.

- [x] A collection-scoped history read returns persisted import jobs with state,
      stage, file completed/total, and links to their persisted per-file outcomes.
      History is paginated with a default of 50 and maximum of 200 jobs.
- [x] The UI shows pending, imported, duplicate, and failed file outcomes with
      safe messages; counters are explicitly labelled as files, not OCR pages.
- [x] Pending imports and failures before managed-copy creation remain import
      items; the UI does not invent document IDs or completed document rows.
- [x] A failed item without a managed document explains resubmission through
      `Add documents`; it does not offer managed-document Retry.
- [x] Reopening or reloading Collections restores server snapshots and ongoing
      progress. Polling/SSE subscriptions stop when their panel is unmounted and
      refresh from a snapshot when reopened.
- [x] Switching collections and out-of-order responses cannot reveal another
      collection's results or overwrite the newly selected collection's state.
- [x] Unknown/deleting collections follow the active-collection boundary. Reads
      expose no original paths, raw failure text, or sensitive job payloads.
- [x] HTTP tests with temporary SQLite data verify history pagination,
      collection isolation, pre-document failures, and persisted outcomes.
- [x] Component tests verify reload/reopen, terminal results, duplicates, stream
      or polling cleanup, stale responses, and readable error states. Focused
      tests and the accumulated offline gate pass.

**Status:** Done for this slice. Implemented `GET /api/collections/{id}/imports`
(`ImportHistoryRoutes.kt`) over two new `JobStore` reads, the typed
`listCollectionImports` client call, and the Collections panel's import-history
section with its per-file results, polling and unmount cleanup.

Verified commands and observed results:

- `JAVA_HOME=/Users/patrik/.asdf/installs/java/temurin-25.0.4+101.0.LTS ./gradlew test --tests infoscry.server.ImportHistoryRoutesTest`
  — first run failed to compile (`ImportsResponse` did not exist yet), then 9/9
  pass.
- `JAVA_HOME=/Users/patrik/.asdf/installs/java/temurin-25.0.4+101.0.LTS ./gradlew test --tests 'infoscry.server.*' --tests 'infoscry.storage.*'`
  — 207 tests, 0 failures. A first attempt ended in a Gradle infrastructure
  failure (`NoSuchFileException` on its own
  `build/test-results/test/binary/in-progress-results-*.bin`), not an assertion;
  the rerun passed.
- `cd web && npx vitest run` — 148 tests, 0 failures (CollectionsPanel 29,
  page 63).
- `cd web && npm run check` — 0 errors, 0 warnings.

The accumulated `./gradlew check` and browser acceptance are ticket 12's gate.
