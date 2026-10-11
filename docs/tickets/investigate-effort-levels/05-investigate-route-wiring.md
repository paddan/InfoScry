# Ticket 05 — Wire the effort dial into Investigate's request path

[Spec](../../specs/2026-10-11-investigate-effort-levels.md) ·
[Plan](../../plans/2026-10-11-investigate-effort-levels.md) · [Status](STATUS.md)

**Depends on:** Ticket 02 reviewed and merged (needs `EffortResolver.resolve`)
AND Ticket 03 reviewed and merged (needs `LlmRequest.reasoning`). Does not
depend on Ticket 04.

**Scope:** `InvestigationRoutes.kt`, `InvestigationService.kt`, and
`InvestigationBudgetPruner.kt` (complete request measurement/protected thinking groups). Do not
touch the admin routes/UI (ticket 04) or the frontend sidebar (ticket 06) —
this ticket only changes the wire contract and the service's internal
plumbing; ticket 06 is the one that updates the client that calls it.

**Note for whoever picks this up:** the plan originally drafted this ticket's
own `Started` event change against the route layer; re-deriving it found the
actual construction site is inside `InvestigationService.investigate` itself
(`InvestigationService.kt:238`), not in the routes file, and the `limits`
value already in scope there is exactly what's needed — no new value needs
threading through the route. Step 4 below reflects that; don't go looking for
a second `Started(...)` call site, there is only one.

---

### Task 5: Wire the effort dial into Investigate's request path

**Files:**
- Modify: `src/main/kotlin/infoscry/server/InvestigationRoutes.kt:43-53` (`InvestigationLimitsRequest`), `:233-260`, `:276-298` (request handling)
- Modify: `src/main/kotlin/infoscry/investigate/InvestigationService.kt:713` (`candidateRequest`)
- Modify: `src/main/kotlin/infoscry/investigate/InvestigationBudgetPruner.kt`
- Test: `src/test/kotlin/infoscry/investigate/InvestigationBudgetPrunerTest.kt`
- Test: `src/test/kotlin/infoscry/investigate/InvestigationServiceTest.kt`
- Test: `src/test/kotlin/infoscry/server/InvestigationRoutesTest.kt`

**Interfaces:**
- Consumes: `EffortResolver.resolve` (Ticket 02), `NativeReasoning` on `LlmRequest` (Ticket 03).
- Produces: `InvestigationLimitsRequest.effortLevel: EffortLevel = EffortLevel.MEDIUM` (replacing the three numeric fields); the start-of-turn SSE event gains a `resolvedLimits: InvestigationLimits` field.

- [ ] **Step 1: Write failing route tests**

```kotlin
@Test fun `create with no limits body resolves MEDIUM against the chosen profile`() {
    val response = postInvestigateCreate(collection, question, profile, body = """{"collection":"$collection","question":"$question"}""")
    val startEvent = firstEventOf(response)
    assertEquals(InvestigationLimits(50, 50, 600), startEvent.resolvedLimits)
}

@Test fun `create with effortLevel LOW resolves the profiles override when one is set`() {
    val overriddenProfile = createTestProfile(effortOverrides = mapOf(EffortLevel.LOW to EffortPreset(InvestigationLimits(3, 3, 15))))
    val response = postInvestigateCreate(collection, question, overriddenProfile, body = """{"collection":"$collection","question":"$question","limits":{"effortLevel":"LOW"}}""")
    assertEquals(InvestigationLimits(3, 3, 15), firstEventOf(response).resolvedLimits)
}

@Test fun `an unrecognized effortLevel value returns 400, not 500`() {
    val response = postInvestigateCreate(collection, question, profile, body = """{"collection":"$collection","question":"$question","limits":{"effortLevel":"NOT_A_LEVEL"}}""")
    assertEquals(HttpStatusCode.BadRequest, response.status)
}
```

Read actual SSE JSON returned by HTTP, not only the internal event. Cover start AND continue, omitted limits (MEDIUM), each explicit level, profile overrides, and invalid enums. Assert `resolvedLimits` appears on `started` and is absent from unrelated events.

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test -PskipFrontend --tests infoscry.server.InvestigationRoutesTest`
Expected: FAIL (`InvestigationLimitsRequest` still has the three numeric fields, no `resolvedLimits` on the start event).

- [ ] **Step 3: Replace `InvestigationLimitsRequest`'s fields and resolve at both call sites**

```kotlin
@Serializable
data class InvestigationLimitsRequest(val effortLevel: EffortLevel = EffortLevel.MEDIUM)
```

Remove `toLimits()`. At each of the two sites building `InvestigateRequest` (lines ~233–260 and ~276–298), replace `body.limits.toLimits()` with `EffortResolver.resolve(profile, body.limits.effortLevel)`, pass `.limits` into `InvestigateRequest(limits = ...)` as before, and keep the resolved `EffortPreset` in scope to attach to the start event.

- [ ] **Step 4: Add `resolvedLimits` to the start-of-turn SSE event payload**

Add `val resolvedLimits: InvestigationLimits` to `InvestigateEvent.Started` (`InvestigationService.kt:63`: `data class Started(val conversationId: String, val resolvedLimits: InvestigationLimits) : InvestigateEvent`). The only construction site is `InvestigationService.kt:238` (`emit(InvestigateEvent.Started(conversationId))`); change it to `emit(InvestigateEvent.Started(conversationId, limits))` — `limits` (the validated `request.limits`, already in scope at line 196) is exactly the `EffortPreset.limits` the route resolved in Step 3 and put into `InvestigateRequest.limits`, so no new value needs to be threaded through the route.

Also add nullable `resolvedLimits: InvestigationLimits? = null` to `InvestigateWire` in `InvestigationRoutes.kt` and map `InvestigateEvent.Started.resolvedLimits` in `toWire()`. Configure omission for null values even if the route's serializer emits defaults; preserve every other event's wire shape. Ticket 01 supplies the limits serializer. Test the HTTP serialization boundary explicitly.

- [ ] **Step 5: Thread the resolved `NativeReasoning` into `InvestigationService`**

`InvestigateRequest` (`InvestigationService.kt:30-37`) gains `val reasoning: NativeReasoning = NativeReasoning.None`; both routes in Step 3 pass the resolved `EffortPreset.reasoning`. In the round-building code at `InvestigationService.kt:713`, change `candidateRequest` to `LlmRequest(pruned.messages, tools.definitions, maxOutputTokens = profile.maxOutputTokens, reasoning = reasoning)` (using the request-scoped field, not the synthesis request at line 602 or the citation-correction request at line 453 — native reasoning applies only to the per-round tool-calling decision, never to those two).

Implement Ticket 03's Anthropic tool-loop protocol gate here: collect the completed envelope, retain it in request-local assistant messages, and protect the required current loop from pruning. Extend `pruneToFit` to measure the same reasoning/envelope-bearing `LlmRequest` that is sent, with default `None` for existing callers. Add the protocol/service/pruner regression cases required in Ticket 03 before calling this ticket complete. Keep round/call/time enforcement and citation validation unchanged; synthesis/correction remain separate None requests with no opaque blocks.

- [ ] **Step 6: Run tests to verify they pass**

Run: `./gradlew test -PskipFrontend --tests infoscry.server.InvestigationRoutesTest --tests infoscry.investigate.InvestigationServiceTest --tests infoscry.investigate.InvestigationBudgetPrunerTest`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/infoscry/investigate/InvestigationBudgetPruner.kt src/test/kotlin/infoscry/investigate/InvestigationBudgetPrunerTest.kt src/test/kotlin/infoscry/investigate/InvestigationServiceTest.kt src/main/kotlin/infoscry/server/InvestigationRoutes.kt src/main/kotlin/infoscry/investigate/InvestigationService.kt src/test/kotlin/infoscry/server/InvestigationRoutesTest.kt
git commit -m "feat: resolve Investigate effort level into limits and native reasoning per request"
```

## Handoff

Report back: confirmation all three test classes pass (including unchanged enforcement regressions in
`InvestigationServiceTest`), and the exact
new SSE `Started` event shape (field name and JSON key). Ticket 06 needs that
exact shape to build the frontend hint against.
