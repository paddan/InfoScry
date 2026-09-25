<script lang="ts">
  import { onDestroy, onMount } from 'svelte';
  import {
    ApiError,
    cancelInvestigation,
    continueInvestigation,
    getInvestigation,
    investigateDefaultProfile,
    listInvestigations,
    listLlmProfilePrices,
    readInvestigationEvents,
    startInvestigation,
    type InvestigateActivity,
    type InvestigateEvidence,
    type InvestigateEvent,
    type InvestigateMessage,
    type InvestigationSummary,
    type LlmProfilePrice,
  } from './api';

  export let collectionId: string;
  export let onOpenSource: (evidence: InvestigateEvidence) => void | Promise<void>;
  export let profile = '';
  export let availableProfiles: LlmProfilePrice[] = [];
  export let profileStatus = 'Loading profiles…';

  let question = '';
  let prices: LlmProfilePrice[] = [];
  let conversations: InvestigationSummary[] = [];
  let conversationId: string | null = null;
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
  let controller: AbortController | null = null;
  let generation = 0;

  async function loadPanel(): Promise<void> {
    try { if (profile === '') profile = await investigateDefaultProfile(); }
    catch { profileStatus = 'No default Investigate profile is configured.'; }
    try {
      prices = await listLlmProfilePrices();
      availableProfiles = prices;
      if (availableProfiles.length === 0) profileStatus = 'No LLM profiles are available.';
    } catch { profileStatus = 'Could not load LLM profiles.'; }
    await refreshConversations();
    const savedId = window.localStorage.getItem(`infoscry-investigate:${collectionId}`);
    if (savedId !== null) await loadConversation(savedId);
    else if (conversations.length > 0) await loadConversation(conversations[0].id);
  }

  async function refreshConversations(): Promise<void> {
    try { conversations = await listInvestigations(collectionId); }
    catch { conversations = []; }
  }

  async function loadConversation(id: string): Promise<void> {
    if (id === '') {
      conversationId = null;
      messages = [];
      evidence = [];
      activity = [];
      inputTokens = 0;
      outputTokens = 0;
      costUsd = null;
      window.localStorage.removeItem(`infoscry-investigate:${collectionId}`);
      return;
    }
    loadingHistory = true;
    error = null;
    try {
      const history = await getInvestigation(collectionId, id);
      conversationId = history.id;
      messages = history.messages;
      evidence = history.evidence;
      activity = history.activity;
      inputTokens = history.inputTokens;
      outputTokens = history.outputTokens;
      costUsd = history.costUsd;
      window.localStorage.setItem(`infoscry-investigate:${collectionId}`, history.id);
    } catch (failure) {
      window.localStorage.removeItem(`infoscry-investigate:${collectionId}`);
      error = describe(failure);
    } finally { loadingHistory = false; }
  }

  async function ask(event: SubmitEvent): Promise<void> {
    event.preventDefault();
    const text = question.trim();
    if (text === '' || collectionId === '' || profile === '' || working) return;
    const turn = ++generation;
    const turnProfile = profile;
    const signalController = new AbortController();
    controller = signalController;
    working = true;
    error = null;
    status = 'Starting investigation…';
    messages = [...messages, { role: 'user', text }, { role: 'assistant', text: '' }];
    const answerIndex = messages.length - 1;
    question = '';
    let usage: { inputTokens: number; outputTokens: number } | null = null;
    const validCitationIds = new Set<string>();
    const liveActivity = new Map<string, InvestigateActivity>();
    try {
      const response = conversationId === null
        ? await startInvestigation(collectionId, text, turnProfile, signalController.signal)
        : await continueInvestigation(conversationId, collectionId, text, turnProfile, signalController.signal);
      for await (const event of readInvestigationEvents(response, signalController.signal)) {
        if (turn !== generation) return;
        if (event.type === 'started') {
          conversationId = event.id;
          window.localStorage.setItem(`infoscry-investigate:${collectionId}`, event.id);
        } else if (event.type === 'delta') {
          messages[answerIndex] = { role: 'assistant', text: messages[answerIndex].text + event.text };
          messages = [...messages];
          status = 'Investigating…';
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
          messages[answerIndex] = { role: 'assistant', text: event.text };
          messages = [...messages];
          evidence = mergeEvidence(evidence, event.evidence.filter((item) => validCitationIds.has(item.id)));
          if (usage !== null) {
            inputTokens += usage.inputTokens;
            outputTokens += usage.outputTokens;
            const price = prices.find((item) => item.name === turnProfile);
            if (price !== undefined) {
              costUsd = (costUsd ?? 0) + (usage.inputTokens * price.inputPricePerMillion + usage.outputTokens * price.outputPricePerMillion) / 1_000_000;
            }
          }
          status = null;
          await refreshConversations();
        } else if (event.type === 'error') {
          status = null;
          error = event.message;
        }
      }
      if (status !== null && signalController.signal.aborted === false) status = null;
    } catch (failure) {
      if (turn === generation && !signalController.signal.aborted) error = describe(failure);
    } finally {
      if (turn === generation) {
        working = false;
        controller = null;
      }
    }
  }

  async function cancel(): Promise<void> {
    const id = conversationId;
    status = 'Cancelling…';
    if (id !== null) {
      try { await cancelInvestigation(id); }
      catch (failure) { error = describe(failure); }
    }
    controller?.abort();
    status = 'Investigation cancelled.';
    working = false;
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

  onMount(loadPanel);
  onDestroy(() => controller?.abort());
</script>

<section aria-labelledby="investigate-heading">
  <h2 id="investigate-heading">Investigate</h2>
  {#if conversations.length > 0}
    <label for="conversation">Conversation history</label>
    <select id="conversation" value={conversationId ?? ''} onchange={(event) => loadConversation(event.currentTarget.value)}>
      <option value="">New conversation</option>
      {#each conversations as item (item.id)}<option value={item.id}>{item.question || 'Untitled conversation'}</option>{/each}
    </select>
  {/if}
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
    <button type="submit" disabled={working || profile === '' || question.trim() === ''}>{conversationId === null ? 'Investigate' : 'Send follow-up'}</button>
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
  textarea, button, select { font: inherit; padding: 0.45rem; }
  .meta { color: #555; font-size: 0.9rem; }
  [role='alert'] { color: #a4232b; }
  @media (max-width: 38rem) { form { grid-template-columns: 1fr; } }
</style>
