import { cleanup, fireEvent, render, screen } from '@testing-library/svelte';
import { afterEach, describe, expect, it, vi } from 'vitest';
import ImportRootsPanel from './ImportRootsPanel.svelte';
import type { ImportRoot } from './api';

function json(body: unknown, status = 200): Response {
  return new Response(body === null ? null : JSON.stringify(body), { status });
}

function root(id: string, path: string, extra: Partial<ImportRoot> = {}): ImportRoot {
  return { id, name: path.split('/').pop()!, path, source: 'ADMIN', available: true, addedAt: '2026-11-06T10:00:00Z', addedBy: 'Anna', ...extra };
}

describe('server folders panel', () => {
  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('adds a folder by its path, says when it already was one, and removes it', async () => {
    let roots: ImportRoot[] = [];
    const requests: { url: string; method: string; body?: string }[] = [];
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      const method = init?.method ?? 'GET';
      requests.push({ url, method, body: init?.body as string | undefined });
      if (url === '/api/session') return json({ csrfToken: 'csrf' });
      if (url === '/api/import-roots' && method === 'POST') {
        const path = JSON.parse(init!.body as string).path as string;
        const existing = roots.find((candidate) => candidate.path === path);
        if (existing) return json({ root: existing, created: false });
        const added = root('r1', path);
        roots = [...roots, added];
        return json({ root: added, created: true }, 201);
      }
      if (url === '/api/import-roots/r1' && method === 'DELETE') {
        roots = [];
        return json(null, 204);
      }
      if (url === '/api/import-roots') return json({ roots });
      return json({});
    }));
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    render(ImportRootsPanel);

    expect(await screen.findByText(/No server folders yet/)).toBeTruthy();
    const field = screen.getByLabelText('Folder path on the server');
    await fireEvent.input(field, { target: { value: '  /Users/anna/Archive ' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Add folder' }));
    expect(await screen.findByText('/Users/anna/Archive was added.')).toBeTruthy();
    expect(requests.find((request) => request.method === 'POST')?.body).toBe('{"path":"/Users/anna/Archive"}');
    expect(await screen.findByText('Archive')).toBeTruthy();
    expect(screen.getByText('by Anna')).toBeTruthy();

    await fireEvent.input(field, { target: { value: '/Users/anna/Archive' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Add folder' }));
    expect(await screen.findByText('/Users/anna/Archive is already a server folder.')).toBeTruthy();

    await fireEvent.click(screen.getByRole('button', { name: 'Remove /Users/anna/Archive' }));
    expect(await screen.findByText('/Users/anna/Archive was removed.')).toBeTruthy();
    expect(await screen.findByText(/No server folders yet/)).toBeTruthy();
  });

  it('shows why a folder was refused, which folders are missing, and leaves environment folders alone', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url === '/api/session') return json({ csrfToken: 'csrf' });
      if (url === '/api/import-roots' && init?.method === 'POST') {
        return json({ error: { code: 'INVALID_REQUEST', message: 'there is no folder at /nowhere on the server' } }, 400);
      }
      if (url === '/api/import-roots') {
        return json({ roots: [
          root('environment-0', '/srv/shared', { source: 'ENVIRONMENT', addedAt: null, addedBy: null }),
          root('r2', '/Volumes/Gone', { available: false }),
        ] });
      }
      return json({});
    }));
    render(ImportRootsPanel);

    expect(await screen.findByText('Set in INFOSCRY_IMPORT_ROOTS')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Remove /srv/shared' })).toBeNull();
    expect(screen.getByText(/Not found on the server/)).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Remove /Volumes/Gone' })).toBeTruthy();

    await fireEvent.input(screen.getByLabelText('Folder path on the server'), { target: { value: '/nowhere' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Add folder' }));
    expect((await screen.findByRole('alert')).textContent).toContain('there is no folder at /nowhere on the server');
  });
});
