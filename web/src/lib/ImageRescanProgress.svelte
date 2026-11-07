<script lang="ts">
  import { onDestroy } from 'svelte';
  import { ApiError, cancelImageRescan, getImageRescanRun, type ImageRescanRun } from './api';

  export let collectionId: string;
  /** The run as last read; this component keeps following it while it is active. */
  export let run: ImageRescanRun;
  /** How often an active run is read again. */
  export let pollMillis = 1500;

  let stopping = false;
  let error: string | null = null;
  let timer: ReturnType<typeof setTimeout> | null = null;
  let destroyed = false;
  let following: string | null = null;

  $: finished = run.done + run.failed + run.cancelled;
  // While documents are still being looked at, the run's size is every document; after that, the ones it started.
  $: total = run.examined < run.documents ? run.documents : run.started;
  $: follow(run);
  $: status = statusOf(run);

  function plural(n: number, one: string, many: string): string {
    return `${n} ${n === 1 ? one : many}`;
  }

  const FAILURES: Record<string, string> = {
    RESCAN_MANAGED_COPY_MISSING:
      "the server could not find the documents' stored copies. If the library was moved, start the server with INFOSCRY_LIBRARY_DIR pointing at it; otherwise add the files again.",
    RESCAN_MANAGED_COPY_CHANGED: "the documents' stored copies are not the files this archive recorded.",
    RESCAN_EMBEDDING_UNAVAILABLE: 'the embedding model is not installed, so no new version could be published.',
    PAGE_RASTER_UNBOUNDED: 'the pictures are larger than the server reads.',
  };

  $: failed = !run.active && run.started === 0 && !!run.errorCode;

  function statusOf(current: ImageRescanRun): string {
    if (!current.active && current.started === 0 && current.errorCode) {
      return `Finding images failed: ${FAILURES[current.errorCode] ?? `no document could be scanned for images (${current.errorCode}).`}`;
    }
    if (current.active) {
      const parts = [`Finding images: ${finished} of ${plural(total, 'document', 'documents')} done`];
      if (current.scanning > 0) parts.push(`${current.scanning} scanning`);
      if (current.waiting > 0) parts.push(`${current.waiting} waiting`);
      if (current.examined < current.documents) parts.push(`starting ${current.examined} of ${current.documents}`);
      return parts.join(' · ');
    }
    const parts = [`${plural(current.done, 'document', 'documents')} done`];
    if (current.failed > 0) parts.push(`${current.failed} failed`);
    if (current.cancelled > 0) parts.push(`${current.cancelled} stopped`);
    return `Finding images finished: ${parts.join(', ')}.`;
  }

  function follow(current: ImageRescanRun): void {
    if (!current.active) {
      clear();
      return;
    }
    if (following === current.jobId && timer !== null) return;
    following = current.jobId;
    schedule();
  }

  function schedule(): void {
    clear();
    if (destroyed) return;
    timer = setTimeout(async () => {
      timer = null;
      try {
        const next = await getImageRescanRun(collectionId);
        if (!destroyed && next !== null && next.jobId === run.jobId) run = next;
        error = null;
      } catch {
        // A missed read is tried again; the run itself is on the server.
      }
      if (!destroyed && run.active) schedule();
    }, pollMillis);
  }

  function clear(): void {
    if (timer !== null) clearTimeout(timer);
    timer = null;
  }

  async function stop(): Promise<void> {
    if (stopping || run.cancelRequested) return;
    stopping = true;
    error = null;
    try {
      const next = await cancelImageRescan(collectionId);
      if (next !== null) run = next;
    } catch (failure) {
      error = failure instanceof ApiError ? failure.message : failure instanceof Error ? failure.message : 'Something went wrong.';
    } finally {
      stopping = false;
    }
  }

  onDestroy(() => {
    destroyed = true;
    clear();
  });
</script>

<div class="progress">
  {#if run.active}
    {#if total > 0}
      <progress value={finished} max={total} aria-label="Image progress"></progress>
    {:else}
      <progress aria-label="Image progress"></progress>
    {/if}
  {/if}
  <p role={failed ? 'alert' : 'status'}>{status}</p>
  {#if run.active && run.currentDocument}<p class="current">Scanning {run.currentDocument}</p>{/if}
  {#if error !== null}<p role="alert">{error}</p>{/if}
  {#if run.active}
    <div>
      <button type="button" onclick={stop} disabled={stopping || run.cancelRequested}>
        {stopping || run.cancelRequested ? 'Stopping…' : 'Stop finding images'}
      </button>
    </div>
  {/if}
</div>

<style>
  .progress { display: grid; gap: 0.45rem; }
  p { margin: 0; }
  .current { color: #929997; font-size: 0.82rem; overflow-wrap: anywhere; }
  progress { width: 100%; height: 0.6rem; accent-color: #c4a77d; }
</style>
