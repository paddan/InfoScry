import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/svelte';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import * as api from './api';
import type { Collection } from './api';
import CollectionGlossarySettings from './CollectionGlossarySettings.svelte';

vi.mock('./api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('./api')>()),
  listGlossary: vi.fn(),
  deleteGlossaryGroup: vi.fn(),
  putGlossaryGroup: vi.fn(),
}));

const collection = { id: 'c1', name: 'Archive' } as Collection;

const stockholm = { id: 'g1', terms: [{ text: 'Stockholm', prefix: false }, { text: 'Sthlm', prefix: true }] };
const kth = { id: 'g2', terms: [{ text: 'KTH', prefix: false }, { text: 'Kungliga Tekniska högskolan', prefix: false }] };

describe('CollectionGlossarySettings', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(api.listGlossary).mockResolvedValue([stockholm, kth]);
    vi.mocked(api.deleteGlossaryGroup).mockResolvedValue(undefined);
  });
  afterEach(cleanup);

  it('lists each group as its terms with prefix terms marked', async () => {
    render(CollectionGlossarySettings, { collection });

    const groups = await screen.findAllByRole('listitem');
    expect(groups).toHaveLength(2);
    expect(groups[0].textContent).toContain('Stockholm');
    expect(groups[0].textContent).toContain('Sthlm*');
    expect(screen.getByText('Names with the same meaning. Searching one finds the others; nothing in the text is changed.')).toBeTruthy();
  });

  it('removing a group moves focus to the next group, and to Add group when none is left', async () => {
    render(CollectionGlossarySettings, { collection });
    await screen.findAllByRole('listitem');

    vi.mocked(api.listGlossary).mockResolvedValue([kth]);
    await fireEvent.click(screen.getByRole('button', { name: 'Remove Stockholm' }));
    await waitFor(() => expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Edit KTH' })));
    expect(api.deleteGlossaryGroup).toHaveBeenCalledWith('c1', 'g1');

    vi.mocked(api.listGlossary).mockResolvedValue([]);
    await fireEvent.click(screen.getByRole('button', { name: 'Remove KTH' }));
    await waitFor(() => expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Add group' })));
  });

  it('keeps the section when the list cannot be loaded', async () => {
    vi.mocked(api.listGlossary).mockRejectedValue(new api.ApiError('HTTP_500', 'The server could not answer.'));
    render(CollectionGlossarySettings, { collection });

    expect(await screen.findByRole('alert')).toBeTruthy();
    expect(screen.getByRole('heading', { name: 'Glossary' })).toBeTruthy();
  });

  it('opens the form for a new group', async () => {
    render(CollectionGlossarySettings, { collection });
    await screen.findAllByRole('listitem');

    await fireEvent.click(screen.getByRole('button', { name: 'Add group' }));

    expect(await screen.findByRole('dialog')).toBeTruthy();
  });
});
