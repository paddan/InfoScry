<script lang="ts">
  /** Conversation history disclosure in the workspace sidebar. */

  export let label: string;
  /** `promptName` is shown beside the title only when it is not the shipped Default. */
  export let entries: { id: string; title: string; promptName?: string }[] = [];
  export let openId: string | null = null;
  export let loading = false;
  /** A live Investigate turn owns the selection; the rows, New and delete wait until done. */
  export let disabled = false;
  export let onSelect: (id: string) => void;
  export let onNew: () => void;
  export let onDelete: (id: string) => void;
</script>

<nav class="history-sidebar" aria-label={label}>
  <button type="button" class="new-conversation" onclick={onNew} disabled={disabled}>New conversation</button>
  <details>
    <summary>Conversation history</summary>
    {#if loading}
    <p class="history-status" role="status">Loading conversations…</p>
    {:else if entries.length === 0}
    <p class="history-status">No conversations yet.</p>
    {:else}
    <ol class="history-list">
      {#each entries as entry (entry.id)}
        <li>
          <div class="history-row-line">
            <button
              type="button"
              class="history-row"
              aria-current={entry.id === openId}
              disabled={disabled}
              onclick={() => onSelect(entry.id)}
            >{entry.title}{#if entry.promptName !== undefined && entry.promptName !== 'Default'}<span class="history-prompt">{entry.promptName}</span>{/if}</button>
            <button
              type="button"
              class="history-delete"
              aria-label={`Delete conversation ${entry.title}`}
              title={`Delete conversation ${entry.title}`}
              disabled={disabled}
              onclick={() => onDelete(entry.id)}
            ><span aria-hidden="true">×</span></button>
          </div>
        </li>
      {/each}
    </ol>
    {/if}
  </details>
</nav>

<style>
  .history-sidebar { display: grid; gap: 0.7rem; min-width: 0; }
  .history-sidebar details { margin: 0; border-top: 1px solid #2d3131; padding: 0.65rem 0.25rem 0; }
  .history-sidebar summary {
    color: #c4c8c6;
    font-size: 0.82rem;
    cursor: pointer;
  }
  .history-sidebar > button {
    border: 1px solid #3a3e3e;
    border-radius: 0.46rem;
    background: #252929;
    color: #e4e6e3;
    padding: 0.4rem 0.7rem;
    font-size: 0.8rem;
    cursor: pointer;
  }
  .history-sidebar > button:hover { border-color: #656b68; background: #2d3232; }
  .history-status { margin: 0; padding: 0 0.3rem; color: #929997; font-size: 0.85rem; }
  .history-list {
    display: grid;
    gap: 0.2rem;
    list-style: none;
    margin: 0;
    padding: 0;
    border-top: 1px solid #2b3030;
  }
  .history-list li { border-bottom: 1px solid #2b3030; }
  .history-row-line {
    display: flex;
    align-items: stretch;
    gap: 0.15rem;
  }
  .history-row {
    flex: 1 1 auto;
    min-width: 0;
    width: auto;
    border: 0;
    border-radius: 0;
    background: transparent;
    color: inherit;
    text-align: left;
    padding: 0.65rem 0.5rem;
    font-size: 0.88rem;
    cursor: pointer;
  }
  .history-row:hover { background: #1c2020; }
  .history-prompt { display: block; color: #929997; font-size: 0.78rem; margin-top: 0.15rem; }
  .history-row[aria-current="true"] { background: #242827; box-shadow: inset 2px 0 #c4a77d; color: #f3e6d1; }
  /* Always visible, sibling to the title button: keyboard- and touch-reachable without a hover state. */
  .history-delete {
    flex: 0 0 auto;
    align-self: center;
    border: 0;
    border-radius: 0.35rem;
    background: transparent;
    color: #929997;
    padding: 0.4rem 0.6rem;
    font-size: 1.05rem;
    line-height: 1;
    cursor: pointer;
  }
  .history-delete:hover, .history-delete:focus-visible { color: #f0a4a0; background: #2a1f1f; }
  /* A live Investigate turn locks history actions through native disabled controls. */
  .history-row:disabled, .history-delete:disabled, .history-sidebar > button:disabled { opacity: 0.5; cursor: not-allowed; }
</style>
