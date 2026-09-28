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

    render(ImportPanel, { collectionId: 'c1', collectionName: 'Archive' });
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

    render(ImportPanel, { collectionId: 'c1', collectionName: 'Archive' });
    await fireEvent.click(screen.getByRole('button', { name: 'Choose folder…' }));

    expect(await screen.findByText('/home/example/docs')).toBeTruthy();
    expect(api.pickPaths).toHaveBeenCalledWith(true);
  });

  it('sends the recursive checkbox state in the import request body', async () => {
    api.pickPaths.mockResolvedValue(['/home/example/docs']);
    api.enqueueImport.mockResolvedValue({ accepted: true, job: job('j1', 'COMPLETE') });
    api.getImportItems.mockResolvedValue([]);

    render(ImportPanel, { collectionId: 'c1', collectionName: 'Archive' });
    await fireEvent.click(screen.getByRole('button', { name: 'Choose folder…' }));
    await screen.findByText('/home/example/docs');
    await fireEvent.click(screen.getByLabelText('Include subfolders'));
    await fireEvent.click(screen.getByRole('button', { name: 'Import' }));

    expect(await screen.findByText('Import complete. 0 files.')).toBeTruthy();
    expect(api.enqueueImport).toHaveBeenCalledWith('c1', ['/home/example/docs'], true);
  });

  it('shows the manual path fallback on PICK_UNAVAILABLE and imports the entered path', async () => {
    api.pickPaths.mockRejectedValue(new ApiError('PICK_UNAVAILABLE', 'the pick dialog could not be opened on this machine'));
    api.enqueueImport.mockResolvedValue({ accepted: true, job: job('j1', 'COMPLETE') });
    api.getImportItems.mockResolvedValue([]);

    render(ImportPanel, { collectionId: 'c1', collectionName: 'Archive' });
    await fireEvent.click(screen.getByRole('button', { name: 'Choose files…' }));

    expect((await screen.findByRole('alert')).textContent)
      .toContain('the pick dialog could not be opened on this machine');
    await fireEvent.input(screen.getByLabelText('Path'), { target: { value: '/home/example/manual.pdf' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Add' }));
    expect(screen.getByText('/home/example/manual.pdf')).toBeTruthy();

    await fireEvent.click(screen.getByRole('button', { name: 'Import' }));
    expect(await screen.findByText('Import complete. 0 files.')).toBeTruthy();
    expect(api.enqueueImport).toHaveBeenCalledWith('c1', ['/home/example/manual.pdf'], false);
  });

  it('does not treat a cancelled pick as an error', async () => {
    api.pickPaths.mockRejectedValue(new ApiError('PICK_CANCELLED', 'the pick dialog was closed without choosing anything'));

    render(ImportPanel, { collectionId: 'c1', collectionName: 'Archive' });
    await fireEvent.click(screen.getByRole('button', { name: 'Choose files…' }));

    await act(async () => {});
    expect(screen.queryByRole('alert')).toBeNull();
    expect(screen.getByText('No paths selected yet.')).toBeTruthy();
  });

  it('polls the job until a terminal state and renders per-file results', async () => {
    api.pickPaths.mockResolvedValue(['/home/example/a.pdf']);
    api.enqueueImport.mockResolvedValue({ accepted: true, job: { ...job('j1', 'RUNNING', 0, 2), stage: 'copy' } });
    api.getJob
      .mockResolvedValueOnce(job('j1', 'RUNNING', 1, 2))
      .mockResolvedValueOnce(job('j1', 'COMPLETE', 2, 2));
    api.getImportItems.mockResolvedValue([
      item('i1', 'IMPORTED', { sourceName: 'a.pdf', documentId: 'd1' }),
      item('i2', 'FAILED', {
        sourceName: 'b.pdf',
        documentId: 'd2',
        errorCode: 'NEEDS_TOOL',
        errorMessage: 'this e-book format needs the Calibre converter, which is not installed',
      }),
    ]);

    render(ImportPanel, { collectionId: 'c1', collectionName: 'Archive' });
    await fireEvent.click(screen.getByRole('button', { name: 'Choose files…' }));
    await screen.findByText('/home/example/a.pdf');
    await fireEvent.click(screen.getByRole('button', { name: 'Import' }));

    // The enqueued job is still running: the status shows its stage in a reader's words and its start
    // counters, and says outright that it has not named a file yet.
    expect(await screen.findByText('Importing… — Copying · 0 of 2 files', undefined, { timeout: 3000 })).toBeTruthy();
    // A determinate progress bar tracks the job's completed/total counters while it runs.
    const bar = screen.getByRole('progressbar', { name: 'Import progress' });
    expect(bar.getAttribute('max')).toBe('2');
    expect(bar.getAttribute('value')).toBe('0');
    // Polling then advances the job to a terminal state and the items render.
    expect(await screen.findByText('Import complete. 2 files.', undefined, { timeout: 5000 })).toBeTruthy();
    expect(api.getJob).toHaveBeenCalledTimes(2);
    expect(api.getImportItems).toHaveBeenCalledWith('j1');
    // The chosen path stays in the selected-paths list; the results table names the file the server
    // reports, because the wire carries the name and never the path it was selected from.
    expect(screen.getAllByText('/home/example/a.pdf')).toHaveLength(1);
    expect(screen.getByText('a.pdf')).toBeTruthy();
    expect(screen.getByText('Imported')).toBeTruthy();
    expect(screen.getByText('b.pdf')).toBeTruthy();
    // The file's bytes are in the archive and its document waits for a tool: not a plain failure.
    expect(screen.getByText('Needs a tool')).toBeTruthy();
    expect(
      screen.getByText('this e-book format needs the Calibre converter, which is not installed (NEEDS_TOOL)'),
    ).toBeTruthy();
  });

  it('names the destination collection and refuses to import without one', async () => {
    const { unmount } = render(ImportPanel, { collectionId: 'c1', collectionName: 'Nightfall' });
    expect(screen.getByText('Nightfall')).toBeTruthy();
    unmount();

    // An empty collection can never enqueue work, and the panel says which one to pick.
    render(ImportPanel, { collectionId: '', collectionName: '' });
    const button = screen.getByRole('button', { name: 'Import' }) as HTMLButtonElement;
    expect(button.disabled).toBe(true);
    expect(screen.getByText('Select a collection to import into.')).toBeTruthy();

    await fireEvent.click(button);
    await act(async () => {});
    expect(api.enqueueImport).not.toHaveBeenCalled();
  });

  it('reports bytes already in the collection as Duplicate', async () => {
    api.pickPaths.mockResolvedValue(['/home/example/a.pdf']);
    api.enqueueImport.mockResolvedValue({ accepted: true, job: job('j1', 'COMPLETE', 2, 2) });
    api.getImportItems.mockResolvedValue([
      item('i1', 'IMPORTED', { sourceName: 'a.pdf', documentId: 'd1' }),
      item('i2', 'DUPLICATE', { sourceName: 'a-copy.pdf', documentId: 'd1' }),
    ]);

    render(ImportPanel, { collectionId: 'c1', collectionName: 'Archive' });
    await fireEvent.click(screen.getByRole('button', { name: 'Choose files…' }));
    await screen.findByText('/home/example/a.pdf');
    await fireEvent.click(screen.getByRole('button', { name: 'Import' }));

    expect(await screen.findByText('Import complete. 2 files.', undefined, { timeout: 5000 })).toBeTruthy();
    expect(screen.getByText('a-copy.pdf')).toBeTruthy();
    expect(screen.getByText('Duplicate')).toBeTruthy();
  });

  it('names the file being imported, and the stage as words rather than the server token', async () => {
    api.pickPaths.mockResolvedValue(['/home/example/a.pdf']);
    api.enqueueImport.mockResolvedValue({
      accepted: true,
      job: { ...job('j1', 'RUNNING', 3, 12), stage: 'extract', currentItem: 'report.pdf' },
    });
    api.getJob.mockResolvedValue(job('j1', 'COMPLETE', 12, 12));
    api.getImportItems.mockResolvedValue([]);

    render(ImportPanel, { collectionId: 'c1', collectionName: 'Archive' });
    await fireEvent.click(screen.getByRole('button', { name: 'Choose files…' }));
    await screen.findByText('/home/example/a.pdf');
    await fireEvent.click(screen.getByRole('button', { name: 'Import' }));

    // The status line is a live region, and it names the file the server reported and the file's own
    // counters; the stage token `extract` reads as English rather than as itself.
    const status = await screen.findByRole('status', undefined, { timeout: 3000 });
    expect(status.textContent).toBe('Importing report.pdf — Extracting · 3 of 12 files');
    expect(status.textContent).not.toContain('extract ·');

    // The terminal sentence keeps its own wording and names no file: nothing is being read any more.
    expect(await screen.findByText('Import complete. 0 files.', undefined, { timeout: 5000 })).toBeTruthy();
  });
});