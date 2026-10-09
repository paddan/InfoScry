<script lang="ts">
  import { onDestroy } from 'svelte';
  import { cancelRescan, getJob, getRescanOperation, listRescanOperations, type DocumentApiRow, type JobApiView, type OcrOperation, type RescanStarted, type RetryAttempt, type RetryOcrChoice } from './api';
  import StartReadingDialog from './StartReadingDialog.svelte';
  import { newRequestId } from './ocrRescan';
  import { documentStatus, readableFailureCause } from './documentStatus';

  export let collectionId: string;
  export let documentId: string;
  export let documentName: string;
  export let document: DocumentApiRow | undefined = undefined;
  export let retryEligible = false;
  export let pollMillis = 2000;
  export let onRetry: ((options: RetryOcrChoice) => Promise<RetryAttempt>) | undefined = undefined;
  export let onRetryFinished: (() => Promise<DocumentApiRow | undefined>) | undefined = undefined;

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

  $: key = `${collectionId}\u0000${documentId}`;
  $: if (key !== boundKey) void load(key);
  $: retryCompletedSuccessfully = retryJob?.state === 'COMPLETE' &&
    ['COMPLETE', 'COMPLETE_WITH_WARNINGS'].includes((retryDocument ?? document)?.status ?? '');
  $: documentRetryAvailable = retryEligible && ['FAILED', 'NEEDS_TOOL'].includes(document?.status ?? '');

  const terminal = (operation: OcrOperation): boolean => ['COMPLETE', 'FAILED', 'CANCELLED', 'NEEDS_TOOL'].includes(operation.stage);
  const inProgress = (operation: OcrOperation | null): boolean => operation !== null && !terminal(operation);
  const retryTerminal = (job: JobApiView): boolean => ['COMPLETE', 'FAILED', 'CANCELLED'].includes(job.state);
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
    if (controlBusy || !retryEligible || onRetry === undefined) return;
    controlBusy = true;
    error = null;
    try {
      // Omit method to resume the failed run's frozen snapshot and committed pages.
      retryRequestId ??= newRequestId();
      const attempt = await onRetry({ requestId: retryRequestId });
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
      void pollRetryJob(mine);
    } catch (failure) {
      error = failure instanceof Error ? failure.message : 'Something went wrong.';
    } finally {
      controlBusy = false;
    }
  }
  async function load(nextKey: string): Promise<void> {
    boundKey = nextKey;
    const mine = ++generation;
    if (timer !== null) clearTimeout(timer);
    timer = null;
    loading = true;
    error = null;
    latest = null;
    try {
      const operations = await listRescanOperations(collectionId, documentId);
      if (mine !== generation) return;
      latest = operations[0] ?? null;
      schedulePoll(mine);
    } catch (failure) {
      if (mine === generation) error = failure instanceof Error ? failure.message : 'Something went wrong.';
    } finally {
      if (mine === generation) loading = false;
    }
  }
  function schedulePoll(mine: number): void {
    if (timer !== null) clearTimeout(timer);
    timer = null;
    if (mine !== generation || !inProgress(latest)) return;
    const id = latest!.operationId;
    timer = setTimeout(() => void poll(mine, id), pollMillis);
  }
  async function poll(mine: number, operationId: string): Promise<void> {
    timer = null;
    try {
      const operation = await getRescanOperation(collectionId, documentId, operationId);
      if (mine !== generation) return;
      latest = operation;
      schedulePoll(mine);
    } catch (failure) {
      if (mine === generation) {
        error = failure instanceof Error ? failure.message : 'Something went wrong.';
        schedulePoll(mine);
      }
    }
  }
  async function pollRetryJob(mine: number): Promise<void> {
    if (mine !== retryGeneration || retryJob === null) return;
    if (retryTimer !== null) clearTimeout(retryTimer);
    retryTimer = null;
    try {
      const current = await getJob(retryJob.id);
      if (mine !== retryGeneration) return;
      retryJob = current;
      if (retryTerminal(current)) {
        retryDocument = await onRetryFinished?.() ?? document;
        return;
      }
    } catch (failure) {
      if (mine !== retryGeneration) return;
      error = failure instanceof Error ? failure.message : 'Something went wrong.';
    }
    retryTimer = setTimeout(() => void pollRetryJob(mine), pollMillis);
  }
  async function cancelAndStartOver(): Promise<void> {
    if (controlBusy) return;
    controlBusy = true;
    try {
      if (latest && inProgress(latest)) {
        const operationId = latest.operationId;
        latest = await cancelRescan(collectionId, documentId, operationId);
        while (inProgress(latest)) {
          await new Promise((resolve) => setTimeout(resolve, Math.min(pollMillis, 100)));
          latest = await getRescanOperation(collectionId, documentId, operationId);
        }
      }
      dialogOpen = true;
    } catch (failure) {
      error = failure instanceof Error ? failure.message : 'Something went wrong.';
    } finally {
      controlBusy = false;
    }
  }
  function readingStarted(run: RescanStarted): void {
    latest = run;
    dialogOpen = false;
    schedulePoll(generation);
  }
  onDestroy(() => {
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
    {#if retryJob !== null && !retryTerminal(retryJob)}
      <p class="hint">Retrying the failed reading…</p>
    {:else if latest !== null && !terminal(latest)}
      <button type="button" class="primary" onclick={() => void cancelAndStartOver()} disabled={controlBusy}>
        {controlBusy ? 'Cancelling…' : 'Cancel and start over'}
      </button>
    {:else if retryEligible && ((latest !== null && (latest.stage === 'FAILED' || latest.stage === 'NEEDS_TOOL')) || documentRetryAvailable) && !retryCompletedSuccessfully}
      <div class="actions">
        <button type="button" onclick={() => void retry()} disabled={loading || controlBusy || onRetry === undefined}>
          {controlBusy ? 'Retrying…' : 'Retry'}
        </button>
        <button type="button" onclick={() => (dialogOpen = true)} disabled={loading || controlBusy}>Scan again</button>
      </div>
    {:else}
      <button type="button" onclick={() => (dialogOpen = true)} disabled={loading || controlBusy}>
        {controlBusy ? 'Retrying…' : actionLabel(latest, retryCompletedSuccessfully, retryEligible)}
      </button>
    {/if}
  </div>
  {#if dialogOpen}
    <StartReadingDialog
      {collectionId}
      request={{ kind: 'rescan', documentId }}
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
