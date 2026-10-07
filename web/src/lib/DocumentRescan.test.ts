import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/svelte';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import DocumentRescan from './DocumentRescan.svelte';
import type { OcrOperation, OcrOperationStage, OcrProfile, OcrSettingsSnapshot, RescanPreview } from './api';

const api = vi.hoisted(() => ({
  listOcrProfiles: vi.fn(),
  previewRescan: vi.fn(),
  admitRescan: vi.fn(),
  getRescanOperation: vi.fn(),
  listRescanOperations: vi.fn(),
  approveRescanExternal: vi.fn(),
  cancelRescan: vi.fn(),
  resumeRescan: vi.fn(),
  listPendingReviews: vi.fn(),
  listDocumentRevisions: vi.fn(),
  readSource: vi.fn(),
  decideReviews: vi.fn(),
  publishReviewDecisions: vi.fn(),
}));

vi.mock('./api', () => ({
  ApiError: class ApiError extends Error {
    code: string;
    status: number | null;
    constructor(code: string, message: string, status: number | null = null) {
      super(message);
      this.code = code;
      this.status = status;
    }
  },
  listOcrProfiles: api.listOcrProfiles,
  previewRescan: api.previewRescan,
  admitRescan: api.admitRescan,
  getRescanOperation: api.getRescanOperation,
  listRescanOperations: api.listRescanOperations,
  approveRescanExternal: api.approveRescanExternal,
  cancelRescan: api.cancelRescan,
  resumeRescan: api.resumeRescan,
  listPendingReviews: api.listPendingReviews,
  listDocumentRevisions: api.listDocumentRevisions,
  readSource: api.readSource,
  decideReviews: api.decideReviews,
  publishReviewDecisions: api.publishReviewDecisions,
}));

async function apiError(code: string, message: string, status: number): Promise<Error> {
  const { ApiError } = await import('./api');
  return new ApiError(code, message, status);
}

const SNAPSHOT: OcrSettingsSnapshot = {
  engine: 'LLM',
  mode: 'CHECK_AND_IMPROVE',
  language: 'eng',
  extractorVersion: '3',
  transcriptionPromptVersion: 1,
  reviewPromptVersion: 2,
  policyVersion: 1,
  externalPageLimit: 5,
  transcriptionProfileRevisionId: 'p-cloud-r1',
};

function preview(over: Partial<RescanPreview> = {}): RescanPreview {
  return {
    previewId: 'preview-1',
    documentId: 'doc-1',
    baseRevisionId: 'rev-1',
    managedHash: 'abc123',
    snapshot: SNAPSHOT,
    snapshotHash: 'hash-1',
    pageTotal: 12,
    externalPageUpperBound: 12,
    destinations: [{
      role: 'transcription',
      engine: 'LLM',
      scope: 'EXTERNAL',
      endpoint: 'https://vision.example.test/v1',
      model: 'vision-model',
      profileRevisionId: 'p-cloud-r1',
    }],
    costEstimate: { amountUsd: 0.04, basis: '12 pages at the profile prices' },
    approvalRequired: true,
    externalAllowance: 5,
    expiresAt: '2026-09-21T08:00:00Z',
    ...over,
  };
}

function operation(stage: OcrOperationStage, over: Partial<OcrOperation> = {}): OcrOperation {
  return {
    operationId: 'op-1',
    collectionId: 'nightfall',
    documentId: 'doc-1',
    jobId: 'job-1',
    baseRevisionId: 'rev-1',
    snapshot: SNAPSHOT,
    stage,
    pageTotal: 12,
    pagesCommitted: 0,
    pagesFailed: 0,
    external: { distinctPages: 0, calls: 0, allowance: 5 },
    pendingReviewCount: 0,
    requestId: 'request-1',
    createdAt: '2026-09-21T07:00:00Z',
    updatedAt: '2026-09-21T07:00:01Z',
    ...over,
  };
}

function profile(id: string, name: string): OcrProfile {
  return {
    id,
    name,
    enabled: true,
    revisionId: `${id}-r1`,
    sequence: 1,
    provider: 'OPENAI_COMPATIBLE',
    endpoint: 'https://example.test/v1',
    scope: 'EXTERNAL',
    model: 'vision-model',
    contextWindow: 128_000,
    maxOutputTokens: 4_096,
    inputPricePerMillion: 0.5,
    outputPricePerMillion: 1.5,
    apiKeyEnvironmentVariable: 'OCR_API_KEY',
    keyAvailable: true,
    imageCapabilityMeasured: true,
    imageCapabilityCheckedAt: null,
  };
}

async function renderRescan(over: { documentId?: string; collectionId?: string } = {}) {
  const view = render(DocumentRescan, {
    collectionId: over.collectionId ?? 'nightfall',
    documentId: over.documentId ?? 'doc-1',
    documentName: 'ledger.pdf',
  });
  await act(async () => {});
  return view;
}

async function openPreview(): Promise<void> {
  await fireEvent.click(screen.getByRole('button', { name: 'Scan again' }));
  await act(async () => {});
}

async function previewAndStart(): Promise<void> {
  await openPreview();
  await fireEvent.click(screen.getByRole('button', { name: 'Preview scan' }));
  await act(async () => {});
}

describe('document rescan', () => {
  beforeEach(() => {
    vi.resetAllMocks();
    api.listOcrProfiles.mockResolvedValue([profile('p-cloud', 'Cloud vision')]);
    api.listRescanOperations.mockResolvedValue([]);
  });

  afterEach(() => {
    cleanup();
    vi.useRealTimers();
  });

  describe('previewing', () => {
    it('offers Scan again and, with no earlier scan, shows none', async () => {
      await renderRescan();

      expect(api.listRescanOperations).toHaveBeenCalledWith('nightfall', 'doc-1');
      expect(screen.getByRole('button', { name: 'Scan again' })).toBeTruthy();
      expect(screen.queryByRole('group', { name: 'Latest scan' })).toBeNull();
    });

    it('previews with the collection defaults and shows pages, destinations, cost and approval', async () => {
      api.previewRescan.mockResolvedValue(preview());
      await renderRescan();
      await previewAndStart();

      expect(api.previewRescan).toHaveBeenCalledWith('nightfall', 'doc-1', {});
      const region = screen.getByRole('region', { name: 'Scan preview' });
      expect(within(region).getByText('Pages').nextElementSibling?.textContent).toBe('12');
      expect(within(region).getByText('External pages at most').nextElementSibling?.textContent).toBe('12');
      expect(within(region).getByText(/vision-model/).textContent).toContain('https://vision.example.test/v1');
      expect(within(region).getByText(/vision-model/).textContent).toMatch(/external/i);
      expect(within(region).getByText('Estimated cost').nextElementSibling?.textContent).toContain('$0.04');
      expect(within(region).getByText('Estimated cost').nextElementSibling?.textContent).toContain('12 pages at the profile prices');
      expect(within(region).getByText(/Approval is required/).textContent).toContain('5');
    });

    it('sends only the overrides the person chose', async () => {
      api.previewRescan.mockResolvedValue(preview());
      await renderRescan();
      await openPreview();

      await fireEvent.change(screen.getByLabelText('Engine for this scan'), { target: { value: 'SURYA' } });
      await fireEvent.change(screen.getByLabelText('Review profile for this scan'), { target: { value: 'p-cloud' } });
      await fireEvent.click(screen.getByRole('button', { name: 'Preview scan' }));
      await act(async () => {});

      expect(api.previewRescan).toHaveBeenCalledWith('nightfall', 'doc-1', {
        engine: 'SURYA',
        reviewProfileId: 'p-cloud',
      });
    });

    it('says an unavailable cost is unavailable and gives the reason, never zero', async () => {
      api.previewRescan.mockResolvedValue(preview({
        costEstimate: null,
        costUnavailableReason: 'the profile has no price recorded',
        approvalRequired: false,
        externalPageUpperBound: null,
        pageTotal: null,
      }));
      await renderRescan();
      await previewAndStart();

      const region = screen.getByRole('region', { name: 'Scan preview' });
      expect(within(region).getByText('Estimated cost').nextElementSibling?.textContent).toMatch(/unavailable/i);
      expect(region.textContent).toContain('the profile has no price recorded');
      expect(region.textContent).not.toContain('$0');
      expect(within(region).getByText('Pages').nextElementSibling?.textContent).toMatch(/not known/i);
    });

    it('shows a failed preview and keeps the overrides that were chosen', async () => {
      api.previewRescan.mockRejectedValue(await apiError('RESCAN_PROFILE_UNMEASURED', 'probe the profile first', 409));
      await renderRescan();
      await openPreview();

      await fireEvent.change(screen.getByLabelText('Engine for this scan'), { target: { value: 'LLM' } });
      await fireEvent.click(screen.getByRole('button', { name: 'Preview scan' }));
      await act(async () => {});

      expect(screen.getByRole('alert').textContent).toContain('probe the profile first');
      expect((screen.getByLabelText('Engine for this scan') as HTMLSelectElement).value).toBe('LLM');
    });

    it('moves focus into the options when they open and back to Scan again when closed', async () => {
      await renderRescan();
      const opener = screen.getByRole('button', { name: 'Scan again' });
      await fireEvent.click(opener);
      await act(async () => {});

      expect(document.activeElement).toBe(screen.getByRole('heading', { name: 'Preview a new scan' }));

      await fireEvent.click(screen.getByRole('button', { name: 'Close preview' }));
      await act(async () => {});
      expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Scan again' }));
    });
  });

  describe('starting', () => {
    it('admits the previewed scan once however often Start is clicked, with a request id', async () => {
      api.previewRescan.mockResolvedValue(preview());
      let admitted!: (value: OcrOperation) => void;
      api.admitRescan.mockImplementation(() => new Promise<OcrOperation>((resolve) => { admitted = resolve; }));
      api.getRescanOperation.mockResolvedValue(operation('PREFLIGHT'));
      await renderRescan();
      await previewAndStart();

      const start = screen.getByRole('button', { name: 'Start scan' });
      await fireEvent.click(start);
      await fireEvent.click(start);
      await fireEvent.click(start);

      expect(api.admitRescan).toHaveBeenCalledTimes(1);
      const [collectionId, documentId, previewId, requestId] = api.admitRescan.mock.calls[0];
      expect([collectionId, documentId, previewId]).toEqual(['nightfall', 'doc-1', 'preview-1']);
      expect(typeof requestId).toBe('string');
      expect(requestId.length).toBeGreaterThan(8);
      admitted(operation('PREFLIGHT'));
      await act(async () => {});

      expect(screen.getByRole('group', { name: 'Latest scan' }).textContent).toContain('Preparing');
    });

    it('retries admission with the same request id so a repeated request is one operation', async () => {
      api.previewRescan.mockResolvedValue(preview());
      api.admitRescan
        .mockRejectedValueOnce(await apiError('INTERNAL_ERROR', 'try again', 500))
        .mockResolvedValueOnce(operation('PREFLIGHT'));
      api.getRescanOperation.mockResolvedValue(operation('PREFLIGHT'));
      await renderRescan();
      await previewAndStart();

      await fireEvent.click(screen.getByRole('button', { name: 'Start scan' }));
      await act(async () => {});
      expect(screen.getByRole('alert').textContent).toContain('try again');
      await fireEvent.click(screen.getByRole('button', { name: 'Start scan' }));
      await act(async () => {});

      expect(api.admitRescan).toHaveBeenCalledTimes(2);
      expect(api.admitRescan.mock.calls[1][3]).toBe(api.admitRescan.mock.calls[0][3]);
    });

    it('uses a new request id for a new preview', async () => {
      api.previewRescan.mockResolvedValue(preview());
      api.admitRescan.mockRejectedValue(await apiError('STALE_RESCAN_PREVIEW', 'preview again', 409));
      await renderRescan();
      await previewAndStart();
      await fireEvent.click(screen.getByRole('button', { name: 'Start scan' }));
      await act(async () => {});
      api.previewRescan.mockResolvedValue(preview({ previewId: 'preview-2' }));
      await fireEvent.click(screen.getByRole('button', { name: 'Preview again' }));
      await act(async () => {});
      await fireEvent.click(screen.getByRole('button', { name: 'Start scan' }));
      await act(async () => {});

      expect(api.admitRescan.mock.calls[1][2]).toBe('preview-2');
      expect(api.admitRescan.mock.calls[1][3]).not.toBe(api.admitRescan.mock.calls[0][3]);
    });

    it('shows a stale preview conflict, keeps the overrides and requires a new preview', async () => {
      api.previewRescan.mockResolvedValue(preview());
      api.admitRescan.mockRejectedValue(await apiError('STALE_RESCAN_PREVIEW', 'the preview is no longer the document\'s state; preview again', 409));
      await renderRescan();
      await openPreview();
      await fireEvent.change(screen.getByLabelText('Engine for this scan'), { target: { value: 'LLM' } });
      await fireEvent.click(screen.getByRole('button', { name: 'Preview scan' }));
      await act(async () => {});
      await fireEvent.click(screen.getByRole('button', { name: 'Start scan' }));
      await act(async () => {});

      expect(screen.getByRole('alert').textContent).toContain('preview again');
      expect((screen.getByLabelText('Engine for this scan') as HTMLSelectElement).value).toBe('LLM');
      expect((screen.getByRole('button', { name: 'Start scan' }) as HTMLButtonElement).disabled).toBe(true);
      expect(screen.getByRole('button', { name: 'Preview again' })).toBeTruthy();
    });

    it('shows a conflict with a scan already holding the document and reads that scan', async () => {
      api.previewRescan.mockResolvedValue(preview());
      api.admitRescan.mockRejectedValue(await apiError('OCR_ALREADY_RUNNING', 'another scan holds this document', 409));
      await renderRescan();
      await previewAndStart();
      api.listRescanOperations.mockResolvedValue([operation('OCR', { pagesCommitted: 2 })]);
      api.getRescanOperation.mockResolvedValue(operation('OCR', { pagesCommitted: 2 }));
      await fireEvent.click(screen.getByRole('button', { name: 'Start scan' }));
      await act(async () => {});

      expect(screen.getByRole('alert').textContent).toContain('another scan holds this document');
      expect(screen.getByRole('group', { name: 'Latest scan' }).textContent).toContain('Reading pages');
    });
  });

  describe('after a reload', () => {
    it('restores an operation that is waiting for approval from its persisted state', async () => {
      api.listRescanOperations.mockResolvedValue([
        operation('AWAITING_APPROVAL', { external: { distinctPages: 5, calls: 7, allowance: 5 }, pagesCommitted: 5 }),
      ]);
      api.getRescanOperation.mockResolvedValue(operation('AWAITING_APPROVAL'));
      await renderRescan();

      const latest = screen.getByRole('group', { name: 'Latest scan' });
      expect(latest.textContent).toContain('Waiting for approval');
      expect(latest.textContent).toContain('5 distinct pages sent');
      expect(latest.textContent).toContain('7 provider calls');
      expect(within(latest).getByRole('button', { name: 'Review approval' })).toBeTruthy();
      expect(within(latest).getByRole('button', { name: 'Cancel scan' })).toBeTruthy();
      expect(within(latest).queryByRole('button', { name: 'Resume scan' })).toBeNull();
      expect((screen.getByRole('button', { name: 'Scan again' }) as HTMLButtonElement).disabled).toBe(true);
      expect(api.admitRescan).not.toHaveBeenCalled();
      expect(api.approveRescanExternal).not.toHaveBeenCalled();
    });

    it('follows a processing operation to the end and then stops reading it', async () => {
      vi.useFakeTimers();
      api.listRescanOperations.mockResolvedValue([operation('OCR', { pagesCommitted: 4 })]);
      api.getRescanOperation
        .mockResolvedValueOnce(operation('OCR', { pagesCommitted: 8 }))
        .mockResolvedValue(operation('COMPLETE', { pagesCommitted: 12, pendingReviewCount: 2 }));
      await renderRescan();

      expect(screen.getByRole('group', { name: 'Latest scan' }).textContent).toContain('4 of 12 pages read');
      expect(within(screen.getByRole('group', { name: 'Latest scan' })).getByRole('button', { name: 'Cancel scan' })).toBeTruthy();

      await act(async () => { await vi.advanceTimersByTimeAsync(2000); });
      expect(api.getRescanOperation).toHaveBeenCalledWith('nightfall', 'doc-1', 'op-1');
      expect(screen.getByRole('group', { name: 'Latest scan' }).textContent).toContain('8 of 12 pages read');

      await act(async () => { await vi.advanceTimersByTimeAsync(2000); });
      const latest = screen.getByRole('group', { name: 'Latest scan' });
      expect(latest.textContent).toContain('Complete');
      expect(latest.textContent).toContain('Needs review');
      expect(latest.textContent).toContain('2 pages');
      expect(within(latest).queryByRole('button', { name: 'Cancel scan' })).toBeNull();

      const reads = api.getRescanOperation.mock.calls.length;
      await act(async () => { await vi.advanceTimersByTimeAsync(10_000); });
      expect(api.getRescanOperation.mock.calls.length).toBe(reads);
    });

    it('stops polling when the component goes away', async () => {
      vi.useFakeTimers();
      api.listRescanOperations.mockResolvedValue([operation('OCR')]);
      api.getRescanOperation.mockResolvedValue(operation('OCR'));
      const { unmount } = await renderRescan();
      await act(async () => { await vi.advanceTimersByTimeAsync(2000); });
      const reads = api.getRescanOperation.mock.calls.length;
      expect(reads).toBeGreaterThan(0);

      unmount();
      await act(async () => { await vi.advanceTimersByTimeAsync(10_000); });
      expect(api.getRescanOperation.mock.calls.length).toBe(reads);
    });

    it('offers resume for a stopped operation and dispatches it once', async () => {
      api.listRescanOperations.mockResolvedValue([
        operation('FAILED', { errorCode: 'OCR_ENGINE_ERROR', errorMessage: 'the engine stopped; resume it', pagesCommitted: 3 }),
      ]);
      let resumed!: (value: OcrOperation) => void;
      api.resumeRescan.mockImplementation(() => new Promise<OcrOperation>((resolve) => { resumed = resolve; }));
      api.getRescanOperation.mockResolvedValue(operation('PREFLIGHT'));
      await renderRescan();

      expect(screen.getByRole('group', { name: 'Latest scan' }).textContent).toContain('the engine stopped; resume it');
      const resume = screen.getByRole('button', { name: 'Resume scan' });
      await fireEvent.click(resume);
      await fireEvent.click(resume);

      expect(api.resumeRescan).toHaveBeenCalledTimes(1);
      expect(api.resumeRescan).toHaveBeenCalledWith('nightfall', 'doc-1', 'op-1');
      resumed(operation('PREFLIGHT'));
      await act(async () => {});
      expect(screen.getByRole('group', { name: 'Latest scan' }).textContent).toContain('Preparing');
    });

    it('lets a cancelled scan be resumed or scanned again, but not a complete one resumed', async () => {
      api.listRescanOperations.mockResolvedValue([operation('CANCELLED')]);
      await renderRescan();
      expect(screen.getByRole('button', { name: 'Resume scan' })).toBeTruthy();
      expect((screen.getByRole('button', { name: 'Scan again' }) as HTMLButtonElement).disabled).toBe(false);
      cleanup();

      api.listRescanOperations.mockResolvedValue([operation('COMPLETE', { pagesCommitted: 12 })]);
      await renderRescan();
      expect(screen.queryByRole('button', { name: 'Resume scan' })).toBeNull();
      expect((screen.getByRole('button', { name: 'Scan again' }) as HTMLButtonElement).disabled).toBe(false);
    });

    it('keeps Scan again unavailable while a completed scan still has pages awaiting review', async () => {
      api.listRescanOperations.mockResolvedValue([operation('COMPLETE', { pendingReviewCount: 3 })]);
      await renderRescan();

      expect((screen.getByRole('button', { name: 'Scan again' }) as HTMLButtonElement).disabled).toBe(true);
      expect(screen.getByRole('group', { name: 'Latest scan' }).textContent).toContain('Needs review');
    });

    it('offers Review pages only for a completed scan with pages waiting, and returns focus when it is closed', async () => {
      api.listRescanOperations.mockResolvedValue([operation('COMPLETE', { pendingReviewCount: 0 })]);
      const none = await renderRescan();
      expect(screen.queryByRole('button', { name: 'Review pages' })).toBeNull();
      none.unmount();

      api.listRescanOperations.mockResolvedValue([operation('COMPLETE', { pendingReviewCount: 3 })]);
      api.listDocumentRevisions.mockResolvedValue({ revisions: [], total: 1, activeRevisionId: 'rev-1' });
      api.listPendingReviews.mockResolvedValue({
        reviews: [], total: 0, pendingPages: 0, pendingReviewCount: 0, externalAccounting: '',
      });
      await renderRescan();
      await fireEvent.click(screen.getByRole('button', { name: 'Review pages' }));
      await act(async () => {});
      await act(async () => {});

      expect(screen.getByRole('region', { name: 'Review pages' })).toBeTruthy();
      expect(api.listPendingReviews).toHaveBeenCalledWith('nightfall', 'doc-1', 'op-1', 0, 1);
      await fireEvent.click(screen.getByRole('button', { name: 'Close review' }));
      await act(async () => {});
      expect(screen.queryByRole('region', { name: 'Review pages' })).toBeNull();
      expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Review pages' }));
    });

    it('says when the earlier scans cannot be read instead of offering a scan blindly', async () => {
      api.listRescanOperations.mockRejectedValue(await apiError('INTERNAL_ERROR', 'scans unavailable', 500));
      await renderRescan();

      expect(screen.getByRole('alert').textContent).toContain('scans unavailable');
    });
  });

  describe('cancelling', () => {
    it('requests cancellation once and keeps following the operation until it ends', async () => {
      vi.useFakeTimers();
      api.listRescanOperations.mockResolvedValue([operation('OCR')]);
      let requested!: (value: OcrOperation) => void;
      api.cancelRescan.mockImplementation(() => new Promise<OcrOperation>((resolve) => { requested = resolve; }));
      api.getRescanOperation
        .mockResolvedValueOnce(operation('OCR'))
        .mockResolvedValue(operation('CANCELLED', { errorCode: 'CANCELLED' }));
      await renderRescan();

      const cancel = screen.getByRole('button', { name: 'Cancel scan' });
      await fireEvent.click(cancel);
      await fireEvent.click(cancel);
      requested(operation('OCR'));
      await act(async () => {});
      expect(screen.getByText(/Cancellation requested/)).toBeTruthy();
      expect((screen.getByRole('button', { name: 'Cancel scan' }) as HTMLButtonElement).disabled).toBe(true);
      expect(api.cancelRescan).toHaveBeenCalledTimes(1);
      expect(api.cancelRescan).toHaveBeenCalledWith('nightfall', 'doc-1', 'op-1');

      await act(async () => { await vi.advanceTimersByTimeAsync(4000); });
      expect(screen.getByRole('group', { name: 'Latest scan' }).textContent).toContain('Cancelled');
    });
  });

  describe('approving', () => {
    async function awaitingApproval(over: Partial<OcrOperation> = {}): Promise<void> {
      api.listRescanOperations.mockResolvedValue([
        operation('AWAITING_APPROVAL', { external: { distinctPages: 5, calls: 5, allowance: 5 }, ...over }),
      ]);
      api.getRescanOperation.mockResolvedValue(operation('AWAITING_APPROVAL', over));
      await renderRescan();
    }

    it('shows the scope before approving and sends nothing until the person asks', async () => {
      api.previewRescan.mockResolvedValue(preview());
      await awaitingApproval();

      await fireEvent.click(screen.getByRole('button', { name: 'Review approval' }));
      await act(async () => {});

      const region = screen.getByRole('region', { name: 'Approve external pages' });
      expect(region.textContent).toContain('vision-model');
      expect(region.textContent).toContain('https://vision.example.test/v1');
      expect(region.textContent).toContain('5 distinct pages');
      expect((within(region).getByLabelText('Distinct pages to approve') as HTMLInputElement).value).toBe('12');
      expect(api.approveRescanExternal).not.toHaveBeenCalled();
    });

    it('binds the approval to the snapshot hash of a preview whose snapshot is the operation\'s, once, however often it is clicked', async () => {
      api.previewRescan.mockResolvedValue(preview({ snapshotHash: 'hash-of-this-snapshot' }));
      let approved!: (value: OcrOperation) => void;
      api.approveRescanExternal.mockImplementation(() => new Promise<OcrOperation>((resolve) => { approved = resolve; }));
      await awaitingApproval();
      await fireEvent.click(screen.getByRole('button', { name: 'Review approval' }));
      await act(async () => {});

      await fireEvent.input(screen.getByLabelText('Distinct pages to approve'), { target: { value: '9' } });
      const approve = screen.getByRole('button', { name: 'Approve external pages' });
      await fireEvent.click(approve);
      await fireEvent.click(approve);

      expect(api.approveRescanExternal).toHaveBeenCalledTimes(1);
      expect(api.approveRescanExternal).toHaveBeenCalledWith('nightfall', 'doc-1', 'op-1', 'hash-of-this-snapshot', 9);
      approved(operation('PREFLIGHT'));
      await act(async () => {});
      expect(screen.getByRole('group', { name: 'Latest scan' }).textContent).toContain('Preparing');
      expect(screen.queryByRole('region', { name: 'Approve external pages' })).toBeNull();
    });

    it('reuses the hash of the preview it started from instead of previewing again', async () => {
      vi.useFakeTimers();
      api.previewRescan.mockResolvedValue(preview({ snapshotHash: 'hash-from-start' }));
      api.admitRescan.mockResolvedValue(operation('PREFLIGHT'));
      api.getRescanOperation.mockResolvedValue(operation('AWAITING_APPROVAL'));
      api.approveRescanExternal.mockResolvedValue(operation('PREFLIGHT'));
      await renderRescan();
      await previewAndStart();
      await fireEvent.click(screen.getByRole('button', { name: 'Start scan' }));
      await act(async () => {});
      await act(async () => { await vi.advanceTimersByTimeAsync(2000); });

      await fireEvent.click(screen.getByRole('button', { name: 'Review approval' }));
      await act(async () => {});
      expect(api.previewRescan).toHaveBeenCalledTimes(1);
      await fireEvent.click(screen.getByRole('button', { name: 'Approve external pages' }));
      await act(async () => {});

      expect(api.approveRescanExternal.mock.calls[0][3]).toBe('hash-from-start');
    });

    it('refuses to bind an approval when the current settings are not the operation\'s snapshot', async () => {
      api.previewRescan.mockResolvedValue(preview({ snapshot: { ...SNAPSHOT, externalPageLimit: 99 } }));
      await awaitingApproval();

      await fireEvent.click(screen.getByRole('button', { name: 'Review approval' }));
      await act(async () => {});

      expect(screen.getByRole('alert').textContent).toMatch(/settings have changed/i);
      expect((screen.getByRole('button', { name: 'Approve external pages' }) as HTMLButtonElement).disabled).toBe(true);
      expect(api.approveRescanExternal).not.toHaveBeenCalled();
    });

    it('keeps what was typed when the approval conflicts, and shows the conflict', async () => {
      api.previewRescan.mockResolvedValue(preview());
      api.approveRescanExternal.mockRejectedValue(
        await apiError('STALE_RESCAN_PREVIEW', 'its settings are not the ones this approval names', 409),
      );
      await awaitingApproval();
      await fireEvent.click(screen.getByRole('button', { name: 'Review approval' }));
      await act(async () => {});

      await fireEvent.input(screen.getByLabelText('Distinct pages to approve'), { target: { value: '8' } });
      await fireEvent.click(screen.getByRole('button', { name: 'Approve external pages' }));
      await act(async () => {});

      expect(screen.getByRole('alert').textContent).toContain('its settings are not the ones this approval names');
      expect((screen.getByLabelText('Distinct pages to approve') as HTMLInputElement).value).toBe('8');
      expect((screen.getByRole('button', { name: 'Approve external pages' }) as HTMLButtonElement).disabled).toBe(false);
    });

    it('needs a whole number of pages no smaller than what was already sent', async () => {
      api.previewRescan.mockResolvedValue(preview());
      await awaitingApproval();
      await fireEvent.click(screen.getByRole('button', { name: 'Review approval' }));
      await act(async () => {});

      await fireEvent.input(screen.getByLabelText('Distinct pages to approve'), { target: { value: '3' } });
      await fireEvent.click(screen.getByRole('button', { name: 'Approve external pages' }));
      await act(async () => {});

      expect(api.approveRescanExternal).not.toHaveBeenCalled();
      expect(screen.getByRole('alert').textContent).toMatch(/at least 5/);
    });

    it('asks for the page count when no total is known, rather than inventing one', async () => {
      api.previewRescan.mockResolvedValue(preview({ pageTotal: null, externalPageUpperBound: null }));
      await awaitingApproval({ pageTotal: null });
      await fireEvent.click(screen.getByRole('button', { name: 'Review approval' }));
      await act(async () => {});

      expect((screen.getByLabelText('Distinct pages to approve') as HTMLInputElement).value).toBe('');
      await fireEvent.click(screen.getByRole('button', { name: 'Approve external pages' }));
      await act(async () => {});
      expect(api.approveRescanExternal).not.toHaveBeenCalled();
    });

    it('returns focus to Review approval when the approval is closed', async () => {
      api.previewRescan.mockResolvedValue(preview());
      await awaitingApproval();
      await fireEvent.click(screen.getByRole('button', { name: 'Review approval' }));
      await act(async () => {});

      expect(document.activeElement).toBe(screen.getByRole('heading', { name: 'Approve external pages' }));
      await fireEvent.click(screen.getByRole('button', { name: 'Close approval' }));
      await act(async () => {});
      expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Review approval' }));
    });
  });

  describe('late answers for something no longer shown', () => {
    it('ignores an operations list that arrives after another document was selected', async () => {
      let late!: (value: OcrOperation[]) => void;
      api.listRescanOperations
        .mockImplementationOnce(() => new Promise<OcrOperation[]>((resolve) => { late = resolve; }))
        .mockResolvedValue([]);
      const view = await renderRescan();

      await view.rerender({ collectionId: 'nightfall', documentId: 'doc-2', documentName: 'other.pdf' });
      await act(async () => {});
      late([operation('AWAITING_APPROVAL', { documentId: 'doc-1' })]);
      await act(async () => {});

      expect(screen.queryByRole('group', { name: 'Latest scan' })).toBeNull();
      expect(screen.queryByText('Waiting for approval')).toBeNull();
      expect((screen.getByRole('button', { name: 'Scan again' }) as HTMLButtonElement).disabled).toBe(false);
    });

    it('ignores a preview that arrives after another collection was selected', async () => {
      let late!: (value: RescanPreview) => void;
      api.previewRescan.mockImplementation(() => new Promise<RescanPreview>((resolve) => { late = resolve; }));
      const view = await renderRescan();
      await previewAndStart();

      await view.rerender({ collectionId: 'dawn', documentId: 'doc-9', documentName: 'dawn.pdf' });
      await act(async () => {});
      late(preview());
      await act(async () => {});

      expect(screen.queryByRole('region', { name: 'Scan preview' })).toBeNull();
      expect(screen.queryByRole('button', { name: 'Start scan' })).toBeNull();
      expect(api.admitRescan).not.toHaveBeenCalled();
    });

    it('ignores an operation read that arrives after another document was selected', async () => {
      vi.useFakeTimers();
      let late!: (value: OcrOperation) => void;
      api.listRescanOperations
        .mockResolvedValueOnce([operation('OCR')])
        .mockResolvedValue([]);
      api.getRescanOperation.mockImplementation(() => new Promise<OcrOperation>((resolve) => { late = resolve; }));
      const view = await renderRescan();
      await act(async () => { await vi.advanceTimersByTimeAsync(2000); });

      await view.rerender({ collectionId: 'nightfall', documentId: 'doc-2', documentName: 'other.pdf' });
      await act(async () => {});
      late(operation('COMPLETE', { pagesCommitted: 12, pendingReviewCount: 4 }));
      await act(async () => {});

      expect(screen.queryByRole('group', { name: 'Latest scan' })).toBeNull();
      expect(screen.queryByText(/Needs review/)).toBeNull();
    });

    it('ignores an admission that answers after another document was selected', async () => {
      api.previewRescan.mockResolvedValue(preview());
      let admitted!: (value: OcrOperation) => void;
      api.admitRescan.mockImplementation(() => new Promise<OcrOperation>((resolve) => { admitted = resolve; }));
      const view = await renderRescan();
      await previewAndStart();
      await fireEvent.click(screen.getByRole('button', { name: 'Start scan' }));

      await view.rerender({ collectionId: 'nightfall', documentId: 'doc-2', documentName: 'other.pdf' });
      await act(async () => {});
      admitted(operation('PREFLIGHT'));
      await act(async () => {});

      expect(screen.queryByRole('group', { name: 'Latest scan' })).toBeNull();
    });
  });

  describe('server text', () => {
    it('renders destinations, profile names and error strings that look like markup literally', async () => {
      const markup = '<img src=x onerror="alert(1)">';
      api.listOcrProfiles.mockResolvedValue([profile('p-x', markup)]);
      api.previewRescan.mockResolvedValue(preview({
        destinations: [{
          role: 'transcription',
          engine: 'LLM',
          scope: 'EXTERNAL',
          endpoint: 'https://x.example.test/<b>v1</b>',
          model: '<script>alert(2)</script>',
        }],
        costEstimate: { amountUsd: 1, basis: '<i>basis</i>' },
      }));
      api.listRescanOperations.mockResolvedValue([
        operation('FAILED', { errorCode: 'X', errorMessage: '<script>alert(3)</script> failed' }),
      ]);
      const { container } = await renderRescan();
      await openPreview();
      expect(Array.from((screen.getByLabelText('Review profile for this scan') as HTMLSelectElement).options)
        .some((option) => (option.textContent ?? '').includes(markup))).toBe(true);
      await fireEvent.click(screen.getByRole('button', { name: 'Preview scan' }));
      await act(async () => {});

      const region = screen.getByRole('region', { name: 'Scan preview' });
      expect(region.textContent).toContain('<script>alert(2)</script>');
      expect(region.textContent).toContain('https://x.example.test/<b>v1</b>');
      expect(region.textContent).toContain('<i>basis</i>');
      expect(screen.getByRole('group', { name: 'Latest scan' }).textContent).toContain('<script>alert(3)</script> failed');
      expect(container.querySelector('script')).toBeNull();
      expect(container.querySelector('img')).toBeNull();
      expect(container.querySelector('b')).toBeNull();
      expect(container.querySelector('i')).toBeNull();
    });
  });
});
