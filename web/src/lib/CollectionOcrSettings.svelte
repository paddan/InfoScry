<script lang="ts">
  import { onDestroy, onMount } from 'svelte';
  import {
    copyLlmProfileToOcr,
    listOcrLlmCandidates,
    listOcrProfiles,
    updateCollectionOcrSettings,
    type Collection,
    type OcrEndpointScope,
    type OcrEngine,
    type OcrImportMode,
    type OcrLlmCandidate,
    type OcrProfile,
  } from './api';
  import {
    ENGINE_LABELS,
    IMPORT_MODE_LABELS,
    llmCandidateLabel,
    ocrProfileLabel,
  } from './ocrRescan';

  /** The collection whose stored OCR settings the draft starts from. */
  export let collection: Collection;
  /** Tells the page that settings were written, so every list that shows them is read again. */
  export let onChanged: () => Promise<void> | void;
  /** Opens Admin → OCR profiles, where a profile is created when none can be chosen yet. */
  export let onOpenOcrProfiles: (() => void) | undefined = undefined;

  /** A select value that names an LLM profile; any other non-empty value is an OCR profile id. */
  const LLM_PREFIX = 'llm:';

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
  let candidates: OcrLlmCandidate[] = [];
  let candidatesLoaded = false;
  let candidatesError: string | null = null;
  /** Whether a stored selection that is the copy of an LLM profile was already shown as that profile. */
  let remapped = false;

  // A different collection starts a fresh draft from that collection's own stored values. A server that
  // predates these settings sends none of them, and the defaults are exactly what such a collection did.
  $: if (collection.id !== boundId) start(collection);
  // An LLM profile whose model the catalog states is text-only is not offered; one it does not state is, labelled.
  $: llmOffered = candidates.filter((candidate) => candidate.imageInput !== false);
  $: textOnlyCount = candidates.length - llmOffered.length;
  // The OCR profile a chosen LLM profile was copied into is that LLM profile, not a second choice.
  $: copyIds = new Map(
    llmOffered.flatMap((candidate) =>
      profiles
        .filter((profile) => profile.sourceLlmProfileId === candidate.id)
        .map((profile) => [profile.id, candidate.id] as const)),
  );
  // The remap comes before the missing checks: they read the selection the remap may change, and a check that ran
  // first would keep a copy's raw id, which no list offers any more, as "no longer available".
  $: if (profilesLoaded && candidatesLoaded && !remapped) remapStoredSelections();
  $: selectable = profiles.filter((profile) => profile.enabled && !copyIds.has(profile.id));
  $: nothingToChoose = profilesLoaded && candidatesLoaded && selectable.length === 0 && llmOffered.length === 0;
  // The transcription profile is used only by the LLM engine, so it counts only then; the review profile always counts.
  $: externalSelected =
    (engine === 'LLM' && scopeOf(transcriptionProfileId, profiles, candidates) === 'EXTERNAL') ||
    scopeOf(reviewProfileId, profiles, candidates) === 'EXTERNAL';
  $: allowanceIsZero = /^\d+$/.test(allowanceText.trim()) && Number(allowanceText.trim()) < 1;
  $: showAllowanceHint = externalSelected && allowanceIsZero;

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
    remapped = false;
  }

  /** Shows a stored copy of an LLM profile as the LLM profile it came from, once both lists are known. */
  function remapStored(id: string): string {
    const llmId = copyIds.get(id);
    return llmId === undefined ? id : `${LLM_PREFIX}${llmId}`;
  }

  function remapStoredSelections(): void {
    remapped = true;
    transcriptionProfileId = remapStored(transcriptionProfileId);
    reviewProfileId = remapStored(reviewProfileId);
  }

  /**
   * Where the selected profile sends page images, from the lists already loaded. Null when nothing is selected,
   * or when the selection is no longer available (it has no scope to show).
   */
  function scopeOf(id: string, available: OcrProfile[], llm: OcrLlmCandidate[]): OcrEndpointScope | null {
    if (id === '') return null;
    if (id.startsWith(LLM_PREFIX)) {
      return llm.find((candidate) => `${LLM_PREFIX}${candidate.id}` === id)?.scope ?? null;
    }
    return available.find((profile) => profile.id === id)?.scope ?? null;
  }

  /** A stored selection that can no longer be chosen stays visible rather than being silently dropped. */
  function missingProfile(id: string, available: OcrProfile[], llm: OcrLlmCandidate[]): boolean {
    if (id === '') return false;
    if (id.startsWith(LLM_PREFIX)) return !llm.some((candidate) => `${LLM_PREFIX}${candidate.id}` === id);
    return !available.some((profile) => profile.id === id);
  }

  onMount(async () => {
    // The two lists are independent: an older server without the LLM list must not hide the OCR profiles.
    const [ocr, llm] = await Promise.allSettled([listOcrProfiles(), listOcrLlmCandidates()]);
    if (!alive) return;
    if (ocr.status === 'fulfilled') {
      profiles = ocr.value ?? [];
      profilesLoaded = true;
    } else {
      profilesError = describe(ocr.reason);
    }
    if (llm.status === 'fulfilled') {
      candidates = llm.value ?? [];
      candidatesLoaded = true;
    } else {
      candidatesError = describe(llm.reason);
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
      // An LLM profile is chosen by copying it into an OCR profile, which is what the collection stores and an
      // attempt pins. The copy happens before anything is saved, so a refusal (a text-only model) saves nothing.
      const copies = new Map<string, string>();
      const resolve = async (value: string): Promise<string> => {
        if (!value.startsWith(LLM_PREFIX)) return value;
        const known = copies.get(value);
        if (known !== undefined) return known;
        const copy = await copyLlmProfileToOcr(value.slice(LLM_PREFIX.length));
        copies.set(value, copy.id);
        return copy.id;
      };
      // The server refuses a transcription profile beside a local engine, so a local engine clears it.
      const transcription = engine === 'LLM' ? await resolve(transcriptionProfileId) : '';
      const review = await resolve(reviewProfileId);
      await updateCollectionOcrSettings(target, {
        ocrEngine: engine,
        ocrImportMode: importMode,
        ocrTranscriptionProfileId: transcription,
        ocrReviewProfileId: review,
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
      {#if missingProfile(transcriptionProfileId, selectable, llmOffered)}
        <option value={transcriptionProfileId}>
          {profilesLoaded ? 'Selected profile (no longer available)' : 'Selected profile'}
        </option>
      {/if}
      {#if selectable.length > 0}
        <optgroup label="OCR profiles">
          {#each selectable as profile (profile.id)}
            <option value={profile.id}>{ocrProfileLabel(profile)}</option>
          {/each}
        </optgroup>
      {/if}
      {#if llmOffered.length > 0}
        <optgroup label="LLM profiles with image input">
          {#each llmOffered as candidate (candidate.id)}
            <option value={`${LLM_PREFIX}${candidate.id}`}>{llmCandidateLabel(candidate)}</option>
          {/each}
        </optgroup>
      {/if}
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
      {#if missingProfile(reviewProfileId, selectable, llmOffered)}
        <option value={reviewProfileId}>
          {profilesLoaded ? 'Selected profile (no longer available)' : 'Selected profile'}
        </option>
      {/if}
      {#if selectable.length > 0}
        <optgroup label="OCR profiles">
          {#each selectable as profile (profile.id)}
            <option value={profile.id}>{ocrProfileLabel(profile)}</option>
          {/each}
        </optgroup>
      {/if}
      {#if llmOffered.length > 0}
        <optgroup label="LLM profiles with image input">
          {#each llmOffered as candidate (candidate.id)}
            <option value={`${LLM_PREFIX}${candidate.id}`}>{llmCandidateLabel(candidate)}</option>
          {/each}
        </optgroup>
      {/if}
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
      aria-describedby={showAllowanceHint
        ? 'collection-ocr-allowance-note collection-ocr-allowance-warning'
        : 'collection-ocr-allowance-note'}
    />
    <p class="hint" id="collection-ocr-allowance-note">
      Pages that may be sent to an external provider per import or scan, counted once each. 0 means every external page needs your approval.
    </p>
    {#if showAllowanceHint}
      <p class="hint" id="collection-ocr-allowance-warning">
        This collection sends pages to an external provider, but the allowance is 0, so imports and scans will pause
        until you approve pages or raise the allowance.
      </p>
    {/if}
  </div>
  <div class="actions">
    <button type="submit" class="primary" disabled={saving}>
      {saving ? 'Saving OCR engine settings…' : 'Save OCR engine settings'}
    </button>
  </div>
  {#if profilesError !== null}<p role="alert">{profilesError}</p>{/if}
  {#if candidatesError !== null}<p role="alert">{candidatesError}</p>{/if}
  {#if nothingToChoose}
    <p class="hint" id="collection-ocr-empty-note">
      No OCR profile and no LLM profile with image input exists yet, so there is nothing to choose for
      transcription or review.
      {#if onOpenOcrProfiles !== undefined}
        <button type="button" class="link" onclick={() => onOpenOcrProfiles?.()}>Open Admin → OCR profiles</button>
      {:else}
        Create one in Admin → OCR profiles.
      {/if}
    </p>
  {/if}
  {#if textOnlyCount > 0}
    <p class="hint">
      {textOnlyCount === 1 ? '1 LLM profile is' : `${textOnlyCount} LLM profiles are`} not offered because the
      catalog states its model does not accept image input.
    </p>
  {/if}
  {#if error !== null}<p role="alert">{error}</p>{/if}
  {#if saved}<p role="status">OCR engine settings saved.</p>{/if}
  <p class="hint" id="collection-ocr-profile-note">
    The transcription profile is used only by the image model engine. A review profile checks a new reading
    against the existing text. Choosing an LLM profile copies its current settings into an OCR profile when you
    save; editing the LLM profile later does not change a scan that was already started. An external profile sends page images off this Mac. The environment variable that
    holds a key is shown by presence only; its value is never displayed.
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
  button.link { border: 0; background: none; padding: 0; color: #c4a77d; text-decoration: underline; cursor: pointer; font: inherit; }
  button.primary { border-color: #c4a77d; background: #c4a77d; color: #1c1b18; font-weight: 650; }
  button.primary:hover:not(:disabled) { background: #d4ba94; }
</style>
