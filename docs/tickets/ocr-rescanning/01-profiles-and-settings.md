# 01: OCR profiles and immutable attempt settings

**Status:** Done (2026-09-30). Migration 017 adds `ocr_profiles`, `ocr_profile_revisions` and the
collection OCR settings columns; `GET/POST /api/ocr/profiles` and `PATCH/DELETE
/api/ocr/profiles/{profileId}` create immutable revisions and disable a profile without deleting its
history. Verified by the focused suite (67 tests: `OcrSettingsTest` 19, `OcrProfileRoutesTest` 10,
`CollectionRoutesTest` 29, `SchemaMigratorTest` 9; 0 failed) and `./gradlew check` (green, 13m45s).
Legacy archives keep their language, Tesseract and fill-missing behavior, and the legacy extraction
fingerprints are byte-identical (two pinned digests re-derived from the pre-change canonical form by
hand). An independent review pass found and fixed three gaps before those gates: a snapshot read its two
profile revisions in two transactions, endpoint validation accepted non-http schemes and hostless URLs,
and unknown body fields were dropped instead of refused.

Caveats for the tickets that follow: the failing-first run is evidenced in the implementing agent's run
transcript, not in the diff; automatic replacement has no enable path to bind yet, so "policy disabled"
is vacuous until ticket 11 accepts a validation record; redirect refusal belongs to the image-transport
client, which arrives with ticket 05. Unchecked boxes below stay requirements for later work.
**Blocked by:** None.
**Spec:** [Selectable OCR and rescanning](../../specs/2026-09-30-ocr-rescanning.md).
Read [shared contracts and gates](CONTRACTS.md) before implementation.

## Deliverable

Users can manage separate OCR profiles through scoped Admin APIs and persist collection engine/mode/reviewer defaults without changing Ask/Investigate.

## Files and interfaces

- `src/main/kotlin/infoscry/ocr/OcrModels.kt (new shared serializable contracts)`
- `src/main/kotlin/infoscry/ocr/OcrProfileService.kt (new)`
- `src/main/kotlin/infoscry/storage/OcrProfileStore.kt (new)`
- `src/main/kotlin/infoscry/collection/CollectionService.kt`
- `src/main/kotlin/infoscry/extract/DocumentExtractor.kt`
- `src/main/kotlin/infoscry/server/OcrProfileRoutes.kt (new)`
- `src/main/kotlin/infoscry/AppContext.kt`
- `src/main/resources/db/migration/ (new forward migration)`

Produce OcrSettingsSnapshot and profile revisions from CONTRACTS.md; extraction and review fingerprints are distinct. Consumers never resolve a mutable profile during resume.

**Tests:** Create src/test/kotlin/infoscry/ocr/OcrSettingsTest.kt and src/test/kotlin/infoscry/server/OcrProfileRoutesTest.kt; extend CollectionRoutesTest and migration tests.

## Test-first implementation

- [x] Add meaningful failing tests for the following observable scenarios before changing production behavior:
  - Legacy database and legacy queued job reopen unchanged.
  - A queued snapshot still references profile revision A after the profile is changed to B; a new job uses B.
  - Invalid/foreign IDs, malformed URLs, negative allowance and secret-bearing payloads are rejected without mutation or logs.
  - Changing reviewer settings invalidates review reuse but not transcription; changing transcription prompt invalidates transcription.
- [x] Run the focused command below and record the expected behavioral failure. For an external test, first prove missing/invalid-runtime diagnostics using the fake transport; do not download private fixtures.
- [x] Migrate existing collections to TESSERACT and FILL_MISSING with unchanged language; externalPageLimit=0, no reviewer and auto policy disabled. Old queued payloads decode with legacy semantics.
- [x] Validate engine/profile combinations, nonnegative finite prices and token limits, valid environment variable names, endpoint schemes and local/external classification. Referenced profile revisions survive edits/deletion; disabling a profile prevents new work.
- [x] Store no secret value. Return key availability only. Snapshot settings and profile revisions atomically with admission; classify endpoint redirects without leaking requests.
- [x] Add separate OCR-profile CRUD routes and collection settings fields; do not overload Ask/Investigate role defaults. Record unavailable reviewer explicitly rather than selecting an external default.
- [x] Run focused tests green, then the accumulated gates in CONTRACTS.md. Record failures and environment blockers separately.
- [x] Review the diff against the spec and update STATUS.md with verified behavior and remaining gates. Commit/push only under the user's separately authorized Git workflow.

## Focused verification

```sh
./gradlew test --tests 'infoscry.ocr.OcrSettingsTest' --tests 'infoscry.server.OcrProfileRoutesTest' --tests 'infoscry.server.CollectionRoutesTest'
```

New test classes above are planned paths, not existing tests. A successful fake-provider run does not satisfy a real-tool or hardware gate.
