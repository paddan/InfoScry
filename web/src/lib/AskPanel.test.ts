import { cleanup, fireEvent, render, screen } from '@testing-library/svelte';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import AskPanel from './AskPanel.svelte';
import type { AskEvent } from './api';

/**
 * The Ask panel shows the answers the server kept, not only the one it just produced.
 *
 * A stored answer is replayed from the conversation the server already has: its question, its text and
 * the sources it cited, with a citation acting exactly like the one on a live answer.
 */
const api = vi.hoisted(() => ({
  askDefaultProfile: vi.fn(async () => 'local-cheap'),
  listLlmProfilePrices: vi.fn(async () => [{ name: 'local-cheap', inputPricePerMillion: 1, outputPricePerMillion: 2 }]),
  listAsks: vi.fn(async () => [] as unknown[]),
  startAsk: vi.fn(),
  readAskEvents: vi.fn(),
}));

vi.mock('./api', () => ({
  ApiError: class ApiError extends Error {},
  askDefaultProfile: api.askDefaultProfile,
  listLlmProfilePrices: api.listLlmProfilePrices,
  listAsks: api.listAsks,
  startAsk: api.startAsk,
  readAskEvents: api.readAskEvents,
}));

const events = (...items: AskEvent[]) => async function* () {
  for (const item of items) yield item;
};

describe('Ask panel', () => {
  beforeEach(() => {
    vi.resetAllMocks();
    api.askDefaultProfile.mockResolvedValue('local-cheap');
    api.listLlmProfilePrices.mockResolvedValue([{ name: 'local-cheap', inputPricePerMillion: 1, outputPricePerMillion: 2 }]);
    api.listAsks.mockResolvedValue([]);
  });

  afterEach(cleanup);

  it('offers the stored questions and replays the stored answer with its source', async () => {
    api.listAsks.mockResolvedValue([
      {
        id: 'ask-1',
        createdAt: '2026-09-26T15:21:42Z',
        question: 'Who signed it?',
        answer: 'Mira signed it [S2].',
        evidence: [{ id: 'S2', documentId: 'doc-2', unitId: 'unit-2', locator: {}, locatorLabel: 'Page 8' }],
        inputTokens: 20,
        outputTokens: 9,
        costUsd: 0.0001,
      },
    ]);
    const onOpenSource = vi.fn();

    render(AskPanel, { props: { collectionId: 'collection-1', onOpenSource } });

    const history = await screen.findByLabelText('Question history');
    expect(screen.getByRole('option', { name: 'Who signed it?' })).toBeTruthy();

    await fireEvent.change(history, { target: { value: 'ask-1' } });

    expect(await screen.findByText(/Mira signed it/)).toBeTruthy();
    expect(screen.getByLabelText('Stored question').textContent).toBe('Who signed it?');
    const citation = screen.getByRole('button', { name: 'Open source S2, Page 8' });
    await fireEvent.click(citation);
    expect(onOpenSource).toHaveBeenCalledWith(expect.objectContaining({ id: 'S2', unitId: 'unit-2' }));
    expect(screen.getByText(/Estimated cost/).textContent).toContain('$0.0001');
  });

  it('shows a fresh answer in the history once the server stored it', async () => {
    api.startAsk.mockResolvedValue(new Response());
    api.readAskEvents.mockReturnValue(events(
      { type: 'delta', text: 'Mira signed it [S1].' },
      { type: 'done', text: 'Mira signed it [S1].', evidence: [{ id: 'S1', documentId: 'doc-1', unitId: 'unit-1', locator: {}, locatorLabel: 'Page 4' }] },
    )());
    api.listAsks
      .mockResolvedValueOnce([])
      .mockResolvedValueOnce([
        {
          id: 'ask-2',
          createdAt: '2026-09-26T15:27:36Z',
          question: 'Who signed it?',
          answer: 'Mira signed it [S1].',
          evidence: [{ id: 'S1', documentId: 'doc-1', unitId: 'unit-1', locator: {}, locatorLabel: 'Page 4' }],
          inputTokens: 7,
          outputTokens: 3,
          costUsd: 0,
        },
      ]);

    render(AskPanel, { props: { collectionId: 'collection-1', onOpenSource: vi.fn() } });
    await fireEvent.input(await screen.findByLabelText('Question'), { target: { value: 'Who signed it?' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Ask' }));

    expect(await screen.findByRole('button', { name: 'Open source S1, Page 4' })).toBeTruthy();
    expect(api.listAsks).toHaveBeenCalledTimes(2);
    expect(await screen.findByLabelText('Question history')).toBeTruthy();
    expect(screen.queryAllByRole('option', { name: 'Who signed it?' }).length).toBe(1);
  });
});
