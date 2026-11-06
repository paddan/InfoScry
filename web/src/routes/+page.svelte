<script lang="ts">
  import { onDestroy, onMount } from 'svelte';
  import Workspace from '../lib/Workspace.svelte';
  import SignInForm from '../lib/SignInForm.svelte';
  import PasswordChangeForm from '../lib/PasswordChangeForm.svelte';
  import { onSignedOut, readSession, signOut, type User } from '../lib/api';

  /** null while the session is read; then the signed-in user, or 'signed-out'. */
  let user: User | null | 'signed-out' = null;
  let error: string | null = null;

  onMount(async () => {
    onSignedOut(() => (user = 'signed-out'));
    try {
      user = (await readSession()) ?? 'signed-out';
    } catch (failure) {
      error = failure instanceof Error ? failure.message : 'InfoScry could not be reached.';
    }
  });

  onDestroy(() => onSignedOut(null));

  async function leave(): Promise<void> {
    try {
      await signOut();
    } finally {
      user = 'signed-out';
    }
  }
</script>

{#if error !== null}
  <main class="gate"><p role="alert">{error}</p></main>
{:else if user === null}
  <main class="gate"><p role="status">Loading…</p></main>
{:else if user === 'signed-out'}
  <main class="gate"><SignInForm onSignedIn={(signedIn) => (user = signedIn)} /></main>
{:else if user.mustChangePassword}
  <main class="gate">
    <PasswordChangeForm
      required
      onChanged={async () => (user = (await readSession()) ?? 'signed-out')}
      onCancel={leave}
    />
  </main>
{:else}
  <Workspace user={user} onSignedOut={() => (user = 'signed-out')} />
{/if}

<style>
  :global(body) { margin: 0; background: #101214; color: #e8e9e7; font: 15px/1.55 Inter, ui-sans-serif, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif; }
  .gate { min-height: 100vh; display: grid; place-items: center; padding: 1rem; box-sizing: border-box; }
</style>
