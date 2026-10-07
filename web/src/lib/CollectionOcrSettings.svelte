<script lang="ts">
  import { onDestroy, onMount } from 'svelte';
  import {
    listOcrProfiles,
    updateCollectionOcrSettings,
    type Collection,
    type OcrEngine,
    type OcrImportMode,
    type OcrProfile,
  } from './api';
  import { ENGINE_LABELS, IMPORT_MODE_LABELS, ocrProfileLabel } from './ocrRescan';

  /** The collection whose stored OCR settings the draft starts from. */
  export let collection: Collection;
  /** Tells the page that settings were written, so every list that shows them is read again. */
  export let onChanged: () => Promise<void> | void;

  const ENGINES = Object.keys(ENGINE_LABELS) as OcrEngine[];
  const MODES = Object.keys(IMPORT_MODE_LABELS) as OcrImportMode[];

  let boundId: string | null = null;
  let engine: OcrEngine = 'TESSERACT';
  let importMode: OcrImportMode = 'FILL_MISSING';
  let transcriptionProfileId = '';
  let reviewProfileId = '';
  let allowanceText = '0';
  let saving = false;
  let error: string | null = null;
  let saved = false;
  /**
   * The draft's generation. A save reads across an await and the person can select another collection
   * meanwhile, so the generation captured when a save starts decides whether its answer may still write
   * into the fields and the status beside them.
   */
  let generation = 0;
  let alive = true;

  let profiles: OcrProfile[] = [];
  let profilesLoaded = false;
  let profilesError: string | null = null;

  // A different collection starts a fresh draft from that collection's own stored values. A server that
  // predates these settings sends none of them, and the defaults are exactly what such a collection did.
  $: if (collection.id !== boundId) start(collection);
  $: selectable = profiles.filter((profile) => profile.enabled);
  $: transcriptionMissing = missingProfile(transcriptionProfileId, selectable);
  $: reviewMissing = missingProfile(reviewProfileId, selectable);

  function start(next: Collection): void {
    boundId = next.id;
    generation += 1;
    engine = next.ocrEngine ?? 'TESSERACT';
    importMode = next.ocrImportMode ?? 'FILL_MISSING';
    transcriptionProfileId = next.ocrTranscriptionProfileId ?? '';
    reviewProfileId = next.ocrReviewProfileId ?? '';
    allowanceText = String(next.ocrExternalPageLimit ?? 0);
    saving = false;
    error = null;
    saved = false;
  }

  /** A stored selection that can no longer be chosen stays visible rather than being silently dropped. */
  function missingProfile(id: string, available: OcrProfile[]): boolean {
    return id !== '' && !available.some((profile) => profile.id === id);
  }

  onMount(async () => {
    try {
      const listed = await listOcrProfiles();
      if (!alive) return;
      profiles = listed ?? [];
      profilesLoaded = true;
    } catch (failure) {
      if (!alive) return;
      profilesError = describe(failure);
    }
  });

  onDestroy(() => {
    alive = false;
    generation += 1;
  });

  function describe(failure: unknown): string {
    return failure instanceof Error ? failure.message : 'Something went wrong.';
  }

  function touched(): void {
    saved = false;
  }

  async function save(event: SubmitEvent): Promise<void> {
    event.preventDefault();
    if (saving) return;
    const text = allowanceText.trim();
    if (!/^\d+$/.test(text)) {
      error = 'The external page allowance must be a whole number, zero or greater.';
      saved = false;
      return;
    }
    const target = collection.id;
    const mine = generation;
    saving = true;
    error = null;
    saved = false;
    try {
      await updateCollectionOcrSettings(target, {
        ocrEngine: engine,
        ocrImportMode: importMode,
        // The server refuses a transcription profile beside a local engine, so a local engine clears it.
        ocrTranscriptionProfileId: engine === 'LLM' ? transcriptionProfileId : '',
        ocrReviewProfileId: reviewProfileId,
        ocrExternalPageLimit: Number(text),
      });
      // The settings are written whatever the person is looking at now, so the page reads its lists again;
      // but the draft on screen belongs to another collection by now and takes nothing from this answer.
      await onChanged();
      if (mine !== generation) return;
      saved = true;
    } catch (failure) {
      if (mine !== generation) return;
      error = describe(failure);
    } finally {
      if (mine === generation) saving = false;
    }
  }
</script>

<form class="settings-form" onsubmit={save} aria-label="OCR engine settings">
  <div class="field">
    <label for="collection-ocr-engine">OCR engine</label>
    <select id="collection-ocr-engine" bind:value={engine} onchange={touched}>
      {#each ENGINES as value (value)}
        <option {value}>{ENGINE_LABELS[value]}</option>
      {/each}
    </select>
  </div>
  <div class="field">
    <label for="collection-ocr-mode">Import mode</label>
    <select id="collection-ocr-mode" bind:value={importMode} onchange={touched}>
      {#each MODES as value (value)}
        <option {value}>{IMPORT_MODE_LABELS[value]}</option>
      {/each}
    </select>
  </div>
  <div class="field">
    <label for="collection-ocr-transcription">Transcription profile</label>
    <select
      id="collection-ocr-transcription"
      bind:value={transcriptionProfileId}
      onchange={touched}
      disabled={engine !== 'LLM'}
      aria-describedby="collection-ocr-profile-note"
    >
      <option value="">None</option>
      {#if transcriptionMissing}
        <option value={transcriptionProfileId}>
          {profilesLoaded ? 'Selected profile (no longer available)' : 'Selected profile'}
        </option>
      {/if}
      {#each selectable as profile (profile.id)}
        <option value={profile.id}>{ocrProfileLabel(profile)}</option>
      {/each}
    </select>
  </div>
  <div class="field">
    <label for="collection-ocr-review">Review profile</label>
    <select
      id="collection-ocr-review"
      bind:value={reviewProfileId}
      onchange={touched}
      aria-describedby="collection-ocr-profile-note"
    >
      <option value="">None</option>
      {#if reviewMissing}
        <option value={reviewProfileId}>
          {profilesLoaded ? 'Selected profile (no longer available)' : 'Selected profile'}
        </option>
      {/if}
      {#each selectable as profile (profile.id)}
        <option value={profile.id}>{ocrProfileLabel(profile)}</option>
      {/each}
    </select>
  </div>
  <div class="field">
    <label for="collection-ocr-allowance">External page allowance</label>
    <input
      id="collection-ocr-allowance"
      inputmode="numeric"
      bind:value={allowanceText}
      oninput={touched}
      aria-invalid={error !== null}
      aria-describedby="collection-ocr-allowance-note"
    />
  </div>
  <div class="actions">
    <button type="submit" class="primary" disabled={saving}>
      {saving ? 'Saving OCR engine settings…' : 'Save OCR engine settings'}
    </button>
  </div>
  {#if profilesError !== null}<p role="alert">{profilesError}</p>{/if}
  {#if error !== null}<p role="alert">{error}</p>{/if}
  {#if saved}<p role="status">OCR engine settings saved.</p>{/if}
  <p class="hint" id="collection-ocr-profile-note">
    The transcription profile is used only by the image model engine. A review profile checks a new reading
    against the existing text. An external profile sends page images off this Mac. The environment variable that
    holds a key is shown by presence only; its value is never displayed.
  </p>
  <p class="hint" id="collection-ocr-allowance-note">
    The most distinct pages one import or scan may send to an external provider before it waits for your
    approval. Zero sends none.
  </p>
</form>

<style>
  .settings-form { display: grid; gap: 0.6rem; }
  .settings-form p { margin: 0; }
  .settings-form .actions { display: flex; gap: 0.55rem; justify-content: start; }
  .field { display: grid; gap: 0.35rem; align-content: start; }
  label { color: #b8bcbb; font-size: 0.82rem; }
  select,
  input { min-width: 0; border: 1px solid #383d3e; border-radius: 0.48rem; background: #202324; padding: 0.62rem 0.72rem; color: #e8e9e7; }
  select:disabled { opacity: 0.6; }
  .hint { color: #929997; font-size: 0.82rem; }
  button.primary { border-color: #c4a77d; background: #c4a77d; color: #1c1b18; font-weight: 650; }
  button.primary:hover:not(:disabled) { background: #d4ba94; }
</style>
