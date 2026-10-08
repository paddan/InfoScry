<script lang="ts">
  import { onDestroy, onMount } from 'svelte';
  import { listOcrProfiles, type OcrEngine, type OcrImportMode, type OcrProfile } from './api';
  import { ENGINE_LABELS, IMPORT_MODE_LABELS, ocrProfileLabel, type OcrChoice } from './ocrRescan';

  /**
   * The OCR method controls a person picks one reading with: engine, mode and the two profiles, and
   * optionally the language list. Scan again and Retry both show these, so what each choice means, and what
   * "Collection default" means, is said in one place.
   *
   * The controls only collect a choice. Whether it can be used — the engine exists on this machine, an
   * external profile was measured as able to read an image — is decided by the server, which answers a
   * refusal in its own words; nothing here duplicates those rules.
   */

  /** Prefix of the control ids, so two groups on one page do not share an id. */
  export let idPrefix: string;
  /** What the choice is for, in the labels: "Engine for this scan". */
  export let purpose = 'scan';
  /** The choice, bound by the parent; an empty field means the collection's default. */
  export let choice: OcrChoice;
  /** Offers the language list too (a rescan keeps the collection's language, a retry may change it). */
  export let showLanguage = false;
  /** Called when the person changed any field, so the parent can drop whatever was based on the old choice. */
  export let onChange: () => void = () => {};

  const ENGINES = Object.keys(ENGINE_LABELS) as OcrEngine[];
  const MODES = Object.keys(IMPORT_MODE_LABELS) as OcrImportMode[];

  let profiles: OcrProfile[] = [];
  let profilesError: string | null = null;
  let alive = true;

  $: selectable = profiles.filter((profile) => profile.enabled);

  onMount(() => {
    void loadProfiles();
  });

  onDestroy(() => {
    alive = false;
  });

  async function loadProfiles(): Promise<void> {
    try {
      const listed = (await listOcrProfiles()) ?? [];
      if (!alive) return;
      profiles = listed;
      profilesError = null;
    } catch (failure) {
      if (!alive) return;
      profilesError = failure instanceof Error ? failure.message : 'Something went wrong.';
    }
  }
</script>

<div class="controls">
  <div class="field">
    <label for={`${idPrefix}-engine`}>Engine for this {purpose}</label>
    <select id={`${idPrefix}-engine`} bind:value={choice.engine} onchange={onChange}>
      <option value="">Collection default</option>
      {#each ENGINES as value (value)}<option {value}>{ENGINE_LABELS[value]}</option>{/each}
    </select>
  </div>
  <div class="field">
    <label for={`${idPrefix}-mode`}>Import mode for this {purpose}</label>
    <select id={`${idPrefix}-mode`} bind:value={choice.mode} onchange={onChange}>
      <option value="">Collection default</option>
      {#each MODES as value (value)}<option {value}>{IMPORT_MODE_LABELS[value]}</option>{/each}
    </select>
  </div>
  <div class="field">
    <label for={`${idPrefix}-transcription`}>Transcription profile for this {purpose}</label>
    <select id={`${idPrefix}-transcription`} bind:value={choice.transcription} onchange={onChange}>
      <option value="">Collection default</option>
      {#each selectable as profile (profile.id)}<option value={profile.id}>{ocrProfileLabel(profile)}</option>{/each}
    </select>
  </div>
  <div class="field">
    <label for={`${idPrefix}-review`}>Review profile for this {purpose}</label>
    <select id={`${idPrefix}-review`} bind:value={choice.review} onchange={onChange}>
      <option value="">Collection default</option>
      {#each selectable as profile (profile.id)}<option value={profile.id}>{ocrProfileLabel(profile)}</option>{/each}
    </select>
  </div>
  {#if showLanguage}
    <div class="field">
      <label for={`${idPrefix}-language`}>OCR languages for this {purpose}</label>
      <input
        id={`${idPrefix}-language`}
        type="text"
        placeholder="Collection default, for example eng+swe"
        bind:value={choice.language}
        oninput={onChange}
        autocomplete="off"
        spellcheck="false"
      />
    </div>
  {/if}
</div>
{#if profilesError !== null}
  <p class="hint">The OCR profiles could not be listed: {profilesError}</p>
{/if}

<style>
  .controls { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0.75rem; }
  .field { display: grid; gap: 0.35rem; align-content: start; }
  label { color: #b8bcbb; font-size: 0.82rem; }
  select,
  input { min-width: 0; border: 1px solid #383d3e; border-radius: 0.48rem; background: #202324; padding: 0.62rem 0.72rem; color: #e8e9e7; }
  .hint { margin: 0; color: #929997; font-size: 0.82rem; }
  @media (max-width: 36rem) {
    .controls { grid-template-columns: minmax(0, 1fr); }
  }
</style>
