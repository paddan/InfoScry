import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/svelte';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import DocumentRescan from './DocumentRescan.svelte';
import * as api from './api';

vi.mock('./api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('./api')>()),
  listRescanOperations: vi.fn(), getRescanOperation: vi.fn(), getJob: vi.fn(), cancelRescan: vi.fn(),
  listReadingMethods: vi.fn(), previewRescan: vi.fn(), startRescan: vi.fn(), resumeRescan: vi.fn(),
}));

function operation(stage: api.OcrOperationStage, over: Partial<api.OcrOperation> = {}): api.OcrOperation {
  return {
    operationId: 'op-1', collectionId: 'c', documentId: 'doc-1', snapshot: {} as api.OcrSettingsSnapshot,
    selection: { mode: 'automatic' }, managedSha256: '0'.repeat(64),
    stage, pageTotal: 48, pagesCommitted: 12, pagesFailed: 0,
    external: { distinctPages: 0, calls: 0, allowance: 0 },
    requestId: 'r1', createdAt: '', updatedAt: '', ...over,
  };
}

describe('DocumentRescan', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(api.listRescanOperations).mockResolvedValue([]);
    vi.mocked(api.getJob).mockResolvedValue({
      id: 'retry-job', type: 'RETRY', state: 'COMPLETE', createdAt: '', updatedAt: '',
      completed: 1, total: 1, cancelRequested: false,
    });
    vi.mocked(api.cancelRescan).mockResolvedValue(operation('CANCELLED'));
    vi.mocked(api.resumeRescan).mockResolvedValue(operation('OCR', {
      selection: { mode: 'explicit', ordinals: [0, 2] }, pagesCommitted: 1, pageTotal: 2,
    }));
    vi.mocked(api.listReadingMethods).mockResolvedValue({ default: 'surya', methods: [
      { method: 'surya', label: 'Surya', destination: 'this machine', available: true, unavailableReason: null, external: false },
    ] });
    vi.mocked(api.previewRescan).mockResolvedValue({
      previewId: 'preview-1', documentId: 'doc-1', managedHash: 'hash', snapshot: {} as api.OcrSettingsSnapshot,
      selection: { mode: 'automatic' },
      pageTotal: 48, externalPageUpperBound: 48, destinations: [], costEstimate: null,
    });
    vi.mocked(api.startRescan).mockResolvedValue(operation('OCR'));
  });
  afterEach(cleanup);

  it('shows one document status and opens the shared dialog for Scan again', async () => {
    render(DocumentRescan, { collectionId: 'c', documentId: 'doc-1', documentName: 'report.pdf' });
    expect((await screen.findByTestId('document-reading-status')).textContent).toContain('Imported');
    await fireEvent.click(screen.getByRole('button', { name: 'Scan again' }));
    expect(await screen.findByRole('dialog')).toBeTruthy();
    expect(await screen.findByRole('button', { name: 'Read 48 pages with Surya' })).toBeTruthy();
    expect(api.previewRescan).toHaveBeenCalledWith('c', 'doc-1', 'surya');
  });

  it('uses a readable cause when a failed run has only a stable error code', async () => {
    vi.mocked(api.listRescanOperations).mockResolvedValue([operation('FAILED', { errorCode: 'IMAGE_ENDPOINT_FAILED' })]);
    render(DocumentRescan, { collectionId: 'c', documentId: 'doc-1', documentName: 'report.pdf' });
    await waitFor(() => expect(screen.getByTestId('document-reading-status').textContent)
      .toBe('Failed: The OCR provider could not be reached.'));
  });

  it('replaces a stale started run with the reloaded terminal selection and resumes its checkpoint', async () => {
    let finishList!: (operations: api.OcrOperation[]) => void;
    vi.mocked(api.listRescanOperations).mockImplementationOnce(() => new Promise((resolve) => { finishList = resolve; }));
    const started = operation('OCR', { selection: { mode: 'explicit', ordinals: [0, 2] }, pageTotal: 2, pagesCommitted: 1 });
    const failed = operation('FAILED', {
      selection: { mode: 'explicit', ordinals: [0, 2] }, pageTotal: 2, pagesCommitted: 1,
      errorMessage: 'the rescan stopped unexpectedly; the published text was kept',
    });
    render(DocumentRescan, {
      collectionId: 'c', documentId: 'doc-1', documentName: 'report.pdf',
      startedRescan: { requestId: 'selection-1', operation: started },
    });

    expect(screen.getByTestId('document-reading-status').textContent).toContain('Reading 1 of 2');
    finishList([failed]);
    await waitFor(() => expect(screen.getByTestId('document-reading-status').textContent)
      .toContain('Failed: the rescan stopped unexpectedly'));
    await fireEvent.click(screen.getByRole('button', { name: 'Resume selected pages' }));
    await waitFor(() => expect(api.resumeRescan).toHaveBeenCalledWith('c', 'doc-1', 'op-1'));
    expect(api.resumeRescan).toHaveBeenCalledOnce();
    expect(screen.getByTestId('document-reading-status').textContent).toContain('Reading 1 of 2');
  });

  it('shows a newly admitted scan when the initial operations list loaded empty', async () => {
    vi.mocked(api.startRescan).mockResolvedValue(operation('OCR', {
      selection: { mode: 'explicit', ordinals: [0, 2] }, pageTotal: 2, pagesCommitted: 0,
    }));
    render(DocumentRescan, { collectionId: 'c', documentId: 'doc-1', documentName: 'report.pdf' });
    await screen.findByText('Imported');
    await fireEvent.click(screen.getByRole('button', { name: 'Scan again' }));
    await fireEvent.click(await screen.findByRole('button', { name: 'Read 48 pages with Surya' }));
    await waitFor(() => expect(screen.getByTestId('document-reading-status').textContent).toContain('Reading 0 of 2'));
  });

  it('admits one resume at a time and ignores its response after the reader is unmounted', async () => {
    vi.mocked(api.listRescanOperations).mockResolvedValue([operation('FAILED', {
      selection: { mode: 'explicit', ordinals: [0, 2] }, pageTotal: 2, pagesCommitted: 1,
    })]);
    let resolveResume!: (value: api.OcrOperation) => void;
    vi.mocked(api.resumeRescan).mockImplementation(() => new Promise((resolve) => { resolveResume = resolve; }));
    const view = render(DocumentRescan, { collectionId: 'c', documentId: 'doc-1', documentName: 'report.pdf' });
    const resume = await screen.findByRole('button', { name: 'Resume selected pages' });
    await fireEvent.click(resume);
    await fireEvent.click(resume);
    expect(api.resumeRescan).toHaveBeenCalledOnce();

    view.unmount();
    resolveResume(operation('OCR', { selection: { mode: 'explicit', ordinals: [0, 2] }, pageTotal: 2, pagesCommitted: 1 }));
    await new Promise((resolve) => setTimeout(resolve, 10));
    expect(api.getRescanOperation).not.toHaveBeenCalled();
  });

  it('labels a failed run with its cause and retries the frozen run through the retry endpoint', async () => {
    vi.mocked(api.listRescanOperations).mockResolvedValue([operation('FAILED', { errorMessage: 'Provider could not be reached' })]);
    const onRetry = vi.fn()
      .mockRejectedValueOnce(new Error('network timeout'))
      .mockResolvedValue({ accepted: true, jobId: 'job-1' });
    render(DocumentRescan, { collectionId: 'c', documentId: 'doc-1', documentName: 'report.pdf', onRetry, retryEligible: true });
    await waitFor(() => expect(screen.getByTestId('document-reading-status').textContent).toContain('Failed: Provider could not be reached'));
    await fireEvent.click(screen.getByRole('button', { name: 'Retry' }));
    await waitFor(() => expect(onRetry).toHaveBeenCalledOnce());
    const requestId = onRetry.mock.calls[0][0].requestId;
    expect(requestId).toEqual(expect.any(String));
    expect(onRetry.mock.calls[0][0]).not.toHaveProperty('method');
    expect((await screen.findByRole('alert')).textContent).toContain('network timeout');
    await fireEvent.click(screen.getByRole('button', { name: 'Retry' }));
    await waitFor(() => expect(onRetry).toHaveBeenCalledTimes(2));
    expect(onRetry.mock.calls[1][0]).toEqual({ requestId });
    expect(screen.queryByRole('dialog')).toBeNull();
    await fireEvent.click(screen.getByRole('button', { name: 'Scan again' }));
    expect(await screen.findByRole('dialog')).toBeTruthy();
  });

  it('waits for asynchronous cancellation to become terminal before opening the start dialog', async () => {
    vi.mocked(api.listRescanOperations).mockResolvedValue([operation('OCR')]);
    vi.mocked(api.cancelRescan).mockResolvedValue(operation('OCR'));
    vi.mocked(api.getRescanOperation).mockResolvedValue(operation('CANCELLED'));
    render(DocumentRescan, { collectionId: 'c', documentId: 'doc-1', documentName: 'report.pdf' });
    await waitFor(() => expect(screen.getByTestId('document-reading-status').textContent).toContain('Reading 12 of 48'));
    await fireEvent.click(screen.getByRole('button', { name: 'Cancel and start over' }));
    await waitFor(() => expect(api.cancelRescan).toHaveBeenCalledWith('c', 'doc-1', 'op-1'));
    expect(screen.queryByRole('dialog')).toBeNull();
    await waitFor(() => expect(api.getRescanOperation).toHaveBeenCalledWith('c', 'doc-1', 'op-1'));
    expect(await screen.findByRole('dialog')).toBeTruthy();
    expect(screen.queryByText(/approval|review/i)).toBeNull();
  });

  it('shows retry progress and uses the refreshed document status after retry succeeds', async () => {
    vi.mocked(api.listRescanOperations).mockResolvedValue([operation('FAILED', {
      errorCode: 'OCR_PROVIDER_UNAVAILABLE', errorMessage: 'The provider is currently unreachable.',
    })]);
    vi.mocked(api.getJob)
      .mockResolvedValueOnce({
        id: 'retry-job', type: 'RETRY', state: 'RUNNING', createdAt: '', updatedAt: '',
        completed: 0, total: 1, cancelRequested: false,
      })
      .mockResolvedValue({
        id: 'retry-job', type: 'RETRY', state: 'COMPLETE', createdAt: '', updatedAt: '',
        completed: 1, total: 1, cancelRequested: false,
      });
    const onRetry = vi.fn().mockResolvedValue({ accepted: true, jobId: 'retry-job' });
    const refreshed = {
      id: 'doc-1', collectionId: 'c', mediaType: 'application/pdf', originalFilename: 'report.pdf',
      sizeBytes: 100, status: 'COMPLETE', createdAt: '', updatedAt: 'later',
    } satisfies api.DocumentApiRow;
    const onRetryFinished = vi.fn().mockResolvedValue(refreshed);
    render(DocumentRescan, {
      collectionId: 'c', documentId: 'doc-1', documentName: 'report.pdf',
      document: { ...refreshed, status: 'FAILED', errorCode: 'OCR_PROVIDER_UNAVAILABLE' }, retryEligible: true,
      onRetry, onRetryFinished, pollMillis: 10,
    });

    await fireEvent.click(await screen.findByRole('button', { name: 'Retry' }));
    await waitFor(() => expect(onRetry).toHaveBeenCalledOnce());
    await waitFor(() => expect(api.getJob).toHaveBeenCalledOnce());
    await waitFor(() => expect(screen.getByTestId('document-reading-status').textContent).toContain('Reading 0 of 1'));
    await waitFor(() => expect(screen.getByTestId('document-reading-status').textContent).toBe('Done'), { timeout: 1000 });
    expect(onRetryFinished).toHaveBeenCalledOnce();
    expect(screen.queryByText('The provider is currently unreachable.')).toBeNull();
    expect(screen.queryByRole('button', { name: 'Retry' })).toBeNull();
    expect(screen.getByRole('button', { name: 'Scan again' })).toBeTruthy();
  });

  it('shows a refreshed failed document after a retry job completes', async () => {
    vi.mocked(api.listRescanOperations).mockResolvedValue([operation('FAILED', {
      errorCode: 'OCR_PROVIDER_UNAVAILABLE', errorMessage: 'The previous run could not reach the provider.',
    })]);
    vi.mocked(api.getJob).mockResolvedValue({
      id: 'retry-job', type: 'RETRY', state: 'COMPLETE', createdAt: '', updatedAt: 'later-than-document',
      completed: 1, total: 1, cancelRequested: false,
    });
    const onRetryFinished = vi.fn().mockResolvedValue({
      id: 'doc-1', collectionId: 'c', mediaType: 'application/pdf', originalFilename: 'report.pdf',
      sizeBytes: 100, status: 'FAILED', errorCode: 'MORE_PAGES_THAN_CONFIRMED', createdAt: '', updatedAt: 'earlier',
    } satisfies api.DocumentApiRow);
    render(DocumentRescan, {
      collectionId: 'c', documentId: 'doc-1', documentName: 'report.pdf',
      retryEligible: true, onRetry: vi.fn().mockResolvedValue({ accepted: true, jobId: 'retry-job' }), onRetryFinished, pollMillis: 10,
    });

    await fireEvent.click(await screen.findByRole('button', { name: 'Retry' }));
    await waitFor(() => expect(onRetryFinished).toHaveBeenCalledOnce());
    expect((await screen.findByTestId('document-reading-status')).textContent)
      .toBe('Failed: The document had more pages than were confirmed.');
    expect(screen.queryByText('The previous run could not reach the provider.')).toBeNull();
    expect(screen.getByRole('button', { name: 'Retry' })).toBeTruthy();
  });

  it('does not offer Retry when the document is ineligible even if the last rescan failed', async () => {
    vi.mocked(api.listRescanOperations).mockResolvedValueOnce([operation('FAILED')]);
    render(DocumentRescan, {
      collectionId: 'c', documentId: 'doc-1', documentName: 'report.pdf', retryEligible: false,
    });

    await waitFor(() => expect(screen.getByTestId('document-reading-status').textContent)
      .toBe('Failed: The reading attempt failed.'));
    expect(screen.queryByRole('button', { name: 'Retry' })).toBeNull();
    expect(screen.getByRole('button', { name: 'Scan again' })).toBeTruthy();
  });

  it('keeps retry progress in the document status after the retry is accepted', async () => {
    vi.mocked(api.listRescanOperations).mockResolvedValue([operation('FAILED')]);
    vi.mocked(api.getJob).mockResolvedValue({
      id: 'retry-job', type: 'RETRY', state: 'RUNNING', createdAt: '', updatedAt: '',
      completed: 0, total: 1, cancelRequested: false,
    });
    const onRetry = vi.fn().mockResolvedValue({ accepted: true, jobId: 'retry-job' });
    render(DocumentRescan, {
      collectionId: 'c', documentId: 'doc-1', documentName: 'report.pdf', retryEligible: true, onRetry, pollMillis: 10,
    });
    await fireEvent.click(await screen.findByRole('button', { name: 'Retry' }));
    await waitFor(() => expect(api.getJob).toHaveBeenCalledWith('retry-job'));
    expect(screen.getByText('Retrying the failed reading…')).toBeTruthy();
  });

  it('does not poll or publish a late accepted start after unmount', async () => {
    let resolveStart!: (value: api.RescanStarted) => void;
    vi.mocked(api.startRescan).mockImplementation(() => new Promise((resolve) => { resolveStart = resolve; }));
    const onPublished = vi.fn();
    const view = render(DocumentRescan, {
      collectionId: 'c', documentId: 'doc-1', documentName: 'report.pdf', pollMillis: 1,
      onPublished,
    });
    await fireEvent.click(await screen.findByRole('button', { name: 'Scan again' }));
    await fireEvent.click(await screen.findByRole('button', { name: 'Read 48 pages with Surya' }));
    await waitFor(() => expect(api.startRescan).toHaveBeenCalledOnce());

    view.unmount();
    const nextViewPublished = vi.fn();
    render(DocumentRescan, {
      collectionId: 'c', documentId: 'doc-1', documentName: 'report.pdf', pollMillis: 1,
      onPublished: nextViewPublished,
    });
    await screen.findByText('Imported');
    // The admission answer is allowed to arrive after this view is gone. Returning an active
    // operation here reproduces the old continuation that restarted polling on the dead view.
    resolveStart(operation('OCR'));
    await waitFor(() => expect(api.startRescan).toHaveBeenCalledOnce());
    await new Promise((resolve) => setTimeout(resolve, 10));
    expect(api.getRescanOperation).not.toHaveBeenCalled();
    expect(onPublished).not.toHaveBeenCalled();
    expect(nextViewPublished).not.toHaveBeenCalled();
    expect(screen.getByTestId('document-reading-status').textContent).toBe('Imported');
  });

  it('does not start retry polling or refresh a newer view after late admission', async () => {
    vi.mocked(api.listRescanOperations).mockResolvedValueOnce([operation('FAILED')]);
    let resolveAdmission!: (value: api.RetryAttempt) => void;
    const onRetry = vi.fn(() => new Promise<api.RetryAttempt>((resolve) => { resolveAdmission = resolve; }));
    const onRetryFinished = vi.fn().mockResolvedValue(undefined);
    const view = render(DocumentRescan, {
      collectionId: 'c', documentId: 'doc-1', documentName: 'report.pdf', retryEligible: true,
      onRetry, onRetryFinished, pollMillis: 1,
    });
    await fireEvent.click(await screen.findByRole('button', { name: 'Retry' }));
    await waitFor(() => expect(onRetry).toHaveBeenCalledOnce());
    view.unmount();
    render(DocumentRescan, {
      collectionId: 'c', documentId: 'doc-1', documentName: 'report.pdf', retryEligible: true,
      onRetryFinished, pollMillis: 1,
    });
    await screen.findByText('Imported');
    resolveAdmission({ accepted: true, jobId: 'late-retry-job' });
    await new Promise((resolve) => setTimeout(resolve, 10));
    expect(api.getJob).not.toHaveBeenCalled();
    expect(onRetryFinished).not.toHaveBeenCalled();
    expect(screen.getByTestId('document-reading-status').textContent).toBe('Imported');
  });

  it('does not publish a late terminal start into a replacement view', async () => {
    let resolveStart!: (value: api.RescanStarted) => void;
    vi.mocked(api.startRescan).mockImplementation(() => new Promise((resolve) => { resolveStart = resolve; }));
    const onPublished = vi.fn();
    const view = render(DocumentRescan, {
      collectionId: 'c', documentId: 'doc-1', documentName: 'report.pdf', onPublished,
    });
    await fireEvent.click(await screen.findByRole('button', { name: 'Scan again' }));
    await fireEvent.click(await screen.findByRole('button', { name: 'Read 48 pages with Surya' }));
    await waitFor(() => expect(api.startRescan).toHaveBeenCalledOnce());
    view.unmount();
    const nextViewPublished = vi.fn();
    render(DocumentRescan, {
      collectionId: 'c', documentId: 'doc-1', documentName: 'report.pdf', onPublished: nextViewPublished,
    });
    await screen.findByText('Imported');
    resolveStart(operation('COMPLETE', { candidateRevisionId: 'revision-published' }));
    await new Promise((resolve) => setTimeout(resolve, 10));
    expect(onPublished).not.toHaveBeenCalled();
    expect(nextViewPublished).not.toHaveBeenCalled();
    expect(api.getRescanOperation).not.toHaveBeenCalled();
    expect(screen.getByTestId('document-reading-status').textContent).toBe('Imported');
  });

  it('announces the candidate revision when a started run completes immediately', async () => {
    vi.mocked(api.startRescan).mockResolvedValue(operation('COMPLETE', { candidateRevisionId: 'revision-published' }));
    const onPublished = vi.fn();
    render(DocumentRescan, {
      collectionId: 'c', documentId: 'doc-1', documentName: 'report.pdf', onPublished,
    });
    await fireEvent.click(await screen.findByRole('button', { name: 'Scan again' }));
    await fireEvent.click(await screen.findByRole('button', { name: 'Read 48 pages with Surya' }));
    await waitFor(() => expect(onPublished).toHaveBeenCalledWith('doc-1', 'revision-published'));
    expect(onPublished).toHaveBeenCalledOnce();
    expect(api.getRescanOperation).not.toHaveBeenCalled();
  });

  it('pauses polling while hidden and refreshes publication when the view reopens', async () => {
    vi.mocked(api.listRescanOperations)
      .mockResolvedValueOnce([operation('OCR')])
      .mockResolvedValueOnce([operation('COMPLETE', { candidateRevisionId: 'revision-published' })]);
    const onPublished = vi.fn();
    const view = render(DocumentRescan, {
      collectionId: 'c', documentId: 'doc-1', documentName: 'report.pdf', pollMillis: 5, onPublished,
    });
    await waitFor(() => expect(screen.getByTestId('document-reading-status').textContent).toContain('Reading 12 of 48'));
    await view.rerender({ visible: false });
    await new Promise((resolve) => setTimeout(resolve, 20));
    expect(api.getRescanOperation).not.toHaveBeenCalled();
    expect(api.listRescanOperations).toHaveBeenCalledOnce();

    await view.rerender({ visible: true });
    await waitFor(() => expect(api.listRescanOperations).toHaveBeenCalledTimes(2));
    await waitFor(() => expect(onPublished).toHaveBeenCalledWith('doc-1', 'revision-published'));
    expect(api.getRescanOperation).not.toHaveBeenCalled();
  });

  it('retries an unaccepted publication after a resync without duplicating an in-flight callback', async () => {
    vi.mocked(api.listRescanOperations).mockResolvedValue([operation('COMPLETE', { candidateRevisionId: 'revision-published' })]);
    let resolveFirst!: (accepted: boolean) => void;
    const first = new Promise<boolean>((resolve) => { resolveFirst = resolve; });
    let resolveSecond!: (accepted: boolean) => void;
    const second = new Promise<boolean>((resolve) => { resolveSecond = resolve; });
    const onPublished = vi.fn()
      .mockImplementationOnce(() => first)
      .mockImplementationOnce(() => second);
    const view = render(DocumentRescan, {
      collectionId: 'c', documentId: 'doc-1', documentName: 'report.pdf', onPublished, pollMillis: 5,
    });

    await screen.findByText('Done');
    await waitFor(() => expect(onPublished).toHaveBeenCalledOnce());
    await view.rerender({ visible: false });
    await view.rerender({ visible: true });
    await waitFor(() => expect(api.listRescanOperations).toHaveBeenCalledTimes(2));
    await waitFor(() => expect(onPublished).toHaveBeenCalledTimes(2));

    resolveFirst(false);
    vi.mocked(api.startRescan).mockResolvedValue(operation('COMPLETE', { candidateRevisionId: 'revision-published' }));
    await fireEvent.click(screen.getByRole('button', { name: 'Scan again' }));
    await fireEvent.click(await screen.findByRole('button', { name: 'Read 48 pages with Surya' }));
    await waitFor(() => expect(api.startRescan).toHaveBeenCalledOnce());
    // The stale first completion must not clear the newer generation's in-flight ownership.
    expect(onPublished).toHaveBeenCalledTimes(2);
    resolveSecond(true);
    await new Promise((resolve) => setTimeout(resolve, 10));
    expect(onPublished).toHaveBeenCalledTimes(2);
  });

  it('resumes an accepted retry poll after the panel is hidden and shown', async () => {
    vi.mocked(api.listRescanOperations).mockResolvedValue([operation('FAILED')]);
    vi.mocked(api.getJob)
      .mockResolvedValueOnce({
        id: 'retry-job', type: 'RETRY', state: 'RUNNING', createdAt: '', updatedAt: '',
        completed: 1, total: 2, cancelRequested: false,
      })
      .mockResolvedValue({
        id: 'retry-job', type: 'RETRY', state: 'COMPLETE', createdAt: '', updatedAt: '',
        completed: 2, total: 2, cancelRequested: false,
      });
    const onRetryFinished = vi.fn().mockResolvedValue({
      id: 'doc-1', collectionId: 'c', mediaType: 'application/pdf', originalFilename: 'report.pdf',
      sizeBytes: 100, status: 'COMPLETE', createdAt: '', updatedAt: 'later',
    } satisfies api.DocumentApiRow);
    const view = render(DocumentRescan, {
      collectionId: 'c', documentId: 'doc-1', documentName: 'report.pdf',
      retryEligible: true, onRetry: vi.fn().mockResolvedValue({ accepted: true, jobId: 'retry-job' }),
      onRetryFinished, pollMillis: 10,
    });
    await fireEvent.click(await screen.findByRole('button', { name: 'Retry' }));
    await waitFor(() => expect(api.getJob).toHaveBeenCalledOnce());
    await view.rerender({ visible: false });
    await new Promise((resolve) => setTimeout(resolve, 20));
    expect(api.getJob).toHaveBeenCalledOnce();
    expect(onRetryFinished).not.toHaveBeenCalled();

    await view.rerender({ visible: true });
    await waitFor(() => expect(onRetryFinished).toHaveBeenCalledOnce());
    expect(api.getJob).toHaveBeenCalledTimes(2);
    expect(screen.getByTestId('document-reading-status').textContent).toBe('Done');
  });

});
