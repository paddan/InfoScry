<script lang="ts">
  import { onMount } from 'svelte';
  import AskPanel from '../lib/AskPanel.svelte';
  import InvestigatePanel from '../lib/InvestigatePanel.svelte';
  import LlmAdminPanel from '../lib/LlmAdminPanel.svelte';
  import ImportPanel from '../lib/ImportPanel.svelte';
  import {
    ApiError,
    listCollections,
    readSource,
    searchCollection,
    type AskEvidence,
    type Collection,
    type InvestigateEvidence,
    type LlmProfilePrice,
    type SearchHit,
    type SearchFilters,
    type SearchMode,
    type SourceContentResponse,
  } from '../lib/api';

  let collections: Collection[] = [];
  let selectedCollectionId = '';
  let loadingCollections = true;
  let searching = false;
  let query = '';
  let mode: SearchMode = 'HYBRID';
  let activeMode: 'SEARCH' | 'ASK' | 'INVESTIGATE' | 'ADMIN' = 'SEARCH';
  let adminTab: 'LLM' | 'IMPORT' = 'LLM';
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
  let selectedHit: SearchHit | null = null;
  let source: SourceContentResponse | null = null;
  let sourceText = '';
  let loadingSource = false;
  let sourceError: string | null = null;
  let sourceGeneration = 0;

  async function refresh(): Promise<void> {
    loadingCollections = true;
    try {
      collections = await listCollections();
      if (!collections.some((collection) => collection.id === selectedCollectionId)) {
        selectedCollectionId = collections[0]?.id ?? '';
      }
      collectionError = null;
    } catch (failure) {
      collectionError = describe(failure);
    } finally {
      loadingCollections = false;
    }
  }

  async function search(event: SubmitEvent): Promise<void> {
    event.preventDefault();
    const text = query.trim();
    if (text === '' || selectedCollectionId === '' || searching) return;

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

  async function openSource(hit: SearchHit): Promise<void> {
    const generation = ++sourceGeneration;
    selectedHit = hit;
    source = null;
    sourceText = '';
    sourceError = null;
    loadingSource = true;
    try {
      const page = await readSource(selectedCollectionId, hit.unitId);
      if (generation !== sourceGeneration || hit.collectionId !== selectedCollectionId) return;
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
      const page = await readSource(selectedCollectionId, hit.unitId, source.offset + source.text.length);
      if (generation !== sourceGeneration || hit.collectionId !== selectedCollectionId) return;
      source = page;
      sourceText += page.text;
    } catch (failure) {
      if (generation === sourceGeneration) sourceError = describe(failure);
    } finally {
      if (generation === sourceGeneration) loadingSource = false;
    }
  }

  function invalidateSearch(): void {
    searchGeneration += 1;
    searching = false;
    hits = [];
    hasSearched = false;
    searchError = null;
    clearSelectedSource();
  }

  function changeCollection(): void {
    invalidateSearch();
  }

  function selectMode(modeName: 'SEARCH' | 'ASK' | 'INVESTIGATE' | 'ADMIN'): void {
    activeMode = modeName;
  }

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
    activeMode = tabs[next];
    document.getElementById(`tab-${activeMode.toLowerCase()}`)?.focus();
  }

  function handleAdminTabKeydown(event: KeyboardEvent): void {
    const tabs: ('LLM' | 'IMPORT')[] = ['LLM', 'IMPORT'];
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
    loadingSource = false;
    sourceError = null;
  }

  function originalHref(hit: SearchHit): string {
    return `/api/collections/${encodeURIComponent(hit.collectionId)}/documents/${encodeURIComponent(hit.documentId)}/original`;
  }

  function openInvestigationSource(evidence: InvestigateEvidence): Promise<void> {
    return openSource({
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
    });
  }

  function describe(failure: unknown): string {
    if (failure instanceof ApiError) return failure.message;
    return failure instanceof Error ? failure.message : 'Something went wrong.';
  }

  async function handleCollectionsChanged(selectedId?: string): Promise<void> {
    await refresh();
    if (selectedId !== undefined && collections.some((collection) => collection.id === selectedId)) {
      selectedCollectionId = selectedId;
    }
  }

  onMount(() => {
    refresh();
  });
</script>

<div class="app-shell">
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
          <select id="mode" bind:value={mode} onchange={invalidateSearch}>
            <option value="KEYWORD">Keyword</option>
            <option value="SEMANTIC">Semantic</option>
            <option value="HYBRID">Hybrid</option>
          </select>
          <details>
            <summary>Advanced search filters</summary>
            <div class="filter-fields">
              <label for="media-type">Media type</label>
              <select id="media-type" bind:value={mediaType}>
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
              <input id="path-contains" aria-label="Path contains" bind:value={pathContains} />
              <label for="text-contains">Title, author or language</label>
              <input id="text-contains" bind:value={textContains} />
              <label for="imported-from">Imported from</label>
              <input id="imported-from" type="date" bind:value={importedFrom} />
              <label for="imported-until">Imported until</label>
              <input id="imported-until" type="date" bind:value={importedUntil} />
              <label for="status-filter">Document status</label>
              <select id="status-filter" bind:value={statusFilter}>
                <option value="">Any status</option>
                <option value="COMPLETE">Complete</option>
                <option value="COMPLETE_WITH_WARNINGS">Complete with warnings</option>
                <option value="FAILED">Failed</option>
                <option value="NEEDS_TOOL">Needs tool</option>
              </select>
              <label class="check-label"><input type="checkbox" bind:checked={ocrOnly} /> OCR only</label>
            </div>
          </details>
        </div>
      {:else if activeMode === 'ADMIN'}
        <div class="sidebar-note">
          <span class="eyebrow">ADMINISTRATION</span>
          {#if adminTab === 'LLM'}
            <p>Configure the LLM profiles Ask and Investigate can use. API keys stay in environment variables.</p>
          {:else}
            <p>Import local files and folders into a collection. Choosing paths opens a native dialog on this machine.</p>
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
              {#each investigateProfiles as profile (profile.name)}<option value={profile.name}>{profile.name}</option>{/each}
            </select>
            {#if investigateProfiles.length === 0}<p>{investigateProfileStatus}</p>{/if}
            <p>Investigate can search the selected collection as needed. Search filters apply to Search only.</p>
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
    <div class:with-source={selectedHit !== null} class="content-layout">
    <div class="mode-panels">
      <div id="panel-search" role="tabpanel" aria-labelledby="tab-search" hidden={activeMode !== 'SEARCH'}>
      <div class="search-input-row">
        <form onsubmit={search}>
          <label class="visually-hidden" for="query">Search query</label>
          <input id="query" name="query" bind:value={query} oninput={invalidateSearch} autocomplete="off" placeholder="Search documents, names, and ideas…" required />
          <button class="primary" type="submit" disabled={searching || query.trim() === ''}>{searching ? 'Searching…' : 'Search'}</button>
        </form>
      </div>
      {#if searching}
        <p role="status">Searching…</p>
      {:else if searchError !== null}
        <p role="alert">{searchError}</p>
      {:else if hasSearched && hits.length === 0}
        <p role="status">No results found.</p>
      {:else if hits.length > 0}
        <ol class="results" aria-label="Search results">
          {#each hits as hit (`${hit.unitId}-${hit.chunkOrdinal}`)}
            <li>
              <button
                type="button"
                class="result"
                aria-pressed={selectedHit?.unitId === hit.unitId}
                onclick={() => openSource(hit)}
              >
                <span class="result-title">{hit.title || hit.locatorLabel}</span>
                <span class="meta">{hit.locatorLabel}</span>
                <span class="meta">Matched by {hit.matchedBy.join(', ').toLowerCase()}</span>
                <span>{hit.text}</span>
              </button>
            </li>
          {/each}
        </ol>
      {/if}
      </div>

      <div id="panel-admin" role="tabpanel" aria-labelledby="tab-admin" hidden={activeMode !== 'ADMIN'}>
        <div class="admin-tabs" role="tablist" aria-label="Administration section" tabindex="-1" onkeydown={handleAdminTabKeydown}>
          <button
            id="admin-tab-llm"
            role="tab"
            aria-selected={adminTab === 'LLM'}
            aria-controls="admin-panel-llm"
            tabindex={adminTab === 'LLM' ? 0 : -1}
            onclick={() => (adminTab = 'LLM')}
          >LLM profiles</button>
          <button
            id="admin-tab-import"
            role="tab"
            aria-selected={adminTab === 'IMPORT'}
            aria-controls="admin-panel-import"
            tabindex={adminTab === 'IMPORT' ? 0 : -1}
            onclick={() => (adminTab = 'IMPORT')}
          >Import</button>
        </div>
        <div class="admin-panels">
          <div id="admin-panel-llm" role="tabpanel" aria-labelledby="admin-tab-llm" hidden={adminTab !== 'LLM'}><LlmAdminPanel /></div>
          <div id="admin-panel-import" role="tabpanel" aria-labelledby="admin-tab-import" hidden={adminTab !== 'IMPORT'}><ImportPanel collectionId={selectedCollectionId} onCollectionsChanged={handleCollectionsChanged} /></div>
        </div>
      </div>

    {#key selectedCollectionId}
      <div id="panel-ask" role="tabpanel" aria-labelledby="tab-ask" hidden={activeMode !== 'ASK'}><AskPanel collectionId={selectedCollectionId} bind:askProfile bind:availableProfiles={askProfiles} bind:profileStatus={askProfileStatus} onOpenSource={(evidence) => openSource({
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
      })} /></div>
      <div id="panel-investigate" role="tabpanel" aria-labelledby="tab-investigate" hidden={activeMode !== 'INVESTIGATE'}><InvestigatePanel collectionId={selectedCollectionId} bind:profile={investigateProfile} bind:availableProfiles={investigateProfiles} bind:profileStatus={investigateProfileStatus} onOpenSource={openInvestigationSource} /></div>
    {/key}
    </div>

  {#if selectedHit !== null}
    <section aria-labelledby="source-heading" aria-live="polite">
      <div class="source-heading-row"><h2 id="source-heading">Source</h2><button type="button" aria-label="Close source viewer" onclick={clearSelectedSource}>×</button></div>
      <p class="meta">{selectedHit.locatorLabel}</p>
      <p><a href={originalHref(selectedHit)}>Open original</a></p>
      {#if loadingSource && source === null}
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
    </section>
  {/if}
    </div>
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
  .app-shell { min-height: 100vh; display: grid; grid-template-columns: 258px minmax(0, 1fr); }
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
  .sidebar-footer { display: flex; align-items: center; gap: 0.5rem; margin-top: auto; border-top: 1px solid #2d3131; padding: 1rem 0.35rem 0; color: #89908e; font-size: 0.76rem; }
  .status-dot { width: 0.45rem; height: 0.45rem; border-radius: 50%; background: #85ad87; box-shadow: 0 0 0 3px #85ad8720; }
  .workspace { width: 100%; min-width: 0; padding: 2.1rem clamp(1.25rem, 4vw, 4.5rem); }
  .workspace-header { display: flex; align-items: end; justify-content: space-between; gap: 1rem; max-width: 78rem; margin: 0 auto 2rem; padding-bottom: 1.25rem; border-bottom: 1px solid #292d2d; }
  .workspace-header h1 { margin: 0.35rem 0 0; color: #f0efec; font-size: clamp(1.45rem, 2vw, 1.9rem); font-weight: 570; letter-spacing: -0.035em; }
  .content-layout { display: grid; grid-template-columns: minmax(0, 1fr); gap: 1.5rem; max-width: 78rem; margin: 0 auto; align-items: start; }
  .content-layout.with-source { grid-template-columns: minmax(0, 1.1fr) minmax(18rem, 0.9fr); }
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
  .result-title { color: #e6e6e2; font-weight: 650; }
  .meta { color: #929997; font-size: 0.82rem; }
  .content-layout > section[aria-labelledby="source-heading"] { min-width: 0; margin: 0; padding: 1rem; border: 1px solid #303535; border-radius: 0.65rem; background: #191c1d; }
  .source-heading-row { display: flex; align-items: center; justify-content: space-between; }
  .source-heading-row h2 { margin-top: 0; }
  #source-heading { margin-top: 0; }
  .source-text { max-height: calc(100vh - 14rem); overflow: auto; overflow-wrap: anywhere; white-space: pre-wrap; border: 1px solid #343939; border-radius: 0.45rem; background: #121515; padding: 0.9rem; font: 0.86rem/1.6 ui-monospace, SFMono-Regular, Menlo, monospace; }
  [role="alert"] { color: #f0a4a0; }
  .visually-hidden { position: absolute; width: 1px; height: 1px; padding: 0; margin: -1px; overflow: hidden; clip: rect(0,0,0,0); white-space: nowrap; border: 0; }
  @media (max-width: 54rem) { .app-shell { grid-template-columns: 1fr; } .sidebar { min-height: auto; gap: 1rem; padding: 0.8rem 1rem; border-right: 0; border-bottom: 1px solid #2a2d2e; } .sidebar-footer { display: none; } .sidebar-note, .search-settings { max-width: 38rem; } .mode-nav [role="tablist"] { display: flex; } .mode-nav [role="tab"] { flex: 1; justify-content: center; } .content-layout.with-source { grid-template-columns: minmax(0, 1fr); } .content-layout > section[aria-labelledby="source-heading"] { order: 2; } }
  @media (max-width: 36rem) { .workspace { padding: 1.3rem 1rem; } .workspace-header { align-items: start; margin-bottom: 1.2rem; } .search-input-row form { align-items: stretch; flex-direction: column; } .mode-nav [role="tab"] { gap: 0.35rem; padding: 0.55rem 0.35rem; font-size: 0.83rem; } }
</style>
