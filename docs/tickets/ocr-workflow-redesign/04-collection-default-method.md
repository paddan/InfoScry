# 04: Collection stores language and a default method only

**Status:** Not started
**Blocked by:** None for logic. It uses `ReadingMethod.kt`, which CONTRACTS section 1 lets this ticket create verbatim if ticket 03 has not landed.
**Plan:** [OCR workflow redesign, Task 4](../../plans/2026-10-08-ocr-workflow-redesign.md). Read first: this ticket, [CONTRACTS.md](CONTRACTS.md) (sections 1, 3, 12, 13), `AGENTS.md`, the [spec](../../specs/2026-10-08-ocr-workflow-redesign.md) ("Collection settings").

## Problem

A collection stores engine, transcription profile, import mode, reviewer profile and
external allowance as separate fields.

## Deliverable

`CollectionOcrSettings(language, defaultMethod)`. Engine and profile columns are derived
from the method and written together. Import mode, reviewer profile and allowance are no
longer read or written. The PATCH route takes `{ language, defaultMethod }`.

## Can be built against

CONTRACTS section 1 (`ReadingMethod`) and section 3. Does not need the list route
(ticket 03) or any web ticket.

## Files and interfaces

- Modify `src/main/kotlin/infoscry/ocr/OcrModels.kt` (`CollectionOcrSettings`, `OcrSettingsSnapshot.of`), `src/main/kotlin/infoscry/storage/CollectionStore.kt` (:138, :163, :286-300), `src/main/kotlin/infoscry/server/Routes.kt` (`UpdateOcrLanguagesRequest` :101, route :345).
- Update every call site the compiler reports to use `ReadingMethod` instead of `engine` + `transcriptionProfileId`. The columns `ocr_import_mode`, `ocr_review_profile_id`, `ocr_external_page_limit` stay in `001_baseline.sql`, unread and unwritten (no migration script).
- Another ticket (07) edits `OcrModels.kt` (`OcrSettingsSnapshot.mode`) in different lines; keep your edits to the settings class and `of`.
- Tests `src/test/kotlin/infoscry/storage/CollectionStoreTest.kt`, `src/test/kotlin/infoscry/ocr/OcrSettingsTest.kt`.

## Test-first implementation

- [ ] Write failing tests: an old row with engine=LLM and no profile loads as the engine's local default method (no exception); saving a method writes engine and profile together; the PATCH route accepts `{ language, defaultMethod }` and rejects an unknown method with 400; saving twice is a no-op.
- [ ] Run `OcrSettingsTest` and `CollectionStoreTest`; expect FAIL.
- [ ] A database written by the shipped flow (engine/profile pair set, mode and reviewer set) opens and loads without error; re-saving it does not change the ignored columns' values.
- [ ] Implement the model change; fix call sites.
- [ ] Run `infoscry.ocr.*`, `infoscry.storage.*`, `infoscry.server.Collection*`; expect PASS.

## Focused verification

```sh
cd /Users/patrik/projects/infoscry
./gradlew test -PskipFrontend --tests 'infoscry.ocr.*' --tests 'infoscry.storage.*' --tests 'infoscry.server.Collection*'
./gradlew check
```

## Out of scope

The settings web UI (ticket 15); the list route (ticket 03); removing the import mode
from extraction (ticket 07); import and rescan start (tickets 06, 08).
