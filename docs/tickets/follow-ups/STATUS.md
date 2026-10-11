# Remaining OCR product choices

[OCR workflow reference](../../ocr-workflow.md) · [Implementation status](../../implementation-status.md)

Implementation follow-ups and the remaining OCR/PDF checks are complete, the
latter confirmed by Patrik on 2026-10-11. Completed tickets were removed; reports
remain in Git history. No new agent-run provider or hardware checks were performed.

Two product choices remain undecided: whether to refine the current per-page token
cost estimate, and whether to offer Restore as an immediate Undo action on the
finished-run status. Neither is implementation scope without a further request.

Existing file/folder filters, case-insensitive ignore matching and fixed 25% image /
75 text-quality thresholds are retained. Existing-data migration/profile conversion
was declined; the baseline applies to fresh/reset databases only.
