# 04: Configure Investigate limits for each question

**What to build:** A reader can set the round, tool-call, and time limits for each Investigate question and follow-up. The selected settings travel through the browser and HTTP API to the service, where each turn enforces the same bounded, cited-answer behavior built in tickets 01–03.

**Blocked by:** 01: Synthesize an answer when the round limit is reached; 02: Synthesize an answer when call or repeat limits stop research; 03: Reserve time to answer when research reaches its deadline.

- [x] The Investigate sidebar exposes accessible native controls below the profile: `Max tool rounds` (1–50, default 50), `Max tool calls` (1–100, default 50), and `Max time per question (seconds)` (10–1800, default 600).
- [x] The settings apply to the next submitted question, including follow-ups, without changing a running turn or the conversation's locked collection/profile/prompt snapshot.
- [x] The browser stores only the three numeric settings under `infoscry-investigate-limits:v1`. Missing, malformed, fractional, wrongly typed, or out-of-range stored values restore defaults; restricted browser storage does not break the reader.
- [x] Controls are disabled during a running turn. Invalid values show field-associated errors and send no request; `Reset defaults` restores all three defaults.
- [x] Start and continue requests send a snapshot of the settings. Older clients omitting `limits` and partial `limits` objects retain default values. The backend validates ranges at both HTTP and service boundaries; invalid HTTP values return 400 before conversation creation, active-turn reservation, SSE, or provider work.
- [x] Focused backend and reader tests cover custom values on start and continue, omitted and partial requests, invalid JSON values and boundaries with no side effects, local-storage reload/fallback, reset, validation, and controls disabled while running.
- [x] README documents defaults, ranges, per-turn behavior, preference persistence, final answers at research limits, and honest time-limit semantics.
- [ ] An integrated browser pass with a fake provider and temporary data verifies a cited answer and notice at a low research cap, changed settings on a follow-up, total-time exhaustion, settings after reload, and working citations.
- [x] Run focused backend/frontend tests, frontend diagnostics, the project check and installDist gates; record exact results and any remaining gate/browser limitation. If the full gate hangs during test-server shutdown, capture a sanitized thread stack after bounded waiting and report it incomplete rather than changing unrelated shutdown code.
- [x] Preserve unrelated working-tree changes. Do not use private source documents or real external providers as tests, restart the user's server, or commit/push.
