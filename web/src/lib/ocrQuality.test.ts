import { describe, expect, it } from 'vitest';
import { qualityLabel } from './ocrQuality';

describe('qualityLabel', () => {
  it('returns null when the score is unknown', () => {
    expect(qualityLabel(undefined)).toBeNull();
  });

  it('classifies scores at and above 75 as good', () => {
    expect(qualityLabel(100)).toEqual({ text: 'Text score 100/100', level: 'good' });
    expect(qualityLabel(82)).toEqual({ text: 'Text score 82/100', level: 'good' });
    expect(qualityLabel(75)).toEqual({ text: 'Text score 75/100', level: 'good' });
  });

  it('classifies scores from 50 up to 74 as fair', () => {
    expect(qualityLabel(74)).toEqual({ text: 'Text score 74/100', level: 'fair' });
    expect(qualityLabel(50)).toEqual({ text: 'Text score 50/100', level: 'fair' });
  });

  it('classifies scores below 50 as poor', () => {
    expect(qualityLabel(49)).toEqual({ text: 'Text score 49/100', level: 'poor' });
    expect(qualityLabel(0)).toEqual({ text: 'Text score 0/100', level: 'poor' });
  });
});
