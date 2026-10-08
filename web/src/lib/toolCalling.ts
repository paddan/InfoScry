/**
 * The tool-calling state an LLM profile's measurement records. "not-measured" is the state Investigate refuses
 * (`TOOL_CALLING_UNSUPPORTED`), so the select and the sidebar note read the same record the server checks.
 */
export type ToolCallingState = 'supported' | 'unsupported' | 'not-measured';

export function toolCallingState(profile: {
  toolCallingMeasured?: boolean | null;
  capabilityCheckedAt?: string | null;
}): ToolCallingState {
  if (profile.toolCallingMeasured === true) return 'supported';
  if (profile.toolCallingMeasured === false) return 'unsupported';
  return 'not-measured';
}

/** A profile's name in the Investigate select; only profiles Investigate cannot use are marked. */
export function profileOptionLabel(name: string, state: ToolCallingState): string {
  if (state === 'unsupported') return `${name} (tool calling unsupported)`;
  if (state === 'not-measured') return `${name} (tool calling not measured)`;
  return name;
}

/** What a reader does about a profile Investigate cannot use yet; null when it can be used. */
export function toolCallingRemedy(state: ToolCallingState): string | null {
  if (state === 'supported') return null;
  if (state === 'unsupported') {
    return 'This profile does not support tool calling, so Investigate cannot use it. Choose another profile, or check its model in Admin → LLM profiles.';
  }
  return 'Tool calling has not been measured for this profile, so Investigate cannot use it yet. Open Admin → LLM profiles and choose Check tool calling.';
}
