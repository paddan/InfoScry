<script lang="ts">
  import { onDestroy, tick } from 'svelte';
  import { containDialogFocus } from './dialogFocus';
  import {
    ApiError,
    documentPageReviewImageUrl,
    editImageTag,
    getImageTags,
    imageAltText,
    imageFileUrl,
    imageParentPage,
    type ImageTag,
    type ImageTagEditView,
    type ImageView,
    type TagCategory,
    type TagEditAction,
  } from './api';

  export let collectionId: string;
  export let documentId: string;
  export let image: ImageView;
  export let open: boolean;
  /** The revision the image belongs to, so the page behind a region is the same reading's page. */
  export let revisionId: string | null = null;
  /** Whether the signed-in user may confirm, remove or add tags in this collection; readers see tags only. */
  export let canEdit = true;
  export let onClose: () => void;
  export let onTagsChanged: ((updated: { description: string | null; tags: ImageTag[]; tagsVersion: number }) => void) | undefined = undefined;

  const CATEGORY_GROUPS: { category: TagCategory; heading: string; optionLabel: string }[] = [
    { category: 'PERSON', heading: 'People', optionLabel: 'Person' },
    { category: 'OBJECT', heading: 'Objects', optionLabel: 'Object' },
    { category: 'ANIMAL', heading: 'Animals', optionLabel: 'Animal' },
    { category: 'PLACE', heading: 'Places', optionLabel: 'Place' },
    { category: 'TEXT', heading: 'Text', optionLabel: 'Text' },
  ];

  let dialogElement: HTMLDivElement | null = null;
  let heading: HTMLHeadingElement | null = null;
  let previousFocus: HTMLElement | null = null;
  let wasOpen = false;
  let boundKey = '';
  let loadGeneration = 0;

  let description: string | null = image.description ?? null;
  let tags: ImageTag[] = image.tags ?? [];
  let edits: ImageTagEditView[] = [];
  let tagsVersion: number = image.tagsVersion ?? 0;
  let busy = false;
  let editError: string | null = null;
  let addingTag = false;
  let newCategory: TagCategory = 'PERSON';
  let newLabel = '';
  let newName = '';

  $: page = imageParentPage(image);
  $: box = image.locator.box;
  // The page behind a region is shown with the box outlined; the original page image is the one the box was found on.
  $: pageUrl = box !== null && page !== null
    ? documentPageReviewImageUrl(collectionId, { documentId, revisionId, ordinal: page - 1, imageKind: 'original' })
    : null;
  $: alt = imageAltText(image);
  $: title = image.label.trim() === '' ? `Image ${image.locator.index}` : image.label.charAt(0).toUpperCase() + image.label.slice(1);
  $: groupedTags = CATEGORY_GROUPS
    .map((group) => ({ ...group, items: tags.filter((tag) => tag.category === group.category) }))
    .filter((group) => group.items.length > 0);
  $: currentKey = `${collectionId}\u0000${documentId}\u0000${image.unitId}\u0000${open ? '1' : '0'}`;
  $: if (currentKey !== boundKey) syncImage(currentKey);

  $: if (open && !wasOpen) {
    wasOpen = true;
    previousFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    void tick().then(() => heading?.focus());
  } else if (!open && wasOpen) {
    wasOpen = false;
    restoreFocus();
  }

  function syncImage(nextKey: string): void {
    boundKey = nextKey;
    description = image.description ?? null;
    tags = image.tags ?? [];
    edits = [];
    tagsVersion = image.tagsVersion ?? 0;
    editError = null;
    addingTag = false;
    newCategory = 'PERSON';
    newLabel = '';
    newName = '';
    if (open) void loadTags(++loadGeneration);
  }

  async function loadTags(mine: number): Promise<void> {
    try {
      const view = await getImageTags(collectionId, documentId, image.unitId);
      if (mine !== loadGeneration || !open) return;
      description = view.description ?? description;
      tags = view.tags;
      edits = view.edits;
      tagsVersion = view.tagsVersion;
    } catch {
      // Keep the description and tags already carried by the ImageView if the detail read fails.
    }
  }

  function newEditId(): string {
    return typeof crypto !== 'undefined' && 'randomUUID' in crypto
      ? crypto.randomUUID()
      : `${Date.now()}-${Math.random().toString(36).slice(2)}`;
  }

  function tagText(tag: ImageTag): string {
    const name = tag.name?.trim();
    if (!name) return tag.label;
    return tag.source === 'APPEARANCE' ? `${tag.label}: possibly ${name}` : `${tag.label}: ${name}`;
  }

  function authorName(edit: ImageTagEditView): string | null {
    const who = edit.createdBy;
    if (!who) return null;
    return (who.displayName ?? who.name ?? who.id).trim() || null;
  }

  function editSummary(edit: ImageTagEditView): string {
    const who = authorName(edit) ?? 'Someone';
    const verb = edit.action === 'CONFIRM' ? 'Confirmed' : edit.action === 'REMOVE' ? 'Removed' : 'Added';
    const name = edit.tag.name?.trim();
    const target = name ? `${edit.tag.label}: ${name}` : edit.tag.label;
    return `${verb} by ${who} (${target})`;
  }

  async function submitEdit(action: TagEditAction, tag: ImageTag): Promise<boolean> {
    if (busy || !canEdit) return false;
    busy = true;
    editError = null;
    const editId = newEditId();
    try {
      const updated = await editImageTag(
        collectionId,
        documentId,
        image.unitId,
        { editId, action, tag },
        tagsVersion,
      );
      description = updated.description ?? description;
      tags = updated.tags;
      edits = updated.edits;
      tagsVersion = updated.tagsVersion;
      onTagsChanged?.({ description, tags, tagsVersion });
      return true;
    } catch (failure) {
      if (failure instanceof ApiError && (failure.code === 'EDIT_CONFLICT' || failure.status === 409)) {
        editError = failure.message || 'Someone else changed these tags while you were viewing them; reload and try again.';
      } else if (failure instanceof ApiError) {
        editError = failure.message;
      } else if (failure instanceof Error) {
        editError = failure.message;
      } else {
        editError = 'Something went wrong.';
      }
      return false;
    } finally {
      busy = false;
    }
  }

  async function confirmTag(tag: ImageTag): Promise<void> {
    await submitEdit('CONFIRM', { ...tag, source: 'MANUAL' });
  }

  async function removeTag(tag: ImageTag): Promise<void> {
    await submitEdit('REMOVE', tag);
  }

  async function submitAddTag(event: SubmitEvent): Promise<void> {
    event.preventDefault();
    const label = newLabel.trim();
    const name = newName.trim();
    if (label === '' || busy) return;
    const tag: ImageTag = {
      category: newCategory,
      label,
      ...(name === '' ? {} : { name }),
      source: 'MANUAL',
    };
    const ok = await submitEdit('ADD', tag);
    if (ok) {
      newLabel = '';
      newName = '';
      addingTag = false;
    }
  }

  function restoreFocus(): void {
    if (previousFocus?.isConnected) previousFocus.focus();
    previousFocus = null;
  }

  onDestroy(() => {
    loadGeneration += 1;
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
        {#if description}<p class="description" data-testid="image-description">{description}</p>{/if}
        <img class="full" src={imageFileUrl(collectionId, documentId, image.unitId, revisionId)} {alt} width={image.widthPx} height={image.heightPx} />
        {#if groupedTags.length > 0 || canEdit || edits.length > 0 || editError !== null}
          <section class="tags-section" aria-label="Picture tags">
            {#each groupedTags as group (group.category)}
              <div class="tag-group">
                <h3>{group.heading}</h3>
                <ul class="tag-list">
                  {#each group.items as tag (`${tag.category}:${tag.label}:${tag.name ?? ''}:${tag.source}`)}
                    <li class="tag-item">
                      <span class="tag-label">{tagText(tag)}</span>
                      {#if canEdit}
                        <span class="tag-actions">
                          {#if tag.source === 'APPEARANCE'}
                            <button type="button" onclick={() => void confirmTag(tag)} disabled={busy}>Confirm</button>
                          {/if}
                          <button type="button" onclick={() => void removeTag(tag)} disabled={busy}>Remove</button>
                        </span>
                      {/if}
                    </li>
                  {/each}
                </ul>
              </div>
            {/each}
            {#if edits.length > 0}
              <ul class="edit-history" aria-label="Tag edits">
                {#each edits as edit (edit.editId)}
                  <li class="meta">{editSummary(edit)}</li>
                {/each}
              </ul>
            {/if}
            {#if editError !== null}<p role="alert">{editError}</p>{/if}
            {#if canEdit}
              {#if addingTag}
                <form class="add-tag-form" onsubmit={submitAddTag}>
                  <div class="add-tag-fields">
                    <label>
                      Category
                      <select aria-label="Category" bind:value={newCategory} disabled={busy}>
                        {#each CATEGORY_GROUPS as group (group.category)}
                          <option value={group.category}>{group.optionLabel}</option>
                        {/each}
                      </select>
                    </label>
                    <label>
                      Kind
                      <input aria-label="Kind" bind:value={newLabel} maxlength="120" required disabled={busy} />
                    </label>
                    <label>
                      Name
                      <input aria-label="Name" bind:value={newName} maxlength="120" placeholder="Optional" disabled={busy} />
                    </label>
                  </div>
                  <div class="add-tag-actions">
                    <button type="submit" class="primary" disabled={busy || newLabel.trim() === ''}>Save tag</button>
                    <button type="button" onclick={() => { addingTag = false; newLabel = ''; newName = ''; }} disabled={busy}>Cancel</button>
                  </div>
                </form>
              {:else}
                <div>
                  <button type="button" onclick={() => (addingTag = true)} disabled={busy}>Add tag</button>
                </div>
              {/if}
            {/if}
          </section>
        {/if}
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
  h2, h3, p, figure, ul { margin: 0; }
  h2 { font-size: 1.05rem; }
  h3 { color: #b9bdbc; font-size: 0.78rem; font-weight: 650; text-transform: uppercase; letter-spacing: 0.06em; }
  .meta { color: #b9bdbc; font-size: 0.85rem; }
  .description { color: #e8e9e7; font-size: 0.92rem; }
  .tags-section { display: grid; gap: 0.6rem; padding-top: 0.6rem; border-top: 1px solid #353a3a; }
  .tag-group { display: grid; gap: 0.3rem; }
  .tag-list, .edit-history { list-style: none; padding: 0; display: grid; gap: 0.3rem; }
  .tag-item { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 0.5rem; }
  .tag-actions { display: flex; gap: 0.35rem; }
  .add-tag-form { display: grid; gap: 0.5rem; padding-top: 0.35rem; }
  .add-tag-fields { display: grid; grid-template-columns: repeat(auto-fit, minmax(9rem, 1fr)); gap: 0.5rem; }
  .add-tag-fields label { display: grid; gap: 0.25rem; font-size: 0.82rem; color: #b9bdbc; }
  .add-tag-fields input, .add-tag-fields select { border: 1px solid #383d3e; border-radius: 0.4rem; background: #181b1c; padding: 0.38rem 0.5rem; color: #f3f3f3; font: inherit; }
  .add-tag-actions { display: flex; gap: 0.45rem; }
  img.full { max-width: 100%; height: auto; justify-self: start; }
  .page-frame { position: relative; display: inline-block; max-width: 100%; }
  .page-frame img { display: block; max-width: 100%; height: auto; }
  .outline { position: absolute; box-sizing: border-box; border: 2px solid #c4a77d; outline: 1px solid #101214; pointer-events: none; }
  button { font: inherit; padding: 0.4rem 0.6rem; }
</style>
