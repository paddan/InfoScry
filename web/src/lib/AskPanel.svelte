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
    type LlmProfilePrice,
  } from './api';

  export let collectionId: string;
  export let onOpenSource: (evidence: AskEvidence) => void | Promise<void>;
  export let askProfile = '';
  export let availableProfiles: LlmProfilePrice[] = [];
  export let profileStatus = 'Loading profiles…';

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
  let abortController: AbortController | null = null;

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

  async function ask(event: SubmitEvent): Promise<void> {
    event.preventDefault();
    const text = question.trim();
    if (text === '' || collectionId === '' || askProfile === '' || asking) return;
    const generation = ++askGeneration;
    requestProfile = askProfile;
    const controller = new AbortController();
    abortController = controller;
    asking = true;
    answerText = '';
    answerEvidence = [];
    renderedAnswerParts = [];
    usage = null;
    askError = null;
    askStatus = 'Preparing answer…';
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
      if (generation === askGeneration) {
        asking = false;
        abortController = null;
      }
    }
  }

  function applyAskEvent(event: AskEvent): void {
    switch (event.type) {
      case 'delta':
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
        answerText = event.text;
        answerEvidence = event.evidence;
        renderedAnswerParts = splitAnswer(answerText, answerEvidence);
        askStatus = null;
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
  onDestroy(() => abortController?.abort());
</script>

<section aria-labelledby="ask-heading">
  <h2 id="ask-heading">Ask</h2>
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
