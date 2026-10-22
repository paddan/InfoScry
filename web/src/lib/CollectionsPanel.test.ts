import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/svelte';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { ComponentProps } from 'svelte';
import CollectionsPanel from './CollectionsPanel.svelte';
import {
  ApiError,
  type Collection,
  type DeletionOperation,
  type DocumentApiRow,
  type DocumentDeletionAdmission,
  type DocumentsPage,
  type ImportHistoryEntry,
  type ImportHistoryPage,
  type ImportItemsPage,
  type ImportItemApiView,
  type RetryAdmission,
  type RetryAttempt,
} from './api';

const api = vi.hoisted(() => ({
  createCollection: vi.fn(),
  pickPaths: vi.fn(),
  enqueueImport: vi.fn(),
  getJob: vi.fn(),
  getImportItems: vi.fn(),
  getCollectionDocumentSummary: vi.fn(),
  listCollectionDocuments: vi.fn(),
  listCollectionImports: vi.fn(),
  getCollectionDocument: vi.fn(),
  renameCollection: vi.fn(),
  getCollectionIgnorePatterns: vi.fn(),
  getCollectionPrompts: vi.fn(),
  updateCollectionIgnorePatterns: vi.fn(),
  deleteCollection: vi.fn(),
  deleteDocuments: vi.fn(),
  getDeletion: vi.fn(),
  listUnfinishedDeletions: vi.fn(),
  updateCollectionOcrSettings: vi.fn(),
  listOcrProfiles: vi.fn(),
  listOcrLlmCandidates: vi.fn(),
  copyLlmProfileToOcr: vi.fn(),
  previewRescan: vi.fn(),
  admitRescan: vi.fn(),
  getRescanOperation: vi.fn(),
  listRescanOperations: vi.fn(),
  listReadingMethods: vi.fn(),
  previewImport: vi.fn(),
  startImport: vi.fn(),
  startRescan: vi.fn(),
  cancelRescan: vi.fn(),
}));

vi.mock('./api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('./api')>()),
  ApiError: class ApiError extends Error {
    code: string;
    status: number | null;
    constructor(code: string, message: string, status: number | null = null) {
      super(message);
      this.code = code;
      this.status = status;
    }
  },
  updateCollectionOcrSettings: api.updateCollectionOcrSettings,
  listOcrProfiles: api.listOcrProfiles,
  listOcrLlmCandidates: api.listOcrLlmCandidates,
  copyLlmProfileToOcr: api.copyLlmProfileToOcr,
  previewRescan: api.previewRescan,
  admitRescan: api.admitRescan,
  getRescanOperation: api.getRescanOperation,
  listRescanOperations: api.listRescanOperations,
  cancelRescan: api.cancelRescan,
  createCollection: api.createCollection,
  pickPaths: api.pickPaths,
  enqueueImport: api.enqueueImport,
  getJob: api.getJob,
  getImportItems: api.getImportItems,
  getCollectionDocumentSummary: api.getCollectionDocumentSummary,
  listCollectionDocuments: api.listCollectionDocuments,
  listCollectionImports: api.listCollectionImports,
  getCollectionDocument: api.getCollectionDocument,
  renameCollection: api.renameCollection,
  getCollectionIgnorePatterns: api.getCollectionIgnorePatterns,
  getCollectionPrompts: api.getCollectionPrompts,
  updateCollectionIgnorePatterns: api.updateCollectionIgnorePatterns,
  deleteCollection: api.deleteCollection,
  deleteDocuments: api.deleteDocuments,
  getDeletion: api.getDeletion,
  listUnfinishedDeletions: api.listUnfinishedDeletions,
  listReadingMethods: api.listReadingMethods,
  previewImport: api.previewImport,
  startImport: api.startImport,
  startRescan: api.startRescan,
}));

function collection(name: string, documentCount = 0): Collection {
  return {
    id: name.toLowerCase(),
    name,
    language: 'eng',
    defaultMethod: 'tesseract',
    createdAt: '2026-09-21T07:00:00Z',
    updatedAt: '2026-09-21T07:00:00Z',
    lifecycle: 'ACTIVE',
    documentCount,
  };
}

/** One listing row as the server sends it; only the fields a test cares about are overridden. */
function documentRow(id: string, over: Partial<DocumentApiRow> = {}): DocumentApiRow {
  return {
    id,
    collectionId: 'nightfall',
    mediaType: 'application/pdf',
    originalFilename: `${id}.pdf`,
    sizeBytes: 1024,
    status: 'COMPLETE',
    createdAt: '2026-09-21T07:00:00Z',
    updatedAt: '2026-09-21T07:00:00Z',
    ...over,
  };
}

function page(rows: DocumentApiRow[], total = rows.length): DocumentsPage {
  return { documents: rows, total };
}

/** One import-history row as the server sends it; only the fields a test cares about are overridden. */
function importEntry(id: string, over: Partial<ImportHistoryEntry> = {}): ImportHistoryEntry {
  return {
    id,
    state: 'COMPLETE',
    filesCompleted: 1,
    filesTotal: 1,
    createdAt: '2026-09-21T07:00:00Z',
    updatedAt: '2026-09-21T07:00:01Z',
    itemsUrl: `/api/jobs/${id}/items`,
    ...over,
  };
}

function history(imports: ImportHistoryEntry[], total = imports.length): ImportHistoryPage {
  return { imports, total };
}

function itemsPage(items: ImportItemApiView[], total = items.length): ImportItemsPage {
  return { items, total };
}

/** One import file as the job-items route sends it: the file's own name, never the path it came from. */
function importItem(id: string, outcome: ImportItemApiView['outcome'], over: Partial<ImportItemApiView> = {}): ImportItemApiView {
  return {
    id,
    jobId: 'job-1',
    documentId: null,
    outcome,
    createdAt: '2026-09-21T07:00:00Z',
    updatedAt: '2026-09-21T07:00:01Z',
    ...over,
  };
}

/** One deletion operation as the server reports it; only the fields a test cares about are overridden. */
function deletion(over: Partial<DeletionOperation> = {}): DeletionOperation {
  return {
    operationId: 'op-1',
    kind: 'COLLECTION',
    collectionId: 'nightfall',
    collectionName: 'Nightfall',
    documentIds: [],
    phase: 'PREPARED',
    terminal: false,
    errorCode: null,
    ...over,
  };
}

/** The props the page supplies; the tests override the list, the selection, and the callbacks. */
function props(over: Partial<ComponentProps<typeof CollectionsPanel>> = {}): ComponentProps<typeof CollectionsPanel> {
  return {
    collections: [],
    selectedId: '',
    loading: false,
    error: null,
    onSelect: vi.fn(),
    onCollectionsChanged: vi.fn(),
    onOpenDocument: vi.fn(),
    onOpenPageReview: vi.fn(),
    onCollectionDeleted: vi.fn(),
    onDocumentDeleted: vi.fn(),
    onRetryDocument: vi.fn(async () => ({ accepted: true as const, jobId: 'job-1' })),
    onRetryAllDocuments: vi.fn(async () => ({ collectionId: 'nightfall', acceptedJobIds: ['job-1'], rejected: [] })),
    ...over,
  };
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}

/** Legacy table/settings/history scenarios explicitly start with those sections open. */
function renderExpandedPanel(_component: typeof CollectionsPanel, input: ComponentProps<typeof CollectionsPanel>) {
  const view = render(CollectionsPanel, {
    ...input, documentViewState: { expanded: true, filename: '', status: '', sort: 'newest' },
  });
  for (const disclosure of view.container.querySelectorAll('details')) disclosure.open = true;
  return view;
}

describe('collections panel', () => {
  it('exposes the shared work-area hook without constraining collection actions', async () => {
    render(CollectionsPanel, props());
    const panel = screen.getByRole('region', { name: 'Collection administration' });
    expect(panel.classList.contains('workspace-area')).toBe(true);
    expect(await within(panel).findByRole('button', { name: 'Create collection' })).toBeTruthy();
  });

  beforeEach(() => {
    vi.resetAllMocks();
    api.getCollectionDocumentSummary.mockResolvedValue({ total: 75, byStatus: { FAILED: 2, COMPLETE: 73 } });
    api.listCollectionDocuments.mockResolvedValue(page([]));
    api.listCollectionImports.mockResolvedValue(history([]));
    api.getCollectionIgnorePatterns.mockResolvedValue(['.DS_Store', '*.tmp']);
    api.getCollectionPrompts.mockResolvedValue({ coreRules: '', ask: { defaultId: 'default', prompts: [] }, investigate: { defaultId: 'default', prompts: [] } });
    // Nothing is being deleted unless a test says so, and a followed operation stays unfinished by
    // default so a late poll cannot turn into a phantom completion.
    api.listUnfinishedDeletions.mockResolvedValue([]);
    api.getDeletion.mockResolvedValue(deletion());
    // No OCR profiles and no earlier scans unless a test says so.
    api.listOcrProfiles.mockResolvedValue([]);
    api.listOcrLlmCandidates.mockResolvedValue([]);
    api.listRescanOperations.mockResolvedValue([]);
    api.listReadingMethods.mockResolvedValue({ default: 'tesseract', methods: [
      { method: 'tesseract', label: 'Tesseract', destination: 'this machine', available: true, unavailableReason: null, external: false },
    ] });
    api.previewImport.mockResolvedValue({ previewId: 'preview-1', totalFiles: 1, totalPages: 1, method: 'tesseract', files: [], external: false, destination: 'this machine', estimatedCostUsd: null, costBasis: null, previewHash: 'hash' });
    api.startImport.mockResolvedValue({ jobId: 'job-1' });
    api.startRescan.mockResolvedValue({ operationId: 'op-2' });
  });

  afterEach(() => { cleanup(); vi.useRealTimers(); vi.restoreAllMocks(); });

  describe('live filename search and inline details', () => {
    async function openList(over: Partial<ComponentProps<typeof CollectionsPanel>> = {}) {
      api.listCollectionDocuments.mockResolvedValue(page([documentRow('doc-1'), documentRow('doc-2')], 51));
      api.getCollectionDocument.mockImplementation(async (_collectionId, id) => ({
        document: documentRow(id), sourceId: `source-${id}`, retryEligible: false,
      }));
      const view = renderExpandedPanel(CollectionsPanel, props({
        collections: [collection('Nightfall', 51), collection('Other', 1)], selectedId: 'nightfall', ...over,
      }));
      await screen.findByRole('table');
      return view;
    }

    async function advance(ms: number) {
      await act(async () => { await vi.advanceTimersByTimeAsync(ms); });
    }

    it('debounces rapid typing for 300ms after the last input and clearing uses the same delay', async () => {
      await openList();
      vi.useFakeTimers();
      const input = screen.getByLabelText('Search filenames');
      await fireEvent.input(input, { target: { value: 'q' } });
      await advance(100);
      await fireEvent.input(input, { target: { value: 'qu' } });
      await advance(100);
      await fireEvent.input(input, { target: { value: '  quarterly  ' } });
      await advance(299);
      expect(api.listCollectionDocuments).toHaveBeenCalledTimes(1);
      await advance(1);
      expect(api.listCollectionDocuments).toHaveBeenCalledTimes(2);
      expect(api.listCollectionDocuments).toHaveBeenLastCalledWith('nightfall', expect.objectContaining({ q: 'quarterly', offset: 0 }));
      await fireEvent.input(input, { target: { value: '' } });
      await advance(299);
      expect(api.listCollectionDocuments).toHaveBeenCalledTimes(2);
      await advance(1);
      expect(api.listCollectionDocuments).toHaveBeenLastCalledWith('nightfall', expect.objectContaining({ q: '' }));
    });

    it('Enter flushes once and removes the Search button', async () => {
      await openList();
      vi.useFakeTimers();
      const input = screen.getByLabelText('Search filenames');
      await fireEvent.input(input, { target: { value: 'invoice' } });
      await fireEvent.keyDown(input, { key: 'Enter' });
      expect(api.listCollectionDocuments).toHaveBeenCalledTimes(2);
      expect(api.listCollectionDocuments).toHaveBeenLastCalledWith('nightfall', expect.objectContaining({ q: 'invoice' }));
      await advance(300);
      expect(api.listCollectionDocuments).toHaveBeenCalledTimes(2);
      expect(screen.queryByRole('button', { name: 'Search' })).toBeNull();
    });

    it('defers IME input and Enter until composition ends', async () => {
      await openList();
      vi.useFakeTimers();
      const input = screen.getByLabelText('Search filenames');
      await fireEvent.input(input, { target: { value: 'before' } });
      await fireEvent.compositionStart(input);
      await fireEvent.input(input, { target: { value: '日本' } });
      await fireEvent.keyDown(input, { key: 'Enter', isComposing: true });
      await advance(600);
      expect(api.listCollectionDocuments).toHaveBeenCalledTimes(1);
      await fireEvent.compositionEnd(input);
      await advance(299);
      expect(api.listCollectionDocuments).toHaveBeenCalledTimes(1);
      await advance(1);
      expect(api.listCollectionDocuments).toHaveBeenLastCalledWith('nightfall', expect.objectContaining({ q: '日本' }));
    });

    it('rejects an old response during the debounce interval', async () => {
      const old = deferred<DocumentsPage>();
      api.listCollectionDocuments.mockReturnValueOnce(old.promise).mockResolvedValue(page([documentRow('new')]));
      renderExpandedPanel(CollectionsPanel, props({ collections: [collection('Nightfall')], selectedId: 'nightfall' }));
      vi.useFakeTimers();
      await fireEvent.input(screen.getByLabelText('Search filenames'), { target: { value: 'new' } });
      await act(async () => { old.resolve(page([documentRow('obsolete')])); });
      expect(screen.queryByText('obsolete.pdf')).toBeNull();
      await advance(300);
      expect(screen.getByText('new.pdf')).toBeTruthy();
    });

    it.each([['Status', 'FAILED', 'status'], ['Sort', 'name-desc', 'sort']])(
      '%s immediately consumes the latest trimmed input without a scheduled duplicate', async (label, value, field) => {
        await openList();
        vi.useFakeTimers();
        await fireEvent.input(screen.getByLabelText('Search filenames'), { target: { value: '  final  ' } });
        await fireEvent.change(screen.getByLabelText(label), { target: { value } });
        expect(api.listCollectionDocuments).toHaveBeenLastCalledWith('nightfall', expect.objectContaining({ q: 'final', [field]: value, offset: 0 }));
        await advance(300);
        expect(api.listCollectionDocuments).toHaveBeenCalledTimes(2);
      },
    );

    it.each(['collapse', 'hide', 'switch', 'unmount'])('cancels pending input on %s and saves it for its originating collection', async (action) => {
      const changed = vi.fn();
      const view = await openList({ onDocumentViewStateChange: changed });
      vi.useFakeTimers();
      await fireEvent.input(screen.getByLabelText('Search filenames'), { target: { value: 'pending' } });
      expect(changed).toHaveBeenLastCalledWith('nightfall', expect.objectContaining({ filename: 'pending' }));
      if (action === 'collapse') await fireEvent.click(screen.getByRole('button', { name: 'Documents' }));
      if (action === 'hide') await view.rerender({ visible: false });
      if (action === 'switch') await view.rerender({ selectedId: 'other', documentViewState: undefined });
      if (action === 'unmount') view.unmount();
      await advance(300);
      expect(api.listCollectionDocuments).toHaveBeenCalledTimes(1);
      if (action === 'switch') {
        await fireEvent.click(screen.getByRole('button', { name: 'Documents' }));
        expect(api.listCollectionDocuments).toHaveBeenLastCalledWith('other', expect.objectContaining({ q: '' }));
        await view.rerender({ selectedId: 'nightfall', documentViewState: { expanded: true, filename: 'pending', status: '', sort: 'newest' } });
      } else if (action === 'collapse') {
        await fireEvent.click(screen.getByRole('button', { name: 'Documents' }));
      } else if (action === 'hide') {
        await view.rerender({ visible: true });
      } else return;
      expect(api.listCollectionDocuments).toHaveBeenLastCalledWith('nightfall', expect.objectContaining({ q: 'pending', offset: 0 }));
    });

    it('renders one full-width detail row directly after its document, with metadata and the reader action', async () => {
      const onOpenPageReview = vi.fn();
      await openList({ onOpenPageReview });
      const row = screen.getByRole('row', { name: /doc-1.pdf/ });
      expect(within(row).queryByText('application/pdf')).toBeNull();
      expect(within(row).queryByText('1 KB')).toBeNull();
      expect(within(row).queryByText('2026-09-21')).toBeNull();
      const trigger = within(row).getByRole('button', { name: 'Details' });
      await fireEvent.click(trigger);
      const details = await screen.findByRole('region', { name: 'Document details' });
      expect(details.closest('tr')).toBe(row.nextElementSibling);
      expect(details.closest('td')?.getAttribute('colspan')).toBe('6');
      expect(screen.getByRole('region', { name: 'Document table' }).contains(details)).toBe(true);
      expect(trigger.getAttribute('aria-expanded')).toBe('true');
      expect(within(details).getByText('application/pdf')).toBeTruthy();
      expect(within(details).getByText('1 KB')).toBeTruthy();
      expect(within(details).getByText('2026-09-21')).toBeTruthy();
      await fireEvent.click(within(details).getByRole('button', { name: 'Open document' }));
      expect(onOpenPageReview).toHaveBeenCalledWith('doc-1', expect.any(HTMLElement));
      const second = screen.getByRole('row', { name: /doc-2.pdf/ });
      await fireEvent.click(within(second).getByRole('button', { name: 'Details' }));
      const next = await screen.findByRole('region', { name: 'Document details' });
      expect(screen.getAllByRole('region', { name: 'Document details' })).toHaveLength(1);
      expect(next.closest('tr')).toBe(second.nextElementSibling);
    });

    it('closing details returns keyboard focus to its trigger and the trigger also toggles them', async () => {
      await openList();
      const trigger = within(screen.getByRole('row', { name: /doc-1.pdf/ })).getByRole('button', { name: 'Details' });
      await fireEvent.click(trigger);
      const close = within(await screen.findByRole('region', { name: 'Document details' })).getByRole('button', { name: 'Close details' });
      close.focus();
      await fireEvent.click(close);
      expect(document.activeElement).toBe(trigger);
      await fireEvent.click(trigger);
      await screen.findByRole('region', { name: 'Document details' });
      await fireEvent.click(trigger);
      expect(screen.queryByRole('region', { name: 'Document details' })).toBeNull();
    });

    it('scrolls only the bounded table enough to reveal details below its sticky header', async () => {
      await openList();
      const table = screen.getByRole('region', { name: 'Document table' });
      const bounds = (top: number, bottom: number) => ({ top, bottom, height: bottom - top, left: 0, right: 500, width: 500, x: 0, y: top, toJSON() {} });
      vi.spyOn(Element.prototype, 'getBoundingClientRect').mockImplementation(function (this: Element) {
        if (this === table) return bounds(100, 500);
        if (this.tagName === 'THEAD') return bounds(100, 140);
        if (this.classList.contains('document-details')) return bounds(400 - table.scrollTop, 600 - table.scrollTop);
        return bounds(0, 0);
      });
      await fireEvent.click(within(screen.getByRole('row', { name: /doc-1.pdf/ })).getByRole('button', { name: 'Details' }));
      await screen.findByRole('region', { name: 'Document details' });
      await waitFor(() => expect(table.scrollTop).toBe(100));
      expect(window.scrollY).toBe(0);
      vi.restoreAllMocks();
    });

    it('keeps a focused opener below the sticky header for tall details and reveals it after Escape', async () => {
      await openList();
      const table = screen.getByRole('region', { name: 'Document table' });
      const trigger = within(screen.getByRole('row', { name: /doc-1.pdf/ })).getByRole('button', { name: 'Details' });
      const bounds = (top: number, bottom: number) => ({ top, bottom, height: bottom - top, left: 0, right: 500, width: 500, x: 0, y: top, toJSON() {} });
      vi.spyOn(Element.prototype, 'getBoundingClientRect').mockImplementation(function (this: Element) {
        if (this === table) return bounds(100, 500);
        if (this.tagName === 'THEAD') return bounds(100, 140);
        if (this === trigger) return bounds(350 - table.scrollTop, 380 - table.scrollTop);
        if (this.classList.contains('document-details')) return bounds(400 - table.scrollTop, 1000 - table.scrollTop);
        return bounds(0, 0);
      });
      trigger.focus();
      await fireEvent.click(trigger);
      const details = await screen.findByRole('region', { name: 'Document details' });
      await waitFor(() => expect(table.scrollTop).toBeGreaterThan(0));
      expect(document.activeElement).toBe(trigger);
      expect(trigger.getBoundingClientRect().top).toBeGreaterThanOrEqual(140);
      expect(details.getBoundingClientRect().top).toBeLessThan(500);
      // The reader can scroll deeper into a tall detail and close from there.
      table.scrollTop = 500;
      const close = within(details).getByRole('button', { name: 'Close details' });
      close.focus();
      await fireEvent.keyDown(close, { key: 'Escape' });
      await waitFor(() => expect(table.scrollTop).toBe(210));
      expect(document.activeElement).toBe(trigger);
      expect(trigger.getBoundingClientRect().top).toBe(140);
      expect(window.scrollY).toBe(0);
      vi.restoreAllMocks();
    });

    it('renders hostile filenames and detail errors as text and Escape closes with focus restored', async () => {
      const view = await openList();
      api.listCollectionDocuments.mockResolvedValue(page([documentRow('hostile', { originalFilename: '<img src=x onerror=alert(1)>.pdf' })]));
      await fireEvent.change(screen.getByLabelText('Sort'), { target: { value: 'oldest' } });
      const row = await screen.findByRole('row', { name: /<img src=x/ });
      const trigger = within(row).getByRole('button', { name: 'Details' });
      api.getCollectionDocument.mockRejectedValue(new ApiError('NOT_FOUND', '<script>unsafe()</script>'));
      await fireEvent.click(trigger);
      const details = await screen.findByRole('region', { name: 'Document details' });
      await waitFor(() => expect(within(details).getByRole('alert').textContent).toBe('<script>unsafe()</script>'));
      expect(view.container.querySelector('img, script')).toBeNull();
      const close = within(details).getByRole('button', { name: 'Close details' });
      close.focus();
      await fireEvent.keyDown(close, { key: 'Escape' });
      expect(screen.queryByRole('region', { name: 'Document details' })).toBeNull();
      expect(document.activeElement).toBe(trigger);
    });

    it('leaves details open when Escape belongs to the nested reading dialog', async () => {
      api.previewRescan.mockResolvedValue({ previewId: 'preview-rescan', documentId: 'doc-1', managedHash: 'hash',
        snapshot: { engine: 'tesseract', mode: 'AUTO', language: 'eng' }, documentPages: 1,
        pageTotal: 1, destinations: [], method: 'tesseract', external: false, destination: 'this machine',
        estimatedCostUsd: null, costBasis: null, previewHash: 'hash' });
      await openList();
      await fireEvent.click(within(screen.getByRole('row', { name: /doc-1.pdf/ })).getByRole('button', { name: 'Details' }));
      const details = await screen.findByRole('region', { name: 'Document details' });
      await fireEvent.click(within(details).getByRole('button', { name: 'Scan again' }));
      const dialog = await within(details).findByRole('dialog');
      await fireEvent.keyDown(dialog, { key: 'Escape' });
      expect(screen.getByRole('region', { name: 'Document details' })).toBe(details);
      expect(within(details).queryByRole('dialog')).toBeNull();
    });

    it('closes details and destructive selection immediately on input and on pagination', async () => {
      await openList();
      await fireEvent.click(within(screen.getByRole('row', { name: /doc-1.pdf/ })).getByRole('button', { name: 'Details' }));
      await screen.findByRole('region', { name: 'Document details' });
      await fireEvent.click(screen.getByLabelText('Select doc-1.pdf'));
      await fireEvent.input(screen.getByLabelText('Search filenames'), { target: { value: 'new' } });
      expect(screen.queryByRole('region', { name: 'Document details' })).toBeNull();
      expect((screen.getByRole('button', { name: 'Delete selected' }) as HTMLButtonElement).disabled).toBe(true);
      await fireEvent.keyDown(screen.getByLabelText('Search filenames'), { key: 'Enter' });
      await fireEvent.click(within(screen.getByRole('row', { name: /doc-2.pdf/ })).getByRole('button', { name: 'Details' }));
      await screen.findByRole('region', { name: 'Document details' });
      await fireEvent.click(screen.getByRole('button', { name: 'Next page' }));
      expect(screen.queryByRole('region', { name: 'Document details' })).toBeNull();
    });

    it('closes an obsolete detail and ignores its pending response when a refresh removes its row', async () => {
      await openList();
      const pending = deferred<Awaited<ReturnType<typeof import('./api').getCollectionDocument>>>();
      api.getCollectionDocument.mockReturnValue(pending.promise);
      await fireEvent.click(within(screen.getByRole('row', { name: /doc-1.pdf/ })).getByRole('button', { name: 'Details' }));
      api.listCollectionDocuments.mockResolvedValue(page([documentRow('doc-2')]));
      await fireEvent.click(screen.getByRole('button', { name: 'Retry all eligible documents' }));
      await waitFor(() => expect(screen.queryByText('Loading details…')).toBeNull());
      await act(async () => { pending.resolve({ document: documentRow('doc-1'), sourceId: 'old-source', retryEligible: false }); });
      expect(screen.queryByRole('region', { name: 'Document details' })).toBeNull();
    });

    it('keeps keyboard focus visible when a refresh removes the document whose details had focus', async () => {
      await openList();
      await fireEvent.click(within(screen.getByRole('row', { name: /doc-1.pdf/ })).getByRole('button', { name: 'Details' }));
      const details = await screen.findByRole('region', { name: 'Document details' });
      within(details).getByRole('button', { name: 'Close details' }).focus();
      api.listCollectionDocuments.mockResolvedValue(page([documentRow('doc-2')]));
      await fireEvent.click(screen.getByRole('button', { name: 'Retry all eligible documents' }));
      await waitFor(() => expect(screen.queryByRole('region', { name: 'Document details' })).toBeNull());
      expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Documents' }));
    });

    it('keeps focus on a visible control when deletion finishes while its details have focus', async () => {
      await openList();
      const pending = deletion({ kind: 'DOCUMENT', documentIds: ['doc-1'] });
      api.deleteDocuments.mockResolvedValue({ operationId: 'op-1', collectionId: 'nightfall', documentIds: ['doc-1'], phase: 'PREPARED' });
      api.getDeletion.mockResolvedValue(pending);
      await fireEvent.click(within(screen.getByRole('row', { name: /doc-1.pdf/ })).getByRole('button', { name: 'Details' }));
      const details = await screen.findByRole('region', { name: 'Document details' });
      vi.useFakeTimers();
      await fireEvent.click(screen.getByRole('button', { name: 'Delete doc-1.pdf' }));
      await fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Delete document' }));
      within(details).getByRole('button', { name: 'Close details' }).focus();
      api.getDeletion.mockResolvedValue({ ...pending, phase: 'DONE', terminal: true });
      api.listCollectionDocuments.mockResolvedValue(page([documentRow('doc-2')]));
      await advance(2000);
      expect(screen.queryByRole('region', { name: 'Document details' })).toBeNull();
      expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Documents' }));
    });

    it.each(['input', 'page', 'detail'])('does not reopen or replace details when a delayed Retry is accepted after a new %s intent', async (intent) => {
      const admission = deferred<RetryAttempt>();
      const onRetryDocument = vi.fn(() => admission.promise);
      await openList({ onRetryDocument });
      api.getCollectionDocument.mockImplementation(async (_collectionId, id) => ({
        document: documentRow(id, { status: 'FAILED' }), sourceId: `source-${id}`, retryEligible: true,
      }));
      api.getJob.mockResolvedValue({ id: 'accepted-retry', type: 'RETRY', state: 'RUNNING',
        createdAt: '', updatedAt: '', completed: 0, total: 1, cancelRequested: false });
      await fireEvent.click(within(screen.getByRole('row', { name: /doc-1.pdf/ })).getByRole('button', { name: 'Details' }));
      const details = await screen.findByRole('region', { name: 'Document details' });
      await fireEvent.click(await within(details).findByRole('button', { name: 'Retry' }));
      expect(onRetryDocument).toHaveBeenCalledWith('doc-1', { requestId: expect.any(String) });
      vi.useFakeTimers();
      if (intent === 'input') await fireEvent.input(screen.getByLabelText('Search filenames'), { target: { value: 'new' } });
      if (intent === 'page') await fireEvent.click(screen.getByRole('button', { name: 'Next page' }));
      if (intent === 'detail') {
        await fireEvent.click(within(screen.getByRole('row', { name: /doc-2.pdf/ })).getByRole('button', { name: 'Details' }));
        expect(within(screen.getByRole('region', { name: 'Document details' })).getByText('doc-2.pdf')).toBeTruthy();
      }
      await act(async () => { admission.resolve({ accepted: true, jobId: 'accepted-retry' }); });
      // Acceptance still completes the original server operation once; only its obsolete UI continuation is dropped.
      expect(onRetryDocument).toHaveBeenCalledTimes(1);
      expect(api.cancelRescan).not.toHaveBeenCalled();
      if (intent === 'detail') {
        const current = screen.getByRole('region', { name: 'Document details' });
        expect(within(current).getByText('doc-2.pdf')).toBeTruthy();
        expect(within(current).queryByText('doc-1.pdf')).toBeNull();
        expect(api.getCollectionDocument).toHaveBeenCalledTimes(2);
      } else {
        expect(screen.queryByRole('region', { name: 'Document details' })).toBeNull();
        expect(api.getCollectionDocument).toHaveBeenCalledTimes(1);
      }
      // In particular, the input case has not reached its 300 ms trailing request yet.
      expect(api.listCollectionDocuments).toHaveBeenCalledTimes(intent === 'page' ? 2 : 1);
    });

    it.each(['before B starts', 'while B is pending'])('admits Retry B after switching details and keeps its request owned when A resolves %s', async (completion) => {
      const first = deferred<RetryAttempt>();
      const second = deferred<RetryAttempt>();
      const onRetryDocument = vi.fn((id: string) => id === 'doc-1' ? first.promise : second.promise);
      await openList({ onRetryDocument });
      api.getCollectionDocument.mockImplementation(async (_collectionId, id) => ({
        document: documentRow(id, { status: 'FAILED' }), sourceId: `source-${id}`, retryEligible: true,
      }));
      api.getJob.mockImplementation(async (id) => ({ id, type: 'RETRY', state: 'RUNNING',
        createdAt: '', updatedAt: '', completed: 0, total: 1, cancelRequested: false }));
      await fireEvent.click(within(screen.getByRole('row', { name: /doc-1.pdf/ })).getByRole('button', { name: 'Details' }));
      await fireEvent.click(await within(screen.getByRole('region', { name: 'Document details' })).findByRole('button', { name: 'Retry' }));
      await fireEvent.click(within(screen.getByRole('row', { name: /doc-2.pdf/ })).getByRole('button', { name: 'Details' }));
      const detailsB = screen.getByRole('region', { name: 'Document details' });
      if (completion === 'before B starts') await act(async () => { first.resolve({ accepted: true, jobId: 'retry-A' }); });
      await fireEvent.click(await within(detailsB).findByRole('button', { name: 'Retry' }));
      expect(onRetryDocument).toHaveBeenNthCalledWith(2, 'doc-2', { requestId: expect.any(String) });
      if (completion === 'while B is pending') await act(async () => { first.resolve({ accepted: true, jobId: 'retry-A' }); });
      expect(within(detailsB).getByText('doc-2.pdf')).toBeTruthy();
      expect((within(detailsB).getByRole('button', { name: 'Retrying…' }) as HTMLButtonElement).disabled).toBe(true);
      expect(within(detailsB).queryByRole('alert')).toBeNull();
      expect(api.getCollectionDocument).toHaveBeenCalledTimes(2);
      if (completion === 'while B is pending') {
        // A normal collection refresh remounts B's controls; the panel must still own B's pending request.
        await fireEvent.click(screen.getByRole('button', { name: 'Retry all eligible documents' }));
        await fireEvent.click(await within(detailsB).findByRole('button', { name: 'Retry' }));
        expect(onRetryDocument).toHaveBeenCalledTimes(2);
        expect(within(detailsB).getByRole('alert').textContent).toBe('A retry is already running.');
      }
      await act(async () => { second.resolve({ accepted: true, jobId: 'retry-B' }); });
      const current = screen.getByRole('region', { name: 'Document details' });
      expect(await within(current).findByText('doc-2.pdf')).toBeTruthy();
      expect(within(current).queryByText('doc-1.pdf')).toBeNull();
      expect(onRetryDocument).toHaveBeenCalledTimes(2);
      expect(api.getCollectionDocument).toHaveBeenLastCalledWith('nightfall', 'doc-2');
      expect(api.cancelRescan).not.toHaveBeenCalled();
    });
  });

  it('starts collapsed with collection-wide counts and no document page read', async () => {
    render(CollectionsPanel, props({ collections: [collection('Nightfall', 75)], selectedId: 'nightfall' }));
    expect(await screen.findByRole('button', { name: '75 total' })).toBeTruthy();
    expect(screen.getByRole('button', { name: '2 failed' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Documents' }).getAttribute('aria-expanded')).toBe('false');
    expect(screen.queryByLabelText('Search filenames')).toBeNull();
    expect(api.listCollectionDocuments).not.toHaveBeenCalled();
    expect(screen.getByRole('button', { name: 'Add documents' })).toBeTruthy();
  });

  it('opens exact summary shortcuts, clears filename and preserves sort', async () => {
    render(CollectionsPanel, props({ collections: [collection('Nightfall')], selectedId: 'nightfall',
      documentViewState: { expanded: true, filename: 'invoice', status: 'FAILED', sort: 'name-asc' } }));
    await screen.findByRole('button', { name: '75 total' });
    await fireEvent.click(screen.getByRole('button', { name: '75 total' }));
    await waitFor(() => expect(api.listCollectionDocuments).toHaveBeenLastCalledWith('nightfall', {
      q: '', status: undefined, sort: 'name-asc', limit: 50, offset: 0,
    }));
    await fireEvent.input(screen.getByLabelText('Search filenames'), { target: { value: 'other' } });
    await fireEvent.click(screen.getByRole('button', { name: '2 failed' }));
    await waitFor(() => expect(api.listCollectionDocuments).toHaveBeenLastCalledWith('nightfall', {
      q: '', status: 'FAILED', sort: 'name-asc', limit: 50, offset: 0,
    }));
    expect((screen.getByLabelText('Search filenames') as HTMLInputElement).value).toBe('');
  });

  it.each([
    ['QUEUED', 'Queued'], ['COPYING', 'Copying'], ['EXTRACTING', 'Extracting text'],
    ['OCR', 'Reading with OCR'], ['CHUNKING', 'Building passages'], ['EMBEDDING', 'Embedding'], ['INDEXING', 'Indexing'],
  ])('opens the exact %s ongoing status', async (status, label) => {
    api.getCollectionDocumentSummary.mockResolvedValue({ total: 1, byStatus: { [status]: 1 } });
    render(CollectionsPanel, props({ collections: [collection('Nightfall')], selectedId: 'nightfall' }));
    await fireEvent.click(await screen.findByRole('button', { name: `1 ${label}` }));
    await waitFor(() => expect(api.listCollectionDocuments).toHaveBeenLastCalledWith('nightfall', expect.objectContaining({ status, q: '', offset: 0 })));
    expect(screen.queryByText(/pending review|waiting for approval/i)).toBeNull();
  });

  it('keeps summary loading and failure distinct from zero and retries', async () => {
    const pending = deferred<{ total: number; byStatus: Record<string, number> }>();
    api.getCollectionDocumentSummary.mockReturnValueOnce(pending.promise);
    render(CollectionsPanel, props({ collections: [collection('Nightfall')], selectedId: 'nightfall' }));
    expect(screen.getByText('Loading document counts…')).toBeTruthy();
    expect(screen.queryByRole('button', { name: '0 total' })).toBeNull();
    await act(async () => pending.reject(new Error('counts unavailable')));
    expect(await screen.findByText('counts unavailable')).toBeTruthy();
    expect(screen.queryByRole('button', { name: '0 total' })).toBeNull();
    await fireEvent.click(screen.getByRole('button', { name: 'Retry document counts' }));
    expect(await screen.findByRole('button', { name: '75 total' })).toBeTruthy();
  });

  it('invalidates late summary and document reads across collection changes and collapse', async () => {
    const counts = deferred<{ total: number; byStatus: Record<string, number> }>();
    const rows = deferred<DocumentsPage>();
    api.getCollectionDocumentSummary.mockReturnValueOnce(counts.promise);
    api.listCollectionDocuments.mockReturnValueOnce(rows.promise);
    const initial = props({ collections: [collection('Nightfall'), collection('Archive')], selectedId: 'nightfall',
      documentViewState: { expanded: true, filename: '', status: '', sort: 'newest' } });
    const view = render(CollectionsPanel, initial);
    await fireEvent.click(screen.getByRole('button', { name: 'Documents' }));
    await act(async () => rows.resolve(page([documentRow('stale')])));
    expect(screen.queryByText('stale.pdf')).toBeNull();
    await view.rerender({ ...initial, selectedId: 'archive', documentViewState: undefined });
    expect(await screen.findByRole('button', { name: '75 total' })).toBeTruthy();
    await act(async () => counts.resolve({ total: 999, byStatus: {} }));
    expect(screen.queryByRole('button', { name: '999 total' })).toBeNull();
  });

  it('clears hidden destructive selection and details while retaining input on collapse', async () => {
    api.listCollectionDocuments.mockResolvedValue(page([documentRow('one')]));
    const changed = vi.fn();
    render(CollectionsPanel, props({ collections: [collection('Nightfall')], selectedId: 'nightfall',
      onDocumentViewStateChange: changed, documentViewState: { expanded: true, filename: '', status: '', sort: 'newest' } }));
    await screen.findByText('one.pdf');
    await fireEvent.click(screen.getByLabelText('Select one.pdf'));
    await fireEvent.input(screen.getByLabelText('Search filenames'), { target: { value: 'draft' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Documents' }));
    expect(changed).toHaveBeenLastCalledWith('nightfall', { expanded: false, filename: 'draft', status: '', sort: 'newest' });
    expect(screen.queryByRole('button', { name: 'Delete selected' })).toBeNull();
    await fireEvent.click(screen.getByRole('button', { name: 'Documents' }));
    await screen.findByText('one.pdf');
    expect((screen.getByLabelText('Select one.pdf') as HTMLInputElement).checked).toBe(false);
    expect(api.listCollectionDocuments).toHaveBeenLastCalledWith('nightfall', expect.objectContaining({ q: 'draft', offset: 0 }));
  });

  it('pauses hidden polling and refetches counts and page one on return', async () => {
    vi.useFakeTimers();
    try {
      api.getCollectionDocumentSummary.mockResolvedValue({ total: 1, byStatus: { OCR: 1 } });
      const initial = props({ collections: [collection('Nightfall')], selectedId: 'nightfall',
        documentViewState: { expanded: true, filename: '', status: '', sort: 'newest' } });
      const view = render(CollectionsPanel, initial);
      await act(async () => {});
      await view.rerender({ ...initial, visible: false });
      const counts = api.getCollectionDocumentSummary.mock.calls.length;
      const pages = api.listCollectionDocuments.mock.calls.length;
      await act(async () => { await vi.advanceTimersByTimeAsync(6000); });
      expect(api.getCollectionDocumentSummary).toHaveBeenCalledTimes(counts);
      expect(api.listCollectionDocuments).toHaveBeenCalledTimes(pages);
      await view.rerender({ ...initial, visible: true });
      await act(async () => {});
      expect(api.getCollectionDocumentSummary).toHaveBeenCalledTimes(counts + 1);
      expect(api.listCollectionDocuments).toHaveBeenCalledTimes(pages + 1);
    } finally { vi.useRealTimers(); }
  });

  it('opens the import panel on a running import, shows its progress once and keeps history read failures visible', async () => {
    const progress = { unitKind: 'PAGE', processedUnits: 3, totalUnits: 20, failedUnits: 0 } as const;
    api.listCollectionImports.mockResolvedValue(history([importEntry('active', { state: 'RUNNING', filesCompleted: 2, filesTotal: 4, currentProgress: progress })]));
    api.getJob.mockResolvedValue({ id: 'active', type: 'IMPORT', state: 'RUNNING', createdAt: '', updatedAt: '', completed: 2, total: 4, cancelRequested: false, currentProgress: progress });
    const view = render(CollectionsPanel, props({ collections: [collection('Nightfall')], selectedId: 'nightfall' }));
    await waitFor(() => expect(view.container.querySelector('.imports')?.textContent).toContain('2 of 4 files'));
    expect(screen.queryByRole('region', { name: 'Active imports' })).toBeNull();
    expect(await screen.findByRole('button', { name: 'Stop importing' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Add documents' }).getAttribute('aria-expanded')).toBe('true');
    expect(screen.getByRole('progressbar', { name: 'Import progress' }).getAttribute('value')).toBe('2');
    expect(screen.getAllByText('3 of 20 pages processed')).toHaveLength(1);
    for (const disclosure of view.container.querySelectorAll('details')) expect(disclosure.open).toBe(false);
    api.listCollectionImports.mockRejectedValueOnce(new Error('history unavailable'));
    await view.rerender({ visible: false });
    await view.rerender({ visible: true });
    expect(await screen.findByRole('alert')).toHaveProperty('textContent', 'history unavailable');
  });

  it('refreshes collapsed idle counts after a newly started import and its terminal outcome', async () => {
    api.pickPaths.mockResolvedValue(['/tmp/one.pdf']);
    api.startImport.mockResolvedValue({ job: { id: 'new-import', state: 'COMPLETE', completed: 1, total: 1 } });
    api.getImportItems.mockResolvedValue({ items: [], total: 0 });
    api.getCollectionDocumentSummary.mockResolvedValueOnce({ total: 0, byStatus: {} })
      .mockResolvedValue({ total: 1, byStatus: { COMPLETE: 1 } });
    render(CollectionsPanel, props({ collections: [collection('Nightfall')], selectedId: 'nightfall' }));
    await screen.findByRole('button', { name: '0 total' });
    await fireEvent.click(screen.getByRole('button', { name: 'Add documents' }));
    await fireEvent.click(screen.getByRole('button', { name: 'Choose files…' }));
    await fireEvent.click(screen.getByRole('button', { name: 'Import' }));
    await fireEvent.click(await screen.findByRole('button', { name: 'Read 1 page with Tesseract' }));
    expect(await screen.findByText('Import complete. 0 files.')).toBeTruthy();
    expect(await screen.findByRole('button', { name: '1 total' })).toBeTruthy();
    expect(api.getCollectionDocumentSummary).toHaveBeenCalledTimes(3);
    expect(api.listCollectionDocuments).not.toHaveBeenCalled();
  });

  it('pauses an active import while hidden and resumes its terminal refresh without submitting again', async () => {
    vi.useFakeTimers();
    try {
      api.pickPaths.mockResolvedValue(['/tmp/one.pdf']);
      api.startImport.mockResolvedValue({ job: { id: 'new-import', state: 'RUNNING', completed: 0, total: 1 } });
      api.getJob.mockResolvedValue({ id: 'new-import', state: 'RUNNING', completed: 0, total: 1 });
      api.getImportItems.mockResolvedValue({ items: [], total: 0 });
      api.getCollectionDocumentSummary.mockResolvedValueOnce({ total: 0, byStatus: {} })
        .mockResolvedValueOnce({ total: 1, byStatus: { OCR: 1 } })
        .mockResolvedValueOnce({ total: 1, byStatus: { OCR: 1 } })
        .mockResolvedValue({ total: 2, byStatus: { COMPLETE: 2 } });
      const initial = props({ collections: [collection('Nightfall')], selectedId: 'nightfall' });
      const view = render(CollectionsPanel, initial);
      await act(async () => {});
      await fireEvent.click(screen.getByRole('button', { name: 'Add documents' }));
      await fireEvent.click(screen.getByRole('button', { name: 'Choose files…' }));
      await fireEvent.click(screen.getByRole('button', { name: 'Import' }));
      await act(async () => {});
      await fireEvent.click(screen.getByRole('button', { name: 'Read 1 page with Tesseract' }));
      await act(async () => { await vi.advanceTimersByTimeAsync(1000); });
      expect(api.getJob).toHaveBeenCalledTimes(1);
      await view.rerender({ ...initial, visible: false });
      await act(async () => { await vi.advanceTimersByTimeAsync(5000); });
      expect(api.getJob).toHaveBeenCalledTimes(1);
      expect(api.getImportItems).not.toHaveBeenCalled();
      api.getJob.mockResolvedValue({ id: 'new-import', state: 'COMPLETE', completed: 1, total: 1 });
      await view.rerender({ ...initial, visible: true });
      await act(async () => { await vi.advanceTimersByTimeAsync(0); });
      expect(api.getJob).toHaveBeenCalledTimes(2);
      expect(api.getJob).toHaveBeenLastCalledWith('new-import');
      expect(api.startImport).toHaveBeenCalledTimes(1);
      expect(screen.getByText('Import complete. 0 files.')).toBeTruthy();
      expect(screen.getByRole('button', { name: '2 total' })).toBeTruthy();
      expect(api.getCollectionDocumentSummary).toHaveBeenCalledTimes(4);
      expect(api.listCollectionDocuments).not.toHaveBeenCalled();
    } finally { vi.useRealTimers(); }
  });

  it('validates cached criteria and keeps them when secondary disclosures toggle', async () => {
    render(CollectionsPanel, props({ collections: [collection('Nightfall')], selectedId: 'nightfall',
      documentViewState: { expanded: true, filename: 'draft', status: 'REVIEW', sort: 'invalid' } as never }));
    await waitFor(() => expect(api.listCollectionDocuments).toHaveBeenLastCalledWith('nightfall', {
      q: 'draft', status: undefined, sort: 'newest', limit: 50, offset: 0,
    }));
    await fireEvent.click(screen.getByText('Settings', { selector: 'summary' }));
    await fireEvent.click(screen.getByText('Import history', { selector: 'summary' }));
    expect((screen.getByLabelText('Search filenames') as HTMLInputElement).value).toBe('draft');
    expect(screen.getByRole('button', { name: 'Documents' }).getAttribute('aria-expanded')).toBe('true');
    expect(api.listCollectionDocuments).toHaveBeenCalledTimes(1);
  });

  it('drops a pending detail response when collapsed and reopens without details', async () => {
    const pending = deferred<Awaited<ReturnType<typeof import('./api').getCollectionDocument>>>();
    api.getCollectionDocument.mockReturnValueOnce(pending.promise);
    api.listCollectionDocuments.mockResolvedValue(page([documentRow('one')]));
    render(CollectionsPanel, props({ collections: [collection('Nightfall')], selectedId: 'nightfall',
      documentViewState: { expanded: true, filename: '', status: 'FAILED', sort: 'oldest' } }));
    await fireEvent.click(await screen.findByRole('button', { name: 'Details' }));
    expect(screen.getByText('Loading details…')).toBeTruthy();
    await fireEvent.click(screen.getByRole('button', { name: 'Documents' }));
    await act(async () => pending.resolve({ document: documentRow('late'), sourceId: 'late-source' }));
    await fireEvent.click(screen.getByRole('button', { name: 'Documents' }));
    expect(screen.queryByRole('region', { name: 'Document details' })).toBeNull();
    expect((screen.getByLabelText('Status') as HTMLSelectElement).value).toBe('FAILED');
    expect((screen.getByLabelText('Sort') as HTMLSelectElement).value).toBe('oldest');
  });

  it('reconciles a followed deletion that completed while the panel was hidden', async () => {
    const onCollectionDeleted = vi.fn();
    api.listUnfinishedDeletions.mockResolvedValueOnce([deletion()]).mockResolvedValue([]);
    api.getDeletion.mockResolvedValue(deletion({ phase: 'DONE', terminal: true }));
    const initial = props({ collections: [collection('Nightfall')], selectedId: 'nightfall', onCollectionDeleted });
    const view = render(CollectionsPanel, initial);
    await screen.findByRole('region', { name: 'Deletions in progress' });
    await view.rerender({ ...initial, visible: false });
    await view.rerender({ ...initial, visible: true });
    await waitFor(() => expect(onCollectionDeleted).toHaveBeenCalledWith('nightfall'));
  });

  it('lists active collections with their document counts', () => {
    renderExpandedPanel(CollectionsPanel, props({
      collections: [collection('Default', 3), collection('Nightfall', 1)],
      selectedId: 'default',
    }));

    expect(screen.getByRole('button', { name: 'Default 3 documents' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Nightfall 1 document' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Default 3 documents' }).getAttribute('aria-pressed')).toBe('true');
    expect(screen.getByRole('button', { name: 'Nightfall 1 document' }).getAttribute('aria-pressed')).toBe('false');
  });

  it('offers the empty archive state with a create action', async () => {
    renderExpandedPanel(CollectionsPanel, props());

    expect(await screen.findByText('No collections yet')).toBeTruthy();
    await fireEvent.click(screen.getByRole('button', { name: 'Create collection' }));
    expect(screen.getByLabelText('Name')).toBeTruthy();
  });

  it('reports a collection listing failure instead of an empty archive', () => {
    renderExpandedPanel(CollectionsPanel, props({ error: 'the archive is unavailable' }));

    expect(screen.getByRole('alert').textContent).toContain('the archive is unavailable');
    expect(screen.queryByText('No collections yet')).toBeNull();
  });

  it('guides creating a collection before the first import, and can still create one named Default', async () => {
    api.createCollection.mockResolvedValue({ ...collection('Default'), id: 'a-generated-id' });
    const onCollectionsChanged = vi.fn();

    renderExpandedPanel(CollectionsPanel, props({ onCollectionsChanged }));

    expect(await screen.findByText('No collections yet')).toBeTruthy();
    expect(screen.getByText(/every import goes into the collection you choose/)).toBeTruthy();
    // Nothing can be imported until a collection is chosen, so the import control is not offered.
    expect(screen.queryByRole('button', { name: 'Add documents' })).toBeNull();

    await fireEvent.click(screen.getByRole('button', { name: 'Create collection' }));
    await fireEvent.input(screen.getByLabelText('Name'), { target: { value: 'Default' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Create' }));

    await act(async () => {});
    expect(api.createCollection).toHaveBeenCalledWith('Default', undefined);
    expect(onCollectionsChanged).toHaveBeenCalledWith('a-generated-id');
  });

  it('explains the retired automatic Default and points at a rename and an exact-name deletion', async () => {
    renderExpandedPanel(CollectionsPanel, props({ collections: [collection('Default', 1)], selectedId: 'default' }));

    const notice = await screen.findByRole('region', { name: 'About the former Default collection' });
    expect(within(notice).getByText(/Earlier InfoScry versions created this collection automatically/)).toBeTruthy();
    expect(within(notice).getByText(/rename it under Settings/)).toBeTruthy();
    // Both choices the explanation names are the working controls: the settings field and the
    // exact-name confirmation.
    expect(screen.getByLabelText('Collection name')).toBeTruthy();
    await fireEvent.click(screen.getByRole('button', { name: 'Delete collection' }));
    expect(screen.getByLabelText('Type the collection name to confirm')).toBeTruthy();
  });

  it('does not mistake a user-created collection named Default for the retired seed', () => {
    renderExpandedPanel(CollectionsPanel, props({
      collections: [{ ...collection('Default', 1), id: 'a-generated-id' }],
      selectedId: 'a-generated-id',
    }));

    expect(screen.queryByRole('region', { name: 'About the former Default collection' })).toBeNull();
    expect(screen.queryByText(/Earlier InfoScry versions created this collection automatically/)).toBeNull();
    // It is still manageable exactly like any other collection.
    expect(screen.getByLabelText('Collection name')).toBeTruthy();
  });

  it('creates a collection, tells the parent which one to select, and keeps the name testable', async () => {
    api.createCollection.mockResolvedValue(collection('Nightfall'));
    const onCollectionsChanged = vi.fn();

    renderExpandedPanel(CollectionsPanel, props({ collections: [collection('Default', 2)], selectedId: 'default', onCollectionsChanged }));
    await fireEvent.click(screen.getByRole('button', { name: 'Create collection' }));
    await fireEvent.input(screen.getByLabelText('Name'), { target: { value: 'Nightfall' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Create' }));

    await act(async () => {});
    expect(api.createCollection).toHaveBeenCalledWith('Nightfall', undefined);
    expect(onCollectionsChanged).toHaveBeenCalledWith('nightfall');
    // The form closes and clears, so a second submission cannot repeat the created name.
    expect(screen.queryByLabelText('Name')).toBeNull();
    expect((screen.getByRole('button', { name: 'Create collection' }) as HTMLButtonElement).disabled).toBe(false);
  });

  it('surfaces a duplicate-name rejection and does not claim success', async () => {
    api.createCollection.mockRejectedValue(new ApiError('COLLECTION_NAME_TAKEN', 'a collection named Nightfall already exists'));
    const onCollectionsChanged = vi.fn();

    renderExpandedPanel(CollectionsPanel, props({ collections: [collection('Nightfall')], selectedId: 'nightfall', onCollectionsChanged }));
    await fireEvent.click(screen.getByRole('button', { name: 'Create collection' }));
    await fireEvent.input(screen.getByLabelText('Name'), { target: { value: 'Nightfall' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Create' }));

    const alert = await screen.findByRole('alert');
    expect(alert.textContent).toContain('already exists');
    expect(onCollectionsChanged).not.toHaveBeenCalled();
    // The rejected name stays in the form so it can be corrected.
    expect((screen.getByLabelText('Name') as HTMLInputElement).value).toBe('Nightfall');
  });

  it('selects a collection without touching the parent collection-change callback', async () => {
    const onSelect = vi.fn();
    const onCollectionsChanged = vi.fn();

    renderExpandedPanel(CollectionsPanel, props({
      collections: [collection('Default', 2), collection('Nightfall', 0)],
      selectedId: 'default',
      onSelect,
      onCollectionsChanged,
    }));
    await fireEvent.click(screen.getByRole('button', { name: /Nightfall/ }));

    expect(onSelect).toHaveBeenCalledWith('nightfall');
    // Selection is not a collection mutation: the workspace refresh path stays untouched.
    expect(onCollectionsChanged).not.toHaveBeenCalled();
  });

  it('shows the selected collection management area', async () => {
    api.listCollectionDocuments.mockResolvedValue(page([
      documentRow('doc-1'), documentRow('doc-2'), documentRow('doc-3'), documentRow('doc-4'),
    ]));

    renderExpandedPanel(CollectionsPanel, props({ collections: [collection('Nightfall', 4)], selectedId: 'nightfall' }));

    const area = screen.getByRole('region', { name: 'Manage Nightfall' });
    expect(area.textContent).toContain('4 documents');
    await screen.findByRole('table');
    expect(within(area).queryByText('No documents yet')).toBeNull();
    expect(screen.getByRole('button', { name: 'Add documents' }).getAttribute('aria-expanded')).toBe('false');
  });

  it('shows the empty collection state and reveals the import controls', async () => {
    renderExpandedPanel(CollectionsPanel, props({ collections: [collection('Nightfall', 0)], selectedId: 'nightfall' }));

    expect(await screen.findByText('No documents yet')).toBeTruthy();
    await fireEvent.click(screen.getByRole('button', { name: 'Add documents' }));

    expect(screen.getByRole('button', { name: 'Add documents' }).getAttribute('aria-expanded')).toBe('true');
    // The reused import panel names the destination collection explicitly.
    expect(await screen.findByText('Nightfall', { selector: 'strong' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Choose files…' })).toBeTruthy();
  });

  it('asks for a selection when the archive has collections but none is managed', () => {
    renderExpandedPanel(CollectionsPanel, props({ collections: [collection('Default', 2)] }));

    expect(screen.getByText('Select a collection to manage its documents.')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Add documents' })).toBeNull();
    // Nothing is managed, so no document read is made.
    expect(api.listCollectionDocuments).not.toHaveBeenCalled();
  });

  it('lists compact filename/status rows with metadata available in details', async () => {
    api.listCollectionDocuments.mockResolvedValue(page([
      documentRow('doc-1', {
        originalFilename: 'quarterly.pdf',
        mediaType: 'application/pdf',
        sizeBytes: 2048,
        createdAt: '2026-09-21T07:00:00Z',
        status: 'COMPLETE_WITH_WARNINGS',
      }),
      documentRow('doc-2', {
        originalFilename: 'notes.txt',
        mediaType: 'text/plain',
        sizeBytes: 512,
        createdAt: '2026-08-02T07:00:00Z',
        status: 'NEEDS_TOOL',
      }),
    ]));
    api.getCollectionDocument.mockResolvedValue({ document: documentRow('doc-1', {
      originalFilename: 'quarterly.pdf', mediaType: 'application/pdf', sizeBytes: 2048,
      createdAt: '2026-09-21T07:00:00Z', status: 'COMPLETE_WITH_WARNINGS',
    }) });

    renderExpandedPanel(CollectionsPanel, props({ collections: [collection('Nightfall', 2)], selectedId: 'nightfall' }));

    const quarterly = await screen.findByRole('row', { name: /quarterly\.pdf/ });
    expect(within(quarterly).queryByText('application/pdf')).toBeNull();
    expect(within(quarterly).getByTestId('document-reading-status').textContent).toBe('Done');
    expect(within(screen.getByRole('row', { name: /notes\.txt/ })).getByTestId('document-reading-status').textContent).toMatch(/^Failed:/);
    expect(screen.getByText('Showing 1–2 of 2 documents')).toBeTruthy();
    expect(api.listCollectionDocuments).toHaveBeenCalledWith('nightfall', {
      q: '',
      status: undefined,
      sort: 'newest',
      limit: 50,
      offset: 0,
    });
    await fireEvent.click(within(quarterly).getByRole('button', { name: 'Details' }));
    const details = await screen.findByRole('region', { name: 'Document details' });
    expect(within(details).getByText('application/pdf')).toBeTruthy();
    expect(within(details).getByText('2 KB')).toBeTruthy();
    expect(within(details).getByText('2026-09-21')).toBeTruthy();
  });

  it('searches filenames from the first page and sends the trimmed term', async () => {
    api.listCollectionDocuments.mockResolvedValue(page([documentRow('doc-1')]));

    renderExpandedPanel(CollectionsPanel, props({ collections: [collection('Nightfall', 1)], selectedId: 'nightfall' }));
    await screen.findByRole('table');
    // Sorting reloads the first page; the search keeps that order and adds the trimmed term.
    await fireEvent.change(screen.getByLabelText('Sort'), { target: { value: 'oldest' } });
    await fireEvent.input(screen.getByLabelText('Search filenames'), { target: { value: '  quarterly  ' } });
    await fireEvent.keyDown(screen.getByLabelText('Search filenames'), { key: 'Enter' });

    await waitFor(() => expect(api.listCollectionDocuments).toHaveBeenLastCalledWith('nightfall', {
      q: 'quarterly',
      status: undefined,
      sort: 'oldest',
      limit: 50,
      offset: 0,
    }));
  });

  it('restricts the listing by status and order, always from the first page', async () => {
    api.listCollectionDocuments.mockResolvedValue(page([documentRow('doc-1')], 51));

    renderExpandedPanel(CollectionsPanel, props({ collections: [collection('Nightfall', 51)], selectedId: 'nightfall' }));
    await screen.findByRole('table');
    expect((screen.getByRole('button', { name: 'Previous page' }) as HTMLButtonElement).disabled).toBe(true);

    await fireEvent.click(screen.getByRole('button', { name: 'Next page' }));
    await waitFor(() => expect(api.listCollectionDocuments).toHaveBeenLastCalledWith('nightfall', {
      q: '', status: undefined, sort: 'newest', limit: 50, offset: 50,
    }));

    await fireEvent.change(screen.getByLabelText('Status'), { target: { value: 'FAILED' } });
    await waitFor(() => expect(api.listCollectionDocuments).toHaveBeenLastCalledWith('nightfall', {
      q: '', status: 'FAILED', sort: 'newest', limit: 50, offset: 0,
    }));

    await fireEvent.change(screen.getByLabelText('Sort'), { target: { value: 'name-desc' } });
    await waitFor(() => expect(api.listCollectionDocuments).toHaveBeenLastCalledWith('nightfall', {
      q: '', status: 'FAILED', sort: 'name-desc', limit: 50, offset: 0,
    }));
  });

  it('shows that documents are loading, and an empty result is not a failure', async () => {
    let finish!: (page: DocumentsPage) => void;
    api.listCollectionDocuments.mockImplementation(() => new Promise<DocumentsPage>((resolve) => { finish = resolve; }));

    renderExpandedPanel(CollectionsPanel, props({ collections: [collection('Nightfall', 0)], selectedId: 'nightfall' }));

    expect(screen.getByText('Loading documents…')).toBeTruthy();
    await act(async () => { finish(page([])); });

    expect(await screen.findByText('No documents yet')).toBeTruthy();
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('reports a failed listing instead of an empty collection', async () => {
    api.listCollectionDocuments.mockRejectedValue(new ApiError('INTERNAL_ERROR', 'the archive is unavailable'));

    renderExpandedPanel(CollectionsPanel, props({ collections: [collection('Nightfall', 3)], selectedId: 'nightfall' }));

    const alert = await screen.findByRole('alert');
    expect(alert.textContent).toContain('the archive is unavailable');
    expect(screen.queryByText('No documents yet')).toBeNull();
    expect(screen.queryByRole('table')).toBeNull();
  });

  it('distinguishes an empty result from the collection having no documents', async () => {
    api.listCollectionDocuments
      .mockResolvedValueOnce(page([documentRow('doc-1')], 1))
      .mockResolvedValue(page([], 0));

    renderExpandedPanel(CollectionsPanel, props({ collections: [collection('Nightfall', 1)], selectedId: 'nightfall' }));
    await screen.findByRole('table');
    await fireEvent.input(screen.getByLabelText('Search filenames'), { target: { value: 'nothing' } });
    await fireEvent.keyDown(screen.getByLabelText('Search filenames'), { key: 'Enter' });

    expect(await screen.findByText('No documents match this search.')).toBeTruthy();
    expect(screen.queryByText('No documents yet')).toBeNull();
  });

  it('ignores a listing response that a newer filter already replaced', async () => {
    let finishOld!: (page: DocumentsPage) => void;
    api.listCollectionDocuments
      .mockImplementationOnce(() => new Promise<DocumentsPage>((resolve) => { finishOld = resolve; }))
      .mockResolvedValue(page([documentRow('doc-new', { originalFilename: 'new.pdf' })]));

    renderExpandedPanel(CollectionsPanel, props({ collections: [collection('Nightfall', 2)], selectedId: 'nightfall' }));
    await fireEvent.change(screen.getByLabelText('Sort'), { target: { value: 'oldest' } });
    expect(await screen.findByText('new.pdf')).toBeTruthy();

    await act(async () => { finishOld(page([documentRow('doc-old', { originalFilename: 'old.pdf' })])); });

    expect(screen.queryByText('old.pdf')).toBeNull();
    expect(screen.getByText('new.pdf')).toBeTruthy();
  });

  it('starts a fresh listing when the managed collection changes, dropping the previous answer', async () => {
    let finishFirst!: (page: DocumentsPage) => void;
    api.listCollectionDocuments
      .mockImplementationOnce(() => new Promise<DocumentsPage>((resolve) => { finishFirst = resolve; }))
      .mockResolvedValue(page([documentRow('doc-two', { originalFilename: 'second.pdf' })]));

    const { rerender } = renderExpandedPanel(CollectionsPanel, props({
      collections: [collection('Default', 1), collection('Nightfall', 1)],
      selectedId: 'default',
    }));
    await rerender({ selectedId: 'nightfall' });

    expect(await screen.findByText('second.pdf')).toBeTruthy();
    await act(async () => { finishFirst(page([documentRow('doc-one', { originalFilename: 'first.pdf' })])); });

    expect(screen.queryByText('first.pdf')).toBeNull();
    expect(screen.getByText('second.pdf')).toBeTruthy();
  });

  it('shows one document detail with its curated error and opens its first readable unit', async () => {
    const onOpenDocument = vi.fn();
    api.listCollectionDocuments.mockResolvedValue(page([documentRow('doc-1', { originalFilename: 'quarterly.pdf' })]));
    api.getCollectionDocument.mockResolvedValue({
      document: documentRow('doc-1', {
        originalFilename: 'quarterly.pdf',
        status: 'FAILED',
        mediaType: 'application/msword',
        errorCode: 'UNSUPPORTED_MEDIA_TYPE',
      }),
      errorMessage: 'the pipeline has no extractor for this kind of file',
      sourceId: 'unit-7',
    });

    renderExpandedPanel(CollectionsPanel, props({
      collections: [collection('Nightfall', 1)],
      selectedId: 'nightfall',
      onOpenDocument,
    }));
    await screen.findByRole('table');
    await fireEvent.click(screen.getByRole('button', { name: 'Details' }));

    const details = await screen.findByRole('region', { name: 'Document details' });
    expect(api.getCollectionDocument).toHaveBeenCalledWith('nightfall', 'doc-1');
    expect(within(details).getByText('the pipeline has no extractor for this kind of file')).toBeTruthy();
    expect(within(details).getByTestId('document-reading-status').textContent).toContain('Failed:');

    await fireEvent.click(within(details).getByRole('button', { name: 'Open document' }));
    expect(onOpenDocument).toHaveBeenCalledWith('doc-1', 'unit-7', expect.any(HTMLElement));
  });

  it('reads every processing stage a document can be in, including the ones that stop for a tool', async () => {
    const stages = [
      ['QUEUED', 'Reading 0'],
      ['COPYING', 'Reading 0'],
      ['EXTRACTING', 'Reading 0'],
      ['OCR', 'Reading 0'],
      ['CHUNKING', 'Reading 0'],
      ['EMBEDDING', 'Reading 0'],
      ['INDEXING', 'Reading 0'],
      ['COMPLETE', 'Done'],
      ['COMPLETE_WITH_WARNINGS', 'Done'],
      ['FAILED', 'Failed: The reading attempt failed.'],
      ['CANCELLED', 'Cancelled'],
      ['NEEDS_TOOL', 'Failed: The reading attempt failed.'],
    ] as const;
    api.listCollectionDocuments.mockResolvedValue(page(
      stages.map(([status], index) => documentRow(`doc-${index}`, { status })),
    ));

    renderExpandedPanel(CollectionsPanel, props({ collections: [collection('Nightfall', stages.length)], selectedId: 'nightfall' }));
    await screen.findByRole('table');

    stages.forEach(([status, label], index) => {
      const row = screen.getByRole('row', { name: new RegExp(`doc-${index}\\.pdf`) });
      expect(within(row).getByTestId('document-reading-status').textContent, status).toContain(label);
    });
  });

  it('shows a committed count against the announced total, with separate OCR and direct-text counters', async () => {
    api.listCollectionDocuments.mockResolvedValue(page([
      documentRow('doc-1', {
        status: 'OCR',
        progress: { unitKind: 'PAGE', totalUnits: 40, processedUnits: 12, failedUnits: 1, directTextUnits: 5, ocrUnits: 7 },
      }),
    ]));
    api.getCollectionDocument.mockResolvedValue({
      document: documentRow('doc-1', {
        status: 'OCR',
        progress: { unitKind: 'PAGE', totalUnits: 40, processedUnits: 12, failedUnits: 1, directTextUnits: 5, ocrUnits: 7 },
      }),
      errorMessage: null,
      sourceId: 'unit-1',
      progress: { unitKind: 'PAGE', totalUnits: 40, processedUnits: 12, failedUnits: 1, directTextUnits: 5, ocrUnits: 7 },
      warnings: ['the OCR tool ran but could not read part of this document; the rest of it was extracted'],
    });

    renderExpandedPanel(CollectionsPanel, props({ collections: [collection('Nightfall', 1)], selectedId: 'nightfall' }));
    const row = await screen.findByRole('row', { name: /doc-1\.pdf/ });
    expect(within(row).getByText('12/40 pages')).toBeTruthy();

    await fireEvent.click(within(row).getByRole('button', { name: 'Details' }));
    const details = await screen.findByRole('region', { name: 'Document details' });

    // The OCR phase names itself, and the denominator is explained rather than left to read as the pages
    // that needed OCR.
    expect(within(details).getByText('OCR · 12/40 pages processed')).toBeTruthy();
    expect(within(details).getByText(/The denominator counts every page of the document/)).toBeTruthy();
    expect(within(details).getByText('Read directly')).toBeTruthy();
    expect(within(details).getByText('5 pages')).toBeTruthy();
    expect(within(details).getByText('Read by OCR')).toBeTruthy();
    expect(within(details).getByText('7 pages')).toBeTruthy();
    expect(within(details).getByText('Units that could not be read')).toBeTruthy();
    expect(within(details).getByText('1')).toBeTruthy();
    expect(
      within(details).getByText('the OCR tool ran but could not read part of this document; the rest of it was extracted'),
    ).toBeTruthy();
  });

  it('shows a count with no denominator rather than inventing one', async () => {
    api.listCollectionDocuments.mockResolvedValue(page([
      documentRow('doc-1', {
        status: 'EXTRACTING',
        progress: { unitKind: 'SECTION', totalUnits: null, processedUnits: 3, failedUnits: 0, directTextUnits: 3, ocrUnits: 0 },
      }),
    ]));

    renderExpandedPanel(CollectionsPanel, props({ collections: [collection('Nightfall', 1)], selectedId: 'nightfall' }));
    const row = await screen.findByRole('row', { name: /doc-1\.pdf/ });

    // A Word document's units are sections, and nothing announced a total, so there is no percentage and
    // no denominator: only what was actually committed.
    expect(within(row).getByText('3 sections processed')).toBeTruthy();
    expect(within(row).queryByText(/%/)).toBeNull();
    expect(within(row).queryByText(/\d+\/\d+/)).toBeNull();
  });

  it('reports a legacy document\u2019s method counts as not recorded instead of zero', async () => {
    const legacy = {
      unitKind: 'PAGE' as const,
      totalUnits: 2,
      processedUnits: 2,
      failedUnits: 0,
      directTextUnits: null,
      ocrUnits: null,
    };
    api.listCollectionDocuments.mockResolvedValue(page([documentRow('doc-1', { progress: legacy })]));
    api.getCollectionDocument.mockResolvedValue({
      document: documentRow('doc-1', { progress: legacy }),
      errorMessage: null,
      sourceId: 'unit-1',
      progress: legacy,
    });

    renderExpandedPanel(CollectionsPanel, props({ collections: [collection('Nightfall', 1)], selectedId: 'nightfall' }));
    await screen.findByRole('table');
    await fireEvent.click(screen.getByRole('button', { name: 'Details' }));

    const details = await screen.findByRole('region', { name: 'Document details' });
    expect(within(details).getAllByText('Not recorded')).toHaveLength(2);
    expect(within(details).getByText('2/2 pages processed')).toBeTruthy();
  });

  it('keeps a gpu-failed document diagnosable and readable, with a remedy and no retry', async () => {
    const remedy = 'the graphics runtime is unavailable; restore the GPU session and retry';
    api.listCollectionDocuments.mockResolvedValue(page([
      documentRow('doc-1', {
        status: 'FAILED',
        errorCode: 'GPU_UNAVAILABLE',
        progress: { unitKind: 'PAGE', totalUnits: 3, processedUnits: 3, failedUnits: 0, directTextUnits: 3, ocrUnits: 0 },
      }),
    ]));
    api.getCollectionDocument.mockResolvedValue({
      document: documentRow('doc-1', { status: 'FAILED', errorCode: 'GPU_UNAVAILABLE' }),
      errorMessage: remedy,
      sourceId: 'unit-1',
      progress: { unitKind: 'PAGE', totalUnits: 3, processedUnits: 3, failedUnits: 0, directTextUnits: 3, ocrUnits: 0 },
    });

    renderExpandedPanel(CollectionsPanel, props({ collections: [collection('Nightfall', 1)], selectedId: 'nightfall', onOpenDocument: vi.fn() }));
    await screen.findByRole('table');
    await fireEvent.click(screen.getByRole('button', { name: 'Details' }));

    const details = await screen.findByRole('region', { name: 'Document details' });
    expect(within(details).getByRole('alert').textContent).toBe(remedy);
    // The extracted text is still evidence: a failed embedding does not take the source view away.
    expect(within(details).getByRole('button', { name: 'Open document' })).toBeTruthy();
    expect(within(details).queryByRole('button', { name: /Retry/ })).toBeNull();
  });

  it('explains why a document without readable content cannot be opened', async () => {
    const onOpenDocument = vi.fn();
    api.listCollectionDocuments.mockResolvedValue(page([documentRow('doc-1', { mediaType: 'application/msword' })]));
    api.getCollectionDocument.mockResolvedValue({
      document: documentRow('doc-1', { status: 'QUEUED', mediaType: 'application/msword' }),
      errorMessage: null,
      sourceId: null,
    });

    renderExpandedPanel(CollectionsPanel, props({
      collections: [collection('Nightfall', 1)],
      selectedId: 'nightfall',
      onOpenDocument,
    }));
    await screen.findByRole('table');
    await fireEvent.click(screen.getByRole('button', { name: 'Details' }));

    const details = await screen.findByRole('region', { name: 'Document details' });
    expect(within(details).getByText('This document has no readable content yet, so there is nothing to open.')).toBeTruthy();
    expect(within(details).queryByRole('button', { name: 'Open document' })).toBeNull();
    expect(onOpenDocument).not.toHaveBeenCalled();
  });

  it('opens a supported PDF for page review even when extraction has no unit yet', async () => {
    const onOpenPageReview = vi.fn();
    api.listCollectionDocuments.mockResolvedValue(page([documentRow('doc-1', { status: 'QUEUED' })]));
    api.getCollectionDocument.mockResolvedValue({
      document: documentRow('doc-1', { status: 'QUEUED' }), errorMessage: null, sourceId: null,
    });
    renderExpandedPanel(CollectionsPanel, props({
      collections: [collection('Nightfall', 1)], selectedId: 'nightfall', onOpenPageReview,
    }));
    await screen.findByRole('table');
    await fireEvent.click(screen.getByRole('button', { name: 'Details' }));
    const details = await screen.findByRole('region', { name: 'Document details' });
    await fireEvent.click(within(details).getByRole('button', { name: 'Open document' }));
    expect(onOpenPageReview).toHaveBeenCalledWith('doc-1', expect.any(HTMLElement));
    expect(within(details).queryByText('This document has no readable content yet, so there is nothing to open.')).toBeNull();
  });

  it('offers Retry for a document whose attempt stopped and refreshes the document after admission', async () => {
    const onRetryDocument = vi.fn(async () => ({ accepted: true as const, jobId: 'job-9' }));
    api.listCollectionDocuments.mockResolvedValue(page([documentRow('doc-1', { status: 'FAILED' })]));
    api.getCollectionDocument.mockResolvedValue({
      document: documentRow('doc-1', { status: 'FAILED', errorCode: 'EXTRACTION_FAILED' }),
      errorMessage: 'the extractor stopped before it delivered every unit of this document',
      sourceId: 'unit-1',
      retryEligible: true,
    });
    api.getJob.mockResolvedValue({
      id: 'job-9', type: 'RETRY', state: 'RUNNING', createdAt: '', updatedAt: '',
      completed: 0, total: 1, cancelRequested: false,
    });

    renderExpandedPanel(CollectionsPanel, props({
      collections: [collection('Nightfall', 1)],
      selectedId: 'nightfall',
      onRetryDocument,
    }));
    await screen.findByRole('table');
    await fireEvent.click(screen.getByRole('button', { name: 'Details' }));

    const details = await screen.findByRole('region', { name: 'Document details' });
    // The action explains what it does, including the one thing it cannot promise: changed OCR languages
    // can mean the extraction has to run again.
    expect(within(details).getByText(/using the settings of its failed/)).toBeTruthy();

    await fireEvent.click(within(details).getByRole('button', { name: 'Retry' }));

    expect(onRetryDocument).toHaveBeenCalledWith('doc-1', { requestId: expect.any(String) });
    // The document's own state is the record, so the detail and the row are read again rather than assumed.
    await waitFor(() => expect(api.getCollectionDocument).toHaveBeenCalledTimes(2));
    await waitFor(() => expect(api.listCollectionDocuments).toHaveBeenCalledTimes(2));
  });

  it('offers one retry action without exposing legacy method override fields', async () => {
    const onRetryDocument = vi.fn(async () => ({ accepted: true as const, jobId: 'job-9' }));
    api.listCollectionDocuments.mockResolvedValue(page([documentRow('doc-1', { status: 'FAILED' })]));
    api.getCollectionDocument.mockResolvedValue({
      document: documentRow('doc-1', { status: 'FAILED', errorCode: 'EXTRACTION_FAILED' }),
      errorMessage: 'the extractor stopped',
      sourceId: 'unit-1',
      retryEligible: true,
    });
    api.getJob.mockResolvedValue({
      id: 'job-9', type: 'RETRY', state: 'RUNNING', createdAt: '', updatedAt: '',
      completed: 0, total: 1, cancelRequested: false,
    });
    api.listOcrProfiles.mockResolvedValue([]);

    renderExpandedPanel(CollectionsPanel, props({
      collections: [collection('Nightfall', 1)],
      selectedId: 'nightfall',
      onRetryDocument,
    }));
    await screen.findByRole('table');
    await fireEvent.click(screen.getByRole('button', { name: 'Details' }));
    const details = await screen.findByRole('region', { name: 'Document details' });

    await fireEvent.click(within(details).getByRole('button', { name: 'Retry' }));
    expect(onRetryDocument).toHaveBeenLastCalledWith('doc-1', { requestId: expect.any(String) });
    expect(within(details).queryByLabelText(/engine|mode|language for this retry/i)).toBeNull();
  });

  it('shows only the document rescan Retry action for a failed rescan and follows its job', async () => {
    const onRetryDocument = vi.fn(async () => ({ accepted: true as const, jobId: 'retry-job' }));
    api.listCollectionDocuments.mockResolvedValue(page([documentRow('doc-1', { status: 'FAILED' })]));
    api.getCollectionDocument.mockResolvedValue({
      document: documentRow('doc-1', { status: 'FAILED', errorCode: 'OCR_PROVIDER_UNAVAILABLE' }),
      errorMessage: 'the OCR provider stopped',
      sourceId: 'unit-1',
      retryEligible: true,
    });
    api.listRescanOperations.mockResolvedValue([{
      operationId: 'op-1', collectionId: 'nightfall', documentId: 'doc-1', snapshot: {} as never,
      stage: 'FAILED', pageTotal: 1, pagesCommitted: 0, pagesFailed: 1,
      external: { distinctPages: 0, calls: 0, allowance: 0 }, requestId: 'request-1', createdAt: '', updatedAt: '',
    }]);
    api.getJob.mockResolvedValue({
      id: 'retry-job', type: 'RETRY', state: 'RUNNING', createdAt: '', updatedAt: '',
      completed: 0, total: 1, cancelRequested: false,
    });
    renderExpandedPanel(CollectionsPanel, props({
      collections: [collection('Nightfall', 1)], selectedId: 'nightfall', onRetryDocument,
    }));
    await screen.findByRole('table');
    await fireEvent.click(screen.getByRole('button', { name: 'Details' }));
    const details = await screen.findByRole('region', { name: 'Document details' });
    const retryButtons = within(details).getAllByRole('button', { name: 'Retry' });
    expect(retryButtons).toHaveLength(1);

    await fireEvent.click(retryButtons[0]);
    await waitFor(() => expect(onRetryDocument).toHaveBeenCalledWith('doc-1', { requestId: expect.any(String) }));
    await waitFor(() => expect(api.getJob).toHaveBeenCalledWith('retry-job'));
  });

  it('retries every eligible document without exposing method override fields', async () => {
    const onRetryAllDocuments = vi.fn(async () => ({
      collectionId: 'nightfall',
      acceptedJobIds: ['job-1'],
      rejected: [
        {
          documentId: 'doc-2',
          code: 'RETRY_USE_SCAN_AGAIN',
          reason: 'this document already has a published text, so use Scan again',
        },
      ],
    }));
    api.listCollectionDocuments.mockResolvedValue(page([documentRow('doc-1', { status: 'FAILED' })]));

    renderExpandedPanel(CollectionsPanel, props({
      collections: [collection('Nightfall', 1)],
      selectedId: 'nightfall',
      onRetryAllDocuments,
    }));
    await screen.findByRole('table');
    const section = screen.getByRole('region', { name: 'Retry documents in Nightfall' });

    await fireEvent.click(within(section).getByRole('button', { name: 'Retry all eligible documents' }));

    expect(onRetryAllDocuments).toHaveBeenCalledWith();
    expect(within(section).queryByLabelText(/engine|mode|language for this retry/i)).toBeNull();
    expect(await within(section).findByText(/use Scan again/)).toBeTruthy();
  });

  it('shows the server sentence for a refused retry and claims nothing was queued', async () => {
    const refusal = 'the OCR tool (Tesseract) is not installed, so this document cannot be read again: install it with brew';
    api.listCollectionDocuments.mockResolvedValue(page([documentRow('doc-1', { status: 'NEEDS_TOOL' })]));
    api.getCollectionDocument.mockResolvedValue({
      document: documentRow('doc-1', { status: 'NEEDS_TOOL', errorCode: 'NEEDS_TESSERACT' }),
      errorMessage: 'the OCR tool (Tesseract) is not installed, so pages without a text layer cannot be read',
      sourceId: null,
      retryEligible: true,
    });

    renderExpandedPanel(CollectionsPanel, props({
      collections: [collection('Nightfall', 1)],
      selectedId: 'nightfall',
      onRetryDocument: vi.fn(async () => ({ accepted: false as const, reason: refusal })),
    }));
    await screen.findByRole('table');
    await fireEvent.click(screen.getByRole('button', { name: 'Details' }));

    const details = await screen.findByRole('region', { name: 'Document details' });
    await fireEvent.click(within(details).getByRole('button', { name: 'Retry' }));

    // The document's own sentence is still there, so the refusal is the second alert rather than a replacement
    // for it.
    expect(
      (await within(details).findAllByRole('alert')).map((alert) => alert.textContent),
    ).toContain(refusal);
    expect(within(details).queryByText(/Retry queued/)).toBeNull();
    // A refusal is the server's answer, not a reason to re-read anything.
    expect(api.getCollectionDocument).toHaveBeenCalledTimes(1);
  });

  it('reports a failed retry request instead of claiming it was queued', async () => {
    api.listCollectionDocuments.mockResolvedValue(page([documentRow('doc-1', { status: 'FAILED' })]));
    api.getCollectionDocument.mockResolvedValue({
      document: documentRow('doc-1', { status: 'FAILED' }),
      sourceId: null,
      retryEligible: true,
    });

    renderExpandedPanel(CollectionsPanel, props({
      collections: [collection('Nightfall', 1)],
      selectedId: 'nightfall',
      onRetryDocument: vi.fn(async () => {
        throw new ApiError('MAINTENANCE_IN_PROGRESS', 'InfoScry is running exclusive maintenance; try again');
      }),
    }));
    await screen.findByRole('table');
    await fireEvent.click(screen.getByRole('button', { name: 'Details' }));

    const details = await screen.findByRole('region', { name: 'Document details' });
    await fireEvent.click(within(details).getByRole('button', { name: 'Retry' }));

    expect(
      (await within(details).findAllByRole('alert')).map((alert) => alert.textContent),
    ).toContain('InfoScry is running exclusive maintenance; try again');
    expect(within(details).queryByText(/Retry queued/)).toBeNull();
  });

  it('describes the collection-wide scope, retries from the page the reader is on, and reports the skips', async () => {
    const onRetryAllDocuments = vi.fn(async () => ({
      collectionId: 'nightfall',
      acceptedJobIds: ['job-7'],
      rejected: [
        { documentId: 'doc-8', reason: 'an import or retry for this document is already queued or running' },
        { documentId: 'doc-9', reason: 'an import or retry for this document is already queued or running' },
        { documentId: 'doc-10', reason: 'this document is being deleted' },
      ],
    }));
    api.listCollectionDocuments.mockResolvedValue(page([documentRow('doc-1', { status: 'FAILED' })], 120));

    renderExpandedPanel(CollectionsPanel, props({
      collections: [collection('Nightfall', 120)],
      selectedId: 'nightfall',
      onRetryAllDocuments,
    }));
    await screen.findByRole('table');
    // The reader filters the table and moves to another page: neither may narrow what Retry all selects.
    await fireEvent.change(screen.getByLabelText('Status'), { target: { value: 'FAILED' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Next page' }));

    const scope = screen.getByText(/across every page/);
    expect(scope.textContent).toMatch(/covers the whole collection/);
    expect(scope.textContent).toMatch(/search/);
    expect(scope.textContent).toMatch(/status\s+filter/);
    expect(scope.textContent).toMatch(/current page do not restrict which documents it selects/);
    const button = screen.getByRole('button', { name: 'Retry all eligible documents' });
    expect(button.getAttribute('aria-describedby')).toBe(scope.id);

    await fireEvent.click(button);

    // One request naming the collection, never the ids or the filter of the page the reader is looking at.
    expect(onRetryAllDocuments).toHaveBeenCalledWith();
    const outcome = await screen.findByRole('region', { name: 'Retry documents in Nightfall' });
    expect(within(outcome).getByRole('status').textContent).toContain('Retry queued');
    // Skips are grouped by the server's own sentence, with how many documents each one covers.
    expect(within(outcome).getByText(/already queued or running \(2 documents\)/)).toBeTruthy();
    expect(within(outcome).getByText('this document is being deleted')).toBeTruthy();
    // The statuses are read from the server again, under the filter and page the reader is on.
    expect(api.listCollectionDocuments).toHaveBeenLastCalledWith('nightfall', {
      q: '', status: 'FAILED', sort: 'newest', limit: 50, offset: 50,
    });
  });

  it('says nothing was eligible instead of counting a success', async () => {
    api.listCollectionDocuments.mockResolvedValue(page([documentRow('doc-1')]));

    renderExpandedPanel(CollectionsPanel, props({
      collections: [collection('Nightfall', 1)],
      selectedId: 'nightfall',
      onRetryAllDocuments: vi.fn(async () => ({ collectionId: 'nightfall', acceptedJobIds: [], rejected: [] })),
    }));
    await screen.findByRole('table');
    await fireEvent.click(screen.getByRole('button', { name: 'Retry all eligible documents' }));

    const outcome = await screen.findByRole('region', { name: 'Retry documents in Nightfall' });
    const status = within(outcome).getByRole('status').textContent ?? '';
    expect(status).toContain('Nothing to retry');
    expect(status).not.toContain('queued');
  });

  it('reports a collection-wide request that queued nothing rather than an admitted attempt', async () => {
    const reason = 'the embedding model is not installed, so a retry could not make this document searchable';
    api.listCollectionDocuments.mockResolvedValue(page([documentRow('doc-1', { status: 'FAILED' })]));

    renderExpandedPanel(CollectionsPanel, props({
      collections: [collection('Nightfall', 1)],
      selectedId: 'nightfall',
      onRetryAllDocuments: vi.fn(async () => ({
        collectionId: 'nightfall',
        acceptedJobIds: [],
        rejected: [{ documentId: 'doc-1', reason }],
      })),
    }));
    await screen.findByRole('table');
    await fireEvent.click(screen.getByRole('button', { name: 'Retry all eligible documents' }));

    const outcome = await screen.findByRole('region', { name: 'Retry documents in Nightfall' });
    const status = within(outcome).getByRole('status').textContent ?? '';
    expect(status).toContain('No retries were queued');
    expect(status).not.toMatch(/^Retry queued/);
    expect(within(outcome).getByText(reason)).toBeTruthy();
  });

  it('shows one collection-wide retry in flight and ignores a second click', async () => {
    let finish!: (admission: RetryAdmission) => void;
    const onRetryAllDocuments = vi.fn(() => new Promise<RetryAdmission>((resolve) => { finish = resolve; }));
    api.listCollectionDocuments.mockResolvedValue(page([documentRow('doc-1', { status: 'FAILED' })]));

    renderExpandedPanel(CollectionsPanel, props({
      collections: [collection('Nightfall', 1)],
      selectedId: 'nightfall',
      onRetryAllDocuments,
    }));
    await screen.findByRole('table');
    await fireEvent.click(screen.getByRole('button', { name: 'Retry all eligible documents' }));

    expect(onRetryAllDocuments).toHaveBeenCalledTimes(1);
    const pending = screen.getByRole('button', { name: 'Retrying…' }) as HTMLButtonElement;
    expect(pending.disabled).toBe(true);
    await fireEvent.click(pending);
    expect(onRetryAllDocuments).toHaveBeenCalledTimes(1);

    await act(async () => {
      finish({ collectionId: 'nightfall', acceptedJobIds: ['job-7'], rejected: [] });
    });

    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'Retry all eligible documents' })).toBeTruthy();
    });
    expect(screen.getByRole('status').textContent)
      .toContain('Retry queued for every eligible document in Nightfall');
  });

  it('reports a failed collection-wide retry instead of claiming anything was queued', async () => {
    api.listCollectionDocuments.mockResolvedValue(page([documentRow('doc-1', { status: 'FAILED' })]));

    renderExpandedPanel(CollectionsPanel, props({
      collections: [collection('Nightfall', 1)],
      selectedId: 'nightfall',
      onRetryAllDocuments: vi.fn(async () => {
        throw new ApiError('MAINTENANCE_IN_PROGRESS', 'InfoScry is running exclusive maintenance; try again');
      }),
    }));
    await screen.findByRole('table');
    await fireEvent.click(screen.getByRole('button', { name: 'Retry all eligible documents' }));

    const outcome = await screen.findByRole('region', { name: 'Retry documents in Nightfall' });
    expect(within(outcome).getByRole('alert').textContent).toContain('exclusive maintenance');
    expect(within(outcome).queryByRole('status')).toBeNull();
    // Nothing was admitted, so the rows are not re-read for an attempt nobody queued.
    expect(api.listCollectionDocuments).toHaveBeenCalledTimes(1);
  });

  it('leaves an accepted collection-wide retry to the server, which a reopened Admin reads back', async () => {
    api.listCollectionDocuments
      .mockResolvedValueOnce(page([documentRow('doc-1', { status: 'FAILED' })]))
      .mockResolvedValue(page([documentRow('doc-1', { status: 'QUEUED' })]));

    renderExpandedPanel(CollectionsPanel, props({ collections: [collection('Nightfall', 1)], selectedId: 'nightfall' }));
    const row = await screen.findByRole('row', { name: /doc-1\.pdf/ });
    expect(within(row).getByTestId('document-reading-status').textContent).toMatch(/^Failed:/);

    await fireEvent.click(screen.getByRole('button', { name: 'Retry all eligible documents' }));
    await waitFor(() => {
      expect(within(screen.getByRole('row', { name: /doc-1\.pdf/ })).getByTestId('document-reading-status').textContent).toContain('Reading');
    });

    // A reopened Admin keeps nothing from the click: the queued attempt is the server's own record.
    cleanup();
    renderExpandedPanel(CollectionsPanel, props({ collections: [collection('Nightfall', 1)], selectedId: 'nightfall' }));
    expect(within(await screen.findByRole('row', { name: /doc-1\.pdf/ })).getByTestId('document-reading-status').textContent).toContain('Reading');
    expect(screen.queryByText(/Retry queued/)).toBeNull();
  });

  it('does not offer Retry for a document whose managed copy is gone or that already succeeded', async () => {
    api.listCollectionDocuments.mockResolvedValue(page([documentRow('doc-1', { status: 'COMPLETE' })]));
    api.getCollectionDocument.mockResolvedValue({
      document: documentRow('doc-1', { status: 'COMPLETE' }),
      sourceId: 'unit-1',
      retryEligible: false,
    });

    renderExpandedPanel(CollectionsPanel, props({ collections: [collection('Nightfall', 1)], selectedId: 'nightfall' }));
    await screen.findByRole('table');
    await fireEvent.click(screen.getByRole('button', { name: 'Details' }));

    const details = await screen.findByRole('region', { name: 'Document details' });
    expect(within(details).queryByRole('button', { name: 'Retry' })).toBeNull();
  });

  it('reports a failed detail read and closes the details region', async () => {    api.listCollectionDocuments.mockResolvedValue(page([documentRow('doc-1')]));
    api.getCollectionDocument.mockRejectedValue(new ApiError('NOT_FOUND', 'no document with id doc-1 exists in this collection'));

    renderExpandedPanel(CollectionsPanel, props({ collections: [collection('Nightfall', 1)], selectedId: 'nightfall' }));
    await screen.findByRole('table');
    await fireEvent.click(screen.getByRole('button', { name: 'Details' }));

    const details = await screen.findByRole('region', { name: 'Document details' });
    expect(within(details).getByRole('alert').textContent).toContain('no document with id doc-1 exists');

    await fireEvent.click(within(details).getByRole('button', { name: 'Close details' }));
    expect(screen.queryByRole('region', { name: 'Document details' })).toBeNull();
  });

  it('restores the selected collection\u2019s persisted imports with file counters, on every reopen', async () => {
    api.listCollectionImports.mockResolvedValue(history([
      importEntry('job-2', {
        state: 'RUNNING',
        stage: 'copy',
        currentItem: 'report.pdf',
        filesCompleted: 1,
        filesTotal: 4,
      }),
      // A finished import still carries the last file the server named; its row must not show it.
      importEntry('job-1', { state: 'COMPLETE', stage: 'index', currentItem: 'done.pdf', filesCompleted: 3, filesTotal: 3 }),
    ]));

    const { unmount } = renderExpandedPanel(CollectionsPanel, props({
      collections: [collection('Nightfall', 3)],
      selectedId: 'nightfall',
    }));

    const area = await screen.findByRole('region', { name: 'Import history for Nightfall' });
    expect(api.listCollectionImports).toHaveBeenCalledWith('nightfall', { limit: 50, offset: 0 });
    await screen.findByText('1 of 4 files');
    const running = within(area).getByRole('row', { name: /Running/ });
    // The stage and the file being read come from the server; the words around them are product copy.
    expect(within(running).getByText('Copying · report.pdf')).toBeTruthy();
    expect(within(running).getByText('1 of 4 files')).toBeTruthy();
    const finished = within(area).getByRole('row', { name: /Complete/ });
    // A finished import reports no stage: the stage it last ran in is not what it is doing now.
    expect(within(finished).queryByText('Indexing')).toBeNull();
    expect(within(finished).queryByText(/done\.pdf/)).toBeNull();
    expect(within(area).getByText('3 of 3 files')).toBeTruthy();
    expect(within(area).getByText('Showing 1–2 of 2 imports')).toBeTruthy();
    // The counters name files: an import queues selected source files, never OCR pages.
    expect(area.textContent).not.toMatch(/page/i);

    // Navigating away and back reads the history from the server again instead of remembering a job.
    unmount();
    renderExpandedPanel(CollectionsPanel, props({ collections: [collection('Nightfall', 3)], selectedId: 'nightfall' }));
    expect(await screen.findByRole('region', { name: 'Import history for Nightfall' })).toBeTruthy();
    expect(api.listCollectionImports).toHaveBeenCalledTimes(2);
    expect(await screen.findByText('1 of 4 files')).toBeTruthy();
  });

  it('shows terminal import outcomes without approval or stale progress stages', async () => {
    api.listCollectionImports.mockResolvedValue(history([
      importEntry('job-done', { state: 'COMPLETE', stage: 'queue', filesCompleted: 2, filesTotal: 2 }),
      importEntry('job-failed', { state: 'FAILED', stage: 'queue', errorCode: 'IMPORT_FAILED' }),
    ]));

    renderExpandedPanel(CollectionsPanel, props({ collections: [collection('Nightfall', 3)], selectedId: 'nightfall' }));

    const area = await screen.findByRole('region', { name: 'Import history for Nightfall' });
    await screen.findByText('2 of 2 files');
    expect(within(area).queryByText('Queued')).toBeNull();
    const done = within(area).getByRole('row', { name: /Complete/ });
    expect(within(done).queryByText(/Queued|Awaiting/)).toBeNull();
    const failed = within(area).getByRole('row', { name: /Failed/ });
    expect(within(failed).queryByText('Queued')).toBeNull();
  });

  it('shows no imports yet for a collection that never imported anything', async () => {
    renderExpandedPanel(CollectionsPanel, props({ collections: [collection('Nightfall', 0)], selectedId: 'nightfall' }));

    const area = await screen.findByRole('region', { name: 'Import history for Nightfall' });
    expect(await within(area).findByText('No imports yet')).toBeTruthy();
    expect(within(area).queryByRole('table')).toBeNull();
  });

  it('pages through the history from the first page', async () => {
    api.listCollectionImports.mockResolvedValue(history([importEntry('job-1')], 51));

    renderExpandedPanel(CollectionsPanel, props({ collections: [collection('Nightfall', 1)], selectedId: 'nightfall' }));
    await screen.findByText('Showing 1–1 of 51 imports');

    await fireEvent.click(screen.getByRole('button', { name: 'Next imports' }));

    await waitFor(() => expect(api.listCollectionImports).toHaveBeenLastCalledWith('nightfall', { limit: 50, offset: 50 }));
  });

  it('shows pending, imported, duplicate and failed files with safe messages and no Retry', async () => {
    api.listCollectionImports.mockResolvedValue(history([
      importEntry('job-1', { state: 'FAILED', errorCode: 'IMPORT_FAILED', filesCompleted: 4, filesTotal: 4 }),
    ]));
    api.getImportItems.mockResolvedValue(itemsPage([
      importItem('i1', 'PENDING', { sourceName: 'still-copying.pdf' }),
      importItem('i2', 'IMPORTED', { sourceName: 'quarterly.pdf', documentId: 'doc-1' }),
      importItem('i3', 'DUPLICATE', { sourceName: 'quarterly-copy.pdf', documentId: 'doc-1' }),
      importItem('i4', 'FAILED', {
        sourceName: 'binary.bin',
        errorCode: 'UNSUPPORTED_MEDIA_TYPE',
        errorMessage: 'the pipeline has no extractor for this kind of file',
      }),
      importItem('i5', 'FAILED', {
        sourceName: 'calibre-book.mobi',
        documentId: 'doc-2',
        errorCode: 'NEEDS_TOOL',
        errorMessage: 'this e-book format needs the Calibre converter, which is not installed',
      }),
    ]));

    renderExpandedPanel(CollectionsPanel, props({ collections: [collection('Nightfall', 1)], selectedId: 'nightfall' }));
    await fireEvent.click(
      await screen.findByRole('button', { name: 'Show files for the import started 2026-09-21' }),
    );

    const files = await screen.findByRole('region', { name: 'Import files' });
    expect(api.getImportItems).toHaveBeenCalledWith('job-1', { limit: 50, offset: 0 });
    ['Pending', 'Imported', 'Duplicate', 'Failed'].forEach((label) =>
      expect(within(files).getByText(label)).toBeTruthy(),
    );
    expect(within(files).getByText('still-copying.pdf')).toBeTruthy();
    expect(within(files).getByText('binary.bin')).toBeTruthy();
    expect(within(files).getByText('the pipeline has no extractor for this kind of file')).toBeTruthy();
    // A prerequisite failure kept its document: the bytes are in the archive and Retry waits for the
    // tool, so the file's outcome says the same thing as the document row instead of "Failed".
    expect(within(files).getByText('Needs a tool')).toBeTruthy();
    expect(within(files).getByText('calibre-book.mobi')).toBeTruthy();
    expect(
      within(files).getByText('this e-book format needs the Calibre converter, which is not installed'),
    ).toBeTruthy();
    // A failed file that never became a document is resubmitted through Add documents, not retried.
    expect(within(files).getByText('This file was not added. Add it again through Add documents.')).toBeTruthy();
    expect(within(files).queryByRole('button', { name: /retry/i })).toBeNull();
    // The history names files: the absolute path a file was selected from never reaches this panel.
    expect(files.textContent).not.toContain('/private/evidence');
    expect(files.textContent).not.toContain('sourcePath');
    // The import's own row reports the job's failure honestly.
    expect(screen.getByText('Failed (IMPORT_FAILED)')).toBeTruthy();
    expect(screen.getByText('4 of 4 files')).toBeTruthy();

    await fireEvent.click(screen.getByRole('button', { name: 'Hide files for the import started 2026-09-21' }));
    expect(screen.queryByRole('region', { name: 'Import files' })).toBeNull();
  });

  it('pages the files of a large import fifty at a time', async () => {
    api.listCollectionImports.mockResolvedValue(history([importEntry('job-1', { filesCompleted: 120, filesTotal: 120 })]));
    api.getImportItems.mockResolvedValue(itemsPage([importItem('i1', 'IMPORTED', { sourceName: 'first.pdf' })], 120));

    renderExpandedPanel(CollectionsPanel, props({ collections: [collection('Nightfall', 1)], selectedId: 'nightfall' }));
    await fireEvent.click(
      await screen.findByRole('button', { name: 'Show files for the import started 2026-09-21' }),
    );

    const files = await screen.findByRole('region', { name: 'Import files' });
    await within(files).findByText('Showing 1–1 of 120 files');
    expect((within(files).getByRole('button', { name: 'Previous files' }) as HTMLButtonElement).disabled).toBe(true);
    let finishNext!: (page: ImportItemsPage) => void;
    api.getImportItems.mockImplementationOnce(() => new Promise<ImportItemsPage>((resolve) => { finishNext = resolve; }));
    await fireEvent.click(within(files).getByRole('button', { name: 'Next files' }));
    await waitFor(() => expect(api.getImportItems).toHaveBeenLastCalledWith('job-1', { limit: 50, offset: 50 }));
    // Until the next page arrives the label still describes the files on screen, and paging waits.
    expect(within(files).getByText('Showing 1–1 of 120 files')).toBeTruthy();
    expect((within(files).getByRole('button', { name: 'Next files' }) as HTMLButtonElement).disabled).toBe(true);
    finishNext(itemsPage([importItem('i51', 'IMPORTED', { sourceName: 'fifty-first.pdf' })], 120));
    await within(files).findByText('Showing 51–51 of 120 files');
    expect(within(files).getByText('fifty-first.pdf')).toBeTruthy();
    expect((within(files).getByRole('button', { name: 'Previous files' }) as HTMLButtonElement).disabled).toBe(false);
  });

  it('reports a failed history read, and a failed per-file read, without claiming an empty history', async () => {
    api.listCollectionImports.mockRejectedValue(new ApiError('INTERNAL_ERROR', 'the archive is unavailable'));

    renderExpandedPanel(CollectionsPanel, props({ collections: [collection('Nightfall', 1)], selectedId: 'nightfall' }));

    const area = await screen.findByRole('region', { name: 'Import history for Nightfall' });
    expect((await screen.findByRole('alert')).textContent).toContain('the archive is unavailable');
    expect(within(area).queryByText('No imports yet')).toBeNull();
  });

  it('reports a failed per-file read inside the import it belongs to', async () => {
    api.listCollectionImports.mockResolvedValue(history([importEntry('job-1')]));
    api.getImportItems.mockRejectedValue(new ApiError('NOT_FOUND', 'no job with id job-1'));

    renderExpandedPanel(CollectionsPanel, props({ collections: [collection('Nightfall', 1)], selectedId: 'nightfall' }));
    await fireEvent.click(
      await screen.findByRole('button', { name: 'Show files for the import started 2026-09-21' }),
    );

    const files = await screen.findByRole('region', { name: 'Import files' });
    expect(within(files).getByRole('alert').textContent).toContain('no job with id job-1');
  });

  it('refreshes a running import while mounted and stops polling when the panel is unmounted', async () => {
    vi.useFakeTimers();
    try {
      api.listCollectionImports
        .mockResolvedValueOnce(history([
          importEntry('job-1', { state: 'RUNNING', stage: 'copy', currentItem: 'first.pdf', filesCompleted: 0, filesTotal: 2 }),
        ]))
        .mockResolvedValue(history([
          importEntry('job-1', { state: 'RUNNING', stage: 'extract', currentItem: 'second.pdf', filesCompleted: 1, filesTotal: 2 }),
        ]));

      const { unmount } = renderExpandedPanel(CollectionsPanel, props({ collections: [collection('Nightfall', 2)], selectedId: 'nightfall' }));
      await act(async () => {});
      expect(api.listCollectionImports).toHaveBeenCalledTimes(1);
      expect(within(screen.getByRole('region', { name: 'Import history for Nightfall' })).getByText('Copying · first.pdf')).toBeTruthy();
      expect(within(screen.getByRole('region', { name: 'Import history for Nightfall' })).getByText(/0 of 2 files/)).toBeTruthy();

      // An unfinished import is read again from the server, which is what makes the row keep up with the
      // file being read — including after a reload, because nothing here is remembered in the browser.
      await act(async () => { await vi.advanceTimersByTimeAsync(2000); });
      expect(api.listCollectionImports).toHaveBeenCalledTimes(2);
      expect(within(screen.getByRole('region', { name: 'Import history for Nightfall' })).getByText('Extracting · second.pdf')).toBeTruthy();
      expect(within(screen.getByRole('region', { name: 'Import history for Nightfall' })).getByText(/1 of 2 files/)).toBeTruthy();
      expect(screen.queryByText(/first\.pdf/)).toBeNull();

      unmount();
      await act(async () => { await vi.advanceTimersByTimeAsync(10_000); });
      expect(api.listCollectionImports).toHaveBeenCalledTimes(2);
    } finally {
      vi.useRealTimers();
    }
  });

  it('stops polling once every listed import is terminal', async () => {
    vi.useFakeTimers();
    try {
      api.listCollectionImports
        .mockResolvedValueOnce(history([importEntry('job-1', { state: 'RUNNING', filesCompleted: 0, filesTotal: 2 })]))
        .mockResolvedValue(history([importEntry('job-1', { filesCompleted: 2, filesTotal: 2 })]));

      renderExpandedPanel(CollectionsPanel, props({ collections: [collection('Nightfall', 2)], selectedId: 'nightfall' }));
      await act(async () => {});
      await act(async () => { await vi.advanceTimersByTimeAsync(2000); });
      expect(api.listCollectionImports).toHaveBeenCalledTimes(2);
      expect(screen.getByText('2 of 2 files')).toBeTruthy();

      await act(async () => { await vi.advanceTimersByTimeAsync(10_000); });
      expect(api.listCollectionImports).toHaveBeenCalledTimes(2);
    } finally {
      vi.useRealTimers();
    }
  });

  it('does not expose approval controls for a completed import', async () => {
    api.listCollectionImports.mockResolvedValue(history([importEntry('job-1', { state: 'COMPLETE', filesCompleted: 1, filesTotal: 1 })]));
    renderExpandedPanel(CollectionsPanel, props({ collections: [collection('Nightfall', 1)], selectedId: 'nightfall' }));
    const area = await screen.findByRole('region', { name: 'Import history for Nightfall' });
    expect(await within(area).findByText('Complete')).toBeTruthy();
    expect(within(area).queryByText(/approval|review/i)).toBeNull();
  });

  it('renames the selected collection, showing pending then success, and refreshes the selectors', async () => {
    let finish!: (value: Collection) => void;
    api.renameCollection.mockImplementation(() => new Promise<Collection>((resolve) => { finish = resolve; }));
    const onCollectionsChanged = vi.fn();

    renderExpandedPanel(CollectionsPanel, props({
      collections: [collection('Nightfall', 2)],
      selectedId: 'nightfall',
      onCollectionsChanged,
    }));

    const name = await screen.findByLabelText('Collection name');
    expect((name as HTMLInputElement).value).toBe('Nightfall');
    await fireEvent.input(name, { target: { value: '  Nightfall archive  ' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Save name' }));

    // The trimmed name goes to the existing rename route, and the button says the save is in flight.
    expect(api.renameCollection).toHaveBeenCalledWith('nightfall', 'Nightfall archive');
    expect(screen.getByRole('button', { name: 'Saving name…' })).toBeTruthy();

    await act(async () => { finish(collection('Nightfall archive', 2)); });

    expect(await screen.findByText('Name saved.')).toBeTruthy();
    // Both the Admin list and the workspace selector come from the parent's reload, which this asks for.
    expect(onCollectionsChanged).toHaveBeenCalled();
    expect((screen.getByLabelText('Collection name') as HTMLInputElement).value).toBe('Nightfall archive');
    expect(screen.getByRole('button', { name: 'Save name' })).toBeTruthy();
  });

  it('shows a duplicate-name refusal on the settings form and claims no success', async () => {
    api.renameCollection.mockRejectedValue(
      new ApiError('DUPLICATE_COLLECTION_NAME', "a collection named 'Acme' already exists"),
    );
    const onCollectionsChanged = vi.fn();

    renderExpandedPanel(CollectionsPanel, props({
      collections: [collection('Nightfall'), collection('Acme')],
      selectedId: 'nightfall',
      onCollectionsChanged,
    }));

    await fireEvent.input(await screen.findByLabelText('Collection name'), { target: { value: 'Acme' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Save name' }));

    const settings = screen.getByRole('region', { name: 'Settings for Nightfall' });
    expect((await within(settings).findByRole('alert')).textContent).toContain('already exists');
    expect(within(settings).queryByText('Name saved.')).toBeNull();
    expect(onCollectionsChanged).not.toHaveBeenCalled();
    // The refused name stays in the field so it can be corrected.
    expect((screen.getByLabelText('Collection name') as HTMLInputElement).value).toBe('Acme');
  });

  it('reports the server refusal of a blank name instead of pretending it was saved', async () => {
    api.renameCollection.mockRejectedValue(new ApiError('INVALID_REQUEST', 'collection name must not be blank'));

    renderExpandedPanel(CollectionsPanel, props({ collections: [collection('Nightfall')], selectedId: 'nightfall' }));

    await fireEvent.input(await screen.findByLabelText('Collection name'), { target: { value: '   ' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Save name' }));

    expect(api.renameCollection).toHaveBeenCalledWith('nightfall', '');
    expect((await screen.findByRole('alert')).textContent).toContain('must not be blank');
    expect(screen.queryByText('Name saved.')).toBeNull();
  });

  it('shows the selected collection language in OCR settings', async () => {
    renderExpandedPanel(CollectionsPanel, props({ collections: [collection('Nightfall', 2)], selectedId: 'nightfall' }));
    expect((await screen.findByLabelText('OCR language') as HTMLInputElement).value).toBe('eng');
    expect(await screen.findByLabelText('Default reading method')).toBeTruthy();
    expect(api.listCollectionDocuments).toHaveBeenCalledTimes(1);
    expect(api.enqueueImport).not.toHaveBeenCalled();
  });

  it('switches OCR settings with the selected collection', async () => {
    const { rerender } = renderExpandedPanel(CollectionsPanel, props({
      collections: [collection('Default'), { ...collection('Nightfall'), language: 'swe' }], selectedId: 'default',
    }));
    expect((await screen.findByLabelText('OCR language') as HTMLInputElement).value).toBe('eng');
    await rerender({ selectedId: 'nightfall' });
    expect((screen.getByLabelText('OCR language') as HTMLInputElement).value).toBe('swe');
  });

  it('ignores a rename answer that arrives after another collection was selected', async () => {
    let finish!: (value: Collection) => void;
    api.renameCollection.mockImplementation(() => new Promise<Collection>((resolve) => { finish = resolve; }));
    const onCollectionsChanged = vi.fn();

    const { rerender } = renderExpandedPanel(CollectionsPanel, props({
      collections: [collection('Default'), collection('Nightfall')],
      selectedId: 'default',
      onCollectionsChanged,
    }));

    await fireEvent.input(await screen.findByLabelText('Collection name'), { target: { value: 'Renamed Default' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Save name' }));
    expect(api.renameCollection).toHaveBeenCalledWith('default', 'Renamed Default');

    // The reader manages another collection while the first save is still on its way.
    await rerender({ selectedId: 'nightfall' });
    expect((screen.getByLabelText('Collection name') as HTMLInputElement).value).toBe('Nightfall');

    await act(async () => { finish(collection('Renamed Default')); });

    // The answer belongs to the collection that is no longer managed: neither its name nor its success
    // message may land in the form the reader is now looking at.
    expect((screen.getByLabelText('Collection name') as HTMLInputElement).value).toBe('Nightfall');
    expect(screen.queryByText('Name saved.')).toBeNull();
    expect(screen.getByRole('button', { name: 'Save name' })).toBeTruthy();
    expect(onCollectionsChanged).not.toHaveBeenCalled();
  });

  it('shows the collection saved ignore patterns, one per line, in the settings', async () => {
    api.getCollectionIgnorePatterns.mockResolvedValue(['.DS_Store', '# mine', '*.tmp']);

    renderExpandedPanel(CollectionsPanel, props({ collections: [collection('Nightfall')], selectedId: 'nightfall' }));

    const field = (await screen.findByLabelText('Ignored files')) as HTMLTextAreaElement;
    await waitFor(() => expect(field.value).toBe('.DS_Store\n# mine\n*.tmp'));
    expect(api.getCollectionIgnorePatterns).toHaveBeenCalledWith('nightfall');
  });

  it('saves the ignore patterns as lines and shows the list the server kept', async () => {
    api.updateCollectionIgnorePatterns.mockResolvedValue(['*.bak', '!keep.bak']);

    renderExpandedPanel(CollectionsPanel, props({ collections: [collection('Nightfall')], selectedId: 'nightfall' }));

    const field = (await screen.findByLabelText('Ignored files')) as HTMLTextAreaElement;
    await waitFor(() => expect(field.disabled).toBe(false));
    await fireEvent.input(field, { target: { value: '*.bak\n\n!keep.bak' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Save ignored files' }));

    expect(api.updateCollectionIgnorePatterns).toHaveBeenCalledWith('nightfall', ['*.bak', '', '!keep.bak']);
    expect(await screen.findByText('Ignored files saved.')).toBeTruthy();
    expect((screen.getByLabelText('Ignored files') as HTMLTextAreaElement).value).toBe('*.bak\n!keep.bak');
  });

  it('keeps the draft and shows the reason when the server refuses a pattern', async () => {
    api.updateCollectionIgnorePatterns.mockRejectedValue(
      new ApiError('INVALID_REQUEST', "ignore pattern '!': names nothing"),
    );

    renderExpandedPanel(CollectionsPanel, props({ collections: [collection('Nightfall')], selectedId: 'nightfall' }));

    const field = (await screen.findByLabelText('Ignored files')) as HTMLTextAreaElement;
    await waitFor(() => expect(field.disabled).toBe(false));
    await fireEvent.input(field, { target: { value: '*.bak\n!' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Save ignored files' }));

    const settings = screen.getByRole('region', { name: 'Settings for Nightfall' });
    expect((await within(settings).findByRole('alert')).textContent).toContain('names nothing');
    expect(within(settings).queryByText('Ignored files saved.')).toBeNull();
    expect((screen.getByLabelText('Ignored files') as HTMLTextAreaElement).value).toBe('*.bak\n!');
  });

  it('cannot save before the saved list has been read', async () => {
    api.getCollectionIgnorePatterns.mockImplementation(() => new Promise<string[]>(() => {}));

    renderExpandedPanel(CollectionsPanel, props({ collections: [collection('Nightfall')], selectedId: 'nightfall' }));

    const save = (await screen.findByRole('button', { name: 'Save ignored files' })) as HTMLButtonElement;
    expect(save.disabled).toBe(true);
    expect((screen.getByLabelText('Ignored files') as HTMLTextAreaElement).disabled).toBe(true);
  });

  it('ignores a list read or save answer that arrives after another collection was selected', async () => {
    let finishFirstRead!: (patterns: string[]) => void;
    api.getCollectionIgnorePatterns
      .mockImplementationOnce(() => new Promise<string[]>((resolve) => { finishFirstRead = resolve; }))
      .mockResolvedValue(['*.nightfall']);
    let finishSave!: (patterns: string[]) => void;
    api.updateCollectionIgnorePatterns.mockImplementation(
      () => new Promise<string[]>((resolve) => { finishSave = resolve; }),
    );

    const { rerender } = renderExpandedPanel(CollectionsPanel, props({
      collections: [collection('Default'), collection('Nightfall')],
      selectedId: 'default',
    }));

    // The first read for Default is still on its way when the reader manages Nightfall.
    await rerender({ selectedId: 'nightfall' });
    const field = (await screen.findByLabelText('Ignored files')) as HTMLTextAreaElement;
    await waitFor(() => expect(field.value).toBe('*.nightfall'));
    await act(async () => { finishFirstRead(['*.default']); });
    expect(field.value).toBe('*.nightfall');

    // A save of Nightfall that completes after Default is selected again must not touch Default's draft.
    await fireEvent.click(screen.getByRole('button', { name: 'Save ignored files' }));
    await rerender({ selectedId: 'default' });
    await waitFor(() => expect((screen.getByLabelText('Ignored files') as HTMLTextAreaElement).value).toBe('*.nightfall'));
    await act(async () => { finishSave(['saved-for-nightfall']); });
    expect(screen.queryByText('Ignored files saved.')).toBeNull();
    expect((screen.getByLabelText('Ignored files') as HTMLTextAreaElement).value).not.toBe('saved-for-nightfall');
  });

  it('ignores a history response that a newly selected collection already replaced', async () => {
    let finishFirst!: (page: ImportHistoryPage) => void;
    api.listCollectionImports
      .mockImplementationOnce(() => new Promise<ImportHistoryPage>((resolve) => { finishFirst = resolve; }))
      .mockResolvedValue(history([importEntry('job-new', { filesCompleted: 2, filesTotal: 2 })]));

    const { rerender } = renderExpandedPanel(CollectionsPanel, props({
      collections: [collection('Default', 1), collection('Nightfall', 1)],
      selectedId: 'default',
    }));
    await rerender({ selectedId: 'nightfall' });
    expect(await screen.findByText('2 of 2 files')).toBeTruthy();

    await act(async () => { finishFirst(history([importEntry('job-old', { filesCompleted: 9, filesTotal: 9 })])); });

    expect(screen.queryByText('9 of 9 files')).toBeNull();
    expect(screen.getByText('2 of 2 files')).toBeTruthy();
    expect(api.listCollectionImports).toHaveBeenLastCalledWith('nightfall', { limit: 50, offset: 0 });
  });

  it('confirms the exact name and sends nothing when the confirmation is declined', async () => {
    renderExpandedPanel(CollectionsPanel, props({ collections: [collection('Nightfall', 2)], selectedId: 'nightfall' }));

    await fireEvent.click(screen.getByRole('button', { name: 'Delete collection' }));

    const dialog = screen.getByRole('dialog', { name: 'Delete Nightfall?' });
    // The dialog names what is removed, says it is permanent, and says the work in flight is cancelled.
    expect(dialog.textContent).toContain('permanently removes');
    expect(dialog.textContent).toContain('cancelled');

    const confirm = screen.getByLabelText('Type the collection name to confirm');
    const submit = within(dialog).getByRole('button', { name: 'Delete collection' }) as HTMLButtonElement;
    expect(submit.disabled).toBe(true);
    await fireEvent.input(confirm, { target: { value: 'nightfall' } });
    // The name has to match exactly, not case-insensitively.
    expect(submit.disabled).toBe(true);

    await fireEvent.click(within(dialog).getByRole('button', { name: 'Cancel' }));

    expect(screen.queryByRole('dialog')).toBeNull();
    expect(api.deleteCollection).not.toHaveBeenCalled();
  });

  it('contains Tab inside the open confirmation and returns focus to the opener when it closes', async () => {
    renderExpandedPanel(CollectionsPanel, props({ collections: [collection('Nightfall', 2)], selectedId: 'nightfall' }));

    const opener = screen.getByRole('button', { name: 'Delete collection' });
    opener.focus();
    await fireEvent.click(opener);

    const dialog = screen.getByRole('dialog', { name: 'Delete Nightfall?' });
    const nameInput = screen.getByLabelText('Type the collection name to confirm');
    await waitFor(() => expect(document.activeElement).toBe(nameInput));

    // Shift+Tab from the first control lands on the last one, and Tab on the last wraps back to the
    // first: keyboard focus never reaches the page behind the backdrop.
    await fireEvent.keyDown(dialog, { key: 'Tab', shiftKey: true });
    expect(document.activeElement).toBe(within(dialog).getByRole('button', { name: 'Cancel' }));

    await fireEvent.keyDown(dialog, { key: 'Tab' });
    expect(document.activeElement).toBe(nameInput);

    await fireEvent.keyDown(dialog, { key: 'Escape' });

    expect(screen.queryByRole('dialog')).toBeNull();
    expect(document.activeElement).toBe(opener);
  });

  it('contains Tab inside the open delete-documents confirmation', async () => {
    api.listCollectionDocuments.mockResolvedValue(page([documentRow('doc-1')]));

    renderExpandedPanel(CollectionsPanel, props({ collections: [collection('Nightfall', 1)], selectedId: 'nightfall' }));
    const opener = await screen.findByRole('button', { name: 'Delete doc-1.pdf' });
    await fireEvent.click(opener);

    const dialog = screen.getByRole('dialog', { name: 'Delete doc-1.pdf?' });
    const submit = within(dialog).getByRole('button', { name: 'Delete document' });
    const cancel = within(dialog).getByRole('button', { name: 'Cancel' });

    // The dialog itself takes focus when it opens, so Tab enters its first control...
    await waitFor(() => expect(document.activeElement).toBe(dialog));
    await fireEvent.keyDown(dialog, { key: 'Tab' });
    expect(document.activeElement).toBe(submit);

    // ...the last control wraps forward to the first...
    cancel.focus();
    await fireEvent.keyDown(dialog, { key: 'Tab' });
    expect(document.activeElement).toBe(submit);

    // ...and the first wraps backward to the last.
    await fireEvent.keyDown(dialog, { key: 'Tab', shiftKey: true });
    expect(document.activeElement).toBe(cancel);
  });

  it('refuses Tab while a submitted document deletion has disabled every control in the dialog', async () => {
    api.listCollectionDocuments.mockResolvedValue(page([documentRow('doc-1')]));
    let finish!: (value: DocumentDeletionAdmission) => void;
    api.deleteDocuments.mockImplementation(
      () => new Promise<DocumentDeletionAdmission>((resolve) => { finish = resolve; }),
    );

    renderExpandedPanel(CollectionsPanel, props({ collections: [collection('Nightfall', 1)], selectedId: 'nightfall' }));
    await fireEvent.click(await screen.findByRole('button', { name: 'Delete doc-1.pdf' }));

    const dialog = screen.getByRole('dialog', { name: 'Delete doc-1.pdf?' });
    const submit = within(dialog).getByRole('button', { name: 'Delete document' });
    // A browser focuses the control that was activated, and then drops that focus when the submission
    // disables it; jsdom does neither, so the button is focused here to reach the same state.
    submit.focus();
    await fireEvent.click(submit);
    await act(async () => {});

    // Both controls are disabled while the removal is in flight, so nothing inside can take focus: the
    // dialog itself holds it rather than handing it to the page behind the backdrop.
    expect(dialog.querySelectorAll('button:not([disabled])').length).toBe(0);
    await waitFor(() => expect(document.activeElement).toBe(dialog));

    const tab = new KeyboardEvent('keydown', { key: 'Tab', bubbles: true, cancelable: true });
    dialog.dispatchEvent(tab);
    expect(tab.defaultPrevented).toBe(true);
    expect(document.activeElement).toBe(dialog);

    await act(async () => {
      finish({ operationId: 'op-9', collectionId: 'nightfall', documentIds: ['doc-1'], phase: 'PREPARED' });
    });
  });

  it('deletes the confirmed collection and says Deleting… until the server reports DONE', async () => {
    api.deleteCollection.mockResolvedValue({ operationId: 'op-1', collectionId: 'nightfall', phase: 'PREPARED' });
    api.getDeletion.mockResolvedValue(deletion({ phase: 'DONE', terminal: true }));
    const onCollectionDeleted = vi.fn();
    const onCollectionsChanged = vi.fn();

    renderExpandedPanel(CollectionsPanel, props({
      collections: [collection('Nightfall', 2)],
      selectedId: 'nightfall',
      onCollectionDeleted,
      onCollectionsChanged,
    }));
    await fireEvent.click(screen.getByRole('button', { name: 'Delete collection' }));
    await fireEvent.input(screen.getByLabelText('Type the collection name to confirm'), { target: { value: 'Nightfall' } });
    await fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Delete collection' }));

    await act(async () => {});
    expect(api.deleteCollection).toHaveBeenCalledWith('nightfall', 'Nightfall');
    // The collection leaves the manageable list, and the deletion is what is shown until it is done.
    expect(onCollectionsChanged).toHaveBeenCalled();
    expect(api.getDeletion).toHaveBeenCalledWith('op-1');
    await waitFor(() => expect(onCollectionDeleted).toHaveBeenCalledWith('nightfall'));
    expect(screen.queryByText('Deleting Nightfall…')).toBeNull();
  });

  it('restores an unfinished deletion when Admin is reopened and sees it through', async () => {
    vi.useFakeTimers();
    try {
      api.listUnfinishedDeletions.mockResolvedValue([deletion()]);
      const onCollectionDeleted = vi.fn();

      renderExpandedPanel(CollectionsPanel, props({ collections: [collection('Default', 1)], onCollectionDeleted }));
      await act(async () => {});

      expect(screen.getByText('Deleting Nightfall…')).toBeTruthy();
      expect(onCollectionDeleted).not.toHaveBeenCalled();

      api.getDeletion.mockResolvedValue(deletion({ phase: 'DONE', terminal: true }));
      await act(async () => { await vi.advanceTimersByTimeAsync(2000); });

      expect(onCollectionDeleted).toHaveBeenCalledWith('nightfall');
      expect(screen.queryByText('Deleting Nightfall…')).toBeNull();
    } finally {
      vi.useRealTimers();
    }
  });

  it('reports an unsafe deletion state instead of claiming success', async () => {
    api.listUnfinishedDeletions.mockResolvedValue([deletion({ errorCode: 'UNSAFE_RECOVERY' })]);

    renderExpandedPanel(CollectionsPanel, props({ collections: [collection('Default', 1)] }));

    const alert = await screen.findByRole('alert');
    expect(alert.textContent).toContain('could not be finished safely');
    expect(alert.textContent).toContain('Nightfall');
    expect(screen.queryByText('Deleting Nightfall…')).toBeNull();
  });

  it('keeps the confirmation open when the server refuses the deletion', async () => {
    api.deleteCollection.mockRejectedValue(
      new ApiError('CONFIRMATION_MISMATCH', 'the confirmation name does not match the collection name'),
    );

    renderExpandedPanel(CollectionsPanel, props({ collections: [collection('Nightfall')], selectedId: 'nightfall' }));
    await fireEvent.click(screen.getByRole('button', { name: 'Delete collection' }));
    await fireEvent.input(screen.getByLabelText('Type the collection name to confirm'), { target: { value: 'Nightfall' } });
    await fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Delete collection' }));

    const dialog = screen.getByRole('dialog', { name: 'Delete Nightfall?' });
    expect((await within(dialog).findByRole('alert')).textContent).toContain('does not match');
    expect(screen.queryByText('Deleting Nightfall…')).toBeNull();
    // The confirmed name stays in the field so it can be corrected.
    expect((screen.getByLabelText('Type the collection name to confirm') as HTMLInputElement).value).toBe('Nightfall');
  });

  it('confirms one document by filename and collection and sends nothing when declined', async () => {
    api.listCollectionDocuments.mockResolvedValue(page([documentRow('doc-1')]));

    renderExpandedPanel(CollectionsPanel, props({ collections: [collection('Nightfall', 1)], selectedId: 'nightfall' }));
    await fireEvent.click(await screen.findByRole('button', { name: 'Delete doc-1.pdf' }));

    const dialog = screen.getByRole('dialog', { name: 'Delete doc-1.pdf?' });
    expect(dialog.textContent).toContain('doc-1.pdf');
    expect(dialog.textContent).toContain('Nightfall');
    expect(dialog.textContent).toContain('permanently removes');
    // The dialog says the targeted work is interrupted, not that the rest of the import is.
    expect(dialog.textContent).toContain('interrupted');
    expect(dialog.textContent).toContain('Other');

    await fireEvent.click(within(dialog).getByRole('button', { name: 'Cancel' }));

    expect(screen.queryByRole('dialog')).toBeNull();
    expect(api.deleteDocuments).not.toHaveBeenCalled();
  });

  it('deletes the confirmed document and says Deleting… until the server reports DONE', async () => {
    vi.useFakeTimers();
    try {
      api.listCollectionDocuments.mockResolvedValue(page([documentRow('doc-1')]));
      api.deleteDocuments.mockResolvedValue({
        operationId: 'op-9',
        collectionId: 'nightfall',
        documentIds: ['doc-1'],
        phase: 'PREPARED',
      });
      api.getDeletion.mockResolvedValue(deletion({ operationId: 'op-9', kind: 'DOCUMENT', documentIds: ['doc-1'] }));
      const onDocumentDeleted = vi.fn();
      const onCollectionsChanged = vi.fn();

      renderExpandedPanel(CollectionsPanel, props({
        collections: [collection('Nightfall', 1)],
        selectedId: 'nightfall',
        onDocumentDeleted,
        onCollectionsChanged,
      }));
      await act(async () => {});
      await fireEvent.click(screen.getByRole('button', { name: 'Delete doc-1.pdf' }));
      await fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Delete document' }));
      await act(async () => {});

      expect(api.deleteDocuments).toHaveBeenCalledWith('nightfall', ['doc-1']);
      expect(screen.getByText('Deleting doc-1.pdf in Nightfall…')).toBeTruthy();
      expect(onDocumentDeleted).not.toHaveBeenCalled();

      // The paging and the count follow once the server reports DONE, and the page is told which
      // document went so it can close the viewer it had open on it.
      api.getDeletion.mockResolvedValue(
        deletion({ operationId: 'op-9', kind: 'DOCUMENT', documentIds: ['doc-1'], phase: 'DONE', terminal: true }),
      );
      api.getCollectionDocumentSummary.mockResolvedValue({ total: 75, byStatus: { FAILED: 2, COMPLETE: 73 } });
    api.listCollectionDocuments.mockResolvedValue(page([]));

      await act(async () => { await vi.advanceTimersByTimeAsync(2000); });

      expect(onDocumentDeleted).toHaveBeenCalledWith('nightfall', 'doc-1');
      expect(onCollectionsChanged).toHaveBeenCalled();
      expect(api.listCollectionDocuments).toHaveBeenLastCalledWith('nightfall', {
        q: '',
        status: undefined,
        sort: 'newest',
        limit: 50,
        offset: 0,
      });
      expect(screen.queryByText('Deleting doc-1.pdf in Nightfall…')).toBeNull();
    } finally {
      vi.useRealTimers();
    }
  });

  it('restores an unfinished document deletion when Admin is reopened and sees it through', async () => {
    vi.useFakeTimers();
    try {
      api.listUnfinishedDeletions.mockResolvedValue([
        deletion({ operationId: 'op-9', kind: 'DOCUMENT', documentIds: ['doc-1'] }),
      ]);
      const onDocumentDeleted = vi.fn();

      renderExpandedPanel(CollectionsPanel, props({ collections: [collection('Nightfall', 1)], onDocumentDeleted }));
      await act(async () => {});

      // The table is not showing the row, so the message names the collection rather than inventing a
      // filename it cannot know.
      expect(screen.getByText('Deleting a document in Nightfall…')).toBeTruthy();
      expect(onDocumentDeleted).not.toHaveBeenCalled();

      api.getDeletion.mockResolvedValue(
        deletion({ operationId: 'op-9', kind: 'DOCUMENT', documentIds: ['doc-1'], phase: 'DONE', terminal: true }),
      );
      await act(async () => { await vi.advanceTimersByTimeAsync(2000); });

      expect(onDocumentDeleted).toHaveBeenCalledWith('nightfall', 'doc-1');
      expect(screen.queryByText('Deleting a document in Nightfall…')).toBeNull();
    } finally {
      vi.useRealTimers();
    }
  });

  it('selects only the displayed page, says so, and confirms the collection and count', async () => {
    api.listCollectionDocuments.mockResolvedValue(page([documentRow('doc-1'), documentRow('doc-2')], 51));
    api.deleteDocuments.mockResolvedValue({
      operationId: 'op-9',
      collectionId: 'nightfall',
      documentIds: ['doc-1', 'doc-2'],
      phase: 'PREPARED',
    });
    api.getDeletion.mockResolvedValue(
      deletion({ operationId: 'op-9', kind: 'DOCUMENT', documentIds: ['doc-1', 'doc-2'] }),
    );

    renderExpandedPanel(CollectionsPanel, props({ collections: [collection('Nightfall', 51)], selectedId: 'nightfall' }));

    // The page holds two of the collection's 51 documents, and select-all is named for exactly that.
    const selectAll = await screen.findByRole('checkbox', { name: 'Select all 2 documents on this page' });
    expect(screen.getByText(/Select all checks only the 2 documents on this page/)).toBeTruthy();

    await fireEvent.click(selectAll);

    expect(screen.getByText(/2 selected on this page/)).toBeTruthy();
    expect((screen.getByRole('checkbox', { name: 'Select doc-1.pdf' }) as HTMLInputElement).checked).toBe(true);
    expect((screen.getByRole('checkbox', { name: 'Select doc-2.pdf' }) as HTMLInputElement).checked).toBe(true);

    await fireEvent.click(screen.getByRole('button', { name: 'Delete selected' }));

    const dialog = screen.getByRole('dialog', { name: 'Delete 2 documents from Nightfall?' });
    expect(dialog.textContent).toContain('the 2 selected documents');
    expect(dialog.textContent).toContain('Nightfall');
    expect(dialog.textContent).toContain('permanently removes');
    expect(dialog.textContent).toContain('doc-1.pdf');
    expect(dialog.textContent).toContain('doc-2.pdf');

    await fireEvent.click(within(dialog).getByRole('button', { name: 'Delete documents' }));

    expect(api.deleteDocuments).toHaveBeenCalledWith('nightfall', ['doc-1', 'doc-2']);
    // The admitted rows are no longer a selection waiting to be sent again.
    expect(screen.queryByText(/selected on this page/)).toBeNull();
  });

  it('clears the checked rows when the filter, sort, page or collection changes', async () => {
    api.listCollectionDocuments.mockResolvedValue(page([documentRow('doc-1'), documentRow('doc-2')], 51));

    const { rerender } = renderExpandedPanel(CollectionsPanel, props({
      collections: [collection('Nightfall', 51), collection('Default', 1)],
      selectedId: 'nightfall',
    }));

    await fireEvent.click(await screen.findByRole('checkbox', { name: 'Select doc-1.pdf' }));
    expect(screen.getByText(/1 selected on this page/)).toBeTruthy();

    await fireEvent.change(screen.getByLabelText('Sort'), { target: { value: 'oldest' } });
    expect(screen.queryByText(/1 selected on this page/)).toBeNull();

    await fireEvent.click(screen.getByRole('checkbox', { name: 'Select doc-1.pdf' }));
    await fireEvent.change(screen.getByLabelText('Status'), { target: { value: 'COMPLETE' } });
    expect(screen.queryByText(/1 selected on this page/)).toBeNull();

    await fireEvent.click(screen.getByRole('checkbox', { name: 'Select doc-1.pdf' }));
    await fireEvent.click(screen.getByRole('button', { name: 'Next page' }));
    expect(screen.queryByText(/1 selected on this page/)).toBeNull();

    await fireEvent.click(screen.getByRole('checkbox', { name: 'Select doc-1.pdf' }));
    await rerender({ selectedId: 'default' });
    expect(await screen.findByLabelText('Collection name')).toBeTruthy();
    expect(screen.queryByText(/1 selected on this page/)).toBeNull();
    expect(api.deleteDocuments).not.toHaveBeenCalled();
  });

  it('keeps only the ids the refreshed page still shows, so nothing newly listed is confirmed', async () => {
    vi.useFakeTimers();
    try {
      // A document deletion is already unfinished, so the panel is polling and its completion refreshes
      // the table underneath the reader's selection.
      api.listUnfinishedDeletions.mockResolvedValue([
        deletion({ operationId: 'op-8', kind: 'DOCUMENT', documentIds: ['doc-other'] }),
      ]);
      api.listCollectionDocuments.mockResolvedValue(page([documentRow('doc-1'), documentRow('doc-2')], 2));
      api.getDeletion.mockResolvedValue(
        deletion({ operationId: 'op-8', kind: 'DOCUMENT', documentIds: ['doc-other'] }),
      );

      renderExpandedPanel(CollectionsPanel, props({ collections: [collection('Nightfall', 2)], selectedId: 'nightfall' }));
      await act(async () => {});
      await fireEvent.click(screen.getByRole('checkbox', { name: 'Select all 2 documents on this page' }));
      expect(screen.getByText(/2 selected on this page/)).toBeTruthy();

      // The refresh brings a newly imported row and drops one of the checked ones.
      api.getDeletion.mockResolvedValue(
        deletion({ operationId: 'op-8', kind: 'DOCUMENT', documentIds: ['doc-other'], phase: 'DONE', terminal: true }),
      );
      api.listCollectionDocuments.mockResolvedValue(page([documentRow('doc-2'), documentRow('doc-3')], 2));
      await act(async () => { await vi.advanceTimersByTimeAsync(2000); });

      expect(screen.getByText(/1 selected on this page/)).toBeTruthy();
      await fireEvent.click(screen.getByRole('button', { name: 'Delete selected' }));

      const dialog = screen.getByRole('dialog', { name: 'Delete doc-2.pdf?' });
      expect(dialog.textContent).not.toContain('doc-3.pdf');
      expect(dialog.textContent).not.toContain('doc-1.pdf');

      api.deleteDocuments.mockResolvedValue({
        operationId: 'op-9',
        collectionId: 'nightfall',
        documentIds: ['doc-2'],
        phase: 'PREPARED',
      });
      await fireEvent.click(within(dialog).getByRole('button', { name: 'Delete document' }));
      expect(api.deleteDocuments).toHaveBeenCalledWith('nightfall', ['doc-2']);
    } finally {
      vi.useRealTimers();
    }
  });

  it('snapshots the confirmed ids and refuses a second submission while one is running', async () => {
    let finish!: (admission: DocumentDeletionAdmission) => void;
    api.listCollectionDocuments.mockResolvedValue(page([documentRow('doc-1'), documentRow('doc-2')]));
    api.deleteDocuments.mockImplementation(() => new Promise((resolve) => { finish = resolve; }));
    api.getDeletion.mockResolvedValue(
      deletion({ operationId: 'op-9', kind: 'DOCUMENT', documentIds: ['doc-1', 'doc-2'] }),
    );

    renderExpandedPanel(CollectionsPanel, props({ collections: [collection('Nightfall', 2)], selectedId: 'nightfall' }));
    await fireEvent.click(await screen.findByRole('checkbox', { name: 'Select all 2 documents on this page' }));
    await fireEvent.click(screen.getByRole('button', { name: 'Delete selected' }));

    const dialog = screen.getByRole('dialog', { name: 'Delete 2 documents from Nightfall?' });
    const submit = within(dialog).getByRole('button', { name: 'Delete documents' }) as HTMLButtonElement;
    await fireEvent.click(submit);

    expect(submit.disabled).toBe(true);
    await fireEvent.click(submit);
    expect(api.deleteDocuments).toHaveBeenCalledTimes(1);

    await act(async () => {
      finish({ operationId: 'op-9', collectionId: 'nightfall', documentIds: ['doc-1', 'doc-2'], phase: 'PREPARED' });
    });

    expect(screen.queryByRole('dialog')).toBeNull();
    expect(screen.getByText('Deleting 2 documents in Nightfall…')).toBeTruthy();
    expect(screen.queryByText(/selected on this page/)).toBeNull();
  });

  describe('OCR reading dialog integration', () => {
    it('shows one document status and starts a rescan through the shared method dialog', async () => {
      api.listCollectionDocuments.mockResolvedValue(page([documentRow('doc-1', { originalFilename: 'ledger.pdf' })]));
      api.getCollectionDocument.mockResolvedValue({ document: documentRow('doc-1', { originalFilename: 'ledger.pdf' }), errorMessage: null, sourceId: 'unit-1' });
      api.previewRescan.mockResolvedValue({ previewId: 'preview-1', documentId: 'doc-1', managedHash: 'hash', snapshot: {} as never, pageTotal: 12, externalPageUpperBound: 0, destinations: [], external: false });
      renderExpandedPanel(CollectionsPanel, props({ collections: [collection('Nightfall', 1)], selectedId: 'nightfall' }));
      await fireEvent.click(await screen.findByRole('button', { name: 'Details' }));
      const details = await screen.findByRole('region', { name: 'Document details' });
      expect(within(details).getByTestId('document-reading-status').textContent).toContain('Done');
      await fireEvent.click(within(details).getByRole('button', { name: 'Scan again' }));
      expect(await within(details).findByRole('dialog')).toBeTruthy();
      expect(api.previewRescan).toHaveBeenCalledWith('nightfall', 'doc-1', 'tesseract');
    });
  });
});
