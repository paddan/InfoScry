import { cleanup, fireEvent, render, screen } from '@testing-library/svelte';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { ImageView } from './api';
import ImageViewer from './ImageViewer.svelte';
import ImageViewerHarness from './ImageViewerHarness.test.svelte';

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

describe('ImageViewer', () => {
  afterEach(cleanup);

  it('shows the caption, the page it came from and the full-size file', () => {
    render(ImageViewer, { collectionId: 'c', documentId: 'd1', image: region, open: true, onClose: vi.fn() });
    expect(screen.getByRole('dialog')).toBeTruthy();
    expect(screen.getByText('A harbour at dusk')).toBeTruthy();
    expect(screen.getByText('from page 14')).toBeTruthy();
    expect(screen.getByAltText('A harbour at dusk').getAttribute('src')).toBe('/api/collections/c/documents/d1/images/i1/file');
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
