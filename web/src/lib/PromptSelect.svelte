<script lang="ts">
  import { getCollectionPrompts, type CollectionPrompt, type PromptRole } from './api';

  export let collectionId: string;
  export let role: PromptRole;
  export let disabled = false;
  /** A continued Investigate conversation is locked to one prompt; the select then shows only its name. */
  export let locked = false;
  /** The locked prompt's name, once the conversation list names it. */
  export let lockedName: string | null = null;
  /** The id of the prompt a new question uses; undefined until the collection's prompts are read. */
  export let value: string | undefined = undefined;

  let prompts: CollectionPrompt[] = [];
  let loading = false;
  let loadError: string | null = null;
  let loadedCollectionId: string | null = null;
  /** Every read claims a higher generation, so an answer for a collection the reader left never lands. */
  let generation = 0;

  const selectId = `prompt-${role.toLowerCase()}`;

  /**
   * A collection change drops the previous collection's selection and reads the new one. A first
   * mount keeps the selection the page remembered, as long as the collection still offers it.
   */
  $: if (collectionId !== loadedCollectionId) {
    if (loadedCollectionId !== null) value = undefined;
    loadedCollectionId = collectionId;
    prompts = [];
    void load();
  }

  /**
   * Read the collection's prompts for this role. The selection falls back to the collection default
   * when nothing is selected or the chosen prompt is no longer offered; otherwise it stays put.
   */
  async function load(): Promise<void> {
    const attempt = ++generation;
    const id = collectionId;
    loadError = null;
    if (id === '') {
      loading = false;
      return;
    }
    loading = true;
    try {
      const all = await getCollectionPrompts(id);
      if (attempt !== generation || id !== collectionId) return;
      const rolePrompts = role === 'ASK' ? all.ask : all.investigate;
      prompts = rolePrompts.prompts;
      if (value === undefined || !prompts.some((prompt) => prompt.id === value)) value = rolePrompts.defaultId;
    } catch (failure) {
      if (attempt !== generation || id !== collectionId) return;
      loadError = failure instanceof Error && failure.message !== '' ? failure.message : 'Could not load prompts.';
    } finally {
      if (attempt === generation) loading = false;
    }
  }

  /** Read the prompts again, for example after a prompt the reader chose was deleted or after a failed read. */
  export async function reload(): Promise<void> {
    await load();
  }
</script>

<div class="prompt-select">
  <label for={selectId}>Prompt</label>
  {#if locked}
    <select id={selectId} disabled>
      <option>{lockedName ?? 'Prompt locked'}</option>
    </select>
  {:else}
    <select id={selectId} bind:value disabled={disabled || loading || prompts.length === 0}>
      {#if prompts.length === 0}<option value="">{loading ? 'Loading prompts…' : 'Prompts unavailable'}</option>{/if}
      {#each prompts as prompt (prompt.id)}<option value={prompt.id}>{prompt.name}</option>{/each}
    </select>
  {/if}
  {#if loadError !== null}
    <p class="prompt-error" role="alert">Could not load prompts. {loadError} New questions use the collection default.</p>
    <button type="button" class="prompt-retry" onclick={() => void reload()}>Retry loading prompts</button>
  {/if}
</div>

<style>
  .prompt-select { display: grid; gap: 0.35rem; min-width: 0; }
  label { font-size: 0.9rem; }
  select { font: inherit; padding: 0.45rem; max-width: 100%; }
  .prompt-error { color: #f0a4a0; font-size: 0.85rem; margin: 0; }
  .prompt-retry { font: inherit; font-size: 0.85rem; justify-self: start; padding: 0.3rem 0.5rem; }
</style>
