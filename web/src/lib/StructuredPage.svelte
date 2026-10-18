<script lang="ts">
  import type { StructureBlockView, StructureView } from './api';

  /** The served window's text: every range below is an index into it, already clamped to it. */
  export let text: string;
  /** Null renders the window as plain preformatted text, as the reader did before structure existed. */
  export let structure: StructureView | null = null;
  /** A span of the same window to mark, such as a search hit; null marks nothing. */
  export let highlight: { start: number; end: number } | null = null;

  type Piece = { text: string; marked: boolean };
  type Group = { list: true; items: StructureBlockView[] } | { list: false; block: StructureBlockView };

  /** Splits `[start, end)` of the window at the highlight, so a mark never crosses a block or cell edge. */
  function pieces(start: number, end: number, mark: { start: number; end: number } | null): Piece[] {
    const from = Math.min(Math.max(start, 0), text.length);
    const to = Math.min(Math.max(end, from), text.length);
    if (mark === null) return to > from ? [{ text: text.slice(from, to), marked: false }] : [];
    const markFrom = Math.max(from, mark.start);
    const markTo = Math.min(to, mark.end);
    if (markTo <= markFrom) return to > from ? [{ text: text.slice(from, to), marked: false }] : [];
    const result: Piece[] = [];
    if (markFrom > from) result.push({ text: text.slice(from, markFrom), marked: false });
    result.push({ text: text.slice(markFrom, markTo), marked: true });
    if (to > markTo) result.push({ text: text.slice(markTo, to), marked: false });
    return result;
  }

  /** Consecutive list items share one list; every other block stands alone. */
  function groupBlocks(blocks: StructureBlockView[]): Group[] {
    const groups: Group[] = [];
    for (const block of blocks) {
      const last = groups[groups.length - 1];
      if (block.kind !== 'LIST_ITEM') {
        groups.push({ list: false, block });
      } else if (last?.list) {
        last.items.push(block);
      } else {
        groups.push({ list: true, items: [block] });
      }
    }
    return groups;
  }

  /** A page title keeps h1, so a document heading of level 1 starts at h2 and nothing goes below h6. */
  function headingTag(level: number | undefined): string {
    return `h${Math.min((level ?? 1) + 1, 6)}`;
  }

  $: groups = structure === null ? [] : groupBlocks([...structure.blocks].sort((a, b) => a.order - b.order));
</script>

{#snippet spans(start: number, end: number, mark: { start: number; end: number } | null)}
  {#each pieces(start, end, mark) as piece}{#if piece.marked}<mark>{piece.text}</mark>{:else}{piece.text}{/if}{/each}
{/snippet}

{#snippet table(block: StructureBlockView)}
  <table>
    <tbody>
      {#each block.rows ?? [] as row, rowIndex}
        <tr>
          {#each row.cells as cell}
            {#if cell.header}
              <th scope={rowIndex === 0 ? 'col' : 'row'} rowspan={cell.rowSpan > 1 ? cell.rowSpan : undefined} colspan={cell.colSpan > 1 ? cell.colSpan : undefined}>{@render spans(cell.start, cell.end, highlight)}</th>
            {:else}
              <td rowspan={cell.rowSpan > 1 ? cell.rowSpan : undefined} colspan={cell.colSpan > 1 ? cell.colSpan : undefined}>{@render spans(cell.start, cell.end, highlight)}</td>
            {/if}
          {/each}
        </tr>
      {/each}
    </tbody>
  </table>
{/snippet}

{#snippet block(item: StructureBlockView)}
  {#if item.kind === 'HEADING'}
    <svelte:element this={headingTag(item.level)}>{@render spans(item.start, item.end, highlight)}</svelte:element>
  {:else if item.kind === 'TABLE'}
    {@render table(item)}
  {:else if item.kind === 'CODE'}
    <pre><code>{@render spans(item.start, item.end, highlight)}</code></pre>
  {:else if item.kind === 'FORMULA'}
    <p><code>{@render spans(item.start, item.end, highlight)}</code></p>
  {:else if item.kind === 'FIGURE'}
    <figure><figcaption>{@render spans(item.start, item.end, highlight)}</figcaption></figure>
  {:else if item.kind === 'CAPTION'}
    <p class="caption">{@render spans(item.start, item.end, highlight)}</p>
  {:else if item.kind === 'FOOTNOTE'}
    <p class="footnote">{@render spans(item.start, item.end, highlight)}</p>
  {:else if item.kind === 'PAGE_HEADER'}
    <section class="page-furniture" aria-label="Page header"><p>{@render spans(item.start, item.end, highlight)}</p></section>
  {:else if item.kind === 'PAGE_FOOTER'}
    <section class="page-furniture" aria-label="Page footer"><p>{@render spans(item.start, item.end, highlight)}</p></section>
  {:else}
    <p>{@render spans(item.start, item.end, highlight)}</p>
  {/if}
{/snippet}

{#if structure === null}
  <pre class="source-text">{text}</pre>
{:else}
  <div class="structured-page">
    {#each groups as group}
      {#if group.list}
        <ul>
          {#each group.items as item}
            <li>{@render spans(item.start, item.end, highlight)}</li>
          {/each}
        </ul>
      {:else}
        {@render block(group.block)}
      {/if}
    {/each}
  </div>
{/if}

<style>
  .structured-page { color: #e4e4df; font: 0.9rem/1.6 system-ui, -apple-system, Segoe UI, sans-serif; overflow-wrap: anywhere; }
  .structured-page :global(h2), .structured-page :global(h3), .structured-page :global(h4),
  .structured-page :global(h5), .structured-page :global(h6) { color: #f0efec; margin: 1rem 0 0.4rem; line-height: 1.3; }
  .structured-page p, .structured-page ul { margin: 0 0 0.75rem; }
  .structured-page .caption, .structured-page .footnote { color: #a6adaa; font-size: 0.82rem; }
  .structured-page figure { margin: 0 0 0.75rem; }
  .structured-page figcaption { color: #a6adaa; font-size: 0.82rem; }
  .structured-page table { border-collapse: collapse; margin: 0 0 0.75rem; max-width: 100%; }
  /* `anywhere` would let a column shrink to one character, splitting short values like "12:38"; break-word only breaks a word that cannot fit. */
  .structured-page th, .structured-page td { border: 1px solid #343939; padding: 0.3rem 0.5rem; text-align: left; vertical-align: top; overflow-wrap: break-word; }
  .structured-page th { color: #f0efec; font-weight: 600; }
  .structured-page pre { overflow: auto; white-space: pre-wrap; border: 1px solid #343939; border-radius: 0.45rem; background: #121515; padding: 0.7rem; font: 0.84rem/1.5 ui-monospace, SFMono-Regular, Menlo, monospace; }
  .structured-page code { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 0.86em; }
  .structured-page mark { background: #4a3d1e; color: #f7ecd0; border-radius: 0.2rem; padding: 0 0.1rem; }
  .page-furniture { color: #929997; font-size: 0.8rem; border-top: 1px solid #303535; padding-top: 0.4rem; margin: 0 0 0.75rem; }
  .page-furniture p { margin: 0; }
  /* The plain fallback keeps the reader's own look: the same preformatted block the page showed before. */
  pre.source-text { max-height: calc(100vh - 14rem); overflow: auto; white-space: pre-wrap; overflow-wrap: anywhere; border: 1px solid #343939; border-radius: 0.45rem; background: #121515; padding: 0.9rem; font: 0.86rem/1.6 ui-monospace, SFMono-Regular, Menlo, monospace; }
</style>
