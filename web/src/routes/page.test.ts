import { cleanup, fireEvent, render, screen } from '@testing-library/svelte';
import { afterEach, describe, expect, it, vi } from 'vitest';
import Page from './+page.svelte';

const USER = {
  id: 'u1',
  username: 'anna',
  displayName: 'Anna Berg',
  role: 'USER',
  active: true,
  mustChangePassword: false,
  createdAt: '2026-11-06T00:00:00.000Z',
  updatedAt: '2026-11-06T00:00:00.000Z',
};

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

/** A fetch that answers the session, sign-in and the workspace's first reads. */
function stub(routes: Record<string, (init?: RequestInit) => Response>) {
  const calls: { url: string; init?: RequestInit }[] = [];
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    calls.push({ url, init });
    const route = routes[url];
    if (route) return route(init);
    if (url === '/api/collections') return json({ collections: [] });
    return json({});
  }));
  return calls;
}

describe('sign-in gate', () => {
  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('shows the sign-in form without a session and the workspace after signing in', async () => {
    const calls = stub({
      '/api/session': () => json({ product: 'InfoScry' }),
      '/api/auth/sign-in': () => json({ user: USER, csrfToken: 'csrf-1' }),
    });
    render(Page);

    await fireEvent.input(await screen.findByLabelText('Username'), { target: { value: ' anna ' } });
    await fireEvent.input(screen.getByLabelText('Password'), { target: { value: 'a long password' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Sign in' }));

    expect(await screen.findByText('Anna Berg')).toBeTruthy();
    const signIn = calls.find((call) => call.url === '/api/auth/sign-in');
    expect(JSON.parse(String(signIn?.init?.body))).toEqual({ username: 'anna', password: 'a long password' });
  });

  it('says a wrong password plainly', async () => {
    stub({
      '/api/session': () => json({ product: 'InfoScry' }),
      '/api/auth/sign-in': () => json({ error: { code: 'INVALID_CREDENTIALS', message: 'the username or password is wrong' } }, 401),
    });
    render(Page);

    await fireEvent.input(await screen.findByLabelText('Username'), { target: { value: 'anna' } });
    await fireEvent.input(screen.getByLabelText('Password'), { target: { value: 'wrong one here' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Sign in' }));

    expect((await screen.findByRole('alert')).textContent).toContain('The username or password is wrong.');
  });

  it('asks for a new password before anything else after a one-time password', async () => {
    stub({ '/api/session': () => json({ product: 'InfoScry', user: { ...USER, mustChangePassword: true }, csrfToken: 'c' }) });
    render(Page);

    expect(await screen.findByRole('heading', { name: 'Choose your password' })).toBeTruthy();
    expect(screen.queryByRole('tab', { name: 'Search' })).toBeNull();
  });

  it('opens the workspace directly for a signed-in browser', async () => {
    stub({ '/api/session': () => json({ product: 'InfoScry', user: USER, csrfToken: 'c' }) });
    render(Page);

    expect(await screen.findByText('Anna Berg')).toBeTruthy();
    expect(screen.queryByLabelText('Password')).toBeNull();
  });
});
