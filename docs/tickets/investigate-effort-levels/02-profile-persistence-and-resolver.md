# Ticket 02 — Persist per-profile effort overrides, and resolve them

[Spec](../../specs/2026-10-11-investigate-effort-levels.md) ·
[Plan](../../plans/2026-10-11-investigate-effort-levels.md) · [Status](STATUS.md)

**Depends on:** Ticket 01 reviewed and merged (needs `EffortLevel`,
`NativeReasoning`, `EffortPreset`, `EffortDefaults.forLevel`).

**Scope:** `LlmProfile` (`LlmProfiles.kt`), the baseline schema, `LlmStore.kt`,
and `EffortResolver` (added to ticket 01's `EffortLevel.kt`). Do not touch the
LLM request builders (ticket 03), `LlmProfileRoutes.kt`/admin UI (ticket 04),
or `InvestigationRoutes.kt`/`InvestigationService.kt` (ticket 05) — this
ticket only makes the override data exist and resolvable; nothing consumes it
yet.

---

### Task 2: Persist per-profile effort overrides, and resolve them

**Files:**
- Modify: `src/main/kotlin/infoscry/llm/LlmProfiles.kt` (add field to `LlmProfile`)
- Modify: `src/main/kotlin/infoscry/investigate/EffortLevel.kt` (add `EffortResolver`, from Ticket 01)
- Modify: `src/main/resources/db/migration/001_baseline.sql:336-354` (add column to `llm_profiles`)
- Modify: `src/main/kotlin/infoscry/storage/LlmStore.kt` (`create` lines 246–282, `update` lines 290–333, `readProfile` row mapper)
- Test: `src/test/kotlin/infoscry/llm/LlmProfilesTest.kt`
- Test: `src/test/kotlin/infoscry/investigate/EffortResolverTest.kt`
- Test: `src/test/kotlin/infoscry/storage/LlmStoreTest.kt`

**Interfaces:**
- Consumes: `EffortLevel`, `EffortPreset`, `NativeReasoning`, `EffortDefaults.forLevel` (Ticket 01).
- Produces:
  - `LlmProfile.effortOverrides: Map<EffortLevel, EffortPreset>? = null`, validated by the complete save-time contract below.
  - `EffortResolver.resolve(profile: LlmProfile, level: EffortLevel): EffortPreset` — used by Ticket 05.

- [ ] **Step 1: Write a failing `LlmProfile` validation test**

```kotlin
@Test fun `rejects an Anthropic thinking budget at or above maxOutputTokens`() {
    val overrides = mapOf(EffortLevel.HIGH to EffortPreset(InvestigationLimits(), NativeReasoning.AnthropicThinkingBudget(4096)))
    assertThrows<IllegalArgumentException> {
        testProfile(provider = LlmProvider.ANTHROPIC, maxOutputTokens = 4096, effortOverrides = overrides)
    }
}

@Test fun `accepts a thinking budget below maxOutputTokens`() {
    val overrides = mapOf(EffortLevel.HIGH to EffortPreset(InvestigationLimits(), NativeReasoning.AnthropicThinkingBudget(2048)))
    testProfile(provider = LlmProvider.ANTHROPIC, maxOutputTokens = 4096, effortOverrides = overrides) // does not throw
}
```

Add table-driven rejection tests for rounds 0/51, calls 0/101, seconds 9/1801 (including negative values), thinking budgets 0/1023/maxOutputTokens, invalid OpenAI strings and mismatched providers. Test valid bounds, `None` for either provider, null/empty maps and sparse overrides. Model validation is shared by store create/update; tests must show neither operation can persist invalid presets (including updates that lower maxOutputTokens or change provider). No silent clamp or fallback.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test -PskipFrontend --tests infoscry.llm.LlmProfilesTest`
Expected: FAIL (field doesn't exist yet).

- [ ] **Step 3: Add `effortOverrides` field and `init` validation to `LlmProfile`**

Add `val effortOverrides: Map<EffortLevel, EffortPreset>? = null` to the constructor (`LlmProfiles.kt`, in the existing `data class LlmProfile(...)` parameter list). In the existing `init { ... }` block, add:

```kotlin
effortOverrides?.values?.forEach { preset ->
    preset.limits.validate()
    when (val reasoning = preset.reasoning) {
        NativeReasoning.None -> Unit
        is NativeReasoning.AnthropicThinkingBudget -> {
            require(provider == LlmProvider.ANTHROPIC)
            require(reasoning.tokens >= 1024 && reasoning.tokens < maxOutputTokens) {
                "Anthropic thinking budget must be at least 1024 and below maxOutputTokens"
            }
        }
        is NativeReasoning.OpenAiReasoningEffort -> {
            require(provider == LlmProvider.OPENAI_COMPATIBLE)
            require(reasoning.value in setOf("low", "medium", "high"))
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test -PskipFrontend --tests infoscry.llm.LlmProfilesTest`
Expected: PASS

- [ ] **Step 5: Write failing tests for `EffortResolver.resolve`**

```kotlin
@Test fun `profile with no overrides resolves to the global default for every level`() {
    val profile = testProfile(effortOverrides = null)
    EffortLevel.entries.forEach { level ->
        assertEquals(EffortDefaults.forLevel(level), EffortResolver.resolve(profile, level))
    }
}

@Test fun `an override for one level does not affect the others`() {
    val overridden = EffortPreset(InvestigationLimits(5, 5, 30), NativeReasoning.AnthropicThinkingBudget(1024))
    val profile = testProfile(provider = LlmProvider.ANTHROPIC, maxOutputTokens = 4096, effortOverrides = mapOf(EffortLevel.LOW to overridden))
    assertEquals(overridden, EffortResolver.resolve(profile, EffortLevel.LOW))
    assertEquals(EffortDefaults.forLevel(EffortLevel.HIGH), EffortResolver.resolve(profile, EffortLevel.HIGH))
}

@Test fun `an empty overrides map behaves identically to a null one`() {
    val profile = testProfile(effortOverrides = emptyMap())
    assertEquals(EffortDefaults.forLevel(EffortLevel.XHIGH), EffortResolver.resolve(profile, EffortLevel.XHIGH))
}
```

- [ ] **Step 6: Run tests to verify they fail**

Run: `./gradlew test -PskipFrontend --tests infoscry.investigate.EffortResolverTest`
Expected: FAIL ("EffortResolver not defined").

- [ ] **Step 7: Implement `EffortResolver.resolve` in `EffortLevel.kt`**

```kotlin
object EffortResolver {
    fun resolve(profile: LlmProfile, level: EffortLevel): EffortPreset =
        profile.effortOverrides?.get(level) ?: EffortDefaults.forLevel(level)
}
```

- [ ] **Step 8: Run tests to verify they pass**

Run: `./gradlew test -PskipFrontend --tests infoscry.investigate.EffortResolverTest`
Expected: PASS

- [ ] **Step 9: Write a failing `LlmStore` round-trip test**

```kotlin
@Test fun `create and findById round-trip effort overrides`() {
    val overrides = mapOf(
        EffortLevel.LOW to EffortPreset(InvestigationLimits(5, 5, 30)),
        EffortLevel.XHIGH to EffortPreset(InvestigationLimits(50, 100, 1800), NativeReasoning.OpenAiReasoningEffort("high")),
    )
    val saved = store.create(testProfile(effortOverrides = overrides))
    assertEquals(overrides, store.findById(saved.id)?.effortOverrides)
}

@Test fun `create and findById round-trip a null overrides map as null, not an empty map`() {
    val saved = store.create(testProfile(effortOverrides = null))
    assertEquals(null, store.findById(saved.id)?.effortOverrides)
}

@Test fun `update can clear a previously saved override back to null`() {
    val saved = store.create(testProfile(effortOverrides = mapOf(EffortLevel.LOW to EffortPreset(InvestigationLimits(5, 5, 30)))))
    store.update(saved.id, saved.copy(effortOverrides = null))
    assertEquals(null, store.findById(saved.id)?.effortOverrides)
}
```

- [ ] **Step 10: Run tests to verify they fail**

Run: `./gradlew test -PskipFrontend --tests infoscry.storage.LlmStoreTest`
Expected: FAIL (no column / no mapping yet).

- [ ] **Step 11: Add `effort_overrides_json TEXT` column to the baseline schema**

Add a line to the `llm_profiles` table in `001_baseline.sql` (after `capability_checked_at`): `effort_overrides_json TEXT,`. This repo edits the single baseline script in place rather than writing incremental migrations (`SchemaMigrator.kt:11-24`). This applies only to fresh/reset archives. A version-1 database is not altered by rerunning the migrator and lacks the column. Do not promise an automatic upgrade, introduce a migration/backfill, or reset a user archive in this ticket; recreation requires separate authorization. Use fresh temporary databases for tests and state this prerequisite in the handoff.

- [ ] **Step 12: Wire the column through `LlmStore.create`, `update`, and the row mapper**

Follow the existing `tool_calls_json` pattern (`LlmStore.kt:572,781`, `Json.encodeToString` / `Json.decodeFromString`). In `create`'s INSERT, add the column and bind `profile.effortOverrides?.let { Json.encodeToString(it) }`. In `update`'s UPDATE, add `effort_overrides_json = ?` bound the same way. In the row mapper (`readProfile`, wherever it reads `tool_calling_measured`/`capability_checked_at`), add `effortOverrides = results.getString("effort_overrides_json")?.let { Json.decodeFromString<Map<EffortLevel, EffortPreset>>(it) }`.

- [ ] **Step 13: Run tests to verify they pass**

Run: `./gradlew test -PskipFrontend --tests infoscry.storage.LlmStoreTest`
Expected: PASS

- [ ] **Step 14: Commit**

```bash
git add src/main/kotlin/infoscry/llm/LlmProfiles.kt src/main/kotlin/infoscry/investigate/EffortLevel.kt src/main/resources/db/migration/001_baseline.sql src/main/kotlin/infoscry/storage/LlmStore.kt src/test/kotlin/infoscry/llm/LlmProfilesTest.kt src/test/kotlin/infoscry/investigate/EffortResolverTest.kt src/test/kotlin/infoscry/storage/LlmStoreTest.kt
git commit -m "feat: persist and resolve per-profile Investigate effort overrides"
```

## Handoff

Report back: confirmation all three test classes pass, and the exact column
name/JSON shape committed to the baseline schema. Ticket 04 starts only after
this is reviewed and merged (it needs `effortOverrides` on the DTOs). Ticket
03 does not depend on this ticket and may run in parallel with it.
