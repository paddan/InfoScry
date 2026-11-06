<script lang="ts">
  import { onMount } from 'svelte';
  import './account.css';
  import { addImportRoot, listImportRoots, removeImportRoot, type ImportRoot } from './api';

  let roots: ImportRoot[] = [];
  let loaded = false;
  let error: string | null = null;
  let flash: string | null = null;
  let path = '';
  let adding = false;

  onMount(load);

  async function load(): Promise<void> {
    try {
      roots = await listImportRoots();
      loaded = true;
    } catch (failure) {
      error = message(failure, 'The server folders could not be read.');
    }
  }

  function message(failure: unknown, fallback: string): string {
    return failure instanceof Error ? failure.message : fallback;
  }

  async function add(event: SubmitEvent): Promise<void> {
    event.preventDefault();
    if (adding) return;
    adding = true;
    error = flash = null;
    try {
      const answer = await addImportRoot(path.trim());
      flash = answer.created ? `${answer.root.path} was added.` : `${answer.root.path} is already a server folder.`;
      path = '';
      await load();
    } catch (failure) {
      error = message(failure, 'The folder could not be added.');
    } finally {
      adding = false;
    }
  }

  async function remove(root: ImportRoot): Promise<void> {
    if (!window.confirm(`Stop importing from ${root.path}? Documents already imported from it stay.`)) return;
    error = flash = null;
    try {
      await removeImportRoot(root.id);
      flash = `${root.path} was removed.`;
      await load();
    } catch (failure) {
      error = message(failure, 'The folder could not be removed.');
    }
  }
</script>

<section class="account-section" aria-labelledby="import-roots-heading">
  <h2 id="import-roots-heading">Server folders</h2>
  <p class="account-hint">
    Editors can browse these folders on the server and import files from them, besides uploading files. Nothing
    outside them can be imported, also not through a link. The files are only read, never changed.
  </p>
  {#if flash !== null}<p class="account-flash" role="status">{flash}</p>{/if}
  {#if error !== null}<p class="account-error" role="alert">{error}</p>{/if}

  <form class="account-form" onsubmit={add} aria-label="Add a server folder">
    <div class="field">
      <label for="import-root-path">Folder path on the server</label>
      <input id="import-root-path" bind:value={path} required placeholder="/Users/anna/Archive" autocomplete="off" spellcheck="false" />
    </div>
    <div><button type="submit" class="primary" disabled={adding || path.trim() === ''}>Add folder</button></div>
  </form>

  {#if loaded}
    {#if roots.length === 0}
      <p class="account-hint">No server folders yet; editors can only upload files.</p>
    {:else}
      <table class="account-table">
        <thead><tr><th>Folder</th><th>Added</th><th><span class="visually-hidden">Actions</span></th></tr></thead>
        <tbody>
          {#each roots as root (root.id)}
            <tr>
              <td>
                <strong>{root.name}</strong><br /><span class="account-hint path">{root.path}</span>
                {#if !root.available}<br /><span class="account-error">Not found on the server; nothing can be imported from it.</span>{/if}
              </td>
              <td>
                {#if root.source === 'ENVIRONMENT'}
                  <span class="account-hint">Set in INFOSCRY_IMPORT_ROOTS</span>
                {:else}
                  {root.addedAt ? root.addedAt.slice(0, 10) : '—'}{#if root.addedBy}<br /><span class="account-hint">by {root.addedBy}</span>{/if}
                {/if}
              </td>
              <td>
                {#if root.source === 'ADMIN'}
                  <div class="account-actions">
                    <button type="button" class="danger" aria-label={`Remove ${root.path}`} onclick={() => remove(root)}>Remove</button>
                  </div>
                {/if}
              </td>
            </tr>
          {/each}
        </tbody>
      </table>
    {/if}
  {/if}
</section>

<style>
  .path { overflow-wrap: anywhere; }
  .visually-hidden { position: absolute; inline-size: 1px; block-size: 1px; overflow: hidden; clip-path: inset(50%); white-space: nowrap; }
</style>
