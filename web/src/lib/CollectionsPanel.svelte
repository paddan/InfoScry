<script lang="ts">
  import { onDestroy, onMount, tick } from 'svelte';
  import {
    ApiError,
    createCollection,
    deleteCollection,
    deleteDocuments,
    getCollectionDocument,
    getDeletion,
    getCollectionIgnorePatterns,
    getImportItems,
    listCollectionDocuments,
    listCollectionImports,
    listUnfinishedDeletions,
    renameCollection,
    updateCollectionIgnorePatterns,
    updateCollectionOcrLanguages,
    type Collection,
    type DeletionOperation,
    type DocumentApiRow,
    type DocumentDetail,
    type DocumentProgressView,
    type DocumentSort,
    type DocumentStatusName,
    type ImportHistoryEntry,
    type ImportItemApiView,
    type JobState,
    type RetryAdmission,
    type RetryAttempt,
    type RetryOcrChoice,
    type RetryRejection,
    type UnitKindName,
  } from './api';
  import CollectionOcrSettings from './CollectionOcrSettings.svelte';
  import DocumentRescan from './DocumentRescan.svelte';
  import OcrChoiceFields from './OcrChoiceFields.svelte';
  import OcrHistoryPanel from './OcrHistoryPanel.svelte';
  import ImportPanel from './ImportPanel.svelte';
  import { importItemOutcomeLabel } from './importOutcome';
  import { fileCountLabel, stageForReader, stageLabel } from './importProgress';
  import { emptyChoice, retryChoice } from './ocrRescan';

  export let collections: Collection[];
  export let selectedId: string;
  export let loading: boolean;
  export let error: string | null;
  export let onSelect: (id: string) => void;
  export let onCollectionsChanged: (selectedId?: string) => Promise<void> | void;
  /** Opens Admin → OCR profiles, so an empty profile list can point at where to create one. */
  export let onOpenOcrProfiles: (() => void) | undefined = undefined;
  /**
   * Opens one managed document in the page's source viewer at the unit its detail named. The page owns
   * the viewer, so this forwards the two ids and keeps no viewer state of its own.
   */
  export let onOpenDocument: (documentId: string, sourceId: string) => void;
  /**
   * Tells the page that a collection finished deleting, so it can close the viewer it had open on it,
   * clear workspace results bound to it, and refresh the lists it owns. The panel does not reach into
   * the page's viewer or workspace state, and the page does not own this panel's deletion status.
   */
  export let onCollectionDeleted: (collectionId: string) => Promise<void> | void;
  /**
   * Tells the page that one document finished deleting, so it can close the viewer it had open on it and
   * refresh what it owns. The panel keeps its own table, but the viewer is the page's.
   */
  export let onDocumentDeleted: (collectionId: string, documentId: string) => Promise<void> | void;
  /**
   * Asks for one existing document to be read again from the managed copy InfoScry holds. The page makes the
   * request and hands back what to show: the attempt it queued, or the server's own sentence for a refusal.
   * The panel never touches the viewer, so nothing about the page's source state moves for a retry.
   */
  export let onRetryDocument: (documentId: string, ocr?: RetryOcrChoice) => Promise<RetryAttempt>;
  /**
   * Asks for every eligible document of the managed collection to be read again, across all pages. It names
   * no documents and carries no filter or page, because the collection-wide set is the server's to pick; the
   * page answers with what the server admitted and refused.
   */
  export let onRetryAllDocuments: (ocr?: RetryOcrChoice) => Promise<RetryAdmission>;

  /** The rows one page asks for; the server's own maximum stays 200. */
  const PAGE_SIZE = 50;

  /**
   * The identifier the first migration gave the collection it created. InfoScry no longer creates one,
   * but an archive that already has this row keeps it, and Admin explains that rather than leaving a
   * surprise. It is the identifier that is matched, never the name: a collection a person created and
   * named Default has a generated id and never shows this notice.
   */
  const LEGACY_DEFAULT_ID = 'default';

  /** Every domain status with the words a reader sees; the filter offers exactly this list. */
  const STATUS_LABELS: Record<DocumentStatusName, string> = {
    QUEUED: 'Queued',
    COPYING: 'Copying',
    EXTRACTING: 'Extracting text',
    OCR: 'Reading with OCR',
    CHUNKING: 'Building passages',
    EMBEDDING: 'Embedding',
    INDEXING: 'Indexing',
    COMPLETE: 'Complete',
    COMPLETE_WITH_WARNINGS: 'Complete with warnings',
    NEEDS_REVIEW: 'Needs review',
    FAILED: 'Failed',
    CANCELLED: 'Cancelled',
    NEEDS_TOOL: 'Needs a tool',
  };

  const SORTS: { value: DocumentSort; label: string }[] = [
    { value: 'newest', label: 'Newest first' },
    { value: 'oldest', label: 'Oldest first' },
    { value: 'name-asc', label: 'Name A to Z' },
    { value: 'name-desc', label: 'Name Z to A' },
  ];

  /**
   * What one unit of a document is called, in a reader's words. The server sends the kind; the words are
   * product copy, so they live here. A kind the UI does not know falls back to the neutral word below
   * rather than pretending to count something it cannot name.
   */
  const UNIT_LABELS: Record<UnitKindName, [singular: string, plural: string]> = {
    PAGE: ['page', 'pages'],
    SECTION: ['section', 'sections'],
    SLIDE: ['slide', 'slides'],
    SHEET: ['sheet', 'sheets'],
    LINE: ['line', 'lines'],
    IMAGE: ['image', 'images'],
  };

  /** The words for a unit nobody named: a count is still a count, even when its nouns are unknown. */
  const NEUTRAL_UNIT: [singular: string, plural: string] = ['unit', 'units'];

  /** How an import job's own lifecycle reads; the stage inside a running job is shown beside it. */
  const JOB_STATE_LABELS: Record<JobState, string> = {
    QUEUED: 'Queued',
    RUNNING: 'Running',
    COMPLETE: 'Complete',
    FAILED: 'Failed',
    CANCELLED: 'Cancelled',
  };

  const TERMINAL_JOB_STATES: JobState[] = ['COMPLETE', 'FAILED', 'CANCELLED'];

  /** How long an unfinished import waits before the panel reads its history again. */
  const IMPORT_REFRESH_MILLIS = 2000;

  /** How long an unfinished deletion waits before the panel reads its status again. */
  const DELETION_REFRESH_MILLIS = 2000;

  let creating = false;
  let saving = false;
  let creatingError: string | null = null;
  let newName = '';
  let newDescription = '';
  let addingDocuments = false;

  /**
   * The settings draft for the selected collection: the two stored values the fields show, and what
   * the last save of each one did. The stored values remain the server's; these are only what the
   * reader is editing until a save answers.
   */
  let renameName = '';
  let renameSaving = false;
  let renameError: string | null = null;
  let renameSaved = false;
  let ocrLanguages = '';
  // The ignore patterns draft: one pattern per line. `ignoreLoaded` is false until the saved list has been read, so a
  // save can never replace a list the reader has not seen.
  let ignoreText = '';
  let ignoreLoaded = false;
  let ignoreSaving = false;
  let ignoreError: string | null = null;
  let ignoreSaved = false;
  let ocrSaving = false;
  let ocrError: string | null = null;
  let ocrSaved = false;
  /**
   * The settings draft's generation. A save reads across an await and the reader can select another
   * collection meanwhile, so the generation captured when a save starts is what decides whether its
   * answer may still write into the fields and the status beside them.
   */
  let settingsGeneration = 0;

  let documents: DocumentApiRow[] = [];
  let documentsTotal = 0;
  let documentsLoading = false;
  let documentsLoaded = false;
  let documentsError: string | null = null;
  let filenameInput = '';
  let filenameQuery = '';
  let statusFilter: DocumentStatusName | '' = '';
  let sort: DocumentSort = 'newest';
  let offset = 0;
  /** The collection the displayed rows belong to; a different one starts a fresh listing. */
  let listingCollectionId: string | null = null;
  let documentsGeneration = 0;

  /**
   * The rows checked in the table, by id. Only ids of the displayed page can ever be in here: a
   * collection, filter, sort or page change clears the set, and a refresh keeps only the ids the page
   * still shows, so nothing hidden can enter the set that a confirmation would then send.
   */
  let selectedDocumentIds = new Set<string>();

  let detail: DocumentDetail | null = null;
  let detailLoading = false;
  let detailError: string | null = null;
  let detailGeneration = 0;
  /** One retry at a time, because it is one attempt per document and the button is per document too. */
  let retrySubmitting = false;
  let retryMessage: string | null = null;
  let retryError: string | null = null;
  /**
   * Whether this retry reads with a method chosen here instead of the collection's settings, and the choice.
   * Closed by default, and an open form that names nothing is the same request as a closed one.
   */
  let retryChoiceOpen = false;
  let retryChoiceValue = emptyChoice();

  /**
   * The collection-wide retry: one submission at a time, what the server answered, and the documents it
   * refused. The admitted work itself is the server's — this is only what the panel shows about an answer,
   * so navigating away, reloading or restarting changes nothing about the attempts.
   */
  let retryAllSubmitting = false;
  let retryAllMessage: string | null = null;
  let retryAllRejected: RetryRejection[] = [];
  let retryAllError: string | null = null;
  let retryAllChoiceOpen = false;
  let retryAllChoiceValue = emptyChoice();

  let imports: ImportHistoryEntry[] = [];
  let importsTotal = 0;
  let importsLoading = false;
  let importsLoaded = false;
  let importsError: string | null = null;
  let importsOffset = 0;
  let importsGeneration = 0;
  /** The pending wait before the next history read; cleared when the panel goes away. */
  let refreshTimer: ReturnType<typeof setTimeout> | null = null;

  /**
   * The deletions this Admin is following, restored from the server on opening and followed to `DONE`
   * afterwards. Nothing about them is held only in this component: the status comes from SQLite, so a
   * reload or a restart finds the unfinished ones again.
   */
  let deletions: DeletionOperation[] = [];
  let deletionsError: string | null = null;
  /** The pending wait before the next status read; cleared when the panel goes away. */
  let deletionTimer: ReturnType<typeof setTimeout> | null = null;
  let deletionsGeneration = 0;

  /** The exact-name confirmation: the collection it names, what was typed, and what the submission did. */
  let deleting: Collection | null = null;
  let confirmName = '';
  let deleteSubmitting = false;
  let deleteError: string | null = null;
  let deleteDialog: HTMLElement | null = null;
  let deleteNameInput: HTMLInputElement | null = null;
  /** The control the dialog was opened from, so declining puts focus back where it was. */
  let deleteOpener: HTMLElement | null = null;

  /**
   * The documents whose removal is being confirmed, and what the submission did. The list is a snapshot
   * taken when the confirmation opened: what the dialog says and what it sends both come from it, so a
   * refresh underneath cannot change the confirmed target set. [documentDeleteOpener] is the control
   * focus returns to if it is declined. Nothing is sent until the confirmation is submitted.
   */
  let deletingDocuments: DocumentApiRow[] | null = null;
  let documentDeleteSubmitting = false;
  let documentDeleteError: string | null = null;
  let documentDeleteDialog: HTMLElement | null = null;
  let documentDeleteOpener: HTMLElement | null = null;

  /** The import whose per-file outcomes are shown, and what that read returned. */
  let itemsJobId: string | null = null;
  let items: ImportItemApiView[] = [];
  let itemsLoading = false;
  let itemsError: string | null = null;
  let itemsGeneration = 0;

  $: selected = collections.find((collection) => collection.id === selectedId) ?? null;
  // The listing follows the selected collection, so a selection change cannot page through the previous
  // collection's term, filter and page.
  $: if (selectedId !== listingCollectionId) startListing(selectedId);

  function countLabel(count: number): string {
    return `${count} document${count === 1 ? '' : 's'}`;
  }

  /** The word for one unit of a document, chosen for the number it is counting. */
  function unitLabel(kind: UnitKindName | null | undefined, count: number): string {
    const [singular, plural] = (kind !== null && kind !== undefined ? UNIT_LABELS[kind] : undefined) ?? NEUTRAL_UNIT;
    return count === 1 ? singular : plural;
  }

  /**
   * A document's progress in one sentence. A total only appears when the extractor actually announced
   * one — a count shown against an invented denominator would be a percentage of nothing — and the OCR
   * phase is named when that is the stage the document is in.
   */
  function progressSentence(progress: DocumentProgressView, status: DocumentStatusName): string {
    const known = progress.totalUnits !== null && progress.totalUnits !== undefined;
    const unit = unitLabel(progress.unitKind, known ? progress.totalUnits ?? 0 : progress.processedUnits);
    const phase = status === 'OCR' ? 'OCR · ' : '';
    if (!known) return `${phase}${progress.processedUnits} ${unit} processed`;
    return `${phase}${progress.processedUnits}/${progress.totalUnits} ${unit} processed`;
  }

  /** One row's compact progress: the same numbers, in the space a table cell has. */
  function progressLabel(progress: DocumentProgressView | null | undefined): string {
    if (progress === null || progress === undefined) return '\u2014';
    const known = progress.totalUnits !== null && progress.totalUnits !== undefined;
    const counted = known ? progress.totalUnits ?? 0 : progress.processedUnits;
    const unit = unitLabel(progress.unitKind, counted);
    if (!known) return `${progress.processedUnits} ${unit} processed`;
    return `${progress.processedUnits}/${progress.totalUnits} ${unit}`;
  }

  /**
   * Whether the denominator needs explaining: a page total counts every page of the document, which is
   * not the same as the number of pages OCR was needed for. That distinction is worth a sentence exactly
   * when part of the document was read by a tool.
   */
  function needsOcrScopeHint(progress: DocumentProgressView, status: DocumentStatusName): boolean {
    if (progress.totalUnits === null || progress.totalUnits === undefined) return false;
    return status === 'OCR' || progress.ocrUnits !== null && progress.ocrUnits !== undefined && progress.ocrUnits > 0;
  }

  /** A count of units read one way, or the truth that the archive never recorded how they were read. */
  function methodCount(count: number | null | undefined, progress: DocumentProgressView): string {
    if (count === null || count === undefined) return 'Not recorded';
    return `${count} ${unitLabel(progress.unitKind, count)}`;
  }

  function statusLabel(status: DocumentStatusName): string {
    return STATUS_LABELS[status] ?? status;
  }

  /** A size as the storage column shows it: whole bytes, then one decimal for the larger units. */
  function formatSize(bytes: number): string {
    const units = ['B', 'KB', 'MB', 'GB', 'TB'];
    let value = bytes;
    let unit = 0;
    while (value >= 1024 && unit < units.length - 1) {
      value /= 1024;
      unit += 1;
    }
    return `${unit === 0 ? value : Math.round(value * 10) / 10} ${units[unit]}`;
  }

  /** The import date out of the stored ISO-8601 UTC instant, so the column has no locale surprises. */
  function formatDate(instant: string): string {
    return instant.slice(0, 10);
  }

  /**
   * A newly selected collection starts at its first page, with no term, filter, history, detail or
   * checked row kept: its rows are a different set of documents.
   */
  function startListing(collectionId: string): void {
    listingCollectionId = collectionId;
    startSettings(collectionId);
    filenameInput = '';
    filenameQuery = '';
    statusFilter = '';
    sort = 'newest';
    offset = 0;
    documents = [];
    documentsTotal = 0;
    documentsLoaded = false;
    documentsError = null;
    clearSelection();
    closeDetails();
    retryAllSubmitting = false;
    retryAllMessage = null;
    retryAllRejected = [];
    retryAllError = null;
    retryAllChoiceOpen = false;
    retryAllChoiceValue = emptyChoice();
    startImports();
    void loadDocuments();
  }

  /** The settings fields start from the selected collection's own stored name and OCR languages. */
  function startSettings(collectionId: string): void {
    settingsGeneration += 1;
    const collection = collections.find((candidate) => candidate.id === collectionId);
    renameName = collection?.name ?? '';
    ocrLanguages = collection?.ocrLanguages ?? '';
    renameSaving = false;
    renameError = null;
    renameSaved = false;
    ocrSaving = false;
    ocrError = null;
    ocrSaved = false;
    ignoreText = '';
    ignoreLoaded = false;
    ignoreSaving = false;
    ignoreError = null;
    ignoreSaved = false;
    void loadIgnorePatterns(collectionId, settingsGeneration);
  }

  /** Reads the saved ignore list into the draft; an answer for a collection no longer managed is dropped. */
  async function loadIgnorePatterns(collectionId: string, generation: number): Promise<void> {
    try {
      const patterns = await getCollectionIgnorePatterns(collectionId);
      if (generation !== settingsGeneration) return;
      ignoreText = patterns.join('\n');
      ignoreLoaded = true;
    } catch (failure) {
      if (generation !== settingsGeneration) return;
      ignoreError = describe(failure);
    }
  }

  /**
   * Saves the ignore list. The server refuses an invalid pattern, and the draft is kept so it can be fixed. Only
   * future imports use the new list: an import already queued or running keeps the list it was admitted with.
   */
  async function saveIgnorePatterns(event: SubmitEvent): Promise<void> {
    event.preventDefault();
    const collection = selected;
    if (collection === null || ignoreSaving || !ignoreLoaded) return;
    const generation = settingsGeneration;
    ignoreSaving = true;
    ignoreError = null;
    ignoreSaved = false;
    try {
      const saved = await updateCollectionIgnorePatterns(collection.id, ignoreText.split('\n'));
      if (generation !== settingsGeneration) return;
      ignoreText = saved.join('\n');
      ignoreSaved = true;
    } catch (failure) {
      if (generation !== settingsGeneration) return;
      ignoreError = describe(failure);
    } finally {
      if (generation === settingsGeneration) ignoreSaving = false;
    }
  }

  /**
   * Saves the name through the existing rename route. The collection keeps its id, its documents and
   * its import history; the parent reloads the collection list, so Admin and the workspace selector
   * both show the new name while the selection stays where it was.
   */
  async function saveName(event: SubmitEvent): Promise<void> {
    event.preventDefault();
    const collection = selected;
    if (collection === null || renameSaving) return;
    const generation = settingsGeneration;
    renameSaving = true;
    renameError = null;
    renameSaved = false;
    try {
      const updated = await renameCollection(collection.id, renameName.trim());
      // A save of another collection's name is not this form's news: the draft the reader is looking at
      // has been restarted since, so neither its field nor its status may take the answer.
      if (generation !== settingsGeneration) return;
      renameName = updated.name;
      renameSaved = true;
      await onCollectionsChanged();
    } catch (failure) {
      if (generation !== settingsGeneration) return;
      renameError = describe(failure);
    } finally {
      if (generation === settingsGeneration) renameSaving = false;
    }
  }

  /**
   * Saves the OCR languages the server snapshots into future imports and explicit retries. Nothing
   * here reprocesses a document: the server changes what the next attempt starts with, and a
   * completed document is left exactly as it is.
   */
  async function saveLanguages(event: SubmitEvent): Promise<void> {
    event.preventDefault();
    const collection = selected;
    if (collection === null || ocrSaving) return;
    const generation = settingsGeneration;
    ocrSaving = true;
    ocrError = null;
    ocrSaved = false;
    try {
      const updated = await updateCollectionOcrLanguages(collection.id, ocrLanguages.trim());
      // Same guard as the name: another collection's answer must not land in this collection's draft.
      if (generation !== settingsGeneration) return;
      ocrLanguages = updated.ocrLanguages;
      ocrSaved = true;
      // The listing the panel was handed now carries the settings it just wrote, so the draft stays
      // truthful when another collection is managed and this one is opened again.
      await onCollectionsChanged();
    } catch (failure) {
      if (generation !== settingsGeneration) return;
      ocrError = describe(failure);
    } finally {
      if (generation === settingsGeneration) ocrSaving = false;
    }
  }

  /**
   * The one listing read. The collection, term, filter, order and offset it sends are read at call time,
   * and a response for anything but the newest read is dropped: an older answer cannot replace the rows a
   * newer filter, page or collection asked for.
   */
  async function loadDocuments(): Promise<void> {
    const collection = selected;
    if (collection === null) {
      documentsLoading = false;
      return;
    }
    const generation = ++documentsGeneration;
    documentsLoading = true;
    documentsError = null;
    try {
      const page = await listCollectionDocuments(collection.id, {
        q: filenameQuery,
        status: statusFilter === '' ? undefined : statusFilter,
        sort,
        limit: PAGE_SIZE,
        offset,
      });
      if (generation !== documentsGeneration) return;
      documents = page.documents;
      documentsTotal = page.total;
      documentsLoaded = true;
      // A refresh is not a selection: an id this page no longer shows leaves the checked set, and one it
      // newly shows never enters it. Nothing hidden is ever added to a confirmed target set this way.
      keepSelectionOnPage();
    } catch (failure) {
      if (generation !== documentsGeneration) return;
      documents = [];
      documentsTotal = 0;
      documentsLoaded = false;
      documentsError = describe(failure);
      clearSelection();
    } finally {
      if (generation === documentsGeneration) documentsLoading = false;
    }
  }

  /**
   * A search, a filter and a sort all start at the first page and clear the checked rows; the details of
   * another row do not stay either. A term, a status filter and an order all change which rows exist, so
   * the previously checked ones are not the ones the reader is looking at any more.
   */
  function searchDocuments(event: SubmitEvent): void {
    event.preventDefault();
    filenameQuery = filenameInput.trim();
    offset = 0;
    clearSelection();
    closeDetails();
    void loadDocuments();
  }

  function changeFilter(): void {
    offset = 0;
    clearSelection();
    closeDetails();
    void loadDocuments();
  }

  function changePage(delta: number): void {
    offset = Math.max(0, offset + delta * PAGE_SIZE);
    clearSelection();
    closeDetails();
    void loadDocuments();
  }

  /** A different page, filter, sort or collection shows different rows: nothing stays checked. */
  function clearSelection(): void {
    selectedDocumentIds = new Set();
  }

  /** Drop every checked id the displayed page no longer carries, and add none. */
  function keepSelectionOnPage(): void {
    const shown = new Set(documents.map((row) => row.id));
    selectedDocumentIds = new Set([...selectedDocumentIds].filter((id) => shown.has(id)));
  }

  function toggleSelected(row: DocumentApiRow, checked: boolean): void {
    const next = new Set(selectedDocumentIds);
    if (checked) next.add(row.id);
    else next.delete(row.id);
    selectedDocumentIds = next;
  }

  /** Whether every row of the displayed page is checked; select-all never means anything wider. */
  function allOnPageSelected(): boolean {
    return documents.length > 0 && documents.every((row) => selectedDocumentIds.has(row.id));
  }

  /** Select-all is the page it sits in: the whole current page, and only it. */
  function toggleAllOnPage(event: Event): void {
    const checked = (event.currentTarget as HTMLInputElement).checked;
    selectedDocumentIds = checked ? new Set(documents.map((row) => row.id)) : new Set();
  }

  /** The checked rows, in the order the table shows them. */
  function selectedDocuments(): DocumentApiRow[] {
    return documents.filter((row) => selectedDocumentIds.has(row.id));
  }

  async function openDetails(documentId: string): Promise<void> {
    const collection = selected;
    if (collection === null) return;
    const generation = ++detailGeneration;
    detail = null;
    detailError = null;
    detailLoading = true;
    try {
      const loaded = await getCollectionDocument(collection.id, documentId);
      if (generation !== detailGeneration) return;
      detail = loaded;
    } catch (failure) {
      if (generation !== detailGeneration) return;
      detailError = describe(failure);
    } finally {
      if (generation === detailGeneration) detailLoading = false;
    }
  }

  function closeDetails(): void {
    detailGeneration += 1;
    detail = null;
    detailError = null;
    detailLoading = false;
    retrySubmitting = false;
    retryMessage = null;
    retryError = null;
    retryChoiceOpen = false;
    retryChoiceValue = emptyChoice();
  }

  /**
   * Reads one document again from the copy InfoScry holds.
   *
   * The page makes the call, because the collection Admin is managing is the page's selection; what the panel
   * does with the answer is show it and re-read the document, so the row and the details show the attempt the
   * server actually queued rather than the one the click hoped for.
   */
  async function retryDocument(entry: DocumentDetail | null): Promise<void> {
    if (entry === null || retrySubmitting) return;
    const documentId = entry.document.id;
    retrySubmitting = true;
    retryMessage = null;
    retryError = null;
    try {
      const ocr = retryChoiceOpen ? retryChoice(retryChoiceValue) : undefined;
      const attempt = ocr === undefined ? await onRetryDocument(documentId) : await onRetryDocument(documentId, ocr);
      if (!attempt.accepted) {
        retryError = attempt.reason;
        return;
      }
      retryMessage = 'Retry queued. The document will be read again from the copy InfoScry holds.';
      await openDetails(documentId);
      await loadDocuments();
    } catch (failure) {
      retryError = describe(failure);
    } finally {
      retrySubmitting = false;
    }
  }

  /**
   * Reads every eligible document of the collection again, wherever it is listed.
   *
   * The request names the collection and nothing else: no ids from this page, no filter and no page, because
   * the eligible set is the server's to select across the whole collection. The answer is not a single
   * success or failure — some documents may be admitted while others are refused with a sentence each — so
   * the panel shows all three and then re-reads the rows and any open details, which is where the attempt the
   * server actually queued becomes visible.
   */
  async function retryAll(): Promise<void> {
    if (retryAllSubmitting) return;
    retryAllSubmitting = true;
    retryAllMessage = null;
    retryAllRejected = [];
    retryAllError = null;
    try {
      const ocr = retryAllChoiceOpen ? retryChoice(retryAllChoiceValue) : undefined;
      const admission = ocr === undefined ? await onRetryAllDocuments() : await onRetryAllDocuments(ocr);
      retryAllRejected = admission.rejected;
      retryAllMessage = retryAllSummary(admission);
      await loadDocuments();
      if (detail !== null) await openDetails(detail.document.id);
    } catch (failure) {
      retryAllError = describe(failure);
    } finally {
      retryAllSubmitting = false;
    }
  }

  /**
   * What a collection-wide answer means in words. An answer that queued nothing is never called a success:
   * either every eligible document was refused, or the collection had nothing eligible to begin with.
   */
  function retryAllSummary(admission: RetryAdmission): string {
    const collectionName = selected?.name ?? 'this collection';
    const skipped = admission.rejected.length;
    if (skipped === 0) {
      return admission.acceptedJobIds.length === 0
        ? `Nothing to retry: no document in ${collectionName} is failed, cancelled or waiting for a tool.`
        : `Retry queued for every eligible document in ${collectionName}. They are read again from the copies ` +
          'InfoScry holds.';
    }
    const notRetried = `${skipped} ${skipped === 1 ? 'document was' : 'documents were'} not retried:`;
    return admission.acceptedJobIds.length === 0
      ? `No retries were queued. ${notRetried}`
      : `Retry queued. ${notRetried}`;
  }

  /**
   * The refused documents, grouped by the server's own sentence and worded for display. The reasons are per
   * document, and one collection-wide request can refuse many for the same reason, so the count is what makes
   * the list readable. Which documents they are stays visible in the table, where a refused document keeps
   * the status it had.
   */
  function retryRefusals(): { reason: string; label: string }[] {
    const counts = new Map<string, number>();
    for (const rejection of retryAllRejected) {
      counts.set(rejection.reason, (counts.get(rejection.reason) ?? 0) + 1);
    }
    return [...counts].map(([reason, count]) => ({
      reason,
      label: count > 1 ? `${reason} (${count} documents)` : reason,
    }));
  }

  /** The detail read named the first unit a reader can open; the page opens it. */
  function openDocument(entry: DocumentDetail | null): void {
    if (entry === null || entry.sourceId === null || entry.sourceId === undefined) return;
    onOpenDocument(entry.document.id, entry.sourceId);
  }

  /**
   * A newly selected collection starts its history at the first page with nothing expanded. The older
   * read is dropped by bumping the generation first, so a response for the previous collection can never
   * fill the newly selected one's history.
   */
  function startImports(): void {
    importsGeneration += 1;
    if (refreshTimer !== null) {
      clearTimeout(refreshTimer);
      refreshTimer = null;
    }
    imports = [];
    importsTotal = 0;
    importsLoaded = false;
    importsLoading = false;
    importsError = null;
    importsOffset = 0;
    closeItems();
    void loadImports();
  }

  /**
   * The one history read. The collection and offset it sends are read at call time, and a response for
   * anything but the newest read is dropped: an older answer cannot replace the history a newer collection
   * or page asked for.
   */
  async function loadImports(): Promise<void> {
    const collection = selected;
    if (collection === null) {
      importsLoading = false;
      return;
    }
    const generation = ++importsGeneration;
    importsLoading = true;
    importsError = null;
    try {
      const history = await listCollectionImports(collection.id, { limit: PAGE_SIZE, offset: importsOffset });
      if (generation !== importsGeneration) return;
      imports = history.imports;
      importsTotal = history.total;
      importsLoaded = true;
      // The expanded import keeps its file outcomes current while its files are still being worked on.
      if (itemsJobId !== null && imports.some((entry) => entry.id === itemsJobId)) void loadItems(itemsJobId);
      scheduleImportRefresh();
    } catch (failure) {
      if (generation !== importsGeneration) return;
      imports = [];
      importsTotal = 0;
      importsLoaded = false;
      importsError = describe(failure);
    } finally {
      if (generation === importsGeneration) importsLoading = false;
    }
  }

  /**
   * While an unfinished import is listed, the panel reads the history again from the server. The timer is
   * one long-lived slot rather than a loop per read, so a refresh cannot stack, and a terminal history
   * schedules nothing: polling ends as soon as there is nothing left to watch.
   */
  function scheduleImportRefresh(): void {
    if (refreshTimer !== null || !imports.some((entry) => !TERMINAL_JOB_STATES.includes(entry.state))) return;
    refreshTimer = setTimeout(() => {
      refreshTimer = null;
      void loadImports();
    }, IMPORT_REFRESH_MILLIS);
  }

  function changeImportsPage(delta: number): void {
    importsOffset = Math.max(0, importsOffset + delta * PAGE_SIZE);
    closeItems();
    void loadImports();
  }

  /** Show one import's persisted per-file outcomes, or hide them again. */
  function toggleItems(jobId: string): void {
    if (itemsJobId === jobId) {
      closeItems();
      return;
    }
    itemsJobId = null;
    void loadItems(jobId);
  }

  /**
   * One import's per-file outcomes. Another import's list is cleared before this read, so its files can
   * never appear under the wrong heading; a refresh of the same list keeps showing it while it reloads.
   */
  async function loadItems(jobId: string): Promise<void> {
    if (itemsJobId !== jobId) {
      itemsJobId = jobId;
      items = [];
    }
    const generation = ++itemsGeneration;
    itemsError = null;
    itemsLoading = items.length === 0;
    try {
      const loaded = await getImportItems(jobId);
      if (generation !== itemsGeneration) return;
      items = loaded;
    } catch (failure) {
      if (generation !== itemsGeneration) return;
      itemsError = describe(failure);
    } finally {
      if (generation === itemsGeneration) itemsLoading = false;
    }
  }

  function closeItems(): void {
    itemsGeneration += 1;
    itemsJobId = null;
    items = [];
    itemsError = null;
    itemsLoading = false;
  }

  /**
   * What the Stage column says: the stage in a reader's words, and — while the import is unfinished — the
   * file it is reading now, e.g. `Extracting · report.pdf`. A finished import is in no stage and has nothing
   * being read, so its row is empty, except for the wait for an approval; a stage the server never reported
   * is an em dash.
   */
  function stageCell(entry: ImportHistoryEntry): string {
    const current = TERMINAL_JOB_STATES.includes(entry.state) ? null : entry.currentItem;
    const parts = [stageLabel(stageForReader(entry.state, entry.stage)), current ?? ''].filter((part) => part !== '');
    return parts.length === 0 ? '\u2014' : parts.join(' · ');
  }

  /** What one selected file is called; the external path is not part of the panel, only the name. */
  function itemName(item: ImportItemApiView): string {
    return item.sourceName ?? item.id;
  }

  /** A failed file that never became a document: its remedy is another Add documents, not a Retry. */
  function needsResubmission(item: ImportItemApiView): boolean {
    return item.outcome === 'FAILED' && (item.documentId === null || item.documentId === undefined);
  }

  function startCreate(): void {
    creating = true;
    creatingError = null;
  }

  function cancelCreate(): void {
    creating = false;
    creatingError = null;
    newName = '';
    newDescription = '';
  }

  async function create(event: SubmitEvent): Promise<void> {
    event.preventDefault();
    const name = newName.trim();
    if (name === '' || saving) return;
    saving = true;
    creatingError = null;
    try {
      const description = newDescription.trim();
      const created = await createCollection(name, description === '' ? undefined : description);
      newName = '';
      newDescription = '';
      creating = false;
      addingDocuments = false;
      // The parent refreshes the workspace selector and selects the new collection for management.
      await onCollectionsChanged(created.id);
    } catch (failure) {
      creatingError = describe(failure);
    } finally {
      saving = false;
    }
  }

  function describe(failure: unknown): string {
    if (failure instanceof ApiError) return failure.message;
    return failure instanceof Error ? failure.message : 'Something went wrong.';
  }

  /**
   * What one deletion is removing, in a reader's words. A document deletion names the documents the
   * table is showing when it can, and falls back to counting them when the row is already gone.
   */
  function deletionTarget(deletion: DeletionOperation): string {
    if (deletion.kind !== 'DOCUMENT') return deletion.collectionName;
    if (deletion.documentIds.length === 1) {
      const name = documents.find((row) => row.id === deletion.documentIds[0])?.originalFilename;
      return `${name ?? 'a document'} in ${deletion.collectionName}`;
    }
    return `${deletion.documentIds.length} documents in ${deletion.collectionName}`;
  }

  /**
   * The removal states as a reader reads them. A deleted target can no longer be managed, so this says
   * what is true — it is being removed, not that it is gone — and it maps the server's stable code to the
   * remedy a person can act on.
   */
  function deletionMessage(deletion: DeletionOperation): string {
    const target = deletionTarget(deletion);
    if (deletion.errorCode === 'UNSAFE_RECOVERY') {
      return `Deleting ${target} could not be finished safely. Its files are kept and ` +
        'InfoScry refuses further changes until the state is resolved; see the InfoScry log.';
    }
    if (deletion.errorCode !== null && deletion.errorCode !== undefined) {
      return `Deleting ${target} could not be finished (${deletion.errorCode}). ` +
        'InfoScry will try again the next time it starts.';
    }
    return `Deleting ${target}…`;
  }

  /** One unfinished operation's status: the interim state, or the reason it stopped. */
  function deletionIsPending(deletion: DeletionOperation): boolean {
    return deletion.errorCode === null || deletion.errorCode === undefined;
  }

  /**
   * The unfinished deletions the server still records. This is the read that makes Admin's deletion
   * status survive navigation and reload, because nothing here depends on this component having seen
   * the admission.
   */
  async function loadDeletions(): Promise<void> {
    const generation = ++deletionsGeneration;
    try {
      const unfinished = await listUnfinishedDeletions();
      if (generation !== deletionsGeneration) return;
      deletions = unfinished;
      deletionsError = null;
    } catch (failure) {
      if (generation !== deletionsGeneration) return;
      deletionsError = describe(failure);
    }
    scheduleDeletionRefresh();
  }

  /**
   * While a deletion is unfinished, its status is read from the server again. A read that failed is not
   * a deletion that finished: the operations stay listed with what will happen to them.
   */
  function scheduleDeletionRefresh(): void {
    if (deletionTimer !== null || deletions.length === 0) return;
    deletionTimer = setTimeout(() => {
      deletionTimer = null;
      void pollDeletions();
    }, DELETION_REFRESH_MILLIS);
  }

  /**
   * One status read per followed operation. A terminal one is removed from the list and its collection
   * is handed to the page, which is what lets the initiating UI say `Deleting…` and then clean up once,
   * at the moment the deletion is really done.
   */
  async function pollDeletions(): Promise<void> {
    const generation = ++deletionsGeneration;
    const followed = deletions;
    try {
      const current = await Promise.all(followed.map((deletion) => getDeletion(deletion.operationId)));
      if (generation !== deletionsGeneration) return;
      deletions = current.filter((deletion) => !deletion.terminal);
      deletionsError = null;
      for (const finished of current.filter((deletion) => deletion.terminal)) {
        await forgetFinished(finished);
      }
    } catch (failure) {
      if (generation !== deletionsGeneration) return;
      deletionsError = describe(failure);
    }
    scheduleDeletionRefresh();
  }

  /**
   * One finished deletion, of either kind: the rows, the counts and the page's viewer have to agree with
   * what the archive now holds.
   *
   * A finished document deletion removes one row: the table is re-read under its current criteria so the
   * paging and the count follow, its details are closed, and the page is told which document went so it
   * can close the viewer it had open on it. A collection deletion takes the whole listing with it.
   */
  async function forgetFinished(deletion: DeletionOperation): Promise<void> {
    if (deletion.kind === 'DOCUMENT') {
      const targeted = new Set(deletion.documentIds);
      if (detail !== null && targeted.has(detail.document.id)) closeDetails();
      // A removed row is not a pending selection either, whether it was sent from here or admitted before
      // a reload; the refresh below then keeps the rest of the checked rows honest.
      selectedDocumentIds = new Set([...selectedDocumentIds].filter((id) => !targeted.has(id)));
      for (const documentId of deletion.documentIds) await onDocumentDeleted(deletion.collectionId, documentId);
      // The count the collection list shows changed, and so may the page the reader is on.
      await onCollectionsChanged();
      if (listingCollectionId === deletion.collectionId) await loadDocuments();
      return;
    }
    forgetCollection(deletion.collectionId);
    await onCollectionDeleted(deletion.collectionId);
  }

  /** A deleted collection's rows, details and history cannot be shown again: they went with it. */
  function forgetCollection(collectionId: string): void {
    if (listingCollectionId !== collectionId) return;
    documents = [];
    documentsTotal = 0;
    documentsLoaded = false;
    documentsError = null;
    clearSelection();
    closeDetails();
    closeItems();
    imports = [];
    importsTotal = 0;
    importsLoaded = false;
    importsError = null;
    if (refreshTimer !== null) {
      clearTimeout(refreshTimer);
      refreshTimer = null;
    }
  }

  /** Opens the exact-name confirmation for the selected collection; nothing is sent until it is confirmed. */
  async function openDeleteDialog(): Promise<void> {
    if (selected === null) return;
    deleting = selected;
    confirmName = '';
    deleteError = null;
    deleteOpener = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    await tick();
    deleteNameInput?.focus();
  }

  /** Declining removes nothing: closing the dialog sends no request at all. */
  function closeDeleteDialog(): void {
    if (deleteSubmitting) return;
    deleting = null;
    confirmName = '';
    deleteError = null;
    const opener = deleteOpener;
    deleteOpener = null;
    if (opener instanceof HTMLElement && opener.isConnected) opener.focus();
  }

  function handleDeleteKeydown(event: KeyboardEvent): void {
    if (event.key === 'Escape') {
      event.preventDefault();
      closeDeleteDialog();
    } else if (event.key === 'Tab') {
      containDialogFocus(event, deleteDialog);
    }
  }

  /**
   * Keep keyboard focus inside the open confirmation. The dialog is announced as modal, so Tab must not
   * walk into the page behind the backdrop; the control that opened it gets focus back when it closes.
   */
  function containDialogFocus(event: KeyboardEvent, dialog: HTMLElement | null): void {
    if (dialog === null) return;
    const focusable = Array.from(dialog.querySelectorAll<HTMLElement>(
      'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])',
    ));
    if (focusable.length === 0) {
      // A submitted removal disables every control inside, so there is nothing for Tab to move to: the
      // dialog itself takes the key and keeps it, rather than letting go of it into the page behind.
      event.preventDefault();
      dialog.focus();
      return;
    }
    const first = focusable[0];
    const last = focusable[focusable.length - 1];
    const active = document.activeElement;
    if (active === first && event.shiftKey) {
      event.preventDefault();
      last.focus();
    } else if (active === last && !event.shiftKey) {
      event.preventDefault();
      first.focus();
    } else if (active === dialog || !dialog.contains(active)) {
      event.preventDefault();
      (event.shiftKey ? last : first).focus();
    }
  }

  /**
   * A submission disables both dialog controls, and a disabled control cannot hold focus in a browser,
   * so the dialog itself — which stays focusable — takes it once the flush has disabled them. The wait
   * is not awaited by the submission: it only serves the keyboard, never the request.
   */
  function holdDialogFocus(dialog: HTMLElement | null): void {
    void tick().then(() => dialog?.focus());
  }

  /**
   * Confirms the deletion. The server admits it durably and answers with the operation, which is what
   * the panel then follows; the collection leaves the manageable list immediately, so the same deletion
   * cannot be submitted twice.
   */
  async function submitDelete(event: SubmitEvent): Promise<void> {
    event.preventDefault();
    const collection = deleting;
    if (collection === null || deleteSubmitting || confirmName.trim() !== collection.name) return;
    deleteSubmitting = true;
    deleteError = null;
    holdDialogFocus(deleteDialog);
    try {
      const admitted = await deleteCollection(collection.id, confirmName.trim());
      deletions = [
        ...deletions.filter((deletion) => deletion.operationId !== admitted.operationId),
        {
          operationId: admitted.operationId,
          kind: 'COLLECTION',
          collectionId: admitted.collectionId,
          collectionName: collection.name,
          documentIds: [],
          phase: admitted.phase,
          terminal: false,
          errorCode: null,
        },
      ];
      deleteSubmitting = false;
      closeDeleteDialog();
      // The status is read now — one round trip, so the panel can say what the server recorded rather
      // than only what it assumed — and then followed while it is unfinished.
      void pollDeletions();
      // The server no longer offers the collection for management, so the list the panel was handed is
      // refreshed rather than left showing something that is on its way out.
      await onCollectionsChanged();
    } catch (failure) {
      deleteSubmitting = false;
      deleteError = describe(failure);
    }
  }

  /**
   * Opens the confirmation for exactly the rows it is handed — one row from the table, or the checked
   * ones — and snapshots them there; nothing is sent until it is confirmed.
   */
  async function openDocumentDeleteDialog(rows: DocumentApiRow[]): Promise<void> {
    if (selected === null || rows.length === 0) return;
    deletingDocuments = [...rows];
    documentDeleteError = null;
    documentDeleteOpener = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    await tick();
    documentDeleteDialog?.focus();
  }

  /** Declining removes nothing: closing the dialog sends no request at all. */
  function closeDocumentDeleteDialog(): void {
    if (documentDeleteSubmitting) return;
    deletingDocuments = null;
    documentDeleteError = null;
    const opener = documentDeleteOpener;
    documentDeleteOpener = null;
    if (opener instanceof HTMLElement && opener.isConnected) opener.focus();
  }

  function handleDocumentDeleteKeydown(event: KeyboardEvent): void {
    if (event.key === 'Escape') {
      event.preventDefault();
      closeDocumentDeleteDialog();
    } else if (event.key === 'Tab') {
      containDialogFocus(event, documentDeleteDialog);
    }
  }

  /**
   * Confirms the removal of the documents the confirmation snapshotted, as one admission. The server
   * validates every id against the active collection before it records anything and answers with the
   * one operation, which the panel then follows; the rows stay listed while the deletion runs, and the
   * operation is added to the same followed list the reopened Admin restores its unfinished work from.
   */
  async function submitDocumentDelete(event: SubmitEvent): Promise<void> {
    event.preventDefault();
    const collection = selected;
    const documentsToDelete = deletingDocuments;
    if (collection === null || documentsToDelete === null || documentDeleteSubmitting) return;
    documentDeleteSubmitting = true;
    documentDeleteError = null;
    holdDialogFocus(documentDeleteDialog);
    try {
      const admitted = await deleteDocuments(collection.id, documentsToDelete.map((row) => row.id));
      deletions = [
        ...deletions.filter((deletion) => deletion.operationId !== admitted.operationId),
        {
          operationId: admitted.operationId,
          kind: 'DOCUMENT',
          collectionId: admitted.collectionId,
          collectionName: collection.name,
          documentIds: admitted.documentIds,
          phase: admitted.phase,
          terminal: false,
          errorCode: null,
        },
      ];
      documentDeleteSubmitting = false;
      closeDocumentDeleteDialog();
      // The admitted documents are on their way out; they are no longer a selection waiting to be sent.
      const targeted = new Set(admitted.documentIds);
      selectedDocumentIds = new Set([...selectedDocumentIds].filter((id) => !targeted.has(id)));
      void pollDeletions();
    } catch (failure) {
      documentDeleteSubmitting = false;
      documentDeleteError = describe(failure);
    }
  }

  /** The confirmation's own heading: one document is named, several are counted with their collection. */
  function documentDeleteHeading(rows: DocumentApiRow[]): string {
    if (rows.length === 1) return `Delete ${rows[0].originalFilename}?`;
    return `Delete ${rows.length} documents from ${selected?.name ?? 'this collection'}?`;
  }

  /** The submit label: one document is a document, several are documents, and both say what is running. */
  function documentDeleteSubmitLabel(rows: DocumentApiRow[]): string {
    if (documentDeleteSubmitting) return 'Deleting…';
    return rows.length === 1 ? 'Delete document' : 'Delete documents';
  }

  /**
   * What the confirmation removes, in a reader's words. The names come from the snapshot, and the count
   * is the snapshot's own, so what is confirmed is what was selected when the dialog opened.
   */
  function documentDeleteSentence(rows: DocumentApiRow[]): string {
    const collectionName = selected?.name ?? 'this collection';
    if (rows.length === 1) {
      return `This permanently removes ${rows[0].originalFilename} from ${collectionName} and everything ` +
        'InfoScry holds for it. The original file outside InfoScry is not touched, and there is no undo.';
    }
    return `This permanently removes the ${rows.length} selected documents from ${collectionName} and ` +
      'everything InfoScry holds for them. The original files outside InfoScry are not touched, and there ' +
      'is no undo.';
  }

  /**
   * Reads the unfinished deletions when Admin is opened, so one admitted before a reload or a restart is
   * shown again rather than forgotten with the browser's memory.
   */
  onMount(() => {
    void loadDeletions();
  });

  onDestroy(() => {
    // Unmounting ends the history's and the deletion's polling and drops any answer still on its way: a
    // timer left behind would keep reading a panel that nobody shows, and a late response would fill
    // state nobody reads.
    importsGeneration += 1;
    if (refreshTimer !== null) clearTimeout(refreshTimer);
    refreshTimer = null;
    deletionsGeneration += 1;
    if (deletionTimer !== null) clearTimeout(deletionTimer);
    deletionTimer = null;
  });
</script>

<section class="collections-panel" aria-label="Collection administration">
  {#if loading}
    <p role="status">Loading collections…</p>
  {:else if error !== null}
    <p role="alert">{error}</p>
  {:else}
    {#if collections.length === 0}
      <div class="empty-archive">
        <p>No collections yet</p>
        <p class="hint">
          Collections hold your documents, and every import goes into the collection you choose. Create
          one to import into.
        </p>
        <button type="button" onclick={startCreate}>Create collection</button>
      </div>
    {:else}
      <div class="collection-list">
        <span class="eyebrow">COLLECTIONS</span>
        <ul>
          {#each collections as collection (collection.id)}
            <li>
              <button
                type="button"
                class:active={collection.id === selectedId}
                aria-pressed={collection.id === selectedId}
                onclick={() => onSelect(collection.id)}
              >
                <span class="name">{collection.name}</span>
                <span class="count">{countLabel(collection.documentCount)}</span>
              </button>
            </li>
          {/each}
        </ul>
        <div><button type="button" onclick={startCreate} disabled={creating}>Create collection</button></div>
      </div>
    {/if}

    {#if creating}
      <form class="collection-form" onsubmit={create}>
        <span class="eyebrow">NEW COLLECTION</span>
        {#if creatingError !== null}<p role="alert">{creatingError}</p>{/if}
        <div class="field">
          <label for="new-collection-name">Name</label>
          <input id="new-collection-name" bind:value={newName} required />
        </div>
        <div class="field">
          <label for="new-collection-description">Description</label>
          <input id="new-collection-description" bind:value={newDescription} />
        </div>
        <div class="actions">
          <button type="submit" class="primary" disabled={saving || newName.trim() === ''}>
            {saving ? 'Creating…' : 'Create'}
          </button>
          <button type="button" onclick={cancelCreate} disabled={saving}>Cancel</button>
        </div>
      </form>
    {/if}

    {#if deletions.length > 0}
      <section class="deletions" aria-label="Deletions in progress">
        <span class="eyebrow">DELETIONS</span>
        {#if deletionsError !== null}<p role="alert">{deletionsError}</p>{/if}
        <ul>
          {#each deletions as deletion (deletion.operationId)}
            <li>
              {#if deletionIsPending(deletion)}
                <p role="status">{deletionMessage(deletion)}</p>
              {:else}
                <p role="alert">{deletionMessage(deletion)}</p>
              {/if}
            </li>
          {/each}
        </ul>
      </section>
    {/if}

    {#if selected === null}
      <p class="hint">Select a collection to manage its documents.</p>
    {:else}
      {#if selected.id === LEGACY_DEFAULT_ID}
        <section class="legacy-default" aria-label="About the former Default collection">
          <span class="eyebrow">FORMER DEFAULT COLLECTION</span>
          <p>
            Earlier InfoScry versions created this collection automatically. New archives start without
            collections, and every import goes into the collection you choose. This one is kept because
            it may hold your material: rename it under Settings to keep it under your own name, or
            delete it after confirming its exact name. Nothing here is changed or removed unless you
            choose it.
          </p>
        </section>
      {/if}
      <section class="management" aria-label={`Manage ${selected.name}`}>
        <div class="management-head">
          <p class="selected-name">{selected.name}</p>
          <p class="hint">{countLabel(selected.documentCount)}</p>
        </div>
        <section class="collection-settings" aria-label={`Settings for ${selected.name}`}>
          <span class="eyebrow">SETTINGS</span>

          <form class="settings-form" onsubmit={saveName}>
            <div class="field">
              <label for="collection-settings-name">Collection name</label>
              <input
                id="collection-settings-name"
                bind:value={renameName}
                oninput={() => (renameSaved = false)}
                aria-invalid={renameError !== null}
              />
            </div>
            <div class="actions">
              <button type="submit" class="primary" disabled={renameSaving}>
                {renameSaving ? 'Saving name…' : 'Save name'}
              </button>
            </div>
            {#if renameError !== null}<p role="alert">{renameError}</p>{/if}
            {#if renameSaved}<p role="status">Name saved.</p>{/if}
          </form>

          <form class="settings-form" onsubmit={saveLanguages}>
            <div class="field">
              <label for="collection-settings-ocr-languages">OCR languages</label>
              <input
                id="collection-settings-ocr-languages"
                bind:value={ocrLanguages}
                oninput={() => (ocrSaved = false)}
                aria-invalid={ocrError !== null}
                aria-describedby="collection-settings-ocr-note"
              />
            </div>
            <div class="actions">
              <button type="submit" class="primary" disabled={ocrSaving}>
                {ocrSaving ? 'Saving languages…' : 'Save OCR languages'}
              </button>
            </div>
            {#if ocrError !== null}<p role="alert">{ocrError}</p>{/if}
            {#if ocrSaved}<p role="status">OCR languages saved.</p>{/if}
            <p class="hint" id="collection-settings-ocr-note">
              Saved languages are used by future imports and by explicit retries. Completed documents are
              not reprocessed automatically; a retry that needs different languages may have to repeat
              extraction for the documents it targets.
            </p>
          </form>

          <form class="settings-form" onsubmit={saveIgnorePatterns}>
            <div class="field">
              <label for="collection-settings-ignore">Ignored files</label>
              <textarea
                id="collection-settings-ignore"
                rows="8"
                spellcheck="false"
                autocapitalize="off"
                bind:value={ignoreText}
                oninput={() => (ignoreSaved = false)}
                disabled={!ignoreLoaded}
                aria-invalid={ignoreError !== null && ignoreLoaded}
                aria-describedby="collection-settings-ignore-note"
              ></textarea>
            </div>
            <div class="actions">
              <button type="submit" class="primary" disabled={ignoreSaving || !ignoreLoaded}>
                {ignoreSaving ? 'Saving ignored files…' : 'Save ignored files'}
              </button>
            </div>
            {#if ignoreError !== null}<p role="alert">{ignoreError}</p>{/if}
            {#if ignoreSaved}<p role="status">Ignored files saved.</p>{/if}
            <p class="hint" id="collection-settings-ignore-note">
              One pattern per line, like a .gitignore file: * and ? match within a name, ** matches across folders, a
              trailing / matches a folder (its contents are never read), ! re-includes, and # starts a comment. Every
              import into this collection skips matching files before anything else. A change applies to imports
              started afterwards, not to one already queued or running.
            </p>
          </form>

          <CollectionOcrSettings collection={selected} onChanged={() => onCollectionsChanged()} {onOpenOcrProfiles} />
        </section>

        <section class="collection-deletion" aria-label={`Delete ${selected.name}`}>
          <span class="eyebrow">DELETE COLLECTION</span>
          <p class="hint">
            Deleting a collection removes every document InfoScry holds for it. Files outside InfoScry are
            not touched, and there is no undo.
          </p>
          <div class="actions">
            <button type="button" onclick={() => void openDeleteDialog()}>Delete collection</button>
          </div>
        </section>

        <div>
          <button type="button" aria-expanded={addingDocuments} onclick={() => (addingDocuments = true)}>
            Add documents
          </button>
        </div>
        {#if addingDocuments}
          {#key selected.id}
            <ImportPanel collectionId={selected.id} collectionName={selected.name} />
          {/key}
        {/if}

        <div class="document-controls">
          <form class="document-search" onsubmit={searchDocuments}>
            <div class="field">
              <label for="document-search">Search filenames</label>
              <input id="document-search" bind:value={filenameInput} placeholder="Filename contains…" />
            </div>
            <button type="submit">Search</button>
          </form>
          <div class="field">
            <label for="document-status">Status</label>
            <select id="document-status" bind:value={statusFilter} onchange={changeFilter}>
              <option value="">Any status</option>
              {#each Object.entries(STATUS_LABELS) as [value, label] (value)}
                <option {value}>{label}</option>
              {/each}
            </select>
          </div>
          <div class="field">
            <label for="document-sort">Sort</label>
            <select id="document-sort" bind:value={sort} onchange={changeFilter}>
              {#each SORTS as option (option.value)}
                <option value={option.value}>{option.label}</option>
              {/each}
            </select>
          </div>
        </div>

        <section class="collection-retry" aria-label={`Retry documents in ${selected.name}`}>
          <span class="eyebrow">RETRY</span>
          <p class="hint" id="retry-all-scope">
            Retry all reads every failed, cancelled or tool-blocked document in {selected.name} again from the
            copies InfoScry holds. It covers the whole collection, across every page: the search term, status
            filter, sort order and current page do not restrict which documents it selects. Completed
            documents, and documents that only have warnings, are left alone.
          </p>
          <label class="choice-toggle">
            <input type="checkbox" bind:checked={retryAllChoiceOpen} />
            Choose the OCR method for this retry
          </label>
          {#if retryAllChoiceOpen}
            <p class="hint">
              A choice left on the collection default uses the collection's settings. The collection itself is not
              changed, and documents that already have a published text are left to Scan again.
            </p>
            <OcrChoiceFields idPrefix="retry-all" purpose="retry" showLanguage bind:choice={retryAllChoiceValue} />
          {/if}
          <div class="actions">
            <button
              type="button"
              aria-describedby="retry-all-scope"
              disabled={retryAllSubmitting}
              onclick={() => void retryAll()}
            >
              {retryAllSubmitting ? 'Retrying…' : 'Retry all eligible documents'}
            </button>
          </div>
          {#if retryAllMessage !== null}<p role="status">{retryAllMessage}</p>{/if}
          {#if retryAllError !== null}<p role="alert">{retryAllError}</p>{/if}
          {#if retryAllRejected.length > 0}
            <ul class="retry-refusals">
              {#each retryRefusals() as refusal (refusal.reason)}
                <li>{refusal.label}</li>
              {/each}
            </ul>
          {/if}
        </section>

        {#if documentsLoading && !documentsLoaded}
          <p role="status">Loading documents…</p>
        {:else if documentsError !== null}
          <p role="alert">{documentsError}</p>
        {:else if documents.length === 0}
          {#if filenameQuery === '' && statusFilter === ''}
            <p>No documents yet</p>
          {:else}
            <p>No documents match this search.</p>
          {/if}
        {:else}
          <div class="table-scroll">
            <table class="documents">
              <caption>Documents in {selected.name}</caption>
              <thead>
                <tr>
                  <th scope="col">
                    <input
                      type="checkbox"
                      aria-label={`Select all ${documents.length} ${documents.length === 1 ? 'document' : 'documents'} on this page`}
                      checked={allOnPageSelected()}
                      onchange={toggleAllOnPage}
                    />
                  </th>
                  <th scope="col">Filename</th>
                  <th scope="col">Media type</th>
                  <th scope="col">Size</th>
                  <th scope="col">Imported</th>
                  <th scope="col">Status</th>
                  <th scope="col">Progress</th>
                  <th scope="col">Details</th>
                  <th scope="col">Delete</th>
                </tr>
              </thead>
              <tbody>
                {#each documents as row (row.id)}
                  <tr>
                    <td class="select-cell">
                      <input
                        type="checkbox"
                        aria-label={`Select ${row.originalFilename}`}
                        checked={selectedDocumentIds.has(row.id)}
                        onchange={(event) => toggleSelected(row, (event.currentTarget as HTMLInputElement).checked)}
                      />
                    </td>
                    <th scope="row">{row.originalFilename}</th>
                    <td>{row.mediaType}</td>
                    <td>{formatSize(row.sizeBytes)}</td>
                    <td>{formatDate(row.createdAt)}</td>
                    <td>{statusLabel(row.status)}</td>
                    <td>{progressLabel(row.progress)}</td>
                    <td>
                      <button type="button" onclick={() => void openDetails(row.id)}>Details</button>
                    </td>
                    <td>
                      <button
                        type="button"
                        aria-label={`Delete ${row.originalFilename}`}
                        onclick={() => void openDocumentDeleteDialog([row])}
                      >
                        Delete
                      </button>
                    </td>
                  </tr>
                {/each}
              </tbody>
            </table>
          </div>
          <div class="bulk-actions">
            <p class="hint" id="document-selection-scope">
              Select all checks only the {documents.length} {documents.length === 1 ? 'document' : 'documents'}
              on this page; documents on other pages stay unchecked.
              {#if selectedDocumentIds.size > 0}
                {selectedDocumentIds.size} selected on this page.
              {/if}
            </p>
            <div class="actions">
              <button
                type="button"
                class="primary"
                disabled={selectedDocumentIds.size === 0}
                onclick={() => void openDocumentDeleteDialog(selectedDocuments())}
              >
                Delete selected
              </button>
              <button type="button" disabled={selectedDocumentIds.size === 0} onclick={clearSelection}>
                Clear selection
              </button>
            </div>
          </div>
          <div class="paging">
            <p class="hint">
              Showing {offset + 1}–{offset + documents.length} of {documentsTotal}
              {documentsTotal === 1 ? 'document' : 'documents'}
            </p>
            <div class="actions">
              <button type="button" disabled={offset === 0 || documentsLoading} onclick={() => changePage(-1)}>
                Previous page
              </button>
              <button
                type="button"
                disabled={offset + PAGE_SIZE >= documentsTotal || documentsLoading}
                onclick={() => changePage(1)}
              >
                Next page
              </button>
            </div>
          </div>
        {/if}

        {#if detailLoading || detailError !== null || detail !== null}
          <section class="document-details" aria-label="Document details">
            <span class="eyebrow">DOCUMENT DETAILS</span>
            {#if detailLoading}
              <p role="status">Loading details…</p>
            {:else if detailError !== null}
              <p role="alert">{detailError}</p>
            {:else if detail !== null}
              <dl>
                <dt>Filename</dt>
                <dd>{detail.document.originalFilename}</dd>
                <dt>Media type</dt>
                <dd>{detail.document.mediaType}</dd>
                <dt>Size</dt>
                <dd>{formatSize(detail.document.sizeBytes)}</dd>
                <dt>Imported</dt>
                <dd>{formatDate(detail.document.createdAt)}</dd>
                <dt>Status</dt>
                <dd>{statusLabel(detail.document.status)}</dd>
              </dl>
              {#if detail.progress !== null && detail.progress !== undefined}
                <dl class="progress">
                  <dt>Progress</dt>
                  <dd>{progressSentence(detail.progress, detail.document.status)}</dd>
                  {#if detail.progress.processedUnits > 0}
                    <dt>Read directly</dt>
                    <dd>{methodCount(detail.progress.directTextUnits, detail.progress)}</dd>
                    <dt>Read by OCR</dt>
                    <dd>{methodCount(detail.progress.ocrUnits, detail.progress)}</dd>
                  {/if}
                  {#if detail.progress.failedUnits > 0}
                    <dt>Units that could not be read</dt>
                    <dd>{detail.progress.failedUnits}</dd>
                  {/if}
                </dl>
                {#if needsOcrScopeHint(detail.progress, detail.document.status)}
                  <p class="hint">
                    The denominator counts every {unitLabel(detail.progress.unitKind, 1)} of the document, not only the
                    ones that needed OCR.
                  </p>
                {/if}
              {/if}
              {#if detail.errorMessage !== null && detail.errorMessage !== undefined}
                <p role="alert">{detail.errorMessage}</p>
              {/if}
              {#if detail.warnings !== undefined && detail.warnings.length > 0}
                <ul class="warnings">
                  {#each detail.warnings as warning (warning)}
                    <li>{warning}</li>
                  {/each}
                </ul>
              {/if}
              {#if detail.sourceId === null || detail.sourceId === undefined}
                <p>This document has no readable content yet, so there is nothing to open.</p>
              {:else}
                <div class="actions">
                  <button type="button" class="primary" onclick={() => openDocument(detail)}>Open document</button>
                </div>
              {/if}
              {#if detail.retryEligible}
                <label class="choice-toggle">
                  <input type="checkbox" bind:checked={retryChoiceOpen} />
                  Choose the OCR method for this retry
                </label>
                {#if retryChoiceOpen}
                  <p class="hint">
                    A choice left on the collection default uses the collection's settings. A document that already
                    has a published text is read again with Scan again instead, which shows the new reading before
                    it replaces the old one.
                  </p>
                  <OcrChoiceFields idPrefix="retry" purpose="retry" showLanguage bind:choice={retryChoiceValue} />
                {/if}
                <div class="actions">
                  <button type="button" onclick={() => void retryDocument(detail)} disabled={retrySubmitting}>
                    {retrySubmitting ? 'Retrying…' : 'Retry'}
                  </button>
                </div>
                <p class="hint">
                  Retry reads this document again from the copy InfoScry holds, keeping its identity and any
                  part of it that was already read successfully. If the collection's OCR languages changed
                  since the last attempt, extraction may need to be repeated.
                </p>
                {#if retryMessage !== null}<p role="status">{retryMessage}</p>{/if}
                {#if retryError !== null}<p role="alert">{retryError}</p>{/if}
              {/if}
            {/if}
            {#if detail !== null}
              {#key `${selected.id}:${detail.document.id}`}
                <DocumentRescan
                  collectionId={selected.id}
                  documentId={detail.document.id}
                  documentName={detail.document.originalFilename}
                />
                <OcrHistoryPanel
                  collectionId={selected.id}
                  documentId={detail.document.id}
                  documentName={detail.document.originalFilename}
                />
              {/key}
            {/if}
            <div class="actions">
              <button type="button" onclick={closeDetails}>Close details</button>
            </div>
          </section>
        {/if}

        <section class="import-history" aria-label={`Import history for ${selected.name}`}>
          <span class="eyebrow">IMPORT HISTORY</span>
          {#if importsLoading && !importsLoaded}
            <p class="hint">Loading imports…</p>
          {:else if importsError !== null}
            <p role="alert">{importsError}</p>
          {:else if imports.length === 0}
            <p>No imports yet</p>
          {:else}
            <div class="table-scroll">
              <table class="imports">
                <caption>Imports into {selected.name}</caption>
                <thead>
                  <tr>
                    <th scope="col">Started</th>
                    <th scope="col">State</th>
                    <th scope="col">Stage</th>
                    <th scope="col">Files</th>
                    <th scope="col">Per-file results</th>
                  </tr>
                </thead>
                <tbody>
                  {#each imports as entry (entry.id)}
                    <tr>
                      <th scope="row">{formatDate(entry.createdAt)}</th>
                      <td>{JOB_STATE_LABELS[entry.state] ?? entry.state}{entry.errorCode ? ` (${entry.errorCode})` : ''}</td>
                      <td>{stageCell(entry)}</td>
                      <td>{fileCountLabel(entry.filesCompleted, entry.filesTotal)}</td>
                      <td>
                        <button
                          type="button"
                          aria-expanded={itemsJobId === entry.id}
                          aria-label={`${itemsJobId === entry.id ? 'Hide' : 'Show'} files for the import started ${formatDate(entry.createdAt)}`}
                          onclick={() => toggleItems(entry.id)}
                        >
                          {itemsJobId === entry.id ? 'Hide files' : 'Show files'}
                        </button>
                      </td>
                    </tr>
                  {/each}
                </tbody>
              </table>
            </div>
            <div class="paging">
              <p class="hint">
                Showing {importsOffset + 1}–{importsOffset + imports.length} of {importsTotal}
                {importsTotal === 1 ? 'import' : 'imports'}
              </p>
              <div class="actions">
                <button
                  type="button"
                  disabled={importsOffset === 0 || importsLoading}
                  onclick={() => changeImportsPage(-1)}
                >
                  Previous imports
                </button>
                <button
                  type="button"
                  disabled={importsOffset + PAGE_SIZE >= importsTotal || importsLoading}
                  onclick={() => changeImportsPage(1)}
                >
                  Next imports
                </button>
              </div>
            </div>
          {/if}

          {#if itemsJobId !== null && (itemsLoading || itemsError !== null || items.length > 0)}
            <section class="import-items" aria-label="Import files">
              <span class="eyebrow">FILES IN THIS IMPORT</span>
              {#if itemsLoading}
                <p class="hint">Loading files…</p>
              {:else if itemsError !== null}
                <p role="alert">{itemsError}</p>
              {:else}
                <table class="imports">
                  <caption>Files in this import</caption>
                  <thead>
                    <tr>
                      <th scope="col">File</th>
                      <th scope="col">Outcome</th>
                      <th scope="col">Result</th>
                    </tr>
                  </thead>
                  <tbody>
                    {#each items as item (item.id)}
                      <tr>
                        <th scope="row">{itemName(item)}</th>
                        <td>{importItemOutcomeLabel(item)}</td>
                        <td>
                          {#if item.errorMessage !== null && item.errorMessage !== undefined}
                            <p>{item.errorMessage}</p>
                          {/if}
                          {#if needsResubmission(item)}
                            <p>This file was not added. Add it again through Add documents.</p>
                          {/if}
                        </td>
                      </tr>
                    {/each}
                  </tbody>
                </table>
                <p class="hint">{items.length} {items.length === 1 ? 'file' : 'files'} in this import</p>
              {/if}
            </section>
          {/if}
        </section>
      </section>
    {/if}
  {/if}

  {#if deletingDocuments !== null}
    <div class="confirm-backdrop" aria-hidden="true" onclick={closeDocumentDeleteDialog}></div>
    <div
      class="confirm-dialog"
      role="dialog"
      aria-modal="true"
      aria-labelledby="delete-document-heading"
      bind:this={documentDeleteDialog}
      tabindex="-1"
      onkeydown={handleDocumentDeleteKeydown}
    >
      <form class="confirm-form" onsubmit={submitDocumentDelete}>
        <h2 id="delete-document-heading">{documentDeleteHeading(deletingDocuments)}</h2>
        <p>{documentDeleteSentence(deletingDocuments)}</p>
        <p>
          A running import or OCR pass for {deletingDocuments.length === 1 ? 'this document' : 'these documents'}
          is interrupted after its current step. Other documents, including others in the same import, are
          not affected.
        </p>
        {#if deletingDocuments.length > 1}
          <ul class="confirm-list">
            {#each deletingDocuments as row (row.id)}
              <li>{row.originalFilename}</li>
            {/each}
          </ul>
        {/if}
        {#if documentDeleteError !== null}<p role="alert">{documentDeleteError}</p>{/if}
        <div class="actions">
          <button type="submit" class="primary" disabled={documentDeleteSubmitting}>
            {documentDeleteSubmitLabel(deletingDocuments)}
          </button>
          <button type="button" onclick={closeDocumentDeleteDialog} disabled={documentDeleteSubmitting}>Cancel</button>
        </div>
      </form>
    </div>
  {/if}

  {#if deleting !== null}
    <div class="confirm-backdrop" aria-hidden="true" onclick={closeDeleteDialog}></div>
    <div
      class="confirm-dialog"
      role="dialog"
      aria-modal="true"
      aria-labelledby="delete-collection-heading"
      bind:this={deleteDialog}
      tabindex="-1"
      onkeydown={handleDeleteKeydown}
    >
      <form class="confirm-form" onsubmit={submitDelete}>
        <h2 id="delete-collection-heading">Delete {deleting.name}?</h2>
        <p>
          This permanently removes the collection and every document InfoScry holds for it. Original files
          outside InfoScry are not touched, and there is no undo.
        </p>
        <p>
          Work still running for this collection is cancelled after its current step, and no new import into
          it can start.
        </p>
        <div class="field">
          <label for="delete-collection-name">Type the collection name to confirm</label>
          <input
            id="delete-collection-name"
            bind:this={deleteNameInput}
            bind:value={confirmName}
            autocomplete="off"
            aria-describedby="delete-collection-hint"
          />
        </div>
        <p class="hint" id="delete-collection-hint">The name has to match exactly.</p>
        {#if deleteError !== null}<p role="alert">{deleteError}</p>{/if}
        <div class="actions">
          <button type="submit" class="primary" disabled={deleteSubmitting || confirmName.trim() !== deleting.name}>
            {deleteSubmitting ? 'Deleting…' : 'Delete collection'}
          </button>
          <button type="button" onclick={closeDeleteDialog} disabled={deleteSubmitting}>Cancel</button>
        </div>
      </form>
    </div>
  {/if}
</section>

<style>
  .collections-panel { max-width: 52rem; display: grid; gap: 1.25rem; align-content: start; }
  .empty-archive { display: grid; gap: 0.75rem; justify-items: start; padding: 1.1rem; border: 1px solid #303535; border-radius: 0.55rem; background: #191c1d; }
  .empty-archive p { margin: 0; }
  .legacy-default { display: grid; gap: 0.5rem; border: 1px solid #303535; border-radius: 0.55rem; background: #191c1d; padding: 1rem; }
  .legacy-default p { margin: 0; max-width: 46rem; }
  .collection-list { display: grid; gap: 0.55rem; justify-items: start; }
  .collection-list ul { width: 100%; margin: 0; padding: 0; list-style: none; border-top: 1px solid #2b3030; }
  .collection-list li { border-bottom: 1px solid #2b3030; }
  .collection-list li button { width: 100%; display: flex; align-items: center; justify-content: space-between; gap: 0.75rem; border: 0; border-radius: 0; background: transparent; padding: 0.6rem 0.1rem; text-align: left; }
  .collection-list li button.active { color: #f3e6d1; font-weight: 600; }
  .collection-list li button.active .name { text-decoration: underline; text-underline-offset: 0.25em; }
  .collection-list .count { color: #929997; font-size: 0.8rem; font-weight: 400; }
  .collection-form { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0.9rem 1rem; padding: 1rem; border: 1px solid #303535; border-radius: 0.55rem; background: #191c1d; }
  .collection-form .eyebrow { grid-column: 1 / -1; }
  .collection-form [role='alert'] { grid-column: 1 / -1; }
  .field { display: grid; gap: 0.35rem; align-content: start; }
  label { color: #b8bcbb; font-size: 0.82rem; }
  input:not([type='checkbox']) { min-width: 0; border: 1px solid #383d3e; border-radius: 0.48rem; background: #202324; padding: 0.62rem 0.72rem; color: #e8e9e7; }
  select { min-width: 0; border: 1px solid #383d3e; border-radius: 0.48rem; background: #202324; padding: 0.62rem 0.72rem; color: #e8e9e7; }
  .actions { display: flex; gap: 0.55rem; }
  .collection-form .actions { grid-column: 1 / -1; }
  .management { display: grid; gap: 0.85rem; padding-top: 1.25rem; border-top: 1px solid #2d3131; }
  .management-head { display: flex; align-items: baseline; justify-content: space-between; gap: 0.75rem; }
  .management-head .selected-name { margin: 0; font-size: 1.05rem; }
  .management p { margin: 0; }
  .collection-settings { display: grid; gap: 0.9rem; border: 1px solid #303535; border-radius: 0.55rem; background: #191c1d; padding: 1rem; }
  .settings-form { display: grid; gap: 0.6rem; }
  .settings-form .actions { justify-content: start; }
  .settings-form textarea { font: inherit; font-family: ui-monospace, SFMono-Regular, Menlo, monospace; padding: 0.5rem; resize: vertical; width: 100%; box-sizing: border-box; }
  .hint { color: #929997; font-size: 0.82rem; }
  .eyebrow { color: #858a8a; font-size: 0.68rem; font-weight: 700; letter-spacing: 0.12em; }
  button.primary { border-color: #c4a77d; background: #c4a77d; color: #1c1b18; font-weight: 650; }
  button.primary:hover:not(:disabled) { background: #d4ba94; }
  .document-controls { display: grid; grid-template-columns: minmax(0, 2fr) minmax(0, 1fr) minmax(0, 1fr); gap: 0.75rem; align-items: end; padding-top: 0.35rem; }
  .collection-retry { display: grid; justify-items: start; gap: 0.6rem; border: 1px solid #303535; border-radius: 0.55rem; background: #191c1d; padding: 1rem; }
  .collection-retry p { margin: 0; }
  .choice-toggle { display: flex; align-items: center; gap: 0.5rem; color: #b8bcbb; font-size: 0.85rem; }
  .retry-refusals { margin: 0; padding-left: 1.1rem; max-height: 8rem; overflow-y: auto; color: #d8c9a8; font-size: 0.82rem; }
  .document-search { display: flex; gap: 0.55rem; align-items: end; }
  .document-search .field { flex: 1 1 auto; }
  /* A page-wide table on a narrow window: the table scrolls inside the panel instead of pushing the
     whole page sideways. */
  .table-scroll { min-width: 0; overflow-x: auto; }
  table.documents { width: 100%; border-collapse: collapse; font-size: 0.85rem; }
  table.documents caption { padding-bottom: 0.5rem; color: #858a8a; font-size: 0.68rem; font-weight: 700; letter-spacing: 0.12em; text-align: left; text-transform: uppercase; }
  table.documents th, table.documents td { border-bottom: 1px solid #2b3030; padding: 0.55rem 0.5rem; text-align: left; vertical-align: top; }
  table.documents thead th { color: #858a8a; font-size: 0.72rem; font-weight: 700; letter-spacing: 0.06em; text-transform: uppercase; }
  table.documents tbody th { font-weight: 600; overflow-wrap: anywhere; }
  table.documents .select-cell { width: 2rem; }
  .bulk-actions { display: flex; align-items: center; justify-content: space-between; gap: 0.75rem; flex-wrap: wrap; }
  .bulk-actions p { margin: 0; }
  .confirm-list { margin: 0; padding-left: 1.1rem; max-height: 8rem; overflow-y: auto; }
  .paging { display: flex; align-items: center; justify-content: space-between; gap: 0.75rem; }
  .document-details { display: grid; gap: 0.6rem; border: 1px solid #303535; border-radius: 0.55rem; background: #191c1d; padding: 1rem; }
  .document-details dl { display: grid; grid-template-columns: auto minmax(0, 1fr); gap: 0.3rem 0.9rem; margin: 0; font-size: 0.85rem; }
  .document-details dl.progress { padding-top: 0.35rem; border-top: 1px solid #2b3030; }
  .document-details .warnings { margin: 0; padding-left: 1.1rem; color: #d8c9a8; font-size: 0.82rem; }
  .document-details dt { color: #b8bcbb; }
  .document-details dd { margin: 0; overflow-wrap: anywhere; }
  .import-history { display: grid; gap: 0.85rem; padding-top: 1.25rem; border-top: 1px solid #2d3131; }
  .import-history p { margin: 0; }
  table.imports { width: 100%; border-collapse: collapse; font-size: 0.85rem; }
  table.imports caption { padding-bottom: 0.5rem; color: #858a8a; font-size: 0.68rem; font-weight: 700; letter-spacing: 0.12em; text-align: left; text-transform: uppercase; }
  table.imports th, table.imports td { border-bottom: 1px solid #2b3030; padding: 0.55rem 0.5rem; text-align: left; vertical-align: top; }
  table.imports thead th { color: #858a8a; font-size: 0.72rem; font-weight: 700; letter-spacing: 0.06em; text-transform: uppercase; }
  table.imports tbody th { font-weight: 600; overflow-wrap: anywhere; }
  table.imports td p + p { margin-top: 0.35rem; color: #d8c9a8; }
  .import-items { display: grid; gap: 0.6rem; border: 1px solid #303535; border-radius: 0.55rem; background: #191c1d; padding: 1rem; }
  .deletions { display: grid; gap: 0.5rem; border: 1px solid #303535; border-radius: 0.55rem; background: #191c1d; padding: 1rem; }
  .deletions ul { display: grid; gap: 0.3rem; margin: 0; padding-left: 1.1rem; }
  .deletions p { margin: 0; }
  .collection-deletion { display: grid; justify-items: start; gap: 0.6rem; border: 1px solid #303535; border-radius: 0.55rem; background: #191c1d; padding: 1rem; }
  .collection-deletion p { margin: 0; }
  .confirm-backdrop { position: fixed; inset: 0; background: rgba(8, 9, 10, 0.62); }
  .confirm-dialog { position: fixed; top: 50%; left: 50%; z-index: 2; transform: translate(-50%, -50%); width: min(32rem, calc(100vw - 2rem)); border: 1px solid #3a3e3e; border-radius: 0.6rem; background: #1d2021; padding: 1.15rem; }
  .confirm-form { display: grid; gap: 0.7rem; }
  .confirm-dialog h2 { margin: 0; font-size: 1.05rem; }
  .confirm-dialog p { margin: 0; }
  .confirm-dialog .actions { justify-content: end; }
  .confirm-dialog .confirm-list li { overflow-wrap: anywhere; }
  input[type='checkbox'] { width: 1rem; height: 1rem; accent-color: #c4a77d; }
  @media (max-width: 36rem) {
    .collection-form { grid-template-columns: minmax(0, 1fr); }
    .document-controls { grid-template-columns: minmax(0, 1fr); }
  }
</style>