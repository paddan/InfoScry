<script lang="ts">
  import { onMount } from 'svelte';
  import {
    ApiError,
    copyLlmProfileToOcr,
    createLlmProfile,
    deleteLlmProfile,
    listLlmPresets,
    listLlmProfiles,
    listOcrProfiles,
    probeLlmProfile,
    probeOcrProfile,
    setLlmDefault,
    updateLlmProfile,
    type LlmDefaults,
    type LlmPreset,
    type LlmProfile,
    type LlmProfileInput,
    type OcrProfile,
  } from './api';
  import ProviderModelFields from './ProviderModelFields.svelte';
  import { toolCallingState } from './toolCalling';

  let profiles: LlmProfile[] = [];
  /** A tool-calling check is in flight; a second click while it runs is ignored, so one check is sent. */
  let probing = false;
  /** The OCR copies of these profiles: what a collection's reading methods and an OCR attempt actually pin. */
  let ocrCopies: OcrProfile[] = [];
  /** An image-reading check is in flight; a second click while it runs is ignored. */
  let checkingImages = false;
  let defaults: LlmDefaults = { ASK: null, INVESTIGATE: null };
  let selectedId = '';
  let draft: LlmProfileInput = emptyDraft();
  let creating = false;
  let loading = true;
  let saving = false;
  let error: string | null = null;
  let flash: string | null = null;
  let presets: LlmPreset[] = [];
  let presetError: string | null = null;

  $: selectedProfile = profiles.find((profile) => profile.id === selectedId) ?? null;
  $: ocrCopy = ocrCopies.find((copy) => copy.sourceLlmProfileId === selectedId) ?? null;
  function emptyDraft(): LlmProfileInput {
    return {
      name: '',
      provider: 'OPENAI_COMPATIBLE',
      endpoint: '',
      model: '',
      contextWindow: 128_000,
      maxOutputTokens: 4_096,
      inputPricePerMillion: 0,
      outputPricePerMillion: 0,
      cacheReadPricePerMillion: 0,
      enabled: true,
      apiKeyEnvironmentVariable: null,
    };
  }

  function toInput(profile: LlmProfile): LlmProfileInput {
    return {
      name: profile.name,
      provider: profile.provider,
      endpoint: profile.endpoint,
      model: profile.model,
      contextWindow: profile.contextWindow,
      maxOutputTokens: profile.maxOutputTokens,
      inputPricePerMillion: profile.inputPricePerMillion,
      outputPricePerMillion: profile.outputPricePerMillion,
      cacheReadPricePerMillion: profile.cacheReadPricePerMillion,
      enabled: profile.enabled,
      apiKeyEnvironmentVariable: profile.apiKeyEnvironmentVariable,
    };
  }

  async function reload(): Promise<void> {
    const data = await listLlmProfiles();
    profiles = data.profiles;
    defaults = data.defaults;
    await reloadOcrCopies();
  }

  /** The copies only decorate this panel; failing to list them must not hide the profiles themselves. */
  async function reloadOcrCopies(): Promise<void> {
    try {
      ocrCopies = await listOcrProfiles();
    } catch {
      ocrCopies = [];
    }
  }

  async function load(): Promise<void> {
    loading = true;
    error = null;
    // Presets are an optional convenience: a presets failure surfaces as a hint and must not stop the
    // profile listing, which is the part the operator actually needs.
    presetError = null;
    try {
      presets = await listLlmPresets();
    } catch (failure) {
      presetError = describe(failure);
    }
    try {
      await reload();
      if (profiles.length === 0) {
        creating = true;
        draft = emptyDraft();
      } else if (!profiles.some((profile) => profile.id === selectedId)) {
        selectedId = profiles[0].id;
        draft = toInput(profiles[0]);
      }
    } catch (failure) {
      error = describe(failure);
    } finally {
      loading = false;
    }
  }

  function selectProfile(id: string): void {
    const profile = profiles.find((candidate) => candidate.id === id);
    if (!profile) return;
    creating = false;
    draft = toInput(profile);
    error = null;
    flash = null;
  }

  function startNew(): void {
    creating = true;
    draft = emptyDraft();
    error = null;
    flash = null;
  }

  function cancel(): void {
    creating = false;
    error = null;
    if (profiles.length > 0) {
      const current = profiles.find((profile) => profile.id === selectedId) ?? profiles[0];
      selectedId = current.id;
      draft = toInput(current);
    }
  }

  async function save(event: SubmitEvent): Promise<void> {
    event.preventDefault();
    const name = draft.name.trim();
    const model = draft.model.trim();
    if (name === '' || model === '') {
      error = 'Name and model are required.';
      return;
    }
    saving = true;
    error = null;
    const payload: LlmProfileInput = {
      name,
      provider: draft.provider,
      endpoint: draft.endpoint.trim(),
      model,
      contextWindow: Math.max(1, Math.round(Number(draft.contextWindow) || 0)),
      maxOutputTokens: Math.max(1, Math.round(Number(draft.maxOutputTokens) || 0)),
      inputPricePerMillion: Math.max(0, Number(draft.inputPricePerMillion) || 0),
      outputPricePerMillion: Math.max(0, Number(draft.outputPricePerMillion) || 0),
      cacheReadPricePerMillion: Math.max(0, Number(draft.cacheReadPricePerMillion) || 0),
      enabled: draft.enabled,
      apiKeyEnvironmentVariable: draft.apiKeyEnvironmentVariable?.trim() || null,
    };
    try {
      if (creating) {
        const created = await createLlmProfile(payload);
        await reload();
        selectedId = created.id;
        draft = toInput(created);
      } else {
        const updated = await updateLlmProfile(selectedId, payload);
        // An existing OCR copy is what OCR reads through, so it follows the edit. The new revision has not
        // passed the image check, which is the safe state until the person runs it again.
        if (ocrCopy !== null) {
          try {
            await copyLlmProfileToOcr(selectedId);
          } catch {
            // A model the catalog now states is text-only keeps its old copy; the status line shows it.
          }
        }
        await reload();
        draft = toInput(updated);
      }
      creating = false;
      flash = `'${payload.name}' saved.`;
    } catch (failure) {
      error = describe(failure);
    } finally {
      saving = false;
    }
  }

  /**
   * Measures the saved profile (the draft is not sent). The server records the result, so the list is reloaded
   * and the state line shows what was stored, not what was just quoted.
   */
  async function checkToolCalling(): Promise<void> {
    if (probing || creating || selectedProfile === null) return;
    probing = true;
    error = null;
    flash = null;
    try {
      const result = await probeLlmProfile(selectedId);
      await reload();
      flash = result.toolCallingSupported
        ? 'Tool calling supported: this profile can be used for Investigate.'
        : 'Tool calling unsupported: Investigate cannot use this profile with this model.';
    } catch (failure) {
      error = describe(failure);
    } finally {
      probing = false;
    }
  }

  /**
   * Offers the saved profile for OCR and measures it with the server's synthetic image. The copy is made first
   * (or brought up to date) so the check is of the settings OCR will read through; only a passed check makes the
   * profile selectable as a reading method.
   */
  async function checkImageReading(): Promise<void> {
    if (checkingImages || creating || selectedProfile === null) return;
    checkingImages = true;
    error = null;
    flash = null;
    try {
      const copy = await copyLlmProfileToOcr(selectedId);
      const result = await probeOcrProfile(copy.id);
      await reloadOcrCopies();
      flash = result.supported
        ? 'Image reading confirmed: this profile can be chosen as a reading method for OCR.'
        : 'Image reading failed: this model did not read the test image, so OCR cannot use it.';
    } catch (failure) {
      error = describe(failure);
    } finally {
      checkingImages = false;
    }
  }

  function imageReadingText(copy: OcrProfile | null): string {
    if (copy === null || copy.imageCapabilityMeasured === null) return 'Image reading for OCR: not checked';
    if (copy.imageCapabilityMeasured) {
      return `Image reading for OCR: confirmed (measured ${copy.imageCapabilityCheckedAt ?? 'unknown time'})`;
    }
    return `Image reading for OCR: failed the check (measured ${copy.imageCapabilityCheckedAt ?? 'unknown time'})`;
  }

  function toolCallingText(profile: LlmProfile): string {
    const state = toolCallingState(profile);
    if (state === 'not-measured') return 'Tool calling: not measured';
    const label = state === 'supported' ? 'supported' : 'unsupported';
    return `Tool calling: ${label} (measured ${profile.capabilityCheckedAt ?? 'unknown time'})`;
  }

  async function remove(): Promise<void> {
    if (profiles.length <= 1 || saving) return;
    saving = true;
    error = null;
    try {
      await deleteLlmProfile(selectedId);
      await reload();
      selectedId = profiles[0]?.id ?? '';
      if (profiles.length > 0) draft = toInput(profiles[0]);
      flash = 'Profile deleted.';
    } catch (failure) {
      error = describe(failure);
    } finally {
      saving = false;
    }
  }

  async function setDefault(role: 'ASK' | 'INVESTIGATE', profileId: string): Promise<void> {
    if (profileId === '') return;
    error = null;
    try {
      await setLlmDefault(role, profileId);
      defaults = { ...defaults, [role]: profileId };
      flash = `Default for ${role === 'ASK' ? 'Ask' : 'Investigate'} updated.`;
    } catch (failure) {
      error = describe(failure);
      await reload();
    }
  }

  function describe(failure: unknown): string {
    if (failure instanceof ApiError) return failure.message;
    return failure instanceof Error ? failure.message : 'Something went wrong.';
  }

  onMount(() => {
    void load();
  });
</script>

<section class="admin-panel" aria-label="LLM profile administration">
  {#if loading}
    <p role="status">Loading profiles…</p>
  {:else}
    {#if flash}<p class="flash" role="status">{flash}</p>{/if}
    {#if error}<p role="alert">{error}</p>{/if}

    <div class="profile-bar">
      <select
        aria-label="Profile"
        bind:value={selectedId}
        onchange={() => selectProfile(selectedId)}
        disabled={creating || profiles.length === 0}
      >
        {#each profiles as profile (profile.id)}
          <option value={profile.id}>{profile.name}</option>
        {/each}
      </select>
      <button type="button" onclick={startNew} disabled={creating}>New</button>
      <button
        type="button"
        class="danger"
        onclick={remove}
        disabled={creating || profiles.length <= 1 || saving}
        title={profiles.length <= 1 ? 'The last profile cannot be deleted' : undefined}
      >Delete</button>
    </div>

    {#if profiles.length === 0}
      <p role="status">No LLM profiles configured yet. Create one below.</p>
    {/if}

    <form onsubmit={save} class="profile-form">
      <div class="field">
        <label for="pf-name">Name</label>
        <input id="pf-name" bind:value={draft.name} placeholder="e.g. DeepSeek fast" required />
      </div>
      <ProviderModelFields
        bind:draft
        idPrefix="pf"
        {presets}
        {presetError}
        showCachePrice
        nativeRequired
        modelPlaceholder="e.g. deepseek-chat"
        keyPlaceholder="e.g. OPENAI_API_KEY"
        keyState={!creating && selectedProfile !== null
          ? (selectedProfile.keyAvailable
            ? '✓ the key is present in the server environment'
            : '⚠ the key is missing from the server environment')
          : null}
      />
      <div class="field check">
        <label><input type="checkbox" bind:checked={draft.enabled} /> Enabled</label>
      </div>

      {#if !creating && selectedProfile !== null}
        <div class="field tool-calling">
          <p class="key-state" role="status">{toolCallingText(selectedProfile)}</p>
          <div class="inline">
            <button type="button" onclick={checkToolCalling} disabled={probing}>
              {probing ? 'Checking…' : 'Check tool calling'}
            </button>
          </div>
          <p class="hint">
            Sends two short requests to the saved profile, not to the values above. Investigate needs a supported profile.
          </p>
        </div>
      {/if}

      {#if !creating && selectedProfile !== null}
        <div class="field tool-calling">
          <p class="key-state" role="status">{imageReadingText(ocrCopy)}</p>
          <div class="inline">
            <button type="button" onclick={checkImageReading} disabled={checkingImages}>
              {checkingImages ? 'Checking…' : 'Check image reading'}
            </button>
          </div>
          <p class="hint">
            Sends a test image to the saved profile. Only a profile that reads it can be chosen for OCR; the image goes to this profile's endpoint.
          </p>
        </div>
      {/if}

      <div class="actions">
        <button type="submit" class="primary" disabled={saving}>
          {saving ? 'Saving…' : creating ? 'Create profile' : 'Save changes'}
        </button>
        {#if creating}<button type="button" onclick={cancel}>Cancel</button>{/if}
      </div>
    </form>

    {#if profiles.length > 0}
      <div class="defaults">
        <span class="eyebrow">DEFAULTS</span>
        <p class="hint">Choose which profile Ask and Investigate use by default.</p>
        <div class="field">
          <label for="df-ask">Ask default</label>
          <select
            id="df-ask"
            value={defaults.ASK ?? ''}
            onchange={(event) => setDefault('ASK', event.currentTarget.value)}
          >
            <option value="" disabled>{defaults.ASK === null ? 'Not set' : 'Choose…'}</option>
            {#each profiles as profile (profile.id)}
              <option value={profile.id}>{profile.name}</option>
            {/each}
          </select>
        </div>
        <div class="field">
          <label for="df-investigate">Investigate default</label>
          <select
            id="df-investigate"
            value={defaults.INVESTIGATE ?? ''}
            onchange={(event) => setDefault('INVESTIGATE', event.currentTarget.value)}
          >
            <option value="" disabled>{defaults.INVESTIGATE === null ? 'Not set' : 'Choose…'}</option>
            {#each profiles as profile (profile.id)}
              <option value={profile.id}>{profile.name}</option>
            {/each}
          </select>
        </div>
      </div>
    {/if}
  {/if}
</section>

<style>
  .admin-panel { max-width: 44rem; }
  .flash { color: #a9c7a6; }
  .profile-bar { display: grid; grid-template-columns: minmax(0, 1fr) auto auto; gap: 0.55rem; align-items: end; margin-bottom: 1.25rem; }
  .profile-form { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0.9rem 1rem; }
  .field { display: grid; gap: 0.35rem; align-content: start; }
  .field.check { align-content: end; }
  .field.check label { display: flex; align-items: center; gap: 0.5rem; }
  .field.check input { accent-color: #c4a77d; }
  label { color: #b8bcbb; font-size: 0.82rem; }
  select,
  input:not([type='checkbox']) {
    width: 100%;
    min-width: 0;
    border: 1px solid #383d3e;
    border-radius: 0.48rem;
    background: #202324;
    padding: 0.62rem 0.72rem;
    color: #e8e9e7;
  }
  select:hover,
  input:hover {
    border-color: #515757;
  }
  .hint { margin: 0; color: #929997; font-size: 0.78rem; }
  .actions { grid-column: 1 / -1; display: flex; gap: 0.55rem; margin-top: 0.2rem; }
  button.primary { border-color: #c4a77d; background: #c4a77d; color: #1c1b18; font-weight: 650; }
  button.primary:hover:not(:disabled) { background: #d4ba94; }
  button.danger { border-color: #6e3a37; color: #f0a4a0; }
  button.danger:hover:not(:disabled) { background: #3a2322; }
  .defaults { margin-top: 1.75rem; padding-top: 1.25rem; border-top: 1px solid #2d3131; display: grid; gap: 0.9rem; grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .defaults .eyebrow { grid-column: 1 / -1; }
  .defaults .hint { grid-column: 1 / -1; margin-bottom: -0.3rem; }
  .eyebrow { color: #858a8a; font-size: 0.68rem; font-weight: 700; letter-spacing: 0.12em; }
  @media (max-width: 36rem) {
    .profile-bar { grid-template-columns: minmax(0, 1fr) auto auto; }
    .profile-form, .defaults { grid-template-columns: minmax(0, 1fr); }
  }
</style>
