<script lang="ts">
  import { onMount, tick } from 'svelte';
  import {
    ApiError,
    createOcrProfile,
    disableOcrProfile,
    listLlmPresets,
    listOcrProfiles,
    probeOcrProfile,
    updateOcrProfile,
    type LlmPreset,
    type OcrProfile,
    type OcrProfileInput,
    type OcrProfileProbe,
  } from './api';
  import ProviderModelFields from './ProviderModelFields.svelte';

  /**
   * OCR profile management. Self-contained: it owns its list, form and probe state and talks only
   * to the profile routes, so a host can mount it anywhere. `onProfilesChanged` reports the saved
   * list after every load, save and probe so a host (for example collection controls) can stay current.
   */
  export let onProfilesChanged: ((profiles: OcrProfile[]) => void) | undefined = undefined;

  let profiles: OcrProfile[] = [];
  let presets: LlmPreset[] = [];
  let presetError: string | null = null;
  let selectedId = '';
  let draft: OcrProfileInput = emptyDraft();
  let creating = false;
  let loading = true;
  let loadError: string | null = null;
  let saving = false;
  let error: string | null = null;
  let conflict = false;
  /** The save was refused because the profile moved on since the form was loaded. */
  let stale = false;
  /** The revision the form was loaded from (or last saved as); sent with an edit so a stale one is refused. */
  let loadedRevisionId: string | null = null;
  /** The settings the form was loaded with, to show what changed elsewhere. */
  let loadedInput: OcrProfileInput | null = null;
  let flash: string | null = null;
  let probing = false;
  let probeResult: { profileId: string; probe: OcrProfileProbe } | null = null;
  let probeError: string | null = null;
  let heading: HTMLElement | undefined;
  let newButton: HTMLButtonElement | undefined;
  // Bumps whenever the selection changes so a probe that settles late cannot report on another profile.
  let selectionGeneration = 0;

  $: selected = profiles.find((profile) => profile.id === selectedId) ?? null;
  $: formVisible = creating || selected !== null;

  function emptyDraft(): OcrProfileInput {
    return {
      name: '',
      provider: 'OPENAI_COMPATIBLE',
      endpoint: '',
      model: '',
      contextWindow: 128_000,
      maxOutputTokens: 4_096,
      inputPricePerMillion: 0,
      outputPricePerMillion: 0,
      enabled: true,
      apiKeyEnvironmentVariable: null,
    };
  }

  function toInput(profile: OcrProfile): OcrProfileInput {
    return {
      name: profile.name,
      provider: profile.provider,
      endpoint: profile.endpoint,
      model: profile.model,
      contextWindow: profile.contextWindow,
      maxOutputTokens: profile.maxOutputTokens,
      inputPricePerMillion: profile.inputPricePerMillion,
      outputPricePerMillion: profile.outputPricePerMillion,
      enabled: profile.enabled,
      apiKeyEnvironmentVariable: profile.apiKeyEnvironmentVariable,
    };
  }

  function setProfiles(next: OcrProfile[]): void {
    profiles = next;
    onProfilesChanged?.(next);
  }

  function replaceProfile(updated: OcrProfile): void {
    const known = profiles.some((profile) => profile.id === updated.id);
    setProfiles(known
      ? profiles.map((profile) => (profile.id === updated.id ? updated : profile))
      : [...profiles, updated]);
  }

  function describe(failure: unknown): string {
    if (failure instanceof Error && failure.message !== '') return failure.message;
    return 'Something went wrong.';
  }

  async function load(): Promise<void> {
    loading = true;
    loadError = null;
    // Presets are a convenience: a failure to list them is a hint beside the form and must not stop the
    // profile listing, which is the part the operator actually needs.
    presetError = null;
    try {
      presets = await listLlmPresets();
    } catch (failure) {
      presetError = describe(failure);
    }
    try {
      setProfiles(await listOcrProfiles());
    } catch (failure) {
      loadError = describe(failure);
    } finally {
      loading = false;
    }
  }

  /** Refresh the saved list after a conflict without touching the form the user is editing. */
  async function reloadKeepingDraft(): Promise<void> {
    try {
      setProfiles(await listOcrProfiles());
      error = null;
      conflict = false;
      flash = 'Profiles reloaded. Your unsaved input is kept.';
    } catch (failure) {
      error = describe(failure);
    }
  }

  const FIELD_LABELS: [keyof OcrProfileInput, string][] = [
    ['name', 'name'],
    ['provider', 'provider'],
    ['endpoint', 'endpoint'],
    ['model', 'model'],
    ['contextWindow', 'context window'],
    ['maxOutputTokens', 'maximum output tokens'],
    ['inputPricePerMillion', 'input price per million'],
    ['outputPricePerMillion', 'output price per million'],
    ['enabled', 'enabled'],
    ['apiKeyEnvironmentVariable', 'key variable'],
  ];

  /** What the saved profile now says where it differs from what the form was loaded with. */
  function changedElsewhere(latest: OcrProfile): string {
    const before = loadedInput;
    const changes = FIELD_LABELS
      .filter(([field]) => before === null || before[field] !== latest[field])
      .map(([field, label]) => `${label} ${latest[field] === null || latest[field] === '' ? '(none)' : String(latest[field])}`);
    return changes.length === 0
      ? 'The saved profile has a newer revision with the same settings.'
      : `The saved profile now has ${changes.join(', ')}.`;
  }

  /**
   * After a stale save: read the saved profiles again. Without `discard` the person's draft stays and the next
   * save is made against the latest revision (an explicit choice to overwrite); with it the draft is replaced.
   */
  async function reloadLatest(discard: boolean): Promise<void> {
    const id = selectedId;
    try {
      setProfiles(await listOcrProfiles());
      const latest = profiles.find((profile) => profile.id === id);
      error = null;
      conflict = false;
      stale = false;
      if (latest === undefined) {
        flash = 'This profile is no longer in the list.';
        return;
      }
      if (discard) {
        draft = toInput(latest);
        flash = 'Your edits were discarded and the latest saved profile is loaded.';
      } else {
        flash = `${changedElsewhere(latest)} Your unsaved input is kept; saving now replaces the saved profile with it.`;
      }
      loadedRevisionId = latest.revisionId;
      loadedInput = toInput(latest);
    } catch (failure) {
      error = describe(failure);
    }
  }

  async function focusHeading(): Promise<void> {
    await tick();
    heading?.focus();
  }

  function selectProfile(profile: OcrProfile): void {
    selectedId = profile.id;
    selectionGeneration += 1;
    creating = false;
    draft = toInput(profile);
    loadedRevisionId = profile.revisionId;
    loadedInput = toInput(profile);
    error = null;
    conflict = false;
    stale = false;
    flash = null;
    probeResult = null;
    probeError = null;
    void focusHeading();
  }

  function startNew(): void {
    selectedId = '';
    selectionGeneration += 1;
    creating = true;
    draft = emptyDraft();
    loadedRevisionId = null;
    loadedInput = null;
    error = null;
    conflict = false;
    stale = false;
    flash = null;
    probeResult = null;
    probeError = null;
    void focusHeading();
  }

  async function cancel(): Promise<void> {
    creating = false;
    error = null;
    conflict = false;
    stale = false;
    await tick();
    newButton?.focus();
  }

  async function save(event: SubmitEvent): Promise<void> {
    event.preventDefault();
    if (saving) return;
    const name = draft.name.trim();
    const model = draft.model.trim();
    if (name === '' || model === '') {
      error = 'Name and model are required.';
      conflict = false;
      return;
    }
    saving = true;
    error = null;
    conflict = false;
    stale = false;
    const payload: OcrProfileInput = {
      name,
      provider: draft.provider,
      endpoint: draft.endpoint.trim(),
      model,
      contextWindow: Math.max(1, Math.round(Number(draft.contextWindow) || 0)),
      maxOutputTokens: Math.max(1, Math.round(Number(draft.maxOutputTokens) || 0)),
      inputPricePerMillion: Math.max(0, Number(draft.inputPricePerMillion) || 0),
      outputPricePerMillion: Math.max(0, Number(draft.outputPricePerMillion) || 0),
      enabled: draft.enabled,
      apiKeyEnvironmentVariable: draft.apiKeyEnvironmentVariable?.trim() || null,
    };
    try {
      const saved = creating
        ? await createOcrProfile(payload)
        : await updateOcrProfile(selectedId, payload, loadedRevisionId ?? undefined);
      replaceProfile(saved);
      selectedId = saved.id;
      creating = false;
      draft = toInput(saved);
      loadedRevisionId = saved.revisionId;
      loadedInput = toInput(saved);
      flash = `'${saved.name}' saved.`;
      await focusHeading();
    } catch (failure) {
      if (failure instanceof ApiError && failure.status === 409 && failure.code === 'STALE_OCR_PROFILE_REVISION') {
        stale = true;
        error = 'This profile was changed elsewhere, so nothing was saved. Your input is kept. Reload the latest saved profile to see what changed, or discard your edits and load it.';
      } else if (failure instanceof ApiError && failure.status === 409) {
        conflict = true;
        error = `This change conflicts with the saved profiles, so nothing was saved. Your input is kept. ${failure.message}`;
      } else {
        error = describe(failure);
      }
    } finally {
      saving = false;
    }
  }

  async function disable(): Promise<void> {
    if (selected === null || saving) return;
    const id = selected.id;
    saving = true;
    error = null;
    conflict = false;
    try {
      await disableOcrProfile(id);
      setProfiles(await listOcrProfiles());
      const current = profiles.find((profile) => profile.id === id);
      if (current && selectedId === id) {
        draft = toInput(current);
        loadedRevisionId = current.revisionId;
        loadedInput = toInput(current);
      }
      flash = 'Profile disabled. Existing jobs and history keep their recorded revisions.';
    } catch (failure) {
      error = describe(failure);
    } finally {
      saving = false;
    }
  }

  async function checkImageSupport(): Promise<void> {
    if (probing || selected === null) return;
    const id = selected.id;
    const generation = selectionGeneration;
    probing = true;
    probeResult = null;
    probeError = null;
    try {
      const probe = await probeOcrProfile(id);
      replaceProfile(probe.profile);
      if (generation === selectionGeneration) probeResult = { profileId: id, probe };
    } catch (failure) {
      if (generation === selectionGeneration) probeError = describe(failure);
    } finally {
      probing = false;
    }
  }

  function capability(profile: OcrProfile): string {
    if (profile.imageCapabilityMeasured === true) return 'Image support measured';
    if (profile.imageCapabilityMeasured === false) return 'Image support failed the check';
    return 'Image support not measured';
  }

  function keyState(profile: OcrProfile): string {
    if (profile.apiKeyEnvironmentVariable === null) return 'No key configured';
    return profile.keyAvailable
      ? `${profile.apiKeyEnvironmentVariable}: present in the server environment`
      : `${profile.apiKeyEnvironmentVariable}: missing from the server environment`;
  }

  onMount(() => {
    void load();
  });
</script>

<section class="admin-panel" aria-label="OCR profile administration">
  <p class="hint">
    OCR profiles describe image-reading models. Collections use them as reading methods.
    They are separate from the Ask and Investigate profiles. API keys stay in environment variables.
  </p>

  {#if loading}
    <p role="status">Loading OCR profiles…</p>
  {:else if loadError !== null}
    <p role="alert">{loadError}</p>
    <button type="button" onclick={load}>Try again</button>
  {:else}
    {#if flash}<p class="flash" role="status">{flash}</p>{/if}
    {#if error}
      <div role="alert" class="problem">
        <p>{error}</p>
        {#if conflict}
          <button type="button" onclick={reloadKeepingDraft}>Reload profiles</button>
        {:else if stale}
          <div class="actions">
            <button type="button" onclick={() => void reloadLatest(false)}>Reload latest</button>
            <button type="button" onclick={() => void reloadLatest(true)}>Discard my edits and load latest</button>
          </div>
        {/if}
      </div>
    {/if}

    {#if profiles.length === 0}
      <p>No OCR profiles yet.</p>
    {:else}
      <ul class="profiles" aria-label="OCR profiles">
        {#each profiles as profile (profile.id)}
          <li aria-label={profile.name} class:selected={profile.id === selectedId && !creating}>
            <div class="title">
              <strong>{profile.name}</strong>
              <button type="button" aria-label={`Edit ${profile.name}`} onclick={() => selectProfile(profile)}>Edit</button>
            </div>
            <p class="meta">
              <span>{profile.scope === 'EXTERNAL' ? 'External' : 'Local'}</span>
              <span>{profile.enabled ? 'Enabled' : 'Disabled'}</span>
              <span>{profile.model}</span>
            </p>
            <p class="meta">
              <span>{capability(profile)}</span>
              {#if profile.imageCapabilityCheckedAt}
                <span>Last checked {profile.imageCapabilityCheckedAt}</span>
              {/if}
              <span>{keyState(profile)}</span>
            </p>
          </li>
        {/each}
      </ul>
    {/if}

    <div class="toolbar">
      <button type="button" bind:this={newButton} onclick={startNew} disabled={creating}>New profile</button>
    </div>

    {#if formVisible}
      <h3 tabindex="-1" bind:this={heading}>{creating ? 'New profile' : 'Edit profile'}</h3>
      <form onsubmit={save} class="profile-form" aria-label="OCR profile">
        <div class="field">
          <label for="ocr-name">Name</label>
          <input id="ocr-name" bind:value={draft.name} placeholder="e.g. Vision reader" aria-required="true" />
        </div>
        <ProviderModelFields
          bind:draft
          idPrefix="ocr"
          {presets}
          {presetError}
          imageOnly
          modelPlaceholder="e.g. a vision-capable model"
          keyPlaceholder="e.g. OCR_API_KEY"
          keyHint="Only the variable name is stored. A key value is never shown. A loopback endpoint can omit it."
          keyState={!creating && selected !== null ? keyState(selected) : null}
        />
        <div class="field check">
          <label><input type="checkbox" bind:checked={draft.enabled} /> Enabled</label>
        </div>
        <div class="actions">
          <button type="submit" class="primary" disabled={saving}>
            {saving ? 'Saving…' : creating ? 'Create profile' : 'Save changes'}
          </button>
          {#if creating}<button type="button" onclick={cancel}>Cancel</button>{/if}
        </div>
      </form>

      {#if !creating && selected !== null}
        <div class="capability">
          <h4>Image support</h4>
          <p class="hint">
            Sends a built-in synthetic test image to the saved profile, never a document. Unsaved edits are not checked.
            It proves image transport, not transcription quality.
          </p>
          <div class="actions">
            <button type="button" onclick={checkImageSupport} disabled={probing}>
              {probing ? 'Checking…' : 'Check image support'}
            </button>
            {#if selected.enabled}
              <button type="button" class="danger" onclick={disable} disabled={saving}>Disable profile</button>
            {/if}
          </div>
          {#if probeResult !== null && probeResult.profileId === selected.id}
            <p role="status">
              {#if probeResult.probe.supported}
                Image check passed.{probeResult.probe.modelVersion ? ` Model version: ${probeResult.probe.modelVersion}.` : ''}
              {:else}
                The endpoint did not accept the image{probeResult.probe.errorCode ? ` (${probeResult.probe.errorCode})` : ''}.
              {/if}
            </p>
          {/if}
          {#if probeError}<p role="alert">{probeError}</p>{/if}
        </div>
      {/if}
    {/if}
  {/if}
</section>

<style>
  .admin-panel { max-width: 44rem; }
  .flash { color: #a9c7a6; }
  .problem p { margin: 0 0 0.5rem; }
  .profiles { list-style: none; margin: 0 0 1rem; padding: 0; display: grid; gap: 0.6rem; }
  .profiles li { border: 1px solid #383d3e; border-radius: 0.48rem; padding: 0.6rem 0.75rem; min-width: 0; }
  .profiles li.selected { border-color: #c4a77d; }
  .title { display: flex; align-items: center; justify-content: space-between; gap: 0.6rem; }
  .title strong { min-width: 0; overflow-wrap: anywhere; }
  .meta { display: flex; flex-wrap: wrap; gap: 0.25rem 0.9rem; margin: 0.3rem 0 0; color: #b8bcbb; font-size: 0.82rem; overflow-wrap: anywhere; }
  .toolbar { margin-bottom: 1rem; }
  h3 { margin: 0.5rem 0; }
  h4 { margin: 0 0 0.35rem; }
  .profile-form { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0.9rem 1rem; }
  .field { display: grid; gap: 0.35rem; align-content: start; }
  .field.check { align-content: end; }
  .field.check label { display: flex; align-items: center; gap: 0.5rem; }
  .field.check input { accent-color: #c4a77d; }
  label { color: #b8bcbb; font-size: 0.82rem; }
  input:not([type='checkbox']) {
    width: 100%;
    min-width: 0;
    border: 1px solid #383d3e;
    border-radius: 0.48rem;
    background: #202324;
    padding: 0.62rem 0.72rem;
    color: #e8e9e7;
  }
  input:hover { border-color: #515757; }
  .hint { margin: 0; color: #929997; font-size: 0.78rem; }
  .actions { grid-column: 1 / -1; display: flex; flex-wrap: wrap; gap: 0.55rem; margin-top: 0.2rem; }
  .capability { margin-top: 1.5rem; padding-top: 1.1rem; border-top: 1px solid #2d3131; display: grid; gap: 0.6rem; }
  button.primary { border-color: #c4a77d; background: #c4a77d; color: #1c1b18; font-weight: 650; }
  button.primary:hover:not(:disabled) { background: #d4ba94; }
  button.danger { border-color: #6e3a37; color: #f0a4a0; }
  button.danger:hover:not(:disabled) { background: #3a2322; }
  @media (max-width: 36rem) {
    .profile-form { grid-template-columns: minmax(0, 1fr); }
  }
</style>
