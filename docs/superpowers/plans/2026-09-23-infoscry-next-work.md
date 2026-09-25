# InfoScry: remaining local work

**Goal:** finish the local, single-user reader workflow. Build and run on this Mac; do not add CI, packaging, release machinery, web administration, or another authentication layer.

The backend, CLI, collection search, bounded source text/original access, Ask, and Investigate UI are implemented. `./gradlew check`, `externalTest`, and the real CoreML `gpuIntegrationTest` passed on 2026-09-24. A disposable fixture imported via CLI and was found through keyword, semantic, and hybrid search in the built browser UI. These facts are verification context, not additional tasks.

## 1. Decide the source-viewer finish line

The current viewer opens the exact extracted source unit and its locator, with an original-file link. The [design specification](../specs/2026-09-20-infoscry-design.md) still calls for PDF.js, OCR overlays, sanitized section rendering, table highlights, and slide previews. The user has not yet decided whether those format-specific views remain necessary for this local project.

- [ ] Ask for that one scope decision before calling source viewing complete or rewriting the specification.
- [ ] If the simple viewer is enough, align the specification and check an original-PDF page jump. If rich views remain required, split them by format and implement only the chosen formats with focused tests.

## 2. Exercise Ask and Investigate end to end in the browser

Use a disposable data directory, redistributable fixture, and a local fake OpenAI-compatible endpoint. Do not send private documents to a paid or external model. Keep import/profile setup in the CLI.

- [ ] Ask: set a disposable default profile, verify streaming text, usage/cost, valid citation navigation, error state, and a browser reload.
- [ ] Investigate: verify create, follow-up, saved-history reload, cancellation, activity, cost, and source links; confirm a duplicate concurrent follow-up is rejected without another provider call.
- [ ] Fix only defects exposed by those flows, then rerun focused tests and `./gradlew check`.

## 3. Finish a short local run guide

- [ ] Put only tested build, import, serve, model, and Tesseract commands in `README.md` and refresh `docs/implementation-status.md` with the final observed limitations.
- [ ] Confirm the fixture original is unchanged and the temporary server is stopped. No installer, CI, signing, or release checklist is needed.

**Done when:** the selected source-viewer scope is met, Ask and Investigate work through the built browser UI with fake responses, and the short run guide matches what was actually tested on this Mac.
