<script lang="ts">
  import { onDestroy, onMount } from 'svelte';
  import { documentPageReviewImageUrl, getDocumentPageReview, getRestoreOperation, restoreRevision, SOURCE_PAGE_CHARS, type PageReviewResponse, type RestoreOperation, type StructureView } from './api';
  import { newRequestId } from './ocrRescan';
  import { diffText } from './textDiff';
  import SelectablePdfActions from './SelectablePdfActions.svelte';
  import StructuredPage from './StructuredPage.svelte';

  export let collectionId: string;
  export let documentId: string;
  export let initialOrdinal = 0;
  export let revisionId: string | undefined = undefined;
  /** A newly published revision observed by the parent; the reader loads it at once and asks to approve or reject it. */
  export let publishedRevisionId: string | null | undefined = undefined;
  /** Incremented by the enclosing workspace when its own narrow Back button hides this pane. */
  export let clearMarksSignal = 0;
  export let historical = false;
  export let savedExcerpt: string | null = null;
  /** A hidden source pane keeps the selectable-PDF status it has but stops polling it. */
  export let visible = true;
  export let narrow = false;
  export let onBack: () => void;
  /** Sends a zero-based explicit page selection to the shared, confirmed rescan flow. */
  export let onScanPages: ((ordinals: number[]) => void) | undefined = undefined;
  export let pollMillis = 1000;

  let ordinal = initialOrdinal;
  let page: PageReviewResponse | null = null;
  let text = '';
  /** The fetched windows of the page text, in order; a window with structure is drawn by StructuredPage. */
  let windows: { text: string; structure: StructureView | null }[] = [];
  let loadingText = false;
  let textError: string | null = null;
  let imageError: string | null = null;
  let imageLoading = false;
  let imageRetry = 0;
  let requestGeneration = 0;
  let pinnedRevision = revisionId;
  /**
   * The revision the selectable-PDF actions describe. A historical review names it at once; a live review
   * learns it from its first page answer. Undefined keeps the actions out until then. An excerpt-only view
   * still shows them, as unavailable, because it names no revision to act on.
   */
  let pdfRevision: string | null | undefined = revisionId;
  let split = 50;
  let zoom = 1;
  let activePane: 'image' | 'text' = 'image';
  let markedOrdinals = new Set<number>();
  let marksDocumentId = documentId;
  let observedClearMarksSignal = clearMarksSignal;
  let divider: HTMLElement;
  let resizeObserver: ResizeObserver | null = null;
  /** The last publication the parent reported; each new one is loaded once. */
  let handledPublication = publishedRevisionId;
  /** The new reading on show and the text the person saw before it, which Reject brings back. */
  let pendingReading: { previous: string; current: string } | null = null;
  let rejectRequest: { key: string; requestId: string } | null = null;
  let rejecting = false;
  let readingError: string | null = null;
  let readingNotice: string | null = null;
  let restoreTimer: ReturnType<typeof setTimeout> | null = null;
  let destroyed = false;
  /** The same page in the text shown before the new reading, fetched while the new reading awaits a choice. */
  let comparison: { ordinal: number; revision: string; text: string; score: number | null; truncated: boolean; loaded: boolean } | null = null;
  let comparisonError: string | null = null;
  let comparisonGeneration = 0;
  let textView: 'new' | 'previous' | 'differences' = 'new';

  $: if (pendingReading === null) {
    comparison = null;
    comparisonError = null;
    textView = 'new';
  } else if (page !== null && page.revisionId === pendingReading.current &&
      (comparison?.ordinal !== page.ordinal || comparison.revision !== pendingReading.previous)) {
    void loadComparison(page.ordinal, pendingReading.previous);
  }
  $: comparing = pendingReading !== null && comparison !== null && comparison.loaded && comparison.ordinal === page?.ordinal;
  $: differences = comparing && textView === 'differences' ? diffText(comparison!.text, text) : null;
  $: imageUrl = page == null ? null : documentPageReviewImageUrl(collectionId, page);
  $: imageIdentity = page == null ? null : `${requestGeneration}:${page.documentId}:${page.revisionId ?? 'unpublished'}:${page.ordinal}:${page.imageKind}:${imageRetry}`;
  $: pageLabel = page?.documentPages == null
    ? `Page ${ordinal + 1} (total unavailable)`
    : `Page ${ordinal + 1} of ${page.documentPages}`;
  $: imageLabel = page?.imageKind === 'ocr' ? `OCR page image, page ${ordinal + 1}` : `Original page image, page ${ordinal + 1}`;
  $: if (!historical && savedExcerpt === null && publishedRevisionId != null && publishedRevisionId !== handledPublication) {
    void showNewReading(publishedRevisionId);
  }
  $: if (marksDocumentId !== documentId) {
    marksDocumentId = documentId;
    markedOrdinals = new Set();
  }
  $: if (observedClearMarksSignal !== clearMarksSignal) {
    observedClearMarksSignal = clearMarksSignal;
    markedOrdinals = new Set();
  }
  $: if (historical && markedOrdinals.size > 0) markedOrdinals = new Set();

  onMount(() => {
    if (typeof ResizeObserver !== 'undefined' && divider !== undefined) {
      resizeObserver = new ResizeObserver((entries) => {
        const width = entries[0]?.contentRect.width ?? window.innerWidth;
        if (width < 720) narrow = true;
      });
      resizeObserver.observe(divider.parentElement ?? divider);
    }
    if (savedExcerpt === null) void loadPage(ordinal, pinnedRevision);
  });
  onDestroy(() => {
    destroyed = true;
    resizeObserver?.disconnect();
    if (restoreTimer !== null) clearTimeout(restoreTimer);
  });

  async function loadPage(nextOrdinal: number, exactRevision = pinnedRevision): Promise<void> {
    const generation = ++requestGeneration;
    ordinal = nextOrdinal;
    page = null;
    text = '';
    windows = [];
    textError = null;
    imageError = null;
    imageLoading = false;
    loadingText = true;
    try {
      const result = await getDocumentPageReview(collectionId, documentId, {
        ordinal: nextOrdinal,
        ...(exactRevision === undefined ? {} : { revision: exactRevision }),
        limit: SOURCE_PAGE_CHARS,
      });
      if (generation !== requestGeneration) return;
      page = result;
      text = result.text;
      windows = [windowOf(result)];
      pdfRevision = result.revisionId;
      // The first live response selects one immutable revision for every later page in this review.
      if (exactRevision === undefined && result.revisionId !== null) pinnedRevision = result.revisionId;
      else if (exactRevision !== undefined) pinnedRevision = exactRevision;
      imageLoading = result.imageKind !== 'none';
    } catch (failure) {
      if (generation === requestGeneration) textError = messageOf(failure);
    } finally {
      if (generation === requestGeneration) loadingText = false;
    }
  }

  async function loadMore(): Promise<void> {
    if (page === null || loadingText || !page.truncated) return;
    const generation = requestGeneration;
    loadingText = true;
    textError = null;
    try {
      const result = await getDocumentPageReview(collectionId, documentId, {
        ordinal,
        ...(page.revisionId === null ? {} : { revision: page.revisionId }),
        offset: page.offset + page.text.length,
        limit: SOURCE_PAGE_CHARS,
      });
      if (generation !== requestGeneration) return;
      page = result;
      text += result.text;
      windows = [...windows, windowOf(result)];
    } catch (failure) {
      if (generation === requestGeneration) textError = messageOf(failure);
    } finally {
      if (generation === requestGeneration) loadingText = false;
    }
  }

  async function retryText(): Promise<void> {
    if (page === null || loadingText) return;
    const generation = requestGeneration;
    loadingText = true;
    textError = null;
    try {
      const result = await getDocumentPageReview(collectionId, documentId, {
        ordinal,
        ...(page.revisionId === null ? {} : { revision: page.revisionId }),
        limit: SOURCE_PAGE_CHARS,
      });
      if (generation !== requestGeneration) return;
      page = result;
      text = result.text;
      windows = [windowOf(result)];
    } catch (failure) {
      if (generation === requestGeneration) textError = messageOf(failure);
    } finally {
      if (generation === requestGeneration) loadingText = false;
    }
  }

  /** A finished reading replaces the text on show at once; the text seen before it stays one Reject away. */
  async function showNewReading(target: string): Promise<void> {
    handledPublication = target;
    const previous = pendingReading?.previous ?? pinnedRevision;
    readingError = null;
    readingNotice = null;
    pendingReading = previous === undefined || previous === target ? null : { previous, current: target };
    await loadPage(ordinal, target);
  }

  async function loadComparison(pageOrdinal: number, revision: string): Promise<void> {
    const mine = ++comparisonGeneration;
    comparison = { ordinal: pageOrdinal, revision, text: '', score: null, truncated: false, loaded: false };
    comparisonError = null;
    try {
      const earlier = await getDocumentPageReview(collectionId, documentId, { ordinal: pageOrdinal, revision, limit: SOURCE_PAGE_CHARS });
      if (mine !== comparisonGeneration || destroyed) return;
      comparison = { ordinal: pageOrdinal, revision, text: earlier.text, score: earlier.qualityScore, truncated: earlier.truncated, loaded: true };
    } catch (failure) {
      if (mine === comparisonGeneration && !destroyed) comparisonError = `The previous text of this page could not be loaded: ${messageOf(failure)}`;
    }
  }

  function qualityComparison(before: number | null, now: number | null): string {
    const shown = (score: number | null) => score === null ? 'unknown' : `${Math.round(score)}%`;
    const change = before === null || now === null ? '' : ` (${Math.round(now) - Math.round(before) >= 0 ? '+' : ''}${Math.round(now) - Math.round(before)})`;
    return `Text quality: ${shown(before)} before, ${shown(now)} now${change}`;
  }

  function approveReading(): void {
    pendingReading = null;
    readingError = null;
    readingNotice = 'The new reading is kept.';
  }

  /** Reject restores the earlier text as a new version; the rejected reading stays in history. */
  async function rejectReading(): Promise<void> {
    const reading = pendingReading;
    if (reading === null || rejecting) return;
    rejecting = true;
    readingError = null;
    const key = `${reading.current}|${reading.previous}`;
    // An identical retry after an unanswered request is the same request, so it cannot restore twice.
    if (rejectRequest === null || rejectRequest.key !== key) rejectRequest = { key, requestId: newRequestId() };
    try {
      const operation = await restoreRevision(collectionId, documentId, rejectRequest.requestId, reading.current, reading.previous);
      if (destroyed) return;
      rejectRequest = null;
      await settleReject(operation);
    } catch (failure) {
      if (destroyed) return;
      const status = (failure as { status?: number | null } | null)?.status ?? null;
      const code = (failure as { code?: string } | null)?.code ?? '';
      if (status !== null) rejectRequest = null;
      readingError = code === 'STALE_DOCUMENT_REVISION'
        ? 'The document\'s text changed again, so nothing was rejected. Use the document history to choose a version.'
        : `The previous text could not be restored: ${messageOf(failure)}`;
      rejecting = false;
    }
  }

  async function settleReject(operation: RestoreOperation): Promise<void> {
    if (operation.phase === 'STAGED') {
      restoreTimer = setTimeout(() => void pollReject(operation.restoreId), pollMillis);
      return;
    }
    if (operation.phase === 'PUBLISHED') {
      pendingReading = null;
      rejecting = false;
      readingNotice = 'The new reading was rejected. The previous text is back; the rejected reading stays in history.';
      await loadPage(ordinal, operation.newRevisionId);
      return;
    }
    readingError = `The previous text could not be restored. ${operation.errorMessage ?? 'The new reading is still the document\'s text.'}`;
    rejecting = false;
  }

  async function pollReject(restoreId: string): Promise<void> {
    restoreTimer = null;
    if (destroyed) return;
    try {
      await settleReject(await getRestoreOperation(collectionId, documentId, restoreId));
    } catch (failure) {
      if (destroyed) return;
      readingError = `The restore could not be refreshed: ${messageOf(failure)}`;
      restoreTimer = setTimeout(() => void pollReject(restoreId), pollMillis);
    }
  }

  function retryImage(): void {
    imageError = null;
    imageLoading = true;
    imageRetry += 1;
  }

  function imageLoaded(identity: string | null): void {
    if (identity === null || identity !== imageIdentity) return;
    imageLoading = false;
  }

  function imageFailed(identity: string | null): void {
    if (identity === null || identity !== imageIdentity) return;
    imageLoading = false;
    imageError = 'This page image is unavailable.';
  }

  function emitScanPages(ordinals: number[]): void {
    if (!historical) onScanPages?.(ordinals);
  }

  function leaveReview(): void {
    markedOrdinals = new Set();
    onBack();
  }

  function toggleCurrentMark(): void {
    const next = new Set(markedOrdinals);
    if (next.has(ordinal)) next.delete(ordinal);
    else next.add(ordinal);
    markedOrdinals = next;
  }

  function clearMarks(): void {
    markedOrdinals = new Set();
  }

  function adjustZoom(delta: number): void {
    zoom = Math.max(0.5, Math.min(3, Math.round((zoom + delta) * 100) / 100));
  }

  function onDividerKeydown(event: KeyboardEvent): void {
    if (event.key === 'ArrowLeft' || event.key === 'ArrowDown') split = Math.max(25, split - 5);
    else if (event.key === 'ArrowRight' || event.key === 'ArrowUp') split = Math.min(75, split + 5);
    else if (event.key === 'Home') split = 25;
    else if (event.key === 'End') split = 75;
    else return;
    event.preventDefault();
  }

  function startDividerDrag(event: PointerEvent): void {
    const host = divider.parentElement;
    if (!host) return;
    divider.setPointerCapture?.(event.pointerId);
    const move = (pointer: PointerEvent) => {
      const bounds = host.getBoundingClientRect();
      if (bounds.width > 0) split = Math.max(25, Math.min(75, ((pointer.clientX - bounds.left) / bounds.width) * 100));
    };
    const finish = () => {
      window.removeEventListener('pointermove', move);
      window.removeEventListener('pointerup', finish);
      window.removeEventListener('pointercancel', finish);
    };
    window.addEventListener('pointermove', move);
    window.addEventListener('pointerup', finish, { once: true });
    window.addEventListener('pointercancel', finish, { once: true });
  }

  function windowOf(result: PageReviewResponse): { text: string; structure: StructureView | null } {
    return { text: result.text, structure: result.structure ?? null };
  }

  function messageOf(failure: unknown): string {
    return failure instanceof Error ? failure.message : 'The page could not be loaded.';
  }
</script>

<section class="page-review" aria-label="Page review">
  <header class="review-toolbar">
    <button type="button" aria-label="Back to documents" onclick={leaveReview}>Back</button>
    <a class="original-link" href={`/api/collections/${encodeURIComponent(collectionId)}/documents/${encodeURIComponent(documentId)}/original`} target="_blank" rel="noopener noreferrer">Open original</a>
    {#if savedExcerpt !== null || pdfRevision !== undefined}
      <SelectablePdfActions {collectionId} {documentId} revisionId={savedExcerpt !== null ? null : pdfRevision} visible={visible} />
    {/if}
    <nav class="page-navigation" aria-label="Document pages">
      <button type="button" aria-label="Previous page" disabled={page?.previousOrdinal == null || loadingText} onclick={() => page?.previousOrdinal != null && loadPage(page.previousOrdinal)}>
        Previous
      </button>
      <strong role="status" aria-live="polite">{pageLabel}</strong>
      <button type="button" aria-label="Next page" disabled={page?.nextOrdinal == null || loadingText} onclick={() => page?.nextOrdinal != null && loadPage(page.nextOrdinal)}>
        Next
      </button>
    </nav>
    <div class="image-controls" aria-label="Image controls">
      <button type="button" aria-label="Zoom out" disabled={zoom <= 0.5} onclick={() => adjustZoom(-0.25)}>−</button>
      <button type="button" aria-label="Fit image" onclick={() => (zoom = 1)}>Fit</button>
      <button type="button" aria-label="Zoom in" disabled={zoom >= 3} onclick={() => adjustZoom(0.25)}>+</button>
      {#if !historical && onScanPages !== undefined}
        <button type="button" aria-label={`Mark page ${ordinal + 1}`} aria-pressed={markedOrdinals.has(ordinal)} onclick={toggleCurrentMark}>
          {markedOrdinals.has(ordinal) ? `Unmark page ${ordinal + 1}` : `Mark page ${ordinal + 1}`}
        </button>
      {/if}
    </div>
  </header>

  {#if pendingReading !== null || readingNotice !== null || readingError !== null}
    <div class="new-reading" role="status" aria-label="New reading">
      {#if pendingReading !== null}
        <p>The text below is the new reading. Keep it, or reject it to bring back the previous text.</p>
        <div class="selection-actions">
          <button type="button" class="primary" onclick={approveReading} disabled={rejecting}>Approve</button>
          <button type="button" onclick={() => void rejectReading()} disabled={rejecting}>{rejecting ? 'Rejecting…' : 'Reject'}</button>
        </div>
      {:else if readingNotice !== null}
        <p>{readingNotice}</p>
      {/if}
      {#if readingError !== null}<p role="alert">{readingError}</p>{/if}
    </div>
  {/if}

  {#if !historical && onScanPages !== undefined}
    <div class="page-selection" aria-label="Selected pages">
      <p>{markedOrdinals.size === 0 ? 'No pages selected' : `Selected pages: ${[...markedOrdinals].sort((a, b) => a - b).map((pageOrdinal) => pageOrdinal + 1).join(', ')}`}</p>
      <div class="selection-actions">
        <button type="button" onclick={clearMarks} disabled={markedOrdinals.size === 0}>Clear selection</button>
        <button type="button" onclick={() => emitScanPages([...markedOrdinals].sort((a, b) => a - b))} disabled={markedOrdinals.size === 0}>Scan selected pages</button>
        <button type="button" onclick={() => emitScanPages([ordinal])}>Scan this page again</button>
      </div>
    </div>
  {/if}

  {#if savedExcerpt !== null}
    <p class="notice" role="note">Revision unknown. This citation excerpt is shown without pairing it with a current page image.</p>
    <pre class="excerpt">{savedExcerpt}</pre>
  {:else}
    {#if narrow}
      <div class="narrow-tabs" role="tablist" aria-label="Page view">
        <button type="button" role="tab" aria-selected={activePane === 'image'} onclick={() => (activePane = 'image')}>Image</button>
        <button type="button" role="tab" aria-selected={activePane === 'text'} onclick={() => (activePane = 'text')}>Text</button>
      </div>
    {/if}

    <div class="comparison" class:narrow style={`--image-width: ${split}%; height: min(70vh, 54rem); min-height: ${narrow ? '18rem' : '0'}; grid-template-rows: minmax(0, 1fr);`}>
      <section class="image-pane" aria-label="Page image" hidden={narrow && activePane !== 'image'} style={narrow ? 'height: 100%; min-height: 0;' : undefined}>
        <div class="pane-heading"><h3>{page?.imageKind === 'ocr' ? 'OCR image' : page?.imageKind === 'original' ? 'Original page' : 'Page image'}</h3></div>
        {#if loadingText && page === null}
          <p role="status">Loading page image…</p>
        {:else if page?.imageKind === 'none'}
          <p role="status">No page image is available for this page.</p>
        {:else if imageError !== null}
          <p role="alert">{imageError}</p><button type="button" onclick={retryImage}>Retry image</button>
        {:else if imageUrl !== null}
          <div class="image-scroll" style="min-height: 0; overflow-y: auto;">
            {#if imageLoading}<p class="image-loading" role="status">Loading image…</p>{/if}
            {#key imageIdentity}
              {@const eventIdentity = imageIdentity}
              <img
                src={`${imageUrl}${imageRetry ? `&retry=${imageRetry}` : ''}`}
                alt={imageLabel}
                style={`transform: scale(${zoom});`}
                onload={() => imageLoaded(eventIdentity)}
                onerror={() => imageFailed(eventIdentity)}
              />
            {/key}
          </div>
        {:else}<p role="status">Page image unavailable.</p>{/if}
      </section>

      {#if !narrow}
        <!-- svelte-ignore a11y_no_noninteractive_tabindex a11y_no_noninteractive_element_interactions -->
        <div
          class="divider"
          role="separator"
          aria-label="Resize page panes"
          aria-orientation="vertical"
          aria-valuemin="25"
          aria-valuemax="75"
          aria-valuenow={Math.round(split)}
          tabindex="0"
          bind:this={divider}
          onkeydown={onDividerKeydown}
          onpointerdown={startDividerDrag}
        ></div>
      {/if}

      <section class="text-pane" aria-label="Extracted text" hidden={narrow && activePane !== 'text'} style={narrow ? 'height: 100%; min-height: 0;' : undefined}>
        <div class="pane-heading"><h3>Extracted text</h3>
          {#if page?.readingMethod}<span class="method">{page.readingMethod}</span>{/if}
        </div>
        {#if loadingText && page === null}
          <p role="status">Loading page text…</p>
        {:else if textError !== null}
          <p role="alert">{textError}</p><button type="button" disabled={loadingText} onclick={retryText}>Retry text</button>
        {:else if page !== null}
          {#if comparing}
            <p class="quality" data-testid="quality-comparison">{qualityComparison(comparison!.score, page.qualityScore)}</p>
            <div class="compare" role="group" aria-label="Compare readings">
              <button type="button" aria-pressed={textView === 'new'} onclick={() => (textView = 'new')}>New</button>
              <button type="button" aria-pressed={textView === 'previous'} onclick={() => (textView = 'previous')}>Previous</button>
              <button type="button" aria-pressed={textView === 'differences'} onclick={() => (textView = 'differences')}>Differences</button>
            </div>
            {#if (page.truncated || comparison!.truncated) && textView !== 'new'}<p class="notice">Only the first part of this page is compared.</p>{/if}
          {:else if page.qualityScore !== null}<p class="quality">Text quality: {Math.round(page.qualityScore)}%</p>{/if}
          {#if comparisonError !== null && pendingReading !== null}<p role="alert">{comparisonError}</p>{/if}
          {#if page.structureDegraded}<p class="notice">Layout could not be fully recognised on this page; text is shown as paragraphs.</p>{/if}
          {#if comparing && textView === 'previous'}
            {#if comparison!.text.length === 0}<p class="empty">The previous reading had no text for this page.</p>
            {:else}<pre class="review-text" style="min-height: 0; max-height: none; overflow-y: auto;">{comparison!.text}</pre>{/if}
          {:else if comparing && textView === 'differences'}
            {#if differences === null}<p class="notice">This page is too long to compare here.</p>
            {:else}<pre class="review-text diff" style="min-height: 0; max-height: none; overflow-y: auto;">{#each differences as part}{#if part.kind === 'added'}<ins>{part.text}</ins>{:else if part.kind === 'removed'}<del>{part.text}</del>{:else}{part.text}{/if}{/each}</pre>{/if}
          {:else if text.length === 0}<p class="empty">No extracted text for this page.</p>
          {:else if windows.some((window) => window.structure !== null)}
            <div class="review-structure">
              {#each windows as window}
                {#if window.structure !== null}<StructuredPage text={window.text} structure={window.structure} />
                {:else}<pre class="review-text">{window.text}</pre>{/if}
              {/each}
            </div>
          {:else}<pre class="review-text" style="min-height: 0; max-height: none; overflow-y: auto;">{text}</pre>{/if}
          {#if textError !== null}<p role="alert">{textError}</p>{/if}
          {#if page.truncated}<button type="button" onclick={loadMore} disabled={loadingText}>{loadingText ? 'Loading…' : 'Load more'}</button>{/if}
        {/if}
      </section>
    </div>
  {/if}
</section>

<style>
  .page-review { display: flex; flex-direction: column; min-width: 0; color: #e8e9e7; }
  .review-toolbar { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: .75rem; margin-bottom: .8rem; }
  .page-selection { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: .5rem; margin: 0 0 .8rem; color: #a6adaa; }
  .page-selection p { margin: 0; }
  .selection-actions { display: flex; flex-wrap: wrap; gap: .5rem; }
  .new-reading { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: .5rem; margin: 0 0 .8rem; border: 1px solid #c4a77d; border-radius: .55rem; padding: .6rem .75rem; color: #d6d0c2; }
  .new-reading p { margin: 0; }
  .compare { display: flex; gap: .35rem; margin: 0 0 .5rem; }
  .compare button[aria-pressed='true'] { border-color: #c4a77d; background: #3a3326; color: #f0e2c8; }
  .diff ins { background: #1f4a2e; color: #c9f2d4; text-decoration: none; }
  .diff del { background: #5a2424; color: #f5c9c9; }
  .new-reading button.primary { border-color: #c4a77d; background: #c4a77d; color: #1c1b18; font-weight: 650; }
  .page-navigation, .image-controls, .narrow-tabs { display: flex; align-items: center; gap: .5rem; }
  .page-navigation strong { min-width: 9rem; text-align: center; }
  .comparison { display: grid; grid-template-columns: minmax(0, var(--image-width)) .65rem minmax(0, calc(100% - var(--image-width) - .65rem)); grid-template-rows: minmax(0, 1fr); height: min(70vh, 54rem); min-height: 18rem; border: 1px solid #343939; border-radius: .55rem; overflow: hidden; }
  .comparison.narrow { display: block; height: min(65vh, 48rem); min-height: 18rem; }
  .image-pane, .text-pane { display: flex; flex-direction: column; min-width: 0; min-height: 0; overflow: hidden; background: #171a1b; }
  .image-pane[hidden], .text-pane[hidden] { display: none; }
  .text-pane { border-left: 1px solid #343939; }
  .pane-heading { display: flex; flex: 0 0 auto; align-items: baseline; justify-content: space-between; gap: .7rem; padding: .65rem .8rem; border-bottom: 1px solid #343939; }
  h3 { margin: 0; font-size: .95rem; }
  .method, .quality, .notice { color: #a6adaa; font-size: .82rem; }
  .image-scroll { flex: 1 1 0; min-height: 0; overflow: auto; display: grid; place-items: center; position: relative; }
  .image-scroll img { max-width: 100%; max-height: 100%; object-fit: contain; transform-origin: center center; }
  .image-loading { position: absolute; inset: .5rem auto auto .75rem; z-index: 1; }
  .review-text, .excerpt { white-space: pre-wrap; overflow-wrap: anywhere; font: inherit; margin: 0; padding: .85rem; overflow: auto; }
  .review-text { flex: 1 1 0; min-height: 0; }
  .review-structure { flex: 1 1 0; min-height: 0; overflow-y: auto; padding: .85rem; }
  .empty { padding: .85rem; color: #9da4a1; }
  .divider { cursor: col-resize; touch-action: none; background: #303535; }
  .divider:hover, .divider:focus { background: #c4a77d; }
  .narrow-tabs { margin: .5rem 0; }
  .narrow-tabs [aria-selected='true'] { border-color: #c4a77d; }
  @media (max-width: 700px) { .comparison:not(.narrow) { display: block; max-height: none; } }
</style>
