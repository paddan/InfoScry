/**
 * A small, bounded word-level difference for the review screen.
 *
 * Pure and text-only: it returns plain strings, never markup, so a caller renders every part as a text node.
 * The work is capped (see {@link MAX_DIFF_CELLS}); a comparison that would exceed it answers `too-large` and the
 * caller shows the two texts side by side instead.
 */

export type DiffPart = { type: 'same' | 'removed' | 'added'; text: string };

export type DiffResult =
  | { kind: 'diff'; parts: DiffPart[]; changed: boolean }
  | { kind: 'too-large' };

/** The most table cells the comparison may fill, after the unchanged start and end are set aside. */
export const MAX_DIFF_CELLS = 1_500_000;

function tokens(text: string): string[] {
  return text.match(/\s+|\S+/g) ?? [];
}

function push(parts: DiffPart[], type: DiffPart['type'], text: string): void {
  if (text === '') return;
  const last = parts[parts.length - 1];
  if (last !== undefined && last.type === type) last.text += text;
  else parts.push({ type, text });
}

/** Differences between `before` and `after`, word by word (whitespace runs count as words). */
export function diffTexts(before: string, after: string): DiffResult {
  if (before === after) {
    return { kind: 'diff', parts: before === '' ? [] : [{ type: 'same', text: before }], changed: false };
  }
  const a = tokens(before);
  const b = tokens(after);

  let start = 0;
  while (start < a.length && start < b.length && a[start] === b[start]) start += 1;
  let endA = a.length;
  let endB = b.length;
  while (endA > start && endB > start && a[endA - 1] === b[endB - 1]) {
    endA -= 1;
    endB -= 1;
  }
  const n = endA - start;
  const m = endB - start;
  if ((n + 1) * (m + 1) > MAX_DIFF_CELLS) return { kind: 'too-large' };

  // table[i][j] is the length of the longest common run of a[start+i..] and b[start+j..].
  const width = m + 1;
  const table = new Uint32Array((n + 1) * width);
  for (let i = n - 1; i >= 0; i -= 1) {
    for (let j = m - 1; j >= 0; j -= 1) {
      table[i * width + j] = a[start + i] === b[start + j]
        ? table[(i + 1) * width + j + 1] + 1
        : Math.max(table[(i + 1) * width + j], table[i * width + j + 1]);
    }
  }

  const parts: DiffPart[] = [];
  push(parts, 'same', a.slice(0, start).join(''));
  let removed = '';
  let added = '';
  const flush = (): void => {
    push(parts, 'removed', removed);
    push(parts, 'added', added);
    removed = '';
    added = '';
  };
  let i = 0;
  let j = 0;
  while (i < n || j < m) {
    if (i < n && j < m && a[start + i] === b[start + j]) {
      flush();
      push(parts, 'same', a[start + i]);
      i += 1;
      j += 1;
    } else if (j >= m || (i < n && table[(i + 1) * width + j] >= table[i * width + j + 1])) {
      removed += a[start + i];
      i += 1;
    } else {
      added += b[start + j];
      j += 1;
    }
  }
  flush();
  push(parts, 'same', a.slice(endA).join(''));
  return { kind: 'diff', parts, changed: true };
}
