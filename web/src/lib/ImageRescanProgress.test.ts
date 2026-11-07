import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/svelte';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import * as api from './api';
import ImageRescanProgress from './ImageRescanProgress.svelte';

vi.mock('./api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('./api')>()),
  getImageRescanRun: vi.fn(),
  cancelImageRescan: vi.fn(),
}));

const running: api.ImageRescanRun = {
  jobId: 'img-1', documents: 46, examined: 46, started: 41, done: 10, failed: 1, cancelled: 0, scanning: 2, waiting: 28,
  currentDocument: 'letter.pdf', active: true, cancelRequested: false,
};

describe('ImageRescanProgress', () => {
  beforeEach(() => vi.clearAllMocks());
  afterEach(cleanup);

  it('shows the run like an import: a bar, counts, the document being scanned and a stop button', () => {
    render(ImageRescanProgress, { collectionId: 'c1', run: running, pollMillis: 60_000 });
    expect(screen.getByText('Finding images: 11 of 41 documents done · 2 scanning · 28 waiting')).toBeTruthy();
    expect(screen.getByRole('progressbar', { name: 'Image progress' }).getAttribute('value')).toBe('11');
    expect(screen.getByRole('progressbar', { name: 'Image progress' }).getAttribute('max')).toBe('41');
    expect(screen.getByText('Scanning letter.pdf')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Stop finding images' })).toBeTruthy();
  });

  it('counts every document while rescans are still being started', () => {
    render(ImageRescanProgress, { collectionId: 'c1', run: { ...running, examined: 12, started: 10 }, pollMillis: 60_000 });
    expect(screen.getByText('Finding images: 11 of 46 documents done · 2 scanning · 28 waiting · starting 12 of 46')).toBeTruthy();
  });

  it('follows the run until it ends and then says what it did', async () => {
    vi.mocked(api.getImageRescanRun).mockResolvedValue({ ...running, done: 40, scanning: 0, waiting: 0, active: false, currentDocument: null });
    render(ImageRescanProgress, { collectionId: 'c1', run: running, pollMillis: 10 });
    expect(await screen.findByText('Finding images finished: 40 documents done, 1 failed.')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Stop finding images' })).toBeNull();
    expect(screen.queryByRole('progressbar')).toBeNull();
  });

  it('stops the whole run', async () => {
    vi.mocked(api.cancelImageRescan).mockResolvedValue({ ...running, cancelRequested: true });
    render(ImageRescanProgress, { collectionId: 'c1', run: running, pollMillis: 60_000 });
    await fireEvent.click(screen.getByRole('button', { name: 'Stop finding images' }));
    await waitFor(() => expect(api.cancelImageRescan).toHaveBeenCalledWith('c1'));
    expect((screen.getByRole('button', { name: 'Stopping…' }) as HTMLButtonElement).disabled).toBe(true);
  });

  it('says why a run that could start nothing failed', () => {
    render(ImageRescanProgress, {
      collectionId: 'c1',
      run: { ...running, started: 0, done: 0, failed: 0, scanning: 0, waiting: 0, active: false, errorCode: 'RESCAN_MANAGED_COPY_MISSING' },
    });
    expect(screen.getByRole('alert').textContent).toMatch(/^Finding images failed: the server could not find the documents' stored copies/);
  });
});
