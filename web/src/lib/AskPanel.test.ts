import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/svelte';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import AskPanel from './AskPanel.svelte';
import type { AskEvent, AskHistoryEntry } from './api';

/**
 * The Ask panel takes the open conversation from the page: the page owns the id and the stored
 * list, the panel replays the selected row (citations, tokens, cost) or streams a fresh answer and
 * reports the stored conversation id the completion event named.
 */
const api = vi.hoisted(() => ({
  askDefaultProfile: vi.fn(async () => 'local-cheap'),
  listLlmProfilePrices: vi.fn(async () => [{ name: 'local-cheap', inputPricePerMillion: 1, outputPricePerMillion: 2 }]),
  startAsk: vi.fn(),
  readAskEvents: vi.fn(),
}));

vi.mock('./api', () => ({
  ApiError: class ApiError extends Error {},
  askDefaultProfile: api.askDefaultProfile,
  listLlmProfilePrices: api.listLlmProfilePrices,
  startAsk: api.startAsk,
  readAskEvents: api.readAskEvents,
}));

const events = (...items: AskEvent[]) => async function* () {
  for (const item of items) yield item;
};

const stored: AskHistoryEntry = {
  id: 'ask-1',
  createdAt: '2026-09-26T15:21:42Z',
  question: 'Who signed it?',
  title: 'The signer',
  answer: 'Mira signed it [S2].',
  evidence: [{ id: 'S2', documentId: 'doc-2', unitId: 'unit-2', locator: {}, locatorLabel: 'Page 8' }],
  inputTokens: 20,
  outputTokens: 9,
  costUsd: 0.0001,
};

describe('Ask panel', () => {
  beforeEach(() => {
    vi.resetAllMocks();
    api.askDefaultProfile.mockResolvedValue('local-cheap');
    api.listLlmProfilePrices.mockResolvedValue([{ name: 'local-cheap', inputPricePerMillion: 1, outputPricePerMillion: 2 }]);
  });

  afterEach(cleanup);

  it('replays the stored answer the page selected, with its source, tokens and cost', async () => {
    const onOpenSource = vi.fn();

    render(AskPanel, {
      props: { collectionId: 'collection-1', conversationId: 'ask-1', entries: [stored], onOpenSource },
    });

    expect(await screen.findByText(/Mira signed it/)).toBeTruthy();
    expect(screen.getByLabelText('Stored question').textContent).toBe('Who signed it?');
    const citation = screen.getByRole('button', { name: 'Open source S2, Page 8' });
    await fireEvent.click(citation);
    expect(onOpenSource).toHaveBeenCalledWith(expect.objectContaining({ id: 'S2', unitId: 'unit-2' }));
    expect(screen.getByText(/20 input tokens/)).toBeTruthy();
    expect(screen.getByText(/Estimated cost/).textContent).toContain('$0.0001');
  });

  it('shows the compose state when the page opens no conversation', async () => {
    const { rerender } = render(AskPanel, {
      props: { collectionId: 'collection-1', conversationId: 'ask-1', entries: [stored], onOpenSource: vi.fn() },
    });
    expect(await screen.findByText(/Mira signed it/)).toBeTruthy();

    await rerender({ conversationId: null });

    expect(screen.queryByLabelText('Stored question')).toBeNull();
    expect(screen.queryByLabelText('Answer')).toBeNull();
    expect((screen.getByLabelText('Question') as HTMLTextAreaElement).value).toBe('');
  });

  it('reports the conversation id the completion event named and shows its row once the list has it', async () => {
    api.startAsk.mockResolvedValue(new Response());
    api.readAskEvents.mockReturnValue(events(
      { type: 'delta', text: 'Mira signed it [S1].' },
      { type: 'usage', inputTokens: 7, outputTokens: 3 },
      { type: 'citation', id: 'S1', valid: true },
      { type: 'done', text: 'Mira signed it [S1].', evidence: [{ id: 'S1', documentId: 'doc-1', unitId: 'unit-1', locator: {}, locatorLabel: 'Page 4' }], conversationId: 'ask-2' },
    )());
    const onAnswerStored = vi.fn();
    const onAnswerFinished = vi.fn();

    const { rerender } = render(AskPanel, {
      props: { collectionId: 'collection-1', conversationId: null, entries: [], onAnswerStored, onAnswerFinished, onOpenSource: vi.fn() },
    });
    await fireEvent.input(await screen.findByLabelText('Question'), { target: { value: 'Who signed it?' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Ask' }));

    expect(await screen.findByRole('button', { name: 'Open source S1, Page 4' })).toBeTruthy();
    expect(onAnswerStored).toHaveBeenCalledWith('ask-2');
    expect(onAnswerFinished).toHaveBeenCalledTimes(1);

    // The streamed answer stays while the streamed view is live; once the page's refresh carries
    // the stored row and marks it, the stored answer replaces the live one.
    await rerender({
      conversationId: 'ask-2',
      entries: [{ ...stored, id: 'ask-2', question: 'Who signed it?', title: 'The signer', answer: 'Mira signed it [S1].', evidence: [{ id: 'S1', documentId: 'doc-1', unitId: 'unit-1', locator: {}, locatorLabel: 'Page 4' }] }],
    });

    expect(await screen.findByLabelText('Stored question')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Open source S1, Page 4' })).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Open source S2, Page 8' })).toBeNull();
  });

  it('keeps a streamed partial answer visible when the stream ends with an error', async () => {
    api.startAsk.mockResolvedValue(new Response());
    api.readAskEvents.mockReturnValue(events(
      { type: 'delta', text: 'Partial answer' },
      { type: 'error', code: 'PROVIDER_FAILED', message: 'provider unavailable' },
    )());

    render(AskPanel, {
      props: { collectionId: 'collection-1', conversationId: null, entries: [], onOpenSource: vi.fn() },
    });
    await fireEvent.input(await screen.findByLabelText('Question'), { target: { value: 'What happened?' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Ask' }));

    expect(await screen.findByText('Partial answer')).toBeTruthy();
    expect((await screen.findByRole('alert')).textContent).toContain('provider unavailable');
  });

  it('re-enables Ask as soon as the done event arrives while the stream keeps draining', async () => {
    let release!: () => void;
    const gate = new Promise<void>((resolve) => { release = resolve; });
    api.startAsk.mockResolvedValue(new Response());
    // The title call keeps the stream open after done, exactly as the server behaves today.
    api.readAskEvents.mockReturnValue((async function* () {
      yield { type: 'done', text: 'Completed answer', evidence: [], conversationId: 'ask-2' };
      await gate;
    })());
    const onAnswerFinished = vi.fn();
    const onAnswerStored = vi.fn();

    render(AskPanel, {
      props: { collectionId: 'collection-1', conversationId: null, entries: [], onAnswerStored, onAnswerFinished, onOpenSource: vi.fn() },
    });
    await fireEvent.input(await screen.findByLabelText('Question'), { target: { value: 'Who signed it?' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Ask' }));

    // The completion event is the user-visible end of the turn: the Ask control unlocks while the
    // stream is still open, and only a later EOF tells the page the title is written.
    const submit = () => screen.getByRole('button', { name: 'Ask' });
    await waitFor(() => expect(submit().getAttribute('disabled')).toBeNull());
    expect(onAnswerStored).toHaveBeenCalledWith('ask-2');
    expect(onAnswerFinished).not.toHaveBeenCalled();

    release();
    await waitFor(() => expect(onAnswerFinished).toHaveBeenCalledTimes(1));
  });

  it('clears the previous selection as a new Ask begins and keeps the partial answer if it fails', async () => {
    let release!: () => void;
    const gate = new Promise<void>((resolve) => { release = resolve; });
    api.startAsk.mockResolvedValue(new Response());
    api.readAskEvents.mockReturnValue((async function* () {
      await gate;
      yield { type: 'delta', text: 'Partial answer' };
      yield { type: 'error', code: 'PROVIDER_FAILED', message: 'provider unavailable' };
    })());
    const onAskStarted = vi.fn();
    const { rerender } = render(AskPanel, {
      props: { collectionId: 'collection-1', conversationId: 'ask-1', entries: [stored], onAskStarted, onOpenSource: vi.fn() },
    });
    expect(await screen.findByLabelText('Stored question')).toBeTruthy();

    await fireEvent.input(await screen.findByLabelText('Question'), { target: { value: 'What happened?' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Ask' }));

    // The page answers the start by clearing the remembered selection and localStorage.
    expect(onAskStarted).toHaveBeenCalledTimes(1);
    await rerender({ conversationId: null });

    release();
    // An unpersisted partial answer stays visible but no previously stored row stays marked for it.
    expect(await screen.findByText('Partial answer')).toBeTruthy();
    expect((await screen.findByRole('alert')).textContent).toContain('provider unavailable');
    expect(screen.queryByLabelText('Stored question')).toBeNull();
    expect(screen.queryByRole('button', { name: /Open source S2/ })).toBeNull();
  });
});