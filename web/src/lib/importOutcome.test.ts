import { describe, expect, it } from 'vitest';
import { importItemOutcomeLabel } from './importOutcome';

/**
 * One file's own result. A failure whose bytes were kept because a tool is missing is not `Failed`:
 * the document is in the collection waiting for that tool, and the label has to say so.
 */
describe('importItemOutcomeLabel', () => {
  it('reads the outcomes a file can end as', () => {
    expect(importItemOutcomeLabel({ outcome: 'PENDING', errorCode: null })).toBe('Pending');
    expect(importItemOutcomeLabel({ outcome: 'IMPORTED', errorCode: null })).toBe('Imported');
    expect(importItemOutcomeLabel({ outcome: 'DUPLICATE', errorCode: null })).toBe('Duplicate');
    expect(importItemOutcomeLabel({ outcome: 'CANCELLED', errorCode: null })).toBe('Cancelled');
    expect(importItemOutcomeLabel({ outcome: 'FAILED', errorCode: 'UNSUPPORTED_MEDIA_TYPE' })).toBe('Failed');
    expect(importItemOutcomeLabel({ outcome: 'FAILED', errorCode: null })).toBe('Failed');
  });

  it('says a tool is missing when the bytes were kept for one', () => {
    for (const code of ['NEEDS_TOOL', 'NEEDS_TESSERACT']) {
      expect(importItemOutcomeLabel({ outcome: 'FAILED', errorCode: code })).toBe('Needs a tool');
    }
  });
});
