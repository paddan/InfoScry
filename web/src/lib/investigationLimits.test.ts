import { afterEach, expect, it, vi } from 'vitest';
import { DEFAULT_EFFORT_LEVEL, EFFORT_LEVELS_STORAGE_KEY, loadEffortLevel, saveEffortLevel } from './investigationLimits';
afterEach(() => { vi.unstubAllGlobals(); localStorage.clear(); });
it('defaults to MEDIUM and ignores the old numeric preference', () => {
  localStorage.setItem('infoscry-investigate-limits:v1', JSON.stringify({ maxToolRounds: 1 }));
  expect(DEFAULT_EFFORT_LEVEL).toBe('MEDIUM');
  expect(loadEffortLevel()).toBe('MEDIUM');
});
it('round trips every effort level', () => {
  for (const level of ['LOW', 'MEDIUM', 'HIGH', 'XHIGH'] as const) {
    saveEffortLevel(level);
    expect(loadEffortLevel()).toBe(level);
  }
});
it('falls back for corrupted or unknown stored values', () => {
  for (const raw of ['invalid', '"UNKNOWN"', 'null', '{}', '15']) {
    localStorage.setItem(EFFORT_LEVELS_STORAGE_KEY, raw);
    expect(loadEffortLevel()).toBe('MEDIUM');
  }
});
it('tolerates unavailable storage', () => {
  vi.stubGlobal('localStorage', { getItem() { throw Error('denied'); }, setItem() { throw Error('denied'); } });
  expect(loadEffortLevel()).toBe('MEDIUM');
  expect(() => saveEffortLevel('HIGH')).not.toThrow();
});
