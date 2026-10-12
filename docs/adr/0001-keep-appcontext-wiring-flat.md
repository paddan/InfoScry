# ADR 0001: Keep AppContext's wiring flat

**Status:** Accepted
**Date:** 2026-10-12

## Context

A codebase architecture review (2026-10-11/12) flagged `AppContext.kt`
(737 lines, ~15 services wired as flat `val`s in `AppContext.open()`) as a
candidate for grouping: splitting the wiring into small domain objects
(`OcrWiring`, `RevisionWiring`, `ImportWiring`, ...) so a new feature's wiring
touches ~100-150 lines instead of the whole file.

`AppContext.kt` already carries its own doc comment recording a deliberate
choice: *"Wiring is explicit — no dependency-injection framework — because
the object graph is small."* The file has kept growing since that comment was
written — most recently, splitting `LlmStore` into `LlmProfileStore`,
`ConversationStore` and `InvestigationLedgerStore` (ADR-adjacent cleanup, no
ADR) added two fields, and neither that change nor the `ApiFaultException`
exception-mapping cleanup needed to touch more than a couple of lines each.

## Decision

Keep `AppContext`'s wiring exactly as it is: flat `val`s, one file, no
grouping objects, no DI framework.

## Why

- The growth is a size trend, not a reported problem. No bug, confusion, or
  review comment has been attributed to the file's length; the two most
  recent additions landed without friction.
- Grouping now would reverse a decision the file itself documents as
  deliberate, not an oversight — doing that without a concrete driver is the
  kind of premature abstraction this project's own conventions warn against.
- The alternative (`Wiring` sub-objects) adds a layer of indirection — every
  reader now has to know both `AppContext` and which `*Wiring` object a given
  service lives in — to solve a problem that hasn't actually shown up yet.

## Consequences

- `AppContext.kt` will keep growing by a field or two per new backend
  feature; that's accepted, not treated as debt.
- Revisit this decision if either becomes true: `AppContext.kt`'s edit
  frequency climbs sharply relative to the rest of the backend (not just
  absolute line count), or a real bug or reviewer confusion is traced to
  navigating the file. Either is a concrete driver this ADR is missing today.
