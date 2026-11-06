import { cleanup, fireEvent, render, screen } from '@testing-library/svelte';
import { afterEach, describe, expect, it, vi } from 'vitest';
import UsersPanel from './UsersPanel.svelte';

function user(id: string, username: string, role = 'USER') {
  return {
    id, username, displayName: username, role, active: true, mustChangePassword: false,
    createdAt: '2026-11-06T00:00:00.000Z', updatedAt: '2026-11-06T00:00:00.000Z',
  };
}

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status });
}

describe('users panel', () => {
  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('hands out a new user\'s one-time password and says when the user already existed', async () => {
    let users = [user('a', 'admin', 'ADMIN')];
    let existing = false;
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url === '/api/session') return json({ csrfToken: 'csrf' });
      if (url === '/api/users' && init?.method === 'POST') {
        const created = user('e', 'erik');
        if (existing) return json({ user: created, created: false });
        existing = true;
        users = [...users, created];
        return json({ user: created, created: true, oneTimePassword: 'OneTimePass2345' }, 201);
      }
      if (url === '/api/users') return json({ users });
      return json({});
    }));
    render(UsersPanel, { props: { currentUserId: 'a' } });

    await fireEvent.input(await screen.findByLabelText('Username'), { target: { value: 'erik' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Add user' }));
    expect((await screen.findByLabelText('One-time password')).textContent).toBe('OneTimePass2345');
    expect(await screen.findByText('erik', { selector: 'strong' })).toBeTruthy();

    await fireEvent.input(screen.getByLabelText('Username'), { target: { value: 'erik' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Add user' }));
    expect(await screen.findByText('erik already exists; nothing was changed.')).toBeTruthy();
    expect(screen.queryByLabelText('One-time password')).toBeNull();
  });

  it('does not offer to deactivate the administrator looking at the list', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) =>
      String(input) === '/api/users' ? json({ users: [user('a', 'admin', 'ADMIN'), user('e', 'erik')] }) : json({})));
    render(UsersPanel, { props: { currentUserId: 'a' } });

    await screen.findByText('erik', { selector: 'strong' });
    expect(screen.getAllByRole('button', { name: 'Deactivate' })).toHaveLength(1);
  });
});
