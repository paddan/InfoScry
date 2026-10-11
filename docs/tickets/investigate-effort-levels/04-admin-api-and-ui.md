# Ticket 04 — Admin LLM profiles API and UI for effort overrides

[Spec](../../specs/2026-10-11-investigate-effort-levels.md) ·
[Plan](../../plans/2026-10-11-investigate-effort-levels.md) · [Status](STATUS.md)

**Depends on:** Ticket 02 reviewed and merged (needs `LlmProfile.effortOverrides`
and its validation). Does not depend on Ticket 03 and may run in parallel with
it and with Ticket 05 once Ticket 02 is in.

**Scope:** `LlmProfileRoutes.kt`, `web/src/lib/api.ts` profile/shared effort types,
and `web/src/lib/LlmAdminPanel.svelte`. Ticket 06 edits `api.ts` only after this ticket lands.
Do not touch `InvestigationRoutes.kt`, `InvestigationService.kt`, or the
Investigate sidebar (`investigationLimits.ts`) — those are ticket 05/06. This
ticket only lets an admin set/clear overrides on a profile; nothing in
Investigate's own request path reads them until ticket 05 lands.

---

### Task 4: Admin LLM profiles API and UI for effort overrides

**Files:**
- Modify: `src/main/kotlin/infoscry/server/LlmProfileRoutes.kt:22-35` (`LlmProfileRequest`), `:37-...` (`LlmProfileApiView`), `:199-218` (`toProfile`), `:220-236` (`toApiView`)
- Modify: `web/src/lib/api.ts` (profile request/view types plus shared effort types)
- Test: `web/src/lib/api.test.ts` (profile create/update serialization and responses)
- Modify: `web/src/lib/LlmAdminPanel.svelte`
- Test: `src/test/kotlin/infoscry/server/LlmProfileRoutesTest.kt`
- Test: `web/src/lib/LlmAdminPanel.test.ts`

**Interfaces:**
- Consumes: `EffortLevel`, `EffortPreset`, `NativeReasoning` (Ticket 01); `LlmProfile.effortOverrides` (Ticket 02).
- Produces: `LlmProfileRequest.effortOverrides: Map<EffortLevel, EffortPreset>? = null`; `LlmProfileApiView.effortOverrides: Map<EffortLevel, EffortPreset>? = null`; the same wire shape `web/src/lib/LlmAdminPanel.svelte` sends/receives.

- [ ] **Step 1: Write a failing route test for saving and clearing an override**

```kotlin
@Test fun `PUT profile with an effort override persists and returns it`() {
    val created = createTestProfile(provider = "ANTHROPIC", maxOutputTokens = 4096)
    val response = client.put("/api/llm/profiles/${created.id}") {
        setBody(createdRequestJsonWith("""
            "effortOverrides": {"HIGH": {"limits": {"maxToolRounds": 50, "maxToolCalls": 75, "maxTurnSeconds": 1200}, "reasoning": {"type": "AnthropicThinkingBudget", "tokens": 2048}}}
        """))
    }
    assertEquals(HttpStatusCode.OK, response.status)
    val view = response.body<LlmProfileApiView>()
    assertEquals(2048, (view.effortOverrides?.get(EffortLevel.HIGH)?.reasoning as NativeReasoning.AnthropicThinkingBudget).tokens)
}

@Test fun `PUT profile with an Anthropic thinking budget at or above maxOutputTokens returns 400`() {
    val created = createTestProfile(provider = "ANTHROPIC", maxOutputTokens = 1024)
    val response = client.put("/api/llm/profiles/${created.id}") {
        setBody(createdRequestJsonWith("""
            "effortOverrides": {"HIGH": {"limits": {"maxToolRounds": 1, "maxToolCalls": 1, "maxTurnSeconds": 10}, "reasoning": {"type": "AnthropicThinkingBudget", "tokens": 1024}}}
        """))
    }
    assertEquals(HttpStatusCode.BadRequest, response.status)
}

@Test fun `PUT profile omitting effortOverrides after it was previously set clears it to null`() {
    val created = createTestProfile(effortOverrides = mapOf(EffortLevel.LOW to EffortPreset(InvestigationLimits(5, 5, 30))))
    val response = client.put("/api/llm/profiles/${created.id}") { setBody(createdRequestJsonWithout("effortOverrides")) }
    assertEquals(null, response.body<LlmProfileApiView>().effortOverrides)
}
```

Test both POST and PUT rejection for every invalid cap/native-reasoning case from Ticket 02, and verify failed updates leave the saved profile unchanged. Assert exact JSON round trips, including clearing, for every reasoning discriminator frozen in Ticket 01.

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test -PskipFrontend --tests infoscry.server.LlmProfileRoutesTest`
Expected: FAIL (`LlmProfileRequest`/`LlmProfileApiView` have no `effortOverrides` field).

- [ ] **Step 3: Add the field to both DTOs and both mapping functions**

Add `val effortOverrides: Map<EffortLevel, EffortPreset>? = null` to `LlmProfileRequest` and to `LlmProfileApiView`. Pass it through in `toProfile` and `toApiView` exactly like every other field (no transformation). The existing `catch (_: IllegalArgumentException) { throw BadRequestException(...) }` in `toProfile` already turns the Ticket 02 `init` validation failure into the required 400.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test -PskipFrontend --tests infoscry.server.LlmProfileRoutesTest`
Expected: PASS

- [ ] **Step 5: Write failing Vitest tests for the admin editor**

```ts
test('shows four effort-level rows with the resolved global defaults when no override is set', () => { /* render, assert LOW/MEDIUM/HIGH/XHIGH rows show 10/15/120, 50/50/600, 50/75/1200, 50/100/1800 */ });
test('an Anthropic profile shows a token-budget input; an OpenAI-compatible profile shows a low/medium/high select', () => { /* render each provider, assert control shape */ });
test('editing a row and saving sends only that levels override in the PUT body', () => { /* fill HIGH row, save, assert fetch body shape */ });
test('clicking Clear on a row reverts its displayed values to the global default and removes it from the next PUT body', () => { /* set override, clear, save, assert body omits that level */ });
```

- [ ] **Step 6: Run tests to verify they fail**

Run: `cd web && npm test -- --run LlmAdminPanel`
Expected: FAIL (no effort-levels section rendered yet).

Define shared `EffortLevel`, `ResolvedInvestigationLimits`, `NativeReasoning` (exact Ticket 01 discriminator strings) and `EffortPreset` types in `api.ts`. Add nullable sparse `effortOverrides` to profile create/update requests and returned profile types; test real client serializers, not only component fetch mocks. Ticket 06 imports these types with `import type`, avoiding an api/storage runtime cycle.

- [ ] **Step 7: Implement the "Effort levels" section in `LlmAdminPanel.svelte`**

Four rows keyed by `EffortLevel`, each pre-filled from `profile.effortOverrides?.[level] ?? GLOBAL_DEFAULTS[level]` (mirror the Ticket 01 Kotlin table as a TS constant alongside the component — the exact values are in Ticket 01). Each row has rounds/calls/seconds number inputs and, when `profile.provider === 'ANTHROPIC'`, a token-budget number input, or when `'OPENAI_COMPATIBLE'`, a low/medium/high select; a "Clear" control per row removes that level's key from the payload sent on save rather than sending it as null.

- [ ] **Step 8: Run tests to verify they pass**

Run: `cd web && npm test -- --run && npm run check`
Expected: PASS

- [ ] **Step 9: Commit**

```bash
git add web/src/lib/api.ts web/src/lib/api.test.ts src/main/kotlin/infoscry/server/LlmProfileRoutes.kt web/src/lib/LlmAdminPanel.svelte src/test/kotlin/infoscry/server/LlmProfileRoutesTest.kt web/src/lib/LlmAdminPanel.test.ts
git commit -m "feat: admin UI and API for per-profile Investigate effort overrides"
```

## Handoff

Report back: confirmation both the Kotlin and Vitest suites pass, and the
exact wire shape `effortOverrides` round-trips as (map keyed by level name,
`reasoning.type` discriminator values). Ticket 07 needs this finished before
its acceptance scenario exercising a native-reasoning override can run.
