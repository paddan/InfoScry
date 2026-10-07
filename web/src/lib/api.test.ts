import { afterEach, describe, expect, it, vi } from 'vitest';
import { continueInvestigation, getCollectionDocumentSummary, listOcrProfiles, readInvestigationEvents, readSource, startInvestigation } from './api';

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
