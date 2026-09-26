import { act, cleanup, fireEvent, render, screen } from '@testing-library/svelte';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import ImportPanel from './ImportPanel.svelte';
import { ApiError } from './api';
import type { ImportItemApiView, JobApiView } from './api';

const api = vi.hoisted(() => ({
  pickPaths: vi.fn(),
  enqueueImport: vi.fn(),
  getJob: vi.fn(),
  getImportItems: vi.fn(),
  createCollection: vi.fn(),
}));

vi.mock('./api', () => ({
  ApiError: class ApiError extends Error {
    code: string;
    constructor(code: string, message: string) {
      super(message);
      this.code = code;
    }
  },
  pickPaths: api.pickPaths,
  enqueueImport: api.enqueueImport,
  getJob: api.getJob,
  getImportItems: api.getImportItems,
  createCollection: api.createCollection,
}));

function job(id: string, state: JobApiView['state'], completed = 0, total = 1): JobApiView {
  return {
    id,
    type: 'IMPORT',
    state,
    createdAt: '2026-09-21T07:00:00Z',
    updatedAt: '2026-09-21T07:00:01Z',
    completed,
    total,
    cancelRequested: false,
  };
}

function item(id: string, outcome: ImportItemApiView['outcome'], over: Partial<ImportItemApiView> = {}): ImportItemApiView {
  return {
    id,
    jobId: 'j1',
    documentId: null,
    outcome,
    createdAt: '2026-09-21T07:00:00Z',
    updatedAt: '2026-09-21T07:00:01Z',
    ...over,
  };
}

describe('import panel', () => {
  beforeEach(() => {
    vi.resetAllMocks();
  });

  afterEach(cleanup);

  it('appends picked file paths and deduplicates repeats', async () => {
    api.pickPaths.mockResolvedValue(['/home/example/a.pdf', '/home/example/b.pdf']);

    render(ImportPanel, { collectionId: 'c1', onCollectionsChanged: vi.fn() });
    await fireEvent.click(screen.getByRole('button', { name: 'Choose files…' }));

    expect(await screen.findByText('/home/example/a.pdf')).toBeTruthy();
    expect(screen.getByText('/home/example/b.pdf')).toBeTruthy();
    expect(api.pickPaths).toHaveBeenCalledWith(false);

    await fireEvent.click(screen.getByRole('button', { name: 'Choose files…' }));
    await screen.findByText('/home/example/a.pdf');
    expect(screen.getAllByText('/home/example/a.pdf')).toHaveLength(1);
  });

  it('chooses one folder with the directory flag', async () => {
    api.pickPaths.mockResolvedValue(['/home/example/docs']);

    render(ImportPanel, { collectionId: 'c1', onCollectionsChanged: vi.fn() });
    await fireEvent.click(screen.getByRole('button', { name: 'Choose folder…' }));

    expect(await screen.findByText('/home/example/docs')).toBeTruthy();
    expect(api.pickPaths).toHaveBeenCalledWith(true);
  });

  it('sends the recursive checkbox state in the import request body', async () => {
    api.pickPaths.mockResolvedValue(['/home/example/docs']);
    api.enqueueImport.mockResolvedValue({ accepted: true, job: job('j1', 'COMPLETE') });
    api.getImportItems.mockResolvedValue([]);

    render(ImportPanel, { collectionId: 'c1', onCollectionsChanged: vi.fn() });
    await fireEvent.click(screen.getByRole('button', { name: 'Choose folder…' }));
    await screen.findByText('/home/example/docs');
    await fireEvent.click(screen.getByLabelText('Include subfolders'));
    await fireEvent.click(screen.getByRole('button', { name: 'Import' }));

    expect(await screen.findByText('Import complete. 0 items.')).toBeTruthy();
    expect(api.enqueueImport).toHaveBeenCalledWith('c1', ['/home/example/docs'], true);
  });

  it('shows the manual path fallback on PICK_UNAVAILABLE and imports the entered path', async () => {
    api.pickPaths.mockRejectedValue(new ApiError('PICK_UNAVAILABLE', 'the pick dialog could not be opened on this machine'));
    api.enqueueImport.mockResolvedValue({ accepted: true, job: job('j1', 'COMPLETE') });
    api.getImportItems.mockResolvedValue([]);

    render(ImportPanel, { collectionId: 'c1', onCollectionsChanged: vi.fn() });
    await fireEvent.click(screen.getByRole('button', { name: 'Choose files…' }));

    expect((await screen.findByRole('alert')).textContent)
      .toContain('the pick dialog could not be opened on this machine');
    await fireEvent.input(screen.getByLabelText('Path'), { target: { value: '/home/example/manual.pdf' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Add' }));
    expect(screen.getByText('/home/example/manual.pdf')).toBeTruthy();

    await fireEvent.click(screen.getByRole('button', { name: 'Import' }));
    expect(await screen.findByText('Import complete. 0 items.')).toBeTruthy();
    expect(api.enqueueImport).toHaveBeenCalledWith('c1', ['/home/example/manual.pdf'], false);
  });

  it('does not treat a cancelled pick as an error', async () => {
    api.pickPaths.mockRejectedValue(new ApiError('PICK_CANCELLED', 'the pick dialog was closed without choosing anything'));

    render(ImportPanel, { collectionId: 'c1', onCollectionsChanged: vi.fn() });
    await fireEvent.click(screen.getByRole('button', { name: 'Choose files…' }));

    await act(async () => {});
    expect(screen.queryByRole('alert')).toBeNull();
    expect(screen.getByText('No paths selected yet.')).toBeTruthy();
  });

  it('polls the job until a terminal state and renders per-file results', async () => {
    api.pickPaths.mockResolvedValue(['/home/example/a.pdf']);
    api.enqueueImport.mockResolvedValue({ accepted: true, job: job('j1', 'RUNNING', 0, 2) });
    api.getJob
      .mockResolvedValueOnce(job('j1', 'RUNNING', 1, 2))
      .mockResolvedValueOnce(job('j1', 'COMPLETE', 2, 2));
    api.getImportItems.mockResolvedValue([
      item('i1', 'IMPORTED', { sourcePath: '/home/example/a.pdf', documentId: 'd1' }),
      item('i2', 'FAILED', {
        sourceName: 'b.pdf',
        documentId: null,
        errorCode: 'UNSUPPORTED_MEDIA_TYPE',
        errorMessage: 'the file type is not supported',
      }),
    ]);

    render(ImportPanel, { collectionId: 'c1', onCollectionsChanged: vi.fn() });
    await fireEvent.click(screen.getByRole('button', { name: 'Choose files…' }));
    await screen.findByText('/home/example/a.pdf');
    await fireEvent.click(screen.getByRole('button', { name: 'Import' }));

    // The enqueued job is still running: the status shows its start counters immediately.
    expect(await screen.findByText('Importing… (0 of 2 items)', undefined, { timeout: 3000 })).toBeTruthy();
    // A determinate progress bar tracks the job's completed/total counters while it runs.
    const bar = screen.getByRole('progressbar', { name: 'Import progress' });
    expect(bar.getAttribute('max')).toBe('2');
    expect(bar.getAttribute('value')).toBe('0');
    // Polling then advances the job to a terminal state and the items render.
    expect(await screen.findByText('Import complete. 2 items.', undefined, { timeout: 5000 })).toBeTruthy();
    expect(api.getJob).toHaveBeenCalledTimes(2);
    expect(api.getImportItems).toHaveBeenCalledWith('j1');
    // The path appears both in the selected-paths list and in the results table.
    expect(screen.getAllByText('/home/example/a.pdf')).toHaveLength(2);
    expect(screen.getByText('IMPORTED')).toBeTruthy();
    expect(screen.getByText('b.pdf')).toBeTruthy();
    expect(screen.getByText('FAILED')).toBeTruthy();
    expect(screen.getByText('the file type is not supported (UNSUPPORTED_MEDIA_TYPE)')).toBeTruthy();
  });

  it('creates a collection and notifies the parent with the new id', async () => {
    api.createCollection.mockResolvedValue({
      id: 'c2',
      name: 'Nightfall',
      ocrLanguages: 'eng',
      createdAt: '2026-09-21T07:00:00Z',
      updatedAt: '2026-09-21T07:00:00Z',
      description: 'night archives',
      lifecycle: 'ACTIVE',
    });
    const onCollectionsChanged = vi.fn();

    render(ImportPanel, { collectionId: 'c1', onCollectionsChanged });
    await fireEvent.click(screen.getByText('New collection'));
    await fireEvent.input(screen.getByLabelText('Name'), { target: { value: 'Nightfall' } });
    await fireEvent.input(screen.getByLabelText('Description'), { target: { value: 'night archives' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Create' }));

    await act(async () => {});
    expect(api.createCollection).toHaveBeenCalledWith('Nightfall', 'night archives');
    expect(onCollectionsChanged).toHaveBeenCalledWith('c2');
  });
});