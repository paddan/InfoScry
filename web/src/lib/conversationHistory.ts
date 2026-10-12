/**
 * Ask and Investigate each keep their own stored conversation list, but the two are the same shape:
 * a per-collection list read from the server, a selection remembered in browser storage, and a delete
 * confirmed with the platform's native dialog. This holds the parts that are identical between them;
 * each caller still owns its own generation counter and reactive fields, since those live in the
 * page's own `let` bindings.
 */

export type ConversationView = 'ask' | 'investigate';

function historyStorageKey(view: ConversationView, collectionId: string): string {
  return `infoscry-history:${view}:${collectionId}`;
}

/** The open conversation remembered for one collection and view, in browser storage. */
export function readStoredConversation(view: ConversationView, collectionId: string): string | null {
  try { return window.localStorage.getItem(historyStorageKey(view, collectionId)); }
  catch { return null; }
}

export function storeConversationSelection(view: ConversationView, collectionId: string, id: string | null): void {
  const key = historyStorageKey(view, collectionId);
  try {
    if (id === null) window.localStorage.removeItem(key);
    else window.localStorage.setItem(key, id);
  } catch { /* Browser storage is optional; the current selection still works in memory. */ }
}

/** Reads a collection's stored conversation list; a failed read stays silent, as the panels behave today. */
export async function readConversationList<T>(
  list: (collectionId: string) => Promise<T[]>,
  collectionId: string,
): Promise<T[]> {
  try { return await list(collectionId); }
  catch { return []; }
}

/**
 * The selected collection's conversation list and the one the reader last had open. With nothing
 * remembered, or an id the collection no longer holds, the selection starts null.
 */
export async function loadConversationHistory<T extends { id: string }>(
  view: ConversationView,
  collectionId: string,
  list: (collectionId: string) => Promise<T[]>,
): Promise<{ conversations: T[]; conversationId: string | null }> {
  const conversations = await readConversationList(list, collectionId);
  const saved = readStoredConversation(view, collectionId);
  const conversationId = saved !== null && conversations.some((item) => item.id === saved) ? saved : null;
  return { conversations, conversationId };
}

/** Confirms a delete with the platform's native dialog; false without prompting for an unknown id. */
export function confirmConversationDeletion(entry: { id: string; title: string } | undefined): boolean {
  if (entry === undefined) return false;
  return window.confirm(`Delete conversation "${entry.title}"? This cannot be undone.`);
}
