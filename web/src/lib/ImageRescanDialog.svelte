<script lang="ts">
  import { onMount, tick } from 'svelte';
  import {
    ApiError,
    getImageRescanCensus,
    listOcrProfiles,
    startImageRescan,
    type ImageAnalysisChoice,
    type ImageExtraction,
    type ImageRescanCensus,
    type JobApiView,
    type OcrProfile,
  } from './api';
  import { containDialogFocus, holdDialogFocus } from './dialogFocus';

  export let collectionId: string;
  export let collectionName: string;
  /** The collection's default, pre-filling the choice; Off falls back to embedded images. */
  export let defaultImageExtraction: ImageExtraction = 'off';
  /** The collection's default for *Describe images with*; it only pre-fills the choice. */
  export let defaultImageProfileId: string | null = null;
  /** The collection's default for identifying from appearance; it only pre-fills the choice. */
  export let defaultIdentifyAppearance = false;
  export let onClose: () => void;
  export let onStarted: (job: JobApiView) => void;

  const SKIP_REASONS: Record<string, string> = {
    UNSUPPORTED_FORMAT: 'Office file or e-book (their images come with a new import)',
    NOT_READ: 'not read yet',
    DELETING: 'being deleted',
  };

  let mode: Exclude<ImageExtraction, 'off'> = defaultImageExtraction === 'all' ? 'all' : 'embedded';
  let imageProfiles: OcrProfile[] = [];
  let imageProfileId = defaultImageProfileId ?? '';
  let identifyAppearance = defaultIdentifyAppearance;
  let census: ImageRescanCensus | null = null;
  let loadError: string | null = null;
  let error: string | null = null;
  let starting = false;
  let dialog: HTMLElement | null = null;
  let select: HTMLSelectElement | null = null;

  $: running = census?.job != null && (census.job.state === 'QUEUED' || census.job.state === 'RUNNING');
  $: canStart = census !== null && census.eligible > 0 && !starting;
  $: imageAnalysis = imageProfileId === '' || imageProfileId === 'none'
    ? undefined
    : ({ profileId: imageProfileId, identifyAppearance } satisfies ImageAnalysisChoice);

  onMount(() => {
    void tick().then(() => select?.focus());
    void load();
  });

  async function load(): Promise<void> {
    loadError = null;
    try {
      const [loadedCensus, profiles] = await Promise.all([
        getImageRescanCensus(collectionId),
        listOcrProfiles().catch(() => [] as OcrProfile[]),
      ]);
      imageProfiles = (profiles ?? []).filter((entry) => entry.enabled && entry.imageCapabilityMeasured === true);
      if (imageProfileId !== '' && !imageProfiles.some((entry) => entry.id === imageProfileId)) {
        imageProfileId = '';
      }
      census = loadedCensus;
    } catch (failure) {
      loadError = describe(failure);
    }
  }

  function count(n: number, one: string, many: string): string {
    return `${n.toLocaleString('en-US')} ${n === 1 ? one : many}`;
  }

  async function start(event: SubmitEvent): Promise<void> {
    event.preventDefault();
    if (!canStart) return;
    starting = true;
    error = null;
    holdDialogFocus(dialog);
    try {
      const job = imageAnalysis === undefined
        ? await startImageRescan(collectionId, mode)
        : await startImageRescan(collectionId, mode, imageAnalysis);
      onStarted(job);
    } catch (failure) {
      error = describe(failure);
      starting = false;
    }
  }

  function describe(failure: unknown): string {
    if (failure instanceof ApiError) return failure.message;
    return failure instanceof Error ? failure.message : 'Something went wrong.';
  }

  function handleKeydown(event: KeyboardEvent): void {
    if (event.key === 'Escape') {
      event.preventDefault();
      if (!starting) onClose();
    } else if (event.key === 'Tab') {
      containDialogFocus(event, dialog);
    }
  }
</script>

<div class="confirm-backdrop" aria-hidden="true" onclick={() => !starting && onClose()}></div>
<div
  class="confirm-dialog"
  role="dialog"
  aria-modal="true"
  aria-labelledby="image-rescan-heading"
  bind:this={dialog}
  tabindex="-1"
  onkeydown={handleKeydown}
>
  <form class="confirm-form" onsubmit={start}>
    <h2 id="image-rescan-heading">Find images in all documents</h2>
    <p class="hint">
      Every document in {collectionName} that can be scanned again keeps its current text; only its images are
      found again. No page is read and nothing is sent anywhere. Each document is scanned as its own rescan.
    </p>
    <div class="field">
      <label for="image-rescan-mode">Extract images</label>
      <select id="image-rescan-mode" bind:this={select} bind:value={mode} disabled={starting}>
        <option value="embedded">Embedded images</option>
        <option value="all">Embedded + regions from scanned pages</option>
      </select>
    </div>
    <div class="field">
      <label for="image-rescan-profile">Describe images with</label>
      <select
        id="image-rescan-profile"
        data-testid="describe-images"
        bind:value={imageProfileId}
        onchange={() => { if (imageProfileId === '') identifyAppearance = false; }}
        disabled={starting}
      >
        <option value="">None</option>
        {#each imageProfiles as profile (profile.id)}
          <option value={profile.id}>{profile.name}</option>
        {/each}
      </select>
    </div>
    {#if imageProfileId !== ''}
      <label class="check-label">
        <input
          type="checkbox"
          data-testid="identify-appearance"
          bind:checked={identifyAppearance}
          disabled={starting}
        />
        Identify specific people, objects, animals and places from their appearance
      </label>
    {/if}
    {#if loadError !== null}
      <p role="alert">{loadError}</p>
    {:else if census === null}
      <p role="status">Counting documents…</p>
    {:else}
      <div role="status">
        <p>{count(census.eligible, 'document', 'documents')} will be scanned for images.</p>
        {#if Object.keys(census.skipped).length > 0}
          <ul>
            {#each Object.entries(census.skipped) as [reason, n] (reason)}
              <li>{count(n, 'document', 'documents')} left out: {SKIP_REASONS[reason] ?? reason}</li>
            {/each}
          </ul>
        {/if}
        {#if running}
          <p class="hint">Images are already being found in this collection; starting shows that run.</p>
        {/if}
      </div>
    {/if}
    {#if error !== null}<p role="alert">{error}</p>{/if}
    <div class="actions end">
      <button type="submit" class="primary" disabled={!canStart}>{starting ? 'Starting…' : 'Start'}</button>
      <button type="button" class="cancel" onclick={onClose} disabled={starting}>Cancel</button>
    </div>
  </form>
</div>

<style>
  .confirm-backdrop { position: fixed; inset: 0; background: rgba(8, 9, 10, 0.62); }
  .confirm-dialog { position: fixed; top: 50%; left: 50%; z-index: 2; transform: translate(-50%, -50%); width: min(36rem, calc(100vw - 2rem)); max-height: calc(100vh - 2rem); overflow: auto; border: 1px solid #3a3e3e; border-radius: 0.6rem; background: #1d2021; padding: 1.15rem; }
  .confirm-form { display: grid; gap: 0.7rem; }
  h2 { margin: 0; font-size: 1.05rem; }
  p { margin: 0; }
  ul { display: grid; gap: 0.3rem; margin: 0.4rem 0 0; padding-left: 1.1rem; font-size: 0.85rem; }
  .field { display: grid; gap: 0.35rem; }
  label { color: #b8bcbb; font-size: 0.82rem; }
  .check-label { display: flex; align-items: center; gap: 0.5rem; color: #e8e9e7; font-size: 0.85rem; }
  .check-label input { accent-color: #c4a77d; }
  select { border: 1px solid #383d3e; border-radius: 0.48rem; background: #202324; padding: 0.55rem 0.65rem; color: #e8e9e7; font: inherit; }
  .hint { color: #929997; font-size: 0.82rem; }
  .actions { display: flex; flex-wrap: wrap; align-items: center; gap: 0.55rem; }
  .actions.end { justify-content: end; }
  button.primary { border-color: #c4a77d; background: #c4a77d; color: #1c1b18; font-weight: 650; }
  button.primary:hover:not(:disabled) { background: #d4ba94; }
</style>
