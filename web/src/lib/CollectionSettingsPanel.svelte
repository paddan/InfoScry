<script lang="ts">
  import { onDestroy, tick } from 'svelte';
  import {
    ApiError,
    getCollectionIgnorePatterns,
    renameCollection,
    updateCollectionIgnorePatterns,
    type Collection,
  } from './api';
  import CollectionOcrSettings from './CollectionOcrSettings.svelte';
  import CollectionPromptSettings from './CollectionPromptSettings.svelte';

  export let collection: Collection;
  export let onCollectionsChanged: (selectedId?: string) => Promise<void> | void;
  /** Opens Admin → LLM profiles, where a profile is checked for image reading before OCR can use it. */
  export let onOpenLlmProfiles: (() => void) | undefined = undefined;
  export let onOpenDeleteDialog: () => void;
  /**
   * Manage prompts names the collection whose Prompts section must open. The section is revealed once
   * this panel shows that collection, and the page then clears the request so a later remount does not
   * open the settings again.
   */
  export let promptsPendingFor: string | null = null;
  export let onPromptsRevealed: () => void = () => {};

  let settingsOpen = false;
  let promptSettings: CollectionPromptSettings;
  $: if (promptsPendingFor !== null && promptsPendingFor === collection.id) {
    settingsOpen = true;
    onPromptsRevealed();
    void tick().then(() => promptSettings?.reveal());
  }

  /**
   * The settings draft for the selected collection: the two stored values the fields show, and what
   * the last save of each one did. The stored values remain the server's; these are only what the
   * reader is editing until a save answers.
   */
  let renameName = '';
  let renameSaving = false;
  let renameError: string | null = null;
  let renameSaved = false;
  // The ignore patterns draft: one pattern per line. `ignoreLoaded` is false until the saved list has been read, so a
  // save can never replace a list the reader has not seen.
  let ignoreText = '';
  let ignoreLoaded = false;
  let ignoreSaving = false;
  let ignoreError: string | null = null;
  let ignoreSaved = false;
  /**
   * The settings draft's generation. A save reads across an await and the reader can select another
   * collection meanwhile, so the generation captured when a save starts is what decides whether its
   * answer may still write into the fields and the status beside them.
   */
  let settingsGeneration = 0;
  let lastCollectionId: string | null = null;

  $: if (collection.id !== lastCollectionId) {
    lastCollectionId = collection.id;
    startSettings();
  }

  /** The settings fields start from the selected collection's own stored name. */
  function startSettings(): void {
    settingsGeneration += 1;
    renameName = collection.name;
    renameSaving = false;
    renameError = null;
    renameSaved = false;
    ignoreText = '';
    ignoreLoaded = false;
    ignoreSaving = false;
    ignoreError = null;
    ignoreSaved = false;
    void loadIgnorePatterns(collection.id, settingsGeneration);
  }

  /** Reads the saved ignore list into the draft; an answer for a collection no longer managed is dropped. */
  async function loadIgnorePatterns(collectionId: string, generation: number): Promise<void> {
    try {
      const patterns = await getCollectionIgnorePatterns(collectionId);
      if (generation !== settingsGeneration) return;
      ignoreText = patterns.join('\n');
      ignoreLoaded = true;
    } catch (failure) {
      if (generation !== settingsGeneration) return;
      ignoreError = describe(failure);
    }
  }

  /**
   * Saves the ignore list. The server refuses an invalid pattern, and the draft is kept so it can be fixed. Only
   * future imports use the new list: an import already queued or running keeps the list it was admitted with.
   */
  async function saveIgnorePatterns(event: SubmitEvent): Promise<void> {
    event.preventDefault();
    if (ignoreSaving || !ignoreLoaded) return;
    const generation = settingsGeneration;
    ignoreSaving = true;
    ignoreError = null;
    ignoreSaved = false;
    try {
      const saved = await updateCollectionIgnorePatterns(collection.id, ignoreText.split('\n'));
      if (generation !== settingsGeneration) return;
      ignoreText = saved.join('\n');
      ignoreSaved = true;
    } catch (failure) {
      if (generation !== settingsGeneration) return;
      ignoreError = describe(failure);
    } finally {
      if (generation === settingsGeneration) ignoreSaving = false;
    }
  }

  /**
   * Saves the name through the existing rename route. The collection keeps its id, its documents and
   * its import history; the parent reloads the collection list, so Admin and the workspace selector
   * both show the new name while the selection stays where it was.
   */
  async function saveName(event: SubmitEvent): Promise<void> {
    event.preventDefault();
    if (renameSaving) return;
    const generation = settingsGeneration;
    renameSaving = true;
    renameError = null;
    renameSaved = false;
    try {
      const updated = await renameCollection(collection.id, renameName.trim());
      // A save of another collection's name is not this form's news: the draft the reader is looking at
      // has been restarted since, so neither its field nor its status may take the answer.
      if (generation !== settingsGeneration) return;
      renameName = updated.name;
      renameSaved = true;
      await onCollectionsChanged();
    } catch (failure) {
      if (generation !== settingsGeneration) return;
      renameError = describe(failure);
    } finally {
      if (generation === settingsGeneration) renameSaving = false;
    }
  }

  function describe(failure: unknown): string {
    if (failure instanceof ApiError) return failure.message;
    return failure instanceof Error ? failure.message : 'Something went wrong.';
  }

  onDestroy(() => {
    settingsGeneration += 1;
  });
</script>

<details class="secondary-settings" bind:open={settingsOpen}>
  <summary>Settings</summary>
  <section class="collection-settings" aria-label={`Settings for ${collection.name}`}>
    <span class="eyebrow">SETTINGS</span>

    <form class="settings-form" onsubmit={saveName}>
      <div class="field">
        <label for="collection-settings-name">Collection name</label>
        <input
          id="collection-settings-name"
          bind:value={renameName}
          oninput={() => (renameSaved = false)}
          aria-invalid={renameError !== null}
        />
      </div>
      <div class="actions">
        <button type="submit" class="primary" disabled={renameSaving}>
          {renameSaving ? 'Saving name…' : 'Save name'}
        </button>
      </div>
      {#if renameError !== null}<p role="alert">{renameError}</p>{/if}
      {#if renameSaved}<p role="status">Name saved.</p>{/if}
    </form>

    <form class="settings-form" onsubmit={saveIgnorePatterns}>
      <div class="field">
        <label for="collection-settings-ignore">Ignored files</label>
        <textarea
          id="collection-settings-ignore"
          rows="8"
          spellcheck="false"
          autocapitalize="off"
          bind:value={ignoreText}
          oninput={() => (ignoreSaved = false)}
          disabled={!ignoreLoaded}
          aria-invalid={ignoreError !== null && ignoreLoaded}
          aria-describedby="collection-settings-ignore-note"
        ></textarea>
      </div>
      <div class="actions">
        <button type="submit" class="primary" disabled={ignoreSaving || !ignoreLoaded}>
          {ignoreSaving ? 'Saving ignored files…' : 'Save ignored files'}
        </button>
      </div>
      {#if ignoreError !== null}<p role="alert">{ignoreError}</p>{/if}
      {#if ignoreSaved}<p role="status">Ignored files saved.</p>{/if}
      <p class="hint" id="collection-settings-ignore-note">
        One pattern per line, like a .gitignore file: * and ? match within a name, ** matches across folders, a
        trailing / matches a folder (its contents are never read), ! re-includes, and # starts a comment. Every
        import into this collection skips matching files before anything else. A change applies to imports
        started afterwards, not to one already queued or running.
      </p>
    </form>

    <CollectionOcrSettings {collection} onChanged={() => onCollectionsChanged()} {onOpenLlmProfiles} />

    <CollectionPromptSettings bind:this={promptSettings} {collection} />
  </section>

  <section class="collection-deletion" aria-label={`Delete ${collection.name}`}>
    <span class="eyebrow">DELETE COLLECTION</span>
    <p class="hint">
      Deleting a collection removes every document InfoScry holds for it. Files outside InfoScry are
      not touched, and there is no undo.
    </p>
    <div class="actions">
      <button type="button" onclick={onOpenDeleteDialog}>Delete collection</button>
    </div>
  </section>
</details>

<style>
  .collection-settings { display: grid; gap: 0.9rem; border: 1px solid #303535; border-radius: 0.55rem; background: #191c1d; padding: 1rem; }
  .settings-form { display: grid; max-width: 65ch; gap: 0.6rem; }
  .settings-form .actions { justify-content: start; }
  .settings-form textarea { font: inherit; font-family: ui-monospace, SFMono-Regular, Menlo, monospace; padding: 0.5rem; resize: vertical; width: 100%; box-sizing: border-box; }
  .collection-deletion { display: grid; justify-items: start; gap: 0.6rem; border: 1px solid #303535; border-radius: 0.55rem; background: #191c1d; padding: 1rem; }
  .collection-deletion p { margin: 0; }
  .field { display: grid; min-width: 0; gap: 0.35rem; align-content: start; }
  label { color: #b8bcbb; font-size: 0.82rem; }
  input:not([type='checkbox']) { min-width: 0; border: 1px solid #383d3e; border-radius: 0.48rem; background: #202324; padding: 0.62rem 0.72rem; color: #e8e9e7; }
  .actions { display: flex; flex-wrap: wrap; gap: 0.55rem; }
  .hint { color: #929997; font-size: 0.82rem; }
  .eyebrow { color: #858a8a; font-size: 0.68rem; font-weight: 700; letter-spacing: 0.12em; }
  button.primary { border-color: #c4a77d; background: #c4a77d; color: #1c1b18; font-weight: 650; }
  button.primary:hover:not(:disabled) { background: #d4ba94; }
  summary { cursor: pointer; padding: 0.6rem 0; }
</style>
