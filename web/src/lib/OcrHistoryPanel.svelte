<script lang="ts">
  import { onDestroy, tick } from 'svelte';
  import {
    getRestoreOperation,
    listDocumentRevisions,
    restoreRevision,
    type PageChanges,
    type RestoreOperation,
    type RevisionView,
  } from './api';
  import { ENGINE_LABELS, IMPORT_MODE_LABELS, newRequestId, pageCount } from './ocrRescan';

  /** The document is named by both ids, because history is only ever read under its own collection. */
  export let collectionId: string;
  export let documentId: string;
  export let documentName: string;
  /** How long a restore that is still staged waits before it is read again. */
  export let pollMillis = 1500;

  /** The server never answers more than this per request. */
  const PAGE_SIZE = 200;
  const NOTHING_CHANGED = 'Nothing was restored and the current text is unchanged.';

  /**
   * Everything below belongs to one collection and one document. Each asynchronous step captures the
   * generation it started in, and a step that finds a newer one drops its answer.
   */
  let boundKey: string | null = null;
  let generation = 0;
  let alive = true;

  let open = false;
  let loading = false;
  let loadingMore = false;
  let loadError: string | null = null;
  let revisions: RevisionView[] = [];
  let total = 0;
  /** The active revision the list was loaded from: what a restore is made against. */
  let activeRevisionId: string | null = null;

  let target: RevisionView | null = null;
  let restoring = false;
  let restoreError: string | null = null;
  let restoreNotice: string | null = null;
  /** The request id of an attempt whose outcome is unknown, reused only for an identical retry. */
  let attempt: { key: string; requestId: string } | null = null;
  let timer: ReturnType<typeof setTimeout> | null = null;

  let toggleButton: HTMLButtonElement | null = null;
  let confirmHeading: HTMLElement | null = null;
  let noticeElement: HTMLElement | null = null;
  let reloadButton: HTMLButtonElement | null = null;
  let restoreButtons: Record<string, HTMLButtonElement | null> = {};

  $: key = `${collectionId}\u0000${documentId}`;
  $: if (key !== boundKey) reset(key);

  function reset(next: string): void {
    boundKey = next;
    generation += 1;
    clearTimer();
    open = false;
    loading = false;
    loadingMore = false;
    loadError = null;
    revisions = [];
    total = 0;
    activeRevisionId = null;
    target = null;
    restoring = false;
    restoreError = null;
    restoreNotice = null;
    attempt = null;
  }

  onDestroy(() => {
    alive = false;
    generation += 1;
    clearTimer();
  });

  function clearTimer(): void {
    if (timer !== null) clearTimeout(timer);
    timer = null;
  }

  function describe(failure: unknown): string {
    return failure instanceof Error ? failure.message : 'Something went wrong.';
  }

  function shortId(id: string): string {
    return id.slice(0, 8);
  }

  function label(labels: object, value: string): string {
    return Object.prototype.hasOwnProperty.call(labels, value) ? (labels as Record<string, string>)[value] : value;
  }

  // ---- listing ----

  async function loadHistory(): Promise<void> {
    const mine = generation;
    loading = true;
    loadError = null;
    try {
      const answered = await listDocumentRevisions(collectionId, documentId, 0, PAGE_SIZE);
      if (mine !== generation) return;
      revisions = answered.revisions ?? [];
      total = answered.total ?? revisions.length;
      activeRevisionId = answered.activeRevisionId ?? revisions.find((entry) => entry.active)?.revisionId ?? null;
    } catch (failure) {
      if (mine !== generation) return;
      // A list that could not be read is an error, never an empty history.
      revisions = [];
      total = 0;
      activeRevisionId = null;
      loadError = describe(failure);
    } finally {
      if (mine === generation) loading = false;
    }
  }

  async function loadOlder(): Promise<void> {
    if (loadingMore || loading) return;
    const mine = generation;
    loadingMore = true;
    try {
      const answered = await listDocumentRevisions(collectionId, documentId, revisions.length, PAGE_SIZE);
      if (mine !== generation) return;
      revisions = [...revisions, ...(answered.revisions ?? [])];
      total = answered.total ?? total;
    } catch (failure) {
      if (mine !== generation) return;
      loadError = describe(failure);
    } finally {
      if (mine === generation) loadingMore = false;
    }
  }

  async function toggle(): Promise<void> {
    if (restoring) return;
    if (open) {
      generation += 1;
      open = false;
      loading = false;
      loadingMore = false;
      target = null;
      restoreError = null;
      restoreNotice = null;
      await tick();
      toggleButton?.focus();
      return;
    }
    open = true;
    void loadHistory();
  }

  async function reload(): Promise<void> {
    if (restoring || loading) return;
    target = null;
    restoreError = null;
    restoreNotice = null;
    await loadHistory();
  }

  // ---- restoring ----

  async function askToRestore(revision: RevisionView): Promise<void> {
    if (restoring) return;
    target = revision;
    restoreError = null;
    restoreNotice = null;
    await tick();
    confirmHeading?.focus();
  }

  async function cancelRestore(): Promise<void> {
    if (restoring) return;
    const was = target;
    target = null;
    restoreError = null;
    await tick();
    (was !== null ? restoreButtons[was.revisionId] : null)?.focus();
  }

  /** The plain sentence for a refusal; the code is the contract, the server's words are shown only for an unknown code. */
  function refusalMessage(code: string, serverMessage: string | null | undefined, status: number | null): string {
    switch (code) {
      case 'COLLECTION_NOT_ACTIVE':
        return `This collection is being deleted, so nothing can be restored. ${NOTHING_CHANGED}`;
      case 'DOCUMENT_BEING_DELETED':
        return `This document is being deleted, so nothing can be restored. ${NOTHING_CHANGED}`;
      case 'OCR_ALREADY_RUNNING':
      case 'OCR_ATTEMPT_IN_PROGRESS':
        return `A scan of this document is in progress or has pages waiting for review, so it cannot be restored yet. ${NOTHING_CHANGED}`;
      case 'RESTORE_ALREADY_ACTIVE':
        return `That version is already the active version, so there is nothing to restore. ${NOTHING_CHANGED}`;
      case 'RESTORE_NOTHING_PUBLISHED':
        return `That version published no text, so restoring it would publish nothing. ${NOTHING_CHANGED}`;
      case 'RESTORE_IN_PROGRESS':
        return `Another restore of this document has not finished. Wait for it to finish, then try again. ${NOTHING_CHANGED}`;
      case 'RESTORE_EMBEDDING_UNAVAILABLE':
        return `This Mac cannot index right now (no embedding model is available), so a version cannot be restored. Keyword search and source viewing still work. ${NOTHING_CHANGED}`;
      case 'RESTORE_EMBEDDING_FAILED':
        return `The restored text could not be indexed, so this Mac cannot index right now. Try again later. ${NOTHING_CHANGED}`;
      case 'RESTORE_PUBLICATION_REFUSED':
        return `The restored text could not be published. ${NOTHING_CHANGED}`;
      case 'RESTORE_INTERRUPTED':
        return `The restore was interrupted before it was published. ${NOTHING_CHANGED}`;
      case 'RESTORE_REQUEST_CONFLICT':
        return 'That request was already used for a different restore. Nothing was restored; start the restore again to send a new request.';
      default:
        if (status === 404 || code === 'NOT_FOUND') {
          return `That version no longer exists for this document. Reload the history. ${NOTHING_CHANGED}`;
        }
        return serverMessage ? `${serverMessage}` : describe(null);
    }
  }

  async function confirmRestore(): Promise<void> {
    const chosen = target;
    const expected = activeRevisionId;
    if (restoring || chosen === null || expected === null) return;
    const mine = generation;
    restoring = true;
    restoreError = null;
    restoreNotice = null;
    const attemptKey = `${expected}|${chosen.revisionId}`;
    // An identical retry after an unanswered request is the same request; anything else is a new one.
    if (attempt === null || attempt.key !== attemptKey) attempt = { key: attemptKey, requestId: newRequestId() };
    const requestId = attempt.requestId;
    try {
      const answered = await restoreRevision(collectionId, documentId, requestId, expected, chosen.revisionId);
      if (mine !== generation) return;
      attempt = null;
      await settle(mine, chosen, answered);
    } catch (failure) {
      if (mine !== generation) return;
      const status = (failure as { status?: number | null } | null)?.status ?? null;
      const code = (failure as { code?: string } | null)?.code ?? '';
      if (status === null) {
        // The answer never arrived, so the request may or may not have been admitted: keep its id.
        restoreError = `The restore could not be confirmed: ${describe(failure)} Trying again sends the same request, so it cannot be applied twice.`;
      } else {
        attempt = null;
        if (code === 'STALE_DOCUMENT_REVISION') {
          restoreError = 'This document\'s text changed since this list was loaded, so nothing was restored. Reload the history and choose again.';
          target = null;
          await tick();
          reloadButton?.focus();
        } else {
          restoreError = refusalMessage(code, (failure as Error).message, status);
        }
      }
      restoring = false;
    }
  }

  /** A restore that answered 202 is finished, failed or still being indexed; the server's phase says which. */
  async function settle(mine: number, chosen: RevisionView, operation: RestoreOperation): Promise<void> {
    if (mine !== generation) return;
    if (operation.phase === 'STAGED') {
      restoreNotice = `Restoring version ${shortId(chosen.revisionId)}: the text is being indexed. Search keeps using the current version until it is done.`;
      timer = setTimeout(() => void poll(mine, chosen, operation.restoreId), pollMillis);
      return;
    }
    if (operation.phase === 'PUBLISHED') {
      restoreNotice = `Version ${shortId(chosen.revisionId)} was restored as new version ${shortId(operation.newRevisionId)}. It is the active version now, and search uses it.`;
      restoreError = null;
      target = null;
      restoring = false;
      await loadHistory();
      if (mine !== generation) return;
      await tick();
      noticeElement?.focus();
      return;
    }
    // FAILED, or a phase this build does not know: the current text is still the document's text.
    restoreNotice = null;
    restoreError = `Version ${shortId(chosen.revisionId)} was not restored. The current text is still the active version. ${refusalMessage(operation.errorCode ?? '', operation.errorMessage, null)}`;
    restoring = false;
  }

  async function poll(mine: number, chosen: RevisionView, restoreId: string): Promise<void> {
    timer = null;
    if (mine !== generation) return;
    try {
      const operation = await getRestoreOperation(collectionId, documentId, restoreId);
      if (mine !== generation) return;
      await settle(mine, chosen, operation);
    } catch (failure) {
      if (mine !== generation) return;
      restoreError = `The restore could not be refreshed: ${describe(failure)}`;
      timer = setTimeout(() => void poll(mine, chosen, restoreId), pollMillis);
    }
  }

  // ---- words for what the archive recorded ----

  function provenanceLabel(revision: RevisionView): string {
    switch (revision.provenance) {
      case 'IMPORT':
        return 'Import';
      case 'IMPORT_CHECK_AND_IMPROVE':
        return 'Import (existing text checked and improved)';
      case 'RESCAN':
        return 'Rescan';
      case 'RESTORE':
        return revision.restoredFromRevisionId
          ? `Restored from ${shortId(revision.restoredFromRevisionId)}`
          : 'Restored from an earlier version (which one was not recorded)';
      default:
        return revision.provenance;
    }
  }

  function changeSummary(changes: PageChanges | undefined): string {
    if (changes === undefined) return 'not recorded';
    const parts: [number, string][] = [
      [changes.automatic, 'automatic'],
      [changes.manual, 'manual'],
      [changes.restored, 'restored'],
      [changes.unknown, 'unknown'],
      [changes.added, 'added'],
      [changes.unchanged, 'unchanged'],
      [changes.notPublished, 'not published'],
    ];
    const shown = parts.filter(([count]) => count > 0).map(([count, name]) => `${count} ${name}`);
    return shown.length > 0 ? shown.join(', ') : 'no pages';
  }
</script>

<section class="history" aria-label="Text history for {documentName}">
  <div class="actions">
    <button
      type="button"
      bind:this={toggleButton}
      aria-expanded={open}
      disabled={restoring}
      onclick={() => void toggle()}
    >
      Text history
    </button>
  </div>

  {#if open}
    {#if loading}
      <p class="hint" role="status">Loading the text history…</p>
    {:else if loadError !== null}
      <p role="alert">{loadError}</p>
      <div class="actions">
        <button type="button" onclick={() => void loadHistory()}>Try again</button>
      </div>
    {:else}
      {#if revisions.length === 0}
        <p class="hint">This document has no published text versions.</p>
      {:else}
        <p class="hint">
          Every published text version, newest first. Search always uses the active version. Automatic and
          manual page changes are inferred from the reviews that were recorded; a change with no recorded
          review is shown as unknown.
        </p>
        <ol class="versions" aria-label="Text versions">
          {#each revisions as revision (revision.revisionId)}
            <li class:active={revision.active}>
              <h4>Version {shortId(revision.revisionId)}</h4>
              {#if revision.active}<p class="marker">Active version</p>{/if}
              <dl>
                <dt>Source</dt>
                <dd>{provenanceLabel(revision)}</dd>
                <dt>Engine and model</dt>
                <dd>
                  {#if revision.reading}
                    {label(ENGINE_LABELS, revision.reading.engine)} · {label(IMPORT_MODE_LABELS, revision.reading.mode)} · language {revision.reading.language}
                    {#if revision.reading.transcriptionModel}<br />Transcription model: {revision.reading.transcriptionModel}{/if}
                    {#if revision.reading.reviewModel}<br />Review model: {revision.reading.reviewModel}{/if}
                    {#if revision.reading.toolVersion}<br />Tool version: {revision.reading.toolVersion}{/if}
                    {#if revision.reading.modelVersion}<br />Model version: {revision.reading.modelVersion}{/if}
                  {:else}
                    Not recorded
                  {/if}
                </dd>
                {#if revision.extractionMethods && revision.extractionMethods.length > 0}
                  <dt>Read by</dt>
                  <dd>{revision.extractionMethods.join(', ')}</dd>
                {/if}
                <dt>Created</dt>
                <dd>{revision.createdAt}</dd>
                <dt>Published</dt>
                <dd>{revision.publishedAt ? revision.publishedAt : 'Not recorded'}</dd>
                <dt>Pages</dt>
                <dd>{pageCount(revision.pageCount)}</dd>
                <dt>Page changes</dt>
                <dd>{changeSummary(revision.pageChanges)}</dd>
              </dl>
              {#if !revision.active && activeRevisionId !== null}
                <div class="actions">
                  <button
                    type="button"
                    bind:this={restoreButtons[revision.revisionId]}
                    aria-label="Restore version {shortId(revision.revisionId)}"
                    disabled={restoring}
                    onclick={() => void askToRestore(revision)}
                  >
                    Restore
                  </button>
                </div>
              {/if}
            </li>
          {/each}
        </ol>
        {#if revisions.length < total}
          <p class="hint">Showing {revisions.length} of {total} versions.</p>
          <div class="actions">
            <button type="button" disabled={loadingMore} onclick={() => void loadOlder()}>
              Show older versions
            </button>
          </div>
        {/if}
      {/if}
      <div class="actions">
        <button type="button" bind:this={reloadButton} disabled={restoring} onclick={() => void reload()}>
          Reload history
        </button>
      </div>
    {/if}

    {#if restoreNotice !== null}
      <p class="notice" role="status" tabindex="-1" bind:this={noticeElement}>{restoreNotice}</p>
    {/if}
    {#if restoreError !== null}<p role="alert">{restoreError}</p>{/if}

    {#if target !== null}
      <div class="panel" role="group" aria-label="Confirm restore">
        <h4 tabindex="-1" bind:this={confirmHeading}>Restore version {shortId(target.revisionId)}?</h4>
        <p>
          This makes the text of version {shortId(target.revisionId)} (created {target.createdAt}) the text of
          {documentName} again, as a new version.
        </p>
        <ul>
          <li>Nothing is rescanned: no page is read again and nothing is sent to an OCR engine.</li>
          <li>The original file is not changed.</li>
          <li>History is kept: every version stays listed, including the one that is active now.</li>
          <li>
            Searchable text switches to this version only after it is indexed. Until then the current
            version stays active, and search always uses the active version.
          </li>
        </ul>
        <div class="actions">
          <button type="button" class="primary" disabled={restoring} onclick={() => void confirmRestore()}>
            {restoring ? 'Restoring…' : 'Restore this version'}
          </button>
          <button type="button" disabled={restoring} onclick={() => void cancelRestore()}>Cancel</button>
        </div>
      </div>
    {/if}
  {/if}
</section>

<style>
  .history { display: grid; gap: 0.6rem; }
  .history p { margin: 0; }
  .hint { color: #929997; font-size: 0.82rem; }
  .notice:focus { outline: 2px solid #c4a77d; outline-offset: 2px; }
  .versions { display: grid; gap: 0.6rem; margin: 0; padding: 0; list-style: none; }
  .versions li { display: grid; gap: 0.4rem; border: 1px solid #303535; border-radius: 0.55rem; padding: 0.75rem; }
  .versions li.active { border-color: #c4a77d; }
  .versions h4 { margin: 0; font-size: 0.92rem; }
  .marker { color: #c4a77d; font-size: 0.82rem; font-weight: 650; }
  dl { display: grid; grid-template-columns: auto minmax(0, 1fr); gap: 0.3rem 0.9rem; margin: 0; font-size: 0.85rem; }
  dt { color: #b8bcbb; }
  dd { margin: 0; overflow-wrap: anywhere; }
  .panel { display: grid; gap: 0.6rem; border: 1px solid #303535; border-radius: 0.55rem; background: #1d2021; padding: 0.9rem; }
  .panel h4 { margin: 0; font-size: 0.95rem; }
  .panel h4:focus { outline: 2px solid #c4a77d; outline-offset: 2px; }
  .panel ul { margin: 0; padding-left: 1.1rem; display: grid; gap: 0.25rem; font-size: 0.85rem; }
  .actions { display: flex; flex-wrap: wrap; gap: 0.55rem; }
  button.primary { border-color: #c4a77d; background: #c4a77d; color: #1c1b18; font-weight: 650; }
  button.primary:hover:not(:disabled) { background: #d4ba94; }
  @media (max-width: 36rem) {
    dl { grid-template-columns: minmax(0, 1fr); gap: 0.1rem; }
    dd { margin-bottom: 0.3rem; }
  }
</style>
