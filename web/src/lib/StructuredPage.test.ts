import { cleanup, render, screen } from '@testing-library/svelte';
import { afterEach, describe, expect, it } from 'vitest';
import StructuredPage from './StructuredPage.svelte';
import type { StructureBlockView, StructureView } from './api';

const cell = (start: number, end: number, header = false) => ({ header, rowSpan: 1, colSpan: 1, start, end });

describe('StructuredPage', () => {
  afterEach(() => cleanup());

  it('rendersEveryBlockKind', () => {
    // Offsets are chosen so each block owns its own slice of the text.
    const parts = [
      'Title', 'Intro', 'First item', 'Second item', 'Name\tScore', 'Ada\t10', 'E = mc^2', 'let x = 1', 'Figure caption',
      'A note', 'Table 1', 'Page 1', 'Chapter',
    ];
    const text = parts.join('\n');
    let cursor = 0;
    const span = (value: string) => {
      const range = { start: cursor, end: cursor + value.length };
      cursor += value.length + 1;
      return range;
    };
    const blocks: StructureBlockView[] = [
      { kind: 'HEADING', level: 1, order: 0, ...span('Title') },
      { kind: 'PARAGRAPH', order: 1, ...span('Intro') },
      { kind: 'LIST_ITEM', order: 2, ...span('First item') },
      { kind: 'LIST_ITEM', order: 3, ...span('Second item') },
      {
        kind: 'TABLE', order: 4, ...span('Name\tScore\nAda\t10'),
        rows: [],
      },
      { kind: 'FORMULA', order: 6, ...span('E = mc^2'), latex: 'E = mc^2' },
      { kind: 'CODE', order: 7, ...span('let x = 1') },
      { kind: 'FIGURE', order: 8, ...span('Figure caption') },
      { kind: 'CAPTION', order: 9, ...span('A note') },
      { kind: 'FOOTNOTE', order: 10, ...span('Table 1') },
      { kind: 'PAGE_HEADER', order: 11, ...span('Page 1') },
      { kind: 'PAGE_FOOTER', order: 12, ...span('Chapter') },
    ];
    // The table's cells need their own ranges inside the table block.
    blocks[4].rows = [
      { cells: [cell(blocks[4].start, blocks[4].start + 4, true), cell(blocks[4].start + 5, blocks[4].start + 10, true)] },
      { cells: [cell(blocks[4].start + 11, blocks[4].start + 14), cell(blocks[4].start + 15, blocks[4].start + 17)] },
    ];
    const structure: StructureView = { blocks };
    const { container } = render(StructuredPage, { props: { text, structure, highlight: null } });

    expect(container.querySelector('h2')?.textContent).toBe('Title');
    expect(container.querySelector('ul')?.querySelectorAll('li')).toHaveLength(2);
    expect(container.querySelector('table')).not.toBeNull();
    expect(container.querySelector('pre code')?.textContent).toBe('let x = 1');
    expect(container.querySelector('figure figcaption')?.textContent).toBe('Figure caption');
    expect(container.querySelector('p.caption')?.textContent).toBe('A note');
    expect(container.querySelector('p.footnote')?.textContent).toBe('Table 1');
    expect(container.querySelector('p code')?.textContent).toBe('E = mc^2');
    expect(screen.getByRole('region', { name: 'Page header' }).textContent).toContain('Page 1');
    expect(screen.getByRole('region', { name: 'Page footer' }).textContent).toContain('Chapter');
    expect(container.querySelectorAll('p').length).toBeGreaterThan(0);
  });

  it('tableUsesThScope', () => {
    const text = 'Name\tScore\nAda\t10';
    const structure: StructureView = {
      blocks: [{
        kind: 'TABLE', order: 0, start: 0, end: text.length,
        rows: [
          { cells: [cell(0, 4, true), cell(5, 10, true)] },
          { cells: [cell(11, 14, true), cell(15, 17)] },
        ],
      }],
    };
    render(StructuredPage, { props: { text, structure, highlight: null } });

    const headers = screen.getAllByRole('columnheader');
    expect(headers.map((header) => header.textContent)).toEqual(['Name', 'Score']);
    expect(headers.every((header) => header.getAttribute('scope') === 'col')).toBe(true);
    const rowHeader = screen.getByRole('rowheader', { name: 'Ada' });
    expect(rowHeader.getAttribute('scope')).toBe('row');
    expect(screen.getByRole('cell', { name: '10' }).tagName).toBe('TD');
  });

  it('highlightInsideCell', () => {
    const text = 'Name\tScore\nAda\t10';
    const structure: StructureView = {
      blocks: [{
        kind: 'TABLE', order: 0, start: 0, end: text.length,
        rows: [
          { cells: [cell(0, 4, true), cell(5, 10, true)] },
          { cells: [cell(11, 14, true), cell(15, 17)] },
        ],
      }],
    };
    const { container } = render(StructuredPage, { props: { text, structure, highlight: { start: 15, end: 17 } } });

    const marked = container.querySelectorAll('mark');
    expect(marked).toHaveLength(1);
    expect(marked[0].textContent).toBe('10');
    expect(marked[0].closest('td')?.textContent).toBe('10');
  });

  it('pageFooterIsLabelled', () => {
    const text = 'Body\nPage 3 of 9';
    const structure: StructureView = {
      blocks: [
        { kind: 'PARAGRAPH', order: 0, start: 0, end: 4 },
        { kind: 'PAGE_FOOTER', order: 1, start: 5, end: text.length },
      ],
    };
    render(StructuredPage, { props: { text, structure, highlight: null } });

    const footer = screen.getByRole('region', { name: 'Page footer' });
    expect(footer.textContent).toBe('Page 3 of 9');
    expect(footer.classList.contains('page-furniture')).toBe(true);
  });

  it('withoutStructureUsesPlainRenderer', () => {
    const text = 'Plain text\nof a page';
    const { container } = render(StructuredPage, { props: { text, structure: null, highlight: null } });

    const pre = container.querySelector('pre.source-text');
    expect(pre?.textContent).toBe(text);
    expect(container.querySelector('table, h2, ul, mark')).toBeNull();
  });

  it('clampsBlocksToTheServedWindow', () => {
    const text = 'tail of a heading\nTable start';
    const structure: StructureView = {
      blocks: [{ kind: 'HEADING', level: 2, order: 0, start: -6, end: 4 }],
    };
    const { container } = render(StructuredPage, { props: { text, structure, highlight: null } });

    expect(container.querySelector('h3')?.textContent).toBe('tail');
  });
});
