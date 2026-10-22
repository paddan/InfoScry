import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/svelte';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import * as api from './api';
import { ApiError } from './api';
import CorrectionDialog from './CorrectionDialog.svelte';

vi.mock('./api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('./api')>()),
  previewCorrection: vi.fn(),
  putCorrection: vi.fn(),
}));

const preview = {
  matches: 14,
  documents: 6,
  examples: [{ documentId: 'd1', sourceId: 'u1', before: 'letter from spinners to', after: 'letter from Spinnars to' }],
};

describe('CorrectionDialog', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(api.previewCorrection).mockResolvedValue(preview);
    vi.mocked(api.putCorrection).mockResolvedValue({ id: 'r1', find: 'spinners', replace: 'Spinnars', exactCase: false, matches: 0 });
  });
  afterEach(cleanup);

  const open = (props: Record<string, unknown> = {}) =>
    render(CorrectionDialog, { collectionId: 'c1', onClose: vi.fn(), onSaved: vi.fn(), ...props });

  it('saves only after a preview of the current words', async () => {
    const onSaved = vi.fn();
    open({ onSaved });
    await fireEvent.input(screen.getByLabelText('Wrong'), { target: { value: 'spinners' } });
    await fireEvent.input(screen.getByLabelText('Correct'), { target: { value: 'Spinnars' } });
    const save = screen.getByRole('button', { name: 'Save' }) as HTMLButtonElement;
    expect(save.disabled).toBe(true);

    await fireEvent.click(screen.getByRole('button', { name: 'Preview' }));

    expect(await screen.findByText('14 matches in 6 documents')).toBeTruthy();
    expect(screen.getByText('letter from Spinnars to')).toBeTruthy();
    expect(save.disabled).toBe(false);
    await fireEvent.click(save);
    await waitFor(() => expect(onSaved).toHaveBeenCalled());
    expect(vi.mocked(api.putCorrection).mock.calls[0][2]).toEqual({ find: 'spinners', replace: 'Spinnars', exactCase: false });
  });

  it('asks for a new preview when a word changes', async () => {
    open();
    await fireEvent.input(screen.getByLabelText('Wrong'), { target: { value: 'spinners' } });
    await fireEvent.input(screen.getByLabelText('Correct'), { target: { value: 'Spinnars' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Preview' }));
    await screen.findByText('14 matches in 6 documents');

    await fireEvent.input(screen.getByLabelText('Correct'), { target: { value: 'Spinnarsson' } });

    expect((screen.getByRole('button', { name: 'Save' }) as HTMLButtonElement).disabled).toBe(true);
  });

  it('opens from a selection with the word filled in and focus on what it should read', async () => {
    open({ initialFind: 'spinners' });

    expect((screen.getByLabelText('Wrong') as HTMLInputElement).value).toBe('spinners');
    await waitFor(() => expect(document.activeElement).toBe(screen.getByLabelText('Correct')));
  });

  it('says plainly when the word already has a correction', async () => {
    vi.mocked(api.putCorrection).mockRejectedValue(new ApiError('CORRECTION_FIND_TAKEN', 'another correction already finds that word', 409));
    open();
    await fireEvent.input(screen.getByLabelText('Wrong'), { target: { value: 'spinners' } });
    await fireEvent.input(screen.getByLabelText('Correct'), { target: { value: 'Spinnars' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Preview' }));
    await screen.findByText('14 matches in 6 documents');

    await fireEvent.click(screen.getByRole('button', { name: 'Save' }));

    expect(await screen.findByText('That word already has a correction.')).toBeTruthy();
  });

  it('uses singular words for one match in one document', async () => {
    vi.mocked(api.previewCorrection).mockResolvedValue({ matches: 1, documents: 1, examples: [] });
    open({ initialFind: 'spinners', initialReplace: 'Spinnars' });

    await fireEvent.click(screen.getByRole('button', { name: 'Preview' }));

    expect(await screen.findByText('1 match in 1 document')).toBeTruthy();
  });

  it('closes on Escape', async () => {
    const onClose = vi.fn();
    open({ onClose });

    await fireEvent.keyDown(screen.getByRole('dialog'), { key: 'Escape' });

    expect(onClose).toHaveBeenCalled();
  });
});
