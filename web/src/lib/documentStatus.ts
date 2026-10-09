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
    return { kind: 'done', label: 'Done', nextAction: 'scan' };
  }
  return { kind: 'imported', label: 'Imported', nextAction: 'scan' };
}
