# Investigate effort levels

[User guide](usage.md#investigate-and-ask-follow-up-questions) · [Implementation status](implementation-status.md)

Each Investigate question or follow-up selects LOW, MEDIUM, HIGH or XHIGH.
MEDIUM is the default. The server resolves the chosen level against the active
LLM profile before starting research. Follow-ups use the conversation's locked
profile. The existing round, tool-call and time enforcement still applies.

| Level | Tool rounds | Tool calls | Seconds |
|---|---|---|---|
| LOW | 10 | 15 | 120 |
| MEDIUM | 50 | 50 | 600 |
| HIGH | 50 | 75 | 1200 |
| XHIGH | 50 | 100 | 1800 |

All global presets use `NativeReasoning.None`. A nullable sparse
`LlmProfile.effortOverrides` map can override individual levels. Missing keys
inherit the table. Clearing a row removes its key; clearing the last row stores
null. Admin shows inherited/override state and provider-specific reasoning controls.
The global table is fixed in code.

## Profile validation and storage

Every override validates rounds 1–50, calls 1–100 and seconds 10–1800 on model
construction and again before storage. Native-reasoning JSON discriminators are
`None`, `AnthropicThinkingBudget` and `OpenAiReasoningEffort`. Anthropic budgets
require an ANTHROPIC profile and at least 1024 tokens, strictly below the profile's
`maxOutputTokens`. OpenAI reasoning requires an OPENAI_COMPATIBLE profile and
exactly `low`, `medium` or `high`. None is valid for either provider. Provider
changes and output-budget reductions revalidate all overrides. Invalid mutations
return 400 and preserve the saved profile.

Nullable `effort_overrides_json TEXT` belongs to the fresh baseline schema only.
An existing version-1 database is not upgraded by changing that baseline. Using
this feature with an older archive requires separately authorized recreation;
implementation does not migrate, backfill or reset user data. Tests use fresh
temporary archives.

## Request and stream contracts

Both start and continue accept `limits: { effortLevel }`; an omitted limits
object or level defaults to MEDIUM. Unknown levels and obsolete numeric fields
return 400 before starting a conversation. The started SSE event includes
`resolvedLimits: { maxToolRounds, maxToolCalls, maxTurnSeconds }`; unrelated
events omit it. Numeric caps are server output, not browser request inputs.

The browser stores its effort selection under `infoscry-investigate-effort:v1`
and ignores the old numeric key. The sidebar hint uses only a started event
matching its current profile and level. It is absent before such a turn, clears
on selection changes and ignores superseded streams. Changing effort never
changes an already running turn.

## Native reasoning and context

Provider-native options are explicit opt-ins for models/endpoints known by the
admin to support them. Only research receives Anthropic `thinking` or OpenAI
`reasoning_effort`. Ask, synthesis and citation correction keep None.

Anthropic's complete ordered assistant envelope preserves thinking, fragmented
signatures, redacted thinking, visible text and tool-use blocks through the current
tool loop. Subsequent requests replay it unchanged before matching tool results.
Envelopes remain request-local; they never become answer text, persisted history,
SSE, browser data or error details. Incomplete or malformed envelopes fail with
sanitized protocol errors.

Budget measurement includes the actual provider envelope and reasoning options.
Required current thinking exchanges cannot be pruned. If that protected request
cannot fit, research stops and synthesis receives a separate view without opaque
blocks and with None. The retained exchange stays intact. Plain irreducible
context overflow retains the existing fatal budget error. Citation eligibility,
round/call/time limits and recovery behavior remain unchanged.

## Verification boundary

Unit, storage, HTTP/SSE, frontend and Chromium acceptance tests use local fake
providers and temporary archives. Acceptance covers native and plain profile
hints, level selection, persistence, limits and existing follow-up/recovery flows.
Fake signed/redacted streams verify exact multi-round replay and no leakage;
they do not establish support on a real provider model. Real Anthropic/OpenAI
model compatibility remains a separate manual gate.
