<script lang="ts">
  import { onDestroy } from 'svelte';
  import { cancelRescan, getJob, getRescanOperation, listRescanOperations, resumeRescan, type DocumentApiRow, type JobApiView, type OcrOperation, type RescanSelection, type RescanStarted, type RetryAttempt, type RetryOcrChoice } from './api';
  import StartReadingDialog from './StartReadingDialog.svelte';
  import { newRequestId } from './ocrRescan';
  import { documentStatus, readableFailureCause } from './documentStatus';

  export let visible = true;
  export let collectionId: string;
  export let documentId: string;
  export let documentName: string;
  export let document: DocumentApiRow | undefined = undefined;
  export let retryEligible = false;
  /** The page review can own a single progress-only instance while its context pane is hidden. */
  export let statusOnly = false;
  export let pollMillis = 2000;
  export let onRetry: ((options: RetryOcrChoice) => Promise<RetryAttempt>) | undefined = undefined;
  export let onRetryFinished: (() => Promise<DocumentApiRow | undefined>) | undefined = undefined;
  export let startedRescan: { requestId: string; operation: OcrOperation } | undefined = undefined;
  export let onPublished: ((documentId: string, revisionId: string) => void | boolean | Promise<void | boolean>) | undefined = undefined;

  let latest: OcrOperation | null = null;
  let loading = true;
  let error: string | null = null;
  let controlBusy = false;
  let retryRequestId: string | null = null;
  let retryJob: JobApiView | null = null;
  let retryDocument: DocumentApiRow | undefined;
  let retryGeneration = 0;
  let retryTimer: ReturnType<typeof setTimeout> | null = null;
  let dialogOpen = false;
  let generation = 0;
  let timer: ReturnType<typeof setTimeout> | null = null;
  let boundKey = '';
  let authoritativeListLoaded = false;
  const initialStartedRequestId = startedRescan?.requestId;
  let destroyed = false;
  let dialogGeneration = -1;
  let dialogSelection: RescanSelection | undefined;
  let handledStartedRequestId: string | null = null;
  let lastVisible = visible;
  const publishedRevisions = new Set<string>();
  const pendingPublishedRevisions = new Map<string, { generation: number }>();

  $: key = `${collectionId}\u0000${documentId}`;
  $: if (key !== boundKey && visible) void load(key);
  $: if (startedRescan !== undefined && startedRescan.requestId !== handledStartedRequestId) {
    handledStartedRequestId = startedRescan.requestId;
    // A start event seeds progress only while its operation is active. Terminal outcomes are
    // delivered by the initiating owner and re-synced from the authoritative operations list;
    // replaying a retained terminal prop after remount can announce an older revision.
    const isNewAdmission = startedRescan.requestId !== initialStartedRequestId;
    if (isNewAdmission) {
      if (inProgress(startedRescan.operation)) {
        latest = startedRescan.operation;
        if (visible && !destroyed) schedulePoll(generation);
      } else {
        // A very fast attempt can fail or complete before the admission response reaches the parent.
        // Reload that new operation instead of dropping its terminal result because the first list read
        // ran before it was admitted.
        void load(key);
      }
    } else if (!authoritativeListLoaded && inProgress(startedRescan.operation)) {
      latest = startedRescan.operation;
      if (visible && !destroyed) schedulePoll(generation);
    }
  }
  $: if (visible !== lastVisible) {
    lastVisible = visible;
    visibilityChanged(visible);
  }
  $: retryCompletedSuccessfully = retryJob?.state === 'COMPLETE' &&
    ['COMPLETE', 'COMPLETE_WITH_WARNINGS'].includes((retryDocument ?? document)?.status ?? '');
  $: documentRetryAvailable = retryEligible && ['FAILED', 'NEEDS_TOOL'].includes(document?.status ?? '');

  const terminal = (operation: OcrOperation): boolean => ['COMPLETE', 'FAILED', 'CANCELLED', 'NEEDS_TOOL'].includes(operation.stage);
  const inProgress = (operation: OcrOperation | null): boolean => operation !== null && !terminal(operation);
  const retryTerminal = (job: JobApiView): boolean => ['COMPLETE', 'FAILED', 'CANCELLED'].includes(job.state);
  function announcePublished(operation: OcrOperation, observedGeneration = generation): void {
    const revisionId = operation.candidateRevisionId;
    if (operation.stage !== 'COMPLETE' || revisionId == null) return;
    const identity = `${operation.documentId}\u0000${revisionId}`;
    if (publishedRevisions.has(identity)) return;
    const pending = pendingPublishedRevisions.get(identity);
    if (pending?.generation === observedGeneration) return;

    // A new visible generation owns a fresh delivery attempt. Keep the old promise harmless:
    // its cleanup may remove the claim only if it still owns this map entry.
    const attempt = { generation: observedGeneration };
    pendingPublishedRevisions.set(identity, attempt);
    void (async () => {
      let accepted = false;
      try {
        // A void result keeps existing synchronous consumers successful; route consumers return
        // false when their asynchronous active-head lookup was dropped or failed.
        accepted = (await onPublished?.(operation.documentId, revisionId)) !== false;
      } catch {
        accepted = false;
      }
      if (accepted) publishedRevisions.add(identity);
      if (pendingPublishedRevisions.get(identity) === attempt) pendingPublishedRevisions.delete(identity);
    })();
  }
  function openDialog(selection?: RescanSelection): void {
    if (!visible || destroyed) return;
    dialogGeneration = generation;
    dialogSelection = selection;
    dialogOpen = true;
  }
  function visibilityChanged(isVisible: boolean): void {
    if (!isVisible) {
      generation += 1;
      retryGeneration += 1;
      if (timer !== null) clearTimeout(timer);
      if (retryTimer !== null) clearTimeout(retryTimer);
      timer = null;
      retryTimer = null;
      return;
    }
    if (retryJob !== null && !retryTerminal(retryJob)) {
      const mine = ++retryGeneration;
      void pollRetryJob(mine);
    }
    void load(key);
  }
  function statusLabel(
    operation: OcrOperation | null,
    currentRetry: JobApiView | null,
    completedDocument: DocumentApiRow | undefined,
    currentDocument: DocumentApiRow | undefined,
  ): string {
    if (currentRetry !== null) {
      if (currentRetry.state === 'QUEUED' || currentRetry.state === 'RUNNING') return `Reading ${currentRetry.completed} of ${currentRetry.total}`;
      if (currentRetry.state === 'CANCELLED') return 'Cancelled';
      const finishedDocument = completedDocument ?? currentDocument;
      if (finishedDocument) return documentStatus(finishedDocument).label;
      return currentRetry.state === 'COMPLETE' ? 'Done' : documentStatus({
        id: documentId, collectionId, mediaType: 'application/octet-stream', originalFilename: documentName,
        sizeBytes: 0, status: 'FAILED', createdAt: '', updatedAt: '', errorCode: currentRetry.errorCode,
      }, currentRetry).label;
    }
    if (operation === null) return currentDocument === undefined ? 'Imported' : documentStatus(currentDocument).label;
    if (operation.stage === 'FAILED' || operation.stage === 'NEEDS_TOOL') return `Failed: ${operation.errorMessage ?? readableFailureCause(operation.errorCode)}`;
    if (operation.stage === 'CANCELLED') return 'Cancelled';
    if (operation.stage === 'COMPLETE') return 'Done';
    return operation.pageTotal === null || operation.pageTotal === undefined
      ? `Reading ${operation.pagesCommitted}`
      : `Reading ${operation.pagesCommitted} of ${operation.pageTotal}`;
  }
  function actionLabel(operation: OcrOperation | null, retrySucceeded: boolean, retryAllowed: boolean): string {
    if (retrySucceeded || !retryAllowed) return 'Scan again';
    return operation?.stage === 'FAILED' || operation?.stage === 'NEEDS_TOOL' ? 'Retry' : 'Scan again';
  }
  async function retry(): Promise<void> {
    if (controlBusy || !retryEligible || onRetry === undefined || !visible || destroyed) return;
    const admissionGeneration = retryGeneration;
    controlBusy = true;
    error = null;
    try {
      // Omit method to resume the failed run's frozen snapshot and committed pages.
      retryRequestId ??= newRequestId();
      const attempt = await onRetry({ requestId: retryRequestId });
      if (destroyed) return;
      if (!visible || admissionGeneration !== retryGeneration) {
        controlBusy = false;
        return;
      }
      if (!attempt.accepted) {
        retryRequestId = null;
        throw new Error(attempt.reason);
      }
      retryRequestId = null;
      retryDocument = undefined;
      retryJob = {
        id: attempt.jobId, type: 'RETRY', state: 'QUEUED', createdAt: '', updatedAt: '',
        completed: 0, total: 1, cancelRequested: false,
      };
      const mine = ++retryGeneration;
      controlBusy = false;
      void pollRetryJob(mine);
    } catch (failure) {
      if (!destroyed && visible && admissionGeneration === retryGeneration) {
        error = failure instanceof Error ? failure.message : 'Something went wrong.';
      }
    } finally {
      if (!destroyed && admissionGeneration === retryGeneration) controlBusy = false;
    }
  }
  async function load(nextKey: string): Promise<void> {
    if (!visible || destroyed) return;
    if (nextKey !== boundKey) {
      authoritativeListLoaded = false;
      latest = null;
    }
    boundKey = nextKey;
    const mine = ++generation;
    if (timer !== null) clearTimeout(timer);
    timer = null;
    loading = true;
    error = null;
    latest = null;
    try {
      const operations = await listRescanOperations(collectionId, documentId);
      if (mine !== generation || !visible || destroyed) return;
      const current = operations[0] ?? null;
      latest = current;
      authoritativeListLoaded = true;
      if (current?.stage === 'COMPLETE') announcePublished(current, mine);
      schedulePoll(mine);
    } catch (failure) {
      if (mine === generation && visible && !destroyed) error = failure instanceof Error ? failure.message : 'Something went wrong.';
    } finally {
      if (mine === generation) loading = false;
    }
  }
  function schedulePoll(mine: number): void {
    if (timer !== null) clearTimeout(timer);
    timer = null;
    if (!visible || destroyed || mine !== generation || !inProgress(latest)) return;
    const id = latest!.operationId;
    timer = setTimeout(() => void poll(mine, id), pollMillis);
  }
  async function poll(mine: number, operationId: string): Promise<void> {
    if (!visible || destroyed || mine !== generation) return;
    timer = null;
    try {
      const operation = await getRescanOperation(collectionId, documentId, operationId);
      if (mine !== generation || !visible || destroyed) return;
      const wasInProgress = inProgress(latest);
      latest = operation;
      if (wasInProgress && operation.stage === 'COMPLETE') announcePublished(operation, mine);
      schedulePoll(mine);
    } catch (failure) {
      if (mine === generation && visible && !destroyed) {
        error = failure instanceof Error ? failure.message : 'Something went wrong.';
        schedulePoll(mine);
      }
    }
  }
  async function pollRetryJob(mine: number): Promise<void> {
    if (!visible || destroyed || mine !== retryGeneration || retryJob === null) return;
    if (retryTimer !== null) clearTimeout(retryTimer);
    retryTimer = null;
    try {
      const current = await getJob(retryJob.id);
      if (mine !== retryGeneration || !visible || destroyed) return;
      retryJob = current;
      if (retryTerminal(current)) {
        const completed = await onRetryFinished?.() ?? document;
        if (mine !== retryGeneration || !visible || destroyed) return;
        retryDocument = completed;
        return;
      }
    } catch (failure) {
      if (mine !== retryGeneration || !visible || destroyed) return;
      error = failure instanceof Error ? failure.message : 'Something went wrong.';
    }
    retryTimer = setTimeout(() => void pollRetryJob(mine), pollMillis);
  }
  async function cancelAndStartOver(): Promise<void> {
    if (controlBusy || !visible || destroyed) return;
    const mine = generation;
    controlBusy = true;
    try {
      if (latest && inProgress(latest)) {
        const operationId = latest.operationId;
        latest = await cancelRescan(collectionId, documentId, operationId);
        if (destroyed || !visible || mine !== generation) return;
        while (inProgress(latest)) {
          await new Promise((resolve) => setTimeout(resolve, Math.min(pollMillis, 100)));
          if (destroyed || !visible || mine !== generation) return;
          latest = await getRescanOperation(collectionId, documentId, operationId);
          if (destroyed || !visible || mine !== generation) return;
        }
      }
      openDialog();
    } catch (failure) {
      if (!destroyed && visible && mine === generation) error = failure instanceof Error ? failure.message : 'Something went wrong.';
    } finally {
      if (!destroyed && mine === generation) controlBusy = false;
    }
  }
  async function resumeStoppedSelection(): Promise<void> {
    if (controlBusy || !visible || destroyed || latest === null || latest.selection?.mode !== 'explicit') return;
    const mine = generation;
    const operationId = latest.operationId;
    controlBusy = true;
    error = null;
    try {
      const resumed = await resumeRescan(collectionId, documentId, operationId);
      if (destroyed || !visible || mine !== generation) return;
      latest = resumed;
      schedulePoll(generation);
    } catch (failure) {
      if (!destroyed && visible && mine === generation) error = failure instanceof Error ? failure.message : 'Something went wrong.';
    } finally {
      if (!destroyed && mine === generation) controlBusy = false;
    }
  }
  function readingStarted(run: RescanStarted): void {
    if (destroyed || !visible || dialogGeneration !== generation) return;
    latest = run;
    dialogOpen = false;
    dialogSelection = undefined;
    announcePublished(run, generation);
    schedulePoll(generation);
  }
  onDestroy(() => {
    destroyed = true;
    generation += 1;
    retryGeneration += 1;
    if (timer !== null) clearTimeout(timer);
    if (retryTimer !== null) clearTimeout(retryTimer);
  });
</script>

<section class="rescan" aria-label="Rescan this document" data-testid="document-rescan" data-document-name={documentName}>
  <span class="eyebrow">SCAN AGAIN</span>
  {#if loading}<p class="hint">Loading scan status…</p>{/if}
  {#if error}<p role="alert">{error}</p>{/if}
  <div class="latest" role="group" aria-label="Document reading status">
    <p class="stage" data-testid="document-reading-status" role="status"><strong>{statusLabel(latest, retryJob, retryDocument, document)}</strong></p>
    {#if latest !== null && latest.errorMessage && latest.stage === 'FAILED' && retryJob === null}<p class="problem">{latest.errorMessage}</p>{/if}
    {#if !statusOnly}
    {#if retryJob !== null && !retryTerminal(retryJob)}
      <p class="hint">Retrying the failed reading…</p>
    {:else if latest !== null && !terminal(latest)}
      <button type="button" class="primary" onclick={() => void cancelAndStartOver()} disabled={controlBusy}>
        {controlBusy ? 'Cancelling…' : 'Cancel and start over'}
      </button>
    {:else if latest !== null && terminal(latest) && latest.selection?.mode === 'explicit' && ['FAILED', 'CANCELLED', 'NEEDS_TOOL'].includes(latest.stage)}
      <div class="actions">
        <button type="button" class="primary" onclick={() => void resumeStoppedSelection()} disabled={loading || controlBusy}>
          {controlBusy ? 'Resuming…' : 'Resume selected pages'}
        </button>
        <button type="button" onclick={() => openDialog()} disabled={loading || controlBusy}>Scan again</button>
      </div>
    {:else if retryEligible && ((latest !== null && (latest.stage === 'FAILED' || latest.stage === 'NEEDS_TOOL')) || documentRetryAvailable) && !retryCompletedSuccessfully}
      <div class="actions">
        <button type="button" onclick={() => void retry()} disabled={loading || controlBusy || onRetry === undefined}>
          {controlBusy ? 'Retrying…' : 'Retry'}
        </button>
        <button type="button" onclick={() => openDialog()} disabled={loading || controlBusy}>Scan again</button>
      </div>
    {:else}
      <button type="button" onclick={() => openDialog()} disabled={loading || controlBusy}>
        {controlBusy ? 'Retrying…' : actionLabel(latest, retryCompletedSuccessfully, retryEligible)}
      </button>
    {/if}
    {/if}
  </div>
  {#if dialogOpen}
    <StartReadingDialog
      {collectionId}
      request={{ kind: 'rescan', documentId, selection: dialogSelection }}
      onstarted={(run) => readingStarted(run as RescanStarted)}
      oncancel={() => (dialogOpen = false)}
    />
  {/if}
</section>

<style>
  .rescan { display: grid; gap: 0.6rem; border-top: 1px solid #2b3030; padding-top: 0.75rem; }
  .rescan p { margin: 0; }
  .eyebrow { color: #858a8a; font-size: 0.68rem; font-weight: 700; letter-spacing: 0.12em; }
  .hint { color: #929997; font-size: 0.82rem; }
  .problem { color: #d8c9a8; overflow-wrap: anywhere; }
  .latest { display: grid; gap: 0.4rem; border: 1px solid #303535; border-radius: 0.55rem; padding: 0.75rem; }
  button.primary { border-color: #c4a77d; background: #c4a77d; color: #1c1b18; font-weight: 650; }
</style>
