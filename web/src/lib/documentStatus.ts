import type { DocumentSummary, JobSummary } from './api';

export type DocumentStatus = {
  kind: 'imported' | 'reading' | 'done' | 'failed' | 'cancelled';
  label: string;
  progress?: { done: number; total: number };
  nextAction: 'scan' | 'retry' | 'cancel' | null;
};

const READING_DOCUMENT_STATES = new Set(['COPYING', 'EXTRACTING', 'OCR', 'CHUNKING', 'EMBEDDING', 'INDEXING']);

const FAILURE_CAUSES: Record<string, string> = {
  OCR_PROVIDER_UNAVAILABLE: 'The OCR provider could not be reached.',
  PROVIDER_UNAVAILABLE: 'The OCR provider could not be reached.',
  IMAGE_ENDPOINT_FAILED: 'The OCR provider could not be reached.',
  MORE_PAGES_THAN_CONFIRMED: 'The document had more pages than were confirmed.',
  RETRY_CANCELLED: 'The reading attempt was interrupted.',
  RESCAN_CANCELLED: 'The reading attempt was interrupted.',
  INTERRUPTED: 'The reading attempt was interrupted.',
  JOB_INTERRUPTED: 'The reading attempt was interrupted.',
  OCR_FAILED: 'OCR could not read some pages.',
  SOURCE_MISSING: 'The source file was missing when reading began.',
  SOURCE_UNREADABLE: 'The source file could not be read.',
};

/** What a document that finished with an image warning tells the person; the text is never part of it. */
const IMAGE_WARNING_NOTES: Record<string, string> = {
  NEEDS_SURYA_LAYOUT:
    'Pictures on scanned pages were not located because Surya is not installed. Install it as the installation guide describes, then scan the pages again.',
  IMAGE_LIMIT_REACHED: 'The document holds more images than the limit, so the rest were not kept.',
  IMAGE_DECODE_FAILED: 'Some images could not be decoded and were left out.',
  IMAGE_TOO_LARGE: 'Some images were too large to keep.',
  TOO_MANY_IMAGES: 'A page or section holds more images than can be kept, so the rest were left out.',
  IMAGE_PARENT_UNREADABLE: 'The images of some pages or sections could not be read.',
  IMAGE_EXTRACTION_FAILED: 'The images could not be extracted. The text was kept.',
  IMAGE_SOURCE_UNREADABLE: 'The document could not be opened to look for its images.',
  IMAGE_PARENT_ORDINAL_TOO_LATE: 'Images late in a very long document were not kept.',
};

/**
 * The sentence for an image warning code, or null when the code is not an image warning.
 *
 * The server sends its own sentence with the warning (`warningNote`, fixed wording, never document text); for the missing Surya layout engine that sentence carries the
 * install commands, the pinned version and the interpreter variable, so it is shown as written rather than repeated here.
 */
export function imageWarningNote(code: string | null | undefined, message?: string | null): string | null {
  if (!code) return null;
  const known = IMAGE_WARNING_NOTES[code] ?? null;
  if (known && code === 'NEEDS_SURYA_LAYOUT' && message && message.trim()) {
    const sentence = message.trim();
    return sentence.charAt(0).toUpperCase() + sentence.slice(1) + (/[.!?]$/.test(sentence) ? '' : '.');
  }
  return known;
}

export function readableFailureCause(code: string | null | undefined): string {
  return code ? FAILURE_CAUSES[code] ?? 'The reading attempt failed.' : 'The reading attempt failed.';
}

export function documentStatus(doc: DocumentSummary, job?: JobSummary): DocumentStatus {
  if (doc.status === 'QUEUED' || job?.state === 'QUEUED' || job?.state === 'RUNNING' || READING_DOCUMENT_STATES.has(doc.status)) {
    const done = doc.progress?.processedUnits ?? job?.completed ?? 0;
    const total = doc.progress?.totalUnits ?? job?.total ?? 0;
    const label = total > 0 ? `Reading ${done} of ${total}` : `Reading ${done}`;
    return { kind: 'reading', label, progress: { done, total }, nextAction: 'cancel' };
  }
  if (job?.state === 'FAILED' || doc.status === 'FAILED' || doc.status === 'NEEDS_TOOL') {
    const cause = job?.errorCode ?? doc.errorCode ?? 'unknown error';
    return { kind: 'failed', label: `Failed: ${readableFailureCause(cause)}`, nextAction: 'retry' };
  }
  if (job?.state === 'CANCELLED' || doc.status === 'CANCELLED') {
    return { kind: 'cancelled', label: 'Cancelled', nextAction: 'scan' };
  }
  if (doc.status === 'COMPLETE' || doc.status === 'COMPLETE_WITH_WARNINGS') {
    const note = doc.status === 'COMPLETE_WITH_WARNINGS' ? imageWarningNote(doc.errorCode, doc.warningNote) : null;
    return { kind: 'done', label: note ? `Done. ${note}` : 'Done', nextAction: 'scan' };
  }
  return { kind: 'imported', label: 'Imported', nextAction: 'scan' };
}
