import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/svelte';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import JobApproval from './JobApproval.svelte';
import { ApiError } from './api';
import type { ExternalApproval } from './api';

const api = vi.hoisted(() => ({
  approveJobExternal: vi.fn(),
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
}));

function approval(over: Partial<ExternalApproval> = {}): ExternalApproval {
  return { snapshotHash: 'hash-1', distinctPagesSent: 2, allowance: 3, calls: 2, ...over };
}

function pagesInput(): HTMLInputElement {
  return screen.getByLabelText('Distinct pages to approve') as HTMLInputElement;
}

describe('job approval', () => {
  beforeEach(() => {
    vi.resetAllMocks();
  });

  afterEach(cleanup);

  it('states what was sent and what approving allows, in a named section', () => {
    render(JobApproval, { jobId: 'j1', approval: approval(), onapproved: vi.fn() });

    const section = screen.getByRole('region', { name: 'Approve external pages' });
    expect(section.textContent).toContain(
      '2 of the allowed 3 external pages were sent. More pages are needed to finish this import; approving lets up to 4 distinct pages leave this machine.',
    );
  });

  it('defaults to one page more than the allowance or one more than sent, whichever is larger', () => {
    render(JobApproval, { jobId: 'j1', approval: approval({ distinctPagesSent: 2, allowance: 3 }), onapproved: vi.fn() });
    expect(pagesInput().value).toBe('4');
    cleanup();

    render(JobApproval, { jobId: 'j1', approval: approval({ distinctPagesSent: 9, allowance: 3 }), onapproved: vi.fn() });
    expect(pagesInput().value).toBe('10');
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
});
