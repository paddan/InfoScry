import { cleanup, render, screen, waitFor } from '@testing-library/svelte';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import Page from './+page.svelte';
import type { Collection } from '../lib/api';

/**
 * The first screen.
 *
 * What it must get right is telling the difference between "you have no collections", "the server said
 * no" and "the server could not be reached" — an empty list shown for a failed load is the bug that
 * makes a user think their material is gone — and sending the CSRF token with every mutation.
 */
describe('app shell', () => {
  beforeEach(() => {
    vi.unstubAllGlobals();
  });

  // The DOM is shared between tests in this environment, so each one starts from an empty document.
  afterEach(() => {
    cleanup();
  });

  it('renders the InfoScry heading', () => {
    render(Page);
    expect(screen.getByRole('heading', { level: 1, name: 'InfoScry' })).toBeTruthy();
  });

  it('lists the collections the API returns', async () => {
    stubFetch({ list: () => jsonResponse({ collections: [collection('Default'), collection('Nightfall')] }) });

    render(Page);

    expect(await screen.findByText('Nightfall')).toBeTruthy();
  });

  it('reports a failed load instead of showing an empty archive', async () => {
    stubFetch({
      list: () => jsonResponse({ error: { code: 'INTERNAL_ERROR', message: 'the archive is unavailable' } }, 500),
    });

    render(Page);

    const alert = await screen.findByRole('alert');
    expect(alert.textContent).toContain('the archive is unavailable');
  });

  it('creates a collection with the session CSRF token and shows it', async () => {
    const { calls } = stubFetch({
      list: () => jsonResponse({ collections: [collection('Default')] }),
      create: () => jsonResponse({ collection: collection('Nightfall') }, 201),
    });

    render(Page);
    await screen.findByText('Default');

    await typeCollectionName('Nightfall');
    screen.getByRole('button', { name: /create collection/i }).click();

    await waitFor(() => {
      expect(calls.some((call) => call.init?.method === 'POST')).toBe(true);
    });
    const created = calls.find((call) => call.init?.method === 'POST');
    const headers = created?.init?.headers as Record<string, string>;
    expect(headers['X-InfoScry-Csrf']).toBe('session-token');
    expect(await screen.findByText(/created/i)).toBeTruthy();
  });

  it('shows the reason the server refused a create', async () => {
    stubFetch({
      list: () => jsonResponse({ collections: [collection('Default')] }),
      create: () =>
        jsonResponse({ error: { code: 'DUPLICATE_COLLECTION_NAME', message: "a collection named 'Acme' already exists" } }, 409),
    });

    render(Page);
    await screen.findByText('Default');

    await typeCollectionName('Acme');

    screen.getByRole('button', { name: /create collection/i }).click();

    const alert = await screen.findByRole('alert');
    expect(alert.textContent).toContain('already exists');
  });
});

async function typeCollectionName(value: string): Promise<void> {
  const input = screen.getByLabelText('Name') as HTMLInputElement;
  await input.focus();
  input.value = value;
  input.dispatchEvent(new Event('input', { bubbles: true }));
}

function collection(name: string): Collection {
  return {
    id: name.toLowerCase(),
    name,
    ocrLanguages: 'eng',
    createdAt: '2026-09-21T07:00:00Z',
    updatedAt: '2026-09-21T07:00:00Z',
    lifecycle: 'ACTIVE',
  };
}

/** The subset of `Response` the client uses, so the tests do not depend on a global fetch stack. */
function jsonResponse(body: unknown, status = 200): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    statusText: '',
    text: async () => JSON.stringify(body),
  } as unknown as Response;
}

function stubFetch(overrides: {
  list?: () => Response;
  create?: () => Response;
  session?: () => Response;
}) {
  const calls: { url: string; init?: RequestInit }[] = [];
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    calls.push({ url, init });
    if (url.endsWith('/api/session')) {
      return (overrides.session ?? (() => jsonResponse({ product: 'InfoScry', csrfToken: 'session-token' })))();
    }
    if (url.endsWith('/api/collections') && init?.method === 'POST') {
      return (overrides.create ?? (() => jsonResponse({}, 500)))();
    }
    if (url.endsWith('/api/collections')) {
      return (overrides.list ?? (() => jsonResponse({ collections: [] })))();
    }
    return jsonResponse({ error: { code: 'NOT_FOUND', message: 'no such route' } }, 404);
  });
  vi.stubGlobal('fetch', fetchMock);
  return { calls };
}
