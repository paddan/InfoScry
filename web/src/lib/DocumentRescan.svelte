<script lang="ts">
  import { onDestroy, tick } from 'svelte';
  import {
    admitRescan,
    approveRescanExternal,
    cancelRescan,
    getRescanOperation,
    listOcrProfiles,
    listRescanOperations,
    previewRescan,
    resumeRescan,
    type OcrEngine,
    type OcrImportMode,
    type OcrOperation,
    type OcrProfile,
    type RescanPreview,
    type RescanPreviewOverrides,
  } from './api';
  import {
    ENGINE_LABELS,
    IMPORT_MODE_LABELS,
    STAGE_LABELS,
    formatUsd,
    holdsDocument,
    isTerminalStage,
    newRequestId,
    ocrProfileLabel,
    pageCount,
    sameSnapshot,
  } from './ocrRescan';

  /** The document is named by both ids, because an operation is only ever read under its own collection. */
  export let collectionId: string;
  export let documentId: string;
  export let documentName: string;
  /** How long a scan that is not finished waits before it is read again. */
  export let pollMillis = 2000;

  const ENGINES = Object.keys(ENGINE_LABELS) as OcrEngine[];
  const MODES = Object.keys(IMPORT_MODE_LABELS) as OcrImportMode[];

  /**
   * Everything below belongs to one collection and one document. Each asynchronous step captures the
   * generation it started in, and a step that finds a newer one drops its answer: a late result for the
   * document that was shown before must never write into the one shown now.
   */
  let boundKey: string | null = null;
  let generation = 0;
  let alive = true;

  let loading = true;
  let loadError: string | null = null;
  let latest: OcrOperation | null = null;
  let timer: ReturnType<typeof setTimeout> | null = null;
  let controlBusy: 'cancel' | 'resume' | null = null;
  let controlError: string | null = null;
  let cancelRequestedFor: string | null = null;

  let profiles: OcrProfile[] = [];
  let profilesLoaded = false;
  let profilesLoading = false;
  let profilesError: string | null = null;

  let previewOpen = false;
  let engineChoice: OcrEngine | '' = '';
  let modeChoice: OcrImportMode | '' = '';
  let transcriptionChoice = '';
  let reviewChoice = '';
  let previewing = false;
  let previewError: string | null = null;
  let preview: RescanPreview | null = null;
  /** The idempotency key of this preview's admission: a repeated Start is the same request, not a second one. */
  let requestId = '';
  let previewStale = false;
  let starting = false;
  let startError: string | null = null;
  /** The preview each operation of this view was started from, whose snapshot hash an approval binds to. */
  let startedFrom: Record<string, RescanPreview> = {};

  let approvalOpen = false;
  let approvalLoading = false;
  let approvalPreview: RescanPreview | null = null;
  let approvalBlocked = false;
  let approvalPages = '';
  let approvalError: string | null = null;
  let approving = false;

  let scanButton: HTMLButtonElement | null = null;
  let previewHeading: HTMLElement | null = null;
  let latestGroup: HTMLElement | null = null;
  let approvalHeading: HTMLElement | null = null;
  let reviewButton: HTMLButtonElement | null = null;

  $: key = `${collectionId}\u0000${documentId}`;
  $: if (key !== boundKey) start(key);
  $: selectable = profiles.filter((profile) => profile.enabled);
  $: holds = latest !== null && holdsDocument(latest);
  $: approvalSent = latest?.external.distinctPages ?? 0;

  function start(next: string): void {
    boundKey = next;
    generation += 1;
    clearTimer();
    latest = null;
    loading = true;
    loadError = null;
    controlBusy = null;
    controlError = null;
    cancelRequestedFor = null;
    previewOpen = false;
    engineChoice = '';
    modeChoice = '';
    transcriptionChoice = '';
    reviewChoice = '';
    previewing = false;
    previewError = null;
    preview = null;
    requestId = '';
    previewStale = false;
    starting = false;
    startError = null;
    startedFrom = {};
    approvalOpen = false;
    approvalLoading = false;
    approvalPreview = null;
    approvalBlocked = false;
    approvalPages = '';
    approvalError = null;
    approving = false;
    void loadOperations();
  }

  onDestroy(() => {
    alive = false;
    generation += 1;
    clearTimer();
  });

  function clearTimer(): void {
    if (timer !== null) clearTimeout(timer);
    timer = null;
  }

  function describe(failure: unknown): string {
    return failure instanceof Error ? failure.message : 'Something went wrong.';
  }

  function isConflict(failure: unknown): boolean {
    return (failure as { status?: number | null } | null)?.status === 409;
  }

  /** The persisted operations are the only source of the scan's state, so a reload finds it again. */
  async function loadOperations(): Promise<void> {
    const mine = generation;
    const collection = collectionId;
    const document = documentId;
    try {
      const operations = (await listRescanOperations(collection, document)) ?? [];
      if (mine !== generation) return;
      loadError = null;
      if (operations.length > 0) setLatest(operations[0]);
    } catch (failure) {
      if (mine !== generation) return;
      loadError = describe(failure);
    } finally {
      if (mine === generation) loading = false;
    }
  }

  function setLatest(operation: OcrOperation): void {
    if (latest !== null && latest.operationId !== operation.operationId) {
      closeApprovalState();
      cancelRequestedFor = null;
    }
    latest = operation;
    if (approvalOpen && operation.stage !== 'AWAITING_APPROVAL') closeApprovalState();
    if (isTerminalStage(operation.stage)) cancelRequestedFor = null;
    schedulePoll();
  }

  function schedulePoll(): void {
    clearTimer();
    if (!alive || latest === null || isTerminalStage(latest.stage)) return;
    const mine = generation;
    const id = latest.operationId;
    timer = setTimeout(() => void poll(mine, id), pollMillis);
  }

  async function poll(mine: number, operationId: string): Promise<void> {
    timer = null;
    if (mine !== generation) return;
    try {
      const operation = await getRescanOperation(collectionId, documentId, operationId);
      if (mine !== generation) return;
      // An answer older than what a control action already returned is not news.
      if (latest !== null && latest.operationId === operation.operationId && latest.updatedAt > operation.updatedAt) {
        schedulePoll();
        return;
      }
      controlError = null;
      setLatest(operation);
    } catch (failure) {
      if (mine !== generation) return;
      controlError = `The scan could not be refreshed: ${describe(failure)}`;
      schedulePoll();
    }
  }

  async function ensureProfiles(): Promise<void> {
    if (profilesLoaded || profilesLoading) return;
    profilesLoading = true;
    try {
      const listed = (await listOcrProfiles()) ?? [];
      if (!alive) return;
      profiles = listed;
      profilesLoaded = true;
      profilesError = null;
    } catch (failure) {
      if (!alive) return;
      profilesError = describe(failure);
    } finally {
      profilesLoading = false;
    }
  }

  // ---- preview and start ----

  async function openPreview(): Promise<void> {
    if (holds) return;
    previewOpen = true;
    void ensureProfiles();
    await tick();
    previewHeading?.focus();
  }

  async function closePreview(): Promise<void> {
    previewOpen = false;
    preview = null;
    previewError = null;
    previewStale = false;
    startError = null;
    await tick();
    scanButton?.focus();
  }

  /** A choice changes what would be read, so a preview of the old choices must not be the one started. */
  function overridesChanged(): void {
    preview = null;
    previewStale = false;
    previewError = null;
    startError = null;
  }

  function overrides(): RescanPreviewOverrides {
    const chosen: RescanPreviewOverrides = {};
    if (engineChoice !== '') chosen.engine = engineChoice;
    if (modeChoice !== '') chosen.importMode = modeChoice;
    if (transcriptionChoice !== '') chosen.transcriptionProfileId = transcriptionChoice;
    if (reviewChoice !== '') chosen.reviewProfileId = reviewChoice;
    return chosen;
  }

  async function runPreview(): Promise<void> {
    if (previewing) return;
    const mine = generation;
    previewing = true;
    previewError = null;
    startError = null;
    try {
      const answered = await previewRescan(collectionId, documentId, overrides());
      if (mine !== generation) return;
      preview = answered;
      previewStale = false;
      requestId = newRequestId();
    } catch (failure) {
      if (mine !== generation) return;
      preview = null;
      previewError = describe(failure);
    } finally {
      if (mine === generation) previewing = false;
    }
  }

  async function startScan(): Promise<void> {
    const shown = preview;
    if (starting || shown === null || previewStale) return;
    const mine = generation;
    starting = true;
    startError = null;
    try {
      const admitted = await admitRescan(collectionId, documentId, shown.previewId, requestId);
      if (mine !== generation) return;
      startedFrom = { ...startedFrom, [admitted.operationId]: shown };
      setLatest(admitted);
      previewOpen = false;
      preview = null;
      await tick();
      latestGroup?.focus();
    } catch (failure) {
      if (mine !== generation) return;
      startError = describe(failure);
      // A conflict says the document or the preview is not what the person was shown: the same preview is
      // not offered again, but what they chose stays in the form.
      if (isConflict(failure)) {
        previewStale = true;
        void loadOperations();
      }
    } finally {
      if (mine === generation) starting = false;
    }
  }

  // ---- control of a running or stopped operation ----

  async function cancelScan(): Promise<void> {
    const operation = latest;
    if (operation === null || controlBusy !== null) return;
    const mine = generation;
    controlBusy = 'cancel';
    controlError = null;
    try {
      const answered = await cancelRescan(collectionId, documentId, operation.operationId);
      if (mine !== generation) return;
      cancelRequestedFor = answered.operationId;
      setLatest(answered);
      if (isTerminalStage(answered.stage)) cancelRequestedFor = null;
    } catch (failure) {
      if (mine !== generation) return;
      controlError = describe(failure);
    } finally {
      if (mine === generation) controlBusy = null;
    }
  }

  async function resumeScan(): Promise<void> {
    const operation = latest;
    if (operation === null || controlBusy !== null) return;
    const mine = generation;
    controlBusy = 'resume';
    controlError = null;
    try {
      const answered = await resumeRescan(collectionId, documentId, operation.operationId);
      if (mine !== generation) return;
      setLatest(answered);
    } catch (failure) {
      if (mine !== generation) return;
      controlError = describe(failure);
    } finally {
      if (mine === generation) controlBusy = null;
    }
  }

  // ---- approving an external page scope ----

  function closeApprovalState(): void {
    approvalOpen = false;
    approvalLoading = false;
    approvalPreview = null;
    approvalBlocked = false;
    approvalError = null;
    approving = false;
  }

  function prefillPages(from: RescanPreview, operation: OcrOperation): void {
    const bound = from.externalPageUpperBound ?? operation.pageTotal ?? null;
    approvalPages = bound === null || bound === undefined || bound < 1 ? '' : String(bound);
  }

  /**
   * Opens the approval with the scope in front of the person. The approval is bound to a snapshot hash that
   * the operation read does not carry, so it comes from the preview this view started the scan from; after a
   * reload it comes from a new preview, accepted only when its snapshot is exactly the operation's.
   */
  async function openApproval(): Promise<void> {
    const operation = latest;
    if (operation === null || approvalOpen) return;
    const mine = generation;
    closeApprovalState();
    approvalOpen = true;
    approvalPages = '';
    await tick();
    approvalHeading?.focus();
    const known = startedFrom[operation.operationId];
    if (known !== undefined) {
      approvalPreview = known;
      prefillPages(known, operation);
      return;
    }
    approvalLoading = true;
    try {
      const fresh = await previewRescan(collectionId, documentId, {});
      if (mine !== generation || !approvalOpen) return;
      if (sameSnapshot(fresh.snapshot, operation.snapshot)) {
        approvalPreview = fresh;
        prefillPages(fresh, operation);
      } else {
        approvalBlocked = true;
        approvalError =
          "The collection's OCR settings have changed since this scan was admitted, so an approval cannot " +
          'be bound to its original scope. Cancel this scan and start a new one.';
      }
    } catch (failure) {
      if (mine !== generation) return;
      approvalError = describe(failure);
    } finally {
      if (mine === generation) approvalLoading = false;
    }
  }

  async function closeApproval(): Promise<void> {
    closeApprovalState();
    await tick();
    reviewButton?.focus();
  }

  async function approve(): Promise<void> {
    const operation = latest;
    const scope = approvalPreview;
    if (approving || operation === null || scope === null || approvalBlocked) return;
    const minimum = Math.max(1, operation.external.distinctPages);
    const text = approvalPages.trim();
    if (!/^\d+$/.test(text) || Number(text) < minimum) {
      approvalError = `Approve a whole number of distinct pages, at least ${minimum}.`;
      return;
    }
    const mine = generation;
    approving = true;
    approvalError = null;
    try {
      const answered = await approveRescanExternal(
        collectionId,
        documentId,
        operation.operationId,
        scope.snapshotHash,
        Number(text),
      );
      if (mine !== generation) return;
      closeApprovalState();
      setLatest(answered);
      await tick();
      latestGroup?.focus();
    } catch (failure) {
      if (mine !== generation) return;
      // The person's number stays where it is: a conflict is something to read, not something to retype.
      approvalError = describe(failure);
    } finally {
      if (mine === generation) approving = false;
    }
  }

  function plural(count: number, noun: string): string {
    return `${count} ${noun}${count === 1 ? '' : 's'}`;
  }
</script>

<section class="rescan" aria-label="Rescan this document">
  <span class="eyebrow">SCAN AGAIN</span>

  {#if loading}
    <p class="hint">Loading earlier scans…</p>
  {:else if loadError !== null}
    <p role="alert">{loadError}</p>
  {/if}

  {#if latest !== null}
    <div class="latest" role="group" aria-label="Latest scan" tabindex="-1" bind:this={latestGroup}>
      <p class="stage">
        <strong>{STAGE_LABELS[latest.stage] ?? latest.stage}</strong>
        {#if latest.stage === 'COMPLETE' && latest.pendingReviewCount > 0}
          <span> · Needs review: {pageCount(latest.pendingReviewCount)}</span>
        {/if}
      </p>
      <p role="status" class="progress">
        {#if latest.pageTotal !== null && latest.pageTotal !== undefined}
          {latest.pagesCommitted} of {pageCount(latest.pageTotal)} read
        {:else}
          {pageCount(latest.pagesCommitted)} read
        {/if}
        {#if latest.pagesFailed > 0}
          · {pageCount(latest.pagesFailed)} could not be read
        {/if}
      </p>
      {#if latest.external.calls > 0 || latest.external.distinctPages > 0 || latest.external.allowance > 0}
        <p class="hint">
          {plural(latest.external.distinctPages, 'distinct page')} sent to an external provider
          (allowance {latest.external.allowance}) · {plural(latest.external.calls, 'provider call')}
        </p>
      {/if}
      {#if latest.errorMessage}
        <p class="problem">{latest.errorMessage}</p>
      {:else if latest.errorCode}
        <p class="problem">{latest.errorCode}</p>
      {/if}
      {#if latest.stage === 'COMPLETE' && latest.pendingReviewCount > 0}
        <p class="hint">
          Pages that need review keep their existing text in search until a decision is made.
        </p>
      {/if}
      {#if cancelRequestedFor === latest.operationId}
        <p class="hint">Cancellation requested. The scan stops between pages.</p>
      {/if}
      <div class="actions">
        {#if latest.stage === 'AWAITING_APPROVAL'}
          <button type="button" class="primary" bind:this={reviewButton} onclick={() => void openApproval()}>
            Review approval
          </button>
        {/if}
        {#if !isTerminalStage(latest.stage)}
          <button
            type="button"
            onclick={() => void cancelScan()}
            disabled={controlBusy !== null || cancelRequestedFor === latest.operationId}
          >
            Cancel scan
          </button>
        {:else if latest.stage !== 'COMPLETE'}
          <button type="button" class="primary" onclick={() => void resumeScan()} disabled={controlBusy !== null}>
            Resume scan
          </button>
        {/if}
      </div>
      {#if controlError !== null}<p role="alert">{controlError}</p>{/if}
    </div>
  {/if}

  {#if approvalOpen && latest !== null}
    <section class="panel" aria-label="Approve external pages">
      <h4 tabindex="-1" bind:this={approvalHeading}>Approve external pages</h4>
      {#if approvalLoading}
        <p class="hint" role="status">Reading this scan's scope…</p>
      {/if}
      <p>
        Approving lets InfoScry send up to the number of distinct pages below from {documentName} to the
        destinations listed here. {plural(approvalSent, 'distinct page')} {approvalSent === 1 ? 'has' : 'have'}
        been sent so far. An approval can only widen the scope; it cannot be taken back once pages are sent.
      </p>
      {#if approvalPreview !== null}
        {#if approvalPreview.destinations.length === 0}
          <p class="hint">No destination outside this Mac is named.</p>
        {:else}
          <ul class="destinations" aria-label="Destinations">
            {#each approvalPreview.destinations as destination (destination.role + destination.model)}
              <li>
                {destination.role}: {destination.scope === 'EXTERNAL' ? 'external' : 'local'} · {destination.model}{destination.endpoint
                  ? ` at ${destination.endpoint}`
                  : ''}
              </li>
            {/each}
          </ul>
        {/if}
      {/if}
      <div class="field">
        <label for="rescan-approval-pages">Distinct pages to approve</label>
        <input id="rescan-approval-pages" inputmode="numeric" bind:value={approvalPages} />
      </div>
      {#if approvalError !== null}<p role="alert">{approvalError}</p>{/if}
      <div class="actions">
        <button
          type="button"
          class="primary"
          onclick={() => void approve()}
          disabled={approving || approvalLoading || approvalBlocked || approvalPreview === null}
        >
          Approve external pages
        </button>
        <button type="button" onclick={() => void closeApproval()}>Close approval</button>
      </div>
    </section>
  {/if}

  <div class="actions">
    <button type="button" bind:this={scanButton} onclick={() => void openPreview()} disabled={loading || holds}>
      Scan again
    </button>
  </div>
  {#if holds}
    <p class="hint">
      A scan of this document is in progress or has pages waiting for review, so another cannot start yet.
    </p>
  {/if}

  {#if previewOpen}
    <div class="panel">
      <h3 tabindex="-1" bind:this={previewHeading}>Preview a new scan</h3>
      <p class="hint">
        Nothing is read or sent until you start the scan. A choice left on the collection default uses the
        collection's settings.
      </p>
      <div class="controls">
        <div class="field">
          <label for="rescan-engine">Engine for this scan</label>
          <select id="rescan-engine" bind:value={engineChoice} onchange={overridesChanged}>
            <option value="">Collection default</option>
            {#each ENGINES as value (value)}<option {value}>{ENGINE_LABELS[value]}</option>{/each}
          </select>
        </div>
        <div class="field">
          <label for="rescan-mode">Import mode for this scan</label>
          <select id="rescan-mode" bind:value={modeChoice} onchange={overridesChanged}>
            <option value="">Collection default</option>
            {#each MODES as value (value)}<option {value}>{IMPORT_MODE_LABELS[value]}</option>{/each}
          </select>
        </div>
        <div class="field">
          <label for="rescan-transcription">Transcription profile for this scan</label>
          <select id="rescan-transcription" bind:value={transcriptionChoice} onchange={overridesChanged}>
            <option value="">Collection default</option>
            {#each selectable as profile (profile.id)}<option value={profile.id}>{ocrProfileLabel(profile)}</option>{/each}
          </select>
        </div>
        <div class="field">
          <label for="rescan-review">Review profile for this scan</label>
          <select id="rescan-review" bind:value={reviewChoice} onchange={overridesChanged}>
            <option value="">Collection default</option>
            {#each selectable as profile (profile.id)}<option value={profile.id}>{ocrProfileLabel(profile)}</option>{/each}
          </select>
        </div>
      </div>
      {#if profilesError !== null}
        <p class="hint">The OCR profiles could not be listed: {profilesError}</p>
      {/if}
      <div class="actions">
        <button type="button" class="primary" onclick={() => void runPreview()} disabled={previewing}>
          {previewing ? 'Previewing…' : preview === null ? 'Preview scan' : 'Preview again'}
        </button>
        <button type="button" onclick={() => void closePreview()}>Close preview</button>
      </div>
      {#if previewError !== null}<p role="alert">{previewError}</p>{/if}

      {#if preview !== null}
        <section class="preview" aria-label="Scan preview">
          <dl>
            <dt>Engine</dt>
            <dd>{ENGINE_LABELS[preview.snapshot.engine]} · {IMPORT_MODE_LABELS[preview.snapshot.mode]}</dd>
            <dt>Pages</dt>
            <dd>{preview.pageTotal === null || preview.pageTotal === undefined ? 'Not known' : preview.pageTotal}</dd>
            <dt>External pages at most</dt>
            <dd>
              {#if preview.externalPageUpperBound === null || preview.externalPageUpperBound === undefined}
                Not known
              {:else}
                {preview.externalPageUpperBound}
              {/if}
            </dd>
            <dt>Destinations</dt>
            <dd>
              {#if preview.destinations.length === 0}
                None outside this Mac
              {:else}
                <ul class="destinations">
                  {#each preview.destinations as destination (destination.role + destination.model)}
                    <li>
                      {destination.role}: {destination.scope === 'EXTERNAL' ? 'external' : 'local'} · {destination.model}{destination.endpoint
                        ? ` at ${destination.endpoint}`
                        : ''}
                    </li>
                  {/each}
                </ul>
              {/if}
            </dd>
            <dt>Estimated cost</dt>
            <dd>
              {#if preview.costEstimate !== null && preview.costEstimate !== undefined}
                {formatUsd(preview.costEstimate.amountUsd)} ({preview.costEstimate.basis})
              {:else}
                Unavailable{preview.costUnavailableReason ? `: ${preview.costUnavailableReason}` : ''}
              {/if}
            </dd>
            <dt>Approval</dt>
            <dd>
              {#if preview.approvalRequired}
                Approval is required before more than {preview.externalAllowance} external
                {preview.externalAllowance === 1 ? 'page is' : 'pages are'} sent. The scan will wait for you.
              {:else}
                Not required; the allowance is {plural(preview.externalAllowance, 'external page')}.
              {/if}
            </dd>
          </dl>
          <p class="hint">Valid until {preview.expiresAt}.</p>
          <div class="actions">
            <button type="button" class="primary" onclick={() => void startScan()} disabled={starting || previewStale}>
              {starting ? 'Starting…' : 'Start scan'}
            </button>
          </div>
        </section>
      {/if}
      {#if startError !== null}<p role="alert">{startError}</p>{/if}
    </div>
  {/if}
</section>

<style>
  .rescan { display: grid; gap: 0.6rem; border-top: 1px solid #2b3030; padding-top: 0.75rem; }
  .rescan p { margin: 0; }
  .eyebrow { color: #858a8a; font-size: 0.68rem; font-weight: 700; letter-spacing: 0.12em; }
  .hint { color: #929997; font-size: 0.82rem; }
  .problem { color: #d8c9a8; overflow-wrap: anywhere; }
  .latest { display: grid; gap: 0.4rem; border: 1px solid #303535; border-radius: 0.55rem; padding: 0.75rem; }
  .latest:focus { outline: 2px solid #c4a77d; outline-offset: 2px; }
  .panel { display: grid; gap: 0.6rem; border: 1px solid #303535; border-radius: 0.55rem; background: #1d2021; padding: 0.9rem; }
  .panel h3, .panel h4 { margin: 0; font-size: 0.95rem; }
  .panel h3:focus, .panel h4:focus { outline: 2px solid #c4a77d; outline-offset: 2px; }
  .controls { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0.75rem; }
  .field { display: grid; gap: 0.35rem; align-content: start; }
  label { color: #b8bcbb; font-size: 0.82rem; }
  select,
  input { min-width: 0; border: 1px solid #383d3e; border-radius: 0.48rem; background: #202324; padding: 0.62rem 0.72rem; color: #e8e9e7; }
  .actions { display: flex; flex-wrap: wrap; gap: 0.55rem; }
  .preview { display: grid; gap: 0.5rem; border-top: 1px solid #2b3030; padding-top: 0.6rem; }
  .preview dl { display: grid; grid-template-columns: auto minmax(0, 1fr); gap: 0.3rem 0.9rem; margin: 0; font-size: 0.85rem; }
  .preview dt { color: #b8bcbb; }
  .preview dd { margin: 0; overflow-wrap: anywhere; }
  .destinations { margin: 0; padding-left: 1.1rem; overflow-wrap: anywhere; }
  button.primary { border-color: #c4a77d; background: #c4a77d; color: #1c1b18; font-weight: 650; }
  button.primary:hover:not(:disabled) { background: #d4ba94; }
  @media (max-width: 36rem) {
    .controls { grid-template-columns: minmax(0, 1fr); }
  }
</style>
