<script lang="ts">
  import { onDestroy, onMount, tick } from 'svelte';
  import AskPanel from '../lib/AskPanel.svelte';
  import HistoryColumn from '../lib/HistoryColumn.svelte';
  import InvestigatePanel from '../lib/InvestigatePanel.svelte';
  import LlmAdminPanel from '../lib/LlmAdminPanel.svelte';
  import OcrProfilesPanel from '../lib/OcrProfilesPanel.svelte';
  import CollectionsPanel from '../lib/CollectionsPanel.svelte';
  import {
    DEFAULT_INVESTIGATION_LIMITS,
    INVESTIGATION_LIMIT_FIELDS,
    investigationLimitErrors,
    loadInvestigationLimits,
    saveInvestigationLimits,
    validInvestigationLimits,
    type InvestigationLimitKey,
    type InvestigationLimitsInput,
  } from '../lib/investigationLimits';
  import { profileOptionLabel, toolCallingRemedy, toolCallingState } from '../lib/toolCalling';
  import {
    ApiError,
    deleteConversation,
    listAsks,
    listCollections,
    listInvestigations,
    listLlmProfilePrices,
    readSource,
    retryDocuments,
    searchCollection,
    SOURCE_PAGE_CHARS,
    type AskEvidence,
    type AskHistoryEntry,
    type Collection,
    type InvestigateEvidence,
    type InvestigationSummary,
    type LlmProfilePrice,
    type RetryAdmission,
    type RetryAttempt,
    type SearchHit,
    type SearchFilters,
    type SearchMode,
    type SourceContentResponse,
  } from '../lib/api';

  let collections: Collection[] = [];
  let selectedCollectionId = '';
  /** The collection the Admin Collections tab manages; independent of the workspace selection. */
  let managedCollectionId = '';
  let loadingCollections = true;
  /**
   * Whether the collection list has ever been read. A reload after the first one must not put the
   * workspace back into its loading state: the Admin Collections panel is only mounted while collections
   * are loaded, so a reload would unmount the panel and lose the collection it manages, its settings draft
   * and the result it was showing.
   */
  let collectionsLoaded = false;
  let searching = false;
  let query = '';
  let mode: SearchMode = 'HYBRID';
  let activeMode: 'SEARCH' | 'ASK' | 'INVESTIGATE' | 'ADMIN' = 'SEARCH';
  let adminTab: 'COLLECTIONS' | 'LLM' | 'OCR' = 'COLLECTIONS';
  let mediaType = '';
  let pathContains = '';
  let textContains = '';
  let importedFrom = '';
  let importedUntil = '';
  let statusFilter = '';
  let ocrOnly = false;
  let askProfile = '';
  let investigateProfile = '';
  let askProfiles: LlmProfilePrice[] = [];
  let investigateProfiles: LlmProfilePrice[] = [];
  let askProfileStatus = 'Loading profiles…';
  let investigateProfileStatus = 'Loading profiles…';
  let hits: SearchHit[] = [];
  let hasSearched = false;
  let collectionError: string | null = null;
  let searchError: string | null = null;
  let searchGeneration = 0;
  /**
   * How long the query field stays quiet before the workspace searches it live. Short enough that
   * results follow the typing, long enough that a word typed at reading speed is one search, not one
   * per keystroke.
   */
  const SEARCH_DEBOUNCE_MS = 250;
  /** The pending live search, cancelled whenever the query, mode or collection changes again. */
  let searchTimer: ReturnType<typeof setTimeout> | null = null;
  let selectedHit: SearchHit | null = null;
  let source: SourceContentResponse | null = null;
  let sourceText = '';
  /**
   * The excerpt a saved citation or ledger entry kept, shown instead of any fetched text when the evidence
   * names no revision. Null for everything that is read from the server.
   */
  let savedExcerpt: string | null = null;
  let loadingSource = false;
  let sourceError: string | null = null;
  let sourceGeneration = 0;
  /**
   * The collection the open viewer reads from. It is the collection of the hit that opened it, not the
   * workspace selection: Admin can open a document in a collection the workspace is not showing.
   */
  let sourceCollectionId = '';
  let sourceSheet: HTMLElement | null = null;
  let sourceOpener: HTMLElement | null = null;
  let investigateConversations: InvestigationSummary[] = [];
  let investigateConversationId: string | null = null;
  let loadingInvestigationList = true;
  let investigateListGeneration = 0;
  let askEntries: AskHistoryEntry[] = [];
  let askConversationId: string | null = null;
  let loadingAskHistory = true;
  let askListGeneration = 0;
  /** An Investigate turn is live; the history column waits until its done event before changing it. */
  let investigateWorking = false;
  /** The per-question Investigate limits, restored from this browser and applied to the next turn. */
  let investigateLimits: InvestigationLimitsInput = { ...loadInvestigationLimits() };
  let limitErrors: Partial<Record<InvestigationLimitKey, string>> = {};
  // One field-associated message per out-of-contract value; the panel refuses to send while any exists.
  $: limitErrors = investigationLimitErrors(investigateLimits);
  // Only a complete, in-range set is stored, so a half-typed value leaves the last good preference.
  $: {
    const storedLimits = validInvestigationLimits(investigateLimits);
    if (storedLimits !== null) saveInvestigationLimits(storedLimits);
  }
  /** A row delete that failed; shows the server's reason without any conversation content. */
  let deleteError: string | null = null;

  /** The open conversation is remembered per collection and per view in browser storage. */
  function historyStorageKey(view: 'ask' | 'investigate', collectionId: string): string {
    return `infoscry-history:${view}:${collectionId}`;
  }

  function readStoredConversation(view: 'ask' | 'investigate', collectionId: string): string | null {
    try { return window.localStorage.getItem(historyStorageKey(view, collectionId)); }
    catch { return null; }
  }

  function storeConversationSelection(view: 'ask' | 'investigate', id: string | null): void {
    const key = historyStorageKey(view, selectedCollectionId);
    try {
      if (id === null) window.localStorage.removeItem(key);
      else window.localStorage.setItem(key, id);
    } catch { /* Browser storage is optional; the current selection still works in memory. */ }
  }

  function storeInvestigateSelection(): void {
    storeConversationSelection('investigate', investigateConversationId);
  }

  function storeAskSelection(): void {
    storeConversationSelection('ask', askConversationId);
  }

  async function readInvestigateConversations(collectionId: string): Promise<InvestigationSummary[]> {
    try { return await listInvestigations(collectionId); }
    catch { return []; } // A list read that fails stays silent, as the panels behave today.
  }

  async function refreshInvestigateConversations(collectionId: string): Promise<void> {
    if (collectionId !== selectedCollectionId) return;
    const generation = ++investigateListGeneration;
    const conversations = await readInvestigateConversations(collectionId);
    if (generation !== investigateListGeneration || collectionId !== selectedCollectionId) return;
    investigateConversations = conversations;
    loadingInvestigationList = false;
  }

  /**
   * Load the selected collection's Investigate conversations and its remembered open one. With
   * nothing remembered, or an id the collection no longer holds, the view starts empty.
   */
  async function loadInvestigate(): Promise<void> {
    const generation = ++investigateListGeneration;
    const collectionId = selectedCollectionId;
    if (collectionId === '') {
      investigateConversations = [];
      investigateConversationId = null;
      loadingInvestigationList = false;
      return;
    }
    loadingInvestigationList = true;
    investigateConversationId = null;
    const conversations = await readInvestigateConversations(collectionId);
    if (generation !== investigateListGeneration || collectionId !== selectedCollectionId) return;
    investigateConversations = conversations;
    const saved = readStoredConversation('investigate', collectionId);
    investigateConversationId = saved !== null && conversations.some((item) => item.id === saved)
      ? saved
      : null;
    loadingInvestigationList = false;
  }

  /** Restore all three settings to the defaults; the reactive store writes them as the preference. */
  function resetInvestigateLimits(): void {
    investigateLimits = { ...DEFAULT_INVESTIGATION_LIMITS };
  }

  function openInvestigationConversation(id: string): void {
    investigateConversationId = id;
    storeInvestigateSelection();
  }

  function newInvestigationConversation(): void {
    investigateConversationId = null;
    storeInvestigateSelection();
  }

  /** A fresh conversation just started streaming; persist its id and show its row. */
  function handleInvestigationStarted(id: string): void {
    investigateConversationId = id;
    storeInvestigateSelection();
    void refreshInvestigateConversations(selectedCollectionId);
  }

  /** The stream finished, so the server has written its title; refresh that collection's list. */
  function handleInvestigationFinished(collectionId: string): void {
    void refreshInvestigateConversations(collectionId);
  }

  /** An Investigate turn started (or its done event arrived); the column lock follows it. */
  function handleInvestigateWorking(working: boolean): void {
    investigateWorking = working;
  }

  async function readAskHistory(collectionId: string): Promise<AskHistoryEntry[]> {
    try { return await listAsks(collectionId); }
    catch { return []; } // A list read that fails stays silent, as the panels behave today.
  }

  async function refreshAskHistory(collectionId: string): Promise<void> {
    if (collectionId !== selectedCollectionId) return;
    const generation = ++askListGeneration;
    const conversations = await readAskHistory(collectionId);
    if (generation !== askListGeneration || collectionId !== selectedCollectionId) return;
    askEntries = conversations;
    loadingAskHistory = false;
  }

  /**
   * Load the selected collection's stored Ask answers and the one the reader last had open. With
   * nothing remembered, or an id the collection no longer holds, the view starts empty.
   */
  async function loadAsk(): Promise<void> {
    const generation = ++askListGeneration;
    const collectionId = selectedCollectionId;
    if (collectionId === '') {
      askEntries = [];
      askConversationId = null;
      loadingAskHistory = false;
      return;
    }
    loadingAskHistory = true;
    askConversationId = null;
    const conversations = await readAskHistory(collectionId);
    if (generation !== askListGeneration || collectionId !== selectedCollectionId) return;
    askEntries = conversations;
    const saved = readStoredConversation('ask', collectionId);
    askConversationId = saved !== null && conversations.some((item) => item.id === saved)
      ? saved
      : null;
    loadingAskHistory = false;
  }

  function openAskConversation(id: string): void {
    askConversationId = id;
    storeAskSelection();
  }

  function newAskConversation(): void {
    askConversationId = null;
    storeAskSelection();
  }

  /** The completion event named the conversation the server just stored; select and mark its row. */
  function handleAskStored(id: string): void {
    // An initial list response started before this Ask cannot contain the new row and must not
    // replace the page-owned selection when it eventually arrives.
    askListGeneration += 1;
    askConversationId = id;
    storeAskSelection();
  }

  /** A new Ask began, so no previously selected row may stay marked under an answer never stored. */
  function handleAskStarted(): void {
    askConversationId = null;
    storeAskSelection();
  }

  /** The stream closed, so the server has written its title; refresh that collection's list. */
  function handleAskFinished(collectionId: string): void {
    void refreshAskHistory(collectionId);
  }

  /**
   * Delete one stored Ask answer after the platform's native confirmation; the list refreshes from
   * the server on success, and the request is sent only when the reader confirms.
   */
  async function deleteAskConversation(id: string): Promise<void> {
    const entry = askEntries.find((item) => item.id === id);
    if (entry === undefined) return;
    if (!window.confirm(`Delete conversation "${entry.title}"? This cannot be undone.`)) return;
    deleteError = null;
    try {
      await deleteConversation(selectedCollectionId, id);
      if (askConversationId === id) {
        askConversationId = null;
        storeAskSelection();
      }
      await refreshAskHistory(selectedCollectionId);
    } catch (failure) {
      deleteError = describe(failure);
    }
  }

  /**
   * Delete one Investigate conversation after the platform's native confirmation, exactly like an
   * Ask answer: the request is sent only when confirmed, and the list refreshes on success.
   */
  async function deleteInvestigateConversation(id: string): Promise<void> {
    const entry = investigateConversations.find((item) => item.id === id);
    if (entry === undefined) return;
    if (!window.confirm(`Delete conversation "${entry.title}"? This cannot be undone.`)) return;
    deleteError = null;
    try {
      await deleteConversation(selectedCollectionId, id);
      if (investigateConversationId === id) {
        investigateConversationId = null;
        storeInvestigateSelection();
      }
      await refreshInvestigateConversations(selectedCollectionId);
    } catch (failure) {
      deleteError = describe(failure);
    }
  }

  async function refresh(): Promise<void> {
    loadingCollections = !collectionsLoaded;
    try {
      collections = await listCollections();
      collectionsLoaded = true;
      if (!collections.some((collection) => collection.id === selectedCollectionId)) {
        // The selection is gone from the archive, so the workspace moves to the first collection left.
        // Results bound to the collection that vanished are unreadable and must not survive the move.
        if (selectedCollectionId !== '') invalidateResults();
        selectedCollectionId = collections[0]?.id ?? '';
      }
      collectionError = null;
    } catch (failure) {
      collectionError = describe(failure);
    } finally {
      loadingCollections = false;
    }
    await loadInvestigate();
    await loadAsk();
  }

  /** Runs the query now rather than waiting for the live debounce: Enter and the Search button. */
  async function search(event: SubmitEvent): Promise<void> {
    event.preventDefault();
    cancelPendingSearch();
    await runSearch();
  }

  /**
   * Runs one search for the query text as it is now. The caller owns when this happens, so live typing,
   * a submit and a mode or collection change all reach the server the same way. A response whose
   * generation is no longer current is dropped, which is what keeps fast typing from landing an older
   * query's results over a newer one's.
   */
  async function runSearch(): Promise<void> {
    const text = query.trim();
    if (text === '' || selectedCollectionId === '') return;

    const generation = ++searchGeneration;
    clearSelectedSource();
    searching = true;
    hasSearched = true;
    searchError = null;
    hits = [];
    try {
      const filters: SearchFilters = {
        mediaType, path: pathContains, text: textContains,
        from: importedFrom, until: importedUntil, status: statusFilter, ocrOnly,
      };
      const response = await searchCollection(selectedCollectionId, text, mode, filters);
      if (generation === searchGeneration) hits = response.hits;
    } catch (failure) {
      if (generation === searchGeneration) searchError = describe(failure);
    } finally {
      if (generation === searchGeneration) searching = false;
    }
  }

  /** A keystroke retires any pending or in-flight search and arms the next live one. */
  function handleQueryInput(): void {
    cancelPendingSearch();
    invalidateResults();
    if (query.trim() === '' || selectedCollectionId === '') return;
    searchTimer = setTimeout(() => {
      searchTimer = null;
      void runSearch();
    }, SEARCH_DEBOUNCE_MS);
  }

  /** A filter change retires the old generation and immediately searches the new criteria. */
  function handleFilterChange(debounceText: boolean): void {
    invalidateSearch();
    if (query.trim() === '' || selectedCollectionId === '') return;
    if (debounceText) {
      searchTimer = setTimeout(() => {
        searchTimer = null;
        void runSearch();
      }, SEARCH_DEBOUNCE_MS);
    } else {
      void runSearch();
    }
  }

  /** SearchHit.highlighted is generated by the server from escaped source segments. */
  function escapeSearchText(value: string): string {
    return value.replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;');
  }

  function cancelPendingSearch(): void {
    if (searchTimer !== null) {
      clearTimeout(searchTimer);
      searchTimer = null;
    }
  }

  /**
   * Opens saved evidence: at the revision it names when it has one; otherwise from the excerpt it saved,
   * labelled "revision unknown", and never from the live unit, whose text may be a later reading than the
   * one the answer was given. Evidence with neither (a citation just streamed, which saved nothing yet and
   * read the live unit) opens the live unit as before.
   */
  function openEvidence(evidence: AskEvidence, hit: SearchHit): Promise<void> {
    const saved = !evidence.revisionId && typeof evidence.excerpt === 'string' ? evidence.excerpt : null;
    return openSource({ ...hit, revisionId: evidence.revisionId }, null, saved);
  }

  async function openSource(hit: SearchHit, opener: HTMLElement | null = null, saved: string | null = null): Promise<void> {
    const generation = ++sourceGeneration;
    sourceOpener = opener ?? (document.activeElement instanceof HTMLElement ? document.activeElement : null);
    selectedHit = hit;
    sourceCollectionId = hit.collectionId;
    source = null;
    sourceText = '';
    savedExcerpt = saved;
    sourceError = null;
    if (saved !== null) {
      loadingSource = false;
      await tick();
      sourceSheet?.focus();
      return;
    }
    loadingSource = true;
    try {
      const pagePromise = readSource(sourceCollectionId, hit.unitId, 0, SOURCE_PAGE_CHARS, hit.revisionId);
      await tick();
      sourceSheet?.focus();
      const page = await pagePromise;
      if (generation !== sourceGeneration || hit.collectionId !== sourceCollectionId) return;
      source = page;
      sourceText = page.text;
    } catch (failure) {
      if (generation === sourceGeneration) sourceError = describe(failure);
    } finally {
      if (generation === sourceGeneration) loadingSource = false;
    }
  }

  async function loadMoreSource(): Promise<void> {
    if (selectedHit === null || source === null || loadingSource || !source.truncated) return;
    const generation = sourceGeneration;
    const hit = selectedHit;
    loadingSource = true;
    sourceError = null;
    try {
      const page = await readSource(
        sourceCollectionId,
        hit.unitId,
        source.offset + source.text.length,
        SOURCE_PAGE_CHARS,
        hit.revisionId,
      );
      if (generation !== sourceGeneration || hit.collectionId !== sourceCollectionId) return;
      source = page;
      sourceText += page.text;
    } catch (failure) {
      if (generation === sourceGeneration) sourceError = describe(failure);
    } finally {
      if (generation === sourceGeneration) loadingSource = false;
    }
  }

  /**
   * The results a search drew, dropped without touching the viewer. A collection that left the archive
   * has no readable content, so its rows have to go before the workspace selector above them moves to
   * whatever collection is listed first: admitting a collection deletion refreshes this list at once,
   * while the deletion's own completion event only arrives later, once the server reports it done.
   */
  function invalidateResults(): void {
    searchGeneration += 1;
    searching = false;
    hits = [];
    hasSearched = false;
    searchError = null;
  }

  function invalidateSearch(): void {
    cancelPendingSearch();
    invalidateResults();
    clearSelectedSource();
  }

  function changeCollection(): void {
    invalidateSearch();
    void loadInvestigate();
    void loadAsk();
    // The query is live, so a different collection draws new results for it instead of waiting for
    // another submit that may never come.
    if (query.trim() !== '') void runSearch();
  }

  function changeMode(): void {
    invalidateSearch();
    if (query.trim() !== '') void runSearch();
  }

  function selectMode(modeName: 'SEARCH' | 'ASK' | 'INVESTIGATE' | 'ADMIN'): void {
    if (activeMode !== modeName) closeSourceSheetWithoutFocus();
    // Admin may have measured a profile's tool calling; the Investigate select must show that, not the start-up read.
    if (modeName === 'INVESTIGATE' && activeMode === 'ADMIN') void refreshInvestigateProfiles();
    activeMode = modeName;
  }

  async function refreshInvestigateProfiles(): Promise<void> {
    try {
      investigateProfiles = await listLlmProfilePrices();
    } catch {
      // The list already on screen stays; a refused read must not clear the select.
    }
  }

  $: selectedInvestigateProfile = investigateProfiles.find((profile) => profile.name === investigateProfile) ?? null;
  $: investigateRemedy = selectedInvestigateProfile === null ? null : toolCallingRemedy(toolCallingState(selectedInvestigateProfile));

  function handleTabKeydown(event: KeyboardEvent): void {
    const tabs: ('SEARCH' | 'ASK' | 'INVESTIGATE' | 'ADMIN')[] = ['SEARCH', 'ASK', 'INVESTIGATE', 'ADMIN'];
    const current = tabs.indexOf(activeMode);
    let next = current;
    if (event.key === 'ArrowRight') next = (current + 1) % tabs.length;
    else if (event.key === 'ArrowLeft') next = (current + tabs.length - 1) % tabs.length;
    else if (event.key === 'Home') next = 0;
    else if (event.key === 'End') next = tabs.length - 1;
    else return;
    event.preventDefault();
    selectMode(tabs[next]);
    document.getElementById(`tab-${activeMode.toLowerCase()}`)?.focus();
  }

  function handleAdminTabKeydown(event: KeyboardEvent): void {
    const tabs: ('COLLECTIONS' | 'LLM' | 'OCR')[] = ['COLLECTIONS', 'LLM', 'OCR'];
    const current = tabs.indexOf(adminTab);
    let next = current;
    if (event.key === 'ArrowRight') next = (current + 1) % tabs.length;
    else if (event.key === 'ArrowLeft') next = (current + tabs.length - 1) % tabs.length;
    else if (event.key === 'Home') next = 0;
    else if (event.key === 'End') next = tabs.length - 1;
    else return;
    event.preventDefault();
    adminTab = tabs[next];
    document.getElementById(`admin-tab-${adminTab.toLowerCase()}`)?.focus();
  }

  function clearSelectedSource(): void {
    sourceGeneration += 1;
    selectedHit = null;
    source = null;
    sourceText = '';
    savedExcerpt = null;
    loadingSource = false;
    sourceError = null;
    sourceOpener = null;
    sourceCollectionId = '';
  }

  function handleSourceKeydown(event: KeyboardEvent): void {
    if (event.key === 'Escape') {
      event.preventDefault();
      closeSourceSheet();
    } else if (event.key === 'Tab') {
      containSourceFocus(event);
    }
  }

  /**
   * Keep keyboard focus inside the sheet while it is open. The sheet is announced as modal, so focus
   * must not reach the controls behind the backdrop; the sidebar stays visible, but keyboard and
   * screen-reader navigation wait until the sheet is closed.
   */
  function containSourceFocus(event: KeyboardEvent): void {
    if (sourceSheet === null) return;
    const focusable = Array.from(sourceSheet.querySelectorAll<HTMLElement>('a[href], button:not([disabled]), [tabindex]:not([tabindex="-1"])'));
    if (focusable.length === 0) return;
    const first = focusable[0];
    const last = focusable[focusable.length - 1];
    const active = document.activeElement;
    if (active === first && event.shiftKey) {
      event.preventDefault();
      last.focus();
    } else if (active === last && !event.shiftKey) {
      event.preventDefault();
      first.focus();
    } else if (active === sourceSheet || !sourceSheet.contains(active)) {
      event.preventDefault();
      (event.shiftKey ? last : first).focus();
    }
  }

  function closeSourceSheet(): void {
    const opener = sourceOpener;
    clearSelectedSource();
    if (opener instanceof HTMLElement && opener.isConnected) opener.focus();
  }

  /**
   * Close the sheet without restoring focus to the opening citation: the reader is navigating, so
   * the workspace tab they chose keeps the focus instead.
   */
  function closeSourceSheetWithoutFocus(): void {
    clearSelectedSource();
  }

  function originalHref(hit: SearchHit): string {
    return `/api/collections/${encodeURIComponent(hit.collectionId)}/documents/${encodeURIComponent(hit.documentId)}/original`;
  }

  function openInvestigationSource(evidence: InvestigateEvidence): Promise<void> {
    return openEvidence(evidence, evidenceHit(evidence));
  }

  /** The viewer's own row for one piece of saved evidence; its revision and excerpt are applied by [openEvidence]. */
  function evidenceHit(evidence: AskEvidence): SearchHit {
    return {
      collectionId: selectedCollectionId,
      documentId: evidence.documentId,
      title: evidence.locatorLabel,
      unitId: evidence.unitId,
      chunkOrdinal: 0,
      text: '',
      highlighted: null,
      locator: evidence.locator,
      locatorLabel: evidence.locatorLabel,
      matchedBy: [],
    };
  }

  /**
   * Open one managed document from Admin at the first unit its detail named, in the collection Admin has
   * selected there. The panel supplies the two ids; the viewer and its state stay this page's.
   */
  function openManagedDocument(documentId: string, sourceId: string): void {
    void openSource({
      collectionId: managedCollectionId,
      documentId,
      title: '',
      unitId: sourceId,
      chunkOrdinal: 0,
      text: '',
      highlighted: null,
      locator: null,
      locatorLabel: 'Extracted content',
      matchedBy: [],
    });
  }

  function describe(failure: unknown): string {
    if (failure instanceof ApiError) return failure.message;
    return failure instanceof Error ? failure.message : 'Something went wrong.';
  }

  /**
   * Admin collection changes refresh the workspace's collection selector; the workspace's own
   * selected collection is untouched, so an active Ask or Investigate conversation keeps its collection.
   */
  async function handleManagedCollectionsChanged(selectedId?: string): Promise<void> {
    await refresh();
    if (selectedId !== undefined) managedCollectionId = selectedId;
  }

  /** Selecting a collection for management never changes the workspace collection. */
  function selectManagedCollection(id: string): void {
    managedCollectionId = id;
  }

  /**
   * A document Admin deleted is gone from the archive, so the viewer open on it must close rather than
   * keep showing content that no longer exists. Saved source links report unavailable content on their
   * own, because the source route answers 404 once the unit is gone.
   */
  function handleManagedDocumentDeleted(collectionId: string, documentId: string): void {
    if (sourceCollectionId !== collectionId || selectedHit?.documentId !== documentId) return;
    closeSourceSheetWithoutFocus();
  }

  /**
   * Admin asked for one document to be read again from the copy InfoScry holds.
   *
   * The page owns the managed collection, so it makes the request; the panel is handed back a result it can
   * show. The server decides per document, so a refusal is a normal answer with its own sentence rather than
   * a failed request, and the retry keeps the document's identity either way.
   */
  async function retryManagedDocument(documentId: string): Promise<RetryAttempt> {
    const admission = await retryDocuments(managedCollectionId, { documentIds: [documentId] });
    const jobId = admission.acceptedJobIds[0];
    if (jobId !== undefined) return { accepted: true, jobId };
    return {
      accepted: false,
      reason: admission.rejected[0]?.reason ?? 'The server did not accept this retry.',
    };
  }

  /**
   * Admin asked for every eligible document of the managed collection to be read again.
   *
   * The request asks for `allEligible` rather than listing the ids the table happens to show: which documents
   * that is, across every page, is the server's decision, and its answer names the attempts it queued and the
   * documents it refused. The panel shows that answer; the work itself belongs to the server, so a navigation,
   * a disconnect or a restart does not lose it.
   */
  async function retryAllManagedDocuments(): Promise<RetryAdmission> {
    return retryDocuments(managedCollectionId, { allEligible: true });
  }

  /**
   * A collection Admin deleted is gone from the archive, so nothing bound to it may stay: the viewer
   * reading its documents, the workspace results drawn from it and the Admin selection itself. The
   * lists and the Ask/Investigate histories are then re-read from the server.
   */
  async function handleManagedCollectionDeleted(collectionId: string): Promise<void> {
    if (sourceCollectionId === collectionId) closeSourceSheet();
    if (managedCollectionId === collectionId) managedCollectionId = '';
    const wasWorkspaceSelection = selectedCollectionId === collectionId;
    if (wasWorkspaceSelection) invalidateSearch();
    await refresh();
    if (wasWorkspaceSelection) {
      await loadInvestigate();
      await loadAsk();
    }
  }

  onMount(() => {
    refresh();
  });

  // A timer that outlives the page would fire a search against a torn-down workspace.
  onDestroy(cancelPendingSearch);
</script>

<div class="app-shell" class:source-open={selectedHit !== null}>
  <aside class="sidebar" aria-label="Workspace controls">
    <a class="brand" href="/" aria-label="InfoScry home"><span class="brand-mark">I</span><span>InfoScry</span></a>
    <div class="sidebar-block collection-block">
      <label for="collection">Collection</label>
      {#if loadingCollections}
        <p role="status">Loading collections…</p>
      {:else if collectionError !== null && collections.length === 0}
        <p role="alert">{collectionError}</p>
      {:else if collections.length === 0}
        <p role="status">No collections yet.</p>
      {:else}
        <select id="collection" bind:value={selectedCollectionId} onchange={changeCollection}>
          {#each collections as collection (collection.id)}
            <option value={collection.id}>{collection.name}</option>
          {/each}
        </select>
      {/if}
    </div>

    {#if !loadingCollections}
      <nav class="mode-nav" aria-label="Workspace mode">
        <span class="eyebrow">WORKSPACE</span>
        <div role="tablist" aria-label="Workspace mode" tabindex="-1" onkeydown={handleTabKeydown}>
          <button id="tab-search" role="tab" aria-selected={activeMode === 'SEARCH'} aria-controls="panel-search" tabindex={activeMode === 'SEARCH' ? 0 : -1} onclick={() => selectMode('SEARCH')}><span aria-hidden="true">⌕</span> Search</button>
          <button id="tab-ask" role="tab" aria-selected={activeMode === 'ASK'} aria-controls="panel-ask" tabindex={activeMode === 'ASK' ? 0 : -1} onclick={() => selectMode('ASK')}><span aria-hidden="true">✦</span> Ask</button>
          <button id="tab-investigate" role="tab" aria-selected={activeMode === 'INVESTIGATE'} aria-controls="panel-investigate" tabindex={activeMode === 'INVESTIGATE' ? 0 : -1} onclick={() => selectMode('INVESTIGATE')}><span aria-hidden="true">◎</span> Investigate</button>
          <button id="tab-admin" role="tab" aria-selected={activeMode === 'ADMIN'} aria-controls="panel-admin" tabindex={activeMode === 'ADMIN' ? 0 : -1} onclick={() => selectMode('ADMIN')}><span aria-hidden="true">⚙</span> Admin</button>
        </div>
      </nav>

      {#if activeMode === 'SEARCH'}
        <div class="sidebar-block search-settings">
          <span class="eyebrow">SEARCH SETTINGS</span>
          <label for="mode">Search mode</label>
          <select id="mode" bind:value={mode} onchange={changeMode}>
            <option value="KEYWORD">Keyword</option>
            <option value="SEMANTIC">Semantic</option>
            <option value="HYBRID">Hybrid</option>
          </select>
          <details>
            <summary>Advanced search filters</summary>
            <div class="filter-fields">
              <label for="media-type">Media type</label>
              <select id="media-type" bind:value={mediaType} onchange={() => handleFilterChange(false)}>
                <option value="">Any type</option>
                <option value="application/pdf">PDF</option>
                <option value="text/plain">Plain text</option>
                <option value="text/markdown">Markdown</option>
                <option value="text/html">HTML</option>
                <option value="text/csv">CSV</option>
                <option value="application/vnd.openxmlformats-officedocument.wordprocessingml.document">Word document</option>
                <option value="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet">Excel spreadsheet</option>
                <option value="application/vnd.openxmlformats-officedocument.presentationml.presentation">PowerPoint presentation</option>
              </select>
              <label for="path-contains">Path contains</label>
              <input id="path-contains" aria-label="Path contains" bind:value={pathContains} oninput={() => handleFilterChange(true)} />
              <label for="text-contains">Title, author or language</label>
              <input id="text-contains" bind:value={textContains} oninput={() => handleFilterChange(true)} />
              <label for="imported-from">Imported from</label>
              <input id="imported-from" type="date" bind:value={importedFrom} onchange={() => handleFilterChange(false)} />
              <label for="imported-until">Imported until</label>
              <input id="imported-until" type="date" bind:value={importedUntil} onchange={() => handleFilterChange(false)} />
              <label for="status-filter">Document status</label>
              <select id="status-filter" bind:value={statusFilter} onchange={() => handleFilterChange(false)}>
                <option value="">Any status</option>
                <option value="COMPLETE">Complete</option>
                <option value="COMPLETE_WITH_WARNINGS">Complete with warnings</option>
                <option value="NEEDS_REVIEW">Needs review</option>
                <option value="FAILED">Failed</option>
                <option value="NEEDS_TOOL">Needs tool</option>
              </select>
              <label class="check-label"><input type="checkbox" bind:checked={ocrOnly} onchange={() => handleFilterChange(false)} /> OCR only</label>
            </div>
          </details>
        </div>
      {:else if activeMode === 'ADMIN'}
        <div class="sidebar-note">
          <span class="eyebrow">ADMINISTRATION</span>
          {#if adminTab === 'COLLECTIONS'}
            <p>Create or select a collection and add local files or folders to it. Choosing paths opens a native dialog on this machine.</p>
          {:else if adminTab === 'LLM'}
            <p>Configure the LLM profiles Ask and Investigate can use. API keys stay in environment variables.</p>
          {:else}
            <p>Configure the image-reading profiles OCR can use. They are separate from Ask and Investigate. API keys stay in environment variables.</p>
          {/if}
        </div>
      {:else}
        <div class="sidebar-note">
          <span class="eyebrow">{activeMode === 'ASK' ? 'GROUNDED ANSWERS' : 'RESEARCH MODE'}</span>
          <label for="llm-profile">LLM profile</label>
          {#if activeMode === 'ASK'}
            <select id="llm-profile" bind:value={askProfile} disabled={askProfiles.length === 0}>
              {#if askProfiles.length === 0}<option value="">{askProfileStatus}</option>{/if}
              {#each askProfiles as profile (profile.name)}<option value={profile.name}>{profile.name}</option>{/each}
            </select>
            {#if askProfiles.length === 0}<p>{askProfileStatus}</p>{/if}
            <p>Ask retrieves evidence for one answer. Search filters apply to Search only.</p>
          {:else}
            <select id="llm-profile" bind:value={investigateProfile} disabled={investigateProfiles.length === 0}>
              {#if investigateProfiles.length === 0}<option value="">{investigateProfileStatus}</option>{/if}
              {#each investigateProfiles as profile (profile.name)}<option value={profile.name}>{profileOptionLabel(profile.name, toolCallingState(profile))}</option>{/each}
            </select>
            {#if investigateProfiles.length === 0}<p>{investigateProfileStatus}</p>{/if}
            {#if investigateRemedy !== null}<p class="profile-remedy" role="note">{investigateRemedy}</p>{/if}
            <p>Investigate can search the selected collection as needed. Search filters apply to Search only.</p>
            <fieldset class="limit-settings">
              <legend>Question limits</legend>
              <p>A round may contain several tool calls. Time includes preparing the final answer.</p>
              {#each INVESTIGATION_LIMIT_FIELDS as field (field.key)}
                <label for={`limit-${field.key}`}>{field.label}</label>
                <input
                  id={`limit-${field.key}`}
                  type="number"
                  min={field.min}
                  max={field.max}
                  step="1"
                  disabled={investigateWorking}
                  bind:value={investigateLimits[field.key]}
                  aria-invalid={limitErrors[field.key] !== undefined}
                  aria-describedby={limitErrors[field.key] === undefined ? undefined : `limit-error-${field.key}`}
                />
                {#if limitErrors[field.key] !== undefined}
                  <p class="limit-error" id={`limit-error-${field.key}`} role="alert">{limitErrors[field.key]}</p>
                {/if}
              {/each}
              <button type="button" disabled={investigateWorking} onclick={resetInvestigateLimits}>Reset defaults</button>
            </fieldset>
          {/if}
        </div>
      {/if}
    {/if}
    <div class="sidebar-footer">Local archive <span class="status-dot"></span></div>
  </aside>

  <main class="workspace">
    {#if activeMode !== 'ADMIN'}
      <header class="workspace-header">
        <div><span class="eyebrow">{activeMode === 'SEARCH' ? 'DISCOVER' : activeMode === 'ASK' ? 'ANSWER' : 'EXPLORE'}</span><h1>{activeMode === 'INVESTIGATE' ? 'Investigate' : activeMode === 'ASK' ? 'Ask your archive' : 'Search your archive'}</h1></div>
      </header>
    {/if}

  {#if !loadingCollections}
    <div class="content-layout" class:with-history={activeMode === 'INVESTIGATE' || activeMode === 'ASK'}>
    <div class="mode-panels">
      <div id="panel-search" role="tabpanel" aria-labelledby="tab-search" hidden={activeMode !== 'SEARCH'}>
      <div class="search-input-row">
        <form onsubmit={search}>
          <label class="visually-hidden" for="query">Search query</label>
          <input id="query" name="query" bind:value={query} oninput={handleQueryInput} autocomplete="off" placeholder="Search documents, names, and ideas…" required />
          <button class="primary" type="submit" disabled={query.trim() === ''}>Search</button>
        </form>
      </div>
      {#if searching}
        <p role="status">Searching…</p>
      {:else if searchError !== null}
        <p role="alert">{searchError}</p>
      {:else if hasSearched && hits.length === 0}
        <p role="status">No results found.</p>
      {:else if hits.length > 0}
        <!-- The list itself is not a live region: announcing it would read every hit aloud on each
             keystroke. The count is what a screen reader needs to know that results changed. -->
        <p class="visually-hidden" role="status">{hits.length} {hits.length === 1 ? 'result' : 'results'}.</p>
        <ol class="results" aria-label="Search results">
          {#each hits as hit (`${hit.unitId}-${hit.chunkOrdinal}`)}
            <li>
              <button
                type="button"
                class="result"
                aria-pressed={selectedHit?.unitId === hit.unitId}
                onclick={(event) => openSource(hit, event.currentTarget as HTMLElement)}
              >
                <span class="result-title">{hit.title || hit.locatorLabel}</span>
                <span class="meta">{hit.locatorLabel}</span>
                <span class="meta">Matched by {hit.matchedBy.join(', ').toLowerCase()}</span>
                <span>{@html hit.highlighted ?? escapeSearchText(hit.text)}</span>
              </button>
            </li>
          {/each}
        </ol>
      {/if}
      </div>

      <div id="panel-admin" role="tabpanel" aria-labelledby="tab-admin" hidden={activeMode !== 'ADMIN'}>
        <div class="admin-tabs" role="tablist" aria-label="Administration section" tabindex="-1" onkeydown={handleAdminTabKeydown}>
          <button
            id="admin-tab-collections"
            role="tab"
            aria-selected={adminTab === 'COLLECTIONS'}
            aria-controls="admin-panel-collections"
            tabindex={adminTab === 'COLLECTIONS' ? 0 : -1}
            onclick={() => (adminTab = 'COLLECTIONS')}
          >Collections</button>
          <button
            id="admin-tab-llm"
            role="tab"
            aria-selected={adminTab === 'LLM'}
            aria-controls="admin-panel-llm"
            tabindex={adminTab === 'LLM' ? 0 : -1}
            onclick={() => (adminTab = 'LLM')}
          >LLM profiles</button>
          <button
            id="admin-tab-ocr"
            role="tab"
            aria-selected={adminTab === 'OCR'}
            aria-controls="admin-panel-ocr"
            tabindex={adminTab === 'OCR' ? 0 : -1}
            onclick={() => (adminTab = 'OCR')}
          >OCR profiles</button>
        </div>
        <div class="admin-panels">
          <div id="admin-panel-collections" role="tabpanel" aria-labelledby="admin-tab-collections" hidden={adminTab !== 'COLLECTIONS'}>
            {#if activeMode === 'ADMIN'}
              <!--
                Mounted with the Admin view rather than kept hidden like the LLM panel: it lists the
                same collection names as the workspace selector, and a second invisible copy would
                make every text lookup for a collection name ambiguous.
              -->
              <CollectionsPanel
                collections={collections}
                selectedId={managedCollectionId}
                loading={loadingCollections}
                error={collectionError}
                onSelect={selectManagedCollection}
                onCollectionsChanged={handleManagedCollectionsChanged}
                onOpenDocument={openManagedDocument}
                onCollectionDeleted={handleManagedCollectionDeleted}
                onDocumentDeleted={handleManagedDocumentDeleted}
                onRetryDocument={retryManagedDocument}
                onRetryAllDocuments={retryAllManagedDocuments}
              />
            {/if}
          </div>
          <div id="admin-panel-llm" role="tabpanel" aria-labelledby="admin-tab-llm" hidden={adminTab !== 'LLM'}><LlmAdminPanel /></div>
          <div id="admin-panel-ocr" role="tabpanel" aria-labelledby="admin-tab-ocr" hidden={adminTab !== 'OCR'}>
            {#if activeMode === 'ADMIN' && adminTab === 'OCR'}<OcrProfilesPanel />{/if}
          </div>
        </div>
      </div>

    {#key selectedCollectionId}
      <div id="panel-ask" role="tabpanel" aria-labelledby="tab-ask" hidden={activeMode !== 'ASK'}>{#if deleteError !== null}<p role="alert">{deleteError}</p>{/if}<AskPanel collectionId={selectedCollectionId} bind:askProfile bind:availableProfiles={askProfiles} bind:profileStatus={askProfileStatus} bind:conversationId={askConversationId} entries={askEntries} onAnswerStored={handleAskStored} onAskStarted={handleAskStarted} onAnswerFinished={handleAskFinished} onOpenSource={(evidence) => openEvidence(evidence, evidenceHit(evidence))} /></div>
      <div id="panel-investigate" role="tabpanel" aria-labelledby="tab-investigate" hidden={activeMode !== 'INVESTIGATE'}>{#if deleteError !== null}<p role="alert">{deleteError}</p>{/if}<InvestigatePanel collectionId={selectedCollectionId} bind:conversationId={investigateConversationId} onConversationStarted={handleInvestigationStarted} onConversationFinished={handleInvestigationFinished} onWorkingChanged={handleInvestigateWorking} limits={investigateLimits} bind:profile={investigateProfile} bind:availableProfiles={investigateProfiles} bind:profileStatus={investigateProfileStatus} onOpenSource={openInvestigationSource} /></div>
    {/key}
    </div>
    {#if activeMode === 'INVESTIGATE'}
      <HistoryColumn
        label="Conversation history"
        entries={investigateConversations.map((item) => ({ id: item.id, title: item.title || item.question || 'Untitled conversation' }))}
        openId={investigateConversationId}
        loading={loadingInvestigationList}
        disabled={investigateWorking}
        onSelect={openInvestigationConversation}
        onNew={newInvestigationConversation}
        onDelete={deleteInvestigateConversation}
      />
    {:else if activeMode === 'ASK'}
      <HistoryColumn
        label="Ask history"
        entries={askEntries.map((item) => ({ id: item.id, title: item.title }))}
        openId={askConversationId}
        loading={loadingAskHistory}
        onSelect={openAskConversation}
        onNew={newAskConversation}
        onDelete={deleteAskConversation}
      />
    {/if}
    </div>

  {#if selectedHit !== null}
    <div class="source-backdrop" aria-hidden="true" onclick={closeSourceSheet}></div>
    <div
      bind:this={sourceSheet}
      class="source-sheet"
      role="dialog"
      aria-modal="true"
      aria-labelledby="source-heading"
      tabindex="-1"
      onkeydown={handleSourceKeydown}
    >
      <div class="source-heading-row"><h2 id="source-heading">Source</h2><button type="button" aria-label="Close source viewer" onclick={closeSourceSheet}>×</button></div>
      <p class="meta">{selectedHit.locatorLabel}</p>
      <p><a href={originalHref(selectedHit)}>Open original</a></p>
      {#if savedExcerpt !== null}
        <p class="meta" role="note">Revision unknown. This is the excerpt saved with the citation, not the document's current text.</p>
        <pre class="source-text">{savedExcerpt}</pre>
      {:else if loadingSource && source === null}
        <p role="status">Loading source…</p>
      {:else if sourceError !== null && source === null}
        <p role="alert">{sourceError}</p>
      {:else if source !== null}
        <pre class="source-text">{sourceText}</pre>
        {#if sourceError !== null}<p role="alert">{sourceError}</p>{/if}
        {#if source.truncated}
          <button type="button" onclick={loadMoreSource} disabled={loadingSource}>
            {loadingSource ? 'Loading…' : 'Load more'}
          </button>
        {/if}
      {/if}
    </div>
  {/if}
  {/if}
</main>
</div>

<style>
  :global(*) { box-sizing: border-box; }
  :global(html) { color-scheme: dark; background: #101214; }
  :global(body) { margin: 0; background: #101214; color: #e8e9e7; font: 15px/1.55 Inter, ui-sans-serif, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif; }
  :global(button), :global(input), :global(select), :global(textarea) { color: inherit; font: inherit; }
  :global(button:focus-visible), :global(input:focus-visible), :global(select:focus-visible), :global(textarea:focus-visible), :global(summary:focus-visible), :global(a:focus-visible) { outline: 2px solid #c4a77d; outline-offset: 2px; }
  :global(a) { color: #d9bd92; }
  :global(.citation) { color: #d9bd92 !important; }
  :global(button) { border: 1px solid #3a3e3e; border-radius: 0.46rem; background: #252929; color: #e4e6e3; padding: 0.55rem 0.85rem; cursor: pointer; }
  :global(button:hover:not(:disabled)) { border-color: #656b68; background: #2d3232; }
  :global(button:disabled) { cursor: not-allowed; opacity: 0.5; }
  :global(textarea) { background: #202324; }
  :global(.meta) { color: #929997 !important; }
  :global([role="alert"]) { color: #f0a4a0 !important; }
  :global(#panel-ask section), :global(#panel-investigate section) { margin-top: 0; }
  .app-shell { min-height: 100vh; display: grid; grid-template-columns: 258px minmax(0, 1fr); --history-column-width: 20rem; --source-column-width: clamp(28rem, 43vw, 58rem); }
  .sidebar { min-height: 100vh; display: flex; flex-direction: column; gap: 1.8rem; padding: 1.35rem 1rem 1rem; background: #181a1b; border-right: 1px solid #2a2d2e; }
  .brand { display: flex; align-items: center; gap: 0.7rem; color: #f1f0ed; text-decoration: none; font-size: 1.04rem; font-weight: 650; letter-spacing: -0.02em; padding: 0 0.3rem; }
  .brand-mark { display: grid; place-items: center; width: 1.85rem; height: 1.85rem; border-radius: 0.55rem; background: #c4a77d; color: #171817; font-family: Georgia, serif; font-size: 1.15rem; }
  .sidebar-block, .sidebar-note { display: grid; gap: 0.55rem; padding: 0 0.3rem; }
  .eyebrow { color: #858a8a; font-size: 0.68rem; font-weight: 700; letter-spacing: 0.12em; }
  label { color: #b8bcbb; font-size: 0.82rem; }
  select, input:not([type="checkbox"]), :global(textarea) { width: 100%; min-width: 0; border: 1px solid #383d3e; border-radius: 0.48rem; background: #202324; padding: 0.62rem 0.72rem; color: #e8e9e7; }
  select:hover, input:hover, :global(textarea:hover) { border-color: #515757; }
  .mode-nav { display: grid; gap: 0.7rem; }
  .mode-nav .eyebrow { padding: 0 0.65rem; }
  [role="tablist"] { display: grid; gap: 0.2rem; }
  [role="tab"] { display: flex; align-items: center; gap: 0.65rem; border: 1px solid transparent; border-radius: 0.48rem; padding: 0.62rem 0.7rem; background: transparent; color: #b5b9b8; text-align: left; cursor: pointer; }
  [role="tab"] span { width: 1.2rem; text-align: center; color: #959b99; font-size: 1.08rem; }
  [role="tab"]:hover { background: #222627; color: #f2f1ed; }
  [role="tab"][aria-selected="true"] { border-color: #373b3b; background: #252929; color: #f3e6d1; }
  [role="tab"][aria-selected="true"] span { color: #d4b789; }
  .search-settings { border-top: 1px solid #2d3131; padding-top: 1.25rem; }
  details { margin-top: 0.3rem; border-top: 1px solid #2d3131; padding-top: 0.65rem; }
  summary { color: #c4c8c6; font-size: 0.82rem; cursor: pointer; }
  .filter-fields { display: grid; gap: 0.5rem; padding-top: 0.8rem; }
  .filter-fields input, .filter-fields select { font-size: 0.82rem; padding: 0.5rem; }
  .check-label { display: flex; align-items: center; gap: 0.5rem; }
  .check-label input { accent-color: #c4a77d; }
  .sidebar-note { padding: 0.85rem; border: 1px solid #303535; border-radius: 0.55rem; background: #1d2021; }
  .sidebar-note p { margin: 0; color: #b0b5b3; font-size: 0.8rem; }
  .limit-settings { display: grid; gap: 0.45rem; margin: 0; border: 0; border-top: 1px solid #2d3131; padding: 0.85rem 0 0; }
  .limit-settings legend { padding: 0; color: #858a8a; font-size: 0.68rem; font-weight: 700; letter-spacing: 0.12em; text-transform: uppercase; }
  .limit-settings input { font-size: 0.82rem; padding: 0.5rem; }
  .sidebar-note .limit-error { color: #e08c8c; }
  .sidebar-footer { display: flex; align-items: center; gap: 0.5rem; margin-top: auto; border-top: 1px solid #2d3131; padding: 1rem 0.35rem 0; color: #89908e; font-size: 0.76rem; }
  .status-dot { width: 0.45rem; height: 0.45rem; border-radius: 50%; background: #85ad87; box-shadow: 0 0 0 3px #85ad8720; }
  .workspace { width: 100%; min-width: 0; padding: 2.1rem clamp(1.25rem, 4vw, 4.5rem); }
  .app-shell.source-open .workspace { padding-right: calc(var(--source-column-width) + clamp(1.25rem, 4vw, 4.5rem)); }
  .workspace-header { display: flex; align-items: end; justify-content: space-between; gap: 1rem; max-width: 78rem; margin: 0 auto 2rem; padding-bottom: 1.25rem; border-bottom: 1px solid #292d2d; }
  .workspace-header h1 { margin: 0.35rem 0 0; color: #f0efec; font-size: clamp(1.45rem, 2vw, 1.9rem); font-weight: 570; letter-spacing: -0.035em; }
  .content-layout { display: grid; grid-template-columns: minmax(0, 1fr); gap: 1.5rem; max-width: 78rem; margin: 0 auto; align-items: start; }
  .content-layout.with-history { padding-right: calc(var(--history-column-width) + 1.5rem); }
  .app-shell.source-open .content-layout.with-history { padding-right: 0; }
  .mode-panels { min-width: 0; }
  .mode-panels > div[role="tabpanel"] { margin: 0; min-width: 0; }
  .mode-panels [hidden] { display: none !important; }
  .admin-tabs { display: flex; gap: 0.25rem; border-bottom: 1px solid #2a2d2e; margin-bottom: 1.35rem; }
  .admin-tabs [role="tab"] { border: 0; border-bottom: 2px solid transparent; border-radius: 0; background: transparent; padding: 0.6rem 0.95rem; color: #929997; font-size: 0.88rem; margin-bottom: -1px; }
  .admin-tabs [role="tab"]:hover { color: #d5d8d6; }
  .admin-tabs [role="tab"][aria-selected="true"] { border-bottom-color: #c4a77d; color: #f3e6d1; font-weight: 600; }
  .search-input-row { margin-bottom: 1.5rem; }
  .search-input-row form { display: flex; gap: 0.55rem; }
  .search-input-row input { min-height: 2.9rem; background: #1b1e1f; border-color: #373b3b; padding-left: 1rem; }
  button { border: 1px solid #3a3e3e; border-radius: 0.46rem; background: #252929; color: #e4e6e3; padding: 0.55rem 0.85rem; cursor: pointer; }
  button:hover:not(:disabled) { border-color: #656b68; background: #2d3232; }
  button:disabled { cursor: not-allowed; opacity: 0.5; }
  button.primary { border-color: #c4a77d; background: #c4a77d; color: #1c1b18; font-weight: 650; }
  button.primary:hover:not(:disabled) { background: #d4ba94; }
  .results { list-style: none; margin: 1.3rem 0; padding: 0; border-top: 1px solid #2b3030; }
  .results li { border-bottom: 1px solid #2b3030; }
  .result { display: grid; gap: 0.45rem; width: 100%; border: 0; border-radius: 0; padding: 1rem 0.65rem; background: transparent; color: inherit; text-align: left; cursor: pointer; }
  .result:hover { background: #1c2020; }
  .result[aria-pressed="true"] { background: #242827; box-shadow: inset 2px 0 #c4a77d; }
  .result :global(mark) { background: #6b5534; color: #fff4dc; border-radius: 0.12rem; padding: 0 0.08em; }
  .result-title { color: #e6e6e2; font-weight: 650; }
  .meta { color: #929997; font-size: 0.82rem; }
  .source-backdrop { position: fixed; inset: 0 0 0 258px; background: rgba(16, 19, 21, 0.55); }
  .source-sheet { position: fixed; top: 0; right: 0; bottom: 0; width: var(--source-column-width); overflow-y: auto; border-left: 1px solid #303535; background: #191c1d; padding: 1.25rem 1.35rem; box-shadow: -1.2rem 0 2.5rem rgba(0, 0, 0, 0.4); }
  .source-heading-row { display: flex; align-items: center; justify-content: space-between; }
  .source-heading-row h2 { margin-top: 0; }
  #source-heading { margin-top: 0; }
  .source-text { max-height: calc(100vh - 14rem); overflow: auto; overflow-wrap: anywhere; white-space: pre-wrap; border: 1px solid #343939; border-radius: 0.45rem; background: #121515; padding: 0.9rem; font: 0.86rem/1.6 ui-monospace, SFMono-Regular, Menlo, monospace; }
  [role="alert"] { color: #f0a4a0; }
  .visually-hidden { position: absolute; width: 1px; height: 1px; padding: 0; margin: -1px; overflow: hidden; clip: rect(0,0,0,0); white-space: nowrap; border: 0; }
  @media (max-width: 82rem) { .app-shell.source-open .workspace { padding-right: clamp(1.25rem, 4vw, 4.5rem); } .source-sheet { left: 258px; width: auto; } }
  @media (max-width: 54rem) { .app-shell { grid-template-columns: 1fr; } .sidebar { min-height: auto; gap: 1rem; padding: 0.8rem 1rem; border-right: 0; border-bottom: 1px solid #2a2d2e; } .sidebar-footer { display: none; } .sidebar-note, .search-settings { max-width: 38rem; } .mode-nav [role="tablist"] { display: flex; } .mode-nav [role="tab"] { flex: 1; justify-content: center; } .content-layout.with-history { padding-right: 0; } .source-backdrop { left: 0; } .source-sheet { left: 0; width: 100%; } }
  @media (max-width: 36rem) { .workspace { padding: 1.3rem 1rem; } .workspace-header { align-items: start; margin-bottom: 1.2rem; } .search-input-row form { align-items: stretch; flex-direction: column; } .mode-nav [role="tab"] { gap: 0.35rem; padding: 0.55rem 0.35rem; font-size: 0.83rem; } }
</style>
