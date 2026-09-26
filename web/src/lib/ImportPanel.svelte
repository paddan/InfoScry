<script lang="ts">
  import { onDestroy } from 'svelte';
  import {
    ApiError,
    createCollection,
    enqueueImport,
    getImportItems,
    getJob,
    pickPaths,
    type ImportItemApiView,
    type JobApiView,
    type JobState,
  } from './api';

  export let collectionId: string;
  export let onCollectionsChanged: (selectedId?: string) => Promise<void> | void;

  let selectedPaths: string[] = [];
  let recursive = false;
  let picking = false;
  let pickerUnavailable = false;
  let manualPath = '';
  let newCollectionName = '';
  let newCollectionDescription = '';
  let creatingCollection = false;
  let importing = false;
  let error: string | null = null;
  let creatingError: string | null = null;
  let job: JobApiView | null = null;
  let items: ImportItemApiView[] = [];
  let importGeneration = 0;
  /**
   * How the current poll wait ends. The timer calls it, and so does destroying the panel: a `clearTimeout`
   * alone would leave the promise in `startImport` pending forever, holding the whole loop in memory.
   */
  let endPollWait: (() => void) | null = null;
  let pollTimer: ReturnType<typeof setTimeout> | null = null;

  const TERMINAL_STATES: JobState[] = ['COMPLETE', 'FAILED', 'CANCELLED'];

  function isTerminal(state: JobState): boolean {
    return TERMINAL_STATES.includes(state);
  }

  function addPaths(paths: string[]): void {
    const additions = paths.filter((path) => path.trim() !== '' && !selectedPaths.includes(path));
    if (additions.length > 0) selectedPaths = [...selectedPaths, ...additions];
  }

  async function chooseFiles(): Promise<void> {
    await openPicker(false);
  }

  async function chooseFolder(): Promise<void> {
    await openPicker(true);
  }

  async function openPicker(directory: boolean): Promise<void> {
    if (picking) return;
    picking = true;
    error = null;
    try {
      addPaths(await pickPaths(directory));
    } catch (failure) {
      if (failure instanceof ApiError && failure.code === 'PICK_CANCELLED') {
        // The user closed the dialog without choosing anything; that is not an error.
      } else if (failure instanceof ApiError && failure.code === 'PICK_UNAVAILABLE') {
        pickerUnavailable = true;
        error = describe(failure);
      } else {
        error = describe(failure);
      }
    } finally {
      picking = false;
    }
  }

  function addManualPath(): void {
    const path = manualPath.trim();
    if (path === '') return;
    addPaths([path]);
    manualPath = '';
  }

  function removePath(path: string): void {
    selectedPaths = selectedPaths.filter((candidate) => candidate !== path);
  }

  async function createCollectionForm(event: SubmitEvent): Promise<void> {
    event.preventDefault();
    const name = newCollectionName.trim();
    if (name === '' || creatingCollection) return;
    creatingCollection = true;
    creatingError = null;
    try {
      const created = await createCollection(name, newCollectionDescription.trim() === ''
        ? undefined
        : newCollectionDescription.trim());
      newCollectionName = '';
      newCollectionDescription = '';
      await onCollectionsChanged(created.id);
    } catch (failure) {
      creatingError = describe(failure);
    } finally {
      creatingCollection = false;
    }
  }

  function sleep(milliseconds: number): Promise<void> {
    return new Promise((resolve) => {
      endPollWait = () => {
        if (pollTimer !== null) clearTimeout(pollTimer);
        pollTimer = null;
        endPollWait = null;
        resolve();
      };
      pollTimer = setTimeout(endPollWait, milliseconds);
    });
  }

  async function startImport(): Promise<void> {
    if (collectionId === '' || selectedPaths.length === 0 || importing) return;
    const generation = ++importGeneration;
    error = null;
    items = [];
    job = null;
    importing = true;
    try {
      const enqueued = await enqueueImport(collectionId, selectedPaths, recursive);
      if (generation !== importGeneration) return;
      job = enqueued.job;
      let current = enqueued.job;
      while (!isTerminal(current.state)) {
        await sleep(1000);
        if (generation !== importGeneration) return;
        current = await getJob(current.id);
        if (generation !== importGeneration) return;
        job = current;
      }
      const results = await getImportItems(current.id);
      if (generation !== importGeneration) return;
      items = results;
    } catch (failure) {
      if (generation === importGeneration) error = describe(failure);
    } finally {
      if (generation === importGeneration) importing = false;
    }
  }

  function sourceLabel(item: ImportItemApiView): string {
    return item.sourcePath ?? item.sourceName ?? item.id;
  }

  function errorDetail(item: ImportItemApiView): string {
    if (item.errorMessage && item.errorCode) return `${item.errorMessage} (${item.errorCode})`;
    if (item.errorMessage) return item.errorMessage;
    return item.errorCode ?? '';
  }

  function jobStatus(): string {
    if (importing && job !== null) {
      const stage = job.stage ? ` — ${job.stage}` : '';
      const count = job.total > 0 ? ` (${job.completed} of ${job.total} items)` : '';
      return `Importing…${stage}${count}`;
    }
    if (job !== null && TERMINAL_STATES.includes(job.state)) {
      if (job.state === 'COMPLETE') return `Import complete. ${items.length} item${items.length === 1 ? '' : 's'}.`;
      if (job.state === 'CANCELLED') return 'Import cancelled.';
      return `Import failed${job.errorCode ? ` (${job.errorCode})` : ''}.`;
    }
    return '';
  }

  function describe(failure: unknown): string {
    if (failure instanceof ApiError) return failure.message;
    return failure instanceof Error ? failure.message : 'Something went wrong.';
  }

  onDestroy(() => {
    // The generation stops any further work; ending the wait releases the loop sleeping on it.
    importGeneration += 1;
    endPollWait?.();
  });
</script>

<section class="import-panel" aria-label="Import local files">
  {#if error !== null}<p role="alert">{error}</p>{/if}
  {#if importing && job !== null}
    <div class="progress">
      {#if job.total > 0}
        <progress value={job.completed} max={job.total} aria-label="Import progress"></progress>
      {:else}
        <progress aria-label="Import progress"></progress>
      {/if}
      <p role="status">{jobStatus()}</p>
    </div>
  {/if}
  {#if !importing && job !== null && isTerminal(job.state)}
    <p role={job.state === 'FAILED' ? 'alert' : 'status'}>{jobStatus()}</p>
  {/if}

  <div class="picker-row">
    <button type="button" onclick={chooseFiles} disabled={picking}>
      {picking ? 'Choosing…' : 'Choose files…'}
    </button>
    <button type="button" onclick={chooseFolder} disabled={picking}>
      {picking ? 'Choosing…' : 'Choose folder…'}
    </button>
    <label class="check-label"><input type="checkbox" bind:checked={recursive} /> Include subfolders</label>
  </div>

  {#if pickerUnavailable}
    <div class="manual-fallback">
      <p>This machine cannot open the pick dialog, so enter each path to import by hand.</p>
      <div class="manual-row">
        <label class="visually-hidden" for="manual-path">Path</label>
        <input id="manual-path" bind:value={manualPath} placeholder="/path/to/file-or-folder" onkeydown={(event) => { if (event.key === 'Enter') { event.preventDefault(); addManualPath(); } }} />
        <button type="button" onclick={addManualPath} disabled={manualPath.trim() === ''}>Add</button>
      </div>
    </div>
  {/if}

  <div class="paths">
    <span class="eyebrow">SELECTED PATHS</span>
    {#if selectedPaths.length === 0}
      <p role="status">No paths selected yet.</p>
    {:else}
      <ul>
        {#each selectedPaths as path (path)}
          <li>
            <span>{path}</span>
            <button type="button" aria-label="Remove {path}" onclick={() => removePath(path)}>×</button>
          </li>
        {/each}
      </ul>
    {/if}
  </div>

  <details class="new-collection">
    <summary>New collection</summary>
    <form onsubmit={createCollectionForm} class="collection-form">
      {#if creatingError !== null}<p role="alert">{creatingError}</p>{/if}
      <div class="field">
        <label for="new-collection-name">Name</label>
        <input id="new-collection-name" bind:value={newCollectionName} required />
      </div>
      <div class="field">
        <label for="new-collection-description">Description</label>
        <input id="new-collection-description" bind:value={newCollectionDescription} />
      </div>
      <div class="actions">
        <button type="submit" class="primary" disabled={creatingCollection || newCollectionName.trim() === ''}>
          {creatingCollection ? 'Creating…' : 'Create'}
        </button>
      </div>
    </form>
  </details>

  <div class="import-row">
    <button type="button" class="primary" onclick={startImport} disabled={importing || collectionId === '' || selectedPaths.length === 0}>
      {importing ? 'Importing…' : 'Import'}
    </button>
    {#if collectionId === ''}<p class="hint">Select a collection to import into.</p>{/if}
    {#if !importing && collectionId !== '' && selectedPaths.length === 0}<p class="hint">Choose files or a folder to import.</p>{/if}
  </div>

  {#if items.length > 0}
    <table class="results">
      <caption class="visually-hidden">Import results</caption>
      <thead>
        <tr>
          <th scope="col">Source</th>
          <th scope="col">Outcome</th>
          <th scope="col">Details</th>
        </tr>
      </thead>
      <tbody>
        {#each items as item (item.id)}
          <tr>
            <td>{sourceLabel(item)}</td>
            <td>{item.outcome}</td>
            <td>{errorDetail(item)}</td>
          </tr>
        {/each}
      </tbody>
    </table>
  {/if}
</section>

<style>
  .import-panel { max-width: 52rem; display: grid; gap: 1.15rem; align-content: start; }
  .picker-row { display: flex; align-items: center; gap: 0.55rem; flex-wrap: wrap; }
  .check-label { display: flex; align-items: center; gap: 0.5rem; }
  .check-label input { accent-color: #c4a77d; }
  .manual-fallback { display: grid; gap: 0.5rem; padding: 0.85rem; border: 1px solid #6e5a33; border-radius: 0.55rem; background: #1d1c1a; }
  .manual-fallback p { margin: 0; color: #d8c9a8; font-size: 0.85rem; }
  .manual-row { display: flex; gap: 0.55rem; }
  .paths { display: grid; gap: 0.55rem; }
  .paths p { margin: 0; color: #929997; font-size: 0.85rem; }
  .paths ul { margin: 0; padding: 0; list-style: none; border-top: 1px solid #2b3030; }
  .paths li { display: flex; align-items: center; justify-content: space-between; gap: 0.6rem; border-bottom: 1px solid #2b3030; padding: 0.45rem 0.1rem; }
  .paths li span { overflow-wrap: anywhere; font-size: 0.85rem; }
  .paths li button { padding: 0.15rem 0.55rem; line-height: 1.2; }
  .eyebrow { color: #858a8a; font-size: 0.68rem; font-weight: 700; letter-spacing: 0.12em; }
  .new-collection { border: 1px solid #303535; border-radius: 0.55rem; background: #191c1d; }
  .new-collection summary { color: #c4c8c6; font-size: 0.85rem; cursor: pointer; padding: 0.75rem 0.9rem; }
  .collection-form { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0.9rem 1rem; padding: 0 0.9rem 0.9rem; }
  .field { display: grid; gap: 0.35rem; align-content: start; }
  label { color: #b8bcbb; font-size: 0.82rem; }
  input:not([type='checkbox']) {
    min-width: 0;
    border: 1px solid #383d3e;
    border-radius: 0.48rem;
    background: #202324;
    padding: 0.62rem 0.72rem;
    color: #e8e9e7;
  }
  .actions { grid-column: 1 / -1; }
  .import-row { display: flex; align-items: center; gap: 0.6rem; }
  .import-row .hint { margin: 0; color: #929997; font-size: 0.8rem; }
  .progress { display: grid; gap: 0.45rem; }
  .progress progress { width: 100%; height: 0.6rem; }
  .progress progress::-webkit-progress-bar { background: #202324; border-radius: 0.3rem; }
  .progress progress::-webkit-progress-value { background: #c4a77d; border-radius: 0.3rem; }
  .progress progress::-moz-progress-bar { background: #c4a77d; border-radius: 0.3rem; }
  .progress p { margin: 0; }
  button.primary { border-color: #c4a77d; background: #c4a77d; color: #1c1b18; font-weight: 650; }
  button.primary:hover:not(:disabled) { background: #d4ba94; }
  .results { border-collapse: collapse; width: 100%; font-size: 0.85rem; }
  .results th, .results td { border-bottom: 1px solid #2b3030; padding: 0.5rem 0.6rem; text-align: left; vertical-align: top; }
  .results th { color: #929997; font-size: 0.72rem; font-weight: 700; letter-spacing: 0.08em; text-transform: uppercase; }
  .results td:first-child { overflow-wrap: anywhere; }
  .visually-hidden { position: absolute; width: 1px; height: 1px; padding: 0; margin: -1px; overflow: hidden; clip: rect(0,0,0,0); white-space: nowrap; border: 0; }
  @media (max-width: 36rem) {
    .collection-form { grid-template-columns: minmax(0, 1fr); }
  }
</style>