export type DiffPart = { kind: 'same' | 'added' | 'removed'; text: string };

/** Comparison tables above this many cells are too slow to build in the browser. */
const MAX_CELLS = 2_500_000;

/**
 * Compares two page texts word by word, falling back to whole lines for long pages. Null means the texts are
 * too long to compare even by line.
 */
export function diffText(before: string, after: string): DiffPart[] | null {
  const words = (text: string) => text.match(/\s+|\S+/g) ?? [];
  const lines = (text: string) => text.match(/[^\n]*\n|[^\n]+/g) ?? [];
  for (const split of [words, lines]) {
    const a = split(before);
    const b = split(after);
    if ((a.length + 1) * (b.length + 1) <= MAX_CELLS) return joinChanges(diffTokens(a, b));
  }
  return null;
}

function diffTokens(a: string[], b: string[]): DiffPart[] {
  const width = b.length + 1;
  // common[i * width + j] is the longest common run of a[i..] and b[j..].
  const common = new Uint32Array((a.length + 1) * width);
  for (let i = a.length - 1; i >= 0; i--) {
    for (let j = b.length - 1; j >= 0; j--) {
      common[i * width + j] = a[i] === b[j]
        ? common[(i + 1) * width + j + 1] + 1
        : Math.max(common[(i + 1) * width + j], common[i * width + j + 1]);
    }
  }
  const parts: DiffPart[] = [];
  const push = (kind: DiffPart['kind'], text: string) => {
    const last = parts.at(-1);
    if (last?.kind === kind) last.text += text;
    else parts.push({ kind, text });
  };
  let i = 0;
  let j = 0;
  while (i < a.length || j < b.length) {
    if (i < a.length && j < b.length && a[i] === b[j]) {
      push('same', a[i]);
      i++;
      j++;
    } else if (j >= b.length || (i < a.length && common[(i + 1) * width + j] >= common[i * width + j + 1])) {
      push('removed', a[i++]);
    } else {
      push('added', b[j++]);
    }
  }
  return parts;
}

/**
 * Reads changes as phrases: neighbouring changes separated only by whitespace become one removed and one added
 * part, so "Tbe old" → "The new" is shown as one replacement rather than two.
 */
function joinChanges(parts: DiffPart[]): DiffPart[] {
  const joined: DiffPart[] = [];
  let removed = '';
  let added = '';
  const flush = () => {
    if (removed) joined.push({ kind: 'removed', text: removed });
    if (added) joined.push({ kind: 'added', text: added });
    removed = '';
    added = '';
  };
  parts.forEach((part, index) => {
    const between = part.kind === 'same' && /^\s+$/.test(part.text) && (removed || added) &&
      index + 1 < parts.length && parts[index + 1].kind !== 'same';
    if (part.kind === 'removed' || between) removed += part.text;
    if (part.kind === 'added' || between) added += part.text;
    if (part.kind === 'same' && !between) {
      flush();
      joined.push(part);
    }
  });
  flush();
  return joined;
}
