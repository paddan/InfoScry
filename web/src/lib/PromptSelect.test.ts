import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/svelte';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import PromptSelect from './PromptSelect.svelte';
import type { CollectionPrompts } from './api';

const api = vi.hoisted(() => ({
  getCollectionPrompts: vi.fn(),
}));

vi.mock('./api', () => ({
  getCollectionPrompts: api.getCollectionPrompts,
}));

const defaultPrompt = { id: 'default', name: 'Default', body: 'Default body', version: 1, builtIn: true };
const boardPrompt = { id: 'p-board', name: 'Board tone', body: 'Plain language.', version: 1, builtIn: false };

/** The collection's prompts for both roles, sharing one default and one list for brevity. */
function collectionPrompts(defaultId: string, rows = [defaultPrompt, boardPrompt]): CollectionPrompts {
  return {
    coreRules: 'Core rules',
    ask: { defaultId, prompts: rows },
    investigate: { defaultId, prompts: rows },
  };
}

describe('Prompt select', () => {
  beforeEach(() => {
    vi.resetAllMocks();
    api.getCollectionPrompts.mockResolvedValue(collectionPrompts('default'));
  });

  afterEach(cleanup);

  it('pre-selects the collection default when nothing was chosen yet', async () => {
    api.getCollectionPrompts.mockResolvedValue(collectionPrompts('p-board'));

    render(PromptSelect, { props: { collectionId: 'collection-1', role: 'ASK' } });

    const select = (await screen.findByLabelText('Prompt')) as HTMLSelectElement;
    await waitFor(() => expect(select.value).toBe('p-board'));
    expect(select.id).toBe('prompt-ask');
  });

  it('keeps a remembered choice that the collection still offers', async () => {
    render(PromptSelect, { props: { collectionId: 'collection-1', role: 'INVESTIGATE', value: 'p-board' } });

    const select = (await screen.findByLabelText('Prompt')) as HTMLSelectElement;
    await waitFor(() => expect(select.querySelectorAll('option').length).toBe(2));
    expect(select.value).toBe('p-board');
  });

  it('a collection change selects that collection default and ignores a late answer for the previous collection', async () => {
    let releaseFirst!: (value: CollectionPrompts) => void;
    api.getCollectionPrompts.mockImplementation((id: string) => (id === 'collection-1'
      ? new Promise<CollectionPrompts>((resolve) => { releaseFirst = resolve; })
      : Promise.resolve(collectionPrompts('p-board'))));

    const view = render(PromptSelect, { props: { collectionId: 'collection-1', role: 'ASK' } });
    await view.rerender({ collectionId: 'collection-2' });

    const select = (await screen.findByLabelText('Prompt')) as HTMLSelectElement;
    await waitFor(() => expect(select.value).toBe('p-board'));
    releaseFirst(collectionPrompts('default'));
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(select.value).toBe('p-board');
  });

  it('a locked select shows the locked prompt name, or a neutral placeholder until the name is known', async () => {
    const view = render(PromptSelect, { props: { collectionId: 'collection-1', role: 'INVESTIGATE', locked: true, lockedName: null } });

    const select = (await screen.findByLabelText('Prompt')) as HTMLSelectElement;
    expect(select.disabled).toBe(true);
    expect(select.textContent).toContain('Prompt locked');

    await view.rerender({ lockedName: 'Board tone' });
    expect((await screen.findByLabelText('Prompt') as HTMLSelectElement).textContent).toContain('Board tone');
  });

  it('a failed prompt read shows the error and Retry reads the prompts again', async () => {
    api.getCollectionPrompts
      .mockRejectedValueOnce(new Error('the server is busy'))
      .mockResolvedValueOnce(collectionPrompts('default'));

    render(PromptSelect, { props: { collectionId: 'collection-1', role: 'ASK' } });

    expect((await screen.findByRole('alert')).textContent).toContain('the server is busy');
    expect(screen.getByText(/New questions use the collection default/)).toBeTruthy();
    await fireEvent.click(screen.getByRole('button', { name: 'Retry loading prompts' }));

    const select = (await screen.findByLabelText('Prompt')) as HTMLSelectElement;
    await waitFor(() => expect(select.querySelectorAll('option').length).toBe(2));
    expect(select.value).toBe('default');
    expect(screen.queryByRole('alert')).toBeNull();
    expect(api.getCollectionPrompts).toHaveBeenCalledTimes(2);
  });

  it('reload selects the collection default when the chosen prompt was deleted', async () => {
    api.getCollectionPrompts
      .mockResolvedValueOnce(collectionPrompts('default'))
      .mockResolvedValueOnce(collectionPrompts('default', [defaultPrompt]));

    const view = render(PromptSelect, { props: { collectionId: 'collection-1', role: 'ASK', value: 'p-board' } });
    const select = (await screen.findByLabelText('Prompt')) as HTMLSelectElement;
    await waitFor(() => expect(select.querySelectorAll('option').length).toBe(2));

    await view.component.reload();

    await waitFor(() => expect(select.value).toBe('default'));
    expect(select.querySelectorAll('option').length).toBe(1);
  });
});
