# 06: Image-grounded comparison and pilot decisions

**Status:** Done (2026-10-02) for the comparison, the decision policy, the durable reviews and the review
call. Verified by `./gradlew check` green on the final tree (104 suites, 1313 tests, 0 failures, 15m53s) and
by the focused suites (`OcrComparisonTest` 26, `OcrDecisionPolicyTest` 19, `ImageLlmClientTest` 33).

What it does: deterministic diagnostics run first (empty/truncated output, repeated text, suspicious
sequences, missing and added regions, length change, aligned differences, and explicit name, number, date and
negation findings); the reviewer answers about *sides* rather than about existing/new, so the request never
says which reading is the published one and no blind preference for new text is invited, with the side answer
mapped to the application's vocabulary in code where the side assignment is known; in pilot mode every
difference is `PROPOSE`, whatever the reviewer says; identical non-blank text is a no-op; an identically empty
pair is a pending proposal and not a blank page; a failed, timed-out or invalid review leaves the baseline in
place and lists nothing as searchable; reuse is keyed by baseline/candidate hashes, reviewer, prompt and
policy, so a changed reviewer or policy recomputes while transcription calls stay at zero; and a review is
only reused while the page image still matches the hash it was judged from.

Review rounds drove the rest, each with a revert proof. The approval gate took four rounds to become
structural: it began as a caller-implementable predicate, then an `internal`-constructor record (module-wide,
therefore forgeable), then a record resolved per decision, and finally a policy that *holds the review store*
— the seam is deleted, `OcrValidationRecord`'s constructor is private to the store, and its only producer is
that store's own read, so an acceptance cannot be stated, only resolved, and it cannot outlive its row. A
comparison's baseline is now loaded from the revision store and must match the input's unit, ordinal, text and
(where the revision recorded one) hash before any early return, so fabricated baseline text naming a real
revision is refused rather than reviewed. A changed render of the A/B wording bumped the review prompt to v2,
so a v1 attempt is refused as `OCR_REVIEW_PROMPT_MISMATCH` instead of being judged under different
instructions.

Residuals and hand-overs: nothing wires a store-backed policy in production yet (`OcrDecisionPolicy()`
resolves nothing), so approval stays unreachable until ticket 07 passes a store-backed policy and ticket 11
writes an accepted-validation row; `OcrReviewStore.pending()` still filters stored rows, so a stored `APPROVE`
whose acceptance has vanished is not listed for manual decision (ticket 08's surface); the baseline hash is
checked only where the revision recorded one, because pages copied by `recordPublishedContent` carry a NULL
`text_sha256` (ticket 02's file); a compared `PageImage` must be named with the baseline revision's own unit
id, because an extraction key like `page:3` is not a revision unit id — ticket 07 must do that; and writing
`ocr_validation_records` directly is the deliberate trust boundary, since that *is* the archive accepting a
combination.
**Blocked by:** 03, 05.
**Spec:** [Selectable OCR and rescanning](../../specs/2026-09-30-ocr-rescanning.md).
Read [shared contracts and gates](CONTRACTS.md) before implementation.

## Deliverable

Each differing page gets a durable recommendation with reasons and a conservative publication decision; pilot mode is enforced server-side.

## Files and interfaces

- `src/main/kotlin/infoscry/ocr/OcrComparisonService.kt (new)`
- `src/main/kotlin/infoscry/ocr/OcrDecisionPolicy.kt (new)`
- `src/main/kotlin/infoscry/storage/OcrReviewStore.kt (new)`
- `src/main/resources/prompts/ocr-review.txt (new)`

Produce compare(PageComparisonInput): PageReview using the baseline/candidate revisions and image hash. Review output cannot mutate page text; accepted text remains exactly one candidate or a manual edit.

**Tests:** Create src/test/kotlin/infoscry/ocr/OcrComparisonTest.kt and OcrDecisionPolicyTest.kt.

## Test-first implementation

- [x] Add meaningful failing tests for the following observable scenarios before changing production behavior:
  - Plausible invented name, changed date/negation, repeated paragraphs and omitted marginal text are not accepted from fluency/confidence alone.
  - NEW_BETTER remains pending in pilot mode; high self-confidence alone cannot unlock auto acceptance.
  - No baseline plus UNCERTAIN yields searchable=false; existing baseline plus review timeout remains searchable and unchanged.
  - Changed reviewer/policy recomputes comparison but leaves transcription calls at zero; stale baseline invalidates a decision.
- [x] Run the focused command below and record the expected behavioral failure. For an external test, first prove missing/invalid-runtime diagnostics using the fake transport; do not download private fixtures.
- [x] Calculate structured differences and diagnostic metrics; use names/numbers/negations/omission signals without treating dictionary agreement or higher confidence as proof.
- [x] Send image plus labelled A/B texts to the reviewer, avoiding engine names and blind preference for new text. Validate span references and bounded explanations; persist reviewer/policy fingerprint.
- [x] Default all changed text to pending manual review while pilot is active. Identical nonempty text is a no-op; identical emptiness is not proof of a blank page.
- [x] On uncertain/missing/failed review, retain baseline; with no baseline, keep proposal outside all retrieval paths. Review failure must not cause transcription replay.
- [x] Separate model recommendation from policy disposition and human decisions; an unvalidated model cannot enable auto mode through the API.
- [x] Run focused tests green, then the accumulated gates in CONTRACTS.md. Record failures and environment blockers separately.
- [x] Review the diff against the spec and update STATUS.md with verified behavior and remaining gates. Commit/push only under the user's separately authorized Git workflow.

## Focused verification

```sh
./gradlew test --tests 'infoscry.ocr.OcrComparisonTest' --tests 'infoscry.ocr.OcrDecisionPolicyTest'
```

New test classes above are planned paths, not existing tests. A successful fake-provider run does not satisfy a real-tool or hardware gate.
