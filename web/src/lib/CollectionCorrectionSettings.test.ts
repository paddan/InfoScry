import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/svelte';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import * as api from './api';
import type { Collection, CorrectionList } from './api';
import CollectionCorrectionSettings from './CollectionCorrectionSettings.svelte';

vi.mock('./api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('./api')>()),
  getCorrections: vi.fn(),
  deleteCorrection: vi.fn(),
  previewCorrectionRemoval: vi.fn(),
  applyCorrections: vi.fn(),
  previewCorrection: vi.fn(),
  putCorrection: vi.fn(),
}));

const collection: Collection = {
  id: 'c1', name: 'Archive', language: 'eng', defaultMethod: null,
  createdAt: '', updatedAt: '', lifecycle: 'ACTIVE', documentCount: 0,
} as Collection;

const job = (state: string, completed = 0, total = 0) => ({
  id: 'j1', type: 'CORRECTIONS', state, createdAt: '', updatedAt: '', completed, total,
}) as unknown as NonNullable<CorrectionList['job']>;

const list = (over: Partial<CorrectionList> = {}): CorrectionList => ({
  rules: [{ id: 'r1', find: 'spinners', replace: 'Spinnars', exactCase: false, matches: 14 }],
  job: job('COMPLETE', 6, 6),
  ...over,
});

describe('CollectionCorrectionSettings', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(api.getCorrections).mockResolvedValue(list());
    vi.mocked(api.deleteCorrection).mockResolvedValue(undefined);
    vi.mocked(api.previewCorrectionRemoval).mockResolvedValue({ matches: 14, documents: 6, examples: [] });
    vi.mocked(api.applyCorrections).mockResolvedValue(job('QUEUED'));
  });
  afterEach(cleanup);

  it('lists the rules with their matches and says the documents follow the list', async () => {
    render(CollectionCorrectionSettings, { collection });

    const table = await screen.findByRole('table');
    const row = within(table).getAllByRole('row')[1];
    expect(row.textContent).toContain('spinners');
    expect(row.textContent).toContain('Spinnars');
    expect(row.textContent).toContain('14');
    expect(screen.getByText('All documents follow the list')).toBeTruthy();
  });

  it('shows how far a running job has come', async () => {
    vi.mocked(api.getCorrections).mockResolvedValue(list({ job: job('RUNNING', 312, 1200) }));
    render(CollectionCorrectionSettings, { collection });

    expect(await screen.findByText('Applying: 312 of 1,200 documents')).toBeTruthy();
  });

  it('asks before removing, naming the documents that change back', async () => {
    render(CollectionCorrectionSettings, { collection });
    await screen.findByRole('table');

    await fireEvent.click(screen.getByRole('button', { name: 'Remove spinners' }));

    const dialog = await screen.findByRole('dialog');
    expect(dialog.textContent).toContain('The text returns to the reading in 6 documents.');
    await fireEvent.click(within(dialog).getByRole('button', { name: 'Remove' }));
    await waitFor(() => expect(api.deleteCorrection).toHaveBeenCalledWith('c1', 'r1'));
  });

  it('offers Retry when the last job failed', async () => {
    vi.mocked(api.getCorrections).mockResolvedValue(list({ job: { ...job('FAILED'), errorCode: 'EMBEDDING_UNAVAILABLE' } }));
    render(CollectionCorrectionSettings, { collection });

    await fireEvent.click(await screen.findByRole('button', { name: 'Retry' }));

    expect(api.applyCorrections).toHaveBeenCalledWith('c1');
  });

  it('keeps reading the list while a job runs, until the documents follow it', async () => {
    vi.useFakeTimers();
    try {
      vi.mocked(api.getCorrections)
        .mockResolvedValueOnce(list({ job: job('RUNNING', 1, 2) }))
        .mockResolvedValue(list({ job: job('COMPLETE', 2, 2) }));
      render(CollectionCorrectionSettings, { collection });
      await vi.waitFor(() => expect(screen.getByText('Applying: 1 of 2 documents')).toBeTruthy());

      await vi.advanceTimersByTimeAsync(2500);

      await vi.waitFor(() => expect(screen.getByText('All documents follow the list')).toBeTruthy());
    } finally {
      vi.useRealTimers();
    }
  });

  it('stops reading the list once the section is gone', async () => {
    vi.useFakeTimers();
    try {
      let resolve: (value: CorrectionList) => void = () => {};
      vi.mocked(api.getCorrections).mockImplementationOnce(() => new Promise((r) => { resolve = r; }));
      vi.mocked(api.getCorrections).mockResolvedValue(list({ job: job('RUNNING', 1, 2) }));
      const view = render(CollectionCorrectionSettings, { collection });
      view.unmount();
      resolve(list({ job: job('RUNNING', 1, 2) }));
      await vi.advanceTimersByTimeAsync(10_000);

      expect(api.getCorrections).toHaveBeenCalledTimes(1);
    } finally {
      vi.useRealTimers();
    }
  });

  it('puts focus on Add correction after a rule is removed', async () => {
    render(CollectionCorrectionSettings, { collection });
    await screen.findByRole('table');
    vi.mocked(api.getCorrections).mockResolvedValue(list({ rules: [] }));

    await fireEvent.click(screen.getByRole('button', { name: 'Remove spinners' }));
    const dialog = await screen.findByRole('dialog');
    await within(dialog).findByText('The text returns to the reading in 6 documents.');
    await fireEvent.click(within(dialog).getByRole('button', { name: 'Remove' }));

    await waitFor(() => expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Add correction' })));
  });

  it('keeps the rules on screen when a refresh fails', async () => {
    vi.useFakeTimers();
    try {
      vi.mocked(api.getCorrections)
        .mockResolvedValueOnce(list({ job: job('RUNNING', 1, 2) }))
        .mockRejectedValueOnce(new Error('network down'))
        .mockResolvedValue(list({ job: job('COMPLETE', 2, 2) }));
      render(CollectionCorrectionSettings, { collection });
      await vi.waitFor(() => expect(screen.getByRole('table')).toBeTruthy());

      await vi.advanceTimersByTimeAsync(2500);

      expect(screen.getByRole('table')).toBeTruthy();
      expect(screen.getByRole('button', { name: 'Add correction' })).toBeTruthy();
      await vi.advanceTimersByTimeAsync(2500);
      await vi.waitFor(() => expect(screen.getByText('All documents follow the list')).toBeTruthy());
    } finally {
      vi.useRealTimers();
    }
  });

  it('does not claim a stopped job finished, and offers Retry', async () => {
    vi.mocked(api.getCorrections).mockResolvedValue(list({ job: job('CANCELLED', 1, 6) }));
    render(CollectionCorrectionSettings, { collection });

    expect(await screen.findByText('Applying the corrections was stopped before every document was done.')).toBeTruthy();
    expect(screen.queryByText('All documents follow the list')).toBeNull();
    expect(screen.getByRole('button', { name: 'Retry' })).toBeTruthy();
  });
});
