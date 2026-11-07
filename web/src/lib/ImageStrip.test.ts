import { cleanup, fireEvent, render, screen } from '@testing-library/svelte';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { ImageView } from './api';
import ImageStrip from './ImageStrip.svelte';

const image = (over: Partial<ImageView> = {}): ImageView => ({
  unitId: 'i1',
  locator: { type: 'image_region', parent: { type: 'pdf_page', page: 14 }, index: 2, box: null },
  label: 'image 2 on page 14',
  parentUnitId: 'p14',
  kind: 'EMBEDDED',
  mediaType: 'image/png',
  widthPx: 400,
  heightPx: 300,
  caption: null,
  captionSource: null,
  ...over,
});

describe('ImageStrip', () => {
  afterEach(cleanup);

  it('shows one thumbnail per image, described by its caption or its place', () => {
    render(ImageStrip, { collectionId: 'c 1', documentId: 'd1', images: [image(), image({ unitId: 'i2', caption: 'A harbour at dusk' })], onOpen: vi.fn() });
    const thumbnails = screen.getAllByRole('img') as HTMLImageElement[];
    expect(thumbnails.map((entry) => entry.getAttribute('alt'))).toEqual(['Image 2 on page 14', 'A harbour at dusk']);
    expect(thumbnails[0].getAttribute('src')).toBe('/api/collections/c%201/documents/d1/images/i1/thumbnail');
  });

  it('asks for the thumbnails of an older revision by name', () => {
    render(ImageStrip, { collectionId: 'c', documentId: 'd1', revisionId: 'rev 1', images: [image()], onOpen: vi.fn() });
    expect((screen.getAllByRole('img')[0] as HTMLImageElement).getAttribute('src'))
      .toBe('/api/collections/c/documents/d1/images/i1/thumbnail?revision=rev%201');
  });

  it('opens an image with a click or the Enter key', async () => {
    const onOpen = vi.fn();
    render(ImageStrip, { collectionId: 'c', documentId: 'd1', images: [image()], onOpen });
    const button = screen.getByRole('button', { name: 'Image 2 on page 14' });
    await fireEvent.keyDown(button, { key: 'Enter' });
    expect(onOpen).toHaveBeenCalledTimes(1);
    expect(onOpen.mock.calls[0][0].unitId).toBe('i1');
    await fireEvent.click(button);
    expect(onOpen).toHaveBeenCalledTimes(2);
  });

  it('renders nothing for a unit without images', () => {
    render(ImageStrip, { collectionId: 'c', documentId: 'd1', images: [], onOpen: vi.fn() });
    expect(screen.queryByRole('list')).toBeNull();
  });
});
