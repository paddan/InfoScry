# Ticket 07 — Acceptance coverage

[Spec](../../specs/2026-10-11-investigate-effort-levels.md) ·
[Plan](../../plans/2026-10-11-investigate-effort-levels.md) · [Status](STATUS.md)

**Depends on:** Ticket 04 reviewed and merged AND Ticket 06 reviewed and
merged — this ticket exercises the finished feature end to end through the
real browser acceptance harness (fake providers/embeddings, per
`AGENTS.md`), so both the admin editor and the sidebar selector must exist.

**Scope:** `InvestigateBrowserAcceptanceTest.kt`, the Playwright fixture/page
object under `web/e2e`, and the two docs files named below. Do not touch
production code — if a scenario here reveals a production bug, stop and
report it rather than patching it inside this ticket.

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

## Handoff

Report back: confirmation `./gradlew check` passes on the integrated branch,
and which checks (if any) remain fake-provider-only versus real-provider gates
per the spec. This is the last ticket; after review, the whole branch gets
the final cross-ticket review per STATUS.md.
