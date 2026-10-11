# Investigate effort levels

## Intent and success

Investigate's per-turn research is bounded today by three raw numbers a reader
sets in the sidebar: `maxToolRounds`, `maxToolCalls`, `maxTurnSeconds`
(`InvestigationLimits`). The owner reports research almost always gets cut off
by one of these, and it is not clear what value trades off correctly against
result quality — three independent numbers give no intuition for the tradeoff
being made.

Replace the three numbers with a single per-request effort dial —
`low` / `medium` / `high` / `xhigh` — that resolves to the existing numeric
caps *and*, where the active profile's provider supports it, the provider's
native reasoning/thinking parameter. Resolution is per LLM profile: each
profile has a default preset table for the four levels, and an admin can
override any level's values for that profile from the LLM profiles page. The
sidebar shows the effective caps the chosen level resolves to, so the
tradeoff being made is visible again.

Out of scope: changing how the round/tool-call/time caps are enforced inside
`InvestigationService`'s loop, changing Ask's limits (Ask has no multi-round
tool loop), adding levels beyond these four, and any UI for editing the
*global* default table (only per-profile overrides are admin-editable; the
global table is a code-level default shipped with the application).

## Chosen approach and alternatives

Recommended: keep `InvestigationLimits` and its enforcement in
`InvestigationService` entirely unchanged. Add a resolution step upstream, in
`InvestigationRoutes`, that turns `(profile, EffortLevel)` into the
`InvestigationLimits` + a new native-reasoning value, before constructing
`InvestigateRequest`. This confines the change to "what feeds the existing
caps" rather than touching the proven round/call/time enforcement, consistent
with keeping idempotence and recovery behavior exactly as it is.

Alternatives considered:

1. Let the frontend compute effective caps from a table it owns and send raw
   numbers as today. Rejected: the admin-editable per-profile override must be
   authoritative server-side (a reader's browser cannot be trusted to carry
   an admin's cap), and the resolved caps must be computed from the same
   profile data the backend uses to build the LLM request.
2. Fold the native-reasoning value into `InvestigationLimits` itself. Rejected:
   `InvestigationLimits` is also used for the plain time/round/call contract
   independent of any model; mixing in a provider-specific reasoning value
   would force every caller (including non-HTTP callers mentioned in the
   existing doc comment) to carry a concept that does not apply to them.
3. Expose the provider's native levels directly (e.g. OpenAI's own
   `low`/`medium`/`high` with no `xhigh`) instead of a fixed four-level UI
   enum. Rejected: a reader switching profiles would see the dial's option
   set change shape, defeating the goal of a single intuitive control; a
   fixed four-level UI enum with a per-provider mapping table keeps the
   control stable across profiles.

## Data model

```kotlin
enum class EffortLevel { LOW, MEDIUM, HIGH, XHIGH }

sealed class NativeReasoning {
    object None : NativeReasoning()
    data class AnthropicThinkingBudget(val tokens: Int) : NativeReasoning()
    data class OpenAiReasoningEffort(val value: String) : NativeReasoning() // "low" | "medium" | "high"
}

data class EffortPreset(
    val limits: InvestigationLimits,
    val reasoning: NativeReasoning,
)
```

A global default table (code constant, not persisted) maps each `EffortLevel`
to an `EffortPreset`, the same for every provider: caps only, `reasoning =
NativeReasoning.None` at every level. `MEDIUM` carries today's existing
defaults unchanged, so a reader who never touches the dial sees no behavior
change. Exact values, chosen within the existing `InvestigationLimits` bounds
(rounds 1–50, calls 1–100, seconds 10–1800):

| Level  | maxToolRounds | maxToolCalls | maxTurnSeconds |
|--------|---------------|--------------|-----------------|
| LOW    | 10            | 15           | 120             |
| MEDIUM | 50            | 50           | 600             |
| HIGH   | 50            | 75           | 1200            |
| XHIGH  | 50            | 100          | 1800            |

`maxToolRounds` sits at its bound from `MEDIUM` upward: the existing bound is
already the current default, so the real headroom `HIGH`/`XHIGH` add over
`MEDIUM` is in tool calls and wall-clock time, not round count. There is no
automatic capability
detection for native reasoning — unlike `toolCallingMeasured`, which is an
explicit, separate probe, whether a profile's actual model accepts a
`thinking` budget or `reasoning_effort` field is never probed. Native
reasoning is opt-in per profile: an admin who knows their profile's model
supports it turns it on with an override (see "Admin LLM profiles page"
below). With no override, MEDIUM preserves today's caps and all levels omit native
reasoning. Selecting another level changes the caps according to the table.
Configured native reasoning is sent only to the matching provider; model
compatibility remains the admin's responsibility, without automatic fallback.

`LlmProfile` gains one new optional field:

```kotlin
val effortOverrides: Map<EffortLevel, EffortPreset>? = null
```

Serialized as a sparse map; a level absent from the map (or the whole field
null) inherits the global default for that level and provider. The nullable field requires no backfill in a fresh/reset archive. Existing
version-1 databases do not gain this column by rerunning the baseline; see
the persistence prerequisite below.

## Resolution

New `EffortResolver` (small, pure function, lives beside `InvestigationLimits`):

```kotlin
fun resolve(profile: LlmProfile, level: EffortLevel): EffortPreset
```

Looks up `profile.effortOverrides?.get(level)`, falling back to the global
default table entry for `(profile.provider, level)`. Called once in
`InvestigationRoutes` per request, before `InvestigationService.investigate`
is invoked. The resolved `InvestigationLimits` still passes through
`validate()` unchanged (same bounds: rounds 1–50, calls 1–100, seconds
10–1800) — an admin override that violates those bounds fails validation at
save time in the admin route, not at request time.

The resolved `NativeReasoning` is threaded into the LLM request builder for
the call site that assembles the Anthropic Messages request or the
OpenAI-compatible chat-completions request, as an additional parameter next
to the existing model/tools/messages fields. `NativeReasoning.None` means the
request is built exactly as it is today — no behavioral change for profiles
that don't support it.

## Backend wiring

- `InvestigationLimitsRequest` (`InvestigationRoutes.kt`) drops
  `maxToolRounds`/`maxToolCalls`/`maxTurnSeconds` and gains
  `effortLevel: EffortLevel` (default `MEDIUM`).
- The route resolves `EffortPreset` from the request's profile and level,
  builds `InvestigateRequest` with the resolved `InvestigationLimits` exactly
  as before, and additionally passes the resolved `NativeReasoning` through to
  the LLM call.
- The start-of-turn event streamed back to the frontend includes the resolved
  `InvestigationLimits` (rounds/calls/seconds) so the UI can render the hint
  without duplicating the resolution table client-side.
- `InvestigationService`, `InvestigationBudgetPruner`, `InvestigationTimeouts`,
  and the round/tool-call enforcement in the main loop keep their enforcement
  semantics. The service and pruner change only for reasoning transport,
  full request measurement and protection of required thinking/tool groups.

## Admin LLM profiles page

Each profile's edit view gains an "Effort levels" section: four rows (one per
`EffortLevel`), each showing the resolved value (override if set, else global
default — caps only, visually distinguished) for rounds/calls/seconds, plus a
native-reasoning control the admin may optionally fill in: a token-budget
number input for an `ANTHROPIC` profile, a low/medium/high select for an
`OPENAI_COMPATIBLE` profile. Which control shape is offered depends only on
`profile.provider` (static, not probed) — filling it in is the admin's own
informed choice that their specific model/endpoint accepts the field; leaving
it blank keeps that level at `NativeReasoning.None`, identical to today.
Clearing a row's override reverts that level to the global default. Saved
overrides go through the same `InvestigationLimits.validate()` bounds as
today; an out-of-range value is rejected with the existing error contract,
not silently clamped.

Persistence adds nullable `effort_overrides_json TEXT` to the single baseline
schema and wires it through create/update/read. This supports fresh/reset
archives only: `SchemaMigrator` returns for version 1, so editing the baseline
does not alter an existing database. No incremental migration or backfill is
in scope. Running the new feature against an older archive requires separately
authorized recreation first; implementation must not reset user data. Tests use
fresh temporary databases. Nullable profile JSON remains compatible with omitted
overrides within a database created with the new schema.

Every override is validated on model construction and admin create/update:
`limits.validate()`; Anthropic budgets only for `ANTHROPIC`, at least 1024 and
strictly below `maxOutputTokens`; OpenAI efforts only for `OPENAI_COMPATIBLE`
and exactly `low`, `medium` or `high`; `None` for either provider. Invalid
updates leave the previous profile intact and return the existing 400 contract.
Changing provider or reducing maxOutputTokens revalidates all overrides.

## Anthropic transport and integration contracts

Manual `thinking.type = "enabled"` is opt-in only for models that support it.
The [Anthropic extended-thinking protocol](https://platform.claude.com/docs/en/build-with-claude/extended-thinking)
requires preserving complete thinking/signature and redacted-thinking blocks
through tool use; adding an outbound budget alone is not sufficient. A typed,
ordered assistant envelope travels from the streaming adapter via an internal
`LlmEvent` into the request-local `LlmMessage`, preserving thinking, signatures,
redacted data, text and tool-use blocks. The next tool-loop request echoes the
original envelope intact before matching tool results. These opaque blocks are
never answer text, logs, persisted history, browser data, SSE or error details.

Protect the required current tool loop from pruning and measure every actual
outbound field, including envelopes and reasoning options. If that protected
request cannot fit, take the existing budget-stop path. Synthesis/correction
keep `None` and a separate outbound view without opaque thinking; do not mutate
the retained tool loop. Round/call/time enforcement and citation checks stay
unchanged. Fake-provider fixtures must prove signed and redacted multi-round
replay, fragmented streaming, complete budgeting, no leakage and unchanged
None/Ask/synthesis behavior. Real-model support remains a separate manual gate.

`InvestigationLimits` must be serializable for nested presets and SSE caps.
Freeze `NativeReasoning` JSON discriminator names as `None`,
`AnthropicThinkingBudget`, `OpenAiReasoningEffort` explicitly. Map
`InvestigateEvent.Started.resolvedLimits` through `InvestigateWire` and `toWire()`;
omit this field on unrelated events. Test actual HTTP SSE JSON for both start
and continue, including defaults and overrides. The TypeScript client owns
shared effort/preset/resolved-cap types and profile override serializers, then
updates both start/continue bodies to `limits: { effortLevel }` and parses the
new started event. The numeric cap output type remains available independently
of the replaced numeric input/storage module.

## Frontend

- `web/src/lib/investigationLimits.ts`'s three number inputs are replaced by a
  single four-option effort selector (`low`/`medium`/`high`/`xhigh`),
  persisted client-side under a new localStorage key (the old
  `infoscry-investigate-limits:v1` key is abandoned, not migrated — this is a
  single-user local app and the old value is just a UI preference).
- Next to the selector, a hint line shows the effective caps for the current
  profile + level, e.g. "High — up to 30 rounds, 10 min", sourced from the
  start-of-turn event's resolved `InvestigationLimits`. Omit the hint until a
  matching turn has started; clear it when profile or level changes and ignore
  late events from superseded runs. No extra resolution endpoint is in scope.
- When a run is cut off by a reached limit, the existing end-of-turn messaging
  is unchanged (`synthesizeFromEvidence("MAX_ROUNDS", ...)` and friends) — the
  effort level is not itself reported as a separate failure reason.

## Testing

- Backend unit tests for `EffortResolver`: override present → override wins;
  override absent → global default for `(provider, level)`; unsupported
  provider → `NativeReasoning.None` for every level.
- Route test for the new `InvestigationLimitsRequest` shape (effort level in,
  resolved limits out in the start event).
- Admin route test: saving an out-of-range override is rejected with the
  existing validation error; clearing an override reverts to default.
- Existing `InvestigationService` tests that construct `InvestigationLimits`
  directly retain their enforcement assertions; add transport/pruning cases
  for signed thinking without changing the enforcement mechanism.
- Update `InvestigateBrowserAcceptanceTest` fixtures to drive the new effort
  selector instead of the three numeric sidebar inputs, and assert the hint
  text renders for at least one profile with a native-reasoning mapping and
  one without.
