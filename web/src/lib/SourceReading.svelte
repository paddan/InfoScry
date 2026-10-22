<script lang="ts">
  import { ApiError, readSourceReading } from './api';
  import CorrectionDialog from './CorrectionDialog.svelte';

  export let collectionId: string;
  export let sourceId: string;
  export let revisionId: string | null = null;
  /** True when the collection's corrections changed this page; its reading can then be shown. */
  export let hasReading = false;

  /** The longest selection a correction can be made from, the same bound a rule's text has. */
  const MAX_SELECTION = 200;

  let container: HTMLElement | null = null;
  let showingReading = false;
  let reading: string | null = null;
  let readingError: string | null = null;
  let loadingReading = false;
  let selected: string | null = null;
  let correcting: string | null = null;
  let correctButton: HTMLElement | null = null;
  let boundKey = '';

  $: if (`${collectionId}/${sourceId}/${revisionId ?? ''}` !== boundKey) reset();

  function reset(): void {
    boundKey = `${collectionId}/${sourceId}/${revisionId ?? ''}`;
    showingReading = false;
    reading = null;
    readingError = null;
    selected = null;
  }

  async function toggleReading(): Promise<void> {
    if (showingReading) {
      showingReading = false;
      return;
    }
    showingReading = true;
    if (reading !== null) return;
    const asked = boundKey;
    loadingReading = true;
    readingError = null;
    try {
      const answer = await readSourceReading(collectionId, sourceId, revisionId);
      if (asked === boundKey) reading = answer;
    } catch (failure) {
      if (asked === boundKey) readingError = failure instanceof ApiError || failure instanceof Error ? failure.message : 'The reading could not be loaded.';
    } finally {
      loadingReading = false;
    }
  }

  /** What the reader selected inside this page, collapsed to single spaces, when it could be a rule's text. */
  function readSelection(): void {
    const selection = typeof window === 'undefined' ? null : window.getSelection();
    const text = selection?.toString().replace(/\s+/g, ' ').trim() ?? '';
    const inside = selection !== null && selection !== undefined && selection.rangeCount > 0 &&
      container !== null && container.contains(selection.getRangeAt(0).commonAncestorContainer);
    selected = inside && text !== '' && text.length <= MAX_SELECTION ? text : null;
  }

  function openCorrection(event: MouseEvent): void {
    correctButton = event.currentTarget instanceof HTMLElement ? event.currentTarget : null;
    correcting = selected;
  }

  function closeCorrection(): void {
    correcting = null;
    if (correctButton?.isConnected) correctButton.focus();
  }
</script>

<div class="source-reading">
  {#if hasReading || selected !== null}
    <div class="reading-tools">
      {#if hasReading}
        <span class="corrected-mark">Corrected</span>
        <button type="button" onclick={toggleReading} aria-pressed={showingReading}>
          {showingReading ? 'Show corrected text' : 'Show reading'}
        </button>
      {/if}
      {#if selected !== null && !showingReading}
        <button type="button" onclick={openCorrection}>Correct across collection</button>
      {/if}
    </div>
  {/if}
  <!-- Selecting text with the mouse or the keyboard offers a correction for it. -->
  <div bind:this={container} onmouseup={readSelection} onkeyup={readSelection} role="presentation">
    {#if showingReading}
      {#if loadingReading}
        <p role="status">Loading the reading…</p>
      {:else if readingError !== null}
        <p role="alert">{readingError}</p>
      {:else}
        <p class="meta">The text as it was read, before the collection's corrections.</p>
        <pre class="source-text">{reading ?? ''}</pre>
      {/if}
    {:else}
      <slot />
    {/if}
  </div>
</div>

{#if correcting !== null}
  <CorrectionDialog
    {collectionId}
    initialFind={correcting}
    onClose={closeCorrection}
    onSaved={closeCorrection}
  />
{/if}

<style>
  .reading-tools { display: flex; flex-wrap: wrap; align-items: center; gap: 0.5rem; margin: 0 0 0.6rem; }
  .corrected-mark { border: 1px solid #4a5050; border-radius: 0.35rem; padding: 0.05rem 0.4rem; color: #b8bcbb; font-size: 0.72rem; }
  .meta { color: #929997; font-size: 0.82rem; margin: 0 0 0.4rem; }
  .source-text { white-space: pre-wrap; overflow-wrap: anywhere; }
</style>
