import { afterEach, describe, expect, it, vi } from 'vitest';
import { documentPageReviewImageUrl, getDocumentPageReview, type PageReviewResponse } from './api';

describe('document page review API client', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('pins subsequent image and bounded text requests to the metadata tuple', async () => {
    const metadata: PageReviewResponse = {
      documentId: 'document/1', unitId: 'unit-1', revisionId: 'revision A', ordinal: 1,
      documentPages: 3, previousOrdinal: 0, nextOrdinal: 2, text: 'A😀B', offset: 0, totalChars: 4, truncated: false,
      qualityScore: null, readingMethod: 'Tesseract', imageKind: 'ocr', structureDegraded: false,
    };
    const urls: string[] = [];
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      urls.push(String(input));
      return { ok: true, status: 200, statusText: '', text: async () => JSON.stringify(metadata) } as Response;
    }));
    const page = await getDocumentPageReview('Collection A', 'document/1', { ordinal: 1 });
    await getDocumentPageReview('Collection A', page.documentId, {
      ordinal: page.ordinal, revision: page.revisionId!, offset: 2, limit: 100,
    });
    expect(urls).toEqual([
      '/api/collections/Collection%20A/documents/document%2F1/page-review?ordinal=1',
      '/api/collections/Collection%20A/documents/document%2F1/page-review?ordinal=1&revision=revision+A&offset=2&limit=100',
    ]);
    expect(documentPageReviewImageUrl('Collection A', page)).toBe(
      '/api/collections/Collection%20A/documents/document%2F1/page-review/image?ordinal=1&kind=ocr&revision=revision+A',
    );
  });

  it('omits the revision only for an unpublished original and offers no image URL without provenance', () => {
    const metadata: PageReviewResponse = {
      documentId: 'document', unitId: 'unit-2', ordinal: 2, revisionId: 'revision-old',
      documentPages: null, previousOrdinal: 0, nextOrdinal: null,
      text: 'recorded page', offset: 0, totalChars: 13, truncated: false,
      qualityScore: null, readingMethod: null, imageKind: 'ocr', structureDegraded: false,
    };
    expect(documentPageReviewImageUrl('default', metadata)).toBe(
      '/api/collections/default/documents/document/page-review/image?ordinal=2&kind=ocr&revision=revision-old',
    );
    const page = { documentId: 'document', ordinal: 2, revisionId: null, imageKind: 'original' as const };
    expect(documentPageReviewImageUrl('default', page)).toBe(
      '/api/collections/default/documents/document/page-review/image?ordinal=2&kind=original',
    );
    expect(documentPageReviewImageUrl('default', { ...page, imageKind: 'none' })).toBeNull();
  });
});
