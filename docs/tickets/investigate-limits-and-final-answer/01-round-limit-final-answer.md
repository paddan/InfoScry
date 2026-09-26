# 01: Synthesize an answer when the round limit is reached

**What to build:** When an Investigate turn reaches its configured research-round limit, it stops asking the model for more research and answers from the evidence it has already gathered. The reader replaces any provisional streamed text with that final answer, shows that the round limit was reached, and keeps the cited result in conversation history.

**Blocked by:** None (can start immediately).

- [x] A round is one model response containing one or more tool calls. After completing the configured number of rounds, the service starts no further tool-enabled research request.
- [x] The service makes at most one tool-free synthesis request, using only evidence included in that request. If no evidence was gathered, it gives an honest no-evidence answer without a provider call or invented citations.
- [x] Synthesis uses the normal request budget and citation validation. Only IDs actually supplied to synthesis can be valid citations; if context pruning omitted evidence, its ID cannot become valid through validation or correction.
- [x] A successful limited turn emits a nonfatal `limit` event with code `MAX_ROUNDS`, then `answer-start` before replacing provisional text, followed by applicable synthesis deltas, citation events, cumulative usage, and exactly one `Done`.
- [x] The final answer, model-call usage, evidence, and limit event are persisted before `Done`. Synthesis failure, irreducible context overflow, or a synthesis response that attempts tools is a typed failure, not a successful completion; no returned tool is executed.
- [x] The SSE route carries the new event shapes without changing existing event shapes. The Investigate reader keeps the limit notice visible beside the final answer, clears provisional text on `answer-start`, and does not treat the notice as a fatal error or unlock a live turn early.
- [x] Focused service, route, and reader tests exercise a low configured round limit through their public seams with fake providers and temporary data, including event order, cited synthesis, persistence, and failure handling. An unrestricted turn still completes as before.
- [x] No private source documents or real external provider requests are used in tests.
