# 10: Retry all eligible documents in a collection

**What to build:** The user starts a retry for every eligible document in the
selected collection, across all list pages, and sees which documents were
accepted or rejected and why.

**Blocked by:** 09 — Retry a document from its managed copy.

- [x] `Retry all` explicitly describes collection-wide scope; filtering or the
      current page does not silently restrict its eligible target set.
- [x] The server selects only `FAILED`, `CANCELLED` and `NEEDS_TOOL` documents in
      that collection and applies individual retry safeguards to each admission.
      Completed documents and warnings alone remain untouched.
- [x] Requests specify either explicit IDs (nonempty, maximum 200) or
      `allEligible: true`, never both. Ambiguous requests are rejected as typed
      bad requests; collection-wide selection does not load all file content.
- [x] A bounded durable admission creates attempts without duplicate scheduling;
      collection deletion/maintenance and newly active or deleting targets obey
      the same ownership and admission guards as single Retry.
- [x] Responses identify accepted job IDs and rejected document IDs with safe
      reasons. No eligible documents is a readable no-op, not a fake success count.
- [x] UI pending/results state distinguishes accepted retries, skips and failures
      and refreshes document statuses. Accepted work stays server-owned after
      navigation, disconnection or restart.
- [x] Tests include eligible documents beyond the first page, mixed statuses,
      no eligible targets, concurrent Retry all calls, changing eligibility,
      invalid request combinations and delete races; accumulated checks pass.

**Status:** Done. Ticket 09's collection-wide admission already existed; this
slice is the collection-wide layer over it, so no second retry path was added.
`Retry all eligible documents` in `CollectionsPanel.svelte` sends
`allEligible: true` through the page (`retryAllManagedDocuments`) and never the
ids, filter or page of the table; its hint names the collection-wide scope
explicitly. It shows one pending submission, then distinguishes accepted
retries, grouped skips with the server's own sentence and request failures, and
re-reads the rows and any open details — a request with nothing eligible reads
`Nothing to retry` rather than a success count. Four route tests were added to
`DocumentRetryRoutesTest` (16 in the file) for eligible documents beyond the
first 50-row page (120 eligible plus a complete document; the job carries all
120), per-document safeguards inside one collection-wide pass (an import that
still holds one document and a deletion that targeted another), two concurrent
`allEligible` calls queueing one attempt, and an archive where every eligible
document is refused by a missing embedding model (no job, reasons returned).
Frontend: six `CollectionsPanel` cases (scope copy and skip reporting from
a filtered second page, nothing eligible, refused-everything, one submission in
flight, a failed request, and statuses read back from the server after a
reopen) plus one `page.test.ts` case asserting the request body is exactly
`{"allEligible":true}` for the managed collection.

Commands and observed results:
`JAVA_HOME=/Users/patrik/.asdf/installs/java/temurin-25.0.4+101.0.LTS ./gradlew -Dkotlin.daemon.jvmargs=-Xmx3g test --tests 'infoscry.server.DocumentRetryRoutesTest'`
→ 16 tests, 0 failed (12 existing, 4 new; the first run died in `compileKotlin`
with a heap OutOfMemoryError, so the daemons were stopped and the same command
succeeded); `--tests 'infoscry.server.*' --tests 'infoscry.storage.*' --tests
'infoscry.jobs.*' --tests 'infoscry.collection.*' --tests 'infoscry.search.*'
--tests 'infoscry.document.*'` → 401 tests, 0 failed; `cd web && npx vitest run`
→ 187 passed; `npm run check` → `svelte-check found 0 errors and 0 warnings`.
The accumulated `./gradlew check` gate and browser acceptance remain the
parent's and ticket 12's.
