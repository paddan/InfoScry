# Compact document list implementation plan

> **For agentic workers:** Use superpowers:executing-plans for inline work, or superpowers:subagent-driven-development if the user chooses delegation. Criteria remain unchecked until verified.

**Goal:** Keep Collections focused on overview/import while making document management compact and accessible.

**Architecture:** Add a collection-wide status aggregate to existing thin routes. Keep listing APIs intact; a parent-owned in-memory state map drives an expandable, bounded document table with debounced search and inline details.

**Tech Stack:** Kotlin/JVM 25, SQLite, TypeScript/SvelteKit, existing Vitest and Playwright harnesses.

**Spec:** [Compact document list](../../specs/2026-09-30-compact-document-list.md).

Design and documentation authorized on 2026-09-30. Implementation has not started.
The existing OCR plan is separate; none of these tickets depends on OCR work.

## Global constraints

- Kotlin/JVM 25; TypeScript/SvelteKit static frontend; one application process.
- SQLite authority; existing loopback, bearer/CSRF, collection scoping and lifecycle guards.
- Preserve immutable sources, pinned dependencies and unrelated working-tree changes.
- English product copy, ordinary accessible HTML/CSS; no new UI library or separate page.
- Keep 50-row pagination, filename-only search and existing Retry/deletion semantics.
- Session memory only; reload resets to collapsed. No durable user-preference storage.
- Planned OCR review is not an implemented status; do not display fictitious counts.

## Tickets

| Ticket | Blocked by | State |
|---|---|---|
| [01 — Collection-wide summary](01-summary.md) | None | Not started |
| [02 — Collapse, bounded layout and session memory](02-collapse-and-session.md) | 01 | Not started |
| [03 — Live search and inline details](03-live-search-and-details.md) | 02 | Not started |
| [04 — Browser acceptance and documentation](04-acceptance.md) | 03 | Not started |

Work the verified dependency frontier. Tickets share CollectionsPanel; do not
infer authorization for parallel edits. This is deliberately four reviewable
slices rather than separate tasks for every control. No code changes, installs,
commits or pushes are authorized by the documentation itself.

## Shared interfaces and test examples

Ticket 01 adds `getCollectionDocumentSummary(collectionId)` returning
`{ total: number, byStatus: Record<DocumentStatus, number> }` in `api.ts`.
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

Planning documents only. All implementation tickets are unstarted; no application
or browser test was run for this feature during planning.
