# Ticket 03 — Native reasoning in the LLM request builders

[Spec](../../specs/2026-10-11-investigate-effort-levels.md) ·
[Plan](../../plans/2026-10-11-investigate-effort-levels.md) · [Status](STATUS.md)

**Depends on:** Ticket 01 reviewed and merged (needs `NativeReasoning`). Does
**not** depend on Ticket 02 — may run in parallel with it.

**Scope:** `LlmClient.kt`, `AnthropicClient.kt`, `OpenAiCompatibleClient.kt`,
and exhaustive stream consumers including `AskService.kt` and the two
`InvestigationService.kt` event switches (ignore the new envelope event for now). Do not touch `LlmProfile`/`LlmStore` (ticket 02), the admin routes/UI
(ticket 04), or `InvestigationRoutes.kt` (ticket 05). In `InvestigationService.kt`, only add
ignore branches so this ticket compiles independently; Ticket 05 later collects
the envelope in the research loop. This ticket only makes `LlmRequest.reasoning` exist and be honored by
both builders plus lossless streaming/replay; nothing produces a non-`None` value yet.

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

## Handoff

Report back: the frozen envelope/event contract, protocol fixtures and confirmation all affected test classes pass, and that a `NativeReasoning.None`
request serializes identically to the pre-change request (no stray `null`
fields changing the wire shape in a way `RequestBudget.measure`'s byte-size
measurement would notice). Ticket 05 starts only after this and Ticket 02 are
both reviewed and merged.
