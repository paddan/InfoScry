# 16: CLI import with a method

**Status:** Not started
**Blocked by:** 04 (collection default method for the no-`--method` case), 05 (summary: pages and destination from `ImportPreviewService`), 06 (the start contract: method, preview hash, request id, idempotence). If those services are called in-process, build against the interfaces in CONTRACTS sections 3-5 with fakes; if the CLI goes through HTTP, against the routes in sections 4 and 5.
**Plan:** [OCR workflow redesign, Task 16](../../plans/2026-10-08-ocr-workflow-redesign.md). Read first: this ticket, [CONTRACTS.md](CONTRACTS.md) (sections 1, 3, 4, 5, 12, 13), `AGENTS.md`, the [spec](../../specs/2026-10-08-ocr-workflow-redesign.md) ("Start dialog"). The standalone CLI import retains process ownership until completion (`AGENTS.md`).

## Problem

The CLI prints approval remedies for a pause that no longer exists and cannot choose a
method.

## Deliverable

`infoscry import` accepts `--method` and `--yes`. It prints the summary (pages,
destination). An external method without `--yes` prompts and aborts on no answer.
Repeating the command does not create a second job. Approval remedy printing is removed.

## Can be built against

The preview service and start contract from CONTRACTS; fakes in `ImportCommandProcessTest`.

## Files and interfaces

- Modify `src/main/kotlin/infoscry/cli/ImportCommand.kt` (add `--method`, `--yes`; remove approval remedy printing at :328 and :398-473).
- The request id for a command invocation must be stable across a repeat of the same command (**chosen**: derive it from collection id, normalized arguments and method, so a repeat gives the same id).
- Test `src/test/kotlin/infoscry/cli/ImportCommandProcessTest.kt`.

## Test-first implementation

- [ ] Write failing tests: without `--method` the collection default is used and the summary (pages, destination) is printed; an external method without `--yes` prompts and aborts on no answer; repeating the command does not create a second job.
- [ ] Run; expect FAIL.
- [ ] Restart test: process killed after the job is enqueued and the command repeated: one job, run completes once.
- [ ] Implement.
- [ ] Run `infoscry.cli.*`; expect PASS.

## Focused verification

```sh
cd /Users/patrik/projects/infoscry
./gradlew test -PskipFrontend --tests 'infoscry.cli.*'
./gradlew check
```

## Out of scope

`docs/cli.md` text (ticket 17); web work.
