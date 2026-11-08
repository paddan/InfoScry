import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/svelte';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Collection, ReadingMethodList } from './api';
import CollectionOcrSettings from './CollectionOcrSettings.svelte';
import * as api from './api';

vi.mock('./api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('./api')>()),
  listReadingMethods: vi.fn(), listOcrProfiles: vi.fn(), updateCollectionOcrSettings: vi.fn(),
}));

const collection = (defaultMethod: string | null = 'tesseract'): Collection => ({
  id: 'c1', name: 'Archive', language: 'eng', defaultMethod,
  createdAt: '', updatedAt: '', lifecycle: 'ACTIVE', documentCount: 0,
});
const methods: ReadingMethodList = { default: 'tesseract', methods: [
  { method: 'tesseract', label: 'Tesseract', destination: 'this machine', available: true, unavailableReason: null, external: false },
  { method: 'surya', label: 'Surya', destination: 'this machine', available: false, unavailableReason: 'Model is missing', external: false },
  { method: 'none', label: "No OCR (use the document's own text)", destination: 'this machine', available: true, unavailableReason: null, external: false },
] };

describe('CollectionOcrSettings', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(api.listReadingMethods).mockResolvedValue(methods);
    vi.mocked(api.listOcrProfiles).mockResolvedValue([]);
    vi.mocked(api.updateCollectionOcrSettings).mockResolvedValue(collection());
  });
  afterEach(cleanup);

  it('shows language and one method list, with unavailable reasons', async () => {
    render(CollectionOcrSettings, { collection: collection(), onChanged: vi.fn() });
    expect(await screen.findByLabelText('OCR language')).toBeTruthy();
    expect(screen.getByLabelText('Default reading method')).toBeTruthy();
    expect((await screen.findByRole('option', { name: 'Surya — unavailable' }) as HTMLOptionElement).disabled).toBe(true);
    // The reason is beside the list, so a long sentence never widens the select.
    expect(screen.getByText('Surya: Model is missing')).toBeTruthy();
    expect(screen.queryByLabelText(/engine|review|allowance|mode/i)).toBeNull();
  });

  it('saves language and the selected method together', async () => {
    const onChanged = vi.fn();
    render(CollectionOcrSettings, { collection: collection(), onChanged });
    await screen.findByLabelText('OCR language');
    await fireEvent.input(screen.getByLabelText('OCR language'), { target: { value: 'swe' } });
    await fireEvent.change(screen.getByLabelText('Default reading method'), { target: { value: 'tesseract' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Save OCR settings' }));
    await waitFor(() => expect(api.updateCollectionOcrSettings).toHaveBeenCalledWith('c1', { language: 'swe', defaultMethod: 'tesseract' }, undefined));
    expect(onChanged).toHaveBeenCalledOnce();
  });

  it('offers and saves No OCR as the default', async () => {
    render(CollectionOcrSettings, { collection: collection(), onChanged: vi.fn() });
    await screen.findByRole('option', { name: "No OCR (use the document's own text)" });
    await fireEvent.change(screen.getByLabelText('Default reading method'), { target: { value: 'none' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Save OCR settings' }));
    await waitFor(() => expect(api.updateCollectionOcrSettings).toHaveBeenCalledWith('c1', { language: 'eng', defaultMethod: 'none' }, undefined));
  });

  it('saves language when no default method is stored without sending an empty default', async () => {
    vi.mocked(api.listReadingMethods).mockResolvedValue({ ...methods, default: null });
    render(CollectionOcrSettings, { collection: collection(null), onChanged: vi.fn() });
    await screen.findByLabelText('OCR language');
    await fireEvent.input(screen.getByLabelText('OCR language'), { target: { value: 'eng+deu' } });
    const methodSelect = screen.getByLabelText('Default reading method') as HTMLSelectElement;
    expect(methodSelect.value).toBe('');
    expect(screen.getByRole('option', { name: /choose a reading method/i }).hasAttribute('disabled')).toBe(true);
    expect((screen.getByRole('button', { name: 'Save OCR settings' }) as HTMLButtonElement).disabled).toBe(false);
    expect(screen.queryByRole('option', { name: 'No default method' })).toBeNull();
    await fireEvent.click(screen.getByRole('button', { name: 'Save OCR settings' }));
    await waitFor(() => expect(api.updateCollectionOcrSettings).toHaveBeenCalledWith('c1', { language: 'eng+deu' }, undefined));
  });

  it('preserves a stored but unavailable method while saving the language', async () => {
    render(CollectionOcrSettings, { collection: collection('surya'), onChanged: vi.fn() });
    await screen.findByLabelText('OCR language');
    await fireEvent.input(screen.getByLabelText('OCR language'), { target: { value: 'eng+deu' } });
    expect((screen.getByRole('button', { name: 'Save OCR settings' }) as HTMLButtonElement).disabled).toBe(false);
    await fireEvent.click(screen.getByRole('button', { name: 'Save OCR settings' }));
    await waitFor(() => expect(api.updateCollectionOcrSettings).toHaveBeenCalledWith('c1', { language: 'eng+deu', defaultMethod: 'surya' }, undefined));
  });

  it('saves the default image extraction with the version it was edited from', async () => {
    render(CollectionOcrSettings, { collection: { ...collection(), version: 7, defaultImageExtraction: 'embedded' }, onChanged: vi.fn() });
    const select = await screen.findByLabelText('Extract images by default') as HTMLSelectElement;
    expect(select.value).toBe('embedded');
    await fireEvent.change(select, { target: { value: 'all' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Save OCR settings' }));
    await waitFor(() => expect(api.updateCollectionOcrSettings).toHaveBeenCalledWith('c1', { language: 'eng', defaultMethod: 'tesseract', defaultImageExtraction: 'all' }, 7));
  });

  it('shows and saves the image analysis defaults with the version they were edited from', async () => {
    vi.mocked(api.listOcrProfiles).mockResolvedValue([
      { id: 'p1', name: 'Vision', enabled: true, imageCapabilityMeasured: true } as api.OcrProfile,
      { id: 'p2', name: 'Unchecked', enabled: true, imageCapabilityMeasured: null } as api.OcrProfile,
    ]);
    render(CollectionOcrSettings, {
      collection: { ...collection(), version: 7, defaultImageExtraction: 'embedded', defaultImageProfileId: 'p1' },
      onChanged: vi.fn(),
    });
    const select = await screen.findByTestId('default-describe-images') as HTMLSelectElement;
    await waitFor(() => expect(select.value).toBe('p1'));
    expect(screen.queryByRole('option', { name: 'Unchecked' })).toBeNull();
    await fireEvent.click(screen.getByTestId('default-identify-appearance'));
    await fireEvent.click(screen.getByRole('button', { name: 'Save OCR settings' }));

    await waitFor(() => expect(api.updateCollectionOcrSettings).toHaveBeenCalledWith(
      'c1',
      { language: 'eng', defaultMethod: 'tesseract', defaultImageProfileId: 'p1', defaultIdentifyAppearance: true },
      7,
    ));
  });

  it('clears the analysis profile when images are turned off', async () => {
    render(CollectionOcrSettings, {
      collection: { ...collection(), defaultImageExtraction: 'embedded', defaultImageProfileId: 'p1', defaultIdentifyAppearance: true },
      onChanged: vi.fn(),
    });
    const images = await screen.findByLabelText('Extract images by default');
    await fireEvent.change(images, { target: { value: 'off' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Save OCR settings' }));

    await waitFor(() => expect(api.updateCollectionOcrSettings).toHaveBeenCalledWith(
      'c1',
      { language: 'eng', defaultMethod: 'tesseract', defaultImageExtraction: 'off', defaultImageProfileId: 'none', defaultIdentifyAppearance: false },
      undefined,
    ));
  });

  it('shows the conflict message when someone else changed the settings', async () => {
    vi.mocked(api.updateCollectionOcrSettings).mockRejectedValue(new api.ApiError('EDIT_CONFLICT', 'These settings changed while you were editing; reload and try again.', 409));
    render(CollectionOcrSettings, { collection: { ...collection(), version: 7 }, onChanged: vi.fn() });
    await fireEvent.change(await screen.findByLabelText('Extract images by default'), { target: { value: 'embedded' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Save OCR settings' }));
    expect((await screen.findByRole('alert')).textContent).toContain('These settings changed while you were editing');
  });
});
