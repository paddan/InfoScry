import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/svelte';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import InvestigatePanel from './InvestigatePanel.svelte';
import { DEFAULT_INVESTIGATION_LIMITS } from './investigationLimits';
import type { InvestigateEvent } from './api';

/**
 * The panel takes the open conversation from the page: the page owns the id, the panel loads and
 * shows its body, streams follow-ups into it, and the `New conversation` id (null) resets it.
 */
const api = vi.hoisted(() => ({
  investigateDefaultProfile: vi.fn(async () => 'local-cheap'),
  listLlmProfilePrices: vi.fn(async () => [{ name: 'local-cheap', inputPricePerMillion: 1, outputPricePerMillion: 2 }]),
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
  getInvestigation: api.getInvestigation,
  startInvestigation: api.startInvestigation,
  continueInvestigation: api.continueInvestigation,
  cancelInvestigation: api.cancelInvestigation,
  readInvestigationEvents: api.readInvestigationEvents,
}));

const events = (...items: InvestigateEvent[]) => async function* () {
  for (const item of items) yield item;
};

const props = {
  collectionId: 'collection-1',
  conversationId: null as string | null,
  onConversationStarted: vi.fn(),
  onConversationFinished: vi.fn(),
  onOpenSource: vi.fn(),
};

describe('Investigate panel', () => {
  beforeEach(() => {
    vi.resetAllMocks();
    api.investigateDefaultProfile.mockResolvedValue('local-cheap');
    api.listLlmProfilePrices.mockResolvedValue([{ name: 'local-cheap', inputPricePerMillion: 1, outputPricePerMillion: 2 }]);
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

    const onConversationStarted = vi.fn();
    const onConversationFinished = vi.fn();
    render(InvestigatePanel, {
      props: { ...props, conversationId: null, onConversationStarted, onConversationFinished },
    });
    await fireEvent.input(await screen.findByLabelText('Investigate question'), { target: { value: 'What happened?' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Investigate' }));

    expect(await screen.findByRole('button', { name: 'Open source S1, Page 4' })).toBeTruthy();
    expect(api.startInvestigation).toHaveBeenCalledTimes(1);
    expect(api.startInvestigation).toHaveBeenCalledWith('collection-1', 'What happened?', 'local-cheap', expect.any(AbortSignal), DEFAULT_INVESTIGATION_LIMITS);
    expect(onConversationStarted).toHaveBeenCalledWith('conversation-1');
    expect(screen.getByRole('button', { name: 'Send follow-up' })).toBeTruthy();

    await fireEvent.input(screen.getByLabelText('Investigate question'), { target: { value: 'When?' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Send follow-up' }));

    expect(await screen.findByText('Follow-up answer')).toBeTruthy();
    expect(api.continueInvestigation).toHaveBeenCalledTimes(1);
    expect(api.continueInvestigation).toHaveBeenCalledWith('conversation-1', 'collection-1', 'When?', 'local-cheap', expect.any(AbortSignal), DEFAULT_INVESTIGATION_LIMITS);
    // The history refresh happens once each stream closes, after the server wrote the title.
    await waitFor(() => expect(onConversationFinished).toHaveBeenCalledTimes(2));
    expect(screen.getByText(/Estimated cost:/).textContent).toContain('$0.0000');
  });

  it('finishes a turn when a done event omits evidence', async () => {
    api.startInvestigation.mockResolvedValue(new Response());
    api.readInvestigationEvents.mockReturnValue(events(
      { type: 'started', id: 'conversation-no-evidence' },
      { type: 'delta', text: 'Answer without sources' },
      { type: 'done', text: 'Answer without sources' },
    )());
    const onConversationFinished = vi.fn();
    render(InvestigatePanel, { props: { ...props, conversationId: null, onConversationFinished } });
    await fireEvent.input(await screen.findByLabelText('Investigate question'), { target: { value: 'Question?' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Investigate' }));

    expect(await screen.findByText('Answer without sources')).toBeTruthy();
    await waitFor(() => expect(onConversationFinished).toHaveBeenCalledTimes(1));
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('replaces provisional text with the limited final answer while keeping the nonfatal notice', async () => {
    let release!: () => void;
    const gate = new Promise<void>((resolve) => { release = resolve; });
    api.startInvestigation.mockResolvedValue(new Response());
    api.readInvestigationEvents.mockReturnValue((async function* () {
      yield { type: 'started', id: 'conversation-limited' };
      yield { type: 'delta', text: 'Provisional half-sentence' };
      yield { type: 'limit', code: 'MAX_ROUNDS', message: 'Tool round limit reached; preparing an answer from collected sources.' };
      yield { type: 'answer-start' };
      await gate;
      yield { type: 'delta', text: 'Final synthesized answer [S1]' };
      yield { type: 'citation', id: 'S1', valid: true };
      yield { type: 'done', text: 'Final synthesized answer [S1]', evidence: [{ id: 'S1', documentId: 'doc-1', unitId: 'unit-1', locator: {}, locatorLabel: 'Page 4' }] };
    })());
    const onWorkingChanged = vi.fn();
    const onConversationFinished = vi.fn();
    render(InvestigatePanel, {
      props: { ...props, conversationId: null, onWorkingChanged, onConversationFinished },
    });
    await fireEvent.input(await screen.findByLabelText('Investigate question'), { target: { value: 'Summarize the file' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Investigate' }));

    // answer-start cleared the provisional text before synthesis streamed, so the final answer
    // replaces it instead of appending to it. The turn stays live, and the notice is nonfatal.
    expect(await screen.findByText(/Tool round limit reached/)).toBeTruthy();
    await waitFor(() => expect(screen.queryByText(/Provisional half-sentence/)).toBeNull());
    expect(screen.getByRole('button', { name: 'Cancel' })).toBeTruthy();
    expect(screen.queryByRole('alert')).toBeNull();

    release();
    expect(await screen.findByText('Final synthesized answer')).toBeTruthy();
    // The notice survives Done, beside the final answer and its citation.
    expect(screen.getByText(/Tool round limit reached/)).toBeTruthy();
    expect(screen.queryByText(/Provisional half-sentence/)).toBeNull();
    expect(screen.queryByRole('alert')).toBeNull();
    expect(screen.getByRole('button', { name: 'Open source S1, Page 4' })).toBeTruthy();
    expect(onWorkingChanged).toHaveBeenCalledWith(false);
    await waitFor(() => expect(onConversationFinished).toHaveBeenCalledTimes(1));
  });

  it('shows a refused call as limit-reached activity rather than a successful execution', async () => {
    api.startInvestigation.mockResolvedValue(new Response());
    api.readInvestigationEvents.mockReturnValue(events(
      { type: 'started', id: 'conversation-call-limited' },
      { type: 'tool', callId: 'refused-1', name: 'get_document_metadata', arguments: '{ }' },
      { type: 'tool', callId: 'refused-1', name: 'get_document_metadata', resultCode: 'LIMIT_REACHED', durationMs: 0 },
      { type: 'limit', code: 'MAX_TOOL_CALLS', message: 'Tool call limit reached; preparing an answer from collected sources.' },
      { type: 'answer-start' },
      { type: 'delta', text: 'No more calls were needed.' },
      { type: 'done', text: 'No more calls were needed.' },
    )());
    render(InvestigatePanel, { props: { ...props, conversationId: null } });
    await fireEvent.input(await screen.findByLabelText('Investigate question'), { target: { value: 'Question?' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Investigate' }));

    expect(await screen.findByText(/Tool call limit reached/)).toBeTruthy();
    expect(await screen.findByText('No more calls were needed.')).toBeTruthy();
    await fireEvent.click(screen.getByText('1 tool call'));
    expect(screen.getByText('get_document_metadata: LIMIT_REACHED (0 ms)')).toBeTruthy();
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('renders the conversation the page opened, with its evidence, activity, tokens and cost', async () => {
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

    render(InvestigatePanel, { props: { ...props, conversationId: 'saved-1', onOpenSource } });

    expect(await screen.findByText('Who signed it?')).toBeTruthy();
    expect(api.getInvestigation).toHaveBeenCalledWith('collection-1', 'saved-1');
    const citation = screen.getByRole('button', { name: /\[S2\] Page 8/ });
    await fireEvent.click(citation);
    expect(onOpenSource).toHaveBeenCalledWith(expect.objectContaining({ id: 'S2', unitId: 'unit-2' }));
    expect(screen.getByText(/1 tool call/)).toBeTruthy();
    expect(screen.getByText(/20 input tokens/)).toBeTruthy();
    expect(screen.getByText(/Estimated cost/).textContent).toContain('$0.0001');
  });

  it('sends the reader\u2019s limits as a snapshot with the question and its follow-up', async () => {
    api.startInvestigation.mockResolvedValue(new Response());
    api.continueInvestigation.mockResolvedValue(new Response());
    api.readInvestigationEvents
      .mockReturnValueOnce(events(
        { type: 'started', id: 'conversation-limits' },
        { type: 'done', text: 'First answer', evidence: [] },
      )())
      .mockReturnValueOnce(events(
        { type: 'started', id: 'conversation-limits' },
        { type: 'done', text: 'Follow-up answer', evidence: [] },
      )());
    const limits = { maxToolRounds: 4, maxToolCalls: 7, maxTurnSeconds: 90 };
    render(InvestigatePanel, { props: { ...props, conversationId: null, limits } });

    await fireEvent.input(await screen.findByLabelText('Investigate question'), { target: { value: 'What happened?' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Investigate' }));
    await screen.findByText('First answer');
    expect(api.startInvestigation).toHaveBeenCalledWith('collection-1', 'What happened?', 'local-cheap', expect.any(AbortSignal), limits);

    await fireEvent.input(screen.getByLabelText('Investigate question'), { target: { value: 'When?' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Send follow-up' }));
    await screen.findByText('Follow-up answer');
    expect(api.continueInvestigation).toHaveBeenCalledWith('conversation-limits', 'collection-1', 'When?', 'local-cheap', expect.any(AbortSignal), limits);
  });

  it('sends no request while a limit value is out of contract', async () => {
    const { rerender } = render(InvestigatePanel, {
      props: { ...props, conversationId: null, limits: { maxToolRounds: 51, maxToolCalls: 20, maxTurnSeconds: 600 } },
    });
    await fireEvent.input(await screen.findByLabelText('Investigate question'), { target: { value: 'What happened?' } });
    const submit = screen.getByRole('button', { name: 'Investigate' }) as HTMLButtonElement;
    expect(submit.disabled).toBe(true);
    await fireEvent.click(submit);
    expect(api.startInvestigation).not.toHaveBeenCalled();

    // An empty number input binds as undefined, exactly like a cleared field in the sidebar.
    await rerender({ limits: { maxToolRounds: undefined, maxToolCalls: 20, maxTurnSeconds: 600 } });
    expect((screen.getByRole('button', { name: 'Investigate' }) as HTMLButtonElement).disabled).toBe(true);

    await rerender({ limits: { maxToolRounds: 10, maxToolCalls: 20, maxTurnSeconds: 600 } });
    expect((screen.getByRole('button', { name: 'Investigate' }) as HTMLButtonElement).disabled).toBe(false);
  });

  it('loads a conversation when the page moves the open id to a different conversation', async () => {
    api.getInvestigation.mockResolvedValue({
      id: 'saved-2',
      messages: [{ role: 'user', text: 'What changed?' }, { role: 'assistant', text: 'The report was revised.' }],
      evidence: [],
      inputTokens: 5,
      outputTokens: 3,
      costUsd: 0,
      activity: [],
    });

    const { rerender } = render(InvestigatePanel, { props: { ...props, conversationId: null } });
    await rerender({ conversationId: 'saved-2' });

    expect(await screen.findByText('The report was revised.')).toBeTruthy();
    expect(api.getInvestigation).toHaveBeenCalledWith('collection-1', 'saved-2');
  });

  it('clears the panel to its empty compose state when the page clears the open id', async () => {
    api.getInvestigation.mockResolvedValue({
      id: 'saved-3',
      messages: [{ role: 'user', text: 'Who signed it?' }, { role: 'assistant', text: 'Mira signed it.' }],
      evidence: [],
      inputTokens: 10,
      outputTokens: 4,
      costUsd: 0,
      activity: [],
    });

    const { rerender } = render(InvestigatePanel, { props: { ...props, conversationId: null } });
    await rerender({ conversationId: 'saved-3' });
    expect(await screen.findByText('Mira signed it.')).toBeTruthy();

    await rerender({ conversationId: null });

    expect(screen.queryByText('Mira signed it.')).toBeNull();
    expect(screen.getByRole('button', { name: 'Investigate' })).toBeTruthy();
    expect((screen.getByLabelText('Investigate question') as HTMLTextAreaElement).value).toBe('');
  });

  it('keeps provider errors visible and does not automatically replay a failed stream', async () => {
    api.startInvestigation.mockResolvedValue(new Response());
    api.readInvestigationEvents.mockReturnValue(events(
      { type: 'started', id: 'conversation-2' },
      { type: 'error', code: 'PROVIDER_TIMEOUT', message: 'The model timed out.' },
    )());
    render(InvestigatePanel, { props: { ...props, conversationId: null } });
    await fireEvent.input(await screen.findByLabelText('Investigate question'), { target: { value: 'Explain this' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Investigate' }));
    expect((await screen.findByRole('alert')).textContent).toContain('The model timed out.');
    expect(api.startInvestigation).toHaveBeenCalledTimes(1);
  });

  it('ignores a stale getInvestigation response after the page moves the selection on', async () => {
    let resolveFirst!: (value: unknown) => void;
    api.getInvestigation
      .mockImplementationOnce(() => new Promise((resolve) => { resolveFirst = resolve; }))
      .mockResolvedValueOnce({
        id: 'saved-2',
        messages: [{ role: 'user', text: 'What changed?' }, { role: 'assistant', text: 'The report was revised.' }],
        evidence: [],
        inputTokens: 5,
        outputTokens: 3,
        costUsd: 0,
        activity: [],
      });

    const { rerender } = render(InvestigatePanel, { props: { ...props, conversationId: 'saved-1' } });
    expect(api.getInvestigation).toHaveBeenCalledWith('collection-1', 'saved-1');
    await rerender({ conversationId: 'saved-2' });
    expect(await screen.findByText('The report was revised.')).toBeTruthy();

    resolveFirst({
      id: 'saved-1',
      messages: [{ role: 'user', text: 'Who signed it?' }, { role: 'assistant', text: 'Mira signed it.' }],
      evidence: [],
      inputTokens: 0,
      outputTokens: 0,
      costUsd: 0,
      activity: [],
    });
    await new Promise<void>((resolve) => setTimeout(resolve, 0));

    // The obsolete response must neither replace the panel content nor the page-selected id.
    expect(screen.queryByText('Mira signed it.')).toBeNull();
    expect(screen.getByText('The report was revised.')).toBeTruthy();
    expect(api.getInvestigation).toHaveBeenCalledTimes(2);
  });

  it('blocks a follow-up until the selected history is loaded and clears an obsolete load on New', async () => {
    let resolveHistory!: (value: unknown) => void;
    api.getInvestigation.mockImplementationOnce(() => new Promise((resolve) => { resolveHistory = resolve; }));

    const { rerender } = render(InvestigatePanel, { props: { ...props, conversationId: 'saved-1' } });
    await screen.findByText('Loading conversation…');
    await fireEvent.input(screen.getByLabelText('Investigate question'), { target: { value: 'And then?' } });
    const followUp = screen.getByRole('button', { name: 'Send follow-up' }) as HTMLButtonElement;
    expect(followUp.disabled).toBe(true);

    await rerender({ conversationId: null });
    await waitFor(() => expect(screen.queryByText('Loading conversation…')).toBeNull());
    expect(screen.queryByText('Mira signed it.')).toBeNull();
    await waitFor(() => expect((screen.getByRole('button', { name: 'Investigate' }) as HTMLButtonElement).disabled).toBe(false));

    resolveHistory({
      id: 'saved-1',
      messages: [{ role: 'user', text: 'Who signed it?' }, { role: 'assistant', text: 'Mira signed it.' }],
      evidence: [], inputTokens: 1, outputTokens: 1, costUsd: 0, activity: [],
    });
    await new Promise<void>((resolve) => setTimeout(resolve, 0));

    expect(screen.queryByText('Mira signed it.')).toBeNull();
    expect(screen.queryByText('Loading conversation…')).toBeNull();
    expect(api.continueInvestigation).not.toHaveBeenCalled();
  });

  it('unlocks the panel at done while the stream is still draining', async () => {
    let release!: () => void;
    const gate = new Promise<void>((resolve) => { release = resolve; });
    api.startInvestigation.mockResolvedValue(new Response());
    // The title call keeps the stream open after done, exactly as the server behaves today.
    api.readInvestigationEvents.mockReturnValue((async function* () {
      yield { type: 'started', id: 'conversation-1' };
      yield { type: 'done', text: 'First answer', evidence: [] };
      await gate;
    })());
    const onWorkingChanged = vi.fn();
    const onConversationFinished = vi.fn();
    render(InvestigatePanel, {
      props: { ...props, conversationId: null, onWorkingChanged, onConversationFinished },
    });
    await fireEvent.input(await screen.findByLabelText('Investigate question'), { target: { value: 'What happened?' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Investigate' }));

    // The completion event is the user-visible end of the turn: the panel unlocks for a follow-up
    // once a question is typed, while the stream is still open, and the history refresh waits for
    // the stream to close.
    await fireEvent.input(screen.getByLabelText('Investigate question'), { target: { value: 'And then?' } });
    const submit = () => screen.getByRole('button', { name: 'Send follow-up' });
    await waitFor(() => expect(submit().getAttribute('disabled')).toBeNull());
    expect(onWorkingChanged).toHaveBeenCalledWith(true);
    expect(onWorkingChanged).toHaveBeenCalledWith(false);
    expect(onConversationFinished).not.toHaveBeenCalled();

    release();
    await waitFor(() => expect(onConversationFinished).toHaveBeenCalledTimes(1));
  });
});
