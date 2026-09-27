# 03: Reuse retained evidence in follow-ups

**What to build:** A follow-up can answer with citations to sources already gathered in the conversation without researching them again. Only evidence present in the request generating that answer is eligible.

**Blocked by:** None (can start immediately).

- [ ] Reproduce a follow-up that cites an earlier source without issuing new tools and currently loses that source from citation eligibility.
- [ ] Restore historical evidence with its stable identifier, source unit, location, and excerpt; associate it with the history groups that actually carry it into provider requests.
- [ ] Normal final answers, limit synthesis, and citation correction use eligibility derived from the generating request after pruning. Persisted ledger membership alone never makes evidence eligible.
- [ ] A retained historical source can be cited without unnecessary research or correction in the scripted successful case. Correction retains the generating answer's eligible evidence and remains limited to one attempt.
- [ ] Pruning a historical group removes its evidence from the new answer's eligibility while preserving complete assistant tool-call/result groups, core instructions, and the current question.
- [ ] Older displayed citations still resolve after their evidence is omitted from a later request. New evidence receives an unused identifier and never overwrites an existing source reference.
- [ ] Reopening a persisted conversation and sending another follow-up preserves the same evidence and citation behavior; collection and profile locks remain intact.
- [ ] Existing service/persistence seams cover retained and pruned historical evidence deterministically. Route tests with a local fake provider verify outgoing evidence, persisted eligibility, and streamed citation results.
- [ ] Verify the cited first-question/follow-up/reopen path in the actual browser against the local fake provider, reusing the acceptance setup if available. A standalone run may establish the same boundary if ticket 02 is not yet complete.
- [ ] Reader source actions resolve historical citation locations correctly, including after reopening. Focused regressions fail before the repair and pass afterward; results do not claim unrelated browser gates complete.
