<script lang="ts">
  import { fetchLlmCatalog, type LlmCatalogModel, type LlmPreset, type LlmProvider } from './api';

  /**
   * The provider, preset, model catalog, endpoint, key variable, limits and prices of a profile form.
   *
   * LLM profiles and OCR profiles are filled in the same way, so both forms render these fields from one
   * place. The parent owns the draft (`bind:draft`) and the fields that differ (name, enabled, actions);
   * this component owns the preset choice and the fetched model catalog, which belong to the draft's
   * connection and are dropped whenever that connection changes.
   *
   * With `imageOnly` the model list is limited to models that accept image input: a model the catalog states
   * is text-only is not offered, and one it does not state is offered and labelled "image support unknown"
   * rather than hidden or assumed.
   */
  type ProviderDraft = {
    provider: LlmProvider;
    endpoint: string;
    model: string;
    contextWindow: number;
    maxOutputTokens: number;
    inputPricePerMillion: number;
    outputPricePerMillion: number;
    cacheReadPricePerMillion?: number;
    apiKeyEnvironmentVariable: string | null;
  };

  export let draft: ProviderDraft;
  /** Prefix of the field ids, so two forms on one page never share one. */
  export let idPrefix: string;
  export let presets: LlmPreset[] = [];
  export let presetError: string | null = null;
  export let imageOnly = false;
  /** A cache-read price exists only for profiles the Ask and Investigate calls use. */
  export let showCachePrice = false;
  /** Whether the model field carries the native required attribute (the OCR form validates it itself). */
  export let nativeRequired = false;
  export let modelPlaceholder = '';
  export let keyPlaceholder = '';
  export let keyHint = 'Only the variable name is stored — never the key value.';
  /** Whether the saved profile's key is present, in words; null while there is no saved profile to ask about. */
  export let keyState: string | null = null;

  let presetId = '';
  let fetchingModels = false;
  let catalog: LlmCatalogModel[] = [];
  let catalogId = '';
  let catalogLive = true;
  let catalogTried = false;
  let catalogPriceUnknown = false;
  let catalogImageUnknown = false;
  // The connection (provider + endpoint + key) the catalog was fetched for, and a counter that bumps
  // whenever that connection changes so stale fetches are droppable.
  let connectionKey = '';
  let appliedConnectionKey: string | null = null;
  let connectionGeneration = 0;

  $: connectionKey = `${draft.provider}\u0000${draft.endpoint}\u0000${draft.apiKeyEnvironmentVariable ?? ''}`;
  $: if (connectionKey !== appliedConnectionKey) {
    // The connection changed (a preset, another profile, or a direct edit): the fetched
    // catalog no longer belongs to the draft, and any fetch still in flight is stale.
    appliedConnectionKey = connectionKey;
    connectionGeneration += 1;
    fetchingModels = false;
    catalog = [];
    catalogId = '';
    catalogLive = true;
    catalogTried = false;
    catalogPriceUnknown = false;
    catalogImageUnknown = false;
  }
  $: unknownImageCount = imageOnly ? catalog.filter((model) => model.imageInput === null).length : 0;

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
    const generation = connectionGeneration;
    const key = connectionKey;
    catalog = [];
    catalogId = '';
    catalogLive = true;
    catalogTried = true;
    catalogPriceUnknown = false;
    catalogImageUnknown = false;
    try {
      const data = imageOnly
        ? await fetchLlmCatalog(draft.provider, draft.endpoint, draft.apiKeyEnvironmentVariable, true)
        : await fetchLlmCatalog(draft.provider, draft.endpoint, draft.apiKeyEnvironmentVariable);
      if (generation !== connectionGeneration || key !== connectionKey) return;
      // The server already drops what the catalog states is text-only; this keeps the rule even for an
      // answer that did not apply the filter.
      catalog = imageOnly
        ? data.models.map((model) => ({ ...model, imageInput: model.imageInput ?? null }))
            .filter((model) => model.imageInput !== false)
        : data.models;
      catalogLive = data.live;
    } catch {
      if (generation !== connectionGeneration || key !== connectionKey) return;
      catalogLive = false;
    } finally {
      if (generation === connectionGeneration) fetchingModels = false;
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
    if (showCachePrice && model.cacheReadPricePerMillion !== null) {
      draft.cacheReadPricePerMillion = model.cacheReadPricePerMillion;
    }
    catalogPriceUnknown = !model.priceKnown;
    catalogImageUnknown = imageOnly && model.imageInput === null;
  }

  function modelOptionLabel(model: LlmCatalogModel): string {
    return imageOnly && model.imageInput === null ? `${model.id} — image support unknown` : model.id;
  }
</script>

<div class="field">
  <label for="{idPrefix}-preset">Provider preset</label>
  <select id="{idPrefix}-preset" bind:value={presetId} onchange={() => applyPreset(presetId)}>
    <option value="" disabled>Pick a preset…</option>
    {#each presets as preset (preset.id)}
      <option value={preset.id}>{preset.label}</option>
    {/each}
  </select>
  {#if presetError}<p class="hint" role="status">{presetError}</p>{/if}
</div>
<div class="field">
  <label for="{idPrefix}-provider">Provider</label>
  <select id="{idPrefix}-provider" bind:value={draft.provider}>
    <option value="OPENAI_COMPATIBLE">OpenAI-compatible</option>
    <option value="ANTHROPIC">Anthropic</option>
  </select>
</div>
<div class="field">
  <label for="{idPrefix}-model">Model</label>
  <div class="inline">
    <input id="{idPrefix}-model" bind:value={draft.model} placeholder={modelPlaceholder} required={nativeRequired} aria-required="true" />
    <button type="button" onclick={fetchModels} disabled={fetchingModels}>
      {fetchingModels ? 'Fetching…' : 'Fetch models'}
    </button>
  </div>
  {#if imageOnly}
    <p class="hint">Only models that accept image input are listed. A model whose image support the catalog does not state is marked as unknown.</p>
  {/if}
  {#if catalog.length > 0}
    <label for="{idPrefix}-catalog">Model catalog</label>
    <select id="{idPrefix}-catalog" bind:value={catalogId} onchange={() => applyCatalogModel(catalogId)}>
      <option value="" disabled>Choose a model…</option>
      {#each catalog as model (model.id)}
        <option value={model.id}>{modelOptionLabel(model)}</option>
      {/each}
    </select>
  {/if}
  {#if catalogTried && (catalog.length === 0 || !catalogLive)}
    <p class="hint">Could not fetch models — enter one manually.</p>
  {/if}
  {#if catalogPriceUnknown}
    <p class="hint">price unknown — enter manually</p>
  {/if}
  {#if catalogImageUnknown || (unknownImageCount > 0 && catalogId === '')}
    <p class="hint">
      Image support unknown for {catalogImageUnknown ? 'this model' : 'some models'}: save the profile and run
      its image check before relying on it.
    </p>
  {/if}
</div>
<div class="field">
  <label for="{idPrefix}-endpoint">Endpoint (base URL)</label>
  <input
    id="{idPrefix}-endpoint"
    bind:value={draft.endpoint}
    placeholder="https://… (leave empty for Anthropic)"
  />
</div>
<div class="field">
  <label for="{idPrefix}-key">API key environment variable</label>
  <input id="{idPrefix}-key" bind:value={draft.apiKeyEnvironmentVariable} placeholder={keyPlaceholder} />
  <p class="hint">{keyHint}</p>
  {#if keyState !== null}
    <p class="hint key-state">{keyState}</p>
  {/if}
</div>
<div class="field">
  <label for="{idPrefix}-context">Context window (tokens)</label>
  <input id="{idPrefix}-context" type="number" min="1" step="1" bind:value={draft.contextWindow} />
</div>
<div class="field">
  <label for="{idPrefix}-maxtokens">Max output tokens</label>
  <input id="{idPrefix}-maxtokens" type="number" min="1" step="1" bind:value={draft.maxOutputTokens} />
</div>
<div class="field">
  <label for="{idPrefix}-inprice">Input price (USD / 1M tokens)</label>
  <input id="{idPrefix}-inprice" type="number" min="0" step="0.0001" bind:value={draft.inputPricePerMillion} />
</div>
<div class="field">
  <label for="{idPrefix}-outprice">Output price (USD / 1M tokens)</label>
  <input id="{idPrefix}-outprice" type="number" min="0" step="0.0001" bind:value={draft.outputPricePerMillion} />
</div>
{#if showCachePrice}
  <div class="field">
    <label for="{idPrefix}-cacheprice">Cache-read price (USD / 1M tokens)</label>
    <input id="{idPrefix}-cacheprice" type="number" min="0" step="0.0001" bind:value={draft.cacheReadPricePerMillion} />
  </div>
{/if}

<style>
  .field { display: grid; gap: 0.35rem; align-content: start; }
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
  input:hover { border-color: #515757; }
  .hint { margin: 0; color: #929997; font-size: 0.78rem; }
  .key-state { color: #a9c7a6; }
</style>
