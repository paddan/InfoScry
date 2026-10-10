# 03 - Keep the document page count with a stored preview

## Problem
`RescanPreview.documentPages` is returned by the live preview but `recordPreview`
does not store it, so a preview read back from the database has null and the dialog
loses "(of N in the document)".

## Scope
Store the field wherever `RescanPreview` is persisted (check `OcrOperationStore`
and the serialized preview). Add a migration only if the preview is stored in
columns; if it is stored as JSON, add the field with a default.

## Acceptance
- Round-trip test: preview, store, read back, `documentPages` unchanged.
- A preview stored before this change still decodes (default null).

## Implementation decision (2026-10-09)

The owner declined all existing-data migrations and conversions and will reset
the database. The nullable `document_pages` column is added directly to the
fresh baseline; schema version remains 1. Existing databases must be recreated
before running this version. No upgrade/backfill path is implemented. Null
values in the fresh schema remain supported.

## Implementation and focused verification

`OcrOperationStore.recordPreview` and `StoredPreview` round-trip nullable
`documentPages` through `ocr_rescan_previews.document_pages`. The fresh baseline
also permits zero preview pages for ticket 01; operation page totals remain
positive or unknown. Real SQLite tests cover a known count and null. Focused
preview, operation-replacement and schema tests passed; accumulated verification
is recorded in [implementation status](../../implementation-status.md).
