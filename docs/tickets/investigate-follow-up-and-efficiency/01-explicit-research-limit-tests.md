# 01: Verify research limits with explicit test values

**What to build:** Starting an Investigate conversation or sending a follow-up obeys the submitted research limits and produces a final answer from collected evidence when a limit is reached with time remaining. Boundary tests remain meaningful when defaults change.

**Blocked by:** None (can start immediately).

- [ ] Read the agreed Investigate follow-up and efficiency specification before implementation; preserve unrelated local changes and pinned dependencies.
- [ ] Reproduce the existing focused backend failures and distinguish stale limit assumptions from product defects. Report unresolved failures rather than weakening assertions to obtain a passing run.
- [ ] Tests for specific round and call boundaries submit explicit small limits and provide enough context capacity to reach the intended boundary. Default-value assertions are separate.
- [ ] Defaults remain 50 rounds, 50 admitted calls, and 600 seconds; ranges remain 1–50 rounds, 1–100 calls, and 10–1800 seconds. Invalid limits are rejected before conversation creation or turn reservation.
- [ ] Start and continue requests apply their submitted per-question limits while preserving conversation collection, profile, and retrieval locks.
- [ ] Reaching a round or call limit emits a nonfatal notice and a tool-free final answer from eligible collected evidence while time remains. With no evidence, the result honestly states that there is nothing to answer from.
- [ ] A batch crossing the call limit executes only admitted calls, records a result for every refused call, and preserves complete tool exchanges for synthesis and subsequent follow-ups.
- [ ] Failed admitted calls consume allowance; refused calls do not execute or allocate evidence. Provider failure and total deadline exhaustion remain failures rather than successful limited answers.
- [ ] Existing route tests with the local fake provider verify HTTP/SSE and persistence for start and continue, including final answer, citation, and limit outcomes. Focused failing regressions precede any production repair.
- [ ] The focused investigation service and route suites finish; results, remaining failures, and environmental blockers are recorded accurately. This ticket does not change timeout semantics or repeated-call equivalence.
