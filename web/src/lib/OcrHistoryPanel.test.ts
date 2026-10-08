import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/svelte';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import OcrHistoryPanel from './OcrHistoryPanel.svelte';
import type { RestoreOperation, RevisionView, RevisionsResponse } from './api';

const api = vi.hoisted(() => ({
  listDocumentRevisions: vi.fn(),
  restoreRevision: vi.fn(),
  getRestoreOperation: vi.fn(),
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
  listDocumentRevisions: api.listDocumentRevisions,
  restoreRevision: api.restoreRevision,
  getRestoreOperation: api.getRestoreOperation,
}));

async function apiError(code: string, message: string, status: number): Promise<Error> {
  const { ApiError } = await import('./api');
  return new ApiError(code, message, status);
}

const NO_CHANGES = { unchanged: 0, added: 0, automatic: 0, manual: 0, unknown: 0, restored: 0, notPublished: 0 };

function revision(id: string, over: Partial<RevisionView> = {}): RevisionView {
  return {
    revisionId: id,
    parentRevisionId: null,
    state: 'PUBLISHED',
    provenance: 'IMPORT',
    createdAt: '2026-09-01T08:00:00Z',
    publishedAt: '2026-09-01T08:01:00Z',
    active: false,
    pageCount: 12,
    extractionMethods: [],
    pageChanges: { ...NO_CHANGES, added: 12 },
    ...over,
  };
}

/** Newest first, as the server answers: a restore of the import on top of a rescan on top of the import. */
function history(over: Partial<RevisionsResponse> = {}): RevisionsResponse {
  return {
    revisions: [
      revision('rev-3333333333', {
        provenance: 'RESTORE',
        active: true,
        restoredFromRevisionId: 'rev-1111111111',
        parentRevisionId: 'rev-2222222222',
        createdAt: '2026-09-03T08:00:00Z',
        publishedAt: '2026-09-03T08:01:00Z',
        pageChanges: { ...NO_CHANGES, restored: 5, unchanged: 7 },
      }),
      revision('rev-2222222222', {
        provenance: 'RESCAN',
        parentRevisionId: 'rev-1111111111',
        createdAt: '2026-09-02T08:00:00Z',
        publishedAt: '2026-09-02T08:01:00Z',
        reading: {
          engine: 'LLM',
          mode: 'CHECK_AND_IMPROVE',
          language: 'eng',
          transcriptionModel: 'vision-model',
          reviewModel: 'review-model',
          toolVersion: null,
          modelVersion: null,
        },
        pageChanges: { ...NO_CHANGES, automatic: 3, manual: 2, unknown: 1, unchanged: 6 },
      }),
      revision('rev-1111111111'),
    ],
    total: 3,
    activeRevisionId: 'rev-3333333333',
    ...over,
  };
}

function restoreOperation(over: Partial<RestoreOperation> = {}): RestoreOperation {
  return {
    restoreId: 'restore-1',
    documentId: 'doc-1',
    requestId: 'ignored',
    expectedRevisionId: 'rev-3333333333',
    restoredFromRevisionId: 'rev-2222222222',
    newRevisionId: 'rev-4444444444',
    phase: 'PUBLISHED',
    createdAt: '2026-09-04T08:00:00Z',
    updatedAt: '2026-09-04T08:00:02Z',
    ...over,
  };
}

async function renderPanel(over: { collectionId?: string; documentId?: string } = {}) {
  const view = render(OcrHistoryPanel, {
    collectionId: over.collectionId ?? 'nightfall',
    documentId: over.documentId ?? 'doc-1',
    documentName: 'ledger.pdf',
    pollMillis: 5,
  });
  await act(async () => {});
  return view;
}

async function openHistory(): Promise<void> {
  await fireEvent.click(screen.getByRole('button', { name: 'Text history' }));
  await act(async () => {});
}

function versions(): HTMLElement[] {
  return within(screen.getByRole('list', { name: 'Text versions' })).getAllByRole('listitem');
}

async function openConfirmation(short = 'rev-2222'): Promise<HTMLElement> {
  await fireEvent.click(screen.getByRole('button', { name: `Restore version ${short}` }));
  await act(async () => {});
  return screen.getByRole('group', { name: 'Confirm restore' });
}

async function confirm(): Promise<void> {
  await fireEvent.click(screen.getByRole('button', { name: 'Restore this version' }));
  await act(async () => {});
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

describe('text history', () => {
  beforeEach(() => {
    vi.resetAllMocks();
    api.listDocumentRevisions.mockResolvedValue(history());
  });

  afterEach(() => {
    cleanup();
    vi.useRealTimers();
  });

  describe('listing', () => {
    it('reads nothing until the history is opened, then lists every version newest first', async () => {
      await renderPanel();
      expect(api.listDocumentRevisions).not.toHaveBeenCalled();

      await openHistory();

      expect(api.listDocumentRevisions).toHaveBeenCalledWith('nightfall', 'doc-1', 0, 200);
      const items = versions();
      expect(items).toHaveLength(3);
      expect(items[0].textContent).toContain('rev-3333');
      expect(items[1].textContent).toContain('rev-2222');
      expect(items[2].textContent).toContain('rev-1111');
    });

    it('marks the active version and gives it no Restore action', async () => {
      await renderPanel();
      await openHistory();

      const [active, rescan, original] = versions();
      expect(active.textContent).toContain('Active version');
      expect(within(active).queryByRole('button', { name: /^Restore version/ })).toBeNull();
      expect(rescan.textContent).not.toContain('Active version');
      expect(within(rescan).getByRole('button', { name: 'Restore version rev-2222' })).toBeTruthy();
      expect(within(original).getByRole('button', { name: 'Restore version rev-1111' })).toBeTruthy();
    });

    it('names where each version came from, saying which version a restore copied', async () => {
      await renderPanel();
      await openHistory();

      const [restored, rescan, original] = versions();
      expect(restored.textContent).toContain('Restored from rev-1111');
      expect(rescan.textContent).toContain('Rescan');
      expect(original.textContent).toContain('Import');
    });

    it('shows engine, model, mode and language from the recorded reading', async () => {
      await renderPanel();
      await openHistory();

      const rescan = versions()[1];
      expect(rescan.textContent).toContain('Image model profile');
      expect(rescan.textContent).toContain('vision-model');
      expect(rescan.textContent).toContain('review-model');
      expect(rescan.textContent).toContain('Check and improve existing text');
      expect(rescan.textContent).toContain('eng');
    });

    it('says the engine was not recorded when there is no reading, and invents none', async () => {
      await renderPanel();
      await openHistory();

      for (const item of [versions()[0], versions()[2]]) {
        expect(item.textContent).toMatch(/engine and model\s*not recorded/i);
        expect(item.textContent).not.toContain('Tesseract');
        expect(item.textContent).not.toContain('Surya');
      }
    });

    it('names the engine, mode, language and tool version an import was read with', async () => {
      api.listDocumentRevisions.mockResolvedValue(
        history({
          revisions: [
            revision('rev-1111111111', {
              provenance: 'IMPORT',
              active: true,
              extractionMethods: ['OCR'],
              reading: {
                engine: 'TESSERACT',
                mode: 'FILL_MISSING',
                language: 'swe',
                toolVersion: 'tesseract 5.5',
                modelVersion: null,
                transcriptionModel: null,
                reviewModel: null,
              },
            }),
          ],
          total: 1,
          activeRevisionId: 'rev-1111111111',
        }),
      );
      await renderPanel();
      await openHistory();

      const [imported] = versions();
      expect(imported.textContent).toContain('Import');
      expect(imported.textContent).toMatch(/engine and model\s*Tesseract/i);
      expect(imported.textContent).toContain('language swe');
      expect(imported.textContent).toContain('Tool version: tesseract 5.5');
      expect(imported.textContent).not.toContain('Not recorded');
    });

    it('says no page needed OCR when a version was read without OCR, and names no engine', async () => {
      api.listDocumentRevisions.mockResolvedValue(
        history({
          revisions: [
            revision('rev-1111111111', {
              provenance: 'IMPORT',
              active: true,
              extractionMethods: ['DIRECT_TEXT'],
              noOcrNeeded: true,
            }),
          ],
          total: 1,
          activeRevisionId: 'rev-1111111111',
        }),
      );
      await renderPanel();
      await openHistory();

      const [imported] = versions();
      expect(imported.textContent).toMatch(/engine and model\s*no page needed OCR/i);
      expect(imported.textContent).not.toContain('Tesseract');
      expect(imported.textContent).not.toContain('Not recorded');
    });

    it('shows created and published times and the page count', async () => {
      await renderPanel();
      await openHistory();

      const rescan = versions()[1];
      expect(rescan.textContent).toContain('2026-09-02T08:00:00Z');
      expect(rescan.textContent).toContain('2026-09-02T08:01:00Z');
      expect(rescan.textContent).toContain('12 pages');
    });

    it('says a version with no publication time was not published at a recorded time', async () => {
      api.listDocumentRevisions.mockResolvedValue(history({
        revisions: [revision('rev-9999999999', { active: true, publishedAt: null })],
        total: 1,
        activeRevisionId: 'rev-9999999999',
      }));
      await renderPanel();
      await openHistory();

      expect(versions()[0].textContent).toMatch(/published\s*not recorded/i);
    });

    it('summarises page changes honestly and says manual and automatic are inferred', async () => {
      await renderPanel();
      await openHistory();

      const rescan = versions()[1];
      expect(rescan.textContent).toContain('3 automatic');
      expect(rescan.textContent).toContain('2 manual');
      expect(rescan.textContent).toContain('1 unknown');
      expect(rescan.textContent).toContain('6 unchanged');
      expect(versions()[0].textContent).toContain('5 restored');
      expect(screen.getByText(/inferred from the reviews that were recorded/i)).toBeTruthy();
    });

    it('renders every server string as text, never as markup', async () => {
      const hostile = '<img src=x onerror="window.__pwned=1"><script>window.__pwned=2</script>';
      api.listDocumentRevisions.mockResolvedValue(history({
        revisions: [
          revision('rev-3333333333', {
            active: true,
            provenance: hostile,
            reading: {
              engine: hostile,
              mode: hostile,
              language: hostile,
              transcriptionModel: hostile,
              reviewModel: '<b>bold</b>',
            },
          }),
        ],
        total: 1,
      }));
      const { container } = await renderPanel();
      await openHistory();

      expect(container.querySelector('img')).toBeNull();
      expect(container.querySelector('script')).toBeNull();
      expect(container.querySelector('b')).toBeNull();
      expect(container.textContent).toContain(hostile);
      expect(container.textContent).toContain('<b>bold</b>');
      expect((window as unknown as { __pwned?: number }).__pwned).toBeUndefined();
    });

    it('offers older versions when the history has more than one page, and appends them', async () => {
      api.listDocumentRevisions
        .mockResolvedValueOnce(history({ total: 4 }))
        .mockResolvedValueOnce({
          revisions: [revision('rev-0000000000', { createdAt: '2026-08-01T08:00:00Z' })],
          total: 4,
          activeRevisionId: 'rev-3333333333',
        });
      await renderPanel();
      await openHistory();
      expect(screen.getByText(/Showing 3 of 4 versions/)).toBeTruthy();

      await fireEvent.click(screen.getByRole('button', { name: 'Show older versions' }));
      await act(async () => {});

      expect(api.listDocumentRevisions).toHaveBeenLastCalledWith('nightfall', 'doc-1', 3, 200);
      expect(versions()).toHaveLength(4);
      expect(screen.queryByRole('button', { name: 'Show older versions' })).toBeNull();
    });
  });

  describe('loading', () => {
    it('shows a failed load with a retry, never an empty list, and retry reads again', async () => {
      api.listDocumentRevisions
        .mockRejectedValueOnce(await apiError('INTERNAL', 'the archive is busy', 500))
        .mockResolvedValueOnce(history());
      await renderPanel();
      await openHistory();

      expect(screen.getByRole('alert').textContent).toContain('the archive is busy');
      expect(screen.queryByRole('list', { name: 'Text versions' })).toBeNull();
      expect(screen.queryByText(/no text versions/i)).toBeNull();

      await fireEvent.click(screen.getByRole('button', { name: 'Try again' }));
      await act(async () => {});

      expect(screen.queryByRole('alert')).toBeNull();
      expect(versions()).toHaveLength(3);
    });

    it('says plainly when the document has no published version', async () => {
      api.listDocumentRevisions.mockResolvedValue({ revisions: [], total: 0, activeRevisionId: null });
      await renderPanel();
      await openHistory();

      expect(screen.getByText(/no published text versions/i)).toBeTruthy();
    });

    it('drops a late answer for the document that was shown before', async () => {
      const first = deferred<RevisionsResponse>();
      api.listDocumentRevisions.mockImplementation((_c: string, documentId: string) => (
        documentId === 'doc-1'
          ? first.promise
          : Promise.resolve(history({
            revisions: [revision('rev-bbbbbbbbbb', { active: true })],
            total: 1,
            activeRevisionId: 'rev-bbbbbbbbbb',
          }))
      ));
      const view = await renderPanel();
      await openHistory();

      await view.rerender({ collectionId: 'nightfall', documentId: 'doc-2', documentName: 'other.pdf' });
      await act(async () => {});
      await openHistory();
      expect(versions()).toHaveLength(1);

      await act(async () => first.resolve(history()));

      expect(versions()).toHaveLength(1);
      expect(versions()[0].textContent).toContain('rev-bbbb');
      expect(screen.queryByText(/rev-3333/)).toBeNull();
    });

    it('reload reads the server list again', async () => {
      await renderPanel();
      await openHistory();
      api.listDocumentRevisions.mockResolvedValue(history({
        revisions: [revision('rev-5555555555', { active: true })],
        total: 1,
        activeRevisionId: 'rev-5555555555',
      }));

      await fireEvent.click(screen.getByRole('button', { name: 'Reload history' }));
      await act(async () => {});

      expect(versions()).toHaveLength(1);
      expect(versions()[0].textContent).toContain('rev-5555');
    });
  });

  describe('restoring', () => {
    it('asks first, naming the version and what a restore does and does not do', async () => {
      await renderPanel();
      await openHistory();

      const dialog = await openConfirmation();

      expect(api.restoreRevision).not.toHaveBeenCalled();
      expect(dialog.textContent).toContain('rev-2222');
      expect(dialog.textContent).toMatch(/nothing is rescanned/i);
      expect(dialog.textContent).toMatch(/original file is not changed/i);
      expect(dialog.textContent).toMatch(/history is kept/i);
      expect(dialog.textContent).toMatch(/only after it is indexed/i);
      expect(dialog.textContent).toMatch(/search always uses the active version/i);
    });

    it('moves focus into the confirmation and returns it to the Restore button on cancel', async () => {
      await renderPanel();
      await openHistory();
      const trigger = screen.getByRole('button', { name: 'Restore version rev-2222' });
      trigger.focus();

      const dialog = await openConfirmation();
      expect(document.activeElement).toBe(within(dialog).getByRole('heading'));

      await fireEvent.click(within(dialog).getByRole('button', { name: 'Cancel' }));
      await act(async () => {});

      expect(screen.queryByRole('group', { name: 'Confirm restore' })).toBeNull();
      expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Restore version rev-2222' }));
      expect(api.restoreRevision).not.toHaveBeenCalled();
    });

    it('sends a request id, the active revision it was loaded from and the version to restore', async () => {
      api.restoreRevision.mockResolvedValue(restoreOperation());
      await renderPanel();
      await openHistory();
      await openConfirmation();

      await confirm();

      expect(api.restoreRevision).toHaveBeenCalledTimes(1);
      const [collection, document, requestId, expected, restore] = api.restoreRevision.mock.calls[0];
      expect([collection, document, expected, restore]).toEqual([
        'nightfall',
        'doc-1',
        'rev-3333333333',
        'rev-2222222222',
      ]);
      expect(typeof requestId).toBe('string');
      expect(requestId.length).toBeGreaterThan(8);
    });

    it('dispatches once however often Restore this version is clicked', async () => {
      const pending = deferred<RestoreOperation>();
      api.restoreRevision.mockReturnValue(pending.promise);
      await renderPanel();
      await openHistory();
      await openConfirmation();

      const button = screen.getByRole('button', { name: /^Restor(e this version|ing)/ });
      await fireEvent.click(button);
      await fireEvent.click(button);
      await fireEvent.click(button);
      await act(async () => {});

      expect(api.restoreRevision).toHaveBeenCalledTimes(1);
      expect(screen.getByRole('button', { name: 'Restoring…' })).toHaveProperty('disabled', true);
      for (const other of screen.getAllByRole('button', { name: /^Restore version/ })) {
        expect(other).toHaveProperty('disabled', true);
      }
      await act(async () => pending.resolve(restoreOperation()));
    });

    it('on success says the new version exists, refreshes the list and returns focus', async () => {
      api.restoreRevision.mockResolvedValue(restoreOperation());
      await renderPanel();
      await openHistory();
      await openConfirmation();
      api.listDocumentRevisions.mockResolvedValue(history({
        revisions: [
          revision('rev-4444444444', {
            active: true,
            provenance: 'RESTORE',
            restoredFromRevisionId: 'rev-2222222222',
          }),
          ...history().revisions.map((entry) => ({ ...entry, active: false })),
        ],
        total: 4,
        activeRevisionId: 'rev-4444444444',
      }));

      await confirm();
      await act(async () => {});

      expect(screen.getByRole('status').textContent).toMatch(/restored/i);
      expect(screen.getByRole('status').textContent).toContain('rev-4444');
      expect(api.listDocumentRevisions).toHaveBeenCalledTimes(2);
      expect(versions()).toHaveLength(4);
      expect(versions()[0].textContent).toContain('Active version');
      expect(screen.queryByRole('group', { name: 'Confirm restore' })).toBeNull();
      // The version that was restored from has its own Restore button again, and it holds focus's place.
      expect(document.activeElement?.textContent ?? '').toBeTruthy();
      expect(document.activeElement).not.toBe(document.body);
    });

    it('follows a restore that is still staged until the server says how it ended', async () => {
      api.restoreRevision.mockResolvedValue(restoreOperation({ phase: 'STAGED' }));
      api.getRestoreOperation
        .mockResolvedValueOnce(restoreOperation({ phase: 'STAGED' }))
        .mockResolvedValueOnce(restoreOperation({ phase: 'PUBLISHED' }));
      await renderPanel();
      await openHistory();
      await openConfirmation();

      await confirm();
      expect(screen.getByRole('status').textContent).toMatch(/indexing|in progress|restoring/i);
      await act(async () => { await new Promise((resolve) => setTimeout(resolve, 40)); });

      expect(api.getRestoreOperation).toHaveBeenCalledWith('nightfall', 'doc-1', 'restore-1');
      expect(screen.getByRole('status').textContent).toMatch(/restored/i);
    });

    it('reports a restore recorded as failed, with the current text still active', async () => {
      api.restoreRevision.mockResolvedValue(restoreOperation({
        phase: 'FAILED',
        errorCode: 'RESTORE_EMBEDDING_FAILED',
        errorMessage: '<b>model said no</b>',
      }));
      await renderPanel();
      await openHistory();
      await openConfirmation();

      await confirm();

      const alert = screen.getByRole('alert');
      expect(alert.textContent).toMatch(/not restored/i);
      expect(alert.textContent).toMatch(/current (text|version) is still/i);
      expect(alert.querySelector('b')).toBeNull();
    });

    it('treats a stale list as a conflict: clear message, nothing changed, Reload reads again', async () => {
      api.restoreRevision.mockRejectedValue(await apiError('STALE_DOCUMENT_REVISION', 'the document publishes another revision', 409));
      await renderPanel();
      await openHistory();
      await openConfirmation();

      await confirm();

      expect(screen.getByRole('alert').textContent).toMatch(/changed since this list was loaded/i);
      expect(screen.getByRole('alert').textContent).toMatch(/nothing was restored/i);
      expect(versions()).toHaveLength(3);

      api.listDocumentRevisions.mockResolvedValue(history({
        revisions: [revision('rev-7777777777', { active: true })],
        total: 1,
        activeRevisionId: 'rev-7777777777',
      }));
      await fireEvent.click(screen.getByRole('button', { name: 'Reload history' }));
      await act(async () => {});

      expect(versions()).toHaveLength(1);
      expect(screen.queryByRole('alert')).toBeNull();
    });

    it.each([
      ['COLLECTION_NOT_ACTIVE', 409, /collection is being deleted/i],
      ['DOCUMENT_BEING_DELETED', 409, /document is being deleted/i],
      ['OCR_ALREADY_RUNNING', 409, /scan of this document is in progress/i],
      ['RESTORE_ALREADY_ACTIVE', 409, /already the active version/i],
      ['RESTORE_NOTHING_PUBLISHED', 409, /published no text/i],
      ['RESTORE_IN_PROGRESS', 409, /another restore.*has not finished/i],
      ['RESTORE_EMBEDDING_UNAVAILABLE', 503, /cannot index right now/i],
      ['RESTORE_EMBEDDING_FAILED', 503, /cannot index right now|could not be indexed/i],
      ['RESTORE_PUBLICATION_REFUSED', 409, /could not be published/i],
      ['RESTORE_INTERRUPTED', 409, /was interrupted/i],
      ['NOT_FOUND', 404, /no longer exists|unknown version/i],
    ])('explains the refusal %s in plain words', async (code, status, expected) => {
      api.restoreRevision.mockRejectedValue(await apiError(code, `server words for ${code}`, status));
      await renderPanel();
      await openHistory();
      await openConfirmation();

      await confirm();

      const alert = screen.getByRole('alert');
      expect(alert.textContent).toMatch(expected);
      expect(alert.textContent).toMatch(/current (text|version) is (still|unchanged)|still the active|nothing was restored/i);
      // The refused request does not leave the confirmation stuck in flight.
      expect(screen.queryByRole('button', { name: 'Restoring…' })).toBeNull();
    });

    it('shows the server message as text for a code it does not know', async () => {
      api.restoreRevision.mockRejectedValue(await apiError('SOMETHING_NEW', '<i>odd</i> failure', 409));
      const { container } = await renderPanel();
      await openHistory();
      await openConfirmation();

      await confirm();

      expect(screen.getByRole('alert').textContent).toContain('<i>odd</i> failure');
      expect(container.querySelector('i')).toBeNull();
    });

    it('reuses the request id for an identical retry and makes a new one for another restore', async () => {
      api.restoreRevision
        .mockRejectedValueOnce(new Error('network down'))
        .mockRejectedValueOnce(await apiError('RESTORE_IN_PROGRESS', 'busy', 409))
        .mockResolvedValue(restoreOperation());
      await renderPanel();
      await openHistory();
      await openConfirmation();
      await confirm();
      expect(screen.getByRole('alert').textContent).toContain('network down');

      await confirm();
      const first = api.restoreRevision.mock.calls[0][2];
      expect(api.restoreRevision.mock.calls[1][2]).toBe(first);

      await fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
      await act(async () => {});
      await openConfirmation('rev-1111');
      await confirm();
      expect(api.restoreRevision.mock.calls[2][2]).not.toBe(first);
      expect(api.restoreRevision.mock.calls[2][4]).toBe('rev-1111111111');
    });

    it('asks for a new request when the server says the request id was used for another restore', async () => {
      api.restoreRevision
        .mockRejectedValueOnce(await apiError('RESTORE_REQUEST_CONFLICT', 'used for a different restore', 409))
        .mockResolvedValue(restoreOperation());
      await renderPanel();
      await openHistory();
      await openConfirmation();
      await confirm();
      expect(screen.getByRole('alert').textContent).toMatch(/already used|different restore/i);

      await confirm();

      expect(api.restoreRevision.mock.calls[1][2]).not.toBe(api.restoreRevision.mock.calls[0][2]);
    });

    it('drops a late restore answer after the document changed', async () => {
      const pending = deferred<RestoreOperation>();
      api.restoreRevision.mockReturnValue(pending.promise);
      const view = await renderPanel();
      await openHistory();
      await openConfirmation();
      await confirm();
      api.listDocumentRevisions.mockClear();

      await view.rerender({ collectionId: 'nightfall', documentId: 'doc-2', documentName: 'other.pdf' });
      await act(async () => {});
      await act(async () => pending.resolve(restoreOperation()));

      expect(screen.queryByRole('status')).toBeNull();
      expect(screen.queryByRole('alert')).toBeNull();
      expect(api.listDocumentRevisions).not.toHaveBeenCalled();
    });
  });

  it('can be closed again, returning focus to the Text history button', async () => {
    await renderPanel();
    await openHistory();
    const toggle = screen.getByRole('button', { name: 'Text history' });
    expect(toggle.getAttribute('aria-expanded')).toBe('true');

    await fireEvent.click(toggle);
    await act(async () => {});

    expect(screen.queryByRole('list', { name: 'Text versions' })).toBeNull();
    expect(toggle.getAttribute('aria-expanded')).toBe('false');
    expect(document.activeElement).toBe(toggle);
  });
});
