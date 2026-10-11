# Investigate effort: remaining verification

[Current contracts](../../investigate-effort.md) · [Implementation status](../../implementation-status.md)

The seven implementation tickets are complete and were removed after preserving
current contracts in the reference guide. Work was implemented and reviewed inline,
without subagents, on 2026-10-11. No commit or push was part of implementation.

Remaining manual gate: verify opt-in native reasoning on the specific real
Anthropic/OpenAI-compatible model and endpoint the owner intends to use. Local
fake-provider tests and browser acceptance do not establish real-model support.
Existing version-1 archives also require separately authorized recreation before
using the new baseline column; no user database was reset or migrated.
