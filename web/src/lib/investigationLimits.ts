import type { EffortLevel } from './api';
export const DEFAULT_EFFORT_LEVEL: EffortLevel = 'MEDIUM';
export const EFFORT_LEVELS_STORAGE_KEY = 'infoscry-investigate-effort:v1';
export function isEffortLevel(value: unknown): value is EffortLevel {
  return ['LOW', 'MEDIUM', 'HIGH', 'XHIGH'].includes(value as string);
}
export function loadEffortLevel(): EffortLevel {
  try {
    const value: unknown = JSON.parse(browserStorage()?.getItem(EFFORT_LEVELS_STORAGE_KEY) ?? 'null');
    return isEffortLevel(value) ? value : DEFAULT_EFFORT_LEVEL;
  } catch { return DEFAULT_EFFORT_LEVEL; }
}
export function saveEffortLevel(level: EffortLevel): void {
  try { browserStorage()?.setItem(EFFORT_LEVELS_STORAGE_KEY, JSON.stringify(level)); } catch { /* Keep the in-memory preference. */ }
}

function browserStorage(): Storage | null {
  try {
    return typeof localStorage === 'undefined' ? null : localStorage;
  } catch {
    return null;
  }
}
