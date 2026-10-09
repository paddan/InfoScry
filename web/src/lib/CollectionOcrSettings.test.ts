import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/svelte';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Collection, ReadingMethodList } from './api';
import CollectionOcrSettings from './CollectionOcrSettings.svelte';
import * as api from './api';

vi.mock('./api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('./api')>()),
  listReadingMethods: vi.fn(), updateCollectionOcrSettings: vi.fn(),
}));

const collection = (defaultMethod: string | null = 'tesseract'): Collection => ({
  id: 'c1', name: 'Archive', language: 'eng', defaultMethod,
  createdAt: '', updatedAt: '', lifecycle: 'ACTIVE', documentCount: 0,
});
const methods: ReadingMethodList = { default: 'tesseract', methods: [
  { method: 'tesseract', label: 'Tesseract', destination: 'this machine', available: true, unavailableReason: null, external: false },
  { method: 'surya', label: 'Surya', destination: 'this machine', available: false, unavailableReason: 'Model is missing', external: false },
] };

describe('CollectionOcrSettings', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(api.listReadingMethods).mockResolvedValue(methods);
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
    await waitFor(() => expect(api.updateCollectionOcrSettings).toHaveBeenCalledWith('c1', { language: 'swe', defaultMethod: 'tesseract' }));
    expect(onChanged).toHaveBeenCalledOnce();
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
    await waitFor(() => expect(api.updateCollectionOcrSettings).toHaveBeenCalledWith('c1', { language: 'eng+deu' }));
  });

  it('preserves a stored but unavailable method while saving the language', async () => {
    render(CollectionOcrSettings, { collection: collection('surya'), onChanged: vi.fn() });
    await screen.findByLabelText('OCR language');
    await fireEvent.input(screen.getByLabelText('OCR language'), { target: { value: 'eng+deu' } });
    expect((screen.getByRole('button', { name: 'Save OCR settings' }) as HTMLButtonElement).disabled).toBe(false);
    await fireEvent.click(screen.getByRole('button', { name: 'Save OCR settings' }));
    await waitFor(() => expect(api.updateCollectionOcrSettings).toHaveBeenCalledWith('c1', { language: 'eng+deu', defaultMethod: 'surya' }));
  });
});
