# Investigate effort levels: ticket status

[Spec](../../specs/2026-10-11-investigate-effort-levels.md) ·
[Plan](../../plans/2026-10-11-investigate-effort-levels.md)

Seven agent-sized tickets, mirroring the plan's seven tasks one-to-one.
Written 2026-10-11; none yet dispatched.

| Ticket | Depends on | State |
|---|---|---|
| [01 — Effort model](01-effort-model.md) | None | Not started |
| [02 — Profile persistence and resolver](02-profile-persistence-and-resolver.md) | 01 reviewed | Not started |
| [03 — LLM request builders](03-llm-request-builders.md) | 01 reviewed | Not started |
| [04 — Admin API and UI](04-admin-api-and-ui.md) | 02 reviewed | Not started |
| [05 — Investigate route wiring](05-investigate-route-wiring.md) | 02 and 03 reviewed | Not started |
| [06 — Frontend effort selector](06-frontend-effort-selector.md) | 04 and 05 reviewed | Not started |
| [07 — Acceptance coverage](07-acceptance-coverage.md) | 04 and 06 reviewed | Not started |

## Dispatch order

```text
01 → review ─┬─ 02 → review ─┬─ 04 → review ─┐
             └─ 03 → review ─┴─ 05 → review ─┴─ 06 → review → 07
```

After 01 is reviewed, 02 and 03 may run in parallel (02 owns profile/schema/
store/resolver; 03 owns LLM transport and exhaustive event consumers). 04 can
start after 02, in parallel with 03/05. 05 needs 02 and 03. 06 needs BOTH 04
and 05, because 04 and 06 sequentially edit `api.ts`. 07 follows 06 and 04.

With a coordinator plus two agents, 02+03 is the first parallel pair; 04 can
join as a third once 02 lands. Do not run several full Gradle builds against
the same output directories simultaneously.

## Shared-file ownership

- `EffortLevel.kt`: 01 creates it (types + `EffortDefaults`); 02 adds
  `EffortResolver` to the same file afterward — sequential, not parallel,
  since 02 cannot start until 01 is merged anyway.
- `InvestigationLimits.kt` serialization annotation: 01 only; no validation/default changes.
- `LlmProfiles.kt`, baseline schema, `LlmStore.kt`: 02 only. Fresh/reset database prerequisite; no migration or user-data reset.
- `LlmClient.kt`, `AnthropicClient.kt`, `OpenAiCompatibleClient.kt`, exhaustive event consumers such as `AskService.kt`: 03 only. Freeze lossless envelope/event contract before 05.
- `LlmProfileRoutes.kt`, `LlmAdminPanel.svelte`: 04 only.
- `InvestigationService.kt`: 03 adds minimal exhaustive-event ignore branches; 05 then adds envelope collection/plumbing after 03 is reviewed. Sequential ownership.
- `InvestigationRoutes.kt`, `InvestigationBudgetPruner.kt`: 05 only.
- `web/src/lib/api.ts` and its tests: 04 adds profile/shared effort types and serializers; 06 then updates start/continue/SSE client paths. Sequential ownership.
- `investigationLimits.ts` and the sidebar component: 06 only.
- Browser acceptance test, `web/e2e` fixtures, `docs/usage.md`,
  `docs/technical-reference.md`: 07, after every production owner releases
  its files.

## Integration and review gates

Each ticket gets its own review before the next dependent ticket starts (per
the plan's recommended subagent-driven execution: a fresh reviewer checks
each task before the next begins). After 07, run a whole-branch review
against the plan's **Review Focus** section (eight items, each already mapped
to its owning ticket above) before calling the feature done. `./gradlew
check` is the integrated gate; it must pass once, at the end, on the fully
merged branch — not repeatedly mid-flight against partial work.
