import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/svelte';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { PageReviewResponse } from './api';
const mocks = vi.hoisted(() => ({ getPage: vi.fn(), imageUrl: vi.fn(), pdfStatus: vi.fn(), pdfCreate: vi.fn() }));
vi.mock('./api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('./api')>()),
  getDocumentPageReview: mocks.getPage,
  documentPageReviewImageUrl: mocks.imageUrl,
  getSelectablePdfStatus: mocks.pdfStatus,
  createSelectablePdf: mocks.pdfCreate,
}));
import PageReview from './PageReview.svelte';

const metadata = (ordinal: number, over: Partial<PageReviewResponse> = {}): PageReviewResponse => ({
  documentId: 'doc-1', unitId: `unit-${ordinal}`, revisionId: 'revision-a', ordinal,
  documentPages: 3, previousOrdinal: ordinal > 0 ? ordinal - 1 : null,
  nextOrdinal: ordinal < 2 ? ordinal + 1 : null, text: `Text for page ${ordinal + 1}`,
  offset: 0, totalChars: 20, truncated: false, qualityScore: 80,
  readingMethod: 'Surya', imageKind: 'ocr', ...over,
});

beforeEach(() => {
  mocks.getPage.mockReset();
  mocks.imageUrl.mockReset();
  mocks.imageUrl.mockImplementation((_collection: string, page: PageReviewResponse | undefined) => page ? `/image/${page.ordinal}` : null);
  mocks.pdfStatus.mockReset();
  mocks.pdfCreate.mockReset();
  mocks.pdfStatus.mockImplementation(async (_collection: string, _document: string, revisionId: string) => ({
    revisionId, key: null, state: 'NOT_CREATED', selectablePages: 0, totalPages: null, blankPages: 0, unavailablePages: [], reasonCode: null,
  }));
});
afterEach(() => { cleanup(); vi.restoreAllMocks(); });

describe('PageReview', () => {
  it('loads the same exact page revision for image and text and navigates physical blanks', async () => {
    mocks.getPage.mockImplementation(async (_collection: string, _document: string, options: { ordinal: number; revision?: string }) =>
      metadata(options.ordinal, options.ordinal === 1 ? { unitId: null, text: '', imageKind: 'original' } : {}));
    mocks.imageUrl.mockImplementation((_collection: string, page: PageReviewResponse) =>
      `/image?ordinal=${page.ordinal}&revision=${page.revisionId}&kind=${page.imageKind}`);
    render(PageReview, { props: { collectionId: 'collection-1', documentId: 'doc-1', initialOrdinal: 0, onBack: vi.fn() } });

    expect(await screen.findByText('Text for page 1')).toBeTruthy();
    expect(screen.getByRole('img', { name: 'OCR page image, page 1' }).getAttribute('src')).toContain('ordinal=0&revision=revision-a&kind=ocr');
    await fireEvent.click(screen.getByRole('button', { name: 'Next page' }));
    await screen.findByText('Page 2 of 3');
    expect(screen.getByText('No extracted text for this page.')).toBeTruthy();
    expect(screen.getByRole('img', { name: 'Original page image, page 2' }).getAttribute('src')).toContain('ordinal=1&revision=revision-a&kind=original');
    expect(mocks.getPage).toHaveBeenLastCalledWith('collection-1', 'doc-1', expect.objectContaining({ ordinal: 1, revision: 'revision-a' }));
  });

  it('ignores stale page responses and lets long Unicode text load more', async () => {
    let resolveMore!: (page: PageReviewResponse) => void;
    mocks.getPage.mockImplementation((_collection: string, _document: string, options: { ordinal: number; offset?: number }) => {
      if (options.ordinal === 0 && options.offset !== undefined && !resolveMore) return new Promise<PageReviewResponse>((resolve) => { resolveMore = resolve; });
      if (options.ordinal === 0 && options.offset !== undefined) return Promise.resolve(metadata(0, { text: '終わり🙂', offset: options.offset, truncated: false, totalChars: 20 }));
      return Promise.resolve(metadata(options.ordinal, { text: options.ordinal === 0 ? '新しい🙂' : 'Page two', truncated: options.ordinal === 0, totalChars: 20 }));
    });
    render(PageReview, { props: { collectionId: 'collection-1', documentId: 'doc-1', initialOrdinal: 0, onBack: vi.fn() } });
    expect(await screen.findByText('新しい🙂')).toBeTruthy();
    await fireEvent.click(screen.getByRole('button', { name: 'Load more' }));
    await fireEvent.click(screen.getByRole('button', { name: 'Next page' }));
    expect(await screen.findByText('Page two')).toBeTruthy();
    resolveMore(metadata(0, { text: 'stale text', revisionId: 'revision-old' }));
    await waitFor(() => expect(screen.queryByText('stale text')).toBeNull());
    await fireEvent.click(screen.getByRole('button', { name: 'Previous page' }));
    await screen.findByText('新しい🙂');
    await fireEvent.click(screen.getByRole('button', { name: 'Load more' }));
    expect(await screen.findByText(/新しい🙂終わり🙂/)).toBeTruthy();
  });

  it('supports keyboard divider movement, bounded zoom, and fit', async () => {
    mocks.getPage.mockImplementation(async (_collection: string, _document: string, options: { ordinal: number }) => metadata(options.ordinal));
    render(PageReview, { props: { collectionId: 'collection-1', documentId: 'doc-1', initialOrdinal: 1, onBack: vi.fn() } });
    const divider = await screen.findByRole('separator', { name: 'Resize page panes' });
    expect(Number(divider.getAttribute('aria-valuenow'))).toBe(50);
    await fireEvent.keyDown(divider, { key: 'ArrowRight' });
    expect(Number(divider.getAttribute('aria-valuenow'))).toBe(55);
    await fireEvent.click(screen.getByRole('button', { name: 'Zoom in' }));
    expect((await screen.findByRole('img')).getAttribute('style')).toContain('scale(1.25)');
    await fireEvent.click(screen.getByRole('button', { name: 'Fit image' }));
    expect((await screen.findByRole('img')).getAttribute('style')).toContain('scale(1)');
    expect(screen.getByText('Text for page 2')).toBeTruthy();
  });

  it('switches narrow panes while preserving page, revision, and zoom', async () => {
    mocks.getPage.mockImplementation(async (_collection: string, _document: string, options: { ordinal: number }) => metadata(options.ordinal));
    render(PageReview, { props: { collectionId: 'collection-1', documentId: 'doc-1', initialOrdinal: 1, onBack: vi.fn(), narrow: true } });
    await screen.findByText('Text for page 2');
    await fireEvent.click(screen.getByRole('button', { name: 'Zoom in' }));
    await fireEvent.click(screen.getByRole('tab', { name: 'Text' }));
    expect(screen.getByText('Text for page 2')).toBeTruthy();
    expect(screen.getByText('Page 2 of 3')).toBeTruthy();
    expect(screen.getByRole('tab', { name: 'Image' }).getAttribute('aria-selected')).toBe('false');
  });

  it('bounds the active narrow pane to the comparison height with its own scroll area', async () => {
    mocks.getPage.mockImplementation(async (_collection: string, _document: string, options: { ordinal: number }) =>
      metadata(options.ordinal, { text: 'Long narrow page '.repeat(200) }));
    render(PageReview, { props: { collectionId: 'collection-1', documentId: 'doc-1', onBack: vi.fn(), narrow: true } });
    expect(await screen.findByText(/^Long narrow page/)).toBeTruthy();

    const comparison = document.querySelector('.comparison')!;
    const imagePane = document.querySelector('.image-pane')!;
    expect(getComputedStyle(comparison).height).not.toBe('auto');
    expect(getComputedStyle(imagePane).height).toBe('100%');
    expect(getComputedStyle(imagePane).minHeight).toBe('0px');
    expect(getComputedStyle(imagePane.querySelector('.image-scroll')!).overflowY).toBe('auto');

    await fireEvent.click(screen.getByRole('tab', { name: 'Text' }));
    const textPane = document.querySelector('.text-pane')!;
    expect(textPane.hasAttribute('hidden')).toBe(false);
    expect(getComputedStyle(textPane).height).toBe('100%');
    expect(getComputedStyle(textPane).minHeight).toBe('0px');
    expect(getComputedStyle(textPane.querySelector('.review-text')!).overflowY).toBe('auto');
  });

  it('offers publication refresh and binds refreshed text and image to the same revision', async () => {
    mocks.imageUrl.mockImplementation((_collection: string, page: PageReviewResponse) => `/image/${page.ordinal}?revision=${page.revisionId}`);
    mocks.getPage.mockImplementation(async (_collection: string, _document: string, options: { ordinal: number; revision?: string }) =>
      metadata(options.ordinal, { revisionId: options.revision ?? 'revision-a', text: `Reading ${options.revision ?? 'revision-a'}` }));
    const view = render(PageReview, { props: {
      collectionId: 'collection-1', documentId: 'doc-1', initialOrdinal: 0,
      revisionId: 'revision-a', publishedRevisionId: null, onBack: vi.fn(),
    } });
    expect(await screen.findByText('Reading revision-a')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Refresh to latest reading' })).toBeNull();
    await view.rerender({ publishedRevisionId: 'revision-b' });
    await fireEvent.click(await screen.findByRole('button', { name: 'Refresh to latest reading' }));
    expect(await screen.findByText('Reading revision-b')).toBeTruthy();
    expect(mocks.getPage).toHaveBeenLastCalledWith('collection-1', 'doc-1', expect.objectContaining({ ordinal: 0, revision: 'revision-b' }));
    expect(screen.getByRole('img').getAttribute('src')).toBe('/image/0?revision=revision-b');
    expect(screen.queryByRole('button', { name: 'Refresh to latest reading' })).toBeNull();
    await fireEvent.click(screen.getByRole('button', { name: 'Next page' }));
    expect(await screen.findByText('Reading revision-b')).toBeTruthy();
    expect(screen.getByText('Page 2 of 3')).toBeTruthy();
    await view.rerender({ publishedRevisionId: 'revision-c' });
    expect(screen.getByText('Page 2 of 3')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Refresh to latest reading' })).toBeTruthy();
  });

  it('shows an unavailable total and retries the image without replacing page text', async () => {
    mocks.getPage.mockImplementation(async (_collection: string, _document: string, options: { ordinal: number }) =>
      metadata(options.ordinal, { documentPages: null, previousOrdinal: 0, nextOrdinal: 4 }));
    render(PageReview, { props: { collectionId: 'collection-1', documentId: 'doc-1', initialOrdinal: 3, onBack: vi.fn() } });
    expect(await screen.findByText('Text for page 4')).toBeTruthy();
    expect(screen.getByText('Page 4 (total unavailable)')).toBeTruthy();
    const image = screen.getByRole('img');
    await fireEvent.error(image);
    expect((await screen.findByRole('alert')).textContent).toContain('This page image is unavailable.');
    await fireEvent.click(screen.getByRole('button', { name: 'Retry image' }));
    expect(screen.getByText('Text for page 4')).toBeTruthy();
    expect(screen.getByRole('img').getAttribute('src')).toContain('&retry=1');
  });

  it('ignores load and error events from an image belonging to the previous page', async () => {
    mocks.getPage.mockImplementation(async (_collection: string, _document: string, options: { ordinal: number }) => metadata(options.ordinal));
    render(PageReview, { props: { collectionId: 'collection-1', documentId: 'doc-1', onBack: vi.fn() } });
    expect(await screen.findByText('Text for page 1')).toBeTruthy();
    const previousImage = screen.getByRole('img', { name: 'OCR page image, page 1' });

    await fireEvent.click(screen.getByRole('button', { name: 'Next page' }));
    expect(await screen.findByText('Text for page 2')).toBeTruthy();
    const currentImage = screen.getByRole('img', { name: 'OCR page image, page 2' });
    expect(screen.getByText('Loading image…')).toBeTruthy();

    await fireEvent.error(previousImage);
    expect(screen.queryByRole('alert')).toBeNull();
    await fireEvent.load(previousImage);
    expect(screen.getByText('Loading image…')).toBeTruthy();
    expect(screen.getByRole('img', { name: 'OCR page image, page 2' })).toBe(currentImage);
  });

  it('gives the comparison and both panes constrained, independent scroll regions', async () => {
    mocks.getPage.mockImplementation(async (_collection: string, _document: string, options: { ordinal: number }) =>
      metadata(options.ordinal, { text: 'Long text '.repeat(500), totalChars: 5000 }));
    render(PageReview, { props: { collectionId: 'collection-1', documentId: 'doc-1', onBack: vi.fn() } });
    expect(await screen.findByText(/^Long text/)).toBeTruthy();

    const comparison = document.querySelector('.comparison')!;
    const imageScroll = document.querySelector('.image-scroll')!;
    const text = document.querySelector('.review-text')!;
    expect(getComputedStyle(comparison).height).not.toBe('auto');
    expect(getComputedStyle(comparison).minHeight).toBe('0px');
    expect(getComputedStyle(imageScroll).overflowY).toBe('auto');
    expect(getComputedStyle(imageScroll).minHeight).toBe('0px');
    expect(getComputedStyle(text).overflowY).toBe('auto');
    expect(getComputedStyle(text).minHeight).toBe('0px');
  });

  it('keeps unknown-revision excerpts excerpt-only and labels historical review', async () => {
    render(PageReview, { props: {
      collectionId: 'collection-1', documentId: 'doc-1', initialOrdinal: 3, historical: true,
      revisionId: 'revision-old', publishedRevisionId: 'revision-new', savedExcerpt: 'Citation excerpt',
      onBack: vi.fn(), onScanPages: vi.fn(),
    } });
    expect(screen.getByText('Citation excerpt')).toBeTruthy();
    expect(screen.getByText(/revision unknown/i)).toBeTruthy();
    expect(screen.queryByRole('img')).toBeNull();
    expect(screen.queryByRole('button', { name: 'Refresh to latest reading' })).toBeNull();
    expect(screen.queryByRole('button', { name: /scan/i })).toBeNull();
    expect(mocks.getPage).not.toHaveBeenCalled();
  });

  it('marks the current page and submits a sorted explicit selection that clears on Back', async () => {
    mocks.getPage.mockImplementation(async (_collection: string, _document: string, options: { ordinal: number }) => metadata(options.ordinal));
    const onScanPages = vi.fn();
    const onBack = vi.fn();
    render(PageReview, { props: { collectionId: 'c', documentId: 'doc-1', initialOrdinal: 1, onBack, onScanPages } });

    await screen.findByText('Text for page 2');
    await fireEvent.click(screen.getByRole('button', { name: 'Mark page 2' }));
    await fireEvent.click(screen.getByRole('button', { name: 'Next page' }));
    await screen.findByText('Text for page 3');
    await fireEvent.click(screen.getByRole('button', { name: 'Mark page 3' }));
    expect(screen.getByText('Selected pages: 2, 3')).toBeTruthy();

    await fireEvent.click(screen.getByRole('button', { name: 'Scan selected pages' }));
    expect(onScanPages).toHaveBeenCalledWith([1, 2]);
    await fireEvent.click(screen.getByRole('button', { name: 'Back to documents' }));
    expect(onBack).toHaveBeenCalledOnce();
    expect(screen.queryByText(/Selected pages/)).toBeNull();
  });

  it('clears page marks when switching to historical review', async () => {
    mocks.getPage.mockImplementation(async (_collection: string, _document: string, options: { ordinal: number }) => metadata(options.ordinal));
    const view = render(PageReview, { props: { collectionId: 'c', documentId: 'doc-1', onBack: vi.fn(), onScanPages: vi.fn() } });
    await screen.findByText('Text for page 1');
    await fireEvent.click(screen.getByRole('button', { name: 'Mark page 1' }));
    expect(screen.getByText('Selected pages: 1')).toBeTruthy();
    await view.rerender({ historical: true, savedExcerpt: 'Archived passage' });
    expect(screen.queryByText(/Selected pages/)).toBeNull();
    expect(screen.queryByRole('button', { name: /Scan/ })).toBeNull();
  });

  it('clears marks when the mounted reader changes documents', async () => {
    mocks.getPage.mockImplementation(async (_collection: string, documentId: string, options: { ordinal: number }) =>
      metadata(options.ordinal, { documentId }));
    const view = render(PageReview, { props: { collectionId: 'c', documentId: 'doc-1', onBack: vi.fn(), onScanPages: vi.fn() } });
    await screen.findByText('Text for page 1');
    await fireEvent.click(screen.getByRole('button', { name: 'Mark page 1' }));
    expect(screen.getByText('Selected pages: 1')).toBeTruthy();
    await view.rerender({ documentId: 'doc-2' });
    await screen.findByText('Text for page 1');
    expect(screen.queryByText('Selected pages: 1')).toBeNull();
    expect(screen.getByText('No pages selected')).toBeTruthy();
  });

  it('places the selectable PDF actions beside Open original and never creates a copy by opening the review', async () => {
    mocks.getPage.mockImplementation(async (_collection: string, _document: string, options: { ordinal: number }) => metadata(options.ordinal));
    render(PageReview, { props: { collectionId: 'collection-1', documentId: 'doc-1', initialOrdinal: 0, onBack: vi.fn() } });

    const create = await screen.findByRole('button', { name: 'Create PDF with selectable text' });
    const toolbar = document.querySelector('.review-toolbar') as HTMLElement;
    expect(toolbar.contains(screen.getByRole('link', { name: 'Open original' }))).toBe(true);
    expect(toolbar.contains(create)).toBe(true);
    expect(mocks.pdfStatus).toHaveBeenCalledWith('collection-1', 'doc-1', 'revision-a');
    expect(mocks.pdfCreate).not.toHaveBeenCalled();
  });

  it('keeps the marked page selection and Open original while the copy is being prepared', async () => {
    mocks.getPage.mockImplementation(async (_collection: string, _document: string, options: { ordinal: number }) => metadata(options.ordinal));
    mocks.pdfStatus.mockResolvedValue({
      revisionId: 'revision-a', key: 'a'.repeat(64), state: 'QUEUED', selectablePages: 0, totalPages: null, blankPages: 0, unavailablePages: [], reasonCode: null,
    });
    render(PageReview, { props: { collectionId: 'c', documentId: 'doc-1', initialOrdinal: 0, onBack: vi.fn(), onScanPages: vi.fn() } });

    await screen.findByText('Text for page 1');
    await fireEvent.click(screen.getByRole('button', { name: 'Mark page 1' }));
    expect(await screen.findByText('PDF with selectable text is queued.')).toBeTruthy();
    expect(screen.getByText('Selected pages: 1')).toBeTruthy();
    expect(screen.getByRole('link', { name: 'Open original' })).toBeTruthy();
    expect(mocks.pdfCreate).not.toHaveBeenCalled();
  });

  it('reads the copy status of a historical citation from its own revision only', async () => {
    mocks.getPage.mockImplementation(async (_collection: string, _document: string, options: { ordinal: number; revision?: string }) =>
      metadata(options.ordinal, { revisionId: options.revision ?? 'revision-old' }));
    render(PageReview, { props: {
      collectionId: 'collection-1', documentId: 'doc-1', initialOrdinal: 0, historical: true,
      revisionId: 'revision-old', publishedRevisionId: 'revision-new', onBack: vi.fn(),
    } });

    await screen.findByText('Text for page 1');
    await screen.findByRole('button', { name: 'Create PDF with selectable text' });
    expect(mocks.pdfStatus).toHaveBeenCalledWith('collection-1', 'doc-1', 'revision-old');
    expect(mocks.pdfStatus).not.toHaveBeenCalledWith('collection-1', 'doc-1', 'revision-new');
  });

  it('shows the selectable PDF actions as unavailable for an excerpt whose revision is unknown', async () => {
    render(PageReview, { props: {
      collectionId: 'collection-1', documentId: 'doc-1', initialOrdinal: 3, historical: true,
      revisionId: 'revision-old', savedExcerpt: 'Citation excerpt', onBack: vi.fn(),
    } });

    expect(screen.getByText('Citation excerpt')).toBeTruthy();
    expect(screen.getByRole('link', { name: 'Open original' })).toBeTruthy();
    expect(await screen.findByText('Selectable PDF is unavailable for this source.')).toBeTruthy();
    expect(screen.getByRole('group', { name: 'Selectable PDF copy' })).toBeTruthy();
    expect(mocks.pdfStatus).not.toHaveBeenCalled();
    expect(mocks.pdfCreate).not.toHaveBeenCalled();
  });

  it('stops polling the copy status while the source pane is hidden and resumes when it is shown', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    try {
      mocks.getPage.mockImplementation(async (_collection: string, _document: string, options: { ordinal: number }) => metadata(options.ordinal));
      mocks.pdfStatus.mockResolvedValue({
        revisionId: 'revision-a', key: null, state: 'QUEUED', selectablePages: 0, totalPages: null, blankPages: 0, unavailablePages: [], reasonCode: null,
      });
      const view = render(PageReview, { props: { collectionId: 'c', documentId: 'doc-1', initialOrdinal: 0, visible: true, onBack: vi.fn() } });
      expect(await screen.findByText('PDF with selectable text is queued.')).toBeTruthy();

      await view.rerender({ visible: false });
      const hiddenAt = mocks.pdfStatus.mock.calls.length;
      await vi.advanceTimersByTimeAsync(6000);
      expect(mocks.pdfStatus.mock.calls.length).toBe(hiddenAt);

      await view.rerender({ visible: true });
      await vi.advanceTimersByTimeAsync(2500);
      expect(mocks.pdfStatus.mock.calls.length).toBeGreaterThan(hiddenAt);
      expect(mocks.pdfCreate).not.toHaveBeenCalled();
    } finally {
      vi.useRealTimers();
    }
  });
});
