<script lang="ts">
  import './account.css';
  import { ApiError, changePassword } from './api';

  /** Whether the change is required before anything else (after a one-time password). */
  export let required = false;
  export let onChanged: () => void = () => {};
  /** Offered only when the change is required: leaving signs out instead. */
  export let onCancel: (() => void) | null = null;

  const MIN_LENGTH = 12;

  let current = '';
  let next = '';
  let repeated = '';
  let busy = false;
  let error: string | null = null;
  let done = false;

  async function submit(event: SubmitEvent): Promise<void> {
    event.preventDefault();
    if (busy) return;
    error = null;
    done = false;
    if (next.length < MIN_LENGTH) {
      error = `A password needs at least ${MIN_LENGTH} characters.`;
      return;
    }
    if (next !== repeated) {
      error = 'The new passwords do not match.';
      return;
    }
    busy = true;
    try {
      await changePassword(current, next);
      current = next = repeated = '';
      done = true;
      onChanged();
    } catch (failure) {
      error = failure instanceof ApiError && failure.code === 'WRONG_CURRENT_PASSWORD'
        ? 'The current password is wrong.'
        : failure instanceof Error ? failure.message : 'The password could not be changed.';
    } finally {
      busy = false;
    }
  }
</script>

<form class="account-form" onsubmit={submit} aria-labelledby="password-heading">
  {#if required}
    <h1 id="password-heading">Choose your password</h1>
    <p class="account-hint">You signed in with a one-time password. Choose your own before going on.</p>
  {:else}
    <h2 id="password-heading">Password</h2>
  {/if}
  <div class="field">
    <label for="password-current">{required ? 'One-time password' : 'Current password'}</label>
    <input id="password-current" type="password" autocomplete="current-password" bind:value={current} required />
  </div>
  <div class="field">
    <label for="password-new">New password</label>
    <input id="password-new" type="password" autocomplete="new-password" minlength={MIN_LENGTH} bind:value={next} required />
  </div>
  <div class="field">
    <label for="password-repeat">Repeat the new password</label>
    <input id="password-repeat" type="password" autocomplete="new-password" bind:value={repeated} required />
  </div>
  <p class="account-hint">At least {MIN_LENGTH} characters. Your other signed-in browsers are signed out.</p>
  {#if error !== null}<p class="account-error" role="alert">{error}</p>{/if}
  {#if done && !required}<p class="account-flash" role="status">Password changed.</p>{/if}
  <div class="account-actions">
    <button type="submit" class="primary" disabled={busy}>{busy ? 'Saving…' : 'Change password'}</button>
    {#if onCancel !== null}<button type="button" onclick={onCancel}>Sign out</button>{/if}
  </div>
</form>

<style>
  h1 { margin: 0; font-size: 1.4rem; font-weight: 570; color: #f0efec; }
  h2 { margin: 0; font-size: 1.05rem; font-weight: 600; color: #f0efec; }
</style>
