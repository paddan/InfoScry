import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/svelte';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import JobApproval, { DEFAULT_APPROVAL_STEP } from './JobApproval.svelte';
import { ApiError } from './api';
import type { ExternalApproval } from './api';

const api = vi.hoisted(() => ({
  approveJobExternal: vi.fn(),
  cancelJob: vi.fn(),
}));

vi.mock('./api', () => ({
  ApiError: class ApiError extends Error {
    code: string;
    constructor(code: string, message: string) {
      super(message);
      this.code = code;
    }
  },
  approveJobExternal: api.approveJobExternal,
  cancelJob: api.cancelJob,
}));

function approval(over: Partial<ExternalApproval> = {}): ExternalApproval {
  return { snapshotHash: 'hash-1', distinctPagesSent: 2, allowance: 3, calls: 2, ...over };
}

function pagesInput(): HTMLInputElement {
  return screen.getByLabelText('Pages that may leave this machine (in total)') as HTMLInputElement;
}

describe('job approval', () => {
  beforeEach(() => {
    vi.resetAllMocks();
  });

  afterEach(cleanup);

  it('explains a fresh import with nothing sent and nothing allowed yet', () => {
    render(JobApproval, { jobId: 'j1', approval: approval({ distinctPagesSent: 0, allowance: 0, calls: 0 }), onapproved: vi.fn() });

    const section = screen.getByRole('region', { name: 'Approve external pages' });
    expect(section.textContent).toContain(
      'This import sends pages to an external provider, and none are allowed yet. Approve how many pages may leave this machine; the import pauses again if it needs more.',
    );
  });

  it('states what was sent and what is allowed, with the singular for one page', () => {
    render(JobApproval, { jobId: 'j1', approval: approval({ distinctPagesSent: 1, allowance: 1, calls: 1 }), onapproved: vi.fn() });

    const section = screen.getByRole('region', { name: 'Approve external pages' });
    expect(section.textContent).toContain(
      '1 external page sent so far, 1 allowed. Approve how many pages in total may leave this machine; the import pauses again if it needs more.',
    );
  });

  it('states what was sent and what is allowed, with the plural for several pages', () => {
    render(JobApproval, { jobId: 'j1', approval: approval({ distinctPagesSent: 2, allowance: 3 }), onapproved: vi.fn() });

    const section = screen.getByRole('region', { name: 'Approve external pages' });
    expect(section.textContent).toContain(
      '2 external pages sent so far, 3 allowed. Approve how many pages in total may leave this machine; the import pauses again if it needs more.',
    );
  });

  it('labels the input as the total of pages that may leave this machine', () => {
    render(JobApproval, { jobId: 'j1', approval: approval(), onapproved: vi.fn() });
    expect(pagesInput().type).toBe('number');
  });

  it('defaults a fresh import to 50 pages, and otherwise to what was sent plus 50', () => {
    expect(DEFAULT_APPROVAL_STEP).toBe(50);

    render(JobApproval, { jobId: 'j1', approval: approval({ distinctPagesSent: 0, allowance: 0, calls: 0 }), onapproved: vi.fn() });
    expect(pagesInput().value).toBe('50');
    cleanup();

    render(JobApproval, { jobId: 'j1', approval: approval({ distinctPagesSent: 2, allowance: 3 }), onapproved: vi.fn() });
    expect(pagesInput().value).toBe('52');
    cleanup();

    render(JobApproval, { jobId: 'j1', approval: approval({ distinctPagesSent: 9, allowance: 3 }), onapproved: vi.fn() });
    expect(pagesInput().value).toBe('59');
  });

  it('approves the typed page count against the snapshot hash, then reports the answer', async () => {
    const answer = { jobId: 'j1', approvalId: 'a1', authorizedDistinctPages: 6, distinctPagesSent: 2, calls: 4 };
    api.approveJobExternal.mockResolvedValue(answer);
    const onapproved = vi.fn();

    render(JobApproval, { jobId: 'j1', approval: approval(), onapproved });
    await fireEvent.input(pagesInput(), { target: { value: '6' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Approve external pages' }));

    await waitFor(() => expect(onapproved).toHaveBeenCalledWith(answer));
    expect(api.approveJobExternal).toHaveBeenCalledWith('j1', 'hash-1', 6);
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it.each([
    ['', 'Enter a whole number of distinct pages, at least 3.'],
    ['2', 'Enter a whole number of distinct pages, at least 3.'],
    ['2.5', 'Enter a whole number of distinct pages, at least 3.'],
    ['-4', 'Enter a whole number of distinct pages, at least 3.'],
    ['abc', 'Enter a whole number of distinct pages, at least 3.'],
  ])('refuses %j without calling the server', async (typed, message) => {
    render(JobApproval, { jobId: 'j1', approval: approval(), onapproved: vi.fn() });
    await fireEvent.input(pagesInput(), { target: { value: typed } });
    await fireEvent.click(screen.getByRole('button', { name: 'Approve external pages' }));

    expect((await screen.findByRole('alert')).textContent).toBe(message);
    expect(api.approveJobExternal).not.toHaveBeenCalled();
  });

  it('keeps the typed page count when the server refuses the approval', async () => {
    api.approveJobExternal.mockRejectedValue(new ApiError('CONFLICT', 'the OCR selection changed since it was previewed'));
    const onapproved = vi.fn();

    render(JobApproval, { jobId: 'j1', approval: approval(), onapproved });
    await fireEvent.input(pagesInput(), { target: { value: '7' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Approve external pages' }));

    expect((await screen.findByRole('alert')).textContent).toContain('the OCR selection changed since it was previewed');
    expect(pagesInput().value).toBe('7');
    expect(onapproved).not.toHaveBeenCalled();
  });

  it('offers no cancel button unless the host handles a cancelled import', () => {
    render(JobApproval, { jobId: 'j1', approval: approval(), onapproved: vi.fn() });
    expect(screen.queryByRole('button', { name: 'Cancel import' })).toBeNull();
  });

  it('cancels the job and tells the host, without approving anything', async () => {
    api.cancelJob.mockResolvedValue({});
    const oncancelled = vi.fn();
    render(JobApproval, { jobId: 'j1', approval: approval(), onapproved: vi.fn(), oncancelled });

    await fireEvent.click(screen.getByRole('button', { name: 'Cancel import' }));

    await waitFor(() => expect(oncancelled).toHaveBeenCalledTimes(1));
    expect(api.cancelJob).toHaveBeenCalledWith('j1');
    expect(api.approveJobExternal).not.toHaveBeenCalled();
  });

  it('shows a refused cancel and keeps the form', async () => {
    api.cancelJob.mockRejectedValue(new Error('The server refused.'));
    const oncancelled = vi.fn();
    render(JobApproval, { jobId: 'j1', approval: approval(), onapproved: vi.fn(), oncancelled });

    await fireEvent.click(screen.getByRole('button', { name: 'Cancel import' }));

    expect((await screen.findByRole('alert')).textContent).toContain('The server refused.');
    expect(oncancelled).not.toHaveBeenCalled();
  });
});
