# 04: OCR profiles offer the LLM providers and only image-capable models

**Status:** Not started. Unchecked criteria are requirements, not evidence.
**Blocked by:** None.
**Plan:** [Local testing feedback](../../plans/2026-10-07-local-testing-feedback.md).

## Problem

Admin → OCR profiles has no provider presets or model catalog, unlike LLM profiles, so a fresh archive has no OCR
profiles and the collection's Transcription and Review profile selects offer only `None`.

## Deliverable

Creating an OCR profile works like creating an LLM profile: the same provider presets and the same form, with the
model list limited to models that accept image input. OCR profiles stay separate from LLM profiles (owner decision).

## Files and interfaces

- `src/main/kotlin/infoscry/server/LlmCatalogRoutes.kt` (presets, live catalog) — reuse; add an image-input filter
  where the catalog states it. Where a provider's catalog does not state image support, list the model as
  "image support unknown" and rely on the existing image probe rather than guessing.
- `web/src/lib/OcrProfilesPanel.svelte` — reuse the LLM profile form's provider/preset/catalog behaviour
  (`web/src/lib/LlmAdminPanel.svelte`); extract a shared component rather than copying it.
- `web/src/lib/CollectionOcrSettings.svelte` — when no OCR profile exists, say so and link to Admin → OCR profiles
  instead of an unexplained `None`.

## Test-first implementation

- [ ] Failing tests first: the OCR profile form offers the same presets as the LLM form; the model list excludes
  models the catalog marks as text-only; unknown support is labelled, not hidden or assumed; the collection selects
  explain an empty list; keys are shown by presence only.
- [ ] Implement with a shared form component; no duplicated provider logic.
- [ ] Extend the OCR profiles browser scenario.

## Focused verification

```sh
./gradlew test -PskipFrontend --tests 'infoscry.server.LlmCatalogRoutesTest' --tests 'infoscry.server.OcrProfileRoutesTest'
(cd web && npm test -- --run && npm run check)
```
