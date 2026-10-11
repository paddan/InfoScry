# Investigate effort levels implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace Investigate's three raw sidebar numbers (`maxToolRounds`, `maxToolCalls`, `maxTurnSeconds`) with a single `low`/`medium`/`high`/`xhigh` effort dial that resolves to those same caps plus an optional native provider reasoning parameter, with per-profile admin overrides.

**Architecture:** A pure resolver (`EffortResolver`) maps `(LlmProfile, EffortLevel)` to an `EffortPreset` (existing `InvestigationLimits` + a new `NativeReasoning` value), consulting the profile's own optional override map before a global default table. `InvestigationRoutes` resolves once per request and feeds the result into the unchanged `InvestigationService` loop and into the LLM request builders. Round/call/time enforcement and citation validation stay unchanged. Transport/history plumbing and pruning must preserve and budget Anthropic signed thinking blocks through the current tool loop (Tasks 3 and 5).

**Tech Stack:** Kotlin/JVM 25, kotlinx.serialization, SQLite (baseline schema), Ktor routes, TypeScript/SvelteKit, Vitest, `./gradlew externalTest` Playwright acceptance.

**Spec:** [Investigate effort levels](../specs/2026-10-11-investigate-effort-levels.md).

**Tickets:** This plan's seven tasks are split one-to-one into [agent tickets](../tickets/investigate-effort-levels/STATUS.md) for dispatch and per-task review; see that status file for dependency order and shared-file ownership.

## Global Constraints

- `InvestigationLimits` bounds stay exactly as today: rounds 1–50, calls 1–100, seconds 10–1800 (`InvestigationLimits.kt`).
- `MEDIUM`'s resolved values must equal today's existing defaults (50 rounds / 50 calls / 600 seconds) so a reader who never touches the dial sees no behavior change.
- Default global table values (same for every provider): LOW = 10/15/120, MEDIUM = 50/50/600, HIGH = 50/75/1200, XHIGH = 50/100/1800 (rounds/calls/seconds).
- No automatic capability detection for native reasoning. Every profile's global default `reasoning` is `NativeReasoning.None`; a profile only gets native reasoning when an admin sets an explicit override.
- Native reasoning threads only into Investigate's per-round tool-calling request (`candidateRequest` in `InvestigationService.kt`), never into the synthesis or citation-correction requests in the same file, and never into Ask.
- Validate every preset's limits, provider compatibility and OpenAI low/medium/high value. `AnthropicThinkingBudget(tokens)` must be at least 1024 and strictly less than the profile's `maxOutputTokens`; reject the combination at admin save time, not at request time.
- `effortOverrides` is nullable in the fresh/reset baseline schema only. Existing version-1 databases are not upgraded; no migration/backfill or user archive reset is authorized by this plan.
- Existing bearer/CSRF/admission boundaries, English UI copy, and the single-application-process/SQLite-authority model are unchanged.

## Review Focus

1. An admin sets an `AnthropicThinkingBudget` override whose tokens are `>=` that profile's `maxOutputTokens`: must be rejected at save time with the existing validation-error contract, not surface mid-turn as a provider error. Covered in Task 2.
2. A profile with `effortOverrides = null` (every existing profile, and any new one until an admin touches the editor) must inherit the global table for the selected level: LOW 10/15/120, MEDIUM 50/50/600, HIGH 50/75/1200, XHIGH 50/100/1800. Only MEDIUM matches today's fixed defaults; all four default to NativeReasoning.None. Covered in Task 2.
3. A stale frontend sends an `effortLevel` string the backend enum doesn't have (old cached JS, a future value removed): must decode through the existing `receiveJson` → `SerializationException` → 400 path, not a 500. Covered in Task 5.
4. Clearing an override in the admin editor must remove that level's entry from the persisted map entirely (round-trips to `effortOverrides == null` or a map missing that key), not persist it as a null/placeholder entry that still shadows the global default. Covered in Task 2.
5. Switching the selected LLM profile in the sidebar while an effort level is already chosen must clear the hint until a turn-start event matches the current profile AND level; ignore stale events from superseded runs. Covered in Task 6.

6. Signed/redacted thinking survives a complete tool loop without pruning required blocks or leaking into persistence/SSE; full requests are budgeted. Covered in Tasks 3 and 5.
7. Nested limits serialize, Started maps through the SSE DTO, and profile/start/continue TypeScript client types and serializers match the backend. Covered in Tasks 1, 4, 5 and 6.
8. Baseline-only schema tests use fresh databases; existing version-1 archives require separately authorized recreation, not an automatic upgrade. Covered in Task 2.

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

---

### Task 3: Native reasoning in the LLM request builders

**Files:**
- Modify: `src/main/kotlin/infoscry/llm/LlmClient.kt:61-76` (`LlmRequest`)
- Modify: `src/main/kotlin/infoscry/llm/AnthropicClient.kt:345-352` (`AnthropicMessagesRequest`), `:408-...` (`buildAnthropicMessagesRequest`)
- Modify: `src/main/kotlin/infoscry/llm/OpenAiCompatibleClient.kt:248-256` (`ChatCompletionRequest`), `:357-390` (`buildChatCompletionRequest`)
- Modify: `src/main/kotlin/infoscry/investigate/InvestigationService.kt` (both exhaustive event switches only; add ignore branches)
- Test: `src/test/kotlin/infoscry/investigate/InvestigationServiceTest.kt` (existing None regression suite)
- Modify: `src/main/kotlin/infoscry/ask/AskService.kt` (exhaustive event handling only)
- Test: `src/test/kotlin/infoscry/ask/AskServiceTest.kt`
- Test: `src/test/kotlin/infoscry/llm/AnthropicClientTest.kt`
- Test: `src/test/kotlin/infoscry/llm/OpenAiCompatibleClientTest.kt`

**Interfaces:**
- Consumes: `NativeReasoning` (Ticket 01).
- Produces: `LlmRequest.reasoning: NativeReasoning = NativeReasoning.None`; both builders honor it; `NativeReasoning.None` produces byte-identical requests to today.

### Anthropic tool-loop protocol gate

Adding the outbound `thinking` field alone is insufficient. Follow the official
[extended-thinking protocol](https://platform.claude.com/docs/en/build-with-claude/extended-thinking).
Manual budgets apply only to models supporting `thinking.type = "enabled"`;
there is no automatic model probe or adaptive-thinking substitution.

Ticket 03 owns a typed, ordered assistant-content envelope on `LlmMessage` and
an `LlmEvent` carrying the completed envelope before `Completed`. It must retain
all `thinking` (including signature), `redacted_thinking` (opaque data), text and
tool-use blocks with their original ordering and exact values. Accumulate split
thinking/signature deltas by content-block index; never convert them to answer
text or reconstruct signatures. The Anthropic builder replays the original
assistant envelope before its matching tool results. Preserve text/tool events
for existing consumers; update all exhaustive event consumers (including Ask)
to ignore the envelope where it is not used. Empty-envelope/None requests retain
today's wire shape. OpenAI never serializes Anthropic-only envelopes.

Ticket 05 attaches the completed envelope to the assistant tool-call message in
the in-memory conversation. Preserve every required thinking/tool exchange in
the current tool loop intact; do not prune individual blocks or required earlier
exchanges from that loop. Measure the complete outbound request, including opaque
blocks, signatures and reasoning options. If protected history cannot fit, use
the existing budget-stop/synthesis path rather than truncate or send an invalid
payload. Synthesis and citation correction keep `None` and use a separate outbound
view without opaque thinking blocks; never mutate the original tool-loop history.
These opaque blocks stay request-local: do not log, persist, expose via SSE or
include them in saved answers, browser data or errors.

Before Ticket 05 starts, freeze the envelope/event contract and prove it with
fake-provider streaming fixtures: signed thinking + tool_use + tool_result +
second thinking-enabled request; fragmented signatures; redacted blocks; multiple
ordered blocks and multiple tool rounds; exact echo, no leakage, cancellation
and malformed/incomplete streams. Ticket 05 adds service and pruning integration
tests, including a protected loop that exceeds budget, and unchanged Ask,
synthesis, citation correction and None behavior. Real-model compatibility is a
separate unverified manual gate; fake tests cannot establish it.

- [ ] **Step 1: Write failing tests for the Anthropic builder**

```kotlin
@Test fun `NativeReasoning None omits thinking from the request`() {
    val request = buildAnthropicMessagesRequest("claude-x", llmRequest(reasoning = NativeReasoning.None))
    assertNull(request.thinking)
}

@Test fun `AnthropicThinkingBudget sets the thinking block`() {
    val request = buildAnthropicMessagesRequest("claude-x", llmRequest(reasoning = NativeReasoning.AnthropicThinkingBudget(2048)))
    assertEquals(AnthropicThinking(type = "enabled", budget_tokens = 2048), request.thinking)
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test -PskipFrontend --tests infoscry.llm.AnthropicClientTest`
Expected: FAIL (`LlmRequest` has no `reasoning` parameter / `AnthropicMessagesRequest` has no `thinking` field).

- [ ] **Step 3: Implement outbound options AND the envelope/stream/replay protocol above**

Add `val reasoning: NativeReasoning = NativeReasoning.None` to `LlmRequest` (`LlmClient.kt`). Add:

```kotlin
@Serializable data class AnthropicThinking(val type: String = "enabled", val budget_tokens: Int)
```

and a `val thinking: AnthropicThinking? = null` field to `AnthropicMessagesRequest`. In `buildAnthropicMessagesRequest`, set `thinking = (request.reasoning as? NativeReasoning.AnthropicThinkingBudget)?.let { AnthropicThinking(budget_tokens = it.tokens) }`.

- [ ] **Step 4: Run builder, streaming/replay, None-regression and Ask tests to verify they pass**

Run: `./gradlew test -PskipFrontend --tests infoscry.llm.AnthropicClientTest --tests infoscry.ask.AskServiceTest --tests infoscry.investigate.InvestigationServiceTest`
Expected: PASS

- [ ] **Step 5: Write failing tests for the OpenAI-compatible builder**

```kotlin
@Test fun `NativeReasoning None omits reasoning_effort`() {
    val request = buildChatCompletionRequest("gpt-x", llmRequest(reasoning = NativeReasoning.None))
    assertNull(request.reasoning_effort)
}

@Test fun `OpenAiReasoningEffort sets reasoning_effort`() {
    val request = buildChatCompletionRequest("gpt-x", llmRequest(reasoning = NativeReasoning.OpenAiReasoningEffort("high")))
    assertEquals("high", request.reasoning_effort)
}
```

- [ ] **Step 6: Run test to verify it fails**

Run: `./gradlew test -PskipFrontend --tests infoscry.llm.OpenAiCompatibleClientTest`
Expected: FAIL (`ChatCompletionRequest` has no `reasoning_effort` field).

- [ ] **Step 7: Implement**

Add `val reasoning_effort: String? = null` to `ChatCompletionRequest`. In `buildChatCompletionRequest`, set `reasoning_effort = (request.reasoning as? NativeReasoning.OpenAiReasoningEffort)?.value`.

- [ ] **Step 8: Run test to verify it passes**

Run: `./gradlew test -PskipFrontend --tests infoscry.llm.OpenAiCompatibleClientTest`
Expected: PASS

- [ ] **Step 9: Commit**

```bash
git add src/main/kotlin/infoscry/investigate/InvestigationService.kt src/test/kotlin/infoscry/investigate/InvestigationServiceTest.kt src/main/kotlin/infoscry/ask/AskService.kt src/test/kotlin/infoscry/ask/AskServiceTest.kt src/main/kotlin/infoscry/llm/LlmClient.kt src/main/kotlin/infoscry/llm/AnthropicClient.kt src/main/kotlin/infoscry/llm/OpenAiCompatibleClient.kt src/test/kotlin/infoscry/llm/AnthropicClientTest.kt src/test/kotlin/infoscry/llm/OpenAiCompatibleClientTest.kt
git commit -m "feat: thread native reasoning parameter into LLM request builders"
```

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

---

### Task 6: Frontend effort selector with resolved-caps hint

**Files:**
- Modify: `web/src/lib/api.ts` (start/continue request types, serializers and SSE parsing)
- Test: `web/src/lib/api.test.ts`
- Modify: `web/src/lib/investigationLimits.ts`
- Modify: wherever `investigationLimits.ts` is consumed in the sidebar component (locate with `rg "investigationLimits" web/src` for the exact `.svelte` file(s))
- Test: `web/src/lib/investigationLimits.test.ts`

**Interfaces:**
- Consumes: the start-of-turn SSE event's `resolvedLimits` (Ticket 05), via whatever client code already parses that event.
- Consumes shared `EffortLevel` and `ResolvedInvestigationLimits` from Ticket 04 via type-only imports.
- Produces: `DEFAULT_EFFORT_LEVEL = 'MEDIUM'`, `EFFORT_LEVELS_STORAGE_KEY = 'infoscry-investigate-effort:v1'`, `loadEffortLevel(): EffortLevel`, `saveEffortLevel(level: EffortLevel): void`.

- [ ] **Step 1: Write failing tests for the new storage module**

```ts
test('loadEffortLevel defaults to MEDIUM when nothing is stored', () => { /* ... */ });
test('saveEffortLevel then loadEffortLevel round-trips', () => { /* ... */ });
test('a corrupted or unrecognized stored value falls back to MEDIUM', () => { /* ... */ });
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd web && npm test -- --run investigationLimits`
Expected: FAIL (functions don't exist).

- [ ] **Step 3: Replace the three-number module with the effort-level module**

Delete `InvestigationLimits`, `InvestigationLimitsInput`, `INVESTIGATION_LIMIT_FIELDS`, `DEFAULT_INVESTIGATION_LIMITS`, `investigationLimitErrors`, `validInvestigationLimits`, and the old storage key/functions. Keep the file's `browserStorage()` guard helper. Import the shared `EffortLevel` type from `api.ts` and add the constants/functions listed in Interfaces above, following the same try/catch-on-storage-access pattern already in the file.

Update `api.ts` in the same change: remove its old `InvestigationLimits` import; change both start and continue argument types and JSON bodies to `limits: { effortLevel }`; extend the `started` SSE type/parser with `resolvedLimits: ResolvedInvestigationLimits`. Numeric caps remain an output type, never request inputs. Add client-level tests for both POST bodies and parsing actual SSE JSON, including omitted-level MEDIUM behavior through the route tests. Run `npm run check` to catch dangling imports and callers.

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd web && npm test -- --run investigationLimits`
Expected: PASS

- [ ] **Step 5: Write failing tests for the sidebar component**

```ts
test('renders four effort options and sends the selected one as limits.effortLevel', () => { /* ... */ });
test('shows a hint with the resolved caps from the last start-of-turn event', () => { /* ... */ });
test('switching profile or effort level clears the hint until a matching turn-start event arrives', () => { /* ... */ });
```

- [ ] **Step 6: Run tests to verify they fail**

Run: `cd web && npm test -- --run` (target the sidebar component file found via the `rg` search in Files)
Expected: FAIL.

- [ ] **Step 7: Implement the selector and hint in the sidebar component**

Replace the three number inputs with a 4-option control bound to `loadEffortLevel`/`saveEffortLevel`. Render the hint from the most recent `resolvedLimits` the turn-start event reported for the currently selected profile AND effort level; discard late events from superseded runs and clear the hint on either selection change. Before any matching turn has run, omit the hint rather than show a stale or guessed number — resolution is server-side only (deliberately: a reader's browser must not be trusted to compute the admin-overridable caps itself).

- [ ] **Step 8: Run tests to verify they pass**

Run: `cd web && npm test -- --run && npm run check`
Expected: PASS

- [ ] **Step 9: Commit**

```bash
git add web/src/lib/api.ts web/src/lib/api.test.ts web/src/lib/investigationLimits.ts web/src/lib/investigationLimits.test.ts <sidebar component file(s)>
git commit -m "feat: replace Investigate sidebar limits with an effort-level selector"
```

---

### Task 7: Acceptance coverage

**Files:**
- Modify: `src/test/kotlin/infoscry/server/InvestigateBrowserAcceptanceTest.kt`
- Modify: whatever Playwright fixture/page-object currently drives the old three-number sidebar (locate via `rg "maxToolRounds|maxToolCalls|maxTurnSeconds" web/e2e`)

**Interfaces:**
- Consumes: the finished sidebar selector (Ticket 06), the admin editor (Ticket 04).

- [ ] **Step 1: Update the browser-test fixture/page-object to drive the new effort selector**

Replace any code that fills the three numeric sidebar inputs with code that picks an effort level from the new control.

- [ ] **Step 2: Add one scenario asserting the resolved-caps hint renders**

Pick a test profile with a native-reasoning override and one without; assert the hint text appears for both and reflects each profile's own resolved numbers.

- [ ] **Step 3: Run the acceptance suite**

Run: `./gradlew externalTest --tests infoscry.server.InvestigateBrowserAcceptanceTest`
Expected: PASS

- [ ] **Step 4: Run the full accumulated gate**

Run: `./gradlew check`
Expected: PASS

- [ ] **Step 5: Update docs**

Update `docs/usage.md`'s Investigate section (effort levels replace the three numeric settings) and `docs/technical-reference.md`'s "Investigate limits" section (the storage key changed; resolution is now per-profile).

- [ ] **Step 6: Commit**

```bash
git add src/test/kotlin/infoscry/server/InvestigateBrowserAcceptanceTest.kt web/e2e docs/usage.md docs/technical-reference.md
git commit -m "test: cover the Investigate effort-level selector in browser acceptance"
```
