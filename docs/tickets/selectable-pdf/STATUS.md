# Remaining selectable-PDF integration work

[Feature reference](../../selectable-pdf.md) · [Contracts](../../selectable-pdf-contracts.md) ·
[Implementation and verification](../../implementation-status.md)

Implementation and the OCR/PDF checks are complete, the remaining checks confirmed
by Patrik on 2026-10-11. Completed implementation and verification tickets have
been removed; reports remain in Git history.

## First-import automatic generation

A plain first import commits through `StoredUnitsSink`, bypassing the publication
hook. It still requires **Create PDF with selectable text**; rescan and Restore
queue generation automatically. Wiring automatic generation for plain imports,
or accepting explicit creation for that case, remains a separate product/integration
decision. Completion of the verification checks does not change this code path.
