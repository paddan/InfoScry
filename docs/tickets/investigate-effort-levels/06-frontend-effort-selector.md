# Ticket 06 — Frontend effort selector with resolved-caps hint

[Spec](../../specs/2026-10-11-investigate-effort-levels.md) ·
[Plan](../../plans/2026-10-11-investigate-effort-levels.md) · [Status](STATUS.md)

**Depends on:** Tickets 04 AND 05 reviewed and merged (04 owns shared effort/profile
types in `api.ts`; 06 must not race its edits). Ticket 05 supplies the `resolvedLimits`
field on the start-of-turn SSE event and the `effortLevel` request field.

**Scope:** `web/src/lib/api.ts`, `web/src/lib/investigationLimits.ts` and the Investigate sidebar
component that consumes it. Do not touch `LlmAdminPanel.svelte` (ticket 04,
unrelated page) or any backend file.

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

## Handoff

Report back: confirmation both test files pass, the exact sidebar component
path(s) touched, and a one-line description of where the hint renders.
Ticket 07's acceptance scenario needs that location to assert against.
