<script lang="ts">
  import { onMount } from 'svelte';
  import './account.css';
  import { createUser, listUsers, resetUserPassword, updateUser, type User, type UserRole } from './api';

  /** The administrator looking at the list; their own row cannot be deactivated from here by mistake. */
  export let currentUserId: string;

  let users: User[] = [];
  let loaded = false;
  let error: string | null = null;
  let username = '';
  let displayName = '';
  let role: UserRole = 'USER';
  let creating = false;
  /** A one-time password just handed out, with whom it belongs to; shown once. */
  let secret: { username: string; password: string } | null = null;
  let flash: string | null = null;

  onMount(load);

  async function load(): Promise<void> {
    try {
      users = await listUsers();
      loaded = true;
    } catch (failure) {
      error = message(failure, 'The users could not be read.');
    }
  }

  function message(failure: unknown, fallback: string): string {
    return failure instanceof Error ? failure.message : fallback;
  }

  async function create(event: SubmitEvent): Promise<void> {
    event.preventDefault();
    if (creating) return;
    creating = true;
    error = flash = null;
    secret = null;
    try {
      const answer = await createUser(username.trim(), displayName.trim() || null, role);
      if (answer.created && answer.oneTimePassword) {
        secret = { username: answer.user.username, password: answer.oneTimePassword };
      } else {
        flash = `${answer.user.username} already exists; nothing was changed.`;
      }
      username = displayName = '';
      role = 'USER';
      await load();
    } catch (failure) {
      error = message(failure, 'The user could not be created.');
    } finally {
      creating = false;
    }
  }

  async function change(user: User, change: { role?: UserRole; active?: boolean }): Promise<void> {
    error = flash = null;
    try {
      await updateUser(user.id, change);
      await load();
    } catch (failure) {
      error = message(failure, 'The user could not be changed.');
    }
  }

  async function reset(user: User): Promise<void> {
    if (!window.confirm(`Reset the password of ${user.username}? They are signed out everywhere.`)) return;
    error = flash = null;
    try {
      secret = { username: user.username, password: await resetUserPassword(user.id) };
    } catch (failure) {
      error = message(failure, 'The password could not be reset.');
    }
  }
</script>

<section class="account-section" aria-labelledby="users-heading">
  <h2 id="users-heading">Users</h2>
  <p class="account-hint">Users are never deleted, so what they did keeps their name; deactivate them instead.</p>
  {#if secret !== null}
    <div role="status">
      <p class="account-flash">One-time password for {secret.username}. Give it to them; they choose their own at first sign-in. It is not shown again.</p>
      <code class="account-secret" aria-label="One-time password">{secret.password}</code>
    </div>
  {/if}
  {#if flash !== null}<p class="account-flash" role="status">{flash}</p>{/if}
  {#if error !== null}<p class="account-error" role="alert">{error}</p>{/if}

  <form class="account-form" onsubmit={create} aria-label="Add a user">
    <div class="field">
      <label for="user-username">Username</label>
      <input id="user-username" bind:value={username} required maxlength="200" autocomplete="off" />
    </div>
    <div class="field">
      <label for="user-display-name">Display name (optional)</label>
      <input id="user-display-name" bind:value={displayName} maxlength="200" />
    </div>
    <div class="field">
      <label for="user-role">Role</label>
      <select id="user-role" bind:value={role}>
        <option value="USER">User</option>
        <option value="ADMIN">Administrator</option>
      </select>
    </div>
    <div><button type="submit" class="primary" disabled={creating || username.trim() === ''}>Add user</button></div>
  </form>

  {#if loaded}
    <table class="account-table">
      <thead><tr><th>User</th><th>Role</th><th>Status</th><th>Last sign-in</th><th><span class="visually-hidden">Actions</span></th></tr></thead>
      <tbody>
        {#each users as user (user.id)}
          <tr>
            <td><strong>{user.displayName}</strong><br /><span class="account-hint">{user.username}</span></td>
            <td>{user.role === 'ADMIN' ? 'Administrator' : 'User'}</td>
            <td>{user.active ? (user.mustChangePassword ? 'Password not chosen yet' : 'Active') : 'Deactivated'}</td>
            <td>{user.lastSignInAt ? user.lastSignInAt.slice(0, 10) : '—'}</td>
            <td>
              <div class="account-actions">
                {#if user.role === 'ADMIN'}
                  <button type="button" onclick={() => change(user, { role: 'USER' })}>Make user</button>
                {:else}
                  <button type="button" onclick={() => change(user, { role: 'ADMIN' })}>Make administrator</button>
                {/if}
                <button type="button" onclick={() => reset(user)}>Reset password</button>
                {#if user.active}
                  {#if user.id !== currentUserId}
                    <button type="button" class="danger" onclick={() => change(user, { active: false })}>Deactivate</button>
                  {/if}
                {:else}
                  <button type="button" onclick={() => change(user, { active: true })}>Reactivate</button>
                {/if}
              </div>
            </td>
          </tr>
        {/each}
      </tbody>
    </table>
  {/if}
</section>

<style>
  .visually-hidden { position: absolute; inline-size: 1px; block-size: 1px; overflow: hidden; clip-path: inset(50%); white-space: nowrap; }
</style>
