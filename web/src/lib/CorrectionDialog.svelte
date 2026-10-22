<script lang="ts">
  import { onMount, tick } from 'svelte';
  import {
    ApiError,
    previewCorrection,
    putCorrection,
    type CorrectionPreview,
    type CorrectionRule,
    type CorrectionRuleInput,
  } from './api';
  import { containDialogFocus, holdDialogFocus } from './dialogFocus';

  export let collectionId: string;
  /** The rule being edited, or null for a new one. */
  export let rule: CorrectionRule | null = null;
  /** A word selected in the reader, filled in as the text to find. */
  export let initialFind = '';
  export let initialReplace = '';
  export let onClose: () => void;
  export let onSaved: (rule: CorrectionRule) => void;

  /** One id per dialog: a Save retried after a lost answer stores the same rule instead of a second one. */
  const ruleId = rule?.id ?? newId();
  let find = rule?.find ?? initialFind;
  let replace = rule?.replace ?? initialReplace;
  let exactCase = rule?.exactCase ?? false;
  let preview: CorrectionPreview | null = null;
  /** The inputs the shown preview was made for; Save needs a preview of exactly what would be stored. */
  let previewedKey: string | null = null;
  let previewing = false;
  let saving = false;
  let error: string | null = null;
  let dialog: HTMLElement | null = null;
  let findInput: HTMLInputElement | null = null;
  let replaceInput: HTMLInputElement | null = null;

  $: input = { find: find.trim(), replace: replace.trim(), exactCase } satisfies CorrectionRuleInput;
  $: key = JSON.stringify(input);
  $: complete = input.find !== '' && input.replace !== '';
  $: canSave = complete && previewedKey === key && !saving && !previewing;

  onMount(() => {
    void tick().then(() => (find.trim() !== '' && replace.trim() === '' ? replaceInput : findInput)?.focus());
  });

  function count(n: number, one: string, many: string): string {
    return `${n.toLocaleString('en-US')} ${n === 1 ? one : many}`;
  }

  async function runPreview(): Promise<void> {
    if (!complete || previewing) return;
    const asked = key;
    previewing = true;
    error = null;
    try {
      const answer = await previewCorrection(collectionId, input, rule?.id ?? null);
      preview = answer;
      previewedKey = asked;
    } catch (failure) {
      error = describe(failure);
    } finally {
      previewing = false;
    }
  }

  async function save(event: SubmitEvent): Promise<void> {
    event.preventDefault();
    if (!canSave) return;
    saving = true;
    error = null;
    holdDialogFocus(dialog);
    try {
      const stored = await putCorrection(collectionId, ruleId, input);
      onSaved(stored);
    } catch (failure) {
      error = failure instanceof ApiError && failure.code === 'CORRECTION_FIND_TAKEN'
        ? 'That word already has a correction.'
        : describe(failure);
      saving = false;
    }
  }

  function newId(): string {
    return typeof crypto !== 'undefined' && 'randomUUID' in crypto
      ? crypto.randomUUID()
      : `rule-${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`;
  }

  function describe(failure: unknown): string {
    if (failure instanceof ApiError) return failure.message;
    return failure instanceof Error ? failure.message : 'Something went wrong.';
  }

  function handleKeydown(event: KeyboardEvent): void {
    if (event.key === 'Escape') {
      event.preventDefault();
      if (!saving) onClose();
    } else if (event.key === 'Tab') {
      containDialogFocus(event, dialog);
    }
  }
</script>

<div class="confirm-backdrop" aria-hidden="true" onclick={() => !saving && onClose()}></div>
<div
  class="confirm-dialog"
  role="dialog"
  aria-modal="true"
  aria-labelledby="correction-heading"
  bind:this={dialog}
  tabindex="-1"
  onkeydown={handleKeydown}
>
  <form class="confirm-form" onsubmit={save}>
    <h2 id="correction-heading">{rule === null ? 'Add correction' : 'Edit correction'}</h2>
    <p class="hint">
      Every whole-word match in this collection's documents will read the correction. Case is ignored unless you
      choose exact case, and the replacement keeps the writing it replaces.
    </p>
    <div class="field">
      <label for="correction-find">Wrong</label>
      <input id="correction-find" bind:this={findInput} bind:value={find} maxlength="200" autocomplete="off" disabled={saving} />
    </div>
    <div class="field">
      <label for="correction-replace">Correct</label>
      <input id="correction-replace" bind:this={replaceInput} bind:value={replace} maxlength="200" autocomplete="off" disabled={saving} />
    </div>
    <label class="check"><input type="checkbox" bind:checked={exactCase} disabled={saving} /> Exact case</label>
    <div class="actions">
      <button type="button" onclick={runPreview} disabled={!complete || previewing || saving}>{previewing ? 'Previewing…' : 'Preview'}</button>
    </div>
    {#if preview !== null && previewedKey === key}
      <div class="preview" role="status">
        <p>{count(preview.matches, 'match', 'matches')} in {count(preview.documents, 'document', 'documents')}</p>
        {#if preview.examples.length > 0}
          <ul>
            {#each preview.examples as example, index (index)}
              <li><span class="before">{example.before}</span> <span aria-hidden="true">→</span> <span class="after">{example.after}</span></li>
            {/each}
          </ul>
        {/if}
      </div>
    {:else if preview !== null}
      <p class="hint">The words changed; preview again before saving.</p>
    {/if}
    {#if error !== null}<p role="alert">{error}</p>{/if}
    <div class="actions end">
      <button type="submit" class="primary" disabled={!canSave}>{saving ? 'Saving…' : 'Save'}</button>
      <button type="button" class="cancel" onclick={onClose} disabled={saving}>Cancel</button>
    </div>
  </form>
</div>

<style>
  .confirm-backdrop { position: fixed; inset: 0; background: rgba(8, 9, 10, 0.62); }
  .confirm-dialog { position: fixed; top: 50%; left: 50%; z-index: 2; transform: translate(-50%, -50%); width: min(36rem, calc(100vw - 2rem)); max-height: calc(100vh - 2rem); overflow: auto; border: 1px solid #3a3e3e; border-radius: 0.6rem; background: #1d2021; padding: 1.15rem; }
  .confirm-form { display: grid; gap: 0.7rem; }
  h2 { margin: 0; font-size: 1.05rem; }
  p { margin: 0; }
  .field { display: grid; gap: 0.35rem; }
  label { color: #b8bcbb; font-size: 0.82rem; }
  .check { display: flex; align-items: center; gap: 0.4rem; }
  input:not([type='checkbox']) { min-width: 0; border: 1px solid #383d3e; border-radius: 0.48rem; background: #202324; padding: 0.62rem 0.72rem; color: #e8e9e7; font: inherit; }
  .hint { color: #929997; font-size: 0.82rem; }
  .preview ul { display: grid; gap: 0.3rem; margin: 0.4rem 0 0; padding-left: 1.1rem; font-size: 0.85rem; }
  .before { color: #b8bcbb; }
  .after { color: #e8e9e7; }
  .actions { display: flex; flex-wrap: wrap; align-items: center; gap: 0.55rem; }
  .actions.end { justify-content: end; }
  button.primary { border-color: #c4a77d; background: #c4a77d; color: #1c1b18; font-weight: 650; }
  button.primary:hover:not(:disabled) { background: #d4ba94; }
</style>
