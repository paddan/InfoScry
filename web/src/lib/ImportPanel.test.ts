import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/svelte';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import ImportPanel from './ImportPanel.svelte';
import * as api from './api';

vi.mock('./api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('./api')>()),
  pickPaths: vi.fn(), listReadingMethods: vi.fn(), previewImport: vi.fn(), startImport: vi.fn(), getJob: vi.fn(), getImportItems: vi.fn(),
  cancelJob: vi.fn(),
}));

const methods: api.ReadingMethodList = { default: 'surya', methods: [
  { method: 'surya', label: 'Surya', destination: 'this machine', available: true, unavailableReason: null, external: false },
] };
const job: api.JobApiView = { id: 'job-1', type: 'IMPORT', state: 'COMPLETE', createdAt: '', updatedAt: '', completed: 1, total: 1, cancelRequested: false };
const preview: api.ImportPreview = { files: [{ path: '/archive/a.pdf', pages: 4, reason: null }], totalPages: 4, atLeast: false, destination: 'this machine', external: false, estimatedCostUsd: null, costBasis: null, previewHash: 'confirmed-hash' };

describe('ImportPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(api.pickPaths).mockResolvedValue(['/archive/a.pdf']);
    vi.mocked(api.listReadingMethods).mockResolvedValue(methods);
    vi.mocked(api.previewImport).mockResolvedValue(preview);
    vi.mocked(api.startImport).mockResolvedValue({ job });
    vi.mocked(api.getJob).mockResolvedValue(job);
    vi.mocked(api.getImportItems).mockResolvedValue({ items: [], total: 0 });
  });
  afterEach(cleanup);

  it('shows page progress separately from file progress and updates on polling', async () => {
    vi.mocked(api.startImport).mockResolvedValue({ job: { ...job, state: 'RUNNING', completed: 0,
      currentItem: 'a.pdf', currentProgress: { unitKind: 'PAGE', processedUnits: 1, totalUnits: 4, failedUnits: 0 } } } as api.ImportStarted);
    vi.mocked(api.getJob).mockResolvedValue({ ...job, state: 'RUNNING', completed: 0,
      currentItem: 'a.pdf', currentProgress: { unitKind: 'PAGE', processedUnits: 2, totalUnits: 4, failedUnits: 0 } } as api.JobApiView);
    render(ImportPanel, { collectionId: 'c1', collectionName: 'Archive' });
    await fireEvent.click(screen.getByRole('button', { name: 'Choose files…' }));
    await fireEvent.click(screen.getByRole('button', { name: 'Import' }));
    await fireEvent.click(await screen.findByRole('button', { name: 'Read 4 pages with Surya' }));
    const pages = await screen.findByRole('progressbar', { name: 'Page progress' });
    expect(pages.getAttribute('value')).toBe('1');
    expect(pages.getAttribute('max')).toBe('4');
    expect(screen.getByRole('progressbar', { name: 'Import progress' }).getAttribute('value')).toBe('0');
    await waitFor(() => expect(pages.getAttribute('value')).toBe('2'), { timeout: 2500 });
    expect(screen.getByText('2 of 4 pages processed')).toBeTruthy();
  });

  it('routes import through the dialog and confirms the method and preview hash', async () => {
    render(ImportPanel, { collectionId: 'c1', collectionName: 'Archive' });
    await fireEvent.click(screen.getByRole('button', { name: 'Choose files…' }));
    expect(await screen.findByText('/archive/a.pdf')).toBeTruthy();
    await fireEvent.click(screen.getByRole('button', { name: 'Import' }));
    expect(await screen.findByRole('dialog')).toBeTruthy();
    const confirm = await screen.findByRole('button', { name: 'Read 4 pages with Surya' });
    await fireEvent.click(confirm);
    await waitFor(() => expect(api.startImport).toHaveBeenCalledTimes(1));
    expect(api.previewImport).toHaveBeenCalledWith({ collection: 'c1', paths: ['/archive/a.pdf'], recursive: false, include: [], exclude: [], method: 'surya' });
    expect(api.startImport).toHaveBeenCalledWith(expect.objectContaining({
      collection: 'c1', paths: ['/archive/a.pdf'], method: 'surya', previewHash: 'confirmed-hash', requestId: expect.any(String),
    }));
    expect(await screen.findByText('Import complete. 0 files.')).toBeTruthy();
  });

  it('keeps the selected folder filters and recursive flag in the dialog request', async () => {
    render(ImportPanel, { collectionId: 'c1', collectionName: 'Archive' });
    await fireEvent.click(screen.getByRole('button', { name: 'Choose folder…' }));
    await fireEvent.click(screen.getByLabelText('Include subfolders'));
    await fireEvent.click(screen.getByLabelText('Only these extensions'));
    await fireEvent.input(screen.getByLabelText('Extensions'), { target: { value: '.PDF, docx' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Import' }));
    await screen.findByRole('dialog');
    await waitFor(() => expect(api.previewImport).toHaveBeenCalledWith(expect.objectContaining({
      recursive: true, include: ['pdf', 'docx'], exclude: [],
    })));
  });

  it('retains the readable import status container without showing approval UI', async () => {
    render(ImportPanel, { collectionId: 'c1', collectionName: 'Archive' });
    await fireEvent.click(screen.getByRole('button', { name: 'Choose files…' }));
    await screen.findByText('/archive/a.pdf');
    await fireEvent.click(screen.getByRole('button', { name: 'Import' }));
    expect(await screen.findByRole('dialog')).toBeTruthy();
    expect(screen.queryByText(/approval|review/i)).toBeNull();
  });

  it('stops a running import and lets the next one start', async () => {
    const running = { ...job, state: 'RUNNING', completed: 0, total: 3 } as api.JobApiView;
    let cancelled = false;
    vi.mocked(api.startImport).mockResolvedValue({ job: running });
    vi.mocked(api.getJob).mockImplementation(async () => (cancelled ? { ...running, state: 'CANCELLED', cancelRequested: true } : running));
    vi.mocked(api.cancelJob).mockImplementation(async () => {
      cancelled = true;
      return { ...running, cancelRequested: true };
    });
    render(ImportPanel, { collectionId: 'c1', collectionName: 'Archive' });
    await fireEvent.click(screen.getByRole('button', { name: 'Choose files…' }));
    await fireEvent.click(screen.getByRole('button', { name: 'Import' }));
    await fireEvent.click(await screen.findByRole('button', { name: 'Read 4 pages with Surya' }));
    await fireEvent.click(await screen.findByRole('button', { name: 'Stop importing' }));
    expect(api.cancelJob).toHaveBeenCalledWith('job-1');
    const stopping = await screen.findByRole('button', { name: 'Stopping…' }) as HTMLButtonElement;
    expect(stopping.disabled).toBe(true);
    expect(await screen.findByText('Import cancelled.', {}, { timeout: 2500 })).toBeTruthy();
    expect(screen.queryByRole('button', { name: /Stop/ })).toBeNull();
    expect((screen.getByRole('button', { name: 'Import' }) as HTMLButtonElement).disabled).toBe(false);
  });

  it('shows why a stop failed and keeps the stop button usable', async () => {
    const running = { ...job, state: 'RUNNING', completed: 0, total: 3 } as api.JobApiView;
    vi.mocked(api.startImport).mockResolvedValue({ job: running });
    vi.mocked(api.getJob).mockResolvedValue(running);
    vi.mocked(api.cancelJob).mockRejectedValue(new Error('Server unavailable.'));
    render(ImportPanel, { collectionId: 'c1', collectionName: 'Archive' });
    await fireEvent.click(screen.getByRole('button', { name: 'Choose files…' }));
    await fireEvent.click(screen.getByRole('button', { name: 'Import' }));
    await fireEvent.click(await screen.findByRole('button', { name: 'Read 4 pages with Surya' }));
    await fireEvent.click(await screen.findByRole('button', { name: 'Stop importing' }));
    expect(await screen.findByText('Server unavailable.')).toBeTruthy();
    expect((screen.getByRole('button', { name: 'Stop importing' }) as HTMLButtonElement).disabled).toBe(false);
  });

  it('follows an unfinished import it is handed, as after a server restart', async () => {
    vi.mocked(api.getJob).mockResolvedValue({ ...job, state: 'RUNNING', completed: 1, total: 3,
      currentItem: 'a.pdf', currentProgress: { unitKind: 'PAGE', processedUnits: 2, totalUnits: 4, failedUnits: 0 } } as api.JobApiView);
    render(ImportPanel, { collectionId: 'c1', collectionName: 'Archive', unfinishedImportId: 'job-1' });
    const files = await screen.findByRole('progressbar', { name: 'Import progress' });
    expect(api.getJob).toHaveBeenCalledWith('job-1');
    expect(files.getAttribute('value')).toBe('1');
    expect(files.getAttribute('max')).toBe('3');
    expect(screen.getByRole('progressbar', { name: 'Page progress' }).getAttribute('value')).toBe('2');
    expect(screen.getByRole('button', { name: 'Stop importing' })).toBeTruthy();
    expect((screen.getByRole('button', { name: 'Importing…' }) as HTMLButtonElement).disabled).toBe(true);
  });

  it('shows no progress when the handed import has already ended', async () => {
    render(ImportPanel, { collectionId: 'c1', collectionName: 'Archive', unfinishedImportId: 'job-1' });
    await waitFor(() => expect(api.getJob).toHaveBeenCalledWith('job-1'));
    expect(screen.queryByRole('progressbar')).toBeNull();
    expect(screen.queryByText(/Import complete/)).toBeNull();
  });

  it('reports every file of a large import but shows its results fifty at a time', async () => {
    const item = (n: number): api.ImportItemApiView => ({ id: `i${n}`, jobId: 'job-1', documentId: null, sourceName: `file-${n}.pdf`, outcome: 'IMPORTED', createdAt: '', updatedAt: '' });
    vi.mocked(api.getImportItems).mockImplementation(async (_id, page) => ({
      items: Array.from({ length: Math.min(page.limit, 120 - page.offset) }, (_, i) => item(page.offset + i + 1)),
      total: 120,
    }));
    render(ImportPanel, { collectionId: 'c1', collectionName: 'Archive' });
    await fireEvent.click(screen.getByRole('button', { name: 'Choose files…' }));
    await fireEvent.click(screen.getByRole('button', { name: 'Import' }));
    await fireEvent.click(await screen.findByRole('button', { name: 'Read 4 pages with Surya' }));
    expect(await screen.findByText('Import complete. 120 files.')).toBeTruthy();
    expect(api.getImportItems).toHaveBeenLastCalledWith('job-1', { limit: 50, offset: 0 });
    expect(screen.getAllByRole('row')).toHaveLength(51);
    expect(screen.getByText('Showing 1–50 of 120 files')).toBeTruthy();
    expect((screen.getByRole('button', { name: 'Previous files' }) as HTMLButtonElement).disabled).toBe(true);

    await fireEvent.click(screen.getByRole('button', { name: 'Next files' }));
    await fireEvent.click(await screen.findByRole('button', { name: 'Next files' }));
    await waitFor(() => expect(api.getImportItems).toHaveBeenLastCalledWith('job-1', { limit: 50, offset: 100 }));
    expect(await screen.findByText('Showing 101–120 of 120 files')).toBeTruthy();
    expect(screen.getByText('file-120.pdf')).toBeTruthy();
    expect((screen.getByRole('button', { name: 'Next files' }) as HTMLButtonElement).disabled).toBe(true);
  });
});
