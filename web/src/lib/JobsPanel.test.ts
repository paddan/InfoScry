import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/svelte';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import JobsPanel from './JobsPanel.svelte';
import * as api from './api';

vi.mock('./api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('./api')>()),
  listActiveJobs: vi.fn(), cancelJob: vi.fn(),
}));

const collections: api.Collection[] = [
  { id: 'c1', name: 'Archive', language: 'eng', defaultMethod: null, createdAt: '', updatedAt: '', lifecycle: 'ACTIVE' } as api.Collection,
];

function job(over: Partial<api.JobApiView>): api.JobApiView {
  return { id: 'job-1', type: 'IMPORT', state: 'RUNNING', createdAt: '', updatedAt: '', completed: 0, total: 0, cancelRequested: false, ...over };
}

describe('JobsPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(api.listActiveJobs).mockResolvedValue([]);
  });
  afterEach(cleanup);

  it('lists running and queued jobs with their collection, stage and progress', async () => {
    vi.mocked(api.listActiveJobs).mockResolvedValue([
      job({ id: 'job-1', collectionId: 'c1', stage: 'extract', currentItem: 'report.pdf', completed: 1, total: 3,
        currentProgress: { unitKind: 'PAGE', processedUnits: 2, totalUnits: 10, failedUnits: 0 } }),
      job({ id: 'job-2', type: 'REINDEX', state: 'QUEUED' }),
    ]);
    render(JobsPanel, { collections, visible: true });
    const rows = await screen.findAllByRole('listitem');
    expect(rows).toHaveLength(2);
    expect(within(rows[0]).getByText('Import')).toBeTruthy();
    expect(within(rows[0]).getByText('Archive')).toBeTruthy();
    expect(within(rows[0]).getByText('Extracting · report.pdf')).toBeTruthy();
    expect(within(rows[0]).getByText('1 of 3')).toBeTruthy();
    expect(within(rows[0]).getByRole('progressbar', { name: 'Page progress' }).getAttribute('value')).toBe('2');
    expect(within(rows[1]).getByText('Reindex')).toBeTruthy();
    expect(within(rows[1]).getByText('Queued')).toBeTruthy();
  });

  it('says when nothing is running', async () => {
    render(JobsPanel, { collections, visible: true });
    expect(await screen.findByText('No jobs are running.')).toBeTruthy();
  });

  it('stops a job and shows it stopping until it ends', async () => {
    let cancelled = false;
    vi.mocked(api.listActiveJobs).mockImplementation(async () => [job({ cancelRequested: cancelled })]);
    vi.mocked(api.cancelJob).mockImplementation(async () => {
      cancelled = true;
      return job({ cancelRequested: true });
    });
    render(JobsPanel, { collections, visible: true });
    await fireEvent.click(await screen.findByRole('button', { name: 'Stop' }));
    expect(api.cancelJob).toHaveBeenCalledWith('job-1');
    expect(((await screen.findByRole('button', { name: 'Stopping…' })) as HTMLButtonElement).disabled).toBe(true);
  });

  it('reads the list again while visible and stops reading when hidden', async () => {
    vi.useFakeTimers();
    try {
      const view = render(JobsPanel, { collections, visible: true });
      await act(async () => {});
      expect(api.listActiveJobs).toHaveBeenCalledTimes(1);
      await act(async () => { await vi.advanceTimersByTimeAsync(2000); });
      expect(api.listActiveJobs).toHaveBeenCalledTimes(2);
      await view.rerender({ collections, visible: false });
      await act(async () => { await vi.advanceTimersByTimeAsync(10_000); });
      expect(api.listActiveJobs).toHaveBeenCalledTimes(2);
      await view.rerender({ collections, visible: true });
      await act(async () => {});
      expect(api.listActiveJobs).toHaveBeenCalledTimes(3);
    } finally { vi.useRealTimers(); }
  });

  it('shows a read failure', async () => {
    vi.mocked(api.listActiveJobs).mockRejectedValue(new Error('Server unavailable.'));
    render(JobsPanel, { collections, visible: true });
    await waitFor(() => expect(screen.getByRole('alert').textContent).toBe('Server unavailable.'));
  });
});
