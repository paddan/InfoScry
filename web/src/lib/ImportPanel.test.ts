import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/svelte';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import ImportPanel from './ImportPanel.svelte';
import * as api from './api';

vi.mock('./api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('./api')>()),
  pickPaths: vi.fn(), listReadingMethods: vi.fn(), previewImport: vi.fn(), startImport: vi.fn(), getJob: vi.fn(), getImportItems: vi.fn(),
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
    vi.mocked(api.getImportItems).mockResolvedValue([]);
  });
  afterEach(cleanup);

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
});
