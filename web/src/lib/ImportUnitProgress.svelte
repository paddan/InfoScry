<script lang="ts">
  import type { DocumentProgressView } from './api';
  export let progress: DocumentProgressView | null | undefined;

  $: unit = progress?.unitKind === 'PAGE' ? 'page'
    : progress?.unitKind === 'SLIDE' ? 'slide'
    : progress?.unitKind === 'SHEET' ? 'sheet'
    : progress?.unitKind === 'SECTION' ? 'section'
    : progress?.unitKind === 'IMAGE' ? 'image'
    : progress?.unitKind === 'LINE' ? 'line' : 'unit';
  $: knownTotal = progress?.totalUnits !== null && progress?.totalUnits !== undefined && progress.totalUnits > 0;
  $: label = progress ? `${progress.processedUnits}${knownTotal ? ` of ${progress.totalUnits}` : ''} ${unit}${(knownTotal ? progress.totalUnits : progress.processedUnits) === 1 ? '' : 's'} processed` : '';
</script>

{#if progress}
  <div class="unit-progress">
    <p>{label}</p>
    {#if knownTotal}
      <progress value={progress.processedUnits} max={progress.totalUnits} aria-label={unit === 'page' ? 'Page progress' : 'Reading progress'}></progress>
    {:else}
      <progress aria-label={unit === 'page' ? 'Page progress' : 'Reading progress'}></progress>
    {/if}
  </div>
{/if}

<style>
  .unit-progress { display: grid; gap: 0.35rem; }
  p { margin: 0; }
  progress { width: 100%; height: 0.6rem; accent-color: #c4a77d; }
</style>
