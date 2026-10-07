<script lang="ts">
  import { onDestroy, onMount, tick } from 'svelte';
  import {
    decideReviews,
    listDocumentRevisions,
    listPendingReviews,
    publishReviewDecisions,
    readSource,
    type OcrOperation,
    type PendingReview,
    type ReviewChoice,
    type ReviewDecisionInput,
    type ReviewReason,
  } from './api';
  import { newRequestId } from './ocrRescan';

  /** The document is named by both ids, because a review is only ever read under its own collection. */
  export let collectionId: string;
  export let documentId: string;
  export let documentName: string;
  export let operationId: string;
  /** Called when the reader closes the review; the opener returns focus to where it came from. */
  export let onclose: () => void = () => {};
  /** Called once with the operation after a publication reported PUBLISHED. */
  export let onpublished: (operation: OcrOperation) => void = () => {};

  /** One page per request: a large document never loads more than one proposal and one baseline at once. */
  const PAGE_SIZE = 1;
  const BASELINE_CHARS = 16_384;
  const MAX_REASONS = 8;
  const MAX_EXPLANATION = 300;

  type Draft = {
    unitId: string;
    ordinal: number;
    /** The candidate hash the decision was taken against. */
    hash: string;
    choice: ReviewChoice | null;
    /** The person's own text; kept when the choice changes, so an edit is never lost by trying another action. */
    text: string;
    /** The page's reading changed since the choice was made, so the choice was dropped and the text kept. */
    stale: boolean;
  };

  type Baseline =
    | { state: 'none' }
    | { state: 'loading' }
    | { state: 'ready'; text: string; truncated: boolean }
    | { state: 'error'; message: string };

  type ProblemKind = 'load' | 'conflict' | 'other';

  /**
   * Everything below belongs to one collection, document and operation. Each asynchronous step captures the
   * generation it started in, and a step that finds a newer one drops its answer.
   */
  let boundKey: string | null = null;
  let generation = 0;
  let loadSequence = 0;
  let baselineSequence = 0;
  let alive = true;

  let loading = true;
  let revisionKnown = false;
  let activeRevisionId: string | null = null;
  let offset = 0;
  let total = 0;
  let accounting: string | null = null;
  let current: PendingReview | null = null;
  let baseline: Baseline = { state: 'none' };

  let drafts: Record<string, Draft> = {};
  let busy: 'save' | 'publish' | null = null;
  let problem: string | null = null;
  let problemKind: ProblemKind = 'other';
  let saveResult: string | null = null;
  let publication: { published: boolean; text: string } | null = null;
  let scopeChoice: ReviewChoice | null = null;
  let scopeOpener: HTMLButtonElement | null = null;
  let lastRequest: { signature: string; id: string } | null = null;

  let panelHeading: HTMLElement | null = null;
  let pageHeading: HTMLElement | null = null;
  let resultBox: HTMLElement | null = null;
  let editBox: HTMLTextAreaElement | null = null;
  let scopeCancel: HTMLButtonElement | null = null;
  let keepScopeButton: HTMLButtonElement | null = null;
  let newScopeButton: HTMLButtonElement | null = null;

  $: key = `${collectionId}\u0000${documentId}\u0000${operationId}`;
  $: if (key !== boundKey) start(key);
  $: unsaved = Object.values(drafts).filter((draft) => draft.choice !== null).length;
  $: draft = current === null ? null : drafts[current.unitId] ?? null;
  $: segments = current === null ? [] : markedSegments(baseline, current.reasons);
  $: noRevision = revisionKnown && activeRevisionId === null;
  $: canPublish = total === 0 && !loading && problemKind !== 'load' && activeRevisionId !== null;

  function start(next: string): void {
    boundKey = next;
    generation += 1;
    loadSequence += 1;
    baselineSequence += 1;
    loading = true;
    revisionKnown = false;
    activeRevisionId = null;
    offset = 0;
    total = 0;
    accounting = null;
    current = null;
    baseline = { state: 'none' };
    drafts = {};
    busy = null;
    problem = null;
    problemKind = 'other';
    saveResult = null;
    publication = null;
    scopeChoice = null;
    lastRequest = null;
    void load(0, { revision: true });
  }

  onMount(() => {
    panelHeading?.focus();
  });

  onDestroy(() => {
    alive = false;
    generation += 1;
    loadSequence += 1;
    baselineSequence += 1;
  });

  function describe(failure: unknown): string {
    return failure instanceof Error ? failure.message : 'Something went wrong.';
  }

  function isConflict(failure: unknown): boolean {
    return (failure as { status?: number | null } | null)?.status === 409;
  }

  function fail(failure: unknown, fallback: ProblemKind = 'other'): void {
    problem = describe(failure);
    problemKind = isConflict(failure) ? 'conflict' : fallback;
  }

  /** Reads one bounded page of proposals (and, when asked, the revision decisions are guarded by). */
  async function load(
    nextOffset: number,
    options: { revision?: boolean; focusPage?: boolean } = {},
  ): Promise<void> {
    const mine = generation;
    const sequence = ++loadSequence;
    const collection = collectionId;
    const document = documentId;
    const operation = operationId;
    loading = true;
    try {
      const [answer, revisions] = await Promise.all([
        listPendingReviews(collection, document, operation, nextOffset, PAGE_SIZE),
        options.revision ? listDocumentRevisions(collection, document, 0, 1) : Promise.resolve(null),
      ]);
      if (mine !== generation || sequence !== loadSequence) return;
      if (revisions !== null) {
        activeRevisionId = revisions.activeRevisionId ?? null;
        revisionKnown = true;
      }
      total = answer.total;
      accounting = answer.externalAccounting ?? null;
      // Decisions that were saved shrink the list: a position past its end steps back to the last page.
      if (answer.reviews.length === 0 && answer.total > 0 && nextOffset > 0) {
        await load(Math.max(0, answer.total - PAGE_SIZE), { focusPage: options.focusPage });
        return;
      }
      offset = nextOffset;
      if (problemKind === 'load') {
        problem = null;
        problemKind = 'other';
      }
      setCurrent(answer.reviews[0] ?? null);
      loading = false;
      if (options.focusPage) {
        await tick();
        if (mine === generation) pageHeading?.focus();
      }
    } catch (failure) {
      if (mine !== generation || sequence !== loadSequence) return;
      loading = false;
      fail(failure, 'load');
      problemKind = 'load';
    }
  }

  function setCurrent(review: PendingReview | null): void {
    current = review;
    if (review === null) {
      baseline = { state: 'none' };
      baselineSequence += 1;
      return;
    }
    // A choice taken against another reading of this page is not applied to the one shown now: the choice is
    // dropped and the text the person wrote stays where they can reuse it.
    const held = drafts[review.unitId];
    if (held !== undefined && held.hash !== review.candidateHash && (held.choice !== null || held.text !== '')) {
      drafts = {
        ...drafts,
        [review.unitId]: { ...held, hash: review.candidateHash, choice: null, stale: true },
      };
    }
    void loadBaseline(review);
  }

  async function loadBaseline(review: PendingReview): Promise<void> {
    const sequence = ++baselineSequence;
    const mine = generation;
    if (!review.baselineRevisionId) {
      baseline = { state: 'none' };
      return;
    }
    baseline = { state: 'loading' };
    try {
      const answer = await readSource(collectionId, review.unitId, 0, BASELINE_CHARS, review.baselineRevisionId);
      if (mine !== generation || sequence !== baselineSequence) return;
      baseline = { state: 'ready', text: answer.text, truncated: answer.truncated };
    } catch (failure) {
      if (mine !== generation || sequence !== baselineSequence) return;
      baseline = { state: 'error', message: describe(failure) };
    }
  }

  /** The baseline split at the reviewer's differing spans; every piece is rendered as a text node. */
  function markedSegments(from: Baseline, reasons: ReviewReason[]): { text: string; mark: boolean }[] {
    if (from.state !== 'ready') return [];
    const text = from.text;
    const spans = reasons
      .map((reason) => [reason.baselineStart, reason.baselineEnd] as const)
      .filter((span): span is readonly [number, number] => typeof span[0] === 'number' && typeof span[1] === 'number')
      .map(([start, end]) => [Math.max(0, start), Math.min(text.length, end)] as [number, number])
      .filter(([start, end]) => start < end)
      .sort((a, b) => a[0] - b[0]);
    const merged: [number, number][] = [];
    for (const span of spans) {
      const last = merged[merged.length - 1];
      if (last !== undefined && span[0] <= last[1]) last[1] = Math.max(last[1], span[1]);
      else merged.push([span[0], span[1]]);
    }
    const parts: { text: string; mark: boolean }[] = [];
    let at = 0;
    for (const [start, end] of merged) {
      if (start > at) parts.push({ text: text.slice(at, start), mark: false });
      parts.push({ text: text.slice(start, end), mark: true });
      at = end;
    }
    if (at < text.length || parts.length === 0) parts.push({ text: text.slice(at), mark: false });
    return parts;
  }

  const RECOMMENDATIONS: Record<string, string> = {
    EXISTING_BETTER: 'The existing reading looks better',
    NEW_BETTER: 'The new reading looks better',
    UNCERTAIN: 'Uncertain',
  };

  function recommendation(code: string): string {
    return RECOMMENDATIONS[code] ?? code;
  }

  function bounded(text: string): string {
    return text.length > MAX_EXPLANATION ? `${text.slice(0, MAX_EXPLANATION)}…` : text;
  }

  function span(start: number | null | undefined, end: number | null | undefined): string | null {
    return typeof start === 'number' && typeof end === 'number' ? `${start}–${end}` : null;
  }

  async function go(next: number): Promise<void> {
    if (next < 0 || (total > 0 && next >= total)) return;
    problem = problem !== null && problemKind === 'load' ? problem : null;
    await load(next, { focusPage: true });
  }

  async function choose(choice: ReviewChoice): Promise<void> {
    if (current === null || busy !== null) return;
    const review = current;
    const held = drafts[review.unitId];
    if (held !== undefined && held.choice === choice && !held.stale) {
      // Choosing the same action again takes it back; an edit's text stays.
      drafts = { ...drafts, [review.unitId]: { ...held, choice: null } };
      return;
    }
    let text = held?.text ?? '';
    if (choice === 'EDIT' && text === '' && baseline.state === 'ready') text = baseline.text;
    drafts = {
      ...drafts,
      [review.unitId]: {
        unitId: review.unitId,
        ordinal: review.ordinal,
        hash: review.candidateHash,
        choice,
        text,
        stale: false,
      },
    };
    problem = problemKind === 'load' ? problem : null;
    if (choice === 'EDIT') {
      await tick();
      editBox?.focus();
    }
  }

  function typed(event: Event): void {
    if (current === null) return;
    const held = drafts[current.unitId];
    if (held === undefined) return;
    drafts = { ...drafts, [current.unitId]: { ...held, text: (event.currentTarget as HTMLTextAreaElement).value } };
  }

  function requestIdFor(signature: string): string {
    // The same body after a failure is the same request, so a retry cannot be applied twice.
    if (lastRequest === null || lastRequest.signature !== signature) lastRequest = { signature, id: newRequestId() };
    return lastRequest.id;
  }

  /** Saves the explicit decisions first, then (when confirmed) one choice for the whole pending set. */
  async function save(scope: ReviewChoice | null): Promise<void> {
    if (busy !== null || activeRevisionId === null) return;
    const chosen = Object.values(drafts)
      .filter((held) => held.choice !== null)
      .sort((a, b) => a.ordinal - b.ordinal);
    const decisions: ReviewDecisionInput[] = [];
    for (const held of chosen) {
      if (held.choice === 'EDIT') {
        if (held.text.trim() === '') {
          problem = `Write the text for page ${held.ordinal + 1} or choose another action.`;
          problemKind = 'other';
          return;
        }
        decisions.push({ unitId: held.unitId, ordinal: held.ordinal, candidateHash: held.hash, choice: 'EDIT', text: held.text });
      } else {
        decisions.push({ unitId: held.unitId, ordinal: held.ordinal, candidateHash: held.hash, choice: held.choice as ReviewChoice });
      }
    }
    if (decisions.length === 0 && scope === null) return;

    const mine = generation;
    const collection = collectionId;
    const document = documentId;
    const operation = operationId;
    const expected = activeRevisionId;
    busy = 'save';
    problem = null;
    problemKind = 'other';
    saveResult = null;
    publication = null;
    try {
      let appliedPages = 0;
      if (decisions.length > 0) {
        const body = { expectedRevisionId: expected, decisions };
        await decideReviews(collection, document, operation, {
          ...body,
          requestId: requestIdFor(JSON.stringify(body)),
        });
        if (mine !== generation) return;
        appliedPages += decisions.length;
        const kept = { ...drafts };
        for (const decision of decisions) delete kept[decision.unitId];
        drafts = kept;
      }
      if (scope !== null) {
        const body = { expectedRevisionId: expected, documentWide: scope };
        await decideReviews(collection, document, operation, {
          ...body,
          requestId: requestIdFor(JSON.stringify(body)),
        });
        if (mine !== generation) return;
      }
      lastRequest = null;
      saveResult = scope === null
        ? `Saved ${appliedPages} ${appliedPages === 1 ? 'decision' : 'decisions'}. Nothing is searchable differently until you publish.`
        : `Saved${appliedPages > 0 ? ` ${appliedPages} ${appliedPages === 1 ? 'decision' : 'decisions'} and` : ''} ${scope === 'USE_NEW' ? 'Use new' : 'Keep existing'} for every page that was waiting. Nothing is searchable differently until you publish.`;
      await load(0, { revision: false });
      if (mine !== generation) return;
      await tick();
      resultBox?.focus();
    } catch (failure) {
      if (mine !== generation) return;
      fail(failure);
      if (problemKind === 'other' && decisions.length > 0 && drafts[decisions[0].unitId] === undefined) {
        problem = `${problem} Some decisions were already saved; the rest are still here.`;
      }
    } finally {
      if (mine === generation) busy = null;
    }
  }

  function openScope(choice: ReviewChoice, opener: HTMLButtonElement | null): void {
    if (busy !== null) return;
    scopeChoice = choice;
    scopeOpener = opener;
    void tick().then(() => scopeCancel?.focus());
  }

  async function cancelScope(): Promise<void> {
    scopeChoice = null;
    await tick();
    scopeOpener?.focus();
  }

  async function confirmScope(): Promise<void> {
    const choice = scopeChoice;
    scopeChoice = null;
    if (choice === null) return;
    await save(choice);
  }

  async function publish(): Promise<void> {
    if (busy !== null || activeRevisionId === null || !canPublish) return;
    const mine = generation;
    busy = 'publish';
    problem = null;
    problemKind = 'other';
    publication = null;
    try {
      const result = await publishReviewDecisions(collectionId, documentId, operationId, activeRevisionId);
      if (mine !== generation) return;
      if (result.phase === 'PUBLISHED') {
        publication = { published: true, text: 'Published. Searchable text now uses the decided pages.' };
        onpublished(result.operation);
      } else {
        publication = {
          published: false,
          text: `Publication is ${result.phase}${result.errorCode ? ` (${result.errorCode})` : ''}. Search still uses the existing text until it is published.`,
        };
      }
    } catch (failure) {
      if (mine !== generation) return;
      fail(failure);
    } finally {
      if (mine === generation) busy = null;
    }
  }

  /** After a conflict: read the current revision and page again; every unsaved decision and edit stays. */
  async function reload(): Promise<void> {
    if (busy !== null) return;
    problem = null;
    problemKind = 'other';
    await load(offset, { revision: true });
  }
</script>

<section class="review" aria-label="Review pages">
  <h3 tabindex="-1" bind:this={panelHeading}>Review pages</h3>
  <p class="hint">
    Pages of {documentName} where the new reading and the existing text could not be settled. Search keeps using the
    existing text until you publish your decisions.
  </p>

  {#if loading && current === null && problem === null}
    <p class="hint">Loading pages…</p>
  {/if}

  <p role="status" aria-label="Position">
    {#if total === 0 && !loading}
      No pages are waiting for review.
    {:else if total > 0}
      {offset + 1} of {total} {total === 1 ? 'page' : 'pages'} waiting for review
    {/if}
  </p>
  {#if accounting}<p class="hint">{accounting}</p>{/if}

  {#if problem !== null}
    <p role="alert" class="problem">{problem}</p>
    {#if problemKind === 'conflict'}
      <div class="actions">
        <button type="button" onclick={() => void reload()} disabled={busy !== null}>
          Reload with the current revision
        </button>
      </div>
    {:else if problemKind === 'load'}
      <div class="actions">
        <button type="button" onclick={() => void load(offset, { revision: true })}>Try again</button>
      </div>
    {/if}
  {/if}

  {#if noRevision}
    <p class="hint">
      This document has no published revision to guard a decision against, so decisions cannot be saved from here.
    </p>
  {/if}

  {#if current !== null}
    <article class="page" aria-label={`Page ${current.ordinal + 1}`} aria-busy={loading}>
      <h4 tabindex="-1" bind:this={pageHeading}>Page {current.ordinal + 1}</h4>

      {#if !current.searchable}
        <p role="note" aria-label="Image-only page" class="flag">
          Image-only page: its text is not in search. It stays out of search until you approve a reading for it.
        </p>
      {/if}

      <p class="hint">
        The page image is not available here: the server has no page-image route for review, so judge this page
        against the original document.
      </p>

      <p>
        Reviewer recommendation: <strong>{recommendation(current.recommendation)}</strong>
        {#if typeof current.confidence === 'number'}
          <span class="hint"> · confidence {Math.round(current.confidence * 100)}%</span>
        {/if}
      </p>
      {#if current.outcomeCode}<p class="hint">Outcome: {current.outcomeCode}</p>{/if}

      {#if current.reasons.length > 0}
        <ul class="reasons" aria-label="Reasons">
          {#each current.reasons.slice(0, MAX_REASONS) as reason, index (index)}
            <li>
              <strong>{reason.code}</strong>
              <span class="hint"> ({reason.origin})</span>
              {#if reason.explanation}<span class="explanation">{bounded(reason.explanation)}</span>{/if}
              {#if span(reason.baselineStart, reason.baselineEnd) !== null}
                <span class="hint"> · existing characters {span(reason.baselineStart, reason.baselineEnd)}</span>
              {/if}
              {#if span(reason.candidateStart, reason.candidateEnd) !== null}
                <span class="hint"> · new characters {span(reason.candidateStart, reason.candidateEnd)}</span>
              {/if}
            </li>
          {/each}
        </ul>
        {#if current.reasons.length > MAX_REASONS}
          <p class="hint">and {current.reasons.length - MAX_REASONS} more reasons.</p>
        {/if}
      {/if}

      <div class="texts">
        <div>
          <h5>Existing text</h5>
          {#if baseline.state === 'loading'}
            <p class="hint">Loading the existing text…</p>
          {:else if baseline.state === 'error'}
            <p class="hint">The existing text could not be read: {baseline.message}</p>
          {:else if baseline.state === 'ready'}
            <p class="baseline">{#each segments as part, index (index)}{#if part.mark}<mark>{part.text}</mark>{:else}{part.text}{/if}{/each}</p>
            <p class="hint">Highlighted text is where the reviewer found a difference.</p>
            {#if baseline.truncated}<p class="hint">Only the first {BASELINE_CHARS} characters are shown.</p>{/if}
          {:else}
            <p class="hint">This page has no text in the published reading.</p>
          {/if}
        </div>
        <div>
          <h5>New reading</h5>
          <p class="hint">
            The new reading's text is not sent to this screen: the server provides its hash and the reasons above only.
          </p>
        </div>
      </div>

      <div class="actions" role="group" aria-label="Decision for this page">
        <button
          type="button"
          aria-pressed={draft?.choice === 'KEEP'}
          disabled={!current.baselineRevisionId || busy !== null}
          onclick={() => void choose('KEEP')}
        >
          Keep existing
        </button>
        <button type="button" aria-pressed={draft?.choice === 'USE_NEW'} disabled={busy !== null} onclick={() => void choose('USE_NEW')}>
          Use new
        </button>
        <button type="button" aria-pressed={draft?.choice === 'EDIT'} disabled={busy !== null} onclick={() => void choose('EDIT')}>
          Edit text
        </button>
      </div>
      {#if !current.baselineRevisionId}
        <p class="hint">There is no existing text to keep for this page.</p>
      {/if}
      {#if draft !== null && draft.stale}
        <p class="hint">
          Your earlier choice was made against a different reading of this page, so it was dropped. Choose again;
          the text you wrote is kept below.
        </p>
      {/if}
      {#if draft !== null && (draft.choice === 'EDIT' || draft.stale)}
        <div class="field">
          <label for="review-edit-text">Your text for page {current.ordinal + 1}</label>
          <textarea
            id="review-edit-text"
            rows="8"
            value={draft.text}
            oninput={typed}
            bind:this={editBox}
          ></textarea>
        </div>
      {/if}
    </article>

    <div class="actions" role="group" aria-label="Move between pages">
      <button type="button" onclick={() => void go(offset - 1)} disabled={offset === 0}>Previous page</button>
      <button type="button" onclick={() => void go(offset + 1)} disabled={offset + 1 >= total}>Next page</button>
    </div>
  {/if}

  {#if total > 0}
    <div class="scope">
      <h4>Whole document</h4>
      <p class="hint">
        One choice for every page still waiting, not only the page shown. You are asked to confirm before anything is
        saved.
      </p>
      <div class="actions">
        <button type="button" bind:this={keepScopeButton} disabled={busy !== null || noRevision} onclick={() => openScope('KEEP', keepScopeButton)}>
          Keep existing for the whole document
        </button>
        <button type="button" bind:this={newScopeButton} disabled={busy !== null || noRevision} onclick={() => openScope('USE_NEW', newScopeButton)}>
          Use new for the whole document
        </button>
      </div>
      {#if scopeChoice !== null}
        <div role="alertdialog" aria-label="Confirm whole-document choice" class="confirm">
          <p>
            {#if scopeChoice === 'USE_NEW'}
              This will approve the new reading for all {total} pages waiting for review, not only the page shown.
            {:else}
              This will keep the existing text for all {total} pages waiting for review, not only the page shown. If
              any of them has no existing text the server refuses the whole choice.
            {/if}
            Pages you decided above are saved first. Nothing is published until you choose Publish decisions.
          </p>
          <div class="actions">
            <button type="button" class="primary" onclick={() => void confirmScope()}>
              {scopeChoice === 'USE_NEW' ? 'Use new' : 'Keep existing'} for all {total} pending pages
            </button>
            <button type="button" bind:this={scopeCancel} onclick={() => void cancelScope()}>Cancel</button>
          </div>
        </div>
      {/if}
    </div>
  {/if}

  <p role="status" aria-label="Unsaved decisions" class="hint">Unsaved decisions: {unsaved}</p>
  {#if saveResult !== null}
    <p role="status" aria-label="Result" tabindex="-1" bind:this={resultBox} class="result">{saveResult}</p>
  {/if}

  <div class="actions">
    <button type="button" class="primary" onclick={() => void save(null)} disabled={busy !== null || unsaved === 0 || noRevision}>
      {busy === 'save' ? 'Saving…' : 'Save decisions'}
    </button>
    <button type="button" onclick={() => void publish()} disabled={busy !== null || !canPublish}>
      {busy === 'publish' ? 'Publishing…' : 'Publish decisions'}
    </button>
    <button type="button" onclick={() => onclose()}>Close review</button>
  </div>
  {#if total > 0}
    <p class="hint">{total} {total === 1 ? 'page is' : 'pages are'} still waiting for a decision, so nothing can be published yet.</p>
  {/if}
  {#if publication !== null}
    <p role="status" aria-label="Publication" class={publication.published ? 'result' : 'problem'}>{publication.text}</p>
  {/if}
</section>

<style>
  .review { display: grid; gap: 0.6rem; border: 1px solid #303535; border-radius: 0.55rem; background: #1d2021; padding: 0.9rem; }
  .review p { margin: 0; }
  .review h3, .review h4, .review h5 { margin: 0; font-size: 0.95rem; }
  .review h5 { color: #b8bcbb; font-size: 0.82rem; }
  .review h3:focus, .review h4:focus { outline: 2px solid #c4a77d; outline-offset: 2px; }
  .hint { color: #929997; font-size: 0.82rem; }
  .problem { color: #d8c9a8; overflow-wrap: anywhere; }
  .result { color: #b9d1b4; }
  .result:focus { outline: 2px solid #c4a77d; outline-offset: 2px; }
  .flag { border-left: 3px solid #c4a77d; padding-left: 0.6rem; }
  .page { display: grid; gap: 0.5rem; border: 1px solid #303535; border-radius: 0.55rem; padding: 0.75rem; }
  .reasons { margin: 0; padding-left: 1.1rem; overflow-wrap: anywhere; }
  .texts { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0.75rem; }
  .baseline { white-space: pre-wrap; overflow-wrap: anywhere; max-height: 16rem; overflow: auto; }
  mark { background: #5b4a2c; color: #f1e6cf; }
  .field { display: grid; gap: 0.35rem; }
  label { color: #b8bcbb; font-size: 0.82rem; }
  textarea { min-width: 0; border: 1px solid #383d3e; border-radius: 0.48rem; background: #202324; padding: 0.62rem 0.72rem; color: #e8e9e7; font: inherit; }
  .actions { display: flex; flex-wrap: wrap; gap: 0.55rem; }
  .scope { display: grid; gap: 0.5rem; border-top: 1px solid #2b3030; padding-top: 0.6rem; }
  .confirm { display: grid; gap: 0.5rem; border: 1px solid #c4a77d; border-radius: 0.55rem; padding: 0.75rem; }
  button[aria-pressed='true'] { border-color: #c4a77d; background: #3a3426; }
  button.primary { border-color: #c4a77d; background: #c4a77d; color: #1c1b18; font-weight: 650; }
  button.primary:hover:not(:disabled) { background: #d4ba94; }
  @media (max-width: 36rem) {
    .texts { grid-template-columns: minmax(0, 1fr); }
  }
</style>
