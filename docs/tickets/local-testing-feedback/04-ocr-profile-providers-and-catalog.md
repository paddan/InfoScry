# 04: OCR profiles offer the LLM providers and only image-capable models

**Status:** Implemented and verified by the focused backend and frontend tests below; the browser scenario is not done. Unchecked criteria are requirements, not evidence.
**Blocked by:** None.
**Plan:** [Local testing feedback](../../plans/2026-10-07-local-testing-feedback.md).

## Problem

Admin → OCR profiles has no provider presets or model catalog, unlike LLM profiles, so a fresh archive has no OCR
profiles and the collection's Transcription and Review profile selects offer only `None`.

## Deliverable

Creating an OCR profile works like creating an LLM profile: the same provider presets and the same form, with the
model list limited to models that accept image input. In addition, a collection's Transcription and Review profile
selects offer existing LLM profiles whose model supports image input, next to the OCR profiles (owner decision:
both own OCR profiles and existing LLM profiles).

## Files and interfaces

- `src/main/kotlin/infoscry/server/LlmCatalogRoutes.kt` (presets, live catalog) — reuse; add an image-input filter
  where the catalog states it. Where a provider's catalog does not state image support, list the model as
  "image support unknown" and rely on the existing image probe rather than guessing.
- `web/src/lib/OcrProfilesPanel.svelte` — reuse the LLM profile form's provider/preset/catalog behaviour
  (`web/src/lib/LlmAdminPanel.svelte`); extract a shared component rather than copying it.
- Selecting an LLM profile: the attempt snapshot must pin an immutable revision of whatever profile was chosen,
  as it does for OCR profiles today (`OcrSettingsSnapshot`, `ocr_profile_revisions`). Decide and record whether an
  LLM profile is referenced by its own revision, or copied into an OCR profile revision when selected; either way a
  later edit of the LLM profile must not change an admitted attempt, and image capability is measured (or probed)
  per model before it can be selected, as for OCR profiles.
- `web/src/lib/CollectionOcrSettings.svelte` — the selects list OCR profiles and image-capable LLM profiles in two
  labelled groups; when neither exists, say so and link to Admin → OCR profiles instead of an unexplained `None`.

## Decision: an LLM profile is copied into an OCR profile revision

Choosing an LLM profile for transcription or review **copies it into an OCR profile** (`POST /api/ocr/profiles/from-llm`),
named `<LLM profile name> (from LLM profile)`, and the collection stores that OCR profile's id. Reason: the snapshot,
`ocr_profile_revisions`, the per-revision image check, admission and resume already work for OCR profiles, so
nothing in the attempt path changes and no second revision model is added. Referencing an LLM profile revision would
need LLM profiles to become revisioned (they are edited in place) plus a second branch in every consumer of the
snapshot. Choosing the same LLM profile again reuses its copy and adds a revision only when the LLM profile changed
since; an admitted attempt keeps the revision it pinned either way. The copy starts unmeasured and the existing
image check runs on it, as for any OCR profile. A model the curated catalog states is text-only is refused with 409
`LLM_PROFILE_TEXT_ONLY`; a model it does not state is allowed and labelled "image support unknown". The copy is
linked to its LLM profile by `ocr_profiles.source_llm_profile_id` (a unique partial index allows at most one copy per
LLM profile), and reuse, refresh and the collection selects' remapping use that id, never the name. Renaming the LLM
profile keeps the link and renames the copy on the next choice; an unrelated OCR profile that happens to carry the
derived name is not adopted (creating the copy then fails with a 409 name conflict and the unrelated profile is left
alone). The column is deliberately not a foreign key: if the LLM profile is deleted, the copy stays selectable, the id
dangles, and admitted attempts are unaffected because they pin the copy's revision.

Image support is stated by the provider's own listing (`architecture.input_modalities` or `architecture.modality`)
when it has one, and otherwise by `imageInput` in `src/main/resources/llm/providers.json` (true for the curated OpenAI
and Anthropic models, false for `deepseek-chat` and `deepseek-reasoner`). Everything else is unknown.

## Test-first implementation

- [x] Failing tests first (9 backend and 9 frontend tests failed before the implementation): the OCR profile form offers the same presets as the LLM form; the model list excludes
  models the catalog marks as text-only; unknown support is labelled, not hidden or assumed; an image-capable LLM
  profile can be selected for transcription and review and a text-only one cannot; editing that LLM profile after
  admission does not change the admitted attempt; the collection selects explain an empty list; keys are shown by
  presence only.
- [x] Implement with a shared form component (`ProviderModelFields.svelte`, used by both the LLM and OCR forms); no duplicated provider logic.
- [ ] Extend the OCR profiles browser scenario. Not done: the Playwright `externalTest` was not run or extended.

## Focused verification

```sh
./gradlew test -PskipFrontend --tests 'infoscry.server.LlmCatalogRoutesTest' --tests 'infoscry.server.OcrProfileRoutesTest'
(cd web && npm test -- --run && npm run check)
```
