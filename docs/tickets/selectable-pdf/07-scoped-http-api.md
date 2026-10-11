# 07 — Scoped PDF status, creation and file routes

**Status:** Not started — documentation only, 2026-10-10.
**Blocked by:** 06 reviewed; 02 wire contract frozen.
**Read first:** [Spec](../../specs/2026-10-10-selectable-pdf.md), [plan](../../plans/2026-10-10-selectable-pdf.md), [shared contracts](CONTRACTS.md), [dispatch and gates](STATUS.md).

## Deliverable and ownership

Expose thin HTTP routes over the service with no generation side effects in GET.
Maps to the backend portion of plan Task 5.

**Own:** `src/main/kotlin/infoscry/server/SelectablePdfRoutes.kt`,
`Routes.kt` registration, `src/test/kotlin/infoscry/server/SelectablePdfRoutesTest.kt`.
**Consume:** 06 service and exact 02 DTO/HTTP contract.
**Do not edit:** web api.ts (08 owns it), service/store/schema or AppContext.
Request any missing injection from 06's owner through the coordinator.

## Acceptance

1. GET status returns exact revision identity and all documented states. Reading
   status/file repeatedly causes zero jobs, local alignment or provider calls.
2. POST create/retry uses existing auth/CSRF/mutation admission, validates fields
   and returns current status idempotently. Unknown/unpublished/foreign IDs cannot
   leak another collection's metadata or bytes.
3. File GET requires the matching key/revision and verified READY/PARTIAL artifact.
   Absent/foreign/unpublished → 404; malformed → 400; valid but unavailable/corrupt
   file → 409 safe code. Existing authentication semantics are unchanged.
4. application/pdf, exact Content-Length, verified-hash ETag, private cache policy
   and safe inline/attachment Content-Disposition. No paths/text/provider bodies
   in failures; symlink/ancestor traversal is rejected using existing confinement.
5. Historical A still returns A after rescan/Restore; a document deleted during
   generation cannot acquire a downloadable late file.

## Steps and checks

- [ ] Add failing routes tests for every vector, including duplicate POST and GET side effects.
- [ ] Implement thin service delegation and route registration, not another state machine.
- [ ] Exercise missing fields, malformed key/inline, header injection and corrupt bytes.
- [ ] Run `./gradlew test -PskipFrontend --tests infoscry.server.SelectablePdfRoutesTest`.
- [ ] Return example DTOs/status codes; coordinator reconciles them with 08's fakes.

No browser-native PDF view is added by this backend ticket. Page selection and
copying are verified on the actual output later, not inferred from HTTP 200.

## Completion evidence

Not run. Return owned diff, behavioral RED/GREEN commands/results, acceptance
vector outcomes, exact exported interfaces and remaining limits. Checkboxes are
future work, not proof. Coordinator review is required before unlocking dependents.
