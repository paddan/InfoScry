# 03: Reserve time to answer when research reaches its deadline

**What to build:** When an Investigate turn reaches its ten-minute research deadline, it stops admitting more research while time remains to prepare an answer from collected evidence. If the total time is exhausted before finalization can finish, the turn fails honestly instead of reporting a successful answer.

**Blocked by:** 02: Synthesize an answer when call or repeat limits stop research.

- [x] The service uses a monotonic, testable turn deadline. The research deadline leaves `min(120 seconds, 20% of the total turn budget)` for synthesis; conversation titling after completion is outside the budget.
- [x] Time spent preparing requests, on provider operations, and on citation correction belongs to the same turn budget; the timer is not reset between rounds. Each provider operation is bounded by both its existing call cap and the remaining applicable time.
- [x] No new research request or tool is admitted after the research deadline. An already-running synchronous native operation may finish and overshoot; no unrelated work is moved to arbitrary threads to simulate hard cancellation.
- [x] When the research deadline is reached with budget remaining, the stream emits a nonfatal `TURN_TIMEOUT` limit notice and uses ticket 02's finalization path. If the total deadline expires, synthesis/correction does not start or continue past the budget, a typed timeout failure is persisted and emitted, and no successful `Done` is produced.
- [x] Explicit user cancellation is propagated unchanged and recorded as cancelled; it does not become a final answer or a misleading ordinary timeout.
- [x] Timeout handling leaves the stream able to persist and deliver a typed failure without cancelling the collector itself. Provider and synthesis failures remain failures; they are not converted into successful limited answers.
- [x] Deterministic tests with an injected monotonic clock cover research cutoff, reserved synthesis time, a provider call bounded by remaining time, exhausted total time, no tool admission after cutoff, and cancellation without long sleeps.
- [x] The route and reader distinguish the nonfatal research-cutoff notice from fatal total-time exhaustion. Copy does not promise a strict wall-clock cutoff for uninterruptible synchronous work.
