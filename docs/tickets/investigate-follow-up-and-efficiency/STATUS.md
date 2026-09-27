# Investigate follow-up and efficiency — verified status (2026-09-27)

Scope: implementation status of tickets 01–07 against
[`docs/specs/investigate-follow-up-and-efficiency.md`](../../specs/investigate-follow-up-and-efficiency.md).
This journal records what was actually verified, on which boundary, and what
remains open. It does not claim browser acceptance.

## Commands run and real results

- `./gradlew test --offline` — **903 tests, 0 failures, 0 errors** (default suite;
  hardware/external tags excluded by the build). Includes the focused Investigate
  service, route, storage, and schema suites and the frontend build.
- `./gradlew externalTest --offline` — **8 tests, 0 failures** (seven real-browser
  Investigate acceptance scenarios plus the machine's real OCR test).
- `./gradlew test --offline -x frontendInstall -x frontendBuild` focused on
  `infoscry.investigate.*`, `infoscry.server.InvestigationRoutesTest`,
  `infoscry.storage.SchemaMigratorTest`, `infoscry.llm.LlmStoreTest` — green.
- `cd web && npx vitest run` — **7 files, 116 tests, 0 failures**.
- `cd web && npx svelte-check --tsconfig ./tsconfig.json` — **0 errors, 0 warnings**.
- `cd web && npx vite build` (also via Gradle `processResources`) — built.

Dependency note: `playwright@1.63.0` was added as an exact devDependency of
`web/` for the browser acceptance. It is the reason for the `package-lock.json`
change and is the only new dependency.

One earlier interrupted run failed with
`NoSuchFileException: build/test-results/test/binary/in-progress-results-*.bin`
after the frontend-install task raced a partially written `node_modules`. A clean
rerun passed; this was environmental, not a test failure.

## Independent review corrections

A fresh-context read-only review of the seam diff found four issues, all fixed and
covered by tests:

1. Pre-migration ledger rows have no `message_seq`, so their evidence stayed
   ineligible. `loadInvestigateHistory` now recovers the introducing exchange
   from the visible tool result that names the evidence id; evidence whose tool
   exchange was dropped from provider history stays ineligible.
2. A successful-but-empty correction used to supersede the draft and leave no
   reader answer. An empty correction is now recorded as a failed correction and
   the draft stays adopted.
3. Inactivity was measured after `onEvent` returned. It is now measured from the
   provider event's arrival, so backpressure cannot silently extend the interval;
   the absolute deadline still bounds the wait.
4. The repeated-call key was a `name + ":" + arguments` string, which malformed
   arguments could collide with. It is now a structured `(name, arguments)` key.

## Ticket 01 — explicit research-limit tests

Verified: boundary tests submit explicit small limits (`roundLimit`,
`maxToolCalls=1/20`); the shipped-default assertion lives in its own
`roundCapLimits()` test; defaults are 50/50/600 and ranges 1–50 / 1–100 /
10–1800; invalid limits are rejected before conversation creation or turn
reservation; start and continue apply their submitted limits under the locked
conversation snapshot; a batch crossing the call cap executes admitted calls and
records a result for every refused call; failed admitted calls consume allowance;
provider failure and total-deadline exhaustion stay failures.
The nine stale-assumption failures reported in the 2026-09-27 diagnosis are gone
at the current baseline; no production defect was found for this ticket.

## Ticket 02 — visible, recoverable follow-ups

Reader-level verified (component level, mocked API boundary):

- Submitted question and visible activity; blank/duplicate submissions refused
  while a turn runs.
- Done with omitted evidence **and** with an explicit empty list both complete
  and release controls.
- A stream that closes with no `done` and no `error` now reports a recoverable
  interruption and releases controls (the previously silent-completion defect);
  HTTP rejection and fatal SSE errors report failures; cancellation has a
  distinct outcome and aborts before the server round trip.
- Done releases the working state before best-effort titling; a follow-up can
  start while the previous stream drains; late events/closure from the old stream
  cannot replace the newer answer or its controller.
- Reopening loads history before enabling a follow-up; obsolete history loads
  cannot overwrite a newer selection.

**Not verified:** the reported real-browser flow. No browser harness exists in
this repository.
## Ticket 03 — reuse retained evidence

Verified:

- A continued turn restores the full evidence ledger with stable id, source unit,
  locator JSON, excerpt, and the seq of the introducing tool exchange; a follow-up
  cites a retained source with no new tool call and no correction, and the source
  appears in the outgoing request.
- Citation eligibility is the union of evidence introduced by history groups that
  survive request pruning. Evidence whose group was pruned is ineligible for a new
  citation and is not re-authorized by correction; its group's tool-call/result
  messages are pruned as a whole.
- Older displayed citations still resolve because the ledger is returned in full
  by the history endpoint; a new source receives an unused `S<n>` id.
- Persisted eligibility is derived from the generating request, not ledger
  membership: a legacy entry with no introducing message seq is never eligible.

## Ticket 04 — one adopted answer

Verified:

- A successful correction marks the streamed draft `superseded` and adds the
  corrected answer as the adopted one; the route's history endpoint and
  `loadInvestigateHistory` both expose only the adopted answer.
- Reopening shows the adopted answer once; the superseded draft remains on its
  message row as distinguishable audit data. Both model calls (draft and
  correction) keep their usage and cost.
- A failed correction keeps the original answer adopted. Cancelled/failed turns
  acquire no fabricated answer.

Compatibility: migration 009 defaults existing rows to `superseded = 0`. Legacy
conversations that contain both an original and a corrected assistant answer stay
ambiguous and are preserved unchanged — no silent deletion and no invented
final-answer selection.

## Ticket 05 — provider inactivity vs turn deadline

Verified with deterministic short inactivity intervals:

- An active stream whose inter-event gaps are each under the cap but whose total
  exceeds one interval completes.
- A genuinely silent provider reports `PROVIDER_TIMEOUT`, not a deadline failure.
- Continuous progress cannot extend the total budget: the turn ends with a
  `TURN_TIMEOUT` outcome and never completes the provider's full script.
- Cancellation still propagates as cancellation.

Design: the inactivity timer resets on every provider event (the OpenAI-compatible
and Anthropic adapters already emit text, usage, and tool-call progress); the
absolute research/total deadline is never reset. Research stops at its fixed
deadline and reserves `min(120 s, 20 % of budget)` for synthesis. Documented
limitation preserved: one in-flight synchronous native tool call may overshoot.

## Ticket 06 — equivalent repeated tool calls

Verified: tool name + recursively canonical parsed JSON arguments (object keys
sorted; array order, value types, and values preserved). Reordered nested keys and
whitespace compare equal and are refused without execution or evidence allocation;
different values, different types, and reordered arrays remain executable;
malformed arguments keep their typed failure and their own raw key. A refused call
receives a typed `LIMIT_REACHED` result, closes the batch, and the turn
synthesizes from collected evidence or answers honestly with no evidence.

## Ticket 07 — integrated acceptance

Verified at the real-browser boundary **and** at the HTTP/SSE + persistence +
reader-component boundary.

`./gradlew externalTest` (`InvestigateBrowserAcceptanceTest`, tagged `external`)
drives the actual built reader in Chromium (`web/e2e/investigate-browser-acceptance.mjs`)
against a local InfoScry server backed by the local scripted fake provider,
temporary SQLite state, and a redistributable text fixture. Eight external tests
pass (seven browser scenarios plus the machine's real-tool OCR test); the browser
scenarios are:

- `primary` — first cited question, retained-evidence follow-up with no new tools
  and no correction, click a citation and read the source, reload/reopen, another
  follow-up. Asserts one adopted answer per turn, stable `S1`, and no console
  errors; the route/server side asserts exactly five provider calls (research,
  first answer, title, two follow-ups) with no correction.
- `correction` — an invalid draft citation is corrected once; the reader shows one
  adopted answer and never the draft, before and after reload; the database keeps
  two assistant rows with exactly one `superseded = 1`.
- `rejection` — an HTTP rejection is a visible failure and the next question recovers.
- `emptyEvidence` — a completion with an omitted evidence field finishes normally and survives a reopen.
- `repeatLimit` — an equivalent repeated tool call stops research with a visible
  notice and a cited answer; a follow-up then reuses the retained evidence.
- `roundLimit` — setting the sidebar round limit to one stops research after the
  first round with a visible notice and a cited answer.
- `cancel` — cancellation releases the controls and a further question succeeds.

Supporting coverage (no browser):

- Route tests with the local fake provider exercise the same retained-evidence
  follow-up and correction persistence paths at the HTTP/SSE level.
- Reader component tests cover interruption, HTTP rejection, fatal error,
  cancellation, omitted/empty evidence, a follow-up during a previous stream's
  drain, and stale history loads.
- Service tests cover provider inactivity, the fixed total deadline, context
  pruning, refused tool calls, and legacy-evidence recovery deterministically.

Collection/profile locks and reopened history are exercised by the route/service
suites and the browser `primary` scenario; ambiguous legacy answer pairs are
preserved unchanged (see ticket 04).

**Limits of this evidence:**

- The provider is the local fake. This is not real-provider or GPU acceptance and
  is not claimed as such.
- The original "follow-up appears to do nothing" symptom was **not** reproduced
  in a browser against the pre-fix code; the browser acceptance verifies the
  current reader completes follow-ups, not a before/after reproduction.
- Ask browser acceptance and the source-viewer finish line remain open.
- Legacy ambiguous answer pairs are preserved rather than reconciled, by design.
