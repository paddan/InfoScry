<script lang="ts">
  import { imageAltText, imageThumbnailUrl, type ImageView } from './api';

  export let collectionId: string;
  export let documentId: string;
  export let images: ImageView[];
  export let onOpen: (image: ImageView, opener: HTMLElement | null) => void;

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
  <ul class="image-strip" aria-label="Images on this page">
    {#each images as image (image.unitId)}
      <li>
        <button type="button" onclick={(event) => open(event, image)} onkeydown={(event) => openOnEnter(event, image)} title={imageAltText(image)}>
          <img src={imageThumbnailUrl(collectionId, documentId, image.unitId)} alt={imageAltText(image)} loading="lazy" />
        </button>
      </li>
    {/each}
  </ul>
{/if}

<style>
  .image-strip { display: flex; flex-wrap: wrap; gap: 0.5rem; margin: 0.6rem 0; padding: 0; list-style: none; }
  button { display: block; padding: 0.2rem; border: 1px solid #383d3e; border-radius: 0.4rem; background: #202324; cursor: pointer; }
  img { display: block; max-width: 8rem; max-height: 6rem; object-fit: contain; }
</style>
