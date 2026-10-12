<script lang="ts">
  import { tick } from 'svelte';
  import { ApiError, deleteDocuments, type Collection, type DeletionOperation, type DocumentApiRow } from './api';
  import { containDialogFocus, holdDialogFocus } from './dialogFocus';

  /** Admitted deletions join the follower's list and start polling; the targeted ids leave any selection. */
  export let onAdmitted: (operation: DeletionOperation) => void;

  /**
   * The documents whose removal is being confirmed, and what the submission did. The list is a snapshot
   * taken when the confirmation opened: what the dialog says and what it sends both come from it, so a
   * refresh underneath cannot change the confirmed target set. Nothing is sent until the confirmation is
   * submitted.
   */
  let rows: DocumentApiRow[] | null = null;
  let collection: Collection | null = null;
  let submitting = false;
  let error: string | null = null;
  let dialog: HTMLElement | null = null;
  /** The control the dialog was opened from, so declining puts focus back where it was. */
  let opener: HTMLElement | null = null;

  /**
   * Opens the confirmation for exactly the rows it is handed — one row from the table, or the checked
   * ones — and snapshots them there; nothing is sent until it is confirmed.
   */
  export function open(targetRows: DocumentApiRow[], targetCollection: Collection, fromElement: HTMLElement | null): void {
    if (targetRows.length === 0) return;
    rows = [...targetRows];
    collection = targetCollection;
    error = null;
    opener = fromElement;
    void tick().then(() => dialog?.focus());
  }

  /** Declining removes nothing: closing the dialog sends no request at all. */
  function close(): void {
    if (submitting) return;
    rows = null;
    collection = null;
    error = null;
    const target = opener;
    opener = null;
    if (target instanceof HTMLElement && target.isConnected) target.focus();
  }

  /** Closes the confirmation unless a submission is in flight; a refresh underneath must not yank it away. */
  export function closeUnlessSubmitting(): void {
    if (!submitting) close();
  }

  function handleKeydown(event: KeyboardEvent): void {
    if (event.key === 'Escape') {
      event.preventDefault();
      close();
    } else if (event.key === 'Tab') {
      containDialogFocus(event, dialog);
    }
  }

  /**
   * Confirms the removal of the documents the confirmation snapshotted, as one admission. The server
   * validates every id against the active collection before it records anything and answers with the
   * one operation, which the follower then follows; the rows stay listed while the deletion runs, and the
   * operation is added to the same followed list the reopened Admin restores its unfinished work from.
   */
  async function submit(event: SubmitEvent): Promise<void> {
    event.preventDefault();
    const target = collection;
    const documentsToDelete = rows;
    if (target === null || documentsToDelete === null || submitting) return;
    submitting = true;
    error = null;
    holdDialogFocus(dialog);
    try {
      const admitted = await deleteDocuments(target.id, documentsToDelete.map((row) => row.id));
      submitting = false;
      close();
      onAdmitted({
        operationId: admitted.operationId,
        kind: 'DOCUMENT',
        collectionId: admitted.collectionId,
        collectionName: target.name,
        documentIds: admitted.documentIds,
        phase: admitted.phase,
        terminal: false,
        errorCode: null,
      });
    } catch (failure) {
      submitting = false;
      error = describe(failure);
    }
  }

  function describe(failure: unknown): string {
    if (failure instanceof ApiError) return failure.message;
    return failure instanceof Error ? failure.message : 'Something went wrong.';
  }

  /** The confirmation's own heading: one document is named, several are counted with their collection. */
  function heading(targetRows: DocumentApiRow[]): string {
    if (targetRows.length === 1) return `Delete ${targetRows[0].originalFilename}?`;
    return `Delete ${targetRows.length} documents from ${collection?.name ?? 'this collection'}?`;
  }

  /**
   * What the confirmation removes, in a reader's words. The names come from the snapshot, and the count
   * is the snapshot's own, so what is confirmed is what was selected when the dialog opened.
   */
  function sentence(targetRows: DocumentApiRow[]): string {
    const collectionName = collection?.name ?? 'this collection';
    if (targetRows.length === 1) {
      return `This permanently removes ${targetRows[0].originalFilename} from ${collectionName} and everything ` +
        'InfoScry holds for it. The original file outside InfoScry is not touched, and there is no undo.';
    }
    return `This permanently removes the ${targetRows.length} selected documents from ${collectionName} and ` +
      'everything InfoScry holds for them. The original files outside InfoScry are not touched, and there ' +
      'is no undo.';
  }

  /** The submit label: one document is a document, several are documents, and both say what is running. */
  function submitLabel(targetRows: DocumentApiRow[]): string {
    if (submitting) return 'Deleting…';
    return targetRows.length === 1 ? 'Delete document' : 'Delete documents';
  }
</script>

{#if rows !== null}
  <div class="confirm-backdrop" aria-hidden="true" onclick={close}></div>
  <div
    class="confirm-dialog"
    role="dialog"
    aria-modal="true"
    aria-labelledby="delete-document-heading"
    bind:this={dialog}
    tabindex="-1"
    onkeydown={handleKeydown}
  >
    <form class="confirm-form" onsubmit={submit}>
      <h2 id="delete-document-heading">{heading(rows)}</h2>
      <p>{sentence(rows)}</p>
      <p>
        A running import or OCR pass for {rows.length === 1 ? 'this document' : 'these documents'}
        is interrupted after its current step. Other documents, including others in the same import, are
        not affected.
      </p>
      {#if rows.length > 1}
        <ul class="confirm-list">
          {#each rows as row (row.id)}
            <li>{row.originalFilename}</li>
          {/each}
        </ul>
      {/if}
      {#if error !== null}<p role="alert">{error}</p>{/if}
      <div class="actions">
        <button type="submit" class="primary" disabled={submitting}>
          {submitLabel(rows)}
        </button>
        <button type="button" onclick={close} disabled={submitting}>Cancel</button>
      </div>
    </form>
  </div>
{/if}

<style>
  .confirm-backdrop { position: fixed; inset: 0; background: rgba(8, 9, 10, 0.62); }
  .confirm-dialog { position: fixed; top: 50%; left: 50%; z-index: 2; transform: translate(-50%, -50%); width: min(32rem, calc(100vw - 2rem)); border: 1px solid #3a3e3e; border-radius: 0.6rem; background: #1d2021; padding: 1.15rem; }
  .confirm-form { display: grid; gap: 0.7rem; }
  .confirm-dialog h2 { margin: 0; font-size: 1.05rem; }
  .confirm-dialog p { margin: 0; }
  .confirm-dialog .actions { justify-content: end; }
  .confirm-dialog .confirm-list li { overflow-wrap: anywhere; }
  .confirm-list { margin: 0; padding-left: 1.1rem; max-height: 8rem; overflow-y: auto; }
  .actions { display: flex; flex-wrap: wrap; gap: 0.55rem; }
  button.primary { border-color: #c4a77d; background: #c4a77d; color: #1c1b18; font-weight: 650; }
  button.primary:hover:not(:disabled) { background: #d4ba94; }
</style>
