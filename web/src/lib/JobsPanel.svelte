<script lang="ts">
  import { onDestroy } from 'svelte';
  import { cancelImageRescan, cancelJob, getImageRescanRun, listActiveJobs, type Collection, type ImageRescanRun, type JobApiView, type JobType } from './api';
  import ImportUnitProgress from './ImportUnitProgress.svelte';
  import { stageForReader, stageLabel } from './importProgress';

  export let collections: Collection[] = [];
  /** The list is read again only while the tab is shown; a hidden tab asks the server for nothing. */
  export let visible = true;

  const REFRESH_MILLIS = 2000;
  const TYPE_LABELS: Record<JobType, string> = {
    IMPORT: 'Import',
    REINDEX: 'Reindex',
    RETRY: 'Retry',
    RESCAN: 'Rescan',
    SELECTABLE_PDF: 'Selectable PDF',
    IMAGE_RESCAN: 'Find images',
  };

  let jobs: JobApiView[] = [];
  /** Image runs by their job id, read for every run whose rescans are on the list. */
  let runs = new Map<string, ImageRescanRun>();

  type Entry =
    | { kind: 'job'; job: JobApiView }
    | { kind: 'images'; jobId: string; collectionId: string; children: JobApiView[] };

  /** The list as shown: a collection's image run is one row however many documents it is scanning. */
  $: entries = groupedEntries(jobs);

  function groupedEntries(list: JobApiView[]): Entry[] {
    const groups = new Map<string, Entry & { kind: 'images' }>();
    const out: Entry[] = [];
    for (const job of list) {
      const runId = job.type === 'IMAGE_RESCAN' ? job.id : job.parentJobId ?? null;
      if (runId === null || !job.collectionId) {
        out.push({ kind: 'job', job });
        continue;
      }
      let group = groups.get(runId);
      if (group === undefined) {
        group = { kind: 'images', jobId: runId, collectionId: job.collectionId, children: [] };
        groups.set(runId, group);
        out.push(group);
      }
      if (job.type !== 'IMAGE_RESCAN') group.children.push(job);
    }
    return out;
  }

  async function loadRuns(list: JobApiView[]): Promise<void> {
    const wanted = new Map<string, string>();
    for (const entry of groupedEntries(list)) if (entry.kind === 'images') wanted.set(entry.jobId, entry.collectionId);
    const next = new Map<string, ImageRescanRun>();
    await Promise.all([...wanted].map(async ([jobId, collectionId]) => {
      try {
        const run = await getImageRescanRun(collectionId);
        if (run !== null && run.jobId === jobId) next.set(jobId, run);
      } catch {
        // The row still shows its rescans without the run's counts.
      }
    }));
    runs = next;
  }

  async function stopImages(collectionId: string, jobId: string): Promise<void> {
    if (stopping.has(jobId)) return;
    stopping = new Set(stopping).add(jobId);
    try {
      await cancelImageRescan(collectionId);
      error = null;
      void refresh();
    } catch (failure) {
      error = failure instanceof Error ? failure.message : 'Something went wrong.';
    } finally {
      const next = new Set(stopping);
      next.delete(jobId);
      stopping = next;
    }
  }

  function imagesStatus(entry: Entry & { kind: 'images' }, known: Map<string, ImageRescanRun>): string {
    const run = known.get(entry.jobId);
    if (run === undefined) return `${entry.children.length} ${entry.children.length === 1 ? 'document' : 'documents'} scanning or waiting`;
    const finished = run.done + run.failed + run.cancelled;
    const total = run.examined < run.documents ? run.documents : run.started;
    const parts = [`${finished} of ${total} ${total === 1 ? 'document' : 'documents'} done`];
    if (run.scanning > 0) parts.push(`${run.scanning} scanning`);
    if (run.waiting > 0) parts.push(`${run.waiting} waiting`);
    if (run.currentDocument) parts.push(run.currentDocument);
    return parts.join(' · ');
  }
  let loaded = false;
  let error: string | null = null;
  /** Jobs whose stop request is on its way; the server's `cancelRequested` takes over once it answers. */
  let stopping = new Set<string>();
  let timer: ReturnType<typeof setTimeout> | null = null;
  let generation = 0;
  let destroyed = false;

  $: updateVisibility(visible);

  function updateVisibility(next: boolean): void {
    if (next) void refresh();
    else clearTimer();
  }

  function clearTimer(): void {
    if (timer !== null) clearTimeout(timer);
    timer = null;
  }

  /** One read of the active jobs; a response for anything but the newest read is dropped. */
  async function refresh(): Promise<void> {
    clearTimer();
    const current = ++generation;
    try {
      const next = await listActiveJobs();
      if (current !== generation || destroyed) return;
      jobs = next;
      error = null;
      void loadRuns(next);
    } catch (failure) {
      if (current !== generation || destroyed) return;
      error = failure instanceof Error ? failure.message : 'Something went wrong.';
    } finally {
      if (current === generation && !destroyed) {
        loaded = true;
        if (visible) timer = setTimeout(() => void refresh(), REFRESH_MILLIS);
      }
    }
  }

  /** Asks the server to stop one job at its next boundary; asking again is harmless. */
  async function stop(job: JobApiView): Promise<void> {
    if (stopping.has(job.id) || job.cancelRequested) return;
    stopping = new Set(stopping).add(job.id);
    try {
      const updated = await cancelJob(job.id);
      jobs = jobs.map((candidate) => (candidate.id === updated.id ? updated : candidate));
      error = null;
    } catch (failure) {
      error = failure instanceof Error ? failure.message : 'Something went wrong.';
    } finally {
      const next = new Set(stopping);
      next.delete(job.id);
      stopping = next;
    }
  }

  function collectionName(job: JobApiView): string {
    if (!job.collectionId) return '';
    return collections.find((collection) => collection.id === job.collectionId)?.name ?? '';
  }

  /** `Extracting · report.pdf`, `Running` or `Queued`: the stage and file when the job reports them. */
  function statusText(job: JobApiView): string {
    if (job.state === 'QUEUED') return 'Queued';
    const parts = [stageLabel(stageForReader(job.state, job.stage)), job.currentItem ?? ''].filter((part) => part !== '');
    return parts.length === 0 ? 'Running' : parts.join(' · ');
  }

  onDestroy(() => {
    destroyed = true;
    clearTimer();
  });
</script>

<section class="jobs-panel" aria-label="Running jobs">
  <h2 class="visually-hidden">Running jobs</h2>
  {#if error !== null}<p role="alert">{error}</p>{/if}
  {#if loaded && jobs.length === 0 && error === null}
    <p role="status">No jobs are running.</p>
  {:else if jobs.length > 0}
    <ul>
      {#each entries as entry (entry.kind === 'job' ? entry.job.id : `images-${entry.jobId}`)}
        {#if entry.kind === 'images'}
        <li>
          <div class="head">
            <strong>{TYPE_LABELS.IMAGE_RESCAN}</strong>
            {#if collections.find((collection) => collection.id === entry.collectionId)}<span class="collection">{collections.find((collection) => collection.id === entry.collectionId)?.name}</span>{/if}
            <button type="button" onclick={() => stopImages(entry.collectionId, entry.jobId)} disabled={stopping.has(entry.jobId) || runs.get(entry.jobId)?.cancelRequested === true}>
              {stopping.has(entry.jobId) || runs.get(entry.jobId)?.cancelRequested ? 'Stopping…' : 'Stop'}
            </button>
          </div>
          <p class="status">{imagesStatus(entry, runs)}</p>
          {#if runs.get(entry.jobId)}
            {@const run = runs.get(entry.jobId)!}
            {@const total = run.examined < run.documents ? run.documents : run.started}
            {#if total > 0}
              <div class="files">
                <progress value={run.done + run.failed + run.cancelled} max={total} aria-label="Image progress"></progress>
                <span>{run.done + run.failed + run.cancelled} of {total}</span>
              </div>
            {/if}
          {/if}
        </li>
        {:else}
        {@const job = entry.job}
        <li>
          <div class="head">
            <strong>{TYPE_LABELS[job.type] ?? job.type}</strong>
            {#if collectionName(job) !== ''}<span class="collection">{collectionName(job)}</span>{/if}
            {#if job.startedBy}<span class="collection">started by {job.startedBy.displayName}</span>{/if}
            <button type="button" onclick={() => stop(job)} disabled={stopping.has(job.id) || job.cancelRequested}>
              {stopping.has(job.id) || job.cancelRequested ? 'Stopping…' : 'Stop'}
            </button>
          </div>
          <p class="status">{statusText(job)}{#if job.cancelledBy}, stop asked by {job.cancelledBy.displayName}{/if}</p>
          {#if job.total > 0}
            <div class="files">
              <progress value={job.completed} max={job.total} aria-label="Job progress"></progress>
              <span>{job.completed} of {job.total}</span>
            </div>
          {/if}
          <ImportUnitProgress progress={job.currentProgress} />
        </li>
        {/if}
      {/each}
    </ul>
  {/if}
</section>

<style>
  .jobs-panel { max-width: 52rem; display: grid; gap: 1rem; align-content: start; }
  .jobs-panel > p { margin: 0; }
  ul { margin: 0; padding: 0; list-style: none; border-top: 1px solid #2b3030; }
  li { display: grid; gap: 0.45rem; padding: 0.8rem 0.1rem; border-bottom: 1px solid #2b3030; }
  .head { display: flex; align-items: center; gap: 0.6rem; }
  .head strong { color: #f3e6d1; font-weight: 650; }
  .collection { color: #b8bcbb; font-size: 0.85rem; }
  .head button { margin-left: auto; }
  .status { margin: 0; color: #d8c9a8; font-size: 0.85rem; overflow-wrap: anywhere; }
  .files { display: flex; align-items: center; gap: 0.6rem; font-size: 0.82rem; color: #929997; }
  progress { flex: 1; width: 100%; height: 0.6rem; accent-color: #c4a77d; }
  .visually-hidden { position: absolute; width: 1px; height: 1px; padding: 0; margin: -1px; overflow: hidden; clip: rect(0,0,0,0); white-space: nowrap; border: 0; }
</style>
