# 03: `ReadingMethod` and the list of available methods

**Status:** Not started
**Blocked by:** None.
**Plan:** [OCR workflow redesign, Task 3](../../plans/2026-10-08-ocr-workflow-redesign.md). Read first: this ticket, [CONTRACTS.md](CONTRACTS.md) (sections 1, 2, 12, 13), `AGENTS.md`, the [spec](../../specs/2026-10-08-ocr-workflow-redesign.md) ("Reading methods").

## Problem

Engine and profile are separate fields that can contradict each other, and the person
cannot see which methods can run now.

## Deliverable

`ReadingMethod` (one value for engine+profile), `MethodAvailability`, a
`ReadingMethodCatalog` that computes availability, and
`GET /api/collections/{id}/reading-methods` returning the list and the collection
default.

## Can be built against

Nothing else. The `default` field reads the collection's current stored engine/profile
and maps it to a method id with the existing columns (ticket 04 later makes the default
a stored method; the mapping rule is the same: local engine -> its method, LLM engine +
profile -> `llm:<profileId>`, LLM without profile -> null). If `ReadingMethod.kt` already
exists (another ticket created it per CONTRACTS section 1), reuse it unchanged.

## Files and interfaces

- Create `src/main/kotlin/infoscry/ocr/ReadingMethod.kt` (sealed interface, `parse`, serializer) and `ReadingMethodCatalog` (CONTRACTS section 1), `src/main/kotlin/infoscry/server/ReadingMethodRoutes.kt`.
- Modify `src/main/kotlin/infoscry/server/Routes.kt` to register the route.
- Use `OcrProfileService` (`keyAvailable`, capability record) and the engine availability already used by `RescanService.snapshotFor`.
- Tests `src/test/kotlin/infoscry/ocr/ReadingMethodTest.kt`, `src/test/kotlin/infoscry/server/ReadingMethodRoutesTest.kt`.

## Test-first implementation

- [ ] Write failing tests: parse/round-trip of all three ids and a bad id (the route returns 400 for a bad id where one is accepted; `parse` throws `IllegalArgumentException`); the route lists Tesseract and Surya with reasons when the tool/model is missing; an LLM profile with `external = true` and `destination` = its host; a disabled profile or an unset key variable as unavailable with a reason; an LLM profile that never passed its image check is unavailable with that reason.
- [ ] Run; expect FAIL.
- [ ] Idempotence test: calling the route twice returns identical bodies and writes nothing.
- [ ] Implement.
- [ ] Run both classes; expect PASS.

## Focused verification

```sh
cd /Users/patrik/projects/infoscry
./gradlew test -PskipFrontend --tests 'infoscry.ocr.ReadingMethodTest' --tests 'infoscry.server.ReadingMethodRoutesTest'
./gradlew check
```

## Out of scope

Storing a default method (ticket 04); using the catalog in preview or start (tickets 05,
06, 08); the web client (ticket 12).
