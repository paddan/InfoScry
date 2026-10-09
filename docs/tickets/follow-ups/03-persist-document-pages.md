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
