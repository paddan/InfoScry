<script lang="ts">
  import { onMount } from 'svelte';
  import { ApiError, createCollection, listCollections, type Collection } from '../lib/api';

  let collections: Collection[] = [];
  let loading = true;
  let busy = false;
  let newName = '';
  let errorMessage: string | null = null;
  let statusMessage: string | null = null;

  async function refresh(): Promise<void> {
    loading = true;
    try {
      collections = await listCollections();
      errorMessage = null;
    } catch (failure) {
      errorMessage = describe(failure);
    } finally {
      loading = false;
    }
  }

  async function create(event: SubmitEvent): Promise<void> {
    event.preventDefault();
    const name = newName.trim();
    if (name === '' || busy) {
      return;
    }
    busy = true;
    statusMessage = null;
    errorMessage = null;
    try {
      const created = await createCollection(name);
      newName = '';
      statusMessage = `Created “${created.name}”.`;
      await refresh();
    } catch (failure) {
      errorMessage = describe(failure);
    } finally {
      busy = false;
    }
  }

  function describe(failure: unknown): string {
    if (failure instanceof ApiError) {
      return failure.message;
    }
    return failure instanceof Error ? failure.message : 'Something went wrong.';
  }

  onMount(refresh);
</script>

<main>
  <h1>InfoScry</h1>
  <p class="intro">
    Import your own documents, search them, and ask questions with citations back to the exact page or
    section.
  </p>

  <section aria-labelledby="collections-heading">
    <h2 id="collections-heading">Collections</h2>
    <p class="hint">A collection is the material one question searches. Documents belong to exactly one.</p>

    {#if loading}
      <p role="status">Loading collections…</p>
    {:else if errorMessage !== null && collections.length === 0}
      <p role="alert">{errorMessage}</p>
    {:else if collections.length === 0}
      <p role="status">No collections yet.</p>
    {:else}
      <ul class="collections">
        {#each collections as collection (collection.id)}
          <li>
            <span class="name">{collection.name}</span>
            <span class="meta">OCR languages: {collection.ocrLanguages}</span>
            {#if collection.description}
              <span class="meta">{collection.description}</span>
            {/if}
          </li>
        {/each}
      </ul>
    {/if}
  </section>

  <section aria-labelledby="create-heading">
    <h2 id="create-heading">New collection</h2>
    <form onsubmit={create}>
      <label for="collection-name">Name</label>
      <input
        id="collection-name"
        name="name"
        bind:value={newName}
        required
        autocomplete="off"
        placeholder="Project Nightfall"
      />
      <button type="submit" disabled={busy}>{busy ? 'Creating…' : 'Create collection'}</button>
    </form>
    {#if statusMessage !== null}
      <p role="status">{statusMessage}</p>
    {/if}
    {#if errorMessage !== null && collections.length > 0}
      <p role="alert">{errorMessage}</p>
    {/if}
  </section>
</main>

<style>
  main {
    margin: 0 auto;
    max-width: 46rem;
    padding: 2rem 1.5rem;
  }

  h1 {
    margin-bottom: 0.25rem;
  }

  .intro,
  .hint,
  .meta {
    color: #4a4a4a;
  }

  .hint {
    font-size: 0.9rem;
    margin-top: 0;
  }

  section {
    margin-top: 2rem;
  }

  .collections {
    list-style: none;
    padding: 0;
  }

  .collections li {
    border-bottom: 1px solid #e0e0e0;
    display: flex;
    flex-direction: column;
    padding: 0.6rem 0;
  }

  .name {
    font-weight: 600;
  }

  .meta {
    font-size: 0.85rem;
  }

  form {
    align-items: flex-end;
    display: flex;
    flex-wrap: wrap;
    gap: 0.75rem;
  }

  label {
    display: block;
    font-size: 0.9rem;
    width: 100%;
  }

  input {
    flex: 1 1 14rem;
    padding: 0.4rem;
  }

  button {
    padding: 0.45rem 1rem;
  }

  [role='alert'] {
    color: #a4232b;
  }
</style>
