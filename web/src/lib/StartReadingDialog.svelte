<script lang="ts">
  import { isUncheckedImageProfile, methodOptionLabel } from './ocrRescan';
  import { onDestroy, onMount, tick } from 'svelte';
  import {
    ApiError,
    listOcrProfiles,
    listReadingMethods,
    previewImport,
    previewRescan,
    startImport,
    startRescan,
    type ImageAnalysisChoice,
    type ImageExtraction,
    type ImportPreview,
    type ImportPreviewRequest,
    type ImportStarted,
    type OcrProfile,
    type ReadingMethodOption,
    type RescanPreview,
    type RescanSelection,
    type RescanStarted,
  } from './api';

  export let collectionId: string;
  export let request:
    | { kind: 'import'; uploads: string[]; include: string[]; exclude: string[] }
    | { kind: 'rescan'; documentId: string; selection?: RescanSelection };
  export let onstarted: (run: ImportStarted | RescanStarted) => void;
  export let oncancel: () => void;
  /** The collection's default for *Extract images*; it only pre-fills the choice. */
  export let defaultImageExtraction: ImageExtraction = 'off';
  /** The collection's default for *Describe images with*; it only pre-fills the choice. */
  export let defaultImageProfileId: string | null = null;
  /** The collection's default for identifying from appearance; it only pre-fills the choice. */
  export let defaultIdentifyAppearance = false;

  type Preview = ImportPreview | RescanPreview;
  type PendingStart =
    | { kind: 'import'; body: Parameters<typeof startImport>[0] }
    | { kind: 'rescan'; collectionId: string; documentId: string; body: Parameters<typeof startRescan>[2] };
  const requestId = typeof crypto !== 'undefined' && 'randomUUID' in crypto
    ? crypto.randomUUID()
    : `${Date.now()}-${Math.random().toString(36).slice(2)}`;
  const imageOptions: { value: ImageExtraction; label: string }[] = [
    { value: 'off', label: 'Off' },
    { value: 'embedded', label: 'Embedded images' },
    { value: 'all', label: 'Embedded + regions from scanned pages' },
  ];
  let imageExtraction: ImageExtraction = defaultImageExtraction;
  let imageProfiles: OcrProfile[] = [];
  let imageProfileId = defaultImageExtraction === 'off' ? '' : (defaultImageProfileId ?? '');
  let identifyAppearance = defaultIdentifyAppearance;
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

  $: imagesOff = imageExtraction === 'off';
  $: effectiveImageProfileId = imagesOff ? '' : imageProfileId;
  $: imageAnalysis = effectiveImageProfileId === '' || effectiveImageProfileId === 'none'
    ? undefined
    : ({ profileId: effectiveImageProfileId, identifyAppearance } satisfies ImageAnalysisChoice);
  $: selectedImageProfile = imageProfiles.find((entry) => entry.id === effectiveImageProfileId);
  $: imageCount = preview === null ? null : preview.imageCount ?? null;
  $: imageSendsExternally = Boolean(preview?.imageExternal || selectedImageProfile?.scope === 'EXTERNAL');
  $: displayedImageCost = imageCostTextFor(preview);

  $: pageCount = preview === null ? null : 'totalPages' in preview ? preview.totalPages : preview.pageTotal ?? preview.externalPageUpperBound ?? null;
  $: shownAtLeast = preview !== null && 'atLeast' in preview && preview.atLeast;
  $: documentPageCount = preview !== null && !('files' in preview) && typeof preview.documentPages === 'number' ? preview.documentPages : null;
  // No OCR reads no page by design, so a zero total is what it is for rather than nothing to do.
  $: usesOwnText = selectedMethod === 'none';
  // Keep current text reads nothing and only makes sense to add images to what is already read.
  $: keepsText = selectedMethod === 'keep';
  $: keepNeedsImages = keepsText && imageExtraction === 'off';
  // A rescan that keeps every page's text reads none; it may report no total, or a total of zero.
  $: nothingToRead = !usesOwnText && !keepsText && preview !== null && !('files' in preview) && (pageCount === 0 || ((preview.pageTotal ?? 0) === 0 && documentPageCount !== null && documentPageCount > 0));
  $: importHasNoPagesToRead = preview !== null && 'files' in preview && pageCount === 0;
  $: explicitPages = preview !== null && !('files' in preview) && preview.selection?.mode === 'explicit'
    ? preview.selection.ordinals.map((ordinal) => ordinal + 1)
    : request.kind === 'rescan' && request.selection?.mode === 'explicit'
      ? request.selection.ordinals.map((ordinal) => ordinal + 1)
      : null;
  $: selectedOption = methods.find((entry) => entry.method === selectedMethod);
  $: sendsExternally = selectedOption?.external ?? (preview !== null && 'external' in preview && Boolean(preview.external));
  $: destinationLabel = preview !== null && 'destination' in preview && preview.destination
    ? preview.destination
    : selectedOption?.destination ?? 'this machine';
  $: unknownExternalTotal = preview !== null && 'files' in preview && preview.external && preview.atLeast;
  $: displayedCost = unknownExternalTotal ? null : costTextFor(preview);
  $: confirmationLabel = keepsText
    ? 'Extract images and keep the current text'
    : usesOwnText
    ? request.kind === 'import' ? "Import with the document's own text" : "Use the document's own text"
    : nothingToRead
    ? 'No pages to read'
    : importHasNoPagesToRead
      ? 'Import without image reading'
      : buildConfirmLabel(pageCount, shownAtLeast, sendsExternally, destinationLabel, selectedOption, explicitPages);

  function costTextFor(current: Preview | null): string | null {
    if (current === null) return null;
    if ('estimatedCostUsd' in current && current.estimatedCostUsd !== undefined && current.estimatedCostUsd !== null) return `$${current.estimatedCostUsd.toFixed(4)}${current.costBasis ? ` (${current.costBasis})` : ''}`;
    if ('costBasis' in current && current.costBasis) return current.costBasis;
    if ('costEstimate' in current && current.costEstimate) return `$${current.costEstimate.amountUsd.toFixed(4)} (${current.costEstimate.basis})`;
    if ('costUnavailableReason' in current && current.costUnavailableReason) return current.costUnavailableReason;
    return null;
  }
  function imageCostTextFor(current: Preview | null): string | null {
    if (current === null) return null;
    if (current.imageEstimatedCostUsd !== undefined && current.imageEstimatedCostUsd !== null) {
      return `$${current.imageEstimatedCostUsd.toFixed(4)}${current.imageCostBasis ? ` (${current.imageCostBasis})` : ''}`;
    }
    if (current.imageCostBasis) return current.imageCostBasis;
    return null;
  }
  function methodName(entry: ReadingMethodOption | undefined): string {
    if (!entry) return '';
    return entry.method.startsWith('llm:') ? entry.label.replace(/^LLM:\s*/i, '') : entry.label;
  }
  function buildConfirmLabel(count: number | null, atLeastCount: boolean, isExternal: boolean, target: string, method: ReadingMethodOption | undefined, pages: number[] | null): string {
    if (count === null || !method) return 'Confirm reading';
    const pageScope = pages === null ? null : pages.length === 1 ? `page ${pages[0]}` : `pages ${pages.join(', ')}`;
    const quantity = `${atLeastCount ? 'at least ' : ''}${count} ${count === 1 ? 'page' : 'pages'}`;
    if (isExternal) return `Send ${pageScope ?? quantity} to ${target} with ${methodName(method)}`;
    if (pageScope !== null) return `Read ${pageScope} with ${method.label}`;
    return `Read ${quantity} with ${method.label}`;
  }
  function describe(failure: unknown): string {
    return failure instanceof Error ? failure.message : 'Something went wrong.';
  }
  function analysisFor(profileId: string, identify: boolean): ImageAnalysisChoice | undefined {
    const effective = imageExtraction === 'off' ? '' : profileId;
    return effective === '' || effective === 'none'
      ? undefined
      : { profileId: effective, identifyAppearance: identify };
  }
  function importPreviewBody(
    currentRequest: Extract<typeof request, { kind: 'import' }>,
    analysis: ImageAnalysisChoice | undefined,
  ): ImportPreviewRequest {
    const base: ImportPreviewRequest = {
      collection: collectionId,
      uploads: [...currentRequest.uploads],
      include: currentRequest.include,
      exclude: currentRequest.exclude,
      method: selectedMethod,
    };
    if (analysis !== undefined) {
      return { ...base, imageExtraction, imageAnalysis: analysis };
    }
    if (imageExtraction !== defaultImageExtraction) {
      return { ...base, imageExtraction };
    }
    return base;
  }

  async function updatePreview(
    profileId = effectiveImageProfileId,
    identify = identifyAppearance,
  ): Promise<void> {
    // The caller has just changed one of these; the reactive values update on the next flush, so they are
    // passed explicitly. A preview that lagged one choice behind would show a count and cost nobody chose.
    const analysis = analysisFor(profileId, identify);
    if (selectedMethod === '' || pendingStart !== null) return;
    const mine = ++generation;
    const currentRequest = request;
    preview = null;
    error = null;
    if (selectedMethod === 'keep' && imageExtraction === 'off') {
      previewing = false;
      return;
    }
    previewing = true;
    try {
      const next = currentRequest.kind === 'import'
        ? await previewImport(importPreviewBody(currentRequest, analysis))
        : currentRequest.selection === undefined
          ? await previewRescan(
              collectionId,
              currentRequest.documentId,
              selectedMethod,
              undefined,
              imageExtraction,
              ...(analysis === undefined ? [] : [analysis]),
            )
          : await previewRescan(
              collectionId,
              currentRequest.documentId,
              selectedMethod,
              currentRequest.selection,
              imageExtraction,
              ...(analysis === undefined ? [] : [analysis]),
            );
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
      const [list, profiles] = await Promise.all([
        request.kind === 'rescan' ? listReadingMethods(collectionId, 'rescan') : listReadingMethods(collectionId),
        listOcrProfiles().catch(() => [] as OcrProfile[]),
      ]);
      imageProfiles = (profiles ?? []).filter((entry) => entry.enabled && entry.imageCapabilityMeasured === true);
      if (imageProfileId !== '' && !imageProfiles.some((entry) => entry.id === imageProfileId)) {
        imageProfileId = '';
      }
      methods = list.methods.filter((entry) => !isUncheckedImageProfile(entry));
      const preferred = methods.find((entry) => entry.method === list.default && entry.available);
      selectedMethod = preferred?.method ?? methods.find((entry) => entry.available)?.method ?? '';
      if (!selectedMethod && methods.length === 0) error = 'No reading methods are available.';
    } catch (failure) {
      error = describe(failure);
    } finally {
      loadingMethods = false;
    }
    // A summary over many files can take long, so the methods are shown first and stay changeable meanwhile.
    if (selectedMethod) await updatePreview();
  }

  async function chooseMethod(event: Event): Promise<void> {
    const value = (event.currentTarget as HTMLSelectElement).value;
    if (value === selectedMethod || !methods.some((entry) => entry.method === value && entry.available)) return;
    selectedMethod = value;
    await updatePreview();
  }

  async function chooseImages(event: Event): Promise<void> {
    const value = (event.currentTarget as HTMLSelectElement).value as ImageExtraction;
    if (value === imageExtraction) return;
    const hadImageAnalysis = imageProfileId !== '';
    imageExtraction = value;
    if (value === 'off') {
      imageProfileId = '';
      identifyAppearance = false;
    }
    // An import's summary does not depend on images unless an image analysis profile was chosen; a rescan's does.
    if (request.kind === 'rescan' || hadImageAnalysis) await updatePreview(imageProfileId, identifyAppearance);
  }

  async function chooseImageProfile(event: Event): Promise<void> {
    const value = (event.currentTarget as HTMLSelectElement).value;
    if (value === imageProfileId) return;
    imageProfileId = value;
    if (value === '') identifyAppearance = false;
    await updatePreview(value, identifyAppearance);
  }

  async function toggleIdentifyAppearance(event: Event): Promise<void> {
    const checked = (event.currentTarget as HTMLInputElement).checked;
    if (checked === identifyAppearance) return;
    identifyAppearance = checked;
    if (effectiveImageProfileId !== '') await updatePreview(effectiveImageProfileId, checked);
  }

  async function confirm(): Promise<void> {
    if (submitLock || started || keepNeedsImages) return;
    if (pendingStart === null && (preview === null || nothingToRead || !selectedOption?.available || selectedMethod === '')) return;
    submitLock = true;
    submitting = true;
    error = null;
    try {
      if (pendingStart === null) {
        const currentRequest = request;
        pendingStart = currentRequest.kind === 'import'
          ? {
              kind: 'import',
              body: {
                collection: collectionId,
                uploads: [...currentRequest.uploads],
                include: [...currentRequest.include],
                exclude: [...currentRequest.exclude],
                method: selectedMethod,
                previewHash: (preview as ImportPreview).previewHash,
                requestId,
                imageExtraction,
                ...((() => {
                  const analysis = analysisFor(imageProfileId, identifyAppearance);
                  return analysis === undefined ? {} : { imageAnalysis: analysis };
                })()),
              },
            }
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
      event.stopPropagation();
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
      <select id="reading-method" data-testid="reading-method" value={selectedMethod} onchange={chooseMethod} disabled={submitting || pendingStart !== null}>
        {#each methods as method (method.method)}
          <option value={method.method} disabled={!method.available}>{methodOptionLabel(method)}</option>
        {/each}
      </select>
      {#each methods.filter((entry) => !entry.available) as method (method.method)}
        <p class="unavailable">{method.label}: {method.unavailableReason ?? 'Unavailable'}</p>
      {/each}
    {/if}
    {#if !loadingMethods && methods.length > 0}
      <label for="image-extraction">Extract images</label>
      <select id="image-extraction" data-testid="image-extraction" value={imageExtraction} onchange={chooseImages} disabled={submitting || pendingStart !== null}>
        {#each imageOptions as option (option.value)}<option value={option.value}>{option.label}</option>{/each}
      </select>
      {#if keepNeedsImages}<p class="unavailable" role="alert">Choose which images to extract.</p>{/if}
      <label for="describe-images">Describe images with</label>
      <select
        id="describe-images"
        data-testid="describe-images"
        value={effectiveImageProfileId}
        onchange={chooseImageProfile}
        disabled={imagesOff || submitting || pendingStart !== null}
      >
        <option value="">None</option>
        {#each imageProfiles as profile (profile.id)}
          <option value={profile.id}>{profile.name}</option>
        {/each}
      </select>
      {#if imagesOff}
        <p class="unavailable">Turn on Extract images to describe pictures.</p>
      {:else if effectiveImageProfileId !== ''}
        <label class="check-label">
          <input
            type="checkbox"
            data-testid="identify-appearance"
            checked={identifyAppearance}
            onchange={toggleIdentifyAppearance}
            disabled={submitting || pendingStart !== null}
          />
          Identify specific people, objects, animals and places from their appearance
        </label>
      {/if}
    {/if}
    {#if previewing}<p role="status">Preparing page summary…</p>{/if}
    {#if preview !== null}
      <section class="summary" aria-label="Reading summary">
        {#if 'files' in preview}
          <ul>{#each preview.files as file (file.path)}<li>{file.path}: {file.pages == null ? file.reason ?? 'page count unknown' : typeof file.documentPages === 'number' && file.documentPages > file.pages ? `${file.pages} of ${file.documentPages} pages will be read` : `${file.pages} pages`}</li>{/each}</ul>
        {/if}
        {#if keepsText}
          <p>No page is read. The text stays as it is; images are added.</p>
        {:else if usesOwnText}
          <p>No page is read. Each page keeps the document's own text; pages without text are kept without text.</p>
        {:else if nothingToRead}
          <p>No page needs reading; the document keeps its text.</p>
        {:else if importHasNoPagesToRead}
          <p>No pages need image reading; the files will still be imported.</p>
        {:else}
          <p>{shownAtLeast ? 'At least ' : ''}{pageCount ?? 'Unknown'} {pageCount === 1 ? 'page' : 'pages'} will be read{#if !('files' in preview) && documentPageCount !== null && pageCount !== null && documentPageCount > pageCount}{' '}(of {documentPageCount} in the document){/if}.</p>
        {/if}
        {#if explicitPages !== null}<p>Pages to read: {explicitPages.join(', ')}</p>{/if}
        {#if unknownExternalTotal}<p>All pages in the listed files will be sent; total cost is unavailable because the page count is unknown.</p>{/if}
        <p>Destination: {destinationLabel}</p>
        {#if displayedCost}<p>{displayedCost}</p>{/if}
        {#if effectiveImageProfileId !== ''}
          {#if imageCount !== null}
            <p>{'atLeast' in preview && preview.imageAtLeast ? 'At least ' : ''}{imageCount} {imageCount === 1 ? 'image' : 'images'} will be described{#if selectedImageProfile} with {selectedImageProfile.name}{/if}.</p>
          {/if}
          {#if imageSendsExternally && displayedImageCost}
            <p>{displayedImageCost}</p>
          {:else if displayedImageCost}
            <p>{displayedImageCost}</p>
          {/if}
        {/if}
      </section>
    {/if}
    {#if error}<p role="alert">{error}</p>{/if}
    </div>
    <div class="actions">
      <button type="button" class="primary" onclick={confirm} disabled={started || submitting || previewing || keepNeedsImages || (pendingStart === null && (preview === null || nothingToRead || !selectedOption?.available))}>{started ? 'Started' : submitting ? 'Starting…' : pendingStart !== null ? 'Retry same start' : confirmationLabel}</button>
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
  .check-label { display: flex; align-items: center; gap: 0.5rem; font-size: 0.85rem; }
  .check-label input { accent-color: #c4a77d; }
  .unavailable { color: #b9bdbc; font-size: 0.82rem; }
  .primary { font-weight: 600; }
</style>
