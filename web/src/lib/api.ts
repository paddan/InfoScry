/**
 * The typed client for InfoScry's local API.
 *
 * Same-origin only: the server answers loopback callers with a loopback `Host` header and offers no
 * cross-origin access, so this client never builds a URL and never needs CORS. Mutations carry the
 * session's CSRF token, which the server issues from `/api/session`. The runtime bearer token is the
 * CLI's credential and never reaches a browser.
 */

export type Collection = {
  id: string;
  name: string;
  ocrLanguages: string;
  createdAt: string;
  updatedAt: string;
  description?: string | null;
  lifecycle: 'ACTIVE' | 'DELETING';
};

export type SearchMode = 'KEYWORD' | 'SEMANTIC' | 'HYBRID';
export type SearchFilters = {
  mediaType?: string;
  path?: string;
  text?: string;
  from?: string;
  until?: string;
  status?: string;
  ocrOnly?: boolean;
};

export type SearchHit = {
  collectionId: string;
  documentId: string;
  title: string;
  unitId: string;
  chunkOrdinal: number;
  text: string;
  highlighted: string | null;
  locator: unknown;
  locatorLabel: string;
  matchedBy: string[];
};

export type SearchResponse = { hits: SearchHit[]; staleFiltered: number };

export type AskEvidence = {
  id: string;
  documentId: string;
  unitId: string;
  locator: unknown;
  locatorLabel: string;
};

export type AskEvent =
  | { type: 'delta'; text: string }
  | { type: 'usage'; inputTokens: number; outputTokens: number }
  | { type: 'citation'; id: string; valid: boolean }
  | { type: 'done'; text: string; evidence: AskEvidence[] }
  | { type: 'error'; code: string; message: string };

export type InvestigateEvidence = AskEvidence & { locatorLabel: string };
export type InvestigateMessage = { role: 'user' | 'assistant'; text: string };
export type InvestigateActivity = { name: string; resultCode: string; durationMs: number };
export type InvestigationSummary = { id: string; createdAt: string; question: string };
export type InvestigationHistory = {
  id: string;
  messages: InvestigateMessage[];
  evidence: InvestigateEvidence[];
  inputTokens: number;
  outputTokens: number;
  costUsd: number;
  activity: InvestigateActivity[];
};
export type InvestigateEvent =
  | { type: 'started'; id: string }
  | { type: 'delta'; text: string }
  | { type: 'tool'; callId: string; name: string; arguments?: string; resultCode?: string; durationMs?: number }
  | { type: 'usage'; inputTokens: number; outputTokens: number }
  | { type: 'citation'; id: string; valid: boolean }
  | { type: 'done'; text: string; evidence: InvestigateEvidence[] }
  | { type: 'error'; code: string; message: string };

export type LlmProfilePrice = {
  name: string;
  inputPricePerMillion: number;
  outputPricePerMillion: number;
};

export type SourceContentResponse = {
  id: string;
  documentId: string;
  ordinal: number;
  locator: unknown;
  text: string;
  offset: number;
  totalChars: number;
  truncated: boolean;
};

export const SOURCE_PAGE_CHARS = 16_384;

/** A failure the server named. `code` is stable; `message` is written for the person reading it. */
export class ApiError extends Error {
  readonly code: string;

  constructor(code: string, message: string) {
    super(message);
    this.name = 'ApiError';
    this.code = code;
  }
}

const CSRF_HEADER = 'X-InfoScry-Csrf';

let sessionToken: string | null = null;

/** Collections this data directory holds, ordered by name. */
export async function listCollections(): Promise<Collection[]> {
  const body = (await readJson(await fetch('/api/collections'))) as { collections: Collection[] };
  return body.collections;
}

/** Search one collection using the existing search route. */
export async function searchCollection(collection: string, query: string, mode: SearchMode, filters: SearchFilters = {}): Promise<SearchResponse> {
  const parameters = new URLSearchParams({ collection, mode, q: query });
  if (filters.mediaType?.trim()) parameters.append('mediaType', filters.mediaType.trim());
  if (filters.path?.trim()) parameters.set('path', filters.path.trim());
  if (filters.text?.trim()) parameters.set('text', filters.text.trim());
  if (filters.from) parameters.set('from', `${filters.from}T00:00:00.000000000Z`);
  if (filters.until) parameters.set('until', `${filters.until}T23:59:59Z`);
  if (filters.status) parameters.append('status', filters.status);
  if (filters.ocrOnly) parameters.set('ocrOnly', 'true');
  const response = await fetch(`/api/search?${parameters.toString()}`);
  return (await readJson(response)) as SearchResponse;
}

export async function askDefaultProfile(): Promise<string> {
  const body = (await readJson(await fetch('/api/llm/defaults/ASK'))) as { profileName: string };
  return body.profileName;
}

export async function listLlmProfilePrices(): Promise<LlmProfilePrice[]> {
  const body = (await readJson(await fetch('/api/llm/profiles'))) as { profiles: LlmProfilePrice[] };
  return body.profiles;
}

/** Start an Ask stream with the browser's session CSRF token. */
export async function startAsk(
  collection: string,
  question: string,
  profile: string,
  signal?: AbortSignal,
): Promise<Response> {
  const send = async (): Promise<Response> => fetch('/api/ask', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', [CSRF_HEADER]: await csrfToken() },
    body: JSON.stringify({ collection, question, profile }),
    signal,
  });
  let response = await send();
  if (!response.ok) {
    try {
      await readJson(response);
    } catch (failure) {
      if (!(failure instanceof ApiError) || failure.code !== 'MUTATION_REQUIRES_CREDENTIALS' || signal?.aborted) {
        throw failure;
      }
      sessionToken = null;
      response = await send();
    }
  }
  return response;
}

/** Decode the API's newline-delimited SSE data frames, including frames split across network chunks. */
export async function* readAskEvents(response: Response, signal?: AbortSignal): AsyncGenerator<AskEvent> {
  if (!response.ok) {
    await readJson(response);
  }
  if (response.body === null) throw new ApiError('EMPTY_STREAM', 'the server returned an empty Ask stream');
  const reader = response.body.getReader();
  const cancelReader = (): void => { void reader.cancel().catch(() => undefined); };
  signal?.addEventListener('abort', cancelReader, { once: true });
  const decoder = new TextDecoder();
  let buffer = '';
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (signal?.aborted) break;
      buffer += decoder.decode(value, { stream: !done });
      let boundary = buffer.indexOf('\n\n');
      while (boundary >= 0) {
        const frame = buffer.slice(0, boundary);
        buffer = buffer.slice(boundary + 2);
        const event = parseAskFrame(frame);
        if (event !== null) yield event;
        boundary = buffer.indexOf('\n\n');
      }
      if (done) break;
    }
    if (buffer.trim() !== '') {
      const event = parseAskFrame(buffer);
      if (event !== null) yield event;
    }
  } finally {
    signal?.removeEventListener('abort', cancelReader);
    if (signal?.aborted) await reader.cancel().catch(() => undefined);
    reader.releaseLock();
  }
}

function parseAskFrame(frame: string): AskEvent | null {
  const data = frame.split(/\r?\n/).find((line) => line.startsWith('data:'));
  if (data === undefined) return null;
  try {
    const event = JSON.parse(data.slice(5).trim()) as AskEvent;
    return ['delta', 'usage', 'citation', 'done', 'error'].includes(event.type) ? event : null;
  } catch {
    return null;
  }
}

export async function investigateDefaultProfile(): Promise<string> {
  const body = (await readJson(await fetch('/api/llm/defaults/INVESTIGATE'))) as { profileName: string };
  return body.profileName;
}

export async function listInvestigations(collectionId: string): Promise<InvestigationSummary[]> {
  const collection = encodeURIComponent(collectionId);
  const body = (await readJson(await fetch(`/api/collections/${collection}/investigations`))) as { investigations: InvestigationSummary[] };
  return body.investigations;
}

export async function getInvestigation(collectionId: string, conversationId: string): Promise<InvestigationHistory> {
  const collection = encodeURIComponent(collectionId);
  const id = encodeURIComponent(conversationId);
  const body = (await readJson(await fetch(`/api/collections/${collection}/investigations/${id}`))) as { investigation: InvestigationHistory };
  return body.investigation;
}

export async function startInvestigation(
  collection: string,
  question: string,
  profile: string,
  signal?: AbortSignal,
): Promise<Response> {
  return investigateRequest('/api/investigations', { collection, question, profile }, signal);
}

export async function continueInvestigation(
  conversationId: string,
  collection: string,
  question: string,
  profile: string,
  signal?: AbortSignal,
): Promise<Response> {
  return investigateRequest(`/api/investigations/${encodeURIComponent(conversationId)}/continue`, { collection, question, profile }, signal);
}

export async function cancelInvestigation(conversationId: string): Promise<void> {
  const response = await fetch(`/api/investigations/${encodeURIComponent(conversationId)}/cancel`, {
    method: 'POST',
    headers: { [CSRF_HEADER]: await csrfToken() },
  });
  await readJson(response);
}

async function investigateRequest(path: string, body: unknown, signal?: AbortSignal): Promise<Response> {
  const send = async (): Promise<Response> => fetch(path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', [CSRF_HEADER]: await csrfToken() },
    body: JSON.stringify(body),
    signal,
  });
  let response = await send();
  if (!response.ok) {
    try {
      await readJson(response);
    } catch (failure) {
      if (!(failure instanceof ApiError) || failure.code !== 'MUTATION_REQUIRES_CREDENTIALS' || signal?.aborted) throw failure;
      sessionToken = null;
      response = await send();
    }
  }
  return response;
}

export async function* readInvestigationEvents(response: Response, signal?: AbortSignal): AsyncGenerator<InvestigateEvent> {
  if (!response.ok) await readJson(response);
  if (response.body === null) throw new ApiError('EMPTY_STREAM', 'the server returned an empty Investigate stream');
  const reader = response.body.getReader();
  const cancelReader = (): void => { void reader.cancel().catch(() => undefined); };
  signal?.addEventListener('abort', cancelReader, { once: true });
  const decoder = new TextDecoder();
  let buffer = '';
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (signal?.aborted) break;
      buffer += decoder.decode(value, { stream: !done });
      let boundary = buffer.indexOf('\n\n');
      while (boundary >= 0) {
        const frame = buffer.slice(0, boundary);
        buffer = buffer.slice(boundary + 2);
        const event = parseInvestigationFrame(frame);
        if (event !== null) yield event;
        boundary = buffer.indexOf('\n\n');
      }
      if (done) break;
    }
    if (buffer.trim() !== '') {
      const event = parseInvestigationFrame(buffer);
      if (event !== null) yield event;
    }
  } finally {
    signal?.removeEventListener('abort', cancelReader);
    if (signal?.aborted) await reader.cancel().catch(() => undefined);
    reader.releaseLock();
  }
}

function parseInvestigationFrame(frame: string): InvestigateEvent | null {
  const data = frame.split(/\r?\n/).find((line) => line.startsWith('data:'));
  if (data === undefined || data.slice(5).trim() === '[DONE]') return null;
  try {
    const event = JSON.parse(data.slice(5).trim()) as InvestigateEvent;
    return ['started', 'delta', 'tool', 'usage', 'citation', 'done', 'error'].includes(event.type) ? event : null;
  } catch {
    return null;
  }
}

/** Read one bounded page from the exact source unit represented by a search hit. */
export async function readSource(
  collectionId: string,
  sourceId: string,
  offset = 0,
  limit = SOURCE_PAGE_CHARS,
): Promise<SourceContentResponse> {
  const collection = encodeURIComponent(collectionId);
  const source = encodeURIComponent(sourceId);
  const response = await fetch(`/api/collections/${collection}/sources/${source}?offset=${offset}&limit=${limit}`);
  return (await readJson(response)) as SourceContentResponse;
}

/** Creates a collection, or throws [ApiError] with the server's reason. */
export async function createCollection(name: string, description?: string): Promise<Collection> {
  const request: { name: string; description?: string } = { name };
  if (description !== undefined && description.trim() !== '') {
    request.description = description;
  }
  const response = await fetch('/api/collections', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      [CSRF_HEADER]: await csrfToken(),
    },
    body: JSON.stringify(request),
  });
  const body = (await readJson(response)) as { collection: Collection };
  return body.collection;
}

/**
 * The session's CSRF token, fetched once.
 *
 * The server generates it per launch, so it is not persisted anywhere: a reload asks again.
 */
async function csrfToken(): Promise<string> {
  if (sessionToken !== null) {
    return sessionToken;
  }
  const body = (await readJson(await fetch('/api/session'))) as { csrfToken?: string };
  if (typeof body.csrfToken !== 'string' || body.csrfToken === '') {
    throw new ApiError('SESSION_WITHOUT_TOKEN', 'the server did not issue a session token');
  }
  sessionToken = body.csrfToken;
  return sessionToken;
}

async function readJson(response: Response): Promise<unknown> {
  const text = await response.text();
  let body: unknown = null;
  if (text !== '') {
    try {
      body = JSON.parse(text);
    } catch {
      throw new ApiError('INVALID_RESPONSE', `the server answered ${response.status} with a body that is not JSON`);
    }
  }
  if (!response.ok) {
    const error = (body as { error?: { code?: string; message?: string } } | null)?.error;
    throw new ApiError(error?.code ?? `HTTP_${response.status}`, error?.message ?? response.statusText);
  }
  return body;
}
