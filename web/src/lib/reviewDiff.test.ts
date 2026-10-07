import { describe, expect, it } from 'vitest';
import { MAX_DIFF_CELLS, diffTexts } from './reviewDiff';

function joined(parts: { type: string; text: string }[], types: string[]): string {
  return parts.filter((part) => types.includes(part.type)).map((part) => part.text).join('');
}

describe('diffTexts', () => {
  it('reports identical texts as a single unchanged part', () => {
    const result = diffTexts('Total 123 due', 'Total 123 due');
    expect(result.kind).toBe('diff');
    if (result.kind !== 'diff') return;
    expect(result.changed).toBe(false);
    expect(result.parts).toEqual([{ type: 'same', text: 'Total 123 due' }]);
  });

  it('marks a changed word as removed and added, word by word', () => {
    const result = diffTexts('Total 123 due', 'Total 128 due');
    expect(result.kind).toBe('diff');
    if (result.kind !== 'diff') return;
    expect(result.changed).toBe(true);
    expect(result.parts).toEqual([
      { type: 'same', text: 'Total ' },
      { type: 'removed', text: '123' },
      { type: 'added', text: '128' },
      { type: 'same', text: ' due' },
    ]);
  });

  it('rebuilds both sides exactly from the parts', () => {
    const before = 'alpha beta\ngamma  delta epsilon';
    const after = 'alpha  beta gamma zeta delta';
    const result = diffTexts(before, after);
    expect(result.kind).toBe('diff');
    if (result.kind !== 'diff') return;
    expect(joined(result.parts, ['same', 'removed'])).toBe(before);
    expect(joined(result.parts, ['same', 'added'])).toBe(after);
  });

  it('handles an empty side', () => {
    const added = diffTexts('', 'new text');
    expect(added.kind === 'diff' && added.parts).toEqual([{ type: 'added', text: 'new text' }]);
    const removed = diffTexts('old text', '');
    expect(removed.kind === 'diff' && removed.parts).toEqual([{ type: 'removed', text: 'old text' }]);
    const none = diffTexts('', '');
    expect(none.kind === 'diff' && none.changed).toBe(false);
  });

  it('keeps markup-like text as plain strings', () => {
    const result = diffTexts('<b>x</b> a', '<script>y</script> a');
    expect(result.kind).toBe('diff');
    if (result.kind !== 'diff') return;
    expect(joined(result.parts, ['same', 'added'])).toBe('<script>y</script> a');
  });

  it('falls back instead of computing a huge difference', () => {
    const left = Array.from({ length: 5000 }, (_, index) => `a${index}`).join(' ');
    const right = Array.from({ length: 5000 }, (_, index) => `b${index}`).join(' ');
    const started = Date.now();
    const result = diffTexts(left, right);
    expect(result).toEqual({ kind: 'too-large' });
    expect(Date.now() - started).toBeLessThan(1000);
    expect(MAX_DIFF_CELLS).toBeGreaterThan(0);
  });

  it('still diffs a very long text when only a small part differs', () => {
    const base = Array.from({ length: 50_000 }, (_, index) => `w${index}`).join(' ');
    const result = diffTexts(`${base} one`, `${base} two`);
    expect(result.kind).toBe('diff');
    if (result.kind !== 'diff') return;
    expect(joined(result.parts, ['removed'])).toBe('one');
    expect(joined(result.parts, ['added'])).toBe('two');
  });
});
