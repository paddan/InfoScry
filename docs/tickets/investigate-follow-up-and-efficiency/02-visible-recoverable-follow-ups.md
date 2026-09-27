# 02: Make follow-ups visibly progress and recover

**What to build:** Sending a follow-up visibly submits the question and ends with an answer or an understandable failure. Interruptions and late events leave the reader usable rather than appearing to ignore the next question.

**Blocked by:** None (can start immediately).

- [ ] Reproduce the reported flow in the actual browser reader against a local InfoScry server, temporary persisted state, redistributable fixtures, and a local fake provider. Record whether the original symptom reproduces before proposing a cause.
- [ ] Prefer the existing route harness and fake-provider boundary; create only the minimal reusable acceptance setup needed for the real reader. Mocked API tests supplement this acceptance setup.
- [ ] First question and follow-up display their submitted question and visible activity, then adopt the done answer or show a reported failure. A blank or duplicate submission is not admitted while a turn is running.
- [ ] Done with omitted evidence and done with an explicit empty list both complete normally and release the controls.
- [ ] HTTP rejection, fatal SSE error, and stream closure without done or an already reported failure produce an understandable failure or interruption state. None is represented as successful completion.
- [ ] After completion or failure, a further question can be submitted. Cancellation has its own outcome and releases the current turn's controls.
- [ ] Done releases the visible working state before best-effort titling finishes. A follow-up can start while the previous stream remains open.
- [ ] Late closure or events from the previous stream cannot replace the newer answer, reset its working state, change its cancellation controller, or move the selected conversation.
- [ ] Reopening a saved conversation loads its history before enabling a follow-up; obsolete history loads cannot overwrite a newer selection or active turn.
- [ ] Browser acceptance covers a complete first question and follow-up, plus at least an interruption-and-retry case and overlap with the previous stream draining. Supporting reader/API tests cover the other terminal outcomes.
- [ ] No private source material is sent to external services. Report actual browser results separately from mocks; do not claim the original symptom fixed if it was not reproduced or its replacement behavior was not verified.
