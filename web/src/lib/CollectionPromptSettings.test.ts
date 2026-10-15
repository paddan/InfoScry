import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/svelte';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError, type Collection, type CollectionPrompts } from './api';
import CollectionPromptSettings from './CollectionPromptSettings.svelte';
import * as api from './api';

vi.mock('./api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('./api')>()),
  getCollectionPrompts: vi.fn(),
  createCollectionPrompt: vi.fn(),
  updateCollectionPrompt: vi.fn(),
  deleteCollectionPrompt: vi.fn(),
  setCollectionPromptDefault: vi.fn(),
}));

const collection = (id = 'c1', name = 'Archive'): Collection => ({
  id, name, language: 'eng', defaultMethod: null,
  createdAt: '', updatedAt: '', lifecycle: 'ACTIVE', documentCount: 0,
});

const prompts: CollectionPrompts = {
  coreRules: 'Answer only from the collection.',
  ask: {
    defaultId: 'default',
    prompts: [
      { id: 'default', name: 'Default', body: 'Ask default body', version: 1, builtIn: true },
      { id: 'p1', name: 'Board tone', body: 'Plain language.', version: 2, builtIn: false },
    ],
  },
  investigate: {
    defaultId: 'p2',
    prompts: [
      { id: 'default', name: 'Default', body: 'Investigate default body', version: 1, builtIn: true },
      { id: 'p2', name: 'Thorough', body: 'Search widely.', version: 1, builtIn: false },
    ],
  },
};

const otherPrompts: CollectionPrompts = {
  coreRules: 'Answer only from the collection.',
  ask: { defaultId: 'default', prompts: [{ id: 'default', name: 'Default', body: 'Other default', version: 1, builtIn: true }] },
  investigate: { defaultId: 'default', prompts: [{ id: 'default', name: 'Default', body: 'Other investigate', version: 1, builtIn: true }] },
};

const stored = (id: string, name: string, body: string) => ({ id, name, body, version: 1, builtIn: false });

describe('CollectionPromptSettings', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(api.getCollectionPrompts).mockImplementation(async (id: string) => (id === 'c1' ? prompts : otherPrompts));
    vi.mocked(api.updateCollectionPrompt).mockResolvedValue(stored('p1', 'Board tone', 'Plain language.'));
    vi.mocked(api.createCollectionPrompt).mockResolvedValue(stored('p3', 'Short', 'Be brief.'));
    vi.mocked(api.deleteCollectionPrompt).mockResolvedValue(undefined);
    vi.mocked(api.setCollectionPromptDefault).mockResolvedValue('p1');
  });
  afterEach(cleanup);

  it('lists Default first with a Built-in badge and marks the collection default', async () => {
    render(CollectionPromptSettings, { collection: collection() });
    const ask = await screen.findByRole('region', { name: 'Ask prompts' });
    const names = within(ask).getAllByRole('button', { name: /Default|Board tone/ });
    expect(names[0].textContent).toContain('Default');
    expect(names[0].textContent).toContain('Built-in');
    expect(names[0].textContent).toContain('Collection default');
    const investigate = screen.getByRole('region', { name: 'Investigate prompts' });
    expect(within(investigate).getByRole('button', { name: /Thorough/ }).textContent).toContain('Collection default');
  });

  it('keeps Default read-only and offers no delete for it', async () => {
    render(CollectionPromptSettings, { collection: collection() });
    const body = (await screen.findByLabelText('Prompt text')) as HTMLTextAreaElement;
    expect(body.value).toBe('Ask default body');
    expect(body.readOnly).toBe(true);
    expect((screen.getByLabelText('Prompt name') as HTMLInputElement).readOnly).toBe(true);
    expect(screen.queryByRole('button', { name: 'Delete prompt' })).toBeNull();
  });

  it('pre-fills New prompt from Default and Duplicate copies the selected prompt', async () => {
    render(CollectionPromptSettings, { collection: collection() });
    const ask = await screen.findByRole('region', { name: 'Ask prompts' });
    await fireEvent.click(within(ask).getByRole('button', { name: 'New prompt' }));
    expect((screen.getByLabelText('Prompt text') as HTMLTextAreaElement).value).toBe('Ask default body');
    expect((screen.getByLabelText('Prompt name') as HTMLInputElement).value).toBe('');

    await fireEvent.click(within(ask).getByRole('button', { name: /Board tone/ }));
    await fireEvent.click(screen.getByRole('button', { name: 'Duplicate' }));
    expect((screen.getByLabelText('Prompt name') as HTMLInputElement).value).toBe('Copy of Board tone');
    expect((screen.getByLabelText('Prompt text') as HTMLTextAreaElement).value).toBe('Plain language.');
  });

  it('enables Save only when the draft differs from the saved prompt', async () => {
    render(CollectionPromptSettings, { collection: collection() });
    const ask = await screen.findByRole('region', { name: 'Ask prompts' });
    await fireEvent.click(within(ask).getByRole('button', { name: /Board tone/ }));
    const save = screen.getByRole('button', { name: 'Save prompt' }) as HTMLButtonElement;
    expect(save.disabled).toBe(true);
    await fireEvent.input(screen.getByLabelText('Prompt text'), { target: { value: 'Plain language, short.' } });
    expect(save.disabled).toBe(false);
    await fireEvent.click(save);
    await waitFor(() => expect(api.updateCollectionPrompt).toHaveBeenCalledWith('c1', 'p1', 'Board tone', 'Plain language, short.'));
  });

  it('shows a name conflict next to Save', async () => {
    vi.mocked(api.updateCollectionPrompt).mockRejectedValue(new ApiError('PROMPT_NAME_TAKEN', 'a prompt with this name already exists', 409));
    render(CollectionPromptSettings, { collection: collection() });
    const ask = await screen.findByRole('region', { name: 'Ask prompts' });
    await fireEvent.click(within(ask).getByRole('button', { name: /Board tone/ }));
    await fireEvent.input(screen.getByLabelText('Prompt name'), { target: { value: 'Default' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Save prompt' }));
    expect(await screen.findByText('a prompt with this name already exists')).toBeTruthy();
  });

  it('asks for confirmation naming the prompt, and says what happens when it is the collection default', async () => {
    render(CollectionPromptSettings, { collection: collection() });
    const investigate = await screen.findByRole('region', { name: 'Investigate prompts' });
    await fireEvent.click(within(investigate).getByRole('button', { name: /Thorough/ }));
    await fireEvent.click(screen.getByRole('button', { name: 'Delete prompt' }));
    const dialog = await screen.findByRole('dialog');
    expect(dialog.textContent).toContain('Thorough');
    expect(dialog.textContent).toContain('Default will be used');
    expect(api.deleteCollectionPrompt).not.toHaveBeenCalled();
    await fireEvent.click(within(dialog).getByRole('button', { name: 'Delete prompt' }));
    await waitFor(() => expect(api.deleteCollectionPrompt).toHaveBeenCalledWith('c1', 'p2'));
  });

  it('Use as collection default calls setCollectionPromptDefault for the role', async () => {
    render(CollectionPromptSettings, { collection: collection() });
    const ask = await screen.findByRole('region', { name: 'Ask prompts' });
    await fireEvent.click(within(ask).getByRole('button', { name: /Board tone/ }));
    await fireEvent.click(screen.getByRole('button', { name: 'Use as collection default' }));
    await waitFor(() => expect(api.setCollectionPromptDefault).toHaveBeenCalledWith('c1', 'ASK', 'p1'));
  });

  it('discards a save answer that arrives after another collection was selected', async () => {
    let finishSave: (value: ReturnType<typeof stored>) => void = () => undefined;
    vi.mocked(api.updateCollectionPrompt).mockReturnValue(new Promise((resolve) => { finishSave = resolve; }));
    const view = render(CollectionPromptSettings, { collection: collection('c1') });
    const ask = await screen.findByRole('region', { name: 'Ask prompts' });
    await fireEvent.click(within(ask).getByRole('button', { name: /Board tone/ }));
    await fireEvent.input(screen.getByLabelText('Prompt text'), { target: { value: 'Changed.' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Save prompt' }));

    await view.rerender({ collection: collection('c2', 'Other') });
    await waitFor(() => expect((screen.getByLabelText('Prompt text') as HTMLTextAreaElement).value).toBe('Other default'));

    finishSave(stored('p1', 'Board tone', 'Changed.'));
    await waitFor(() => expect(api.updateCollectionPrompt).toHaveBeenCalledOnce());
    expect(screen.queryByText('Prompt saved.')).toBeNull();
    expect((screen.getByLabelText('Prompt text') as HTMLTextAreaElement).value).toBe('Other default');
  });

  it('keeps the help collapsed by default and links it from the body field', async () => {
    render(CollectionPromptSettings, { collection: collection() });
    const body = await screen.findByLabelText('Prompt text');
    const describedBy = body.getAttribute('aria-describedby');
    expect(describedBy).toBeTruthy();
    const help = document.getElementById(describedBy ?? '');
    expect(help?.textContent).toContain('Always applied first.');
    expect(help?.closest('details')?.hasAttribute('open')).toBe(false);
  });
});
