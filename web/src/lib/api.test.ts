import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiError, continueInvestigation, createCollection, getCollectionDocumentSummary, listCollections, listOcrProfiles, listReadingMethods, previewImport, previewRescan, readInvestigationEvents, readSource, renameCollection, startImport, startInvestigation, startRescan, updateCollectionOcrSettings } from './api';

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
    await previewRescan('c', 'd/1', 'llm:p1');
    await startRescan('c', 'd/1', { previewId: 'p', requestId: 'request-2' });

    expect(calls).toEqual([
      { url: '/api/collections/a%2Fb/reading-methods', body: undefined },
      { url: '/api/imports/preview', body: request },
      { url: '/api/imports', body: { ...request, previewHash: 'hash', requestId: 'request-1' } },
      { url: '/api/collections/c/documents/d%2F1/ocr/preview', body: { method: 'llm:p1' } },
      { url: '/api/collections/c/documents/d%2F1/ocr/rescan', body: { previewId: 'p', requestId: 'request-2' } },
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
