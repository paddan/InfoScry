# 02 - Progress counts the pages that are read

## Problem
The dialog says "12 of 48 pages will be read", but once a rescan runs,
`recordProgress` overwrites `pageTotal` with the document's page count because
skipped pages are counted as completed. The progress line then shows 48.

## Scope
`RescanJobHandler` progress writes (`operations.recordProgress`,
`stage.reportProgress`). Count only pages that are read in `pageTotal`, `committed`
and `failed`; a skipped page is neither read nor failed. A resumed attempt derives
`committed` from the candidate and must not double-count skipped pages.

## Acceptance
- Test: two clean pages and one scan give total 1 and finish at 1 of 1.
- Test: interrupt after the scan page is staged, resume, same numbers.
- Existing `alreadyStaged` short-circuit keeps working.

## Implementation and focused verification (2026-10-09)

Operation and job progress count selected pages only. Preserved pages still
enter the complete candidate without increasing reading or failure counters.
On resume, selected checkpoints initialize both committed and visible job
progress; visiting those pages again neither reads nor counts them twice.

The original regression failed because progress included a preserved page. The
handler suite now includes mixed-page publication and interruption after a
durable selected-page checkpoint; the first resumed progress report is checked
before the rest of the loop. The complete 17-test handler suite is also covered
by the accumulated normal JVM gate recorded in
[implementation status](../../implementation-status.md).
