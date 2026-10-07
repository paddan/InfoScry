import { describe, expect, it } from 'vitest';
import { profileOptionLabel, toolCallingRemedy, toolCallingState } from './toolCalling';

describe('tool calling state of a profile', () => {
  it('reads a measured profile as supported or unsupported and an unmeasured one as not measured', () => {
    expect(toolCallingState({ toolCallingMeasured: true, capabilityCheckedAt: '2026-10-07T10:00:00Z' })).toBe('supported');
    expect(toolCallingState({ toolCallingMeasured: false, capabilityCheckedAt: '2026-10-07T10:00:00Z' })).toBe('unsupported');
    expect(toolCallingState({ toolCallingMeasured: null, capabilityCheckedAt: null })).toBe('not-measured');
    expect(toolCallingState({})).toBe('not-measured');
  });

  it('marks only profiles that cannot be used for Investigate in the select', () => {
    expect(profileOptionLabel('fast', 'supported')).toBe('fast');
    expect(profileOptionLabel('fast', 'unsupported')).toBe('fast (tool calling unsupported)');
    expect(profileOptionLabel('fast', 'not-measured')).toBe('fast (tool calling not measured)');
  });

  it('tells the reader what to do for a profile that is not measured or unsupported', () => {
    expect(toolCallingRemedy('supported')).toBeNull();
    expect(toolCallingRemedy('not-measured')).toContain('Admin');
    expect(toolCallingRemedy('not-measured')).toContain('Check tool calling');
    expect(toolCallingRemedy('unsupported')).toContain('does not support tool calling');
  });
});
