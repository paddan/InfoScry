<script lang="ts">
  import { onDestroy } from 'svelte';
  import {
    listMembers,
    listUserDirectory,
    putMember,
    removeMember,
    type Collection,
    type CollectionRole,
    type DirectoryEntry,
    type Member,
  } from './api';

  export let collection: Collection;
  /** Whether the signed-in user is an administrator, who manages every collection's members. */
  export let isAdmin = false;

  const ROLE_LABELS: Record<CollectionRole, string> = { OWNER: 'Owner', EDITOR: 'Editor', READER: 'Reader' };
  const ROLE_HINT = 'Readers search, read and ask. Editors also import, review and edit corrections, glossary and prompts. Owners also change settings and members and can delete the collection.';

  let members: Member[] | null = null;
  let callerRole: CollectionRole | null = null;
  let directory: DirectoryEntry[] = [];
  let error: string | null = null;
  let busy = false;
  let newUserId = '';
  let newRole: CollectionRole = 'READER';
  let boundId = '';
  let generation = 0;

  $: canManage = isAdmin || callerRole === 'OWNER';
  $: candidates = directory.filter((entry) => !(members ?? []).some((member) => member.userId === entry.id));
  $: if (collection.id !== boundId) void load(collection.id);

  onDestroy(() => {
    generation++;
  });

  async function load(target: string): Promise<void> {
    boundId = target;
    members = null;
    error = null;
    await reload(++generation);
  }

  async function reload(mine = generation): Promise<void> {
    try {
      const answer = await listMembers(boundId);
      if (mine !== generation) return;
      members = answer.members;
      callerRole = answer.callerRole;
      if (isAdmin || callerRole === 'OWNER') {
        const people = await listUserDirectory();
        if (mine !== generation) return;
        directory = people;
      }
    } catch (failure) {
      if (mine !== generation) return;
      error = failure instanceof Error ? failure.message : 'The members could not be read.';
    }
  }

  async function act(action: () => Promise<unknown>): Promise<void> {
    if (busy) return;
    busy = true;
    error = null;
    try {
      await action();
      await reload();
    } catch (failure) {
      error = failure instanceof Error ? failure.message : 'The change could not be saved.';
    } finally {
      busy = false;
    }
  }

  function add(event: SubmitEvent): void {
    event.preventDefault();
    if (newUserId === '') return;
    const userId = newUserId;
    void act(async () => {
      await putMember(boundId, userId, newRole);
      newUserId = '';
      newRole = 'READER';
    });
  }
</script>

<section class="members" aria-labelledby={`members-${collection.id}`}>
  <h3 id={`members-${collection.id}`}>Members</h3>
  <p class="hint">{ROLE_HINT}</p>
  {#if error !== null}<p role="alert">{error}</p>{/if}
  {#if members === null && error === null}
    <p role="status">Loading members…</p>
  {:else if members !== null}
    <ul>
      {#each members as member (member.userId)}
        <li>
          <span class="who"><strong>{member.displayName}</strong> <span class="hint">{member.username}{member.active ? '' : ', deactivated'}</span></span>
          {#if canManage}
            <label class="visually-hidden" for={`role-${member.userId}`}>Role of {member.displayName}</label>
            <select
              id={`role-${member.userId}`}
              value={member.role}
              disabled={busy}
              onchange={(event) => act(() => putMember(boundId, member.userId, (event.currentTarget as HTMLSelectElement).value as CollectionRole))}
            >
              {#each Object.entries(ROLE_LABELS) as [value, label] (value)}<option {value}>{label}</option>{/each}
            </select>
            <button type="button" disabled={busy} onclick={() => act(() => removeMember(boundId, member.userId))} aria-label={`Remove ${member.displayName}`}>Remove</button>
          {:else}
            <span>{ROLE_LABELS[member.role]}</span>
          {/if}
        </li>
      {/each}
    </ul>
    {#if canManage}
      <form onsubmit={add} class="add">
        <div class="field">
          <label for={`member-new-${collection.id}`}>Add a person</label>
          <select id={`member-new-${collection.id}`} bind:value={newUserId} disabled={busy || candidates.length === 0}>
            <option value="">{candidates.length === 0 ? 'Everyone is already a member' : 'Choose…'}</option>
            {#each candidates as entry (entry.id)}<option value={entry.id}>{entry.displayName} ({entry.username})</option>{/each}
          </select>
        </div>
        <div class="field">
          <label for={`member-role-${collection.id}`}>Role</label>
          <select id={`member-role-${collection.id}`} bind:value={newRole} disabled={busy}>
            {#each Object.entries(ROLE_LABELS) as [value, label] (value)}<option {value}>{label}</option>{/each}
          </select>
        </div>
        <button type="submit" class="primary" disabled={busy || newUserId === ''}>Add member</button>
      </form>
    {/if}
  {/if}
</section>

<style>
  .members { display: grid; gap: 0.6rem; max-width: 65ch; }
  h3 { margin: 0; font-size: 0.95rem; font-weight: 600; color: #f0efec; }
  ul { list-style: none; margin: 0; padding: 0; display: grid; gap: 0.4rem; }
  li { display: flex; flex-wrap: wrap; align-items: center; gap: 0.55rem; }
  .who { flex: 1 1 14rem; min-width: 0; overflow-wrap: anywhere; }
  .add { display: flex; flex-wrap: wrap; align-items: end; gap: 0.55rem; }
  .field { display: grid; gap: 0.35rem; }
  label { color: #b8bcbb; font-size: 0.82rem; }
  select { border: 1px solid #383d3e; border-radius: 0.48rem; background: #202324; padding: 0.5rem 0.6rem; color: #e8e9e7; }
  .hint { color: #929997; font-size: 0.82rem; margin: 0; }
  .visually-hidden { position: absolute; inline-size: 1px; block-size: 1px; overflow: hidden; clip-path: inset(50%); white-space: nowrap; }
</style>
