import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/svelte';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import * as api from './api';
import SourceReadingHarness from './SourceReadingHarness.test.svelte';

vi.mock('./api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('./api')>()),
  readSourceReading: vi.fn(),
  listImages: vi.fn(async () => []),
  previewCorrection: vi.fn(),
  putCorrection: vi.fn(),
  putGlossaryGroup: vi.fn(),
}));

/** Selects [text] inside the element, the way a reader's drag does, and lets go of the mouse. */
async function select(element: HTMLElement, text: string): Promise<void> {
  const node = element.firstChild as Text;
  const start = node.data.indexOf(text);
  const range = document.createRange();
  range.setStart(node, start);
  range.setEnd(node, start + text.length);
  const selection = window.getSelection()!;
  selection.removeAllRanges();
  selection.addRange(range);
  await fireEvent.mouseUp(element);
}

describe('SourceReading', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(api.readSourceReading).mockResolvedValue('hos spinners');
    vi.mocked(api.previewCorrection).mockResolvedValue({ matches: 1, documents: 1, examples: [] });
  });
  afterEach(cleanup);

  it('shows the reading of a corrected page and switches back', async () => {
    render(SourceReadingHarness, { hasReading: true, text: 'hos Spinnars' });

    await fireEvent.click(screen.getByRole('button', { name: 'Show reading' }));

    expect(await screen.findByText('hos spinners')).toBeTruthy();
    expect(api.readSourceReading).toHaveBeenCalledWith('c1', 'u1', null);
    await fireEvent.click(screen.getByRole('button', { name: 'Show corrected text' }));
    expect(screen.getByText('hos Spinnars')).toBeTruthy();
  });

  it('offers no reading for a page the corrections did not change', () => {
    render(SourceReadingHarness, { hasReading: false, text: 'hos Spinnars' });

    expect(screen.queryByRole('button', { name: 'Show reading' })).toBeNull();
  });

  it('offers a correction for a selected word and opens it filled in', async () => {
    render(SourceReadingHarness, { hasReading: false, text: 'brev från spinners igen' });

    await select(screen.getByText('brev från spinners igen'), 'spinners');
    await fireEvent.click(await screen.findByRole('button', { name: 'Correct across collection' }));

    const dialog = await screen.findByRole('dialog');
    expect((screen.getByLabelText('Wrong') as HTMLInputElement).value).toBe('spinners');
    expect(dialog).toBeTruthy();
  });

  it('offers to add a selected word to the glossary, filled in as the first term', async () => {
    render(SourceReadingHarness, { hasReading: false, text: 'brev från Sthlm igen' });

    await select(screen.getByText('brev från Sthlm igen'), 'Sthlm');
    await fireEvent.click(await screen.findByRole('button', { name: 'Add to glossary' }));

    await screen.findByRole('dialog');
    expect((screen.getByLabelText('Term 1') as HTMLInputElement).value).toBe('Sthlm');
  });

  it('offers nothing for a selection longer than a rule may be', async () => {
    const long = 'a'.repeat(201);
    render(SourceReadingHarness, { hasReading: false, text: long });

    await select(screen.getByText(long), long);

    await waitFor(() => expect(screen.queryByRole('button', { name: 'Correct across collection' })).toBeNull());
  });

  describe('images', () => {
    const image = (over: Partial<api.ImageView> = {}): api.ImageView => ({
      unitId: 'i1',
      locator: { type: 'image_region', parent: { type: 'pdf_page', page: 14 }, index: 2, box: null },
      label: 'image 2 on page 14', parentUnitId: 'u1', kind: 'EMBEDDED', mediaType: 'image/png', widthPx: 400, heightPx: 300,
      caption: null, captionSource: null, ...over,
    });

    it('shows a strip under the unit for its own images only', async () => {
      vi.mocked(api.listImages).mockResolvedValue([image(), image({ unitId: 'i9', parentUnitId: 'other' })]);
      render(SourceReadingHarness, { hasReading: false, text: 'page text', documentId: 'd1' });

      expect((await screen.findAllByRole('img')).length).toBe(1);
      expect(api.listImages).toHaveBeenCalledWith('c1', 'd1', null);
      await fireEvent.click(screen.getByRole('button', { name: 'Image 2 on page 14' }));
      expect(await screen.findByRole('dialog')).toBeTruthy();
    });

    it('opens the viewer over its parent for a citation on an image', async () => {
      vi.mocked(api.listImages).mockResolvedValue([image({ caption: 'A harbour at dusk' })]);
      render(SourceReadingHarness, { hasReading: false, text: 'page text', documentId: 'd1', openImageUnitId: 'i1' });

      expect(await screen.findByRole('dialog')).toBeTruthy();
      expect(screen.getByText('page text')).toBeTruthy();
      expect(screen.getAllByText('A harbour at dusk').length).toBeGreaterThan(0);
    });

    it('keeps the text readable when the images cannot be listed', async () => {
      vi.mocked(api.listImages).mockRejectedValue(new Error('offline'));
      render(SourceReadingHarness, { hasReading: false, text: 'page text', documentId: 'd1' });

      await waitFor(() => expect(api.listImages).toHaveBeenCalled());
      expect(screen.getByText('page text')).toBeTruthy();
      expect(screen.queryByRole('list')).toBeNull();
    });
  });
});
