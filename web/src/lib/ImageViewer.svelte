<script lang="ts">
  import { onDestroy, tick } from 'svelte';
  import { containDialogFocus } from './dialogFocus';
  import { documentPageReviewImageUrl, imageAltText, imageFileUrl, imageParentPage, type ImageView } from './api';

  export let collectionId: string;
  export let documentId: string;
  export let image: ImageView;
  export let open: boolean;
  /** The revision the image belongs to, so the page behind a region is the same reading's page. */
  export let revisionId: string | null = null;
  export let onClose: () => void;

  let dialogElement: HTMLDivElement | null = null;
  let heading: HTMLHeadingElement | null = null;
  let previousFocus: HTMLElement | null = null;
  let wasOpen = false;

  $: page = imageParentPage(image);
  $: box = image.locator.box;
  // The page behind a region is shown with the box outlined; the original page image is the one the box was found on.
  $: pageUrl = box !== null && page !== null
    ? documentPageReviewImageUrl(collectionId, { documentId, revisionId, ordinal: page - 1, imageKind: 'original' })
    : null;
  $: alt = imageAltText(image);
  $: title = image.label.trim() === '' ? `Image ${image.locator.index}` : image.label.charAt(0).toUpperCase() + image.label.slice(1);

  $: if (open && !wasOpen) {
    wasOpen = true;
    previousFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    void tick().then(() => heading?.focus());
  } else if (!open && wasOpen) {
    wasOpen = false;
    restoreFocus();
  }

  function restoreFocus(): void {
    if (previousFocus?.isConnected) previousFocus.focus();
    previousFocus = null;
  }

  onDestroy(() => {
    if (wasOpen) restoreFocus();
  });

  function percent(value: number): string {
    return `${Number((value * 100).toFixed(2))}%`;
  }

  function keydown(event: KeyboardEvent): void {
    if (event.key === 'Escape') {
      event.stopPropagation();
      event.preventDefault();
      onClose();
      return;
    }
    if (event.key === 'Tab') containDialogFocus(event, dialogElement);
  }
</script>

{#if open}
  <section class="backdrop" role="presentation">
    <div class="dialog" role="dialog" aria-modal="true" aria-labelledby="image-viewer-title" tabindex="-1" data-testid="image-viewer" bind:this={dialogElement} onkeydown={keydown}>
      <div class="content">
        <h2 id="image-viewer-title" tabindex="-1" bind:this={heading}>{title}</h2>
        {#if image.caption}<p class="caption">{image.caption}</p>{/if}
        {#if page !== null}<p class="meta">from page {page}</p>{/if}
        <img class="full" src={imageFileUrl(collectionId, documentId, image.unitId, revisionId)} {alt} width={image.widthPx} height={image.heightPx} />
        {#if pageUrl !== null && box !== null}
          <figure class="page">
            <div class="page-frame">
              <img src={pageUrl} alt={`Page ${page} with the image outlined`} />
              <div
                class="outline"
                data-testid="image-box"
                style:left={percent(box.left)}
                style:top={percent(box.top)}
                style:width={percent(box.right - box.left)}
                style:height={percent(box.bottom - box.top)}
              ></div>
            </div>
          </figure>
        {/if}
      </div>
      <div class="actions"><button type="button" onclick={onClose}>Close</button></div>
    </div>
  </section>
{/if}

<style>
  .backdrop { position: fixed; inset: 0; z-index: 30; display: grid; place-items: center; padding: 1rem; background: #0009; }
  .dialog { display: flex; flex-direction: column; width: min(48rem, 100%); max-height: 92vh; overflow: hidden; border: 1px solid #454a4a; border-radius: 0.6rem; background: #202424; color: #f3f3f3; box-shadow: 0 1rem 3rem #0008; }
  .content { display: grid; gap: 0.7rem; min-height: 0; overflow: auto; padding: 1.2rem; }
  .actions { display: flex; justify-content: end; padding: 0.8rem 1.2rem; border-top: 1px solid #454a4a; }
  h2, p, figure { margin: 0; }
  h2 { font-size: 1.05rem; }
  .meta { color: #b9bdbc; font-size: 0.85rem; }
  img.full { max-width: 100%; height: auto; justify-self: start; }
  .page-frame { position: relative; display: inline-block; max-width: 100%; }
  .page-frame img { display: block; max-width: 100%; height: auto; }
  .outline { position: absolute; box-sizing: border-box; border: 2px solid #c4a77d; outline: 1px solid #101214; pointer-events: none; }
  button { font: inherit; padding: 0.4rem 0.6rem; }
</style>
