/**
 * The per-question Investigate limits the reader sets in the sidebar: what this browser stores and
 * what the streaming calls send. The bounds and defaults mirror the server's `InvestigationLimits`,
 * which validates the same values again at the HTTP and service boundaries.
 */

/** The three bounded values one Investigate turn accepts. */
export type InvestigationLimits = {
  maxToolRounds: number;
  maxToolCalls: number;
  maxTurnSeconds: number;
};

/** The same values while the reader is editing them; a cleared number input binds as `undefined`. */
export type InvestigationLimitsInput = { [K in keyof InvestigationLimits]: number | undefined };

export type InvestigationLimitKey = keyof InvestigationLimits;

type InvestigationLimitField = {
  key: InvestigationLimitKey;
  label: string;
  min: number;
  max: number;
};

/** The sidebar controls in display order, each with the label and range it presents. */
export const INVESTIGATION_LIMIT_FIELDS: readonly InvestigationLimitField[] = [
  { key: 'maxToolRounds', label: 'Max tool rounds', min: 1, max: 50 },
  { key: 'maxToolCalls', label: 'Max tool calls', min: 1, max: 100 },
  { key: 'maxTurnSeconds', label: 'Max time per question (seconds)', min: 10, max: 1800 },
];

/** What a turn runs with when the reader changes nothing: the server's own defaults. */
export const DEFAULT_INVESTIGATION_LIMITS: InvestigationLimits = {
  maxToolRounds: 50,
  maxToolCalls: 50,
  maxTurnSeconds: 600,
};

export const INVESTIGATION_LIMITS_STORAGE_KEY = 'infoscry-investigate-limits:v1';

/**
 * The settings this browser holds. A stored value that is missing, not a whole number inside its
 * range, or not readable at all falls back to its default, so no unusable value can reach a turn.
 */
export function loadInvestigationLimits(): InvestigationLimits {
  const limits = { ...DEFAULT_INVESTIGATION_LIMITS };
  const stored = readStoredValues();
  for (const field of INVESTIGATION_LIMIT_FIELDS) {
    const value = stored[field.key];
    if (typeof value === 'number' && Number.isInteger(value) && value >= field.min && value <= field.max) {
      limits[field.key] = value;
    }
  }
  return limits;
}

/** Store exactly the three numeric settings; storage that refuses the write leaves the reader be. */
export function saveInvestigationLimits(limits: InvestigationLimits): void {
  try {
    browserStorage()?.setItem(INVESTIGATION_LIMITS_STORAGE_KEY, JSON.stringify(limits));
  } catch {
    // Private or sandboxed storage rejects writes and reads alike; the settings stay in memory.
  }
}

/** One field-associated message per value the server would reject; an empty result is submittable. */
export function investigationLimitErrors(
  values: InvestigationLimitsInput,
): Partial<Record<InvestigationLimitKey, string>> {
  const errors: Partial<Record<InvestigationLimitKey, string>> = {};
  for (const field of INVESTIGATION_LIMIT_FIELDS) {
    if (!isInRange(field, values[field.key])) {
      errors[field.key] = `${field.label} must be a whole number between ${field.min} and ${field.max}.`;
    }
  }
  return errors;
}

/** The snapshot to send, or null when any value is out of contract so no turn starts. */
export function validInvestigationLimits(values: InvestigationLimitsInput): InvestigationLimits | null {
  if (Object.keys(investigationLimitErrors(values)).length > 0) return null;
  // Every field passed the checkpoint above, so each value is a whole number in range.
  return values as InvestigationLimits;
}

function readStoredValues(): Partial<Record<InvestigationLimitKey, unknown>> {
  try {
    const raw = browserStorage()?.getItem(INVESTIGATION_LIMITS_STORAGE_KEY);
    if (raw === null || raw === undefined) return {};
    const parsed: unknown = JSON.parse(raw);
    if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) return {};
    return parsed as Record<InvestigationLimitKey, unknown>;
  } catch {
    return {}; // Unreadable storage is the same as nothing stored.
  }
}

function isInRange(field: InvestigationLimitField, value: number | undefined): boolean {
  return typeof value === 'number' && Number.isInteger(value) && value >= field.min && value <= field.max;
}

/**
 * The browser's localStorage, or null when the environment denies it: a restricted frame throws on
 * the property itself, not only on read, so even reaching for it is guarded.
 */
function browserStorage(): Storage | null {
  try {
    return typeof localStorage === 'undefined' ? null : localStorage;
  } catch {
    return null;
  }
}
