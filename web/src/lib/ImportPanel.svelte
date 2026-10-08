<script lang="ts">
  import { onDestroy } from 'svelte';
  import {
    ApiError,
    enqueueImport,
    getImportItems,
    getJob,
    pickPaths,
    type ImportExternalApprovalResponse,
    type ImportItemApiView,
    type JobApiView,
    type JobState,
  } from './api';
  import JobApproval from './JobApproval.svelte';
  import { importItemOutcomeLabel } from './importOutcome';
  import { importProgressText } from './importProgress';

  export let collectionId: string;
  /** The collection the paths are added to, shown so the destination is explicit before submission. */
  export let collectionName: string;

  let selectedPaths: string[] = [];
  let recursive = false;
  /** Which files of a folder are imported: every type, only the listed extensions, or all but the listed ones. */
  let fileTypes: 'all' | 'include' | 'exclude' = 'all';
  let extensionText = '';
  let picking = false;
  let pickerUnavailable = false;
  let manualPath = '';
  let importing = false;
  let error: string | null = null;
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

  /** The extensions as typed, normalised: lower case, no leading dots, no repeats. Commas and spaces both separate. */
  function parseExtensions(text: string): string[] {
    const found = new Set<string>();
    for (const raw of text.split(/[\s,]+/)) {
      const extension = raw.replace(/^\.+/, '').toLowerCase();
      if (extension !== '') found.add(extension);
    }
    return [...found];
  }

  $: listedExtensions = fileTypes === 'all' ? [] : parseExtensions(extensionText);
  // A list that is chosen but names nothing would import everything, so the import waits for a real entry.
  $: extensionListEmpty = fileTypes !== 'all' && listedExtensions.length === 0;

  function isTerminal(state: JobState): boolean {
    return TERMINAL_STATES.includes(state);
  }

  /**
   * A job paused for external pages is COMPLETE in its record, so its state alone reads as finished. The
   * stage and the approval the server attaches say it is still waiting on a person.
   */
  function awaitingApproval(current: JobApiView): boolean {
    return current.state === 'COMPLETE' && current.stage === 'awaiting-approval' && Boolean(current.externalApproval);
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
    if (collectionId === '' || selectedPaths.length === 0 || extensionListEmpty || importing) return;
    const generation = ++importGeneration;
    error = null;
    items = [];
    job = null;
    importing = true;
    try {
      const extensions = {
        include: fileTypes === 'include' ? listedExtensions : [],
        exclude: fileTypes === 'exclude' ? listedExtensions : [],
      };
      const enqueued = await enqueueImport(collectionId, selectedPaths, recursive, extensions);
      if (generation !== importGeneration) return;
      job = enqueued.job;
      let current = enqueued.job;
      while (!isTerminal(current.state) || awaitingApproval(current)) {
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

  /** What one selected file is called: the server sends its name, never the path it was selected from. */
  function sourceLabel(item: ImportItemApiView): string {
    return item.sourceName ?? item.id;
  }

  function errorDetail(item: ImportItemApiView): string {
    if (item.errorMessage && item.errorCode) return `${item.errorMessage} (${item.errorCode})`;
    if (item.errorMessage) return item.errorMessage;
    return item.errorCode ?? '';
  }

  /**
   * The server recorded the approval and the job resumes, so the form leaves. The polling loop is still
   * running and reads the job's new state on its next poll; the state given here only bridges that gap.
   */
  function approved(answer: ImportExternalApprovalResponse): void {
    if (job === null) return;
    const state: JobState = answer.state === 'QUEUED' ? 'QUEUED' : 'RUNNING';
    job = { ...job, state, stage: answer.stage ?? null, externalApproval: undefined };
  }

  /** The server ended the paused import as cancelled, so the form leaves and the polling loop sees it ended. */
  function cancelled(): void {
    if (job === null) return;
    job = { ...job, state: 'CANCELLED', stage: null, externalApproval: undefined };
  }

  /**
   * The status line for the job as it is now. It takes its inputs as arguments so the reactive statement
   * below re-runs whenever the job, the run state or the results change.
   */
  function jobStatus(current: JobApiView | null, active: boolean, results: ImportItemApiView[]): string {
    if (active && current !== null && awaitingApproval(current)) return 'Waiting for your approval.';
    if (active && current !== null) {
      // The line names the file being imported and the stage a reader can act on: `Importing report.pdf
      // — Copying · 3 of 12 files`. The stage and the file's name are the server's; the words are ours.
      return importProgressText({
        state: current.state,
        stage: current.stage,
        currentItem: current.currentItem,
        filesCompleted: current.completed,
        filesTotal: current.total,
      });
    }
    if (current !== null && TERMINAL_STATES.includes(current.state)) {
      if (current.state === 'COMPLETE') return `Import complete. ${results.length} file${results.length === 1 ? '' : 's'}.`;
      if (current.state === 'CANCELLED') return 'Import cancelled.';
      return `Import failed${current.errorCode ? ` (${current.errorCode})` : ''}.`;
    }
    return '';
  }

  $: status = jobStatus(job, importing, items);

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
  {#if collectionName !== ''}<p class="destination">Adding documents to <strong>{collectionName}</strong>.</p>{/if}
  {#if error !== null}<p role="alert">{error}</p>{/if}
  {#if importing && job !== null}
    <div class="progress">
      {#if job.total > 0}
        <progress value={job.completed} max={job.total} aria-label="Import progress"></progress>
      {:else}
        <progress aria-label="Import progress"></progress>
      {/if}
      <p role="status">{status}</p>
    </div>
  {/if}
  {#if importing && job !== null && job.externalApproval && awaitingApproval(job)}
    <JobApproval jobId={job.id} approval={job.externalApproval} onapproved={approved} oncancelled={cancelled} />
  {/if}
  {#if !importing && job !== null && isTerminal(job.state)}
    <p role={job.state === 'FAILED' ? 'alert' : 'status'}>{status}</p>
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

  <fieldset class="file-types">
    <legend>File types</legend>
    <label class="check-label"><input type="radio" name="file-types" value="all" bind:group={fileTypes} /> All file types</label>
    <label class="check-label"><input type="radio" name="file-types" value="include" bind:group={fileTypes} /> Only these extensions</label>
    <label class="check-label"><input type="radio" name="file-types" value="exclude" bind:group={fileTypes} /> All except these extensions</label>
    {#if fileTypes !== 'all'}
      <div class="extension-row">
        <label for="extension-list">Extensions</label>
        <input id="extension-list" bind:value={extensionText} placeholder="pdf, docx" />
      </div>
      <p class="hint">Separate extensions with commas or spaces. A leading dot is optional.</p>
    {/if}
  </fieldset>

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

  <div class="import-row">
    <button type="button" class="primary" onclick={startImport} disabled={importing || collectionId === '' || selectedPaths.length === 0 || extensionListEmpty}>
      {importing ? 'Importing…' : 'Import'}
    </button>
    {#if collectionId === ''}<p class="hint">Select a collection to import into.</p>{/if}
    {#if !importing && collectionId !== '' && selectedPaths.length === 0}<p class="hint">Choose files or a folder to import.</p>{/if}
    {#if extensionListEmpty}<p class="hint">Enter at least one extension.</p>{/if}
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
            <td>{importItemOutcomeLabel(item)}</td>
            <td>{errorDetail(item)}</td>
          </tr>
        {/each}
      </tbody>
    </table>
  {/if}
</section>

<style>
  .import-panel { max-width: 52rem; display: grid; gap: 1.15rem; align-content: start; }
  .destination { margin: 0; }
  .destination strong { color: #f3e6d1; }
  .picker-row { display: flex; align-items: center; gap: 0.55rem; flex-wrap: wrap; }
  .check-label { display: flex; align-items: center; gap: 0.5rem; }
  .check-label input { accent-color: #c4a77d; }
  .file-types { display: grid; gap: 0.5rem; margin: 0; padding: 0; border: 0; }
  .file-types legend { color: #858a8a; font-size: 0.68rem; font-weight: 700; letter-spacing: 0.12em; text-transform: uppercase; padding: 0; margin-bottom: 0.35rem; }
  .file-types .hint { margin: 0; color: #929997; font-size: 0.8rem; }
  .extension-row { display: flex; align-items: center; gap: 0.55rem; }
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
  label { color: #b8bcbb; font-size: 0.82rem; }
  input:not([type='checkbox']):not([type='radio']) {
    min-width: 0;
    border: 1px solid #383d3e;
    border-radius: 0.48rem;
    background: #202324;
    padding: 0.62rem 0.72rem;
    color: #e8e9e7;
  }
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
</style>