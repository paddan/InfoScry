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
  type ImportItemApiView,
  type RetryAdmission,
} from './api';

const api = vi.hoisted(() => ({
  createCollection: vi.fn(),
  pickPaths: vi.fn(),
  enqueueImport: vi.fn(),
  getJob: vi.fn(),
  getImportItems: vi.fn(),
  listCollectionDocuments: vi.fn(),
  listCollectionImports: vi.fn(),
  getCollectionDocument: vi.fn(),
  renameCollection: vi.fn(),
  updateCollectionOcrLanguages: vi.fn(),
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
  approveRescanExternal: vi.fn(),
  cancelRescan: vi.fn(),
  resumeRescan: vi.fn(),
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
  updateCollectionOcrSettings: api.updateCollectionOcrSettings,
  listOcrProfiles: api.listOcrProfiles,
  listOcrLlmCandidates: api.listOcrLlmCandidates,
  copyLlmProfileToOcr: api.copyLlmProfileToOcr,
  previewRescan: api.previewRescan,
  admitRescan: api.admitRescan,
  getRescanOperation: api.getRescanOperation,
  listRescanOperations: api.listRescanOperations,
  approveRescanExternal: api.approveRescanExternal,
  cancelRescan: api.cancelRescan,
  resumeRescan: api.resumeRescan,
  createCollection: api.createCollection,
  pickPaths: api.pickPaths,
  enqueueImport: api.enqueueImport,
  getJob: api.getJob,
  getImportItems: api.getImportItems,
  listCollectionDocuments: api.listCollectionDocuments,
  listCollectionImports: api.listCollectionImports,
  getCollectionDocument: api.getCollectionDocument,
  renameCollection: api.renameCollection,
  updateCollectionOcrLanguages: api.updateCollectionOcrLanguages,
  deleteCollection: api.deleteCollection,
  deleteDocuments: api.deleteDocuments,
  getDeletion: api.getDeletion,
  listUnfinishedDeletions: api.listUnfinishedDeletions,
}));

function collection(name: string, documentCount = 0): Collection {
  return {
    id: name.toLowerCase(),
    name,
    ocrLanguages: 'eng',
    ocrEngine: 'TESSERACT',
    ocrImportMode: 'FILL_MISSING',
    ocrExternalPageLimit: 0,
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
    onCollectionDeleted: vi.fn(),
    onDocumentDeleted: vi.fn(),
    onRetryDocument: vi.fn(async () => ({ accepted: true as const, jobId: 'job-1' })),
    onRetryAllDocuments: vi.fn(async () => ({ collectionId: 'nightfall', acceptedJobIds: ['job-1'], rejected: [] })),
    ...over,
  };
}

describe('collections panel', () => {
  beforeEach(() => {
    vi.resetAllMocks();
    api.listCollectionDocuments.mockResolvedValue(page([]));
    api.listCollectionImports.mockResolvedValue(history([]));
    // Nothing is being deleted unless a test says so, and a followed operation stays unfinished by
    // default so a late poll cannot turn into a phantom completion.
    api.listUnfinishedDeletions.mockResolvedValue([]);
    api.getDeletion.mockResolvedValue(deletion());
    // No OCR profiles and no earlier scans unless a test says so.
    api.listOcrProfiles.mockResolvedValue([]);
    api.listOcrLlmCandidates.mockResolvedValue([]);
    api.listRescanOperations.mockResolvedValue([]);
  });

  afterEach(cleanup);

  it('lists active collections with their document counts', () => {
    render(CollectionsPanel, props({
      collections: [collection('Default', 3), collection('Nightfall', 1)],
      selectedId: 'default',
    }));

    expect(screen.getByRole('button', { name: 'Default 3 documents' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Nightfall 1 document' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Default 3 documents' }).getAttribute('aria-pressed')).toBe('true');
    expect(screen.getByRole('button', { name: 'Nightfall 1 document' }).getAttribute('aria-pressed')).toBe('false');
  });

  it('offers the empty archive state with a create action', async () => {
    render(CollectionsPanel, props());

    expect(await screen.findByText('No collections yet')).toBeTruthy();
    await fireEvent.click(screen.getByRole('button', { name: 'Create collection' }));
    expect(screen.getByLabelText('Name')).toBeTruthy();
  });

  it('reports a collection listing failure instead of an empty archive', () => {
    render(CollectionsPanel, props({ error: 'the archive is unavailable' }));

    expect(screen.getByRole('alert').textContent).toContain('the archive is unavailable');
    expect(screen.queryByText('No collections yet')).toBeNull();
  });

  it('guides creating a collection before the first import, and can still create one named Default', async () => {
    api.createCollection.mockResolvedValue({ ...collection('Default'), id: 'a-generated-id' });
    const onCollectionsChanged = vi.fn();

    render(CollectionsPanel, props({ onCollectionsChanged }));

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
    render(CollectionsPanel, props({ collections: [collection('Default', 1)], selectedId: 'default' }));

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
    render(CollectionsPanel, props({
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

    render(CollectionsPanel, props({ collections: [collection('Default', 2)], selectedId: 'default', onCollectionsChanged }));
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

    render(CollectionsPanel, props({ collections: [collection('Nightfall')], selectedId: 'nightfall', onCollectionsChanged }));
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

    render(CollectionsPanel, props({
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

    render(CollectionsPanel, props({ collections: [collection('Nightfall', 4)], selectedId: 'nightfall' }));

    const area = screen.getByRole('region', { name: 'Manage Nightfall' });
    expect(area.textContent).toContain('4 documents');
    await screen.findByRole('table');
    expect(within(area).queryByText('No documents yet')).toBeNull();
    expect(screen.getByRole('button', { name: 'Add documents' }).getAttribute('aria-expanded')).toBe('false');
  });

  it('shows the empty collection state and reveals the import controls', async () => {
    render(CollectionsPanel, props({ collections: [collection('Nightfall', 0)], selectedId: 'nightfall' }));

    expect(await screen.findByText('No documents yet')).toBeTruthy();
    await fireEvent.click(screen.getByRole('button', { name: 'Add documents' }));

    expect(screen.getByRole('button', { name: 'Add documents' }).getAttribute('aria-expanded')).toBe('true');
    // The reused import panel names the destination collection explicitly.
    expect(await screen.findByText('Nightfall', { selector: 'strong' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Choose files…' })).toBeTruthy();
  });

  it('asks for a selection when the archive has collections but none is managed', () => {
    render(CollectionsPanel, props({ collections: [collection('Default', 2)] }));

    expect(screen.getByText('Select a collection to manage its documents.')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Add documents' })).toBeNull();
    // Nothing is managed, so no document read is made.
    expect(api.listCollectionDocuments).not.toHaveBeenCalled();
  });

  it('lists documents with their filename, media type, size, import date and readable status', async () => {
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

    render(CollectionsPanel, props({ collections: [collection('Nightfall', 2)], selectedId: 'nightfall' }));

    const quarterly = await screen.findByRole('row', { name: /quarterly\.pdf/ });
    expect(within(quarterly).getByText('application/pdf')).toBeTruthy();
    expect(within(quarterly).getByText('2 KB')).toBeTruthy();
    expect(within(quarterly).getByText('2026-09-21')).toBeTruthy();
    expect(within(quarterly).getByText('Complete with warnings')).toBeTruthy();
    expect(within(screen.getByRole('row', { name: /notes\.txt/ })).getByText('Needs a tool')).toBeTruthy();
    expect(screen.getByText('Showing 1–2 of 2 documents')).toBeTruthy();
    expect(api.listCollectionDocuments).toHaveBeenCalledWith('nightfall', {
      q: '',
      status: undefined,
      sort: 'newest',
      limit: 50,
      offset: 0,
    });
  });

  it('searches filenames from the first page and sends the trimmed term', async () => {
    api.listCollectionDocuments.mockResolvedValue(page([documentRow('doc-1')]));

    render(CollectionsPanel, props({ collections: [collection('Nightfall', 1)], selectedId: 'nightfall' }));
    await screen.findByRole('table');
    // Sorting reloads the first page; the search keeps that order and adds the trimmed term.
    await fireEvent.change(screen.getByLabelText('Sort'), { target: { value: 'oldest' } });
    await fireEvent.input(screen.getByLabelText('Search filenames'), { target: { value: '  quarterly  ' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Search' }));

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

    render(CollectionsPanel, props({ collections: [collection('Nightfall', 51)], selectedId: 'nightfall' }));
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

    render(CollectionsPanel, props({ collections: [collection('Nightfall', 0)], selectedId: 'nightfall' }));

    expect(screen.getByRole('status').textContent).toContain('Loading documents…');
    await act(async () => { finish(page([])); });

    expect(await screen.findByText('No documents yet')).toBeTruthy();
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('reports a failed listing instead of an empty collection', async () => {
    api.listCollectionDocuments.mockRejectedValue(new ApiError('INTERNAL_ERROR', 'the archive is unavailable'));

    render(CollectionsPanel, props({ collections: [collection('Nightfall', 3)], selectedId: 'nightfall' }));

    const alert = await screen.findByRole('alert');
    expect(alert.textContent).toContain('the archive is unavailable');
    expect(screen.queryByText('No documents yet')).toBeNull();
    expect(screen.queryByRole('table')).toBeNull();
  });

  it('distinguishes an empty result from the collection having no documents', async () => {
    api.listCollectionDocuments
      .mockResolvedValueOnce(page([documentRow('doc-1')], 1))
      .mockResolvedValue(page([], 0));

    render(CollectionsPanel, props({ collections: [collection('Nightfall', 1)], selectedId: 'nightfall' }));
    await screen.findByRole('table');
    await fireEvent.input(screen.getByLabelText('Search filenames'), { target: { value: 'nothing' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Search' }));

    expect(await screen.findByText('No documents match this search.')).toBeTruthy();
    expect(screen.queryByText('No documents yet')).toBeNull();
  });

  it('ignores a listing response that a newer filter already replaced', async () => {
    let finishOld!: (page: DocumentsPage) => void;
    api.listCollectionDocuments
      .mockImplementationOnce(() => new Promise<DocumentsPage>((resolve) => { finishOld = resolve; }))
      .mockResolvedValue(page([documentRow('doc-new', { originalFilename: 'new.pdf' })]));

    render(CollectionsPanel, props({ collections: [collection('Nightfall', 2)], selectedId: 'nightfall' }));
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

    const { rerender } = render(CollectionsPanel, props({
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
        errorCode: 'UNSUPPORTED_MEDIA_TYPE',
      }),
      errorMessage: 'the pipeline has no extractor for this kind of file',
      sourceId: 'unit-7',
    });

    render(CollectionsPanel, props({
      collections: [collection('Nightfall', 1)],
      selectedId: 'nightfall',
      onOpenDocument,
    }));
    await screen.findByRole('table');
    await fireEvent.click(screen.getByRole('button', { name: 'Details' }));

    const details = await screen.findByRole('region', { name: 'Document details' });
    expect(api.getCollectionDocument).toHaveBeenCalledWith('nightfall', 'doc-1');
    expect(within(details).getByText('the pipeline has no extractor for this kind of file')).toBeTruthy();
    expect(within(details).getByText('Failed')).toBeTruthy();

    await fireEvent.click(within(details).getByRole('button', { name: 'Open document' }));
    expect(onOpenDocument).toHaveBeenCalledWith('doc-1', 'unit-7');
  });

  it('reads every processing stage a document can be in, including the ones that stop for a tool', async () => {
    const stages = [
      ['QUEUED', 'Queued'],
      ['COPYING', 'Copying'],
      ['EXTRACTING', 'Extracting text'],
      ['OCR', 'Reading with OCR'],
      ['CHUNKING', 'Building passages'],
      ['EMBEDDING', 'Embedding'],
      ['INDEXING', 'Indexing'],
      ['COMPLETE', 'Complete'],
      ['COMPLETE_WITH_WARNINGS', 'Complete with warnings'],
      ['FAILED', 'Failed'],
      ['CANCELLED', 'Cancelled'],
      ['NEEDS_TOOL', 'Needs a tool'],
    ] as const;
    api.listCollectionDocuments.mockResolvedValue(page(
      stages.map(([status], index) => documentRow(`doc-${index}`, { status })),
    ));

    render(CollectionsPanel, props({ collections: [collection('Nightfall', stages.length)], selectedId: 'nightfall' }));
    await screen.findByRole('table');

    stages.forEach(([status, label], index) => {
      const row = screen.getByRole('row', { name: new RegExp(`doc-${index}\\.pdf`) });
      expect(within(row).getByText(label), status).toBeTruthy();
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

    render(CollectionsPanel, props({ collections: [collection('Nightfall', 1)], selectedId: 'nightfall' }));
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

    render(CollectionsPanel, props({ collections: [collection('Nightfall', 1)], selectedId: 'nightfall' }));
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

    render(CollectionsPanel, props({ collections: [collection('Nightfall', 1)], selectedId: 'nightfall' }));
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

    render(CollectionsPanel, props({ collections: [collection('Nightfall', 1)], selectedId: 'nightfall', onOpenDocument: vi.fn() }));
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
    api.listCollectionDocuments.mockResolvedValue(page([documentRow('doc-1')]));
    api.getCollectionDocument.mockResolvedValue({
      document: documentRow('doc-1', { status: 'QUEUED' }),
      errorMessage: null,
      sourceId: null,
    });

    render(CollectionsPanel, props({
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

  it('offers Retry for a document whose attempt stopped, and shows the attempt it queued', async () => {
    const onRetryDocument = vi.fn(async () => ({ accepted: true as const, jobId: 'job-9' }));
    api.listCollectionDocuments.mockResolvedValue(page([documentRow('doc-1', { status: 'FAILED' })]));
    api.getCollectionDocument.mockResolvedValue({
      document: documentRow('doc-1', { status: 'FAILED', errorCode: 'EXTRACTION_FAILED' }),
      errorMessage: 'the extractor stopped before it delivered every unit of this document',
      sourceId: 'unit-1',
      retryEligible: true,
    });

    render(CollectionsPanel, props({
      collections: [collection('Nightfall', 1)],
      selectedId: 'nightfall',
      onRetryDocument,
    }));
    await screen.findByRole('table');
    await fireEvent.click(screen.getByRole('button', { name: 'Details' }));

    const details = await screen.findByRole('region', { name: 'Document details' });
    // The action explains what it does, including the one thing it cannot promise: changed OCR languages
    // can mean the extraction has to run again.
    expect(within(details).getByText(/OCR languages changed/)).toBeTruthy();

    await fireEvent.click(within(details).getByRole('button', { name: 'Retry' }));

    expect(onRetryDocument).toHaveBeenCalledWith('doc-1');
    expect(await within(details).findByRole('status')).toBeTruthy();
    // The document's own state is the record, so the detail and the row are read again rather than assumed.
    expect(api.getCollectionDocument).toHaveBeenCalledTimes(2);
    expect(api.listCollectionDocuments).toHaveBeenCalledTimes(2);
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

    render(CollectionsPanel, props({
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

    render(CollectionsPanel, props({
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

    render(CollectionsPanel, props({
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

    render(CollectionsPanel, props({
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

    render(CollectionsPanel, props({
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

    render(CollectionsPanel, props({
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

    render(CollectionsPanel, props({
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

    render(CollectionsPanel, props({ collections: [collection('Nightfall', 1)], selectedId: 'nightfall' }));
    const row = await screen.findByRole('row', { name: /doc-1\.pdf/ });
    expect(within(row).getByText('Failed')).toBeTruthy();

    await fireEvent.click(screen.getByRole('button', { name: 'Retry all eligible documents' }));
    await waitFor(() => {
      expect(within(screen.getByRole('row', { name: /doc-1\.pdf/ })).getByText('Queued')).toBeTruthy();
    });

    // A reopened Admin keeps nothing from the click: the queued attempt is the server's own record.
    cleanup();
    render(CollectionsPanel, props({ collections: [collection('Nightfall', 1)], selectedId: 'nightfall' }));
    expect(within(await screen.findByRole('row', { name: /doc-1\.pdf/ })).getByText('Queued')).toBeTruthy();
    expect(screen.queryByText(/Retry queued/)).toBeNull();
  });

  it('does not offer Retry for a document whose managed copy is gone or that already succeeded', async () => {
    api.listCollectionDocuments.mockResolvedValue(page([documentRow('doc-1', { status: 'COMPLETE' })]));
    api.getCollectionDocument.mockResolvedValue({
      document: documentRow('doc-1', { status: 'COMPLETE' }),
      sourceId: 'unit-1',
      retryEligible: false,
    });

    render(CollectionsPanel, props({ collections: [collection('Nightfall', 1)], selectedId: 'nightfall' }));
    await screen.findByRole('table');
    await fireEvent.click(screen.getByRole('button', { name: 'Details' }));

    const details = await screen.findByRole('region', { name: 'Document details' });
    expect(within(details).queryByRole('button', { name: 'Retry' })).toBeNull();
  });

  it('reports a failed detail read and closes the details region', async () => {    api.listCollectionDocuments.mockResolvedValue(page([documentRow('doc-1')]));
    api.getCollectionDocument.mockRejectedValue(new ApiError('NOT_FOUND', 'no document with id doc-1 exists in this collection'));

    render(CollectionsPanel, props({ collections: [collection('Nightfall', 1)], selectedId: 'nightfall' }));
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

    const { unmount } = render(CollectionsPanel, props({
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
    render(CollectionsPanel, props({ collections: [collection('Nightfall', 3)], selectedId: 'nightfall' }));
    expect(await screen.findByRole('region', { name: 'Import history for Nightfall' })).toBeTruthy();
    expect(api.listCollectionImports).toHaveBeenCalledTimes(2);
    expect(await screen.findByText('1 of 4 files')).toBeTruthy();
  });

  it('never reads a finished import as still queued, and keeps the wait for an approval', async () => {
    api.listCollectionImports.mockResolvedValue(history([
      importEntry('job-waiting', { state: 'COMPLETE', stage: 'awaiting-approval', filesCompleted: 1, filesTotal: 3 }),
      // The stage an import last entered before it finished: the row reads its outcome, not that stage.
      importEntry('job-done', { state: 'COMPLETE', stage: 'queue', filesCompleted: 2, filesTotal: 2 }),
      importEntry('job-failed', { state: 'FAILED', stage: 'queue', errorCode: 'IMPORT_FAILED' }),
    ]));

    render(CollectionsPanel, props({ collections: [collection('Nightfall', 3)], selectedId: 'nightfall' }));

    const area = await screen.findByRole('region', { name: 'Import history for Nightfall' });
    await screen.findByText('2 of 2 files');
    expect(within(area).queryByText('Queued')).toBeNull();
    // Newest first: the waiting import is listed before the one that finished with a stale stage.
    const [waiting, done] = within(area).getAllByRole('row', { name: /Complete/ });
    expect(within(waiting).getByText('Awaiting approval')).toBeTruthy();
    expect(within(done).queryByText(/Queued|Awaiting/)).toBeNull();
    const failed = within(area).getByRole('row', { name: /Failed/ });
    expect(within(failed).queryByText('Queued')).toBeNull();
  });

  it('shows no imports yet for a collection that never imported anything', async () => {
    render(CollectionsPanel, props({ collections: [collection('Nightfall', 0)], selectedId: 'nightfall' }));

    const area = await screen.findByRole('region', { name: 'Import history for Nightfall' });
    expect(await within(area).findByText('No imports yet')).toBeTruthy();
    expect(within(area).queryByRole('table')).toBeNull();
  });

  it('pages through the history from the first page', async () => {
    api.listCollectionImports.mockResolvedValue(history([importEntry('job-1')], 51));

    render(CollectionsPanel, props({ collections: [collection('Nightfall', 1)], selectedId: 'nightfall' }));
    await screen.findByText('Showing 1–1 of 51 imports');

    await fireEvent.click(screen.getByRole('button', { name: 'Next imports' }));

    await waitFor(() => expect(api.listCollectionImports).toHaveBeenLastCalledWith('nightfall', { limit: 50, offset: 50 }));
  });

  it('shows pending, imported, duplicate and failed files with safe messages and no Retry', async () => {
    api.listCollectionImports.mockResolvedValue(history([
      importEntry('job-1', { state: 'FAILED', errorCode: 'IMPORT_FAILED', filesCompleted: 4, filesTotal: 4 }),
    ]));
    api.getImportItems.mockResolvedValue([
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
    ]);

    render(CollectionsPanel, props({ collections: [collection('Nightfall', 1)], selectedId: 'nightfall' }));
    await fireEvent.click(
      await screen.findByRole('button', { name: 'Show files for the import started 2026-09-21' }),
    );

    const files = await screen.findByRole('region', { name: 'Import files' });
    expect(api.getImportItems).toHaveBeenCalledWith('job-1');
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

  it('reports a failed history read, and a failed per-file read, without claiming an empty history', async () => {
    api.listCollectionImports.mockRejectedValue(new ApiError('INTERNAL_ERROR', 'the archive is unavailable'));

    render(CollectionsPanel, props({ collections: [collection('Nightfall', 1)], selectedId: 'nightfall' }));

    const area = await screen.findByRole('region', { name: 'Import history for Nightfall' });
    expect((await within(area).findByRole('alert')).textContent).toContain('the archive is unavailable');
    expect(within(area).queryByText('No imports yet')).toBeNull();
  });

  it('reports a failed per-file read inside the import it belongs to', async () => {
    api.listCollectionImports.mockResolvedValue(history([importEntry('job-1')]));
    api.getImportItems.mockRejectedValue(new ApiError('NOT_FOUND', 'no job with id job-1'));

    render(CollectionsPanel, props({ collections: [collection('Nightfall', 1)], selectedId: 'nightfall' }));
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

      const { unmount } = render(CollectionsPanel, props({ collections: [collection('Nightfall', 2)], selectedId: 'nightfall' }));
      await act(async () => {});
      expect(api.listCollectionImports).toHaveBeenCalledTimes(1);
      expect(screen.getByText('Copying · first.pdf')).toBeTruthy();
      expect(screen.getByText('0 of 2 files')).toBeTruthy();

      // An unfinished import is read again from the server, which is what makes the row keep up with the
      // file being read — including after a reload, because nothing here is remembered in the browser.
      await act(async () => { await vi.advanceTimersByTimeAsync(2000); });
      expect(api.listCollectionImports).toHaveBeenCalledTimes(2);
      expect(screen.getByText('Extracting · second.pdf')).toBeTruthy();
      expect(screen.getByText('1 of 2 files')).toBeTruthy();
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

      render(CollectionsPanel, props({ collections: [collection('Nightfall', 2)], selectedId: 'nightfall' }));
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

  it('renames the selected collection, showing pending then success, and refreshes the selectors', async () => {
    let finish!: (value: Collection) => void;
    api.renameCollection.mockImplementation(() => new Promise<Collection>((resolve) => { finish = resolve; }));
    const onCollectionsChanged = vi.fn();

    render(CollectionsPanel, props({
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

    render(CollectionsPanel, props({
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

    render(CollectionsPanel, props({ collections: [collection('Nightfall')], selectedId: 'nightfall' }));

    await fireEvent.input(await screen.findByLabelText('Collection name'), { target: { value: '   ' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Save name' }));

    expect(api.renameCollection).toHaveBeenCalledWith('nightfall', '');
    expect((await screen.findByRole('alert')).textContent).toContain('must not be blank');
    expect(screen.queryByText('Name saved.')).toBeNull();
  });

  it('saves OCR languages for future work, and reads no documents again', async () => {
    api.updateCollectionOcrLanguages.mockResolvedValue({ ...collection('Nightfall', 2), ocrLanguages: 'eng+swe' });

    render(CollectionsPanel, props({ collections: [collection('Nightfall', 2)], selectedId: 'nightfall' }));
    await screen.findByRole('region', { name: 'Settings for Nightfall' });

    const languages = await screen.findByLabelText('OCR languages');
    expect((languages as HTMLInputElement).value).toBe('eng');
    await fireEvent.input(languages, { target: { value: ' eng+swe ' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Save OCR languages' }));

    expect(api.updateCollectionOcrLanguages).toHaveBeenCalledWith('nightfall', 'eng+swe');
    expect(await screen.findByText('OCR languages saved.')).toBeTruthy();
    expect((screen.getByLabelText('OCR languages') as HTMLInputElement).value).toBe('eng+swe');

    const settings = screen.getByRole('region', { name: 'Settings for Nightfall' });
    expect(within(settings).getByText(/future imports and by explicit retries/)).toBeTruthy();
    expect(within(settings).getByText(/Completed documents are not reprocessed automatically/)).toBeTruthy();
    expect(within(settings).getByText(/may have to repeat extraction/)).toBeTruthy();
    // A settings save is not an automatic reprocessing: no document is re-read or re-queued.
    expect(api.listCollectionDocuments).toHaveBeenCalledTimes(1);
    expect(api.getCollectionDocument).not.toHaveBeenCalled();
    expect(api.enqueueImport).not.toHaveBeenCalled();
  });

  it('surfaces an invalid language setting and a failed save as readable errors', async () => {
    api.updateCollectionOcrLanguages.mockRejectedValueOnce(
      new ApiError('INVALID_REQUEST', 'collection ocr languages must not be blank'),
    );

    render(CollectionsPanel, props({ collections: [collection('Nightfall')], selectedId: 'nightfall' }));

    await fireEvent.input(await screen.findByLabelText('OCR languages'), { target: { value: '   ' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Save OCR languages' }));

    expect(api.updateCollectionOcrLanguages).toHaveBeenCalledWith('nightfall', '');
    const settings = screen.getByRole('region', { name: 'Settings for Nightfall' });
    expect((await within(settings).findByRole('alert')).textContent).toContain('must not be blank');
    expect(within(settings).queryByText('OCR languages saved.')).toBeNull();

    api.updateCollectionOcrLanguages.mockRejectedValueOnce(new ApiError('INTERNAL_ERROR', 'the archive is unavailable'));
    await fireEvent.input(screen.getByLabelText('OCR languages'), { target: { value: 'swe' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Save OCR languages' }));

    expect((await within(settings).findByRole('alert')).textContent).toContain('the archive is unavailable');
    expect(within(settings).queryByText('OCR languages saved.')).toBeNull();
  });

  it('shows the newly selected collection own name and languages in the settings fields', async () => {
    const { rerender } = render(CollectionsPanel, props({
      collections: [collection('Default'), { ...collection('Nightfall'), ocrLanguages: 'swe' }],
      selectedId: 'default',
    }));

    expect((await screen.findByLabelText('Collection name') as HTMLInputElement).value).toBe('Default');
    expect((screen.getByLabelText('OCR languages') as HTMLInputElement).value).toBe('eng');

    await rerender({ selectedId: 'nightfall' });

    expect((screen.getByLabelText('Collection name') as HTMLInputElement).value).toBe('Nightfall');
    expect((screen.getByLabelText('OCR languages') as HTMLInputElement).value).toBe('swe');
  });

  it('ignores a rename answer that arrives after another collection was selected', async () => {
    let finish!: (value: Collection) => void;
    api.renameCollection.mockImplementation(() => new Promise<Collection>((resolve) => { finish = resolve; }));
    const onCollectionsChanged = vi.fn();

    const { rerender } = render(CollectionsPanel, props({
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

  it('ignores an OCR-language answer that arrives after another collection was selected', async () => {
    let finish!: (value: Collection) => void;
    api.updateCollectionOcrLanguages
      .mockImplementation(() => new Promise<Collection>((resolve) => { finish = resolve; }));

    const { rerender } = render(CollectionsPanel, props({
      collections: [collection('Default'), { ...collection('Nightfall'), ocrLanguages: 'swe' }],
      selectedId: 'default',
    }));

    await fireEvent.input(await screen.findByLabelText('OCR languages'), { target: { value: 'eng+swe' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Save OCR languages' }));
    expect(api.updateCollectionOcrLanguages).toHaveBeenCalledWith('default', 'eng+swe');

    await rerender({ selectedId: 'nightfall' });
    expect((screen.getByLabelText('OCR languages') as HTMLInputElement).value).toBe('swe');

    await act(async () => { finish({ ...collection('Default'), ocrLanguages: 'eng+swe' }); });

    expect((screen.getByLabelText('OCR languages') as HTMLInputElement).value).toBe('swe');
    expect(screen.queryByText('OCR languages saved.')).toBeNull();
  });

  it('ignores a history response that a newly selected collection already replaced', async () => {
    let finishFirst!: (page: ImportHistoryPage) => void;
    api.listCollectionImports
      .mockImplementationOnce(() => new Promise<ImportHistoryPage>((resolve) => { finishFirst = resolve; }))
      .mockResolvedValue(history([importEntry('job-new', { filesCompleted: 2, filesTotal: 2 })]));

    const { rerender } = render(CollectionsPanel, props({
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
    render(CollectionsPanel, props({ collections: [collection('Nightfall', 2)], selectedId: 'nightfall' }));

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
    render(CollectionsPanel, props({ collections: [collection('Nightfall', 2)], selectedId: 'nightfall' }));

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

    render(CollectionsPanel, props({ collections: [collection('Nightfall', 1)], selectedId: 'nightfall' }));
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

    render(CollectionsPanel, props({ collections: [collection('Nightfall', 1)], selectedId: 'nightfall' }));
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

    render(CollectionsPanel, props({
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

      render(CollectionsPanel, props({ collections: [collection('Default', 1)], onCollectionDeleted }));
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

    render(CollectionsPanel, props({ collections: [collection('Default', 1)] }));

    const alert = await screen.findByRole('alert');
    expect(alert.textContent).toContain('could not be finished safely');
    expect(alert.textContent).toContain('Nightfall');
    expect(screen.queryByText('Deleting Nightfall…')).toBeNull();
  });

  it('keeps the confirmation open when the server refuses the deletion', async () => {
    api.deleteCollection.mockRejectedValue(
      new ApiError('CONFIRMATION_MISMATCH', 'the confirmation name does not match the collection name'),
    );

    render(CollectionsPanel, props({ collections: [collection('Nightfall')], selectedId: 'nightfall' }));
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

    render(CollectionsPanel, props({ collections: [collection('Nightfall', 1)], selectedId: 'nightfall' }));
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

      render(CollectionsPanel, props({
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

      render(CollectionsPanel, props({ collections: [collection('Nightfall', 1)], onDocumentDeleted }));
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

    render(CollectionsPanel, props({ collections: [collection('Nightfall', 51)], selectedId: 'nightfall' }));

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

    const { rerender } = render(CollectionsPanel, props({
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

      render(CollectionsPanel, props({ collections: [collection('Nightfall', 2)], selectedId: 'nightfall' }));
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

    render(CollectionsPanel, props({ collections: [collection('Nightfall', 2)], selectedId: 'nightfall' }));
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

  describe('OCR engine settings and rescanning', () => {
    const cloud = {
      id: 'p-cloud',
      name: 'Cloud vision',
      enabled: true,
      revisionId: 'p-cloud-r1',
      sequence: 1,
      provider: 'OPENAI_COMPATIBLE' as const,
      endpoint: 'https://example.test/v1',
      scope: 'EXTERNAL' as const,
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

    const snapshot = {
      engine: 'LLM' as const,
      mode: 'CHECK_AND_IMPROVE' as const,
      language: 'eng',
      extractorVersion: '3',
      transcriptionPromptVersion: 1,
      reviewPromptVersion: 2,
      policyVersion: 1,
      externalPageLimit: 5,
      transcriptionProfileRevisionId: 'p-cloud-r1',
    };

    function scan(stage: 'AWAITING_APPROVAL' | 'OCR' | 'COMPLETE', over: Record<string, unknown> = {}) {
      return {
        operationId: 'op-1',
        collectionId: 'nightfall',
        documentId: 'doc-1',
        snapshot,
        stage,
        pageTotal: 12,
        pagesCommitted: 3,
        pagesFailed: 0,
        external: { distinctPages: 5, calls: 6, allowance: 5 },
        pendingReviewCount: 0,
        requestId: 'request-1',
        createdAt: '2026-09-21T07:00:00Z',
        updatedAt: '2026-09-21T07:00:01Z',
        ...over,
      };
    }

    function previewOf(over: Record<string, unknown> = {}) {
      return {
        previewId: 'preview-1',
        documentId: 'doc-1',
        managedHash: 'abc',
        snapshot,
        snapshotHash: 'hash-1',
        pageTotal: 12,
        externalPageUpperBound: 12,
        destinations: [],
        approvalRequired: false,
        externalAllowance: 5,
        expiresAt: '2026-09-21T08:00:00Z',
        ...over,
      };
    }

    async function openDetails(): Promise<HTMLElement> {
      api.listCollectionDocuments.mockResolvedValue(page([documentRow('doc-1', { originalFilename: 'ledger.pdf' })]));
      api.getCollectionDocument.mockResolvedValue({
        document: documentRow('doc-1', { originalFilename: 'ledger.pdf' }),
        errorMessage: null,
        sourceId: 'unit-1',
      });
      render(CollectionsPanel, props({ collections: [collection('Nightfall', 1)], selectedId: 'nightfall' }));
      await screen.findByRole('table');
      await fireEvent.click(screen.getByRole('button', { name: 'Details' }));
      return screen.findByRole('region', { name: 'Document details' });
    }

    it('shows the collection OCR engine controls beside the languages and saves through the settings route', async () => {
      api.listOcrProfiles.mockResolvedValue([cloud]);
      api.updateCollectionOcrSettings.mockResolvedValue({
        ...collection('Nightfall'),
        ocrEngine: 'LLM',
        ocrTranscriptionProfileId: 'p-cloud',
        ocrExternalPageLimit: 5,
      });
      const onCollectionsChanged = vi.fn();
      render(CollectionsPanel, props({
        collections: [collection('Nightfall')],
        selectedId: 'nightfall',
        onCollectionsChanged,
      }));

      const settings = await screen.findByRole('region', { name: 'Settings for Nightfall' });
      expect((within(settings).getByLabelText('OCR engine') as HTMLSelectElement).value).toBe('TESSERACT');
      await act(async () => {});
      await fireEvent.change(within(settings).getByLabelText('OCR engine'), { target: { value: 'LLM' } });
      await fireEvent.change(within(settings).getByLabelText('Transcription profile'), { target: { value: 'p-cloud' } });
      await fireEvent.input(within(settings).getByLabelText('External page allowance'), { target: { value: '5' } });
      await fireEvent.click(within(settings).getByRole('button', { name: 'Save OCR engine settings' }));
      await act(async () => {});

      expect(api.updateCollectionOcrSettings).toHaveBeenCalledWith('nightfall', {
        ocrEngine: 'LLM',
        ocrImportMode: 'FILL_MISSING',
        ocrTranscriptionProfileId: 'p-cloud',
        ocrReviewProfileId: '',
        ocrExternalPageLimit: 5,
      });
      expect(api.updateCollectionOcrLanguages).not.toHaveBeenCalled();
      expect(onCollectionsChanged).toHaveBeenCalled();
    });

    it('does not let a settings save of one collection overwrite the collection selected meanwhile', async () => {
      let finish!: (value: Collection) => void;
      api.updateCollectionOcrSettings.mockImplementation(() => new Promise<Collection>((resolve) => { finish = resolve; }));
      const { rerender } = render(CollectionsPanel, props({
        collections: [collection('Nightfall'), { ...collection('Dawn'), ocrImportMode: 'CHECK_AND_IMPROVE', ocrExternalPageLimit: 9 }],
        selectedId: 'nightfall',
      }));

      await act(async () => {});
      await fireEvent.change(await screen.findByLabelText('OCR engine'), { target: { value: 'SURYA' } });
      await fireEvent.click(screen.getByRole('button', { name: 'Save OCR engine settings' }));
      await rerender({ selectedId: 'dawn' });
      await act(async () => {});

      expect((screen.getByLabelText('OCR engine') as HTMLSelectElement).value).toBe('TESSERACT');
      expect((screen.getByLabelText('Import mode') as HTMLSelectElement).value).toBe('CHECK_AND_IMPROVE');
      finish({ ...collection('Nightfall'), ocrEngine: 'SURYA' });
      await act(async () => {});

      expect((screen.getByLabelText('OCR engine') as HTMLSelectElement).value).toBe('TESSERACT');
      expect((screen.getByLabelText('External page allowance') as HTMLInputElement).value).toBe('9');
      expect(screen.queryByText('OCR engine settings saved.')).toBeNull();
    });

    it('offers Scan again in the document details and previews for the selected document', async () => {
      api.previewRescan.mockResolvedValue(previewOf());
      const details = await openDetails();
      await act(async () => {});

      expect(api.listRescanOperations).toHaveBeenCalledWith('nightfall', 'doc-1');
      await fireEvent.click(within(details).getByRole('button', { name: 'Scan again' }));
      await act(async () => {});
      await fireEvent.click(within(details).getByRole('button', { name: 'Preview scan' }));
      await act(async () => {});

      expect(api.previewRescan).toHaveBeenCalledWith('nightfall', 'doc-1', {});
      expect(within(details).getByRole('region', { name: 'Scan preview' })).toBeTruthy();
    });

    it('restores a waiting approval after a reload from the persisted operation', async () => {
      api.listRescanOperations.mockResolvedValue([scan('AWAITING_APPROVAL')]);
      api.getRescanOperation.mockResolvedValue(scan('AWAITING_APPROVAL'));
      const details = await openDetails();
      await act(async () => {});

      const latest = within(details).getByRole('group', { name: 'Latest scan' });
      expect(latest.textContent).toContain('Waiting for approval');
      expect(within(latest).getByRole('button', { name: 'Review approval' })).toBeTruthy();
      expect(within(latest).getByRole('button', { name: 'Cancel scan' })).toBeTruthy();
    });

    it('drops a preview that answers after another collection was selected', async () => {
      let late!: (value: ReturnType<typeof previewOf>) => void;
      api.previewRescan.mockImplementation(() => new Promise<ReturnType<typeof previewOf>>((resolve) => { late = resolve; }));
      api.listCollectionDocuments
        .mockResolvedValueOnce(page([documentRow('doc-1', { originalFilename: 'ledger.pdf' })]))
        .mockResolvedValue(page([]));
      api.getCollectionDocument.mockResolvedValue({
        document: documentRow('doc-1', { originalFilename: 'ledger.pdf' }),
        errorMessage: null,
        sourceId: 'unit-1',
      });
      const { rerender } = render(CollectionsPanel, props({
        collections: [collection('Nightfall', 1), collection('Dawn')],
        selectedId: 'nightfall',
      }));
      await screen.findByRole('table');
      await fireEvent.click(screen.getByRole('button', { name: 'Details' }));
      await screen.findByRole('region', { name: 'Document details' });
      await act(async () => {});
      await fireEvent.click(screen.getByRole('button', { name: 'Scan again' }));
      await act(async () => {});
      await fireEvent.click(screen.getByRole('button', { name: 'Preview scan' }));

      await rerender({ selectedId: 'dawn' });
      await act(async () => {});
      late(previewOf());
      await act(async () => {});

      expect(screen.queryByRole('region', { name: 'Scan preview' })).toBeNull();
      expect(screen.queryByRole('button', { name: 'Start scan' })).toBeNull();
    });

    it('drops an operation read that answers after another collection was selected', async () => {
      vi.useFakeTimers();
      try {
        api.listRescanOperations.mockResolvedValue([scan('OCR')]);
        let late!: (value: ReturnType<typeof scan>) => void;
        api.getRescanOperation.mockImplementation(() => new Promise<ReturnType<typeof scan>>((resolve) => { late = resolve; }));
        api.listCollectionDocuments
          .mockResolvedValueOnce(page([documentRow('doc-1', { originalFilename: 'ledger.pdf' })]))
          .mockResolvedValue(page([]));
        api.getCollectionDocument.mockResolvedValue({
          document: documentRow('doc-1', { originalFilename: 'ledger.pdf' }),
          errorMessage: null,
          sourceId: 'unit-1',
        });
        const { rerender } = render(CollectionsPanel, props({
          collections: [collection('Nightfall', 1), collection('Dawn')],
          selectedId: 'nightfall',
        }));
        await act(async () => {});
        await fireEvent.click(await screen.findByRole('button', { name: 'Details' }));
        await act(async () => {});
        await act(async () => { await vi.advanceTimersByTimeAsync(2000); });

        await rerender({ selectedId: 'dawn' });
        await act(async () => {});
        late(scan('COMPLETE', { pendingReviewCount: 4 }));
        await act(async () => {});

        expect(screen.queryByRole('group', { name: 'Latest scan' })).toBeNull();
        expect(screen.queryByText(/Needs review: 4 pages/)).toBeNull();
      } finally {
        vi.useRealTimers();
      }
    });
  });
});