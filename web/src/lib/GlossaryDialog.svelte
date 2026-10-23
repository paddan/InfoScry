<script lang="ts">
  import { onMount, tick } from 'svelte';
  import { ApiError, putGlossaryGroup, type GlossaryGroup, type GlossaryTerm } from './api';
  import { containDialogFocus, holdDialogFocus } from './dialogFocus';

  export let collectionId: string;
  /** The collection's groups, to name the one that already has a term. */
  export let groups: GlossaryGroup[] = [];
  /** The group being edited, or null for a new one. */
  export let group: GlossaryGroup | null = null;
  /** A word selected in the reader, filled in as the first term. */
  export let initialTerm = '';
  export let onClose: () => void;
  export let onSaved: (group: GlossaryGroup) => void;

  /** One id per dialog: a Save retried after a lost answer stores the same group instead of a second one. */
  const groupId = group?.id ?? newId();
  let terms: GlossaryTerm[] = group?.terms.map((term) => ({ ...term })) ?? [{ text: initialTerm, prefix: false }, { text: '', prefix: false }];
  let saving = false;
  let error: string | null = null;
  /** A refusal that names one term: shown beside it, and the field is focused. */
  let termError: { index: number; message: string } | null = null;
  let dialog: HTMLElement | null = null;
  let inputs: HTMLInputElement[] = [];

  $: filled = terms.filter((term) => term.text.trim() !== '');
  $: canSave = filled.length >= 2 && !saving;

  onMount(() => {
    void tick().then(() => inputs[initialTerm !== '' && group === null ? 1 : 0]?.focus());
  });

  function addTerm(): void {
    terms = [...terms, { text: '', prefix: false }];
    void tick().then(() => inputs[terms.length - 1]?.focus());
  }

  function removeTerm(index: number): void {
    terms = terms.filter((_, position) => position !== index);
    termError = null;
    void tick().then(() => inputs[Math.min(index, terms.length - 1)]?.focus());
  }

  async function save(event: SubmitEvent): Promise<void> {
    event.preventDefault();
    if (!canSave) return;
    // What is sent, and the field each sent term came from, so a refusal's index finds its field.
    const sent = terms.map((term, index) => ({ term: { text: term.text.trim(), prefix: term.prefix }, index }))
      .filter(({ term }) => term.text !== '');
    saving = true;
    error = null;
    termError = null;
    holdDialogFocus(dialog);
    try {
      const stored = await putGlossaryGroup(collectionId, groupId, sent.map(({ term }) => term));
      onSaved(stored);
    } catch (failure) {
      saving = false;
      const index = failure instanceof ApiError && failure.details?.term !== undefined
        ? sent[Number(failure.details.term)]?.index
        : undefined;
      if (failure instanceof ApiError && index !== undefined && failure.code === 'GLOSSARY_TERM_TAKEN') {
        const holder = groups.find((candidate) => candidate.id === failure.details?.groupId);
        termError = { index, message: holder ? `Already in the group “${holder.terms[0]?.text ?? ''}”.` : 'Already in another group.' };
      } else if (failure instanceof ApiError && index !== undefined) {
        termError = { index, message: failure.message };
      } else {
        error = failure instanceof Error ? failure.message : 'Something went wrong.';
      }
      if (termError !== null) {
        const target = termError.index;
        await tick();
        inputs[target]?.focus();
      }
    }
  }

  function newId(): string {
    return typeof crypto !== 'undefined' && 'randomUUID' in crypto
      ? crypto.randomUUID()
      : `group-${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`;
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
  aria-labelledby="glossary-heading"
  bind:this={dialog}
  tabindex="-1"
  onkeydown={handleKeydown}
>
  <form class="confirm-form" onsubmit={save}>
    <h2 id="glossary-heading">{group === null ? 'Add glossary group' : 'Edit glossary group'}</h2>
    <p class="hint">
      Names with the same meaning, such as an abbreviation and the full name. Searching one finds the others, and Ask
      and Investigate are told they mean the same. Nothing in the text is changed.
    </p>
    {#each terms as term, index (index)}
      <div class="term">
        <div class="field">
          <label for={`glossary-term-${index}`}>Term {index + 1}</label>
          <div class="row">
            <input
              id={`glossary-term-${index}`}
              bind:this={inputs[index]}
              bind:value={term.text}
              maxlength="200"
              autocomplete="off"
              disabled={saving}
              aria-invalid={termError?.index === index ? 'true' : undefined}
              aria-describedby={termError?.index === index ? `glossary-term-error-${index}` : undefined}
            />
            {#if terms.length > 2}
              <button type="button" aria-label={`Remove term ${index + 1}`} onclick={() => removeTerm(index)} disabled={saving}>Remove term</button>
            {/if}
          </div>
        </div>
        <label class="check"><input type="checkbox" bind:checked={term.prefix} disabled={saving} /> Also match words that start with this</label>
        {#if termError?.index === index}<p class="term-error" id={`glossary-term-error-${index}`}>{termError.message}</p>{/if}
      </div>
    {/each}
    <div class="actions">
      <button type="button" onclick={addTerm} disabled={saving || terms.length >= 20}>Add term</button>
    </div>
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
  .term { display: grid; gap: 0.35rem; }
  .field { display: grid; gap: 0.35rem; }
  .row { display: flex; gap: 0.45rem; }
  .row input { flex: 1; }
  label { color: #b8bcbb; font-size: 0.82rem; }
  .check { display: flex; align-items: center; gap: 0.4rem; }
  input:not([type='checkbox']) { min-width: 0; border: 1px solid #383d3e; border-radius: 0.48rem; background: #202324; padding: 0.62rem 0.72rem; color: #e8e9e7; font: inherit; }
  input[aria-invalid='true'] { border-color: #d08770; }
  .term-error { color: #e0a28f; font-size: 0.82rem; }
  .hint { color: #929997; font-size: 0.82rem; }
  .actions { display: flex; flex-wrap: wrap; align-items: center; gap: 0.55rem; }
  .actions.end { justify-content: end; }
  button.primary { border-color: #c4a77d; background: #c4a77d; color: #1c1b18; font-weight: 650; }
  button.primary:hover:not(:disabled) { background: #d4ba94; }
</style>
