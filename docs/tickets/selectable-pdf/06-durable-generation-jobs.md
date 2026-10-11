# 06 — Durable generation, publication and cleanup

**Status:** Not started — documentation only, 2026-10-10.
**Blocked by:** 04 and 05 reviewed.
**Read first:** [Spec](../../specs/2026-10-10-selectable-pdf.md), [plan](../../plans/2026-10-10-selectable-pdf.md), [shared contracts](CONTRACTS.md), [dispatch and gates](STATUS.md).

## Deliverable and ownership

Integrate independent PDF generation with successful revision publication,
recovery and deletion. Maps to plan Task 4.

**Own:** `src/main/kotlin/infoscry/pdf/SelectablePdfService.kt`,
`src/main/kotlin/infoscry/storage/SelectablePdfStore.kt`,
`src/main/kotlin/infoscry/jobs/SelectablePdfJobHandler.kt`,
`src/main/kotlin/infoscry/domain/Models.kt`, `config/AppPaths.kt`, `AppContext.kt`,
`document/RevisionPublicationService.kt`, `jobs/ImportJobHandler.kt`,
`server/Server.kt`, baseline schema for artifact/job tables, and existing CLI/job
composition and deletion owners after inventory. 07 edits Routes.kt later.
**Tests:** SelectablePdfServiceTest and SelectablePdfRecoveryTest in `infoscry.pdf`.
**Consume:** 04 revision layouts, 05 writer, 02 status/manifest records.
**Produce:** service signatures and state invariants in CONTRACTS.md.

## Acceptance

1. Successful import/rescan/Restore queues the exact revision independently of
   OCR/search success. Solve publication→enqueue crash gap durably; no GPU work
   or embedding requirement in the PDF path itself. No archive-wide backfill.
2. Cache key covers original hash, revision, ordered text/image/layout hashes,
   geometry and renderer/font/positioning versions. Include every physical page,
   even pages with no extracted unit; unknowns cannot be guessed from text alone.
3. Repeat ensure/recovery twice returns one current generation; READY reuse verifies
   files/hashes. PARTIAL/FAILED retries are explicit. Stopped work does not prevent
   retry or a new import/rescan. Job composition works in server and standalone CLI.
4. Crash after intent, enqueue, page checkpoint, temporary close, rename and SQLite
   completion converges. A manifest/PDF pair is served only after both are verified;
   partial publication is not READY. Completed OCR is never rerun during export.
5. Missing/changed image/layout/font, disk-full, cancellation and corrupt cache
   produce safe statuses without rolling back the published text or search index.
6. Late A completion cannot become current after B or Restore. Historical published
   A remains A. Foreign/candidate/withdrawn revision cannot generate a public file.
7. Deletion cancels/drains only artifact work as needed for data cleanup, prevents
   late directory recreation and removes files/rows idempotently. Do not make an
   active reading wait for viewers or add concurrency refusals.
8. Retry may repair local layouts but not issue provider transcription. All-blank
   READY and PARTIAL coverage counts follow CONTRACTS.md exactly.

## Steps and checks

- [ ] Add meaningful failing service/identity and crash-boundary tests first.
- [ ] Inventory every JobType dispatch/wiring and publication/deletion path.
- [ ] Implement desired rows/jobs, snapshot resolution, file publication and recovery.
- [ ] Test each boundary with interrupted state, then two repeat recovery passes.
- [ ] Run `./gradlew test -PskipFrontend --tests 'infoscry.pdf.SelectablePdfServiceTest' --tests 'infoscry.pdf.SelectablePdfRecoveryTest' --tests 'infoscry.document.RevisionPublicationRecoveryTest' --tests 'infoscry.document.RevisionRestoreRecoveryTest' --tests 'infoscry.document.DocumentDeletionRecoveryTest' --tests 'infoscry.collection.CollectionDeletionRecoveryTest'`.
- [ ] Return the wiring inventory and recorded transcription call counts.

Coordinator runs ./gradlew check after integration and before 07 unlocks. No
owner database conversion or installed-server restart is part of this ticket.

## Completion evidence

Not run. Return owned diff, behavioral RED/GREEN commands/results, acceptance
vector outcomes, exact exported interfaces and remaining limits. Checkboxes are
future work, not proof. Coordinator review is required before unlocking dependents.
