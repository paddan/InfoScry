import type { AskEvidence } from './api';

/** Where one piece of evidence comes from, as a reader names it: the document, then the place in it. */
export function evidenceSourceLabel(evidence: AskEvidence): string {
  return evidence.documentTitle ? `${evidence.documentTitle}, ${evidence.locatorLabel}` : evidence.locatorLabel;
}
