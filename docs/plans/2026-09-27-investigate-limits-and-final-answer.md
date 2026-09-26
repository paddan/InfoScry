# Investigate limits and final answer — implementation plan

> **For agentic workers:** Implement the vertical tickets one at a time. The user authorizes agent-assisted implementation; do not spawn nested workers. Follow the current saved worker model and delegation rules in the applicable `AGENTS.md`, rather than hard-coding a model here. Check off a step only after its stated verification passes.

**Goal:** Investigate returns a cited final answer from collected evidence when its research limits are reached, and the reader can configure tool rounds, tool calls, and maximum time per question in the left sidebar.

**Architecture:** Limits belong to an individual turn and travel from the sidebar through the start/continue API to the shared investigation service. The service stops research within those limits, reserves time for one tool-free synthesis request, and uses the same budgeting, citation validation, persistence, and completion path as a normal answer.

**Tech stack:** Kotlin/JVM 25, Ktor, kotlinx.coroutines, SQLite, TypeScript, SvelteKit, Vitest. Preserve pinned dependencies.

**Specification:** The accepted user requirements and concrete product contract are recorded below in this document. Read `../../README.md`, `../../AGENTS.md`, `../specs/2026-09-26-conversation-history.md`, and `../tickets/conversation-history/03-history-column-investigate.md` before execution. This plan adds turn controls; it does not replace the existing conversation-history contract.

**Status:** Tickets 01–04 code is implemented. Focused backend/frontend tests, frontend diagnostics, standalone `installDist`, and a later full Gradle check pass. Manual browser acceptance remains open. Pre-existing working-tree changes were preserved.

## Baseline at plan creation (before implementation)

- `InvestigationService.MAX_ROUNDS` is 10; `MAX_TOOL_CALLS` is 20.
- `InvestigationTimeouts.TURN_TIMEOUT_MS` is 600,000; the provider-call cap is 120,000 ms and tool-call cap is 30,000 ms.
- The latest diagnosed turn executed eight successful `search_collection` calls, one successful `read_content_unit`, and one `get_document_metadata` returning `NOT_FOUND`; it persisted 14 evidence entries.
- `MAX_ROUNDS` currently emits an error and exits. `Done` is emitted only if `finalAnswer` and `finalEvidence` are set, so this path delivers no final answer.
- The existing loop can append an assistant tool-call batch without results for calls skipped by a limit. Those incomplete groups must not be sent to synthesis or a later turn.
- Current turn time is checked at boundaries and can overshoot during an in-flight call. A synchronous `tools.execute` is not made interruptible merely by wrapping it in `withTimeout`.
- Preserve all existing uncommitted changes, including Investigate blank-filter handling, missing-evidence handling, flow error propagation, port 8080, and their tests. Preserve untracked `test documents/`; do not read those documents or send them to providers as tests.

## Product and API contract

### Sidebar

Show a restrained `Investigation settings` fieldset below the LLM profile when Investigate is selected. Product copy remains English.

| Label | Request field | Default | Allowed values |
| --- | --- | --- | --- |
| Max tool rounds | `maxToolRounds` | 50 | integer 1–50 |
| Max tool calls | `maxToolCalls` | 50 | integer 1–100 |
| Max time per question (seconds) | `maxTurnSeconds` | 600 | integer 10–1800 |

These ranges are deliberate bounded v1 choices. The defaults allow up to 50 tool rounds and 50 tool calls, so the round cap does not undercut the default call budget; whichever configured limit is reached first wins. A round is one model response containing one or more tool calls. A tool call counts once when execution is admitted; unsuccessful calls count too. Calls refused after a limit are recorded as refused, not counted as executed.

- Settings apply to the next submitted question, including follow-ups; they do not change a running turn.
- Persist settings in browser storage under `infoscry-investigate-limits:v1`, globally for this browser. Store only the three numeric values. Missing, malformed, or out-of-range stored values restore defaults.
- Disable controls during a running turn. Validate before submission; invalid input displays a field-associated message and sends no request. Allow a `Reset defaults` button.
- Explain: `A round may contain several tool calls. Time includes preparing the final answer.`
- Search filters remain scoped to Search. Ask behavior remains unchanged.

### HTTP

Extend `InvestigationApiRequest` with optional `limits`; old clients omitting it retain defaults. Both creation and continuation use the submitted limits for that turn, while collection/profile/prompt locks remain unchanged.

```json
{
  "collection": "test",
  "question": "What do the sources establish?",
  "profile": "configured-profile",
  "limits": {"maxToolRounds": 50, "maxToolCalls": 50, "maxTurnSeconds": 600}
}
```

Reject fractional, wrongly typed, zero, negative, and excessive values with HTTP 400 before SSE, conversation creation, active-turn reservation, or any provider call. Use existing sanitized bad-request conventions. Default missing individual fields. Do not silently clamp server requests.

Suggested shared domain contract (new `InvestigationLimits.kt`):

```kotlin
data class InvestigationLimits(
    val maxToolRounds: Int = 50,
    val maxToolCalls: Int = 50,
    val maxTurnSeconds: Int = 600,
) {
    fun validate() {
        require(maxToolRounds in 1..50) { "maxToolRounds must be between 1 and 50" }
        require(maxToolCalls in 1..100) { "maxToolCalls must be between 1 and 100" }
        require(maxTurnSeconds in 10..1800) { "maxTurnSeconds must be between 10 and 1800" }
    }
}
```

Use a serializable wire DTO in the server with identical field defaults, mapped explicitly to this domain type. Add `limits: InvestigationLimits = InvestigationLimits()` to `InvestigateRequest`. Validate at the service boundary as well, since non-HTTP service callers must obey the same contract. No Investigate CLI command currently exists; adding one is outside this task.

### Finalization and events

- Research stops at the configured round/call limit, repeated-call guard, or reserved synthesis boundary. It does not request another tool-enabled model response once a research limit is exhausted.
- Emit a nonfatal `limit` event with `code` and a safe English `message`; persist the reason in `limit_events`. Codes: `MAX_ROUNDS`, `MAX_TOOL_CALLS`, `REPEATED_TOOL_CALL`, `TURN_TIMEOUT`.
- Example: `{"type":"limit","code":"MAX_ROUNDS","message":"Tool round limit reached; preparing an answer from collected sources."}`.
- The UI keeps a visible notice next to the final answer. This event does not set the fatal-error state or unlock selection controls. `Done` remains the user-visible completion.
- A successful limited turn emits any synthesis deltas, citation events, cumulative `Usage`, and exactly one `Done`. The final answer and model call are persisted before completion. Normal unrestricted turns keep their existing behavior.
- Exactly one synthesis request is allowed for a limited turn, with no tool definitions and an explicit instruction to answer only from supplied evidence, distinguish uncertainty, and describe insufficient evidence honestly. Do not execute tool calls returned despite tools being absent; treat that provider response as a typed failure.
- If no evidence was obtained, return a clear no-evidence answer without invented citations. A local fixed answer is acceptable for this path and should avoid an unnecessary provider call.
- Synthesis failure, irreducible context overflow, explicit cancellation, or exhausted total time produces a typed fatal error and durable status, not a successful `Done` or invented answer.
- A partial streamed research answer must not be concatenated with the synthesized answer. Add an explicit `answer-start` event before replacing the current assistant text for synthesis; update Kotlin wire, all exhaustive service-event handlers, TypeScript parser, and UI together. The final `Done.text` remains authoritative.

### Time semantics

`maxTurnSeconds` bounds provider work and admission of further research from the beginning of the service turn, including final synthesis and citation correction. It excludes the optional conversation-title request after completion. UI copy must not promise a strict wall-clock cutoff for an uninterruptible native search; document this limitation.

Use monotonic elapsed time with a testable clock. Reserve `min(120 seconds, 20% of the total budget)` for synthesis. Stop admitting research at `totalDeadline - reserve`. Cap every provider call by both its existing cap and the remaining relevant time; synthesis/correction use the remaining total time. Stop admitting tools once the research deadline is reached. Avoid a coroutine cancellation that prevents emitting the final error/persisting the turn: timeouts must be scoped to the individual provider operation, with explicit user cancellation propagated unchanged.

Do not relocate synchronous database/Lucene/CoreML work into arbitrary threads as an incidental refactor. Check before and after that work; document potential overshoot. If hard cancellation cannot be made safe without larger changes, keep this limit honest in README and UI.

## Global constraints

- One backend process/module; SQLite is authoritative. No source files modified, no external provider tests, no secrets or source text in logs.
- Budget complete synthesis/correction requests with reserved output. Never restore evidence omitted from the actual generating request when validating citations.
- Preserve stable evidence IDs, collection isolation, atomic tool-call/result groups, cancellation, streaming, and persisted history.
- Do not change provider dependencies, GPU behavior, hosting, packaging, server lifecycle, default port, or unrelated UI.
- No commits, pushes, or production-server restarts unless separately requested.

## Review focus

1. A provider batch crossing the call limit must leave a complete result for every recorded assistant tool call.
2. Context pruning must remove whole exchanges and prevent omitted evidence from acquiring valid citations.
3. A very short time limit must still leave synthesis time and propagate explicit user cancellation.
4. A new follow-up must use new limits without changing its locked collection/profile or mixing an older stream into it.
5. Invalid browser storage and tampered HTTP requests must not bypass bounded defaults or backend validation.

## Task 1 — Shared limits and request admission

**Files:** Create `src/main/kotlin/infoscry/investigate/InvestigationLimits.kt`; modify `InvestigationService.kt` and `src/main/kotlin/infoscry/server/InvestigationRoutes.kt`; test `InvestigationLimitsTest.kt`, `InvestigationServiceTest.kt`, and `src/test/kotlin/infoscry/server/InvestigationRoutesTest.kt`.

**Produces:** Validated `InvestigateRequest.limits`, supplied consistently for start and continue.

- [x] Add failing domain tests for defaults, lower/upper bounds, and each invalid numeric boundary. Example assertions: `assertEquals(600, InvestigationLimits().maxTurnSeconds)` and `assertFailsWith<IllegalArgumentException> { InvestigationLimits(maxToolCalls = 101).validate() }`.
- [x] Add route tests with fake providers: old request omitted limits succeeds; partial limits default remaining fields; both endpoints forward custom values; malformed limits return 400 with zero provider calls and no new conversation/reservation.
- [x] Run the new focused tests against existing code and record the actual failure; compilation failures alone are not behavioral evidence.
- [x] Implement domain validation and wire mapping before route side effects. Preserve old positional request construction by appending the new defaulted property.
- [x] Replace hard-coded loop comparisons with request limits. Keep constants only as aliases if consumers require them; avoid two independent default sources.
- [x] Run domain/service/route focused tests. Gate: valid limits reach the service; invalid values cannot start a turn.

## Task 2 — Final answer when research stops

**Files:** Modify `InvestigationService.kt`, `InvestigationTimeouts.kt`, `InvestigationRoutes.kt`, and any exhaustive event consumers found by `rg -n 'InvestigateEvent' src/main src/test`. Add a small finalization helper only if it avoids duplicating normal answer validation. Test `InvestigationServiceTest.kt` and route tests.

**Consumes:** Task 1 limits. **Produces:** complete persisted exchanges and coherent `limit` / `answer-start` / citation / usage / done events.

- [x] Extend the scripted provider fixture to record requests and emit a different script per invocation. Use fake search/content and redistributable fixtures only.
- [x] Add failing scenario: limits rounds=1, first response requests search and obtains `S1`, next request has no tools and produces `Answer [S1]`; assert one executed round, final request contains supplied evidence, one valid citation, one `Done`, cumulative usage, and persisted assistant answer.
- [x] Add failing scenario: calls=1, first model response requests two calls. Execute only one; return a bounded `LIMIT_REACHED` tool result for the refused call without creating evidence or a successful execution record. Assert every assistant call ID has exactly one result before synthesis and in reopened history.
- [x] Add tests for repeated calls triggering synthesis, empty evidence yielding no-evidence answer, unsolicited synthesis tool calls, synthesis provider failure, and user cancellation (no additional provider calls after cancellation).
- [x] Add pruning test with multiple exchanges: synthesis citations reference only evidence included after pruning. Assert correction request remains budgeted and cannot validate omitted IDs.
- [x] Run the new scenarios red, then implement a shared final-answer path used by both normal completion and limit synthesis. Track limit reason distinctly from fatal errors. Do not synthesize for arbitrary provider failures.
- [x] Ensure a full tool batch receives real or refused results. Persist model usage even when calls are refused; do not count refused calls as executed.
- [x] Map the two new event types through server serialization. Assert exact SSE JSON fields and event order in route tests; update all exhaustive event consumers identified by the search.
- [x] Run focused service and route tests. Gate: each successful limited turn produces exactly one validated/persisted final answer.

## Task 3 — Total time budget with synthesis reserve

**Files:** Modify `InvestigationService.kt`, `InvestigationTimeouts.kt` and their tests. Use injected monotonic clock/deadline helpers with production defaults; preserve existing short-timeout test seams deliberately.

- [x] Add deterministic tests using virtual coroutine time or a controllable clock; never sleep for minutes.
- [x] Cover: research boundary reached before total deadline; synthesis admitted within reserve; in-flight provider bounded by remaining time; synthesis exceeding total time emits `TURN_TIMEOUT` without `Done`; cancellation is propagated; no tools admitted after research deadline.
- [x] Verify timeout scopes leave persistence and event delivery active. Avoid catching downstream collector errors and attempting another emit (preserve the existing flow fix).
- [x] Implement one total deadline and one research deadline, calculated at turn entry. Clamp provider-operation timeout to positive remaining duration; when none remains, use the explicit timeout-finalization path without invoking the provider.
- [x] Ensure citation correction and synthesis share the remaining total budget. Do not reset the timer for each round or finalization request.
- [x] Run timeout/service focused suites. Gate: controllable tests demonstrate reserve behavior and no new provider work after total deadline. Document synchronous-tool overshoot rather than claiming a hard guarantee.

## Task 4 — Sidebar controls and stream presentation

**Files:** Modify `web/src/routes/+page.svelte`, `web/src/lib/InvestigatePanel.svelte`, `web/src/lib/api.ts`, `web/src/routes/page.test.ts`, and `web/src/lib/InvestigatePanel.test.ts`. Create `web/src/lib/investigationLimits.ts` and a focused test for storage/validation if this keeps the page simpler.

**Consumes:** API and event contracts from Tasks 1–3.

```typescript
export type InvestigationLimits = {
  maxToolRounds: number;
  maxToolCalls: number;
  maxTurnSeconds: number;
};
export const defaultInvestigationLimits: InvestigationLimits = {
  maxToolRounds: 50, maxToolCalls: 50, maxTurnSeconds: 600
};
```

Append an optional limits argument after the existing `signal` argument in `startInvestigation` and `continueInvestigation` to preserve callers. Snapshot settings at submit; do not read mutable controls during an active stream.

- [x] Add failing page tests: settings only in Investigate, defaults displayed, custom limits in start/continue JSON, field validation blocks submission, settings disabled during work, reset defaults, reload persistence, malformed storage restores defaults.
- [x] Add failing panel tests: `limit` notice persists through `Done`; `answer-start` clears provisional assistant text; citations are clickable; usage accumulates; loading/selection stays locked until completion; fatal error remains distinct; old stream cannot overwrite a newer turn.
- [x] Implement normal labels, numeric input bounds/steps, field-associated errors, storage guards, and readable notices without additional UI libraries. Guard storage reads/writes against browser restrictions.
- [x] Update `InvestigateEvent` union and parser whitelist, both API helpers, panel props, and the page's request state together. No silent fallback that sends invalid browser values.
- [x] Run `cd web && npm test -- --run` and `npm run check`. Gate: real page-to-request boundary is covered, not just a mocked panel prop.

## Task 5 — Integrated verification, docs, and handoff

**Files:** Modify `README.md` and this plan's checked steps/results. Add a focused ticket/spec note if the current contract requires it. Journal the final outcome according to AGENTS.md after results are known.

- [x] Update README with defaults/ranges, examples, per-turn behavior, synthesis at limits, browser preference persistence, and honest time-limit semantics.
- [x] Run focused combined backend command:

```sh
JAVA_HOME="$(asdf where java)" ./gradlew test --tests 'infoscry.investigate.*' --tests infoscry.server.InvestigationRoutesTest --console=plain
```

- [x] Read all focused-suite failures and final exit status; do not infer success from an unfinished command. There is no Investigate CLI test class to run in the current tree.
- [x] Run `JAVA_HOME="$(asdf where java)" ./gradlew check installDist --console=plain`. An earlier bounded run did not finish: a sanitized stack at `/tmp/infoscry-check-thread-dump.txt` showed `OpenAiCompatibleClientTest.cancelling the consumer mid-stream` blocked in test-only `FakeOpenAiServer.close` / JDK `HttpServerImpl.stop` joining a handler thread (not the previously suspected `DocumentRoutesTest` Netty path). `installDist` passed separately then; a later complete `./gradlew check --console=plain` passed in 9m28. Do not expand this task into fixing unrelated test-server shutdown.
- [x] Run `git diff --check` and check all new local Markdown links. Inspect the scoped diff and confirm prior user changes are intact.
- [ ] Browser acceptance uses a fake provider and a temporary data directory: a low round/call cap returns a cited answer with a notice, follow-up uses changed limits, time exhaustion is honest, reload keeps preferences, and sources open. No private documents or external provider calls. Browser automation was unavailable; retain this as an open manual gate.
- [x] Record completed commands, exit codes/test counts, remaining gates, and touched files here. Keep README explicit that broader Ask/Investigate browser acceptance and format-specific source viewers are still open.
- [x] Build the launcher, but do not restart the user's server. Explain that using the new code requires restart and page reload. No commit/push unless requested.

## Verified execution record (2026-09-27)

- Tickets 01–04 have implementation coverage in source and tests. The four ticket files have checked acceptance items except the manual browser pass in ticket 04.
- Focused backend command: `./gradlew test --tests 'infoscry.investigate.*' --tests infoscry.server.InvestigationRoutesTest --console=plain` — passed. JUnit XML: `InvestigationLimitsTest` 3, `InvestigationServiceTest` 41, `InvestigationRoutesTest` 24; zero failures/errors.
- Frontend: `cd web && npm test -- --run` — 7 files, 110 tests passed. `cd web && npm run check` — `svelte-check` 0 errors and 0 warnings.
- `git diff --check` passed; rewritten ticket/plan/README Markdown whitespace and local-link checks found no issues.
- Full gate: the initial bounded `check installDist` run did not finish; a sanitized thread dump identified `OpenAiCompatibleClientTest.cancelling the consumer mid-stream` blocked in `FakeOpenAiServer.close` / JDK `HttpServerImpl.stop` joining a handler. `installDist` passed separately. After the limit defaults and tests were aligned, the complete `./gradlew check --console=plain` passed in 9m28; JUnit XML reports 883 tests, 0 failures/errors. No unrelated shutdown code was changed.
- Serve bind-error follow-up: `./gradlew test --tests infoscry.cli.ServeCommandTest --console=plain` passed after the error path was made actionable and the occupied-port regression test was added; all serve integration tests use `--port 0`.
- Launcher build: `./gradlew installDist --console=plain` passed separately. The user's server was not restarted; using the built code requires a manual restart and page reload.
- Manual browser acceptance remains open: no browser automation was available. No private documents or real external providers were used.
- Feature files: `InvestigationLimits.kt`, `InvestigationService.kt`, `InvestigationTimeouts.kt`, `InvestigationRoutes.kt`; their domain/service/route tests; `web/src/lib/investigationLimits.ts`, `api.ts`, `InvestigatePanel.svelte`, `+page.svelte`, and their focused Vitest files. README, the four vertical tickets, and this execution record were updated.
- Preserved pre-existing README port-8080, blank Investigate search-filter, missing-evidence, stream-disconnect, tool-filter, and test-document changes; did not read `test documents/`, restart a server, stage, or commit.

## Completion checklist

- [x] Defaults are 50 rounds, 50 calls, and 600 seconds; all three are configurable in the left sidebar.
- [x] Both start and continue honor validated per-turn settings and preserve conversation locks.
- [x] Successful research-limit finalization produces a cited answer and a nonfatal notice, including at the configured call limit.
- [x] No incomplete tool groups, unjustified citations, duplicate completion, or timer reset.
- [x] Failure/cancellation/time exhaustion never masquerades as successful completion.
- [x] Focused backend and frontend tests, type checks, build, and diff checks pass; full gate and browser acceptance are reported separately if blocked.
- [x] Final journal entry and plan status reflect verified outcomes only.
