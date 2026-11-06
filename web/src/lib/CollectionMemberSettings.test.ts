import { cleanup, fireEvent, render, screen, within } from '@testing-library/svelte';
import { afterEach, describe, expect, it, vi } from 'vitest';
import CollectionMemberSettings from './CollectionMemberSettings.svelte';
import type { Collection } from './api';

const COLLECTION = {
  id: 'c1', name: 'Archive', language: 'eng', defaultMethod: 'none', createdAt: 't', updatedAt: 't',
  lifecycle: 'ACTIVE', documentCount: 0,
} as Collection;

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status });
}

function member(userId: string, name: string, role: string) {
  return { userId, username: name, displayName: name, active: true, role, addedAt: 't' };
}

describe('collection members', () => {
  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('lets an owner add a colleague with a role', async () => {
    let members = [member('u1', 'anna', 'OWNER')];
    const calls: { url: string; init?: RequestInit }[] = [];
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      calls.push({ url, init });
      if (url === '/api/session') return json({ csrfToken: 'csrf' });
      if (url === '/api/collections/c1/members') return json({ members, callerRole: 'OWNER' });
      if (url === '/api/users/directory') return json({ users: [{ id: 'u1', username: 'anna', displayName: 'anna' }, { id: 'u2', username: 'erik', displayName: 'erik' }] });
      if (url === '/api/collections/c1/members/u2') {
        members = [...members, member('u2', 'erik', 'EDITOR')];
        return json({ member: members[1] });
      }
      return json({});
    }));
    render(CollectionMemberSettings, { props: { collection: COLLECTION } });

    await screen.findByRole('option', { name: 'erik (erik)' });
    const person = screen.getByLabelText('Add a person');
    expect(within(person).queryByText('anna (anna)')).toBeNull();
    await fireEvent.change(person, { target: { value: 'u2' } });
    await fireEvent.change(screen.getByLabelText('Role'), { target: { value: 'EDITOR' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Add member' }));

    expect(await screen.findByRole('button', { name: 'Remove erik' })).toBeTruthy();
    const put = calls.find((call) => call.url === '/api/collections/c1/members/u2');
    expect(put?.init?.method).toBe('PUT');
    expect(JSON.parse(String(put?.init?.body))).toEqual({ role: 'EDITOR' });
  });

  it('shows a reader the members without controls', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) =>
      String(input) === '/api/collections/c1/members'
        ? json({ members: [member('u1', 'anna', 'OWNER'), member('u3', 'lisa', 'READER')], callerRole: 'READER' })
        : json({})));
    render(CollectionMemberSettings, { props: { collection: COLLECTION } });

    expect(await screen.findByText('lisa', { selector: 'strong' })).toBeTruthy();
    expect(screen.queryByRole('button', { name: /Remove/ })).toBeNull();
    expect(screen.queryByLabelText('Add a person')).toBeNull();
  });
});
