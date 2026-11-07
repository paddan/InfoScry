import { describe, expect, it } from 'vitest';
import type { DocumentApiRow, JobApiView } from './api';
import { documentStatus } from './documentStatus';

function doc(status: DocumentApiRow['status'], over: Partial<DocumentApiRow> = {}): DocumentApiRow {
  return {
    id: 'doc-1', collectionId: 'c', mediaType: 'application/pdf', originalFilename: 'report.pdf',
    sizeBytes: 100, status, createdAt: '', updatedAt: '', ...over,
  };
}

function job(state: JobApiView['state'], over: Partial<JobApiView> = {}): JobApiView {
  return { id: 'job-1', type: 'IMPORT', state, createdAt: '', updatedAt: '', completed: 0, total: 4, cancelRequested: false, ...over };
}

describe('documentStatus', () => {
  it('offers a scan for imported documents and completed documents', () => {
    expect(documentStatus(doc('QUEUED'))).toEqual({ kind: 'reading', label: 'Reading 0', progress: { done: 0, total: 0 }, nextAction: 'cancel' });
    expect(documentStatus(doc('COMPLETE'))).toEqual({ kind: 'done', label: 'Done', nextAction: 'scan' });
  });

  it('shows run progress and offers cancellation while reading', () => {
    expect(documentStatus(doc('OCR'), job('RUNNING', { completed: 12, total: 48 }))).toEqual({
      kind: 'reading', label: 'Reading 12 of 48', progress: { done: 12, total: 48 }, nextAction: 'cancel',
    });
  });

  it('does not present an unknown page total as zero', () => {
    expect(documentStatus(doc('OCR', { progress: { processedUnits: 1, failedUnits: 0, totalUnits: 0, unitKind: 'PAGE' } })))
      .toEqual({ kind: 'reading', label: 'Reading 1', progress: { done: 1, total: 0 }, nextAction: 'cancel' });
  });

  it('shows a failure cause with retry and cancelled with a scan action', () => {
    expect(documentStatus(doc('FAILED', { errorCode: 'PROVIDER_UNAVAILABLE' }), job('FAILED', { errorCode: 'PROVIDER_UNAVAILABLE' })))
      .toEqual({ kind: 'failed', label: 'Failed: The OCR provider could not be reached.', nextAction: 'retry' });
    expect(documentStatus(doc('CANCELLED'), job('CANCELLED')))
      .toEqual({ kind: 'cancelled', label: 'Cancelled', nextAction: 'scan' });
  });

  it('names the image warning a finished document carries', () => {
    expect(documentStatus(doc('COMPLETE_WITH_WARNINGS', { errorCode: 'IMAGE_LIMIT_REACHED' })).label)
      .toBe('Done. The document holds more images than the limit, so the rest were not kept.');
    // The remedy is the server's own sentence, so the pinned version and the interpreter variable never drift.
    const remedy = "pictures on scanned pages could not be located because Surya is not available: create it with 'uv venv --python 3.12' and 'uv pip install surya-ocr==9.9.9' in that environment, then name its python with INFOSCRY_SURYA_PYTHON";
    const surya = documentStatus(doc('COMPLETE_WITH_WARNINGS', { errorCode: 'NEEDS_SURYA_LAYOUT', warningNote: remedy })).label;
    expect(surya).toBe(`Done. P${remedy.slice(1)}.`);
    expect(surya).toContain('INFOSCRY_SURYA_PYTHON');
    // Without the server's sentence the copy names no version and points at the installation guide.
    const bare = documentStatus(doc('COMPLETE_WITH_WARNINGS', { errorCode: 'NEEDS_SURYA_LAYOUT' })).label;
    expect(bare).toContain('Surya');
    expect(bare).not.toMatch(/surya-ocr==/);
    for (const code of ['IMAGE_DECODE_FAILED', 'IMAGE_TOO_LARGE', 'TOO_MANY_IMAGES', 'IMAGE_PARENT_UNREADABLE', 'IMAGE_EXTRACTION_FAILED', 'IMAGE_SOURCE_UNREADABLE', 'IMAGE_PARENT_ORDINAL_TOO_LATE']) {
      expect(documentStatus(doc('COMPLETE_WITH_WARNINGS', { errorCode: code })).label).not.toBe('Done');
    }
    // A warning without an image code, and a clean document, stay plain.
    expect(documentStatus(doc('COMPLETE_WITH_WARNINGS', { errorCode: 'NO_EXTRACTABLE_CONTENT' })).label).toBe('Done');
    expect(documentStatus(doc('COMPLETE', { errorCode: 'IMAGE_LIMIT_REACHED' })).label).toBe('Done');
  });

  it('translates stable failure codes and keeps unknown causes readable', () => {
    expect(documentStatus(doc('FAILED', { errorCode: 'MORE_PAGES_THAN_CONFIRMED' })).label)
      .toBe('Failed: The document had more pages than were confirmed.');
    expect(documentStatus(doc('FAILED', { errorCode: 'RETRY_CANCELLED' })).label)
      .toBe('Failed: The reading attempt was interrupted.');
    expect(documentStatus(doc('FAILED', { errorCode: 'NEW_ENGINE_FAILURE' })).label)
      .toBe('Failed: The reading attempt failed.');
  });
});
