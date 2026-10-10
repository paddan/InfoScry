<script lang="ts">
  import { onMount, tick } from 'svelte';

  export let mode: 'split' | 'review';
  export let sourceSnippet: string;
  export let sourceOpen: boolean;
  export let opener: HTMLElement | null = null;
  export let fallbackFocus: HTMLElement | null = null;
  export let narrow = false;
  export let historyShouldCollapse = false;
  /** True only while the source pane is the workspace's visible pane. */
  export let sourceVisible = false;
  export let onClose: () => void;
  export let onBack: () => void;

  let workspace: HTMLElement;
  let sourceHeading: HTMLElement;
  let narrowPane: 'context' | 'source' = 'context';
  let wasOpen = false;
  let savedOpener: HTMLElement | null = null;
  let measuredWidth = 0;

  function measure(width: number): void {
    measuredWidth = width;
    narrow = width < 1000;
    historyShouldCollapse = sourceOpen && width < 1280;
  }

  onMount(() => {
    measure(workspace.clientWidth || workspace.getBoundingClientRect().width || window.innerWidth);
    if (typeof ResizeObserver === 'undefined') return;
    const observer = new ResizeObserver(([entry]) => measure(entry.contentRect.width));
    observer.observe(workspace);
    return () => observer.disconnect();
  });

  $: if (sourceOpen && !wasOpen) {
    wasOpen = true;
    savedOpener = opener;
    narrowPane = 'source';
    tick().then(() => sourceHeading?.focus());
  } else if (!sourceOpen) {
    wasOpen = false;
    narrowPane = 'context';
  }
  $: if (sourceOpen) savedOpener = opener;
  $: sourceVisible = sourceOpen && (!narrow || narrowPane === 'source');
  $: if (measuredWidth > 0) historyShouldCollapse = sourceOpen && measuredWidth < 1280;

  async function close(): Promise<void> {
    const openerToReturn = savedOpener;
    onClose();
    await tick();
    returnFocus(openerToReturn);
  }

  function back(): void {
    const openerToReturn = savedOpener;
    narrowPane = 'context';
    onBack();
    void tick().then(() => returnFocus(openerToReturn));
  }

  function returnFocus(openerTarget: HTMLElement | null): void {
    const targetIsVisible = openerTarget?.isConnected && openerTarget.closest('[hidden]') === null;
    (targetIsVisible ? openerTarget : fallbackFocus)?.focus();
  }

  function handleKeydown(event: KeyboardEvent): void {
    if (event.key === 'Escape' && sourceOpen) {
      event.preventDefault();
      void close();
    }
  }
</script>

<svelte:window onkeydown={handleKeydown} />

<div
  bind:this={workspace}
  class="source-workspace"
  class:source-open={sourceOpen}
  data-layout={narrow ? 'narrow' : 'split'}
  data-mode={mode}
  role="group"
  aria-label="Source workspace"
>
  <div class="workspace-context" hidden={(mode === 'review' && !(narrow && sourceOpen && narrowPane === 'context')) || (narrow && sourceOpen && narrowPane === 'source')}>
    {#if narrow && sourceOpen && narrowPane === 'context'}
      <button type="button" class="reopen-source" onclick={() => (narrowPane = 'source')}>Open selected source</button>
    {/if}
    <slot />
  </div>
  {#if sourceOpen}
    <section
      class="source-pane"
      aria-labelledby="source-heading"
      tabindex="-1"
      hidden={narrow && narrowPane !== 'source'}
    >
      <div class="source-heading-row">
        {#if narrow}
          <button type="button" aria-label="Back to results" onclick={back}>Back</button>
        {/if}
        <h2 id="source-heading" bind:this={sourceHeading} tabindex="-1">Source</h2>
        {#if !narrow}
          <button type="button" aria-label="Close source viewer" onclick={close}>Close</button>
        {/if}
      </div>
      <p class="source-snippet">{sourceSnippet}</p>
      <slot name="source" />
    </section>
  {/if}
</div>

<style>
  .source-workspace { display: grid; grid-template-columns: minmax(0, 1fr); gap: 1.5rem; min-width: 0; }
  .source-workspace[data-layout='split'].source-open { grid-template-columns: minmax(0, 1fr) minmax(22rem, clamp(22rem, 33vw, 34rem)); align-items: start; }
  .source-workspace[data-mode='review'].source-open { grid-template-columns: minmax(0, 1fr); }
  .workspace-context { min-width: 0; }
  .source-pane { min-width: 0; max-height: calc(100vh - 2.5rem); overflow: auto; border: 1px solid #303535; border-radius: 0.55rem; background: #191c1d; padding: 1.1rem 1.2rem; }
  .source-heading-row { display: flex; align-items: center; justify-content: space-between; gap: 0.75rem; }
  .source-heading-row h2 { margin: 0; color: #f0efec; font-size: 1.1rem; }
  .source-snippet { color: #929997; font-size: 0.82rem; }
  @media (max-width: 54rem) {
    .source-workspace[data-layout='split'].source-open { grid-template-columns: minmax(0, 1fr) minmax(20rem, 42%); }
  }
  @media (max-width: 44rem) {
    .source-workspace[data-layout='split'].source-open { grid-template-columns: minmax(0, 1fr); }
  }
</style>
