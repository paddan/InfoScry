<script lang="ts">
  import { onDestroy, tick } from 'svelte';
  import {
    ApiError,
    applyCorrections,
    deleteCorrection,
    getCorrections,
    previewCorrectionRemoval,
    type Collection,
    type CorrectionList,
    type CorrectionRule,
  } from './api';
  import CorrectionDialog from './CorrectionDialog.svelte';
  import { containDialogFocus, holdDialogFocus } from './dialogFocus';

  export let collection: Collection;

  const POLL_MILLIS = 2000;

  let list: CorrectionList | null = null;
  let loadError: string | null = null;
  let boundId = '';
  /** Answers for a collection the reader has left carry an older generation and are dropped. */
  let generation = 0;
  let poll: ReturnType<typeof setTimeout> | null = null;

  /** The dialog: null when closed, `'new'` for a new rule, or the rule being edited. */
  let editing: CorrectionRule | 'new' | null = null;
  let dialogOpener: HTMLElement | null = null;

  let removing: CorrectionRule | null = null;
  let removalDocuments: number | null = null;
  let removeBusy = false;
  let removeError: string | null = null;
  let removeDialog: HTMLElement | null = null;
  let removeOpener: HTMLElement | null = null;
  let retryError: string | null = null;

  $: if (collection.id !== boundId) void load(collection.id);
  $: job = list?.job ?? null;

  onDestroy(() => stopPolling());

  async function load(target: string): Promise<void> {
    boundId = target;
    const mine = ++generation;
    stopPolling();
    list = null;
    loadError = null;
    await reload(mine);
  }

  async function reload(mine = generation): Promise<void> {
    try {
      const answer = await getCorrections(boundId);
      if (mine !== generation) return;
      list = answer;
      loadError = null;
    } catch (failure) {
      if (mine === generation) loadError = describe(failure);
      return;
    }
    stopPolling();
    // Read from the answer itself: the reactive `active` is not recomputed until after this function returns.
    if (isRunning(list)) poll = setTimeout(() => void reload(mine), POLL_MILLIS);
  }

  function isRunning(current: CorrectionList | null): boolean {
    const latest = current?.job ?? null;
    return latest !== null && (latest.state === 'QUEUED' || latest.state === 'RUNNING');
  }

  function stopPolling(): void {
    if (poll !== null) clearTimeout(poll);
    poll = null;
  }

  function number(n: number): string {
    return n.toLocaleString('en-US');
  }

  function documents(n: number): string {
    return `${number(n)} ${n === 1 ? 'document' : 'documents'}`;
  }

  function statusText(current: CorrectionList): string {
    const latest = current.job;
    if (latest !== null && (latest.state === 'QUEUED' || latest.state === 'RUNNING')) {
      return `Applying: ${number(latest.completed ?? 0)} of ${documents(latest.total ?? 0)}`;
    }
    if (latest !== null && latest.state === 'FAILED') {
      return latest.errorCode === 'EMBEDDING_UNAVAILABLE'
        ? 'The corrections could not be applied: the embedding model is not available on this machine.'
        : 'The corrections could not be applied to every document.';
    }
    if (current.rules.length === 0 && latest === null) return 'No corrections yet.';
    return 'All documents follow the list';
  }

  function openDialog(event: MouseEvent, target: CorrectionRule | 'new'): void {
    dialogOpener = event.currentTarget instanceof HTMLElement ? event.currentTarget : null;
    editing = target;
  }

  function closeDialog(): void {
    editing = null;
    const target = dialogOpener;
    dialogOpener = null;
    if (target !== null && target.isConnected) target.focus();
  }

  async function saved(): Promise<void> {
    closeDialog();
    await reload();
  }

  async function openRemove(event: MouseEvent, rule: CorrectionRule): Promise<void> {
    removeOpener = event.currentTarget instanceof HTMLElement ? event.currentTarget : null;
    removing = rule;
    removalDocuments = null;
    removeError = null;
    void tick().then(() => removeDialog?.querySelector<HTMLElement>('button.cancel')?.focus());
    try {
      const preview = await previewCorrectionRemoval(boundId, rule.id);
      if (removing?.id === rule.id) removalDocuments = preview.documents;
    } catch (failure) {
      if (removing?.id === rule.id) removeError = describe(failure);
    }
  }

  function closeRemove(): void {
    if (removeBusy) return;
    removing = null;
    const target = removeOpener;
    removeOpener = null;
    if (target !== null && target.isConnected) target.focus();
  }

  async function confirmRemove(event: SubmitEvent): Promise<void> {
    event.preventDefault();
    if (removing === null || removeBusy) return;
    const rule = removing;
    removeBusy = true;
    removeError = null;
    holdDialogFocus(removeDialog);
    try {
      await deleteCorrection(boundId, rule.id);
    } catch (failure) {
      removeError = describe(failure);
      removeBusy = false;
      return;
    }
    removeBusy = false;
    closeRemove();
    await reload();
  }

  async function retry(): Promise<void> {
    retryError = null;
    try {
      await applyCorrections(boundId);
    } catch (failure) {
      retryError = describe(failure);
      return;
    }
    await reload();
  }

  function handleRemoveKeydown(event: KeyboardEvent): void {
    if (event.key === 'Escape') {
      event.preventDefault();
      closeRemove();
    } else if (event.key === 'Tab') {
      containDialogFocus(event, removeDialog);
    }
  }

  function describe(failure: unknown): string {
    if (failure instanceof ApiError) return failure.message;
    return failure instanceof Error ? failure.message : 'Something went wrong.';
  }
</script>

<section class="correction-settings" aria-labelledby="corrections-heading">
  <h4 id="corrections-heading">Corrections</h4>
  <p class="hint">
    Words the reading got wrong, such as a misread surname. Each rule rewrites every whole-word match in this
    collection's documents, now and in later imports and rescans; removing it brings the reading back.
  </p>
  {#if loadError !== null}
    <p role="alert">{loadError}</p>
  {:else if list === null}
    <p aria-live="polite">Loading corrections…</p>
  {:else}
    <p class="status" aria-live="polite">{statusText(list)}</p>
    {#if job !== null && job.state === 'FAILED'}
      <div class="actions"><button type="button" onclick={retry}>Retry</button></div>
      {#if retryError !== null}<p role="alert">{retryError}</p>{/if}
    {/if}
    {#if list.rules.length > 0}
      <table>
        <thead>
          <tr><th scope="col">Wrong</th><th scope="col">Correct</th><th scope="col">Exact case</th><th scope="col">Matches</th><th scope="col"><span class="visually-hidden">Actions</span></th></tr>
        </thead>
        <tbody>
          {#each list.rules as rule (rule.id)}
            <tr>
              <td>{rule.find}</td>
              <td>{rule.replace}</td>
              <td>{rule.exactCase ? 'Yes' : 'No'}</td>
              <td>{number(rule.matches)}</td>
              <td class="row-actions">
                <button type="button" aria-label={`Edit ${rule.find}`} onclick={(event) => openDialog(event, rule)}>Edit</button>
                <button type="button" aria-label={`Remove ${rule.find}`} onclick={(event) => openRemove(event, rule)}>Remove</button>
              </td>
            </tr>
          {/each}
        </tbody>
      </table>
    {/if}
    <div class="actions"><button type="button" onclick={(event) => openDialog(event, 'new')}>Add correction</button></div>
  {/if}
</section>

{#if editing !== null}
  <CorrectionDialog
    collectionId={collection.id}
    rule={editing === 'new' ? null : editing}
    onClose={closeDialog}
    onSaved={saved}
  />
{/if}

{#if removing !== null}
  <div class="confirm-backdrop" aria-hidden="true" onclick={closeRemove}></div>
  <div
    class="confirm-dialog"
    role="dialog"
    aria-modal="true"
    aria-labelledby="remove-correction-heading"
    bind:this={removeDialog}
    tabindex="-1"
    onkeydown={handleRemoveKeydown}
  >
    <form class="confirm-form" onsubmit={confirmRemove}>
      <h2 id="remove-correction-heading">Remove {removing.find} → {removing.replace}?</h2>
      {#if removalDocuments === null && removeError === null}
        <p role="status">Counting the documents it changed…</p>
      {:else if removalDocuments !== null}
        <p>The text returns to the reading in {documents(removalDocuments)}.</p>
      {/if}
      {#if removeError !== null}<p role="alert">{removeError}</p>{/if}
      <div class="actions end">
        <button type="submit" class="primary" disabled={removeBusy}>{removeBusy ? 'Removing…' : 'Remove'}</button>
        <button type="button" class="cancel" onclick={closeRemove} disabled={removeBusy}>Cancel</button>
      </div>
    </form>
  </div>
{/if}

<style>
  .correction-settings { display: grid; gap: 0.7rem; border: 1px solid #303535; border-radius: 0.55rem; background: #191c1d; padding: 1rem; }
  .correction-settings h4 { margin: 0; font-size: 0.9rem; }
  .hint { color: #929997; font-size: 0.82rem; margin: 0; }
  .status { margin: 0; color: #c9cdcb; font-size: 0.85rem; }
  table { width: 100%; border-collapse: collapse; font-size: 0.85rem; }
  th { color: #b8bcbb; font-weight: 600; text-align: left; }
  th, td { border-bottom: 1px solid #2a2e2e; padding: 0.4rem 0.5rem; overflow-wrap: anywhere; }
  .row-actions { display: flex; flex-wrap: wrap; gap: 0.4rem; justify-content: end; }
  .actions { display: flex; flex-wrap: wrap; align-items: center; gap: 0.55rem; }
  .actions.end { justify-content: end; }
  .visually-hidden { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0 0 0 0); white-space: nowrap; }
  button.primary { border-color: #c4a77d; background: #c4a77d; color: #1c1b18; font-weight: 650; }
  .confirm-backdrop { position: fixed; inset: 0; background: rgba(8, 9, 10, 0.62); }
  .confirm-dialog { position: fixed; top: 50%; left: 50%; z-index: 2; transform: translate(-50%, -50%); width: min(32rem, calc(100vw - 2rem)); border: 1px solid #3a3e3e; border-radius: 0.6rem; background: #1d2021; padding: 1.15rem; }
  .confirm-form { display: grid; gap: 0.7rem; }
  .confirm-dialog h2 { margin: 0; font-size: 1.05rem; overflow-wrap: anywhere; }
  .confirm-dialog p { margin: 0; }
</style>
