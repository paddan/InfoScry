<script lang="ts">
  import { tick } from 'svelte';
  import {
    ApiError,
    createCollectionPrompt,
    deleteCollectionPrompt,
    getCollectionPrompts,
    setCollectionPromptDefault,
    updateCollectionPrompt,
    type Collection,
    type CollectionPrompt,
    type CollectionPrompts,
    type PromptRole,
    type RolePrompts,
  } from './api';
  import { containDialogFocus, holdDialogFocus } from './dialogFocus';
  import { PROMPT_HELP } from './promptHelp';

  export let collection: Collection;

  const ROLE_LISTS: { role: PromptRole; label: string }[] = [
    { role: 'ASK', label: 'Ask' },
    { role: 'INVESTIGATE', label: 'Investigate' },
  ];

  /** The prompts as the server last listed them; null while a collection's list is loading. */
  let prompts: CollectionPrompts | null = null;
  let loading = false;
  let loadError: string | null = null;
  let boundId = '';
  /**
   * The settings generation. Every answer that arrives after the reader selects another collection carries
   * an older generation, so it may not write into the draft, the status or the list.
   */
  let generation = 0;

  /** The selected prompt: a stored one (by id) in the chosen role, or a new draft when `creating` is set. */
  let activeRole: PromptRole = 'ASK';
  let selectedId: string | null = null;
  let creating = false;
  let draftName = '';
  let draftBody = '';
  let selected: CollectionPrompt | null = null;

  let saving = false;
  let saveError: string | null = null;
  let saved = false;
  let defaultSaving = false;
  let defaultError: string | null = null;
  let defaultSaved = false;

  let confirmOpen = false;
  let deleting = false;
  let deleteError: string | null = null;
  let deleteDialog: HTMLElement | null = null;
  /** The control the delete dialog was opened from, so closing it puts focus back there. */
  let deleteOpener: HTMLElement | null = null;

  $: if (collection.id !== boundId) void load(collection.id);
  $: selected = findSelected(prompts, activeRole, selectedId);
  $: readOnly = !creating && selected !== null && selected.builtIn;
  $: isCollectionDefault = selected !== null && section(activeRole)?.defaultId === selected.id;
  $: dirty = creating
    ? draftName.trim() !== '' && draftBody.trim() !== ''
    : selected !== null && !selected.builtIn && (draftName.trim() !== selected.name || draftBody !== selected.body);
  $: canSave = dirty && !saving;

  function section(target: PromptRole): RolePrompts | null {
    if (prompts === null) return null;
    return target === 'ASK' ? prompts.ask : prompts.investigate;
  }

  function findSelected(list: CollectionPrompts | null, target: PromptRole, id: string | null): CollectionPrompt | null {
    if (list === null || id === null) return null;
    const role = target === 'ASK' ? list.ask : list.investigate;
    return role.prompts.find((prompt) => prompt.id === id) ?? null;
  }

  function roleLabel(target: PromptRole): string {
    return target === 'ASK' ? 'Ask' : 'Investigate';
  }

  /** Shows a stored prompt in the editor, starting from its own saved name and text. */
  function select(target: PromptRole, prompt: CollectionPrompt): void {
    activeRole = target;
    selectedId = prompt.id;
    creating = false;
    draftName = prompt.name;
    draftBody = prompt.body;
    resetStatus();
  }

  /** A new prompt starts from Default's text in the chosen role; the name is the reader's to give. */
  function startNew(target: PromptRole): void {
    const fallback = section(target)?.prompts.find((prompt) => prompt.builtIn);
    activeRole = target;
    selectedId = null;
    creating = true;
    draftName = '';
    draftBody = fallback?.body ?? '';
    resetStatus();
  }

  /** Duplicate starts a new prompt from the selected one's text, in the same role. */
  function duplicate(): void {
    if (selected === null) return;
    selectedId = null;
    creating = true;
    draftName = `Copy of ${selected.name}`.slice(0, 80);
    draftBody = selected.body;
    resetStatus();
  }

  function resetStatus(): void {
    saveError = null;
    saved = false;
    defaultError = null;
    defaultSaved = false;
  }

  /** Reads the collection's prompts. A stale answer is dropped, and a new load clears every in-flight flag. */
  async function load(collectionId: string): Promise<void> {
    boundId = collectionId;
    const mine = ++generation;
    prompts = null;
    loading = true;
    loadError = null;
    creating = false;
    selectedId = null;
    saving = false;
    defaultSaving = false;
    deleting = false;
    confirmOpen = false;
    resetStatus();
    try {
      const next = await getCollectionPrompts(collectionId);
      if (mine !== generation) return;
      prompts = next;
      const first = next.ask.prompts.find((prompt) => prompt.id === next.ask.defaultId) ?? next.ask.prompts[0];
      if (first !== undefined) select('ASK', first);
    } catch (failure) {
      if (mine === generation) loadError = describe(failure);
    } finally {
      if (mine === generation) loading = false;
    }
  }

  /** Re-reads the list after a write; the caller has already checked that its generation is still current. */
  async function reload(collectionId: string, mine: number): Promise<void> {
    const next = await getCollectionPrompts(collectionId);
    if (mine === generation) prompts = next;
  }

  /** The prompt to show after a deletion: the role's collection default, or Default when that is gone too. */
  function fallbackPrompt(target: PromptRole): CollectionPrompt | null {
    const list = section(target);
    if (list === null) return null;
    return list.prompts.find((prompt) => prompt.id === list.defaultId) ?? list.prompts.find((prompt) => prompt.builtIn) ?? null;
  }

  async function save(event: SubmitEvent): Promise<void> {
    event.preventDefault();
    if (!canSave) return;
    const mine = generation;
    const target = collection.id;
    const role = activeRole;
    const name = draftName.trim();
    const body = draftBody;
    const isNew = creating;
    const promptId = selectedId;
    saving = true;
    resetStatus();
    try {
      const stored = isNew
        ? await createCollectionPrompt(target, role, name, body)
        : await updateCollectionPrompt(target, promptId ?? '', name, body);
      if (mine !== generation) return;
      await reload(target, mine);
      if (mine !== generation) return;
      select(role, stored);
      saved = true;
    } catch (failure) {
      if (mine === generation) saveError = describe(failure);
    } finally {
      if (mine === generation) saving = false;
    }
  }

  function openDelete(event: MouseEvent): void {
    deleteOpener = event.currentTarget instanceof HTMLElement ? event.currentTarget : null;
    deleteError = null;
    confirmOpen = true;
    void tick().then(() => deleteDialog?.querySelector<HTMLElement>('button.cancel')?.focus());
  }

  /** Closing the dialog sends nothing; it only restores focus to the control that opened it. */
  function closeDelete(): void {
    if (deleting) return;
    confirmOpen = false;
    deleteError = null;
    const target = deleteOpener;
    deleteOpener = null;
    if (target !== null && target.isConnected) target.focus();
  }

  function handleDialogKeydown(event: KeyboardEvent): void {
    if (event.key === 'Escape') {
      event.preventDefault();
      closeDelete();
    } else if (event.key === 'Tab') {
      containDialogFocus(event, deleteDialog);
    }
  }

  async function confirmDelete(event: SubmitEvent): Promise<void> {
    event.preventDefault();
    if (selected === null || deleting) return;
    const mine = generation;
    const target = collection.id;
    const role = activeRole;
    const promptId = selected.id;
    deleting = true;
    deleteError = null;
    holdDialogFocus(deleteDialog);
    try {
      await deleteCollectionPrompt(target, promptId);
      if (mine !== generation) return;
      deleting = false;
      confirmOpen = false;
      deleteOpener = null;
      await reload(target, mine);
      if (mine !== generation) return;
      const next = fallbackPrompt(role);
      if (next !== null) {
        select(role, next);
      } else {
        selectedId = null;
      }
    } catch (failure) {
      if (mine === generation) deleteError = describe(failure);
    } finally {
      if (mine === generation) deleting = false;
    }
  }

  async function useAsDefault(): Promise<void> {
    if (selected === null || creating || defaultSaving || isCollectionDefault) return;
    const mine = generation;
    const target = collection.id;
    const role = activeRole;
    const promptId = selected.id;
    defaultSaving = true;
    defaultError = null;
    defaultSaved = false;
    try {
      await setCollectionPromptDefault(target, role, promptId);
      if (mine !== generation) return;
      await reload(target, mine);
      if (mine !== generation) return;
      defaultSaved = true;
    } catch (failure) {
      if (mine === generation) defaultError = describe(failure);
    } finally {
      if (mine === generation) defaultSaving = false;
    }
  }

  function describe(failure: unknown): string {
    if (failure instanceof ApiError) return failure.message;
    return failure instanceof Error ? failure.message : 'Something went wrong.';
  }
</script>

<section class="prompt-settings" aria-label={`Prompts for ${collection.name}`}>
  <span class="eyebrow">PROMPTS</span>

  {#if loading}<p class="hint" role="status">Loading prompts…</p>{/if}
  {#if loadError !== null}<p role="alert">{loadError}</p>{/if}

  {#if prompts !== null}
    <div class="core-rules">
      <h4>Always applied first</h4>
      <p class="hint">These core rules are read-only. They come before the selected prompt in every request.</p>
      <pre class="core-text">{prompts.coreRules}</pre>
    </div>

    <div class="role-lists">
      {#each ROLE_LISTS as entry (entry.role)}
        {@const list = section(entry.role)}
        {#if list !== null}
          <section class="role-list" aria-label={`${entry.label} prompts`}>
            <h4>{entry.label}</h4>
            <ul class="prompt-list">
              {#each list.prompts as prompt (prompt.id)}
                <li>
                  <button
                    type="button"
                    class="prompt-option"
                    class:selected={!creating && selected !== null && activeRole === entry.role && selected.id === prompt.id}
                    aria-pressed={!creating && activeRole === entry.role && selected?.id === prompt.id}
                    onclick={() => select(entry.role, prompt)}
                  >
                    <span class="prompt-name">{prompt.name}</span>
                    {#if prompt.builtIn}<span class="badge">Built-in</span>{/if}
                    {#if prompt.id === list.defaultId}<span class="badge">Collection default</span>{/if}
                  </button>
                </li>
              {/each}
            </ul>
            <div class="actions">
              <button type="button" onclick={() => startNew(entry.role)} disabled={saving}>New prompt</button>
            </div>
          </section>
        {/if}
      {/each}
    </div>

    {#if creating || selected !== null}
      <form class="settings-form prompt-editor" onsubmit={save}>
        <p class="hint">Changes apply to new conversations. Existing conversations keep the prompt they started with.</p>
        <div class="field">
          <label for="collection-prompt-name">Prompt name</label>
          <input
            id="collection-prompt-name"
            bind:value={draftName}
            readonly={readOnly}
            disabled={saving}
            autocomplete="off"
            maxlength="80"
          />
        </div>
        <div class="field">
          <label for="collection-prompt-body">Prompt text</label>
          <textarea
            id="collection-prompt-body"
            rows="10"
            spellcheck="false"
            bind:value={draftBody}
            readonly={readOnly}
            disabled={saving}
            aria-describedby="collection-prompt-help"
          ></textarea>
        </div>

        <details class="prompt-help">
          <summary>How to write a prompt</summary>
          <div id="collection-prompt-help" class="help-body">
            <p><strong>{PROMPT_HELP.alwaysApplied.lead}</strong> {PROMPT_HELP.alwaysApplied.text}</p>
            {#each [PROMPT_HELP.goodThings, PROMPT_HELP.investigateAlso, PROMPT_HELP.writingTips] as group (group.heading)}
              <h5>{group.heading}</h5>
              <ul>
                {#each group.items as item (item)}<li>{item}</li>{/each}
              </ul>
            {/each}
            <p><strong>{PROMPT_HELP.example.lead}</strong> {PROMPT_HELP.example.text}</p>
          </div>
        </details>

        <div class="actions">
          <button type="submit" class="primary" disabled={!canSave}>{saving ? 'Saving prompt…' : 'Save prompt'}</button>
          {#if !creating && selected !== null}
            <button type="button" onclick={duplicate} disabled={saving}>Duplicate</button>
          {/if}
          {#if !creating && selected !== null && !selected.builtIn}
            <button type="button" onclick={openDelete} disabled={saving}>Delete prompt</button>
          {/if}
        </div>
        {#if saveError !== null}<p role="alert">{saveError}</p>{/if}
        {#if saved}<p role="status">Prompt saved.</p>{/if}
      </form>

      {#if !creating && selected !== null}
        <div class="actions">
          {#if isCollectionDefault}
            <span class="hint" role="status">This is the collection default for {roleLabel(activeRole)}.</span>
          {:else}
            <button type="button" onclick={useAsDefault} disabled={defaultSaving}>
              {defaultSaving ? 'Saving default…' : 'Use as collection default'}
            </button>
          {/if}
        </div>
        {#if defaultError !== null}<p role="alert">{defaultError}</p>{/if}
        {#if defaultSaved}<p role="status">Collection default updated.</p>{/if}
      {/if}
    {/if}
  {/if}
</section>

{#if confirmOpen && selected !== null}
  <div class="confirm-backdrop" aria-hidden="true" onclick={closeDelete}></div>
  <div
    class="confirm-dialog"
    role="dialog"
    aria-modal="true"
    aria-labelledby="delete-prompt-heading"
    bind:this={deleteDialog}
    tabindex="-1"
    onkeydown={handleDialogKeydown}
  >
    <form class="confirm-form" onsubmit={confirmDelete}>
      <h2 id="delete-prompt-heading">Delete {selected.name}?</h2>
      <p>
        This removes the prompt from {collection.name}. Conversations that used it keep their history.
      </p>
      {#if isCollectionDefault}
        <p>
          It is the collection default for {roleLabel(activeRole)}, so Default will be used for new conversations
          until another prompt is set as the default.
        </p>
      {/if}
      {#if deleteError !== null}<p role="alert">{deleteError}</p>{/if}
      <div class="actions">
        <button type="submit" class="primary" disabled={deleting}>{deleting ? 'Deleting…' : 'Delete prompt'}</button>
        <button type="button" class="cancel" onclick={closeDelete} disabled={deleting}>Cancel</button>
      </div>
    </form>
  </div>
{/if}

<style>
  .prompt-settings { display: grid; gap: 0.9rem; border: 1px solid #303535; border-radius: 0.55rem; background: #191c1d; padding: 1rem; }
  .prompt-settings h4 { margin: 0 0 0.35rem; font-size: 0.9rem; }
  .prompt-settings h5 { margin: 0.7rem 0 0.25rem; font-size: 0.85rem; }
  .core-rules { display: grid; gap: 0.3rem; }
  .core-text { margin: 0; white-space: pre-wrap; border: 1px solid #303535; border-radius: 0.48rem; background: #202324; padding: 0.6rem 0.72rem; color: #c9cdcb; font-size: 0.85rem; }
  .role-lists { display: grid; gap: 0.9rem; }
  .role-list { display: grid; gap: 0.4rem; justify-items: start; }
  .role-list ul { display: grid; width: 100%; gap: 0.3rem; margin: 0; padding: 0; list-style: none; }
  .prompt-option { display: flex; width: 100%; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 0.4rem; text-align: left; }
  .prompt-option.selected { border-color: #c4a77d; }
  .badge { border: 1px solid #4a5050; border-radius: 0.35rem; padding: 0.05rem 0.4rem; color: #b8bcbb; font-size: 0.72rem; }
  .settings-form { display: grid; max-width: 65ch; gap: 0.6rem; }
  .field { display: grid; min-width: 0; gap: 0.35rem; align-content: start; }
  label { color: #b8bcbb; font-size: 0.82rem; }
  input:not([type='checkbox']) { min-width: 0; border: 1px solid #383d3e; border-radius: 0.48rem; background: #202324; padding: 0.62rem 0.72rem; color: #e8e9e7; font: inherit; }
  textarea { font: inherit; font-family: ui-monospace, SFMono-Regular, Menlo, monospace; padding: 0.5rem; resize: vertical; width: 100%; box-sizing: border-box; }
  .actions { display: flex; flex-wrap: wrap; align-items: center; gap: 0.55rem; }
  .hint { color: #929997; font-size: 0.82rem; margin: 0; }
  .eyebrow { color: #858a8a; font-size: 0.68rem; font-weight: 700; letter-spacing: 0.12em; }
  .prompt-help summary { cursor: pointer; color: #b8bcbb; font-size: 0.85rem; }
  .help-body { display: grid; gap: 0.4rem; color: #c9cdcb; font-size: 0.85rem; }
  .help-body p, .help-body ul { margin: 0; }
  .help-body ul { padding-left: 1.2rem; }
  button.primary { border-color: #c4a77d; background: #c4a77d; color: #1c1b18; font-weight: 650; }
  button.primary:hover:not(:disabled) { background: #d4ba94; }
  .confirm-backdrop { position: fixed; inset: 0; background: rgba(8, 9, 10, 0.62); }
  .confirm-dialog { position: fixed; top: 50%; left: 50%; z-index: 2; transform: translate(-50%, -50%); width: min(32rem, calc(100vw - 2rem)); border: 1px solid #3a3e3e; border-radius: 0.6rem; background: #1d2021; padding: 1.15rem; }
  .confirm-form { display: grid; gap: 0.7rem; }
  .confirm-dialog h2 { margin: 0; font-size: 1.05rem; }
  .confirm-dialog p { margin: 0; }
  .confirm-dialog .actions { justify-content: end; }
</style>
