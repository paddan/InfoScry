# Task 1 report: deterministic tool capability measurement

## Result

Task 1 is implemented in the main checkout. `LlmRequest` now accepts an optional
`requiredToolName`, validates that the name is present in the supplied tools, and
the capability probe sets it to `ping`. The OpenAI-compatible adapter emits a
named function `tool_choice`; the Anthropic adapter emits a named `tool_choice`.
The human-readable `llm list` output distinguishes `unknown`, `unsupported`,
and `supported` capability states.

## RED

Added request-body assertions for both provider adapters and a CLI presentation
test. Before the implementation, the required focused command failed during
test compilation because `LlmRequest` had no `requiredToolName` parameter:

```text
No parameter with name 'requiredToolName' found.
```

This was the expected missing-contract failure.

## GREEN and verification

- `JAVA_HOME="$(asdf where java)" ./gradlew test --tests infoscry.llm.OpenAiCompatibleClientTest --tests infoscry.llm.AnthropicClientTest --tests infoscry.cli.LlmCommandTest` — PASS.
- `JAVA_HOME="$(asdf where java)" ./gradlew check` — PASS; frontend tests reported 5/5 passing and the full Gradle check completed successfully.
- `git diff --check` — PASS.

The focused tests capture the local fake server request bodies and verify the
provider-specific named choices. The CLI test verifies all three rendered
states and the existing probe persistence tests continue to cover false and
true measurements.

## Changed files

Production:

- `src/main/kotlin/infoscry/llm/LlmClient.kt`
- `src/main/kotlin/infoscry/llm/OpenAiCompatibleClient.kt`
- `src/main/kotlin/infoscry/llm/AnthropicClient.kt`
- `src/main/kotlin/infoscry/cli/LlmCommand.kt`

Tests:

- `src/test/kotlin/infoscry/llm/OpenAiCompatibleClientTest.kt`
- `src/test/kotlin/infoscry/llm/AnthropicClientTest.kt`
- `src/test/kotlin/infoscry/cli/LlmCommandTest.kt`
- `src/test/kotlin/infoscry/llm/FakeOpenAiServer.kt` (request-body capture used by the wire assertions)

## Self-review and remaining risk

The request contract is narrow: ordinary calls omit `tool_choice`, while only a
request that names one of its supplied tools can force a choice. No provider
body, prompt, key, or authorization value is added to errors or logs. No Ask or
Investigate behavior was introduced.

The existing store reader has a pre-existing null-handling defect (`wasNull()`
is checked after reading another column), so an unchecked profile can appear as
`false` in the serialized profile object. The CLI presentation uses the
capability timestamp to preserve the required three visible states without
expanding this task into the storage file outside the brief's file list. A
follow-up storage fix may be warranted if JSON-level null fidelity is required.
