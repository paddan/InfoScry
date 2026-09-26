<script lang="ts">
  import { onMount } from 'svelte';
  import {
    ApiError,
    createLlmProfile,
    deleteLlmProfile,
    fetchLlmCatalog,
    listLlmPresets,
    listLlmProfiles,
    setLlmDefault,
    updateLlmProfile,
    type LlmCatalogModel,
    type LlmDefaults,
    type LlmPreset,
    type LlmProfile,
    type LlmProfileInput,
  } from './api';

  let profiles: LlmProfile[] = [];
  let defaults: LlmDefaults = { ASK: null, INVESTIGATE: null };
  let selectedId = '';
  let draft: LlmProfileInput = emptyDraft();
  let creating = false;
  let loading = true;
  let saving = false;
  let error: string | null = null;
  let flash: string | null = null;
  let presets: LlmPreset[] = [];
  let presetId = '';
  let fetchingModels = false;
  let catalog: LlmCatalogModel[] = [];
  let catalogId = '';
  let catalogLive = true;
  let catalogTried = false;
  let catalogPriceUnknown = false;

  $: selectedProfile = profiles.find((profile) => profile.id === selectedId) ?? null;

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
  }

  async function load(): Promise<void> {
    loading = true;
    error = null;
    try {
      presets = await listLlmPresets();
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

  function applyPreset(id: string): void {
    const preset = presets.find((candidate) => candidate.id === id);
    if (!preset) return;
    draft.provider = preset.provider;
    draft.endpoint = preset.endpoint;
    draft.apiKeyEnvironmentVariable = preset.apiKeyEnvironmentVariable;
  }

  async function fetchModels(): Promise<void> {
    if (fetchingModels) return;
    fetchingModels = true;
    catalog = [];
    catalogId = '';
    catalogLive = true;
    catalogTried = true;
    catalogPriceUnknown = false;
    try {
      const data = await fetchLlmCatalog(draft.provider, draft.endpoint, draft.apiKeyEnvironmentVariable);
      catalog = data.models;
      catalogLive = data.live;
    } catch {
      catalogLive = false;
    } finally {
      fetchingModels = false;
    }
  }

  function applyCatalogModel(id: string): void {
    const model = catalog.find((candidate) => candidate.id === id);
    if (!model) return;
    catalogId = id;
    draft.model = model.id;
    if (model.contextWindow !== null) draft.contextWindow = model.contextWindow;
    if (model.maxOutputTokens !== null) draft.maxOutputTokens = model.maxOutputTokens;
    if (model.inputPricePerMillion !== null) draft.inputPricePerMillion = model.inputPricePerMillion;
    if (model.outputPricePerMillion !== null) draft.outputPricePerMillion = model.outputPricePerMillion;
    if (model.cacheReadPricePerMillion !== null) draft.cacheReadPricePerMillion = model.cacheReadPricePerMillion;
    catalogPriceUnknown = !model.priceKnown;
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
      <div class="field">
        <label for="pf-preset">Provider preset</label>
        <select id="pf-preset" bind:value={presetId} onchange={() => applyPreset(presetId)}>
          <option value="" disabled>Pick a preset…</option>
          {#each presets as preset (preset.id)}
            <option value={preset.id}>{preset.label}</option>
          {/each}
        </select>
      </div>
      <div class="field">
        <label for="pf-provider">Provider</label>
        <select id="pf-provider" bind:value={draft.provider}>
          <option value="OPENAI_COMPATIBLE">OpenAI-compatible</option>
          <option value="ANTHROPIC">Anthropic</option>
        </select>
      </div>
      <div class="field">
        <label for="pf-model">Model</label>
        <div class="inline">
          <input id="pf-model" bind:value={draft.model} placeholder="e.g. deepseek-chat" required />
          <button type="button" onclick={fetchModels} disabled={fetchingModels}>
            {fetchingModels ? 'Fetching…' : 'Fetch models'}
          </button>
        </div>
        {#if catalog.length > 0}
          <label for="pf-catalog">Model catalog</label>
          <select id="pf-catalog" bind:value={catalogId} onchange={() => applyCatalogModel(catalogId)}>
            <option value="" disabled>Choose a model…</option>
            {#each catalog as model (model.id)}
              <option value={model.id}>{model.id}</option>
            {/each}
          </select>
        {/if}
        {#if catalogTried && (catalog.length === 0 || !catalogLive)}
          <p class="hint">Could not fetch models — enter one manually.</p>
        {/if}
        {#if catalogPriceUnknown}
          <p class="hint">price unknown — enter manually</p>
        {/if}
      </div>
      <div class="field">
        <label for="pf-endpoint">Endpoint (base URL)</label>
        <input
          id="pf-endpoint"
          bind:value={draft.endpoint}
          placeholder="https://… (leave empty for Anthropic)"
        />
      </div>
      <div class="field">
        <label for="pf-key">API key environment variable</label>
        <input
          id="pf-key"
          bind:value={draft.apiKeyEnvironmentVariable}
          placeholder="e.g. OPENAI_API_KEY"
        />
        <p class="hint">Only the variable name is stored — never the key value.</p>
        {#if !creating && selectedProfile !== null}
          <p class="hint key-state">
            {selectedProfile.keyAvailable
              ? '✓ the key is present in the server environment'
              : '⚠ the key is missing from the server environment'}
          </p>
        {/if}
      </div>
      <div class="field">
        <label for="pf-context">Context window (tokens)</label>
        <input id="pf-context" type="number" min="1" step="1" bind:value={draft.contextWindow} />
      </div>
      <div class="field">
        <label for="pf-maxtokens">Max output tokens</label>
        <input id="pf-maxtokens" type="number" min="1" step="1" bind:value={draft.maxOutputTokens} />
      </div>
      <div class="field">
        <label for="pf-inprice">Input price (USD / 1M tokens)</label>
        <input id="pf-inprice" type="number" min="0" step="0.0001" bind:value={draft.inputPricePerMillion} />
      </div>
      <div class="field">
        <label for="pf-outprice">Output price (USD / 1M tokens)</label>
        <input id="pf-outprice" type="number" min="0" step="0.0001" bind:value={draft.outputPricePerMillion} />
      </div>
      <div class="field">
        <label for="pf-cacheprice">Cache-read price (USD / 1M tokens)</label>
        <input id="pf-cacheprice" type="number" min="0" step="0.0001" bind:value={draft.cacheReadPricePerMillion} />
      </div>
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
  .inline { display: flex; align-items: center; gap: 0.55rem; }
  .inline button { white-space: nowrap; }
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
  .key-state { color: #a9c7a6; }
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
