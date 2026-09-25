import { cleanup, fireEvent, render, screen } from '@testing-library/svelte';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import InvestigatePanel from './InvestigatePanel.svelte';
import type { InvestigateEvent } from './api';

const api = vi.hoisted(() => ({
  investigateDefaultProfile: vi.fn(async () => 'local-cheap'),
  listLlmProfilePrices: vi.fn(async () => [{ name: 'local-cheap', inputPricePerMillion: 1, outputPricePerMillion: 2 }]),
  listInvestigations: vi.fn(async () => [] as { id: string; createdAt: string }[]),
  getInvestigation: vi.fn(async () => null as unknown),
  startInvestigation: vi.fn(),
  continueInvestigation: vi.fn(),
  cancelInvestigation: vi.fn(async () => undefined),
  readInvestigationEvents: vi.fn(),
}));

vi.mock('./api', () => ({
  ApiError: class ApiError extends Error {},
  investigateDefaultProfile: api.investigateDefaultProfile,
  listLlmProfilePrices: api.listLlmProfilePrices,
  listInvestigations: api.listInvestigations,
  getInvestigation: api.getInvestigation,
  startInvestigation: api.startInvestigation,
  continueInvestigation: api.continueInvestigation,
  cancelInvestigation: api.cancelInvestigation,
  readInvestigationEvents: api.readInvestigationEvents,
}));

const events = (...items: InvestigateEvent[]) => async function* () {
  for (const item of items) yield item;
};

describe('Investigate panel', () => {
  beforeEach(() => {
    vi.resetAllMocks();
    window.localStorage.clear();
    api.investigateDefaultProfile.mockResolvedValue('local-cheap');
    api.listLlmProfilePrices.mockResolvedValue([{ name: 'local-cheap', inputPricePerMillion: 1, outputPricePerMillion: 2 }]);
    api.listInvestigations.mockResolvedValue([]);
    api.getInvestigation.mockResolvedValue(null);
  });

  afterEach(cleanup);

  it('creates once, then continues the same persisted conversation without replaying old deltas', async () => {
    api.startInvestigation.mockResolvedValue(new Response());
    api.continueInvestigation.mockResolvedValue(new Response());
    api.readInvestigationEvents
      .mockReturnValueOnce(events(
        { type: 'started', id: 'conversation-1' },
        { type: 'delta', text: 'First answer [S1]' },
        { type: 'usage', inputTokens: 10, outputTokens: 4 },
        { type: 'citation', id: 'S1', valid: true },
        { type: 'done', text: 'First answer [S1]', evidence: [{ id: 'S1', documentId: 'doc-1', unitId: 'unit-1', locator: {}, locatorLabel: 'Page 4' }] },
      )())
      .mockReturnValueOnce(events(
        { type: 'started', id: 'conversation-1' },
        { type: 'delta', text: 'Follow-up answer' },
        { type: 'done', text: 'Follow-up answer', evidence: [] },
      )());

    render(InvestigatePanel, { props: { collectionId: 'collection-1', onOpenSource: vi.fn() } });
    await fireEvent.input(await screen.findByLabelText('Investigate question'), { target: { value: 'What happened?' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Investigate' }));

    expect(await screen.findByRole('button', { name: 'Open source S1, Page 4' })).toBeTruthy();
    expect(api.startInvestigation).toHaveBeenCalledTimes(1);
    expect(api.startInvestigation).toHaveBeenCalledWith('collection-1', 'What happened?', 'local-cheap', expect.any(AbortSignal));

    await fireEvent.input(screen.getByLabelText('Investigate question'), { target: { value: 'When?' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Send follow-up' }));

    expect(await screen.findByText('Follow-up answer')).toBeTruthy();
    expect(api.continueInvestigation).toHaveBeenCalledTimes(1);
    expect(api.continueInvestigation).toHaveBeenCalledWith('conversation-1', 'collection-1', 'When?', 'local-cheap', expect.any(AbortSignal));
    expect(screen.getByText(/Estimated cost:/).textContent).toContain('$0.0000');
  });

  it('restores persisted conversation history and renders its source as a link action', async () => {
    api.listInvestigations.mockResolvedValue([{ id: 'saved-1', createdAt: '2026-09-23T10:00:00Z' }]);
    api.getInvestigation.mockResolvedValue({
      id: 'saved-1',
      messages: [
        { role: 'user', text: 'Who signed it?' },
        { role: 'assistant', text: 'Mira signed it [S2]' },
      ],
      evidence: [{ id: 'S2', documentId: 'doc-2', unitId: 'unit-2', locator: {}, locatorLabel: 'Page 8' }],
      inputTokens: 20,
      outputTokens: 9,
      costUsd: 0.0001,
      activity: [{ name: 'search_collection', resultCode: 'SUCCESS', durationMs: 12 }],
    });
    const onOpenSource = vi.fn();

    render(InvestigatePanel, { props: { collectionId: 'collection-1', onOpenSource } });

    expect(await screen.findByText('Who signed it?')).toBeTruthy();
    expect(screen.getByRole('button', { name: /\[S2\] Page 8/ })).toBeTruthy();
    await fireEvent.click(screen.getByRole('button', { name: /\[S2\] Page 8/ }));
    expect(onOpenSource).toHaveBeenCalledWith(expect.objectContaining({ id: 'S2', unitId: 'unit-2' }));
    expect(screen.getByText(/1 tool call/)).toBeTruthy();
    expect(screen.getByText(/Estimated cost/)).toBeTruthy();
  });

  it('keeps provider errors visible and does not automatically replay a failed stream', async () => {
    api.startInvestigation.mockResolvedValue(new Response());
    api.readInvestigationEvents.mockReturnValue(events(
      { type: 'started', id: 'conversation-2' },
      { type: 'error', code: 'PROVIDER_TIMEOUT', message: 'The model timed out.' },
    )());
    render(InvestigatePanel, { props: { collectionId: 'collection-1', onOpenSource: vi.fn() } });
    await fireEvent.input(await screen.findByLabelText('Investigate question'), { target: { value: 'Explain this' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Investigate' }));
    expect((await screen.findByRole('alert')).textContent).toContain('The model timed out.');
    expect(api.startInvestigation).toHaveBeenCalledTimes(1);
  });
});
