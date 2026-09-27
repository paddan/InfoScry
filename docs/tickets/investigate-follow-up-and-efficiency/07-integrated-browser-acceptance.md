# 07: Verify the complete Investigate conversation

**What to build:** A reader can complete a cited investigation, ask follow-ups, reopen the conversation, and continue again with stable sources, one adopted answer per completed new turn, bounded research, and recoverable failures.

**Blocked by:** 01: Verify research limits with explicit test values; 02: Make follow-ups visibly progress and recover; 03: Reuse retained evidence in follow-ups; 04: Keep one adopted answer in conversation history; 05: Distinguish provider inactivity from turn deadlines; 06: Detect equivalent repeated tool calls.

- [ ] Verify all preceding tickets' acceptance evidence and unresolved limitations; unchecked criteria and plan checkboxes alone do not prove implementation.
- [ ] Run the confirmed boundary: actual browser reader → local InfoScry server → local fake provider, with temporary persisted state and redistributable fixtures. Exercise real form submission, HTTP/SSE decoding, service behavior, persistence, and source actions together.
- [ ] Complete first question with gathered evidence → follow-up using retained evidence without new tools → reload/reopen → another follow-up. All submitted questions visibly complete, adopted answers remain singular, citation IDs remain stable, and source references open the correct locations.
- [ ] In the scripted retained-evidence case, no unnecessary research or citation correction occurs. A separate invalid-citation case corrects once and preserves audit provenance without duplicate conversation answers.
- [ ] Verify that a prior stream kept open after done can close during a newer turn without altering that turn's content, controls, or selected conversation.
- [ ] Exercise HTTP rejection, fatal provider error, abrupt stream closure, and cancellation followed by recovery. Omitted and explicitly empty done evidence both complete normally.
- [ ] Verify configured research cutoff and repeated-call synthesis with visible notices and final answers. Verify inactivity and total-time outcomes using deterministic supporting tests and browser checks that do not require long sleeps.
- [ ] Verify collection/profile locks, restored history, and safe treatment of ambiguous legacy answers. Report any legacy compatibility limitation explicitly.
- [ ] Run focused regressions and the applicable accumulated project gate, including frontend type checking/build and relevant backend checks. Record completed commands, failures, and environmental blockers; do not treat a hanging or incomplete command as passing.
- [ ] Reconcile README, specification, diagnosis status, and ticket evidence with actual results. Distinguish the historical reported symptom from currently reproduced defects and state whether the real browser reproduction now succeeds.
- [ ] Journal the verified final status. Do not contact external providers with private documents, claim GPU or real-provider acceptance from fixtures, commit, or publish as part of this ticket.
