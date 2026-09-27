# 05: Distinguish provider inactivity from turn deadlines

**What to build:** An active Investigate model stream continues within its available turn budget, a silent provider reports inactivity accurately, and the total turn deadline stays fixed across research, synthesis, and correction.

**Blocked by:** None (can start immediately).

- [ ] Reproduce the current whole-call timeout with a deterministic test of an active stream. Use short injected durations or existing time seams instead of waiting 120 seconds.
- [ ] Provider inactivity means no valid provider event for 120 seconds. Activity resets the inactivity timer; the independent absolute phase/turn deadline does not reset.
- [ ] Inspect both existing provider adapters' event behavior. Count text, usage, and exposed tool-call progress as activity; introduce adapter changes only where required to observe genuine progress consistently.
- [ ] An active stream can run beyond one inactivity interval when its overall budget permits. A silent interval produces a typed inactivity failure with accurate reader copy and honest persisted outcome.
- [ ] Continuous activity cannot extend the total budget. Research stops at its fixed deadline and reserves the smaller of 120 seconds and 20 percent of the budget for synthesis.
- [ ] No new research request or tool is admitted after the research deadline; synthesis or correction cannot run beyond the applicable total deadline. Total exhaustion does not emit successful done.
- [ ] Explicit cancellation propagates as cancellation rather than inactivity or a successful limited answer. Controls recover when the turn ends.
- [ ] Preserve the documented limitation that an in-flight synchronous native tool operation may overshoot; do not introduce unrelated threading or promise a hard cutoff.
- [ ] Deterministic service/adapter tests cover active, silent, deadline-exhausted, and cancelled streams. Route tests verify typed SSE outcomes and persisted records.
- [ ] Browser verification against the local fake provider shows an inactivity error and a subsequent usable follow-up; supporting reader tests distinguish the nonfatal research cutoff from fatal failure.
- [ ] Keep defaults and limits unchanged, update relevant timeout documentation, and report focused results and any remaining timing limitations.
