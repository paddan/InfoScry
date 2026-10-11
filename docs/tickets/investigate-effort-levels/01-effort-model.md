# Ticket 01 — Effort model

[Spec](../../specs/2026-10-11-investigate-effort-levels.md) ·
[Plan](../../plans/2026-10-11-investigate-effort-levels.md) · [Status](STATUS.md)

**Depends on:** nothing. This is the contract gate — every other ticket consumes
the types defined here, so this one lands and gets reviewed before any other
ticket starts.

**Scope:** new `EffortLevel.kt`, the serialization annotation on existing
`InvestigationLimits.kt`, and their tests. Keep limit bounds, defaults and enforcement unchanged. Do not touch `LlmProfile`, `LlmStore`, the LLM request builders,
`InvestigationRoutes`/`InvestigationService`, or anything under `web/`. In
particular, do **not** implement `EffortResolver` here — it needs
`LlmProfile.effortOverrides`, which ticket 02 adds; attempting it in this
ticket will not compile.

---

### Task 1: Effort model

**Files:**
- Create: `src/main/kotlin/infoscry/investigate/EffortLevel.kt`
- Modify: `src/main/kotlin/infoscry/investigate/InvestigationLimits.kt` (add `@Serializable`)
- Test: `src/test/kotlin/infoscry/investigate/EffortDefaultsTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  @Serializable enum class EffortLevel { LOW, MEDIUM, HIGH, XHIGH }

  @Serializable sealed class NativeReasoning {
      @Serializable object None : NativeReasoning()
      @Serializable data class AnthropicThinkingBudget(val tokens: Int) : NativeReasoning()
      @Serializable data class OpenAiReasoningEffort(val value: String) : NativeReasoning() // "low" | "medium" | "high"
  }

  @Serializable data class EffortPreset(val limits: InvestigationLimits, val reasoning: NativeReasoning = NativeReasoning.None)

  object EffortDefaults {
      fun forLevel(level: EffortLevel): EffortPreset
  }
  ```
- Produces a serializable `InvestigationLimits` as well: `EffortPreset` cannot generate a serializer while its nested limits type is non-serializable. Add the annotation/import without changing validation or defaults.
- Freeze explicit `@SerialName` values `None`, `AnthropicThinkingBudget`, and `OpenAiReasoningEffort` for the sealed subclasses; all stored/API JSON and TypeScript use these exact discriminator strings.
- Note: `EffortResolver.resolve(profile, level)` — the function that consults `LlmProfile.effortOverrides` before this global table — is NOT part of this task. It is implemented in Ticket 02, immediately after `effortOverrides` exists on `LlmProfile`; building it here would not compile, since `LlmProfile` has no such field yet.

**Global values this ticket must use exactly** (from the plan's Global Constraints — same for every provider, `reasoning = NativeReasoning.None` at every level):

| Level  | maxToolRounds | maxToolCalls | maxTurnSeconds |
|--------|---------------|--------------|-----------------|
| LOW    | 10            | 15           | 120             |
| MEDIUM | 50            | 50           | 600             |
| HIGH   | 50            | 75           | 1200            |
| XHIGH  | 50            | 100          | 1800            |

- [ ] **Step 1: Write failing tests for `EffortDefaults.forLevel`**

```kotlin
@Test fun `medium matches todays existing defaults`() {
    val preset = EffortDefaults.forLevel(EffortLevel.MEDIUM)
    assertEquals(InvestigationLimits(50, 50, 600), preset.limits)
    assertEquals(NativeReasoning.None, preset.reasoning)
}

@Test fun `every level resolves within InvestigationLimits bounds and defaults to no native reasoning`() {
    EffortLevel.entries.forEach { level ->
        val preset = EffortDefaults.forLevel(level)
        preset.limits.validate() // must not throw
        assertEquals(NativeReasoning.None, preset.reasoning)
    }
}

@Test fun `low is tighter than medium is tighter than high is tighter than xhigh`() {
    val (low, medium, high, xhigh) = EffortLevel.entries.map { EffortDefaults.forLevel(it).limits }
    assertTrue(low.maxToolCalls < medium.maxToolCalls)
    assertTrue(medium.maxToolCalls < high.maxToolCalls)
    assertTrue(high.maxToolCalls < xhigh.maxToolCalls)
    assertTrue(medium.maxTurnSeconds < high.maxTurnSeconds)
    assertTrue(high.maxTurnSeconds < xhigh.maxTurnSeconds)
}
```

Also test JSON encode/decode round trips of `EffortPreset` for every reasoning variant, including its nested numeric limits, and assert the exact discriminator values.

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test -PskipFrontend --tests infoscry.investigate.EffortDefaultsTest`
Expected: FAIL with "EffortDefaults not defined" (or similar unresolved reference).

- [ ] **Step 3: Implement `EffortLevel`, `NativeReasoning`, `EffortPreset`, `EffortDefaults.forLevel` in `EffortLevel.kt`**

Add `@Serializable` to existing `InvestigationLimits`; add explicit reasoning serial names. Use the exact rounds/calls/seconds table above. Every `forLevel` result has `reasoning = NativeReasoning.None`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test -PskipFrontend --tests infoscry.investigate.EffortDefaultsTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/infoscry/investigate/InvestigationLimits.kt src/main/kotlin/infoscry/investigate/EffortLevel.kt src/test/kotlin/infoscry/investigate/EffortDefaultsTest.kt
git commit -m "feat: add EffortLevel model for Investigate"
```

## Handoff

Report back: the exact `EffortLevel.kt` symbols committed, and confirmation
`./gradlew test -PskipFrontend --tests infoscry.investigate.EffortDefaultsTest`
passes. Tickets 02 and 03 both start only after this is reviewed and merged.
