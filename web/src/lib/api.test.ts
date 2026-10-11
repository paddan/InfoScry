import { afterEach, describe, expect, it, vi } from 'vitest';
import { fetchLlmCatalog, ApiError, continueInvestigation, createCollection, createSelectablePdf, getCollectionDocumentSummary, getSelectablePdfStatus, listCollections, listOcrProfiles, listReadingMethods, previewImport, previewRescan, readInvestigationEvents, readSource, renameCollection, resumeRescan, selectablePdfFileHref, startImport, startInvestigation, startRescan, updateCollectionOcrSettings } from './api';

const sse = (...frames: string[]) => new Response(frames.map((frame) => `data:${frame}\n\n`).join(''));

const collect = async (response: Response) => {
  const events = [];
  for await (const event of readInvestigationEvents(response)) events.push(event);
  return events;
};

describe('readInvestigationEvents', () => {
  it('passes the limit and answer-start frames through and still drops unknown types', async () => {
    const events = await collect(sse(
      JSON.stringify({ type: 'started', id: 'conversation-1' }),
      JSON.stringify({ type: 'limit', code: 'MAX_ROUNDS', message: 'Tool round limit reached.' }),
      JSON.stringify({ type: 'answer-start' }),
      JSON.stringify({ type: 'delta', text: 'answer' }),
      JSON.stringify({ type: 'something-else' }),
      JSON.stringify({ type: 'done', text: 'answer' }),
      '[DONE]',
    ));

    expect(events.map((event) => event.type)).toEqual(['started', 'limit', 'answer-start', 'delta', 'done']);
    expect(events[1]).toEqual({ type: 'limit', code: 'MAX_ROUNDS', message: 'Tool round limit reached.' });
    expect(events[2]).toEqual({ type: 'answer-start' });
  });
});

describe('collection response decoding', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('adapts the backend collection JSON for list, create, rename and OCR settings responses', async () => {
    // This is the current serialized `domain.Collection`: old persisted fields are still on the wire.
    const backendJson = {
      id: 'archive-1', name: 'Archive', ocrLanguages: 'swe+eng', ocrEngine: 'LLM',
      ocrImportMode: 'FILL_MISSING', ocrTranscriptionProfileId: 'vision-7',
      ocrReviewProfileId: null, ocrExternalPageLimit: 4,
      createdAt: '2026-10-08T10:00:00Z', updatedAt: '2026-10-08T10:00:00Z',
      description: null, lifecycle: 'ACTIVE', documentCount: 3,
    };
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      if (String(input) === '/api/collections' && init?.method !== 'POST') {
        return new Response(JSON.stringify({ collections: [backendJson] }), { status: 200 });
      }
      if (String(input) === '/api/session') return new Response(JSON.stringify({ csrfToken: 'csrf-test' }), { status: 200 });
      return new Response(JSON.stringify({ collection: backendJson }), { status: 200 });
    });
    vi.stubGlobal('fetch', fetchMock);

    const expected = {
      id: 'archive-1', name: 'Archive', language: 'swe+eng', defaultMethod: 'llm:vision-7',
      createdAt: '2026-10-08T10:00:00Z', updatedAt: '2026-10-08T10:00:00Z',
      description: null, lifecycle: 'ACTIVE', documentCount: 3,
    };
    expect(await listCollections()).toEqual([expected]);
    expect(await createCollection('Archive')).toEqual(expected);
    expect(await renameCollection('archive-1', 'Archive')).toEqual(expected);
    expect(await updateCollectionOcrSettings('archive-1', { language: 'eng', defaultMethod: 'surya' })).toEqual(expected);

    expect(fetchMock).toHaveBeenCalledWith('/api/collections');
    expect(fetchMock).toHaveBeenCalledWith('/api/collections', expect.objectContaining({ method: 'POST' }));
    expect(fetchMock).toHaveBeenCalledWith('/api/collections/archive-1', expect.objectContaining({ method: 'PATCH' }));
    expect(fetchMock).toHaveBeenCalledWith('/api/collections/archive-1/ocr-languages', expect.objectContaining({ method: 'PATCH' }));
  });

  it('uses the stored engine when a profile is absent or does not belong to that engine', async () => {
    const legacyRows = [
      { id: 'surya', name: 'Surya', ocrLanguages: 'eng', ocrEngine: 'SURYA', ocrTranscriptionProfileId: 'stale', createdAt: '', updatedAt: '', lifecycle: 'ACTIVE', documentCount: 0 },
      { id: 'llm-no-profile', name: 'Local fallback', ocrLanguages: 'eng', ocrEngine: 'LLM', ocrTranscriptionProfileId: null, createdAt: '', updatedAt: '', lifecycle: 'ACTIVE', documentCount: 0 },
    ];
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ collections: legacyRows }), { status: 200 })));
    const collections = await listCollections();
    expect(collections.map(({ id, language, defaultMethod }) => ({ id, language, defaultMethod }))).toEqual([
      { id: 'surya', language: 'eng', defaultMethod: 'surya' },
      { id: 'llm-no-profile', language: 'eng', defaultMethod: 'tesseract' },
    ]);
  });
});

describe('readSource', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('names the revision an excerpt came from and reads the live unit when none is known', async () => {
    const urls: string[] = [];
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      urls.push(String(input));
      return { ok: true, status: 200, statusText: '', text: async () => JSON.stringify({ id: 'unit-1' }) } as Response;
    }));

    await readSource('Default collection', 'unit/1', 0, 16_384, 'revision-7');
    await readSource('Default collection', 'unit/1');

    expect(urls).toEqual([
      '/api/collections/Default%20collection/sources/unit%2F1?offset=0&limit=16384&revision=revision-7',
      '/api/collections/Default%20collection/sources/unit%2F1?offset=0&limit=16384',
    ]);
  });
});

describe('getCollectionDocumentSummary', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('reads the collection-wide summary through its own filterless request', async () => {
    const urls: string[] = [];
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      urls.push(String(input));
      return {
        ok: true,
        status: 200,
        statusText: '',
        text: async () => JSON.stringify({ total: 75, byStatus: { FAILED: 2, COMPLETE: 73 } }),
      } as Response;
    }));

    const summary = await getCollectionDocumentSummary('Collection One');

    // One static route beside the listing; no q/status/limit parameters can reach the aggregate.
    expect(urls).toEqual(['/api/collections/Collection%20One/documents/summary']);
    expect(summary.total).toBe(75);
    expect(summary.byStatus.FAILED).toBe(2);
  });
});

describe('investigation limit payloads', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('omits limits for a caller that sends none and appends them when provided', async () => {
    const bodies: unknown[] = [];
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      if (String(input) === '/api/session') {
        return { ok: true, status: 200, statusText: '', text: async () => JSON.stringify({ csrfToken: 'token' }) } as Response;
      }
      bodies.push(JSON.parse(String(init?.body)));
      return { ok: true, status: 200, statusText: '' } as Response;
    }));

    await startInvestigation('collection-1', 'What happened?', 'local-cheap');
    await continueInvestigation('conversation-1', 'collection-1', 'When?', 'local-cheap', undefined, {
      maxToolRounds: 4,
      maxToolCalls: 7,
      maxTurnSeconds: 90,
    });

    expect(bodies[0]).toEqual({ collection: 'collection-1', question: 'What happened?', profile: 'local-cheap' });
    expect(bodies[1]).toEqual({
      collection: 'collection-1',
      question: 'When?',
      profile: 'local-cheap',
      limits: { maxToolRounds: 4, maxToolCalls: 7, maxTurnSeconds: 90 },
    });
  });
});

describe('listOcrProfiles', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('reads the fields the server leaves out as null, so a keyless profile is not a missing key', async () => {
    // The server omits null fields from its JSON; the panels compare with null.
    vi.stubGlobal('fetch', vi.fn(async () => ({
      ok: true,
      status: 200,
      statusText: '',
      text: async () => JSON.stringify({
        profiles: [{ id: 'p1', name: 'Local reader', enabled: true, scope: 'LOCAL', keyAvailable: false }],
      }),
    } as Response)));

    const [profile] = await listOcrProfiles();

    expect(profile.apiKeyEnvironmentVariable).toBeNull();
    expect(profile.imageCapabilityMeasured).toBeNull();
    expect(profile.imageCapabilityCheckedAt).toBeNull();
  });
});

describe('reading workflow requests', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('uses the pinned method routes and sends the chosen method and idempotency keys unchanged', async () => {
    const calls: { url: string; body?: unknown }[] = [];
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url === '/api/session') return { ok: true, status: 200, text: async () => JSON.stringify({ csrfToken: 'token' }) } as Response;
      calls.push({ url, body: init?.body ? JSON.parse(String(init.body)) : undefined });
      const body = url.includes('reading-methods') ? { methods: [], default: 'surya' }
        : url === '/api/imports/preview' ? { files: [], totalPages: 3, atLeast: false, destination: 'this machine', external: false, estimatedCostUsd: null, costBasis: null, previewHash: 'hash' }
          : url.includes('/ocr/preview') ? { previewId: 'preview', pageTotal: 3, externalPageUpperBound: 3 }
            : url === '/api/imports' ? { job: { id: 'job-1' } }
              : { operationId: 'op-1' };
      return { ok: true, status: 200, text: async () => JSON.stringify(body) } as Response;
    }));

    await listReadingMethods('a/b');
    const request = { collection: 'c', paths: ['/a.pdf'], recursive: true, include: ['pdf'], exclude: [], method: 'surya' };
    await previewImport(request);
    await startImport({ ...request, previewHash: 'hash', requestId: 'request-1' });
    await previewRescan('c', 'd/1', 'llm:p1', { mode: 'explicit', ordinals: [2, 4] });
    await startRescan('c', 'd/1', { previewId: 'p', requestId: 'request-2' });
    await resumeRescan('c', 'd/1', 'operation/1');

    expect(calls).toEqual([
      { url: '/api/collections/a%2Fb/reading-methods', body: undefined },
      { url: '/api/imports/preview', body: request },
      { url: '/api/imports', body: { ...request, previewHash: 'hash', requestId: 'request-1' } },
      { url: '/api/collections/c/documents/d%2F1/ocr/preview', body: { method: 'llm:p1', selection: { mode: 'explicit', ordinals: [2, 4] } } },
      { url: '/api/collections/c/documents/d%2F1/ocr/rescan', body: { previewId: 'p', requestId: 'request-2' } },
      { url: '/api/collections/c/documents/d%2F1/ocr/operations/operation%2F1/resume', body: undefined },
    ]);
  });

  it('surfaces a stale preview conflict as PREVIEW_STALE', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      if (String(input) === '/api/session') return { ok: true, status: 200, text: async () => JSON.stringify({ csrfToken: 'token' }) } as Response;
      return { ok: false, status: 409, statusText: 'Conflict', text: async () => JSON.stringify({ error: { code: 'PREVIEW_STALE', message: 'The files changed; review the summary again.' } }) } as Response;
    }));
    await expect(startImport({ collection: 'c', paths: [], recursive: false, include: [], exclude: [], method: 'tesseract', previewHash: 'old', requestId: 'id' }))
      .rejects.toMatchObject({ code: 'PREVIEW_STALE' });
  });
});


describe('model catalog metadata decoding', () => {
  afterEach(() => vi.unstubAllGlobals());
  it('normalizes omitted metadata while preserving stated limits and zero prices', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => new Response(JSON.stringify(
      String(input) === '/api/session' ? { csrfToken: 'test' } : {
        live: true, models: [
          { id: 'qwen3-vl:8b', priceKnown: false },
          { id: 'known', contextWindow: 32000, maxOutputTokens: 4096, inputPricePerMillion: 0, outputPricePerMillion: 0, priceKnown: true, imageInput: true },
        ],
      }), { status: 200 })));
    const result = await fetchLlmCatalog('OPENAI_COMPATIBLE', 'http://localhost:11434/v1', null);
    expect(result.models[0]).toEqual({
      id: 'qwen3-vl:8b', priceKnown: false, contextWindow: null, maxOutputTokens: null,
      inputPricePerMillion: null, outputPricePerMillion: null, cacheReadPricePerMillion: null, imageInput: null,
    });
    expect(result.models[1]).toEqual({
      id: 'known', contextWindow: 32000, maxOutputTokens: 4096, inputPricePerMillion: 0, outputPricePerMillion: 0,
      cacheReadPricePerMillion: null, priceKnown: true, imageInput: true,
    });
  });
});

describe('selectable PDF status decoding', () => {
  afterEach(() => vi.unstubAllGlobals());

  const answer = (body: unknown, status = 200) => vi.fn(async (_input: RequestInfo | URL, _init?: RequestInit) => new Response(JSON.stringify(body), { status }));

  it('decodes NOT_CREATED with the explicit nulls the server writes and no counts', async () => {
    vi.stubGlobal('fetch', answer({
      revisionId: 'rev-1', key: null, state: 'NOT_CREATED', selectablePages: 0, totalPages: null,
      blankPages: 0, unavailablePages: [], reasonCode: null,
    }));

    expect(await getSelectablePdfStatus('Archive', 'doc-1', 'rev-1')).toEqual({
      revisionId: 'rev-1', key: null, state: 'NOT_CREATED', selectablePages: 0, totalPages: null,
      blankPages: 0, unavailablePages: [], reasonCode: null,
    });
  });

  it('reads omitted nullable fields as null for every documented state', async () => {
    const key = 'a'.repeat(64);
    for (const state of ['QUEUED', 'BUILDING', 'READY', 'PARTIAL', 'FAILED'] as const) {
      vi.stubGlobal('fetch', answer({
        revisionId: 'rev-1', state, selectablePages: 0, blankPages: 0, unavailablePages: [],
        ...(state === 'FAILED' ? { reasonCode: 'ARTIFACT_VERIFICATION_FAILED' } : { key }),
      }));
      const status = await getSelectablePdfStatus('Archive', 'doc-1', 'rev-1');
      expect(status.state).toBe(state);
      expect(status.totalPages).toBeNull();
      expect(status.key).toBe(state === 'FAILED' ? null : key);
      expect(status.reasonCode).toBe(state === 'FAILED' ? 'ARTIFACT_VERIFICATION_FAILED' : null);
      expect(status.unavailablePages).toEqual([]);
    }
  });

  it('decodes READY and BLANK-only counts and keeps PARTIAL unavailable pages with their reasons', async () => {
    const key = 'b'.repeat(64);
    vi.stubGlobal('fetch', answer({
      revisionId: 'rev-1', key, state: 'PARTIAL', selectablePages: 1, totalPages: 3, blankPages: 0,
      unavailablePages: [{ ordinal: 1, coverage: 'UNAVAILABLE', reasonCode: 'PDF_TEXT_ALIGNMENT_AMBIGUOUS' }],
      reasonCode: null,
    }));
    const partial = await getSelectablePdfStatus('Archive', 'doc-1', 'rev-1');
    expect(partial).toEqual({
      revisionId: 'rev-1', key, state: 'PARTIAL', selectablePages: 1, totalPages: 3, blankPages: 0,
      unavailablePages: [{ ordinal: 1, coverage: 'UNAVAILABLE', reasonCode: 'PDF_TEXT_ALIGNMENT_AMBIGUOUS' }],
      reasonCode: null,
    });

    vi.stubGlobal('fetch', answer({
      revisionId: 'rev-1', key, state: 'READY', selectablePages: 0, totalPages: 2, blankPages: 2,
      unavailablePages: [], reasonCode: null,
    }));
    expect(await getSelectablePdfStatus('Archive', 'doc-1', 'rev-1')).toMatchObject({
      state: 'READY', selectablePages: 0, totalPages: 2, blankPages: 2,
    });
  });

  it('refuses a state the page does not know and a queued artifact without its key', async () => {
    vi.stubGlobal('fetch', answer({ revisionId: 'rev-1', key: null, state: 'SOMETHING_ELSE', selectablePages: 0, blankPages: 0, unavailablePages: [] }));
    await expect(getSelectablePdfStatus('Archive', 'doc-1', 'rev-1')).rejects.toMatchObject({ code: 'INVALID_RESPONSE' });

    vi.stubGlobal('fetch', answer({ revisionId: 'rev-1', key: null, state: 'QUEUED', selectablePages: 0, totalPages: null, blankPages: 0, unavailablePages: [], reasonCode: null }));
    await expect(getSelectablePdfStatus('Archive', 'doc-1', 'rev-1')).rejects.toMatchObject({ code: 'INVALID_RESPONSE' });
  });

  it('reads the status with an encoded revision and never sends a request that generates', async () => {
    const fetchMock = answer({ revisionId: 'rev/1', key: null, state: 'NOT_CREATED', selectablePages: 0, totalPages: null, blankPages: 0, unavailablePages: [], reasonCode: null });
    vi.stubGlobal('fetch', fetchMock);

    await getSelectablePdfStatus('Archive One', 'doc/1', 'rev/1');

    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(fetchMock.mock.calls[0][0]).toBe('/api/collections/Archive%20One/documents/doc%2F1/selectable-pdf?revision=rev%2F1');
    expect(fetchMock.mock.calls[0][1]).toBeUndefined();
  });

  it('surfaces the server refusal code for an unknown revision as an ApiError', async () => {
    vi.stubGlobal('fetch', answer({ error: { code: 'NOT_FOUND', message: 'no revision' } }, 404));
    await expect(getSelectablePdfStatus('Archive', 'doc-1', 'rev-9')).rejects.toMatchObject({ code: 'NOT_FOUND', status: 404 });
  });
});

describe('selectable PDF creation', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('posts the revision with the explicit retry flag and the session CSRF token, then decodes the answer', async () => {
    const calls: { url: string; init?: RequestInit }[] = [];
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      calls.push({ url: String(input), init });
      if (String(input) === '/api/session') return new Response(JSON.stringify({ csrfToken: 'csrf-pdf' }), { status: 200 });
      return new Response(JSON.stringify({
        revisionId: 'rev-1', key: 'c'.repeat(64), state: 'QUEUED', selectablePages: 0,
        totalPages: null, blankPages: 0, unavailablePages: [], reasonCode: null,
      }), { status: 200 });
    }));

    const status = await createSelectablePdf('Archive', 'doc-1', { revisionId: 'rev-1', retry: false });

    expect(status.state).toBe('QUEUED');
    const post = calls.find((call) => call.url === '/api/collections/Archive/documents/doc-1/selectable-pdf');
    expect(post?.init?.method).toBe('POST');
    expect(JSON.parse(String(post?.init?.body))).toEqual({ revisionId: 'rev-1', retry: false });
    // The session token is cached for the module's life, so the test asserts that a token is sent, not its value.
    expect((post?.init?.headers as Record<string, string>)['X-InfoScry-Csrf']).toEqual(expect.any(String));
    expect((post?.init?.headers as Record<string, string>)['X-InfoScry-Csrf'].length).toBeGreaterThan(0);
  });

  it('sends retry true only when the caller asks for a retry', async () => {
    const bodies: unknown[] = [];
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      if (String(input) === '/api/session') return new Response(JSON.stringify({ csrfToken: 'csrf-pdf' }), { status: 200 });
      bodies.push(JSON.parse(String(init?.body)));
      return new Response(JSON.stringify({
        revisionId: 'rev-1', key: 'd'.repeat(64), state: 'QUEUED', selectablePages: 0,
        totalPages: null, blankPages: 0, unavailablePages: [], reasonCode: null,
      }), { status: 200 });
    }));

    await createSelectablePdf('Archive', 'doc-1', { revisionId: 'rev-1', retry: true });

    expect(bodies).toEqual([{ revisionId: 'rev-1', retry: true }]);
  });

  it('keeps the server conflict code so a caller can offer a retry without a raw message', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      if (String(input) === '/api/session') return new Response(JSON.stringify({ csrfToken: 'csrf-pdf' }), { status: 200 });
      return new Response(JSON.stringify({ error: { code: 'DOCUMENT_BEING_DELETED', message: 'The document is being deleted.' } }), { status: 409 });
    }));

    await expect(createSelectablePdf('Archive', 'doc-1', { revisionId: 'rev-1', retry: false }))
      .rejects.toMatchObject({ code: 'DOCUMENT_BEING_DELETED', status: 409 });
  });
});

describe('selectable PDF file links', () => {
  it('builds encoded relative inline and attachment links for exactly the named revision and key', () => {
    const key = 'e'.repeat(64);
    expect(selectablePdfFileHref('Archive One', 'doc/1', 'rev/1', key, true))
      .toBe(`/api/collections/Archive%20One/documents/doc%2F1/selectable-pdf/file?revision=rev%2F1&key=${key}&inline=true`);
    expect(selectablePdfFileHref('Archive', 'doc-1', 'rev-1', key, false))
      .toBe(`/api/collections/Archive/documents/doc-1/selectable-pdf/file?revision=rev-1&key=${key}&inline=false`);
  });
});
