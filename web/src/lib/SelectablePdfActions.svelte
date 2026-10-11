<script lang="ts">
  import { onDestroy, onMount, tick } from 'svelte';
  import {
    createSelectablePdf,
    getSelectablePdfStatus,
    selectablePdfFileHref,
    type PdfArtifactStatus,
  } from './api';

  /** One revision's selectable PDF copy, shown beside the source's "Open original" link. */
  export let collectionId: string;
  export let documentId: string;
  /** The exact revision the source reads. Null or empty names none, so nothing is ever dispatched for it. */
  export let revisionId: string | null | undefined = null;
  /** A hidden view keeps its last status but neither polls nor reads again until it is shown. */
  export let visible = true;
  export let pollMillis = 2000;

  /**
   * Plain-English copy for the safe reason codes the backend can name, as a function of how many pages share
   * it: one page reads "this page", several read "these pages". Unknown codes get the generic line.
   */
  const REASON_COPY: Record<string, (count: number) => string> = {
    PDF_TEXT_ALIGNMENT_AMBIGUOUS: (n) => `The recognized text could not be matched to its position on ${n === 1 ? 'this page' : 'these pages'}.`,
    PDF_TEXT_ALIGNMENT_LIMIT_EXCEEDED: (n) => `Positioning ${n === 1 ? 'this page' : 'these pages'} took more work than allowed.`,
    LAYOUT_MISSING: (n) => `The text positions for ${n === 1 ? 'this page are' : 'these pages are'} missing.`,
    LAYOUT_CORRUPT: (n) => `The saved text positions for ${n === 1 ? 'this page are' : 'these pages are'} damaged.`,
    LAYOUT_PROVENANCE_MISMATCH: () => 'The saved text positions do not belong to this reading.',
    READING_IMAGE_MISSING: () => 'The page image needed to place the text is missing.',
    READING_IMAGE_UNREADABLE: () => 'The page image needed to place the text could not be read.',
    READING_IMAGE_MISMATCH: () => 'The page image changed after the text was read.',
    READING_IMAGE_PROVENANCE: () => 'The page image does not belong to this reading.',
    SOURCE_IMAGE_PROVENANCE: () => 'The page image does not belong to this reading.',
    PAGE_GEOMETRY_UNREADABLE: () => 'The page size could not be read.',
    PAGE_GEOMETRY_MISMATCH: () => 'The page size does not match the reading.',
    TRANSFORM_MISMATCH: () => 'The page image and its text positions do not line up.',
    UNSUPPORTED_GLYPH: (n) => `${n === 1 ? 'This page has' : 'These pages have'} characters the PDF font cannot show.`,
    UNAVAILABLE_WITHOUT_IMAGE: (n) => `There is no page image to show for ${n === 1 ? 'this page' : 'these pages'}.`,
    BLANK_NOT_VERIFIED: (n) => `${n === 1 ? 'This page' : 'These pages'} could not be verified as blank.`,
    BLANK_WITHOUT_IMAGE: (n) => `${n === 1 ? 'This page' : 'These pages'} could not be verified as blank.`,
    ARTIFACT_VERIFICATION_FAILED: () => 'The generated PDF did not pass its check.',
    OUTPUT_TEXT: () => 'The generated PDF did not match the published reading.',
    OUTPUT_PAGE_COUNT: () => 'The generated PDF did not match the published reading.',
    OUTPUT_GEOMETRY: () => 'The generated PDF did not match the published reading.',
    PAGE_COUNT_MISMATCH: () => 'The generated PDF did not match the published reading.',
    WRITE_IO_FAILED: () => 'The PDF could not be written to disk.',
    ORIGINAL_MISSING: () => 'The original file could not be used to make the PDF.',
    ORIGINAL_UNREADABLE: () => 'The original file could not be used to make the PDF.',
    UNSUPPORTED_MEDIA_TYPE: () => 'The original file could not be used to make the PDF.',
    REVISION_NOT_PUBLIC: () => 'This revision is not published, so it has no PDF.',
    SELECTABLE_PDF_NOT_READY: () => 'The PDF is not ready yet.',
  };
  const GENERIC_FAILURE = 'The PDF with selectable text did not finish.';
  const OCR_NOTE = 'Pages read by OCR are copied as flat page images with a text layer on top. The original file is not changed.';

  let status: PdfArtifactStatus | null = null;
  /** The identity (collection, document, revision) this instance currently shows; null when it names none. */
  let identity: string | null = null;
  let alive = false;
  let loading = false;
  let loadFailed = false;
  let actionPending = false;
  let actionFailed = false;
  let documentHidden = typeof document !== 'undefined' && document.hidden;
  /** Every in-flight answer and timer belongs to one generation; stopping work moves the generation on. */
  let generation = 0;
  let timer: ReturnType<typeof setTimeout> | null = null;
  let lastVisible = visible;
  let statusLine: HTMLElement | undefined;

  $: effectiveVisible = visible && !documentHidden;
  $: unavailable = !revisionId;
  $: reconcile(identityOf(collectionId, documentId, revisionId), effectiveVisible);
  /** Every physical page is a verified blank: nothing to select, which is a result and not a failure. */
  $: allBlank = status !== null && status.state === 'READY' && status.totalPages !== null && status.totalPages > 0
    && status.blankPages === status.totalPages && status.selectablePages === 0;
  $: blankNote = status !== null && status.blankPages > 0 && !allBlank
    ? `${status.blankPages} blank ${status.blankPages === 1 ? 'page has' : 'pages have'} no text to select.`
    : null;
  $: showOcrNote = status !== null && ['NOT_CREATED', 'READY', 'PARTIAL'].includes(status.state);

  onMount(() => {
    alive = true;
    reconcile(identityOf(collectionId, documentId, revisionId), effectiveVisible);
  });
  onDestroy(() => {
    alive = false;
    stop();
  });

  function identityOf(collection: string, document: string, revision: string | null | undefined): string | null {
    return revision ? JSON.stringify([collection, document, revision]) : null;
  }

  function reconcile(next: string | null, isVisible: boolean): void {
    if (!alive) return;
    if (next !== identity) {
      identity = next;
      stop();
      status = null;
      loadFailed = false;
      actionFailed = false;
      lastVisible = isVisible;
      if (next !== null && isVisible) void refresh();
      return;
    }
    if (isVisible === lastVisible) return;
    lastVisible = isVisible;
    if (!isVisible) stop();
    else if (identity !== null) void refresh();
  }

  /** Cancels the poll timer and makes every in-flight answer stale. A status already shown stays visible. */
  function stop(): void {
    generation += 1;
    if (timer !== null) clearTimeout(timer);
    timer = null;
    loading = false;
    actionPending = false;
  }

  /** A read of the status only. It never creates, retries or enqueues anything. */
  async function refresh(): Promise<void> {
    const token = generation;
    const current = identity;
    if (current === null || revisionId === null || !revisionId) return;
    const revision = revisionId;
    loading = true;
    loadFailed = false;
    try {
      const next = await getSelectablePdfStatus(collectionId, documentId, revision);
      if (token !== generation || current !== identity) return;
      if (next.revisionId !== revision) {
        loadFailed = true;
        return;
      }
      applyStatus(next);
    } catch {
      if (token !== generation || current !== identity) return;
      loadFailed = true;
    } finally {
      if (token === generation && current === identity) loading = false;
    }
  }

  function applyStatus(next: PdfArtifactStatus): void {
    status = next;
    loadFailed = false;
    actionFailed = false;
    schedulePoll();
  }

  /** Only a queued or building copy is polled; every other state, including a verified READY, ends the timer. */
  function schedulePoll(): void {
    if (timer !== null) clearTimeout(timer);
    timer = null;
    if (status === null || !['QUEUED', 'BUILDING'].includes(status.state) || !effectiveVisible || identity === null) return;
    const token = generation;
    timer = setTimeout(() => {
      timer = null;
      if (token === generation) void refresh();
    }, pollMillis);
  }

  /** Create (retry false) or retry (retry true). A second press while one is in flight does nothing. */
  function startAction(retry: boolean): void {
    if (actionPending || identity === null || revisionId === null || !revisionId || !effectiveVisible) return;
    const token = generation;
    const current = identity;
    const revision = revisionId;
    actionPending = true;
    actionFailed = false;
    // Focus moves to the status line before the button is disabled, so keyboard focus is never lost.
    void tick().then(() => statusLine?.focus());
    void (async () => {
      try {
        const next = await createSelectablePdf(collectionId, documentId, { revisionId: revision, retry });
        if (token !== generation || current !== identity) return;
        if (next.revisionId !== revision) {
          actionFailed = true;
          return;
        }
        applyStatus(next);
      } catch {
        if (token !== generation || current !== identity) return;
        actionFailed = true;
      } finally {
        if (token === generation && current === identity) actionPending = false;
      }
    })();
  }

  function checkAgain(): void {
    if (identity !== null) void refresh();
  }

  function onDocumentVisibility(): void {
    documentHidden = document.hidden;
  }

  function reasonOf(code: string | null, count = 1): string {
    return code === null ? GENERIC_FAILURE : REASON_COPY[code]?.(count) ?? GENERIC_FAILURE;
  }

  /** One contiguous run of 1-based page numbers, formatted as "N" or "N-M". */
  function pageRange(from: number, to: number): string {
    return from === to ? `${from}` : `${from}-${to}`;
  }

  /** Collapses 0-based page ordinals into ranges, e.g. [44,45,46,56] -> "45-47, 57". */
  function pageRanges(ordinals: number[]): string {
    const pages = [...ordinals].sort((a, b) => a - b).map((ordinal) => ordinal + 1);
    const ranges: string[] = [];
    let start = pages[0];
    let end = pages[0];
    for (const page of pages.slice(1)) {
      if (page === end + 1) {
        end = page;
        continue;
      }
      ranges.push(pageRange(start, end));
      start = page;
      end = page;
    }
    ranges.push(pageRange(start, end));
    return ranges.join(', ');
  }

  /** Groups unavailable pages by reason, in first-seen order, so one line reports every page sharing it. */
  function unavailableGroups(pages: { ordinal: number; reasonCode: string | null }[]): {
    reasonCode: string | null;
    count: number;
    label: string;
  }[] {
    const ordinalsByReason = new Map<string | null, number[]>();
    for (const page of pages) {
      const ordinals = ordinalsByReason.get(page.reasonCode);
      if (ordinals) ordinals.push(page.ordinal);
      else ordinalsByReason.set(page.reasonCode, [page.ordinal]);
    }
    return Array.from(ordinalsByReason.entries()).map(([reasonCode, ordinals]) => ({
      reasonCode,
      count: ordinals.length,
      label: pageRanges(ordinals),
    }));
  }

  function fileLink(key: string | null, inline: boolean): string | null {
    if (key === null || revisionId === null || !revisionId) return null;
    return selectablePdfFileHref(collectionId, documentId, revisionId, key, inline);
  }
</script>

<svelte:document onvisibilitychange={onDocumentVisibility} />

<div class="pdf-actions" role="group" aria-label="Selectable PDF copy" style="display: flex; flex-wrap: wrap; min-width: 0; max-width: 100%;">
  {#if unavailable}
    <p class="pdf-line" role="status" tabindex="-1" bind:this={statusLine}>Selectable PDF is unavailable for this source.</p>
  {:else}
    <p class="pdf-line" role="status" tabindex="-1" bind:this={statusLine}>
      {#if actionPending}Starting PDF generation…
      {:else if loading && status === null}Checking the PDF with selectable text…
      {:else if loadFailed && status === null}Selectable PDF status is unavailable right now.
      {:else if status === null}Checking the PDF with selectable text…
      {:else if status.state === 'NOT_CREATED'}No PDF with selectable text has been made for this revision.
      {:else if status.state === 'QUEUED'}PDF with selectable text is queued.
      {:else if status.state === 'BUILDING'}PDF with selectable text is being built.
      {:else if allBlank}Nothing to select here: all {status.totalPages} pages are verified blank.
      {:else if status.state === 'READY' || status.state === 'PARTIAL'}Selectable text on {status.selectablePages} of {status.totalPages ?? '?'} pages.
      {:else}PDF with selectable text could not be made.{/if}
    </p>

    {#if actionFailed}
      <p class="pdf-problem pdf-block" role="alert">The PDF with selectable text could not be started. Try again.</p>
    {/if}
    {#if loadFailed}
      <p class="pdf-problem pdf-block" role="alert">The PDF status could not be loaded.</p>
      <button type="button" onclick={checkAgain} disabled={loading}>Check again</button>
    {/if}

    {#if status !== null}
      {#if blankNote !== null}<p class="pdf-note pdf-block">{blankNote}</p>{/if}

      {#if status.state === 'FAILED'}
        <p class="pdf-detail pdf-block" style="overflow-wrap: anywhere; max-width: 100%;">{reasonOf(status.reasonCode)}</p>
      {/if}

      {#if status.state === 'PARTIAL' && status.unavailablePages.length > 0}
        <div class="pdf-block">
          <p>Pages without selectable text:</p>
          <ul class="pdf-unavailable" aria-label="Pages without selectable text" style="max-width: 100%;">
            {#each unavailableGroups(status.unavailablePages) as group (group.reasonCode ?? 'null')}
              <li style="min-width: 0; max-width: 100%; overflow-wrap: anywhere;">{group.count === 1 ? 'Page' : 'Pages'} {group.label}: {reasonOf(group.reasonCode, group.count)}</li>
            {/each}
          </ul>
        </div>
      {/if}

      {#if status.state === 'NOT_CREATED'}
        <button type="button" onclick={() => startAction(false)} disabled={actionPending}>Create PDF with selectable text</button>
      {/if}

      {#if (status.state === 'READY' || status.state === 'PARTIAL') && fileLink(status.key, true) !== null}
        <a class="pdf-link" href={fileLink(status.key, true) ?? undefined} target="_blank" rel="noopener noreferrer">Open PDF with selectable text</a>
        <a class="pdf-link" href={fileLink(status.key, false) ?? undefined}>Download PDF with selectable text</a>
      {/if}

      {#if status.state === 'PARTIAL' || status.state === 'FAILED'}
        <button type="button" onclick={() => startAction(true)} disabled={actionPending}>Retry PDF generation</button>
      {/if}

      {#if showOcrNote}<p class="pdf-note pdf-block">{OCR_NOTE}</p>{/if}
    {/if}
  {/if}
</div>

<style>
  .pdf-actions { display: flex; flex-wrap: wrap; align-items: center; gap: .4rem .6rem; min-width: 0; max-width: 100%; color: #a6adaa; font-size: .9rem; }
  .pdf-actions p { margin: 0; min-width: 0; max-width: 100%; overflow-wrap: anywhere; }
  .pdf-actions .pdf-block { flex: 1 0 100%; }
  .pdf-actions .pdf-problem { color: #e0a58a; }
  .pdf-actions .pdf-note { font-size: .82rem; }
  .pdf-unavailable { margin: .2rem 0 0; padding-left: 1.2rem; max-width: 100%; }
  .pdf-unavailable li { min-width: 0; max-width: 100%; overflow-wrap: anywhere; }
  .pdf-link { color: #c4a77d; }
  [tabindex='-1']:focus { outline: 2px solid #c4a77d; outline-offset: 2px; }
</style>
