<script lang="ts">
  import { onMount } from 'svelte';
  import { listReadingMethods, updateCollectionOcrSettings, type Collection, type ReadingMethodOption } from './api';

  export let collection: Collection;
  export let onChanged: () => Promise<void> | void;
  export let onOpenOcrProfiles: (() => void) | undefined = undefined;

  let language = '';
  let defaultMethod = '';
  let methods: ReadingMethodOption[] = [];
  let loading = true;
  let saving = false;
  let error: string | null = null;
  let saved = false;
  let boundId = '';
  let generation = 0;

  $: if (collection.id !== boundId) void load(collection);
  $: hasValidDefaultMethod = defaultMethod === '' || methods.some((method) => method.method === defaultMethod);
  $: hasAvailableMethod = methods.some((method) => method.available);

  async function load(target: Collection): Promise<void> {
    boundId = target.id;
    const mine = ++generation;
    const hasStoredMethod = target.defaultMethod !== undefined;
    language = target.language;
    defaultMethod = target.defaultMethod ?? '';
    loading = true;
    error = null;
    saved = false;
    try {
      const result = await listReadingMethods(target.id);
      if (mine !== generation) return;
      methods = result.methods;
      if (!hasStoredMethod && defaultMethod === '' && result.default !== null) defaultMethod = result.default;
    } catch (failure) {
      if (mine === generation) error = failure instanceof Error ? failure.message : 'Something went wrong.';
    } finally {
      if (mine === generation) loading = false;
    }
  }
  async function save(event: SubmitEvent): Promise<void> {
    event.preventDefault();
    if (saving) return;
    const mine = generation;
    saving = true;
    error = null;
    saved = false;
    try {
      await updateCollectionOcrSettings(collection.id, {
        language: language.trim(),
        ...(defaultMethod === '' ? {} : { defaultMethod }),
      });
      await onChanged();
      if (mine === generation) saved = true;
    } catch (failure) {
      if (mine === generation) error = failure instanceof Error ? failure.message : 'Something went wrong.';
    } finally {
      if (mine === generation) saving = false;
    }
  }
</script>

<form class="settings-form" onsubmit={save} aria-label="OCR settings" data-testid="collection-ocr-settings">
  <div class="field">
    <label for="collection-ocr-language">OCR language</label>
    <input id="collection-ocr-language" bind:value={language} disabled={saving} />
  </div>
  <div class="field">
    <label for="default-reading-method">Default reading method</label>
    <select id="default-reading-method" data-testid="default-reading-method" bind:value={defaultMethod} disabled={loading || saving}>
      {#if defaultMethod === ''}
        <option value="" disabled>{hasAvailableMethod ? 'Choose a reading method' : 'No reading method is available'}</option>
      {/if}
      {#each methods as method (method.method)}
        <option value={method.method} disabled={!method.available}>{method.label}{method.available ? '' : ` — unavailable: ${method.unavailableReason ?? 'Unavailable'}`}</option>
      {/each}
    </select>
  </div>
  {#if onOpenOcrProfiles && methods.some((method) => method.method.startsWith('llm:'))}
    <button type="button" onclick={onOpenOcrProfiles}>Manage OCR profiles</button>
  {/if}
  {#if loading}<p class="hint" role="status">Loading reading methods…</p>{/if}
  {#if error}<p role="alert">{error}</p>{/if}
  {#if saved}<p role="status">OCR settings saved.</p>{/if}
  <div class="actions"><button type="submit" class="primary" disabled={saving || loading || !hasValidDefaultMethod}>{saving ? 'Saving…' : 'Save OCR settings'}</button></div>
</form>

<style>
  .settings-form { display: grid; gap: 0.7rem; }
  .field { display: grid; gap: 0.3rem; }
  label { color: #b8bcbb; font-size: 0.82rem; }
  input, select { min-width: 0; border: 1px solid #383d3e; border-radius: 0.48rem; background: #202324; padding: 0.62rem 0.72rem; color: #e8e9e7; font: inherit; }
  .actions { display: flex; gap: 0.55rem; }
  button.primary { border-color: #c4a77d; background: #c4a77d; color: #1c1b18; font-weight: 650; }
  .hint { color: #929997; font-size: 0.82rem; }
</style>
