<script lang="ts">
  import { isUncheckedImageProfile, methodOptionLabel } from './ocrRescan';
  import { onDestroy, onMount, tick } from 'svelte';
  import { ApiError, listReadingMethods, previewImport, previewRescan, startImport, startRescan, type ImportPreview, type ImportStarted, type ReadingMethodOption, type RescanPreview, type RescanStarted } from './api';

  export let collectionId: string;
  export let request:
    | { kind: 'import'; paths: string[]; recursive: boolean; include: string[]; exclude: string[] }
    | { kind: 'rescan'; documentId: string };
  export let onstarted: (run: ImportStarted | RescanStarted) => void;
  export let oncancel: () => void;

  type Preview = ImportPreview | RescanPreview;
  type PendingStart =
    | { kind: 'import'; body: Parameters<typeof startImport>[0] }
    | { kind: 'rescan'; collectionId: string; documentId: string; body: Parameters<typeof startRescan>[2] };
  const requestId = typeof crypto !== 'undefined' && 'randomUUID' in crypto
    ? crypto.randomUUID()
    : `${Date.now()}-${Math.random().toString(36).slice(2)}`;
  let methods: ReadingMethodOption[] = [];
  let selectedMethod = '';
  let preview: Preview | null = null;
  let loadingMethods = true;
  let previewing = false;
  let submitting = false;
  let error: string | null = null;
  let dialogHeading: HTMLHeadingElement;
  let dialogElement: HTMLDivElement;
  let previousFocus: HTMLElement | null = null;
  let generation = 0;
  let submitLock = false;
  let started = false;
  let pendingStart: PendingStart | null = null;

  $: pageCount = preview === null ? null : 'totalPages' in preview ? preview.totalPages : preview.pageTotal ?? preview.externalPageUpperBound ?? null;
  $: shownAtLeast = preview !== null && 'atLeast' in preview && preview.atLeast;
  $: documentPageCount = preview !== null && !('files' in preview) && typeof preview.documentPages === 'number' ? preview.documentPages : null;
  // A rescan that keeps every page's text reads none; it may report no total, or a total of zero.
  $: nothingToRead = preview !== null && (pageCount === 0 || (!('files' in preview) && (preview.pageTotal ?? 0) === 0 && documentPageCount !== null && documentPageCount > 0));
  $: selectedOption = methods.find((entry) => entry.method === selectedMethod);
  $: sendsExternally = selectedOption?.external ?? (preview !== null && 'external' in preview && Boolean(preview.external));
  $: destinationLabel = preview !== null && 'destination' in preview && preview.destination
    ? preview.destination
    : selectedOption?.destination ?? 'this machine';
  $: unknownExternalTotal = preview !== null && 'files' in preview && preview.external && preview.atLeast;
  $: displayedCost = unknownExternalTotal ? null : costTextFor(preview);
  $: confirmationLabel = buildConfirmLabel(pageCount, shownAtLeast, sendsExternally, destinationLabel, selectedOption);

  function costTextFor(current: Preview | null): string | null {
    if (current === null) return null;
    if ('estimatedCostUsd' in current && current.estimatedCostUsd !== undefined && current.estimatedCostUsd !== null) return `$${current.estimatedCostUsd.toFixed(4)}${current.costBasis ? ` (${current.costBasis})` : ''}`;
    if ('costBasis' in current && current.costBasis) return current.costBasis;
    if ('costEstimate' in current && current.costEstimate) return `$${current.costEstimate.amountUsd.toFixed(4)} (${current.costEstimate.basis})`;
    if ('costUnavailableReason' in current && current.costUnavailableReason) return current.costUnavailableReason;
    return null;
  }
  function methodName(entry: ReadingMethodOption | undefined): string {
    if (!entry) return '';
    return entry.method.startsWith('llm:') ? entry.label.replace(/^LLM:\s*/i, '') : entry.label;
  }
  function buildConfirmLabel(count: number | null, atLeastCount: boolean, isExternal: boolean, target: string, method: ReadingMethodOption | undefined): string {
    if (count === null || !method) return 'Confirm reading';
    const quantity = `${atLeastCount ? 'at least ' : ''}${count} ${count === 1 ? 'page' : 'pages'}`;
    if (isExternal) return `Send ${quantity} to ${target} with ${methodName(method)}`;
    return `Read ${quantity} with ${method.label}`;
  }
  function describe(failure: unknown): string {
    return failure instanceof Error ? failure.message : 'Something went wrong.';
  }

  async function updatePreview(): Promise<void> {
    if (selectedMethod === '' || pendingStart !== null) return;
    const mine = ++generation;
    const currentRequest = request;
    previewing = true;
    preview = null;
    error = null;
    try {
      const next = currentRequest.kind === 'import'
        ? await previewImport({ collection: collectionId, paths: currentRequest.paths, recursive: currentRequest.recursive, include: currentRequest.include, exclude: currentRequest.exclude, method: selectedMethod })
        : await previewRescan(collectionId, currentRequest.documentId, selectedMethod);
      if (mine === generation) preview = next;
    } catch (failure) {
      if (mine === generation) error = describe(failure);
    } finally {
      if (mine === generation) previewing = false;
    }
  }

  async function load(): Promise<void> {
    loadingMethods = true;
    try {
      const list = await listReadingMethods(collectionId);
      methods = list.methods.filter((entry) => !isUncheckedImageProfile(entry));
      const preferred = methods.find((entry) => entry.method === list.default && entry.available);
      selectedMethod = preferred?.method ?? methods.find((entry) => entry.available)?.method ?? '';
      if (selectedMethod) await updatePreview();
      else if (methods.length === 0) error = 'No reading methods are available.';
    } catch (failure) {
      error = describe(failure);
    } finally {
      loadingMethods = false;
    }
  }

  async function chooseMethod(event: Event): Promise<void> {
    const value = (event.currentTarget as HTMLSelectElement).value;
    if (value === selectedMethod || !methods.some((entry) => entry.method === value && entry.available)) return;
    selectedMethod = value;
    await updatePreview();
  }

  async function confirm(): Promise<void> {
    if (submitLock || started) return;
    if (pendingStart === null && (preview === null || !selectedOption?.available || selectedMethod === '')) return;
    submitLock = true;
    submitting = true;
    error = null;
    try {
      if (pendingStart === null) {
        const currentRequest = request;
        pendingStart = currentRequest.kind === 'import'
          ? { kind: 'import', body: { collection: collectionId, paths: [...currentRequest.paths], recursive: currentRequest.recursive, include: [...currentRequest.include], exclude: [...currentRequest.exclude], method: selectedMethod, previewHash: (preview as ImportPreview).previewHash, requestId } }
          : { kind: 'rescan', collectionId, documentId: currentRequest.documentId, body: { previewId: (preview as RescanPreview).previewId, requestId } };
      }
      const run = pendingStart.kind === 'import'
        ? await startImport(pendingStart.body)
        : await startRescan(pendingStart.collectionId, pendingStart.documentId, pendingStart.body);
      pendingStart = null;
      started = true;
      onstarted(run);
    } catch (failure) {
      const staleImport = failure instanceof ApiError && failure.code === 'PREVIEW_STALE';
      const staleRescan = failure instanceof ApiError && failure.code === 'STALE_RESCAN_PREVIEW';
      if (staleImport || staleRescan) {
        pendingStart = null;
        const staleMessage = staleImport ? 'The files changed; review the summary again.' : 'The document changed; review the summary again.';
        error = staleMessage;
        await updatePreview();
        error = staleMessage;
      } else if (failure instanceof ApiError && failure.status !== null && failure.status >= 400 && failure.status < 500) {
        // A definitive client refusal cannot have started this request, so a new preview/action is safe.
        pendingStart = null;
        error = describe(failure);
      } else {
        error = 'The start may have reached the server. Retry this exact start before changing its method or closing the dialog.';
      }
    } finally {
      submitLock = false;
      submitting = false;
    }
  }

  onMount(() => {
    previousFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    void load();
    void tick().then(() => dialogHeading?.focus());
  });

  onDestroy(() => {
    if (previousFocus?.isConnected) previousFocus.focus();
  });

  function containFocus(event: KeyboardEvent): void {
    if (event.key === 'Escape') {
      if (pendingStart === null && !submitting) oncancel();
      return;
    }
    if (event.key !== 'Tab') return;
    const focusable = Array.from(dialogElement.querySelectorAll<HTMLElement>(
      'button:not([disabled]), select:not([disabled]), input:not([disabled]), textarea:not([disabled]), a[href], [tabindex]:not([tabindex="-1"])',
    )).filter((element) => !element.hidden && element.getAttribute('aria-hidden') !== 'true');
    if (focusable.length === 0) {
      event.preventDefault();
      dialogHeading.focus();
      return;
    }
    const first = focusable[0];
    const last = focusable[focusable.length - 1];
    if (event.shiftKey && (document.activeElement === first || document.activeElement === dialogHeading)) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault();
      first.focus();
    }
  }
</script>

<section class="backdrop" role="presentation">
  <div class="dialog" role="dialog" aria-modal="true" aria-labelledby="start-reading-title" tabindex="-1" data-testid="start-reading-dialog" bind:this={dialogElement} onkeydown={containFocus}>
    <div class="content">
    <h2 id="start-reading-title" tabindex="-1" bind:this={dialogHeading}>{request.kind === 'import' ? 'Start import' : 'Scan again'}</h2>
    {#if loadingMethods}<p role="status">Loading reading methods…</p>{/if}
    {#if !loadingMethods && methods.length > 0}
      <label for="reading-method">Reading method</label>
      <select id="reading-method" data-testid="reading-method" value={selectedMethod} onchange={chooseMethod} disabled={submitting || previewing || pendingStart !== null}>
        {#each methods as method (method.method)}
          <option value={method.method} disabled={!method.available}>{methodOptionLabel(method)}</option>
        {/each}
      </select>
      {#each methods.filter((entry) => !entry.available) as method (method.method)}
        <p class="unavailable">{method.label}: {method.unavailableReason ?? 'Unavailable'}</p>
      {/each}
    {/if}
    {#if previewing}<p role="status">Preparing page summary…</p>{/if}
    {#if preview !== null}
      <section class="summary" aria-label="Reading summary">
        {#if 'files' in preview}
          <ul>{#each preview.files as file (file.path)}<li>{file.path}: {file.pages === null ? file.reason ?? 'page count unknown' : typeof file.documentPages === 'number' && file.documentPages > file.pages ? `${file.pages} of ${file.documentPages} pages will be read` : `${file.pages} pages`}</li>{/each}</ul>
        {/if}
        {#if nothingToRead}
          <p>No page needs reading; the document keeps its text.</p>
        {:else}
          <p>{shownAtLeast ? 'At least ' : ''}{pageCount ?? 'Unknown'} {pageCount === 1 ? 'page' : 'pages'} will be read{#if !('files' in preview) && documentPageCount !== null && pageCount !== null && documentPageCount > pageCount}{' '}(of {documentPageCount} in the document){/if}.</p>
        {/if}
        {#if unknownExternalTotal}<p>All pages in the listed files will be sent; total cost is unavailable because the page count is unknown.</p>{/if}
        <p>Destination: {destinationLabel}</p>
        {#if displayedCost}<p>{displayedCost}</p>{/if}
      </section>
    {/if}
    {#if error}<p role="alert">{error}</p>{/if}
    </div>
    <div class="actions">
      <button type="button" class="primary" onclick={confirm} disabled={started || submitting || previewing || (pendingStart === null && (preview === null || !selectedOption?.available))}>{started ? 'Started' : submitting ? 'Starting…' : pendingStart !== null ? 'Retry same start' : confirmationLabel}</button>
      <button type="button" onclick={oncancel} disabled={submitting || pendingStart !== null}>Cancel</button>
    </div>
  </div>
</section>

<style>
  .backdrop { position: fixed; inset: 0; z-index: 20; display: grid; place-items: center; padding: 1rem; background: #0009; }
  .dialog { display: flex; flex-direction: column; width: min(34rem, 100%); max-height: 90vh; overflow: hidden; padding: 0; border: 1px solid #454a4a; border-radius: 0.6rem; background: #202424; color: #f3f3f3; box-shadow: 0 1rem 3rem #0008; }
  .content { display: grid; gap: 0.7rem; min-height: 0; overflow: auto; padding: 1.2rem; }
  h2, p, ul { margin: 0; }
  .summary { display: grid; gap: 0.35rem; padding-top: 0.65rem; border-top: 1px solid #454a4a; }
  .summary ul { padding-left: 1.2rem; max-height: 8rem; overflow: auto; }
  /* Outside the scrolling content, so Cancel stays in view however long the summary or the method names are. */
  .actions { flex: none; display: flex; flex-wrap: wrap; justify-content: end; gap: 0.5rem; padding: 0.8rem 1.2rem; border-top: 1px solid #454a4a; }
  button, select { font: inherit; padding: 0.4rem 0.6rem; }
  select { width: 100%; max-width: 100%; text-overflow: ellipsis; }
  .unavailable { color: #b9bdbc; font-size: 0.82rem; }
  .primary { font-weight: 600; }
</style>
