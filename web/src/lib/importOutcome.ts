import type { ImportItemApiView } from './api';

/**
 * What one file of an import ended as, in a reader's words.
 *
 * The server sends the machine outcome and a failure code; the sentence is product copy, so it lives
 * here rather than in each panel. Nothing is decided here — an unknown outcome is not interpreted.
 */

/** What one file of an import ended as, in a reader's words. */
const OUTCOME_LABELS: Record<ImportItemApiView['outcome'], string> = {
  PENDING: 'Pending',
  IMPORTED: 'Imported',
  DUPLICATE: 'Duplicate',
  FAILED: 'Failed',
  CANCELLED: 'Cancelled',
};

/**
 * The failure codes whose document is kept and waits for a tool rather than for another attempt.
 *
 * They match the codes the server maps to `DocumentStatus.NEEDS_TOOL`, so the file's outcome says the
 * same thing as the document's own status.
 */
const TOOL_PREREQUISITE_CODES: ReadonlySet<string> = new Set(['NEEDS_TOOL', 'NEEDS_TESSERACT']);

/**
 * What one file of an import ended as: `Imported`, `Duplicate`, `Needs a tool`, `Failed`.
 *
 * A failure whose bytes were kept because a tool is missing is not `Failed` — the document is in the
 * collection waiting for that tool, and calling it failed would contradict the document row's own
 * "Needs a tool" while the file sits there. The bytes are the server's fact; the words are ours.
 */
export function importItemOutcomeLabel(item: Pick<ImportItemApiView, 'outcome' | 'errorCode'>): string {
  if (item.outcome === 'FAILED' && item.errorCode != null && TOOL_PREREQUISITE_CODES.has(item.errorCode)) {
    return 'Needs a tool';
  }
  return OUTCOME_LABELS[item.outcome];
}
