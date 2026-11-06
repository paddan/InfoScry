<script lang="ts">
  import { onMount } from 'svelte';
  import { browseImportRoot, listImportRoots, type ImportRoot, type ImportRootEntry } from './api';

  /** Adds a server path (a file or a folder inside an import root) to the import. */
  export let onAdd: (path: string) => void;

  let roots: ImportRoot[] | null = null;
  let root: ImportRoot | null = null;
  let path = '';
  let entries: ImportRootEntry[] = [];
  let error: string | null = null;
  let loading = false;

  onMount(async () => {
    try {
      roots = (await listImportRoots()).filter((candidate) => candidate.available);
      if (roots.length === 1) await open(roots[0], '');
    } catch (failure) {
      error = failure instanceof Error ? failure.message : 'The import folders could not be read.';
    }
  });

  async function open(target: ImportRoot, relative: string): Promise<void> {
    loading = true;
    error = null;
    try {
      const listing = await browseImportRoot(target.id, relative);
      root = target;
      path = listing.path;
      entries = listing.entries;
    } catch (failure) {
      error = failure instanceof Error ? failure.message : 'The folder could not be read.';
    } finally {
      loading = false;
    }
  }

  function up(): void {
    if (root === null) return;
    void open(root, path.split('/').slice(0, -1).join('/'));
  }

  function child(name: string): string {
    return path === '' ? name : `${path}/${name}`;
  }
</script>

<section class="root-browser" aria-label="Server import folders">
  {#if error !== null}<p role="alert">{error}</p>{/if}
  {#if roots !== null && roots.length === 0}
    <p class="hint">No import folders are set up on the server. An administrator adds them under Admin → Server folders.</p>
  {:else if roots !== null}
    {#if roots.length > 1}
      <div class="roots">
        {#each roots as candidate (candidate.id)}
          <button type="button" aria-pressed={root?.id === candidate.id} onclick={() => open(candidate, '')}>{candidate.name}</button>
        {/each}
      </div>
    {/if}
    {#if root !== null}
      <div class="location">
        <span>{root.name}{path === '' ? '' : `/${path}`}</span>
        {#if path !== ''}<button type="button" onclick={up} disabled={loading}>Up</button>{/if}
        <button type="button" onclick={() => onAdd(path === '' ? root!.path : `${root!.path}/${path}`)} disabled={loading}>Add this folder</button>
      </div>
      {#if entries.length === 0}
        <p class="hint">This folder is empty.</p>
      {:else}
        <ul>
          {#each entries as entry (entry.path)}
            <li>
              {#if entry.directory}
                <button type="button" class="folder" onclick={() => open(root!, child(entry.name))} disabled={loading}>{entry.name}/</button>
              {:else}
                <span>{entry.name}</span>
              {/if}
              <button type="button" aria-label={`Add ${entry.name}`} onclick={() => onAdd(entry.path)}>Add</button>
            </li>
          {/each}
        </ul>
      {/if}
    {/if}
  {/if}
</section>

<style>
  .root-browser { display: grid; gap: 0.5rem; padding: 0.85rem; border: 1px solid #303535; border-radius: 0.55rem; }
  .roots, .location { display: flex; flex-wrap: wrap; gap: 0.45rem; align-items: center; }
  .location span { flex: 1 1 12rem; min-width: 0; overflow-wrap: anywhere; color: #b8bcbb; }
  ul { list-style: none; margin: 0; padding: 0; display: grid; gap: 0.25rem; max-height: 18rem; overflow: auto; }
  li { display: flex; gap: 0.5rem; align-items: center; justify-content: space-between; }
  li > span, .folder { min-width: 0; overflow-wrap: anywhere; }
  .folder { background: none; border: 0; color: #c4a77d; padding: 0; cursor: pointer; text-align: start; }
  .hint { color: #929997; font-size: 0.82rem; margin: 0; }
</style>
