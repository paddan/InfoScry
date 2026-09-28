/**
 * The typed client for InfoScry's local API.
 *
 * Same-origin only: the server answers loopback callers with a loopback `Host` header and offers no
 * cross-origin access, so this client never builds a URL and never needs CORS. Mutations carry the
 * session's CSRF token, which the server issues from `/api/session`. The runtime bearer token is the
 * CLI's credential and never reaches a browser.
 */

import type { InvestigationLimits } from './investigationLimits';

export type Collection = {
  id: string;
  name: string;
  ocrLanguages: string;
  createdAt: string;
  updatedAt: string;
  description?: string | null;
  lifecycle: 'ACTIVE' | 'DELETING';
  /** Documents the collection holds; the listing route counts them, a read by id reports `0`. */
  documentCount: number;
};

/** The lifecycle of a job record; stages inside a running job are reported separately. */
export type JobState = 'QUEUED' | 'RUNNING' | 'COMPLETE' | 'FAILED' | 'CANCELLED';

export type JobType = 'IMPORT' | 'REINDEX' | 'RETRY';

/**
 * One persistent job. Optional fields may be absent from the wire (nulls are omitted), so callers
 * treat `collectionId`, `stage`, `currentItem` and `errorCode` as possibly undefined.
 */
export type JobApiView = {
  id: string;
  type: JobType;
  state: JobState;
  createdAt: string;
  updatedAt: string;
  collectionId?: string | null;
  stage?: string | null;
  /** The file the job is working on now, as its own name; never the path it was selected from. */
  currentItem?: string | null;
  completed: number;
  total: number;
  errorCode?: string | null;
  cancelRequested: boolean;
};

/**
 * What one selected file ended as. `PENDING` is a queued file whose work has not finished: an import that
 * is still copying, or a file that never became a document, stays visible as an item rather than as a row.
 */
export type ImportItemOutcome = 'PENDING' | 'IMPORTED' | 'DUPLICATE' | 'FAILED' | 'CANCELLED';

/**
 * One source file of an import, and what happened to it. Nulls are omitted from the wire.
 *
 * Only the file's own name crosses, never the absolute path it was selected from: this is a management
 * read of the durable import history, and the archive's stored source path stays behind it.
 */
export type ImportItemApiView = {
  id: string;
  jobId: string;
  documentId: string | null;
  sourceName?: string | null;
  outcome: ImportItemOutcome;
  errorCode?: string | null;
  errorMessage?: string | null;
  createdAt: string;
  updatedAt: string;
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
  | { type: 'done'; text: string; evidence: AskEvidence[]; conversationId?: string }
  | { type: 'error'; code: string; message: string };

/**
 * One answer the server kept: the question asked, the answer it produced, the sources it cited, and
 * what it cost. The title was written from the opening question and always carries a value the
 * server fell back to, so a row never has to handle an empty label.
 */
export type AskHistoryEntry = {
  id: string;
  createdAt: string;
  question: string;
  title: string;
  answer: string;
  evidence: AskEvidence[];
  inputTokens: number;
  outputTokens: number;
  costUsd: number;
};

export type InvestigateEvidence = AskEvidence & { locatorLabel: string };
export type InvestigateMessage = { role: 'user' | 'assistant'; text: string };
export type InvestigateActivity = { name: string; resultCode: string; durationMs: number };
export type InvestigationSummary = { id: string; createdAt: string; question: string; title: string };
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
  | { type: 'limit'; code: string; message: string }
  | { type: 'answer-start' }
  | { type: 'done'; text: string; evidence?: InvestigateEvidence[] }
  | { type: 'error'; code: string; message: string };

export type LlmProfilePrice = {
  name: string;
  inputPricePerMillion: number;
  outputPricePerMillion: number;
};

export type LlmProvider = 'OPENAI_COMPATIBLE' | 'ANTHROPIC';

export type LlmProfile = {
  id: string;
  name: string;
  provider: LlmProvider;
  endpoint: string;
  model: string;
  contextWindow: number;
  maxOutputTokens: number;
  inputPricePerMillion: number;
  outputPricePerMillion: number;
  cacheReadPricePerMillion: number;
  enabled: boolean;
  apiKeyEnvironmentVariable: string | null;
  keyAvailable: boolean;
  toolCallingMeasured: boolean | null;
  capabilityCheckedAt: string | null;
};

/** The fields a profile mutation accepts; the server owns id and capability measurements. */
export type LlmProfileInput = Omit<LlmProfile, 'id' | 'keyAvailable' | 'toolCallingMeasured' | 'capabilityCheckedAt'>;

export type LlmDefaults = { ASK: string | null; INVESTIGATE: string | null };

export type LlmProfiles = { profiles: LlmProfile[]; defaults: LlmDefaults };

/** One curated provider configuration the Admin view offers as a preset. */
export type LlmPreset = {
  id: string;
  label: string;
  provider: LlmProvider;
  endpoint: string;
  apiKeyEnvironmentVariable: string | null;
};

/** One model a provider lists, with the fields a profile may adopt; null means unknown. */
export type LlmCatalogModel = {
  id: string;
  contextWindow: number | null;
  maxOutputTokens: number | null;
  inputPricePerMillion: number | null;
  outputPricePerMillion: number | null;
  cacheReadPricePerMillion: number | null;
  priceKnown: boolean;
};

export type LlmCatalog = { live: boolean; models: LlmCatalogModel[] };

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

/** The document statuses the pipeline moves a document through; the server owns this vocabulary. */
export type DocumentStatusName =
  | 'QUEUED'
  | 'COPYING'
  | 'EXTRACTING'
  | 'OCR'
  | 'CHUNKING'
  | 'EMBEDDING'
  | 'INDEXING'
  | 'COMPLETE'
  | 'COMPLETE_WITH_WARNINGS'
  | 'FAILED'
  | 'CANCELLED'
  | 'NEEDS_TOOL';

/** What one unit of a document is; the UI chooses the words a reader actually sees. */
export type UnitKindName = 'PAGE' | 'SECTION' | 'SLIDE' | 'SHEET' | 'LINE' | 'IMAGE';

/**
 * What a document's current attempt has committed.
 *
 * `totalUnits` is null when no extractor announced a total, and the UI must then show a count without a
 * denominator rather than invent one. The two method counters are null when no committed unit carries a
 * stored method, which is what a document extracted before methods were recorded looks like: that is
 * "unknown", not zero.
 */
export type DocumentProgressView = {
  unitKind?: UnitKindName | null;
  totalUnits?: number | null;
  processedUnits: number;
  failedUnits: number;
  directTextUnits?: number | null;
  ocrUnits?: number | null;
};

/**
 * One document row of a collection listing. Safe metadata only: the managed copy's path, the content
 * hash and the stored error message are never part of the wire, so a row cannot carry them further.
 */
export type DocumentApiRow = {
  id: string;
  collectionId: string;
  mediaType: string;
  originalFilename: string;
  sizeBytes: number;
  status: DocumentStatusName;
  createdAt: string;
  updatedAt: string;
  title?: string | null;
  author?: string | null;
  language?: string | null;
  errorCode?: string | null;
  /** Compact progress of the current attempt; absent while the document has no attempt recorded. */
  progress?: DocumentProgressView | null;
};

/** A page of one collection's documents plus the total the same criteria match. */
export type DocumentsPage = { documents: DocumentApiRow[]; total: number };

/** One document's collection-scoped detail: its row, its curated error sentence, its first source unit. */
export type DocumentDetail = {
  document: DocumentApiRow;
  /** InfoScry's own sentence for the error code; never the stored message, which may quote the document. */
  errorMessage?: string | null;
  /** The first content unit a reader can open, or null when the document has none yet. */
  sourceId?: string | null;
  /** The same counts the row shows, read for this one document. */
  progress?: DocumentProgressView | null;
  /**
   * Whether Retry is offered: the document's status has unfinished business, nothing is deleting it, and
   * its managed copy is still there. Admission can still refuse for a reason only it knows, which is why
   * a refusal comes back with its own sentence.
   */
  retryEligible?: boolean;
  /** InfoScry's own sentences for the codes of the units this attempt could not read. */
  warnings?: string[];
};

export type DocumentSort = 'newest' | 'oldest' | 'name-asc' | 'name-desc';

export type DocumentListOptions = {
  /** A literal, case-insensitive contains match on the original filename only. */
  q?: string;
  status?: DocumentStatusName;
  sort?: DocumentSort;
  limit?: number;
  offset?: number;
};

/** One page of a collection's documents, filtered and ordered by the server before it is paged. */
export async function listCollectionDocuments(
  collectionId: string,
  options: DocumentListOptions = {},
): Promise<DocumentsPage> {
  const parameters = new URLSearchParams();
  if (options.q?.trim()) parameters.set('q', options.q.trim());
  if (options.status) parameters.set('status', options.status);
  if (options.sort) parameters.set('sort', options.sort);
  if (options.limit !== undefined) parameters.set('limit', String(options.limit));
  if (options.offset) parameters.set('offset', String(options.offset));
  const collection = encodeURIComponent(collectionId);
  const query = parameters.toString();
  return (await readJson(await fetch(`/api/collections/${collection}/documents${query === '' ? '' : `?${query}`}`))) as DocumentsPage;
}

/** One document's collection-scoped detail. A cross-collection or unknown id is a 404. */
export async function getCollectionDocument(collectionId: string, documentId: string): Promise<DocumentDetail> {
  const collection = encodeURIComponent(collectionId);
  const document = encodeURIComponent(documentId);
  return (await readJson(await fetch(`/api/collections/${collection}/documents/${document}`))) as DocumentDetail;
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

/** The Ask answers this collection kept, newest first. */
export async function listAsks(collectionId: string): Promise<AskHistoryEntry[]> {
  const collection = encodeURIComponent(collectionId);
  const body = (await readJson(await fetch(`/api/collections/${collection}/asks`))) as { asks: AskHistoryEntry[] };
  return body.asks;
}

export async function listLlmProfilePrices(): Promise<LlmProfilePrice[]> {
  const body = (await readJson(await fetch('/api/llm/profiles'))) as { profiles: LlmProfilePrice[] };
  return body.profiles;
}

/** Full profile list plus the per-role defaults (profile id per role, or null). */
export async function listLlmProfiles(): Promise<LlmProfiles> {
  const body = (await readJson(await fetch('/api/llm/profiles'))) as { profiles: LlmProfile[]; defaults: LlmDefaults };
  return { profiles: body.profiles, defaults: body.defaults };
}

/** The provider configurations the Admin view offers as presets. */
export async function listLlmPresets(): Promise<LlmPreset[]> {
  const body = (await readJson(await fetch('/api/llm/presets'))) as { presets: LlmPreset[] };
  return body.presets;
}

/**
 * One provider's model list, live when reachable and the static fallback otherwise.
 *
 * The route sends this server's API key to the endpoint, so it demands a session credential: the
 * browser presents its CSRF token. After a server restart the cached token is stale; the server
 * answers 401 and the request is retried once with a fresh token, exactly like [mutate].
 */
export async function fetchLlmCatalog(
  provider: LlmProvider,
  endpoint: string,
  apiKeyEnvironmentVariable: string | null,
): Promise<LlmCatalog> {
  const parameters = new URLSearchParams({ provider, endpoint });
  if (apiKeyEnvironmentVariable !== null) {
    parameters.append('apiKeyEnvironmentVariable', apiKeyEnvironmentVariable);
  }
  const url = `/api/llm/catalog?${parameters.toString()}`;
  const send = async (): Promise<Response> => fetch(url, {
    headers: { [CSRF_HEADER]: await csrfToken() },
  });
  let response = await send();
  if (!response.ok) {
    try {
      await readJson(response);
    } catch (failure) {
      if (!(failure instanceof ApiError) || failure.code !== 'CATALOG_REQUIRES_CREDENTIALS') throw failure;
      sessionToken = null;
      response = await send();
    }
  }
  return (await readJson(response)) as LlmCatalog;
}

export async function createLlmProfile(profile: LlmProfileInput): Promise<LlmProfile> {
  const body = (await mutate('/api/llm/profiles', 'POST', profile)) as { profile: LlmProfile };
  return body.profile;
}

export async function updateLlmProfile(id: string, profile: LlmProfileInput): Promise<LlmProfile> {
  const body = (await mutate(`/api/llm/profiles/${encodeURIComponent(id)}`, 'PUT', profile)) as { profile: LlmProfile };
  return body.profile;
}

export async function deleteLlmProfile(id: string): Promise<void> {
  await mutate(`/api/llm/profiles/${encodeURIComponent(id)}`, 'DELETE');
}

/** Delete one stored conversation (a kept Ask answer or an Investigate conversation) from its collection. */
export async function deleteConversation(collectionId: string, conversationId: string): Promise<void> {
  await mutate(
    `/api/collections/${encodeURIComponent(collectionId)}/conversations/${encodeURIComponent(conversationId)}`,
    'DELETE',
  );
}

export async function setLlmDefault(role: 'ASK' | 'INVESTIGATE', profileId: string): Promise<void> {
  await mutate(`/api/llm/defaults/${role}`, 'PUT', { profileId });
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

/**
 * Start an Investigate turn. `limits` carries the reader's per-question settings; a caller that
 * omits it keeps the server defaults, so `limits` reaches the wire only when it is provided.
 */
export async function startInvestigation(
  collection: string,
  question: string,
  profile: string,
  signal?: AbortSignal,
  limits?: InvestigationLimits,
): Promise<Response> {
  return investigateRequest('/api/investigations', { collection, question, profile, ...(limits === undefined ? {} : { limits }) }, signal);
}

/** Continue an existing conversation; the collection and profile are re-validated against its locked snapshot. */
export async function continueInvestigation(
  conversationId: string,
  collection: string,
  question: string,
  profile: string,
  signal?: AbortSignal,
  limits?: InvestigationLimits,
): Promise<Response> {
  const path = `/api/investigations/${encodeURIComponent(conversationId)}/continue`;
  return investigateRequest(path, { collection, question, profile, ...(limits === undefined ? {} : { limits }) }, signal);
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
    return ['started', 'delta', 'tool', 'usage', 'citation', 'limit', 'answer-start', 'done', 'error'].includes(event.type) ? event : null;
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

/**
 * Ask the server to open the native pick dialog for files (`directory = false`) or one folder.
 * Returns the absolute paths the user chose, in pick order.
 */
export async function pickPaths(directory: boolean): Promise<string[]> {
  const body = (await mutate('/api/imports/pick', 'POST', { directory })) as { paths: string[] };
  return body.paths;
}

/** Queue an import of the selected paths into a collection; the job runs in the background. */
export async function enqueueImport(
  collection: string,
  paths: string[],
  recursive: boolean,
): Promise<{ accepted: boolean; job: JobApiView }> {
  return (await mutate('/api/imports', 'POST', { collection, paths, recursive })) as {
    accepted: boolean;
    job: JobApiView;
  };
}

/** One job's current record; polling stops once `state` is terminal. */
export async function getJob(id: string): Promise<JobApiView> {
  const body = (await readJson(await fetch(`/api/jobs/${encodeURIComponent(id)}`))) as { job: JobApiView };
  return body.job;
}

/** The per-file results an import finished with. */
export async function getImportItems(id: string): Promise<ImportItemApiView[]> {
  const body = (await readJson(await fetch(`/api/jobs/${encodeURIComponent(id)}/items`))) as {
    items: ImportItemApiView[];
  };
  return body.items;
}

/**
 * One import of a collection as its history lists it: the job's durable state and stage, how many of its
 * files it has finished, and where its per-file outcomes are read.
 *
 * The counters are files, one per selected source; an import queues files, never OCR pages. The route
 * carries no source path, no worker payload, and no stored failure text.
 */
export type ImportHistoryEntry = {
  id: string;
  state: JobState;
  stage?: string | null;
  /** The file the import is working on now, as its own name; never the path it was selected from. */
  currentItem?: string | null;
  filesCompleted: number;
  filesTotal: number;
  errorCode?: string | null;
  createdAt: string;
  updatedAt: string;
  /** The persisted per-file outcomes of this import, on the existing job-items route. */
  itemsUrl: string;
};

/** A page of one collection's imports plus the total the same criteria match. */
export type ImportHistoryPage = { imports: ImportHistoryEntry[]; total: number };

/** One page of a collection's durable import history, newest first. */
export async function listCollectionImports(
  collectionId: string,
  options: { limit?: number; offset?: number } = {},
): Promise<ImportHistoryPage> {
  const parameters = new URLSearchParams();
  if (options.limit !== undefined) parameters.set('limit', String(options.limit));
  if (options.offset) parameters.set('offset', String(options.offset));
  const collection = encodeURIComponent(collectionId);
  const query = parameters.toString();
  return (await readJson(
    await fetch(`/api/collections/${collection}/imports${query === '' ? '' : `?${query}`}`),
  )) as ImportHistoryPage;
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

/** Renames a collection. Its id, its documents and its import history stay as they were. */
export async function renameCollection(collectionId: string, name: string): Promise<Collection> {
  const body = (await mutate(`/api/collections/${encodeURIComponent(collectionId)}`, 'PATCH', { name })) as {
    collection: Collection;
  };
  return body.collection;
}

/**
 * Saves the OCR languages a collection snapshots into future imports and explicit retries. Nothing is
 * reprocessed by this call: documents that completed are left alone.
 */
export async function updateCollectionOcrLanguages(collectionId: string, ocrLanguages: string): Promise<Collection> {
  const body = (await mutate(
    `/api/collections/${encodeURIComponent(collectionId)}/ocr-languages`,
    'PATCH',
    { ocrLanguages },
  )) as { collection: Collection };
  return body.collection;
}

/**
 * One deletion operation as the server reports it.
 *
 * It carries no managed or trash path: the ids, the phase, whether the deletion has finished, and — when
 * it stopped — the stable code whose remedy the UI writes in its own words.
 */
export type DeletionOperation = {
  operationId: string;
  kind: string;
  collectionId: string;
  collectionName: string;
  documentIds: string[];
  phase: string;
  terminal: boolean;
  errorCode?: string | null;
};

/** What an admitted deletion answers with: the operation to follow, its collection, and its phase. */
export type DeletionAdmission = { operationId: string; collectionId: string; phase: string };

/**
 * What an admitted document deletion answers with: the operation to follow, the collection it removes
 * from, the documents it targets, and the phase it has already recorded.
 */
export type DocumentDeletionAdmission = {
  operationId: string;
  collectionId: string;
  documentIds: string[];
  phase: string;
};

/**
 * Confirms a collection's permanent removal. The server admits it durably and answers before the
 * destructive phases run, so the caller follows the operation rather than waiting for the deletion.
 */
export async function deleteCollection(collectionId: string, confirmName: string): Promise<DeletionAdmission> {
  const body = (await mutate(
    `/api/collections/${encodeURIComponent(collectionId)}`,
    'DELETE',
    { confirmName },
  )) as DeletionAdmission;
  return body;
}

/**
 * Confirms the permanent removal of explicit documents from one collection. The server validates every
 * id against the active collection before it admits anything and answers before the destructive phases
 * run, so the caller follows the operation rather than waiting for the deletion.
 */
export async function deleteDocuments(
  collectionId: string,
  documentIds: string[],
): Promise<DocumentDeletionAdmission> {
  const body = (await mutate(
    `/api/collections/${encodeURIComponent(collectionId)}/documents/delete`,
    'POST',
    { documentIds, confirmed: true },
  )) as DocumentDeletionAdmission;
  return body;
}

/** One document a retry refused, and InfoScry's own sentence for the reason. */
export type RetryRejection = { documentId: string; reason: string };

/**
 * What a retry asks for: the documents to read again, or every eligible one in the collection.
 *
 * The two are mutually exclusive because they are different requests — one may explain a document the user
 * named, and the other picks the set itself.
 */
export type RetryRequest = { documentIds: string[] } | { allEligible: true };

/** What an admitted retry answers with: the attempts it queued and the documents it refused. */
export type RetryAdmission = {
  collectionId: string;
  acceptedJobIds: string[];
  rejected: RetryRejection[];
};

/**
 * Reads existing documents again from the managed copies InfoScry holds, keeping their identities.
 *
 * The server decides per document: some may be accepted (and appear as job ids) while others come back with
 * the sentence that says why they were not — already running, being deleted, or needing a tool this machine
 * does not have. It never copies or classifies bytes, so a retry cannot mistake the archive's own copy for a
 * duplicate of itself.
 */
export async function retryDocuments(collectionId: string, request: RetryRequest): Promise<RetryAdmission> {
  const body = (await mutate(
    `/api/collections/${encodeURIComponent(collectionId)}/documents/retry`,
    'POST',
    request,
  )) as RetryAdmission;
  return body;
}

/** What an Admin retry action has to show: the attempt it queued, or why there is none. */
export type RetryAttempt = { accepted: true; jobId: string } | { accepted: false; reason: string };

/** One deletion operation, terminal or not, read from the server's own record. */
export async function getDeletion(operationId: string): Promise<DeletionOperation> {
  const body = (await readJson(
    await fetch(`/api/deletions/${encodeURIComponent(operationId)}`),
  )) as { operation: DeletionOperation };
  return body.operation;
}

/** The deletions that still need work: what a reopened Admin restores as unfinished. */
export async function listUnfinishedDeletions(): Promise<DeletionOperation[]> {
  const body = (await readJson(await fetch('/api/deletions'))) as { deletions: DeletionOperation[] };
  return body.deletions;
}

/**
 * One mutation with the browser's CSRF token, retrying once after a server restart invalidates the
 * cached token (the same recovery the streaming calls use). A `DELETE` with an empty body needs the
 * same token, so this helper covers both.
 */
async function mutate(path: string, method: 'POST' | 'PUT' | 'PATCH' | 'DELETE', body?: unknown): Promise<unknown> {
  const send = async (): Promise<Response> => fetch(path, {
    method,
    headers: body === undefined
      ? { [CSRF_HEADER]: await csrfToken() }
      : { 'Content-Type': 'application/json', [CSRF_HEADER]: await csrfToken() },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  let response = await send();
  if (!response.ok) {
    try {
      await readJson(response);
    } catch (failure) {
      if (!(failure instanceof ApiError) || failure.code !== 'MUTATION_REQUIRES_CREDENTIALS') throw failure;
      sessionToken = null;
      response = await send();
    }
  }
  return readJson(response);
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
