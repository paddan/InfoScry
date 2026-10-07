import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/svelte';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import OcrReviewPanel from './OcrReviewPanel.svelte';
import type { OcrOperation, PendingReview, ReviewsResponse } from './api';

const api = vi.hoisted(() => ({
  listPendingReviews: vi.fn(),
  listDocumentRevisions: vi.fn(),
  readSource: vi.fn(),
  readPendingCandidate: vi.fn(),
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
  listPendingReviews: api.listPendingReviews,
  listDocumentRevisions: api.listDocumentRevisions,
  readSource: api.readSource,
  readPendingCandidate: api.readPendingCandidate,
  pendingImageUrl: (collection: string, document: string, operation: string, unit: string) =>
    `/api/collections/${collection}/documents/${document}/ocr/reviews/${unit}/image?operationId=${operation}`,
  decideReviews: api.decideReviews,
  publishReviewDecisions: api.publishReviewDecisions,
}));

async function apiError(code: string, message: string, status: number): Promise<Error> {
  const { ApiError } = await import('./api');
  return new ApiError(code, message, status);
}

function review(ordinal: number, over: Partial<PendingReview> = {}): PendingReview {
  return {
    unitId: `unit-${ordinal}`,
    ordinal,
    recommendation: 'UNCERTAIN',
    disposition: 'PROPOSE',
    baselineRevisionId: 'rev-1',
    baselineTextHash: `base-${ordinal}`,
    candidateHash: `cand-${ordinal}`,
    outcomeCode: null,
    confidence: 0.42,
    reasons: [{
      code: 'DIGIT_DIFFERENCE',
      origin: 'COMPARISON',
      baselineStart: 6,
      baselineEnd: 9,
      candidateStart: 6,
      candidateEnd: 9,
      explanation: 'The last digits differ.',
    }],
    reviewerRevisionId: 'reviewer-r1',
    reviewPromptVersion: 1,
    policyVersion: 1,
    searchable: true,
    imageAvailable: true,
    ...over,
  };
}

function page(reviews: PendingReview[], total = reviews.length, over: Partial<ReviewsResponse> = {}): ReviewsResponse {
  return {
    reviews,
    total,
    pendingPages: total,
    pendingReviewCount: total,
    decidedUnpublishedCount: 0,
    externalAccounting: '1 distinct page(s) sent to an external provider; 2 provider call(s)',
    ...over,
  };
}

function operation(over: Partial<OcrOperation> = {}): OcrOperation {
  return {
    operationId: 'op-1',
    collectionId: 'nightfall',
    documentId: 'doc-1',
    baseRevisionId: 'rev-1',
    candidateRevisionId: 'cand-rev',
    snapshot: {
      engine: 'LLM',
      mode: 'CHECK_AND_IMPROVE',
      language: 'eng',
      extractorVersion: '3',
      transcriptionPromptVersion: 1,
      reviewPromptVersion: 1,
      policyVersion: 1,
      externalPageLimit: 5,
    },
    stage: 'COMPLETE',
    pagesCommitted: 3,
    pagesFailed: 0,
    external: { distinctPages: 1, calls: 2, allowance: 5 },
    pendingReviewCount: 0,
    decidedUnpublishedCount: 0,
    requestId: 'request-1',
    createdAt: '2026-09-21T07:00:00Z',
    updatedAt: '2026-09-21T07:00:01Z',
    ...over,
  };
}

function source(text: string) {
  return { id: 'unit', documentId: 'doc-1', ordinal: 0, locator: null, text, offset: 0, totalChars: text.length, truncated: false };
}

function candidate(ordinal: number, text = 'Total 128 due', over: Record<string, unknown> = {}) {
  return {
    operationId: 'op-1',
    unitId: `unit-${ordinal}`,
    ordinal,
    candidateHash: `cand-${ordinal}`,
    baselineRevisionId: 'rev-1',
    text,
    totalChars: text.length,
    truncated: false,
    ...over,
  };
}

type Deferred<T> = { promise: Promise<T>; resolve: (value: T) => void };
function deferred<T>(): Deferred<T> {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => { resolve = done; });
  return { promise, resolve };
}

/** Lets chained promise steps (page, then its baseline, then focus) finish. */
async function settle(): Promise<void> {
  for (let round = 0; round < 4; round += 1) await act(async () => {});
}

async function renderPanel(over: Record<string, unknown> = {}) {
  const props = {
    collectionId: 'nightfall',
    documentId: 'doc-1',
    documentName: 'ledger.pdf',
    operationId: 'op-1',
    ...over,
  };
  const view = render(OcrReviewPanel, props);
  await settle();
  return view;
}

async function click(name: string | RegExp): Promise<void> {
  await fireEvent.click(screen.getByRole('button', { name }));
  await settle();
}

describe('OcrReviewPanel', () => {
  beforeEach(() => {
    vi.resetAllMocks();
    api.listDocumentRevisions.mockResolvedValue({ revisions: [], total: 1, activeRevisionId: 'rev-1' });
    api.listPendingReviews.mockImplementation(async (_c: string, _d: string, _o: string, offset: number) =>
      page([review(offset)], 3));
    api.readSource.mockImplementation(async () => source('Total 123 due'));
    api.readPendingCandidate.mockImplementation(async (_c: string, _d: string, _o: string, unitId: string) =>
      candidate(Number(unitId.replace('unit-', ''))));
    api.decideReviews.mockResolvedValue({ operation: operation(), applied: [] });
    api.publishReviewDecisions.mockResolvedValue({ operation: operation(), publicationId: 'pub-1', phase: 'PUBLISHED' });
  });

  afterEach(() => {
    cleanup();
  });

  describe('showing one page at a time', () => {
    it('reads one bounded page of proposals and only that page\'s baseline, never every page', async () => {
      api.listPendingReviews.mockImplementation(async (_c: string, _d: string, _o: string, offset: number) =>
        page([review(offset)], 40));
      await renderPanel();

      expect(api.listPendingReviews).toHaveBeenCalledTimes(1);
      expect(api.listPendingReviews).toHaveBeenCalledWith('nightfall', 'doc-1', 'op-1', 0, 1);
      expect(api.readSource).toHaveBeenCalledTimes(1);
      expect(api.readSource.mock.calls[0][1]).toBe('unit-0');
      expect(api.readSource.mock.calls[0][4]).toBe('rev-1');
      expect(screen.getByRole('status', { name: 'Position' }).textContent).toContain('1 of 40');
      expect(document.querySelectorAll('img').length).toBe(1);

      await click('Next page');
      expect(api.listPendingReviews).toHaveBeenLastCalledWith('nightfall', 'doc-1', 'op-1', 1, 1);
      expect(api.readSource).toHaveBeenCalledTimes(2);
      expect(screen.getByRole('status', { name: 'Position' }).textContent).toContain('2 of 40');
      expect(api.readPendingCandidate).toHaveBeenCalledTimes(2);
      expect(document.querySelectorAll('img').length).toBe(1);
    });

    it('shows the recommendation, the bounded reasons and the baseline with the differing span marked', async () => {
      await renderPanel();

      const card = screen.getByRole('article', { name: 'Page 1' });
      expect(within(card).getByText(/Uncertain/)).toBeTruthy();
      expect(within(card).getByText(/DIGIT_DIFFERENCE/)).toBeTruthy();
      expect(within(card).getByText('The last digits differ.')).toBeTruthy();
      const marked = card.querySelectorAll('mark');
      expect(marked.length).toBe(1);
      expect(marked[0].textContent).toBe('123');
      expect(card.querySelector('.baseline')?.textContent).toBe('Total 123 due');
    });

    it('shows OCR text literally: markup and script-like text becomes text, never elements', async () => {
      const hostile = '<img src=x onerror="alert(1)"><script>window.hacked=1</script> **bold** &amp;';
      api.readSource.mockResolvedValue(source(hostile));
      api.listPendingReviews.mockResolvedValue(page([review(0, {
        reasons: [{ code: '<b>CODE</b>', origin: 'COMPARISON', explanation: '<script>x()</script>' }],
      })], 1));
      await renderPanel();

      const card = screen.getByRole('article', { name: 'Page 1' });
      expect(card.querySelector('.baseline')?.textContent).toBe(hostile);
      // The only image is the scan itself; the hostile text created no element.
      expect(card.querySelectorAll('img').length).toBe(1);
      expect(card.querySelector('img')?.getAttribute('alt')).toBe('Scanned page 1');
      expect(card.querySelector('script')).toBeNull();
      expect(card.querySelector('b')).toBeNull();
      expect(card.textContent).toContain('<script>x()</script>');
      expect((window as unknown as { hacked?: number }).hacked).toBeUndefined();
    });

    it('flags an image-only page and explains why it is not in search', async () => {
      api.listPendingReviews.mockResolvedValue(page([review(0, {
        baselineRevisionId: null,
        baselineTextHash: null,
        searchable: false,
        reasons: [],
      })], 1));
      await renderPanel();

      expect(screen.getByRole('note', { name: 'Image-only page' }).textContent).toMatch(/not searchable|not in search/i);
      expect(api.readSource).not.toHaveBeenCalled();
      expect((screen.getByRole('button', { name: 'Keep existing' }) as HTMLButtonElement).disabled).toBe(true);
      expect(screen.getByText(/no existing text to keep/i)).toBeTruthy();
    });

    it('shows a load failure with a retry instead of an empty list', async () => {
      api.listPendingReviews.mockRejectedValueOnce(new Error('the server is busy'));
      await renderPanel();

      expect(screen.getByRole('alert').textContent).toContain('the server is busy');
      await click('Try again');
      expect(screen.getByRole('article', { name: 'Page 1' })).toBeTruthy();
    });

    it('resumes from what the server persisted when the panel is opened again', async () => {
      const first = await renderPanel();
      first.unmount();
      api.listPendingReviews.mockResolvedValue(page([review(4)], 2));

      await renderPanel();

      expect(screen.getByRole('article', { name: 'Page 5' })).toBeTruthy();
      expect(api.listPendingReviews).toHaveBeenLastCalledWith('nightfall', 'doc-1', 'op-1', 0, 1);
    });
  });

  describe('deciding', () => {
    it('keeps a manual edit while the reader moves to another page and back', async () => {
      await renderPanel();
      await click('Edit text');
      await fireEvent.input(screen.getByLabelText('Your text for page 1'), { target: { value: 'Total 128 due' } });

      await click('Next page');
      expect(screen.queryByLabelText('Your text for page 1')).toBeNull();
      await click('Previous page');

      expect((screen.getByLabelText('Your text for page 1') as HTMLTextAreaElement).value).toBe('Total 128 due');
      expect(screen.getByRole('status', { name: 'Unsaved decisions' }).textContent).toContain('1');
    });

    it('starts an edit from the new reading and sends the batch with the revision and candidate hash', async () => {
      await renderPanel();
      await click('Edit text');
      const box = screen.getByLabelText('Your text for page 1') as HTMLTextAreaElement;
      expect(box.value).toBe('Total 128 due');
      await fireEvent.input(box, { target: { value: 'Total 128 due.' } });
      await click('Save decisions');

      expect(api.decideReviews).toHaveBeenCalledTimes(1);
      const [collection, document_, operationId, body] = api.decideReviews.mock.calls[0];
      expect([collection, document_, operationId]).toEqual(['nightfall', 'doc-1', 'op-1']);
      expect(body.expectedRevisionId).toBe('rev-1');
      expect(typeof body.requestId).toBe('string');
      expect(body.requestId.length).toBeGreaterThan(0);
      expect(body.decisions).toEqual([{
        unitId: 'unit-0', ordinal: 0, candidateHash: 'cand-0', choice: 'EDIT', text: 'Total 128 due.',
      }]);
      expect(body.documentWide ?? null).toBeNull();
    });

    it('sends Keep existing and Use new without text', async () => {
      await renderPanel();
      await click('Use new');
      await click('Next page');
      await click('Keep existing');
      await click('Save decisions');

      const body = api.decideReviews.mock.calls[0][3];
      expect(body.decisions).toEqual([
        { unitId: 'unit-0', ordinal: 0, candidateHash: 'cand-0', choice: 'USE_NEW' },
        { unitId: 'unit-1', ordinal: 1, candidateHash: 'cand-1', choice: 'KEEP' },
      ]);
    });

    it('refuses an edit with no text instead of sending it', async () => {
      await renderPanel();
      await click('Edit text');
      await fireEvent.input(screen.getByLabelText('Your text for page 1'), { target: { value: '   ' } });
      await click('Save decisions');

      expect(api.decideReviews).not.toHaveBeenCalled();
      expect(screen.getByRole('alert').textContent).toMatch(/write the text for page 1/i);
    });

    it('dispatches a save once however many times it is clicked', async () => {
      const pending = deferred<unknown>();
      api.decideReviews.mockReturnValue(pending.promise);
      await renderPanel();
      await click('Use new');

      const save = screen.getByRole('button', { name: 'Save decisions' });
      await fireEvent.click(save);
      await fireEvent.click(save);
      await fireEvent.click(save);
      await act(async () => {});

      expect(api.decideReviews).toHaveBeenCalledTimes(1);
      expect((screen.getByRole('button', { name: /Saving/ }) as HTMLButtonElement).disabled).toBe(true);
      pending.resolve({ operation: operation(), applied: [] });
      await settle();
      expect(screen.getByRole('button', { name: 'Save decisions' })).toBeTruthy();
    });

    it('disables saving and says why when the document has no active revision to guard', async () => {
      api.listDocumentRevisions.mockResolvedValue({ revisions: [], total: 0, activeRevisionId: null });
      await renderPanel();
      await click('Use new');

      expect((screen.getByRole('button', { name: 'Save decisions' }) as HTMLButtonElement).disabled).toBe(true);
      expect(screen.getByText(/no published revision/i)).toBeTruthy();
    });
  });

  describe('conflicts', () => {
    it('shows a revision conflict, keeps every unsaved decision and edit, and can reload against the current revision', async () => {
      api.decideReviews.mockRejectedValueOnce(
        await apiError('STALE_DOCUMENT_REVISION', 'the document publishes rev-2 now', 409),
      );
      await renderPanel();
      await click('Edit text');
      await fireEvent.input(screen.getByLabelText('Your text for page 1'), { target: { value: 'Total 128 due' } });
      await click('Save decisions');

      expect(screen.getByRole('alert').textContent).toContain('the document publishes rev-2 now');
      expect((screen.getByLabelText('Your text for page 1') as HTMLTextAreaElement).value).toBe('Total 128 due');
      expect(screen.getByRole('status', { name: 'Unsaved decisions' }).textContent).toContain('1');
      // The old revision is not silently replaced: nothing is retried until the reader asks.
      expect(api.decideReviews).toHaveBeenCalledTimes(1);

      api.listDocumentRevisions.mockResolvedValue({ revisions: [], total: 2, activeRevisionId: 'rev-2' });
      await click('Reload with the current revision');
      expect(api.listDocumentRevisions).toHaveBeenCalledTimes(2);
      expect((screen.getByLabelText('Your text for page 1') as HTMLTextAreaElement).value).toBe('Total 128 due');

      await click('Save decisions');
      expect(api.decideReviews).toHaveBeenCalledTimes(2);
      expect(api.decideReviews.mock.calls[1][3].expectedRevisionId).toBe('rev-2');
      expect(api.decideReviews.mock.calls[1][3].decisions[0].text).toBe('Total 128 due');
    });

    it('keeps the edit but asks for a new choice when the page\'s reading changed', async () => {
      api.decideReviews.mockRejectedValueOnce(
        await apiError('STALE_REVIEW_DECISION', 'the text of page 0 is not the text this decision was taken against', 409),
      );
      await renderPanel();
      await click('Edit text');
      await fireEvent.input(screen.getByLabelText('Your text for page 1'), { target: { value: 'Total 128 due' } });
      await click('Save decisions');
      expect(screen.getByRole('alert').textContent).toContain('not the text this decision was taken against');

      api.listPendingReviews.mockResolvedValue(page([review(0, { candidateHash: 'cand-0-new' })], 3));
      await click('Reload with the current revision');

      expect(screen.getByText(/different reading/i)).toBeTruthy();
      expect((screen.getByLabelText('Your text for page 1') as HTMLTextAreaElement).value).toBe('Total 128 due');
      expect((screen.getByRole('button', { name: 'Save decisions' }) as HTMLButtonElement).disabled).toBe(true);
      await click('Edit text');
      await click('Save decisions');
      expect(api.decideReviews.mock.calls[1][3].decisions[0].candidateHash).toBe('cand-0-new');
    });
  });

  describe('the whole document', () => {
    it('states its effect first and sends nothing until it is confirmed', async () => {
      api.listPendingReviews.mockResolvedValue(page([review(0)], 12));
      await renderPanel();

      await click('Use new for the whole document');
      const confirmation = screen.getByRole('alertdialog', { name: 'Confirm whole-document choice' });
      expect(confirmation.textContent).toMatch(/all 12 pages/i);
      expect(confirmation.textContent).toMatch(/not only the page shown/i);
      expect(api.decideReviews).not.toHaveBeenCalled();
      expect(document.activeElement).toBe(within(confirmation).getByRole('button', { name: 'Cancel' }));

      await click('Cancel');
      expect(screen.queryByRole('alertdialog')).toBeNull();
      expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Use new for the whole document' }));
      expect(api.decideReviews).not.toHaveBeenCalled();
    });

    it('saves explicit decisions first and then the document-wide choice, each once', async () => {
      api.listPendingReviews.mockResolvedValue(page([review(0)], 12));
      await renderPanel();
      await click('Keep existing');
      await click('Use new for the whole document');
      const confirm = screen.getByRole('button', { name: 'Use new for all 12 pending pages' });
      await fireEvent.click(confirm);
      await fireEvent.click(confirm);
      await act(async () => {});

      expect(api.decideReviews).toHaveBeenCalledTimes(2);
      expect(api.decideReviews.mock.calls[0][3].decisions).toHaveLength(1);
      expect(api.decideReviews.mock.calls[0][3].documentWide ?? null).toBeNull();
      expect(api.decideReviews.mock.calls[1][3].documentWide).toBe('USE_NEW');
      expect(api.decideReviews.mock.calls[1][3].decisions ?? []).toEqual([]);
      expect(api.decideReviews.mock.calls[0][3].requestId).not.toBe(api.decideReviews.mock.calls[1][3].requestId);
    });

    it('does not offer Edit text for the whole document', async () => {
      await renderPanel();
      expect(screen.queryByRole('button', { name: /Edit text for the whole document/ })).toBeNull();
    });
  });

  describe('publishing', () => {
    it('publishes the decided batch once and reports that searchable text changed only on PUBLISHED', async () => {
      const onpublished = vi.fn();
      const pending = deferred<unknown>();
      api.publishReviewDecisions.mockReturnValue(pending.promise);
      api.listPendingReviews.mockResolvedValue(page([], 0, { pendingReviewCount: 0 }));
      await renderPanel({ onpublished });

      const publish = screen.getByRole('button', { name: 'Publish decisions' });
      await fireEvent.click(publish);
      await fireEvent.click(publish);
      await act(async () => {});
      expect(api.publishReviewDecisions).toHaveBeenCalledTimes(1);
      expect(api.publishReviewDecisions).toHaveBeenCalledWith('nightfall', 'doc-1', 'op-1', 'rev-1');
      expect(screen.queryByText(/Searchable text now/)).toBeNull();

      pending.resolve({ operation: operation(), publicationId: 'pub-1', phase: 'PUBLISHED' });
      await act(async () => {});
      expect(screen.getByRole('status', { name: 'Publication' }).textContent).toMatch(/Searchable text now uses/);
      expect(onpublished).toHaveBeenCalledTimes(1);
    });

    it('does not claim success when the publication is only prepared or was refused', async () => {
      api.listPendingReviews.mockResolvedValue(page([], 0));
      api.publishReviewDecisions.mockResolvedValue({
        operation: operation(), publicationId: 'pub-1', phase: 'REFUSED', errorCode: 'AWAITING_REVIEW',
      });
      const onpublished = vi.fn();
      await renderPanel({ onpublished });
      await click('Publish decisions');

      const status = screen.getByRole('status', { name: 'Publication' }).textContent ?? '';
      expect(status).toContain('REFUSED');
      expect(status).toContain('AWAITING_REVIEW');
      expect(status).toMatch(/search still uses the existing text/i);
      expect(onpublished).not.toHaveBeenCalled();
    });

    it('holds publishing back while pages still wait for a decision', async () => {
      await renderPanel();
      expect((screen.getByRole('button', { name: 'Publish decisions' }) as HTMLButtonElement).disabled).toBe(true);
      expect(screen.getByText(/still waiting for a decision/i)).toBeTruthy();
    });

    it('shows a publication conflict and leaves the decisions where they are', async () => {
      api.listPendingReviews.mockResolvedValue(page([], 0));
      api.publishReviewDecisions.mockRejectedValue(
        await apiError('STALE_DOCUMENT_REVISION', 'the document publishes rev-2 now', 409),
      );
      await renderPanel();
      await click('Publish decisions');

      expect(screen.getByRole('alert').textContent).toContain('the document publishes rev-2 now');
      expect(screen.getByRole('button', { name: 'Reload with the current revision' })).toBeTruthy();
    });
  });

  describe('late answers for something no longer shown', () => {
    it('ignores a review page that arrives after another document was selected', async () => {
      const late = deferred<ReviewsResponse>();
      api.listPendingReviews
        .mockImplementationOnce(() => late.promise)
        .mockResolvedValue(page([review(7)], 1));
      const view = await renderPanel();

      await view.rerender({ collectionId: 'nightfall', documentId: 'doc-2', documentName: 'other.pdf', operationId: 'op-2' });
      await act(async () => {});
      late.resolve(page([review(0)], 1));
      await act(async () => {});

      expect(screen.queryByRole('article', { name: 'Page 1' })).toBeNull();
      expect(screen.getByRole('article', { name: 'Page 8' })).toBeTruthy();
    });

    it('ignores a baseline text that arrives after the reader moved to another page', async () => {
      const late = deferred<ReturnType<typeof source>>();
      api.readSource
        .mockImplementationOnce(() => late.promise)
        .mockResolvedValue(source('second page text'));
      await renderPanel();
      await click('Next page');
      late.resolve(source('FIRST PAGE TEXT'));
      await act(async () => {});

      expect(document.body.textContent).not.toContain('FIRST PAGE TEXT');
      expect(document.querySelector('.baseline')?.textContent).toBe('second page text');
    });

    it('drops unsaved decisions of a document that is no longer shown, and never sends them to the new one', async () => {
      const view = await renderPanel();
      await click('Use new');
      await view.rerender({ collectionId: 'dawn', documentId: 'doc-9', documentName: 'dawn.pdf', operationId: 'op-9' });
      await act(async () => {});

      expect(screen.getByRole('status', { name: 'Unsaved decisions' }).textContent).toContain('0');
      expect((screen.getByRole('button', { name: 'Save decisions' }) as HTMLButtonElement).disabled).toBe(true);
      expect(api.decideReviews).not.toHaveBeenCalled();
    });

    it('ignores a save result that arrives after another document was selected', async () => {
      const pending = deferred<unknown>();
      api.decideReviews.mockReturnValue(pending.promise);
      const view = await renderPanel();
      await click('Use new');
      await click('Save decisions');
      await view.rerender({ collectionId: 'dawn', documentId: 'doc-9', documentName: 'dawn.pdf', operationId: 'op-9' });
      await act(async () => {});
      pending.resolve({ operation: operation(), applied: [] });
      await act(async () => {});

      expect(screen.queryByText(/Saved/)).toBeNull();
      expect((screen.getByRole('button', { name: 'Publish decisions' }) as HTMLButtonElement).disabled).toBe(true);
    });
  });

  describe('keyboard use', () => {
    it('focuses the heading on open and returns control through Close review', async () => {
      const onclose = vi.fn();
      await renderPanel({ onclose });

      expect(document.activeElement).toBe(screen.getByRole('heading', { name: 'Review pages' }));
      await click('Close review');
      expect(onclose).toHaveBeenCalledTimes(1);
    });

    it('moves focus to the page heading after moving between pages and into the edit box on Edit text', async () => {
      await renderPanel();
      await click('Next page');
      expect(document.activeElement).toBe(screen.getByRole('heading', { name: 'Page 2' }));

      await click('Edit text');
      expect(document.activeElement).toBe(screen.getByLabelText('Your text for page 2'));
    });

    it('moves focus to the result after saving so the outcome is read', async () => {
      await renderPanel();
      await click('Use new');
      await click('Save decisions');

      const result = screen.getByRole('status', { name: 'Result' });
      expect(result.textContent).toMatch(/Saved/);
      expect(document.activeElement).toBe(result);
    });

    it('marks the chosen action with aria-pressed so it is announced', async () => {
      await renderPanel();
      await click('Use new');

      expect(screen.getByRole('button', { name: 'Use new' }).getAttribute('aria-pressed')).toBe('true');
      expect(screen.getByRole('button', { name: 'Keep existing' }).getAttribute('aria-pressed')).toBe('false');
    });
  });

  describe('page image', () => {
    it('shows the image of the current page only, by its opaque route, with alternative text', async () => {
      await renderPanel();

      const images = document.querySelectorAll('img');
      expect(images.length).toBe(1);
      const image = screen.getByRole('img', { name: 'Scanned page 1' });
      expect(image.getAttribute('src')).toBe(
        '/api/collections/nightfall/documents/doc-1/ocr/reviews/unit-0/image?operationId=op-1',
      );

      await click('Next page');
      expect(document.querySelectorAll('img').length).toBe(1);
      expect(screen.getByRole('img', { name: 'Scanned page 2' }).getAttribute('src')).toContain('/reviews/unit-1/image');
    });

    it('says so when the image cannot be loaded, even though the listing said it was available', async () => {
      await renderPanel();
      await fireEvent.error(screen.getByRole('img', { name: 'Scanned page 1' }));
      await settle();

      expect(screen.queryByRole('img', { name: 'Scanned page 1' })).toBeNull();
      expect(screen.getByText('The page image could not be loaded.')).toBeTruthy();
      // The text decision is still possible.
      expect((screen.getByRole('button', { name: 'Use new' }) as HTMLButtonElement).disabled).toBe(false);

      await click('Next page');
      expect(screen.getByRole('img', { name: 'Scanned page 2' })).toBeTruthy();
      expect(screen.queryByText('The page image could not be loaded.')).toBeNull();
    });

    it('does not request an image when the listing says there is none', async () => {
      api.listPendingReviews.mockResolvedValue(page([review(0, { imageAvailable: false })], 1));
      await renderPanel();

      expect(document.querySelectorAll('img').length).toBe(0);
      expect(screen.getByText(/no page image is available for this page/i)).toBeTruthy();
    });
  });

  describe('new reading and differences', () => {
    it('shows the new reading and a plain-text difference against the existing text', async () => {
      await renderPanel();

      const card = screen.getByRole('article', { name: 'Page 1' });
      expect(api.readPendingCandidate).toHaveBeenCalledWith('nightfall', 'doc-1', 'op-1', 'unit-0');
      expect(card.querySelector('.candidate')?.textContent).toBe('Total 128 due');
      const removed = card.querySelectorAll('.diff del');
      const added = card.querySelectorAll('.diff ins');
      expect(Array.from(removed).map((node) => node.textContent)).toEqual(['123']);
      expect(Array.from(added).map((node) => node.textContent)).toEqual(['128']);
      expect(within(card).getByText(/removed text is struck through/i)).toBeTruthy();
    });

    it('shows hostile candidate text literally, in both the reading and the difference', async () => {
      const hostile = '<img src=x onerror="alert(2)"> <script>window.hacked2=1</script> done';
      api.readPendingCandidate.mockResolvedValue(candidate(0, hostile));
      await renderPanel();

      const card = screen.getByRole('article', { name: 'Page 1' });
      expect(card.querySelector('.candidate')?.textContent).toBe(hostile);
      expect(card.querySelector('.diff')?.textContent).toContain('<script>window.hacked2=1</script>');
      expect(card.querySelector('script')).toBeNull();
      expect(card.querySelectorAll('img').length).toBe(1);
      expect(card.querySelector('img')?.getAttribute('alt')).toBe('Scanned page 1');
      expect((window as unknown as { hacked2?: number }).hacked2).toBeUndefined();
    });

    it('says the display is cut when the candidate is truncated, and still allows Use new', async () => {
      api.readPendingCandidate.mockResolvedValue(candidate(0, 'Total 128', { totalChars: 900_000, truncated: true }));
      await renderPanel();

      expect(screen.getByText(/only the first 9 of 900000 characters of the new reading are shown/i)).toBeTruthy();
      expect(screen.getByText(/differences are not shown/i)).toBeTruthy();
      await click('Use new');
      await click('Save decisions');
      expect(api.decideReviews.mock.calls[0][3].decisions[0]).toEqual({
        unitId: 'unit-0', ordinal: 0, candidateHash: 'cand-0', choice: 'USE_NEW',
      });
    });

    it('does not prefill an edit with cut text: it starts from the existing text instead', async () => {
      api.readPendingCandidate.mockResolvedValue(candidate(0, 'Total 128', { totalChars: 900_000, truncated: true }));
      await renderPanel();
      await click('Edit text');

      expect((screen.getByLabelText('Your text for page 1') as HTMLTextAreaElement).value).toBe('Total 123 due');
    });

    it('falls back to the two texts side by side when they are too long to compare', async () => {
      const left = Array.from({ length: 6000 }, (_, index) => `a${index}`).join(' ');
      const right = Array.from({ length: 6000 }, (_, index) => `b${index}`).join(' ');
      api.readSource.mockResolvedValue(source(left));
      api.readPendingCandidate.mockResolvedValue(candidate(0, right));
      await renderPanel();

      const card = screen.getByRole('article', { name: 'Page 1' });
      expect(card.querySelector('.diff')).toBeNull();
      expect(within(card).getByText(/too long to compare word by word/i)).toBeTruthy();
      expect(card.querySelector('.baseline')?.textContent).toBe(left);
      expect(card.querySelector('.candidate')?.textContent).toBe(right);
    });

    it('does not diff against a missing existing text', async () => {
      api.listPendingReviews.mockResolvedValue(page([review(0, {
        baselineRevisionId: null, baselineTextHash: null, searchable: false, reasons: [],
      })], 1));
      await renderPanel();

      expect(screen.getByRole('article', { name: 'Page 1' }).querySelector('.candidate')?.textContent).toBe('Total 128 due');
      expect(document.querySelector('.diff')).toBeNull();
    });

    it('keeps the existing text and decisions usable when the new reading cannot be read', async () => {
      api.readPendingCandidate.mockRejectedValue(new Error('candidate unavailable'));
      await renderPanel();

      expect(screen.getByText(/new reading could not be read: candidate unavailable/i)).toBeTruthy();
      expect(document.querySelector('.baseline')?.textContent).toBe('Total 123 due');
      await click('Keep existing');
      expect(screen.getByRole('button', { name: 'Keep existing' }).getAttribute('aria-pressed')).toBe('true');
    });

    it('refreshes once when the candidate hash differs from the listing, and never decides against it', async () => {
      api.readPendingCandidate
        .mockResolvedValueOnce(candidate(0, 'Total 128 due', { candidateHash: 'cand-other' }))
        .mockResolvedValue(candidate(0));
      await renderPanel();

      expect(api.listPendingReviews).toHaveBeenCalledTimes(2);
      expect(api.readPendingCandidate).toHaveBeenCalledTimes(2);
      expect(document.querySelector('.candidate')?.textContent).toBe('Total 128 due');
    });

    it('stops refreshing and says so when the hashes keep differing', async () => {
      api.readPendingCandidate.mockResolvedValue(candidate(0, 'x', { candidateHash: 'cand-other' }));
      await renderPanel();

      expect(api.listPendingReviews.mock.calls.length).toBeLessThanOrEqual(2);
      expect(screen.getByText(/reading of this page changed/i)).toBeTruthy();
      expect(document.querySelector('.candidate')).toBeNull();
    });

    it('drops a candidate that arrives after the reader moved to another page', async () => {
      const late = deferred<ReturnType<typeof candidate>>();
      api.readPendingCandidate.mockImplementationOnce(() => late.promise);
      await renderPanel();
      await click('Next page');
      late.resolve(candidate(0, 'STALE TEXT'));
      await settle();

      expect(document.querySelector('.candidate')?.textContent).toBe('Total 128 due');
      expect(document.body.textContent).not.toContain('STALE TEXT');
    });
  });

  describe('publishing decided pages after a reload', () => {
    it('offers Publish with 0 pending and decided pages waiting, and says how many', async () => {
      api.listPendingReviews.mockResolvedValue(page([], 0, { decidedUnpublishedCount: 3 }));
      await renderPanel();

      expect(screen.getByText('3 decided pages are waiting to be published.')).toBeTruthy();
      expect((screen.getByRole('button', { name: 'Publish decisions' }) as HTMLButtonElement).disabled).toBe(false);
    });

    it('uses the singular for one decided page', async () => {
      api.listPendingReviews.mockResolvedValue(page([], 0, { decidedUnpublishedCount: 1 }));
      await renderPanel();

      expect(screen.getByText('1 decided page is waiting to be published.')).toBeTruthy();
    });

    it('keeps Publish disabled while pages are still pending, though decided pages wait', async () => {
      api.listPendingReviews.mockImplementation(async (_c: string, _d: string, _o: string, offset: number) =>
        page([review(offset)], 2, { decidedUnpublishedCount: 2 }));
      await renderPanel();

      expect((screen.getByRole('button', { name: 'Publish decisions' }) as HTMLButtonElement).disabled).toBe(true);
      expect(screen.getByText('2 decided pages are waiting to be published.')).toBeTruthy();
    });

    it('clears the waiting note after a successful publish', async () => {
      api.listPendingReviews.mockResolvedValue(page([], 0, { decidedUnpublishedCount: 2 }));
      await renderPanel();
      await click('Publish decisions');

      expect(screen.getByRole('status', { name: 'Publication' }).textContent).toMatch(/Searchable text now uses/);
      expect(screen.queryByText(/waiting to be published/)).toBeNull();
    });

    it('treats an older server without the count as zero', async () => {
      const old = page([], 0);
      delete (old as { decidedUnpublishedCount?: number }).decidedUnpublishedCount;
      api.listPendingReviews.mockResolvedValue(old);
      await renderPanel();

      expect(screen.queryByText(/waiting to be published/)).toBeNull();
    });
  });
});
