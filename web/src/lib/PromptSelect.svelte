<script lang="ts">
  import { getCollectionPrompts, type CollectionPrompt, type PromptRole } from './api';

  export let collectionId: string;
  export let role: PromptRole;
  export let disabled = false;
  /** A continued Investigate conversation is locked to one prompt; the select then shows only its name. */
  export let lockedName: string | null = null;
  /** The id of the prompt a new question uses; undefined until the collection's prompts are read. */
  export let value: string | undefined = undefined;

  let prompts: CollectionPrompt[] = [];
  let defaultId = '';
  let loading = false;
  let loadError: string | null = null;
  let loadedCollectionId: string | null = null;
  /** Every read claims a higher generation, so an answer for a collection the reader left never lands. */
  let generation = 0;

  const selectId = `prompt-${role.toLowerCase()}`;

  /** A new collection starts from its own default; the previous collection's selection is dropped at once. */
  $: if (collectionId !== loadedCollectionId) {
    loadedCollectionId = collectionId;
    value = undefined;
    prompts = [];
    void load(true);
  }

  /**
   * Read the collection's prompts for this role. The selection moves to the collection default when
   * the collection changed, or when the chosen prompt is no longer offered; otherwise it stays put.
   */
  async function load(resetSelection: boolean): Promise<void> {
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
      defaultId = rolePrompts.defaultId;
      if (resetSelection || value === undefined || !prompts.some((prompt) => prompt.id === value)) value = defaultId;
    } catch (failure) {
      if (attempt !== generation || id !== collectionId) return;
      loadError = failure instanceof Error ? failure.message : 'Could not load prompts.';
    } finally {
      if (attempt === generation) loading = false;
    }
  }

  /** Read the prompts again, for example after a prompt the reader chose was deleted. */
  export async function reload(): Promise<void> {
    await load(false);
  }
</script>

<div class="prompt-select">
  <label for={selectId}>Prompt</label>
  {#if lockedName !== null}
    <select id={selectId} disabled>
      <option value={lockedName}>{lockedName}</option>
    </select>
  {:else}
    <select id={selectId} bind:value disabled={disabled || loading || prompts.length === 0}>
      {#if prompts.length === 0}<option value="">{loading ? 'Loading prompts…' : 'No prompts'}</option>{/if}
      {#each prompts as prompt (prompt.id)}<option value={prompt.id}>{prompt.name}</option>{/each}
    </select>
  {/if}
  {#if loadError !== null}<p class="prompt-error" role="alert">{loadError}</p>{/if}
</div>

<style>
  .prompt-select { display: grid; gap: 0.35rem; min-width: 0; }
  label { font-size: 0.9rem; }
  select { font: inherit; padding: 0.45rem; max-width: 100%; }
  .prompt-error { color: #a4232b; font-size: 0.9rem; margin: 0; }
</style>
