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

  it('translates stable failure codes and keeps unknown causes readable', () => {
    expect(documentStatus(doc('FAILED', { errorCode: 'MORE_PAGES_THAN_CONFIRMED' })).label)
      .toBe('Failed: The document had more pages than were confirmed.');
    expect(documentStatus(doc('FAILED', { errorCode: 'RETRY_CANCELLED' })).label)
      .toBe('Failed: The reading attempt was interrupted.');
    expect(documentStatus(doc('FAILED', { errorCode: 'NEW_ENGINE_FAILURE' })).label)
      .toBe('Failed: The reading attempt failed.');
  });
});
