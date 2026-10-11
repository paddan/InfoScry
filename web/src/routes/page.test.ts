import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/svelte';
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
    Object.defineProperty(window, 'innerWidth', { configurable: true, value: 1440 });
  });

  // The DOM is shared between tests in this environment, so each one starts from an empty document.
  afterEach(() => {
    cleanup();
    window.localStorage.clear();
  });

  it('provides the shared workspace hook across mode changes without replacing navigation', async () => {
    stubFetch({ list: () => jsonResponse({ collections: [collection('Default')] }) });
    render(Page);
    await screen.findByText('Default');
    const workspace = screen.getByRole('main');
    expect(workspace.classList.contains('workspace-area')).toBe(true);
    await fireEvent.click(screen.getByRole('tab', { name: 'Admin' }));
    expect(screen.getByRole('main')).toBe(workspace);
    expect(await screen.findByRole('tablist', { name: 'Administration section' })).toBeTruthy();
    await fireEvent.click(screen.getByRole('tab', { name: 'Search' }));
    expect(within(workspace).getByLabelText('Search query')).toBeTruthy();
  });

  it('restores collection-specific document choices across Admin navigation and resets on reload', async () => {
    stubFetch({ list: () => jsonResponse({ collections: [collection('Default'), collection('Nightfall')] }) });
    const view = render(Page);
    await screen.findByText('Default');
    await fireEvent.click(screen.getByRole('tab', { name: 'Admin' }));
    await fireEvent.click(screen.getByRole('button', { name: 'Default 0 documents' }));
    expect(screen.getByRole('button', { name: 'Documents' }).getAttribute('aria-expanded')).toBe('false');
    await fireEvent.click(screen.getByRole('button', { name: 'Documents' }));
    await fireEvent.input(screen.getByLabelText('Search filenames'), { target: { value: 'draft' } });
    await fireEvent.change(screen.getByLabelText('Status'), { target: { value: 'FAILED' } });
    await fireEvent.change(screen.getByLabelText('Sort'), { target: { value: 'name-desc' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Nightfall 0 documents' }));
    expect(screen.getByRole('button', { name: 'Documents' }).getAttribute('aria-expanded')).toBe('false');
    await fireEvent.click(screen.getByRole('button', { name: 'Default 0 documents' }));
    expect((screen.getByLabelText('Search filenames') as HTMLInputElement).value).toBe('draft');
    expect((screen.getByLabelText('Status') as HTMLSelectElement).value).toBe('FAILED');
    expect((screen.getByLabelText('Sort') as HTMLSelectElement).value).toBe('name-desc');
    await fireEvent.click(screen.getByRole('tab', { name: 'Search' }));
    await fireEvent.click(screen.getByRole('tab', { name: 'Admin' }));
    expect((screen.getByLabelText('Search filenames') as HTMLInputElement).value).toBe('draft');
    view.unmount();
    render(Page);
    await screen.findByText('Default');
    await fireEvent.click(screen.getByRole('tab', { name: 'Admin' }));
    await fireEvent.click(screen.getByRole('button', { name: 'Default 0 documents' }));
    expect(screen.getByRole('button', { name: 'Documents' }).getAttribute('aria-expanded')).toBe('false');
  });

  it('removes deleted collection view choices before the same managed ID is seen again', async () => {
    let deleted = false;
    let reintroduced = false;
    stubFetch({
      list: () => jsonResponse({ collections: deleted && !reintroduced ? [collection('Default')] : [collection('Default'), collection('Nightfall')] }),
      deleteCollection: () => { deleted = true; return jsonResponse({ deletion: {
        operationId: 'gone', kind: 'COLLECTION', collectionId: 'nightfall', collectionName: 'Nightfall',
        documentIds: [], phase: 'DONE', terminal: true, errorCode: null,
      } }); },
      rename: () => jsonResponse({ collection: collection('Default') }),
    });
    render(Page);
    await screen.findByText('Default');
    await fireEvent.click(screen.getByRole('tab', { name: 'Admin' }));
    await fireEvent.click(screen.getByRole('button', { name: 'Nightfall 0 documents' }));
    await fireEvent.click(screen.getByRole('button', { name: 'Documents' }));
    await fireEvent.input(screen.getByLabelText('Search filenames'), { target: { value: 'must disappear' } });
    await fireEvent.click(screen.getByText('Settings', { selector: 'summary' }));
    await fireEvent.click(screen.getByRole('button', { name: 'Delete collection' }));
    await fireEvent.input(screen.getByLabelText('Type the collection name to confirm'), { target: { value: 'Nightfall' } });
    await fireEvent.click(within(screen.getByRole('dialog', { name: 'Delete Nightfall?' })).getByRole('button', { name: 'Delete collection' }));
    await waitFor(() => expect(screen.queryByRole('button', { name: 'Nightfall 0 documents' })).toBeNull());
    reintroduced = true;
    await fireEvent.click(screen.getByRole('button', { name: 'Default 0 documents' }));
    await fireEvent.click(screen.getByText('Settings', { selector: 'summary' }));
    await fireEvent.click(screen.getByRole('button', { name: 'Save name' }));
    await fireEvent.click(await screen.findByRole('button', { name: 'Nightfall 0 documents' }));
    expect(screen.getByRole('button', { name: 'Documents' }).getAttribute('aria-expanded')).toBe('false');
    await fireEvent.click(screen.getByRole('button', { name: 'Documents' }));
    expect((screen.getByLabelText('Search filenames') as HTMLInputElement).value).toBe('');
  });

  it('keeps New conversation visible and puts Ask history in a closed disclosure', async () => {
    stubFetch({
      list: () => jsonResponse({ collections: [collection('Default')] }),
      asks: () => jsonResponse({ asks: [askHistoryEntry('ask-1', 'The signer', 'Who signed it?')] }),
    });
    render(Page);
    await screen.findByText('Default');
    await fireEvent.click(screen.getByRole('tab', { name: 'Ask' }));
    const history = await screen.findByRole('navigation', { name: 'Ask history' });
    expect(within(history).getByRole('button', { name: 'New conversation' })).toBeTruthy();
    const disclosure = within(history).getByText('Conversation history', { selector: 'summary' });
    expect(disclosure.closest('details')?.getAttribute('open')).toBeNull();
    await fireEvent.click(disclosure);
    expect(await within(history).findByRole('button', { name: 'The signer' })).toBeTruthy();
    expect(disclosure.closest('details')?.getAttribute('open')).not.toBeNull();
    expect(screen.getByRole('tabpanel', { name: 'Ask' })).toBeTruthy();
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

  it('keeps the reader usable when browser storage is blocked', async () => {
    stubFetch({ list: () => jsonResponse({ collections: [collection('Default')] }) });
    const storageDescriptor = Object.getOwnPropertyDescriptor(window, 'localStorage');
    if (!storageDescriptor) throw new Error('localStorage descriptor unavailable in test DOM');
    Object.defineProperty(window, 'localStorage', {
      configurable: true,
      get: () => { throw new DOMException('Storage denied', 'SecurityError'); },
    });

    try {
      render(Page);
      await screen.findByText('Default');
      await fireEvent.click(screen.getByRole('tab', { name: 'Investigate' }));
      expect(await screen.findByLabelText('Investigate question')).toBeTruthy();
    } finally {
      Object.defineProperty(window, 'localStorage', storageDescriptor);
    }
  });

  it('keeps the Admin view reachable before any collection exists', async () => {
    stubFetch({
      list: () => jsonResponse({ collections: [] }),
      presets: () => jsonResponse({ presets: [] }),
    });

    render(Page);
    await screen.findByText('No collections yet.');
    await fireEvent.click(screen.getByRole('tab', { name: 'Admin' }));

    expect(await screen.findByRole('tablist', { name: 'Administration section' })).toBeTruthy();
    expect(screen.getByRole('tab', { name: 'Collections' }).getAttribute('aria-selected')).toBe('true');
    // The empty archive still offers the create action, and no Import tab exists.
    expect(await screen.findByText('No collections yet')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Create collection' })).toBeTruthy();
    expect(screen.queryByRole('tab', { name: 'Import' })).toBeNull();
  });

  it('refreshes the workspace selector after a rename without changing the workspace selection', async () => {
    let renames = 0;
    const { calls } = stubFetch({
      list: () => jsonResponse({
        collections: renames === 0
          ? [collection('Default', 2), collection('Nightfall', 0)]
          : [collection('Default', 2), { ...collection('Nightfall archive', 0), id: 'nightfall' }],
      }),
      rename: () => {
        renames += 1;
        return jsonResponse({ collection: { ...collection('Nightfall archive'), id: 'nightfall' } });
      },
    });

    render(Page);
    await screen.findByText('Default');
    await fireEvent.click(screen.getByRole('tab', { name: 'Admin' }));
    await fireEvent.click(await screen.findByRole('button', { name: 'Nightfall 0 documents' }));
    await fireEvent.click(screen.getByRole('button', { name: 'Documents' }));
    await fireEvent.input(screen.getByLabelText('Search filenames'), { target: { value: 'invoice' } });
    await fireEvent.click(screen.getByText('Settings', { selector: 'summary' }));
    await fireEvent.input(screen.getByLabelText('Collection name'), { target: { value: 'Nightfall archive' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Save name' }));

    // The panel stays mounted through the reload, so the save reports itself instead of flashing a spinner.
    expect(await screen.findByText('Name saved.')).toBeTruthy();
    expect((screen.getByLabelText('Search filenames') as HTMLInputElement).value).toBe('invoice');
    await fireEvent.click(screen.getByRole('tab', { name: 'Search' }));
    await fireEvent.click(screen.getByRole('tab', { name: 'Admin' }));
    expect((screen.getByLabelText('Search filenames') as HTMLInputElement).value).toBe('invoice');
    await act(async () => {});
    const options = within(screen.getByLabelText('Collection')).getAllByRole('option');
    expect(options.map((option) => option.textContent)).toEqual(['Default', 'Nightfall archive']);    // Renaming is not a workspace switch: the workspace keeps the collection it had, and Admin keeps
    // managing the renamed one, which kept its id.
    expect((screen.getByLabelText('Collection') as HTMLSelectElement).value).toBe('default');
    expect(screen.getByRole('region', { name: 'Manage Nightfall archive' })).toBeTruthy();
    expect(calls.some((call) => call.url === '/api/collections/nightfall' && call.init?.method === 'PATCH')).toBe(true);
  });

  it('switches the Admin sub-tab between Collections and LLM profiles', async () => {
    stubFetch({ list: () => jsonResponse({ collections: [collection('Default', 2), collection('Nightfall', 0)] }) });

    render(Page);
    await screen.findByText('Default');
    await fireEvent.click(screen.getByRole('tab', { name: 'Admin' }));
    expect(await screen.findByRole('tablist', { name: 'Administration section' })).toBeTruthy();
    expect(screen.getByRole('tab', { name: 'Collections' }).getAttribute('aria-selected')).toBe('true');
    expect(screen.getByRole('tab', { name: 'LLM profiles' }).getAttribute('aria-selected')).toBe('false');
    expect(screen.queryByRole('tab', { name: 'Import' })).toBeNull();
    expect(screen.getByText('2 documents')).toBeTruthy();

    await fireEvent.click(screen.getByRole('tab', { name: 'LLM profiles' }));
    expect(screen.getByRole('tab', { name: 'LLM profiles' }).getAttribute('aria-selected')).toBe('true');

    await fireEvent.click(screen.getByRole('tab', { name: 'Collections' }));
    expect(screen.getByRole('tab', { name: 'Collections' }).getAttribute('aria-selected')).toBe('true');

    // Managing a collection in Admin never switches the workspace collection or its conversations.
    await fireEvent.click(screen.getByRole('button', { name: 'Nightfall 0 documents' }));
    expect((screen.getByLabelText('Collection') as HTMLSelectElement).value).toBe('default');
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

  it('searches as the reader types, without pressing Search', async () => {
    const { calls } = stubFetch({
      list: () => jsonResponse({ collections: [collection('Default')] }),
      search: () => jsonResponse({ hits: [hit('Default', 'Page 12')], staleFiltered: 0 }),
    });

    render(Page);
    await screen.findByText('Default');
    await fireEvent.input(screen.getByLabelText('Search query'), { target: { value: 'tax records' } });

    expect(await screen.findByText('Page 12')).toBeTruthy();
    expect(calls.filter((entry) => entry.url.startsWith('/api/search')).map((entry) => entry.url))
      .toEqual(['/api/search?collection=default&mode=HYBRID&q=tax+records']);
  });

  it('cancels the live search when the reader submits before the debounce fires', async () => {
    const { calls } = stubFetch({
      list: () => jsonResponse({ collections: [collection('Default')] }),
      search: () => jsonResponse({ hits: [hit('Default', 'Page 7')], staleFiltered: 0 }),
    });

    render(Page);
    await screen.findByText('Default');
    await fireEvent.input(screen.getByLabelText('Search query'), { target: { value: 'payment records' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Search' }));
    expect(await screen.findByText('Page 7')).toBeTruthy();

    // Past the debounce window: a timer that outlived the submit would add a second request here.
    await new Promise((resolve) => setTimeout(resolve, 350));
    expect(calls.filter((entry) => entry.url.startsWith('/api/search'))).toHaveLength(1);
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
    expect(search?.url).toContain('from=2026-01-01T00%3A00%3A00.000Z');
    expect(search?.url).toContain('until=2026-06-30T23%3A59%3A59.999Z');
    expect(search?.url).toContain('ocrOnly=true');
  });

  it('retires a pending search when a filter changes and sends the new filter', async () => {
    let finishOld!: (response: Response) => void;
    let requestCount = 0;
    const { calls } = stubFetch({
      list: () => jsonResponse({ collections: [collection('Default')] }),
      search: () => {
        requestCount += 1;
        if (requestCount === 1) return new Promise<Response>((resolve) => { finishOld = resolve; });
        return jsonResponse({ hits: [hit('Default', 'Filtered page')], staleFiltered: 0 });
      },
    });
    render(Page);
    await screen.findByText('Default');
    await fireEvent.input(screen.getByLabelText('Search query'), { target: { value: 'budget' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Search' }));
    await fireEvent.click(screen.getByText('Advanced search filters'));
    await fireEvent.input(screen.getByLabelText('Path contains'), { target: { value: 'reports/' } });
    await new Promise((resolve) => setTimeout(resolve, 300));

    expect(await screen.findByText('Filtered page')).toBeTruthy();
    expect(calls.filter((call) => call.url.startsWith('/api/search')).at(-1)?.url)
      .toContain('path=reports%2F');
    finishOld(jsonResponse({ hits: [hit('Default', 'Old unfiltered page')], staleFiltered: 0 }));
    expect(screen.queryByText('Old unfiltered page')).toBeNull();
  });

  it('reruns an existing query after an advanced filter changes', async () => {
    let requestCount = 0;
    const { calls } = stubFetch({
      list: () => jsonResponse({ collections: [collection('Default')] }),
      search: () => {
        requestCount += 1;
        return jsonResponse({ hits: [hit('Default', requestCount === 1 ? 'All documents' : 'Complete only')], staleFiltered: 0 });
      },
    });
    render(Page);
    await screen.findByText('Default');
    await fireEvent.input(screen.getByLabelText('Search query'), { target: { value: 'budget' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Search' }));
    expect(await screen.findByText('All documents')).toBeTruthy();
    await fireEvent.click(screen.getByText('Advanced search filters'));
    await fireEvent.change(screen.getByLabelText('Document status'), { target: { value: 'COMPLETE' } });

    expect(await screen.findByText('Complete only')).toBeTruthy();
    expect(calls.filter((call) => call.url.startsWith('/api/search'))).toHaveLength(2);
    expect(calls.filter((call) => call.url.startsWith('/api/search')).at(-1)?.url).toContain('status=COMPLETE');
  });

  it('renders escaped server highlights as marks without activating source HTML', async () => {
    const highlighted = {
      ...hit('Default', 'Page 4'),
      text: 'budget <script>alert(1)</script>',
      highlighted: '<mark>budget</mark> &lt;script&gt;alert(1)&lt;/script&gt;',
    };
    stubFetch({
      list: () => jsonResponse({ collections: [collection('Default')] }),
      search: () => jsonResponse({ hits: [highlighted], staleFiltered: 0 }),
    });
    render(Page);
    await screen.findByText('Default');
    await fireEvent.input(screen.getByLabelText('Search query'), { target: { value: 'budget' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Search' }));

    expect(await screen.findByText('budget', { selector: 'mark' })).toBeTruthy();
    expect(document.querySelector('.result script')).toBeNull();
    expect(document.querySelector('.result')?.textContent).toContain('<script>alert(1)</script>');
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

  it('opens a managed PDF from Admin in the physical-page reader', async () => {
    const { calls } = stubFetch({
      list: () => jsonResponse({ collections: [collection('Default'), collection('Nightfall', 2)] }),
      documents: () => jsonResponse({
        documents: [{
          id: 'document-1',
          collectionId: 'nightfall',
          mediaType: 'application/pdf',
          originalFilename: 'quarterly.pdf',
          sizeBytes: 2048,
          status: 'COMPLETE',
          createdAt: '2026-09-21T07:00:00Z',
          updatedAt: '2026-09-21T07:00:00Z',
        }],
        total: 2,
      }),
      document: () => jsonResponse({
        document: {
          id: 'document-1',
          collectionId: 'nightfall',
          mediaType: 'application/pdf',
          originalFilename: 'quarterly.pdf',
          sizeBytes: 2048,
          status: 'COMPLETE',
          createdAt: '2026-09-21T07:00:00Z',
          updatedAt: '2026-09-21T07:00:00Z',
        },
        errorMessage: null,
        sourceId: 'unit-9',
      }),
      source: () => jsonResponse(sourcePage('Managed document text', 0, 21)),
      pageReview: (url) => {
        const revision = new URL(url, window.location.origin).searchParams.get('revision') ?? 'revision-1';
        return jsonResponse(pageReviewResponse({ revisionId: revision, text: `Reading ${revision}` }));
      },
    });

    render(Page);
    await screen.findByText('Default');
    await fireEvent.click(screen.getByRole('tab', { name: 'Admin' }));
    await fireEvent.click(await screen.findByRole('button', { name: 'Nightfall 2 documents' }));
    await fireEvent.click(screen.getByRole('button', { name: 'Documents' }));
    await fireEvent.click(await screen.findByRole('button', { name: 'Details' }));
    const openDocument = await screen.findByRole('button', { name: 'Open document' });
    await fireEvent.click(openDocument);

    expect(await screen.findByRole('region', { name: 'Page review' })).toBeTruthy();
    expect(await screen.findByText('Reading revision-1')).toBeTruthy();
    // Admin opener keeps its managed collection and the reader requests one exact page tuple.
    expect(calls.some((call) => call.url === '/api/collections/nightfall/documents/document-1/page-review?ordinal=0&limit=16384')).toBe(true);

    await fireEvent.click(screen.getByRole('button', { name: 'Back to documents' }));
    await waitFor(() => expect(document.activeElement).toBe(openDocument));
  });

  it('routes a page scan request from the Admin reader into the explicit preview dialog', async () => {
    const { calls } = stubFetch({
      list: () => jsonResponse({ collections: [collection('Default'), collection('Nightfall', 1)] }),
      documents: () => jsonResponse({ documents: [{
        id: 'document-1', collectionId: 'nightfall', mediaType: 'application/pdf', originalFilename: 'quarterly.pdf',
        sizeBytes: 2048, status: 'COMPLETE', createdAt: '', updatedAt: '',
      }], total: 1 }),
      document: () => jsonResponse({ document: {
        id: 'document-1', collectionId: 'nightfall', mediaType: 'application/pdf', originalFilename: 'quarterly.pdf',
        sizeBytes: 2048, status: 'COMPLETE', createdAt: '', updatedAt: '',
      }, errorMessage: null, sourceId: 'unit-9' }),
      pageReview: (url) => {
        const revision = new URL(url, window.location.origin).searchParams.get('revision') ?? 'revision-1';
        return jsonResponse(pageReviewResponse({ revisionId: revision, text: `Reading ${revision}` }));
      },
      readingMethods: () => jsonResponse({ default: 'surya', methods: [
        { method: 'surya', label: 'Surya', destination: 'this machine', available: true, unavailableReason: null, external: false },
      ] }),
      rescanPreview: () => jsonResponse({
        previewId: 'preview-1', documentId: 'document-1', managedHash: 'hash', snapshot: {},
        selection: { mode: 'explicit', ordinals: [0] }, pageTotal: 1, documentPages: 1,
        externalPageUpperBound: 1, destinations: [], costEstimate: null,
      }),
      startRescan: (_url, body) => jsonResponse({
        operationId: 'op-1', collectionId: 'nightfall', documentId: 'document-1',
        snapshot: {}, selection: { mode: 'explicit', ordinals: [0] }, managedSha256: 'hash', stage: 'COMPLETE',
        candidateRevisionId: 'revision-2', pagesCommitted: 1, pagesFailed: 0,
        external: { distinctPages: 0, calls: 0, allowance: 0 }, requestId: body.requestId,
        createdAt: '', updatedAt: '',
      }),
      documentRevisions: () => jsonResponse({
        revisions: [{ revisionId: 'revision-2', active: true, state: 'PUBLISHED', provenance: 'OCR', createdAt: '', pageCount: 2 }],
        total: 2, activeRevisionId: 'revision-2',
      }),
    });

    render(Page);
    await screen.findByText('Default');
    await fireEvent.click(screen.getByRole('tab', { name: 'Admin' }));
    await fireEvent.click(await screen.findByRole('button', { name: 'Nightfall 1 document' }));
    await fireEvent.click(screen.getByRole('button', { name: 'Documents' }));
    await fireEvent.click(await screen.findByRole('button', { name: 'Details' }));
    await fireEvent.click(await screen.findByRole('button', { name: 'Open document' }));
    await screen.findByText('Reading revision-1');
    expect(screen.getByTestId('document-rescan')).toBeTruthy();
    await fireEvent.click(screen.getByRole('button', { name: 'Scan this page again' }));
    expect(await screen.findByRole('dialog')).toBeTruthy();
    expect(await screen.findByRole('button', { name: 'Read page 1 with Surya' })).toBeTruthy();
    const previewCall = calls.find((call) => call.url.endsWith('/documents/document-1/ocr/preview'));
    expect(JSON.parse(String(previewCall?.init?.body))).toEqual({ method: 'surya', selection: { mode: 'explicit', ordinals: [0] } });
    await fireEvent.click(screen.getByRole('button', { name: 'Read page 1 with Surya' }));
    await waitFor(() => expect(calls.some((call) => call.url.endsWith('/documents/document-1/ocr/rescan'))).toBe(true));
    expect(await screen.findByRole('button', { name: 'Refresh to latest reading' }, { timeout: 3000 })).toBeTruthy();
    await fireEvent.click(screen.getByRole('button', { name: 'Refresh to latest reading' }));
    expect(await screen.findByText('Reading revision-2')).toBeTruthy();
    expect(calls.some((call) => call.url.includes('page-review?ordinal=0&revision=revision-2'))).toBe(true);
  });

  it('cancels the nested page-scan dialog without closing the reader or losing its selection', async () => {
    stubFetch({
      list: () => jsonResponse({ collections: [collection('Default'), collection('Nightfall', 1)] }),
      documents: () => jsonResponse({ documents: [{
        id: 'document-1', collectionId: 'nightfall', mediaType: 'application/pdf', originalFilename: 'quarterly.pdf',
        sizeBytes: 2048, status: 'COMPLETE', createdAt: '', updatedAt: '',
      }], total: 1 }),
      document: () => jsonResponse({ document: {
        id: 'document-1', collectionId: 'nightfall', mediaType: 'application/pdf', originalFilename: 'quarterly.pdf',
        sizeBytes: 2048, status: 'COMPLETE', createdAt: '', updatedAt: '',
      }, errorMessage: null, sourceId: 'unit-9' }),
      pageReview: (url) => {
        const ordinal = Number(new URL(url, window.location.origin).searchParams.get('ordinal') ?? 0);
        return jsonResponse(pageReviewResponse({
          ordinal, documentPages: 2,
          previousOrdinal: ordinal === 1 ? 0 : null,
          nextOrdinal: ordinal === 0 ? 1 : null,
        }));
      },
      readingMethods: () => jsonResponse({ default: 'surya', methods: [
        { method: 'surya', label: 'Surya', destination: 'this machine', available: true, unavailableReason: null, external: false },
      ] }),
      rescanPreview: () => jsonResponse({
        previewId: 'preview-1', documentId: 'document-1', managedHash: 'hash', snapshot: {},
        selection: { mode: 'explicit', ordinals: [0, 1] }, pageTotal: 2, documentPages: 2,
        externalPageUpperBound: 2, destinations: [], costEstimate: null,
      }),
    });

    render(Page);
    await screen.findByText('Default');
    await fireEvent.click(screen.getByRole('tab', { name: 'Admin' }));
    await fireEvent.click(await screen.findByRole('button', { name: 'Nightfall 1 document' }));
    await fireEvent.click(screen.getByRole('button', { name: 'Documents' }));
    await fireEvent.click(await screen.findByRole('button', { name: 'Details' }));
    await fireEvent.click(await screen.findByRole('button', { name: 'Open document' }));
    await screen.findByText('Page 1 of 2');
    await fireEvent.click(screen.getByRole('button', { name: 'Mark page 1' }));
    await fireEvent.click(screen.getByRole('button', { name: 'Next page' }));
    await screen.findByText('Page 2 of 2');
    await fireEvent.click(screen.getByRole('button', { name: 'Mark page 2' }));
    expect(screen.getByText('Selected pages: 1, 2')).toBeTruthy();

    await fireEvent.click(screen.getByRole('button', { name: 'Scan selected pages' }));
    const dialog = await screen.findByRole('dialog');
    await screen.findByRole('button', { name: 'Read pages 1, 2 with Surya' });
    await fireEvent.keyDown(dialog, { key: 'Escape' });

    expect(screen.queryByRole('dialog')).toBeNull();
    expect(screen.getByRole('region', { name: 'Page review' })).toBeTruthy();
    expect(screen.getByText('Page 2 of 2')).toBeTruthy();
    expect(screen.getByText('Selected pages: 1, 2')).toBeTruthy();
    expect((screen.getByRole('button', { name: 'Mark page 2' }) as HTMLButtonElement).getAttribute('aria-pressed')).toBe('true');
  });

  it('keeps the selected page through narrow Back and reopens without hidden marks', async () => {
    Object.defineProperty(window, 'innerWidth', { configurable: true, value: 390 });
    stubFetch({
      list: () => jsonResponse({ collections: [collection('Default'), collection('Nightfall', 1)] }),
      documents: () => jsonResponse({ documents: [{
        id: 'document-1', collectionId: 'nightfall', mediaType: 'application/pdf', originalFilename: 'quarterly.pdf',
        sizeBytes: 2048, status: 'COMPLETE', createdAt: '', updatedAt: '',
      }], total: 1 }),
      document: () => jsonResponse({ document: {
        id: 'document-1', collectionId: 'nightfall', mediaType: 'application/pdf', originalFilename: 'quarterly.pdf',
        sizeBytes: 2048, status: 'COMPLETE', createdAt: '', updatedAt: '',
      }, errorMessage: null, sourceId: 'unit-9' }),
      pageReview: (url) => {
        const request = new URL(url, window.location.origin);
        return jsonResponse(pageReviewResponse({
          ordinal: Number(request.searchParams.get('ordinal') ?? 0), documentPages: 2,
          previousOrdinal: request.searchParams.get('ordinal') === '1' ? 0 : null,
          nextOrdinal: request.searchParams.get('ordinal') === '0' ? 1 : null,
        }));
      },
    });
    render(Page);
    await screen.findByText('Default');
    await fireEvent.click(screen.getByRole('tab', { name: 'Admin' }));
    await fireEvent.click(await screen.findByRole('button', { name: 'Nightfall 1 document' }));
    await fireEvent.click(screen.getByRole('button', { name: 'Documents' }));
    await fireEvent.click(await screen.findByRole('button', { name: 'Details' }));
    await fireEvent.click(await screen.findByRole('button', { name: 'Open document' }));
    await fireEvent.click(await screen.findByRole('tab', { name: 'Text' }));
    await screen.findByText('Managed document text');
    await fireEvent.click(screen.getByRole('button', { name: 'Mark page 1' }));
    await fireEvent.click(screen.getByRole('button', { name: 'Next page' }));
    expect(await screen.findByText('Page 2 of 2')).toBeTruthy();
    await fireEvent.click(screen.getByRole('button', { name: 'Mark page 2' }));

    await fireEvent.click(screen.getByRole('button', { name: 'Back to results' }));
    expect(await screen.findByRole('button', { name: 'Open selected source' })).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Mark page 2' })).toBeNull();
    await fireEvent.click(screen.getByRole('button', { name: 'Open selected source' }));
    expect(await screen.findByText('Page 2 of 2')).toBeTruthy();
    expect(screen.getByText('No pages selected')).toBeTruthy();
  });

  it('starts a fresh publication lookup after narrow Back and ignores the old result', async () => {
    Object.defineProperty(window, 'innerWidth', { configurable: true, value: 390 });
    let activeRevisionId = 'revision-1';
    let resolveFirstLookup!: (response: Response) => void;
    const firstLookup = new Promise<Response>((resolve) => { resolveFirstLookup = resolve; });
    let resolveSecondLookup!: (response: Response) => void;
    const secondLookup = new Promise<Response>((resolve) => { resolveSecondLookup = resolve; });
    let revisionLookups = 0;
    const { calls } = stubFetch({
      list: () => jsonResponse({ collections: [collection('Default'), collection('Nightfall', 1)] }),
      documents: () => jsonResponse({ documents: [{
        id: 'document-1', collectionId: 'nightfall', mediaType: 'application/pdf', originalFilename: 'quarterly.pdf',
        sizeBytes: 2048, status: 'COMPLETE', createdAt: '', updatedAt: '',
      }], total: 1 }),
      document: () => jsonResponse({ document: {
        id: 'document-1', collectionId: 'nightfall', mediaType: 'application/pdf', originalFilename: 'quarterly.pdf',
        sizeBytes: 2048, status: 'COMPLETE', createdAt: '', updatedAt: '',
      }, errorMessage: null, sourceId: 'unit-9' }),
      pageReview: (url) => {
        const request = new URL(url, window.location.origin);
        const revision = request.searchParams.get('revision') ?? 'revision-1';
        return jsonResponse(pageReviewResponse({ revisionId: revision, text: `Reading ${revision}` }));
      },
      rescanOperations: () => jsonResponse({ operations: [{
        operationId: 'operation-1', collectionId: 'nightfall', documentId: 'document-1', snapshot: {},
        selection: { mode: 'automatic' }, managedSha256: 'hash', stage: 'COMPLETE', pageTotal: 1,
        pagesCommitted: 1, pagesFailed: 0, candidateRevisionId: 'candidate-revision',
        external: { distinctPages: 0, calls: 0, allowance: 0 }, requestId: 'request-1', createdAt: '', updatedAt: '',
      }] }),
      documentRevisions: () => {
        revisionLookups += 1;
        if (revisionLookups === 1) return firstLookup;
        if (revisionLookups === 2) return secondLookup;
        return jsonResponse({ revisions: [{ revisionId: activeRevisionId, active: true, state: 'PUBLISHED', provenance: 'RESTORE', createdAt: '', pageCount: 2 }], total: 2, activeRevisionId });
      },
    });

    render(Page);
    await screen.findByText('Default');
    await fireEvent.click(screen.getByRole('tab', { name: 'Admin' }));
    await fireEvent.click(await screen.findByRole('button', { name: 'Nightfall 1 document' }));
    await fireEvent.click(screen.getByRole('button', { name: 'Documents' }));
    await fireEvent.click(await screen.findByRole('button', { name: 'Details' }));
    await fireEvent.click(await screen.findByRole('button', { name: 'Open document' }));
    expect(await screen.findByText('Reading revision-1')).toBeTruthy();
    await waitFor(() => expect(revisionLookups).toBe(1));
    expect(screen.queryByRole('button', { name: 'Refresh to latest reading' })).toBeNull();

    await fireEvent.click(screen.getByRole('button', { name: 'Back to results' }));
    expect(await screen.findByRole('button', { name: 'Open selected source' })).toBeTruthy();
    activeRevisionId = 'revision-restored';
    const operationLookupsBeforeReopen = calls.filter((call) => call.url.endsWith('/ocr/operations')).length;
    await fireEvent.click(screen.getByRole('button', { name: 'Open selected source' }));
    await waitFor(() => expect(calls.filter((call) => call.url.endsWith('/ocr/operations')).length)
      .toBeGreaterThan(operationLookupsBeforeReopen));
    await waitFor(() => expect(revisionLookups).toBe(2));

    // The first answer belongs to the hidden view and points back at its old R1 head.
    await act(async () => {
      resolveFirstLookup(jsonResponse({ revisions: [{ revisionId: 'revision-1', active: true, state: 'PUBLISHED', provenance: 'OCR', createdAt: '', pageCount: 2 }], total: 2, activeRevisionId: 'revision-1' }));
      await new Promise((resolve) => setTimeout(resolve, 0));
    });
    expect(screen.queryByRole('button', { name: 'Refresh to latest reading' })).toBeNull();
    expect(screen.getByText('Reading revision-1')).toBeTruthy();

    await act(async () => {
      resolveSecondLookup(jsonResponse({ revisions: [{ revisionId: activeRevisionId, active: true, state: 'PUBLISHED', provenance: 'RESTORE', createdAt: '', pageCount: 2 }], total: 2, activeRevisionId }));
      await new Promise((resolve) => setTimeout(resolve, 0));
    });
    await waitFor(() => expect(screen.getByRole('button', { name: 'Refresh to latest reading' })).toBeTruthy());
    await fireEvent.click(screen.getByRole('button', { name: 'Refresh to latest reading' }));
    expect(await screen.findByText('Reading revision-restored')).toBeTruthy();
    expect(calls.some((call) => call.url.includes('page-review?') && call.url.includes('revision=candidate-revision'))).toBe(false);
  });

  it('retries a failed active-head lookup on the next visible resync', async () => {
    Object.defineProperty(window, 'innerWidth', { configurable: true, value: 390 });
    let activeRevisionId = 'revision-restored';
    let revisionLookups = 0;
    const { calls } = stubFetch({
      list: () => jsonResponse({ collections: [collection('Default'), collection('Nightfall', 1)] }),
      documents: () => jsonResponse({ documents: [{
        id: 'document-1', collectionId: 'nightfall', mediaType: 'application/pdf', originalFilename: 'quarterly.pdf',
        sizeBytes: 2048, status: 'COMPLETE', createdAt: '', updatedAt: '',
      }], total: 1 }),
      document: () => jsonResponse({ document: {
        id: 'document-1', collectionId: 'nightfall', mediaType: 'application/pdf', originalFilename: 'quarterly.pdf',
        sizeBytes: 2048, status: 'COMPLETE', createdAt: '', updatedAt: '',
      }, errorMessage: null, sourceId: 'unit-9' }),
      pageReview: (url) => {
        const request = new URL(url, window.location.origin);
        const revision = request.searchParams.get('revision') ?? 'revision-1';
        return jsonResponse(pageReviewResponse({ revisionId: revision, text: `Reading ${revision}` }));
      },
      rescanOperations: () => jsonResponse({ operations: [{
        operationId: 'operation-1', collectionId: 'nightfall', documentId: 'document-1', snapshot: {},
        selection: { mode: 'automatic' }, managedSha256: 'hash', stage: 'COMPLETE', pageTotal: 1,
        pagesCommitted: 1, pagesFailed: 0, candidateRevisionId: 'candidate-revision',
        external: { distinctPages: 0, calls: 0, allowance: 0 }, requestId: 'request-1', createdAt: '', updatedAt: '',
      }] }),
      documentRevisions: () => {
        revisionLookups += 1;
        if (revisionLookups === 1) return jsonResponse({ error: { code: 'TEMPORARY_FAILURE', message: 'temporary failure' } }, 503);
        return jsonResponse({ revisions: [{ revisionId: activeRevisionId, active: true, state: 'PUBLISHED', provenance: 'RESTORE', createdAt: '', pageCount: 2 }], total: 2, activeRevisionId });
      },
    });

    render(Page);
    await screen.findByText('Default');
    await fireEvent.click(screen.getByRole('tab', { name: 'Admin' }));
    await fireEvent.click(await screen.findByRole('button', { name: 'Nightfall 1 document' }));
    await fireEvent.click(screen.getByRole('button', { name: 'Documents' }));
    await fireEvent.click(await screen.findByRole('button', { name: 'Details' }));
    await fireEvent.click(await screen.findByRole('button', { name: 'Open document' }));
    expect(await screen.findByText('Reading revision-1')).toBeTruthy();
    await waitFor(() => expect(revisionLookups).toBe(1));
    expect(screen.queryByRole('button', { name: 'Refresh to latest reading' })).toBeNull();

    await fireEvent.click(screen.getByRole('button', { name: 'Back to results' }));
    expect(await screen.findByRole('button', { name: 'Open selected source' })).toBeTruthy();
    await fireEvent.click(screen.getByRole('button', { name: 'Open selected source' }));
    await waitFor(() => expect(revisionLookups).toBe(2));
    await waitFor(() => expect(screen.getByRole('button', { name: 'Refresh to latest reading' })).toBeTruthy());
    await fireEvent.click(screen.getByRole('button', { name: 'Refresh to latest reading' }));
    expect(await screen.findByText('Reading revision-restored')).toBeTruthy();
    expect(calls.some((call) => call.url.includes('page-review?') && call.url.includes('revision=candidate-revision'))).toBe(false);
  });

  it('shows page-scan progress in the reader and refreshes the exact published revision', async () => {
    const { calls } = stubFetch({
      list: () => jsonResponse({ collections: [collection('Default'), collection('Nightfall', 1)] }),
      documents: () => jsonResponse({ documents: [{
        id: 'document-1', collectionId: 'nightfall', mediaType: 'application/pdf', originalFilename: 'quarterly.pdf',
        sizeBytes: 2048, status: 'COMPLETE', createdAt: '', updatedAt: '',
      }], total: 1 }),
      document: () => jsonResponse({ document: {
        id: 'document-1', collectionId: 'nightfall', mediaType: 'application/pdf', originalFilename: 'quarterly.pdf',
        sizeBytes: 2048, status: 'COMPLETE', createdAt: '', updatedAt: '',
      }, errorMessage: null, sourceId: 'unit-9' }),
      pageReview: (url) => {
        const request = new URL(url, window.location.origin);
        const revision = request.searchParams.get('revision') ?? 'revision-1';
        return jsonResponse(pageReviewResponse({
          revisionId: revision, ordinal: Number(request.searchParams.get('ordinal') ?? 0), documentPages: 2,
          text: `Reading ${revision}`,
        }));
      },
      readingMethods: () => jsonResponse({ default: 'surya', methods: [
        { method: 'surya', label: 'Surya', destination: 'this machine', available: true, unavailableReason: null, external: false },
      ] }),
      rescanPreview: () => jsonResponse({
        previewId: 'preview-1', documentId: 'document-1', managedHash: 'hash', snapshot: {},
        selection: { mode: 'explicit', ordinals: [0] }, pageTotal: 1, documentPages: 2,
        externalPageUpperBound: 1, destinations: [], costEstimate: null,
      }),
      startRescan: (_url, body) => jsonResponse({
        operationId: 'op-1', collectionId: 'nightfall', documentId: 'document-1', snapshot: {},
        selection: { mode: 'explicit', ordinals: [0] }, managedSha256: 'hash', stage: 'OCR', pageTotal: 1,
        pagesCommitted: 0, pagesFailed: 0, external: { distinctPages: 0, calls: 0, allowance: 0 },
        requestId: body.requestId, createdAt: '', updatedAt: '',
      }),
      rescanOperation: () => jsonResponse({
        operationId: 'op-1', collectionId: 'nightfall', documentId: 'document-1', snapshot: {},
        selection: { mode: 'explicit', ordinals: [0] }, managedSha256: 'hash', stage: 'COMPLETE', pageTotal: 1,
        candidateRevisionId: 'revision-2', pagesCommitted: 1, pagesFailed: 0,
        external: { distinctPages: 0, calls: 0, allowance: 0 }, requestId: 'request-1', createdAt: '', updatedAt: '',
      }),
      documentRevisions: () => jsonResponse({
        revisions: [{ revisionId: 'revision-2', active: true, state: 'PUBLISHED', provenance: 'OCR', createdAt: '', pageCount: 2 }],
        total: 2, activeRevisionId: 'revision-2',
      }),
    });
    render(Page);
    await screen.findByText('Default');
    await fireEvent.click(screen.getByRole('tab', { name: 'Admin' }));
    await fireEvent.click(await screen.findByRole('button', { name: 'Nightfall 1 document' }));
    await fireEvent.click(screen.getByRole('button', { name: 'Documents' }));
    await fireEvent.click(await screen.findByRole('button', { name: 'Details' }));
    await fireEvent.click(await screen.findByRole('button', { name: 'Open document' }));
    await screen.findByText('Reading revision-1');
    await fireEvent.click(screen.getByRole('button', { name: 'Scan this page again' }));
    await fireEvent.click(await screen.findByRole('button', { name: 'Read page 1 with Surya' }));
    expect(await screen.findByText('Reading 0 of 1')).toBeTruthy();
    expect(screen.getByText('Reading 0 of 1').closest('[hidden]')).toBeNull();
    expect(screen.getAllByTestId('document-rescan')).toHaveLength(1);
    expect(await screen.findByRole('button', { name: 'Refresh to latest reading' }, { timeout: 3000 })).toBeTruthy();
    await fireEvent.click(screen.getByRole('button', { name: 'Refresh to latest reading' }));
    expect(await screen.findByText('Reading revision-2')).toBeTruthy();
    expect(calls.some((call) => call.url.includes('page-review?ordinal=0&revision=revision-2'))).toBe(true);
  });

  it('resyncs the latest same-document publication instead of replaying a stale start after reopen', async () => {
    const { calls } = stubFetch({
      list: () => jsonResponse({ collections: [collection('Default'), collection('Nightfall', 1)] }),
      documents: () => jsonResponse({ documents: [{
        id: 'document-1', collectionId: 'nightfall', mediaType: 'application/pdf', originalFilename: 'quarterly.pdf',
        sizeBytes: 2048, status: 'COMPLETE', createdAt: '', updatedAt: '',
      }], total: 1 }),
      document: () => jsonResponse({ document: {
        id: 'document-1', collectionId: 'nightfall', mediaType: 'application/pdf', originalFilename: 'quarterly.pdf',
        sizeBytes: 2048, status: 'COMPLETE', createdAt: '', updatedAt: '',
      }, errorMessage: null, sourceId: 'unit-9' }),
      pageReview: (url) => {
        const request = new URL(url, window.location.origin);
        const revision = request.searchParams.get('revision') ?? latestRevision;
        const ordinal = Number(request.searchParams.get('ordinal') ?? 0);
        return jsonResponse(pageReviewResponse({
          revisionId: revision, ordinal, documentPages: 2,
          previousOrdinal: ordinal === 1 ? 0 : null, nextOrdinal: ordinal === 0 ? 1 : null,
          text: `Reading ${revision}`,
        }));
      },
      readingMethods: () => jsonResponse({ default: 'surya', methods: [
        { method: 'surya', label: 'Surya', destination: 'this machine', available: true, unavailableReason: null, external: false },
      ] }),
      rescanPreview: (_url, body) => jsonResponse({
        previewId: `preview-${startCount + 1}`, documentId: 'document-1', managedHash: 'hash', snapshot: {},
        selection: (body as { selection?: { mode: 'automatic' | 'explicit'; ordinals?: number[] } }).selection ?? { mode: 'automatic' },
        pageTotal: 1, documentPages: 1, externalPageUpperBound: 1, destinations: [], costEstimate: null,
      }),
      startRescan: (_url, body) => {
        startCount += 1;
        const revision = startCount === 1 ? 'revision-1' : 'revision-2';
        latestRevision = revision;
        latestOperation = {
          operationId: `operation-${startCount}`, collectionId: 'nightfall', documentId: 'document-1', snapshot: {},
          selection: startCount === 1 ? { mode: 'explicit', ordinals: [0] } : { mode: 'automatic' },
          managedSha256: 'hash', stage: 'COMPLETE', pageTotal: 1, pagesCommitted: 1, pagesFailed: 0,
          candidateRevisionId: revision, external: { distinctPages: 0, calls: 0, allowance: 0 },
          requestId: body.requestId, createdAt: '', updatedAt: '',
        };
        return jsonResponse(latestOperation);
      },
      rescanOperations: () => jsonResponse({ operations: latestOperation === null ? [] : [latestOperation] }),
      documentRevisions: () => jsonResponse({
        revisions: [{ revisionId: latestRevision, active: true, state: 'PUBLISHED', provenance: 'OCR', createdAt: '', pageCount: 2 }],
        total: 1, activeRevisionId: latestRevision,
      }),
    });
    let latestRevision = 'revision-0';
    let startCount = 0;
    let latestOperation: Record<string, unknown> | null = null;

    render(Page);
    await screen.findByText('Default');
    await fireEvent.click(screen.getByRole('tab', { name: 'Admin' }));
    await fireEvent.click(await screen.findByRole('button', { name: 'Nightfall 1 document' }));
    await fireEvent.click(screen.getByRole('button', { name: 'Documents' }));
    await fireEvent.click(await screen.findByRole('button', { name: 'Details' }));
    await fireEvent.click(await screen.findByRole('button', { name: 'Open document' }));
    expect(await screen.findByText('Reading revision-0')).toBeTruthy();

    await fireEvent.click(screen.getByRole('button', { name: 'Scan this page again' }));
    await fireEvent.click(await screen.findByRole('button', { name: 'Read page 1 with Surya' }));
    await waitFor(() => expect(calls.some((call) => call.url.includes('/ocr/revisions?'))).toBe(true));
    expect(await screen.findByRole('button', { name: 'Refresh to latest reading' })).toBeTruthy();
    await fireEvent.click(screen.getByRole('button', { name: 'Refresh to latest reading' }));
    expect(await screen.findByText('Reading revision-1')).toBeTruthy();
    await fireEvent.click(screen.getByRole('button', { name: 'Back to documents' }));

    await fireEvent.click(await screen.findByRole('button', { name: 'Scan again' }));
    await fireEvent.click(await screen.findByRole('button', { name: 'Read 1 page with Surya' }));
    expect(startCount).toBe(2);
    const callCountBeforeReopen = calls.length;
    await fireEvent.click(await screen.findByRole('button', { name: 'Open document' }));
    expect(await screen.findByText('Reading revision-2')).toBeTruthy();
    await waitFor(() => expect(calls.slice(callCountBeforeReopen).some((call) => call.url.endsWith('/ocr/operations'))).toBe(true));

    expect(screen.queryByRole('button', { name: 'Refresh to latest reading' })).toBeNull();
    expect(calls.slice(callCountBeforeReopen).some((call) => call.url.includes('page-review?') && call.url.includes('revision=revision-1'))).toBe(false);
    await fireEvent.click(screen.getByRole('button', { name: 'Next page' }));
    await waitFor(() => expect(calls.some((call) => call.url.includes('page-review?ordinal=1&revision=revision-2&limit=16384'))).toBe(true));
  });

  it('does not offer an old OCR candidate after History Restore made a different revision active', async () => {
    const { calls } = stubFetch({
      list: () => jsonResponse({ collections: [collection('Default'), collection('Nightfall', 1)] }),
      documents: () => jsonResponse({ documents: [{
        id: 'document-1', collectionId: 'nightfall', mediaType: 'application/pdf', originalFilename: 'quarterly.pdf',
        sizeBytes: 2048, status: 'COMPLETE', createdAt: '', updatedAt: '',
      }], total: 1 }),
      document: () => jsonResponse({ document: {
        id: 'document-1', collectionId: 'nightfall', mediaType: 'application/pdf', originalFilename: 'quarterly.pdf',
        sizeBytes: 2048, status: 'COMPLETE', createdAt: '', updatedAt: '',
      }, errorMessage: null, sourceId: 'unit-9' }),
      pageReview: (url) => {
        const request = new URL(url, window.location.origin);
        const revision = request.searchParams.get('revision') ?? activeRevisionId;
        const ordinal = Number(request.searchParams.get('ordinal') ?? 0);
        return jsonResponse(pageReviewResponse({
          revisionId: revision, ordinal, documentPages: 2,
          previousOrdinal: ordinal === 1 ? 0 : null, nextOrdinal: ordinal === 0 ? 1 : null,
          text: `Reading ${revision}`,
        }));
      },
      rescanOperations: () => jsonResponse({ operations: oldOcrOperation === null ? [] : [oldOcrOperation] }),
      documentRevisions: () => jsonResponse({
        revisions: [{ revisionId: activeRevisionId, active: true, state: 'PUBLISHED', provenance: 'RESTORE', createdAt: '', pageCount: 2 }],
        total: 2, activeRevisionId,
      }),
    });
    let activeRevisionId = 'revision-1';
    let oldOcrOperation: Record<string, unknown> | null = null;

    render(Page);
    await screen.findByText('Default');
    await fireEvent.click(screen.getByRole('tab', { name: 'Admin' }));
    await fireEvent.click(await screen.findByRole('button', { name: 'Nightfall 1 document' }));
    await fireEvent.click(screen.getByRole('button', { name: 'Documents' }));
    await fireEvent.click(await screen.findByRole('button', { name: 'Details' }));
    await fireEvent.click(await screen.findByRole('button', { name: 'Open document' }));
    expect(await screen.findByText('Reading revision-1')).toBeTruthy();
    await fireEvent.click(screen.getByRole('button', { name: 'Back to documents' }));

    // History Restore publishes a fresh active revision without creating another OCR operation.
    oldOcrOperation = {
      operationId: 'operation-old-ocr', collectionId: 'nightfall', documentId: 'document-1', snapshot: {},
      selection: { mode: 'automatic' }, managedSha256: 'hash', stage: 'COMPLETE', pageTotal: 2,
      pagesCommitted: 2, pagesFailed: 0, candidateRevisionId: 'revision-1',
      external: { distinctPages: 0, calls: 0, allowance: 0 }, requestId: 'old-request', createdAt: '', updatedAt: '',
    };
    activeRevisionId = 'revision-restored';
    const beforeReopen = calls.length;
    await fireEvent.click(await screen.findByRole('button', { name: 'Open document' }));
    expect(await screen.findByText('Reading revision-restored')).toBeTruthy();
    // The old COMPLETE OCR operation still points to revision-1; that must not be offered
    // as a new reading after Restore made revision-restored active.
    await waitFor(() => expect(calls.slice(beforeReopen).some((call) => call.url.includes('/ocr/operations'))).toBe(true));
    expect(screen.queryByRole('button', { name: 'Refresh to latest reading' })).toBeNull();
    expect(calls.slice(beforeReopen).some((call) => call.url.includes('/ocr/revisions?'))).toBe(true);
    expect(calls.slice(beforeReopen).some((call) => call.url.includes('page-review?') && call.url.includes('revision=revision-1'))).toBe(false);
    await fireEvent.click(screen.getByRole('button', { name: 'Next page' }));
    await waitFor(() => expect(calls.some((call) => call.url.includes('page-review?ordinal=1&revision=revision-restored&limit=16384'))).toBe(true));
  });

  it('returns focus to the visible Admin heading when the source opener is removed', async () => {
    stubFetch({
      list: () => jsonResponse({ collections: [collection('Default'), collection('Nightfall', 2)] }),
      documents: () => jsonResponse({
        documents: [{
          id: 'document-1', collectionId: 'nightfall', mediaType: 'application/pdf',
          originalFilename: 'quarterly.pdf', sizeBytes: 2048, status: 'COMPLETE',
          createdAt: '2026-09-21T07:00:00Z', updatedAt: '2026-09-21T07:00:00Z',
        }],
        total: 2,
      }),
      document: () => jsonResponse({
        document: {
          id: 'document-1', collectionId: 'nightfall', mediaType: 'application/pdf',
          originalFilename: 'quarterly.pdf', sizeBytes: 2048, status: 'COMPLETE',
          createdAt: '2026-09-21T07:00:00Z', updatedAt: '2026-09-21T07:00:00Z',
        },
        errorMessage: null,
        sourceId: 'unit-9',
      }),
      source: () => jsonResponse(sourcePage('Managed document text', 0, 21)),
      pageReview: () => jsonResponse(pageReviewResponse()),
    });

    render(Page);
    await screen.findByText('Default');
    await fireEvent.click(screen.getByRole('tab', { name: 'Admin' }));
    await fireEvent.click(await screen.findByRole('button', { name: 'Nightfall 2 documents' }));
    await fireEvent.click(screen.getByRole('button', { name: 'Documents' }));
    await fireEvent.click(await screen.findByRole('button', { name: 'Details' }));
    const openDocument = await screen.findByRole('button', { name: 'Open document' });
    await fireEvent.click(openDocument);
    expect(await screen.findByRole('heading', { name: 'Source' })).toBeTruthy();

    openDocument.remove();
    await fireEvent.click(screen.getByRole('button', { name: 'Back to documents' }));

    const adminHeading = screen.getByRole('heading', { name: 'Administration' });
    await waitFor(() => expect(document.activeElement).toBe(adminHeading));
  });

  it('asks the server for every eligible document of the managed collection, not the displayed page', async () => {
    const { calls } = stubFetch({
      list: () => jsonResponse({ collections: [collection('Default'), collection('Nightfall', 61)] }),
      documents: () => jsonResponse({
        documents: [{
          id: 'document-1',
          collectionId: 'nightfall',
          mediaType: 'application/pdf',
          originalFilename: 'quarterly.pdf',
          sizeBytes: 2048,
          status: 'FAILED',
          createdAt: '2026-09-21T07:00:00Z',
          updatedAt: '2026-09-21T07:00:00Z',
        }],
        total: 61,
      }),
      retry: () => jsonResponse({ collectionId: 'nightfall', acceptedJobIds: ['job-3'], rejected: [] }, 202),
    });

    render(Page);
    await screen.findByText('Default');
    await fireEvent.click(screen.getByRole('tab', { name: 'Admin' }));
    await fireEvent.click(await screen.findByRole('button', { name: 'Nightfall 61 documents' }));
    await fireEvent.click(screen.getByRole('button', { name: 'Documents' }));
    await fireEvent.click(await screen.findByRole('button', { name: 'Retry all eligible documents' }));

    // Collection-wide selection is the server's to make: the request names the collection and asks for every
    // eligible document, never the ids of the page the table happens to show.
    await waitFor(() => {
      expect(calls.some((call) => call.url === '/api/collections/nightfall/documents/retry')).toBe(true);
    });
    const retry = calls.find((call) => call.url === '/api/collections/nightfall/documents/retry');
    expect(retry?.init?.method).toBe('POST');
    expect(JSON.parse(String(retry?.init?.body))).toEqual({ allEligible: true, requestId: expect.any(String) });
    const outcome = await screen.findByRole('region', { name: 'Retry documents in Nightfall' });
    await waitFor(() => {
      expect(within(outcome).getByRole('status').textContent)
        .toContain('Retry queued for every eligible document in Nightfall');
    });
  });

  it('closes the viewer and drops the collection when Admin deletes it', async () => {
    let deleted = false;
    const { calls } = stubFetch({
      list: () => jsonResponse({
        collections: deleted
          ? [collection('Default', 0)]
          : [collection('Default', 0), collection('Nightfall', 1)],
      }),
      documents: () => jsonResponse({
        documents: [{
          id: 'document-1',
          collectionId: 'nightfall',
          mediaType: 'application/pdf',
          originalFilename: 'quarterly.pdf',
          sizeBytes: 2048,
          status: 'COMPLETE',
          createdAt: '2026-09-21T07:00:00Z',
          updatedAt: '2026-09-21T07:00:00Z',
        }],
        total: 1,
      }),
      document: () => jsonResponse({
        document: {
          id: 'document-1',
          collectionId: 'nightfall',
          mediaType: 'application/pdf',
          originalFilename: 'quarterly.pdf',
          sizeBytes: 2048,
          status: 'COMPLETE',
          createdAt: '2026-09-21T07:00:00Z',
          updatedAt: '2026-09-21T07:00:00Z',
        },
        errorMessage: null,
        sourceId: 'unit-9',
      }),
      source: () => jsonResponse(sourcePage('Managed document text', 0, 21)),
      pageReview: () => jsonResponse(pageReviewResponse()),
      deleteCollection: () => {
        deleted = true;
        return jsonResponse({ operationId: 'op-1', collectionId: 'nightfall', phase: 'PREPARED' }, 202);
      },
    });

    render(Page);
    await screen.findByText('Default');
    await fireEvent.click(screen.getByRole('tab', { name: 'Admin' }));
    await fireEvent.click(await screen.findByRole('button', { name: 'Nightfall 1 document' }));
    await fireEvent.click(screen.getByRole('button', { name: 'Documents' }));
    await fireEvent.click(await screen.findByRole('button', { name: 'Details' }));
    await fireEvent.click(await screen.findByRole('button', { name: 'Open document' }));
    expect(await screen.findByRole('heading', { name: 'Source' })).toBeTruthy();
    await fireEvent.click(screen.getByRole('button', { name: 'Close source viewer' }));

    await fireEvent.click(screen.getByText('Settings', { selector: 'summary' }));
    await fireEvent.click(screen.getByRole('button', { name: 'Delete collection' }));
    await fireEvent.input(
      screen.getByLabelText('Type the collection name to confirm'),
      { target: { value: 'Nightfall' } },
    );
    await fireEvent.click(
      within(screen.getByRole('dialog', { name: 'Delete Nightfall?' })).getByRole('button', { name: 'Delete collection' }),
    );

    // The finished deletion closes the viewer bound to the removed collection and refreshes both lists.
    await waitFor(() => expect(screen.queryByRole('heading', { name: 'Source' })).toBeNull());
    await waitFor(() => {
      const options = within(screen.getByLabelText('Collection')).getAllByRole('option');
      expect(options.map((option) => option.textContent)).toEqual(['Default']);
    });
    expect(screen.queryByRole('button', { name: 'Nightfall 1 document' })).toBeNull();
    expect(calls.some((call) => call.url === '/api/collections/nightfall' && call.init?.method === 'DELETE')).toBe(true);
  });

  it('drops the deleted collection’s results when admitting the deletion already moved the selection', async () => {
    let deleted = false;
    stubFetch({
      list: () => jsonResponse({
        collections: deleted
          ? [collection('Default', 0)]
          : [collection('Default', 0), collection('Nightfall', 1)],
      }),
      search: () => jsonResponse({ hits: [hit('Nightfall', 'Page 7')], staleFiltered: 0 }),
      documents: () => jsonResponse({ documents: [], total: 0 }),
      deleteCollection: () => {
        deleted = true;
        return jsonResponse({ operationId: 'op-1', collectionId: 'nightfall', phase: 'PREPARED' }, 202);
      },
      // The deletion is still running when this checks, so only the admission's own refresh can move the
      // workspace selection: nothing here waits for the completion event to clean up.
      deletion: () => jsonResponse({
        operation: {
          operationId: 'op-1',
          kind: 'COLLECTION',
          collectionId: 'nightfall',
          collectionName: 'Nightfall',
          documentIds: [],
          phase: 'PREPARED',
          terminal: false,
          errorCode: null,
        },
      }),
    });

    render(Page);
    await screen.findByText('Default');
    await fireEvent.change(screen.getByLabelText('Collection'), { target: { value: 'nightfall' } });
    await fireEvent.input(screen.getByLabelText('Search query'), { target: { value: 'payment records' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Search' }));
    expect(await screen.findByText('Page 7')).toBeTruthy();

    await fireEvent.click(screen.getByRole('tab', { name: 'Admin' }));
    await fireEvent.click(await screen.findByRole('button', { name: 'Nightfall 1 document' }));
    await fireEvent.click(screen.getByText('Settings', { selector: 'summary' }));
    await fireEvent.click(screen.getByRole('button', { name: 'Delete collection' }));
    await fireEvent.input(
      screen.getByLabelText('Type the collection name to confirm'),
      { target: { value: 'Nightfall' } },
    );
    await fireEvent.click(
      within(screen.getByRole('dialog', { name: 'Delete Nightfall?' })).getByRole('button', { name: 'Delete collection' }),
    );

    // Admitting the deletion refreshes the list, which moves the workspace selection to the collection
    // that is left; the rows the search drew from the deleted one go before that, not after the deletion
    // is done.
    await waitFor(() => expect((screen.getByLabelText('Collection') as HTMLSelectElement).value).toBe('default'));
    await fireEvent.click(screen.getByRole('tab', { name: 'Search' }));
    expect(screen.queryByText('Page 7')).toBeNull();
    expect(screen.queryByRole('list', { name: 'Search results' })).toBeNull();
  });

  it('shows the managed collection\u2019s durable import history from the server', async () => {
    const { calls } = stubFetch({
      list: () => jsonResponse({ collections: [collection('Default'), collection('Nightfall', 1)] }),
      imports: () => jsonResponse({
        imports: [{
          id: 'job-1',
          state: 'RUNNING',
          stage: 'COPYING',
          filesCompleted: 1,
          filesTotal: 4,
          createdAt: '2026-09-21T07:00:00Z',
          updatedAt: '2026-09-21T07:00:01Z',
          itemsUrl: '/api/jobs/job-1/items',
        }],
        total: 1,
      }),
    });

    render(Page);
    await screen.findByText('Default');
    await fireEvent.click(screen.getByRole('tab', { name: 'Admin' }));
    await fireEvent.click(await screen.findByRole('button', { name: 'Nightfall 1 document' }));

    await fireEvent.click(screen.getByText('Import history', { selector: 'summary' }));
    const history = await screen.findByRole('region', { name: 'Import history for Nightfall' });
    expect(await within(history).findByText('1 of 4 files')).toBeTruthy();
    // The history read names the collection Admin manages, and carries no source path or payload.
    expect(calls.some((call) => call.url === '/api/collections/nightfall/imports?limit=50')).toBe(true);
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

  it('opens a search hit at the revision the hit names', async () => {
    const { calls } = stubFetch({
      list: () => jsonResponse({ collections: [collection('Default')] }),
      search: () => jsonResponse({ hits: [{ ...hit('Default', 'Page 12'), revisionId: 'revision-7' }] }),
      source: () => jsonResponse(sourcePage('Extracted page text', 0, 16)),
    });
    render(Page);
    await screen.findByText('Default');
    await fireEvent.input(screen.getByLabelText('Search query'), { target: { value: 'tax records' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Search' }));
    await fireEvent.click(await screen.findByRole('button', { name: /Default.*Page 12/s }));

    expect(await screen.findByText('Extracted page text')).toBeTruthy();
    // The hit's own reading is what the viewer asks for, so a replacement cannot show this hit's text
    // under the wording the document publishes now.
    expect(
      calls.some((call) => call.url === '/api/collections/default/sources/unit-1?offset=0&limit=16384&revision=revision-7'),
    ).toBe(true);
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

  it('steps through the pages of a document with previous and next', async () => {
    const pages: Record<string, ReturnType<typeof sourcePage>> = {
      'unit-1': { ...sourcePage('Page one text', 0, 13), position: 1, unitCount: 2, nextId: 'unit-2' } as never,
      'unit-2': { ...sourcePage('Page two text', 0, 13), id: 'unit-2', position: 2, unitCount: 2, previousId: 'unit-1' } as never,
    };
    const { calls } = stubFetch({
      list: () => jsonResponse({ collections: [collection('Default')] }),
      search: () => jsonResponse({ hits: [hit('Default', 'Page 1')] }),
      source: (url: string) => jsonResponse(pages[url.includes('unit-2') ? 'unit-2' : 'unit-1']),
    });
    render(Page);
    await screen.findByText('Default');
    await fireEvent.input(screen.getByLabelText('Search query'), { target: { value: 'tax records' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Search' }));
    await fireEvent.click(await screen.findByRole('button', { name: /Default.*Page 1/s }));

    expect(await screen.findByText('Page 1 of 2')).toBeTruthy();
    expect((screen.getByRole('button', { name: 'Previous' }) as HTMLButtonElement).disabled).toBe(true);
    await fireEvent.click(screen.getByRole('button', { name: 'Next' }));

    expect(await screen.findByText('Page two text')).toBeTruthy();
    expect(screen.getByText('Page 2 of 2')).toBeTruthy();
    expect((screen.getByRole('button', { name: 'Next' }) as HTMLButtonElement).disabled).toBe(true);
    expect(calls.some((call) => call.url.startsWith('/api/collections/default/sources/unit-2?'))).toBe(true);

    await fireEvent.click(screen.getByRole('button', { name: 'Previous' }));
    expect(await screen.findByText('Page one text')).toBeTruthy();
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

  it('shows a nonmodal source beside Search and returns focus to the citation on close', async () => {
    stubFetch({
      list: () => jsonResponse({ collections: [collection('Default')] }),
      search: () => jsonResponse({ hits: [hit('Default', 'Page 12')] }),
      source: () => jsonResponse(sourcePage('Extracted page text', 0, 16)),
    });
    render(Page);
    await screen.findByText('Default');
    await fireEvent.input(screen.getByLabelText('Search query'), { target: { value: 'tax records' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Search' }));

    await fireEvent.click(await screen.findByRole('button', { name: /Default.*Page 12/s }));

    const sourcePane = await screen.findByRole('region', { name: 'Source' });
    expect(sourcePane.getAttribute('aria-modal')).toBeNull();
    expect(sourcePane.getAttribute('aria-labelledby')).toBe('source-heading');
    expect(document.querySelector('.source-backdrop')).toBeNull();
    expect(document.querySelector('.app-shell')?.classList.contains('source-open')).toBe(true);
    expect(screen.getByRole('list', { name: 'Search results' })).toBeTruthy();
    expect(await screen.findByText('Extracted page text')).toBeTruthy();
    expect((screen.getByLabelText('Search query') as HTMLInputElement).value).toBe('tax records');

    await fireEvent.click(screen.getByRole('button', { name: 'Close source viewer' }));
    await waitFor(() => expect(screen.queryByRole('region', { name: 'Source' })).toBeNull());
    expect(document.activeElement).toBe(screen.getByRole('button', { name: /Default.*Page 12/s }));
  });

  it('offers Back in the narrow source layout and keeps the selected source available', async () => {
    const previousWidth = window.innerWidth;
    Object.defineProperty(window, 'innerWidth', { configurable: true, value: 390 });
    stubFetch({
      list: () => jsonResponse({ collections: [collection('Default')] }),
      search: () => jsonResponse({ hits: [hit('Default', 'Page 12')] }),
      source: () => jsonResponse(sourcePage('Extracted page text', 0, 16)),
    });
    render(Page);
    await screen.findByText('Default');
    await fireEvent.input(screen.getByLabelText('Search query'), { target: { value: 'tax records' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Search' }));
    await fireEvent.click(await screen.findByRole('button', { name: /Default.*Page 12/s }));

    expect(await screen.findByRole('button', { name: 'Back to results' })).toBeTruthy();
    await fireEvent.click(screen.getByRole('button', { name: 'Back to results' }));
    expect(await screen.findByRole('button', { name: 'Open selected source' })).toBeTruthy();
    expect(screen.getByRole('button', { name: /Default.*Page 12/s }).getAttribute('aria-pressed')).toBe('true');
    await fireEvent.click(screen.getByRole('button', { name: 'Open selected source' }));
    expect(await screen.findByText('Extracted page text')).toBeTruthy();
    Object.defineProperty(window, 'innerWidth', { configurable: true, value: previousWidth });
  });

  it('moves focus into the source sheet when it opens', async () => {
    stubFetch({
      list: () => jsonResponse({ collections: [collection('Default')] }),
      search: () => jsonResponse({ hits: [hit('Default', 'Page 12')] }),
      source: () => jsonResponse(sourcePage('Extracted page text', 0, 16)),
    });
    render(Page);
    await screen.findByText('Default');
    await fireEvent.input(screen.getByLabelText('Search query'), { target: { value: 'tax records' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Search' }));

    const result = await screen.findByRole('button', { name: /Default.*Page 12/s });
    result.focus();
    await fireEvent.click(result);

    const dialog = await screen.findByRole('region', { name: 'Source' });
    await waitFor(() => expect(dialog.contains(document.activeElement)).toBe(true));
  });

  it('closes the source sheet on Escape and returns focus to the opening citation', async () => {
    stubFetch({
      list: () => jsonResponse({ collections: [collection('Default')] }),
      search: () => jsonResponse({ hits: [hit('Default', 'Page 12')] }),
      source: () => jsonResponse(sourcePage('Extracted page text', 0, 16)),
    });
    render(Page);
    await screen.findByText('Default');
    await fireEvent.input(screen.getByLabelText('Search query'), { target: { value: 'tax records' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Search' }));

    const result = await screen.findByRole('button', { name: /Default.*Page 12/s });
    result.focus();
    await fireEvent.click(result);
    const dialog = await screen.findByRole('region', { name: 'Source' });
    await waitFor(() => expect(dialog.contains(document.activeElement)).toBe(true));

    await fireEvent.keyDown(dialog, { key: 'Escape' });

    await waitFor(() => expect(screen.queryByRole('region', { name: 'Source' })).toBeNull());
    expect(screen.getByRole('list', { name: 'Search results' })).toBeTruthy();
    expect(document.activeElement).toBe(result);
  });

  it('closes the source sheet from its close button and returns focus to the opening citation', async () => {
    stubFetch({
      list: () => jsonResponse({ collections: [collection('Default')] }),
      search: () => jsonResponse({ hits: [hit('Default', 'Page 12')] }),
      source: () => jsonResponse(sourcePage('Extracted page text', 0, 16)),
    });
    render(Page);
    await screen.findByText('Default');
    await fireEvent.input(screen.getByLabelText('Search query'), { target: { value: 'tax records' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Search' }));

    const result = await screen.findByRole('button', { name: /Default.*Page 12/s });
    result.focus();
    await fireEvent.click(result);
    await screen.findByRole('region', { name: 'Source' });

    await fireEvent.click(await screen.findByRole('button', { name: 'Close source viewer' }));

    await waitFor(() => expect(screen.queryByRole('region', { name: 'Source' })).toBeNull());
    expect(document.activeElement).toBe(result);
  });

  it('returns focus to the active view heading when the source opener was removed', async () => {
    stubFetch({
      list: () => jsonResponse({ collections: [collection('Default')] }),
      search: () => jsonResponse({ hits: [hit('Default', 'Page 12')] }),
      source: () => jsonResponse(sourcePage('Extracted page text', 0, 16)),
    });
    render(Page);
    await screen.findByText('Default');
    await fireEvent.input(screen.getByLabelText('Search query'), { target: { value: 'tax records' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Search' }));
    const result = await screen.findByRole('button', { name: /Default.*Page 12/s });
    await fireEvent.click(result);
    await screen.findByRole('region', { name: 'Source' });
    result.remove();

    await fireEvent.click(screen.getByRole('button', { name: 'Close source viewer' }));

    await waitFor(() => expect(document.activeElement).toBe(screen.getByRole('heading', { name: 'Search your archive' })));
  });

  it('keeps the workspace available without a backdrop while the source is open', async () => {
    stubFetch({
      list: () => jsonResponse({ collections: [collection('Default')] }),
      search: () => jsonResponse({ hits: [hit('Default', 'Page 12')] }),
      source: () => jsonResponse(sourcePage('Extracted page text', 0, 16)),
    });
    render(Page);
    await screen.findByText('Default');
    await fireEvent.input(screen.getByLabelText('Search query'), { target: { value: 'tax records' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Search' }));

    await fireEvent.click(await screen.findByRole('button', { name: /Default.*Page 12/s }));
    await screen.findByRole('region', { name: 'Source' });

    expect(document.querySelector('.source-backdrop')).toBeNull();
    expect(screen.getByRole('list', { name: 'Search results' })).toBeTruthy();
    expect(screen.getByLabelText('Search query')).toBeTruthy();
  });

  it('does not trap keyboard focus in the nonmodal source pane', async () => {
    stubFetch({
      list: () => jsonResponse({ collections: [collection('Default')] }),
      search: () => jsonResponse({ hits: [hit('Default', 'Page 12')] }),
      source: () => jsonResponse(sourcePage('Extracted page text', 0, 400, true)),
    });
    render(Page);
    await screen.findByText('Default');
    await fireEvent.input(screen.getByLabelText('Search query'), { target: { value: 'tax records' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Search' }));

    await fireEvent.click(await screen.findByRole('button', { name: /Default.*Page 12/s }));
    const pane = await screen.findByRole('region', { name: 'Source' });
    await waitFor(() => expect(pane.contains(document.activeElement)).toBe(true));
    const eventWasNotCancelled = await fireEvent.keyDown(pane, { key: 'Tab' });
    expect(eventWasNotCancelled).toBe(true);
    expect(screen.getByLabelText('Search query')).toBeTruthy();
  });

  it('closes the source sheet without returning focus when the workspace mode changes by mouse', async () => {
    stubFetch({
      list: () => jsonResponse({ collections: [collection('Default')] }),
      search: () => jsonResponse({ hits: [hit('Default', 'Page 12')] }),
      source: () => jsonResponse(sourcePage('Extracted page text', 0, 16)),
    });
    render(Page);
    await screen.findByText('Default');
    await fireEvent.input(screen.getByLabelText('Search query'), { target: { value: 'tax records' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Search' }));

    const result = await screen.findByRole('button', { name: /Default.*Page 12/s });
    result.focus();
    const focusSpy = vi.spyOn(result, 'focus');
    await fireEvent.click(result);
    await screen.findByRole('region', { name: 'Source' });
    focusSpy.mockClear();

    await fireEvent.click(screen.getByRole('tab', { name: 'Ask' }));

    await waitFor(() => expect(screen.queryByRole('region', { name: 'Source' })).toBeNull());
    // The reader chose to navigate; the sheet must not move focus back to the opening citation.
    expect(focusSpy).not.toHaveBeenCalled();
    expect(screen.getByRole('tab', { name: 'Ask' }).getAttribute('aria-selected')).toBe('true');
  });

  it('closes the source sheet on keyboard workspace navigation and keeps focus on the tab', async () => {
    stubFetch({
      list: () => jsonResponse({ collections: [collection('Default')] }),
      search: () => jsonResponse({ hits: [hit('Default', 'Page 12')] }),
      source: () => jsonResponse(sourcePage('Extracted page text', 0, 16)),
    });
    render(Page);
    await screen.findByText('Default');
    await fireEvent.input(screen.getByLabelText('Search query'), { target: { value: 'tax records' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Search' }));

    const result = await screen.findByRole('button', { name: /Default.*Page 12/s });
    result.focus();
    await fireEvent.click(result);
    await screen.findByRole('region', { name: 'Source' });

    await fireEvent.keyDown(screen.getByRole('tablist', { name: 'Workspace mode' }), { key: 'ArrowRight' });

    await waitFor(() => expect(screen.queryByRole('region', { name: 'Source' })).toBeNull());
    expect(document.activeElement).not.toBe(result);
    expect(document.activeElement).toBe(screen.getByRole('tab', { name: 'Ask' }));
    expect(screen.getByRole('tab', { name: 'Ask' }).getAttribute('aria-selected')).toBe('true');
  });

  it('clears the remembered Ask selection when a new Ask begins', async () => {
    window.localStorage.setItem('infoscry-history:ask:default', 'ask-1');
    stubFetch({
      list: () => jsonResponse({ collections: [collection('Default')] }),
      asks: () => jsonResponse({ asks: [askHistoryEntry('ask-1', 'The signer', 'Who signed it?')] }),
      ask: () => eventResponse([
        { type: 'delta', text: 'Partial answer' },
        { type: 'error', code: 'PROVIDER_FAILED', message: 'provider unavailable' },
      ]),
    });
    render(Page);
    await screen.findByText('Default');
    await fireEvent.click(screen.getByRole('tab', { name: 'Ask' }));
    const column = await screen.findByRole('navigation', { name: 'Ask history' });
    expect(await within(column).findByRole('button', { name: 'The signer', current: true })).toBeTruthy();

    await fireEvent.input(screen.getByLabelText('Question', { selector: '#question' }), { target: { value: 'What happened?' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Ask' }));

    // A fresh Ask must not leave the old row marked under a partial answer that was never stored.
    await waitFor(() => expect(window.localStorage.getItem('infoscry-history:ask:default')).toBeNull());
    expect(within(column).queryByRole('button', { name: 'The signer', current: true })).toBeNull();
    expect(await screen.findByText('Partial answer')).toBeTruthy();
  });

  it('locks the history column while an Investigate turn runs and unlocks it at done', async () => {
    let releaseDone!: () => void;
    const doneGate = new Promise<void>((resolve) => { releaseDone = resolve; });
    const encoder = new TextEncoder();
    stubFetch({
      list: () => jsonResponse({ collections: [collection('Default')] }),
      investigations: () => jsonResponse({ investigations: [investigationSummary('conv-1', 'The treaty')] }),
      investigation: () => jsonResponse({ investigation: investigationHistory('conv-1', 'Who signed it?', 'Mira signed it.') }),
      investigateStart: () => ({
        ok: true,
        status: 200,
        statusText: '',
        body: new ReadableStream<Uint8Array>({
          async start(controller) {
            controller.enqueue(encoder.encode(`data: ${JSON.stringify({ type: 'started', id: 'conv-2' })}\n\n`));
            await doneGate;
            controller.enqueue(encoder.encode(`data: ${JSON.stringify({ type: 'done', text: 'First answer', evidence: [] })}\n\n`));
            controller.close();
          },
        }),
      } as Response),
    });
    render(Page);
    await screen.findByText('Default');
    await fireEvent.click(screen.getByRole('tab', { name: 'Investigate' }));
    const column = await screen.findByRole('navigation', { name: 'Conversation history' });
    await within(column).findByRole('button', { name: 'The treaty' });
    const row = () => within(column).getByRole('button', { name: 'The treaty' });

    await fireEvent.input(screen.getByLabelText('Investigate question'), { target: { value: 'What happened?' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Investigate' }));

    // A live turn locks selection, New and delete; they unlock once done arrives, even while the
    // title call keeps the stream open.
    await waitFor(() => expect(row().getAttribute('disabled')).not.toBeNull());
    expect(within(column).getByRole('button', { name: 'New conversation' }).getAttribute('disabled')).not.toBeNull();
    expect(within(column).getByRole('button', { name: 'Delete conversation The treaty' }).getAttribute('disabled')).not.toBeNull();

    releaseDone();
    await waitFor(() => expect(row().getAttribute('disabled')).toBeNull());
    expect(within(column).getByRole('button', { name: 'New conversation' }).getAttribute('disabled')).toBeNull();
    expect(within(column).getByRole('button', { name: 'Delete conversation The treaty' }).getAttribute('disabled')).toBeNull();
  });

  it('sends and remembers effort, displays server caps and clears them when the selection changes', async () => {
    const { calls } = stubFetch({
      list: () => jsonResponse({ collections: [collection('Default')] }),
      investigateStart: () => eventResponse([
        { type: 'started', id: 'effort-conv', resolvedLimits: { maxToolRounds: 3, maxToolCalls: 7, maxTurnSeconds: 90 } },
        { type: 'done', text: 'Answer', evidence: [] },
      ]),
    });
    render(Page);
    await screen.findByText('Default');
    await fireEvent.click(screen.getByRole('tab', { name: 'Investigate' }));
    const effort = screen.getByLabelText('Effort level') as HTMLSelectElement;
    expect([...effort.options].map(o => o.value)).toEqual(['LOW', 'MEDIUM', 'HIGH', 'XHIGH']);
    expect(effort.value).toBe('MEDIUM');
    expect(screen.queryByText(/Up to .* tool rounds/)).toBeNull();
    await fireEvent.change(effort, { target: { value: 'HIGH' } });
    expect(JSON.parse(localStorage.getItem('infoscry-investigate-effort:v1')!)).toBe('HIGH');
    await fireEvent.input(screen.getByLabelText('Investigate question'), { target: { value: 'What happened?' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Investigate' }));
    await screen.findByText('Up to 3 tool rounds, 7 tool calls, 90 seconds per question.');
    expect(JSON.parse(String(calls.find(c => c.url === '/api/investigations')?.init?.body)).limits).toEqual({ effortLevel: 'HIGH' });
    await fireEvent.change(effort, { target: { value: 'LOW' } });
    expect(screen.queryByText(/Up to .* tool rounds/)).toBeNull();
    await fireEvent.click(screen.getByRole('button', { name: 'Reset defaults' }));
    expect(effort.value).toBe('MEDIUM');
  });

  it('ignores old numeric preferences and restores the new effort preference', async () => {
    localStorage.setItem('infoscry-investigate-limits:v1', JSON.stringify({ maxToolRounds: 2 }));
    localStorage.setItem('infoscry-investigate-effort:v1', JSON.stringify('XHIGH'));
    stubFetch({ list: () => jsonResponse({ collections: [collection('Default')] }) });
    render(Page);
    await screen.findByText('Default');
    await fireEvent.click(screen.getByRole('tab', { name: 'Investigate' }));
    expect((screen.getByLabelText('Effort level') as HTMLSelectElement).value).toBe('XHIGH');
  });

  it('disables effort while a turn is running', async () => {
    let releaseDone!: () => void;
    const doneGate = new Promise<void>(resolve => { releaseDone = resolve; });
    const encoder = new TextEncoder();
    stubFetch({
      list: () => jsonResponse({ collections: [collection('Default')] }),
      investigateStart: () => ({ ok: true, status: 200, statusText: '', body: new ReadableStream<Uint8Array>({
        async start(controller) {
          controller.enqueue(encoder.encode('data: {"type":"started","id":"conv-2"}\n\n'));
          await doneGate;
          controller.enqueue(encoder.encode('data: {"type":"done","text":"Answer","evidence":[]}\n\n'));
          controller.close();
        },
      }) } as Response),
    });
    render(Page);
    await screen.findByText('Default');
    await fireEvent.click(screen.getByRole('tab', { name: 'Investigate' }));
    await fireEvent.input(screen.getByLabelText('Investigate question'), { target: { value: 'What happened?' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Investigate' }));
    await waitFor(() => expect((screen.getByLabelText('Effort level') as HTMLSelectElement).disabled).toBe(true));
    releaseDone();
    await waitFor(() => expect((screen.getByLabelText('Effort level') as HTMLSelectElement).disabled).toBe(false));
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
    expect(document.querySelector('.app-shell')?.classList.contains('source-open')).toBe(true);
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

  it('lists investigate conversations newest first with the open row marked', async () => {
    window.localStorage.setItem('infoscry-history:investigate:default', 'conv-2');
    stubFetch({
      list: () => jsonResponse({ collections: [collection('Default')] }),
      investigations: () => jsonResponse({
        investigations: [
          investigationSummary('conv-3', 'Third conversation'),
          investigationSummary('conv-2', 'Second conversation'),
          investigationSummary('conv-1', 'First conversation'),
        ],
      }),
      investigation: () => jsonResponse({ investigation: investigationHistory('conv-2', 'Who signed it?', 'Mira signed it [S2]') }),
    });

    render(Page);
    await screen.findByText('Default');
    await fireEvent.click(screen.getByRole('tab', { name: 'Investigate' }));

    const column = await screen.findByRole('navigation', { name: 'Conversation history' });
    expect(within(column).getAllByRole('listitem').map((row) => row.querySelector('.history-row')?.textContent))
      .toEqual(['Third conversation', 'Second conversation', 'First conversation']);
    expect(within(column).getByRole('button', { name: 'Second conversation', current: true })).toBeTruthy();
    expect(within(column).getByRole('button', { name: 'Third conversation', current: false })).toBeTruthy();
    expect(within(column).getByRole('button', { name: 'Delete conversation Third conversation' })).toBeTruthy();
  });

  it('falls back to the opening question when a conversation has no title', async () => {
    stubFetch({
      list: () => jsonResponse({ collections: [collection('Default')] }),
      investigations: () => jsonResponse({ investigations: [investigationSummary('conv-1', '', 'Who signed it?')] }),
    });

    render(Page);
    await screen.findByText('Default');
    await fireEvent.click(screen.getByRole('tab', { name: 'Investigate' }));

    expect(await screen.findByRole('button', { name: 'Who signed it?' })).toBeTruthy();
  });

  it('opens a clicked conversation row in the panel with its evidence, activity, tokens and cost', async () => {
    const { calls } = stubFetch({
      list: () => jsonResponse({ collections: [collection('Default')] }),
      investigations: () => jsonResponse({ investigations: [investigationSummary('conv-1', 'The treaty')] }),
      investigation: () => jsonResponse({ investigation: investigationHistory('conv-1', 'Who signed it?', 'Mira signed it [S2]') }),
      source: () => jsonResponse(sourcePage('Investigate source body', 0, 23)),
    });

    render(Page);
    await screen.findByText('Default');
    await fireEvent.click(screen.getByRole('tab', { name: 'Investigate' }));
    await fireEvent.click(await screen.findByRole('button', { name: 'The treaty' }));

    expect(await screen.findByText('Who signed it?')).toBeTruthy();
    expect(document.querySelector('.answer')?.textContent).toContain('Mira signed it [S2]');
    expect(screen.getByRole('button', { name: 'Open source S2, Page 8' })).toBeTruthy();
    expect(screen.getByText(/1 tool call/)).toBeTruthy();
    expect(screen.getByText(/20 input tokens/)).toBeTruthy();
    expect(screen.getByText(/Estimated cost/).textContent).toContain('$0.0001');
    expect(calls.some((call) => call.url === '/api/collections/default/investigations/conv-1')).toBe(true);
    expect(screen.getByRole('button', { name: 'The treaty', current: true })).toBeTruthy();
    await fireEvent.click(screen.getByRole('button', { name: 'Open source S2, Page 8' }));
    expect(await screen.findByText('Investigate source body')).toBeTruthy();
    expect(document.querySelector('.app-shell')?.classList.contains('source-open')).toBe(true);
  });

  it('clears the panel to its empty compose state with the New conversation button', async () => {
    stubFetch({
      list: () => jsonResponse({ collections: [collection('Default')] }),
      investigations: () => jsonResponse({ investigations: [investigationSummary('conv-1', 'The treaty')] }),
      investigation: () => jsonResponse({ investigation: investigationHistory('conv-1', 'Who signed it?', 'Mira signed it.') }),
    });

    render(Page);
    await screen.findByText('Default');
    await fireEvent.click(screen.getByRole('tab', { name: 'Investigate' }));
    await fireEvent.click(await screen.findByRole('button', { name: 'The treaty' }));
    expect(await screen.findByText('Who signed it?')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Send follow-up' })).toBeTruthy();

    await fireEvent.click(screen.getByRole('button', { name: 'New conversation' }));

    expect(screen.queryByText('Who signed it?')).toBeNull();
    expect(screen.getByRole('button', { name: 'Investigate' })).toBeTruthy();
    expect((screen.getByLabelText('Investigate question') as HTMLTextAreaElement).value).toBe('');
    expect(window.localStorage.getItem('infoscry-history:investigate:default')).toBeNull();
  });

  it('shows the column empty state, distinct from a loading state', async () => {
    let finishList!: (response: Response) => void;
    stubFetch({
      list: () => jsonResponse({ collections: [collection('Default')] }),
      investigations: () => new Promise<Response>((resolve) => { finishList = resolve; }),
    });

    render(Page);
    await screen.findByText('Default');
    await fireEvent.click(screen.getByRole('tab', { name: 'Investigate' }));

    expect(await screen.findByText('Loading conversations…')).toBeTruthy();
    expect(screen.queryByText('No conversations yet.')).toBeNull();
    finishList(jsonResponse({ investigations: [] }));
    expect(await screen.findByText('No conversations yet.')).toBeTruthy();
    expect(screen.queryByText('Loading conversations…')).toBeNull();
  });

  it('renders the history column only in the Ask and Investigate views', async () => {
    stubFetch({
      list: () => jsonResponse({ collections: [collection('Default')] }),
      presets: () => jsonResponse({ presets: [] }),
      asks: () => jsonResponse({ asks: [askHistoryEntry('ask-1', 'The signer')] }),
    });

    render(Page);
    await screen.findByText('Default');
    expect(screen.queryByRole('navigation', { name: 'Conversation history' })).toBeNull();
    expect(screen.queryByRole('navigation', { name: 'Ask history' })).toBeNull();

    await fireEvent.click(screen.getByRole('tab', { name: 'Admin' }));
    expect(screen.queryByRole('navigation', { name: 'Conversation history' })).toBeNull();
    expect(screen.queryByRole('navigation', { name: 'Ask history' })).toBeNull();

    await fireEvent.click(screen.getByRole('tab', { name: 'Investigate' }));
    expect(await screen.findByRole('navigation', { name: 'Conversation history' })).toBeTruthy();

    await fireEvent.click(screen.getByRole('tab', { name: 'Ask' }));
    expect(await screen.findByRole('navigation', { name: 'Ask history' })).toBeTruthy();
    expect(screen.queryByRole('navigation', { name: 'Conversation history' })).toBeNull();

    await fireEvent.click(screen.getByRole('tab', { name: 'Search' }));
    expect(screen.queryByRole('navigation', { name: 'Conversation history' })).toBeNull();
    expect(screen.queryByRole('navigation', { name: 'Ask history' })).toBeNull();
  });

  it('restores the remembered open conversation across a reload', async () => {
    window.localStorage.setItem('infoscry-history:investigate:default', 'saved-1');
    stubFetch({
      list: () => jsonResponse({ collections: [collection('Default')] }),
      investigations: () => jsonResponse({ investigations: [investigationSummary('saved-1', 'The treaty')] }),
      investigation: () => jsonResponse({ investigation: investigationHistory('saved-1', 'Who signed it?', 'Mira signed it [S2]') }),
    });

    render(Page);
    await screen.findByText('Default');
    await fireEvent.click(screen.getByRole('tab', { name: 'Investigate' }));

    expect(await screen.findByText('Who signed it?')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'The treaty', current: true })).toBeTruthy();
  });

  it('switching collections shows that collection remembered conversation and never another', async () => {
    window.localStorage.setItem('infoscry-history:investigate:default', 'conv-default');
    window.localStorage.setItem('infoscry-history:investigate:nightfall', 'conv-nightfall');
    stubFetch({
      list: () => jsonResponse({ collections: [collection('Default'), collection('Nightfall')] }),
      investigations: (url) => url.endsWith('/default/investigations')
        ? jsonResponse({ investigations: [investigationSummary('conv-default', 'The treaty')] })
        : jsonResponse({ investigations: [investigationSummary('conv-nightfall', 'Night watch')] }),
      investigation: (url) => url.includes('/investigations/conv-nightfall')
        ? jsonResponse({ investigation: investigationHistory('conv-nightfall', 'What happens at night?', 'The guard patrols.') })
        : jsonResponse({ investigation: investigationHistory('conv-default', 'Who signed it?', 'Mira signed it.') }),
    });

    render(Page);
    await screen.findByText('Default');
    await fireEvent.click(screen.getByRole('tab', { name: 'Investigate' }));
    expect(await screen.findByText('Mira signed it.')).toBeTruthy();
    expect(screen.queryByText('The guard patrols.')).toBeNull();

    await fireEvent.change(screen.getByLabelText('Collection'), { target: { value: 'nightfall' } });

    expect(await screen.findByText('The guard patrols.')).toBeTruthy();
    expect(screen.queryByText('Mira signed it.')).toBeNull();
    const column = await screen.findByRole('navigation', { name: 'Conversation history' });
    expect(within(column).getByRole('button', { name: 'Night watch', current: true })).toBeTruthy();
    expect(within(column).queryByRole('button', { name: 'The treaty' })).toBeNull();
  });

  it('lists Ask answers newest first with the remembered open row marked', async () => {
    window.localStorage.setItem('infoscry-history:ask:default', 'ask-2');
    stubFetch({
      list: () => jsonResponse({ collections: [collection('Default')] }),
      asks: () => jsonResponse({ asks: [
        askHistoryEntry('ask-3', 'Third question'),
        askHistoryEntry('ask-2', 'Second question'),
        askHistoryEntry('ask-1', 'First question'),
      ] }),
    });

    render(Page);
    await screen.findByText('Default');
    await fireEvent.click(screen.getByRole('tab', { name: 'Ask' }));

    const column = await screen.findByRole('navigation', { name: 'Ask history' });
    expect((await within(column).findAllByRole('listitem')).map((row) => row.querySelector('.history-row')?.textContent))
      .toEqual(['Third question', 'Second question', 'First question']);
    expect(within(column).getByRole('button', { name: 'Second question', current: true })).toBeTruthy();
    expect(within(column).getByRole('button', { name: 'Third question', current: false })).toBeTruthy();
    expect(await screen.findByText(/Mira signed it/)).toBeTruthy();
  });

  it('opens a clicked Ask row with its citations, tokens and cost', async () => {
    stubFetch({
      list: () => jsonResponse({ collections: [collection('Default')] }),
      asks: () => jsonResponse({ asks: [askHistoryEntry('ask-1', 'The signer', 'Who signed it?')] }),
    });

    render(Page);
    await screen.findByText('Default');
    await fireEvent.click(screen.getByRole('tab', { name: 'Ask' }));
    await fireEvent.click(await screen.findByRole('button', { name: 'The signer' }));

    expect((await screen.findByLabelText('Stored question')).textContent).toBe('Who signed it?');
    expect(screen.getByRole('button', { name: 'Open source S1, Page 4' })).toBeTruthy();
    expect(screen.getByText(/20 input tokens/)).toBeTruthy();
    expect(screen.getByText(/Estimated cost/).textContent).toContain('$0.0001');
    expect(screen.getByRole('button', { name: 'The signer', current: true })).toBeTruthy();
  });

  it('opens stored Ask evidence at the revision the citation names', async () => {
    const { calls } = stubFetch({
      list: () => jsonResponse({ collections: [collection('Default')] }),
      asks: () => jsonResponse({
        asks: [askHistoryEntry('ask-1', 'The signer', 'Who signed it?', 'Mira signed it [S1].', 'revision-8')],
      }),
      pageReview: () => jsonResponse(pageReviewResponse({ documentId: 'doc-1', ordinal: 3, revisionId: 'revision-8', text: 'Stored page body' })),
    });
    render(Page);
    await screen.findByText('Default');
    await fireEvent.click(screen.getByRole('tab', { name: 'Ask' }));
    await fireEvent.click(await screen.findByRole('button', { name: 'The signer' }));
    const citation = await screen.findByRole('button', { name: 'Open source S1, Page 4' });
    citation.focus();
    await fireEvent.click(citation);

    expect(await screen.findByText('Stored page body')).toBeTruthy();
    // A stored citation names the revision its excerpt was saved from; asking for it is what keeps an
    // old answer's source from being re-read as the document's current text.
    expect(calls.some((call) => call.url === '/api/collections/default/documents/doc-1/page-review?ordinal=3&revision=revision-8&limit=16384')).toBe(true);
    expect(screen.getByRole('img', { name: 'Original page image, page 4' }).getAttribute('src'))
      .toBe('/api/collections/default/documents/doc-1/page-review/image?ordinal=3&kind=original&revision=revision-8');
    await fireEvent.click(screen.getByRole('button', { name: 'Back to documents' }));
    await waitFor(() => expect(document.activeElement).toBe(citation));
  });

  it('shows a stored Ask excerpt with a revision-unknown label instead of fetching the live unit', async () => {
    const { calls } = stubFetch({
      list: () => jsonResponse({ collections: [collection('Default')] }),
      asks: () => jsonResponse({
        asks: [askHistoryEntry('ask-1', 'The signer', 'Who signed it?', 'Mira signed it [S1].', undefined, 'Saved excerpt as it was')],
      }),
      source: () => jsonResponse(sourcePage("Today's text, which is not the old evidence", 0, 42)),
    });
    render(Page);
    await screen.findByText('Default');
    await fireEvent.click(screen.getByRole('tab', { name: 'Ask' }));
    await fireEvent.click(await screen.findByRole('button', { name: 'The signer' }));
    await fireEvent.click(await screen.findByRole('button', { name: 'Open source S1, Page 4' }));

    expect(await screen.findByText('Saved excerpt as it was')).toBeTruthy();
    expect(screen.getByText(/Revision unknown/)).toBeTruthy();
    // The unit is live, but nobody recorded which reading the excerpt came from, so showing the live text
    // here would attribute today's wording to yesterday's answer.
    expect(calls.some((call) => call.url.includes('/sources/'))).toBe(false);
    expect(screen.queryByText("Today's text, which is not the old evidence")).toBeNull();
    expect(screen.queryByRole('button', { name: 'Load more' })).toBeNull();
    expect(screen.getByRole('link', { name: 'Open original' })).toBeTruthy();
  });

  it('opens the named revision for stored Ask evidence even when an excerpt was saved', async () => {
    const { calls } = stubFetch({
      list: () => jsonResponse({ collections: [collection('Default')] }),
      asks: () => jsonResponse({
        asks: [askHistoryEntry('ask-1', 'The signer', 'Who signed it?', 'Mira signed it [S1].', 'revision-8', 'Saved excerpt')],
      }),
      pageReview: () => jsonResponse(pageReviewResponse({ documentId: 'doc-1', ordinal: 3, revisionId: 'revision-8', text: 'Body of revision eight' })),
    });
    render(Page);
    await screen.findByText('Default');
    await fireEvent.click(screen.getByRole('tab', { name: 'Ask' }));
    await fireEvent.click(await screen.findByRole('button', { name: 'The signer' }));
    await fireEvent.click(await screen.findByRole('button', { name: 'Open source S1, Page 4' }));

    expect(await screen.findByText('Body of revision eight')).toBeTruthy();
    expect(calls.some((call) => call.url === '/api/collections/default/documents/doc-1/page-review?ordinal=3&revision=revision-8&limit=16384')).toBe(true);
    expect(screen.getByRole('img', { name: 'Original page image, page 4' })).toBeTruthy();
    expect(screen.queryByText(/Revision unknown/)).toBeNull();
  });

  it('opens successive historical PDF citations with each exact document, revision, and ordinal', async () => {
    const { calls } = stubFetch({
      list: () => jsonResponse({ collections: [collection('Default')] }),
      ask: () => eventResponse([
        { type: 'delta', text: 'Compare [S1] with [S2].' },
        { type: 'citation', id: 'S1', valid: true },
        { type: 'citation', id: 'S2', valid: true },
        { type: 'done', text: 'Compare [S1] with [S2].', evidence: [
          { id: 'S1', documentId: 'document-one', unitId: 'unit-one', locator: { type: 'pdf_page', page: 2 }, locatorLabel: 'Page 2', revisionId: 'revision-a', excerpt: 'First excerpt' },
          { id: 'S2', documentId: 'document-two', unitId: 'unit-two', locator: { type: 'pdf_page', page: 7 }, locatorLabel: 'Page 7', revisionId: 'revision-b', excerpt: 'Second excerpt' },
        ] },
      ]),
      pageReview: (url) => {
        const match = url.match(/\/documents\/([^/]+)\/page-review/);
        const params = new URL(url, 'http://localhost').searchParams;
        const documentId = match?.[1] ?? 'missing-document';
        const revisionId = params.get('revision') ?? 'missing-revision';
        const ordinal = Number(params.get('ordinal'));
        return jsonResponse(pageReviewResponse({
          documentId, revisionId, ordinal, text: `Review ${documentId} ${revisionId} ordinal ${ordinal}`,
        }));
      },
    });
    render(Page);
    await screen.findByText('Default');
    await fireEvent.click(screen.getByRole('tab', { name: 'Ask' }));
    await fireEvent.input(screen.getByLabelText('Question', { selector: '#question' }), { target: { value: 'Compare these pages' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Ask' }));

    await fireEvent.click(await screen.findByRole('button', { name: 'Open source S1, Page 2' }));
    expect(await screen.findByText('Review document-one revision-a ordinal 1')).toBeTruthy();
    expect(screen.getByRole('img', { name: 'Original page image, page 2' }).getAttribute('src'))
      .toBe('/api/collections/default/documents/document-one/page-review/image?ordinal=1&kind=original&revision=revision-a');

    await fireEvent.click(await screen.findByRole('button', { name: 'Open source S2, Page 7' }));
    expect(await screen.findByText('Review document-two revision-b ordinal 6')).toBeTruthy();
    expect(calls.some((call) => call.url === '/api/collections/default/documents/document-one/page-review?ordinal=1&revision=revision-a&limit=16384')).toBe(true);
    expect(calls.some((call) => call.url === '/api/collections/default/documents/document-two/page-review?ordinal=6&revision=revision-b&limit=16384')).toBe(true);
    expect(screen.getByRole('img', { name: 'Original page image, page 7' }).getAttribute('src'))
      .toBe('/api/collections/default/documents/document-two/page-review/image?ordinal=6&kind=original&revision=revision-b');
  });

  it('keeps a known-revision citation without a page locator in the text source viewer', async () => {
    const { calls } = stubFetch({
      list: () => jsonResponse({ collections: [collection('Default')] }),
      asks: () => jsonResponse({
        asks: [askHistoryEntry(
          'ask-1', 'The signer', 'Who signed it?', 'Mira signed it [S1].', 'revision-8', 'Saved section excerpt',
          { type: 'WordSection', headingPath: ['Witnesses'], paragraphStart: 2, paragraphEnd: 3 },
        )],
      }),
      source: () => jsonResponse(sourcePage('Body of the named section', 0, 24)),
    });
    render(Page);
    await screen.findByText('Default');
    await fireEvent.click(screen.getByRole('tab', { name: 'Ask' }));
    await fireEvent.click(await screen.findByRole('button', { name: 'The signer' }));
    await fireEvent.click(await screen.findByRole('button', { name: 'Open source S1, Page 4' }));

    expect(await screen.findByText('Body of the named section')).toBeTruthy();
    expect(calls.some((call) => call.url === '/api/collections/default/sources/unit-1?offset=0&limit=16384&revision=revision-8')).toBe(true);
    expect(calls.some((call) => call.url.includes('/page-review?'))).toBe(false);
  });

  it('reopens an Ask citation that has neither a revision nor a saved excerpt from the live unit', async () => {
    const { calls } = stubFetch({
      list: () => jsonResponse({ collections: [collection('Default')] }),
      asks: () => jsonResponse({ asks: [askHistoryEntry('ask-1', 'The signer', 'Who signed it?')] }),
      source: () => jsonResponse(sourcePage('Live unit text', 0, 14)),
    });
    render(Page);
    await screen.findByText('Default');
    await fireEvent.click(screen.getByRole('tab', { name: 'Ask' }));
    await fireEvent.click(await screen.findByRole('button', { name: 'The signer' }));
    await fireEvent.click(await screen.findByRole('button', { name: 'Open source S1, Page 4' }));

    // A citation that was just streamed carries no excerpt: nothing was saved, and the live unit is what it read.
    expect(await screen.findByText('Live unit text')).toBeTruthy();
    expect(calls.some((call) => call.url === '/api/collections/default/sources/unit-1?offset=0&limit=16384')).toBe(true);
    expect(screen.queryByText(/Revision unknown/)).toBeNull();
  });

  it('shows saved Investigate evidence with its excerpt and a revision-unknown label when no revision was recorded', async () => {
    const { calls } = stubFetch({
      list: () => jsonResponse({ collections: [collection('Default')] }),
      investigations: () => jsonResponse({ investigations: [investigationSummary('conv-1', 'The treaty')] }),
      investigation: () => jsonResponse({
        investigation: investigationHistory('conv-1', 'Who signed it?', 'Mira signed it [S2]', { excerpt: 'Saved ledger excerpt' }),
      }),
      source: () => jsonResponse(sourcePage("Today's text, which is not the old evidence", 0, 42)),
    });
    render(Page);
    await screen.findByText('Default');
    await fireEvent.click(screen.getByRole('tab', { name: 'Investigate' }));
    await fireEvent.click(await screen.findByRole('button', { name: 'The treaty' }));
    await fireEvent.click(await screen.findByRole('button', { name: /\[S2\] Page 8/ }));

    expect(await screen.findByText('Saved ledger excerpt')).toBeTruthy();
    expect(screen.getByText(/Revision unknown/)).toBeTruthy();
    expect(calls.some((call) => call.url.includes('/sources/'))).toBe(false);
    expect(screen.queryByText("Today's text, which is not the old evidence")).toBeNull();
  });

  it('opens saved Investigate evidence at the revision the ledger recorded', async () => {
    const { calls } = stubFetch({
      list: () => jsonResponse({ collections: [collection('Default')] }),
      investigations: () => jsonResponse({ investigations: [investigationSummary('conv-1', 'The treaty')] }),
      investigation: () => jsonResponse({
        investigation: investigationHistory('conv-1', 'Who signed it?', 'Mira signed it [S2]', { revisionId: 'revision-3', excerpt: 'Saved ledger excerpt' }),
      }),
      source: () => jsonResponse(sourcePage('Body of revision three', 0, 22)),
    });
    render(Page);
    await screen.findByText('Default');
    await fireEvent.click(screen.getByRole('tab', { name: 'Investigate' }));
    await fireEvent.click(await screen.findByRole('button', { name: 'The treaty' }));
    await fireEvent.click(await screen.findByRole('button', { name: /\[S2\] Page 8/ }));

    expect(await screen.findByText('Body of revision three')).toBeTruthy();
    expect(calls.some((call) => call.url === '/api/collections/default/sources/unit-2?offset=0&limit=16384&revision=revision-3')).toBe(true);
    expect(screen.queryByText(/Revision unknown/)).toBeNull();
  });

  it('clears the Ask panel to its compose state with the New conversation button', async () => {
    stubFetch({
      list: () => jsonResponse({ collections: [collection('Default')] }),
      asks: () => jsonResponse({ asks: [askHistoryEntry('ask-1', 'The signer', 'Who signed it?')] }),
    });

    render(Page);
    await screen.findByText('Default');
    await fireEvent.click(screen.getByRole('tab', { name: 'Ask' }));
    await fireEvent.click(await screen.findByRole('button', { name: 'The signer' }));
    expect(await screen.findByLabelText('Stored question')).toBeTruthy();

    await fireEvent.click(screen.getByRole('button', { name: 'New conversation' }));

    expect(screen.queryByLabelText('Stored question')).toBeNull();
    expect(screen.queryByLabelText('Answer')).toBeNull();
    expect((screen.getByLabelText('Question', { selector: '#question' }) as HTMLTextAreaElement).value).toBe('');
    expect(window.localStorage.getItem('infoscry-history:ask:default')).toBeNull();
  });

  it('marks the row of the answer the streaming completion event named', async () => {
    let askHistoryRequests = 0;
    stubFetch({
      list: () => jsonResponse({ collections: [collection('Default')] }),
      asks: () => {
        askHistoryRequests += 1;
        return askHistoryRequests === 1
          ? jsonResponse({ asks: [] })
          : jsonResponse({ asks: [askHistoryEntry('ask-9', 'The signer', 'Who signed it?')] });
      },
      ask: () => eventResponse([
        { type: 'delta', text: 'Mira signed it [S1].' },
        { type: 'usage', inputTokens: 20, outputTokens: 9 },
        { type: 'citation', id: 'S1', valid: true },
        { type: 'done', text: 'Mira signed it [S1].', evidence: [{ id: 'S1', documentId: 'doc-1', unitId: 'unit-1', locator: {}, locatorLabel: 'Page 4' }], conversationId: 'ask-9' },
      ]),
    });

    render(Page);
    await screen.findByText('Default');
    await fireEvent.click(screen.getByRole('tab', { name: 'Ask' }));
    await fireEvent.input(screen.getByLabelText('Question', { selector: '#question' }), { target: { value: 'Who signed it?' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Ask' }));

    const column = await screen.findByRole('navigation', { name: 'Ask history' });
    expect(await within(column).findByRole('button', { name: 'The signer', current: true })).toBeTruthy();
    expect(await screen.findByRole('button', { name: 'Open source S1, Page 4' })).toBeTruthy();
    expect((await screen.findByLabelText('Stored question')).textContent).toBe('Who signed it?');
    expect(window.localStorage.getItem('infoscry-history:ask:default')).toBe('ask-9');
  });

  it('ignores an older Ask history response that arrives after a newer refresh', async () => {
    let resolveInitial!: (response: Response) => void;
    let askListRequests = 0;
    stubFetch({
      list: () => jsonResponse({ collections: [collection('Default')] }),
      asks: () => {
        askListRequests += 1;
        return askListRequests === 1
          ? new Promise<Response>((resolve) => { resolveInitial = resolve; })
          : jsonResponse({ asks: [askHistoryEntry('ask-9', 'Newest title', 'What happened?')] });
      },
      ask: () => eventResponse([
        { type: 'done', text: 'A completed answer.', evidence: [], conversationId: 'ask-9' },
      ]),
    });

    render(Page);
    await screen.findByText('Default');
    await fireEvent.click(screen.getByRole('tab', { name: 'Ask' }));
    await fireEvent.input(screen.getByLabelText('Question', { selector: '#question' }), { target: { value: 'What happened?' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Ask' }));

    const column = await screen.findByRole('navigation', { name: 'Ask history' });
    expect(await within(column).findByRole('button', { name: 'Newest title', current: true })).toBeTruthy();
    resolveInitial(jsonResponse({ asks: [askHistoryEntry('ask-old', 'Stale question')] }));
    await new Promise<void>((resolve) => setTimeout(resolve, 0));

    expect(within(column).getByRole('button', { name: 'Newest title', current: true })).toBeTruthy();
    expect(within(column).queryByRole('button', { name: 'Stale question' })).toBeNull();
    expect(window.localStorage.getItem('infoscry-history:ask:default')).toBe('ask-9');
  });

  it('keeps the fresh Ask selected when the initial stale list resolves after done but before title completion', async () => {
    let resolveInitial!: (response: Response) => void;
    let releaseStream!: () => void;
    let askListRequests = 0;
    const streamGate = new Promise<void>((resolve) => { releaseStream = resolve; });
    const encoder = new TextEncoder();
    stubFetch({
      list: () => jsonResponse({ collections: [collection('Default')] }),
      asks: () => {
        askListRequests += 1;
        return askListRequests === 1
          ? new Promise<Response>((resolve) => { resolveInitial = resolve; })
          : jsonResponse({ asks: [askHistoryEntry('ask-9', 'Newest title', 'What happened?', 'A fresh answer.')] });
      },
      ask: () => ({
        ok: true,
        status: 200,
        statusText: '',
        body: new ReadableStream<Uint8Array>({
          async start(controller) {
            controller.enqueue(encoder.encode(`data: ${JSON.stringify({ type: 'done', text: 'A fresh answer.', evidence: [], conversationId: 'ask-9' })}\n\n`));
            await streamGate;
            controller.close();
          },
        }),
      } as Response),
    });

    render(Page);
    await screen.findByText('Default');
    await fireEvent.click(screen.getByRole('tab', { name: 'Ask' }));
    await fireEvent.input(screen.getByLabelText('Question', { selector: '#question' }), { target: { value: 'What happened?' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Ask' }));
    await waitFor(() => expect(window.localStorage.getItem('infoscry-history:ask:default')).toBe('ask-9'));

    // This is the old initial load, which began before the streamed conversation existed.
    resolveInitial(jsonResponse({ asks: [askHistoryEntry('ask-old', 'Stale question')] }));
    await new Promise<void>((resolve) => setTimeout(resolve, 0));
    expect(screen.getByText('A fresh answer.')).toBeTruthy();

    // Closing the SSE stream means the title attempt finished and triggers the authoritative refresh.
    releaseStream();
    const column = await screen.findByRole('navigation', { name: 'Ask history' });
    expect(await within(column).findByRole('button', { name: 'Newest title', current: true })).toBeTruthy();
    expect(within(column).queryByRole('button', { name: 'Stale question' })).toBeNull();
  });

  it('ignores an older Investigate history response that arrives after a newer refresh', async () => {
    let resolveInitial!: (response: Response) => void;
    let resolveStarted!: (response: Response) => void;
    let investigationListRequests = 0;
    stubFetch({
      list: () => jsonResponse({ collections: [collection('Default')] }),
      investigations: () => {
        investigationListRequests += 1;
        if (investigationListRequests === 1) return new Promise<Response>((resolve) => { resolveInitial = resolve; });
        if (investigationListRequests === 2) return new Promise<Response>((resolve) => { resolveStarted = resolve; });
        return jsonResponse({ investigations: [investigationSummary('conv-new', 'Newest title', 'What happened?')] });
      },
      investigateStart: () => eventResponse([
        { type: 'started', id: 'conv-new' },
        { type: 'done', text: 'A completed investigation.', evidence: [] },
      ]),
    });

    render(Page);
    await screen.findByText('Default');
    await fireEvent.click(screen.getByRole('tab', { name: 'Investigate' }));
    await fireEvent.input(screen.getByLabelText('Investigate question'), { target: { value: 'What happened?' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Investigate' }));

    const column = await screen.findByRole('navigation', { name: 'Conversation history' });
    expect(await within(column).findByRole('button', { name: 'Newest title', current: true })).toBeTruthy();
    resolveStarted(jsonResponse({ investigations: [investigationSummary('conv-new', 'Opening question')] }));
    await new Promise<void>((resolve) => setTimeout(resolve, 0));
    resolveInitial(jsonResponse({ investigations: [] }));
    await new Promise<void>((resolve) => setTimeout(resolve, 0));

    expect(within(column).getByRole('button', { name: 'Newest title', current: true })).toBeTruthy();
    expect(within(column).queryByRole('button', { name: 'Opening question' })).toBeNull();
    expect(window.localStorage.getItem('infoscry-history:investigate:default')).toBe('conv-new');
  });

  it('keeps Ask and Investigate conversations in their own columns', async () => {
    stubFetch({
      list: () => jsonResponse({ collections: [collection('Default')] }),
      asks: () => jsonResponse({ asks: [askHistoryEntry('ask-1', 'The signer', 'Who signed it?')] }),
      investigations: () => jsonResponse({ investigations: [investigationSummary('conv-1', 'The treaty', 'What happened?')] }),
    });

    render(Page);
    await screen.findByText('Default');
    await fireEvent.click(screen.getByRole('tab', { name: 'Ask' }));

    let column = await screen.findByRole('navigation', { name: 'Ask history' });
    expect(await within(column).findByRole('button', { name: 'The signer' })).toBeTruthy();
    expect(within(column).queryByRole('button', { name: 'The treaty' })).toBeNull();

    await fireEvent.click(screen.getByRole('tab', { name: 'Investigate' }));
    column = await screen.findByRole('navigation', { name: 'Conversation history' });
    expect(within(column).getByRole('button', { name: 'The treaty' })).toBeTruthy();
    expect(within(column).queryByRole('button', { name: 'The signer' })).toBeNull();
  });

  it('shows an always-visible delete control with an accessible name on every row', async () => {
    stubFetch({
      list: () => jsonResponse({ collections: [collection('Default')] }),
      investigations: () => jsonResponse({ investigations: [investigationSummary('conv-1', 'The treaty')] }),
    });

    render(Page);
    await screen.findByText('Default');
    await fireEvent.click(screen.getByRole('tab', { name: 'Investigate' }));

    const column = await screen.findByRole('navigation', { name: 'Conversation history' });
    const row = within(column).getAllByRole('listitem')[0];
    const remove = within(row).getByRole('button', { name: 'Delete conversation The treaty' });
    expect(remove.tagName).toBe('BUTTON');
    expect(remove.getAttribute('aria-label')).toBe('Delete conversation The treaty');
    // The title control and the delete control are siblings, never one nested inside the other.
    const title = within(row).getByRole('button', { name: 'The treaty' });
    expect(title.contains(remove)).toBe(false);
  });

  it('asks the platform confirmation and sends the request only when confirmed, then refreshes the list', async () => {
    const order: string[] = [];
    const confirm = vi.fn(() => { order.push('confirm'); return true; });
    vi.stubGlobal('confirm', confirm);
    let conversationListRequests = 0;
    const { calls } = stubFetch({
      list: () => jsonResponse({ collections: [collection('Default')] }),
      // The refreshed list reflects the delete, so the second read holds nothing.
      investigations: () => {
        conversationListRequests += 1;
        return conversationListRequests === 1
          ? jsonResponse({ investigations: [investigationSummary('conv-1', 'The treaty')] })
          : jsonResponse({ investigations: [] });
      },
      deleteConversation: () => { order.push('request'); return jsonResponse({}, 204); },
    });

    render(Page);
    await screen.findByText('Default');
    await fireEvent.click(screen.getByRole('tab', { name: 'Investigate' }));
    await fireEvent.click(await screen.findByRole('button', { name: 'Delete conversation The treaty' }));

    expect(confirm).toHaveBeenCalledTimes(1);
    expect(confirm).toHaveBeenCalledWith(expect.stringContaining('The treaty'));
    expect(order).toEqual(['confirm', 'request']);
    const remove = calls.find((call) => call.url === '/api/collections/default/conversations/conv-1' && call.init?.method === 'DELETE');
    expect(remove).toBeTruthy();
    // The mutation carries a CSRF token; the exact value depends on which earlier test seeded the
    // session cache, so only the header's presence is asserted here.
    expect((remove?.init?.headers as Record<string, string>)['X-InfoScry-Csrf']).toBeTruthy();
    // The list is refreshed from the server, not patched by hand.
    await waitFor(() => expect(screen.getByText('No conversations yet.')).toBeTruthy());
    expect(calls.filter((call) => call.url.endsWith('/investigations'))).toHaveLength(2);
  });

  it('does not call the API when the confirmation is declined', async () => {
    vi.stubGlobal('confirm', vi.fn(() => false));
    const { calls } = stubFetch({
      list: () => jsonResponse({ collections: [collection('Default')] }),
      investigations: () => jsonResponse({ investigations: [investigationSummary('conv-1', 'The treaty')] }),
    });

    render(Page);
    await screen.findByText('Default');
    await fireEvent.click(screen.getByRole('tab', { name: 'Investigate' }));
    await fireEvent.click(await screen.findByRole('button', { name: 'Delete conversation The treaty' }));

    expect(calls.some((call) => call.url.includes('/conversations/'))).toBe(false);
    expect(screen.getByRole('button', { name: 'The treaty' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Delete conversation The treaty' })).toBeTruthy();
  });

  it('keeps the list and the open panel when a delete fails', async () => {
    vi.stubGlobal('confirm', vi.fn(() => true));
    stubFetch({
      list: () => jsonResponse({ collections: [collection('Default')] }),
      investigations: () => jsonResponse({ investigations: [investigationSummary('conv-1', 'The treaty')] }),
      investigation: () => jsonResponse({ investigation: investigationHistory('conv-1', 'Who signed it?', 'Mira signed it.') }),
      deleteConversation: () => jsonResponse({ error: { code: 'MAINTENANCE_IN_PROGRESS', message: 'maintenance is running; try again later' } }, 423),
    });

    render(Page);
    await screen.findByText('Default');
    await fireEvent.click(screen.getByRole('tab', { name: 'Investigate' }));
    await fireEvent.click(await screen.findByRole('button', { name: 'The treaty' }));
    expect(await screen.findByText('Who signed it?')).toBeTruthy();

    await fireEvent.click(screen.getByRole('button', { name: 'Delete conversation The treaty' }));

    expect((await screen.findByRole('alert')).textContent).toContain('maintenance is running');
    expect(screen.getByRole('button', { name: 'The treaty' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'The treaty', current: true })).toBeTruthy();
    expect(screen.getByText('Mira signed it.')).toBeTruthy();
  });

  it('deleting the open Investigate conversation clears the panel and forgets its remembered selection', async () => {
    window.localStorage.setItem('infoscry-history:investigate:default', 'conv-1');
    vi.stubGlobal('confirm', vi.fn(() => true));
    let conversationListRequests = 0;
    stubFetch({
      list: () => jsonResponse({ collections: [collection('Default')] }),
      // The refreshed list reflects the delete, so the second read holds nothing.
      investigations: () => {
        conversationListRequests += 1;
        return conversationListRequests === 1
          ? jsonResponse({ investigations: [investigationSummary('conv-1', 'The treaty')] })
          : jsonResponse({ investigations: [] });
      },
      investigation: () => jsonResponse({ investigation: investigationHistory('conv-1', 'Who signed it?', 'Mira signed it.') }),
    });

    render(Page);
    await screen.findByText('Default');
    await fireEvent.click(screen.getByRole('tab', { name: 'Investigate' }));
    expect(await screen.findByText('Who signed it?')).toBeTruthy();

    await fireEvent.click(screen.getByRole('button', { name: 'Delete conversation The treaty' }));

    expect(await screen.findByText('No conversations yet.')).toBeTruthy();
    expect(screen.queryByText('Who signed it?')).toBeNull();
    expect((screen.getByLabelText('Investigate question') as HTMLTextAreaElement).value).toBe('');
    expect(window.localStorage.getItem('infoscry-history:investigate:default')).toBeNull();
  });

  it('deleting the open Ask conversation clears the panel and forgets its remembered selection', async () => {
    window.localStorage.setItem('infoscry-history:ask:default', 'ask-1');
    vi.stubGlobal('confirm', vi.fn(() => true));
    let askListRequests = 0;
    stubFetch({
      list: () => jsonResponse({ collections: [collection('Default')] }),
      // The refreshed list reflects the delete, so the second read holds nothing.
      asks: () => {
        askListRequests += 1;
        return askListRequests === 1
          ? jsonResponse({ asks: [askHistoryEntry('ask-1', 'The signer', 'Who signed it?')] })
          : jsonResponse({ asks: [] });
      },
    });

    render(Page);
    await screen.findByText('Default');
    await fireEvent.click(screen.getByRole('tab', { name: 'Ask' }));
    await fireEvent.click(await screen.findByRole('button', { name: 'The signer' }));
    expect(await screen.findByLabelText('Stored question')).toBeTruthy();

    await fireEvent.click(screen.getByRole('button', { name: 'Delete conversation The signer' }));

    expect(await screen.findByText('No conversations yet.')).toBeTruthy();
    expect(screen.queryByLabelText('Stored question')).toBeNull();
    expect(screen.queryByLabelText('Answer')).toBeNull();
    expect((screen.getByLabelText('Question', { selector: '#question' }) as HTMLTextAreaElement).value).toBe('');
    expect(window.localStorage.getItem('infoscry-history:ask:default')).toBeNull();
  });
});

function collection(name: string, documentCount = 0): Collection {
  return {
    id: name.toLowerCase(),
    name,
    language: 'eng',
    defaultMethod: 'tesseract',
    createdAt: '2026-09-21T07:00:00Z',
    updatedAt: '2026-09-21T07:00:00Z',
    lifecycle: 'ACTIVE',
    documentCount,
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

function pageReviewResponse(over: Record<string, unknown> = {}) {
  return {
    documentId: 'document-1', unitId: 'unit-9', revisionId: 'revision-1', ordinal: 0,
    documentPages: 1, previousOrdinal: null, nextOrdinal: null, text: 'Managed document text', offset: 0,
    totalChars: 21, truncated: false, qualityScore: 90, readingMethod: 'Surya', imageKind: 'original', ...over,
  };
}

function investigationSummary(id: string, title: string, question = title) {
  return { id, createdAt: '2026-09-26T10:00:00Z', question, title };
}

/**
 * One stored Ask answer, the object the list route returns and the panel replays.
 *
 * The server's stored citation always carries the excerpt it saved; tests ask for one explicitly so a
 * live citation (which has none) stays expressible, and a revision only when one was recorded.
 */
function askHistoryEntry(
  id: string,
  title: string,
  question = title,
  answer = 'Mira signed it [S1].',
  revisionId?: string,
  excerpt?: string,
  locator: unknown = { type: 'pdf_page', page: 4 },
) {
  return {
    id,
    createdAt: '2026-09-26T10:00:00Z',
    question,
    title,
    answer,
    evidence: [{
      id: 'S1',
      documentId: 'doc-1',
      unitId: 'unit-1',
      locator,
      locatorLabel: 'Page 4',
      ...(revisionId === undefined ? {} : { revisionId }),
      ...(excerpt === undefined ? {} : { excerpt }),
    }],
    inputTokens: 20,
    outputTokens: 9,
    costUsd: 0.0001,
  };
}

function investigationHistory(
  id: string,
  question: string,
  answer: string,
  evidenceExtras: { revisionId?: string; excerpt?: string } = {},
) {
  return {
    id,
    messages: [
      { role: 'user', text: question },
      { role: 'assistant', text: answer },
    ],
    evidence: [{ id: 'S2', documentId: 'doc-2', unitId: 'unit-2', locator: {}, locatorLabel: 'Page 8', ...evidenceExtras }],
    inputTokens: 20,
    outputTokens: 9,
    costUsd: 0.0001,
    activity: [{ name: 'search_collection', resultCode: 'SUCCESS', durationMs: 12 }],
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
  list?: (url: string) => Response | Promise<Response>;
  documents?: (url: string) => Response | Promise<Response>;
  retry?: (url: string) => Response | Promise<Response>;
  document?: (url: string) => Response | Promise<Response>;
  imports?: (url: string) => Response | Promise<Response>;
  rename?: (url: string) => Response | Promise<Response>;
  ocrLanguages?: (url: string) => Response | Promise<Response>;
  deleteCollection?: (url: string) => Response | Promise<Response>;
  deletions?: (url: string) => Response | Promise<Response>;
  deletion?: (url: string) => Response | Promise<Response>;
  search?: (url: string) => Response | Promise<Response>;
  source?: (url: string) => Response | Promise<Response>;
  pageReview?: (url: string) => Response | Promise<Response>;
  selectablePdf?: (url: string) => Response | Promise<Response>;
  ask?: (signal?: AbortSignal) => Response | Promise<Response>;
  asks?: (url: string) => Response | Promise<Response>;
  investigations?: (url: string) => Response | Promise<Response>;
  investigation?: (url: string) => Response | Promise<Response>;
  investigateStart?: (signal?: AbortSignal) => Response | Promise<Response>;
  deleteConversation?: (url: string) => Response | Promise<Response>;
  defaults?: (url: string) => Response | Promise<Response>;
  profiles?: (url: string) => Response | Promise<Response>;
  presets?: (url: string) => Response | Promise<Response>;
  readingMethods?: (url: string) => Response | Promise<Response>;
  rescanPreview?: (url: string, body: unknown) => Response | Promise<Response>;
  startRescan?: (url: string, body: { requestId: string; [key: string]: unknown }) => Response | Promise<Response>;
  rescanOperation?: (url: string) => Response | Promise<Response>;
  rescanOperations?: (url: string) => Response | Promise<Response>;
  documentRevisions?: (url: string) => Response | Promise<Response>;
  session?: () => Response;
}) {
  const calls: { url: string; init?: RequestInit }[] = [];
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    calls.push({ url, init });
    if (url.endsWith('/api/session')) {
      return (overrides.session ?? (() => jsonResponse({ product: 'InfoScry', csrfToken: 'session-token' })))();
    }
    if (url.includes('/conversations/') && init?.method === 'DELETE') {
      return (overrides.deleteConversation ?? (() => jsonResponse({}, 204)))(url);
    }
    if (url.endsWith('/api/collections')) {
      return (overrides.list ?? (() => jsonResponse({ collections: [] })))(url);
    }
    if (url.includes('/reading-methods')) return (overrides.readingMethods ?? (() => jsonResponse({ default: 'surya', methods: [] })))(url);
    if (url.endsWith('/ocr/preview') && init?.method === 'POST') {
      return (overrides.rescanPreview ?? (() => jsonResponse({ error: { code: 'NOT_FOUND', message: 'no such route' } }, 404)))(url, JSON.parse(String(init.body)));
    }
    if (url.endsWith('/ocr/rescan') && init?.method === 'POST') {
      return (overrides.startRescan ?? (() => jsonResponse({ error: { code: 'NOT_FOUND', message: 'no such route' } }, 404)))(url, JSON.parse(String(init.body)));
    }
    if (url.includes('/ocr/operations/')) return (overrides.rescanOperation ?? (() => jsonResponse({ error: { code: 'NOT_FOUND', message: 'no such route' } }, 404)))(url);
    if (url.endsWith('/ocr/operations')) return (overrides.rescanOperations ?? (() => jsonResponse({ operations: [] })))(url);
    if (url.includes('/ocr/revisions?')) return (overrides.documentRevisions ?? (() => jsonResponse({ revisions: [], total: 0, activeRevisionId: null })))(url);
    if (url.includes('/ocr-languages')) {
      return (overrides.ocrLanguages ?? (() => jsonResponse({ collection: collection('Nightfall') })))(url);
    }
    if (url.startsWith('/api/collections/') && init?.method === 'PATCH') {
      return (overrides.rename ?? (() => jsonResponse({ error: { code: 'NOT_FOUND', message: 'no such route' } }, 404)))(url);
    }
    if (url.startsWith('/api/collections/') && init?.method === 'DELETE') {
      return (overrides.deleteCollection
        ?? (() => jsonResponse({ operationId: 'op-1', collectionId: 'nightfall', phase: 'PREPARED' }, 202)))(url);
    }
    if (url.startsWith('/api/deletions/')) {
      return (overrides.deletion ?? (() => jsonResponse({
        operation: {
          operationId: 'op-1',
          kind: 'COLLECTION',
          collectionId: 'nightfall',
          collectionName: 'Nightfall',
          documentIds: [],
          phase: 'DONE',
          terminal: true,
          errorCode: null,
        },
      })))(url);
    }
    if (url.endsWith('/api/deletions')) {
      return (overrides.deletions ?? (() => jsonResponse({ deletions: [] })))(url);
    }
    if (url.includes('/imports')) {
      return (overrides.imports ?? (() => jsonResponse({ imports: [], total: 0 })))(url);
    }
    if (url.includes('/selectable-pdf')) {
      return (overrides.selectablePdf ?? ((target: string) => jsonResponse(notCreatedPdfStatus(target))))(url);
    }
    if (url.endsWith('/documents/retry')) {
      return (overrides.retry ?? (() => jsonResponse({ collectionId: 'nightfall', acceptedJobIds: [], rejected: [] }, 202)))(url);
    }
    if (url.endsWith('/documents/summary')) return jsonResponse({ total: 0, byStatus: {} });
    if (url.includes('/page-review')) return (overrides.pageReview ?? (() => jsonResponse(pageReviewResponse())))(url);
    if (url.includes('/documents/')) {
      return (overrides.document ?? (() => jsonResponse({ error: { code: 'NOT_FOUND', message: 'no such route' } }, 404)))(url);
    }
    if (url.includes('/documents')) {
      return (overrides.documents ?? (() => jsonResponse({ documents: [], total: 0 })))(url);
    }
    if (url.endsWith('/api/investigations')) {
      return (overrides.investigateStart ?? (() => eventResponse([{ type: 'done', text: '', evidence: [] }])))(init?.signal as AbortSignal | undefined);
    }
    if (url.endsWith('/investigations')) {
      return (overrides.investigations ?? (() => jsonResponse({ investigations: [] })))(url);
    }
    if (url.endsWith('/asks')) {
      return (overrides.asks ?? (() => jsonResponse({ asks: [] })))(url);
    }
    if (url.includes('/investigations/')) {
      return (overrides.investigation ?? (() => jsonResponse({ investigation: { id: '', messages: [], evidence: [], activity: [], inputTokens: 0, outputTokens: 0, costUsd: 0 } })))(url);
    }
    if (url.startsWith('/api/search')) {
      return (overrides.search ?? (() => jsonResponse({ hits: [], staleFiltered: 0 })))(url);
    }
    if (url.includes('/api/llm/defaults/')) {
      return (overrides.defaults ?? (() => jsonResponse({ profileName: 'Local' })))(url);
    }
    if (url.endsWith('/api/llm/profiles')) {
      return (overrides.profiles ?? (() => jsonResponse({ profiles: [{ name: 'Local', inputPricePerMillion: 1, outputPricePerMillion: 2 }], defaults: {} })))(url);
    }
    if (url.endsWith('/api/llm/presets')) {
      return (overrides.presets ?? (() => jsonResponse({ presets: [] })))(url);
    }
    if (url.endsWith('/api/ask')) return (overrides.ask ?? (() => eventResponse([{ type: 'done', text: '', evidence: [] }])))(init?.signal as AbortSignal | undefined);
    if (url.includes('/sources/')) {
      return (overrides.source ?? (() => jsonResponse({ error: { code: 'NOT_FOUND', message: 'no such route' } }, 404)))(url);
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

/** A status the selectable-PDF route answers for a revision with no copy yet; the revision is the one asked for. */
function notCreatedPdfStatus(url: string) {
  const revisionId = new URL(url, window.location.origin).searchParams.get('revision') ?? '';
  return {
    revisionId, key: null, state: 'NOT_CREATED', selectablePages: 0, totalPages: null,
    blankPages: 0, unavailablePages: [], reasonCode: null,
  };
}

describe('selectable PDF copies of cited sources', () => {
  beforeEach(() => {
    vi.unstubAllGlobals();
    Object.defineProperty(window, 'innerWidth', { configurable: true, value: 1440 });
  });
  afterEach(() => {
    cleanup();
    window.localStorage.clear();
  });

  /** Searches the default collection and opens its one result: the cited source that bypasses PageReview. */
  async function openCitedSearchResult(result: Record<string, unknown>) {
    const { calls } = stubFetch({
      list: () => jsonResponse({ collections: [collection('Default')] }),
      search: () => jsonResponse({ hits: [result], staleFiltered: 0 }),
      source: () => jsonResponse(sourcePage('A matching excerpt', 0, 19)),
    });
    render(Page);
    await screen.findByText('Default');
    await fireEvent.input(screen.getByLabelText('Search query'), { target: { value: 'tax records' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Search' }));
    await fireEvent.click(await screen.findByRole('button', { name: /Default.*Page 12/s }));
    await screen.findByRole('region', { name: 'Source' });
    return { calls };
  }

  it('shows the copy beside Open original for the citation revision and sends no create request', async () => {
    const { calls } = await openCitedSearchResult({ ...hit('Default', 'Page 12'), revisionId: 'revision-1' });

    const group = await screen.findByRole('group', { name: 'Selectable PDF copy' });
    const original = screen.getByRole('link', { name: 'Open original' });
    expect(group.parentElement).toBe(original.parentElement);
    expect(await screen.findByRole('button', { name: 'Create PDF with selectable text' })).toBeTruthy();
    expect(calls.some((call) => call.url === '/api/collections/default/documents/document-1/selectable-pdf?revision=revision-1')).toBe(true);
    expect(calls.some((call) => call.url.includes('/selectable-pdf') && call.init?.method === 'POST')).toBe(false);
  });

  it('shows the copy as unavailable and asks for nothing when the citation names no revision', async () => {
    const { calls } = await openCitedSearchResult(hit('Default', 'Page 12'));

    expect(await screen.findByText('Selectable PDF is unavailable for this source.')).toBeTruthy();
    expect(calls.some((call) => call.url.includes('/selectable-pdf'))).toBe(false);
    expect(screen.getByRole('link', { name: 'Open original' })).toBeTruthy();
  });
});
