<script lang="ts">
  import { tick } from 'svelte';
  import { ApiError, deleteCollection, type Collection, type DeletionOperation } from './api';
  import { containDialogFocus, holdDialogFocus } from './dialogFocus';

  /** Admitted deletions join the follower's list and start polling; the collection list is reloaded. */
  export let onAdmitted: (operation: DeletionOperation) => void;
  export let onCollectionsChanged: (selectedId?: string) => Promise<void> | void;

  /** The exact-name confirmation: the collection it names, what was typed, and what the submission did. */
  let collection: Collection | null = null;
  let confirmName = '';
  let submitting = false;
  let error: string | null = null;
  let dialog: HTMLElement | null = null;
  let nameInput: HTMLInputElement | null = null;
  /** The control the dialog was opened from, so declining puts focus back where it was. */
  let opener: HTMLElement | null = null;

  /** Opens the exact-name confirmation for one collection; nothing is sent until it is confirmed. */
  export function open(target: Collection, fromElement: HTMLElement | null): void {
    collection = target;
    confirmName = '';
    error = null;
    opener = fromElement;
    void tick().then(() => nameInput?.focus());
  }

  /** Declining removes nothing: closing the dialog sends no request at all. */
  function close(): void {
    if (submitting) return;
    collection = null;
    confirmName = '';
    error = null;
    const target = opener;
    opener = null;
    if (target instanceof HTMLElement && target.isConnected) target.focus();
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
   * Confirms the deletion. The server admits it durably and answers with the operation, which the
   * follower then follows; the collection leaves the manageable list immediately, so the same deletion
   * cannot be submitted twice.
   */
  async function submit(event: SubmitEvent): Promise<void> {
    event.preventDefault();
    const target = collection;
    if (target === null || submitting || confirmName.trim() !== target.name) return;
    submitting = true;
    error = null;
    holdDialogFocus(dialog);
    try {
      const admitted = await deleteCollection(target.id, confirmName.trim());
      submitting = false;
      close();
      onAdmitted({
        operationId: admitted.operationId,
        kind: 'COLLECTION',
        collectionId: admitted.collectionId,
        collectionName: target.name,
        documentIds: [],
        phase: admitted.phase,
        terminal: false,
        errorCode: null,
      });
      // The server no longer offers the collection for management, so the list the caller was handed is
      // refreshed rather than left showing something that is on its way out.
      await onCollectionsChanged();
    } catch (failure) {
      submitting = false;
      error = describe(failure);
    }
  }

  function describe(failure: unknown): string {
    if (failure instanceof ApiError) return failure.message;
    return failure instanceof Error ? failure.message : 'Something went wrong.';
  }
</script>

{#if collection !== null}
  <div class="confirm-backdrop" aria-hidden="true" onclick={close}></div>
  <div
    class="confirm-dialog"
    role="dialog"
    aria-modal="true"
    aria-labelledby="delete-collection-heading"
    bind:this={dialog}
    tabindex="-1"
    onkeydown={handleKeydown}
  >
    <form class="confirm-form" onsubmit={submit}>
      <h2 id="delete-collection-heading">Delete {collection.name}?</h2>
      <p>
        This permanently removes the collection and every document InfoScry holds for it. Original files
        outside InfoScry are not touched, and there is no undo.
      </p>
      <p>
        Work still running for this collection is cancelled after its current step, and no new import into
        it can start.
      </p>
      <div class="field">
        <label for="delete-collection-name">Type the collection name to confirm</label>
        <input
          id="delete-collection-name"
          bind:this={nameInput}
          bind:value={confirmName}
          autocomplete="off"
          aria-describedby="delete-collection-hint"
        />
      </div>
      <p class="hint" id="delete-collection-hint">The name has to match exactly.</p>
      {#if error !== null}<p role="alert">{error}</p>{/if}
      <div class="actions">
        <button type="submit" class="primary" disabled={submitting || confirmName.trim() !== collection.name}>
          {submitting ? 'Deleting…' : 'Delete collection'}
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
  .field { display: grid; min-width: 0; gap: 0.35rem; align-content: start; }
  label { color: #b8bcbb; font-size: 0.82rem; }
  input:not([type='checkbox']) { min-width: 0; border: 1px solid #383d3e; border-radius: 0.48rem; background: #202324; padding: 0.62rem 0.72rem; color: #e8e9e7; }
  .actions { display: flex; flex-wrap: wrap; gap: 0.55rem; }
  .hint { color: #929997; font-size: 0.82rem; }
  button.primary { border-color: #c4a77d; background: #c4a77d; color: #1c1b18; font-weight: 650; }
  button.primary:hover:not(:disabled) { background: #d4ba94; }
</style>
