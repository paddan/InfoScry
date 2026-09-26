<script lang="ts">
  import { onDestroy, onMount } from 'svelte';
  import {
    ApiError,
    askDefaultProfile,
    listLlmProfilePrices,
    readAskEvents,
    startAsk,
    type AskEvidence,
    type AskEvent,
    type AskHistoryEntry,
    type LlmProfilePrice,
  } from './api';

  export let collectionId: string;
  export let onOpenSource: (evidence: AskEvidence) => void | Promise<void>;
  export let askProfile = '';
  export let availableProfiles: LlmProfilePrice[] = [];
  export let profileStatus = 'Loading profiles…';
  /** The page owns the open conversation for this view; a row click or a new conversation moves it once. */
  export let conversationId: string | null = null;
  /** The stored answers the page loaded; the panel replays the selected row from this same list. */
  export let entries: AskHistoryEntry[] = [];
  /** The completion event named the conversation the server just stored; the page marks its row. */
  export let onAnswerStored: (id: string) => void = () => {};
  /** A new Ask began, so the page clears the old selection before an answer may never be stored. */
  export let onAskStarted: () => void = () => {};
  /** The stream closed, so the server has finished titling; the page refreshes that collection's list. */
  export let onAnswerFinished: (collectionId: string) => void = () => {};

  let question = '';
  let asking = false;
  let answerText = '';
  let requestProfile = '';
  let askError: string | null = null;
  let askStatus: string | null = null;
  let profilePrices: LlmProfilePrice[] = [];
  let usage: { inputTokens: number; outputTokens: number } | null = null;
  let answerEvidence: AskEvidence[] = [];
  let renderedAnswerParts: { text: string; evidence: AskEvidence | null }[] = [];
  let askGeneration = 0;
  /** Every in-flight stream's controller; a destroyed panel aborts them all so no drain leaks. */
  let activeControllers = new Set<AbortController>();
  let storedQuestion = '';
  let costUsd: number | null = null;
  /** A stream produced an answer after the page's selection stopped matching the view. */
  let liveAnswerVisible = false;
  /** The selection the current stream started from; a fresh answer must not be yanked back to it. */
  let streamStartedFrom: string | null = null;
  let previousConversationId: string | null = null;

  async function loadConfiguration(): Promise<void> {
    try {
      if (askProfile === '') askProfile = await askDefaultProfile();
    } catch {
      profileStatus = 'No default Ask profile is configured.';
    }
    try {
      profilePrices = await listLlmProfilePrices();
      availableProfiles = profilePrices;
      if (availableProfiles.length === 0) profileStatus = 'No LLM profiles are available.';
    } catch {
      // Pricing is optional; Ask remains usable when profile listing is unavailable.
      profileStatus = 'Could not load LLM profiles.';
    }
  }

  /** Show one stored answer, exactly as the history column selected it. */
  function showStored(stored: AskHistoryEntry): void {
    liveAnswerVisible = false;
    storedQuestion = stored.question;
    answerText = stored.answer;
    answerEvidence = stored.evidence;
    renderedAnswerParts = splitAnswer(stored.answer, stored.evidence);
    usage = { inputTokens: stored.inputTokens, outputTokens: stored.outputTokens };
    costUsd = stored.costUsd;
  }

  /** Return the panel to the compose state: no stored question, no answer, no usage. */
  function clearView(): void {
    liveAnswerVisible = false;
    storedQuestion = '';
    answerText = '';
    answerEvidence = [];
    renderedAnswerParts = [];
    usage = null;
    costUsd = null;
  }

  /**
   * Follow the page's selection. A live stream owns the view while it runs; once it ends, a row the
   * refreshed list carries takes over, `New conversation` (null) clears the panel, and an answer the
   * stream just produced stays visible until the page's selection or the list catches up with it.
   */
  $: {
    if (!asking) {
      const stored = conversationId === null ? undefined : entries.find((item) => item.id === conversationId);
      if (stored !== undefined) {
        // Do not yank a freshly streamed answer back to the selection the ask started from; only a
        // selection that moved (to the stored row the completion event named) replaces the live view.
        if (!(liveAnswerVisible && stored.id === streamStartedFrom)) showStored(stored);
      } else if (conversationId === null) {
        // Only a transition to null (the New conversation button) clears the view; an id that stays
        // null leaves whatever is on screen, including a partial answer from a failed stream. The
        // page also moves the selection to null when a new Ask begins, and that move must not wipe
        // the live partial answer either, so the previous id is tracked through the asking phase.
        if (conversationId !== previousConversationId) clearView();
      } else if (!liveAnswerVisible) {
        // A row the refreshed list does not hold yet: show the compose state, but keep a
        // just-streamed answer until the refreshed list carries the row.
        clearView();
      }
    }
    previousConversationId = conversationId;
  }

  async function ask(event: SubmitEvent): Promise<void> {
    event.preventDefault();
    const text = question.trim();
    if (text === '' || collectionId === '' || askProfile === '' || asking) return;
    const generation = ++askGeneration;
    const streamCollection = collectionId;
    requestProfile = askProfile;
    streamStartedFrom = conversationId;
    const controller = new AbortController();
    activeControllers.add(controller);
    asking = true;
    liveAnswerVisible = false;
    storedQuestion = '';
    costUsd = null;
    answerText = '';
    answerEvidence = [];
    renderedAnswerParts = [];
    usage = null;
    askError = null;
    askStatus = 'Preparing answer…';
    // The old selection must not stay marked under an answer this stream may never store.
    onAskStarted();
    try {
      const response = await startAsk(collectionId, text, askProfile, controller.signal);
      for await (const event of readAskEvents(response, controller.signal)) {
        if (generation !== askGeneration) return;
        applyAskEvent(event);
      }
      if (askStatus === 'Preparing answer…' || askStatus === 'Generating answer…') {
        askStatus = 'The Ask stream ended before a final answer arrived.';
      }
    } catch (failure) {
      if (generation === askGeneration) askError = describe(failure);
    } finally {
      activeControllers.delete(controller);
      if (generation === askGeneration) {
        asking = false;
      }
      // The stream closing is what makes the title exist server-side, so the history refresh the
      // page runs now shows the just-stored row with the title it was written. An older stream
      // refreshes the collection it asked in, never the panel state of a newer turn.
      onAnswerFinished(streamCollection);
    }
  }

  function applyAskEvent(event: AskEvent): void {
    switch (event.type) {
      case 'delta':
        liveAnswerVisible = true;
        answerText += event.text;
        renderedAnswerParts = splitAnswer(answerText, answerEvidence);
        askStatus = 'Generating answer…';
        break;
      case 'usage':
        usage = { inputTokens: event.inputTokens, outputTokens: event.outputTokens };
        break;
      case 'citation':
        break;
      case 'done':
        liveAnswerVisible = true;
        answerText = event.text;
        answerEvidence = event.evidence;
        renderedAnswerParts = splitAnswer(answerText, answerEvidence);
        askStatus = null;
        if (event.conversationId !== undefined) onAnswerStored(event.conversationId);
        // The completion event is the user-visible end of the turn: the Ask action may start again
        // while the reader keeps draining the stream until the server's title call finishes.
        asking = false;
        break;
      case 'error':
        askStatus = null;
        askError = event.message;
        break;
    }
  }

  function splitAnswer(text: string, evidence: AskEvidence[]): { text: string; evidence: AskEvidence | null }[] {
    const evidenceById = new Map(evidence.map((item) => [item.id, item]));
    const parts: { text: string; evidence: AskEvidence | null }[] = [];
    const matcher = /\[S\d+\]/g;
    let cursor = 0;
    for (const match of text.matchAll(matcher)) {
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
    if (usage === null) return null;
    const profile = profilePrices.find((item) => item.name === requestProfile);
    if (profile === undefined) return null;
    const cost = (usage.inputTokens * profile.inputPricePerMillion + usage.outputTokens * profile.outputPricePerMillion) / 1_000_000;
    return `$${cost.toFixed(4)}`;
  }

  function describe(failure: unknown): string {
    if (failure instanceof ApiError) return failure.message;
    return failure instanceof Error ? failure.message : 'Something went wrong.';
  }

  onMount(loadConfiguration);
  onDestroy(() => activeControllers.forEach((controller) => controller.abort()));
</script>

<section aria-labelledby="ask-heading">
  <h2 id="ask-heading">Ask</h2>
  {#if storedQuestion !== ''}<p class="stored-question" aria-label="Stored question">{storedQuestion}</p>{/if}
  <form class="ask-form" onsubmit={ask}>
    <label for="question">Question</label>
    <textarea id="question" name="question" bind:value={question} rows="3" required></textarea>
    <button type="submit" disabled={asking || askProfile === '' || question.trim() === ''}>
      {asking ? 'Asking…' : 'Ask'}
    </button>
  </form>
  {#if askStatus !== null}<p role="status">{askStatus}</p>{/if}
  {#if askError !== null}<p role="alert">{askError}</p>{/if}
  {#if answerText !== ''}
    <div class="answer" aria-label="Answer">
      {#each renderedAnswerParts as part, index (`${index}-${part.text}`)}
        {#if part.evidence !== null}
          <button class="citation" type="button" onclick={() => onOpenSource(part.evidence!)} aria-label={`Open source ${part.text.slice(1, -1)}, ${part.evidence.locatorLabel}`}>{part.text}</button>
        {:else}{part.text}{/if}
      {/each}
    </div>
  {/if}
  {#if usage !== null}
    <p class="meta" aria-label="Token usage">{usage.inputTokens.toLocaleString()} input tokens · {usage.outputTokens.toLocaleString()} output tokens</p>
    {#if estimatedCost() !== null}<p class="meta">Estimated cost: {estimatedCost()}</p>{/if}
  {/if}
</section>

<style>
  section { margin-top: 2rem; }
  .ask-form {
    display: grid;
    gap: 0.5rem;
    grid-template-columns: minmax(0, 1fr) auto;
  }
  .ask-form label,
  .ask-form textarea { grid-column: 1 / -1; }
  textarea,
  button { font: inherit; padding: 0.45rem; }
  textarea { padding: 0.5rem; resize: vertical; }
  .stored-question { font-weight: 600; margin: 1rem 0 0; }
  .answer {
    line-height: 1.6;
    margin-top: 1rem;
    white-space: pre-wrap;
    overflow-wrap: anywhere;
  }
  .citation {
    background: transparent;
    border: 0;
    color: #1558a6;
    cursor: pointer;
    font: inherit;
    padding: 0;
    text-decoration: underline;
  }
  .meta { color: #555; font-size: 0.9rem; }
  [role='alert'] { color: #a4232b; }
  @media (max-width: 38rem) { .ask-form { grid-template-columns: 1fr; } }
</style>