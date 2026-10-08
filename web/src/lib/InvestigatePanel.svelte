<script lang="ts">
  import { onDestroy, onMount } from 'svelte';
  import {
    ApiError,
    cancelInvestigation,
    continueInvestigation,
    getInvestigation,
    investigateDefaultProfile,
    listLlmProfilePrices,
    readInvestigationEvents,
    startInvestigation,
    type InvestigateActivity,
    type InvestigateEvidence,
    type InvestigateEvent,
    type InvestigateMessage,
    type LlmProfilePrice,
  } from './api';
  import {
    DEFAULT_INVESTIGATION_LIMITS,
    validInvestigationLimits,
    type InvestigationLimitsInput,
  } from './investigationLimits';

  export let collectionId: string;
  /** The page owns the open conversation for this view; a row click or a new conversation moves it once. */
  export let conversationId: string | null = null;
  export let onConversationStarted: (id: string) => void = () => {};
  /** The stream closed, so the server has finished titling; the page refreshes that collection's list. */
  export let onConversationFinished: (collectionId: string) => void = () => {};
  /** A live turn started or ended; the page locks the history column until the turn's done event. */
  export let onWorkingChanged: (working: boolean) => void = () => {};
  export let onOpenSource: (evidence: InvestigateEvidence) => void | Promise<void>;
  export let profile = '';
  export let availableProfiles: LlmProfilePrice[] = [];
  export let profileStatus = 'Loading profiles…';
  /** The sidebar's per-question limits; each turn sends the snapshot this prop holds at submit. */
  export let limits: InvestigationLimitsInput = { ...DEFAULT_INVESTIGATION_LIMITS };

  let question = '';
  let prices: LlmProfilePrice[] = [];
  let messages: InvestigateMessage[] = [];
  let evidence: InvestigateEvidence[] = [];
  let activity: InvestigateActivity[] = [];
  let inputTokens = 0;
  let outputTokens = 0;
  let costUsd: number | null = null;
  let working = false;
  let loadingHistory = false;
  let error: string | null = null;
  let status: string | null = null;
  /** A nonfatal research-limit notice for the newest turn; it stays beside the final answer. */
  let limitNotice: string | null = null;
  /** The newest turn's controller, which Cancel stops; drains of older turns stay tracked separately. */
  let controller: AbortController | null = null;
  /** Every in-flight stream's controller; a destroyed panel aborts them all so no drain leaks. */
  let activeControllers = new Set<AbortController>();
  let generation = 0;
  /** Bumped for every selection change so an obsolete history load can never land late. */
  let historyGeneration = 0;
  let handledConversationId: string | null = null;
  /** An out-of-contract setting blocks the turn here as well as in the sidebar's own error state. */
  $: limitsProblem = validInvestigationLimits(limits) === null;

  async function loadPanel(): Promise<void> {
    try { if (profile === '') profile = await investigateDefaultProfile(); }
    catch { profileStatus = 'No default Investigate profile is configured.'; }
    try {
      prices = await listLlmProfilePrices();
      availableProfiles = prices;
      if (availableProfiles.length === 0) profileStatus = 'No LLM profiles are available.';
    } catch { profileStatus = 'Could not load LLM profiles.'; }
  }

  /**
   * Show the conversation the page selected. A fresh stream owns the panel while it runs, and a
   * null id means the empty compose state the `New conversation` button returns to.
   *
   * Every call claims a higher generation, so a response that lands after the selection moved on
   * is dropped and can neither replace the panel content nor rewrite the page-selected id.
   */
  async function openConversation(id: string | null): Promise<void> {
    const attempt = ++historyGeneration;
    if (working) return;
    if (id === null) {
      loadingHistory = false;
      messages = [];
      evidence = [];
      activity = [];
      inputTokens = 0;
      outputTokens = 0;
      costUsd = null;
      error = null;
      limitNotice = null;
      return;
    }
    const panelCollection = collectionId;
    loadingHistory = true;
    messages = [];
    evidence = [];
    activity = [];
    inputTokens = 0;
    outputTokens = 0;
    costUsd = null;
    error = null;
    limitNotice = null;
    try {
      const history = await getInvestigation(panelCollection, id);
      // The page owns the selection; a response for an obsolete id or collection never lands here.
      if (attempt !== historyGeneration || id !== conversationId || panelCollection !== collectionId) return;
      messages = history.messages;
      evidence = history.evidence;
      activity = history.activity;
      inputTokens = history.inputTokens;
      outputTokens = history.outputTokens;
      costUsd = history.costUsd;
    } catch (failure) {
      if (attempt === historyGeneration) error = describe(failure);
    } finally {
      if (attempt === historyGeneration) loadingHistory = false;
    }
  }

  $: if (conversationId !== handledConversationId) {
    handledConversationId = conversationId;
    void openConversation(conversationId);
  }

  async function ask(event: SubmitEvent): Promise<void> {
    event.preventDefault();
    const text = question.trim();
    if (text === '' || collectionId === '' || profile === '' || working || loadingHistory) return;
    const turnLimits = validInvestigationLimits(limits);
    if (turnLimits === null) return;
    // No in-flight history read may land over this turn's streamed messages.
    historyGeneration += 1;
    loadingHistory = false;
    const turn = ++generation;
    const streamCollection = collectionId;
    const turnProfile = profile;
    const signalController = new AbortController();
    activeControllers.add(signalController);
    controller = signalController;
    working = true;
    onWorkingChanged?.(true);
    error = null;
    limitNotice = null;
    status = 'Starting investigation…';
    messages = [...messages, { role: 'user', text }, { role: 'assistant', text: '' }];
    const answerIndex = messages.length - 1;
    question = '';
    let usage: { inputTokens: number; outputTokens: number } | null = null;
    /** A `done` or `error` event: the turn reached an outcome the reader can see. */
    let finished = false;
    const validCitationIds = new Set<string>();
    const liveActivity = new Map<string, InvestigateActivity>();
    try {
      const response = conversationId === null
        ? await startInvestigation(collectionId, text, turnProfile, signalController.signal, turnLimits)
        : await continueInvestigation(conversationId, collectionId, text, turnProfile, signalController.signal, turnLimits);
      for await (const event of readInvestigationEvents(response, signalController.signal)) {
        if (turn !== generation) return;
        if (event.type === 'started') {
          conversationId = event.id;
          onConversationStarted(event.id);
        } else if (event.type === 'delta') {
          messages[answerIndex] = { role: 'assistant', text: messages[answerIndex].text + event.text };
          messages = [...messages];
          status = 'Investigating…';
        } else if (event.type === 'limit') {
          // Nonfatal: the turn keeps running into synthesis, so the notice never sets `error`
          // and never touches the working lock.
          limitNotice = event.message;
        } else if (event.type === 'answer-start') {
          // Synthesis replaces the provisional streamed text rather than appending to it.
          messages[answerIndex] = { role: 'assistant', text: '' };
          messages = [...messages];
        } else if (event.type === 'tool') {
          if (event.resultCode !== undefined) {
            liveActivity.set(event.callId, { name: event.name, resultCode: event.resultCode, durationMs: event.durationMs ?? 0 });
            activity = [...activity, ...[...liveActivity.entries()].slice(-1).map(([, item]) => item)];
          }
        } else if (event.type === 'usage') {
          usage = { inputTokens: event.inputTokens, outputTokens: event.outputTokens };
        } else if (event.type === 'citation') {
          if (event.valid) validCitationIds.add(event.id);
        } else if (event.type === 'done') {
          finished = true;
          messages[answerIndex] = { role: 'assistant', text: event.text };
          messages = [...messages];
          evidence = mergeEvidence(evidence, (event.evidence ?? []).filter((item) => validCitationIds.has(item.id)));
          if (usage !== null) {
            inputTokens += usage.inputTokens;
            outputTokens += usage.outputTokens;
            const price = prices.find((item) => item.name === turnProfile);
            if (price !== undefined) {
              costUsd = (costUsd ?? 0) + (usage.inputTokens * price.inputPricePerMillion + usage.outputTokens * price.outputPricePerMillion) / 1_000_000;
            }
          }
          status = null;
          // The completion event is the user-visible end of the turn: a follow-up may start and the
          // selection locks release while the reader keeps draining until the title call finishes.
          working = false;
          onWorkingChanged?.(false);
        } else if (event.type === 'error') {
          finished = true;
          status = null;
          error = event.message;
        }
      }
      // A stream that ends without a done or an error event is an interruption, never a completion:
      // the turn has no answer, the controls are released, and the reader is told it can ask again.
      // Only the current turn reports this; an older stream's closure leaves the newer turn alone.
      if (turn === generation && !finished && !signalController.signal.aborted) {
        status = null;
        error = 'The investigation was interrupted before it finished. You can send another question.';
      }
    } catch (failure) {
      if (turn === generation && !signalController.signal.aborted) error = describeTurnFailure(failure);
    } finally {
      activeControllers.delete(signalController);
      if (turn === generation) {
        // A turn that ended with no answer text (refused, failed or cancelled) leaves no empty bubble; the
        // alert or status beside the form is the outcome the reader sees.
        const placeholder = messages[answerIndex];
        if (messages.length === answerIndex + 1 && placeholder?.role === 'assistant' && placeholder.text === '') {
          messages = messages.slice(0, answerIndex);
        }
        working = false;
        controller = null;
        onWorkingChanged?.(false);
      }
      // The stream closing is what makes the title exist server-side, so the history refresh the
      // page runs now shows the stored conversation with the title it was written. An older stream
      // refreshes the collection it asked in, never the panel state of a newer turn.
      onConversationFinished(streamCollection);
    }
  }

  async function cancel(): Promise<void> {
    const id = conversationId;
    status = 'Cancelling…';
    // Stop the local stream before asking the server: the server answers by ending the stream
    // without a done event, and that ending must read as this cancellation, not as an interruption.
    controller?.abort();
    if (id !== null) {
      try { await cancelInvestigation(id); }
      catch (failure) { error = describe(failure); }
    }
    status = 'Investigation cancelled.';
    working = false;
    onWorkingChanged?.(false);
  }

  function mergeEvidence(previous: InvestigateEvidence[], incoming: InvestigateEvidence[]): InvestigateEvidence[] {
    const byId = new Map(previous.map((item) => [item.id, item]));
    incoming.forEach((item) => byId.set(item.id, item));
    return [...byId.values()];
  }

  function answerParts(text: string): { text: string; evidence: InvestigateEvidence | null }[] {
    const evidenceById = new Map(evidence.map((item) => [item.id, item]));
    const parts: { text: string; evidence: InvestigateEvidence | null }[] = [];
    let cursor = 0;
    for (const match of text.matchAll(/\[S\d+\]/g)) {
      const index = match.index ?? 0;
      if (index > cursor) parts.push({ text: text.slice(cursor, index), evidence: null });
      const id = match[0].slice(1, -1);
      parts.push({ text: match[0], evidence: evidenceById.get(id) ?? null });
      cursor = index + match[0].length;
    }
    if (cursor < text.length || parts.length === 0) parts.push({ text: text.slice(cursor), evidence: null });
    return parts;
  }

  function estimatedCost(): string | null {
    if (costUsd !== null) return `$${costUsd.toFixed(4)}`;
    const price = prices.find((item) => item.name === profile);
    if (price === undefined || (inputTokens === 0 && outputTokens === 0)) return null;
    const cost = (inputTokens * price.inputPricePerMillion + outputTokens * price.outputPricePerMillion) / 1_000_000;
    return `$${cost.toFixed(4)}`;
  }

  function describe(failure: unknown): string {
    if (failure instanceof ApiError) return failure.message;
    return failure instanceof Error ? failure.message : 'Something went wrong.';
  }

  /**
   * A refused profile is the one failure the reader can fix on the spot, so the refusal names the place where
   * tool calling is measured, not only the server's sentence.
   */
  function describeTurnFailure(failure: unknown): string {
    if (failure instanceof ApiError && failure.code === 'TOOL_CALLING_UNSUPPORTED') {
      return `${failure.message}. Open Admin → LLM profiles, choose Check tool calling for this profile, then ask again.`;
    }
    return describe(failure);
  }

  onMount(loadPanel);
  onDestroy(() => activeControllers.forEach((streamController) => streamController.abort()));
</script>

<section aria-labelledby="investigate-heading">
  <h2 id="investigate-heading">Investigate</h2>
  {#if loadingHistory}<p role="status">Loading conversation…</p>{/if}
  <ol class="messages" aria-label="Conversation">
    {#each messages as message, index (`${index}-${message.role}`)}
      <li class:assistant={message.role === 'assistant'}>
        <strong>{message.role === 'user' ? 'You' : 'InfoScry'}</strong>
        {#if message.role === 'assistant'}
          <div class="answer">{#each answerParts(message.text) as part, partIndex (`${partIndex}-${part.text}`)}{#if part.evidence !== null}<button class="citation" type="button" aria-label={`Open source ${part.text.slice(1, -1)}, ${part.evidence.locatorLabel}`} onclick={() => onOpenSource(part.evidence!)}>{part.text}</button>{:else}{part.text}{/if}{/each}</div>
        {:else}<p>{message.text}</p>{/if}
      </li>
    {/each}
  </ol>
  {#if limitNotice !== null}<p class="notice" role="note">{limitNotice}</p>{/if}
  {#if activity.length > 0}
    <details>
      <summary>{activity.length} tool call{activity.length === 1 ? '' : 's'}</summary>
      <ol>{#each activity as item, index (`${index}-${item.name}`)}<li>{item.name}: {item.resultCode} ({item.durationMs} ms)</li>{/each}</ol>
    </details>
  {/if}
  {#if evidence.length > 0}
    <details>
      <summary>Sources in this conversation ({evidence.length})</summary>
      <ul>{#each evidence as item (item.id)}<li><button class="citation" type="button" onclick={() => onOpenSource(item)}> [{item.id}] {item.locatorLabel}</button></li>{/each}</ul>
    </details>
  {/if}
  {#if inputTokens + outputTokens > 0}
    <p class="meta">{inputTokens.toLocaleString()} input tokens · {outputTokens.toLocaleString()} output tokens</p>
    {#if estimatedCost() !== null}<p class="meta">Estimated cost: {estimatedCost()}</p>{/if}
  {/if}
  <form onsubmit={ask}>
    <label for="investigate-question">Investigate question</label>
    <textarea id="investigate-question" bind:value={question} rows="3" required></textarea>
    <button type="submit" disabled={working || loadingHistory || limitsProblem || profile === '' || question.trim() === ''}>{conversationId === null ? 'Investigate' : 'Send follow-up'}</button>
    {#if working}<button type="button" onclick={cancel}>Cancel</button>{/if}
  </form>
  {#if status !== null}<p role="status">{status}</p>{/if}
  {#if error !== null}<p role="alert">{error}</p>{/if}
</section>

<style>
  section { margin-top: 2rem; }
  .messages { display: grid; gap: 0.75rem; list-style: none; margin: 1rem 0; padding: 0; }
  .messages li { border-left: 2px solid #dedede; padding: 0.25rem 0 0.25rem 0.75rem; }
  .messages li.assistant { border-color: #8aa0b8; }
  .messages p, .answer { line-height: 1.55; margin: 0.35rem 0 0; white-space: pre-wrap; overflow-wrap: anywhere; }
  .citation { background: transparent; border: 0; color: #1558a6; cursor: pointer; font: inherit; padding: 0; text-decoration: underline; }
  form { display: grid; gap: 0.5rem; grid-template-columns: minmax(0, 1fr) auto auto; margin-top: 0.75rem; }
  label, textarea { grid-column: 1 / -1; }
  textarea, button { font: inherit; padding: 0.45rem; }
  .meta { color: #555; font-size: 0.9rem; }
  .notice { background: #2b261d; border-left: 3px solid #c98a10; color: #f0d49f; margin: 0.75rem 0 0; padding: 0.4rem 0.6rem; }
  [role='alert'] { color: #a4232b; }
  @media (max-width: 38rem) { form { grid-template-columns: 1fr; } }
</style>
