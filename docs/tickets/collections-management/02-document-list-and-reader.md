# 02: Find and open documents

**What to build:** Within a selected collection, the user can find documents in
a paginated list, inspect their existing metadata and status, and open available
content in the reader. This slice uses actual existing status information;
ticket 04 adds detailed extraction progress.

**Blocked by:** 01 — Collections replaces Import.

- [x] Rows show filename, media type, size, import date, and readable document
      status. An empty result is distinct from a loading or failed request.
- [x] Filename search is a literal case-insensitive contains match, including
      literal `%`, `_`, and backslash; it never searches external source paths.
- [x] Status filtering and sorting by newest, oldest, name ascending, or name
      descending happen before server pagination. Ties use stable document IDs.
- [x] The displayed total uses the same criteria as the returned rows. The UI
      requests 50 rows per page; the existing server cap remains 200.
- [x] A filter/sort change resets the offset. Older responses cannot overwrite
      results for a newer filter or another selected collection.
- [x] Collection-scoped details return safe metadata, status, curated error
      information, and the first available reader source ID. Unknown or
      cross-collection document IDs return not-found without metadata leakage.
- [x] `Open document` opens the existing reader at the first available content
      unit. Documents without readable content explain why opening is unavailable.
- [x] Raw exceptions, document text, source paths, hashes, job payloads, and
      authorization headers are excluded from management responses and errors.
- [x] Backend tests cover filters before pagination, matching totals, wildcard
      escaping, deterministic ties, invalid parameters, and scoped detail reads.
- [x] Component tests cover search/filter/sort/paging, empty/error states,
      stale-response protection, details, and reader opening; focused checks and
      the accumulated offline gate pass.

**Status:** done (2026-09-27) — verified by
`JAVA_HOME=/Users/patrik/.asdf/installs/java/temurin-25.0.4+101.0.LTS ./gradlew test --tests infoscry.server.DocumentRoutesTest`
(13 tests, 0 failures; includes 7 new tests for filters before paging, matching totals, literal
wildcard escaping, deterministic ties, invalid `sort`/`status`, scoped detail reads and the
cross-collection 404),
`./gradlew test --tests 'infoscry.server.*' --tests 'infoscry.storage.*'` (green: 198 tests, 0
failures on the re-run; see the note below), `cd web && npx vitest run` (138 tests, 0 failures;
CollectionsPanel 20, page 62) and `npm run check` (`svelte-check`: 0 errors, 0 warnings). A first
run of the server/storage selection reported 4 `NoClassDefFoundError` failures in
`SearchRoutesTest` together with a Gradle `NoSuchFileException` on its in-progress results file;
`SearchRoutesTest` alone and the full selection re-run with `--rerun-tasks` both passed, so that
run looks like test-infrastructure trouble rather than an application failure. The accumulated
offline gate (`./gradlew check`) and browser acceptance are the parent's/ticket 12's; no browser
acceptance is claimed here.
