# 01: Measure tool calling from Admin and explain an unmeasured profile in Investigate

**Status:** Not started. Unchecked criteria are requirements, not evidence.
**Blocked by:** None.
**Plan:** [Local testing feedback](../../plans/2026-10-07-local-testing-feedback.md).

## Problem

Investigate refuses a profile whose tool calling was never measured (`TOOL_CALLING_UNSUPPORTED`, "the profile '…'
has not been measured for tool calling"). The only way to measure it is the CLI (`infoscry llm test <name>`), and
the reader sees "nothing happens".

## Deliverable

A person can measure an LLM profile's tool calling from Admin → LLM profiles, and Investigate says plainly why a
profile cannot be used and what to do.

## Files and interfaces

- `src/main/kotlin/infoscry/server/LlmProfileRoutes.kt`: `POST /api/llm/profiles/{id}/probe` running the same
  two-request probe as `src/main/kotlin/infoscry/cli/LlmCommand.kt` (`llm test`) through a shared service, and
  persisting `tool_calling_measured` / `capability_checked_at` the same way.
- `web/src/lib/LlmAdminPanel.svelte`, `web/src/lib/api.ts`: a "Check tool calling" action with the measured state.
- `web/src/lib/InvestigatePanel.svelte`: the refusal is shown next to the profile select with the remedy; profiles
  measured as unsupported or not measured are marked in the select.

## Test-first implementation

- [ ] Failing tests first: the probe route measures and persists through a fake provider, refuses a switched-off
  profile, never echoes a key or provider body; the CLI and the route share one implementation; the Admin action
  dispatches once on repeated clicks and shows the result; Investigate shows the `TOOL_CALLING_UNSUPPORTED` message
  and the remedy instead of doing nothing.
- [ ] Implement; keep the route thin over the shared service.
- [ ] Browser scenario: an unmeasured profile is refused with the explanation, measured from Admin, then a
  question runs (fake provider).

## Focused verification

```sh
./gradlew test -PskipFrontend --tests 'infoscry.server.LlmProfileRoutesTest' --tests 'infoscry.server.InvestigationRoutesTest'
(cd web && npm test -- --run && npm run check)
```
