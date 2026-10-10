# Wide workspace and page review implementation plan

> **For agentic workers:** Use superpowers:executing-plans for inline implementation, or superpowers:subagent-driven-development only if the owner chooses delegation. Follow the tickets task by task; criteria remain unchecked until verified.

**Goal:** Let the owner check import/reading, compare page images with extracted text and reread chosen pages with Surya or an LLM, using available space across the application.

**Architecture:** A common adaptive workspace hosts context and source panes; a separate page-review service/component binds images and text to exact revisions. Explicit page selection extends the existing durable OCR preview, operation and publication protocol. The compact list remains a separate deliverable feeding this reader.

**Tech Stack:** Kotlin/JVM 25, SQLite, TypeScript/SvelteKit, existing Vitest and Playwright harnesses.

**Spec:** [Wide workspace and page review](../specs/2026-10-09-workspace-and-page-review.md).
**Existing plan:** [Compact document list](../tickets/compact-document-list/STATUS.md).

Documentation and subsequent implementation with agents authorized 2026-10-09.
Follow the written spec and tickets, preserving the existing working-tree changes.

## Global constraints

- Kotlin/JVM 25; TypeScript/SvelteKit static frontend; one application process.
- SQLite authority; existing loopback, bearer/CSRF, collection scoping and lifecycle guards.
- Preserve immutable sources, stable unit IDs, pinned dependencies and unrelated edits.
- English product copy, ordinary accessible HTML/CSS; no new UI dependency.
- Keep 50-row filename-only listing and existing Retry/deletion semantics.
- Session UI memory only, no persistent preference store or hidden destructive selection.
- Automatic OCR selection remains default; explicit selections override its heuristic.
- Only confirmed available LLM profiles; exact external destination/pages approved at start.
- Current text changes only at successful complete publication; old text stays in history.
- Fresh schema only; no existing-data migration/conversion or owner database reset.
- No real provider/private-data tests, CPU embedding fallback or GPU claims from fakes.

## Work packages and order

See [ticket status](../tickets/workspace-and-page-review/STATUS.md) for individual
files, interface contracts, acceptance vectors, steps and commands.

1. Common wide workspace (01); then compact-list 02 → 03 → 04.
2. Adaptive source/history layout (02), building on workspace 01.
3. Page-review API/provenance (03); can be developed independently of layout.
4. Side-by-side review (04), after source layout 02 and API 03.
5. Durable explicit rescan selection (05), after page contract 03.
6. Page marking and start confirmation (06), after reader 04 and backend 05.
7. Integrated browser acceptance/docs (07), after 06 and compact-list 04.

Delegation is authorized. Page API 03 and layout work have independent behavior
but share api.ts/page wiring with later tickets; sequence implementation and assign
ownership to avoid overlapping edits. Deliver each ticket with its own meaningful
verification and independent review.

## File responsibilities

- `workspace.css`: common layout spacing and readable text/form limits.
- `SourceWorkspace.svelte`: adaptive pane layout, history interaction and focus.
- `PageReviewService.kt` / `PageReviewRoutes.kt`: exact revision/page text and
  original/OCR image resolution with scoped IDs and bounded resources.
- `PageReview.svelte`: page navigation, image/text fetch state, zoom/divider and
  session-only page marks. No publication logic.
- Existing OCR routes/service/store/job: preview, frozen explicit selection,
  dispatch/checkpoints and atomic complete-revision publication.
- `StartReadingDialog.svelte`: one confirmed page set/method/destination.
- Compact-list components: overview/search/inline metadata and entry to reader.

## Review focus

- Clean-looking text layer may still be wrong: explicit page scan must override
  heuristics; backend 05 and UI 06 test this exact case.
- Publication/page changes between requests must not mix image/text revisions;
  API 03 and reader 04 test delayed reads and superseded revisions.
- Preserved pages have earlier provenance: API 03 and backend 05 test attribution
  instead of showing the latest operation method for every page.
- Wide windows with history and sources can still squeeze content: layout 02 and
  acceptance 07 measure actual available width and keyboard behavior.
- A crash or uncertain confirmation must not lose selection or resend completed
  OCR: backend 05 and dialog 06 test persisted selection and identical retries.

## Shared verification and stop conditions

Run meaningful failing tests first, focused passing tests on the changed side,
then `./gradlew check` per implementation ticket. Frontend changes also run
`npm run check` and `npm run build` from web. Browser geometry and complete flows
belong to 07; existing affected browser suites must keep passing.

Stop dependent work on wrong revision pairing, selected/unselected data loss,
duplicate dispatch after recovery, hidden selections or incorrect external-page
approval. Report provider/GPU infrastructure separately. Real OCR quality, complete
CoreML workflow, native picker and human accessibility gates stay open until run.
Do not start the owner server, recreate their database, commit or push as part of
implementation. The owner's execution instruction does not authorize those actions.

Documentation checks: local links, fences, interface/state consistency, dependency
cycles, spec coverage and diff whitespace. These do not establish application
behavior or close pre-existing manual/format-specific source-viewer gates.
