import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  DEFAULT_INVESTIGATION_LIMITS,
  INVESTIGATION_LIMITS_STORAGE_KEY,
  investigationLimitErrors,
  loadInvestigationLimits,
  saveInvestigationLimits,
  validInvestigationLimits,
} from './investigationLimits';

describe('investigation limits', () => {
  afterEach(() => {
    vi.restoreAllMocks();
    window.localStorage.clear();
  });

  it('restores the defaults when nothing is stored', () => {
    expect(loadInvestigationLimits()).toEqual({ maxToolRounds: 50, maxToolCalls: 50, maxTurnSeconds: 600 });
    expect(DEFAULT_INVESTIGATION_LIMITS).toEqual({ maxToolRounds: 50, maxToolCalls: 50, maxTurnSeconds: 600 });
  });

  it('round-trips exactly the three numeric settings', () => {
    saveInvestigationLimits({ maxToolRounds: 3, maxToolCalls: 9, maxTurnSeconds: 45 });

    expect(JSON.parse(window.localStorage.getItem(INVESTIGATION_LIMITS_STORAGE_KEY) ?? 'null')).toEqual({
      maxToolRounds: 3,
      maxToolCalls: 9,
      maxTurnSeconds: 45,
    });
    expect(loadInvestigationLimits()).toEqual({ maxToolRounds: 3, maxToolCalls: 9, maxTurnSeconds: 45 });
  });

  it('restores the default for a malformed, fractional, wrongly typed or out-of-range value', () => {
    window.localStorage.setItem(INVESTIGATION_LIMITS_STORAGE_KEY, 'not json');
    expect(loadInvestigationLimits()).toEqual(DEFAULT_INVESTIGATION_LIMITS);

    window.localStorage.setItem(INVESTIGATION_LIMITS_STORAGE_KEY, '"10"');
    expect(loadInvestigationLimits()).toEqual(DEFAULT_INVESTIGATION_LIMITS);

    window.localStorage.setItem(INVESTIGATION_LIMITS_STORAGE_KEY, JSON.stringify({ maxToolRounds: 4.5 }));
    expect(loadInvestigationLimits()).toEqual(DEFAULT_INVESTIGATION_LIMITS);

    window.localStorage.setItem(INVESTIGATION_LIMITS_STORAGE_KEY, JSON.stringify({
      maxToolRounds: '4',
      maxToolCalls: true,
      maxTurnSeconds: 7200,
    }));
    expect(loadInvestigationLimits()).toEqual(DEFAULT_INVESTIGATION_LIMITS);
  });

  it('keeps the valid values while a stored field restores its default', () => {
    window.localStorage.setItem(INVESTIGATION_LIMITS_STORAGE_KEY, JSON.stringify({
      maxToolRounds: 4,
      maxToolCalls: 200,
      maxTurnSeconds: 300,
    }));

    expect(loadInvestigationLimits()).toEqual({ maxToolRounds: 4, maxToolCalls: 50, maxTurnSeconds: 300 });
  });

  it('survives storage that denies access', () => {
    vi.spyOn(window, 'localStorage', 'get').mockImplementation(() => {
      throw new Error('storage is disabled');
    });

    expect(loadInvestigationLimits()).toEqual(DEFAULT_INVESTIGATION_LIMITS);
    expect(() => saveInvestigationLimits({ maxToolRounds: 1, maxToolCalls: 1, maxTurnSeconds: 10 })).not.toThrow();
  });

  it('reports a field-associated error for every value outside the contract', () => {
    expect(investigationLimitErrors({ maxToolRounds: undefined, maxToolCalls: 1.5, maxTurnSeconds: 9999 })).toEqual({
      maxToolRounds: 'Max tool rounds must be a whole number between 1 and 50.',
      maxToolCalls: 'Max tool calls must be a whole number between 1 and 100.',
      maxTurnSeconds: 'Max time per question (seconds) must be a whole number between 10 and 1800.',
    });
    expect(investigationLimitErrors({ maxToolRounds: 1, maxToolCalls: 100, maxTurnSeconds: 10 })).toEqual({});
    expect(investigationLimitErrors({ maxToolRounds: 0, maxToolCalls: 101, maxTurnSeconds: 9 })).toEqual({
      maxToolRounds: 'Max tool rounds must be a whole number between 1 and 50.',
      maxToolCalls: 'Max tool calls must be a whole number between 1 and 100.',
      maxTurnSeconds: 'Max time per question (seconds) must be a whole number between 10 and 1800.',
    });
  });

  it('returns a sendable snapshot only when every value is in contract', () => {
    expect(validInvestigationLimits({ maxToolRounds: 1, maxToolCalls: 100, maxTurnSeconds: 1800 }))
      .toEqual({ maxToolRounds: 1, maxToolCalls: 100, maxTurnSeconds: 1800 });
    expect(validInvestigationLimits({ maxToolRounds: 51, maxToolCalls: 20, maxTurnSeconds: 600 })).toBeNull();
    expect(validInvestigationLimits({ maxToolRounds: undefined, maxToolCalls: 20, maxTurnSeconds: 600 })).toBeNull();
  });
});
