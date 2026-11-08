import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/svelte';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError, type ImageTagsView, type ImageView } from './api';
import ImageViewer from './ImageViewer.svelte';
import ImageViewerHarness from './ImageViewerHarness.test.svelte';
import * as api from './api';

vi.mock('./api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('./api')>()),
  getImageTags: vi.fn(), editImageTag: vi.fn(),
}));

const region: ImageView = {
  unitId: 'i1',
  locator: { type: 'image_region', parent: { type: 'pdf_page', page: 14 }, index: 2, box: { left: 0.1, top: 0.25, right: 0.6, bottom: 0.75 } },
  label: 'image 2 on page 14',
  parentUnitId: 'p14',
  kind: 'PAGE_REGION',
  mediaType: 'image/png',
  widthPx: 400,
  heightPx: 300,
  caption: 'A harbour at dusk',
  captionSource: 'OCR',
};

const person = { category: 'PERSON' as const, label: 'man', name: 'Jeffrey Epstein', source: 'APPEARANCE' as const };
const car = { category: 'OBJECT' as const, label: 'bil', name: 'Volvo PV444', source: 'TEXT' as const };

const tagsView = (overrides: Partial<ImageTagsView> = {}): ImageTagsView => ({
  documentId: 'd1',
  unitId: 'i1',
  description: 'Två män vid en bil.',
  tags: [person, car],
  edits: [],
  tagsVersion: 3,
  ...overrides,
});

describe('ImageViewer', () => {
  beforeEach(() => {
    vi.mocked(api.getImageTags).mockResolvedValue(tagsView());
    vi.mocked(api.editImageTag).mockResolvedValue(tagsView());
  });
  afterEach(() => {
    cleanup();
    vi.clearAllMocks();
  });

  it('shows the caption, the page it came from and the full-size file', () => {
    render(ImageViewer, { collectionId: 'c', documentId: 'd1', image: region, open: true, onClose: vi.fn() });
    expect(screen.getByRole('dialog')).toBeTruthy();
    expect(screen.getByText('A harbour at dusk')).toBeTruthy();
    expect(screen.getByText('from page 14')).toBeTruthy();
    expect(screen.getByAltText('A harbour at dusk').getAttribute('src')).toBe('/api/collections/c/documents/d1/images/i1/file');
  });

  it('shows the full-size file of the revision it belongs to', () => {
    render(ImageViewer, { collectionId: 'c', documentId: 'd1', image: region, open: true, revisionId: 'r1', onClose: vi.fn() });
    expect(screen.getByAltText('A harbour at dusk').getAttribute('src')).toBe('/api/collections/c/documents/d1/images/i1/file?revision=r1');
  });

  it('outlines a page region on the page image with percentages of the page', () => {
    render(ImageViewer, { collectionId: 'c', documentId: 'd1', image: region, open: true, revisionId: 'r1', onClose: vi.fn() });
    const outline = screen.getByTestId('image-box');
    expect(outline.style.left).toBe('10%');
    expect(outline.style.top).toBe('25%');
    expect(outline.style.width).toBe('50%');
    expect(outline.style.height).toBe('50%');
    const page = screen.getByAltText('Page 14 with the image outlined') as HTMLImageElement;
    expect(page.getAttribute('src')).toBe('/api/collections/c/documents/d1/page-review/image?ordinal=13&kind=original&revision=r1');
  });

  it('draws no outline for an embedded image', () => {
    render(ImageViewer, { collectionId: 'c', documentId: 'd1', image: { ...region, caption: null, locator: { ...region.locator, box: null } }, open: true, onClose: vi.fn() });
    expect(screen.queryByTestId('image-box')).toBeNull();
    expect(screen.getByAltText('Image 2 on page 14')).toBeTruthy();
  });

  it('groups tags by category, marks an appearance name as possibly and confirms it', async () => {
    vi.mocked(api.editImageTag).mockResolvedValue(tagsView({ tags: [{ ...person, source: 'MANUAL' }, car], tagsVersion: 4 }));
    render(ImageViewer, { collectionId: 'c', documentId: 'd1', image: region, open: true, onClose: vi.fn() });

    expect(await screen.findByText('Två män vid en bil.')).toBeTruthy();
    expect(screen.getByRole('heading', { name: 'People' })).toBeTruthy();
    expect(screen.getByRole('heading', { name: 'Objects' })).toBeTruthy();
    expect(screen.getByText('man: possibly Jeffrey Epstein')).toBeTruthy();
    await fireEvent.click(screen.getByRole('button', { name: 'Confirm' }));

    await waitFor(() => expect(api.editImageTag).toHaveBeenCalledWith(
      'c', 'd1', 'i1',
      expect.objectContaining({ action: 'CONFIRM', tag: { ...person, source: 'MANUAL' } }),
      3,
    ));
    expect(await screen.findByText('man: Jeffrey Epstein')).toBeTruthy();
  });

  it('removes and adds a tag with the version it was edited from', async () => {
    vi.mocked(api.editImageTag)
      .mockResolvedValueOnce(tagsView({ tags: [car], tagsVersion: 4 }))
      .mockResolvedValueOnce(tagsView({ tags: [car, { category: 'ANIMAL', label: 'hund', name: null, source: 'MANUAL' }], tagsVersion: 5 }));
    render(ImageViewer, { collectionId: 'c', documentId: 'd1', image: region, open: true, onClose: vi.fn() });

    await fireEvent.click((await screen.findAllByRole('button', { name: 'Remove' }))[0]);
    await waitFor(() => expect(api.editImageTag).toHaveBeenCalledWith(
      'c', 'd1', 'i1', expect.objectContaining({ action: 'REMOVE' }), 3,
    ));

    await fireEvent.click(screen.getByRole('button', { name: 'Add tag' }));
    await fireEvent.change(screen.getByLabelText('Category'), { target: { value: 'ANIMAL' } });
    await fireEvent.input(screen.getByLabelText('Kind'), { target: { value: 'hund' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Save tag' }));

    await waitFor(() => expect(api.editImageTag).toHaveBeenCalledWith(
      'c', 'd1', 'i1',
      expect.objectContaining({ action: 'ADD', tag: { category: 'ANIMAL', label: 'hund', source: 'MANUAL' } }),
      4,
    ));
  });

  it('shows the conflict message and readers get no edit buttons', async () => {
    vi.mocked(api.editImageTag).mockRejectedValue(new ApiError('EDIT_CONFLICT', 'Someone else changed these tags; reload and try again.', 409));
    const { unmount } = render(ImageViewer, { collectionId: 'c', documentId: 'd1', image: region, open: true, onClose: vi.fn() });
    await fireEvent.click((await screen.findAllByRole('button', { name: 'Remove' }))[0]);
    expect((await screen.findByRole('alert')).textContent).toContain('Someone else changed these tags');
    unmount();

    render(ImageViewer, { collectionId: 'c', documentId: 'd1', image: region, open: true, canEdit: false, onClose: vi.fn() });
    await screen.findByText('man: possibly Jeffrey Epstein');
    expect(screen.queryByRole('button', { name: 'Confirm' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Remove' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Add tag' })).toBeNull();
  });

  it('closes on Escape and returns focus to what opened it', async () => {
    render(ImageViewerHarness, { image: region });
    const opener = screen.getByRole('button', { name: 'Open image' });
    opener.focus();
    await fireEvent.click(opener);
    const dialog = await screen.findByRole('dialog');
    await fireEvent.keyDown(dialog, { key: 'Escape' });
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(document.activeElement).toBe(opener);
  });
});
