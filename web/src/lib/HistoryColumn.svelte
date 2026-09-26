<script lang="ts">
  /** One row of the history column: the conversation's stored title with a fallback label. */

  export let label: string;
  export let entries: { id: string; title: string }[] = [];
  export let openId: string | null = null;
  export let loading = false;
  /** A live Investigate turn owns the selection; the rows, New and delete wait until done. */
  export let disabled = false;
  export let onSelect: (id: string) => void;
  export let onNew: () => void;
  export let onDelete: (id: string) => void;
</script>

<nav class="history-column" aria-label={label}>
  <div class="history-heading">
    <h2>{label}</h2>
    <button type="button" class="new-conversation" onclick={onNew} disabled={disabled}>New conversation</button>
  </div>
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
            >{entry.title}</button>
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
</nav>

<style>
  .history-column {
    position: fixed;
    top: 0;
    right: 0;
    bottom: 0;
    width: var(--history-column-width, 20rem);
    display: flex;
    flex-direction: column;
    gap: 0.9rem;
    padding: 1.35rem 1rem 1rem;
    overflow-y: auto;
    border-left: 1px solid #2a2d2e;
    background: #181a1b;
  }
  .history-heading {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 0.6rem;
    padding: 0 0.3rem;
  }
  .history-heading h2 {
    margin: 0;
    color: #f0efec;
    font-size: 0.95rem;
    font-weight: 600;
    letter-spacing: -0.01em;
  }
  .history-heading button {
    border: 1px solid #3a3e3e;
    border-radius: 0.46rem;
    background: #252929;
    color: #e4e6e3;
    padding: 0.4rem 0.7rem;
    font-size: 0.8rem;
    cursor: pointer;
  }
  .history-heading button:hover { border-color: #656b68; background: #2d3232; }
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
  /* A live Investigate turn locks the column; the native disabled attribute stays visible here. */
  .history-row:disabled, .history-delete:disabled, .history-heading button:disabled { opacity: 0.5; cursor: not-allowed; }
  @media (max-width: 54rem) {
    .history-column {
      position: static;
      width: 100%;
      border-left: 0;
      border-top: 1px solid #2a2d2e;
      flex-direction: column;
    }
  }
</style>