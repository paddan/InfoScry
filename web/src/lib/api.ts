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
