# 04: Browser acceptance and user documentation

**Status:** Not started.
**Blocked by:** 03.
**Spec:** [Compact document list](../../specs/2026-09-30-compact-document-list.md).
Read the [shared constraints and gates](STATUS.md) before starting.

## Deliverable and files

Extend the real Collections browser flow with a multi-page fixture, bounded layout assertions, session restore and live-search behavior. Update usage and verified status while retaining unrelated open gates.

- `web/e2e/collections-browser-acceptance.mjs`
- `src/test/kotlin/infoscry/server/CollectionsBrowserAcceptanceTest.kt`
- `docs/usage.md`
- `docs/implementation-status.md`

**Tests:** Extend CollectionsBrowserAcceptanceTest and its Playwright script with temporary redistributable fixtures/local fake extraction; no private data or real OCR required.

## Test-first steps

- [ ] Add failing tests for these exact observable cases:
  - At desktop and narrow viewports the collapsed table is absent, settings/import/history remain reachable and no document table causes page-width overflow.
  - Expanded table scrollHeight exceeds clientHeight for >50 documents while search/sort and pager sit outside that scrolling box; details stay inside it.
  - Hidden-page failed count opens matching documents; live search, sorting, details and existing Retry/Delete actions remain functional.
  - Leave Admin and return, switch A/B, then reload: session state survives navigation but reload collapses; selections never revive.
  - Record a human keyboard/screen-reader pass or leave it explicitly open. Existing browser scenarios must pass, not only newly added ones.
- [ ] Run the focused command and record a meaningful behavioral failure before implementation.
- [ ] Implement the deliverable against the spec's shared endpoint/state contracts, preserving unrelated work and pinned dependencies.
- [ ] Run focused verification green, then `./gradlew check`; frontend work also runs web `npm run check` and `npm run build`.
- [ ] Review spec coverage, update this ticket and STATUS.md with actual evidence and remaining gates. Do not commit or push without the user's Git authorization.

## Focused command

```sh
./gradlew externalTest --tests 'infoscry.server.CollectionsBrowserAcceptanceTest'
./gradlew check
(cd web && npm run check && npm run build)
```
