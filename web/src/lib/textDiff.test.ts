import { describe, expect, it } from 'vitest';
import { diffText } from './textDiff';

describe('diffText', () => {
  it('marks removed and added words and keeps the rest', () => {
    expect(diffText('the qiuck brown fox', 'the quick brown fox')).toEqual([
      { kind: 'same', text: 'the ' },
      { kind: 'removed', text: 'qiuck' },
      { kind: 'added', text: 'quick' },
      { kind: 'same', text: ' brown fox' },
    ]);
  });

  it('joins neighbouring changes into one replacement', () => {
    expect(diffText('Tbe old reading', 'The new reading')).toEqual([
      { kind: 'removed', text: 'Tbe old' },
      { kind: 'added', text: 'The new' },
      { kind: 'same', text: ' reading' },
    ]);
  });

  it('reports identical text as one unchanged part', () => {
    expect(diffText('same text', 'same text')).toEqual([{ kind: 'same', text: 'same text' }]);
  });

  it('handles empty sides', () => {
    expect(diffText('', 'new words')).toEqual([{ kind: 'added', text: 'new words' }]);
    expect(diffText('old words', '')).toEqual([{ kind: 'removed', text: 'old words' }]);
  });

  it('falls back to whole lines when a word comparison would be too large', () => {
    const before = Array.from({ length: 3000 }, (_, i) => `w${i}`).join(' ') + '\nend';
    const after = Array.from({ length: 3000 }, (_, i) => `v${i}`).join(' ') + '\nend';
    const parts = diffText(before, after)!;
    expect(parts.map((part) => part.text).join('')).toContain('end');
    expect(parts.at(-1)).toEqual({ kind: 'same', text: 'end' });
  });
});
