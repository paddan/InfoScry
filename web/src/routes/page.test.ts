import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/svelte';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import Page from './+page.svelte';
import type { Collection } from '../lib/api';

/**
 * The first screen.
 *
 * The reader shell selects one collection and sends the selected search mode and query to the API.
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
    expect(screen.getByRole('heading', { level: 1, name: 'Search your archive' })).toBeTruthy();
  });

  it('uses a collection selector and reports an empty collection list', async () => {
    stubFetch({ list: () => jsonResponse({ collections: [] }) });

    render(Page);

    expect(await screen.findByText('No collections yet.')).toBeTruthy();
    expect(screen.queryByRole('button', { name: /create collection/i })).toBeNull();
  });

  it('reports a failed load instead of showing an empty archive', async () => {
    stubFetch({
      list: () => jsonResponse({ error: { code: 'INTERNAL_ERROR', message: 'the archive is unavailable' } }, 500),
    });

    render(Page);

    const alert = await screen.findByRole('alert');
    expect(alert.textContent).toContain('the archive is unavailable');
  });

  it('shows that collections are loading while the request is pending', async () => {
    let finish!: (response: Response) => void;
    stubFetch({ list: () => new Promise<Response>((resolve) => { finish = resolve; }) });
    render(Page);
    expect(screen.getByText('Loading collections…')).toBeTruthy();
    finish(jsonResponse({ collections: [] }));
    expect(await screen.findByText('No collections yet.')).toBeTruthy();
  });

  it('searches the selected collection with the chosen mode and displays its locator label', async () => {
    const { calls } = stubFetch({
      list: () => jsonResponse({ collections: [collection('Default'), collection('Nightfall')] }),
      search: () => jsonResponse({ hits: [hit('Nightfall', 'Page 12')], staleFiltered: 0 }),
    });

    render(Page);
    await screen.findByText('Default');
    await fireEvent.change(screen.getByLabelText('Collection'), { target: { value: 'nightfall' } });
    await fireEvent.change(screen.getByLabelText('Search mode'), { target: { value: 'SEMANTIC' } });
    await fireEvent.input(screen.getByLabelText('Search query'), { target: { value: 'tax records' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Search' }));

    expect(await screen.findByText('Page 12')).toBeTruthy();
    const call = calls.find((entry) => entry.url.startsWith('/api/search'));
    expect(call?.url).toBe('/api/search?collection=nightfall&mode=SEMANTIC&q=tax+records');
    expect(call?.init?.method).toBeUndefined();
  });

  it('keeps search settings in the sidebar and serializes supported filters', async () => {
    const { calls } = stubFetch({
      list: () => jsonResponse({ collections: [collection('Default')] }),
      search: () => jsonResponse({ hits: [], staleFiltered: 0 }),
    });
    render(Page);
    await screen.findByText('Default');
    await fireEvent.change(screen.getByLabelText('Search mode'), { target: { value: 'KEYWORD' } });
    await fireEvent.click(screen.getByText('Advanced search filters'));
    await fireEvent.input(screen.getByLabelText('Path contains'), { target: { value: 'reports' } });
    await fireEvent.input(screen.getByLabelText('Imported from'), { target: { value: '2026-01-01' } });
    await fireEvent.input(screen.getByLabelText('Imported until'), { target: { value: '2026-06-30' } });
    await fireEvent.click(screen.getByLabelText('OCR only'));
    await fireEvent.input(screen.getByLabelText('Search query'), { target: { value: 'budget' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Search' }));

    expect(await screen.findByText('No results found.')).toBeTruthy();
    const search = calls.find((entry) => entry.url.startsWith('/api/search'));
    expect(search?.url).toContain('mode=KEYWORD');
    expect(search?.url).toContain('path=reports');
    expect(search?.url).toContain('from=2026-01-01T00%3A00%3A00.000000000Z');
    expect(search?.url).toContain('until=2026-06-30T23%3A59%3A59Z');
    expect(search?.url).toContain('ocrOnly=true');
  });

  it('keeps a streamed Ask panel mounted and running while another mode is selected', async () => {
    let finishAsk!: (response: Response) => void;
    stubFetch({
      list: () => jsonResponse({ collections: [collection('Default')] }),
      ask: () => new Promise<Response>((resolve) => { finishAsk = resolve; }),
    });
    render(Page);
    await screen.findByText('Default');
    await fireEvent.click(screen.getByRole('tab', { name: 'Ask' }));
    await fireEvent.input(screen.getByLabelText('Question', { selector: '#question' }), { target: { value: 'What happened?' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Ask' }));
    await fireEvent.click(screen.getByRole('tab', { name: 'Search' }));
    expect(document.querySelector('#question')).toBeTruthy();
    await fireEvent.click(screen.getByRole('tab', { name: 'Ask' }));
    finishAsk(eventResponse([{ type: 'delta', text: 'The streamed answer' }, { type: 'done', text: 'The streamed answer', evidence: [] }]));

    expect(await screen.findByText('The streamed answer')).toBeTruthy();
  });

  it('renders an empty result state for a search with no hits', async () => {
    stubFetch({ list: () => jsonResponse({ collections: [collection('Default')] }), search: () => jsonResponse({ hits: [], staleFiltered: 0 }) });
    render(Page);
    await screen.findByText('Default');
    await fireEvent.input(screen.getByLabelText('Search query'), { target: { value: 'missing thing' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Search' }));
    expect(await screen.findByText('No results found.')).toBeTruthy();
  });

  it('reports a search error from the API', async () => {
    stubFetch({
      list: () => jsonResponse({ collections: [collection('Default')] }),
      search: () => jsonResponse({ error: { code: 'SEARCH_UNAVAILABLE', message: 'GPU embeddings are unavailable' } }, 503),
    });
    render(Page);
    await screen.findByText('Default');
    await fireEvent.input(screen.getByLabelText('Search query'), { target: { value: 'a question' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Search' }));
    expect((await screen.findByRole('alert')).textContent).toContain('GPU embeddings are unavailable');
  });

  it('does not show results from a prior collection after switching while search is pending', async () => {
    let finishSearch!: (response: Response) => void;
    let requestCount = 0;
    stubFetch({
      list: () => jsonResponse({ collections: [collection('Default'), collection('Nightfall')] }),
      search: () => {
        requestCount += 1;
        if (requestCount === 1) return new Promise<Response>((resolve) => { finishSearch = resolve; });
        return jsonResponse({ hits: [], staleFiltered: 0 });
      },
    });
    render(Page);
    await screen.findByText('Default');
    await fireEvent.input(screen.getByLabelText('Search query'), { target: { value: 'payment records' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Search' }));
    await fireEvent.change(screen.getByLabelText('Collection'), { target: { value: 'nightfall' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Search' }));
    expect(await screen.findByText('No results found.')).toBeTruthy();
    finishSearch(jsonResponse({ hits: [hit('Default', 'Page 3')], staleFiltered: 0 }));

    expect(screen.queryByText('Default')).toBeTruthy(); // Still present as the collection option.
    expect(screen.queryByText('Page 3')).toBeNull();
  });

  it('shows results from the newest search when the query changes', async () => {
    let finishFirst!: (response: Response) => void;
    let requestCount = 0;
    stubFetch({
      list: () => jsonResponse({ collections: [collection('Default')] }),
      search: () => {
        requestCount += 1;
        if (requestCount === 1) return new Promise<Response>((resolve) => { finishFirst = resolve; });
        return jsonResponse({ hits: [hit('Default', 'Page 9')], staleFiltered: 0 });
      },
    });
    render(Page);
    await screen.findByText('Default');
    await fireEvent.input(screen.getByLabelText('Search query'), { target: { value: 'old query' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Search' }));
    finishFirst(jsonResponse({ hits: [hit('Default', 'Page 1')], staleFiltered: 0 }));
    expect(await screen.findByText('Page 1')).toBeTruthy();

    await fireEvent.input(screen.getByLabelText('Search query'), { target: { value: 'new query' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Search' }));
    expect(await screen.findByText('Page 9')).toBeTruthy();
    expect(screen.queryByText('Page 1')).toBeNull();
  });

  it('ignores an older pending search after a newer query is submitted', async () => {
    let finishOldSearch!: (response: Response) => void;
    let requestCount = 0;
    stubFetch({
      list: () => jsonResponse({ collections: [collection('Default')] }),
      search: () => {
        requestCount += 1;
        if (requestCount === 1) return new Promise<Response>((resolve) => { finishOldSearch = resolve; });
        return jsonResponse({ hits: [hit('Default', 'Page 9')], staleFiltered: 0 });
      },
    });
    render(Page);
    await screen.findByText('Default');
    await fireEvent.input(screen.getByLabelText('Search query'), { target: { value: 'old query' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Search' }));
    await fireEvent.input(screen.getByLabelText('Search query'), { target: { value: 'new query' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Search' }));
    expect(await screen.findByText('Page 9')).toBeTruthy();

    finishOldSearch(jsonResponse({ hits: [hit('Default', 'Page 1')], staleFiltered: 0 }));
    expect(screen.queryByText('Page 1')).toBeNull();
    expect(screen.getByText('Page 9')).toBeTruthy();
  });

  it('opens an exact source from a result using an accessible keyboard-operable control', async () => {
    const { calls } = stubFetch({
      list: () => jsonResponse({ collections: [collection('Default')] }),
      search: () => jsonResponse({ hits: [hit('Default', 'Page 12')] }),
      source: () => jsonResponse(sourcePage('Extracted page text', 0, 16)),
    });
    render(Page);
    await screen.findByText('Default');
    await fireEvent.input(screen.getByLabelText('Search query'), { target: { value: 'tax records' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Search' }));

    const result = await screen.findByRole('button', { name: /Default.*Page 12/s });
    expect(result.tagName).toBe('BUTTON');
    result.focus();
    expect(document.activeElement).toBe(result);
    expect(result.getAttribute('aria-pressed')).toBe('false');
    await fireEvent.click(result);

    expect(await screen.findByRole('heading', { name: 'Source' })).toBeTruthy();
    expect(screen.getByText('Extracted page text')).toBeTruthy();
    expect(screen.getByRole('link', { name: 'Open original' }).getAttribute('href'))
      .toBe('/api/collections/default/documents/document-1/original');
    expect(calls.some((call) => call.url === '/api/collections/default/sources/unit-1?offset=0&limit=16384')).toBe(true);
  });

  it('loads more extracted source text by advancing the offset', async () => {
    let sourceRequests = 0;
    const { calls } = stubFetch({
      list: () => jsonResponse({ collections: [collection('Default')] }),
      search: () => jsonResponse({ hits: [hit('Default', 'Page 12')] }),
      source: () => {
        sourceRequests += 1;
        return sourceRequests === 1
          ? jsonResponse(sourcePage('First part ', 0, 30, true))
          : jsonResponse(sourcePage('second part', 11, 30, false));
      },
    });
    render(Page);
    await screen.findByText('Default');
    await fireEvent.input(screen.getByLabelText('Search query'), { target: { value: 'tax records' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Search' }));
    await fireEvent.click(await screen.findByRole('button', { name: /Default.*Page 12/s }));
    await fireEvent.click(await screen.findByRole('button', { name: 'Load more' }));

    expect(await screen.findByText('First part second part')).toBeTruthy();
    expect(calls.some((call) => call.url === '/api/collections/default/sources/unit-1?offset=11&limit=16384')).toBe(true);
    expect(screen.queryByRole('button', { name: 'Load more' })).toBeNull();
  });

  it('ignores a pending source response after switching collections', async () => {
    let finishSource!: (response: Response) => void;
    stubFetch({
      list: () => jsonResponse({ collections: [collection('Default'), collection('Nightfall')] }),
      search: () => jsonResponse({ hits: [hit('Default', 'Page 12')] }),
      source: () => new Promise<Response>((resolve) => { finishSource = resolve; }),
    });
    render(Page);
    await screen.findByText('Default');
    await fireEvent.input(screen.getByLabelText('Search query'), { target: { value: 'tax records' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Search' }));
    await fireEvent.click(await screen.findByRole('button', { name: /Default.*Page 12/s }));
    await fireEvent.change(screen.getByLabelText('Collection'), { target: { value: 'nightfall' } });
    finishSource(jsonResponse(sourcePage('Stale source text', 0, 16)));

    await waitFor(() => expect(screen.queryByText('Stale source text')).toBeNull());
    expect(screen.queryByRole('heading', { name: 'Source' })).toBeNull();
  });

  it('renders extracted source as text rather than interpreting markup', async () => {
    stubFetch({
      list: () => jsonResponse({ collections: [collection('Default')] }),
      search: () => jsonResponse({ hits: [hit('Default', 'Page 12')] }),
      source: () => jsonResponse(sourcePage('<script>unsafe()</script>', 0, 26)),
    });
    render(Page);
    await screen.findByText('Default');
    await fireEvent.input(screen.getByLabelText('Search query'), { target: { value: 'tax records' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Search' }));
    await fireEvent.click(await screen.findByRole('button', { name: /Default.*Page 12/s }));

    expect(await screen.findByText('<script>unsafe()</script>')).toBeTruthy();
    expect(document.querySelector('script')).toBeNull();
  });

  it('streams Ask events, shows usage and links only citations from completed evidence', async () => {
    const { calls } = stubFetch({
      list: () => jsonResponse({ collections: [collection('Default')] }),
      ask: () => eventResponse([
        { type: 'delta', text: 'Answer [S1], invalid [S9].' },
        { type: 'usage', inputTokens: 1000, outputTokens: 500 },
        { type: 'citation', id: 'S1', valid: true },
        { type: 'done', text: 'Answer [S1], invalid [S9].', evidence: [{ id: 'S1', unitId: 'unit-ask', locatorLabel: 'Page 4', locator: { type: 'PdfPage', page: 4 } }] },
      ]),
      source: () => jsonResponse(sourcePage('Ask source body', 0, 15)),
    });

    render(Page);
    await screen.findByText('Default');
    await fireEvent.click(screen.getByRole('tab', { name: 'Ask' }));
    await fireEvent.input(screen.getByLabelText('Question', { selector: '#question' }), { target: { value: 'What happened?' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Ask' }));

    const citation = await screen.findByRole('button', { name: 'Open source S1, Page 4' });
    expect(screen.getByLabelText('Answer').textContent).toContain('Answer ');
    expect(screen.getByLabelText('Answer').textContent).toContain('[S9]');
    expect(screen.queryByRole('button', { name: /Open source S9/ })).toBeNull();
    expect(screen.getByText(/1,000 input tokens/)).toBeTruthy();
    expect(screen.getByText('Estimated cost: $0.0020')).toBeTruthy();
    await fireEvent.click(citation);
    expect(await screen.findByText('Ask source body')).toBeTruthy();
    expect(calls.some((call) => call.url === '/api/collections/default/sources/unit-ask?offset=0&limit=16384')).toBe(true);
    const askCall = calls.find((call) => call.url === '/api/ask');
    expect(JSON.parse(String(askCall?.init?.body))).toEqual({ collection: 'default', question: 'What happened?', profile: 'Local' });
    expect((askCall?.init?.headers as Record<string, string>)['X-InfoScry-Csrf']).toBe('session-token');
  });

  it('preserves streamed partial text when Ask ends with an error', async () => {
    stubFetch({
      list: () => jsonResponse({ collections: [collection('Default')] }),
      ask: () => eventResponse([
        { type: 'delta', text: 'Partial answer' },
        { type: 'error', code: 'PROVIDER_FAILED', message: 'provider unavailable' },
      ]),
    });
    render(Page);
    await screen.findByText('Default');
    await fireEvent.click(screen.getByRole('tab', { name: 'Ask' }));
    await fireEvent.input(screen.getByLabelText('Question', { selector: '#question' }), { target: { value: 'What happened?' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Ask' }));

    expect(await screen.findByText('Partial answer')).toBeTruthy();
    expect((await screen.findByRole('alert')).textContent).toContain('provider unavailable');
  });

  it('aborts the active provider request when switching collections', async () => {
    let requestSignal: AbortSignal | null = null;
    let streamCanceled = false;
    const encoder = new TextEncoder();
    stubFetch({
      list: () => jsonResponse({ collections: [collection('Default'), collection('Nightfall')] }),
      ask: (signal) => Promise.resolve({
        ok: true,
        status: 200,
        statusText: '',
        body: new ReadableStream<Uint8Array>({
          start(controller) {
            requestSignal = signal ?? null;
            controller.enqueue(encoder.encode(`data: ${JSON.stringify({ type: 'delta', text: 'Old answer' })}\n\n`));
          },
          cancel() { streamCanceled = true; },
        }),
      } as Response),
    });
    render(Page);
    await screen.findByText('Default');
    await fireEvent.click(screen.getByRole('tab', { name: 'Ask' }));
    await fireEvent.input(screen.getByLabelText('Question', { selector: '#question' }), { target: { value: 'What happened?' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Ask' }));
    expect(await screen.findByText('Old answer')).toBeTruthy();

    await fireEvent.change(screen.getByLabelText('Collection'), { target: { value: 'nightfall' } });
    await waitFor(() => expect(requestSignal?.aborted).toBe(true));
    expect(streamCanceled).toBe(true);
    expect(screen.queryByText('Old answer')).toBeNull();
  });

  it('refreshes CSRF once and retries Ask only for the stale-session rejection', async () => {
    let askRequests = 0;
    const { calls } = stubFetch({
      list: () => jsonResponse({ collections: [collection('Default')] }),
      session: () => jsonResponse({ csrfToken: 'fresh-token' }),
      ask: () => {
        askRequests += 1;
        return askRequests === 1
          ? jsonResponse({ error: { code: 'MUTATION_REQUIRES_CREDENTIALS', message: 'session expired' } }, 401)
          : eventResponse([{ type: 'done', text: 'Recovered answer', evidence: [] }]);
      },
    });
    render(Page);
    await screen.findByText('Default');
    await fireEvent.click(screen.getByRole('tab', { name: 'Ask' }));
    await fireEvent.input(screen.getByLabelText('Question', { selector: '#question' }), { target: { value: 'What happened?' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Ask' }));

    expect(await screen.findByText('Recovered answer')).toBeTruthy();
    const askCalls = calls.filter((call) => call.url === '/api/ask');
    expect(askCalls).toHaveLength(2);
    expect((askCalls[1].init?.headers as Record<string, string>)['X-InfoScry-Csrf']).toBe('fresh-token');
  });

  it('does not retry Ask for other request failures', async () => {
    const { calls } = stubFetch({
      list: () => jsonResponse({ collections: [collection('Default')] }),
      ask: () => jsonResponse({ error: { code: 'PROVIDER_FAILED', message: 'provider unavailable' } }, 503),
    });
    render(Page);
    await screen.findByText('Default');
    await fireEvent.click(screen.getByRole('tab', { name: 'Ask' }));
    await fireEvent.input(screen.getByLabelText('Question', { selector: '#question' }), { target: { value: 'What happened?' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Ask' }));

    expect((await screen.findByRole('alert')).textContent).toContain('provider unavailable');
    expect(calls.filter((call) => call.url === '/api/ask')).toHaveLength(1);
  });
});

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

function hit(collectionName: string, locatorLabel: string) {
  return {
    collectionId: collectionName.toLowerCase(),
    documentId: 'document-1',
    title: collectionName,
    unitId: 'unit-1',
    chunkOrdinal: 0,
    text: 'A matching excerpt',
    highlighted: null,
    locator: { type: 'PdfPage', page: 12 },
    locatorLabel,
    matchedBy: ['SEMANTIC'],
  };
}

function sourcePage(text: string, offset: number, totalChars: number, truncated = false) {
  return {
    id: 'unit-1',
    documentId: 'document-1',
    ordinal: 0,
    locator: { type: 'PdfPage', page: 12 },
    text,
    offset,
    totalChars,
    truncated,
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
  list?: () => Response | Promise<Response>;
  search?: () => Response | Promise<Response>;
  source?: () => Response | Promise<Response>;
  ask?: (signal?: AbortSignal) => Response | Promise<Response>;
  defaults?: () => Response | Promise<Response>;
  profiles?: () => Response | Promise<Response>;
  session?: () => Response;
}) {
  const calls: { url: string; init?: RequestInit }[] = [];
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    calls.push({ url, init });
    if (url.endsWith('/api/session')) {
      return (overrides.session ?? (() => jsonResponse({ product: 'InfoScry', csrfToken: 'session-token' })))();
    }
    if (url.endsWith('/api/collections')) {
      return (overrides.list ?? (() => jsonResponse({ collections: [] })))();
    }
    if (url.startsWith('/api/search')) {
      return (overrides.search ?? (() => jsonResponse({ hits: [], staleFiltered: 0 })))();
    }
    if (url.endsWith('/api/llm/defaults/ASK')) {
      return (overrides.defaults ?? (() => jsonResponse({ profileName: 'Local' })))();
    }
    if (url.endsWith('/api/llm/profiles')) {
      return (overrides.profiles ?? (() => jsonResponse({ profiles: [{ name: 'Local', inputPricePerMillion: 1, outputPricePerMillion: 2 }], defaults: {} })))();
    }
    if (url.endsWith('/api/ask')) return (overrides.ask ?? (() => eventResponse([{ type: 'done', text: '', evidence: [] }])))(init?.signal as AbortSignal | undefined);
    if (url.includes('/sources/')) {
      return (overrides.source ?? (() => jsonResponse({ error: { code: 'NOT_FOUND', message: 'no such route' } }, 404)))();
    }
    return jsonResponse({ error: { code: 'NOT_FOUND', message: 'no such route' } }, 404);
  });
  vi.stubGlobal('fetch', fetchMock);
  return { calls };
}

function eventResponse(events: unknown[]): Response {
  const chunks = events.map((event) => `data: ${JSON.stringify(event)}\n\n`);
  const encoder = new TextEncoder();
  const stream = new ReadableStream<Uint8Array>({
    start(controller) {
      for (const chunk of chunks) controller.enqueue(encoder.encode(chunk));
      controller.close();
    },
  });
  return { ok: true, status: 200, statusText: '', body: stream } as Response;
}
