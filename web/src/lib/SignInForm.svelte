<script lang="ts">
  import './account.css';
  import { ApiError, signIn, type User } from './api';

  export let onSignedIn: (user: User) => void;

  let username = '';
  let password = '';
  let busy = false;
  let error: string | null = null;

  async function submit(event: SubmitEvent): Promise<void> {
    event.preventDefault();
    if (busy) return;
    busy = true;
    error = null;
    try {
      const user = await signIn(username.trim(), password);
      password = '';
      onSignedIn(user);
    } catch (failure) {
      error = failure instanceof ApiError && failure.code === 'SIGN_IN_RATE_LIMITED'
        ? 'Too many failed sign-ins. Wait a few minutes and try again.'
        : failure instanceof ApiError && failure.code === 'INVALID_CREDENTIALS'
          ? 'The username or password is wrong.'
          : failure instanceof Error ? failure.message : 'Signing in failed.';
    } finally {
      busy = false;
    }
  }
</script>

<form class="account-form" onsubmit={submit} aria-labelledby="sign-in-heading">
  <h1 id="sign-in-heading">Sign in to InfoScry</h1>
  <div class="field">
    <label for="sign-in-username">Username</label>
    <input id="sign-in-username" autocomplete="username" bind:value={username} required />
  </div>
  <div class="field">
    <label for="sign-in-password">Password</label>
    <input id="sign-in-password" type="password" autocomplete="current-password" bind:value={password} required />
  </div>
  {#if error !== null}<p class="account-error" role="alert">{error}</p>{/if}
  <div><button type="submit" class="primary" disabled={busy}>{busy ? 'Signing in…' : 'Sign in'}</button></div>
  <p class="account-hint">Accounts are created by an administrator.</p>
</form>

<style>
  h1 { margin: 0 0 0.5rem; font-size: 1.4rem; font-weight: 570; color: #f0efec; }
</style>
