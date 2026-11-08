<script lang="ts">
  import { imageAltText, imageThumbnailUrl, type ImageView } from './api';

  export let collectionId: string;
  export let documentId: string;
  export let images: ImageView[];
  /** The revision the images belong to; an older reading's thumbnails are that reading's, not the current one's. */
  export let revisionId: string | null = null;
  export let onOpen: (image: ImageView, opener: HTMLElement | null) => void;

  function isAnalysed(image: ImageView): boolean {
    const hasDescription = typeof image.description === 'string' && image.description.trim() !== '';
    const hasTags = Array.isArray(image.tags) && image.tags.length > 0;
    return hasDescription || hasTags;
  }

  function hasFailure(image: ImageView): boolean {
    return Boolean(image.analysisErrorCode);
  }

  $: analysedCount = images.filter(isAnalysed).length;

  function open(event: Event, image: ImageView): void {
    onOpen(image, event.currentTarget instanceof HTMLElement ? event.currentTarget : null);
  }
  function openOnEnter(event: KeyboardEvent, image: ImageView): void {
    if (event.key !== 'Enter') return;
    // The key opens the image once; the browser's own click for Enter is suppressed so it is not opened twice.
    event.preventDefault();
    open(event, image);
  }
</script>

{#if images.length > 0}
  {#if analysedCount > 0}
    <p class="strip-meta" data-testid="image-analysed-count">
      {analysedCount} of {images.length} {images.length === 1 ? 'image' : 'images'} described
    </p>
  {/if}
  <ul class="image-strip" aria-label="Images on this page">
    {#each images as image (image.unitId)}
      <li>
        <button type="button" onclick={(event) => open(event, image)} onkeydown={(event) => openOnEnter(event, image)} title={imageAltText(image)}>
          <img src={imageThumbnailUrl(collectionId, documentId, image.unitId, revisionId)} alt={imageAltText(image)} loading="lazy" />
          {#if hasFailure(image)}
            <span class="failure-mark" data-testid="image-failure-mark" title="Could not be described">!</span>
          {/if}
        </button>
      </li>
    {/each}
  </ul>
{/if}

<style>
  .strip-meta { margin: 0.5rem 0 0.2rem; color: #929997; font-size: 0.82rem; }
  .image-strip { display: flex; flex-wrap: wrap; gap: 0.5rem; margin: 0.6rem 0; padding: 0; list-style: none; }
  button { position: relative; display: block; padding: 0.2rem; border: 1px solid #383d3e; border-radius: 0.4rem; background: #202324; cursor: pointer; }
  img { display: block; max-width: 8rem; max-height: 6rem; object-fit: contain; }
  .failure-mark { position: absolute; top: 0.25rem; right: 0.25rem; display: inline-grid; place-items: center; min-width: 1.1rem; height: 1.1rem; padding: 0 0.25rem; border-radius: 999px; background: #5a2624; color: #f6d5d3; font-size: 0.72rem; font-weight: 700; line-height: 1; }
</style>
