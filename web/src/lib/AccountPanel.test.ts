import { cleanup, fireEvent, render, screen } from '@testing-library/svelte';
import { afterEach, describe, expect, it, vi } from 'vitest';
import AccountPanel from './AccountPanel.svelte';
import type { User } from './api';

const USER: User = {
  id: 'u1', username: 'anna', displayName: 'Anna', role: 'USER', active: true, mustChangePassword: false,
  createdAt: '2026-11-06T00:00:00.000Z', updatedAt: '2026-11-06T00:00:00.000Z',
};

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status });
}

describe('account panel', () => {
  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('shows a new token once and lists it without its value', async () => {
    let tokens: unknown[] = [];
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url === '/api/session') return json({ csrfToken: 'csrf' });
      if (url === '/api/me/tokens' && init?.method === 'POST') {
        const token = { id: 't1', name: 'laptop', createdAt: '2026-11-06T10:00:00.000Z', expiresAt: '2027-02-04T10:00:00.000Z' };
        tokens = [token];
        return json({ token, value: 'isc_secretvalue' }, 201);
      }
      if (url === '/api/me/tokens') return json({ tokens });
      return json({});
    }));
    render(AccountPanel, { props: { user: USER } });

    await fireEvent.input(await screen.findByLabelText('Name'), { target: { value: 'laptop' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Create token' }));

    expect((await screen.findByLabelText('New token')).textContent).toBe('isc_secretvalue');
    expect(await screen.findByRole('button', { name: 'Revoke laptop' })).toBeTruthy();
    expect(screen.getAllByText(/isc_secretvalue/)).toHaveLength(1);
  });
});
