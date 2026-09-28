# 08: Delete selected documents together

**What to build:** The user selects several documents and removes exactly those
documents through one confirmation, with the same recoverable deletion behavior
as a single document.

**Blocked by:** 07 — Delete a document safely.

- [x] Each displayed row has an accessible checkbox; select-all selects only the
      current page and clearly states that scope. Hidden pages are never selected
      implicitly.
- [x] Collection, filter, sort or page changes clear selection; background
      refresh cannot silently add documents to the confirmed target set.
- [x] The confirmation identifies collection and count and snapshots the selected
      IDs. Cancellation sends no deletion; repeated submission is disabled.
- [x] The server accepts a nonempty explicit list of at most 200 document IDs.
      An invalid or cross-collection target rejects the entire request before
      destructive side effects. Duplicated IDs do not multiply removal work.
- [x] One durable operation records all targets and reuses single-document
      safeguards, phase recovery, import-item disposition and scoped cancellation.
- [x] Pending/failure/done state survives reopen and restart; partial execution
      is not presented as full success and resumes from durable phases.
- [x] Completion clears affected selections/viewers and refreshes matching totals
      and valid pagination. Unselected documents and external originals survive.
- [x] Route and component tests cover page-only selection, clearing selection,
      target snapshots, size limits, atomic validation, duplicated IDs and
      crash/resume of a multi-target deletion; accumulated checks pass.

**Status:** Done. Ticket 07's `POST
/api/collections/{id}/documents/delete` already admitted a list of IDs as one
operation, so this slice added the selection layer over it, not a second
deletion path: `CollectionsPanel.svelte` renders a checkbox per row and a
select-all whose name and hint say it checks only the current page, clears the
checked set on a collection, term, status, sort or page change, and prunes it to
the ids the refreshed page still shows, so a background refresh can neither add
a target nor keep a row that is gone. `Delete selected` opens one confirmation
that names the collection and the count (listing the filenames), snapshots the
selected IDs, and submits them through the existing `deleteDocuments` call as a
single 202 admission; the submit button disables while it runs, and the admitted
rows leave the selection. The server side is unchanged because it already
deduplicates targets (`DocumentService.beginDeletion`), validates every id
against the active collection before recording anything, and records up to 200
ids in one durable operation.

`JAVA_HOME=/Users/patrik/.asdf/installs/java/temurin-25.0.4+101.0.LTS ./gradlew
test --tests 'infoscry.server.DocumentDeletionRoutesTest' --tests
'infoscry.document.*'` passed: 27 tests, 0 failed (the route class gained a
duplicated-ID case, `DocumentDeletionRecoveryTest` gained a three-target
kill-and-recover case and a no-crash three-target case). The accumulated focused
suite `--tests 'infoscry.server.*' --tests 'infoscry.storage.*' --tests
'infoscry.jobs.*' --tests 'infoscry.collection.*' --tests 'infoscry.search.*'`
passed: 344 tests across 31 classes, 0 failed. Web: `npx vitest run` passed (173
tests, 8 files; `CollectionsPanel` gained 5 cases: page-only select-all and the
collection/count confirmation, clearing on filter/sort/page/collection change,
pruning on refresh, the ID snapshot with repeated submission refused, and the
single-document confirmation unchanged) and `npm run check` reported 0 errors
and 0 warnings. Removing `clearSelection`'s body or the refresh pruning was
checked to fail those two component tests before restoring the file. The
accumulated gate and browser acceptance stay ticket 12's.

Notes: selection is one `Set<string>` of IDs pruned to the displayed page, so
"select all" cannot mean the collection and no hidden row can enter the
confirmation; the dialog's snapshot is the array captured when it opened, so its
count and filenames cannot change under an overlapping refresh. The durable
operation's stored target order is not the request order, so the tests compare
target sets; the UI treats the returned ids as a set too.
