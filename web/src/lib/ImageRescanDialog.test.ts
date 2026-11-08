import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/svelte';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import * as api from './api';
import { ApiError } from './api';
import ImageRescanDialog from './ImageRescanDialog.svelte';

vi.mock('./api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('./api')>()),
  getImageRescanCensus: vi.fn(),
  listOcrProfiles: vi.fn(),
  startImageRescan: vi.fn(),
}));

const job = { id: 'j1', type: 'IMAGE_RESCAN', state: 'QUEUED', createdAt: 't', updatedAt: 't', completed: 0, total: 3 } as api.JobApiView;

describe('ImageRescanDialog', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(api.getImageRescanCensus).mockResolvedValue({ eligible: 3, skipped: { UNSUPPORTED_FORMAT: 2 }, job: null });
    vi.mocked(api.listOcrProfiles).mockResolvedValue([]);
    vi.mocked(api.startImageRescan).mockResolvedValue(job);
  });
  afterEach(cleanup);

  const open = (props: Record<string, unknown> = {}) =>
    render(ImageRescanDialog, { collectionId: 'c1', collectionName: 'Letters', onClose: vi.fn(), onStarted: vi.fn(), ...props });

  it('shows how many documents are covered and why others are left out', async () => {
    open();
    expect(await screen.findByText('3 documents will be scanned for images.')).toBeTruthy();
    expect(screen.getByText('2 documents left out: Office file or e-book (their images come with a new import)')).toBeTruthy();
  });

  it('starts with the chosen image mode and reports the job', async () => {
    const onStarted = vi.fn();
    open({ onStarted });
    await screen.findByText('3 documents will be scanned for images.');
    await fireEvent.change(screen.getByLabelText('Extract images'), { target: { value: 'all' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Start' }));

    await waitFor(() => expect(onStarted).toHaveBeenCalledWith(job));
    expect(vi.mocked(api.startImageRescan)).toHaveBeenCalledWith('c1', 'all');
  });

  it('pre-fills from the collection default and never offers Off', async () => {
    open({ defaultImageExtraction: 'all' });
    const select = screen.getByLabelText('Extract images') as HTMLSelectElement;
    expect(select.value).toBe('all');
    expect(Array.from(select.options).map((option) => option.value)).toEqual(['embedded', 'all']);
  });

  it('offers only checked image profiles and starts with the chosen analysis', async () => {
    vi.mocked(api.listOcrProfiles).mockResolvedValue([
      { id: 'v1', name: 'Vision', enabled: true, imageCapabilityMeasured: true } as api.OcrProfile,
      { id: 'unchecked', name: 'Unchecked', enabled: true, imageCapabilityMeasured: null } as api.OcrProfile,
    ]);
    open();
    await screen.findByText('3 documents will be scanned for images.');
    expect(screen.queryByRole('option', { name: 'Unchecked' })).toBeNull();
    await fireEvent.change(screen.getByTestId('describe-images'), { target: { value: 'v1' } });
    await fireEvent.click(screen.getByTestId('identify-appearance'));
    await fireEvent.click(screen.getByRole('button', { name: 'Start' }));

    await waitFor(() => expect(api.startImageRescan).toHaveBeenCalledWith(
      'c1', 'embedded', { profileId: 'v1', identifyAppearance: true },
    ));
  });

  it('cannot start when no document qualifies', async () => {
    vi.mocked(api.getImageRescanCensus).mockResolvedValue({ eligible: 0, skipped: { NOT_READ: 1 }, job: null });
    open();
    await screen.findByText('0 documents will be scanned for images.');
    expect((screen.getByRole('button', { name: 'Start' }) as HTMLButtonElement).disabled).toBe(true);
  });

  it('says a run is already going', async () => {
    vi.mocked(api.getImageRescanCensus).mockResolvedValue({ eligible: 3, skipped: {}, job: { ...job, state: 'RUNNING' } });
    open();
    expect(await screen.findByText('Images are already being found in this collection; starting shows that run.')).toBeTruthy();
  });

  it('shows a refusal and lets the person try again', async () => {
    vi.mocked(api.startImageRescan).mockRejectedValueOnce(new ApiError('COLLECTION_DELETING', 'The collection is being deleted.', 409));
    open();
    await screen.findByText('3 documents will be scanned for images.');
    await fireEvent.click(screen.getByRole('button', { name: 'Start' }));

    expect(await screen.findByText('The collection is being deleted.')).toBeTruthy();
    expect((screen.getByRole('button', { name: 'Start' }) as HTMLButtonElement).disabled).toBe(false);
  });

  it('closes on Escape', async () => {
    const onClose = vi.fn();
    open({ onClose });
    await fireEvent.keyDown(screen.getByRole('dialog'), { key: 'Escape' });
    expect(onClose).toHaveBeenCalled();
  });
});
