# Compact document list implementation plan

> **For agentic workers:** Use superpowers:executing-plans for inline work, or superpowers:subagent-driven-development if the user chooses delegation. Criteria remain unchecked until verified.

**Goal:** Make import/reading outcomes easy to check, with a compact full-width document list leading into page review.

**Architecture:** Add a collection-wide status aggregate to existing thin routes. Keep listing APIs intact; a parent-owned in-memory state map drives an expandable, bounded document table with debounced search and inline details.

**Tech Stack:** Kotlin/JVM 25, SQLite, TypeScript/SvelteKit, existing Vitest and Playwright harnesses.

**Spec:** [Compact document list](../../specs/2026-09-30-compact-document-list.md).

Design and documentation authorized on 2026-09-30. Ticket 01 was implemented on
2026-10-07; ticket 02 is implemented and reviewed following the owner's execution
instruction on 2026-10-09. Tickets 03–04 are implemented and reviewed; browser
acceptance completed 2026-10-10, with human/provider/GPU gates still open.
Refined 2026-10-09 alongside the [wide workspace and page review plan](../../plans/2026-10-09-workspace-and-page-review.md).
That plan's ticket 01 supplies the shared wide workspace before ticket 02 here.
Manual page rescanning is separate and does not block shipping the compact list.

## Global constraints

- Kotlin/JVM 25; TypeScript/SvelteKit static frontend; one application process.
- SQLite authority; existing loopback, bearer/CSRF, collection scoping and lifecycle guards.
- Preserve immutable sources, pinned dependencies and unrelated working-tree changes.
- English product copy, ordinary accessible HTML/CSS; no new UI library or separate page.
- Keep 50-row pagination, filename-only search and existing Retry/deletion semantics.
- Session memory only; reload resets to collapsed. No durable user-preference storage.
- No pending OCR review/approval stage; counts use existing statuses only.

## Tickets

| Ticket | Blocked by | State |
|---|---|---|
| 01 — Collection-wide summary | None | Implemented |
| [02 — Collapse, bounded layout and session memory](02-collapse-and-session.md) | 01; workspace/page-review 01 | Implemented |
| [03 — Live search and inline details](03-live-search-and-details.md) | 02 | Implemented |
| [04 — Browser acceptance and documentation](04-acceptance.md) | 03 | Implemented |

Work the verified dependency frontier. Tickets share CollectionsPanel; do not
infer authorization for parallel edits. This remains four reviewable slices; the separate companion tickets own the
workspace, page comparison and explicit-page OCR contracts. No code changes, installs,
commits or pushes are authorized by the documentation itself.

## Shared interfaces and test examples

Ticket 01 adds `getCollectionDocumentSummary(collectionId)` returning
`{ total: number, byStatus: Record<DocumentStatusName, number> }` in `api.ts`.
Use the existing frontend status union; resolve its exact exported type name
when editing rather than create a second incompatible enum. Ticket 02 defines
`DocumentListViewState` in `documentListViewState.ts` with the exact fields in
the spec and adds `onDocumentViewStateChange(collectionId, state)` to the panel.
The parent owns the map. Ticket 03 consumes those same fields and callback.

Required acceptance vectors, owned by the indicated tickets:

```json
[
  {"ticket":"01","documents":75,"failedOnSecondPage":2,"expectedSummaryFailed":2},
  {"ticket":"02","action":"click total","oldQuery":"invoice","oldStatus":"FAILED","expectedQuery":"","expectedStatus":""},
  {"ticket":"02","action":"reload","previousExpanded":true,"expectedExpanded":false},
  {"ticket":"03","typedAtMs":[0,100,200],"debounceMs":300,"expectedFinalRequestAtMs":500},
  {"ticket":"03","openDetails":["a","b"],"expectedOpenDetails":"b"}
]
```

## Review focus

- Counts derived from a filtered/loaded page rather than the collection — 01.
- Parent unmount losing state or restoring checked destructive targets — 02.
- A response arriving during debounce or after a collection switch — 03.
- Nested scrolling hiding keyboard focus/details on a narrow screen — 03 and 04.
- Polling hidden panels or summary failures being rendered as zero — 02 and 04.

## Verification and stop conditions

Each ticket requires a failing behavioral test, focused green tests and the
accumulated `./gradlew check`. Select JDK 25 with
`export JAVA_HOME="$(asdf where java)"` when needed. Frontend changes also run
`npm run check` and `npm run build` from web. Ticket 04 runs the Collections
`externalTest` class against the real local app and fake extractors. Tests do
not require private data or real OCR/CoreML; no hardware capability is claimed.
Stop dependent work on incorrect aggregate scope, stale-response failures,
hidden selected targets or broken lifecycle guards. Record infrastructure
failures separately, and leave unperformed manual acceptance unchecked.

Documentation-only verification: local links, fenced code balance, whitespace,
consistent state/API names and spec-to-ticket coverage. Existing Ask/source-viewer
and other manual gates remain open.

## Current evidence

Ticket 01 is implemented and its commands, counts and red evidence are in the
verification record below. Ticket 02 passed its final accumulated gate with 1,585
JVM and 358 frontend tests, and independent review is clean. Ticket 03 is in
implemented with a final gate of 1,585 JVM / 382 frontend tests and clean review;
04 passed 16 browser scenarios and final gate 1,585 JVM / 383 frontend tests.
The existing Ask/source-viewer and human accessibility gates remain open.

## Verification record

### Ticket 01 — collection-wide document summary (2026-10-07)

Worktree branched at `8dc876d`; JDK 25 selected with
`export JAVA_HOME="$(asdf where java)"`; `./gradlew` only, one invocation at a time.

Red before implementation (the wire type `DocumentSummaryResponse` was added together
with the tests so they compile; no route or store read existed yet):

```sh
./gradlew test --tests 'infoscry.server.DocumentRoutesTest' --console=plain -q
```

Result: `24 tests completed, 5 failed` — all five new summary tests, each with the same
behavioral assertion:

```text
{"error":{"code":"NOT_FOUND","message":"no document with id summary exists in this collection"}} ==> expected: <200 OK> but was: <404 Not Found>
```

`/documents/summary` fell through to the document-ID route — the exact conflation the
spec's "place/resolve the static summary route safely" forbids.

Focused green for this ticket's classes:

```sh
./gradlew test --tests 'infoscry.server.DocumentRoutesTest' --tests 'infoscry.storage.DocumentStoreTest' --console=plain
```

Result: BUILD SUCCESSFUL — `DocumentRoutesTest` 24 tests / 0 failures,
`DocumentStoreTest` 3 tests / 0 failures.

The ticket's full focused command was also run:

```sh
./gradlew test --tests 'infoscry.server.DocumentRoutesTest' --tests 'infoscry.storage.*' --console=plain
```

Result: 135 tests, 133 passed, 2 failed — only the pre-existing `SchemaMigratorTest`
incidents recorded below; every test of this ticket passed.

Web frontend, from `web/`:

```sh
npm test -- --run   # 10 files, 220 tests passed (219 existing + 1 new)
npm run check       # svelte-check found 0 errors and 0 warnings
npm run build       # vite build ok; adapter-static wrote build/
```

Accumulated gate:

```sh
./gradlew check --console=plain
```

Result: BUILD FAILED in 18m — `:frontendTest` passed (10 files, 220 tests); backend
`:test` ran 108 suites / 1417 tests with 2 failed, both the pre-existing
`SchemaMigratorTest` cases below; no other suite failed and there was no
OutOfMemoryError.

#### Infrastructure incidents (separate from this ticket's behavior)

- `infoscry.storage.SchemaMigratorTest` has two cases requiring migration 028
  (`revision_id` on `evidence_ledger`, `user_version = 28`), but the branch point
  `8dc876d` contains no `028_*.sql` and `SchemaMigrator.SUPPORTED_VERSION` is still 27;
  the test file is untouched by this ticket (`8dc876d` introduced those cases,
  `8dc876d^` has none). Observed failing twice — the ticket's full focused command and
  the accumulated check — and deliberately not retried or fixed here: migration 028 is
  OCR ticket 02d's work ("Not started"), this plan does not allocate migrations it does
  not own, and these tickets are independent of OCR work. A green accumulated gate
  needs 028 landed by the OCR side first.

#### Not claimed

No hardware or real-provider gate was run or claimed: this is a presentation/aggregate
feature tested with fake providers and local fixtures only. Human keyboard/screen-reader
checks and viewport measurements remain for ticket 04; the existing Ask/source-viewer
and manual accessibility gates remain open.
