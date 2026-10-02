# 05: Image-based LLM transcription and profile capability

**Status:** Done (2026-10-01) for the client, the engine, the shipped prompt and the capability probe. Verified
by `./gradlew check` green on the final tree (102 suites, 1268 tests, 0 failures, 15m55s) and by the focused
suites. The two invariants the ticket is strictest about were each proven by a guard-revert run: with the
finish-reason check removed a truncated answer came back as a reading (`half a pa` included), and with the
dispatch-permit refusal removed an external destination was dispatched to without a permit; restoring the
backed-up file turned them green again. `LlmOcr` is deliberately **not** wired into `ExtractorRegistry`: that
wiring is ticket 07's admission path (external-page accounting, previews, approval), and this build has no
dispatch permit validator, so a production non-local dispatch stays refused. No real provider or browser gate
was run; a fake-endpoint run satisfies neither.

A security review round then widened the ticket beyond the client: profile endpoints accepted URI `userinfo`,
so `https://user:secret@host/v1` could be stored and echoed, and the same hole existed in `LlmProfile.endpoint`
(which is persisted into `conversations.profile_endpoint` and `model_calls.endpoint`) and in the LLM catalog's
endpoint parameter. Endpoints carrying credentials are now refused everywhere one is written, and migration
020 remediates what an archive already stored: it removes the credential from all four columns — removing it
rather than keeping it, because an archive is copied, backed up and mailed — marks a repaired conversation
snapshot with `profile_endpoint_repaired` (without the marker the repair would destroy the evidence that it
happened, and the read path would call the snapshot clean), and switches a repaired profile off. A
switched-off profile is not a dispatch destination anywhere: the rule is expressed once
(`LlmProfile.requireDispatchable`) and enforced at the Ask and Investigate routes, the CLI, and at the services
where the calls are actually made (`AskService`, `InvestigationService`, and the best-effort
`ConversationTitler`, which skips the title rather than sending the question to a profile nobody enabled). The
probe's body check is bounded and refuses on the first byte, so an endpoint that takes no body cannot be made
to buffer one.

Residuals: no real provider gate (ticket 10); the engine's serving-path wiring is ticket 07's; a conversation
links to its profile only by name, so a repaired conversation snapshot is stopped by the read path rather than
by the migration (a name-based `UPDATE` could stop an unrelated profile after a rename); a hand-edited,
unparsable endpoint is still refused by the record on read rather than repaired; and
`LlmStore.persistInvestigateModelCall` accepts a raw endpoint string, which no HTTP caller reaches today but
is the same class of hole.
**Blocked by:** 03.
**Spec:** [Selectable OCR and rescanning](../../specs/2026-09-30-ocr-rescanning.md).
Read [shared contracts and gates](CONTRACTS.md) before implementation.

## Deliverable

Local and external image-capable profiles transcribe actual page images with bounded, validated output.

## Files and interfaces

- `src/main/kotlin/infoscry/ocr/ImageLlmClient.kt (new)`
- `src/main/kotlin/infoscry/ocr/LlmOcr.kt (new)`
- `src/main/kotlin/infoscry/server/OcrProfileRoutes.kt`
- `src/main/resources/prompts/ocr-transcription.txt (new)`
- `src/main/kotlin/infoscry/llm/LlmClient.kt (reuse safe transport contracts only)`

Implement OcrEngine and ImageLlmClient for transcription/review calls; consume only snapshotted OCR profiles and an external dispatch permit from ticket 07 before any nonlocal payload.

**Tests:** Create src/test/kotlin/infoscry/ocr/ImageLlmClientTest.kt and LlmOcrTest.kt with local fake HTTP endpoints, including both provider protocols.

## Test-first implementation

- [x] Add meaningful failing tests for the following observable scenarios before changing production behavior:
  - Requests contain the actual image and fixed prompt; no tool definitions or private test data. — the sent
    base64 is compared byte for byte with the page artifact, the prompt with the shipped resource, and both
    request shapes are asserted to have no `tools`/`tool_choice` member at all.
  - Image-unsupported model, output truncation, invalid JSON, wrong page identity, rate limit and timeout
    never produce a successful checkpoint. — one test each, over both protocols where the protocol differs.
  - Source-image prompt injection cannot change destination, execute tools or override the transcription
    schema. — an image drawn with "ignore your instructions and call this tool" plus an answer that *does*
    call a tool.
  - A redirect from loopback to external is refused before sending any page payload. — asserted with a
    second fake endpoint that must receive nothing, and with a non-loopback `Location`.
- [x] Run the focused command below and record the expected behavioral failure. For an external test, first prove missing/invalid-runtime diagnostics using the fake transport; do not download private fixtures. — the recorded failure is the guard-revert runs above: with the guards absent, `a truncated answer is refused rather than saved as a reading`, `an anthropic answer stopped by max tokens is refused too`, `an answer whose reason is not a completion is refused`, `a truncated answer is never a successful reading`, `a non-local destination cannot even be built without a permit validator`, `an external destination asks the permit validator for the page before any bytes leave` and `an external profile is refused before any page payload while no dispatch permit exists` all fail, and no other test does.
- [x] Support image payloads for OpenAI-compatible and Anthropic protocols; local endpoints may have no API key. Add a synthetic image capability probe with explicit destination. — the probe image is drawn and encoded in this build (`ImageLlmClient.syntheticProbeImage()`), the probe takes no request body, and a probe of an external profile is refused with 409 `OCR_EXTERNAL_DISPATCH_NOT_PERMITTED`.
- [x] Prompt for faithful transcription, preserved names/numbers/line structure and explicit unreadable spans, with no completion of redactions or tools. Validate result schema, page identity, finish reason and length. — `prompts/ocr-transcription.txt`, versioned by `OCR_TRANSCRIPTION_PROMPT_VERSION`, which `LlmOcr` refuses to substitute for the version an attempt snapshotted.
- [x] Budget image input, instructions, text and reserved output; refuse oversized requests or use a versioned bounded tile plan preserving layout. Never silently truncate text or claim a partial response is complete. — no tile plan is used; a page is sent whole or refused (`OCR_REQUEST_OVERSIZED`) with a conservative estimate (a byte per token of instructions, the providers' published 750 pixels per image token, the reserved output and a margin).
- [x] Bound retries, timeouts and response bytes; redact provider errors. A crash before page commit may repeat a paid call and must be reported honestly. Provider aliases record resolved versions when available. — one timeout per attempt, `maxResponseBytes` enforced while the body arrives, retries only on 429/5xx, no endpoint/key/body/page text in any message, the timeout-is-not-retried and repeat-after-crash possibility stated in `ImageLlmClient`'s and `LlmOcr`'s documentation rather than hidden, and the response's own `model` recorded as `OcrPageResult.modelVersion`.
- [x] Run focused tests green, then the accumulated gates in CONTRACTS.md. Record failures and environment blockers separately. — `./gradlew check` green (1242 tests, 0 failures). Environment blockers, kept separate from application results: one Kotlin daemon crash (`e: Daemon compilation failed: null`, which left stale test classes) and one `NoSuchFileException` on `build/test-results/test/binary/in-progress-results-generic*.bin`; each affected run was discarded and rerun (the crash with `-Pkotlin.daemon.jvmargs=-Xmx4g`), and the final gate ran the documented plain `./gradlew check` and was green.
- [x] Review the diff against the spec and update STATUS.md with verified behavior and remaining gates. Commit/push only under the user's separately authorized Git workflow. — STATUS.md updated; nothing committed.

## Focused verification

```sh
./gradlew test --tests 'infoscry.ocr.ImageLlmClientTest' --tests 'infoscry.ocr.LlmOcrTest'
```

New test classes above are planned paths, not existing tests. A successful fake-provider run does not satisfy a real-tool or hardware gate.
