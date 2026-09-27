# Reliable Investigate follow-ups and efficient research

## Problem Statement

The reader reports that sending a follow-up question in Investigate appears to do nothing. The stored conversation shows that two follow-ups reached the backend and received successful model responses, so the exact browser failure remains unverified. A previous failure when a completion event omitted empty evidence is already guarded against in current code and local build artifacts.

The investigation also found defects that make continued conversations less useful and more expensive. Previous source excerpts remain in model history but are absent from citation eligibility for the follow-up. Citation correction can therefore receive no allowed evidence even though the model used sources present in its request. Original and corrected answers both become ordinary conversation messages. The provider's advertised inactivity timeout actually limits the whole streaming call, and repeated tool calls are compared by raw JSON formatting. Existing boundary tests no longer consistently match the configured research limits.

## Solution

Make an Investigate conversation reliably continue from its existing questions, final answers, and retained source evidence. Each accepted question must visibly progress to a final answer or an actionable failure. A follow-up may reuse previous evidence without fetching it again, provided that evidence was actually included in the request generating the answer. Reopening a conversation must show one adopted answer per successfully completed question.

Keep research bounded by the existing per-question controls while making timeout messages and repeated-call detection match their actual meaning. Verify the complete reader flow against a local fake provider before describing the reported browser problem as fixed.

## User Stories

1. As a reader, I want to send a follow-up after an answer, so that I can investigate further in the same conversation.
2. As a reader, I want my submitted follow-up to appear immediately, so that I know it was accepted.
3. As a reader, I want visible progress while a follow-up runs, so that a slow response does not look like an ignored question.
4. As a reader, I want each accepted question to end with an answer or a visible failure, so that I know whether the turn completed.
5. As a reader, I want a failed or interrupted stream to release the working state, so that I can continue using the conversation.
6. As a reader, I want a completion without sources to finish normally, so that an omitted empty evidence field cannot break the reader.
7. As a reader, I want a follow-up to understand previous questions and final answers, so that I do not need to repeat the conversation.
8. As a reader, I want follow-ups to cite previously gathered sources, so that useful evidence does not disappear between questions.
9. As a reader, I want already available evidence to be reusable without another search, so that simple follow-ups avoid unnecessary work and cost.
10. As a reader, I want old citations to retain their identifiers, so that a source reference never changes meaning.
11. As a reader, I want newly gathered evidence to receive unused identifiers, so that follow-ups cannot overwrite earlier references.
12. As a reader, I want citations to resolve to their source locations, so that I can inspect the evidence behind an answer.
13. As a reader, I want answers to cite only evidence the generating request contained, so that omitted history cannot silently justify a claim.
14. As a reader, I want citation correction to use the evidence available to the original answer, so that valid old references are not stripped unnecessarily.
15. As a reader, I want context pruning to preserve complete tool exchanges, so that long conversations remain valid for the model.
16. As a reader, I want the current question to survive context pruning, so that the model always answers what I just asked.
17. As a reader, I want one final answer per completed question after reopening a conversation, so that superseded drafts do not look like additional answers.
18. As a reader, I want subsequent questions to use adopted answers, so that discarded answers do not confuse the model.
19. As a reader, I want a reopened conversation to support another follow-up, so that stored work remains usable across reloads.
20. As a reader, I want conversation titles to remain best effort after completion, so that titling does not block my next question.
21. As a reader, I want a stream for an older turn to leave a newer turn alone, so that late completion cannot overwrite current content.
22. As a reader, I want cancellation to stop the current turn and release its controls, so that I can recover from an unwanted investigation.
23. As a reader, I want per-question research limits to apply to follow-ups, so that continuing a conversation stays bounded.
24. As a reader, I want research limits to lead to an answer from collected evidence when time remains, so that reaching a limit does not discard useful work.
25. As a reader, I want an active model stream to continue while its overall budget remains, so that an inactivity timeout does not reject useful progress.
26. As a reader, I want a genuinely silent provider to time out with an accurate message, so that I can distinguish inactivity from overall time exhaustion.
27. As a reader, I want the total turn deadline to remain fixed across research and correction, so that progress cannot extend the budget indefinitely.
28. As a reader, I want equivalent repeated tool calls to be detected despite JSON formatting differences, so that the model does not waste its budget repeating research.
29. As a reader, I want genuinely different tool arguments to remain usable, so that repeated-call protection does not prevent refining a search.
30. As a reader, I want a conversation to retain its collection and model snapshot, so that a follow-up cannot silently switch its research context.
31. As a maintainer, I want rejected or failed turns to retain honest audit state, so that visible success never hides a provider failure.
32. As a maintainer, I want original and corrected model responses to remain distinguishable in audit data, so that provenance is preserved without duplicating reader answers.
33. As a maintainer, I want deterministic boundary tests with explicit research limits, so that changing defaults does not invalidate unrelated tests.
34. As a maintainer, I want browser acceptance to exercise the real reader, HTTP routes, persistence, and fake provider together, so that isolated passing mocks do not conceal a broken follow-up flow.

## Implementation Decisions

- This specifies repairs to the existing Investigate reader, investigation service, history persistence, citation validation, provider timeout handling, and repeated-call admission. It does not authorize implementation by itself.
- Keep one application process, SQLite as authoritative state, thin HTTP routes, and the existing collection-scoped tool boundary. Preserve unrelated local changes and pinned dependencies.
- A turn starts or continues the selected conversation and carries a snapshot of its per-question limits. Conversation collection, profile, and retrieval locks remain in effect.
- Historical evidence must be restored with its stable identifier, source unit, location, and excerpt. Associate evidence with the history groups that carry it into provider requests. Do not treat all persisted ledger entries as automatically eligible.
- Derive citation eligibility from evidence actually included in the generating request after pruning. Apply the same rule to normal final answers, limit synthesis, and citation correction. Keep dropped evidence available for older displayed citations without allowing it to justify a new answer.
- Preserve complete assistant tool-call/result groups when pruning. Protect the core instructions and current question. Keep the existing maximum of one citation correction per answer.
- Adopt one final conversation answer per successful turn. Keep superseded model output in audit state rather than ordinary reader or provider conversation history. Storage representation and any migration must be selected during implementation; no destructive history rewrite is specified.
- Existing conversations with duplicated corrected answers need an explicit compatibility strategy. Do not infer supersession merely because two assistant messages are adjacent; retain ambiguous legacy data rather than silently deleting it.
- Keep the current SSE event contract, including started, delta, tool, usage, citation, limit, answer-start, done, and error. An omitted empty evidence field is equivalent to an empty list. No new event is required by this spec.
- The done event ends the visible turn and releases the reader controls. Draining an older stream for best-effort titling must not modify a newer turn's messages, working state, cancellation controller, or selected conversation.
- A stream closing without a successful done event or a reported failure must not look like successful completion. Show a recoverable interruption state and release controls. Cancellation retains its distinct outcome.
- Provider inactivity means no provider event for 120 seconds. Reset its timer on valid provider events, including text, tool-call progress exposed by the adapter, and usage. Retain the independent absolute deadline for the applicable research or final-answer phase. Verify the event behavior of existing adapters before changing their interfaces.
- Preserve the current per-question defaults: 50 tool rounds, 50 admitted tool calls, and 600 seconds. Preserve validation ranges of 1–50 rounds, 1–100 calls, and 10–1800 seconds. This spec does not retune those values.
- Preserve the synthesis reserve of the smaller of 120 seconds and 20 percent of the turn budget. Do not admit new research after its deadline. Total deadline exhaustion remains a failure rather than a successful limited answer.
- Keep the documented limitation that an in-flight synchronous native tool operation may overshoot; do not promise a hard wall-clock cutoff for uninterruptible work.
- Compare repeated tool calls using the tool name and canonical parsed JSON arguments. Ignore object-key order and formatting, but preserve array order, value types, and distinct values. Do not add fuzzy equivalence of search queries or tool-specific defaults without a separate decision.
- Keep successful and unsuccessful admitted calls within the call budget; refused calls do not execute or allocate evidence, and still close their tool exchanges with a typed result.
- Limit-specific tests must supply explicit limits and adequate context capacity. Test defaults separately. A failure caused by obsolete fixture assumptions is not evidence of a separate production defect.
- Keep source text, questions, answers, credentials, and authorization headers out of diagnostic logs. Browser reproduction uses redistributable fixtures and a local fake provider.

## Testing Decisions

- Prefer one acceptance boundary: the actual browser reader talking to a local InfoScry server, backed by temporary SQLite state and the existing local fake OpenAI-compatible provider. This covers form submission, SSE decoding, service behavior, persistence, reopening, and source references together. The user confirmed this test boundary.
- The primary scenario starts a conversation, gathers evidence, receives a cited answer, sends a follow-up that cites the retained evidence without new tools, reloads and reopens the conversation, and sends another follow-up. Assert visible answers, usable controls, stable citations, one adopted answer per successful turn, and absence of unnecessary research or correction calls in the scripted case.
- Use the existing route-test harness and fake provider as prior art for transport and persisted-history assertions. Use the existing reader tests as prior art for UI state and streamed event handling. Their mocked coverage supplements browser acceptance and cannot replace it.
- Use existing investigation service and persistence seams for deterministic cases that are awkward at the browser boundary: context pruning, citation correction, cancelled turns, refused tool calls, and limits. Add no new production abstraction solely to mirror private implementation details.
- Verify that a historical source retained in the outgoing request remains eligible; after its history group is pruned, its citation becomes ineligible. A new source must receive an unused evidence identifier.
- Verify that correction produces one adopted answer in the reader and later provider history while retaining distinguishable audit records. Cover reopening and legacy ambiguity without asserting an unchosen storage schema.
- Verify completion events both with omitted evidence and with an explicit empty list. Cover HTTP rejection, fatal SSE error, abrupt stream closure, cancellation, and retry after the turn ends. Assert externally visible outcomes rather than reactive variable names.
- Hold the first stream open after done while submitting a follow-up. Verify that the first stream's eventual closure cannot overwrite or unlock the new turn incorrectly.
- Verify timeout behavior with deterministic time control or short injected durations through existing seams. A continuing stream may outlive one inactivity interval within its total budget; a silent interval must time out; continuous activity cannot outlive the total deadline. Do not wait 120 seconds in routine tests.
- Verify repeated-call equivalence with reordered object keys and whitespace, including nested objects. Verify that different values and reordered arrays remain distinct. Assert executed calls and refused outcomes, not the internal key string.
- Exercise configured round and call boundaries with explicit small values, including a provider batch crossing the cap, failed calls consuming allowance, no-evidence synthesis, and exhausted total time. Keep default-value assertions separate from these scenarios.
- Run focused failing regressions before repairs, then focused passing tests and the applicable accumulated project gate. Report command results and any environmental limitation. A mock or fixture result does not establish real provider quality or GPU acceptance.

## Out of Scope

New research tools, retrieval algorithms, model selection, embedding changes, GPU runtime changes, provider benchmarking, default-limit tuning, UI redesign, conversation management features, deployment, packaging, CI, commits, and publication are outside this spec. It does not permit transmitting private documents to an external provider or deleting original source files or ambiguous historical audit data.

## Further Notes

The investigation on 2026-09-27 found three stored user questions, 15 successful model-call records, and nine evidence entries in the local Investigate conversation. The four model calls belonging to its two follow-ups had no recorded eligible evidence. The recorded follow-ups therefore reached the backend; the exact reason for the reported unresponsive browser remains an acceptance question.

At that investigation checkpoint, 76 focused frontend tests passed. The focused backend run completed with 56 passing tests and nine failures out of 65. Several fixtures still assumed earlier round/call defaults, while the current defaults are 50 and 50. These results describe the inspected state and are not an acceptance claim for this specification.

No active InfoScry server was available for the reported browser reproduction. The existing empty-evidence reader guard was present in current source and local build artifacts. Implementation must reproduce and verify the current browser behavior before claiming that the user's original symptom is resolved.
