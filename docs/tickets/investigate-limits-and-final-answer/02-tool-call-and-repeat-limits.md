# 02: Synthesize an answer when call or repeat limits stop research

**What to build:** An Investigate turn stops safely when it reaches its configured tool-call cap or the repeated-call guard, then returns the same kind of cited final answer as a round-limited turn. Every model-issued call in a persisted exchange has a matching result, including calls refused at the limit.

**Blocked by:** 01: Synthesize an answer when the round limit is reached.

- [x] The service executes no more than the configured number of tool calls. Failed calls count once admitted for execution; calls refused after the cap do not execute or add evidence and are recorded with an explicit refusal result.
- [x] If a provider response contains more calls than the remaining allowance, calls within the allowance execute and every other call receives a bounded refusal result. Each assistant tool-call ID has exactly one matching tool result before synthesis or any later request, and reopened history preserves the complete exchange.
- [x] Repeating an identical tool call stops further research with a nonfatal `REPEATED_TOOL_CALL` limit event and goes directly to finalization; it does not start another tool-enabled provider request.
- [x] Both stop conditions use ticket 01's tool-free, evidence-bounded synthesis path and preserve its citation validation, `answer-start`, usage, persistence, and single-`Done` behavior.
- [x] Reader activity distinguishes refused calls from executed calls, while the limit notice remains visible next to the final answer and is not treated as a fatal error.
- [x] Focused service and route tests cover a provider batch crossing the remaining allowance, failed executed calls counting toward the limit, repeated calls, complete persisted/reopened tool exchanges, and no further research request. A reader test verifies the visible limit/refusal behavior.
- [x] Tests use fake providers and temporary data only; do not use private documents or send requests to a real provider.
