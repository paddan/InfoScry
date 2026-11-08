<script lang="ts">
  import { onDestroy } from 'svelte';
  import {
    ApiError,
    cancelJob,
    getImportItems,
    getJob,
    uploadFile,
    type ImportStarted,
    type ImportItemApiView,
    type JobApiView,
    type JobState,
  } from './api';
  import type { ImageExtraction, RescanStarted } from './api';
  import ImportUnitProgress from './ImportUnitProgress.svelte';
  import StartReadingDialog from './StartReadingDialog.svelte';
  import { importItemOutcomeLabel } from './importOutcome';
  import { importProgressText } from './importProgress';

  export let collectionId: string;
  export let defaultImageExtraction: ImageExtraction = 'off';
  export let defaultImageProfileId: string | null = null;
  export let defaultIdentifyAppearance = false;
  export let visible = true;
  /** Refresh collection counts/history after admission and after the job reaches a terminal state. */
  export let onImportChanged: (collectionId: string) => void = () => {};
  /** The collection the paths are added to, shown so the destination is explicit before submission. */
  export let collectionName: string;
  /** An import of this collection that has not finished yet; the panel shows its progress as its own. */
  export let unfinishedImportId: string | null = null;
  /** Kept for the panel's callers; importing is the same for everyone who may import. */
  export let isAdmin = false;
  $: void isAdmin;

  /**
   * What the person chose on this computer: single files, or a folder with everything under it. Nothing is
   * sent until Import is pressed, so the file types and Include subfolders still decide what is uploaded.
   */
  type Chosen = { key: string; label: string; folder: boolean; files: File[] };
  let chosen: Chosen[] = [];
  let uploading: string | null = null;
  let uploadNotice: string | null = null;
  let fileInput: HTMLInputElement | null = null;
  let folderInput: HTMLInputElement | null = null;
  /** Upload ids of files already sent from this panel, so a cancelled dialog does not send them again. */
  const uploaded = new Map<File, string | null>();
  /** Upload ids the start dialog is opened with. */
  let uploadIds: string[] = [];

  function addChosen(items: Chosen[]): void {
    const known = new Set(chosen.map((item) => item.key));
    chosen = [...chosen, ...items.filter((item) => !known.has(item.key))];
  }

  function chooseFiles(event: Event): void {
    const input = event.currentTarget as HTMLInputElement;
    const files = Array.from(input.files ?? []);
    input.value = '';
    addChosen(files.map((file) => ({ key: `file:${file.name}:${file.size}:${file.lastModified}`, label: file.name, folder: false, files: [file] })));
  }

  function chooseFolder(event: Event): void {
    const input = event.currentTarget as HTMLInputElement;
    // Name order, as a folder reads in a file manager, rather than whatever order the browser lists it in.
    const files = Array.from(input.files ?? []).sort((a, b) => (a.webkitRelativePath || a.name).localeCompare(b.webkitRelativePath || b.name));
    input.value = '';
    const byFolder = new Map<string, File[]>();
    for (const file of files) {
      const top = (file.webkitRelativePath || file.name).split('/')[0];
      byFolder.set(top, [...(byFolder.get(top) ?? []), file]);
    }
    addChosen([...byFolder].map(([folder, inFolder]) => ({ key: `folder:${folder}:${inFolder.length}`, label: `${folder}/`, folder: true, files: inFolder })));
  }

  function removeChosen(key: string): void {
    chosen = chosen.filter((item) => item.key !== key);
  }

  let recursive = false;
  /** Which files are imported: every type, only the listed extensions, or all but the listed ones. */
  let fileTypes: 'all' | 'include' | 'exclude' = 'all';
  let extensionText = '';

  /** A chosen folder's files: hidden ones are left out, and files in subfolders only with Include subfolders. */
  function filesOf(item: Chosen, subfolders: boolean): File[] {
    if (!item.folder) return item.files;
    return item.files.filter((file) => {
      const segments = (file.webkitRelativePath || file.name).split('/');
      if (segments.some((segment) => segment.startsWith('.'))) return false;
      return subfolders || segments.length <= 2;
    });
  }

  function keptByType(file: File, types: typeof fileTypes, extensions: string[]): boolean {
    if (types === 'all') return true;
    const dot = file.name.lastIndexOf('.');
    const extension = dot <= 0 ? '' : file.name.slice(dot + 1).toLowerCase();
    return types === 'include' ? extensions.includes(extension) : !extensions.includes(extension);
  }

  $: importable = chosen.flatMap((item) => filesOf(item, recursive)).filter((file) => keptByType(file, fileTypes, listedExtensions));

  let importing = false;
  let error: string | null = null;
  let job: JobApiView | null = null;
  let dialogOpen = false;
  /** A stop request is on its way to the server; the job itself says once the request is recorded. */
  let stopping = false;
  /** The finished import's files are read a page at a time: an import can hold tens of thousands. */
  const ITEMS_PAGE_SIZE = 50;
  let items: ImportItemApiView[] = [];
  let itemsTotal = 0;
  let itemsOffset = 0;
  let itemsLoading = false;
  /** The finished job whose files the table pages through. */
  let finishedJobId: string | null = null;
  let importGeneration = 0;
  /**
   * How the current poll wait ends. The timer calls it, and so does destroying the panel: a `clearTimeout`
   * alone would leave the promise in `startImport` pending forever, holding the whole loop in memory.
   */
  let endPollWait: (() => void) | null = null;
  let pollTimer: ReturnType<typeof setTimeout> | null = null;
  let resumeVisibleWait: (() => void) | null = null;

  $: updateVisibility(visible);

  function updateVisibility(next: boolean): void {
    if (next) resumeVisibleWait?.();
    else endPollWait?.();
  }

  function waitUntilVisible(): Promise<void> {
    if (visible) return Promise.resolve();
    return new Promise((resolve) => {
      resumeVisibleWait = () => {
        resumeVisibleWait = null;
        resolve();
      };
    });
  }

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

  /** Uploads what will be imported, then opens the start dialog; files already in the collection are not sent twice. */
  async function startImport(): Promise<void> {
    if (collectionId === '' || importable.length === 0 || extensionListEmpty || importing || uploading !== null) return;
    error = null;
    uploadNotice = null;
    const files = importable;
    const ids: string[] = [];
    let already = 0;
    let same = 0;
    try {
      for (const [index, file] of files.entries()) {
        if (!uploaded.has(file)) {
          uploading = `Uploading ${index + 1} of ${files.length}: ${file.name}`;
          const answer = await uploadFile(collectionId, file);
          uploaded.set(file, answer.uploadId ?? null);
        }
        const id = uploaded.get(file);
        // The server keeps one upload per content, so a file identical to another chosen one comes back as it.
        if (id && ids.includes(id)) same += 1;
        else if (id) ids.push(id);
        else already += 1;
      }
    } catch (failure) {
      error = describe(failure);
      return;
    } finally {
      uploading = null;
    }
    const notices: string[] = [];
    if (already > 0) notices.push(`${already} file${already === 1 ? ' is' : 's are'} already in the collection and ${already === 1 ? 'was' : 'were'} not uploaded again.`);
    if (same > 0) notices.push(`${same} file${same === 1 ? ' is' : 's are'} identical to another chosen file and ${same === 1 ? 'is' : 'are'} imported once.`);
    if (notices.length > 0) uploadNotice = notices.join(' ');
    if (ids.length === 0) return;
    uploadIds = ids;
    dialogOpen = true;
  }

  function readingStarted(run: ImportStarted | RescanStarted): void {
    dialogOpen = false;
    if (!('job' in run)) return;
    onImportChanged(collectionId);
    void followImport(run.job);
  }

  async function followImport(initial: JobApiView): Promise<void> {
    const generation = ++importGeneration;
    error = null;
    items = [];
    itemsTotal = 0;
    itemsOffset = 0;
    finishedJobId = null;
    job = null;
    stopping = false;
    importing = true;
    try {
      job = initial;
      let current = initial;
      while (!isTerminal(current.state)) {
        if (visible) await sleep(1000);
        if (generation !== importGeneration) return;
        await waitUntilVisible();
        if (generation !== importGeneration) return;
        current = await getJob(current.id);
        if (generation !== importGeneration) return;
        job = current;
      }
      await waitUntilVisible();
      if (generation !== importGeneration) return;
      onImportChanged(collectionId);
      const results = await getImportItems(current.id, { limit: ITEMS_PAGE_SIZE, offset: 0 });
      if (generation !== importGeneration) return;
      finishedJobId = current.id;
      items = results.items;
      itemsTotal = results.total;
    } catch (failure) {
      if (generation === importGeneration) error = describe(failure);
    } finally {
      if (generation === importGeneration) importing = false;
    }
  }

  /**
   * Asks the server to stop the import. It ends at the next file or page boundary, keeping what it saved,
   * and the polling loop reports it once the job is cancelled. Asking again is harmless.
   */
  async function stopImport(): Promise<void> {
    if (job === null || stopping || isTerminal(job.state)) return;
    const generation = importGeneration;
    stopping = true;
    error = null;
    try {
      const updated = await cancelJob(job.id);
      if (generation === importGeneration && !isTerminal(job.state)) job = updated;
    } catch (failure) {
      if (generation === importGeneration) error = describe(failure);
    } finally {
      if (generation === importGeneration) stopping = false;
    }
  }

  /**
   * Follows an unfinished import of this collection — one a server restart resumed, or one started before
   * the page was reloaded — the same way as one started here. The caller finds it in the durable history;
   * the panel follows each one once, never while it is following another, and moves on to the next queued
   * import when the one it showed ends.
   */
  async function resumeImport(jobId: string): Promise<void> {
    const generation = importGeneration;
    try {
      await waitUntilVisible();
      if (generation !== importGeneration) return;
      const current = await getJob(jobId);
      if (generation !== importGeneration || isTerminal(current.state)) return;
      await followImport(current);
    } catch (failure) {
      if (generation === importGeneration) error = describe(failure);
    }
  }

  let resumedImportId: string | null = null;
  $: if (unfinishedImportId !== null && unfinishedImportId !== resumedImportId && !importing && (job === null || isTerminal(job.state))) {
    resumedImportId = unfinishedImportId;
    void resumeImport(unfinishedImportId);
  }

  /** Moves the results table one page back or forward; the page is read again, never kept from before. */
  async function changeItemsPage(delta: number): Promise<void> {
    if (finishedJobId === null || itemsLoading) return;
    const generation = importGeneration;
    const offset = Math.max(0, itemsOffset + delta * ITEMS_PAGE_SIZE);
    itemsLoading = true;
    error = null;
    try {
      const page = await getImportItems(finishedJobId, { limit: ITEMS_PAGE_SIZE, offset });
      if (generation !== importGeneration) return;
      items = page.items;
      itemsTotal = page.total;
      itemsOffset = offset;
    } catch (failure) {
      if (generation === importGeneration) error = describe(failure);
    } finally {
      if (generation === importGeneration) itemsLoading = false;
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
   * The status line for the job as it is now. It takes its inputs as arguments so the reactive statement
   * below re-runs whenever the job, the run state or the results change.
   */
  function jobStatus(current: JobApiView | null, active: boolean, files: number): string {
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
      if (current.state === 'COMPLETE') return `Import complete. ${files} file${files === 1 ? '' : 's'}.`;
      if (current.state === 'CANCELLED') return 'Import cancelled.';
      return `Import failed${current.errorCode ? ` (${current.errorCode})` : ''}.`;
    }
    return '';
  }

  $: status = jobStatus(job, importing, itemsTotal);

  function describe(failure: unknown): string {
    if (failure instanceof ApiError) return failure.message;
    return failure instanceof Error ? failure.message : 'Something went wrong.';
  }

  onDestroy(() => {
    // The generation stops any further work; ending the wait releases the loop sleeping on it.
    importGeneration += 1;
    endPollWait?.();
    resumeVisibleWait?.();
  });
</script>

<section class="import-panel" aria-label="Import local files">
  <h3 class="visually-hidden">Import local files</h3>
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
      <ImportUnitProgress progress={job.currentProgress} />
      {#if !isTerminal(job.state)}
        <div>
          <button type="button" onclick={stopImport} disabled={stopping || job.cancelRequested}>
            {stopping || job.cancelRequested ? 'Stopping…' : 'Stop importing'}
          </button>
        </div>
      {/if}
    </div>
  {/if}
  {#if !importing && job !== null && isTerminal(job.state)}
    <p role={job.state === 'FAILED' ? 'alert' : 'status'}>{status}</p>
  {/if}

  <div class="picker-row">
    <input class="visually-hidden" type="file" multiple bind:this={fileInput} onchange={chooseFiles} aria-label="Files to upload" />
    <input class="visually-hidden" type="file" webkitdirectory bind:this={folderInput} onchange={chooseFolder} aria-label="Folder to upload" />
    <button type="button" class="primary-outline" onclick={() => fileInput?.click()} disabled={uploading !== null || collectionId === ''}>Upload files…</button>
    <button type="button" onclick={() => folderInput?.click()} disabled={uploading !== null || collectionId === ''}>Upload folder…</button>
    <label class="check-label"><input type="checkbox" bind:checked={recursive} /> Include subfolders</label>
  </div>
  <p class="hint">Files are chosen on this computer and uploaded when you press Import.</p>

  {#if uploading !== null}<p role="status">{uploading}</p>{/if}
  {#if uploadNotice !== null}<p role="status">{uploadNotice}</p>{/if}

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

  <div class="paths">
    <span class="eyebrow">SELECTED</span>
    {#if chosen.length === 0}
      <p role="status">Nothing selected yet.</p>
    {:else}
      <ul>
        {#each chosen as item (item.key)}
          <li>
            <span>{item.label}{#if item.folder} <span class="hint">({filesOf(item, recursive).length} file{filesOf(item, recursive).length === 1 ? '' : 's'})</span>{/if}</span>
            <button type="button" aria-label="Remove {item.label}" onclick={() => removeChosen(item.key)}>×</button>
          </li>
        {/each}
      </ul>
    {/if}
  </div>

  <div class="import-row">
    <button type="button" class="primary" onclick={startImport} disabled={importing || uploading !== null || collectionId === '' || importable.length === 0 || extensionListEmpty}>
      {importing ? 'Importing…' : uploading !== null ? 'Uploading…' : 'Import'}
    </button>
    {#if collectionId === ''}<p class="hint">Select a collection to import into.</p>{/if}
    {#if !importing && collectionId !== '' && chosen.length === 0}<p class="hint">Choose files or a folder on this computer.</p>{/if}
    {#if chosen.length > 0 && importable.length === 0 && !extensionListEmpty}<p class="hint">No chosen file matches the file types.</p>{/if}
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
    {#if itemsTotal > ITEMS_PAGE_SIZE}
      <nav class="paging" aria-label="Import results pages">
        <p class="hint">Showing {itemsOffset + 1}–{itemsOffset + items.length} of {itemsTotal} files</p>
        <button type="button" disabled={itemsOffset === 0 || itemsLoading} onclick={() => changeItemsPage(-1)}>Previous files</button>
        <button type="button" disabled={itemsOffset + ITEMS_PAGE_SIZE >= itemsTotal || itemsLoading} onclick={() => changeItemsPage(1)}>Next files</button>
      </nav>
    {/if}
  {/if}
  {#if dialogOpen}
    <StartReadingDialog
      {collectionId}
      {defaultImageExtraction}
      {defaultImageProfileId}
      {defaultIdentifyAppearance}
      request={{ kind: 'import', uploads: uploadIds, include: [], exclude: [] }}
      onstarted={readingStarted}
      oncancel={() => (dialogOpen = false)}
    />
  {/if}
</section>

<style>
  .import-panel { max-width: 52rem; display: grid; gap: 1.15rem; align-content: start; }
  .destination { margin: 0; }
  .destination strong { color: #f3e6d1; }
  .picker-row { display: flex; align-items: center; gap: 0.55rem; flex-wrap: wrap; }
  .picker-row + .hint { margin: -0.6rem 0 0; color: #929997; font-size: 0.8rem; }
  .check-label { display: flex; align-items: center; gap: 0.5rem; }
  .check-label input { accent-color: #c4a77d; }
  .file-types { display: grid; gap: 0.5rem; margin: 0; padding: 0; border: 0; }
  .file-types legend { color: #858a8a; font-size: 0.68rem; font-weight: 700; letter-spacing: 0.12em; text-transform: uppercase; padding: 0; margin-bottom: 0.35rem; }
  .file-types .hint { margin: 0; color: #929997; font-size: 0.8rem; }
  .extension-row { display: flex; align-items: center; gap: 0.55rem; }
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
  .paging { display: flex; align-items: center; gap: 0.55rem; flex-wrap: wrap; }
  .paging .hint { margin: 0 auto 0 0; }
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
