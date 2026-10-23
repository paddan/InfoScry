<script lang="ts">
  import { onDestroy, tick } from 'svelte';
  import { ApiError, deleteGlossaryGroup, listGlossary, type Collection, type GlossaryGroup } from './api';
  import GlossaryDialog from './GlossaryDialog.svelte';

  export let collection: Collection;

  let groups: GlossaryGroup[] | null = null;
  let loadError: string | null = null;
  let actionError: string | null = null;
  let addButton: HTMLButtonElement | null = null;
  let list: HTMLElement | null = null;
  let boundId = '';
  /** Answers for a collection the reader has left carry an older generation and are dropped. */
  let generation = 0;

  /** The dialog: null when closed, `'new'` for a new group, or the group being edited. */
  let editing: GlossaryGroup | 'new' | null = null;
  let dialogOpener: HTMLElement | null = null;
  let removingId: string | null = null;

  $: if (collection.id !== boundId) void load(collection.id);

  onDestroy(() => {
    generation++;
  });

  async function load(target: string): Promise<void> {
    boundId = target;
    groups = null;
    loadError = null;
    await reload(++generation);
  }

  async function reload(mine = generation): Promise<void> {
    try {
      const answer = await listGlossary(boundId);
      if (mine !== generation) return;
      groups = answer;
      loadError = null;
    } catch (failure) {
      if (mine !== generation) return;
      if (groups === null) loadError = describe(failure);
      else actionError = `The glossary could not be refreshed: ${describe(failure)}`;
    }
  }

  function openDialog(event: MouseEvent, target: GlossaryGroup | 'new'): void {
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

  async function remove(group: GlossaryGroup, index: number): Promise<void> {
    if (removingId !== null) return;
    removingId = group.id;
    actionError = null;
    try {
      await deleteGlossaryGroup(boundId, group.id);
    } catch (failure) {
      actionError = describe(failure);
      removingId = null;
      return;
    }
    await reload();
    removingId = null;
    await tick();
    // The row is gone: focus the group that took its place, or Add group when none is left.
    const edits = list?.querySelectorAll<HTMLButtonElement>('button.edit') ?? [];
    (edits[Math.min(index, edits.length - 1)] ?? addButton)?.focus();
  }

  function label(term: { text: string; prefix: boolean }): string {
    return term.prefix ? `${term.text}*` : term.text;
  }

  function describe(failure: unknown): string {
    if (failure instanceof ApiError) return failure.message;
    return failure instanceof Error ? failure.message : 'Something went wrong.';
  }
</script>

<section class="glossary-settings" aria-labelledby="glossary-heading-settings">
  <h4 id="glossary-heading-settings">Glossary</h4>
  <p class="hint">Names with the same meaning. Searching one finds the others; nothing in the text is changed.</p>
  {#if loadError !== null}
    <p role="alert">{loadError}</p>
  {:else if groups === null}
    <p aria-live="polite">Loading the glossary…</p>
  {:else}
    {#if actionError !== null}<p role="alert">{actionError}</p>{/if}
    {#if groups.length === 0}
      <p class="status">No glossary groups yet.</p>
    {:else}
      <ul class="groups" bind:this={list}>
        {#each groups as group, index (group.id)}
          <li>
            <span class="terms">
              {#each group.terms as term, position (position)}<span class="chip">{label(term)}</span>{/each}
            </span>
            <span class="row-actions">
              <button type="button" class="edit" aria-label={`Edit ${group.terms[0]?.text ?? ''}`} onclick={(event) => openDialog(event, group)}>Edit</button>
              <button
                type="button"
                aria-label={`Remove ${group.terms[0]?.text ?? ''}`}
                disabled={removingId !== null}
                onclick={() => remove(group, index)}
              >Remove</button>
            </span>
          </li>
        {/each}
      </ul>
    {/if}
    <div class="actions"><button type="button" bind:this={addButton} onclick={(event) => openDialog(event, 'new')}>Add group</button></div>
  {/if}
</section>

{#if editing !== null}
  <GlossaryDialog
    collectionId={collection.id}
    groups={groups ?? []}
    group={editing === 'new' ? null : editing}
    onClose={closeDialog}
    onSaved={saved}
  />
{/if}

<style>
  .glossary-settings { display: grid; gap: 0.7rem; border: 1px solid #303535; border-radius: 0.55rem; background: #191c1d; padding: 1rem; }
  .glossary-settings h4 { margin: 0; font-size: 0.9rem; }
  .hint { color: #929997; font-size: 0.82rem; margin: 0; }
  .status { margin: 0; color: #c9cdcb; font-size: 0.85rem; }
  .groups { display: grid; gap: 0.4rem; margin: 0; padding: 0; list-style: none; }
  .groups li { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 0.5rem; border-bottom: 1px solid #2a2e2e; padding: 0.35rem 0; }
  .terms { display: flex; flex-wrap: wrap; gap: 0.3rem; }
  .chip { border: 1px solid #3a3e3e; border-radius: 0.35rem; padding: 0.1rem 0.45rem; font-size: 0.85rem; overflow-wrap: anywhere; }
  .row-actions { display: flex; flex-wrap: wrap; gap: 0.4rem; }
  .actions { display: flex; flex-wrap: wrap; align-items: center; gap: 0.55rem; }
</style>
