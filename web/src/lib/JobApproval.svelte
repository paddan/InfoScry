<script module lang="ts">
  /** How many pages beyond what was already sent a default approval names. */
  export const DEFAULT_APPROVAL_STEP = 50;
</script>

<script lang="ts">
  import { approveJobExternal, type ExternalApproval, type ImportExternalApprovalResponse } from './api';

  /** The import job waiting for the approval. */
  export let jobId: string;
  /** What the server reports the wait needs: the snapshot to bind to, what was sent, and what may be sent. */
  export let approval: ExternalApproval;
  /** Called with the server's answer once the approval is recorded; the job then resumes on the server. */
  export let onapproved: (answer: ImportExternalApprovalResponse) => void;

  /** The default names a batch of pages beyond what was already sent, so the import is unlikely to pause again. */
  let pages = String(approval.distinctPagesSent + DEFAULT_APPROVAL_STEP);
  let approving = false;
  let error: string | null = null;

  /**
   * The fewest pages an approval may name. A count no larger than what was already sent authorises nothing
   * new, so the minimum is one more than that, and never below one.
   */
  $: minimum = Math.max(1, approval.distinctPagesSent + 1);
  $: typedCount = parsePages(pages);

  function parsePages(text: string): number | null {
    const trimmed = text.trim();
    return /^\d+$/.test(trimmed) ? Number(trimmed) : null;
  }

  function describe(failure: unknown): string {
    return failure instanceof Error ? failure.message : 'Something went wrong.';
  }

  async function approve(): Promise<void> {
    if (approving) return;
    const count = parsePages(pages);
    if (count === null || count < minimum) {
      error = `Enter a whole number of distinct pages, at least ${minimum}.`;
      return;
    }
    approving = true;
    error = null;
    let answer: ImportExternalApprovalResponse;
    try {
      answer = await approveJobExternal(jobId, approval.snapshotHash, count);
    } catch (failure) {
      // The typed number stays where it is: a refusal is something to read, not something to retype.
      error = describe(failure);
      return;
    } finally {
      approving = false;
    }
    onapproved(answer);
  }
</script>

<section class="job-approval" aria-label="Approve external pages">
  <h4>Approve external pages</h4>
  {#if approval.distinctPagesSent === 0 && approval.allowance === 0}
    <p>This import sends pages to an external provider, and none are allowed yet. Approve how many pages may leave this machine; the import pauses again if it needs more.</p>
  {:else}
    <p>{approval.distinctPagesSent} external {approval.distinctPagesSent === 1 ? 'page' : 'pages'} sent so far, {approval.allowance} allowed. Approve how many pages in total may leave this machine; the import pauses again if it needs more.</p>
  {/if}
  <div class="field">
    <label for="job-approval-pages-{jobId}">Pages that may leave this machine (in total)</label>
    <input
      id="job-approval-pages-{jobId}"
      type="number"
      min={minimum}
      step="1"
      value={pages}
      oninput={(event) => (pages = event.currentTarget.value)}
    />
  </div>
  {#if error !== null}<p role="alert">{error}</p>{/if}
  <div class="actions">
    <button type="button" class="primary" onclick={() => void approve()} disabled={approving}>
      Approve external pages
    </button>
  </div>
</section>

<style>
  .job-approval { display: grid; gap: 0.6rem; padding: 0.85rem; border: 1px solid #6e5a33; border-radius: 0.55rem; background: #1d1c1a; }
  .job-approval h4 { margin: 0; color: #f3e6d1; font-size: 0.95rem; }
  .job-approval p { margin: 0; color: #d8c9a8; font-size: 0.85rem; }
  .field { display: grid; gap: 0.35rem; max-width: 16rem; }
  .field input {
    min-width: 0;
    border: 1px solid #383d3e;
    border-radius: 0.48rem;
    background: #202324;
    padding: 0.62rem 0.72rem;
    color: #e8e9e7;
  }
  .actions { display: flex; gap: 0.55rem; }
  button.primary { border-color: #c4a77d; background: #c4a77d; color: #1c1b18; font-weight: 650; }
  button.primary:hover:not(:disabled) { background: #d4ba94; }
  label { color: #b8bcbb; font-size: 0.82rem; }
</style>
