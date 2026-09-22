# Task 3 / Task 21 — cited Ask

## Scope

Implemented only the Ask boundary: conservative full-request budgeting, diverse evidence packing, request-scoped citation validation, streaming Ask events, `/api/ask`, and `infoscry ask`. No Investigate, UI, release, schema, or controller-owned documentation changes were included in this commit.

## TDD evidence

- RED: the initial budget test failed because the provider-frame serializer used heterogeneous JSON maps and raised `SerializationException`; the test then failed correctly on the too-small context fixture.
- GREEN: after switching to typed provider-frame DTOs and correcting the fixture window, `RequestBudgetTest` passed.
- Additional tests cover duplicate collapse/document diversity, evidence delimiters, and unknown citation IDs.

## Verification

- `JAVA_HOME="$(asdf where java)" ./gradlew test --tests 'infoscry.ask.*' --tests infoscry.llm.RequestBudgetTest` — PASS.
- `git diff --check` — PASS.
- `JAVA_HOME="$(asdf where java)" ./gradlew check` — PASS, including frontend tests (5/5) and backend tests.

## Design/security decisions

`RequestBudget` serializes roles, escaped content, tools, required-tool metadata, framing, streaming, and output reserve. It uses UTF-8 bytes as an explicitly estimated fallback and never uses the E5 tokenizer. `AskService` calls `requireFits` immediately before the provider flow, so irreducible budget overflow makes no provider call. Evidence is wrapped in delimited source data and prompt rules remain first; citation IDs are allocated per request and validated only against supplied evidence. Error events intentionally avoid question, document text, paths, secrets, and provider payloads.

## Self-review / remaining limits

The existing Task 1–20 persistence layer exposes no answer/evidence tables, so this commit uses the `AskPersistence` seam and does not alter schema-owned files. The route currently buffers the service flow before emitting SSE chunks; the service itself remains streaming and the boundary event contract is stable. A later task may provide durable persistence wiring without changing the Ask core contract.

## Changed files

`src/main/kotlin/infoscry/ask/ContextPacker.kt`, `CitationValidator.kt`, `AskService.kt`; `src/main/kotlin/infoscry/llm/RequestBudget.kt`; `src/main/kotlin/infoscry/server/AskRoutes.kt`; `src/main/kotlin/infoscry/cli/AskCommand.kt`; `src/main/kotlin/infoscry/cli/RootCommand.kt`; `src/main/kotlin/infoscry/server/Routes.kt`; mirrored Ask/request-budget tests.
