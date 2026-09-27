# 06: Detect equivalent repeated tool calls

**What to build:** Investigate stops wasting research on an identical tool request even when its JSON formatting changes, while allowing genuinely different arguments and finishing from evidence already collected.

**Blocked by:** None (can start immediately).

- [ ] Reproduce repeated calls with equivalent JSON but different whitespace or object-key order that currently execute more than once.
- [ ] Compare tool name and canonical parsed JSON arguments, ignoring formatting and object-key order recursively. Preserve array order, value types, and distinct values.
- [ ] Different tool names or genuinely different arguments remain eligible. Do not introduce fuzzy query matching or inferred equivalence of tool defaults.
- [ ] Malformed arguments retain their typed failure behavior without breaking repeated-call admission or the turn; admitted failures still consume the configured call allowance.
- [ ] An equivalent repeated call is refused without execution or evidence allocation. Its result closes the assistant tool exchange, including the rest of a batch stopped by that limit.
- [ ] The stream reports the nonfatal repeated-call limit and synthesizes from eligible collected evidence while time remains. No-evidence, provider-failure, and total-time-exhaustion outcomes remain honest.
- [ ] Explicit small limits isolate repetition behavior from default round/call values. Supporting tests include nested reordered objects, changed values, changed types, and reordered arrays.
- [ ] Route tests with a local fake provider verify executed/refused outcomes, persisted activity, complete tool exchanges, and the final SSE answer rather than the internal comparison key.
- [ ] Browser verification shows the repeat notice and final answer, then permits another question. Reuse the local acceptance boundary and redistributable fixtures.
- [ ] Focused failing regressions precede the repair, relevant tests pass afterward, and documentation reflects the exact equivalence rule without promising broader semantic deduplication.
