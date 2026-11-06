<script lang="ts">
  import { onMount } from 'svelte';
  import './account.css';
  import PasswordChangeForm from './PasswordChangeForm.svelte';
  import {
    createAccessToken,
    listAccessTokens,
    revokeAccessToken,
    updateDisplayName,
    type AccessToken,
    type User,
  } from './api';

  export let user: User;

  let displayName = user.displayName;
  let savingName = false;
  let nameMessage: string | null = null;
  let nameError: string | null = null;

  let tokens: AccessToken[] = [];
  let tokensLoaded = false;
  let tokenError: string | null = null;
  let tokenName = '';
  let tokenDays = '90';
  let creating = false;
  /** The value of the token just created: shown once, never again. */
  let created: { name: string; value: string } | null = null;

  onMount(loadTokens);

  async function loadTokens(): Promise<void> {
    try {
      tokens = await listAccessTokens();
      tokensLoaded = true;
    } catch (failure) {
      tokenError = failure instanceof Error ? failure.message : 'The tokens could not be read.';
    }
  }

  async function saveName(event: SubmitEvent): Promise<void> {
    event.preventDefault();
    if (savingName) return;
    savingName = true;
    nameMessage = nameError = null;
    try {
      user = await updateDisplayName(displayName);
      displayName = user.displayName;
      nameMessage = 'Saved.';
    } catch (failure) {
      nameError = failure instanceof Error ? failure.message : 'The name could not be saved.';
    } finally {
      savingName = false;
    }
  }

  async function createToken(event: SubmitEvent): Promise<void> {
    event.preventDefault();
    if (creating) return;
    creating = true;
    tokenError = null;
    try {
      const days = tokenDays === '' ? null : Number(tokenDays);
      const answer = await createAccessToken(tokenName.trim(), days);
      created = { name: answer.token.name, value: answer.value };
      tokenName = '';
      await loadTokens();
    } catch (failure) {
      tokenError = failure instanceof Error ? failure.message : 'The token could not be created.';
    } finally {
      creating = false;
    }
  }

  async function revoke(token: AccessToken): Promise<void> {
    tokenError = null;
    try {
      await revokeAccessToken(token.id);
      await loadTokens();
    } catch (failure) {
      tokenError = failure instanceof Error ? failure.message : 'The token could not be revoked.';
    }
  }

  function day(instant: string | null | undefined): string {
    return instant ? instant.slice(0, 10) : '—';
  }
</script>

<section class="account-section" aria-labelledby="profile-heading">
  <h2 id="profile-heading">Profile</h2>
  <p class="account-hint">Signed in as <strong>{user.username}</strong> ({user.role === 'ADMIN' ? 'administrator' : 'user'}).</p>
  <form class="account-form" onsubmit={saveName}>
    <div class="field">
      <label for="account-display-name">Display name</label>
      <input id="account-display-name" bind:value={displayName} required maxlength="200" />
    </div>
    {#if nameError !== null}<p class="account-error" role="alert">{nameError}</p>{/if}
    {#if nameMessage !== null}<p class="account-flash" role="status">{nameMessage}</p>{/if}
    <div><button type="submit" class="primary" disabled={savingName || displayName.trim() === ''}>Save name</button></div>
  </form>
</section>

<section class="account-section"><PasswordChangeForm /></section>

<section class="account-section" aria-labelledby="tokens-heading">
  <h2 id="tokens-heading">Personal access tokens</h2>
  <p class="account-hint">The command line signs in with a token: export it as <code>INFOSCRY_TOKEN</code>. A token is shown only once.</p>
  {#if created !== null}
    <div role="status">
      <p class="account-flash">Token “{created.name}” created. Copy it now; it is not shown again.</p>
      <code class="account-secret" aria-label="New token">{created.value}</code>
    </div>
  {/if}
  <form class="account-form" onsubmit={createToken}>
    <div class="field">
      <label for="token-name">Name</label>
      <input id="token-name" bind:value={tokenName} required maxlength="200" placeholder="For example: laptop" />
    </div>
    <div class="field">
      <label for="token-expiry">Expires</label>
      <select id="token-expiry" bind:value={tokenDays}>
        <option value="30">After 30 days</option>
        <option value="90">After 90 days</option>
        <option value="365">After a year</option>
        <option value="">Never</option>
      </select>
    </div>
    <div><button type="submit" class="primary" disabled={creating || tokenName.trim() === ''}>Create token</button></div>
  </form>
  {#if tokenError !== null}<p class="account-error" role="alert">{tokenError}</p>{/if}
  {#if tokensLoaded && tokens.length === 0}
    <p class="account-hint">No tokens yet.</p>
  {:else if tokens.length > 0}
    <table class="account-table">
      <thead><tr><th>Name</th><th>Created</th><th>Expires</th><th>Last used</th><th><span class="visually-hidden">Actions</span></th></tr></thead>
      <tbody>
        {#each tokens as token (token.id)}
          <tr>
            <td>{token.name}{#if token.revokedAt} <span class="account-hint">(revoked)</span>{/if}</td>
            <td>{day(token.createdAt)}</td>
            <td>{token.expiresAt ? day(token.expiresAt) : 'Never'}</td>
            <td>{day(token.lastUsedAt)}</td>
            <td>{#if !token.revokedAt}<button type="button" class="danger" onclick={() => revoke(token)} aria-label={`Revoke ${token.name}`}>Revoke</button>{/if}</td>
          </tr>
        {/each}
      </tbody>
    </table>
  {/if}
</section>

<style>
  .visually-hidden { position: absolute; inline-size: 1px; block-size: 1px; overflow: hidden; clip-path: inset(50%); white-space: nowrap; }
  code { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }
</style>
