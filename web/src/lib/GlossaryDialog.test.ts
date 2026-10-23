import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/svelte';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import * as api from './api';
import GlossaryDialog from './GlossaryDialog.svelte';

vi.mock('./api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('./api')>()),
  putGlossaryGroup: vi.fn(),
}));

const groups = [{ id: 'g1', terms: [{ text: 'Stockholm', prefix: false }, { text: 'Sthlm', prefix: false }] }];

function termInputs(): HTMLInputElement[] {
  return screen.getAllByLabelText(/^Term \d+$/) as HTMLInputElement[];
}

describe('GlossaryDialog', () => {
  beforeEach(() => vi.clearAllMocks());
  afterEach(cleanup);

  it('starts with the selected word and keeps one id across a retried save', async () => {
    vi.mocked(api.putGlossaryGroup)
      .mockRejectedValueOnce(new api.ApiError('NETWORK', 'The connection was lost.'))
      .mockResolvedValueOnce({ id: 'x', terms: [] });
    const onSaved = vi.fn();
    render(GlossaryDialog, { collectionId: 'c1', groups, initialTerm: 'Sthlms', onClose: vi.fn(), onSaved });

    expect(termInputs()[0].value).toBe('Sthlms');
    await fireEvent.input(termInputs()[1], { target: { value: 'Stockholms' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    expect(await screen.findByRole('alert')).toBeTruthy();
    await fireEvent.click(screen.getByRole('button', { name: 'Save' }));

    await waitFor(() => expect(onSaved).toHaveBeenCalled());
    const ids = vi.mocked(api.putGlossaryGroup).mock.calls.map((call) => call[1]);
    expect(ids).toHaveLength(2);
    expect(ids[0]).toBe(ids[1]);
    expect(vi.mocked(api.putGlossaryGroup).mock.calls[1][2]).toEqual([
      { text: 'Sthlms', prefix: false },
      { text: 'Stockholms', prefix: false },
    ]);
  });

  it('shows a conflict at the term, naming the group that has it', async () => {
    vi.mocked(api.putGlossaryGroup).mockRejectedValue(
      new api.ApiError('GLOSSARY_TERM_TAKEN', 'taken', 409, { term: '1', groupId: 'g1' }),
    );
    render(GlossaryDialog, { collectionId: 'c1', groups, onClose: vi.fn(), onSaved: vi.fn() });

    await fireEvent.input(termInputs()[0], { target: { value: 'Huvudstaden' } });
    await fireEvent.input(termInputs()[1], { target: { value: 'STHLM' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Save' }));

    expect(await screen.findByText('Already in the group “Stockholm”.')).toBeTruthy();
    await waitFor(() => expect(document.activeElement).toBe(termInputs()[1]));
    expect(termInputs()[1].getAttribute('aria-invalid')).toBe('true');
  });

  it('adds and removes terms but always keeps two, and maps the prefix box', async () => {
    vi.mocked(api.putGlossaryGroup).mockResolvedValue({ id: 'x', terms: [] });
    render(GlossaryDialog, { collectionId: 'c1', groups: [], onClose: vi.fn(), onSaved: vi.fn() });

    expect(screen.queryByRole('button', { name: /Remove term/ })).toBeNull();
    await fireEvent.click(screen.getByRole('button', { name: 'Add term' }));
    expect(termInputs()).toHaveLength(3);
    await fireEvent.click(screen.getByRole('button', { name: 'Remove term 3' }));
    expect(termInputs()).toHaveLength(2);

    await fireEvent.input(termInputs()[0], { target: { value: 'Sthlm' } });
    await fireEvent.input(termInputs()[1], { target: { value: 'Stockholm' } });
    await fireEvent.click(screen.getAllByLabelText('Also match words that start with this')[0]);
    await fireEvent.click(screen.getByRole('button', { name: 'Save' }));

    await waitFor(() => expect(api.putGlossaryGroup).toHaveBeenCalled());
    expect(vi.mocked(api.putGlossaryGroup).mock.calls[0][2]).toEqual([
      { text: 'Sthlm', prefix: true },
      { text: 'Stockholm', prefix: false },
    ]);
  });

  it('edits a group under its own id', async () => {
    vi.mocked(api.putGlossaryGroup).mockResolvedValue({ id: 'g1', terms: [] });
    render(GlossaryDialog, { collectionId: 'c1', groups, group: groups[0], onClose: vi.fn(), onSaved: vi.fn() });

    expect(termInputs().map((input) => input.value)).toEqual(['Stockholm', 'Sthlm']);
    await fireEvent.click(screen.getByRole('button', { name: 'Save' }));

    await waitFor(() => expect(vi.mocked(api.putGlossaryGroup).mock.calls[0][1]).toBe('g1'));
  });
});
